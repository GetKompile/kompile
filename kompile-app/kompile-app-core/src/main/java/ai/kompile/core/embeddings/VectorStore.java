/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.embeddings;

import org.springframework.ai.document.Document; // Using Spring AI's Document
import java.util.List;
import java.util.Map;

/**
 * Interface for a vector store that can store documents with their embeddings
 * and perform similarity searches.
 */
public interface VectorStore {

    /**
     * Adds documents along with their pre-generated embeddings to the store.
     * Implementations might also accept just documents and generate embeddings internally
     * if an EmbeddingModel is provided.
     *
     * @param documents List of Spring AI Documents.
     * @param embeddings List of corresponding vector embeddings.
     */
    void add(List<Document> documents, List<List<Float>> embeddings);

    /**
     * Adds documents to the store. The implementation is expected to
     * generate embeddings for these documents using a configured EmbeddingModel.
     *
     * @param documents List of Spring AI Documents.
     */
    void add(List<Document> documents);


    /**
     * Deletes documents from the store by their IDs.
     *
     * @param ids List of document IDs to delete.
     * @return true if deletion was successful for all specified IDs, false otherwise or if partially successful.
     */
    boolean delete(List<String> ids);

    /**
     * Performs a similarity search against the stored vector embeddings.
     *
     * @param queryEmbedding The vector embedding of the query.
     * @param k The number of most similar documents to retrieve.
     * @param threshold Optional similarity threshold (behavior depends on implementation).
     * @return A list of Spring AI Documents that are most similar to the query embedding.
     */
    List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold);

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
}