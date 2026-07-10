/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HybridConsensusTrainer}. The consensus-signal derivation (the novel logic) is
 * tested deterministically against hand-built rankings; an end-to-end smoke test proves the joint
 * loop runs PSL training against a real hybrid ranking.
 */
class HybridConsensusTrainerTest {

    // ── normalizeScores ──────────────────────────────────────────────────────────────────────

    @Test
    void normalizeScores_minMaxToUnitInterval() {
        Map<String, Double> n = HybridConsensusTrainer.normalizeScores(List.of(
                new ScoredEntity("a", 0.9, 0.9, 0.0),
                new ScoredEntity("b", 0.1, 0.1, 0.0),
                new ScoredEntity("c", 0.5, 0.5, 0.0)));
        assertEquals(1.0, n.get("a"), 1e-9);
        assertEquals(0.0, n.get("b"), 1e-9);
        assertEquals(0.5, n.get("c"), 1e-9);
    }

    @Test
    void normalizeScores_flatRanking_isUniformHalf() {
        Map<String, Double> n = HybridConsensusTrainer.normalizeScores(List.of(
                new ScoredEntity("a", 0.3, 0.3, 0.0),
                new ScoredEntity("b", 0.3, 0.3, 0.0)));
        assertEquals(0.5, n.get("a"), 1e-9);
        assertEquals(0.5, n.get("b"), 1e-9);
    }

    // ── hybridForAtom ────────────────────────────────────────────────────────────────────────

    @Test
    void hybridForAtom_takesMaxOverMentionedEntities() {
        Map<String, Double> byEntity = Map.of("alice", 0.8, "acme", 0.3, "bob", 0.5);
        assertEquals(0.8, HybridConsensusTrainer.hybridForAtom("works_at(alice, acme)", byEntity), 1e-9);
        assertEquals(0.5, HybridConsensusTrainer.hybridForAtom("State(bob)", byEntity), 1e-9);
        // Unknown entity / no parens → null (target keeps its observed value)
        assertEquals(null, HybridConsensusTrainer.hybridForAtom("State(zzz)", byEntity));
    }

    // ── consensusTargets ─────────────────────────────────────────────────────────────────────

    @Test
    void consensusTargets_pullsObservedTowardRanking() {
        Map<String, Double> observed = Map.of("State(alice)", 0.2, "State(bob)", 0.9);
        List<ScoredEntity> ranking = List.of(
                new ScoredEntity("alice", 1.0, 1.0, 0.0),   // normalized 1.0
                new ScoredEntity("bob", 0.0, 0.0, 0.0));    // normalized 0.0
        Map<String, Double> c = HybridConsensusTrainer.consensusTargets(observed, ranking, 0.5);
        // alice: 0.5*0.2 + 0.5*1.0 = 0.6 ; bob: 0.5*0.9 + 0.5*0.0 = 0.45
        assertEquals(0.6, c.get("State(alice)"), 1e-9);
        assertEquals(0.45, c.get("State(bob)"), 1e-9);
    }

    @Test
    void consensusTargets_weightZero_isPureObserved() {
        Map<String, Double> observed = Map.of("State(alice)", 0.2);
        List<ScoredEntity> ranking = List.of(new ScoredEntity("alice", 1.0, 1.0, 0.0));
        assertEquals(observed, HybridConsensusTrainer.consensusTargets(observed, ranking, 0.0));
    }

    @Test
    void consensusTargets_mapOverload_matchesRankingOverload() {
        // The cheap per-cascade path passes precomputed per-entity scores (e.g. aggregated MAP
        // posteriors) directly; it must produce exactly the same blend as ranking the same scores.
        Map<String, Double> observed = Map.of("State(alice)", 0.2, "State(bob)", 0.9);
        List<ScoredEntity> ranking = List.of(
                new ScoredEntity("alice", 1.0, 1.0, 0.0),
                new ScoredEntity("bob", 0.0, 0.0, 0.0));
        Map<String, Double> entityScores = Map.of("alice", 1.0, "bob", 0.0);
        assertEquals(
                HybridConsensusTrainer.consensusTargets(observed, ranking, 0.5),
                HybridConsensusTrainer.consensusTargets(observed, entityScores, 0.5));
    }

