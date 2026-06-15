package {{packageName}}.service

import android.content.Context
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import {{packageName}}.config.AppConfig
import java.io.File

/**
 * Service for local on-device inference using SDX (SameDiff eXecution) runtime
 * loaded via JNA from a native .so library bundled in the AAR.
 */
class SdxInferenceService(private val context: Context) {

    companion object {
        private const val TAG = "SdxInference"
        private const val LIB_NAME = "sdx_runtime"
    }

    /** JNA interface mapping to the SDX native library */
    interface SdxNativeLib : Library {
        fun sdx_init(modelPath: String, numThreads: Int): Pointer?
        fun sdx_generate_next(handle: Pointer, prompt: String, maxTokens: Int): String?
        fun sdx_generate_token(handle: Pointer): String?
        fun sdx_set_prompt(handle: Pointer, prompt: String): Int
        fun sdx_is_done(handle: Pointer): Boolean
        fun sdx_reset(handle: Pointer)
        fun sdx_destroy(handle: Pointer)
        fun sdx_get_embedding(handle: Pointer, text: String, buffer: FloatArray, bufferSize: Int): Int
        fun sdx_get_embedding_dim(handle: Pointer): Int
    }

    private var nativeLib: SdxNativeLib? = null
    private var modelHandle: Pointer? = null
    private var isInitialized = false

    /**
     * Initialize the SDX runtime and load the model.
     * Copies the model from assets to internal storage if needed.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            // Load the native library via JNA
            nativeLib = Native.load(LIB_NAME, SdxNativeLib::class.java)
            Log.i(TAG, "SDX native library loaded successfully")

            // Ensure model file exists in internal storage
            val modelFile = getModelFile()
            if (!modelFile.exists()) {
                Log.i(TAG, "Copying model from assets to ${modelFile.absolutePath}")
                copyModelFromAssets(modelFile)
            }

            // Initialize the model
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            modelHandle = nativeLib?.sdx_init(modelFile.absolutePath, numThreads)

            if (modelHandle != null) {
                isInitialized = true
                Log.i(TAG, "SDX model initialized: ${AppConfig.modelId} with $numThreads threads")
            } else {
                throw RuntimeException("Failed to initialize SDX model - null handle returned")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "SDX native library not found. Ensure the AAR is included in libs/", e)
            throw RuntimeException("SDX runtime not available: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SDX inference", e)
            throw e
        }
    }

    /**
     * Generate text from a prompt, returning tokens as a Flow for streaming display.
     */
    fun generate(prompt: String): Flow<String> = flow {
        if (!isInitialized) {
            initialize()
        }

        val handle = modelHandle ?: throw IllegalStateException("Model not initialized")
        val lib = nativeLib ?: throw IllegalStateException("Native library not loaded")

        try {
            // Set the prompt for generation
            val result = lib.sdx_set_prompt(handle, prompt)
            if (result != 0) {
                throw RuntimeException("Failed to set prompt, error code: $result")
            }

            // Stream tokens one at a time
            var tokenCount = 0
            val maxTokens = AppConfig.maxGenerationTokens

            while (!lib.sdx_is_done(handle) && tokenCount < maxTokens) {
                val token = lib.sdx_generate_token(handle)
                if (token != null && token.isNotEmpty()) {
                    emit(token)
                    tokenCount++
                } else {
                    break
                }
            }

            Log.d(TAG, "Generation complete: $tokenCount tokens")
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            throw e
        } finally {
            lib.sdx_reset(handle)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generate a complete response (non-streaming).
     */
    suspend fun generateComplete(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initialize()
        }

        val handle = modelHandle ?: throw IllegalStateException("Model not initialized")
        val lib = nativeLib ?: throw IllegalStateException("Native library not loaded")

        try {
            val response = lib.sdx_generate_next(handle, prompt, AppConfig.maxGenerationTokens)
            lib.sdx_reset(handle)
            response ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Complete generation failed", e)
            lib.sdx_reset(handle)
            throw e
        }
    }

    /**
     * Compute embeddings for the given text using the local model.
     * Returns a float array of the embedding vector.
     */
    suspend fun computeEmbedding(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            initialize()
        }

        val handle = modelHandle ?: throw IllegalStateException("Model not initialized")
        val lib = nativeLib ?: throw IllegalStateException("Native library not loaded")

        val dim = lib.sdx_get_embedding_dim(handle)
        if (dim <= 0) {
            throw RuntimeException("Model does not support embeddings or returned invalid dimension: $dim")
        }

        val buffer = FloatArray(dim)
        val written = lib.sdx_get_embedding(handle, text, buffer, dim)
        if (written != dim) {
            Log.w(TAG, "Expected $dim embedding dimensions but got $written")
        }

        buffer
    }

    /**
     * Release all native resources.
     */
    fun destroy() {
        modelHandle?.let { handle ->
            try {
                nativeLib?.sdx_destroy(handle)
                Log.i(TAG, "SDX model destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SDX model", e)
            }
        }
        modelHandle = null
        nativeLib = null
        isInitialized = false
    }

    private fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "models")
        modelsDir.mkdirs()
        return File(modelsDir, AppConfig.modelFileName)
    }

    private fun copyModelFromAssets(destination: File) {
        try {
            context.assets.open("models/${AppConfig.modelFileName}").use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "Model copied to ${destination.absolutePath} (${destination.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Model not found in assets, it may need to be downloaded", e)
            throw RuntimeException(
                "Model file '${AppConfig.modelFileName}' not found in assets/models/. " +
                "Place the model file there or download it at runtime.", e
            )
        }
    }
}
