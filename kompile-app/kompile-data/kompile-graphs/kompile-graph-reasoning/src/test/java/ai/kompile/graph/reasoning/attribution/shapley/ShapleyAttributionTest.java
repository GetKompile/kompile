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
package ai.kompile.graph.reasoning.attribution.shapley;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ShapleyAttribution} and {@link DatalogClaimEvaluator}.
 *
 * <p>All tests use explicit seeds for full determinism.  Tolerances account for the
 * Monte-Carlo estimation error of O(1/√samples) at 2000 samples (±0.05).</p>
 */
@DisplayName("ShapleyAttribution")
class ShapleyAttributionTest {

    private static final long SEED = 42L;
    private static final int SAMPLES = 2000;
    private static final double TOL = 0.07; // generous for 2000 samples

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Build a simple evaluator from a literal Set-to-boolean function (lambda). */
    private static ClaimEvaluator lambdaEval(java.util.function.Predicate<Set<String>> fn) {
        return fn::test;
    }

    /** Build a DatalogClaimEvaluator for a conjunction claim: needs f1 AND f2. */
    private static DatalogClaimEvaluator conjunctionEvaluator(String f1, String f2) {
        // Rule: claim() :- p1(), p2()
        // We model f1="p1(a)", f2="p2(a)" as zero/one-arity atoms
        // and derive claim via a rule.
        // Simplest: use a ClaimEvaluator directly as a lambda for analytic tests.
        // For DatalogClaimEvaluator tests we build proper rules.
        DatalogRule rule = new DatalogRule(
                "claim",
                List.of(),
                List.of(
                        RuleAtom.pos("p1"),
                        RuleAtom.pos("p2")
                )
        );
        Map<String, Double> allFacts = Map.of(f1, 1.0, f2, 1.0);
        return new DatalogClaimEvaluator("claim", List.of(rule), allFacts, Set.of());
    }

    // ─── Analytic conjunction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Analytic conjunction: claim = f1 AND f2")
    class ConjunctionTests {

        /**
         * Conjunction game: v(S) = 1 iff {f1, f2} ⊆ S.
         * By symmetry, Shapley(f1) = Shapley(f2) = 0.5.
         * Efficiency: sum ≈ 1.
         */
        @Test
        @DisplayName("lambda evaluator: Shapley(f1)=Shapley(f2)=0.5, sum≈1")
        void conjunctionShapleyEqualHalf() {
            String f1 = "fact1", f2 = "fact2";
            ClaimEvaluator eval = lambdaEval(s -> s.contains(f1) && s.contains(f2));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of(f1, f2), SAMPLES, SEED);

            assertEquals(0.5, report.shapleyFor(f1), TOL,
                    "Shapley(f1) should be 0.5 for symmetric conjunction");
            assertEquals(0.5, report.shapleyFor(f2), TOL,
                    "Shapley(f2) should be 0.5 for symmetric conjunction");
            assertEquals(1.0, report.sum(), TOL, "efficiency: sum ≈ 1");
            assertEquals(1.0, report.targetDelta(), 1e-9, "targetDelta=1 (claim changes value)");
        }

        /**
         * Same game via DatalogClaimEvaluator with actual rule: claim() :- p1(), p2().
         */
        @Test
        @DisplayName("Datalog evaluator: conjunction rule → Shapley(f1)=Shapley(f2)=0.5")
        void conjunctionDatalogEvaluator() {
            // f1="p1" (zero-arity atom), f2="p2"
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            Map<String, Double> allFacts = Map.of("p1", 1.0, "p2", 1.0);
            DatalogClaimEvaluator eval = new DatalogClaimEvaluator(
                    "claim", List.of(rule), allFacts, Set.of());

            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("p1", "p2"), SAMPLES, SEED);

