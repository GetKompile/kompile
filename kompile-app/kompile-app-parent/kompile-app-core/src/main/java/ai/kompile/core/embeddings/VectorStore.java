/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.embeddings;

import ai.kompile.core.reranking.RerankerConfig;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Interface for a vector store that can store documents with their embeddings
 * and perform similarity searches.
 *
 * <p>
 * This interface provides both optimized INDArray-based methods (preferred for
 * performance)
 * and legacy float[]/List&lt;Float&gt; methods for Spring AI compatibility.
 * </p>
 *
 * <h2>Performance Recommendations</h2>
 * <ul>
 * <li>Use {@link #addWithEmbeddings(List, INDArray)} for batch document
 * addition</li>
 * <li>Use {@link #similaritySearchWithScores(INDArray, int, double)} for search
 * operations</li>
 * <li>Avoid the legacy List&lt;Float&gt; methods in hot paths</li>
 * </ul>
 */
public interface VectorStore {

    private static float[] toHostFloatVector(INDArray array) {
        if (array == null || array.isEmpty()) {
            return new float[0];
        }
        long length = array.length();
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("INDArray too large to materialize as float[]: " + length);
        }
        if (array.elementWiseStride() == 1) {
            return array.data().getFloatsAt(array.offset(), (int) length);
        }
        INDArray copy = null;
        try {
            copy = array.dup('c');
            return copy.data().getFloatsAt(copy.offset(), (int) length);
        } finally {
            if (copy != null && !copy.wasClosed()) {
                copy.close();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROWSING AND STATUS METHODS (for Index Browser)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the path to the vector store index.
     * 
     * @return path as String, or "N/A" if not applicable
     */
    default String getVectorStorePath() {
        return "N/A";
    }

    /**
     * Returns whether the vector store is available and ready for use.
     * 
     * @return true if vector store is available
     */
    default boolean isVectorStoreAvailable() {
        return true;
    }

    /**
     * Returns whether the vector store is using a temporary fallback index due to
     * initialization errors.
     * 
     * @return true if using fallback index
     */
    default boolean isUsingFallbackIndex() {
        return false;
    }

    /**
     * Returns the approximate count of vectors/documents in the store.
     * 
     * @return approximate count, or -1 if not available
     */
    default long getApproxVectorCount() {
        return -1L;
    }

    /**
     * Lists documents from the vector store with pagination.
     *
     * @param offset starting offset
     * @param limit  maximum number of documents to return
     * @return list of document info maps containing id, content preview, and
     *         metadata
     */
    default List<Map<String, Object>> listVectorDocuments(int offset, int limit) {
        return Collections.emptyList();
    }

    /**
     * Refreshes the internal reader to see changes made by external processes.
     * <p>
     * This is useful when documents are added by a subprocess (e.g., vector
     * population subprocess) and the main app needs to see the new documents.
     * Implementations should use efficient mechanisms like
     * {@code DirectoryReader.openIfChanged()} to only refresh if the index
     * has actually changed.
     * </p>
     *
     * @return true if the reader was refreshed (index had changes), false if no
     *         refresh was needed
     */
    default boolean refreshReader() {
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OPTIMIZED METHODS (INDArray-native, no conversion overhead)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents with pre-computed INDArray embeddings.
     * <p>
     * This is the PREFERRED method for batch additions as it avoids
     * float[]/List&lt;Float&gt; conversion overhead.
     *
     * @param documents  List of Spring AI Documents
     * @param embeddings INDArray of shape [numDocs, embeddingDim] containing
     *                   embeddings
     * @return The actual number of documents successfully persisted to the store
     * @throws IllegalArgumentException if documents.size() != embeddings.rows()
     */
    default int addWithEmbeddings(List<Document> documents, INDArray embeddings) {
        // Default implementation converts to legacy format for backward compatibility
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        if (embeddings == null || embeddings.isEmpty()) {
            return add(documents);
        }

        List<List<Float>> embeddingsList = new ArrayList<>(documents.size());
        for (int i = 0; i < embeddings.rows(); i++) {
            // CRITICAL: Close row view after extracting float[] to prevent memory leak
            INDArray rowView = null;
            try {
                rowView = embeddings.getRow(i);
                float[] row = toHostFloatVector(rowView);
                List<Float> rowList = new ArrayList<>(row.length);
                for (float v : row) {
                    rowList.add(v);
                }
                embeddingsList.add(rowList);
            } finally {
                if (rowView != null && !rowView.wasClosed()) {
                    try {
                        rowView.close();
                    } catch (Exception e) {
                        // Ignore close errors
                    }
                }
            }
        }
        return add(documents, embeddingsList);
    }

    /**
     * Performs a similarity search using an INDArray query vector.
     * <p>
     * This is the PREFERRED search method as it avoids float[] conversion
     * and returns scored documents for ranking.
     *
     * @param queryEmbedding INDArray of shape [1, embeddingDim] or [embeddingDim]
     * @param k              The number of most similar documents to retrieve
     * @param threshold      Minimum similarity score threshold (behavior depends on
     *                       implementation)
     * @return A list of ScoredDocuments sorted by score descending
     */
    default List<ScoredDocument> similaritySearchWithScores(INDArray queryEmbedding, int k, double threshold) {
        // Default implementation converts to legacy format
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return List.of();
        }

        // CRITICAL: If not a vector, get row view and close it after extracting float[]
        float[] vector;
        if (queryEmbedding.isVector()) {
            vector = toHostFloatVector(queryEmbedding);
        } else {
            INDArray rowView = null;
            try {
                rowView = queryEmbedding.getRow(0);
                vector = toHostFloatVector(rowView);
            } finally {
                if (rowView != null && !rowView.wasClosed()) {
                    try {
                        rowView.close();
                    } catch (Exception e) {
                        // Ignore close errors
                    }
                }
            }
        }

        List<Float> queryList = new ArrayList<>(vector.length);
        for (float v : vector) {
            queryList.add(v);
        }

        List<Document> docs = similaritySearch(queryList, k, threshold);
        List<ScoredDocument> scored = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            // Extract score from metadata if available
            double score = 0.0;
            if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
                Object scoreObj = doc.getMetadata().get("score");
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                }
            }
            scored.add(new ScoredDocument(doc, score));
        }
        return scored;
    }

    /**
     * Performs batch similarity search for multiple queries.
     * <p>
     * Enables BLAS-optimized batch operations for multi-query scenarios.
     *
     * @param queryEmbeddings INDArray of shape [numQueries, embeddingDim]
     * @param k               Number of results per query
     * @param threshold       Minimum similarity score
     * @return List of results for each query
     */
    default List<List<ScoredDocument>> batchSimilaritySearch(INDArray queryEmbeddings, int k, double threshold) {
        // Default implementation performs individual searches
        if (queryEmbeddings == null || queryEmbeddings.isEmpty()) {
            return List.of();
        }

        List<List<ScoredDocument>> results = new ArrayList<>();
        for (int i = 0; i < queryEmbeddings.rows(); i++) {
            // CRITICAL: Close row view after use to prevent memory leak
            INDArray query = null;
            try {
                query = queryEmbeddings.getRow(i);
                results.add(similaritySearchWithScores(query, k, threshold));
            } finally {
                if (query != null && !query.wasClosed()) {
                    try {
                        query.close();
                    } catch (Exception e) {
                        // Ignore close errors
                    }
                }
            }
        }
        return results;
    }

    /**
     * Adds documents with pre-computed float[][] embeddings.
     * <p>
     * This method avoids the boxing overhead of List&lt;List&lt;Float&gt;&gt; and
     * the INDArray allocation overhead, making it ideal for bulk indexing from
     * pre-computed embeddings.
     * </p>
     *
     * @param documents  List of Spring AI Documents
     * @param embeddings float[numDocs][embeddingDim] array of pre-computed embeddings
     * @return The actual number of documents successfully persisted to the store
     * @throws IllegalArgumentException if documents.size() != embeddings.length
     */
    default int addWithFloatArrayEmbeddings(List<Document> documents, float[][] embeddings) {
        // Default implementation converts to legacy format for backward compatibility
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        if (embeddings == null || embeddings.length == 0) {
            return add(documents);
        }

        // Convert float[][] to List<List<Float>> (fallback for implementations
        // that haven't overridden this method)
        List<List<Float>> embeddingsList = new ArrayList<>(documents.size());
        for (float[] embedding : embeddings) {
            if (embedding != null) {
                List<Float> rowList = new ArrayList<>(embedding.length);
                for (float v : embedding) {
                    rowList.add(v);
                }
                embeddingsList.add(rowList);
            } else {
                embeddingsList.add(null);
            }
        }
        return add(documents, embeddingsList);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE METHODS (auto-embed, may involve internal conversion)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents to the store. The implementation is expected to
     * generate embeddings for these documents using a configured EmbeddingModel.
     *
     * @param documents List of Spring AI Documents.
     * @return The actual number of documents successfully persisted to the store.
     *         This may be less than documents.size() if some documents were skipped
     *         (e.g., due to empty embeddings or errors).
     */
    int add(List<Document> documents);

    /**
     * Performs a similarity search using a query string.
     * The implementation will first generate an embedding for the query string
     * using a configured EmbeddingModel, then perform the search.
     *
     * @param query The query string.
     * @param k     The number of most similar documents to retrieve.
     * @return A list of Spring AI Documents.
     */
    List<Document> similaritySearch(String query, int k);

    /**
     * Performs a similarity search using a query string with a similarity
     * threshold.
     *
     * @param query     The query string.
     * @param k         The number of most similar documents to retrieve.
     * @param threshold The similarity score threshold.
     * @return A list of Spring AI Documents.
     */
    List<Document> similaritySearch(String query, int k, double threshold);

    /**
     * Performs a similarity search using a query string, returning scored
     * documents.
     * <p>
     * This method auto-embeds the query and returns documents with scores.
     *
     * @param query     The query string
     * @param k         The number of most similar documents to retrieve
     * @param threshold The similarity score threshold
     * @return A list of ScoredDocuments sorted by score descending
     */
    default List<ScoredDocument> similaritySearchWithScores(String query, int k, double threshold) {
        List<Document> docs = similaritySearch(query, k, threshold);
        List<ScoredDocument> scored = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            double score = 0.0;
            if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
                Object scoreObj = doc.getMetadata().get("score");
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                }
            }
            scored.add(new ScoredDocument(doc, score));
        }
        return scored;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RERANKING METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Performs a similarity search with optional reranking.
     * <p>
     * This method retrieves documents using vector similarity search and then
     * applies the specified reranking algorithm to improve result quality.
     *
     * @param query          The query string
     * @param k              The number of most similar documents to retrieve
     * @param threshold      The similarity score threshold
     * @param rerankerConfig Configuration for reranking (null to skip reranking)
     * @return A list of ScoredDocuments sorted by reranked score descending
     */
    default List<ScoredDocument> similaritySearchWithReranking(String query, int k, double threshold,
            RerankerConfig rerankerConfig) {
        // Default implementation: just do similarity search without reranking
        // Implementations with reranking support should override this
        return similaritySearchWithScores(query, k, threshold);
    }

    /**
     * Performs a similarity search with reranking using INDArray query embedding.
     *
     * @param queryEmbedding The query embedding as INDArray
     * @param query          The original query string (for reranking algorithms
     *                       that need it)
     * @param k              The number of results
     * @param threshold      The similarity threshold
     * @param rerankerConfig Configuration for reranking
     * @return Reranked scored documents
     */
    default List<ScoredDocument> similaritySearchWithReranking(INDArray queryEmbedding, String query, int k,
            double threshold, RerankerConfig rerankerConfig) {
        // Default implementation: just do similarity search without reranking
        return similaritySearchWithScores(queryEmbedding, k, threshold);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANAGEMENT METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Deletes documents from the store by their IDs.
     *
     * @param ids List of document IDs to delete.
     * @return true if deletion was successful for all specified IDs, false
     *         otherwise.
     */
    boolean delete(List<String> ids);

    /**
     * Flushes any pending documents and commits them to the store.
     * <p>
     * This method should be called after bulk indexing operations to ensure
     * all documents are persisted. Implementations using batch commit optimization
     * will commit any buffered documents that haven't reached the batch threshold yet.
     * </p>
     *
     * @return true if flush/commit was successful, false otherwise
     */
    default boolean flushAndCommit() {
        // Default implementation does nothing - implementations with batch
        // commit optimization should override this
        return true;
    }

    /**
     * Blocks until all pending async embedding tasks (dispatched by {@link #add(List)}) have
     * completed and their documents are committed to the store.
     *
     * <p>Call this before any step that reads from the vector store (VECTOR_INDEXING, KGE
     * training) to ensure graph-node embeddings dispatched during GRAPH_PREP are visible.
     * The default implementation is a no-op; implementations that use async embedding override it.</p>
     */
    default void awaitPendingEmbeddings() {
        // No-op for implementations without async embedding.
    }

    /**
     * Persists documents to the store as metadata-only records, WITHOUT computing or
     * storing an embedding vector.
     *
     * <p>Use this for documents that carry structured metadata (id + content + metadata map)
     * but must NOT be returned by similarity search and do NOT require an embedding model.
     * Typical use cases: graph metadata, adjacency-matrix serialization, node-embedding
     * matrix snapshots — all of which are stored-field blobs read back by
     * {@link #listVectorDocuments}, never by KNN search.</p>
     *
     * <p>This method must work even when the vector store is in <em>read/stored-vector
     * mode</em> (embedding model disabled, e.g. in the graph-matrix subprocess).  The
     * default implementation falls back to {@link #add(List)}, which works in the main
     * process (embedding model available) but silently drops documents in the subprocess
     * (embeddingDisabled=true).  {@code AnseriniVectorStoreImpl} overrides this to write
     * directly to the Lucene {@code IndexWriter} without a KNN vector field, so it always
     * persists regardless of whether an embedding model is present.</p>
     *
     * @param documents documents to persist; id, text (content), and metadata are stored
     * @return number of documents successfully written
     */
    default int addStoredOnlyDocuments(List<Document> documents) {
        // Fallback: delegate to add() for implementations that do not override.
        // Works in main-process mode (has embedding model); may silently drop in subprocess.
        return add(documents);
    }

    /**
     * Switches the vector store to use a different index path.
     * <p>
     * This allows dynamic switching between different vector indices at runtime,
     * which is useful for per-fact-sheet index storage. After switching, the store
     * will read from and write to the new location.
     * </p>
     * <p>
     * Implementations should close any existing resources for the old path and
     * open/create resources for the new path. If the new path doesn't exist,
     * implementations should create it.
     * </p>
     *
     * @param newPath The new index path to switch to
     * @return true if the switch was successful, false otherwise
     */
    default boolean switchIndexPath(String newPath) {
        // Default implementation does nothing - implementations that support
        // index path switching should override this
        return false;
    }

    /**
     * Returns the current index path used by the vector store.
     * <p>
     * This is an alias for {@link #getVectorStorePath()} for consistency with
     * the switchIndexPath method naming.
     * </p>
     *
     * @return The current index path, or "N/A" if not applicable
     */
    default String getIndexPath() {
        return getVectorStorePath();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHUNK MANAGEMENT METHODS (for Chunk Manager)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Retrieves a single document/chunk from the vector store by its ID.
     *
     * @param id The document ID
     * @return Map containing document info (id, content, metadata), or null if not found
     */
    default Map<String, Object> getVectorDocument(String id) {
        return null;
    }

    /** Exact lookup that distinguishes a missing document from a storage/read failure. */
    default Map<String, Object> getVectorDocumentStrict(String id) {
        return getVectorDocument(id);
    }

    /**
     * Deletes all documents from the vector store.
     * <p>
     * This is a destructive operation and should be used with caution.
     * Implementations should handle cleanup of all indices and resources.
     * </p>
     *
     * @return true if deletion was successful, false otherwise
     */
    default boolean deleteAll() {
        return false;
    }

    /**
     * Retrieves all document IDs that belong to a specific source document.
     * <p>
     * This is useful for bulk deletion of all chunks from a source file.
     * The source ID is typically stored in the 'source_id' metadata field.
     * </p>
     *
     * @param sourceId The source document ID (e.g., file path or URL)
     * @return List of document IDs belonging to this source
     */
    default List<String> getDocumentIdsBySourceId(String sourceId) {
        return Collections.emptyList();
    }

    /**
     * Retrieves all documents from the vector store.
     * <p>
     * This method is used for operations that need to scan all documents,
     * such as deduplication. Use with caution on large indices as this
     * may be memory-intensive.
     * </p>
     *
     * @return List of all document info maps
     */
    default List<Map<String, Object>> getAllVectorDocuments() {
        // Default implementation uses pagination to get all documents
        List<Map<String, Object>> allDocs = new ArrayList<>();
        int offset = 0;
        int limit = 1000;
        List<Map<String, Object>> batch;
        do {
            batch = listVectorDocuments(offset, limit);
            allDocs.addAll(batch);
            offset += limit;
        } while (batch.size() == limit);
        return allDocs;
    }

    /**
     * Bounded exact-match lookup over scalar metadata fields. Stores with a metadata index should
     * override this; the compatibility implementation scans bounded document pages without retaining
     * the complete index.
     */
    default List<Map<String, Object>> listVectorDocumentsByMetadata(
            Map<String, String> filters, int limit) {
        if (filters == null || filters.isEmpty() || limit <= 0) return Collections.emptyList();
        List<Map<String, Object>> matches = new ArrayList<>(Math.min(limit, 1_000));
        int offset = 0;
        int pageSize = 1_000;
        List<Map<String, Object>> page;
        do {
            page = listVectorDocuments(offset, pageSize);
            for (Map<String, Object> document : page) {
                Object rawMetadata = document.get("metadata");
                if (!(rawMetadata instanceof Map<?, ?> metadata)) continue;
                boolean accepted = true;
                for (Map.Entry<String, String> filter : filters.entrySet()) {
                    if (!filter.getValue().equals(String.valueOf(metadata.get(filter.getKey())))) {
                        accepted = false;
                        break;
                    }
                }
                if (accepted) {
                    matches.add(document);
                    if (matches.size() >= limit) return matches;
                }
            }
            offset += page.size();
        } while (page.size() == pageSize);
        return matches;
    }

    /**
     * Strict page scan used by durable migrations. Implementations must propagate storage failures
     * instead of converting them to an empty page. Compatibility stores delegate to the legacy API.
     */
    default List<Map<String, Object>> listVectorDocumentsStrict(int offset, int limit) {
        return listVectorDocuments(offset, limit);
    }

    /**
     * Traverse one stable document snapshot in bounded pages. Implementations with snapshot readers
     * should override this so concurrent commits cannot shift offset-based pagination.
     */
    @FunctionalInterface
    interface DocumentPageConsumer {
        void accept(List<Map<String, Object>> page) throws IOException;
    }

    default void scanVectorDocumentsStrict(
            int pageSize, DocumentPageConsumer consumer) throws IOException {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be > 0");
        int offset = 0;
        List<Map<String, Object>> page;
        do {
            page = listVectorDocumentsStrict(offset, pageSize);
            consumer.accept(page);
            offset += page.size();
        } while (page.size() == pageSize);
    }

    /**
     * Returns a list of unique source document IDs present in the vector store.
     * <p>
     * This is useful for UI components that need to show a filter dropdown
     * of available sources.
     * </p>
     *
     * @return List of unique source IDs
     */
    default List<String> getUniqueSourceIds() {
        return Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY METHODS (Spring AI compatibility, involves conversion overhead)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents along with their pre-generated embeddings to the store.
     * <p>
     * <b>Note:</b> This method involves boxing overhead. Prefer
     * {@link #addWithEmbeddings(List, INDArray)} for better performance.
     *
     * @param documents  List of Spring AI Documents.
     * @param embeddings List of corresponding vector embeddings as
     *                   List&lt;Float&gt;.
     * @return The actual number of documents successfully persisted to the store.
     *         This may be less than documents.size() if some documents were skipped.
     * @deprecated Use {@link #addWithEmbeddings(List, INDArray)} instead
     */
    @Deprecated
    int add(List<Document> documents, List<List<Float>> embeddings);

    /**
     * Performs a similarity search against the stored vector embeddings.
     * <p>
     * <b>Note:</b> This method involves unboxing overhead. Prefer
     * {@link #similaritySearchWithScores(INDArray, int, double)} for better
     * performance.
     *
     * @param queryEmbedding The vector embedding of the query as List&lt;Float&gt;.
     * @param k              The number of most similar documents to retrieve.
     * @param threshold      Optional similarity threshold.
     * @return A list of Spring AI Documents.
     * @deprecated Use {@link #similaritySearchWithScores(INDArray, int, double)}
     *             instead
     */
    @Deprecated
    List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold);
}
