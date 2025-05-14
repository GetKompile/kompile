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
import java.util.stream.Collectors;

@Service
public class NoOpEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(NoOpEmbeddingModelImpl.class);
    private static final int DEFAULT_DUMMY_DIMENSIONS = 1; // Or 0, or a typical small number

    public NoOpEmbeddingModelImpl() {
        logger.warn("No specific EmbeddingModel implementation found. Initializing NoOpEmbeddingModelImpl. Embeddings will be non-functional (dummy).");
    }

    @Override
    public List<Float> embed(String text) {
        logger.warn("NoOpEmbeddingModel: embed(String) called for text: '{}'. Returning dummy embedding.", text.substring(0, Math.min(text.length(), 30)));
        return Collections.nCopies(dimensions(), 0.0f);
    }

    @Override
    public List<List<Float>> embed(List<String> texts) {
        logger.warn("NoOpEmbeddingModel: embed(List<String>) called for {} texts. Returning dummy embeddings.", texts.size());
        return texts.stream().map(text -> Collections.nCopies(dimensions(), 0.0f)).collect(Collectors.toList());
    }

    @Override
    public List<List<Float>> embedDocuments(List<Document> documents) {
        logger.warn("NoOpEmbeddingModel: embedDocuments called for {} documents. Returning dummy embeddings.", documents.size());
        return documents.stream().map(doc -> Collections.nCopies(dimensions(), 0.0f)).collect(Collectors.toList());
    }

    @Override
    public int dimensions() {
        logger.debug("NoOpEmbeddingModel: dimensions() called, returning default dummy dimension: {}", DEFAULT_DUMMY_DIMENSIONS);
        return DEFAULT_DUMMY_DIMENSIONS;
    }
}