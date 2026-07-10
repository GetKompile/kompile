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
package ai.kompile.graph.reasoning.prior;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.SSBNGenerator;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.psl.AdmmHlMrfInference;
import ai.kompile.graph.reasoning.psl.PslProgram;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the {@link PriorProvider} SPI cascade fires in priority order, returns 0.5
 * only when no richer signal is available, and that all five seam sites (SSBNGenerator × 3,
 * AdmmHlMrfInference × 5) correctly consult the provider.
 */
class PriorProviderCascadeTest {

    // ─── Stub KB — no-op (no context constraints needed for these tests) ──────

    private static final KnowledgeBase EMPTY_KB = new KnowledgeBase() {
        @Override public boolean entityExists(String id)                            { return false; }
        @Override public boolean edgeExists(String s, String t)                     { return false; }
        @Override public boolean edgeExistsOfType(String s, String t, String type) { return false; }
        @Override public Optional<String> getEntityType(String id)                  { return Optional.empty(); }
        @Override public Optional<String> getMetadata(String id, String key)        { return Optional.empty(); }
        @Override public Optional<Double> getEdgeWeight(String s, String t)         { return Optional.empty(); }
        @Override public Set<String> getEntitiesOfType(String type)                 { return Set.of(); }
        @Override public Set<String> getConnectedEntities(String id)                { return Set.of(); }
        @Override public boolean shareProperty(String id1, String id2, String key)  { return false; }
    };

    // ─── DefaultPriorProvider ────────────────────────────────────────────────

    @Test
    void defaultProvider_alwaysReturns05() {
        PriorProvider p = DefaultPriorProvider.INSTANCE;
        assertEquals(0.5, p.priorFor("any.key", PriorContext.EMPTY), 1e-9);
        assertEquals(0.5, p.strengthFor("A", "B", PriorContext.EMPTY), 1e-9);
    }

    // ─── Tier (a): Hard findings ─────────────────────────────────────────────

    @Test
    void cascade_hardFinding_returnsOneOrZero() {
        OpinionStore store = new InMemoryOpinionStore();
        CascadePriorProvider provider = new CascadePriorProvider(store,
                Map.of("isActive(alice)", 1.0, "isActive(bob)", 0.0));

        assertEquals(1.0, provider.priorFor("isActive(alice)", PriorContext.EMPTY), 1e-9,
                "TRUE finding should return 1.0");
        assertEquals(0.0, provider.priorFor("isActive(bob)", PriorContext.EMPTY), 1e-9,
                "FALSE finding should return 0.0");
    }

    // ─── Tier (b): OpinionStore ──────────────────────────────────────────────

    @Test
    void cascade_opinionStore_returnsExpectation_whenNonVacuous() {
        OpinionStore store = new InMemoryOpinionStore();
        Opinion op = Opinion.fromSoftTruth(0.8, 5);
        store.put("revenue(Q1)", op);

        CascadePriorProvider provider = new CascadePriorProvider(store);
        double prior = provider.priorFor("revenue(Q1)", PriorContext.EMPTY);
        assertEquals(op.expectation(), prior, 1e-9,
                "Should return the opinion's expectation for a non-vacuous stored opinion");
    }

    @Test
    void cascade_opinionStore_skipsVacuousOpinion() {
        OpinionStore store = new InMemoryOpinionStore();
        store.put("unknown.key", Opinion.vacuous()); // vacuous → skip

        CascadePriorProvider provider = new CascadePriorProvider(store);
        // No other tier fires → falls through to 0.5
        assertEquals(0.5, provider.priorFor("unknown.key", PriorContext.EMPTY), 1e-9,
                "Vacuous opinion should not block fallback to 0.5");
    }

    // ─── Tier (a) beats (b) ──────────────────────────────────────────────────

    @Test
    void cascade_hardFinding_beatsOpinionStore() {
        OpinionStore store = new InMemoryOpinionStore();
        store.put("signal", Opinion.fromSoftTruth(0.7, 10)); // expectation ~0.7

        CascadePriorProvider provider = new CascadePriorProvider(store,
                Map.of("signal", 1.0)); // hard finding = 1.0 should win

        assertEquals(1.0, provider.priorFor("signal", PriorContext.EMPTY), 1e-9,
                "Hard finding in tier (a) must beat opinion in tier (b)");
    }

    // ─── Tier (c): Embedding geometric prior ─────────────────────────────────

