package ai.kompile.chat.local.android.model

import android.content.Context
import android.system.Os
import android.util.Log
import ai.kompile.chat.local.ChatException
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Direct GGUF/GGML chat session backed by DL4J's libsdx_llm Graal native image.
 *
 * The C ABI binds a runtime/model pair to the OS thread that creates it, so every
 * create/load/render/generate/free/destroy operation is serialized on one dedicated
 * thread. A downloaded file is not considered usable merely because it exists: opening
 * this session loads it through SDX, and activation additionally performs a real decode.
 */
internal class SdxRawGgufChatSession private constructor(
    private val modelFile: File,
    private val executor: ExecutorService,
    private val runtimeThread: AtomicReference<Thread>
) : PlatformLocalChatSession {

    /** Minimal Android binding for the canonical sdx_llm_c.h ABI. */
    internal interface SdxLlmBinding : Library {
        fun sdxLlmCreateRuntime(): Pointer?
        fun sdxLlmDestroyRuntime(runtime: Pointer): Int
        fun sdxLlmAbiVersion(runtime: Pointer): Int
        fun sdxLlmLoadModel(
            runtime: Pointer,
            modelPath: String,
            tokenizerPath: String?,
            optionsJson: String?
        ): Pointer?
        fun sdxLlmUnloadModel(runtime: Pointer, model: Pointer): Int
        fun sdxLlmRenderChatPrompt(
            runtime: Pointer,
            model: Pointer,
            messagesJson: String,
            addGenerationPrompt: Int,
            outPrompt: PointerByReference
        ): Int
        fun sdxLlmGenerate(
            runtime: Pointer,
            model: Pointer,
            prompt: String,
            optionsJson: String?,
            outText: PointerByReference
        ): Int
        fun sdxLlmFree(runtime: Pointer, pointer: Pointer)
        fun sdxLlmGetLastError(runtime: Pointer, buffer: ByteArray, capacity: Int): Int
    }

    private var binding: SdxLlmBinding? = null
    private var runtime: Pointer? = null
    private var model: Pointer? = null
    private val closed = AtomicBoolean(false)

    override val routeName: String = "SDX_GGUF_AOT"
    override val modelId: String = "sdx-gguf:${modelFile.name}"

    override fun generate(
        messages: List<Message>,
        opts: GenOptions,
        onChunk: Consumer<String>?
    ): String = onRuntimeThread("generate") {
        val native = requireNotNull(binding) { "SDX binding is not initialized" }
        val rt = requireNotNull(runtime) { "SDX runtime is not initialized" }
        val loadedModel = requireNotNull(model) { "SDX model is not initialized" }

        val promptRef = PointerByReference()
        val renderStatus = native.sdxLlmRenderChatPrompt(
            rt,
            loadedModel,
            messagesJson(messages),
            1,
            promptRef
        )
        if (renderStatus != STATUS_OK) {
            throw ChatException(
                "SDX chat-template rendering failed (status=$renderStatus): ${lastError(native, rt)}"
            )
        }
        val prompt = readAndFree(native, rt, promptRef.value, "chat prompt")

        val outputRef = PointerByReference()
        val generateStatus = native.sdxLlmGenerate(
            rt,
            loadedModel,
            prompt,
            opts.toOptionsJson(),
            outputRef
        )
        if (generateStatus != STATUS_OK) {
            throw ChatException(
                "SDX generation failed (status=$generateStatus): ${lastError(native, rt)}"
            )
        }
        readAndFree(native, rt, outputRef.value, "generated text").trim().also { text ->
            if (text.isNotEmpty()) onChunk?.accept(text)
        }
    }

    override fun cancel() {
        // ABI v2 generation is blocking and has no cancellation entry point. The dedicated
        // runtime thread still prevents concurrent lifecycle calls or cross-thread isolate use.
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var cleanupFailure: ChatException? = null
        try {
            if (Thread.currentThread() === runtimeThread.get()) {
                releaseNativeState()
            } else {
                val closeTask = executor.submit { releaseNativeState() }
                try {
                    closeTask.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (timeout: TimeoutException) {
                    closeTask.cancel(true)
                    cleanupFailure = ChatException(
                        "Timed out waiting for the SDX runtime thread to release ${modelFile.name}",
                        timeout
                    )
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupFailure = ChatException("Interrupted while closing the SDX model session", interrupted)
        } catch (failed: ExecutionException) {
            cleanupFailure = ChatException(
                "SDX model cleanup failed",
                failed.cause ?: failed
            )
        } catch (failure: ChatException) {
            cleanupFailure = failure
        } finally {
            executor.shutdownNow()
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && cleanupFailure == null) {
                cleanupFailure = ChatException(
                    "SDX runtime thread did not terminate for ${modelFile.name}"
                )
            }
        }
        cleanupFailure?.let {
            Log.e(TAG, it.message, it)
            throw it
        }
    }

    private fun initialize(context: Context, temperature: Float, maxTokens: Int) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val library = File(nativeDir, LIB_FILE_NAME)
        if (!library.isFile) {
            throw ChatException(
                "$LIB_FILE_NAME is missing from this APK (${nativeDir.absolutePath}); " +
                    "build it with the DL4J sdx-aot android-aot profile"
            )
        }

        // Graal's isolate snapshots process environment during creation. Set the side-library
        // directory before Native.load and before sdxLlmCreateRuntime.
        Os.setenv("SDX_NATIVE_LIB_DIR", nativeDir.absolutePath, true)
        System.setProperty("jna.library.path", nativeDir.absolutePath)

        val native = Native.load(library.absolutePath, SdxLlmBinding::class.java)
        binding = native
        val rt = native.sdxLlmCreateRuntime()
            ?: throw ChatException("SDX failed to create its Android runtime")
        runtime = rt

        val actualAbi = native.sdxLlmAbiVersion(rt)
        if (actualAbi != EXPECTED_ABI_VERSION) {
            throw ChatException(
                "libsdx_llm ABI mismatch: app=$EXPECTED_ABI_VERSION library=$actualAbi"
            )
        }

        val loadOptions = JSONObject()
            .put("maxNewTokens", maxTokens.coerceAtLeast(1))
            .put("sampling", JSONObject().put("temperature", temperature.toDouble()))
            .toString()
        model = native.sdxLlmLoadModel(rt, modelFile.absolutePath, null, loadOptions)
            ?: throw ChatException(
                "SDX could not load ${modelFile.name}: ${lastError(native, rt)}"
            )
    }

    private fun releaseNativeState() {
        val native = binding
        val rt = runtime
        val loadedModel = model
        model = null
        runtime = null
        binding = null
        if (native == null || rt == null) return

        var failure: ChatException? = null
        if (loadedModel != null) {
            val unloadStatus = native.sdxLlmUnloadModel(rt, loadedModel)
            if (unloadStatus != STATUS_OK) {
                failure = ChatException(
                    "SDX model unload failed (status=$unloadStatus): ${lastError(native, rt)}"
                )
            }
        }
        val destroyStatus = native.sdxLlmDestroyRuntime(rt)
        if (destroyStatus != STATUS_OK) {
            // The isolate may already be invalid after destroy returns, so do not call
            // sdxLlmGetLastError with this runtime pointer.
            val destroyFailure = ChatException(
                "SDX runtime destroy failed (status=$destroyStatus)"
            )
            if (failure == null) failure = destroyFailure else failure.addSuppressed(destroyFailure)
        }
        failure?.let { throw it }
    }

    private fun <T> onRuntimeThread(action: String, block: () -> T): T {
        if (closed.get()) throw ChatException("SDX model session is closed")
        if (Thread.currentThread() === runtimeThread.get()) return block()
        return try {
            executor.submit(Callable { block() }).get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChatException("Interrupted while waiting for SDX to $action")
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is ChatException) throw cause
            throw ChatException(
                "SDX $action failed: ${cause.message ?: cause.javaClass.simpleName}"
            )
        }
    }

    private fun messagesJson(messages: List<Message>): String {
        if (messages.isEmpty()) throw ChatException("Conversation has no messages")
        val array = JSONArray()
        messages.forEach { message ->
            val role = when (message.role()) {
                "system", "user", "assistant" -> message.role()
                "tool_result" -> "user"
                else -> "user"
            }
            array.put(
                JSONObject()
                    .put("role", role)
                    .put("content", message.content())
            )
        }
        return array.toString()
    }

    private fun readAndFree(
        native: SdxLlmBinding,
        rt: Pointer,
        pointer: Pointer?,
        description: String
    ): String {
        val value = pointer
            ?: throw ChatException("SDX returned a null $description pointer")
        return try {
            value.getString(0, StandardCharsets.UTF_8.name()) ?: ""
        } finally {
            native.sdxLlmFree(rt, value)
        }
    }

    private fun lastError(native: SdxLlmBinding, rt: Pointer): String {
        val buffer = ByteArray(ERROR_BUFFER_BYTES)
        val fullLength = native.sdxLlmGetLastError(rt, buffer, buffer.size)
        if (fullLength <= 0) return "no native error detail"
        val length = minOf(fullLength, buffer.size - 1)
        return String(buffer, 0, length, StandardCharsets.UTF_8)
    }

    companion object {
        private const val TAG = "SdxRawGgufSession"
        private const val LIB_FILE_NAME = "libsdx_llm.so"
        private const val EXPECTED_ABI_VERSION = 2
        private const val STATUS_OK = 0
        private const val ERROR_BUFFER_BYTES = 2048
        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private const val MIN_MODEL_BYTES = 16L

        fun open(
            context: Context,
            modelPath: String,
            temperature: Float,
            maxTokens: Int
        ): SdxRawGgufChatSession {
            val model = File(modelPath).canonicalFile
            validateModelFile(model)

            val runtimeThread = AtomicReference<Thread>()
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sdx-gguf-runtime").apply {
                    isDaemon = true
                    runtimeThread.set(this)
                }
            }
            val session = SdxRawGgufChatSession(model, executor, runtimeThread)
            try {
                session.onRuntimeThread("load ${model.name}") {
                    session.initialize(context.applicationContext, temperature, maxTokens)
                }
                return session
            } catch (failure: Throwable) {
                runCatching {
                    executor.submit { session.releaseNativeState() }.get()
                }
                executor.shutdownNow()
                if (failure is ChatException) throw failure
                throw ChatException(
                    "SDX could not open ${model.name}: " +
                        (failure.message ?: failure.javaClass.simpleName)
                )
            }
        }

        fun supports(modelPath: String): Boolean {
            val extension = File(modelPath).extension.lowercase()
            return extension == "gguf" || extension == "ggml"
        }

        private fun validateModelFile(model: File) {
            if (!model.isFile || !model.canRead()) {
                throw ChatException("GGUF/GGML model is not a readable file: ${model.absolutePath}")
            }
            if (!supports(model.path)) {
                throw ChatException("Raw SDX import requires a .gguf or .ggml file: ${model.name}")
            }
            if (model.length() < MIN_MODEL_BYTES) {
                throw ChatException("Model file is truncated: ${model.name}")
            }
            val magic = ByteArray(4)
            RandomAccessFile(model, "r").use { it.readFully(magic) }
            if (!hasSupportedMagic(magic)) {
                val signature = magic.joinToString(separator = "") {
                    "%02x".format(it.toInt() and 0xff)
                }
                throw ChatException(
                    "${model.name} is not a GGUF/GGML model (magic=0x$signature)"
                )
            }
        }

        /** Mirrors DL4J's GGMLFormatDetector without pulling its JVM jar into the APK. */
        internal fun hasSupportedMagic(magic: ByteArray): Boolean {
            if (magic.size < 4) return false
            val littleEndian = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).int
            val bigEndian = ByteBuffer.wrap(magic).order(ByteOrder.BIG_ENDIAN).int
            return littleEndian == GGUF_MAGIC ||
                littleEndian == GGML_MAGIC || littleEndian == GGMF_MAGIC ||
                littleEndian == GGJT_MAGIC || bigEndian == GGML_MAGIC ||
                bigEndian == GGMF_MAGIC || bigEndian == GGJT_MAGIC
        }

        private const val GGUF_MAGIC = 0x46554747
        private const val GGML_MAGIC = 0x67676D6C
        private const val GGMF_MAGIC = 0x67676D66
        private const val GGJT_MAGIC = 0x67676A74
    }
}
