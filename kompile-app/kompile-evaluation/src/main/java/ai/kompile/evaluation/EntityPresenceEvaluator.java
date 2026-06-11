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

package ai.kompile.evaluation;

import ai.kompile.core.evaluation.*;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates entity extraction quality by comparing extracted entities against
 * a ground truth graph using precision, recall, and F1 metrics.
 * <p>
 * Entities are matched by title (exact or fuzzy). Optionally, type must also match.
 * This evaluator does not require an LLM — it is purely algorithmic.
 */
@Slf4j
@RequiredArgsConstructor
public class EntityPresenceEvaluator implements GraphEvaluator {

    private final EvaluationProperties properties;

    @Override
    public GraphEvaluationResult evaluate(Graph extracted, GraphEvaluationContext context) {
        long startTime = System.currentTimeMillis();

        if (context.getGroundTruth() == null) {
            return GraphEvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(false)
                    .score(0.0)
                    .explanation("No ground truth graph provided")
                    .evaluationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        List<Entity> extractedEntities = filterEntities(
                extracted.getEntities(), context.getEntityTypeFilter());
        List<Entity> expectedEntities = filterEntities(
                context.getGroundTruth().getEntities(), context.getEntityTypeFilter());

        boolean fuzzy = context.isFuzzyMatch();
        double simThreshold = context.getSimilarityThreshold();
        boolean requireType = context.isRequireTypeMatch();

        // Track which expected entities have been matched
        Set<Integer> matchedExpectedIndices = new HashSet<>();
        List<GraphEvaluationResult.EntityMatch> matches = new ArrayList<>();

        // For each extracted entity, find the best matching expected entity
        for (Entity ext : extractedEntities) {
            int bestIdx = -1;
            double bestSim = 0.0;

            for (int i = 0; i < expectedEntities.size(); i++) {
                if (matchedExpectedIndices.contains(i)) continue;

                Entity exp = expectedEntities.get(i);
                double sim = computeSimilarity(ext.getTitle(), exp.getTitle(), fuzzy);

                if (sim > bestSim && (sim >= 1.0 || (fuzzy && sim >= simThreshold))) {
                    if (requireType && !typesMatch(ext.getType(), exp.getType())) {
                        continue;
                    }
                    bestSim = sim;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                Entity matched = expectedEntities.get(bestIdx);
                matchedExpectedIndices.add(bestIdx);

                GraphEvaluationResult.MatchType matchType;
                if (!typesMatch(ext.getType(), matched.getType())) {
                    matchType = GraphEvaluationResult.MatchType.TYPE_MISMATCH;
                } else {
                    matchType = GraphEvaluationResult.MatchType.TRUE_POSITIVE;
                }

                matches.add(GraphEvaluationResult.EntityMatch.builder()
                        .extractedTitle(ext.getTitle())
                        .expectedTitle(matched.getTitle())
                        .extractedType(ext.getType())
                        .expectedType(matched.getType())
                        .matchType(matchType)
                        .similarity(bestSim)
                        .build());
            } else {
                matches.add(GraphEvaluationResult.EntityMatch.builder()
                        .extractedTitle(ext.getTitle())
                        .extractedType(ext.getType())
                        .matchType(GraphEvaluationResult.MatchType.FALSE_POSITIVE)
                        .build());
            }
        }

        // Add false negatives for unmatched expected entities
        for (int i = 0; i < expectedEntities.size(); i++) {
            if (!matchedExpectedIndices.contains(i)) {
                Entity exp = expectedEntities.get(i);
                matches.add(GraphEvaluationResult.EntityMatch.builder()
                        .expectedTitle(exp.getTitle())
                        .expectedType(exp.getType())
                        .matchType(GraphEvaluationResult.MatchType.FALSE_NEGATIVE)
                        .build());
            }
        }

        int tp = (int) matches.stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TRUE_POSITIVE)
                .count();
        int fp = (int) matches.stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.FALSE_POSITIVE)
                .count();
        int fn = (int) matches.stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.FALSE_NEGATIVE)
                .count();

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1 = (precision + recall) > 0 ? 2.0 * precision * recall / (precision + recall) : 0.0;

