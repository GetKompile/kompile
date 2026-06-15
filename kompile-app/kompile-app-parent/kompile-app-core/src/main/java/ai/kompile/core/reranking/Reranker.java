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
 * Interface for document reranking.
 * <p>
 * Rerankers take an initial set of retrieved documents and re-score them
 * to improve ranking quality. Common approaches include:
 * <ul>
 *   <li>Query expansion (RM3, Rocchio)</li>
 *   <li>Pseudo-relevance feedback (BM25-PRF)</li>
 *   <li>Cross-encoder reranking</li>
 *   <li>Axiomatic reranking</li>
 * </ul>
 */
public interface Reranker {

    /**
     * Rerank a list of documents based on the query.
     *
     * @param documents The initial retrieved documents with scores
     * @param query The original query text
     * @param context Additional context for reranking (may include query tokens, etc.)
     * @return Reranked documents with updated scores
     */
    List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context);

    /**
     * Rerank a list of documents based on the query using default context.
     *
     * @param documents The initial retrieved documents with scores
     * @param query The original query text
     * @return Reranked documents with updated scores
     */
    default List<ScoredDocument> rerank(List<ScoredDocument> documents, String query) {
        return rerank(documents, query, RerankerContext.empty());
    }

    /**
     * Get a descriptive tag for this reranker (e.g., "RM3(fbDocs=10,fbTerms=10)").
     *
     * @return A string describing this reranker configuration
     */
    String tag();

    /**
     * Get the type of this reranker.
     *
     * @return The reranker type
     */
    RerankerType getType();
}
