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
package ai.kompile.graph.neo4j.integration;

import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Random;

/**
 * Test implementation of EmbeddingModel for integration tests.
 * Generates deterministic embeddings based on text hash for reproducible tests.
 */
public class TestEmbeddingModel implements EmbeddingModel {

    private final int dimensions;
    private final Random random;

    public TestEmbeddingModel() {
        this(384); // Common embedding dimension
    }

    public TestEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
        this.random = new Random(42); // Fixed seed for reproducibility
    }

    @Override
    public INDArray embed(String text) {
        if (text == null || text.isEmpty()) {
            return Nd4j.zeros(dimensions);
        }

        // Generate deterministic embedding based on text hash
        Random textRandom = new Random(text.hashCode());
        float[] embedding = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = textRandom.nextFloat() * 2 - 1; // Range [-1, 1]
        }

        // Normalize to unit vector
        INDArray arr = Nd4j.create(embedding);
        double norm = arr.norm2Number().doubleValue();
        if (norm > 0) {
            arr.divi(norm);
        }
        return arr;
    }

    @Override
    public INDArray embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        INDArray[] embeddings = texts.stream()
                .map(this::embed)
                .toArray(INDArray[]::new);
        return Nd4j.vstack(embeddings);
    }

    @Override
    public INDArray embedDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        return embed(documents.stream()
                .map(Document::getText)
                .toList());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public void close() {
        // No-op
    }
}
