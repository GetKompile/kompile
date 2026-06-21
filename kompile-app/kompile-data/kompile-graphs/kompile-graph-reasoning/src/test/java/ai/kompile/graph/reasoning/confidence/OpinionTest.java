/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Tests for Opinion, StrengthBand, InMemoryOpinionStore and calibrator bridge.
 */
@DisplayName("Opinion + StrengthBand + OpinionStore tests")
class OpinionTest {

    // ── Opinion record ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Opinion simplex constraint")
    class SimplexTests {

        @Test
        void vacuousHasUncertainty1() {
            Opinion v = Opinion.vacuous();
            assertEquals(0.0, v.belief(), 1e-12);
            assertEquals(0.0, v.disbelief(), 1e-12);
            assertEquals(1.0, v.uncertainty(), 1e-12);
            assertTrue(v.isVacuous());
        }

        @Test
        void expectationFormula() {
            Opinion o = new Opinion(0.6, 0.2, 0.2, 0.5);
            assertEquals(0.6 + 0.5 * 0.2, o.expectation(), 1e-9);
        }

        @Test
        void invalidSumRejects() {
            assertThrows(IllegalArgumentException.class, () ->
                new Opinion(0.5, 0.5, 0.5, 0.5));
        }

        @Test
        void negativeBeliefRejects() {
            assertThrows(IllegalArgumentException.class, () ->
                new Opinion(-0.1, 0.5, 0.6, 0.5));
        }

        @Test
        void baseRateOutOfRangeRejects() {
            assertThrows(IllegalArgumentException.class, () ->
                new Opinion(0.5, 0.3, 0.2, 1.5));
        }
    }

    @Nested
    @DisplayName("Opinion factories")
    class FactoryTests {

        @Test
        void fromSoftTruthHighEvidence() {
            Opinion o = Opinion.fromSoftTruth(0.9, 10L);
            // uncertainty = 1/(10+1) ≈ 0.0909; b ~ 0.9 * (1 - 1/11)
            assertTrue(o.uncertainty() < 0.15);
            assertTrue(o.belief() > 0.7);
        }

        @Test
        void fromBetaEvidenceSymmetric() {
            Opinion o = Opinion.fromBetaEvidence(5, 5);
            assertEquals(o.belief(), o.disbelief(), 1e-6);
        }

        @Test
        void fromBayesianPosteriorHighInfoGain() {
            Opinion o = Opinion.fromBayesianPosterior(0.9, 0.5);
            // info gain = |0.9 - 0.5| = 0.4 → u = max(0, 1 - 0.8) = 0.2
            assertTrue(o.uncertainty() < 0.3);
        }

        @Test
        void fromObservedValueZero() {
            Opinion o = Opinion.fromObservedValue(0.0);
            assertEquals(0.0, o.uncertainty(), 1e-9);
            assertEquals(1.0, o.disbelief(), 1e-9);
        }

        @Test
        void fromObservedValueOne() {
            Opinion o = Opinion.fromObservedValue(1.0);
            assertEquals(0.0, o.uncertainty(), 1e-9);
            assertEquals(1.0, o.belief(), 1e-9);
        }
    }

    @Nested
    @DisplayName("Opinion fusion operators")
    class FusionTests {

        @Test
        void cumulativeFusionReducesUncertainty() {
            Opinion a = Opinion.fromBetaEvidence(3, 1);
            Opinion b = Opinion.fromBetaEvidence(3, 1);
            Opinion fused = a.cumulativeFuse(b);
            assertTrue(fused.uncertainty() < a.uncertainty());
        }

        @Test
        void averageFusionMidpoint() {
            Opinion a = Opinion.fromObservedValue(0.8);
            Opinion b = Opinion.fromObservedValue(0.4);
            Opinion avg = a.averageFuse(b);
            assertEquals((0.8 + 0.4) / 2, avg.expectation(), 0.05);
        }

        @Test
        void consensusWeightsLowUncertaintyMore() {
            Opinion certain = Opinion.fromObservedValue(0.9);
            Opinion uncertain = Opinion.vacuous();
            Opinion c = Opinion.consensus(List.of(certain, uncertain));
            // Certain source has weight=1, uncertain has weight=0 → consensus ~ certain
            assertTrue(c.expectation() > 0.6);
        }

        @Test
        void cumulativeFuseVarargs() {
            Opinion o1 = Opinion.fromSoftTruth(0.7, 3);
            Opinion o2 = Opinion.fromSoftTruth(0.8, 3);
            Opinion o3 = Opinion.fromSoftTruth(0.9, 3);
            Opinion fused = Opinion.cumulativeFuse(o1, o2, o3);
            assertTrue(fused.uncertainty() < o1.uncertainty());
        }

