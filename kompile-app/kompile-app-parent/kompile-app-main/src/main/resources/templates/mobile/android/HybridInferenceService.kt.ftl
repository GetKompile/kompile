package {{packageName}}.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import {{packageName}}.config.AppConfig
import {{packageName}}.data.model.ChatMessage

/**
 * Hybrid inference service that combines local embeddings (via SDX)
 * with remote LLM generation (via OpenAI-compatible API).
 *
 * This gives the best of both worlds:
 * - Fast, private embeddings computed on-device (no data leaves the phone for indexing/search)
 * - High-quality text generation from powerful cloud models
 */
class HybridInferenceService(private val context: Context) {

    companion object {
        private const val TAG = "HybridInference"
    }

    private val sdxService = SdxInferenceService(context)
    private val remoteService = RemoteLLMService()
    private var isInitialized = false

    /**
     * Initialize the local SDX model for embeddings.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        try {
            sdxService.initialize()
            isInitialized = true
            Log.i(TAG, "Hybrid inference initialized - local embeddings ready")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize local SDX model, falling back to remote-only", e)
            // Continue without local embeddings - remote service handles everything
        }
    }

    /**
     * Compute embeddings locally using the SDX model.
     * Falls back to remote embeddings if local model is unavailable.
     */
    suspend fun computeEmbedding(text: String): FloatArray {
        return try {
            if (!isInitialized) {
                initialize()
            }
            sdxService.computeEmbedding(text)
        } catch (e: Exception) {
            Log.w(TAG, "Local embedding failed, falling back to remote", e)
            remoteService.computeEmbedding(text)
        }
    }

    /**
     * Generate text using the remote LLM with optional RAG context.
     * The prompt is augmented with locally-retrieved document context.
     */
    fun generate(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        contextSnippets: List<String> = emptyList()
    ): Flow<String> {
        // Build an augmented prompt with RAG context
        val augmentedPrompt = if (contextSnippets.isNotEmpty()) {
            buildString {
                appendLine("Use the following retrieved context to answer the question.")
                appendLine("If the context is not relevant, answer based on your general knowledge.")
                appendLine()
                appendLine("Retrieved Context:")
                contextSnippets.forEachIndexed { index, snippet ->
                    appendLine("[${index + 1}] $snippet")
                }
                appendLine()
                appendLine("Question: $prompt")
            }
        } else {
            prompt
        }

        // Use the remote service for generation (streaming)
        return remoteService.generateStream(
            prompt = augmentedPrompt,
            history = history,
            temperature = AppConfig.defaultTemperature
        )
    }

    /**
     * Generate a complete response (non-streaming) using the remote LLM.
     */
    suspend fun generateComplete(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        contextSnippets: List<String> = emptyList()
    ): String {
        val augmentedPrompt = if (contextSnippets.isNotEmpty()) {
            buildString {
                appendLine("Use the following retrieved context to answer the question.")
                appendLine("If the context is not relevant, answer based on your general knowledge.")
                appendLine()
                appendLine("Retrieved Context:")
                contextSnippets.forEachIndexed { index, snippet ->
                    appendLine("[${index + 1}] $snippet")
                }
                appendLine()
                appendLine("Question: $prompt")
            }
        } else {
            prompt
        }

        return remoteService.generateComplete(
            prompt = augmentedPrompt,
            history = history,
            temperature = AppConfig.defaultTemperature
        )
    }

    /**
     * Full hybrid RAG pipeline:
     * 1. Compute query embedding locally
     * 2. Search vector index locally
     * 3. Generate response remotely with retrieved context
     */
    fun hybridRagQuery(
        query: String,
        vectorSearchService: VectorSearchService,
        history: List<ChatMessage> = emptyList()
    ): Flow<String> = flow {
        // Step 1: Search for relevant documents
        val searchResults = vectorSearchService.search(query, AppConfig.ragTopK)
        val contextSnippets = searchResults.map { it.chunkText }

        Log.d(TAG, "Retrieved ${searchResults.size} documents for hybrid RAG")

        // Step 2: Generate with context using remote LLM
        val generationFlow = generate(query, history, contextSnippets)
        generationFlow.collect { token ->
            emit(token)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Release all resources.
     */
    fun destroy() {
        sdxService.destroy()
        isInitialized = false
    }
}
