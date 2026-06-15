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
 * Evaluates whether extracted entities have the correct type assigned,
 * compared to a ground truth graph.
 * <p>
 * First matches entities by title (exact or fuzzy), then checks whether each
 * matched pair has the same type. The score is the fraction of matched entities
 * with correct types.
 * <p>
 * This evaluator does not require an LLM.
 */
@Slf4j
@RequiredArgsConstructor
public class EntityTypeAccuracyEvaluator implements GraphEvaluator {

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

        // Match entities by title (ignoring type for matching purposes)
        Set<Integer> matchedExpectedIndices = new HashSet<>();
        List<GraphEvaluationResult.EntityMatch> matches = new ArrayList<>();
        int correctTypes = 0;
        int totalMatched = 0;

        for (Entity ext : extractedEntities) {
            int bestIdx = -1;
            double bestSim = 0.0;

            for (int i = 0; i < expectedEntities.size(); i++) {
                if (matchedExpectedIndices.contains(i)) continue;

                Entity exp = expectedEntities.get(i);
                double sim = EntityPresenceEvaluator.computeSimilarity(
                        ext.getTitle(), exp.getTitle(), fuzzy);
                if (sim > bestSim && (sim >= 1.0 || (fuzzy && sim >= simThreshold))) {
                    bestSim = sim;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                Entity matched = expectedEntities.get(bestIdx);
                matchedExpectedIndices.add(bestIdx);
                totalMatched++;

                boolean typeCorrect = typesMatch(ext.getType(), matched.getType());
                if (typeCorrect) correctTypes++;

                matches.add(GraphEvaluationResult.EntityMatch.builder()
                        .extractedTitle(ext.getTitle())
                        .expectedTitle(matched.getTitle())
                        .extractedType(ext.getType())
                        .expectedType(matched.getType())
                        .matchType(typeCorrect
                                ? GraphEvaluationResult.MatchType.TRUE_POSITIVE
                                : GraphEvaluationResult.MatchType.TYPE_MISMATCH)
                        .similarity(bestSim)
                        .build());
            }
            // Unmatched extracted entities are not relevant for type accuracy
        }

        double accuracy = totalMatched > 0 ? (double) correctTypes / totalMatched : 0.0;

        double threshold = context.getThreshold() != null
                ? context.getThreshold()
                : properties.getEntityTypeAccuracy().getThreshold();

        // Per-type confusion breakdown
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("accuracy", accuracy);
        metrics.put("correct_types", (double) correctTypes);
        metrics.put("total_matched", (double) totalMatched);
        metrics.put("type_mismatches", (double) (totalMatched - correctTypes));

        // Confusion: count how often each extracted type maps to each expected type
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        for (GraphEvaluationResult.EntityMatch m : matches) {
            String extType = m.getExtractedType() != null ? m.getExtractedType().toUpperCase(Locale.ROOT) : "UNKNOWN";
            String expType = m.getExpectedType() != null ? m.getExpectedType().toUpperCase(Locale.ROOT) : "UNKNOWN";
            confusion.computeIfAbsent(extType, k -> new LinkedHashMap<>())
                    .merge(expType, 1, Integer::sum);
        }

        // Add per-type accuracy to metrics
        for (Map.Entry<String, Map<String, Integer>> entry : confusion.entrySet()) {
            String extType = entry.getKey().toLowerCase(Locale.ROOT);
            int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            int correct = entry.getValue().getOrDefault(entry.getKey(), 0);
            metrics.put(extType + ".accuracy", total > 0 ? (double) correct / total : 0.0);
        }

        String explanation = String.format(
                "Entity type accuracy: %d matched entities, %d correct types, %d mismatches. " +
                        "Accuracy=%.3f",
                totalMatched, correctTypes, totalMatched - correctTypes, accuracy);

        return GraphEvaluationResult.builder()
                .evaluatorName(getName())
                .evaluationType(getType())
                .passed(accuracy >= threshold)
                .score(accuracy)
                .precision(accuracy)
                .recall(accuracy)
                .f1(accuracy)
                .truePositives(correctTypes)
                .falsePositives(totalMatched - correctTypes)
                .falseNegatives(0)
                .threshold(threshold)
                .entityMatches(matches)
                .metrics(metrics)
                .explanation(explanation)
                .evaluationTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    @Override
    public String getName() {
        return "entity-type-accuracy";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.ENTITY_TYPE_ACCURACY;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    @Override
    public double getDefaultThreshold() {
        return 0.7;
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
}
