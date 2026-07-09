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

import ai.kompile.graph.reasoning.attribution.shapley.ClaimShapley.AttributionResult;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClaimShapley}: end-to-end attribution including witness-restricted
 * player selection, player cap, source aggregation, and memoization verification.
 */
@DisplayName("ClaimShapley")
class ClaimShapleyTest {

    private static final long SEED = 77L;
    private static final int SAMPLES = 2000;
    private static final double TOL = 0.07;

    // ─── Helper ──────────────────────────────────────────────────────────────────

    private static Fact hard(String atomKey, String sourceId) {
        return new Fact(atomKey, 1.0, sourceId, Instant.now(), true);
    }

    private static FactStore storeOf(Fact... facts) {
        FactStore fs = new FactStore();
        for (Fact f : facts) fs.assertFact(f);
        return fs;
    }

    // ─── Player restriction ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Player restriction: only witness facts are players")
    class PlayerRestrictionTests {

        /**
         * Setup:
         *   10 facts total: f1..f10.
         *   Rule: claim() :- f1(), f2().   (only f1 and f2 in any proof)
         *   f3..f10 are irrelevant.
         *
         * Expected: players list is exactly {f1, f2} (witness-restricted).
         */
        @Test
        @DisplayName("10 facts, only 2 in proof → player set is exactly those 2")
        void playerRestrictionToWitnessSet() {
            FactStore facts = storeOf(
                    hard("f1", "src1"), hard("f2", "src1"),
                    hard("f3", "src2"), hard("f4", "src2"),
                    hard("f5", "src3"), hard("f6", "src3"),
                    hard("f7", "src4"), hard("f8", "src4"),
                    hard("f9", "src5"), hard("f10", "src5"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("f1"), RuleAtom.pos("f2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);

            // Only f1 and f2 should be players
            assertEquals(2, result.players().size(),
                    "Expected exactly 2 players (witness facts), got: " + result.players());
            assertTrue(result.players().contains("f1"), "f1 must be a player");
            assertTrue(result.players().contains("f2"), "f2 must be a player");
            assertFalse(result.truncated(), "Should not be truncated (only 2 players)");
        }

        /**
         * With the same setup but verifying Shapley values: f3..f10 have Shapley 0
         * (they are not players at all), f1 and f2 each ≈ 0.5.
         */
        @Test
        @DisplayName("Shapley values: witness facts ≈ 0.5, non-witness excluded")
        void witnessFactsHaveNonZeroShapley() {
            FactStore facts = storeOf(
                    hard("f1", "src1"), hard("f2", "src1"),
                    hard("f3", "src2"), hard("f4", "src2"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("f1"), RuleAtom.pos("f2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);

            ShapleyReport report = result.report();
            // Only f1 and f2 are players; f3 and f4 not even in the report
            assertFalse(report.shapley().containsKey("f3"),
                    "f3 should not be a player in the report");
            assertFalse(report.shapley().containsKey("f4"),
                    "f4 should not be a player in the report");
            assertEquals(0.5, report.shapleyFor("f1"), TOL);
            assertEquals(0.5, report.shapleyFor("f2"), TOL);
        }
    }

    // ─── Source aggregation ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Source aggregation by Fact.sourceId")
    class SourceAggregationTests {

        /**
         * Two facts sharing a sourceId: source Shapley = sum of member Shapley values.
         *
         * Setup: claim() :- p1(), p2().
         * f1="p1" with sourceId="doc-A", f2="p2" with sourceId="doc-A".
         * Both Shapley(f1) ≈ Shapley(f2) ≈ 0.5 → bySource["doc-A"] ≈ 1.0.
         */
        @Test
        @DisplayName("Two facts with same sourceId → bySource sums to ≈ 1.0")
        void sameSourceIdSumsValues() {
            FactStore facts = storeOf(
                    hard("p1", "doc-A"),
                    hard("p2", "doc-A"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);

            Map<String, Double> bySource = result.report().bySource();
            assertTrue(bySource.containsKey("doc-A"),
                    "bySource should contain 'doc-A'");
            assertEquals(1.0, bySource.get("doc-A"), TOL,
                    "bySource['doc-A'] should ≈ Shapley(p1) + Shapley(p2) = 1.0");
        }

        /**
         * Two facts from different sources, each independently sufficient (disjunction).
         *
         * claim() :- p1().
         * claim() :- p2().
         * f1="p1" (src-X), f2="p2" (src-Y).
         * Both Shapley ≈ 0.5 → bySource["src-X"] ≈ 0.5, bySource["src-Y"] ≈ 0.5.
         */
        @Test
        @DisplayName("Different sourceIds on disjunction: each ≈ 0.5 in bySource")
        void differentSourcesDisjunction() {
            FactStore facts = storeOf(
                    hard("p1", "src-X"),
                    hard("p2", "src-Y"));

            DatalogRule rule1 = new DatalogRule("claim", List.of(),
                    List.of(RuleAtom.pos("p1")));
            DatalogRule rule2 = new DatalogRule("claim", List.of(),
                    List.of(RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule1, rule2), facts, SAMPLES, SEED);

            Map<String, Double> bySource = result.report().bySource();
            assertEquals(0.5, bySource.getOrDefault("src-X", 0.0), TOL,
                    "src-X contribution ≈ 0.5");
            assertEquals(0.5, bySource.getOrDefault("src-Y", 0.0), TOL,
                    "src-Y contribution ≈ 0.5");
        }
    }

    // ─── Player cap ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Player cap and truncation")
    class PlayerCapTests {

        /**
         * When witness set exceeds maxPlayers, the result is truncated and the
         * truncatedPlayers list is non-empty.
         *
         * Setup: claim :- p1, p2, p3, p4, p5.  maxPlayers=2.
         * The witness set has 5 facts; cap at 2 → truncated=true, truncatedPlayers.size()=3.
         */
        @Test
        @DisplayName("Truncation: witness set=5, maxPlayers=2 → truncated=true")
        void truncationFlagSetWhenPlayerCapExceeded() {
            FactStore facts = storeOf(
                    hard("p1", "s"), hard("p2", "s"), hard("p3", "s"),
                    hard("p4", "s"), hard("p5", "s"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2"), RuleAtom.pos("p3"),
                            RuleAtom.pos("p4"), RuleAtom.pos("p5")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED, 2);

            assertTrue(result.truncated(), "Should be truncated when cap exceeded");
            assertEquals(2, result.players().size(),
                    "players() should have exactly maxPlayers=2 entries");
            assertEquals(3, result.truncatedPlayers().size(),
                    "3 players should be reported as truncated");
        }

        /**
         * When witness set ≤ maxPlayers, no truncation occurs.
         */
        @Test
        @DisplayName("No truncation when witness set ≤ maxPlayers")
        void noTruncationWhenWithinCap() {
            FactStore facts = storeOf(hard("p1", "s"), hard("p2", "s"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED, 10);

            assertFalse(result.truncated());
            assertTrue(result.truncatedPlayers().isEmpty());
        }
    }

    // ─── Provenance availability ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Provenance availability flag")
    class ProvenanceTests {

        /**
         * When the claim is derivable and rules are non-empty, provenanceAvailable=true.
         */
        @Test
        @DisplayName("provenanceAvailable=true when derivation exists")
        void provenanceAvailableWhenDerived() {
            FactStore facts = storeOf(hard("p1", "s"), hard("p2", "s"));
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);
            assertTrue(result.provenanceAvailable(),
                    "provenanceAvailable should be true when rules derive the claim");
        }

        /**
         * When no derivation is recorded (empty rules), provenanceAvailable=false
         * and all facts are used as fallback players.
         */
        @Test
        @DisplayName("provenanceAvailable=false with empty rules → falls back to all facts")
        void provenanceFallbackWhenNoRules() {
            FactStore facts = storeOf(hard("p1", "s"), hard("p2", "s"));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(), facts, SAMPLES, SEED);
            // No rules → no derivation → provenanceAvailable=false
            assertFalse(result.provenanceAvailable(),
                    "provenanceAvailable should be false with no rules");
            // All 2 facts used as fallback players
            assertEquals(2, result.players().size(),
                    "fallback should include all facts as players");
        }
    }

    // ─── Memoization counter in ClaimShapley ─────────────────────────────────────

    @Nested
    @DisplayName("Memoization via DatalogClaimEvaluator in ClaimShapley")
    class ClaimShapleyMemoTests {

        /**
         * With 2 witness players, the number of engine invocations must be ≤ 4
         * (all subsets of a 2-element set: ∅, {p1}, {p2}, {p1,p2})
         * regardless of sample count.
         */
        @Test
        @DisplayName("2 witness players: engine invocations ≤ 4 (memo reduces redundant calls)")
        void memoReducesCallsInFullPipeline() {
            FactStore facts = storeOf(hard("p1", "src1"), hard("p2", "src1"));
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, 500, SEED);

            // The evaluator within result should show ≤ 4 engine calls
            int calls = result.evaluator().callCount();
            assertTrue(calls <= 4,
                    "Expected ≤ 4 engine invocations for 2 players, got: " + calls);
        }
    }

    // ─── Full conjunction scenario ────────────────────────────────────────────────

    @Nested
    @DisplayName("Full conjunction end-to-end")
    class FullConjunctionTests {

        /**
         * Full end-to-end: claim() :- p1(), p2().
         * Two hard facts, each value=1.0.
         * Expected: Shapley(p1) ≈ Shapley(p2) ≈ 0.5, sum ≈ 1, bySource correct.
         */
        @Test
        @DisplayName("E2E conjunction: Shapley values and source attribution")
        void endToEndConjunction() {
            FactStore facts = storeOf(
                    hard("p1", "source-A"),
                    hard("p2", "source-B"));

            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);

            ShapleyReport report = result.report();
            assertEquals(0.5, report.shapleyFor("p1"), TOL,
                    "Shapley(p1) ≈ 0.5 for symmetric conjunction");
            assertEquals(0.5, report.shapleyFor("p2"), TOL,
                    "Shapley(p2) ≈ 0.5 for symmetric conjunction");
            assertEquals(1.0, report.sum(), TOL, "efficiency: sum ≈ 1");

            // bySource: each source gets its own player's value ≈ 0.5
            assertEquals(0.5, report.bySource().getOrDefault("source-A", 0.0), TOL,
                    "bySource['source-A'] ≈ 0.5");
            assertEquals(0.5, report.bySource().getOrDefault("source-B", 0.0), TOL,
                    "bySource['source-B'] ≈ 0.5");

            // Metadata
            assertFalse(result.truncated());
            assertTrue(result.provenanceAvailable());
        }
    }

    // ─── Efficiency axiom ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Efficiency axiom: sum(shapley) ≈ targetDelta")
    class EfficiencyTests {

        @Test
        @DisplayName("Efficiency holds for conjunction")
        void efficiencyConjunction() {
            FactStore facts = storeOf(hard("p1", "s"), hard("p2", "s"));
            DatalogRule rule = new DatalogRule(
                    "claim", List.of(),
                    List.of(RuleAtom.pos("p1"), RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(rule), facts, SAMPLES, SEED);
            ShapleyReport report = result.report();
            assertEquals(report.targetDelta(), report.sum(), TOL,
                    "sum(shapley) should ≈ targetDelta");
        }

        @Test
        @DisplayName("Efficiency holds for disjunction with 3 facts")
        void efficiencyDisjunction() {
            FactStore facts = storeOf(hard("p1", "s"), hard("p2", "s"), hard("p3", "s"));

            // Two disjunctive rules: claim if p1 or p2 (p3 irrelevant)
            DatalogRule r1 = new DatalogRule("claim", List.of(), List.of(RuleAtom.pos("p1")));
            DatalogRule r2 = new DatalogRule("claim", List.of(), List.of(RuleAtom.pos("p2")));

            AttributionResult result = ClaimShapley.INSTANCE.attribute(
                    "claim", List.of(r1, r2), facts, SAMPLES, SEED);
            ShapleyReport report = result.report();

            assertEquals(report.targetDelta(), report.sum(), TOL,
                    "Efficiency axiom must hold: sum ≈ targetDelta");
            // p3 is a player here because witness restriction includes all facts if fallback
            // but p3 is not in ANY derivation so it gets Shapley 0
            // (p1 and p2 are witnesses; p3 is not in any derivation proof)
        }
    }
}
