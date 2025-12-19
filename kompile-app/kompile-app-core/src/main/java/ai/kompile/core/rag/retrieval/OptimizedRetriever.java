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

import org.nd4j.linalg.api.ndarray.INDArray;

/**
 * High-performance document retriever optimized for INDArray operations.
 * <p>
 * This interface provides retrieval methods that avoid float[]/List&lt;Float&gt;
 * conversion overhead by working directly with INDArray embeddings.
 * Implementations typically combine semantic (vector) and keyword (BM25) search.
 *
 * <h2>Performance Benefits</h2>
 * <ul>
 *   <li>Single embedding generation per query</li>
 *   <li>INDArray stays in native memory through the hot path</li>
 *   <li>Conversion to float[] only at storage boundary (e.g., Lucene)</li>
 *   <li>Supports batch operations for multi-query scenarios</li>
 * </ul>
 *
 * @see RetrievalOptions
 * @see RetrievalResult
 */
public interface OptimizedRetriever {

    /**
     * Retrieves documents using the configured hybrid search strategy.
     * <p>
     * This method generates the query embedding internally and performs
     * both semantic and keyword search based on the options.
     *
     * @param query User query string
     * @param options Retrieval configuration (k values, thresholds, etc.)
     * @return Retrieved documents with scores and metrics
     */
    RetrievalResult retrieve(String query, RetrievalOptions options);

    /**
     * Retrieves documents using a pre-computed query embedding.
     * <p>
     * Use this method when the query embedding has already been generated
     * (e.g., for query expansion or multi-stage pipelines).
     *
     * @param queryEmbedding Pre-computed query embedding as INDArray
     * @param originalQuery Original query string (for keyword search)
     * @param options Retrieval configuration
     * @return Retrieved documents with scores and metrics
     */
    RetrievalResult retrieve(INDArray queryEmbedding, String originalQuery, RetrievalOptions options);

    /**
     * Retrieves documents using default options.
     *
     * @param query User query string
     * @return Retrieved documents with default configuration
     */
    default RetrievalResult retrieve(String query) {
        return retrieve(query, RetrievalOptions.defaults());
    }

    /**
     * Retrieves documents using semantic search only.
     *
     * @param query User query string
     * @param k Number of documents to retrieve
     * @param threshold Minimum similarity threshold
     * @return Retrieved documents
     */
    default RetrievalResult retrieveSemantic(String query, int k, double threshold) {
        return retrieve(query, RetrievalOptions.semanticOnly(k, threshold));
    }

    /**
     * Retrieves documents using keyword search only.
     *
     * @param query User query string
     * @param k Number of documents to retrieve
     * @return Retrieved documents
     */
    default RetrievalResult retrieveKeyword(String query, int k) {
        return retrieve(query, RetrievalOptions.keywordOnly(k));
    }

    /**
     * Checks if this retriever is ready to handle requests.
     * <p>
     * Returns false if required components (embedding model, vector store,
     * keyword index) are not properly initialized.
     *
     * @return true if retriever is operational
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns the name/identifier of this retriever implementation.
     *
     * @return Retriever name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
