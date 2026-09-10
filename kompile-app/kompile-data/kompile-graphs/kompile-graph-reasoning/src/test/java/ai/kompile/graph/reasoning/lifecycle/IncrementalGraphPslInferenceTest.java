/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.psl.AdmmHlMrfInference;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.ScalarHlMrfInference;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalGraphPslInferenceTest {

    @Test
    void hybridReasonerUsesGraphOwnedCacheForUnifiedGraphs() {
        UnifiedGraph graph = twoComponents();
        new HybridReasoner().rank(graph);
        assertEquals(2, graph.meta().get(IncrementalGraphPslInference.META_SOLVE_COUNT));

        new HybridReasoner().rank(graph);
        assertEquals(0, graph.meta().get(IncrementalGraphPslInference.META_SOLVE_COUNT));
        assertEquals(2, graph.meta().get(IncrementalGraphPslInference.META_REUSE_COUNT));
    }

    @Test
    void unchangedComponentsAreReusedAndScoresMatchFullInference() {
        UnifiedGraph graph = twoComponents();

        IncrementalGraphPslInference.Result first = IncrementalGraphPslInference.infer(graph);
        IncrementalGraphPslInference.Result second = IncrementalGraphPslInference.infer(graph);

        assertEquals(2, first.componentCount());
        assertEquals(0, first.reusedComponentCount());
        assertEquals(2, first.solvedComponentCount());
        assertEquals(2, second.reusedComponentCount());
        assertEquals(0, second.solvedComponentCount());
        Map<String, Double> ordinary = fullScores(graph);
        for (String id : ordinary.keySet()) {
            assertEquals(ordinary.get(id), first.scores().get(id), 1.0e-5, id);
        }
        assertEquals(first.scores(), second.scores());
        assertEquals(2, graph.meta().get(IncrementalGraphPslInference.META_REUSE_COUNT));
        assertEquals(0, graph.meta().get(IncrementalGraphPslInference.META_SOLVE_COUNT));
    }

    @Test
    void addRemoveAndUpdateOnlyInvalidateAffectedComponents() {
        UnifiedGraph graph = twoComponents();
        IncrementalGraphPslInference.infer(graph);

        graph.addEntity("e", "T", "E");
        IncrementalGraphPslInference.Result added = IncrementalGraphPslInference.infer(graph);
        assertEquals(3, added.componentCount());
        assertEquals(2, added.reusedComponentCount());
        assertEquals(1, added.solvedComponentCount());
        assertScoresMatchFull(graph, added.scores());

        graph.addRelation(SimpleGraphRelation.directed("r1", "a", "b", "CAUSES", 0.2));
        IncrementalGraphPslInference.Result updated = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, updated.reusedComponentCount());
        assertEquals(1, updated.solvedComponentCount());
        assertScoresMatchFull(graph, updated.scores());

        graph.removeRelationById("r1");
        IncrementalGraphPslInference.Result removed = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, removed.reusedComponentCount()); // c-d and the new isolate e remain unchanged
        assertEquals(2, removed.solvedComponentCount()); // a and b are newly split components
        assertScoresMatchFull(graph, removed.scores());
    }

    @Test
    void splitAndMergeDoNotReuseAStaleComponent() {
        UnifiedGraph graph = twoComponents();
        IncrementalGraphPslInference.infer(graph);

        graph.addRelation(SimpleGraphRelation.directed("join", "b", "c", "CAUSES", 0.8));
        IncrementalGraphPslInference.Result merged = IncrementalGraphPslInference.infer(graph);
        assertEquals(1, merged.componentCount());
        assertEquals(0, merged.reusedComponentCount());
        assertEquals(1, merged.solvedComponentCount());
        assertScoresMatchFull(graph, merged.scores());

        graph.removeRelationById("join");
        IncrementalGraphPslInference.Result split = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, split.componentCount());
        assertEquals(0, split.reusedComponentCount());
        assertEquals(2, split.solvedComponentCount());
        assertScoresMatchFull(graph, split.scores());
    }

    @Test
    void conflictDirectionIdentityAndThresholdSemanticsArePartOfTheComponent() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("a", 0.8));
        graph.addEntity(entity("b", 0.7));
        graph.addRelation(SimpleGraphRelation.directed("identity", "a", "b", "NOT_SAME_AS", 1.0));
        graph.addRelation(SimpleGraphRelation.directed("weak", "a", "b", "CAUSES", 0.04));

        IncrementalGraphPslInference.Result first = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, first.componentCount());
        assertEquals(2, first.solvedComponentCount());

        // Both relations are semantically absent from the default graph-PSL program.
        IncrementalGraphPslInference.Result unchanged = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, unchanged.reusedComponentCount());
        assertEquals(0, unchanged.solvedComponentCount());
        assertScoresMatchFull(graph, unchanged.scores());

        graph.addRelation(SimpleGraphRelation.directed("conflict", "a", "b", "CONTRADICTS", 0.8));
        IncrementalGraphPslInference.Result conflict = IncrementalGraphPslInference.infer(graph);
        assertEquals(1, conflict.componentCount());
        assertEquals(0, conflict.reusedComponentCount());
        assertEquals(1, conflict.solvedComponentCount());
        assertScoresMatchFull(graph, conflict.scores());

        graph.addRelation(SimpleGraphRelation.undirected("symmetric", "a", "b", "SAME_AS", 0.8));
        IncrementalGraphPslInference.Result symmetric = IncrementalGraphPslInference.infer(graph);
        assertEquals(0, symmetric.reusedComponentCount());
        assertEquals(1, symmetric.solvedComponentCount());
        assertScoresMatchFull(graph, symmetric.scores());
    }

    @Test
    void danglingAndPriorInputsInvalidateOnlyTheEndpointThatCanChange() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("a", 0.8));
        graph.addEntity(entity("b", 0.8));
        IncrementalGraphPslInference.Result beforeMutation = IncrementalGraphPslInference.infer(graph);
        double unchangedScore = beforeMutation.scores().get("b");

        // A directed edge to a missing target contributes to a's effective out-degree prior.
        graph.addRelation(SimpleGraphRelation.directed("dangling", "a", "missing", "CAUSES", 0.9));
        IncrementalGraphPslInference.Result dangling = IncrementalGraphPslInference.infer(graph);
        assertEquals(1, dangling.reusedComponentCount());
        assertEquals(1, dangling.solvedComponentCount());
        assertTrue(dangling.scores().get("a") < 1.0);
        assertEquals(unchangedScore, dangling.scores().get("b"));
        assertScoresMatchFull(graph, dangling.scores());
    }

    @Test
    void learnedWeightsInvalidateButMetadataVersionsDoNot() {
        UnifiedGraph graph = twoComponents();
        IncrementalGraphPslInference.infer(graph);
        graph.putArtifactText(UnifiedGraphReasoningLifecycle.PSL_WEIGHTS_ARTIFACT,
                "{\"2: State(X) & Link(X, Y) -> State(Y) ^2\": 3.0}");
        IncrementalGraphPslInference.Result weights = IncrementalGraphPslInference.infer(graph);
        assertEquals(0, weights.reusedComponentCount());
        assertEquals(2, weights.solvedComponentCount());
        assertScoresMatchFull(graph, weights.scores());

        graph.meta("version", "next");
        IncrementalGraphPslInference.Result version = IncrementalGraphPslInference.infer(graph);
        assertEquals(2, version.reusedComponentCount());
        assertEquals(0, version.solvedComponentCount());
        assertScoresMatchFull(graph, version.scores());
    }

    @Test
    void warmCacheDoesNotGroundAnyComponent() {
        UnifiedGraph graph = twoComponents();
        AtomicInteger groundingPasses = new AtomicInteger();

        IncrementalGraphPslInference.Result cold = IncrementalGraphPslInference.infer(
                graph, ignored -> groundingPasses.incrementAndGet());
        assertEquals(2, cold.solvedComponentCount());
        assertEquals(2, groundingPasses.get());

        IncrementalGraphPslInference.Result warm = IncrementalGraphPslInference.infer(
                graph, ignored -> groundingPasses.incrementAndGet());
        assertEquals(2, warm.reusedComponentCount());
        assertEquals(0, warm.solvedComponentCount());
        assertEquals(2, groundingPasses.get());
        assertEquals(0, warm.nonConvergedComponentCount());
        assertEquals("COMPLETED", graph.meta().get(IncrementalGraphPslInference.META_STATUS));
    }

    @Test
    void largerComponentCrossesDefaultSolverThresholdAndMatchesFullInference() {
        UnifiedGraph graph = chainGraph(252);
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        PslProgram program = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                graph, builder.build(graph));
        assertTrue(program.ground().size() >= HlMrfMapInference.DEFAULT_SCALAR_THRESHOLD);

        IncrementalGraphPslInference.Result incremental = IncrementalGraphPslInference.infer(graph);
        assertEquals(1, incremental.componentCount());
        assertScoresMatchFull(graph, incremental.scores());
    }

    @Test
    void independentComponentsCrossWholeProgramAdmmThresholdAndMatchFullInference() {
        UnifiedGraph graph = heterogeneousSmallComponents();

        UnifiedGraph singleComponent = new UnifiedGraph();
        addIndependentComponent(singleComponent, 0, "CAUSES", 0.8, 0.8, 0.8, 1.0);
        GraphPslProgramBuilder componentBuilder = new GraphPslProgramBuilder();
        PslProgram componentProgram = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                singleComponent, componentBuilder.build(singleComponent));
        int componentGroundRules = componentProgram.ground().size();
        assertTrue(componentGroundRules < HlMrfMapInference.DEFAULT_SCALAR_THRESHOLD);
        assertTrue(HlMrfMapInference.chooseSolver(componentGroundRules, componentProgram.atomCount())
                instanceof ScalarHlMrfInference);

        GraphPslProgramBuilder wholeBuilder = new GraphPslProgramBuilder();
        PslProgram wholeProgram = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                graph, wholeBuilder.build(graph));
        int wholeGroundRules = wholeProgram.ground().size();
        assertTrue(wholeGroundRules >= HlMrfMapInference.DEFAULT_SCALAR_THRESHOLD);
        assertTrue(HlMrfMapInference.chooseSolver(wholeGroundRules, wholeProgram.atomCount())
                instanceof AdmmHlMrfInference);

        IncrementalGraphPslInference.Result incremental = IncrementalGraphPslInference.infer(graph);
        assertEquals(100, incremental.componentCount());
        assertEquals(0, incremental.reusedComponentCount());
        assertEquals(100, incremental.solvedComponentCount());
        assertScoresMatchFull(graph, incremental.scores());
        assertTrue(incremental.scores().get("component-0-target")
                > incremental.scores().get("component-1-target"));
    }

    @Test
    void cacheSurvivesUnifiedGraphRestart() throws IOException {
        UnifiedGraph graph = twoComponents();
        IncrementalGraphPslInference.infer(graph);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        graph.save(output);
        UnifiedGraph restored = UnifiedGraph.load(new ByteArrayInputStream(output.toByteArray()));

        IncrementalGraphPslInference.Result afterRestart = IncrementalGraphPslInference.infer(restored);
        assertTrue(afterRestart.cacheLoaded());
        assertEquals(2, afterRestart.reusedComponentCount());
        assertEquals(0, afterRestart.solvedComponentCount());
    }

    @Test
    void malformedCacheIsAColdStartAndIsReplaced() {
        UnifiedGraph graph = twoComponents();
        IncrementalGraphPslInference.infer(graph);
        graph.putArtifact(IncrementalGraphPslInference.CACHE_ARTIFACT, new byte[]{1, 2, 3});

        IncrementalGraphPslInference.Result repaired = IncrementalGraphPslInference.infer(graph);
        assertFalse(repaired.cacheLoaded());
        assertEquals(0, repaired.reusedComponentCount());
        assertEquals(2, repaired.solvedComponentCount());
        assertEquals(2, repaired.cacheEntryCount());
    }

    @Test
    void emptyAndIsolatedGraphsHaveCurrentOnlyCacheEntries() {
        UnifiedGraph empty = new UnifiedGraph();
        IncrementalGraphPslInference.Result noEntities = IncrementalGraphPslInference.infer(empty);
        assertEquals(0, noEntities.componentCount());
        assertEquals(0, noEntities.cacheEntryCount());
        assertFalse(empty.artifacts().isEmpty());

        UnifiedGraph isolates = new UnifiedGraph();
        isolates.addEntity("n10", "T", "10");
        isolates.addEntity("n0", "T", "0");
        IncrementalGraphPslInference.Result first = IncrementalGraphPslInference.infer(isolates);
        assertEquals(2, first.componentCount());
        assertEquals(2, first.solvedComponentCount());
        isolates.removeEntityById("n0");
        IncrementalGraphPslInference.Result removed = IncrementalGraphPslInference.infer(isolates);
        assertEquals(1, removed.cacheEntryCount());
        assertEquals(0, removed.solvedComponentCount());
        assertEquals(1, removed.reusedComponentCount());
    }

    private static UnifiedGraph twoComponents() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("a", 0.8));
        graph.addEntity(entity("b", 0.7));
        graph.addEntity(entity("c", 0.6));
        graph.addEntity(entity("d", 0.5));
        graph.addRelation(SimpleGraphRelation.directed("r1", "a", "b", "CAUSES", 0.8));
        graph.addRelation(SimpleGraphRelation.directed("r2", "c", "d", "CAUSES", 0.7));
        return graph;
    }

    private static UnifiedGraph chainGraph(int entityCount) {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 0; i < entityCount; i++) {
            graph.addEntity(entity("n" + i, 0.8));
            if (i > 0) {
                graph.addRelation(SimpleGraphRelation.directed(
                        "r" + i, "n" + (i - 1), "n" + i, "CAUSES", 0.8));
            }
        }
        return graph;
    }

    private static UnifiedGraph heterogeneousSmallComponents() {
        UnifiedGraph graph = new UnifiedGraph();
        for (int i = 0; i < 100; i++) {
            boolean conflict = (i & 1) == 1;
            double sourceConfidence = i < 2 ? 0.8 : 0.2 + (i % 7) * 0.1;
            double targetConfidence = i < 2 ? 0.8 : 0.3 + (i % 5) * 0.11;
            double edgeWeight = i < 2 ? 0.8 : 0.35 + (i % 5) * 0.1;
            double edgeConfidence = i < 2 ? 1.0 : 0.45 + (i % 4) * 0.12;
            addIndependentComponent(graph, i, conflict ? "CONTRADICTS" : "CAUSES",
                    sourceConfidence, targetConfidence, edgeWeight, edgeConfidence);
        }
        return graph;
    }

    private static void addIndependentComponent(UnifiedGraph graph, int index, String relationType,
                                                double sourceConfidence, double targetConfidence,
                                                double edgeWeight, double edgeConfidence) {
        String source = "component-" + index + "-source";
        String target = "component-" + index + "-target";
        graph.addEntity(entity(source, sourceConfidence));
        graph.addEntity(entity(target, targetConfidence));
        graph.addRelation(new SimpleGraphRelation("component-edge-" + index, source, target,
                relationType, edgeWeight, edgeConfidence, true, Set.of(), null, null, Map.of()));
    }

    private static GraphEntity entity(String id, double confidence) {
        return new SimpleGraphEntity(id, "T", id, 1.0, confidence, Set.of(), null, null, Map.of());
    }

    private static Map<String, Double> fullScores(UnifiedGraph graph) {
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        PslProgram program = UnifiedGraphReasoningLifecycle.applyLearnedPslWeights(
                graph, builder.build(graph));
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : builder.entityIdToConstant().entrySet()) {
            scores.put(entry.getKey(), result.values().getOrDefault(
                    GraphPslProgramBuilder.STATE + "(" + entry.getValue() + ")", 0.0));
        }
        return scores;
    }

    private static void assertScoresMatchFull(UnifiedGraph graph, Map<String, Double> actual) {
        Map<String, Double> ordinary = fullScores(graph);
        assertEquals(ordinary.keySet(), actual.keySet());
        for (String id : ordinary.keySet()) {
            assertEquals(ordinary.get(id), actual.get(id), 1.0e-5, id);
        }
    }
}
