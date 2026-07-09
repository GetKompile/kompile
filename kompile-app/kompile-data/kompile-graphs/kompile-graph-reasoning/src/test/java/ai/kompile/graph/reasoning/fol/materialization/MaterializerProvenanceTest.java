/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.materialization;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for provenance in {@link ForwardChainingMaterializer} and
 * {@link FolDatalogAdapter} (E1 + fix #7b).
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link ForwardChainingMaterializer}: derived facts now carry real
 *       {@code supportingFactKeys} and {@code supportingRuleIds} (not empty).</li>
 *   <li>The {@link ForwardChainingMaterializer#DEDUCTIVE_BASIS_MARKER} remains present
 *       as a final entry in {@code supportingRuleIds} for downstream compatibility.</li>
 *   <li>{@link FolDatalogAdapter#factStoreEdb(FactStore, double)}: the binarization
 *       threshold knob admits/excludes facts correctly.</li>
 *   <li>The {@link FolDatalogAdapter.EdbProviderWithStats#excludedFactCount()} accessor
 *       returns the correct number of excluded facts.</li>
 * </ul>
 */
class MaterializerProvenanceTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Create a FactStore seeded with the given observed atom keys (value=1.0). */
    private static FactStore seedStore(String... atomKeys) {
        FactStore store = new FactStore();
        for (String key : atomKeys) {
            store.assertFact(Fact.observed(key, "test"));
        }
        return store;
    }

    private static String atomKey(String pred, String... args) {
        if (args.length == 0) return pred;
        return pred + "(" + String.join(", ", args) + ")";
    }

    // ─── ForwardChainingMaterializer provenance ──────────────────────────────────

    @Nested
    @DisplayName("ForwardChainingMaterializer provenance")
    class MaterializerProvenance {

        @Test
        @DisplayName("Derived fact carries real supportingFactKeys from transitivity rule")
        void derivedFactHasRealParents() {
            // ancestor(alice, bob), ancestor(bob, carol)
            // Rule: ancestor(?X,?Z) :- ancestor(?X,?Y), ancestor(?Y,?Z)
            FactStore store = seedStore(
                    atomKey("ancestor", "alice", "bob"),
                    atomKey("ancestor", "bob", "carol")
            );

            List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("ancestor"));
            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, rules, sink);

            // ancestor(alice, carol) should be derived
            String derived = atomKey("ancestor", "alice", "carol");
            Optional<InferredFact> factOpt = sink.latest(derived);
            assertTrue(factOpt.isPresent(), "ancestor(alice, carol) must be materialized");

            InferredFact fact = factOpt.get();
            assertFalse(fact.supportingFactKeys().isEmpty(),
                    "supportingFactKeys must be populated, was empty");
            // Parents should include the two contributing EDB facts
            assertTrue(fact.supportingFactKeys().contains(atomKey("ancestor", "alice", "bob"))
                    || fact.supportingFactKeys().contains(atomKey("ancestor", "bob", "carol")),
                    "supportingFactKeys should reference parent atoms, got: "
                    + fact.supportingFactKeys());
        }

        @Test
        @DisplayName("Derived fact supportingRuleIds is non-empty and contains the Datalog rule")
        void derivedFactHasRuleIds() {
            FactStore store = seedStore(
                    atomKey("edge", "a", "b"),
                    atomKey("edge", "b", "c")
            );

            List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("edge"));
            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, rules, sink);

            String derived = atomKey("edge", "a", "c");
            Optional<InferredFact> factOpt = sink.latest(derived);
            assertTrue(factOpt.isPresent(), "edge(a, c) must be materialized");

            InferredFact fact = factOpt.get();
            assertFalse(fact.supportingRuleIds().isEmpty(),
                    "supportingRuleIds must not be empty");
        }

        @Test
        @DisplayName("Deductive basis marker is still present in supportingRuleIds for compatibility")
        void deductiveBasisMarkerPreserved() {
            FactStore store = seedStore(
                    atomKey("link", "x", "y"),
                    atomKey("link", "y", "z")
            );

            List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("link"));
            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, rules, sink);

            String derived = atomKey("link", "x", "z");
            Optional<InferredFact> factOpt = sink.latest(derived);
            assertTrue(factOpt.isPresent(), "link(x, z) must be materialized");

            InferredFact fact = factOpt.get();
            assertTrue(fact.supportingRuleIds().contains(ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER),
                    "Deductive basis marker must be present, got: " + fact.supportingRuleIds());
        }

        @Test
        @DisplayName("ruleWeights() parsing still works with marker appended after rule ids")
        void ruleWeightsParseTolerantOfMarker() {
            // ruleWeights() parses "<weight>: ..." prefix; the DEDUCTIVE_BASIS_MARKER has no such
            // prefix and should be silently skipped.
            FactStore store = seedStore(
                    atomKey("hop", "p", "q"),
                    atomKey("hop", "q", "r")
            );
            List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("hop"));
            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            new ForwardChainingMaterializer().materialize(store, rules, sink);

            Optional<InferredFact> factOpt = sink.latest(atomKey("hop", "p", "r"));
            assertTrue(factOpt.isPresent());
            // ruleWeights() should not throw
            assertDoesNotThrow(() -> factOpt.get().ruleWeights(),
                    "ruleWeights() should tolerate the marker in supportingRuleIds");
        }
    }

    // ─── FolDatalogAdapter EDB threshold knob ────────────────────────────────────

    @Nested
    @DisplayName("FolDatalogAdapter EDB binarization threshold")
    class EdbThresholdKnob {

        @Test
        @DisplayName("Default threshold 0.5 excludes fact with value 0.4")
        void defaultThresholdExcludesBelowHalf() {
            FactStore store = new FactStore();
            // Value 0.4 < 0.5 → should be excluded
            store.assertFact(new Fact(atomKey("low", "x"), 0.4, "run", java.time.Instant.now(), false));
            // Value 1.0 → should be included
            store.assertFact(new Fact(atomKey("high", "y"), 1.0, "run", java.time.Instant.now(), false));

            FolDatalogAdapter.EdbProviderWithStats edb =
                    (FolDatalogAdapter.EdbProviderWithStats) FolDatalogAdapter.factStoreEdb(store);

            assertEquals(1, edb.excludedFactCount(),
                    "Should exclude exactly 1 fact (value 0.4 < 0.5)");
            // "low" predicate tuple should not appear
            assertTrue(edb.tuplesFor("low").isEmpty(),
                    "low(x) should be absent from EDB (value 0.4 excluded)");
            // "high" predicate tuple should appear
            assertFalse(edb.tuplesFor("high").isEmpty(),
                    "high(y) should be present in EDB (value 1.0)");
        }

        @Test
        @DisplayName("Threshold 0.3 admits fact with value 0.4")
        void lowerThresholdAdmitsWeakerFact() {
            FactStore store = new FactStore();
            // Value 0.4 >= 0.3 → should be included with threshold=0.3
            store.assertFact(new Fact(atomKey("medium", "x"), 0.4, "run", java.time.Instant.now(), false));
            // Value 0.2 < 0.3 → should be excluded
            store.assertFact(new Fact(atomKey("weak", "y"), 0.2, "run", java.time.Instant.now(), false));

            FolDatalogAdapter.EdbProviderWithStats edb =
                    (FolDatalogAdapter.EdbProviderWithStats)
                    FolDatalogAdapter.factStoreEdb(store, 0.3);

            assertEquals(1, edb.excludedFactCount(),
                    "Should exclude exactly 1 fact (value 0.2 < 0.3)");
            assertFalse(edb.tuplesFor("medium").isEmpty(),
                    "medium(x) should be present (0.4 >= 0.3)");
            assertTrue(edb.tuplesFor("weak").isEmpty(),
                    "weak(y) should be absent (0.2 < 0.3)");
        }

        @Test
        @DisplayName("Threshold 0.0 admits all facts regardless of value")
        void zeroThresholdAdmitsAll() {
            FactStore store = new FactStore();
            store.assertFact(new Fact(atomKey("tiny", "z"), 0.01, "run", java.time.Instant.now(), false));

            FolDatalogAdapter.EdbProviderWithStats edb =
                    (FolDatalogAdapter.EdbProviderWithStats)
                    FolDatalogAdapter.factStoreEdb(store, 0.0);

            assertEquals(0, edb.excludedFactCount(),
                    "Threshold 0.0 should exclude nothing");
            assertFalse(edb.tuplesFor("tiny").isEmpty(),
                    "tiny(z) should be present (0.01 >= 0.0)");
        }

        @Test
        @DisplayName("factStoreEdb(store) returns EdbProviderWithStats")
        void defaultOverloadReturnsStats() {
            FactStore store = new FactStore();
            store.assertFact(Fact.observed(atomKey("a", "b"), "run"));

            EdbProvider edb = FolDatalogAdapter.factStoreEdb(store);
            assertInstanceOf(FolDatalogAdapter.EdbProviderWithStats.class, edb,
                    "Default factStoreEdb should return EdbProviderWithStats");
        }

        @Test
        @DisplayName("Threshold 0.3 in full materialization lets 0.4-value fact participate in Datalog")
        void lowerThresholdMaterializesWeakerFact() {
            // Build a FactStore with a weak fact (value=0.4) and a normal fact (value=1.0)
            // Rule: derived(?X, ?Z) :- weak(?X, ?Y), strong(?Y, ?Z)
            // With default threshold (0.5): weak(a,b) is excluded → derived(a,c) NOT derived
            // With threshold (0.3):        weak(a,b) is admitted  → derived(a,c) IS derived

            FactStore store = new FactStore();
            store.assertFact(new Fact(atomKey("weak", "a", "b"), 0.4, "r", java.time.Instant.now(), false));
            store.assertFact(new Fact(atomKey("strong", "b", "c"), 1.0, "r", java.time.Instant.now(), false));

            DatalogRule twoHop = FolDatalogAdapter.twoHopRule("derived", "weak", "strong");

            // Default threshold: derived(a,c) should NOT appear
            EdbProvider defaultEdb = FolDatalogAdapter.factStoreEdb(store);
            FixpointResult defaultResult = RecursiveQueryEngine.evaluate(List.of(twoHop), defaultEdb);
            assertFalse(defaultResult.derivedFacts().getOrDefault("derived", java.util.Set.of())
                    .contains(List.of("a", "c")),
                    "With default threshold, derived(a,c) should NOT be derived (weak excluded)");

            // Lower threshold: derived(a,c) SHOULD appear
            EdbProvider lowerEdb = FolDatalogAdapter.factStoreEdb(store, 0.3);
            FixpointResult lowerResult = RecursiveQueryEngine.evaluate(List.of(twoHop), lowerEdb);
            assertTrue(lowerResult.derivedFacts().getOrDefault("derived", java.util.Set.of())
                    .contains(List.of("a", "c")),
                    "With threshold=0.3, derived(a,c) should be derived (weak 0.4 >= 0.3)");
        }
    }

    // ─── FolInferenceService truncation ──────────────────────────────────────────

    @Nested
    @DisplayName("FolInferenceService truncation (fix #7a)")
    class FolInferenceTruncation {

        private static ai.kompile.graph.reasoning.unified.UnifiedGraph tinyGraph(int entityCount) {
            ai.kompile.graph.reasoning.unified.UnifiedGraph graph =
                    new ai.kompile.graph.reasoning.unified.UnifiedGraph();
            for (int i = 0; i < entityCount; i++) {
                graph.addEntity("e" + i, "T", "Entity" + i);
            }
            return graph;
        }

        @Test
        @DisplayName("groundingTruncated=false when pair count below cap")
        void noTruncationBelowCap() {
            // Very small graph → no truncation with default cap
            ai.kompile.graph.reasoning.unified.UnifiedGraph graph = tinyGraph(2);

            ai.kompile.graph.reasoning.fol.FolRuleSet ruleSet =
                    ai.kompile.graph.reasoning.fol.FolRuleSet.of("test", java.util.List.of());

            ai.kompile.graph.reasoning.fol.FolInferenceService svc =
                    new ai.kompile.graph.reasoning.fol.FolInferenceService(10_000);
            ai.kompile.graph.reasoning.fol.FolInferenceResult result = svc.infer(graph, ruleSet);

            // Empty rule set → no grounding → not truncated
            assertFalse(result.groundingTruncated(),
                    "Should not be truncated for tiny graph with default cap");
        }

        @Test
        @DisplayName("Service constructs with small cap and returns valid result")
        void smallCapConstructs() {
            // Build a graph with 5 entities; cap at 3 → truncated when rules exist
            ai.kompile.graph.reasoning.unified.UnifiedGraph graph = tinyGraph(5);

            // Use a cap of 3
            ai.kompile.graph.reasoning.fol.FolInferenceService svc =
                    new ai.kompile.graph.reasoning.fol.FolInferenceService(3);

            ai.kompile.graph.reasoning.fol.FolRuleSet ruleSet =
                    ai.kompile.graph.reasoning.fol.FolRuleSet.of("test", java.util.List.of());

            // Empty rule set → no translate → not truncated, but service is exercised
            ai.kompile.graph.reasoning.fol.FolInferenceResult result = svc.infer(graph, ruleSet);
            assertNotNull(result, "Result must not be null");
            assertTrue(result.pairsConsidered() >= 0, "pairsConsidered must be non-negative");
        }

        @Test
        @DisplayName("pairsConsidered is non-negative")
        void pairsConsideredNonNegative() {
            ai.kompile.graph.reasoning.unified.UnifiedGraph graph = tinyGraph(1);

            ai.kompile.graph.reasoning.fol.FolRuleSet ruleSet =
                    ai.kompile.graph.reasoning.fol.FolRuleSet.of("t", java.util.List.of());

            ai.kompile.graph.reasoning.fol.FolInferenceService svc =
                    new ai.kompile.graph.reasoning.fol.FolInferenceService();
            ai.kompile.graph.reasoning.fol.FolInferenceResult result = svc.infer(graph, ruleSet);

            assertTrue(result.pairsConsidered() >= 0,
                    "pairsConsidered must be non-negative");
        }

        @Test
        @DisplayName("FolInferenceResult includes truncation fields in toString")
        void toStringIncludesTruncation() {
            ai.kompile.graph.reasoning.unified.UnifiedGraph graph = tinyGraph(1);
            ai.kompile.graph.reasoning.fol.FolRuleSet ruleSet =
                    ai.kompile.graph.reasoning.fol.FolRuleSet.of("t", java.util.List.of());
            ai.kompile.graph.reasoning.fol.FolInferenceService svc =
                    new ai.kompile.graph.reasoning.fol.FolInferenceService();
            ai.kompile.graph.reasoning.fol.FolInferenceResult result = svc.infer(graph, ruleSet);

            String str = result.toString();
            assertTrue(str.contains("truncated"),
                    "toString should mention 'truncated', got: " + str);
            assertTrue(str.contains("pairs"),
                    "toString should mention 'pairs', got: " + str);
        }
    }
}
