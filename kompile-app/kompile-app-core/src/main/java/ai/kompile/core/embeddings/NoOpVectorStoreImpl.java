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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * A no-operation implementation of the VectorStore interface.
 * This bean is created if no other concrete VectorStore implementation is found,
 * allowing the application to start but with vector store functionality disabled.
 */
@Service("noOpVectorStore") // Give it a specific bean name
public class NoOpVectorStoreImpl implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(NoOpVectorStoreImpl.class);

    public NoOpVectorStoreImpl() {
        logger.warn("No specific VectorStore implementation found or configured. " +
                "Initializing NoOpVectorStoreImpl. Vector store functionality will be non-operational.");
    }

    @Override
    public void add(List<Document> documents, List<List<Float>> embeddings) {
        logger.warn("NoOpVectorStore: add(documents, List<List<Float>> embeddings) called but no operation will be performed.");
        // No-op
    }

    @Override
    public void add(List<Document> documents) {
        logger.warn("NoOpVectorStore: add(List<Document> documents) called but no operation will be performed.");
        // No-op
    }

    @Override
    public boolean delete(List<String> ids) {
        logger.warn("NoOpVectorStore: delete called for IDs: {}. No operation will be performed. Returning false.", ids);
        return false; // Or true, depending on how you want to signify a no-op success. False implies nothing was done.
    }

    @Override
    public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
        logger.warn("NoOpVectorStore: similaritySearch by embedding called. Returning empty list.");
        return Collections.emptyList();
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        logger.warn("NoOpVectorStore: similaritySearch by query string called for query: '{}'. Returning empty list.", query);
        return Collections.emptyList();
    }

    @Override
    public List<Document> similaritySearch(String query, int k, double threshold) {
        logger.warn("NoOpVectorStore: similaritySearch by query string with threshold called for query: '{}'. Returning empty list.", query);
        return Collections.emptyList();
    }
}