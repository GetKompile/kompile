/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.Step;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for E2: {@link PslTraceAdapter} — PSL MAP result → {@link ReasoningTrace}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Root is an INFERENCE step for the target atom</li>
 *   <li>Premises are RULE steps with FACT leaves</li>
 *   <li>Confidences are in [0, 1]</li>
 *   <li>The trace is walkable via steps() and leaves()</li>
 *   <li>FactStore overload wires sourceId into leaf steps</li>
 *   <li>maxPremises cap is respected</li>
 * </ul>
 */
@DisplayName("E2 — PslTraceAdapter")
class PslTraceAdapterTest {

    private static HlMrfMapInference.Result buildSimpleResult() {
        PslProgram prog = new PslProgram()
                .observe("Trust", 0.9, "alice", "bob")
                .observe("Trust", 0.7, "bob", "carol")
                .target("Trust", "alice", "carol")
                .addRule("2.0: Trust(A, B) & Trust(B, C) -> Trust(A, C) ^2")
                .addRule("0.5: Trust(A, B)");  // soft prior

        return HlMrfMapInference.solve(prog);
    }

    @Nested
    @DisplayName("Trace structure")
    class StructureTests {

        @Test
        @DisplayName("Root step is INFERENCE for the target atom")
        void rootIsInferenceStep() {
            HlMrfMapInference.Result result = buildSimpleResult();
            // Find a target atom key
            String targetAtom = result.values().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("Trust(alice"))
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElse("Trust(alice, carol)");

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            Step root = trace.conclusion();
            assertEquals(StepKind.INFERENCE, root.kind(),
                    "Root step must be INFERENCE; got: " + root.kind());
            assertEquals(targetAtom, root.conclusion(),
                    "Root conclusion must be the target atom");
            assertTrue(root.operation().startsWith("psl-map value="),
                    "Root operation must start with 'psl-map value='; got: " + root.operation());
        }

        @Test
        @DisplayName("Rule premises exist under root")
        void hasPremises() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse(null);
            assumeNonNull(targetAtom);

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertFalse(trace.conclusion().premises().isEmpty(),
                    "Root should have at least one RULE premise");

