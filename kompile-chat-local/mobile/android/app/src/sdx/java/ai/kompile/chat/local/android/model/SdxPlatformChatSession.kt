package ai.kompile.chat.local.android.model

import android.app.Application
import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Provider-owned SDX session. No native object from this contract may cross Binder. */
internal interface SdxOwnedPlatformChatSession {
    val routeName: String
    val modelId: String

    fun generate(
        messages: List<Message>,
        opts: GenOptions,
        onChunk: Consumer<String>?,
        operation: NativeOperationTransaction
    ): String

    fun cancel(operation: NativeOperationTransaction)

    fun close(operation: NativeOperationTransaction)
}

/**
 * Owns one shared sdx_llm ABI runtime inside the app-private runtime process.
 *
 * Android supplies process isolation, journaling, and notifications. Canonical SDZ resolution,
 * target policy, tokenizer ownership, chat-template rendering, and token generation all remain in
 * the same Java/Graal implementation used by the desktop and iOS bindings.
 */
internal object SdxPlatformRuntimeOwner {

    private const val STATUS_OK = 0
    private const val RESOLVED_MODEL_SCHEMA = "sdx-resolved-text-model-v1"

    fun open(
        context: Context,
        modelPath: String,
        diagnosticModelPath: String = modelPath,
        routeName: String,
        modelIdPrefix: String,
        loadTransaction: NativeOperationTransaction
    ): SdxOwnedPlatformChatSession {
        val applicationContext = context.applicationContext
        check(Application.getProcessName() == sdxRuntimeProcessName(applicationContext.packageName)) {
            "Direct SDX runtime initialization is allowed only in the app-private runtime process."
        }

        var native: SdxAndroidLlmAbi? = null
        var runtime: Pointer? = null
        var model: Pointer? = null
        try {
            val source = File(modelPath).canonicalFile
            require(source.isFile && source.canRead()) {
                "Canonical SDZ model is not readable: ${source.absolutePath}"
            }
            require(source.extension.equals("sdz", ignoreCase = true)) {
                "Local SDX models use the canonical sharded .sdz format"
            }

            loadTransaction.checkpoint(NativeOperationCheckpoint.PREPARE_DEVICE_CACHE)
            val modelCache = File(applicationContext.noBackupFilesDir, "sdx-model-cache")
            require(modelCache.isDirectory || modelCache.mkdirs()) {
                "Unable to create the app-owned SDX model cache: ${modelCache.absolutePath}"
            }
            val deviceCache = File(applicationContext.codeCacheDir, "sdx-device-compilation")
            require(deviceCache.isDirectory || deviceCache.mkdirs()) {
                "Unable to create the device compilation cache: ${deviceCache.absolutePath}"
            }

            val library = SdxAndroidLlmLibrary.configure(applicationContext)
            loadTransaction.checkpoint(NativeOperationCheckpoint.LOAD_NATIVE_TRANSPORT)
            val abi = SdxAndroidLlmLibrary.bind(library)
            native = abi

            loadTransaction.checkpoint(NativeOperationCheckpoint.CREATE_NATIVE_RUNTIME)
            val runtimeHandle = abi.sdxLlmCreateRuntime()
                ?: throw ChatException("SDX failed to create its shared compiled-model runtime")
            runtime = runtimeHandle

            loadTransaction.checkpoint(NativeOperationCheckpoint.QUERY_RUNTIME_ABI)
            val runtimeAbi = abi.sdxLlmAbiVersion(runtimeHandle)
            check(runtimeAbi == SdxAndroidLlmAbi.ABI_VERSION) {
                "libsdx_llm ABI mismatch: app=${SdxAndroidLlmAbi.ABI_VERSION} library=$runtimeAbi"
            }

            loadTransaction.checkpoint(NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS)
            val resolvedRef = SdxPointerByReference()
            val resolveStatus = abi.sdxLlmResolveModelBundle(
                runtimeHandle,
                source.absolutePath,
                BuildConfig.SDX_TARGET_PROFILE,
                modelCache.absolutePath,
                resolvedRef
            )
            requireStatus(
                abi,
                runtimeHandle,
                resolveStatus,
                "SDX could not resolve ${source.name} for ${BuildConfig.SDX_TARGET_PROFILE}"
            )
            val resolved = JSONObject(
                readAndFree(abi, runtimeHandle, resolvedRef.value, "resolved model")
            )
            check(resolved.optString("schema") == RESOLVED_MODEL_SCHEMA) {
                "Unsupported resolved SDX model schema: ${resolved.optString("schema")}"
            }
            check(resolved.getString("targetProfile") == BuildConfig.SDX_TARGET_PROFILE) {
                "Resolved SDX target ${resolved.getString("targetProfile")} does not match " +
                    BuildConfig.SDX_TARGET_PROFILE
            }
            val bundle = requireCachePath(
                modelCache,
                File(resolved.getString("modelPath")),
                "runtime model",
                allowDirectory = true
            )
            val tokenizer = requireCachePath(
                modelCache,
                File(resolved.getString("tokenizerPath")),
                "tokenizer",
                allowDirectory = false
            )

            loadTransaction.checkpoint(NativeOperationCheckpoint.LOAD_MODEL_BUNDLE)
            val modelHandle = abi.sdxLlmLoadCompiledModel(
                runtimeHandle,
                bundle.absolutePath,
                tokenizer.absolutePath,
                BuildConfig.SDX_TARGET_PROFILE,
                JSONObject()
                    .put("deviceCompilationCacheDirectory", deviceCache.absolutePath)
                    .toString()
            ) ?: throw ChatException(
                "SDX could not load the canonical compiled bundle: " +
                    lastError(abi, runtimeHandle)
            )
            model = modelHandle

            val session = Session(
                native = abi,
                runtime = runtimeHandle,
                model = modelHandle,
                routeName = routeName,
                modelId = "$modelIdPrefix:${source.name}"
            )
            loadTransaction.complete()
            return session
        } catch (failure: Throwable) {
            val failedCheckpoint = loadTransaction.snapshot().checkpoint
            val abi = native
            val runtimeHandle = runtime
            val modelHandle = model
            if (abi != null && runtimeHandle != null && modelHandle != null) {
                closeAndSuppress(
                    loadTransaction,
                    NativeOperationCheckpoint.CLOSE_MODEL,
                    failure
                ) {
                    requireStatus(
                        abi,
                        runtimeHandle,
                        abi.sdxLlmUnloadModel(runtimeHandle, modelHandle),
                        "SDX compiled-model cleanup failed"
                    )
                }
            }
            if (abi != null && runtimeHandle != null) {
                closeAndSuppress(
                    loadTransaction,
                    NativeOperationCheckpoint.CLOSE_RUNTIME,
                    failure
                ) {
                    val status = abi.sdxLlmDestroyRuntime(runtimeHandle)
                    check(status == STATUS_OK) {
                        "SDX runtime cleanup failed (status=$status)"
                    }
                }
            }
            try {
                loadTransaction.checkpoint(failedCheckpoint)
            } catch (journalFailure: Throwable) {
                failure.addSuppressed(journalFailure)
            }
            throw failure
        }
    }

