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

package ai.kompile.vectorstore.anserini.reranking;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.reranking.Reranker;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.core.reranking.RerankerContext;
import ai.kompile.core.reranking.RerankerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MMR (Maximal Marginal Relevance) reranker adapter for diversity.
 * <p>
 * MMR balances relevance and diversity in search results by penalizing
 * documents that are similar to already-selected documents. The algorithm
 * iteratively selects documents that maximize:
 * <pre>
 *   MMR(d) = λ * Relevance(d) - (1 - λ) * max(Similarity(d, d_selected))
 * </pre>
 * where:
 * <ul>
 *   <li>λ (lambda) = 1.0: Pure relevance (no diversity consideration)</li>
 *   <li>λ (lambda) = 0.0: Pure diversity (only avoid redundancy)</li>
 *   <li>λ (lambda) = 0.5: Balanced (default)</li>
 * </ul>
 * <p>
 * This implementation uses term overlap (Jaccard similarity) to measure
 * document-document similarity, which works well for text documents.
 * <p>
 * Reference: Carbonell, J., & Goldstein, J. (1998). The use of MMR,
 * diversity-based reranking for reordering documents and producing summaries.
 */
public class MmrRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(MmrRerankerAdapter.class);

    private final float lambda;

    public MmrRerankerAdapter(RerankerConfig config) {
        this.lambda = config.getLambda();
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        if (documents.size() == 1) {
            return documents;
        }

        // Normalize original scores to [0, 1] for fair MMR computation
        double maxScore = documents.stream().mapToDouble(ScoredDocument::score).max().orElse(1.0);
        double minScore = documents.stream().mapToDouble(ScoredDocument::score).min().orElse(0.0);
        double scoreRange = maxScore - minScore;
        if (scoreRange == 0) scoreRange = 1.0;

        // Pre-compute term sets for all documents (for similarity calculation)
        List<Set<String>> documentTermSets = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            documentTermSets.add(tokenize(doc.getText()));
        }

        // Track selected documents and remaining candidates
        List<ScoredDocument> selected = new ArrayList<>();
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            remaining.add(i);
        }

        // Iteratively select documents using MMR
        double finalScoreRange = scoreRange;
        while (!remaining.isEmpty()) {
            int bestIdx = -1;
            double bestMmrScore = Double.NEGATIVE_INFINITY;
            int bestOriginalIdx = -1;

            for (int idx : remaining) {
                ScoredDocument candidate = documents.get(idx);

                // Compute normalized relevance score
                double normalizedRelevance = (candidate.score() - minScore) / finalScoreRange;

                // Compute max similarity to already selected documents
                double maxSimilarity = 0.0;
                Set<String> candidateTerms = documentTermSets.get(idx);

                for (ScoredDocument selectedDoc : selected) {
                    int selectedOriginalIdx = findOriginalIndex(documents, selectedDoc);
                    if (selectedOriginalIdx >= 0) {
                        Set<String> selectedTerms = documentTermSets.get(selectedOriginalIdx);
                        double similarity = jaccardSimilarity(candidateTerms, selectedTerms);
                        maxSimilarity = Math.max(maxSimilarity, similarity);
                    }
                }

                // Compute MMR score: λ * relevance - (1 - λ) * max_similarity
                double mmrScore = lambda * normalizedRelevance - (1 - lambda) * maxSimilarity;

                if (mmrScore > bestMmrScore) {
                    bestMmrScore = mmrScore;
                    bestIdx = remaining.indexOf(idx);
                    bestOriginalIdx = idx;
                }
            }

            if (bestIdx >= 0) {
                // Add best document with MMR score
                ScoredDocument bestDoc = documents.get(bestOriginalIdx);
                selected.add(new ScoredDocument(bestDoc.document(), bestMmrScore));
                remaining.remove(bestIdx);
            } else {
                break;
            }
        }

        log.debug("MMR reranking complete: {} documents with lambda={}", selected.size(), lambda);
        return selected;
    }

    private int findOriginalIndex(List<ScoredDocument> documents, ScoredDocument target) {
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).document() == target.document()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compute Jaccard similarity between two term sets.
     * J(A,B) = |A ∩ B| / |A ∪ B|
     */
    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new HashSet<>();
        }
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(s -> !s.isBlank() && s.length() >= 2 && s.length() <= 20)
                .filter(s -> s.matches("[a-z0-9]+"))
                .collect(Collectors.toSet());
    }

    @Override
    public String tag() {
        return String.format("MMR(lambda=%.2f)", lambda);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.MMR;
    }
}
