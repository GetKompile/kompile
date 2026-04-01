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
 * Represents a scored result from link prediction or triple scoring.
 *
 * <p>Lower scores typically indicate more plausible predictions
 * for distance-based models like TransE and RotatE.
 *
 * @param entity The entity or relation being scored
 * @param entityType Optional type/label of the entity
 * @param score The raw score from the embedding model (lower = more plausible)
 * @param rank The rank position in the prediction results (1 = best)
 */
public record EmbeddingScore(
        String entity,
        String entityType,
        double score,
        int rank
) implements Comparable<EmbeddingScore> {

    /**
     * Creates a score without entity type information.
     */
    public EmbeddingScore(String entity, double score, int rank) {
        this(entity, null, score, rank);
    }

    /**
     * Creates a score with rank 0 (unranked).
     */
    public EmbeddingScore(String entity, double score) {
        this(entity, null, score, 0);
    }

    /**
     * Returns a new EmbeddingScore with the specified rank.
     */
    public EmbeddingScore withRank(int newRank) {
        return new EmbeddingScore(entity, entityType, score, newRank);
    }

    /**
     * Compare by score (ascending - lower scores are better).
     */
    @Override
    public int compareTo(EmbeddingScore other) {
        return Double.compare(this.score, other.score);
    }

    /**
     * Check if this is a better (lower) score than another.
     */
    public boolean isBetterThan(EmbeddingScore other) {
        return this.score < other.score;
    }
}