        @Test
        void averageFuseVarargs() {
            Opinion avg = Opinion.averageFuse(
                Opinion.fromObservedValue(0.2),
                Opinion.fromObservedValue(0.8));
            assertEquals(0.5, avg.expectation(), 0.05);
        }
    }

    @Nested
    @DisplayName("Opinion projection")
    class ProjectionTests {

        @Test
        void highBeliefProjectsSupported() {
            Opinion o = Opinion.fromObservedValue(0.9);
            assertEquals(Opinion.VerifyStatusProjection.SUPPORTED, o.projectStatus());
        }

        @Test
        void highDisbeliefProjectsRefuted() {
            Opinion o = Opinion.fromObservedValue(0.05);
            assertEquals(Opinion.VerifyStatusProjection.REFUTED, o.projectStatus());
        }

        @Test
        void vacuousProjectsUnknown() {
            assertEquals(Opinion.VerifyStatusProjection.UNKNOWN, Opinion.vacuous().projectStatus());
        }

        @Test
        void establishedBand() {
            Opinion o = Opinion.fromObservedValue(0.95);
            assertEquals(StrengthBand.ESTABLISHED, o.projectBand());
        }

        @Test
        void suppressedBand() {
            Opinion o = Opinion.fromObservedValue(0.05);
            assertEquals(StrengthBand.SUPPRESSED, o.projectBand());
        }
    }

    @Nested
    @DisplayName("Opinion JSON round-trip")
    class JsonTests {

        @Test
        void roundTrip() {
            Opinion original = new Opinion(0.5, 0.3, 0.2, 0.6);
            String json = original.toJson();
            Opinion parsed = Opinion.fromJson(json);
            assertEquals(original.belief(), parsed.belief(), 1e-7);
            assertEquals(original.disbelief(), parsed.disbelief(), 1e-7);
            assertEquals(original.uncertainty(), parsed.uncertainty(), 1e-7);
            assertEquals(original.baseRate(), parsed.baseRate(), 1e-7);
        }
    }

    // ── StrengthBand ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("StrengthBand bridge methods")
    class StrengthBandTests {

        @Test
        void toAttributionConfidenceBridgeWorks() {
            assertEquals(ai.kompile.graph.reasoning.domain.AttributionConfidence.DEFINITIVE,
                StrengthBand.ESTABLISHED.toAttributionConfidence());
            assertEquals(ai.kompile.graph.reasoning.domain.AttributionConfidence.INSUFFICIENT,
                StrengthBand.SUPPRESSED.toAttributionConfidence());
        }

        @Test
        void fromAttributionConfidenceBridgeWorks() {
            assertEquals(StrengthBand.ESTABLISHED,
                StrengthBand.fromAttributionConfidence(
                    ai.kompile.graph.reasoning.domain.AttributionConfidence.DEFINITIVE));
        }

        @Test
        void fromScalarHighConfidence() {
            assertEquals(StrengthBand.ESTABLISHED, StrengthBand.fromScalar(0.95));
        }

        @Test
        void fromScalarLowConfidence() {
            // fromScalar(0.05) → fromObservedValue(0.05) which has e~0.05,u=0 → SUPPRESSED
            assertEquals(StrengthBand.SUPPRESSED, StrengthBand.fromScalar(0.05));
        }
    }

    // ── InMemoryOpinionStore ──────────────────────────────────────────────────

    @Nested
    @DisplayName("InMemoryOpinionStore")
    class StoreTests {

        @Test
        void putAndGet() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            Opinion o = Opinion.fromObservedValue(0.7);
            store.put("foo(bar)", o);
            Opinion retrieved = store.get("foo(bar)");
            assertEquals(o.belief(), retrieved.belief(), 1e-9);
        }

        @Test
        void absentReturnsVacuous() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            assertTrue(store.get("missing").isVacuous());
        }

        @Test
        void hasReturnsFalseForAbsent() {
            assertFalse(new InMemoryOpinionStore().has("x"));
        }

        @Test
        void clearEmptiesStore() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            store.put("a", Opinion.vacuous());
            store.clear();
            assertEquals(0, store.size());
        }

        @Test
        void sizeAndEntries() {
            InMemoryOpinionStore store = new InMemoryOpinionStore();
            store.put("a", Opinion.vacuous());
            store.put("b", Opinion.vacuous());
            assertEquals(2, store.size());
            assertEquals(2, store.entries().size());
        }

        @Test
        void nullKeyRejects() {
            assertThrows(IllegalArgumentException.class, () ->
                new InMemoryOpinionStore().put(null, Opinion.vacuous()));
        }

        @Test
        void nullOpinionRejects() {
            assertThrows(IllegalArgumentException.class, () ->
                new InMemoryOpinionStore().put("k", null));
        }
    }
}
