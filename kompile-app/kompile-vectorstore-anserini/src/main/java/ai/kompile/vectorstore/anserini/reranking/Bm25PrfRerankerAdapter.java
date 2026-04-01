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
 * BM25-PRF (Pseudo-Relevance Feedback) reranker adapter.
 * <p>
 * This implements a simplified BM25-weighted query expansion approach.
 * Expansion terms are weighted using BM25 formula components.
 */
public class Bm25PrfRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(Bm25PrfRerankerAdapter.class);

    private final int fbDocs;
    private final int fbTerms;
    private final float k1;
    private final float b;
    private final float newTermWeight;
    private final boolean outputQuery;

    public Bm25PrfRerankerAdapter(RerankerConfig config) {
        this.fbDocs = config.getFbDocs();
        this.fbTerms = config.getFbTerms();
        this.k1 = config.getK1();
        this.b = config.getB();
        this.newTermWeight = config.getNewTermWeight();
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

        Set<String> queryTermSet = new HashSet<>(queryTokens.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList()));

        // Get feedback documents
        int numFbDocs = Math.min(fbDocs, documents.size());
        List<ScoredDocument> feedbackDocs = documents.subList(0, numFbDocs);

        // Calculate average document length
        double avgDocLen = feedbackDocs.stream()
                .mapToInt(d -> tokenize(d.getText()).size())
                .average()
                .orElse(100.0);

        // Extract expansion terms with BM25-inspired weighting
        Map<String, Float> expansionTerms = extractExpansionTerms(feedbackDocs, queryTermSet, avgDocLen);

        if (outputQuery) {
            log.info("Original query: {}", queryTokens);
            log.info("BM25-PRF expansion terms: {}", expansionTerms);
        }

        // Build expanded query weights
        Map<String, Float> expandedQuery = new HashMap<>();

        // Original query terms get weight 1.0
        for (String token : queryTokens) {
            expandedQuery.put(token.toLowerCase(), 1.0f);
        }

        // New terms get configured weight
        for (Map.Entry<String, Float> entry : expansionTerms.entrySet()) {
            if (!queryTermSet.contains(entry.getKey())) {
                expandedQuery.put(entry.getKey(), entry.getValue() * newTermWeight);
            }
        }

        // Re-score documents using BM25-style scoring
        List<ScoredDocument> reranked = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            double newScore = scoreBm25(doc, expandedQuery, avgDocLen);
            reranked.add(new ScoredDocument(doc.document(), newScore));
        }

        // Sort by new scores
        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        log.debug("BM25-PRF reranking complete: {} documents", reranked.size());
        return reranked;
    }

    private Map<String, Float> extractExpansionTerms(List<ScoredDocument> feedbackDocs,
                                                      Set<String> queryTerms,
                                                      double avgDocLen) {
        Map<String, Float> termScores = new HashMap<>();
        int numDocs = feedbackDocs.size();

        // Document frequency for each term across feedback docs
        Map<String, Integer> docFreq = new HashMap<>();
        Map<String, Float> totalTf = new HashMap<>();

        for (ScoredDocument doc : feedbackDocs) {
            List<String> tokens = tokenize(doc.getText());
            int docLen = Math.max(1, tokens.size());

            Map<String, Integer> termCounts = new HashMap<>();
            for (String token : tokens) {
                String term = token.toLowerCase();
                if (isValidTerm(term)) {
                    termCounts.merge(term, 1, Integer::sum);
                }
            }

            // Update DF and TF scores
            for (Map.Entry<String, Integer> entry : termCounts.entrySet()) {
                String term = entry.getKey();
                int tf = entry.getValue();

                docFreq.merge(term, 1, Integer::sum);

                // BM25 TF component
                double normalizedLen = docLen / avgDocLen;
                double bm25Tf = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * normalizedLen));
                totalTf.merge(term, (float) bm25Tf, Float::sum);
            }
        }

        // Compute final scores
        for (Map.Entry<String, Float> entry : totalTf.entrySet()) {
            String term = entry.getKey();

            // Skip original query terms
            if (queryTerms.contains(term)) {
                continue;
            }

            int df = docFreq.get(term);
            float avgTf = entry.getValue() / numDocs;

            // Weight by document frequency within feedback set
            float idfLike = (float) Math.log((numDocs + 1.0) / (df + 0.5));
            float score = avgTf * idfLike;

            termScores.put(term, score);
        }

        // Return top fbTerms
        return termScores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(fbTerms)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private double scoreBm25(ScoredDocument doc, Map<String, Float> queryTerms, double avgDocLen) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        List<String> tokens = tokenize(text);
        int docLen = Math.max(1, tokens.size());

        Map<String, Integer> termCounts = new HashMap<>();
        for (String token : tokens) {
            termCounts.merge(token.toLowerCase(), 1, Integer::sum);
        }

        double score = 0.0;
        double normalizedLen = docLen / avgDocLen;

        for (Map.Entry<String, Float> entry : queryTerms.entrySet()) {
            String term = entry.getKey();
            float queryWeight = entry.getValue();

            int tf = termCounts.getOrDefault(term, 0);
            if (tf > 0) {
                // BM25 scoring (simplified, without IDF from collection)
                double bm25Tf = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * normalizedLen));
                score += queryWeight * bm25Tf;
            }
        }

        return score;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(s -> !s.isBlank() && s.length() >= 2)
                .collect(Collectors.toList());
    }

    private boolean isValidTerm(String term) {
        if (term.length() < 2 || term.length() > 20) {
            return false;
        }
        return term.matches("[a-z0-9]+");
    }

    @Override
    public String tag() {
        return String.format("BM25PRF(fbDocs=%d,fbTerms=%d,k1=%.2f,b=%.2f)", fbDocs, fbTerms, k1, b);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.BM25_PRF;
    }
}
