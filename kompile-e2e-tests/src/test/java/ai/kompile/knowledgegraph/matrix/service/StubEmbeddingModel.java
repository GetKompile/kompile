/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Stub implementation of EmbeddingModel for testing.
 */
public class StubEmbeddingModel implements EmbeddingModel {

    private int embeddingDimension = 5;
    private INDArray fixedEmbedding;
    private boolean shouldReturnNull = false;

    public StubEmbeddingModel() {
        this.fixedEmbedding = Nd4j.create(new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f});
    }

    public void setFixedEmbedding(INDArray embedding) {
        this.fixedEmbedding = embedding;
        this.embeddingDimension = (int) embedding.length();
    }

    public void setShouldReturnNull(boolean shouldReturnNull) {
        this.shouldReturnNull = shouldReturnNull;
    }

    public void setEmbeddingDimension(int dimension) {
        this.embeddingDimension = dimension;
        this.fixedEmbedding = Nd4j.create(dimension).assign(0.1f);
    }

    @Override
    public INDArray embed(String text) {
        if (shouldReturnNull) {
            return null;
        }
        return fixedEmbedding.dup();
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (shouldReturnNull || texts == null || texts.isEmpty()) {
            return null;
        }
        return Nd4j.vstack(texts.stream().map(t -> fixedEmbedding.dup()).toArray(INDArray[]::new));
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (shouldReturnNull || documents == null || documents.isEmpty()) {
            return null;
        }
        return Nd4j.vstack(documents.stream().map(d -> fixedEmbedding.dup()).toArray(INDArray[]::new));
    }

    @Override
    public int dimensions() {
        return embeddingDimension;
    }

    @Override
    public void close() {
        // No-op for stub
    }
}
