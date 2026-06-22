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
package ai.kompile.graph.reasoning.pruning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpinionPruner} and {@link PrunePolicy}.
 *
 * <p>All tests are pure-Java; no Spring context, no mocks, no I/O.</p>
 */
@DisplayName("OpinionPruner + PrunePolicy")
class OpinionPrunerTest {

    // ─── PrunePolicy ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PrunePolicy construction")
    class PrunePolicyTests {

        @Test
        @DisplayName("defaults() produces valid record")
        void defaultsValid() {
            PrunePolicy p = PrunePolicy.defaults();
            assertEquals(0.10, p.minBelief(), 1e-9);
            assertEquals(0.80, p.maxUncertainty(), 1e-9);
            assertEquals(0.15, p.minExpectation(), 1e-9);
            assertTrue(p.pruneSuppressedBand());
        }

        @Test
        @DisplayName("aggressive() has tighter thresholds than defaults()")
        void aggressiveTighterThanDefaults() {
            PrunePolicy def = PrunePolicy.defaults();
            PrunePolicy agg = PrunePolicy.aggressive();
            assertTrue(agg.minBelief() > def.minBelief(),
                    "aggressive minBelief should be higher than default");
            assertTrue(agg.maxUncertainty() < def.maxUncertainty(),
                    "aggressive maxUncertainty should be lower than default");
            assertTrue(agg.minExpectation() > def.minExpectation(),
                    "aggressive minExpectation should be higher than default");
        }

        @Test
        @DisplayName("conservative() has looser thresholds than defaults()")
        void conservativeLooperThanDefaults() {
            PrunePolicy def = PrunePolicy.defaults();
            PrunePolicy con = PrunePolicy.conservative();
            assertTrue(con.minBelief() < def.minBelief());
            assertTrue(con.maxUncertainty() > def.maxUncertainty());
            assertTrue(con.minExpectation() < def.minExpectation());
        }

