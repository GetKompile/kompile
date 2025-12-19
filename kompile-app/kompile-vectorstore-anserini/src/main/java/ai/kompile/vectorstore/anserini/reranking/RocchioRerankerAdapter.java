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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Rocchio reranker adapter implementing vector space query expansion.
 * <p>
 * The Rocchio algorithm modifies the query vector by:
 * - Moving it towards the centroid of relevant documents
 * - Optionally moving away from non-relevant documents
 * <p>
 * Formula: q_new = alpha * q_original + beta * mean(relevant) - gamma * mean(non-relevant)
 */
public class RocchioRerankerAdapter implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(RocchioRerankerAdapter.class);

    private final int fbDocs;
    private final int fbTerms;
    private final float alpha;    // Weight for original query
    private final float beta;     // Weight for positive feedback (relevant docs)
    private final float gamma;    // Weight for negative feedback (non-relevant docs)
    private final boolean useNegative;
    private final boolean outputQuery;

    public RocchioRerankerAdapter(RerankerConfig config) {
        this.fbDocs = config.getFbDocs();
        this.fbTerms = config.getFbTerms();
        this.alpha = config.getAlpha();
        this.beta = config.getBeta();
        this.gamma = config.getGamma();
        this.useNegative = config.isUseNegative();
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

        // Build original query vector
        Map<String, Float> queryVector = buildTermVector(queryTokens);

        // Get positive feedback (top-k documents)
        int numPositive = Math.min(fbDocs, documents.size());
        List<ScoredDocument> positiveDocs = documents.subList(0, numPositive);

        // Compute centroid of positive documents
        Map<String, Float> positiveCentroid = computeCentroid(positiveDocs);

        // Optionally compute centroid of negative documents (bottom-k)
        Map<String, Float> negativeCentroid = new HashMap<>();
        if (useNegative && documents.size() > numPositive) {
            int numNegative = Math.min(fbDocs, documents.size() - numPositive);
            int startIdx = documents.size() - numNegative;
            List<ScoredDocument> negativeDocs = documents.subList(startIdx, documents.size());
            negativeCentroid = computeCentroid(negativeDocs);
        }

        // Apply Rocchio formula: q_new = alpha * q + beta * pos_centroid - gamma * neg_centroid
        Map<String, Float> expandedQuery = new HashMap<>();

        // Add weighted original query
        for (Map.Entry<String, Float> entry : queryVector.entrySet()) {
            expandedQuery.merge(entry.getKey(), alpha * entry.getValue(), Float::sum);
        }

        // Add weighted positive centroid
        for (Map.Entry<String, Float> entry : positiveCentroid.entrySet()) {
            expandedQuery.merge(entry.getKey(), beta * entry.getValue(), Float::sum);
        }

        // Subtract weighted negative centroid
        for (Map.Entry<String, Float> entry : negativeCentroid.entrySet()) {
            expandedQuery.merge(entry.getKey(), -gamma * entry.getValue(), Float::sum);
        }

        // Remove terms with negative or zero weights
        expandedQuery.entrySet().removeIf(e -> e.getValue() <= 0);

        // Prune to top fbTerms
        if (expandedQuery.size() > fbTerms) {
            expandedQuery = expandedQuery.entrySet().stream()
                    .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                    .limit(fbTerms)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        // Normalize
        float norm = (float) Math.sqrt(expandedQuery.values().stream()
                .mapToDouble(v -> v * v)
                .sum());
        if (norm > 0) {
            for (String key : expandedQuery.keySet()) {
                expandedQuery.compute(key, (k, v) -> v / norm);
            }
        }

        if (outputQuery) {
            log.info("Original query vector: {}", queryVector);
            log.info("Rocchio expanded query: {}", expandedQuery);
        }

        // Re-score documents using cosine similarity with expanded query
        List<ScoredDocument> reranked = new ArrayList<>();
        for (ScoredDocument doc : documents) {
            double newScore = cosineSimilarity(doc, expandedQuery);
            reranked.add(new ScoredDocument(doc.document(), newScore));
        }

        // Sort by new scores
        reranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        log.debug("Rocchio reranking complete: {} documents", reranked.size());
        return reranked;
    }

    private Map<String, Float> buildTermVector(List<String> tokens) {
        Map<String, Float> vector = new HashMap<>();
        for (String token : tokens) {
            vector.merge(token.toLowerCase(), 1.0f, Float::sum);
        }
        // Normalize
        float norm = (float) Math.sqrt(vector.values().stream()
                .mapToDouble(v -> v * v)
                .sum());
        if (norm > 0) {
            for (String key : vector.keySet()) {
                vector.compute(key, (k, v) -> v / norm);
            }
        }
        return vector;
    }

    private Map<String, Float> computeCentroid(List<ScoredDocument> documents) {
        Map<String, Float> centroid = new HashMap<>();
        int count = 0;

        for (ScoredDocument doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            List<String> tokens = tokenize(text);
            Map<String, Float> docVector = buildTermVector(tokens);

            for (Map.Entry<String, Float> entry : docVector.entrySet()) {
                centroid.merge(entry.getKey(), entry.getValue(), Float::sum);
            }
            count++;
        }

        // Average
        if (count > 0) {
            int finalCount = count;
            centroid.replaceAll((k, v) -> v / finalCount);
        }

        return centroid;
    }

    private double cosineSimilarity(ScoredDocument doc, Map<String, Float> queryVector) {
        String text = doc.getText();
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        List<String> tokens = tokenize(text);
        Map<String, Float> docVector = buildTermVector(tokens);

        // Compute dot product
        double dotProduct = 0.0;
        for (Map.Entry<String, Float> entry : queryVector.entrySet()) {
            Float docWeight = docVector.get(entry.getKey());
            if (docWeight != null) {
                dotProduct += entry.getValue() * docWeight;
            }
        }

        // Vectors are already normalized, so dot product = cosine similarity
        return dotProduct;
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
        return String.format("Rocchio(alpha=%.2f,beta=%.2f,gamma=%.2f,useNeg=%s)",
                alpha, beta, gamma, useNegative);
    }

    @Override
    public RerankerType getType() {
        return RerankerType.ROCCHIO;
    }
}