    @Test
    void cascade_embedding_firesWhenEmbeddingPresent() {
        OpinionStore store = new InMemoryOpinionStore(); // empty store

        // Use {3.0, 4.0} → norm = 5.0, score = 5/(5+1) ≈ 0.833 → expectation ≈ 0.733 ≠ 0.5
        double[] emb = {3.0, 4.0};
        PriorContext ctx = PriorContext.builder().embedding(emb).build();

        CascadePriorProvider provider = new CascadePriorProvider(store);
        double prior = provider.priorFor("entity", ctx);

        // Embedding tier should fire and return a value distinguishable from 0.5
        assertNotEquals(0.5, prior, 1e-3,
                "Non-zero embedding (norm=5) should produce a non-trivial prior ≠ 0.5; got " + prior);
        assertTrue(prior > 0.0 && prior <= 1.0,
                "Embedding-derived prior must be in (0, 1]; got " + prior);
    }

    @Test
    void embeddingPrior_zeroVector_returnsNegativeSentinel() {
        double[] zero = {0.0, 0.0};
        assertEquals(-1.0, CascadePriorProvider.embeddingPrior(zero), 1e-9,
                "Zero-magnitude vector should return the skip sentinel");
    }

    @Test
    void embeddingPrior_nullVector_returnsNegativeSentinel() {
        assertEquals(-1.0, CascadePriorProvider.embeddingPrior(null), 1e-9,
                "null vector should return the skip sentinel");
    }

    @Test
    void embeddingPrior_nonZeroVector_returnsPositiveValue() {
        double[] emb = {3.0, 4.0}; // magnitude = 5.0; score = 5/(5+1) ≈ 0.833
        double prior = CascadePriorProvider.embeddingPrior(emb);
        assertTrue(prior > 0.0 && prior <= 1.0,
                "Non-zero embedding prior must be in (0,1]; got " + prior);
    }

    // ─── Tier (c'): WP19 topology prior ──────────────────────────────────────

    @Test
    void topologyPrior_disabledWeight_returnsSkipSentinel() {
        PriorContext ctx = PriorContext.builder().pageRankPercentile(0.9).build();
        assertEquals(-1.0, CascadePriorProvider.topologyPrior(ctx, 0.0), 1e-9,
                "weight 0 must disable the tier (skip sentinel), so lower tiers still fire");
    }

    @Test
    void topologyPrior_noPercentile_returnsSkipSentinel() {
        assertEquals(-1.0, CascadePriorProvider.topologyPrior(PriorContext.EMPTY, 0.5), 1e-9,
                "absent PageRank percentile must skip the tier");
    }

    @Test
    void topologyPrior_fullWeight_returnsRawPercentile() {
        PriorContext hub = PriorContext.builder().pageRankPercentile(0.95).build();
        PriorContext leaf = PriorContext.builder().pageRankPercentile(0.05).build();
        assertEquals(0.95, CascadePriorProvider.topologyPrior(hub, 1.0), 1e-9);
        assertEquals(0.05, CascadePriorProvider.topologyPrior(leaf, 1.0), 1e-9);
    }

    @Test
    void topologyPrior_partialWeight_interpolatesFromUniform() {
        // prior = 0.5 + 0.5*(0.9 - 0.5) = 0.7
        PriorContext ctx = PriorContext.builder().pageRankPercentile(0.9).build();
        assertEquals(0.7, CascadePriorProvider.topologyPrior(ctx, 0.5), 1e-9);
    }

    @Test
    void cascade_topologyFires_whenEnabledAndNoRicherSignal() {
        OpinionStore store = new InMemoryOpinionStore(); // empty → no opinion tier
        // No embedding, no type-freq, no timestamp → topology is the first tier to fire.
        PriorContext ctx = PriorContext.builder().pageRankPercentile(0.8).build();
        CascadePriorProvider provider = new CascadePriorProvider(store, null, 1.0);
        assertEquals(0.8, provider.priorFor("hub.entity", ctx), 1e-9,
                "with topology enabled and no richer signal, the topology tier supplies the prior");
    }

    @Test
    void cascade_opinionBeatsTopology() {
        OpinionStore store = new InMemoryOpinionStore();
        store.put("signal", Opinion.fromSoftTruth(0.7, 10)); // tier (b) is above topology
        PriorContext ctx = PriorContext.builder().pageRankPercentile(0.99).build();
        CascadePriorProvider provider = new CascadePriorProvider(store, null, 1.0);
        assertEquals(Opinion.fromSoftTruth(0.7, 10).expectation(), provider.priorFor("signal", ctx), 1e-9,
                "a held opinion (tier b) must outrank the topology positional prior (tier c')");
    }

