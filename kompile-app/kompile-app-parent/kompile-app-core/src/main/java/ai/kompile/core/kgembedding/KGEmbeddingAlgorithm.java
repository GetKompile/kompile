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

/**
 * Enum representing the available knowledge graph embedding algorithms.
 */
public enum KGEmbeddingAlgorithm {

    /**
     * TransE: Translational embedding model.
     * Models relations as translations in embedding space: h + r ≈ t
     * Simple, efficient, works well for one-to-one relations.
     */
    TRANSE("TransE", "Translational model: h + r ≈ t"),

    /**
     * RotatE: Rotational embedding model.
     * Models relations as rotations in complex space: h ∘ r ≈ t
     * Handles symmetric, antisymmetric, inverse, and composition patterns.
     */
    ROTATE("RotatE", "Rotational model in complex space: h ∘ r ≈ t");

    private final String displayName;
    private final String description;

    KGEmbeddingAlgorithm(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Parse algorithm from string, case-insensitive.
     */
    public static KGEmbeddingAlgorithm fromString(String value) {
        if (value == null) {
            return TRANSE;
        }
        String upper = value.toUpperCase().trim();
        for (KGEmbeddingAlgorithm algo : values()) {
            if (algo.name().equals(upper) || algo.displayName.equalsIgnoreCase(value)) {
                return algo;
            }
        }
        return TRANSE; // Default
    }
}
