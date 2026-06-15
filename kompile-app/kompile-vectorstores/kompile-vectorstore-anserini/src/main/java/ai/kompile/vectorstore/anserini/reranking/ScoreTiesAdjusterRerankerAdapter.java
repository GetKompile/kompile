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
import ai.kompile.core.reranking.RerankerContext;
import ai.kompile.core.reranking.RerankerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Score ties adjuster reranker for deterministic tie-breaking.
 * <p>
 * This reranker ensures reproducible rankings by adjusting tied scores
 * using document IDs to create a deterministic ordering.
 * <p>
 * Based on Anserini's ScoreTiesAdjusterReranker, this is useful when:
 * - Reproducibility across runs is important
 * - Evaluation metrics need consistent baselines
 */
public class ScoreTiesAdjusterRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ScoreTiesAdjusterRerankerAdapter.class);

    // Precision for score comparison
    private static final double SCORE_PRECISION = 1e-4;
    // Small adjustment for tie-breaking
    private static final double TIE_BREAKER = 1e-6;

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.size() <= 1) {
            return documents;
        }

        List<ScoredDocument> adjusted = new ArrayList<>(documents.size());

        // First, round all scores to consistent precision
        for (ScoredDocument doc : documents) {
            double roundedScore = Math.round(doc.score() / SCORE_PRECISION) * SCORE_PRECISION;
            adjusted.add(new ScoredDocument(doc.document(), roundedScore));
        }

        // Sort by score descending, then by document ID for ties
        adjusted.sort((a, b) -> {
            int scoreCompare = Double.compare(b.score(), a.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            // Use document ID for deterministic tie-breaking
            String idA = a.getId() != null ? a.getId() : "";
            String idB = b.getId() != null ? b.getId() : "";
            return idA.compareTo(idB);
        });

        // Adjust tied scores with tiny decrements to maintain order
        List<ScoredDocument> result = new ArrayList<>(adjusted.size());
        double prevScore = Double.MAX_VALUE;
        int tieCount = 0;

        for (ScoredDocument doc : adjusted) {
            double score = doc.score();

            if (Math.abs(score - prevScore) < SCORE_PRECISION * 0.5) {
                // This is a tie with the previous document
                tieCount++;
                score = prevScore - (TIE_BREAKER * tieCount);
            } else {
                // New score group
                prevScore = score;
                tieCount = 0;
            }

            result.add(new ScoredDocument(doc.document(), score));
        }

        log.debug("Score ties adjusted for {} documents", result.size());
        return result;
    }

    @Override
    public String tag() {
        return "ScoreTiesAdjuster";
    }

    @Override
    public RerankerType getType() {
        return RerankerType.SCORE_TIES_ADJUSTER;
    }
}
