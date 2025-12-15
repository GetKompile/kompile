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

import java.util.ArrayList;
import java.util.List;

/**
 * Interface for a vector store that can store documents with their embeddings
 * and perform similarity searches.
 *
 * <p>This interface provides both optimized INDArray-based methods (preferred for performance)
 * and legacy float[]/List&lt;Float&gt; methods for Spring AI compatibility.</p>
 *
 * <h2>Performance Recommendations</h2>
 * <ul>
 *   <li>Use {@link #addWithEmbeddings(List, INDArray)} for batch document addition</li>
 *   <li>Use {@link #similaritySearchWithScores(INDArray, int, double)} for search operations</li>
 *   <li>Avoid the legacy List&lt;Float&gt; methods in hot paths</li>
 * </ul>
 */
public interface VectorStore {

    // ═══════════════════════════════════════════════════════════════════════════
    // OPTIMIZED METHODS (INDArray-native, no conversion overhead)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents with pre-computed INDArray embeddings.
     * <p>
     * This is the PREFERRED method for batch additions as it avoids
     * float[]/List&lt;Float&gt; conversion overhead.
     *
     * @param documents List of Spring AI Documents
     * @param embeddings INDArray of shape [numDocs, embeddingDim] containing embeddings
     * @throws IllegalArgumentException if documents.size() != embeddings.rows()
     */
    default void addWithEmbeddings(List<Document> documents, INDArray embeddings) {
        // Default implementation converts to legacy format for backward compatibility
        if (documents == null || documents.isEmpty()) {
            return;
        }
        if (embeddings == null || embeddings.isEmpty()) {
            add(documents);
            return;
        }

        List<List<Float>> embeddingsList = new ArrayList<>(documents.size());
        for (int i = 0; i < embeddings.rows(); i++) {
            // CRITICAL: Close row view after extracting float[] to prevent memory leak
            INDArray rowView = null;
            try {
                rowView = embeddings.getRow(i);
                float[] row = rowView.toFloatVector();
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
        add(documents, embeddingsList);
    }

    /**
     * Performs a similarity search using an INDArray query vector.
     * <p>
     * This is the PREFERRED search method as it avoids float[] conversion
     * and returns scored documents for ranking.
     *
     * @param queryEmbedding INDArray of shape [1, embeddingDim] or [embeddingDim]
     * @param k The number of most similar documents to retrieve
     * @param threshold Minimum similarity score threshold (behavior depends on implementation)
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
            vector = queryEmbedding.toFloatVector();
        } else {
            INDArray rowView = null;
            try {
                rowView = queryEmbedding.getRow(0);
                vector = rowView.toFloatVector();
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
     * @param k Number of results per query
     * @param threshold Minimum similarity score
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE METHODS (auto-embed, may involve internal conversion)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents to the store. The implementation is expected to
     * generate embeddings for these documents using a configured EmbeddingModel.
     *
     * @param documents List of Spring AI Documents.
     */
    void add(List<Document> documents);

    /**
     * Performs a similarity search using a query string.
     * The implementation will first generate an embedding for the query string
     * using a configured EmbeddingModel, then perform the search.
     *
     * @param query The query string.
     * @param k The number of most similar documents to retrieve.
     * @return A list of Spring AI Documents.
     */
    List<Document> similaritySearch(String query, int k);

    /**
     * Performs a similarity search using a query string with a similarity threshold.
     *
     * @param query The query string.
     * @param k The number of most similar documents to retrieve.
     * @param threshold The similarity score threshold.
     * @return A list of Spring AI Documents.
     */
    List<Document> similaritySearch(String query, int k, double threshold);

    /**
     * Performs a similarity search using a query string, returning scored documents.
     * <p>
     * This method auto-embeds the query and returns documents with scores.
     *
     * @param query The query string
     * @param k The number of most similar documents to retrieve
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
     * @param query The query string
     * @param k The number of most similar documents to retrieve
     * @param threshold The similarity score threshold
     * @param rerankerConfig Configuration for reranking (null to skip reranking)
     * @return A list of ScoredDocuments sorted by reranked score descending
     */
    default List<ScoredDocument> similaritySearchWithReranking(String query, int k, double threshold, RerankerConfig rerankerConfig) {
        // Default implementation: just do similarity search without reranking
        // Implementations with reranking support should override this
        return similaritySearchWithScores(query, k, threshold);
    }

    /**
     * Performs a similarity search with reranking using INDArray query embedding.
     *
     * @param queryEmbedding The query embedding as INDArray
     * @param query The original query string (for reranking algorithms that need it)
     * @param k The number of results
     * @param threshold The similarity threshold
     * @param rerankerConfig Configuration for reranking
     * @return Reranked scored documents
     */
    default List<ScoredDocument> similaritySearchWithReranking(INDArray queryEmbedding, String query, int k, double threshold, RerankerConfig rerankerConfig) {
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
     * @return true if deletion was successful for all specified IDs, false otherwise.
     */
    boolean delete(List<String> ids);

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY METHODS (Spring AI compatibility, involves conversion overhead)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds documents along with their pre-generated embeddings to the store.
     * <p>
     * <b>Note:</b> This method involves boxing overhead. Prefer
     * {@link #addWithEmbeddings(List, INDArray)} for better performance.
     *
     * @param documents List of Spring AI Documents.
     * @param embeddings List of corresponding vector embeddings as List&lt;Float&gt;.
     * @deprecated Use {@link #addWithEmbeddings(List, INDArray)} instead
     */
    @Deprecated
    void add(List<Document> documents, List<List<Float>> embeddings);

    /**
     * Performs a similarity search against the stored vector embeddings.
     * <p>
     * <b>Note:</b> This method involves unboxing overhead. Prefer
     * {@link #similaritySearchWithScores(INDArray, int, double)} for better performance.
     *
     * @param queryEmbedding The vector embedding of the query as List&lt;Float&gt;.
     * @param k The number of most similar documents to retrieve.
     * @param threshold Optional similarity threshold.
     * @return A list of Spring AI Documents.
     * @deprecated Use {@link #similaritySearchWithScores(INDArray, int, double)} instead
     */
    @Deprecated
    List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold);
}
