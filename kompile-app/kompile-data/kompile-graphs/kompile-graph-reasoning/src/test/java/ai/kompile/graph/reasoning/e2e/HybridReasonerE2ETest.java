/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.e2e;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.learning.HybridConsensusTrainer;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@link HybridReasoner} — which blends a STRUCTURAL score (PSL/HL-MRF
 * activation or Bayesian posterior over the graph) with a SEMANTIC score (embedding cosine to a
 * query). Because it drives PSL + Bayesian + embeddings together, it doubles as a multi-engine E2E.
 * Also covers {@link HybridConsensusTrainer}'s joint co-training loop.
 */
class HybridReasonerE2ETest {

    private static final double[] QUERY = {0.0, 0.0, 1.0}; // matches n3's embedding exactly

    /** A hub (n1) with a high prior + three leaves; n3's embedding equals the query. */
    private UnifiedGraph fixture() {
        UnifiedGraph g = new UnifiedGraph();
        g.addEntity(new SimpleGraphEntity("n1", "HUB", "Hub", 0.95, 0.95, Set.of(), new double[] {1.0, 0.0, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n2", "LEAF", "n2", 0.4, 0.4, Set.of(), new double[] {0.5, 0.5, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n3", "LEAF", "n3", 0.4, 0.4, Set.of(), new double[] {0.0, 0.0, 1.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("n4", "LEAF", "n4", 0.4, 0.4, Set.of(), new double[] {0.0, 1.0, 0.0}, null, Map.of()));
        g.addRelation("r1", "n1", "n2", "REL", 0.9);
        g.addRelation("r2", "n1", "n3", "REL", 0.9);
        g.addRelation("r3", "n1", "n4", "REL", 0.9);
        return g;
    }

    private Map<String, ScoredEntity> byId(List<ScoredEntity> ranking) {
        Map<String, ScoredEntity> m = new LinkedHashMap<>();
        for (ScoredEntity e : ranking) m.put(e.entityId(), e);
        return m;
    }

    // ── Blend semantics ──────────────────────────────────────────────────────────

    @Test
    void defaultBlendFormulaIsExact() {
        List<ScoredEntity> ranking = new HybridReasoner().rank(fixture(), QUERY); // 0.6 struct / 0.4 sem
        assertEquals(4, ranking.size());
        for (ScoredEntity e : ranking) {
            double expected = 0.6 * e.structuralScore() + 0.4 * e.semanticScore();
            assertEquals(expected, e.score(), 1e-9, "blend = 0.6*struct + 0.4*sem for " + e.entityId());
        }
    }

    @Test
    void pureStructuralRankingIgnoresTheQuery() {
        List<ScoredEntity> ranking = new HybridReasoner().rank(fixture()); // no query → semantic omitted
        for (ScoredEntity e : ranking) {
            assertEquals(0.0, e.semanticScore(), 0.0, "no query → zero semantic component");
            assertEquals(e.structuralScore(), e.score(), 1e-9, "pure structural → score == structural");
        }
    }

    @Test
    void pureSemanticRankingFollowsTheQuery() {
        List<ScoredEntity> ranking = new HybridReasoner()
                .structuralWeight(0.0).semanticWeight(1.0)
                .rank(fixture(), QUERY);
        assertEquals("n3", ranking.get(0).entityId(), "n3's embedding equals the query → ranks first");
        assertEquals(1.0, byId(ranking).get("n3").semanticScore(), 1e-9, "cosine(n3, query) == 1");
    }

    @Test
    void structuralAndSemanticDisagreeOnTheTopEntity() {
        UnifiedGraph g = fixture();
        String structuralTop = new HybridReasoner().rank(g).get(0).entityId();
        String semanticTop = new HybridReasoner().structuralWeight(0.0).semanticWeight(1.0)
                .rank(g, QUERY).get(0).entityId();
        assertEquals("n3", semanticTop);
        assertFalse(structuralTop.equals("n3"),
                "a low-weight leaf must not win the structural ranking; got " + structuralTop);
    }

    // ── Structural engines ───────────────────────────────────────────────────────

    @Test
    void bayesianStructuralEngineProducesAValidRanking() {
        List<ScoredEntity> ranking = new HybridReasoner()
                .structural(HybridReasoner.Structural.BAYESIAN).rank(fixture());
        assertEquals(4, ranking.size());
        assertInRankedOrder(ranking);
        for (ScoredEntity e : ranking) assertUnitInterval(e);
    }

    @Test
    void pslStructuralEngineProducesAValidRanking() {
        List<ScoredEntity> ranking = new HybridReasoner()
                .structural(HybridReasoner.Structural.PSL).rank(fixture(), QUERY);
        assertEquals(4, ranking.size());
        assertInRankedOrder(ranking);
        for (ScoredEntity e : ranking) assertUnitInterval(e);
    }

    // ── Invariants ───────────────────────────────────────────────────────────────

    @Test
    void rankingIsSortedByScoreDescending() {
        assertInRankedOrder(new HybridReasoner().rank(fixture(), QUERY));
    }

    @Test
    void allScoreComponentsAreInTheUnitInterval() {
        for (ScoredEntity e : new HybridReasoner().rank(fixture(), QUERY)) assertUnitInterval(e);
    }

    @Test
    void entityWithoutAnEmbeddingGetsZeroSemanticScore() {
        UnifiedGraph g = fixture();
        g.addEntity("n5", "LEAF", "n5"); // no embedding
        Map<String, ScoredEntity> m = byId(new HybridReasoner().rank(g, QUERY));
        assertEquals(0.0, m.get("n5").semanticScore(), 0.0);
    }

    @Test
    void sparseEntityCanResolveSemanticScoreFromItsNeighborhood() {
        UnifiedGraph g = fixture();
        g.addEntity("n5", "LEAF", "n5");
        g.addRelation("r5", "n5", "n3", "RELATED", 1.0);

        Map<String, ScoredEntity> m = byId(new HybridReasoner()
                .structuralWeight(0.0)
                .semanticWeight(1.0)
                .semanticResolutionHops(1)
                .rank(g, QUERY));

        assertEquals(1.0, m.get("n5").semanticScore(), 1e-9,
                "n5 resolves n3's matching vector through one graph hop");
    }

    @Test
    void suppliedStructuralScoresAreFusedWithoutAnotherStructuralSolve() {
        UnifiedGraph g = fixture();
        Map<String, Double> supplied = Map.of("n1", 0.1, "n2", 0.2, "n3", 0.3, "n4", 0.4);

        List<ScoredEntity> ranking = new HybridReasoner()
                .rankWithStructuralScores(g, supplied, QUERY);

        for (ScoredEntity entity : ranking) {
            assertEquals(supplied.get(entity.entityId()), entity.structuralScore(), 1e-9);
            assertEquals(0.6 * entity.structuralScore() + 0.4 * entity.semanticScore(),
                    entity.score(), 1e-9);
        }
    }

    // ── Round-trip fidelity (capstone pattern) ───────────────────────────────────

    @Test
    void hybridRankingIsIdenticalAfterRoundTrip() throws IOException {
        UnifiedGraph g = fixture();
        List<ScoredEntity> before = new HybridReasoner().rank(g, QUERY);
        List<ScoredEntity> after = new HybridReasoner().rank(roundTrip(g), QUERY);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).entityId(), after.get(i).entityId(), "rank " + i + " id");
            assertEquals(before.get(i).score(), after.get(i).score(), 1e-9, "rank " + i + " score");
        }
    }

