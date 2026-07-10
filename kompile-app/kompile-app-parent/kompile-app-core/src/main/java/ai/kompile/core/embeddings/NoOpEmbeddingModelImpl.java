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

import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Unavailable EmbeddingModel implementation used only to make missing embedding configuration
 * fail explicitly when a caller tries to use embeddings.
 */
@Service
@ConditionalOnMissingBean(value = EmbeddingModel.class, ignored = NoOpEmbeddingModelImpl.class)
public class NoOpEmbeddingModelImpl implements EmbeddingModel {

    private static final Logger logger = LoggerFactory.getLogger(NoOpEmbeddingModelImpl.class);

    public NoOpEmbeddingModelImpl() {
        logger.warn("No EmbeddingModel implementation found. Embedding calls will fail until a real model is configured.");
    }

    /** No-op model cannot embed — callers use this to skip the embedding path entirely (proxy-safe). */
    @Override
    public boolean canEmbed() {
        return false;
    }

    @Override
    public INDArray embed(String text) {
        throw unavailable();
    }

    @Override
    public INDArray embed(List<String> texts) {
        throw unavailable();
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        throw unavailable();
    }

    @Override
    public int dimensions() {
        throw unavailable();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("No real EmbeddingModel is configured");
    }
}