    private inline fun closeAndSuppress(
        transaction: NativeOperationTransaction,
        checkpoint: NativeOperationCheckpoint,
        primary: Throwable,
        close: () -> Unit
    ) {
        try {
            transaction.checkpoint(checkpoint)
            close()
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
        }
    }

    private fun requireCachePath(
        cacheRoot: File,
        candidate: File,
        description: String,
        allowDirectory: Boolean
    ): File {
        val root = cacheRoot.canonicalFile
        val value = candidate.canonicalFile
        val rootPath = root.absolutePath
        val valuePath = value.absolutePath
        require(valuePath == rootPath || valuePath.startsWith(rootPath + File.separator)) {
            "Resolved $description escaped the app-owned SDX cache: $valuePath"
        }
        require(if (allowDirectory) value.isDirectory else value.isFile && value.canRead()) {
            "Resolved $description is not readable: $valuePath"
        }
        return value
    }

    private fun readAndFree(
        native: SdxAndroidLlmAbi,
        runtime: Pointer,
        pointer: Pointer?,
        description: String
    ): String {
        val value = pointer
            ?: throw ChatException("SDX returned a null $description pointer")
        return try {
            BytePointer(value).string ?: ""
        } finally {
            native.sdxLlmFree(runtime, value)
        }
    }

    private fun lastError(native: SdxAndroidLlmAbi, runtime: Pointer): String =
        readCompleteSdxLastError { buffer ->
            native.sdxLlmGetLastError(runtime, buffer, buffer.size)
        }

