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
package ai.kompile.graph.reasoning.fol.semiring;

import ai.kompile.graph.reasoning.fol.grounding.AnnotatedResult;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.EdbProvider;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for semiring-annotated Datalog fixpoint evaluation.
 *
 * <p>Covers Viterbi (max-product) and Counting semirings, chain/diamond graphs,
 * cycle safety, and parity with all-1.0 EDB.</p>
 */
class SemiringFixpointTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Build an EdbProvider from a predicate → tuple-list map. */
    private static EdbProvider mapEdb(Map<String, List<List<String>>> map) {
        return pred -> map.getOrDefault(pred, List.of());
    }

    /**
     * Two-hop chain rules:
     * <pre>
     *   path(?X, ?Y) :- edge(?X, ?Y)
     *   path(?X, ?Z) :- path(?X, ?Y), edge(?Y, ?Z)
     * </pre>
     */
    private static List<DatalogRule> chainRules() {
        return List.of(
                new DatalogRule("path", List.of("?X", "?Y"),
                        List.of(RuleAtom.pos("edge", "?X", "?Y"))),
                new DatalogRule("path", List.of("?X", "?Z"),
                        List.of(RuleAtom.pos("path", "?X", "?Y"),
                                RuleAtom.pos("edge", "?Y", "?Z")))
        );
    }

    // ─── Viterbi semiring ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Viterbi (max-product) semiring")
    class ViterbiTests {

        @Test
        @DisplayName("Chain a→b(0.8), b→c(0.5): path(a,c) Viterbi annotation = 0.4")
        void chainViterbi() {
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            edbMap.put("edge", List.of(List.of("a", "b"), List.of("b", "c")));

            // EDB annotator: a→b has value 0.8, b→c has value 0.5
            java.util.function.Function<String, Double> annotator = atomKey -> switch (atomKey) {
                case "edge(a, b)" -> 0.8;
                case "edge(b, c)" -> 0.5;
                default -> 1.0;
            };

            AnnotatedResult<Double> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), ViterbiSemiring.INSTANCE, annotator);

            // path(a, b) derives directly from edge(a, b) → annotation = 0.8
            assertEquals(0.8, result.annotation("path(a, b)"), 1e-9,
                    "path(a,b) derives from edge(a,b) with value 0.8");

            // path(b, c) derives directly from edge(b, c) → annotation = 0.5
            assertEquals(0.5, result.annotation("path(b, c)"), 1e-9,
                    "path(b,c) derives from edge(b,c) with value 0.5");

            // path(a, c) derives via path(a,b) × edge(b,c) = 0.8 × 0.5 = 0.4
            assertEquals(0.4, result.annotation("path(a, c)"), 1e-9,
                    "path(a,c) = max-product chain 0.8 × 0.5 = 0.4");
        }

        @Test
        @DisplayName("Chain + direct shortcut: path(a,c) annotation = max(0.4, 0.6) = 0.6")
        void chainViterbiWithShortcut() {
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            // Chain: a→b(0.8), b→c(0.5); PLUS direct a→c(0.6)
            edbMap.put("edge", List.of(
                    List.of("a", "b"),
                    List.of("b", "c"),
                    List.of("a", "c")   // direct shortcut with value 0.6
            ));

            java.util.function.Function<String, Double> annotator = atomKey -> switch (atomKey) {
                case "edge(a, b)" -> 0.8;
                case "edge(b, c)" -> 0.5;
                case "edge(a, c)" -> 0.6;
                default -> 1.0;
            };

            AnnotatedResult<Double> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), ViterbiSemiring.INSTANCE, annotator);

            // path(a, c) has two derivations:
            //   1. direct: path(a,c) :- edge(a,c)  → ann = 0.6
            //   2. chain:  path(a,c) :- path(a,b), edge(b,c) → ann = 0.8 × 0.5 = 0.4
            // Viterbi = max(0.6, 0.4) = 0.6
            double ann = result.annotation("path(a, c)");
            assertEquals(0.6, ann, 1e-9,
                    "path(a,c) with shortcut: Viterbi = max(0.6, 0.4) = 0.6, got: " + ann);
        }

        @Test
        @DisplayName("Parity: all-1.0 EDB produces Viterbi annotations all 1.0")
        void allOnesEdbViterbiParity() {
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            edbMap.put("edge", List.of(List.of("a", "b"), List.of("b", "c")));

            // All EDB facts have value 1.0 (uniform annotator)
            AnnotatedResult<Double> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), ViterbiSemiring.INSTANCE,
                    atomKey -> 1.0);

            assertEquals(1.0, result.annotation("path(a, b)"), 1e-9);
            assertEquals(1.0, result.annotation("path(b, c)"), 1e-9);
            assertEquals(1.0, result.annotation("path(a, c)"), 1e-9);
        }

        @Test
        @DisplayName("Cyclic rules terminate; annotations remain stable")
        void cyclicRulesTerminate() {
            // Cyclic EDB: a→b, b→a
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            edbMap.put("edge", List.of(List.of("a", "b"), List.of("b", "a")));

            java.util.function.Function<String, Double> annotator = atomKey -> switch (atomKey) {
                case "edge(a, b)" -> 0.9;
                case "edge(b, a)" -> 0.8;
                default -> 1.0;
            };

            // Should not hang; must complete
            long start = System.currentTimeMillis();
            AnnotatedResult<Double> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), ViterbiSemiring.INSTANCE, annotator);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 5_000, "Cyclic graph annotation must complete quickly, took: " + elapsed + "ms");

            // path(a, b) exists (direct edge)
            assertNotNull(result.fixpointResult().derivedFacts().get("path"));
            assertTrue(result.fixpointResult().derivedFacts().get("path").contains(List.of("a", "b")));

            // Viterbi annotation for path(a, b) = max(ann_direct, ann_via_cycle)
            // Direct: edge(a,b) = 0.9
            // Via cycle: path(a,b) via path(a,a)?  Only if path(a,a) derived.
            // Key: annotation must be ≥ 0.9 (direct edge) and ≤ 1.0
            double ann = result.annotation("path(a, b)");
            assertTrue(ann >= 0.9 - 1e-9 && ann <= 1.0 + 1e-9,
                    "path(a,b) annotation must be in [0.9, 1.0] for cyclic graph, got: " + ann);
        }
    }

    // ─── Counting semiring ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Counting semiring")
    class CountingTests {

        /**
         * Diamond graph: a→b, a→c, b→d, c→d.
         * Rules derive path via each 2-hop route.
         * path(a, d) should have count 2 (two distinct routes).
         */
        @Test
        @DisplayName("Diamond graph: path(a,d) has count 2 (two routes: a→b→d and a→c→d)")
        void diamondCountTwo() {
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            edbMap.put("edge", List.of(
                    List.of("a", "b"),
                    List.of("a", "c"),
                    List.of("b", "d"),
                    List.of("c", "d")
            ));

            // Use a large maxDerivationsPerAtom to capture both derivations
            AnnotatedResult<Long> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), CountingSemiring.INSTANCE,
                    atomKey -> 1L,
                    RecursiveQueryEngine.DEFAULT_MAX_ROUNDS,
                    RecursiveQueryEngine.DEFAULT_MAX_DERIVED_FACTS,
                    8   // raise cap to capture both derivations
            );

            // path(a, b) and path(a, c) each have count 1 (single direct edge)
            assertEquals(1L, result.annotation("path(a, b)"),
                    "path(a,b) — single edge, count = 1");
            assertEquals(1L, result.annotation("path(a, c)"),
                    "path(a,c) — single edge, count = 1");

            // path(a, d): derived via path(a,b)+edge(b,d) AND path(a,c)+edge(c,d) → count ≥ 2
            long countAD = result.annotation("path(a, d)");
            assertTrue(countAD >= 2L,
                    "path(a,d) has two derivations (via b and via c), count must be ≥ 2, got: " + countAD);
        }

        @Test
        @DisplayName("Linear chain a→b→c: path(a,c) has count 1 (single derivation)")
        void linearChainCountOne() {
            Map<String, List<List<String>>> edbMap = new HashMap<>();
            edbMap.put("edge", List.of(List.of("a", "b"), List.of("b", "c")));

            AnnotatedResult<Long> result = RecursiveQueryEngine.evaluateAnnotated(
                    chainRules(), mapEdb(edbMap), CountingSemiring.INSTANCE,
                    atomKey -> 1L);

            long countAC = result.annotation("path(a, c)");
            assertEquals(1L, countAC,
                    "Linear chain: path(a,c) has exactly 1 derivation, got: " + countAC);
        }

        @Test
        @DisplayName("Counting semiring zero and one: semiring axioms hold")
        void countingSemiringAxioms() {
            CountingSemiring s = CountingSemiring.INSTANCE;
            assertEquals(0L, s.zero());
            assertEquals(1L, s.one());
            assertEquals(3L, s.plus(1L, 2L));
            assertEquals(6L, s.times(2L, 3L));
            assertEquals(0L, s.times(0L, 5L), "0 × k = 0");
            assertEquals(0L, s.times(5L, 0L), "k × 0 = 0");
            assertFalse(s.isAbsorptive());
        }

        @Test
        @DisplayName("CountingSemiring saturation: plus and times saturate at cap")
        void saturation() {
            CountingSemiring s = new CountingSemiring(10L);
            assertEquals(10L, s.plus(8L, 5L), "8+5=13 saturates at cap=10");
            assertEquals(10L, s.times(4L, 3L), "4×3=12 saturates at cap=10");
            assertEquals(10L, s.plus(10L, 1L), "10+1=11 saturates at cap=10");
        }
    }

    // ─── Viterbi semiring axioms ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ViterbiSemiring algebra axioms")
    class ViterbiAxiomsTests {

        @Test
        @DisplayName("ViterbiSemiring axioms: zero, one, plus=max, times=×, absorptive")
        void axioms() {
            ViterbiSemiring s = ViterbiSemiring.INSTANCE;
            assertEquals(0.0, s.zero(), 1e-15);
            assertEquals(1.0, s.one(), 1e-15);
            assertEquals(0.9, s.plus(0.7, 0.9), 1e-9);
            assertEquals(0.56, s.times(0.7, 0.8), 1e-9);
            assertEquals(0.0, s.times(0.0, 0.5), 1e-9, "0 annihilator");
            assertEquals(0.7, s.times(s.one(), 0.7), 1e-9, "one() identity for times: 1.0 × 0.7 = 0.7");
            assertEquals(0.7, s.times(0.7, s.one()), 1e-9, "one() right identity: 0.7 × 1.0 = 0.7");
            assertEquals(0.3, s.plus(s.zero(), 0.3), 1e-9, "zero() identity for plus: max(0.0, 0.3) = 0.3");
            assertTrue(s.isAbsorptive(), "Viterbi is absorptive (max is idempotent)");
        }
    }
}