    // ── Consensus co-training ────────────────────────────────────────────────────

    @Test
    void consensusTrainerRunsTheHybridLoopAndRanks() {
        UnifiedGraph g = fixture();
        PslProgram program = new GraphPslProgramBuilder().build(g);
        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                null, 1,                          // no PSL learner
                false, null, null, null, 1,       // no MEBN
                false, null, null,                // no embeddings (pure-Java, fast)
                new HybridReasoner(), 0.5, 2);    // reasoner, consensusWeight, 2 rounds

        HybridConsensusTrainer.Result r =
                HybridConsensusTrainer.train(g, program, Map.of("n1", 1.0), plan);

        assertNotNull(r);
        assertEquals(2, r.rounds());
        assertEquals(1, r.modelsTrained(), "reasoner-only plan trains no extra models");
        assertFalse(r.ranking().isEmpty(), "the hybrid ranking is always produced");
        assertTrue(r.consensusTargets().containsKey("n1"), "observed target survives into the consensus");
        assertSame(program, r.trainedProgram(), "PSL skipped → program unchanged");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UnifiedGraph roundTrip(UnifiedGraph g) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        g.save(bos, Dtype.F64);
        return UnifiedGraph.load(new ByteArrayInputStream(bos.toByteArray()));
    }

    private void assertInRankedOrder(List<ScoredEntity> ranking) {
        for (int i = 1; i < ranking.size(); i++) {
            assertTrue(ranking.get(i - 1).score() >= ranking.get(i).score() - 1e-12,
                    "ranking must be sorted descending by score");
        }
    }

    private void assertUnitInterval(ScoredEntity e) {
        assertTrue(e.score() >= 0.0 && e.score() <= 1.0, "score in [0,1]: " + e);
        assertTrue(e.structuralScore() >= 0.0 && e.structuralScore() <= 1.0, "structural in [0,1]: " + e);
        assertTrue(e.semanticScore() >= 0.0 && e.semanticScore() <= 1.0, "semantic in [0,1]: " + e);
    }
}
