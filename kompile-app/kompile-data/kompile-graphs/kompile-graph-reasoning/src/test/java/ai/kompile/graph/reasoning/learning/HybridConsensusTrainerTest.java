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
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.psl.PslProgram;
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
    void normalizeScoreMap_minMaxToUnitInterval() {
        Map<String, Double> n = HybridConsensusTrainer.normalizeScoreMap(Map.of("a", 0.9, "b", 0.1, "c", 0.5));
        assertEquals(1.0, n.get("a"), 1e-9);
        assertEquals(0.0, n.get("b"), 1e-9);
        assertEquals(0.5, n.get("c"), 1e-9);
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
}