    @Test
    void cascade_defaultWeight_leavesTopologyDormant() {
        OpinionStore store = new InMemoryOpinionStore();
        // Default 2-arg constructor → weight 0. Even with a percentile present, topology must not fire;
        // with no other signal the waterfall falls through to uniform 0.5 (backward-compatible).
        PriorContext ctx = PriorContext.builder().pageRankPercentile(0.95).build();
        CascadePriorProvider provider = new CascadePriorProvider(store);
        assertEquals(0.5, provider.priorFor("some.entity", ctx), 1e-9);
    }

    // ─── Tier (d): Type-frequency shrinkage ──────────────────────────────────

    @Test
    void cascade_typeFrequency_laplaceSmoothed() {
        Map<String, Long> freqs = Map.of("PERSON", 10L, "ORG", 90L);
        PriorContext ctx = PriorContext.builder()
                .entityType("PERSON")
                .typeFrequencies(freqs)
                .build();

        // Laplace prior = (10 + 1) / (100 + 2) = 11/102 ≈ 0.1078
        double expected = (10.0 + 1.0) / (100.0 + 2.0);
        assertEquals(expected, CascadePriorProvider.typeFrequencyPrior(ctx), 1e-9);
    }

    @Test
    void cascade_typeFrequency_unknownType_returnsNegativeSentinel() {
        PriorContext ctx = PriorContext.builder()
                .entityType("EVENT")
                .typeFrequencies(Map.of("PERSON", 5L))
                .build();

        assertEquals(-1.0, CascadePriorProvider.typeFrequencyPrior(ctx), 1e-9,
                "Unknown type should return the skip sentinel");
    }

    // ─── Tier (e): Temporal decay ─────────────────────────────────────────────

    @Test
    void cascade_temporal_veryRecentEvent_closeTo1() {
        OpinionStore store = new InMemoryOpinionStore();
        Instant justNow = Instant.now().minusSeconds(1);
        PriorContext ctx = PriorContext.builder()
                .occurredAt(justNow)
                .gamma(PriorContext.DEFAULT_GAMMA)
                .build();

        CascadePriorProvider provider = new CascadePriorProvider(store);
        double prior = provider.priorFor("recent.event", ctx);
        // deltaT ≈ 1s, gamma ≈ ln2/3600; P ≈ 0.5 + 0.5*exp(-tiny) ≈ ~1.0
        assertTrue(prior > 0.99, "Very recent event should have prior close to 1.0; got " + prior);
    }

    @Test
    void cascade_temporal_noTimestamp_fallsToHalfPoint() {
        OpinionStore store = new InMemoryOpinionStore();
        // No timestamps at all → temporal tier returns -1 → falls to uniform 0.5
        CascadePriorProvider provider = new CascadePriorProvider(store);
        assertEquals(0.5, provider.priorFor("timeless.key", PriorContext.EMPTY), 1e-9,
                "No timestamp → no temporal tier → fallback 0.5");
    }

    @Test
    void temporalDecayPrior_noTimestamp_returnsNegativeSentinel() {
        PriorContext ctx = PriorContext.builder().build(); // no timestamps
        assertEquals(-1.0, CascadePriorProvider.temporalDecayPrior(ctx), 1e-9,
                "No timestamp should produce the skip sentinel");
    }

    @Test
    void temporalDecayPrior_oldEvent_convergesToHalf() {
        // Very old event: deltaT very large → P → 0.5
        Instant ancient = Instant.ofEpochSecond(0); // epoch
        PriorContext ctx = PriorContext.builder()
                .occurredAt(ancient)
                .gamma(PriorContext.DEFAULT_GAMMA)
                .build();
        double prior = CascadePriorProvider.temporalDecayPrior(ctx);
        // P = 0.5 + 0.5 * exp(-very_large) ≈ 0.5
        assertTrue(prior < 0.51, "Very old event should have prior close to 0.5; got " + prior);
        assertTrue(prior >= 0.5, "Prior must not go below 0.5");
    }

    // ─── Tier (f): Uniform 0.5 ───────────────────────────────────────────────

    @Test
    void cascade_noSignal_returns05() {
        OpinionStore store = new InMemoryOpinionStore();
        CascadePriorProvider provider = new CascadePriorProvider(store);
        assertEquals(0.5, provider.priorFor("nothing.known", PriorContext.EMPTY), 1e-9,
                "All tiers miss → must return 0.5");
    }

    // ─── SSBNGenerator seam ──────────────────────────────────────────────────

