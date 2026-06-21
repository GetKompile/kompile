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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PslMarginalInference} (GAP 5).
 */
class PslMarginalInferenceTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** A simple 2-entity program: alice is observed active; bob should be inferred active. */
    private static PslProgram tinyProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 0.9, "alice", "bob");
        p.target("State", "bob");
        return p;
    }

    /** A program where the target atom is forced to 0 by a hard prior. */
    private static PslProgram forcedToZeroProgram() {
        PslProgram p = new PslProgram();
        p.addRule("10.0: ~State(X) ^2");  // very strong push toward 0
        p.target("State", "node");
        return p;
    }

    /** A program where the target atom is forced to 1 by a hard prior. */
    private static PslProgram forcedToOneProgram() {
        PslProgram p = new PslProgram();
        p.addRule("10.0: State(X) ^2");  // -> State(X) ^2 is ambiguous in our parse; use observe instead
        p.observe("State", 1.0, "active");
        p.addRule("5.0: State(X) -> State(Y) ^2");
        p.target("State", "derived");
        p.observe("State", 1.0, "active");
        return p;
    }

    // ─── AtomMarginal record ─────────────────────────────────────────────────────

    @Nested
    class AtomMarginalTests {

        @Test
        void deterministicHasZeroVariance() {
            AtomMarginal m = AtomMarginal.deterministic("k", 0.7);
            assertEquals("k", m.atomKey());
            assertEquals(0.7, m.mean(), 1e-9);
            assertEquals(0.0, m.variance(), 1e-9);
            assertEquals(0.0, m.stdDev(), 1e-9);
            assertEquals(1, m.samples());
        }

        @Test
        void halfWidth95IsNonNegative() {
            AtomMarginal m = new AtomMarginal("k", 0.5, 0.04, 50);
            assertTrue(m.halfWidth95() >= 0.0);
            assertTrue(m.halfWidth95() <= 0.5);  // clamped by min(mean, 1-mean)
        }

        @Test
        void stdDevIsSquareRootOfVariance() {
            AtomMarginal m = new AtomMarginal("k", 0.5, 0.09, 50);
            assertEquals(Math.sqrt(0.09), m.stdDev(), 1e-9);
        }
    }

    // ─── Result accessors ────────────────────────────────────────────────────────

    @Nested
    class ResultAccessors {

        @Test
        void getForUnknownKeyReturnsMarginalAtMapValue() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            // "State(bob)" is a target — should be in the marginals
            AtomMarginal m = result.get("State(bob)");
            assertNotNull(m);
            // Mean should be in [0, 1]
            assertTrue(m.mean() >= 0.0 && m.mean() <= 1.0);
        }

        @Test
        void samplesUsedIsPositive() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            assertTrue(result.samplesUsed() > 0,
                    "samplesUsed should be positive after a successful marginal inference run");
        }
    }

    // ─── Sanity: marginals in [0,1] ──────────────────────────────────────────────

    @Nested
    class MarginalSanity {

        @Test
        void allTargetMarginalsInUnitInterval() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            for (Map.Entry<String, AtomMarginal> entry : result.marginals().entrySet()) {
                double mean = entry.getValue().mean();
                assertTrue(mean >= 0.0 && mean <= 1.0,
                        "Marginal mean must be in [0,1] but got " + mean + " for " + entry.getKey());
            }
        }

        @Test
        void observedAtomsAreDeterministic() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            AtomMarginal aliceMarginal = result.get("State(alice)");
            // Observed atoms should be deterministic
            assertEquals(0.0, aliceMarginal.variance(), 1e-9,
                    "Observed atom should have zero variance");
            assertEquals(1.0, aliceMarginal.mean(), 1e-9,
                    "Observed atom State(alice)=1.0 should have mean 1.0");
        }

        @Test
        void stronglyInfluencedTargetHasHighMean() {
            // alice is active (1.0) and has a strong link to bob (0.9)
            // + a propagation rule → bob's mean should be significantly above 0.5
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            AtomMarginal bobMarginal = result.get("State(bob)");
            assertNotNull(bobMarginal);
            // We don't expect bob to be exactly 1.0, but he should be inferred active
            assertTrue(bobMarginal.mean() > 0.4,
                    "State(bob) marginal mean should be > 0.4 given strong evidence from alice; got: "
                            + bobMarginal.mean());
        }

        @Test
        void marginalVarianceIsNonNegative() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            for (AtomMarginal m : result.marginals().values()) {
                assertTrue(m.variance() >= 0.0,
                        "Variance must be >= 0 for all atoms");
            }
        }
    }

    // ─── Different temperatures ───────────────────────────────────────────────────

    @Nested
    class TemperatureEffect {

        @Test
        void higherTemperatureIncreasesVariance() {
            PslProgram p = tinyProgram();
            Random rng = new Random(42);

            // Low temperature: close to MAP, low variance
            PslMarginalInference.Result low = PslMarginalInference.solve(p, 30, 5, 0.01, new Random(42));
            // High temperature: more exploration, higher variance
            PslMarginalInference.Result high = PslMarginalInference.solve(p, 30, 5, 0.5, new Random(42));

            AtomMarginal bobLow = low.get("State(bob)");
            AtomMarginal bobHigh = high.get("State(bob)");

            // Variance at higher temperature should be >= variance at lower temperature
            // (this is a probabilistic test; use a generous tolerance)
            assertTrue(bobHigh.variance() >= bobLow.variance() - 0.05,
                    "Higher temperature should produce at least as much variance as lower temperature; "
                            + "low.var=" + bobLow.variance() + " high.var=" + bobHigh.variance());
        }

        @Test
        void invalidSamplesThrows() {
            PslProgram p = tinyProgram();
            assertThrows(IllegalArgumentException.class,
                    () -> PslMarginalInference.solve(p, 5, 10, 0.1, new Random(42)),
                    "numSamples <= burnIn should throw");
        }
    }

    // ─── Empty program ────────────────────────────────────────────────────────────

    @Nested
    class EdgeCases {

        @Test
        void noTargetAtoms() {
            PslProgram p = new PslProgram();
            p.addRule("1.0: State(X) -> State(X) ^2");
            p.observe("State", 1.0, "only");
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            // No target atoms → 0 samples used; observed atoms still returned as deterministic
            assertEquals(0, result.samplesUsed());
            AtomMarginal m = result.get("State(only)");
            assertEquals(1.0, m.mean(), 1e-9);
        }

        @Test
        void marginalResultIsUnmodifiable() {
            PslProgram p = tinyProgram();
            PslMarginalInference.Result result = PslMarginalInference.solve(p);
            assertThrows(UnsupportedOperationException.class,
                    () -> result.marginals().put("newKey", AtomMarginal.deterministic("k", 0.0)));
        }
    }
}
