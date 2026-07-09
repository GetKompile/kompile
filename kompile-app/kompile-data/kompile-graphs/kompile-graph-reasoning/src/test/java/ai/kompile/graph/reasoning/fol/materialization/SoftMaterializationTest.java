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
package ai.kompile.graph.reasoning.fol.materialization;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for soft-confidence materialization via {@link ForwardChainingMaterializer}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Parity: all-1.0 EDB → derived facts have value=confidence=1.0 (backward compat).</li>
 *   <li>Soft chain: a→b (0.8), b→c (0.5) → derived path(a,c) has confidence ≈ 0.4.</li>
 *   <li>Soft chain with uniform annotator override reproduces hard 1.0.</li>
 *   <li>Supporting fact keys and rule IDs preserved in derived facts.</li>
 *   <li>EDB facts are NOT re-materialized into the sink.</li>
 * </ul>
 */
class SoftMaterializationTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Transitivity rule: ancestor(?X,?Z) :- ancestor(?X,?Y), ancestor(?Y,?Z). */
    private static List<DatalogRule> transitivityRules() {
        return List.of(FolDatalogAdapter.transitivityRule("ancestor"));
    }

    /** Chain rules for "path" using two predicates. */
    private static List<DatalogRule> pathChainRules() {
        return List.of(
                // path(?X,?Y) :- edge(?X,?Y)
                new DatalogRule("path", List.of("?X", "?Y"),
                        List.of(RuleAtom.pos("edge", "?X", "?Y"))),
                // path(?X,?Z) :- path(?X,?Y), edge(?Y,?Z)
                new DatalogRule("path", List.of("?X", "?Z"),
                        List.of(RuleAtom.pos("path", "?X", "?Y"),
                                RuleAtom.pos("edge", "?Y", "?Z")))
        );
    }

    // ─── Parity tests ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parity: all-1.0 EDB → confidence = 1.0")
    class ParityTests {

        @Test
        @DisplayName("Transitivity with hard-observed (value=1.0) EDB → all derived facts value=1.0")
        void hardEdbParity() {
            FactStore store = new FactStore();
            store.assertFact(Fact.observed("ancestor(alice, bob)", "test"));
            store.assertFact(Fact.observed("ancestor(bob, carol)", "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, transitivityRules(), sink);

            // ancestor(alice, carol) should be derived
            Optional<InferredFact> derived = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("ancestor(alice, carol)"))
                    .findFirst();
            assertTrue(derived.isPresent(), "ancestor(alice, carol) must be derived");

            InferredFact fact = derived.get();
            assertEquals(1.0, fact.value(), 1e-9,
                    "Parity: all-1.0 EDB → derived fact value = 1.0");
            assertEquals(1.0, fact.confidence(), 1e-9,
                    "Parity: all-1.0 EDB → derived fact confidence = 1.0");
        }

        @Test
        @DisplayName("Path chain with hard EDB (value=1.0): derived path(a,c) confidence = 1.0")
        void pathChainHardEdbParity() {
            FactStore store = new FactStore();
            store.assertFact(Fact.observed("edge(a, b)", "test"));
            store.assertFact(Fact.observed("edge(b, c)", "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, pathChainRules(), sink);

            Optional<InferredFact> pathAC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, c)"))
                    .findFirst();
            assertTrue(pathAC.isPresent(), "path(a,c) must be derived");
            assertEquals(1.0, pathAC.get().value(), 1e-9,
                    "All-1.0 EDB: path(a,c) value = 1.0");
            assertEquals(1.0, pathAC.get().confidence(), 1e-9,
                    "All-1.0 EDB: path(a,c) confidence = 1.0");
        }

        @Test
        @DisplayName("Uniform annotator override: derived facts always have confidence = 1.0")
        void uniformAnnotatorOverrideParity() {
            FactStore store = new FactStore();
            store.assertFact(Fact.soft("edge(a, b)", 0.8, "test"));
            store.assertFact(Fact.soft("edge(b, c)", 0.5, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            // Use uniform annotator to reproduce old behavior regardless of EDB values
            mat.materialize(store, pathChainRules(), sink, FolDatalogAdapter.uniformViterbiAnnotator());

            Optional<InferredFact> pathAC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, c)"))
                    .findFirst();
            assertTrue(pathAC.isPresent(), "path(a,c) must be derived");
            assertEquals(1.0, pathAC.get().confidence(), 1e-9,
                    "Uniform annotator: path(a,c) confidence = 1.0 regardless of EDB values");
        }
    }

    // ─── Soft materialization tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("Soft confidence materialization")
    class SoftConfidenceTests {

        @Test
        @DisplayName("Chain a→b(0.8), b→c(0.5): path(a,c) confidence ≈ 0.4")
        void softChainConfidence() {
            FactStore store = new FactStore();
            // Soft facts with values 0.8 and 0.5 — above binarization threshold 0.5 so
            // edge(b,c) with value exactly 0.5 is included in crisp EDB
            store.assertFact(Fact.soft("edge(a, b)", 0.8, "test"));
            store.assertFact(Fact.soft("edge(b, c)", 0.5, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            ForwardChainingMaterializer mat = new ForwardChainingMaterializer();
            mat.materialize(store, pathChainRules(), sink);

            // path(a, b) derives from edge(a, b) (0.8) → confidence = 0.8
            Optional<InferredFact> pathAB = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, b)"))
                    .findFirst();
            assertTrue(pathAB.isPresent(), "path(a,b) must be derived");
            assertEquals(0.8, pathAB.get().confidence(), 1e-9,
                    "path(a,b) confidence = 0.8 (direct from edge with value 0.8)");

            // path(b, c) derives from edge(b, c) (0.5) → confidence = 0.5
            Optional<InferredFact> pathBC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(b, c)"))
                    .findFirst();
            assertTrue(pathBC.isPresent(), "path(b,c) must be derived");
            assertEquals(0.5, pathBC.get().confidence(), 1e-9,
                    "path(b,c) confidence = 0.5");

            // path(a, c) derives via path(a,b)(0.8) × edge(b,c)(0.5) = 0.4
            Optional<InferredFact> pathAC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, c)"))
                    .findFirst();
            assertTrue(pathAC.isPresent(), "path(a,c) must be derived");
            assertEquals(0.4, pathAC.get().confidence(), 1e-9,
                    "path(a,c) confidence = 0.8 × 0.5 = 0.4 (Viterbi product)");
            assertEquals(0.4, pathAC.get().value(), 1e-9,
                    "path(a,c) value = confidence = 0.4");
        }

        @Test
        @DisplayName("Soft chain: max-path wins with shortcut edge")
        void softChainWithShortcut() {
            FactStore store = new FactStore();
            store.assertFact(Fact.soft("edge(a, b)", 0.8, "test"));
            store.assertFact(Fact.soft("edge(b, c)", 0.5, "test"));
            // Direct shortcut a→c with value 0.6 — better than chain 0.8×0.5=0.4
            store.assertFact(Fact.soft("edge(a, c)", 0.6, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            new ForwardChainingMaterializer().materialize(store, pathChainRules(), sink);

            Optional<InferredFact> pathAC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, c)"))
                    .findFirst();
            assertTrue(pathAC.isPresent(), "path(a,c) must be derived");
            double conf = pathAC.get().confidence();
            // Viterbi = max(0.6 [direct], 0.4 [chain]) = 0.6
            assertEquals(0.6, conf, 1e-9,
                    "Viterbi = max(direct=0.6, chain=0.4) = 0.6, got: " + conf);
        }

        @Test
        @DisplayName("Provenance intact: supportingFactKeys and DEDUCTIVE_BASIS_MARKER present")
        void provenanceIntact() {
            FactStore store = new FactStore();
            store.assertFact(Fact.soft("edge(a, b)", 0.8, "test"));
            store.assertFact(Fact.soft("edge(b, c)", 0.5, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            new ForwardChainingMaterializer().materialize(store, pathChainRules(), sink);

            // path(a, c) should have provenance
            Optional<InferredFact> pathAC = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("path(a, c)"))
                    .findFirst();
            assertTrue(pathAC.isPresent());

            InferredFact fact = pathAC.get();
            // Supporting fact keys must be non-empty (parents in the derivation)
            assertFalse(fact.supportingFactKeys().isEmpty(),
                    "path(a,c) must have supporting fact keys (the body atoms)");

            // DEDUCTIVE_BASIS_MARKER must be in supportingRuleIds
            assertTrue(fact.supportingRuleIds().contains(ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER),
                    "supportingRuleIds must contain DEDUCTIVE_BASIS_MARKER");
        }

        @Test
        @DisplayName("EDB facts are NOT materialized into the sink")
        void edbNotRematerialized() {
            FactStore store = new FactStore();
            store.assertFact(Fact.soft("edge(a, b)", 0.8, "test"));
            store.assertFact(Fact.soft("edge(b, c)", 0.5, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            new ForwardChainingMaterializer().materialize(store, pathChainRules(), sink);

            // edge(a, b) and edge(b, c) are EDB — must NOT appear in sink
            boolean edbInSink = sink.allLatest().stream()
                    .anyMatch(f -> f.atomKey().equals("edge(a, b)") || f.atomKey().equals("edge(b, c)"));
            assertFalse(edbInSink, "EDB facts must not be materialized into the InferredFact sink");
        }

        @Test
        @DisplayName("Soft transitivity: ancestor(alice,carol) confidence = alice→bob × bob→carol")
        void softTransitivity() {
            FactStore store = new FactStore();
            // Soft facts above threshold (0.5 ≥ 0.5 = included; 0.9 ≥ 0.5 = included)
            store.assertFact(Fact.soft("ancestor(alice, bob)", 0.9, "test"));
            store.assertFact(Fact.soft("ancestor(bob, carol)", 0.7, "test"));

            InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
            new ForwardChainingMaterializer().materialize(store, transitivityRules(), sink);

            // The engine derives ancestor(alice, carol) via ancestor(alice,bob) × ancestor(bob,carol)
            Optional<InferredFact> derived = sink.allLatest().stream()
                    .filter(f -> f.atomKey().equals("ancestor(alice, carol)"))
                    .findFirst();
            assertTrue(derived.isPresent(), "ancestor(alice, carol) must be derived");
            assertEquals(0.9 * 0.7, derived.get().confidence(), 1e-9,
                    "confidence = 0.9 × 0.7 = " + (0.9 * 0.7));
        }
    }
}
