package ai.kompile.chat.local.android.model

import android.app.Application
import android.content.Context
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.diagnostics.DspDiagnosticsTraceLog
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import ai.kompile.chat.local.android.diagnostics.SmokeDecodeTraceLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Provider-owned SDX session. No native object from this contract may cross Binder. */
internal interface SdxOwnedPlatformChatSession {
    val routeName: String
    val modelId: String

    fun generate(
        requestJson: String,
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
    private const val TENSOR_G3_TARGET_PROFILE = "android-arm64-nnapi-accelerator"
    private const val TENSOR_G3_MAX_PROMPT_TOKENS = 256

    fun open(
        context: Context,
        modelPath: String,
        diagnosticModelPath: String = modelPath,
        diagnosticMode: ModelDiagnosticMode = ModelDiagnosticMode.STANDARD,
        routeName: String,
        modelIdPrefix: String,
        loadTransaction: NativeOperationTransaction
    ): SdxOwnedPlatformChatSession {
        val applicationContext = context.applicationContext
        val trace = SmokeDecodeTraceLog(applicationContext)
        trace.record(
            "runtime_diagnostics_configured",
            loadTransaction.snapshot().attemptId,
            mapOf(
                "diagnostic_mode" to diagnosticMode.wireValue,
                "dsp_categories" to diagnosticMode.dspCategories,
                "dsp_level" to diagnosticMode.dspLevel,
                "dsp_trace_location" to if (diagnosticMode == ModelDiagnosticMode.DSP_DIAGNOSTICS) {
                    DspDiagnosticsTraceLog(applicationContext).locationDescription()
                } else {
                    "disabled"
                },
            )
        )
        check(Application.getProcessName() == sdxRuntimeProcessName(applicationContext.packageName)) {
            "Direct SDX runtime initialization is allowed only in the app-private runtime process."
        }

        var native: SdxAndroidLlmAbi? = null
        var runtime: SdxNativeHandle? = null
        var model: SdxNativeHandle? = null
        try {
            val source = File(modelPath).canonicalFile
            require(source.isFile && source.canRead()) {
                "Canonical SDZ model is not readable: ${source.absolutePath}"
            }
            require(source.extension.equals("sdz", ignoreCase = true)) {
                "Local SDX models use the canonical sharded .sdz format"
            }

            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.PREPARE_DEVICE_CACHE)
            val modelCache = File(applicationContext.noBackupFilesDir, "sdx-model-cache")
            require(modelCache.isDirectory || modelCache.mkdirs()) {
                "Unable to create the app-owned SDX model cache: ${modelCache.absolutePath}"
            }
            val deviceCache = File(applicationContext.codeCacheDir, "sdx-device-compilation")
            require(deviceCache.isDirectory || deviceCache.mkdirs()) {
                "Unable to create the device compilation cache: ${deviceCache.absolutePath}"
            }

            val library = SdxAndroidLlmLibrary.configure(applicationContext, diagnosticMode)
            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.LOAD_NATIVE_TRANSPORT)
            val abi = SdxAndroidLlmLibrary.bind(library)
            native = abi

            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.CREATE_NATIVE_RUNTIME)
            val runtimeHandle = abi.sdxLlmCreateRuntime()
                ?: throw ChatException("SDX failed to create its shared compiled-model runtime")
            runtime = runtimeHandle

            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.QUERY_RUNTIME_ABI)
            val runtimeAbi = abi.sdxLlmAbiVersion(runtimeHandle)
            check(runtimeAbi == SdxAndroidLlmAbi.ABI_VERSION) {
                "libsdx_llm ABI mismatch: app=${SdxAndroidLlmAbi.ABI_VERSION} library=$runtimeAbi"
            }

            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS)
            val resolvedRef = SdxPointerByReference()
            val resolveHeartbeat = trace.startHeartbeat(
                loadTransaction.snapshot().attemptId,
                "sdxLlmResolveModelBundle"
            )
            val resolveStatus = try {
                abi.sdxLlmResolveModelBundle(
                    runtimeHandle,
                    source.absolutePath,
                    BuildConfig.SDX_TARGET_PROFILE,
                    modelCache.absolutePath,
                    resolvedRef
                )
            } finally {
                resolveHeartbeat.close()
            }
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

            checkpointLoad(trace, loadTransaction, NativeOperationCheckpoint.LOAD_MODEL_BUNDLE)
            val loadHeartbeat = trace.startHeartbeat(
                loadTransaction.snapshot().attemptId,
                "sdxLlmLoadCompiledModel"
            )
            val modelHandle = try {
                abi.sdxLlmLoadCompiledModel(
                    runtimeHandle,
                    bundle.absolutePath,
                    tokenizer.absolutePath,
                    BuildConfig.SDX_TARGET_PROFILE,
                    JSONObject()
                        .put("deviceCompilationCacheDirectory", deviceCache.absolutePath)
                        .toString()
                )
            } finally {
                loadHeartbeat.close()
            } ?: throw ChatException(
                "SDX could not load the canonical compiled bundle: " +
                    lastError(abi, runtimeHandle)
            )
            model = modelHandle

            val session = Session(
                native = abi,
                runtime = runtimeHandle,
                model = modelHandle,
                routeName = routeName,
                modelId = "$modelIdPrefix:${source.name}",
                trace = SmokeDecodeTraceLog(applicationContext)
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

    private fun checkpointLoad(
        trace: SmokeDecodeTraceLog,
        transaction: NativeOperationTransaction,
        checkpoint: NativeOperationCheckpoint
    ) {
        transaction.checkpoint(checkpoint)
        trace.record(
            "runtime_load_checkpoint",
            transaction.snapshot().attemptId,
            mapOf(
                "checkpoint" to checkpoint.name,
                "pid" to android.os.Process.myPid()
            )
        )
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
        runtime: SdxNativeHandle,
        pointer: SdxNativeHandle?,
        description: String
    ): String {
        val value = pointer
            ?: throw ChatException("SDX returned a null $description pointer")
        return try {
            native.sdxLlmReadUtf8(value)
        } finally {
            native.sdxLlmFree(runtime, value)
        }
    }

    private fun lastError(native: SdxAndroidLlmAbi, runtime: SdxNativeHandle): String =
        readCompleteSdxLastError { buffer ->
            native.sdxLlmGetLastError(runtime, buffer, buffer.size)
        }

    private fun requireStatus(
        native: SdxAndroidLlmAbi,
        runtime: SdxNativeHandle,
        status: Int,
        action: String
    ) {
        if (status != STATUS_OK) {
            throw ChatException("$action (status=$status): ${lastError(native, runtime)}")
        }
    }

    private class Session(
        private val native: SdxAndroidLlmAbi,
        private val runtime: SdxNativeHandle,
        private val model: SdxNativeHandle,
        override val routeName: String,
        override val modelId: String,
        private val trace: SmokeDecodeTraceLog
    ) : SdxOwnedPlatformChatSession {
        private val cancelRequested = AtomicBoolean(false)

        override fun generate(
            requestJson: String,
            opts: GenOptions,
            onChunk: Consumer<String>?,
            operation: NativeOperationTransaction
        ): String {
            cancelRequested.set(false)
            val attemptId = operation.snapshot().attemptId
            val canonicalRequestJson = JSONObject(requestJson).toString()

            operation.checkpoint(NativeOperationCheckpoint.RENDER_CHAT_TEMPLATE)
            trace.record(
                "render_prompt_enter",
                attemptId,
                mapOf("route" to routeName, "process" to Application.getProcessName())
            )
            val promptRef = SdxPointerByReference()
            val renderStatus = native.sdxLlmRenderChatPrompt(
                runtime,
                model,
                canonicalRequestJson,
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
            val tokenCount = native.sdxLlmTokenCount(runtime, model, prompt)
            requireStatus(
                native,
                runtime,
                tokenCount.status,
                "SDX could not count rendered prompt tokens"
            )
            val maxPromptTokens = if (BuildConfig.SDX_TARGET_PROFILE == TENSOR_G3_TARGET_PROFILE) {
                TENSOR_G3_MAX_PROMPT_TOKENS
            } else {
                Int.MAX_VALUE
            }
            trace.record(
                "render_prompt_return",
                attemptId,
                mapOf(
                    "prompt_chars" to prompt.length,
                    "prompt_tokens" to tokenCount.count,
                    "max_prompt_tokens" to maxPromptTokens
                )
            )
            if (tokenCount.count > maxPromptTokens) {
                throw ChatException(
                    "Rendered chat prompt has ${tokenCount.count} tokens; the " +
                        "${BuildConfig.SDX_TARGET_PROFILE} safety limit is $maxPromptTokens. " +
                        "The request was stopped before NNAPI execution to prevent a low-memory termination. " +
                        "Start a new conversation or ask a shorter graph question."
                )
            }

            val callbackFailure = AtomicReference<Throwable?>()
            val chunkCount = AtomicInteger(0)
            val chunkChars = AtomicLong(0L)
            val chunkCallback = if (onChunk == null) null else
                SdxAndroidLlmAbi.ChunkCallback { chunk ->
                    try {
                        val text = chunk.orEmpty()
                        if (text.isNotEmpty()) {
                            val count = chunkCount.incrementAndGet()
                            val chars = chunkChars.addAndGet(text.length.toLong())
                            if (count == 1 || count % 16 == 0) {
                                trace.record(
                                    "native_chunk",
                                    attemptId,
                                    mapOf("chunk_count" to count, "chunk_chars" to chars)
                                )
                            }
                        }
                    } catch (failure: Throwable) {
                        callbackFailure.compareAndSet(null, failure)
                        cancelRequested.set(true)
                    }
                }
            val cancelCallback = SdxAndroidLlmAbi.CancelCallback {
                if (cancelRequested.get() || callbackFailure.get() != null) 1 else 0
            }
            val outputRef = SdxPointerByReference()
            trace.record(
                "native_generate_enter",
                attemptId,
                mapOf("max_tokens" to opts.maxTokens(), "pid" to android.os.Process.myPid())
            )
            val heartbeat = trace.startHeartbeat(attemptId, "sdxLlmGenerateStreaming")
            val generationStatus: Int
            try {
                generationStatus = native.sdxLlmGenerateStreaming(
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
            } catch (failure: Throwable) {
                trace.recordFailure("native_generate_failed", attemptId, failure)
                throw failure
            } finally {
                heartbeat.close()
            }
            trace.record(
                "native_generate_return",
                attemptId,
                mapOf(
                    "status" to generationStatus,
                    "chunk_count" to chunkCount.get(),
                    "chunk_chars" to chunkChars.get()
                )
            )
            callbackFailure.get()?.let {
                trace.recordFailure("native_callback_failed", attemptId, it)
                throw it
            }
            requireStatus(
                native,
                runtime,
                generationStatus,
                "SDX compiled-model generation failed"
            )
            val generationReportRef = SdxPointerByReference()
            val generationReportStatus = native.sdxLlmLastResultJson(
                runtime,
                model,
                generationReportRef
            )
            if (generationReportStatus == STATUS_OK && generationReportRef.value != null) {
                val report = JSONObject(
                    readAndFree(
                        native,
                        runtime,
                        generationReportRef.value,
                        "native generation report"
                    )
                )
                trace.record(
                    "native_generation_report",
                    attemptId,
                    mapOf(
                        "prompt_tokens" to report.optInt("promptTokens", -1),
                        "generated_tokens" to report.optInt("generatedTokens", -1),
                        "prefill_ns" to report.optLong("prefillTimeNanos", -1L),
                        "decode_ns" to report.optLong("decodeTimeNanos", -1L),
                        "decode_tokens_per_second" to
                            report.optDouble("decodeTokensPerSecond", -1.0),
                        "plan_phase" to report.optInt("planPhase", -1),
                        "execution_count" to report.optInt("executionCount", -1),
                        "used_fallback" to report.optInt("usedFallback", -1)
                    )
                )
            } else {
                generationReportRef.value?.let { native.sdxLlmFree(runtime, it) }
                trace.record(
                    "native_generation_report_unavailable",
                    attemptId,
                    mapOf("status" to generationReportStatus)
                )
            }
            val rawDecoded = readAndFree(
                native, runtime, outputRef.value, "generated text"
            )
            val parsedRef = SdxPointerByReference()
            val parseStatus = native.sdxLlmParseChatResult(
                runtime,
                model,
                canonicalRequestJson,
                rawDecoded,
                parsedRef
            )
            requireStatus(
                native,
                runtime,
                parseStatus,
                "SDX could not decode the model-owned chat result"
            )
            val structured = JSONObject(
                readAndFree(native, runtime, parsedRef.value, "structured chat result")
            )
            val decoded = structured.optString("content").trim()
            if (decoded.isNotEmpty()) onChunk?.accept(decoded)
            trace.record(
                "native_output_ready",
                attemptId,
                mapOf(
                    "output_chars" to decoded.length,
                    "tool_calls" to structured.getJSONArray("toolCalls").length(),
                    "protocol_errors" to structured.getJSONArray("protocolErrors").length(),
                    "chunk_count" to chunkCount.get(),
                    "chunk_chars" to chunkChars.get()
                )
            )
            operation.complete()
            return structured.toString()
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