    @Test
    void consensusTargets_resolvesPslConstantsToGraphEntities() {
        Map<String, Double> observed = Map.of("State(n0)", 0.2, "State(n1)", 0.9);
        List<ScoredEntity> ranking = List.of(
                new ScoredEntity("alice", 1.0, 0.0, 1.0),
                new ScoredEntity("bob", 0.0, 0.0, 0.0));

        Map<String, Double> consensus = HybridConsensusTrainer.consensusTargets(
                observed, ranking, 0.5, Map.of("n0", "alice", "n1", "bob"));

        assertEquals(0.6, consensus.get("State(n0)"), 1e-9);
        assertEquals(0.45, consensus.get("State(n1)"), 1e-9);
    }

    @Test
    void normalizeScoreMap_minMaxToUnitInterval() {
        Map<String, Double> n = HybridConsensusTrainer.normalizeScoreMap(Map.of("a", 0.9, "b", 0.1, "c", 0.5));
        assertEquals(1.0, n.get("a"), 1e-9);
        assertEquals(0.0, n.get("b"), 1e-9);
        assertEquals(0.5, n.get("c"), 1e-9);
    }

    @Test
    void contextualConsensusFusesSemanticContextWithPrecomputedStructure() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("a")
                .type("NODE").label("A").embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("b")
                .type("NODE").label("B").embedding(new double[]{0.0, 1.0}).build());

        HybridConsensusTrainer.ContextualConsensus result =
                HybridConsensusTrainer.contextualConsensus(
                        graph,
                        Map.of("State(a)", 1.0, "State(b)", 0.2),
                        Map.of("a", 0.0, "b", 1.0),
                        Map.of(),
                        new HybridReasoner().structuralWeight(0.0).semanticWeight(1.0),
                        1.0);

        assertTrue(result.semanticConsensus());
        assertEquals(2, result.semanticAnchorCount());
        assertEquals(0, result.inferredSemanticAnchorCount());
        assertEquals("a", result.ranking().get(0).entityId());
        assertEquals(1.0, result.targets().get("State(a)"), 1e-9);
        assertEquals(0.0, result.targets().get("State(b)"), 1e-9);
    }

    @Test
    void contextualConsensusResolvesSparseObservedAnchorFromGraph() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("anchor", "NODE", "Anchor");
        graph.addEntity(GraphEntity.builder("source")
                .type("NODE").label("Source").embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("other")
                .type("NODE").label("Other").embedding(new double[]{0.0, 1.0}).build());
        graph.addRelation("link", "anchor", "source", "RELATED", 1.0);

        HybridConsensusTrainer.ContextualConsensus result =
                HybridConsensusTrainer.contextualConsensus(
                        graph, Map.of("State(anchor)", 1.0), Map.of("anchor", 0.5), Map.of(),
                        new HybridReasoner()
                                .structuralWeight(0.0)
                                .semanticWeight(1.0)
                                .semanticResolutionHops(1),
                        0.5);

        assertTrue(result.semanticConsensus());
        assertEquals(1, result.semanticAnchorCount());
        assertEquals(1, result.inferredSemanticAnchorCount());
        assertTrue(result.ranking().stream()
                .filter(score -> score.entityId().equals("anchor"))
                .anyMatch(score -> score.semanticScore() > 0.99));
    }

    // ── end-to-end joint training (PSL against a real hybrid ranking) ────────────────────────

    @Test
    void train_jointlyTrainsPslAgainstHybridRanking() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("alice", "PERSON", "Alice");
        graph.addEntity("bob", "PERSON", "Bob");
        graph.addEntity("carol", "PERSON", "Carol");
        graph.addRelation("e1", "alice", "bob", "KNOWS", 1.0);
        graph.addRelation("e2", "alice", "carol", "KNOWS", 1.0);

        PslProgram program = new PslProgram();
        program.addRule("1.0: State(?X) -> derived_State(?X) ^2");
        program.observe("State", 1.0, "alice");
        program.observe("State", 0.4, "bob");
        program.target("derived_State", "alice");
        program.target("derived_State", "bob");

        Map<String, Double> observed = Map.of("State(alice)", 1.0, "State(bob)", 0.4);

        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                new PslWeightLearningService(), 1,
                false, null, null, null, 0,          // no MEBN
                false, null, null,                   // no embeddings (keep the unit test light)
                new HybridReasoner(), 0.5, 2);       // hybrid reasoner, consensus weight 0.5, 2 rounds

        HybridConsensusTrainer.Result r = HybridConsensusTrainer.train(graph, program, observed, plan);

        assertNotNull(r.trainedProgram());
        assertFalse(r.trainedProgram().rules().isEmpty());
        assertEquals(3, r.ranking().size(), "all 3 entities ranked");
        assertFalse(r.consensusTargets().isEmpty(), "consensus signal derived from the ranking");
        assertEquals(2, r.rounds());
        assertEquals(1, r.modelsTrained(), "only PSL co-trained in this config");
        assertFalse(r.mebnTrained());
        assertFalse(r.embeddingsTrained());
        assertTrue(r.consensusTargets().containsKey("State(alice)"));
    }

    @Test
    void trainUsesObservedEmbeddingContextForSemanticConsensus() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("alice")
                .type("PERSON").label("Alice").embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("bob")
                .type("PERSON").label("Bob").embedding(new double[]{0.0, 1.0}).build());
        graph.addEntity(GraphEntity.builder("unrelated")
                .type("PERSON").label("Unrelated").embedding(new double[]{-1.0, 0.0}).build());
        graph.addRelation("e1", "alice", "bob", "KNOWS", 0.5);

        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                null, 1,
                false, null, null, null, 0,
                false, null, null,
                new HybridReasoner().structuralWeight(0.0).semanticWeight(1.0),
                0.5, 1,
                Map.of("n0", "alice", "n1", "bob"), null);

        HybridConsensusTrainer.Result result = HybridConsensusTrainer.train(
                graph, new PslProgram(), Map.of("State(n0)", 1.0, "State(n1)", 0.2), plan);

        assertTrue(result.semanticConsensus());
        assertEquals(2, result.semanticAnchorCount());
        assertTrue(result.ranking().stream().anyMatch(score -> score.semanticScore() > 0.0));
        assertEquals("alice", result.ranking().get(0).entityId());
    }

    @Test
    void trainWritesLearnedEmbeddingsToUnifiedGraphLayer() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("a", "NODE", "A");
        graph.addEntity("b", "NODE", "B");
        graph.addRelation("ab", "a", "b", "LINK", 1.0);

        ai.kompile.graph.reasoning.embedding.learn.EmbeddingLearner learner = (source, config) ->
                new ai.kompile.graph.reasoning.embedding.learn.EmbeddingTable(
                        source.entities().stream().map(GraphEntity::id).toList(), 4, 7L);
        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                null, 1,
                false, null, null, null, 0,
                true, learner, null,
                new HybridReasoner(), 0.5, 1,
                Map.of("n0", "a", "n1", "b"), null);

        HybridConsensusTrainer.Result result = HybridConsensusTrainer.train(
                graph, new PslProgram(), Map.of("State(n0)", 1.0, "State(n1)", 0.8), plan);

        assertTrue(result.embeddingsTrained());
        assertTrue(result.semanticConsensus());
        assertEquals(HybridConsensusTrainer.LEARNED_EMBEDDING_LAYER, result.embeddingLayer());
        assertEquals(2, graph.vectorLayer(HybridConsensusTrainer.LEARNED_EMBEDDING_LAYER).size());
        assertFalse(graph.entity("a").orElseThrow().hasEmbedding(),
                "named-layer training must preserve the primary embedding field");
    }
}
