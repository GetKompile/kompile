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
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Axiom reranker adapter implementing axiomatic semantic relevance feedback.
 * <p>
 * The Axiom approach is based on axiomatic information retrieval theory,
 * which provides a principled framework for query expansion. It uses
 * semantic term similarity and axiomatic constraints to identify
 * good expansion terms from feedback documents.
 * <p>
 * Key features:
 * <ul>
 *   <li>Uses term co-occurrence patterns from top-R documents</li>
 *   <li>Expands query with top-N semantically related terms</li>
 *   <li>Applies axiomatic beta interpolation between original and expanded scores</li>
 *   <li>Supports deterministic mode for reproducible results</li>
 * </ul>
 */
public class AxiomRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(AxiomRerankerAdapter.class);

    private final int r;              // Number of top documents for feedback
    private final int n;              // Number of expansion terms
    private final float axiomBeta;    // Interpolation weight (0 = original only, 1 = expansion only)
    private final boolean deterministic;
    private final long seed;
    private final boolean outputQuery;

    public AxiomRerankerAdapter(RerankerConfig config) {
        this.r = config.getR();
        this.n = config.getN();
        this.axiomBeta = config.getAxiomBeta();
        this.deterministic = config.isDeterministic();
        this.seed = config.getSeed();
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

        // Get feedback documents (top-R by initial score)
        int numFeedbackDocs = Math.min(r, documents.size());
        List<ScoredDocument> feedbackDocs = documents.subList(0, numFeedbackDocs);

        // Compute term co-occurrence matrix from feedback documents
        Map<String, Map<String, Integer>> coOccurrenceMatrix = buildCoOccurrenceMatrix(feedbackDocs);

        // Compute axiomatic term scores based on semantic similarity to query terms
        Map<String, Float> expansionTermScores = computeAxiomExpansionScores(
                coOccurrenceMatrix, queryTermSet, feedbackDocs);

        // Select top-N expansion terms
        Map<String, Float> selectedExpansionTerms = expansionTermScores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (outputQuery) {
            log.info("Original query terms: {}", queryTermSet);
            log.info("Axiom expansion terms (top {}): {}", n, selectedExpansionTerms);
        }

        // Build expanded query with interpolation
        Map<String, Float> expandedQuery = new HashMap<>();

        // Add original query terms with weight (1 - axiomBeta)
        float originalWeight = 1.0f - axiomBeta;
        float perQueryTermWeight = originalWeight / Math.max(1, queryTermSet.size());
        for (String term : queryTermSet) {
            expandedQuery.put(term, perQueryTermWeight);
        }

        // Add expansion terms with weight axiomBeta
        if (!selectedExpansionTerms.isEmpty()) {
            // Normalize expansion term scores
            float maxScore = selectedExpansionTerms.values().stream()
                    .max(Float::compare)
                    .orElse(1.0f);

            for (Map.Entry<String, Float> entry : selectedExpansionTerms.entrySet()) {
                float normalizedScore = entry.getValue() / maxScore;
                float termWeight = axiomBeta * normalizedScore;
                expandedQuery.merge(entry.getKey(), termWeight, Float::sum);
            }
        }

        // Re-score documents based on expanded query
        List<ScoredDocument> reranked = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            double newScore = scoreDocument(doc, expandedQuery);
            reranked.add(new ScoredDocument(doc.document(), newScore));
        }

        // Sort by new scores (descending)
        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        // Handle ties deterministically if requested
        if (deterministic) {
            reranked = breakTiesDeterministically(reranked);
        }

        log.debug("Axiom reranking complete: {} documents reranked with {} expansion terms",
                reranked.size(), selectedExpansionTerms.size());
        return reranked;
    }

    /**
     * Build co-occurrence matrix from feedback documents.
     * Counts how often term pairs appear together in the same document.
     */
    private Map<String, Map<String, Integer>> buildCoOccurrenceMatrix(List<ScoredDocument> documents) {
        Map<String, Map<String, Integer>> matrix = new HashMap<>();

        for (ScoredDocument doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            List<String> tokens = tokenize(text);
            Set<String> uniqueTerms = new HashSet<>(tokens);

            // For each pair of terms in the document, increment co-occurrence count
            for (String term1 : uniqueTerms) {
                for (String term2 : uniqueTerms) {
                    if (!term1.equals(term2)) {
                        matrix.computeIfAbsent(term1, k -> new HashMap<>())
                                .merge(term2, 1, Integer::sum);
                    }
                }
            }
        }

        return matrix;
    }

    /**
     * Compute axiomatic expansion scores for candidate terms.
     * Uses co-occurrence with query terms as a proxy for semantic similarity.
     */
    private Map<String, Float> computeAxiomExpansionScores(
            Map<String, Map<String, Integer>> coOccurrenceMatrix,
            Set<String> queryTerms,
            List<ScoredDocument> feedbackDocs) {

        Map<String, Float> scores = new HashMap<>();

        // Compute document frequency for normalization
        Map<String, Integer> termDocFreq = new HashMap<>();
        for (ScoredDocument doc : feedbackDocs) {
            String text = doc.getText();
            if (text == null) continue;

            Set<String> docTerms = new HashSet<>(tokenize(text));
            for (String term : docTerms) {
                termDocFreq.merge(term, 1, Integer::sum);
            }
        }

        int numDocs = feedbackDocs.size();

        // For each term in the co-occurrence matrix
        for (Map.Entry<String, Map<String, Integer>> entry : coOccurrenceMatrix.entrySet()) {
            String candidateTerm = entry.getKey();

            // Skip if it's already a query term
            if (queryTerms.contains(candidateTerm)) {
                continue;
            }

            // Skip very rare or very common terms
            int df = termDocFreq.getOrDefault(candidateTerm, 0);
            if (df < 1 || df == numDocs) {
                continue;
            }

            Map<String, Integer> coOccurrences = entry.getValue();
            float score = 0.0f;

            // Sum co-occurrence scores with query terms
            for (String queryTerm : queryTerms) {
                int coOccCount = coOccurrences.getOrDefault(queryTerm, 0);
                if (coOccCount > 0) {
                    // Weight by IDF-like factor
                    float idf = (float) Math.log((double) numDocs / df);
                    score += coOccCount * idf;
                }
            }

            if (score > 0) {
                scores.put(candidateTerm, score);
            }
        }

        return scores;
    }

    /**
     * Score a document using the expanded query terms.
     */
    private double scoreDocument(ScoredDocument doc, Map<String, Float> expandedQuery) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        List<String> tokens = tokenize(text);
        Map<String, Integer> docTermFreq = new HashMap<>();
        for (String token : tokens) {
            docTermFreq.merge(token, 1, Integer::sum);
        }

        int docLength = Math.max(1, tokens.size());
        double score = 0.0;

        for (Map.Entry<String, Float> entry : expandedQuery.entrySet()) {
            String term = entry.getKey();
            float queryWeight = entry.getValue();

            int termCount = docTermFreq.getOrDefault(term, 0);
            if (termCount > 0) {
                // TF-based scoring with length normalization
                double tf = (double) termCount / docLength;
                score += queryWeight * tf;
            }
        }

        return score;
    }

    /**
     * Break ties deterministically by using document ID or content hash.
     */
    private List<ScoredDocument> breakTiesDeterministically(List<ScoredDocument> documents) {
        Random random = new Random(seed);

        // Group by score
        Map<Double, List<ScoredDocument>> scoreGroups = documents.stream()
                .collect(Collectors.groupingBy(ScoredDocument::score));

        List<ScoredDocument> result = new ArrayList<>();

        // Process groups in descending score order
        scoreGroups.entrySet().stream()
                .sorted(Map.Entry.<Double, List<ScoredDocument>>comparingByKey().reversed())
                .forEach(entry -> {
                    List<ScoredDocument> group = entry.getValue();
                    if (group.size() > 1) {
                        // Sort by content hash for determinism
                        group.sort(Comparator.comparingInt(d -> {
                            String id = d.document().getMetadata().getOrDefault("docid", "").toString();
                            String text = d.getText();
                            return (id + text).hashCode();
                        }));
                    }
                    result.addAll(group);
                });

        return result;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(s -> !s.isBlank() && s.length() >= 2 && s.length() <= 20)
                .filter(s -> s.matches("[a-z0-9]+"))
                .collect(Collectors.toList());
    }

    @Override
    public String tag() {
        return String.format("Axiom(R=%d,N=%d,beta=%.2f,det=%s)", r, n, axiomBeta, deterministic);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.AXIOM;
    }
}