            assertEquals(0.5, report.shapleyFor("p1"), TOL);
            assertEquals(0.5, report.shapleyFor("p2"), TOL);
            assertEquals(1.0, report.sum(), TOL);
        }
    }

    // ─── Analytic disjunction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Analytic disjunction: claim = f1 OR f2")
    class DisjunctionTests {

        /**
         * Disjunction game: v(S) = 1 iff {f1} ⊆ S OR {f2} ⊆ S.
         * By symmetry Shapley(f1) = Shapley(f2) = 0.5.
         * Efficiency: sum ≈ 1.
         */
        @Test
        @DisplayName("lambda evaluator: Shapley(f1)=Shapley(f2)=0.5, irrelevant f3≈0")
        void disjunctionShapleyEqualHalfIrrelevantZero() {
            String f1 = "fact1", f2 = "fact2", f3 = "fact3";
            ClaimEvaluator eval = lambdaEval(s -> s.contains(f1) || s.contains(f2));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of(f1, f2, f3), SAMPLES, SEED);

            assertEquals(0.5, report.shapleyFor(f1), TOL,
                    "Shapley(f1) ≈ 0.5 for disjunction");
            assertEquals(0.5, report.shapleyFor(f2), TOL,
                    "Shapley(f2) ≈ 0.5 for disjunction");
            assertEquals(0.0, report.shapleyFor(f3), TOL,
                    "Shapley(f3) ≈ 0 — irrelevant player");
            // Efficiency: sum ≈ v(all) - v(∅) = 1 - 0 = 1
            assertEquals(1.0, report.sum(), TOL, "efficiency sum ≈ 1");
        }

        /**
         * v(∅)=0 so targetDelta must be 1 when the claim holds with all players.
         */
        @Test
        @DisplayName("targetDelta=1 when claim holds with all players")
        void targetDeltaIsOne() {
            String f1 = "fact1", f2 = "fact2";
            ClaimEvaluator eval = lambdaEval(s -> s.contains(f1) || s.contains(f2));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of(f1, f2), SAMPLES, SEED);
            assertEquals(1.0, report.targetDelta(), 1e-9);
        }
    }

    // ─── Chain ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chain derivation: f1→d, (d,f2)→claim")
    class ChainTests {

        /**
         * Chain: rule1: derived(?X) :- base(?X, ?Y).
         *        rule2: claim(?Y)   :- derived(?X), link(?X, ?Y).
         *
         * Simulated as a lambda conjunction for analytic verification:
         * claim holds iff f1 (base(a, b)) AND f2 (link(b, c)) are present.
         * By symmetry Shapley(f1) = Shapley(f2) = 0.5.
         */
        @Test
        @DisplayName("lambda chain: both endpoints equally necessary → Shapley each ≈ 0.5")
        void chainBothNecessary() {
            String f1 = "base(a, b)", f2 = "link(b, c)";
            // claim holds iff both facts present (one-hop chain, both equally necessary)
            ClaimEvaluator eval = lambdaEval(s -> s.contains(f1) && s.contains(f2));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of(f1, f2), SAMPLES, SEED);

            assertEquals(0.5, report.shapleyFor(f1), TOL);
            assertEquals(0.5, report.shapleyFor(f2), TOL);
            assertEquals(1.0, report.sum(), TOL);
        }

        /**
         * Via real Datalog:
         * d(?X) :- f1(?X, ?Y).
         * claim(?Y) :- d(?X), f2(?X, ?Y).
         * Atoms: f1(a,b), f2(a,b).  Claim: claim(b).
         */
        @Test
        @DisplayName("Datalog chain: f1(a,b) → d(a), [d(a),f2(a,b)] → claim(b)")
        void datalogChain() {
            DatalogRule rule1 = new DatalogRule(
                    "d", List.of("?X"),
                    List.of(RuleAtom.pos("f1", "?X", "?Y")));
            DatalogRule rule2 = new DatalogRule(
                    "claim", List.of("?Y"),
                    List.of(RuleAtom.pos("d", "?X"), RuleAtom.pos("f2", "?X", "?Y")));

            Map<String, Double> allFacts = Map.of("f1(a, b)", 1.0, "f2(a, b)", 1.0);
            DatalogClaimEvaluator eval = new DatalogClaimEvaluator(
                    "claim(b)", List.of(rule1, rule2), allFacts, Set.of());

            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("f1(a, b)", "f2(a, b)"), SAMPLES, SEED);

            assertEquals(0.5, report.shapleyFor("f1(a, b)"), TOL,
                    "f1 is necessary: Shapley ≈ 0.5");
            assertEquals(0.5, report.shapleyFor("f2(a, b)"), TOL,
                    "f2 is necessary: Shapley ≈ 0.5");
            assertEquals(1.0, report.sum(), TOL);
        }
    }

    // ─── Determinism ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {

        @Test
        @DisplayName("Same seed → identical report values")
        void sameSeedIdenticalReport() {
            ClaimEvaluator eval = lambdaEval(s -> s.contains("a") && s.contains("b"));
            ShapleyReport r1 = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b", "c"), SAMPLES, SEED);
            ShapleyReport r2 = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b", "c"), SAMPLES, SEED);

            for (String p : List.of("a", "b", "c")) {
                assertEquals(r1.shapleyFor(p), r2.shapleyFor(p), 1e-12,
                        "Player " + p + " must have identical value with same seed");
                assertEquals(r1.stdErrorFor(p), r2.stdErrorFor(p), 1e-12,
                        "StdError for " + p + " must be identical with same seed");
            }
        }

        @Test
        @DisplayName("Different seed → different permutation but values within tolerance")
        void differentSeedDifferentButTolerable() {
            ClaimEvaluator eval = lambdaEval(s -> s.contains("a") && s.contains("b"));
            ShapleyReport r1 = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED);
            ShapleyReport r2 = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED + 1);

            // Values should differ (different RNG paths) but both ≈ 0.5
            assertEquals(0.5, r1.shapleyFor("a"), TOL);
            assertEquals(0.5, r2.shapleyFor("a"), TOL);
            // With high probability different seeds produce different raw doubles
            // (not a hard assertion — we just check both are within tolerance)
        }
    }

    // ─── topContributors ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ShapleyReport.topContributors")
    class TopContributorsTests {

        @Test
        @DisplayName("topContributors(1) returns the single highest-value player")
        void topContributorsOne() {
            // f1 alone determines the claim → higher Shapley than f2 (irrelevant)
            ClaimEvaluator eval = lambdaEval(s -> s.contains("f1"));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("f1", "f2"), SAMPLES, SEED);

            List<Map.Entry<String, Double>> top = report.topContributors(1);
            assertEquals(1, top.size());
            assertEquals("f1", top.get(0).getKey(),
                    "f1 should be the top contributor (only necessary fact)");
        }

        @Test
        @DisplayName("topContributors(k) returns k entries in descending order")
        void topContributorsDescendingOrder() {
            ClaimEvaluator eval = lambdaEval(s -> s.contains("a") && s.contains("b"));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED);

            List<Map.Entry<String, Double>> top = report.topContributors(2);
            assertEquals(2, top.size());
            assertTrue(top.get(0).getValue() >= top.get(1).getValue(),
                    "entries must be in descending order");
        }

        @Test
        @DisplayName("topContributors with k > players.size() caps at players.size()")
        void topContributorsCap() {
            ClaimEvaluator eval = lambdaEval(s -> s.contains("a"));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED);

            List<Map.Entry<String, Double>> top = report.topContributors(100);
            assertEquals(2, top.size(), "should cap at number of players");
        }

        @Test
        @DisplayName("topContributors(0) throws IllegalArgumentException")
        void topContributorsZeroThrows() {
            ClaimEvaluator eval = lambdaEval(s -> true);
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a"), SAMPLES, SEED);
            assertThrows(IllegalArgumentException.class, () -> report.topContributors(0));
        }
    }

    // ─── Standard error ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Standard error properties")
    class StdErrorTests {

        @Test
        @DisplayName("stdError is non-negative for all players")
        void stdErrorNonNegative() {
            ClaimEvaluator eval = lambdaEval(s -> s.contains("a") || s.contains("b"));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b", "c"), SAMPLES, SEED);

            for (String p : List.of("a", "b", "c")) {
                assertTrue(report.stdErrorFor(p) >= 0.0,
                        "stdError must be non-negative for player " + p);
            }
        }

        @Test
        @DisplayName("irrelevant player has stdError ≈ 0 (zero variance)")
        void irrelevantPlayerStdErrorZero() {
            // f3 never affects the claim → marginal always 0 → variance 0
            ClaimEvaluator eval = lambdaEval(s -> s.contains("f1"));
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("f1", "f2", "f3"), SAMPLES, SEED);

            // f2 and f3 are irrelevant: marginal always 0 → variance 0 → stdError 0
            assertEquals(0.0, report.stdErrorFor("f2"), 1e-10,
                    "irrelevant f2 stdError should be 0");
            assertEquals(0.0, report.stdErrorFor("f3"), 1e-10,
                    "irrelevant f3 stdError should be 0");
        }
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty player list → empty report, targetDelta=0")
        void emptyPlayers() {
            ClaimEvaluator eval = lambdaEval(s -> false);
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of(), SAMPLES, SEED);
            assertTrue(report.shapley().isEmpty());
            assertEquals(0.0, report.targetDelta(), 1e-9);
        }

        @Test
        @DisplayName("Claim always false → all Shapley values 0")
        void claimAlwaysFalse() {
            ClaimEvaluator eval = lambdaEval(s -> false);
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED);
            assertEquals(0.0, report.shapleyFor("a"), 1e-10);
            assertEquals(0.0, report.shapleyFor("b"), 1e-10);
            assertEquals(0.0, report.targetDelta(), 1e-9);
        }

        @Test
        @DisplayName("Claim always true → all Shapley values 0 and targetDelta=0")
        void claimAlwaysTrue() {
            ClaimEvaluator eval = lambdaEval(s -> true);
            ShapleyReport report = ShapleyAttribution.INSTANCE.assess(
                    eval, List.of("a", "b"), SAMPLES, SEED);
            assertEquals(0.0, report.shapleyFor("a"), 1e-10);
            assertEquals(0.0, report.shapleyFor("b"), 1e-10);
            // v(all)=1, v(∅)=1 → delta=0
            assertEquals(0.0, report.targetDelta(), 1e-9);
        }

        @Test
        @DisplayName("Duplicate players throws IllegalArgumentException")
        void duplicatePlayersThrows() {
            ClaimEvaluator eval = lambdaEval(s -> true);
            assertThrows(IllegalArgumentException.class, () ->
                ShapleyAttribution.INSTANCE.assess(eval, List.of("a", "a"), SAMPLES, SEED));
        }

        @Test
        @DisplayName("samples < 1 throws IllegalArgumentException")
        void zeroSamplesThrows() {
            ClaimEvaluator eval = lambdaEval(s -> true);
            assertThrows(IllegalArgumentException.class, () ->
                ShapleyAttribution.INSTANCE.assess(eval, List.of("a"), 0, SEED));
        }
    }

    // ─── Memoization ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DatalogClaimEvaluator memoization")
    class MemoizationTests {

        /**
         * With 2 players and N samples, there are at most 4 distinct subsets evaluated:
         * ∅, {f1}, {f2}, {f1,f2}.
         * The evaluator also separately evaluates ∅ and {f1,f2} for targetDelta.
         * Total distinct: 4.  Engine calls must be ≤ 4 regardless of sample count.
         */
        @Test
        @DisplayName("2 players: engine invocations ≤ 4 regardless of sample count")
        void memoReducesInvocationsFor2Players() {
            // Use Datalog so DatalogClaimEvaluator.callCount() is meaningful
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));
            Map<String, Double> allFacts = Map.of("p1", 1.0, "p2", 1.0);
            DatalogClaimEvaluator eval = new DatalogClaimEvaluator(
                    "claim", List.of(rule), allFacts, Set.of());

            // 500 samples, 2 players → up to 500 × 2 = 1000 calls without memo,
            // but with memo only 4 distinct subsets are possible
            ShapleyAttribution.INSTANCE.assess(eval, List.of("p1", "p2"), 500, SEED);

            // The evaluator also receives ∅ and {p1,p2} from targetDelta computation.
            // Total distinct subsets ≤ 4 (all subsets of a 2-element set).
            int callCount = eval.callCount();
            assertTrue(callCount <= 4,
                    "Expected ≤ 4 engine invocations (memo hit for repeated prefixes), got: "
                    + callCount);
        }

        /**
         * With 3 players and sufficient samples, distinct subsets ≤ 8.
         * Engine calls must be strictly less than samples × players = 500×3 = 1500.
         */
        @Test
        @DisplayName("3 players: engine invocations strictly less than samples × players")
        void memoReducesInvocationsFor3Players() {
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2"), RuleAtom.pos("p3")));
            Map<String, Double> allFacts = Map.of("p1", 1.0, "p2", 1.0, "p3", 1.0);
            DatalogClaimEvaluator eval = new DatalogClaimEvaluator(
                    "claim", List.of(rule), allFacts, Set.of());

            int samples = 500;
            ShapleyAttribution.INSTANCE.assess(eval, List.of("p1", "p2", "p3"), samples, SEED);

            // Max engine invocations: 8 distinct subsets + 2 (boundary checks in assess) = 10
            // Without memo: 500 × 3 = 1500
            assertTrue(eval.callCount() < samples * 3,
                    "Memo should reduce invocations below " + (samples * 3)
                    + ", got: " + eval.callCount());
        }
    }
}
