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

package ai.kompile.core.rag.retrieval;

import java.util.Map;

/**
 * Configuration options for document retrieval operations.
 *
 * @param semanticK Number of documents to retrieve via semantic (vector) search
 * @param keywordK Number of documents to retrieve via keyword (BM25) search
 * @param similarityThreshold Minimum similarity score for semantic search results (0.0 to 1.0)
 * @param enableKeywordSearch Whether to include BM25 keyword search results
 * @param enableSemanticSearch Whether to include vector similarity search results
 * @param deduplicateResults Whether to remove duplicate documents across retrieval methods
 * @param metadataFilters Optional metadata filters to apply during retrieval
 */
public record RetrievalOptions(
    int semanticK,
    int keywordK,
    double similarityThreshold,
    boolean enableKeywordSearch,
    boolean enableSemanticSearch,
    boolean deduplicateResults,
    Map<String, Object> metadataFilters
) {

    /**
     * Creates default retrieval options with balanced hybrid search.
     */
    public static RetrievalOptions defaults() {
        return new RetrievalOptions(5, 5, 0.5, true, true, true, Map.of());
    }

    /**
     * Creates options for semantic-only search.
     */
    public static RetrievalOptions semanticOnly(int k, double threshold) {
        return new RetrievalOptions(k, 0, threshold, false, true, false, Map.of());
    }

    /**
     * Creates options for keyword-only search.
     */
    public static RetrievalOptions keywordOnly(int k) {
        return new RetrievalOptions(0, k, 0.0, true, false, false, Map.of());
    }

    /**
     * Creates options for hybrid search with custom k values.
     */
    public static RetrievalOptions hybrid(int semanticK, int keywordK, double threshold) {
        return new RetrievalOptions(semanticK, keywordK, threshold, true, true, true, Map.of());
    }

    /**
     * Returns the total number of documents to potentially retrieve (before deduplication).
     */
    public int totalK() {
        int total = 0;
        if (enableSemanticSearch) total += semanticK;
        if (enableKeywordSearch) total += keywordK;
        return total;
    }

    /**
     * Creates a new options instance with updated semantic k.
     */
    public RetrievalOptions withSemanticK(int k) {
        return new RetrievalOptions(k, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, deduplicateResults, metadataFilters);
    }

    /**
     * Creates a new options instance with updated keyword k.
     */
    public RetrievalOptions withKeywordK(int k) {
        return new RetrievalOptions(semanticK, k, similarityThreshold, enableKeywordSearch, enableSemanticSearch, deduplicateResults, metadataFilters);
    }

    /**
     * Creates a new options instance with updated similarity threshold.
     */
    public RetrievalOptions withSimilarityThreshold(double threshold) {
        return new RetrievalOptions(semanticK, keywordK, threshold, enableKeywordSearch, enableSemanticSearch, deduplicateResults, metadataFilters);
    }

    /**
     * Creates a new options instance with metadata filters.
     */
    public RetrievalOptions withMetadataFilters(Map<String, Object> filters) {
        return new RetrievalOptions(semanticK, keywordK, similarityThreshold, enableKeywordSearch, enableSemanticSearch, deduplicateResults, filters);
    }
}
