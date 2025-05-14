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

package ai.kompile.vectorstore.chroma;

import ai.kompile.core.embeddings.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest; // Correct import
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service("chromaVectorStoreImpl")
@ConditionalOnProperty(prefix = "spring.ai.vectorstore.chroma.client", name = "host")
public class ChromaVectorStoreImpl implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(ChromaVectorStoreImpl.class);
    private final org.springframework.ai.vectorstore.VectorStore springAiChromaVectorStore;

    @Autowired
    public ChromaVectorStoreImpl(org.springframework.ai.vectorstore.VectorStore springAiChromaVectorStore) {
        this.springAiChromaVectorStore = springAiChromaVectorStore;
        logger.info("ChromaVectorStoreImpl initialized with Spring AI VectorStore: {}",
                springAiChromaVectorStore.getClass().getSimpleName());
    }

    // ... add and delete methods remain the same ...
    @Override
    public void add(List<Document> documents, List<List<Float>> embeddings) {
        logger.warn("add(documents, List<List<Float>> embeddings) called on ChromaVectorStoreImpl. " +
                "This wrapper will delegate to add(List<Document> documents), " +
                "relying on the underlying Spring AI ChromaVectorStore to handle embedding generation " +
                "using its configured EmbeddingModel. The provided pre-computed embeddings will be ignored by this call.");
        if (documents != null && !documents.isEmpty()) {
            add(documents);
        }
    }

    @Override
    public void add(List<Document> documents) {
        if (documents != null && !documents.isEmpty()) {
            logger.debug("Adding {} documents to ChromaVectorStore via Spring AI VectorStore.", documents.size());
            this.springAiChromaVectorStore.add(documents);
            logger.info("Successfully submitted {} documents to ChromaVectorStore.", documents.size());
        } else {
            logger.debug("No documents provided to add to ChromaVectorStore.");
        }
    }

    @Override
    public boolean delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            logger.debug("No IDs provided for deletion from ChromaVectorStore.");
            return true;
        }
        logger.debug("Attempting to delete document IDs from ChromaVectorStore: {}", ids);
        try {
            this.springAiChromaVectorStore.delete(ids);
            logger.info("Deletion command sent for IDs {} to ChromaVectorStore.", ids);
            return true;
        } catch (UnsupportedOperationException e) {
            logger.warn("Deletion is not supported by the underlying ChromaVectorStore implementation: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Error during deletion from ChromaVectorStore for IDs {}: {}", ids, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        logger.warn("Direct similaritySearch by pre-computed queryEmbedding is not a standard feature " +
                "of Spring AI's top-level VectorStore interface using SearchRequest. " +
                "This method is not implemented for ChromaVectorStoreImpl wrapper and will return an empty list.");
        return Collections.emptyList();
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0); // Default threshold
    }

    @Override
    public List<Document> similaritySearch(String query, int k, double similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Similarity search called with empty or null query.");
            return Collections.emptyList();
        }
        String Squery = query.substring(0, Math.min(query.length(), 50)) + (query.length() > 50 ? "..." : "");

        logger.debug("Performing similarity search in ChromaVectorStore for query: [{}], k={}, threshold={}",
                Squery, k, similarityThreshold);

        // CORRECTED SearchRequest construction using the builder methods from 1.0.0-M8 documentation
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)       // Uses .query(String)
                .topK(k);           // Uses .topK(int)

        if (similarityThreshold > 0.0 && similarityThreshold <= 1.0) {
            // This uses .similarityThreshold(double) on the builder
            requestBuilder.similarityThreshold(similarityThreshold);
            logger.debug("Applied similarity threshold: {}", similarityThreshold);
        } else {
            logger.trace("No valid similarity threshold (0.0-1.0 exclusive of 0 unless intended) provided (was {}), using store defaults for query: {}", similarityThreshold, Squery);
            // If threshold is not applied, it will use the VectorStore's default (often 0.0, meaning accept all).
            // Alternatively, some stores might have a specific "no threshold" value for the builder if needed.
            // For Chroma and Spring AI, passing a SearchRequest without a threshold often means it defaults to returning
            // topK results without a score cutoff.
        }

        SearchRequest searchRequest = requestBuilder.build();

        try {
            List<Document> results = this.springAiChromaVectorStore.similaritySearch(searchRequest);
            logger.info("ChromaVectorStore similarity search returned {} document(s) for query: [{}]",
                    results.size(), Squery);
            return results;
        } catch (Exception e) {
            logger.error("Error during similarity search in Chroma for query [{}]: {}", Squery, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}