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
import ai.kompile.core.graphrag.model.Relationship;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates relationship extraction quality by comparing extracted relationships
 * against a ground truth graph using precision, recall, and F1 metrics.
 * <p>
 * Relationships are matched by comparing (source title, target title, type) tuples.
 * Entity IDs in the extracted graph are resolved to titles for comparison since
 * IDs are generated and not stable across extractions.
 * <p>
 * This evaluator does not require an LLM.
 */
@Slf4j
@RequiredArgsConstructor
public class RelationshipPresenceEvaluator implements GraphEvaluator {

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

        // Build ID-to-title maps for resolving relationship endpoints
        Map<String, String> extractedIdToTitle = buildIdToTitleMap(extracted.getEntities());
        Map<String, String> expectedIdToTitle = buildIdToTitleMap(
                context.getGroundTruth().getEntities());

        List<ResolvedRelationship> extractedRels = resolveRelationships(
                extracted.getRelationships(), extractedIdToTitle, context.getRelationshipTypeFilter());
        List<ResolvedRelationship> expectedRels = resolveRelationships(
                context.getGroundTruth().getRelationships(), expectedIdToTitle,
                context.getRelationshipTypeFilter());

        boolean fuzzy = context.isFuzzyMatch();
        double simThreshold = context.getSimilarityThreshold();

        Set<Integer> matchedExpectedIndices = new HashSet<>();
        List<GraphEvaluationResult.RelationshipMatch> matches = new ArrayList<>();

        for (ResolvedRelationship ext : extractedRels) {
            int bestIdx = -1;
            double bestScore = 0.0;

            for (int i = 0; i < expectedRels.size(); i++) {
                if (matchedExpectedIndices.contains(i)) continue;

                ResolvedRelationship exp = expectedRels.get(i);
                double score = computeRelationshipSimilarity(ext, exp, fuzzy, simThreshold);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                ResolvedRelationship matched = expectedRels.get(bestIdx);
                matchedExpectedIndices.add(bestIdx);
                matches.add(GraphEvaluationResult.RelationshipMatch.builder()
                        .extractedSource(ext.sourceTitle)
                        .extractedTarget(ext.targetTitle)
                        .extractedType(ext.type)
                        .expectedSource(matched.sourceTitle)
                        .expectedTarget(matched.targetTitle)
                        .expectedType(matched.type)
                        .matchType(GraphEvaluationResult.MatchType.TRUE_POSITIVE)
                        .build());
            } else {
                matches.add(GraphEvaluationResult.RelationshipMatch.builder()
                        .extractedSource(ext.sourceTitle)
                        .extractedTarget(ext.targetTitle)
                        .extractedType(ext.type)
                        .matchType(GraphEvaluationResult.MatchType.FALSE_POSITIVE)
                        .build());
            }
        }

        // False negatives
        for (int i = 0; i < expectedRels.size(); i++) {
            if (!matchedExpectedIndices.contains(i)) {
                ResolvedRelationship exp = expectedRels.get(i);
                matches.add(GraphEvaluationResult.RelationshipMatch.builder()
                        .expectedSource(exp.sourceTitle)
                        .expectedTarget(exp.targetTitle)
                        .expectedType(exp.type)
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
                : properties.getRelationshipPresence().getThreshold();

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1", f1);

        String explanation = String.format(
                "Relationship presence: %d extracted, %d expected. TP=%d, FP=%d, FN=%d. " +
                        "Precision=%.3f, Recall=%.3f, F1=%.3f",
                extractedRels.size(), expectedRels.size(),
                tp, fp, fn, precision, recall, f1);

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
                .relationshipMatches(matches)
                .metrics(metrics)
                .explanation(explanation)
                .evaluationTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    @Override
    public String getName() {
        return "relationship-presence";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.RELATIONSHIP_PRESENCE;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    private double computeRelationshipSimilarity(
            ResolvedRelationship ext, ResolvedRelationship exp,
            boolean fuzzy, double simThreshold) {
        // Type must match (case-insensitive)
        if (!typesMatch(ext.type, exp.type)) return 0.0;

        double srcSim = EntityPresenceEvaluator.computeSimilarity(
                ext.sourceTitle, exp.sourceTitle, fuzzy);
        double tgtSim = EntityPresenceEvaluator.computeSimilarity(
                ext.targetTitle, exp.targetTitle, fuzzy);

        double minSim = fuzzy ? simThreshold : 1.0;

        if (srcSim >= minSim && tgtSim >= minSim) {
            return (srcSim + tgtSim) / 2.0;
        }
        return 0.0;
    }

    private static boolean typesMatch(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static Map<String, String> buildIdToTitleMap(List<Entity> entities) {
        if (entities == null) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (Entity e : entities) {
            if (e.getId() != null && e.getTitle() != null) {
                map.put(e.getId(), e.getTitle());
            }
        }
        return map;
    }

    private static List<ResolvedRelationship> resolveRelationships(
            List<Relationship> relationships, Map<String, String> idToTitle,
            Set<String> typeFilter) {
        if (relationships == null) return Collections.emptyList();

        Set<String> normalizedFilter = (typeFilter != null && !typeFilter.isEmpty())
                ? typeFilter.stream()
                    .map(t -> t.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet())
                : null;

        List<ResolvedRelationship> result = new ArrayList<>();
        for (Relationship r : relationships) {
            if (normalizedFilter != null && r.getType() != null
                    && !normalizedFilter.contains(r.getType().trim().toUpperCase(Locale.ROOT))) {
                continue;
            }

            // Resolve IDs to titles; fall back to ID if no title mapping exists
            String sourceTitle = idToTitle.getOrDefault(r.getSource(), r.getSource());
            String targetTitle = idToTitle.getOrDefault(r.getTarget(), r.getTarget());

            result.add(new ResolvedRelationship(sourceTitle, targetTitle, r.getType()));
        }
        return result;
    }

    private static class ResolvedRelationship {
        final String sourceTitle;
        final String targetTitle;
        final String type;

        ResolvedRelationship(String sourceTitle, String targetTitle, String type) {
            this.sourceTitle = sourceTitle;
            this.targetTitle = targetTitle;
            this.type = type;
        }
    }
}
