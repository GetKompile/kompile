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

package ai.kompile.core.reranking;

/**
 * Enumeration of supported reranker types.
 */
public enum RerankerType {

    /**
     * No reranking - returns documents as-is.
     */
    NONE("none", "No reranking applied"),

    /**
     * RM3 (Relevance Model 3) - Query expansion using pseudo-relevance feedback.
     * Estimates probability distribution over terms from top-k documents.
     */
    RM3("rm3", "RM3 query expansion with pseudo-relevance feedback"),

    /**
     * BM25-PRF - BM25-weighted pseudo-relevance feedback.
     * Uses BM25 weighting for expansion terms.
     */
    BM25_PRF("bm25prf", "BM25 pseudo-relevance feedback"),

    /**
     * Rocchio - Vector space model query expansion.
     * Moves query vector towards relevant documents and away from non-relevant ones.
     */
    ROCCHIO("rocchio", "Rocchio vector space query expansion"),

    /**
     * Axiom - Axiomatic semantic relevance feedback.
     * Uses axiomatic framework for query expansion.
     */
    AXIOM("axiom", "Axiomatic semantic relevance feedback"),

    /**
     * Score ties adjuster - Deterministic tie-breaking for reproducibility.
     */
    SCORE_TIES_ADJUSTER("score_ties", "Deterministic score tie-breaking"),

    /**
     * Cross-encoder reranking using a neural model.
     */
    CROSS_ENCODER("cross_encoder", "Neural cross-encoder reranking"),

    /**
     * RRF (Reciprocal Rank Fusion) - Combines multiple ranked lists.
     * Uses formula: score = 1/(k + rank) where k is typically 60.
     * Excellent for hybrid search combining BM25 and vector results.
     */
    RRF("rrf", "Reciprocal Rank Fusion for hybrid search"),

    /**
     * Normalize - Min-max score normalization.
     * Rescales all scores to [0, 1] range.
     */
    NORMALIZE("normalize", "Min-max score normalization"),

    /**
     * MMR (Maximal Marginal Relevance) - Balances relevance and diversity.
     * Reduces redundancy in search results by penalizing similar documents.
     */
    MMR("mmr", "Maximal Marginal Relevance for diversity");

    private final String id;
    private final String description;

    RerankerType(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get a RerankerType from its string ID.
     *
     * @param id The reranker type ID (case-insensitive)
     * @return The matching RerankerType, or NONE if not found
     */
    public static RerankerType fromId(String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        String normalizedId = id.toLowerCase().trim();
        for (RerankerType type : values()) {
            if (type.id.equals(normalizedId)) {
                return type;
            }
        }
        return NONE;
    }
}