    /**
     * Verifies that SSBNGenerator's root-node fallback consults the priorProvider.
     * Uses a propositional (no entity argument) single-node theory so the grounded
     * variable name equals the RV name — the same approach as {@code SSBNGeneratorTest}.
     */
    @Test
    void ssbnGenerator_rootNode_consultsPriorProvider() {
        // Track which keys were queried
        java.util.concurrent.atomic.AtomicBoolean providerCalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        PriorProvider trackingProvider = new PriorProvider() {
            @Override
            public double priorFor(String rvKey, PriorContext ctx) {
                providerCalled.set(true);
                return 0.8; // distinguishable from the old 0.5
            }
            @Override
            public double strengthFor(String p, String c, PriorContext ctx) { return 0.6; }
        };

        // Propositional single-node theory: root node "X" with no parents
        MFrag frag = new MFrag("MFrag_X");
        frag.addResidentNode(RandomVariable.propositional("X", RandomVariable.NodeRole.RESIDENT));

        MTheory theory = new MTheory("rootTest");
        theory.addMFrag(frag);

        SSBNGenerator gen = new SSBNGenerator(theory, EMPTY_KB).priorProvider(trackingProvider);
        BayesianNetwork net = gen.generate();

        assertTrue(providerCalled.get(),
                "SSBNGenerator should have called priorProvider.priorFor() for the root node");

        BayesianNode nodeX = net.getNode("X");
        assertNotNull(nodeX, "Node X must be present in the SSBN");
        assertTrue(nodeX.isRoot(), "Propositional node X should be a root");

        // Prior Factor for root: values are [P(FALSE), P(TRUE)] → index 1 = P(TRUE) = 0.8
        double pTrue = nodeX.getCpt().getValue(1);
        assertEquals(0.8, pTrue, 1e-9,
                "Root node prior should be 0.8 from custom provider, not the old hard-coded 0.5");
    }

    // ─── AdmmHlMrfInference seam ─────────────────────────────────────────────

    @Test
    void admmInference_priorProviderConsultedForTargetInit() {
        PriorProvider fixedProvider = new PriorProvider() {
            @Override
            public double priorFor(String rvKey, PriorContext ctx) { return 0.7; }
            @Override
            public double strengthFor(String p, String c, PriorContext ctx) { return 0.6; }
        };

        PslProgram program = new PslProgram();
        program.target("revenue", "Q1");
        program.observe("expense", 0.6, "Q1");

        // No rules → ADMM early-exits with the prior-initialized snapshot
        AdmmHlMrfInference solver = new AdmmHlMrfInference().priorProvider(fixedProvider);
        var result = solver.solve(program, List.<ai.kompile.graph.reasoning.psl.GroundRule>of());

        // The target atom should be initialized to 0.7, not 0.5
        String targetKey = result.values().keySet().stream()
                .filter(k -> k.startsWith("revenue")).findFirst().orElse("revenue(Q1)");
        assertEquals(0.7, result.values().get(targetKey), 1e-9,
                "ADMM target init should use priorProvider (0.7), not hard-coded 0.5");
    }

    @Test
    void admmInference_defaultProvider_gives05() {
        PslProgram program = new PslProgram();
        program.target("signal", "X");

        AdmmHlMrfInference solver = new AdmmHlMrfInference(); // default provider → 0.5
        var result = solver.solve(program, List.<ai.kompile.graph.reasoning.psl.GroundRule>of());

        String targetKey = result.values().keySet().stream()
                .filter(k -> k.startsWith("signal")).findFirst().orElse("signal(X)");
        assertEquals(0.5, result.values().get(targetKey), 1e-9,
                "Default provider should preserve the original 0.5 behaviour");
    }

    // ─── MFrag.getEdgeStrength(…, provider, ctx) seam ────────────────────────

    @Test
    void mfrag_getEdgeStrength_withProviderFallback() {
        MFrag frag = new MFrag("test");
        PriorProvider fixedStrength = new PriorProvider() {
            @Override public double priorFor(String k, PriorContext c)           { return 0.9; }
            @Override public double strengthFor(String p, String ch, PriorContext c) { return 0.75; }
        };

        // No edge set in map → provider consulted
        double s = frag.getEdgeStrength("parent", "child", fixedStrength, PriorContext.EMPTY);
        assertEquals(0.75, s, 1e-9, "MFrag.getEdgeStrength should consult provider when edge absent");

        // Explicit edge in map → provider NOT consulted
        frag.setEdgeStrength("parent", "child", 0.4);
        double s2 = frag.getEdgeStrength("parent", "child", fixedStrength, PriorContext.EMPTY);
        assertEquals(0.4, s2, 1e-9, "Explicit edge in map must take precedence over provider");
    }

    @Test
    void mfrag_getEdgeStrength_noOverride_defaultProvider_gives05() {
        MFrag frag = new MFrag("test");
        // No edge, no provider override → backward-compatible 0.5
        assertEquals(0.5, frag.getEdgeStrength("A", "B"), 1e-9,
                "Original single-arg overload should still return 0.5 via DefaultPriorProvider");
    }
}