            for (Step premise : trace.conclusion().premises()) {
                assertEquals(StepKind.RULE, premise.kind(),
                        "Each premise of root should be a RULE step; got: " + premise.kind());
            }
        }

        @Test
        @DisplayName("RULE premises have FACT leaves")
        void rulePremisesHaveFactLeaves() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse(null);
            assumeNonNull(targetAtom);

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            List<Step> leaves = trace.leaves();
            assertFalse(leaves.isEmpty(), "Trace must have at least one leaf");

            for (Step leaf : leaves) {
                assertEquals(StepKind.FACT, leaf.kind(),
                        "Leaf step must be FACT; got: " + leaf.kind());
                assertTrue(leaf.isLeaf(), "Leaf step must have no premises");
            }
        }

        @Test
        @DisplayName("All confidences are in [0, 1]")
        void confidencesInRange() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse(null);
            assumeNonNull(targetAtom);

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            for (Step step : trace.steps()) {
                double conf = step.confidence();
                assertTrue(conf >= 0.0 && conf <= 1.0,
                        "Confidence out of [0,1]: " + conf + " for step: " + step.conclusion());
            }
        }

        @Test
        @DisplayName("Trace is walkable via steps() and leaves()")
        void walkable() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse(null);
            assumeNonNull(targetAtom);

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            List<Step> allSteps = trace.steps();
            assertFalse(allSteps.isEmpty());
            assertTrue(allSteps.size() >= 2,
                    "Trace should have root + at least one leaf; size=" + allSteps.size());

            // size() count must match steps() count
            assertEquals(allSteps.size(), trace.size());

            // leaves() are a subset of steps()
            for (Step leaf : trace.leaves()) {
                assertTrue(allSteps.contains(leaf), "Leaf must appear in steps()");
                assertTrue(leaf.isLeaf());
            }

            // contains() works for root conclusion
            assertTrue(trace.contains(targetAtom),
                    "trace.contains(targetAtom) must be true");
        }

        @Test
        @DisplayName("maxPremises cap is respected")
        void maxPremisesCap() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse(null);
            assumeNonNull(targetAtom);

            int cap = 1;
            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT, null, cap);

            assertTrue(trace.conclusion().premises().size() <= cap,
                    "Should have at most " + cap + " premises under root; got: "
                            + trace.conclusion().premises().size());
        }
    }

    @Nested
    @DisplayName("FactStore integration")
    class FactStoreTests {

        @Test
        @DisplayName("FactStore overload wires sourceId into FACT leaf steps")
        void factStoreProvidesSourceId() {
            PslProgram prog = new PslProgram()
                    .observe("Prior", 0.8, "alice")
                    .target("IsActive", "alice")
                    .addRule("2.0: Prior(X) -> IsActive(X) ^2");

            FactStore factStore = new FactStore();
            factStore.assertFact(Fact.soft("Prior(alice)", 0.8, "doc-evidence-001"));

            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new ScalarHlMrfInference().solve(prog, ground);

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, "IsActive(alice)",
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT, factStore);

            // At least one FACT leaf should have a non-null source
            boolean anySourced = trace.leaves().stream()
                    .anyMatch(l -> l.source() != null);
            assertTrue(anySourced,
                    "At least one FACT leaf should have a sourceId from the FactStore; "
                            + "leaves: " + trace.leaves());
        }

        @Test
        @DisplayName("Without FactStore, leaf source is null (no NPE)")
        void noFactStoreNoNpe() {
            PslProgram prog = new PslProgram()
                    .observe("Prior", 0.8, "alice")
                    .target("IsActive", "alice")
                    .addRule("2.0: Prior(X) -> IsActive(X) ^2");

            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new ScalarHlMrfInference().solve(prog, ground);

            assertDoesNotThrow(() -> {
                ReasoningTrace trace = PslTraceAdapter.toTrace(result, "IsActive(alice)",
                        HlMrfMapInference.DEFAULT_HARD_WEIGHT, null);
                // leaves may have null source, should not throw
                trace.leaves().forEach(l -> assertNull(l.source(),
                        "Without FactStore, leaf source should be null"));
            });
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Unknown target atom produces a leaf-only trace with value=0")
        void unknownTargetAtom() {
            HlMrfMapInference.Result result = buildSimpleResult();

            assertDoesNotThrow(() -> {
                ReasoningTrace trace = PslTraceAdapter.toTrace(result, "NonExistent(x)",
                        HlMrfMapInference.DEFAULT_HARD_WEIGHT);
                // Should produce a valid trace (root with no premises, confidence 0)
                assertNotNull(trace);
                assertEquals("NonExistent(x)", trace.conclusion().conclusion());
                assertEquals(StepKind.INFERENCE, trace.conclusion().kind());
                assertEquals(0.0, trace.conclusion().confidence(), 1e-9,
                        "Unknown atom should have confidence=0");
            });
        }

        @Test
        @DisplayName("Trace toJson() produces valid JSON (no exception)")
        void toJsonDoesNotThrow() {
            HlMrfMapInference.Result result = buildSimpleResult();
            String targetAtom = result.values().keySet().stream()
                    .filter(k -> k.startsWith("Trust(alice"))
                    .findFirst().orElse("Trust(alice, carol)");

            ReasoningTrace trace = PslTraceAdapter.toTrace(result, targetAtom,
                    HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            String json = assertDoesNotThrow(trace::toJson);
            assertNotNull(json);
            assertTrue(json.startsWith("{"), "JSON should start with '{'");
            assertTrue(json.contains("\"kind\""), "JSON should contain 'kind' field");
        }
    }

    /** Convenience null-check that also gives a clear message. */
    private static void assumeNonNull(Object obj) {
        assertNotNull(obj, "Could not locate target atom in result values — check PslProgram setup");
    }
}
