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

import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * No-op implementation of EmbeddingModel that serves as a fallback when no real
 * embedding model is configured. This bean is only created when no other EmbeddingModel
 * implementation is available.
 */
@Service
@ConditionalOnMissingBean(value = EmbeddingModel.class, ignored = NoOpEmbeddingModelImpl.class)
public class NoOpEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(NoOpEmbeddingModelImpl.class);
    private static final int DEFAULT_DUMMY_DIMENSIONS = 1; // Or 0, or a typical small number

    public NoOpEmbeddingModelImpl() {
        logger.warn("No specific EmbeddingModel implementation found. Initializing NoOpEmbeddingModelImpl. Embeddings will be non-functional (dummy).");
    }

    @Override
    public INDArray embed(String text) {
        logger.warn("NoOpEmbeddingModel: embed(String) called for text: '{}'. Returning dummy embedding.", text.substring(0, Math.min(text.length(), 30)));
        return Nd4j.empty(DataType.FLOAT);
    }

    @Override
    public INDArray embed(List<String> texts) {
        logger.warn("NoOpEmbeddingModel: embed(List<String>) called for {} texts. Returning dummy embeddings.", texts.size());
        return Nd4j.empty(DataType.FLOAT);
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        logger.warn("NoOpEmbeddingModel: embedDocuments called for {} documents. Returning dummy embeddings.", documents.size());
        return Nd4j.empty(DataType.FLOAT);
    }

    @Override
    public int dimensions() {
        logger.debug("NoOpEmbeddingModel: dimensions() called, returning default dummy dimension: {}", DEFAULT_DUMMY_DIMENSIONS);
        return DEFAULT_DUMMY_DIMENSIONS;
    }
}