        @Test
        @DisplayName("out-of-range minBelief rejects")
        void outOfRangeMinBeliefRejects() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PrunePolicy(-0.1, 0.8, 0.15, true));
            assertThrows(IllegalArgumentException.class,
                    () -> new PrunePolicy(1.1, 0.8, 0.15, true));
        }

        @Test
        @DisplayName("out-of-range maxUncertainty rejects")
        void outOfRangeMaxUncertaintyRejects() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PrunePolicy(0.1, -0.1, 0.15, true));
        }

        @Test
        @DisplayName("out-of-range minExpectation rejects")
        void outOfRangeMinExpectationRejects() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PrunePolicy(0.1, 0.8, 1.5, true));
        }
    }

    // ─── OpinionPruner.decide — SUPPRESSED-band ───────────────────────────────

    @Nested
    @DisplayName("SUPPRESSED-band pruning")
    class SuppressedBandTests {

        @Test
        @DisplayName("SUPPRESSED-band opinion is pruned when pruneSuppressedBand=true")
        void suppressedIsPruned() {
            // Opinion.fromObservedValue(0.05) → expectation≈0.05 < 0.10 → SUPPRESSED band
            Opinion suppressed = Opinion.fromObservedValue(0.05);
            assertEquals(StrengthBand.SUPPRESSED, suppressed.projectBand(), "precondition");

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision d = pruner.decide("edge-1", suppressed);

            assertTrue(d.shouldPrune(), "SUPPRESSED-band edge must be pruned");
            assertTrue(d.reason().contains("SUPPRESSED"), "reason must mention SUPPRESSED");
        }

        @Test
        @DisplayName("SUPPRESSED-band opinion is KEPT when pruneSuppressedBand=false")
        void suppressedKeptWhenFlagOff() {
            Opinion suppressed = Opinion.fromObservedValue(0.05);
            // policy with pruneSuppressedBand=false but also loose numeric thresholds
            PrunePolicy policy = new PrunePolicy(0.0, 1.0, 0.0, false);
            OpinionPruner pruner = new OpinionPruner(policy);

            OpinionPruner.Decision d = pruner.decide("edge-2", suppressed);
            assertFalse(d.shouldPrune(),
                    "With pruneSuppressedBand=false and thresholds that pass, edge should be kept");
        }

        @Test
        @DisplayName("vacuous opinion (all uncertainty) is pruned via uncertainty rule")
        void vacuousIsPruned() {
            Opinion vacuous = Opinion.vacuous(); // u=1.0 > maxUncertainty=0.80
            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision d = pruner.decide("edge-3", vacuous);
            assertTrue(d.shouldPrune(), "Vacuous opinion (u=1.0) should be pruned");
        }
    }

    // ─── OpinionPruner.decide — belief < minBelief ───────────────────────────

    @Nested
    @DisplayName("belief < minBelief pruning")
    class BeliefThresholdTests {

        @Test
        @DisplayName("edge with belief below threshold is pruned (band not SUPPRESSED)")
        void beliefBelowThresholdPruned() {
            // Construct a custom opinion: low belief but not vacuous and expectation above floor
            // b=0.05, d=0.60, u=0.35, a=0.5 → expectation=0.05+0.5*0.35=0.225 (above 0.15)
            // band: e=0.225 < 0.40 and u=0.35 < 0.60 → SPECULATIVE (not SUPPRESSED)
            Opinion o = new Opinion(0.05, 0.60, 0.35, 0.5);
            assertEquals(StrengthBand.SPECULATIVE, o.projectBand(), "precondition: should be SPECULATIVE");

            // Use a policy that does NOT auto-prune SUPPRESSED band, but has minBelief=0.10
            PrunePolicy policy = new PrunePolicy(0.10, 0.80, 0.05, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-4", o);

            assertTrue(d.shouldPrune(), "belief=0.05 < minBelief=0.10 should be pruned");
            assertTrue(d.reason().contains("belief"), "reason should mention belief");
            assertTrue(d.reason().contains("minBelief"), "reason should mention minBelief");
        }

        @Test
        @DisplayName("edge at exactly minBelief is kept")
        void beliefAtThresholdKept() {
            // b=0.10, d=0.50, u=0.40, a=0.5 → expectation=0.10+0.5*0.40=0.30 (above 0.15)
            Opinion o = new Opinion(0.10, 0.50, 0.40, 0.5);
            PrunePolicy policy = new PrunePolicy(0.10, 0.80, 0.05, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-5", o);
            assertFalse(d.shouldPrune(),
                    "belief exactly at minBelief threshold should be KEPT (not strictly less than)");
        }
    }

    // ─── OpinionPruner.decide — uncertainty > maxUncertainty ─────────────────

    @Nested
    @DisplayName("uncertainty > maxUncertainty pruning")
    class UncertaintyThresholdTests {

        @Test
        @DisplayName("high-uncertainty opinion is pruned even if belief is fine")
        void highUncertaintyPruned() {
            // b=0.12, d=0.03, u=0.85, a=0.5 → expectation=0.12+0.5*0.85=0.545 (above 0.15)
            // band: e=0.545 ≥ 0.40 but u=0.85 >= 0.60 → SPECULATIVE (not SUPPRESSED)
            Opinion o = new Opinion(0.12, 0.03, 0.85, 0.5);
            PrunePolicy policy = new PrunePolicy(0.10, 0.80, 0.05, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-6", o);

            assertTrue(d.shouldPrune(), "uncertainty=0.85 > maxUncertainty=0.80 should be pruned");
            assertTrue(d.reason().contains("uncertainty"), "reason should mention uncertainty");
        }

        @Test
        @DisplayName("uncertainty at exactly maxUncertainty is kept")
        void uncertaintyAtCeilingKept() {
            // b=0.12, d=0.08, u=0.80, a=0.5 → sums to 1.0
            Opinion o = new Opinion(0.12, 0.08, 0.80, 0.5);
            PrunePolicy policy = new PrunePolicy(0.10, 0.80, 0.05, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-7", o);
            assertFalse(d.shouldPrune(),
                    "uncertainty exactly at maxUncertainty should be KEPT (not strictly greater)");
        }
    }

    // ─── OpinionPruner.decide — expectation < minExpectation ─────────────────

    @Nested
    @DisplayName("expectation < minExpectation pruning")
    class ExpectationThresholdTests {

        @Test
        @DisplayName("low-expectation opinion is pruned")
        void lowExpectationPruned() {
            // b=0.10, d=0.80, u=0.10, a=0.5 → expectation=0.10+0.5*0.10=0.15 (on boundary)
            // Let's use a=0.3 → expectation=0.10+0.3*0.10=0.13 < 0.15
            Opinion o = new Opinion(0.10, 0.80, 0.10, 0.3);
            double e = o.expectation(); // 0.10 + 0.3*0.10 = 0.13
            assertTrue(e < 0.15, "precondition: expectation should be < 0.15");

            PrunePolicy policy = new PrunePolicy(0.09, 0.85, 0.15, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-8", o);

            assertTrue(d.shouldPrune(), "expectation " + e + " < minExpectation=0.15 should be pruned");
            assertTrue(d.reason().contains("expectation"), "reason should mention expectation");
        }
    }

    // ─── OpinionPruner.decide — KEEP cases ───────────────────────────────────

    @Nested
    @DisplayName("ESTABLISHED/HIGH band → KEEP")
    class KeepTests {

        @Test
        @DisplayName("ESTABLISHED-band opinion is always kept with default policy")
        void establishedIsKept() {
            // Opinion.fromObservedValue(0.95) → e≈0.95≥0.85 and u=0 < 0.15 → ESTABLISHED
            Opinion established = Opinion.fromObservedValue(0.95);
            assertEquals(StrengthBand.ESTABLISHED, established.projectBand(), "precondition");

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision d = pruner.decide("edge-9", established);

            assertFalse(d.shouldPrune(), "ESTABLISHED-band opinion must be kept");
            assertTrue(d.reason().contains("KEEP"), "reason should say KEEP");
        }

        @Test
        @DisplayName("HIGH-band opinion is kept with default policy")
        void highBandIsKept() {
            // b=0.75, d=0.05, u=0.20, a=0.5 → expectation=0.75+0.5*0.20=0.85 ≥ 0.70 and u=0.20 < 0.30 → HIGH
            Opinion o = new Opinion(0.75, 0.05, 0.20, 0.5);
            assertEquals(StrengthBand.HIGH, o.projectBand(), "precondition");

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision d = pruner.decide("edge-10", o);

            assertFalse(d.shouldPrune(), "HIGH-band opinion must be kept");
        }

        @Test
        @DisplayName("PROBABLE-band opinion is kept with default policy")
        void probableBandIsKept() {
            // b=0.50, d=0.10, u=0.40, a=0.5 → expectation=0.50+0.5*0.40=0.70 ≥ 0.40 and u<0.60 → PROBABLE
            Opinion o = new Opinion(0.50, 0.10, 0.40, 0.5);
            assertEquals(StrengthBand.PROBABLE, o.projectBand(), "precondition");

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision d = pruner.decide("edge-11", o);

            assertFalse(d.shouldPrune(), "PROBABLE-band opinion must be kept");
        }
    }

    // ─── Contested (high uncertainty + mixed belief/disbelief) ───────────────

    @Nested
    @DisplayName("Contested opinion handling")
    class ContestedTests {

        @Test
        @DisplayName("contested opinion (high u) above maxUncertainty is pruned")
        void contestedAboveUncertaintyCeilingPruned() {
            // Both b and d are non-trivial, u is high
            // b=0.20, d=0.15, u=0.65, a=0.5 → isConflicted(0.10)=true; u > 0.60
            // Expectation = 0.20 + 0.5*0.65 = 0.525 (above minExpectation=0.15)
            Opinion o = new Opinion(0.20, 0.15, 0.65, 0.5);
            assertTrue(o.isConflicted(0.10), "precondition: should be conflicted");

            PrunePolicy policy = new PrunePolicy(0.10, 0.60, 0.15, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-12", o);

            assertTrue(d.shouldPrune(),
                    "Contested opinion with u=0.65 > maxUncertainty=0.60 should be pruned");
        }

        @Test
        @DisplayName("contested opinion below maxUncertainty is kept if other thresholds pass")
        void contestedBelowUncertaintyCeilingKept() {
            // b=0.30, d=0.25, u=0.45, a=0.5 → expectation=0.30+0.5*0.45=0.525 ≥ 0.15
            // band: e=0.525 ≥ 0.40 and u=0.45 < 0.60 → PROBABLE
            Opinion o = new Opinion(0.30, 0.25, 0.45, 0.5);
            assertEquals(StrengthBand.PROBABLE, o.projectBand(), "precondition");

            PrunePolicy policy = new PrunePolicy(0.10, 0.80, 0.15, false);
            OpinionPruner pruner = new OpinionPruner(policy);
            OpinionPruner.Decision d = pruner.decide("edge-13", o);

            assertFalse(d.shouldPrune(),
                    "Contested but PROBABLE opinion that passes all thresholds should be kept");
        }
    }

    // ─── Batch API ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Batch API")
    class BatchTests {

        @Test
        @DisplayName("decideBatch(map) returns one decision per entry")
        void batchMapReturnsAllDecisions() {
            Opinion established = Opinion.fromObservedValue(0.95);
            Opinion suppressed = Opinion.fromObservedValue(0.03);

            Map<String, Opinion> input = Map.of(
                    "e1", established,
                    "e2", suppressed
            );

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            Map<String, OpinionPruner.Decision> results = pruner.decideBatch(input);

            assertEquals(2, results.size());
            assertFalse(results.get("e1").shouldPrune(), "ESTABLISHED should be kept");
            assertTrue(results.get("e2").shouldPrune(), "SUPPRESSED should be pruned");
        }

        @Test
        @DisplayName("decideBatch(collection) works with OpinionEntry list")
        void batchCollectionWorks() {
            List<OpinionPruner.OpinionEntry> entries = List.of(
                    OpinionPruner.OpinionEntry.of("a", Opinion.fromObservedValue(0.9)),
                    OpinionPruner.OpinionEntry.of("b", Opinion.fromObservedValue(0.02))
            );

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            Map<String, OpinionPruner.Decision> results = pruner.decideBatch(entries);

            assertFalse(results.get("a").shouldPrune());
            assertTrue(results.get("b").shouldPrune());
        }

        @Test
        @DisplayName("null opinion in batch is treated as vacuous")
        void nullOpinionTreatedAsVacuous() {
            Map<String, Opinion> input = new java.util.LinkedHashMap<>();
            input.put("x", null);

            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            Map<String, OpinionPruner.Decision> results = pruner.decideBatch(input);

            assertTrue(results.get("x").shouldPrune(),
                    "Null opinion treated as vacuous (u=1.0 > 0.80) → should be pruned");
        }

        @Test
        @DisplayName("empty batch returns empty map")
        void emptyBatchReturnsEmpty() {
            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            assertTrue(pruner.decideBatch(Map.of()).isEmpty());
            assertTrue(pruner.decideBatch(List.of()).isEmpty());
        }
    }

    // ─── Decision record ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Decision record")
    class DecisionTests {

        @Test
        @DisplayName("shouldKeep is the negation of shouldPrune")
        void shouldKeepNegation() {
            Opinion o = Opinion.fromObservedValue(0.9);
            OpinionPruner.Decision d = new OpinionPruner(PrunePolicy.defaults()).decide("x", o);
            assertEquals(d.shouldKeep(), !d.shouldPrune());
        }

        @Test
        @DisplayName("opinion is preserved in the Decision")
        void opinionPreserved() {
            Opinion o = Opinion.fromObservedValue(0.5);
            OpinionPruner.Decision d = new OpinionPruner(PrunePolicy.defaults()).decide("y", o);
            assertEquals(o, d.opinion());
        }

        @Test
        @DisplayName("reason is non-null and non-empty for both PRUNE and KEEP")
        void reasonNonNull() {
            OpinionPruner pruner = new OpinionPruner(PrunePolicy.defaults());
            OpinionPruner.Decision pruneD = pruner.decide("a", Opinion.fromObservedValue(0.02));
            OpinionPruner.Decision keepD = pruner.decide("b", Opinion.fromObservedValue(0.9));
            assertNotNull(pruneD.reason());
            assertFalse(pruneD.reason().isBlank());
            assertNotNull(keepD.reason());
            assertFalse(keepD.reason().isBlank());
        }
    }

    // ─── Null-safety ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null-safety")
    class NullSafetyTests {

        @Test
        @DisplayName("null policy rejects at construction")
        void nullPolicyRejects() {
            assertThrows(NullPointerException.class, () -> new OpinionPruner(null));
        }

        @Test
        @DisplayName("null elementId rejects")
        void nullElementIdRejects() {
            assertThrows(NullPointerException.class,
                    () -> new OpinionPruner(PrunePolicy.defaults()).decide(null, Opinion.vacuous()));
        }

        @Test
        @DisplayName("null opinion rejects")
        void nullOpinionRejects() {
            assertThrows(NullPointerException.class,
                    () -> new OpinionPruner(PrunePolicy.defaults()).decide("x", null));
        }
    }
}