    private fun requireStatus(
        native: SdxAndroidLlmAbi,
        runtime: Pointer,
        status: Int,
        action: String
    ) {
        if (status != STATUS_OK) {
            throw ChatException("$action (status=$status): ${lastError(native, runtime)}")
        }
    }

    private class Session(
        private val native: SdxAndroidLlmAbi,
        private val runtime: Pointer,
        private val model: Pointer,
        override val routeName: String,
        override val modelId: String
    ) : SdxOwnedPlatformChatSession {
        private val cancelRequested = AtomicBoolean(false)

        override fun generate(
            messages: List<Message>,
            opts: GenOptions,
            onChunk: Consumer<String>?,
            operation: NativeOperationTransaction
        ): String {
            cancelRequested.set(false)

            operation.checkpoint(NativeOperationCheckpoint.RENDER_CHAT_TEMPLATE)
            val promptRef = SdxPointerByReference()
            val renderStatus = native.sdxLlmRenderChatPrompt(
                runtime,
                model,
                encodeSdxRuntimeMessages(messages),
                1,
                promptRef
            )
            requireStatus(
                native,
                runtime,
                renderStatus,
                "SDX could not render the model chat template"
            )
            val prompt = readAndFree(native, runtime, promptRef.value, "rendered prompt")

            operation.checkpoint(NativeOperationCheckpoint.GENERATE_TOKENS)
            val callbackFailure = AtomicReference<Throwable?>()
            val chunkCallback = onChunk?.let { consumer ->
                SdxAndroidLlmAbi.ChunkCallback { chunk ->
                    try {
                        val text = chunk?.let { BytePointer(it).string }.orEmpty()
                        if (text.isNotEmpty()) {
                            consumer.accept(text)
                        }
                    } catch (failure: Throwable) {
                        callbackFailure.compareAndSet(null, failure)
                        cancelRequested.set(true)
                    }
                }
            }
            val cancelCallback = SdxAndroidLlmAbi.CancelCallback {
                if (cancelRequested.get() || callbackFailure.get() != null) 1 else 0
            }
            val outputRef = SdxPointerByReference()
            val generationStatus = native.sdxLlmGenerateStreaming(
                runtime,
                model,
                prompt,
                JSONObject(opts.toOptionsJson())
                    .put("promptMode", "rendered_chat")
                    .toString(),
                chunkCallback,
                cancelCallback,
                outputRef
            )
            callbackFailure.get()?.let { throw it }
            requireStatus(
                native,
                runtime,
                generationStatus,
                "SDX compiled-model generation failed"
            )
            val decoded = readAndFree(native, runtime, outputRef.value, "generated text").trim()
            operation.complete()
            return decoded
        }

        override fun cancel(operation: NativeOperationTransaction) {
            operation.checkpoint(NativeOperationCheckpoint.CANCEL_GENERATION)
            // The Graal isolate handle is thread-bound. Cancellation is observed by the callback
            // on the owning generation thread; no C ABI call crosses threads.
            cancelRequested.set(true)
            operation.complete()
        }

        override fun close(operation: NativeOperationTransaction) {
            var firstFailure: Throwable? = null
            var firstFailureCheckpoint: NativeOperationCheckpoint? = null
            val closeSteps = listOf<Pair<NativeOperationCheckpoint, () -> Unit>>(
                NativeOperationCheckpoint.CLOSE_MODEL to {
                    requireStatus(
                        native,
                        runtime,
                        native.sdxLlmUnloadModel(runtime, model),
                        "SDX compiled-model unload failed"
                    )
                },
                NativeOperationCheckpoint.CLOSE_RUNTIME to {
                    val status = native.sdxLlmDestroyRuntime(runtime)
                    check(status == STATUS_OK) {
                        "SDX runtime destroy failed (status=$status)"
                    }
                }
            )
            closeSteps.forEach { (checkpoint, closeStep) ->
                try {
                    operation.checkpoint(checkpoint)
                    closeStep()
                } catch (failure: Throwable) {
                    if (firstFailure == null) {
                        firstFailure = failure
                        firstFailureCheckpoint = checkpoint
                    } else {
                        firstFailure?.addSuppressed(failure)
                    }
                }
            }
            val failure = firstFailure
            if (failure == null) {
                operation.complete()
                return
            }
            firstFailureCheckpoint?.let { operation.checkpoint(it) }
            throw failure
        }
    }
}
