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

package ai.kompile.core.kgembedding;

import java.util.function.Consumer;

/**
 * Configuration for knowledge graph embedding training.
 *
 * @param embeddingDim Dimension of entity and relation embeddings
 * @param epochs Number of training epochs
 * @param learningRate Learning rate for gradient descent
 * @param batchSize Number of triples per training batch
 * @param margin Margin for margin-based ranking loss
 * @param negativeSamples Number of negative samples per positive triple
 * @param normalizeEntities Whether to L2-normalize entity embeddings after each epoch
 * @param progressCallback Optional callback for training progress updates
 */
public record KGEmbeddingConfig(
        int embeddingDim,
        int epochs,
        double learningRate,
        int batchSize,
        double margin,
        int negativeSamples,
        boolean normalizeEntities,
        Consumer<TrainingProgress> progressCallback
) {
    /**
     * Default configuration for TransE.
     */
    public static final KGEmbeddingConfig TRANSE_DEFAULTS = new KGEmbeddingConfig(
            100,    // embeddingDim
            100,    // epochs
            0.01,   // learningRate
            1024,   // batchSize
            1.0,    // margin
            10,     // negativeSamples
            true,   // normalizeEntities
            null    // progressCallback
    );

    /**
     * Default configuration for RotatE.
     */
    public static final KGEmbeddingConfig ROTATE_DEFAULTS = new KGEmbeddingConfig(
            100,    // embeddingDim (will be split into real/imag)
            100,    // epochs
            0.001,  // learningRate (lower for RotatE)
            512,    // batchSize
            6.0,    // margin (higher for RotatE)
            256,    // negativeSamples (more for RotatE)
            false,  // normalizeEntities (RotatE uses modulus constraint)
            null    // progressCallback
    );

    /**
     * Creates a config with default values for the specified algorithm.
     */
    public static KGEmbeddingConfig defaultsFor(KGEmbeddingAlgorithm algorithm) {
        return switch (algorithm) {
            case TRANSE -> TRANSE_DEFAULTS;
            case ROTATE -> ROTATE_DEFAULTS;
        };
    }

    /**
     * Returns a builder starting from this config.
     */
    public Builder toBuilder() {
        return new Builder()
                .embeddingDim(embeddingDim)
                .epochs(epochs)
                .learningRate(learningRate)
                .batchSize(batchSize)
                .margin(margin)
                .negativeSamples(negativeSamples)
                .normalizeEntities(normalizeEntities)
                .progressCallback(progressCallback);
    }

    /**
     * Returns a new config with the specified progress callback.
     */
    public KGEmbeddingConfig withProgressCallback(Consumer<TrainingProgress> callback) {
        return new KGEmbeddingConfig(
                embeddingDim, epochs, learningRate, batchSize,
                margin, negativeSamples, normalizeEntities, callback
        );
    }

    /**
     * Builder for KGEmbeddingConfig.
     */
    public static class Builder {
        private int embeddingDim = 100;
        private int epochs = 100;
        private double learningRate = 0.01;
        private int batchSize = 1024;
        private double margin = 1.0;
        private int negativeSamples = 10;
        private boolean normalizeEntities = true;
        private Consumer<TrainingProgress> progressCallback;

        public Builder embeddingDim(int embeddingDim) {
            this.embeddingDim = embeddingDim;
            return this;
        }

        public Builder epochs(int epochs) {
            this.epochs = epochs;
            return this;
        }

        public Builder learningRate(double learningRate) {
            this.learningRate = learningRate;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder margin(double margin) {
            this.margin = margin;
            return this;
        }

        public Builder negativeSamples(int negativeSamples) {
            this.negativeSamples = negativeSamples;
            return this;
        }

        public Builder normalizeEntities(boolean normalizeEntities) {
            this.normalizeEntities = normalizeEntities;
            return this;
        }

        public Builder progressCallback(Consumer<TrainingProgress> progressCallback) {
            this.progressCallback = progressCallback;
            return this;
        }

        public KGEmbeddingConfig build() {
            return new KGEmbeddingConfig(
                    embeddingDim, epochs, learningRate, batchSize,
                    margin, negativeSamples, normalizeEntities, progressCallback
            );
        }
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}
