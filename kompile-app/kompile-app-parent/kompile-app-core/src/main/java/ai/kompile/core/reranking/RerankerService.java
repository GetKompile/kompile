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

package ai.kompile.core.reranking;

import ai.kompile.core.embeddings.ScoredDocument;

import java.util.List;

/**
 * Service interface for document reranking.
 * <p>
 * This service provides a high-level API for reranking retrieved documents
 * using various algorithms. Implementations may wrap lower-level reranking
 * libraries like Anserini's rerankers.
 */
public interface RerankerService {

    /**
     * Rerank a list of documents based on the query.
     *
     * @param documents The initial retrieved documents with scores
     * @param query The original query text
     * @param config Configuration for the reranking operation
     * @return Reranked documents with updated scores
     */
    List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerConfig config);

    /**
     * Rerank using the default configuration.
     *
     * @param documents The initial retrieved documents with scores
     * @param query The original query text
     * @return Reranked documents with updated scores
     */
    default List<ScoredDocument> rerank(List<ScoredDocument> documents, String query) {
        return rerank(documents, query, getDefaultConfig());
    }

    /**
     * Get the default reranking configuration.
     */
    RerankerConfig getDefaultConfig();

    /**
     * Get a list of supported reranker types.
     */
    List<RerankerType> getSupportedTypes();

    /**
     * Check if a specific reranker type is supported.
     */
    default boolean isSupported(RerankerType type) {
        return getSupportedTypes().contains(type);
    }

    /**
     * Create a reranker instance for the given configuration.
     *
     * @param config The reranker configuration
     * @return A configured reranker instance
     */
    Reranker createReranker(RerankerConfig config);
}
