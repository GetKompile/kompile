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
import java.util.Comparator;
import java.util.List;

/**
 * Normalize reranker adapter implementing min-max score normalization.
 * <p>
 * This reranker rescales all document scores to the [0, 1] range using
 * min-max normalization:
 * <pre>
 *   normalized_score = (score - min_score) / (max_score - min_score)
 * </pre>
 * <p>
 * Score normalization is useful when:
 * <ul>
 *   <li>Combining results from different retrieval systems with different score scales</li>
 *   <li>Making scores more interpretable (as relative relevance within the result set)</li>
 *   <li>Preparing scores for downstream fusion methods</li>
 * </ul>
 * <p>
 * Note: This does not change the relative ordering of documents, only their scores.
 */
public class NormalizeRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(NormalizeRerankerAdapter.class);

    public NormalizeRerankerAdapter(RerankerConfig config) {
        // No specific configuration needed for min-max normalization
    }

    public NormalizeRerankerAdapter() {
        // Default constructor
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        if (documents.size() == 1) {
            // Single document gets max score of 1.0
            ScoredDocument doc = documents.get(0);
            return List.of(new ScoredDocument(doc.document(), 1.0));
        }

        // Find min and max scores
        double minScore = Double.MAX_VALUE;
        double maxScore = Double.MIN_VALUE;

        for (ScoredDocument doc : documents) {
            double score = doc.score();
            if (score < minScore) minScore = score;
            if (score > maxScore) maxScore = score;
        }

        // Avoid division by zero if all scores are equal
        double range = maxScore - minScore;
        if (range == 0) {
            // All documents have equal score - give them all 1.0
            List<ScoredDocument> normalized = new ArrayList<>();
            for (ScoredDocument doc : documents) {
                normalized.add(new ScoredDocument(doc.document(), 1.0));
            }
            return normalized;
        }

        // Apply min-max normalization
        List<ScoredDocument> normalized = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            double normalizedScore = (doc.score() - minScore) / range;
            normalized.add(new ScoredDocument(doc.document(), normalizedScore));
        }

        // Sort by normalized score (descending)
        normalized.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        log.debug("Normalize reranking complete: {} documents, score range [{}, {}] -> [0, 1]",
                normalized.size(), minScore, maxScore);
        return normalized;
    }

    @Override
    public String tag() {
        return "Normalize(min-max)";
    }

    @Override
    public RerankerType getType() {
        return RerankerType.NORMALIZE;
    }
}
