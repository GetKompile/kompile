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

package ai.kompile.vectorstore.pgvector; // Or ai.kompile.vectorstore.chroma;

import ai.kompile.core.embeddings.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service("pgVectorStoreImpl") // Or "chromaVectorStoreImpl"
@ConditionalOnProperty(prefix = "spring.ai.vectorstore.pgvector.jdbc-url", name = "url") // Adjust prefix for Chroma if needed
// Example for Chroma: @ConditionalOnProperty(prefix = "spring.ai.vectorstore.chroma.client", name = "host")
public class PgVectorStoreImpl implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorStoreImpl.class);

    private final org.springframework.ai.vectorstore.VectorStore springAiVectorStore;

    @Autowired
    public PgVectorStoreImpl(org.springframework.ai.vectorstore.VectorStore springAiVectorStore) {
        this.springAiVectorStore = springAiVectorStore;
        logger.info("PgVectorStoreImpl (or relevant store) initialized with Spring AI VectorStore: {}",
                springAiVectorStore.getClass().getSimpleName());
    }

    // ... add and delete methods remain the same as the last correct version ...
    @Override
    public void add(List<Document> documents, List<List<Float>> embeddings) {
        logger.warn("add(documents, List<List<Float>> embeddings) called. " +
                "This wrapper will delegate to add(List<Document> documents), " +
                "relying on the underlying Spring AI VectorStore to handle embedding generation " +
                "using its configured EmbeddingModel. The provided pre-computed embeddings will be ignored.");
        if (documents != null && !documents.isEmpty()) {
            add(documents);
        }
    }

    @Override
    public void add(List<Document> documents) {
        if (documents != null && !documents.isEmpty()) {
            logger.debug("Adding {} documents to VectorStore via Spring AI VectorStore.", documents.size());
            this.springAiVectorStore.add(documents);
            logger.info("Successfully submitted {} documents to VectorStore.", documents.size());
        } else {
            logger.debug("No documents provided to add to VectorStore.");
        }
    }

    @Override
    public boolean delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            logger.debug("No IDs provided for deletion from VectorStore.");
            return true;
        }
        logger.debug("Attempting to delete document IDs from VectorStore: {}", ids);
        try {
            this.springAiVectorStore.delete(ids);
            logger.info("Deletion command sent for IDs {} to VectorStore.", ids);
            return true;
        } catch (UnsupportedOperationException e) {
            logger.warn("Deletion is not supported by the underlying VectorStore: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Error during deletion from VectorStore for IDs {}: {}", ids, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        logger.warn("Direct similaritySearch by pre-computed queryEmbedding is not implemented in this wrapper. " +
                "Use similaritySearch(String query, ...). Returning empty list.");
        return Collections.emptyList();
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        return similaritySearch(query, k, 0.0); // Default threshold (effectively none for some stores)
    }

    @Override
    public List<Document> similaritySearch(String query, int k, double similarityThreshold) {
        if (query == null || query.trim().isEmpty()) {
            logger.warn("Similarity search called with empty or null query.");
            return Collections.emptyList();
        }
        String sQuery = query.substring(0, Math.min(query.length(), 50)) + (query.length() > 50 ? "..." : "");

        logger.debug("Performing similarity search for query: [{}], k={}, threshold={}",
                sQuery, k, similarityThreshold);

        // *** CORRECTED SearchRequest construction using builder with direct method names ***
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)   // Direct method name on builder
                .topK(k);       // Direct method name on builder

        if (similarityThreshold > 0.0 && similarityThreshold <= 1.0) {
            // Direct method name on builder, as per Spring AI 1.0.x documentation pattern
            requestBuilder.similarityThreshold(similarityThreshold);
            logger.debug("Applied similarity threshold: {}", similarityThreshold);
        } else {
            logger.trace("No valid similarity threshold (0.0-1.0, exclusive of 0 unless intended) provided (was {}), " +
                    "using store defaults for query: {}", similarityThreshold, sQuery);
        }

        SearchRequest searchRequest = requestBuilder.build();

        try {
            List<Document> results = this.springAiVectorStore.similaritySearch(searchRequest);
            logger.info("VectorStore similarity search returned {} document(s) for query: [{}]",
                    results.size(), sQuery);
            return results;
        } catch (Exception e) {
            logger.error("Error during similarity search for query [{}]: {}", sQuery, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}