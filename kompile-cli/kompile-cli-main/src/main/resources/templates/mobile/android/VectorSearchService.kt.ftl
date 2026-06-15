package {{packageName}}.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import {{packageName}}.config.AppConfig
import kotlin.math.sqrt

/**
 * In-memory vector similarity search service.
 * Stores document chunk embeddings and performs cosine similarity search.
 *
 * For production use, consider replacing with an on-device vector database
 * such as ObjectBox or a SQLite-backed HNSW implementation.
 */
class VectorSearchService {

    companion object {
        private const val TAG = "VectorSearch"
    }

    data class IndexedChunk(
        val documentId: String,
        val documentName: String,
        val chunkIndex: Int,
        val chunkText: String,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IndexedChunk) return false
            return documentId == other.documentId && chunkIndex == other.chunkIndex
        }

        override fun hashCode(): Int {
            return 31 * documentId.hashCode() + chunkIndex
        }
    }

    data class SearchResult(
        val documentId: String,
        val documentName: String,
        val chunkText: String,
        val score: Float
    )

    private val mutex = Mutex()
    private val index = mutableListOf<IndexedChunk>()
    private var queryEmbeddingProvider: (suspend (String) -> FloatArray)? = null

    /**
     * Set the embedding function used to compute query embeddings at search time.
     */
    fun setQueryEmbeddingProvider(provider: suspend (String) -> FloatArray) {
        queryEmbeddingProvider = provider
    }

    /**
     * Add a document chunk with its pre-computed embedding to the index.
     */
    suspend fun addChunk(
        documentId: String,
        documentName: String,
        chunkIndex: Int,
        chunkText: String,
        embedding: FloatArray
    ) = mutex.withLock {
        index.add(
            IndexedChunk(
                documentId = documentId,
                documentName = documentName,
                chunkIndex = chunkIndex,
                chunkText = chunkText,
                embedding = embedding
            )
        )
        Log.d(TAG, "Indexed chunk $chunkIndex of $documentName (total: ${index.size})")
    }

    /**
     * Add multiple chunks at once for batch indexing.
     */
    suspend fun addChunks(chunks: List<IndexedChunk>) = mutex.withLock {
        index.addAll(chunks)
        Log.i(TAG, "Batch indexed ${chunks.size} chunks (total: ${index.size})")
    }

    /**
     * Search the index for chunks most similar to the query.
     * Returns top-k results sorted by cosine similarity (descending).
     */
    suspend fun search(query: String, topK: Int = AppConfig.ragTopK): List<SearchResult> =
        withContext(Dispatchers.Default) {
            if (index.isEmpty()) {
                return@withContext emptyList()
            }

            val provider = queryEmbeddingProvider
            if (provider == null) {
                Log.w(TAG, "No query embedding provider set, returning empty results")
                return@withContext emptyList()
            }

            try {
                val queryEmbedding = provider(query)
                if (queryEmbedding.isEmpty()) {
                    Log.w(TAG, "Empty query embedding returned")
                    return@withContext emptyList()
                }

                searchByEmbedding(queryEmbedding, topK)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                emptyList()
            }
        }

    /**
     * Search using a pre-computed query embedding vector.
     */
    suspend fun searchByEmbedding(
        queryEmbedding: FloatArray,
        topK: Int = AppConfig.ragTopK
    ): List<SearchResult> = mutex.withLock {
        if (index.isEmpty()) return emptyList()

        index.map { chunk ->
            SearchResult(
                documentId = chunk.documentId,
                documentName = chunk.documentName,
                chunkText = chunk.chunkText,
                score = cosineSimilarity(queryEmbedding, chunk.embedding)
            )
        }
            .sortedByDescending { it.score }
            .take(topK)
            .filter { it.score > 0f } // Only return positively correlated results
    }

    /**
     * Remove all chunks for a given document.
     */
    suspend fun removeDocument(documentId: String) = mutex.withLock {
        val removed = index.removeAll { it.documentId == documentId }
        Log.i(TAG, "Removed chunks for document $documentId (removed: $removed, remaining: ${index.size})")
    }

    /**
     * Clear the entire index.
     */
    suspend fun clearIndex() = mutex.withLock {
        val size = index.size
        index.clear()
        Log.i(TAG, "Index cleared ($size chunks removed)")
    }

    /**
     * Get the total number of indexed chunks.
     */
    fun getIndexSize(): Int = index.size

    /**
     * Compute cosine similarity between two vectors.
     * Returns a value between -1 and 1, where 1 means identical direction.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.w(TAG, "Embedding dimension mismatch: ${a.size} vs ${b.size}")
            return 0f
        }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) {
            (dotProduct / denominator).coerceIn(-1f, 1f)
        } else {
            0f
        }
    }

    /**
     * Compute Euclidean distance between two vectors (lower is more similar).
     */
    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE

        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * Compute dot product between two vectors.
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
}
