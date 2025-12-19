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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RM3 (Relevance Model 3) reranker adapter.
 * <p>
 * This is a simplified implementation of RM3 that works with pre-retrieved documents.
 * It performs query expansion using pseudo-relevance feedback from the top documents,
 * then re-scores documents based on the expanded query terms.
 * <p>
 * The full Anserini RM3 reranker requires Lucene index access for term statistics.
 * This adapter provides an approximation that works with document text directly.
 */
public class Rm3RerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(Rm3RerankerAdapter.class);

    private final int fbDocs;
    private final int fbTerms;
    private final float originalQueryWeight;
    private final boolean filterTerms;
    private final boolean outputQuery;

    public Rm3RerankerAdapter(RerankerConfig config) {
        this.fbDocs = config.getFbDocs();
        this.fbTerms = config.getFbTerms();
        this.originalQueryWeight = config.getOriginalQueryWeight();
        this.filterTerms = config.isFilterTerms();
        this.outputQuery = config.isOutputQuery();
    }

    @Override
    public List<ScoredDocument> rerank(List<ScoredDocument> documents, String query, RerankerContext context) {
        if (documents == null || documents.isEmpty() || query == null || query.isBlank()) {
            return documents;
        }

        // Tokenize query
        List<String> queryTokens = context.getQueryTokens();
        if (queryTokens == null || queryTokens.isEmpty()) {
            queryTokens = tokenize(query);
        }

        // Get feedback documents (top-k by initial score)
        int numFbDocs = Math.min(fbDocs, documents.size());
        List<ScoredDocument> feedbackDocs = documents.subList(0, numFbDocs);

        // Extract and weight feedback terms
        Map<String, Float> termWeights = estimateFeedbackTerms(feedbackDocs, queryTokens);

        if (outputQuery) {
            log.info("Original query tokens: {}", queryTokens);
            log.info("Feedback terms (top {}): {}", fbTerms,
                    termWeights.entrySet().stream()
                            .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                            .limit(10)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        }

        // Create expanded query term set (weighted combination)
        Map<String, Float> expandedQuery = new HashMap<>();

        // Add original query terms with original weight
        float perTermWeight = originalQueryWeight / Math.max(1, queryTokens.size());
        for (String token : queryTokens) {
            expandedQuery.merge(token.toLowerCase(), perTermWeight, Float::sum);
        }

        // Add feedback terms with remaining weight
        float feedbackWeight = 1.0f - originalQueryWeight;
        for (Map.Entry<String, Float> entry : termWeights.entrySet()) {
            expandedQuery.merge(entry.getKey(), entry.getValue() * feedbackWeight, Float::sum);
        }

        // Re-score documents based on expanded query
        List<ScoredDocument> reranked = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            double newScore = scoreDocument(doc, expandedQuery);
            reranked.add(new ScoredDocument(doc.document(), newScore));
        }

        // Sort by new scores (descending)
        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        log.debug("RM3 reranking complete: {} documents reranked", reranked.size());
        return reranked;
    }

    private Map<String, Float> estimateFeedbackTerms(List<ScoredDocument> feedbackDocs, List<String> queryTokens) {
        Map<String, Float> termFreq = new HashMap<>();
        Set<String> queryTermSet = new HashSet<>(queryTokens.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList()));

        float totalScore = 0f;
        for (ScoredDocument doc : feedbackDocs) {
            totalScore += (float) doc.score();
        }

        // Extract terms from feedback documents, weighted by document score
        for (ScoredDocument doc : feedbackDocs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            float docWeight = totalScore > 0 ? (float) doc.score() / totalScore : 1.0f / feedbackDocs.size();
            List<String> tokens = tokenize(text);
            Map<String, Integer> docTermFreq = new HashMap<>();

            for (String token : tokens) {
                String normalizedToken = token.toLowerCase();

                // Apply filters
                if (filterTerms && !isValidTerm(normalizedToken)) {
                    continue;
                }

                // Skip query terms (we'll add them separately)
                if (queryTermSet.contains(normalizedToken)) {
                    continue;
                }

                docTermFreq.merge(normalizedToken, 1, Integer::sum);
            }

            // Normalize by document length and weight by document score
            int docLength = Math.max(1, tokens.size());
            for (Map.Entry<String, Integer> entry : docTermFreq.entrySet()) {
                float tf = (float) entry.getValue() / docLength;
                termFreq.merge(entry.getKey(), tf * docWeight, Float::sum);
            }
        }

        // Prune to top fbTerms
        return termFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(fbTerms)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private double scoreDocument(ScoredDocument doc, Map<String, Float> expandedQuery) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        List<String> tokens = tokenize(text);
        Map<String, Integer> docTermFreq = new HashMap<>();
        for (String token : tokens) {
            docTermFreq.merge(token.toLowerCase(), 1, Integer::sum);
        }

        int docLength = Math.max(1, tokens.size());
        double score = 0.0;

        for (Map.Entry<String, Float> entry : expandedQuery.entrySet()) {
            String term = entry.getKey();
            float queryWeight = entry.getValue();

            int termCount = docTermFreq.getOrDefault(term, 0);
            if (termCount > 0) {
                // Simple TF-based scoring with length normalization
                double tf = (double) termCount / docLength;
                score += queryWeight * tf;
            }
        }

        return score;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // Simple whitespace + punctuation tokenization
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(s -> !s.isBlank() && s.length() >= 2)
                .collect(Collectors.toList());
    }

    private boolean isValidTerm(String term) {
        // Filter short terms and non-alphanumeric
        if (term.length() < 2 || term.length() > 20) {
            return false;
        }
        return term.matches("[a-z0-9]+");
    }

    @Override
    public String tag() {
        return String.format("RM3(fbDocs=%d,fbTerms=%d,origWeight=%.2f)", fbDocs, fbTerms, originalQueryWeight);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.RM3;
    }
}
