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

package ai.kompile.staging.registry;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum representing the type of ML model in the RAG pipeline.
 *
 * <h3>Model Type Taxonomy:</h3>
 * <ul>
 *   <li><b>DENSE_ENCODER</b> - Dense bi-encoders for semantic retrieval (BGE, Arctic, CosDPR).
 *       Produce fixed-dimension float vectors for similarity search.</li>
 *   <li><b>SPARSE_ENCODER</b> - Sparse encoders for learned sparse retrieval (SPLADE, UniCOIL).
 *       Produce token-weight maps for inverted index search.</li>
 *   <li><b>CROSS_ENCODER</b> - Neural rerankers that score query-document pairs (MiniLM, TinyBERT).
 *       Take [CLS] query [SEP] document [SEP] as input and output a relevance score.</li>
 * </ul>
 *
 * <p>Note: Cross-encoders ARE rerankers (the neural kind). We don't have a separate "reranker"
 * type since all our rerankers are cross-encoders. Non-neural reranking methods (RRF, MMR, BM25-PRF)
 * are algorithms, not models.</p>
 */
public enum ModelType {
    /**
     * Dense bi-encoder for semantic retrieval.
     * Examples: bge-base-en-v1.5, arctic-embed-l, cosdpr-distil
     */
    DENSE_ENCODER("dense_encoder"),

    /**
     * Sparse encoder for learned sparse retrieval.
     * Examples: splade-pp-ed, splade-pp-sd, unicoil
     */
    SPARSE_ENCODER("sparse_encoder"),

    /**
     * Cross-encoder for neural reranking.
     * Examples: ms-marco-MiniLM-L-6-v2, stsb-TinyBERT-L-4
     */
    CROSS_ENCODER("cross_encoder"),

    /**
     * @deprecated Use DENSE_ENCODER or SPARSE_ENCODER instead.
     * Kept for backward compatibility with existing registries.
     */
    @Deprecated
    ENCODER("encoder");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static ModelType fromValue(String value) {
        if (value == null) {
            return DENSE_ENCODER; // Default
        }
        String lower = value.toLowerCase().trim();

        // Handle legacy "encoder" type - map to dense_encoder
        if ("encoder".equals(lower)) {
            return DENSE_ENCODER;
        }
        // Handle legacy "reranker" type - map to cross_encoder
        if ("reranker".equals(lower)) {
            return CROSS_ENCODER;
        }

        for (ModelType type : values()) {
            if (type.value.equalsIgnoreCase(lower)) {
                return type;
            }
        }
        // Default to dense encoder for unknown types
        return DENSE_ENCODER;
    }

    /**
     * Get the directory name for this model type.
     */
    public String getDirectoryName() {
        switch (this) {
            case DENSE_ENCODER:
            case ENCODER:
                return "encoders";
            case SPARSE_ENCODER:
                return "sparse-encoders";
            case CROSS_ENCODER:
                return "cross-encoders";
            default:
                return value.replace("_", "-") + "s";
        }
    }

    /**
     * Check if this type is for retrieval (vs reranking).
     */
    public boolean isRetrieval() {
        return this == DENSE_ENCODER || this == SPARSE_ENCODER || this == ENCODER;
    }

    /**
     * Check if this type is for reranking.
     */
    public boolean isReranking() {
        return this == CROSS_ENCODER;
    }

    /**
     * Get a human-readable display name.
     */
    public String getDisplayName() {
        switch (this) {
            case DENSE_ENCODER:
                return "Dense Encoder";
            case SPARSE_ENCODER:
                return "Sparse Encoder";
            case CROSS_ENCODER:
                return "Cross-Encoder (Reranker)";
            case ENCODER:
                return "Encoder (Legacy)";
            default:
                return value;
        }
    }
}