        double threshold = context.getThreshold() != null
                ? context.getThreshold()
                : properties.getEntityPresence().getThreshold();

        // Per-type breakdown metrics
        Map<String, Double> metrics = computePerTypeMetrics(matches, expectedEntities);
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1", f1);

        int typeMismatches = (int) matches.stream()
                .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TYPE_MISMATCH)
                .count();

        String explanation = String.format(
                "Entity presence: %d extracted, %d expected. TP=%d, FP=%d, FN=%d, type_mismatches=%d. " +
                        "Precision=%.3f, Recall=%.3f, F1=%.3f",
                extractedEntities.size(), expectedEntities.size(),
                tp, fp, fn, typeMismatches, precision, recall, f1);

        return GraphEvaluationResult.builder()
                .evaluatorName(getName())
                .evaluationType(getType())
                .passed(f1 >= threshold)
                .score(f1)
                .precision(precision)
                .recall(recall)
                .f1(f1)
                .truePositives(tp)
                .falsePositives(fp)
                .falseNegatives(fn)
                .threshold(threshold)
                .entityMatches(matches)
                .metrics(metrics)
                .explanation(explanation)
                .evaluationTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    @Override
    public String getName() {
        return "entity-presence";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.ENTITY_PRESENCE;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    /**
     * Compute similarity between two entity titles.
     * For exact matching, returns 1.0 on case-insensitive exact match, 0.0 otherwise.
     * For fuzzy matching, uses normalized Levenshtein distance.
     */
    static double computeSimilarity(String a, String b, boolean fuzzy) {
        if (a == null || b == null) return 0.0;

        String normA = a.trim().toLowerCase(Locale.ROOT);
        String normB = b.trim().toLowerCase(Locale.ROOT);

        if (normA.equals(normB)) return 1.0;
        if (!fuzzy) return 0.0;

        // Normalized Levenshtein similarity
        int maxLen = Math.max(normA.length(), normB.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(normA, normB);
        return 1.0 - ((double) distance / maxLen);
    }

    static int levenshteinDistance(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }

    private static boolean typesMatch(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static List<Entity> filterEntities(List<Entity> entities, Set<String> typeFilter) {
        if (entities == null) return Collections.emptyList();
        if (typeFilter == null || typeFilter.isEmpty()) return entities;
        Set<String> normalizedFilter = typeFilter.stream()
                .map(t -> t.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return entities.stream()
                .filter(e -> e.getType() != null
                        && normalizedFilter.contains(e.getType().trim().toUpperCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private Map<String, Double> computePerTypeMetrics(
            List<GraphEvaluationResult.EntityMatch> matches, List<Entity> expectedEntities) {
        // Gather all types from both extracted and expected
        Set<String> allTypes = new HashSet<>();
        for (GraphEvaluationResult.EntityMatch m : matches) {
            if (m.getExtractedType() != null) allTypes.add(m.getExtractedType().toUpperCase(Locale.ROOT));
            if (m.getExpectedType() != null) allTypes.add(m.getExpectedType().toUpperCase(Locale.ROOT));
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String type : allTypes) {
            long typeTp = matches.stream()
                    .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.TRUE_POSITIVE
                            && type.equalsIgnoreCase(m.getExpectedType()))
                    .count();
            long typeFp = matches.stream()
                    .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.FALSE_POSITIVE
                            && type.equalsIgnoreCase(m.getExtractedType()))
                    .count();
            long typeFn = matches.stream()
                    .filter(m -> m.getMatchType() == GraphEvaluationResult.MatchType.FALSE_NEGATIVE
                            && type.equalsIgnoreCase(m.getExpectedType()))
                    .count();

            double tp = typeTp + typeFp > 0 ? (double) typeTp / (typeTp + typeFp) : 0.0;
            double rc = typeTp + typeFn > 0 ? (double) typeTp / (typeTp + typeFn) : 0.0;
            double f = (tp + rc) > 0 ? 2.0 * tp * rc / (tp + rc) : 0.0;

            String prefix = type.toLowerCase(Locale.ROOT);
            metrics.put(prefix + ".precision", tp);
            metrics.put(prefix + ".recall", rc);
            metrics.put(prefix + ".f1", f);
        }
        return metrics;
    }
}
