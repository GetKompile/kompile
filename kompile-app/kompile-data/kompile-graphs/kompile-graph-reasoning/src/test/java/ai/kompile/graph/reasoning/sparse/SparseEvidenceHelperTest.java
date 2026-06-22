/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.sparse;

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SparseEvidenceHelper}.
 *
 * <p>Key contracts verified:</p>
 * <ul>
 *   <li>Unobserved / absent edges → high uncertainty (u &gt; 0.7), low disbelief (d &lt; 0.3).</li>
 *   <li>Fully-observed fact (10/10) → high belief.</li>
 *   <li>Partial observation (1/20) → low belief but nonzero.</li>
 *   <li>Vacuous opinions satisfy {@link Opinion#isVacuous()}.</li>
 *   <li>Input validation rejects negative evidence or pos &gt; total.</li>
 * </ul>
 */
@DisplayName("SparseEvidenceHelper tests")
class SparseEvidenceHelperTest {

    // ── Absent / unobserved edges → must be vacuous / near-vacuous ───────────

    @Nested
    @DisplayName("Absent-edge opinions: uncertainty not disbelief")
    class AbsentEdgeOpinions {

        @Test
        @DisplayName("opinionForUnobservedPair() is fully vacuous")
        void unobservedPairIsVacuous() {
            Opinion o = SparseEvidenceHelper.opinionForUnobservedPair();
            assertTrue(o.isVacuous(),
                "unobserved pair must yield vacuous opinion; got " + o);
        }

        @Test
        @DisplayName("opinionForUnobservedPair() has uncertainty=1.0")
        void unobservedPairHasMaxUncertainty() {
            Opinion o = SparseEvidenceHelper.opinionForUnobservedPair();
            assertEquals(1.0, o.uncertainty(), 1e-12,
                "uncertainty must be exactly 1.0 for unobserved pair");
        }

        @Test
        @DisplayName("opinionForUnobservedPair() has disbelief=0.0")
        void unobservedPairHasZeroDisbelief() {
            Opinion o = SparseEvidenceHelper.opinionForUnobservedPair();
            assertEquals(0.0, o.disbelief(), 1e-12,
                "disbelief must be 0 for unobserved pair (absence ≠ falsity in sparse graph)");
        }

        @Test
        @DisplayName("opinionForAbsentInSparse() is vacuous (same as unobserved)")
        void absentInSparseIsVacuous() {
            Opinion o = SparseEvidenceHelper.opinionForAbsentInSparse();
            assertTrue(o.isVacuous(),
                "absent-in-sparse must yield vacuous opinion; got " + o);
            assertEquals(0.0, o.disbelief(), 1e-12);
            assertTrue(o.uncertainty() > 0.7,
                "uncertainty must be high (> 0.7); got " + o.uncertainty());
        }

        @Test
        @DisplayName("opinionForAbsentInSparse(baseRate) preserves vacuousness with custom base rate")
        void absentInSparseWithBaseRate() {
            Opinion o = SparseEvidenceHelper.opinionForAbsentInSparse(0.05);
            assertTrue(o.isVacuous(), "must still be vacuous even with non-default base rate");
            assertEquals(0.05, o.baseRate(), 1e-12, "base rate must be preserved");
            assertEquals(0.0, o.disbelief(), 1e-12);
        }

        @Test
        @DisplayName("Generic property: unobservedPair has u > 0.7 and d < 0.3")
        void unobservedPairHighUncertaintyLowDisbelief() {
            Opinion o = SparseEvidenceHelper.opinionForUnobservedPair();
            assertTrue(o.uncertainty() > 0.7,
                "uncertainty should be > 0.7; got " + o.uncertainty());
            assertTrue(o.disbelief() < 0.3,
                "disbelief should be < 0.3; got " + o.disbelief());
        }
    }

    // ── Observed facts: high belief when strong evidence ─────────────────────

    @Nested
    @DisplayName("Observed-fact opinions")
    class ObservedFactOpinions {

        @Test
        @DisplayName("opinionForObservedFact(10, 10) has high belief")
        void fullyObservedHighBelief() {
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(10, 10);
            // Beta: pos=10, neg=0, k=2 → b = 10/12, d = 0/12, u = 2/12 ≈ 0.167
            assertTrue(o.belief() > 0.7,
                "fully observed (10/10) should have high belief; got " + o.belief());
            assertEquals(0.0, o.disbelief(), 1e-9,
                "no negative evidence → disbelief must be 0");
        }

        @Test
        @DisplayName("opinionForObservedFact(10, 10) still has nonzero uncertainty from prior")
        void fullyObservedNonzeroUncertainty() {
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(10, 10);
            // k=2 → u = 2/(10+0+2) = 2/12 ≈ 0.167; nonzero (not fully certain)
            assertTrue(o.uncertainty() > 0.0,
                "Beta prior keeps some residual uncertainty; got " + o.uncertainty());
            assertTrue(o.uncertainty() < 0.3,
                "uncertainty should be relatively low for 10/10; got " + o.uncertainty());
        }

        @Test
        @DisplayName("opinionForObservedFact(1, 20) has low belief")
        void partialObservationLowBelief() {
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(1, 20);
            // pos=1, neg=19, k=2 → b = 1/22, d = 19/22 → mostly disbelief
            assertTrue(o.belief() < 0.2,
                "1/20 should yield low belief; got " + o.belief());
        }

        @Test
        @DisplayName("opinionForObservedFact(5, 10) is midrange belief")
        void midrangeObservation() {
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(5, 10);
            // pos=5, neg=5, k=2 → b = 5/12, d = 5/12, u = 2/12; belief ≈ 0.417
            assertTrue(o.belief() > 0.2 && o.belief() < 0.7,
                "5/10 should yield midrange belief; got " + o.belief());
            assertEquals(o.belief(), o.disbelief(), 1e-9,
                "equal pos and neg evidence → belief should equal disbelief");
        }

        @Test
        @DisplayName("opinionForObservedFact(0, 0) yields vacuous opinion")
        void zeroObservationsVacuous() {
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(0, 0);
            // pos=0, neg=0, k=2 → b = 0/2, d = 0/2, u = 2/2 = 1.0 → vacuous
            assertTrue(o.isVacuous(),
                "zero observations should yield vacuous opinion; got " + o);
        }

        @Test
        @DisplayName("opinionForObservedFact with custom prior strength")
        void customPriorStrengthStructural() {
            // structural prior strength W=0.1 → single positive obs yields high belief
            Opinion o = SparseEvidenceHelper.opinionForObservedFact(1.0, 1.0, 0.5, 0.1);
            // pos=1, neg=0, k=0.1 → b = 1/1.1 ≈ 0.909; high belief
            assertTrue(o.belief() > 0.8,
                "structural prior (k=0.1) with 1 observation should yield high belief; got " + o.belief());
        }
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("Negative positiveEvidence rejects")
        void negativePositiveEvidenceRejects() {
            assertThrows(IllegalArgumentException.class,
                () -> SparseEvidenceHelper.opinionForObservedFact(-1.0, 5.0));
        }

        @Test
        @DisplayName("Negative totalObservations rejects")
        void negativeTotalObservationsRejects() {
            assertThrows(IllegalArgumentException.class,
                () -> SparseEvidenceHelper.opinionForObservedFact(0.0, -1.0));
        }

        @Test
        @DisplayName("positiveEvidence > totalObservations rejects")
        void positiveMayNotExceedTotal() {
            assertThrows(IllegalArgumentException.class,
                () -> SparseEvidenceHelper.opinionForObservedFact(10.0, 5.0));
        }

        @Test
        @DisplayName("Custom overload rejects negative evidence too")
        void customOverloadValidates() {
            assertThrows(IllegalArgumentException.class,
                () -> SparseEvidenceHelper.opinionForObservedFact(-1.0, 5.0, 0.5, 2.0));
        }
    }

    // ── Contrast: closed-world vs open-world ─────────────────────────────────

    @Nested
    @DisplayName("Epistemic contrast: sparse absence vs confirmed absence")
    class EpistemicContrast {

        @Test
        @DisplayName("Sparse absence yields higher uncertainty than confirmed absence")
        void sparseAbsenceHigherUncertaintyThanConfirmedAbsence() {
            Opinion sparseAbsent = SparseEvidenceHelper.opinionForAbsentInSparse();
            // In a closed-world setting, confirmed absence = disbelief
            Opinion confirmedAbsent = Opinion.fromObservedValue(0.0);

            assertTrue(sparseAbsent.uncertainty() > confirmedAbsent.uncertainty(),
                "sparse absence must be more uncertain than confirmed absence");
            assertTrue(confirmedAbsent.disbelief() > sparseAbsent.disbelief(),
                "confirmed absence must have higher disbelief than sparse absence");
        }

        @Test
        @DisplayName("Observed fact yields higher belief than sparse-absent opinion")
        void observedFactHigherBeliefThanAbsent() {
            Opinion observed = SparseEvidenceHelper.opinionForObservedFact(10, 10);
            Opinion absent = SparseEvidenceHelper.opinionForAbsentInSparse();
            assertTrue(observed.belief() > absent.belief(),
                "observed fact should have much higher belief than sparse absent");
        }
    }

    // ── Simplex constraint ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Simplex constraint: b + d + u = 1.0")
    class SimplexConstraint {

        @Test
        @DisplayName("All factory outputs satisfy simplex constraint")
        void allOutputsSatisfySimplex() {
            Opinion[] opinions = {
                SparseEvidenceHelper.opinionForUnobservedPair(),
                SparseEvidenceHelper.opinionForAbsentInSparse(),
                SparseEvidenceHelper.opinionForAbsentInSparse(0.1),
                SparseEvidenceHelper.opinionForObservedFact(10, 10),
                SparseEvidenceHelper.opinionForObservedFact(5, 10),
                SparseEvidenceHelper.opinionForObservedFact(1, 20),
                SparseEvidenceHelper.opinionForObservedFact(0, 0),
                SparseEvidenceHelper.opinionForObservedFact(1.0, 1.0, 0.5, 0.1),
            };
            for (Opinion o : opinions) {
                double sum = o.belief() + o.disbelief() + o.uncertainty();
                assertEquals(1.0, sum, 1e-9,
                    "simplex constraint violated: b+d+u=" + sum + " for " + o);
            }
        }
    }
}
