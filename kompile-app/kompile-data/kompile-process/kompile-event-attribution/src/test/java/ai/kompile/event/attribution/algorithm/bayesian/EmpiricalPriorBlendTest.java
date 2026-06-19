/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import ai.kompile.core.events.EmpiricalPriorSource;
import ai.kompile.core.events.ObservedEventStat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmpiricalPriorBlendTest {

    /** Build a source where every node has probability {@code prob} and the given evidence strength. */
    private EmpiricalPriorSource source(double prob, double evidenceStrength, double blendK) {
        return new EmpiricalPriorSource() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public OptionalDouble priorForNode(String nodeId) {
                return OptionalDouble.of(prob);
            }

            @Override
            public OptionalDouble priorForConnection(String s, String e, String t) {
                return OptionalDouble.of(prob);
            }

            @Override
            public Optional<ObservedEventStat> statForNode(String nodeId) {
                return Optional.of(new ObservedEventStat(nodeId, "ENTITY_OCCURRENCE", prob,
                        0, 0, evidenceStrength, 0, 1, Instant.now()));
            }

            @Override
            public double blendK() {
                return blendK;
            }
        };
    }

    @Test
    void nullSourceReturnsStructural() {
        assertEquals(0.4, EmpiricalPriorBlend.forNode(0.4, null, "n1"), 1e-9);
    }

    @Test
    void disabledSourceReturnsStructural() {
        assertEquals(0.4, EmpiricalPriorBlend.forNode(0.4, EmpiricalPriorSource.DISABLED, "n1"), 1e-9);
    }

    @Test
    void noRealEvidenceReturnsStructural() {
        // evidenceStrength 2 == the ~uniform prior pseudo-counts ⇒ no real evidence ⇒ structural.
        assertEquals(0.4, EmpiricalPriorBlend.forNode(0.4, source(0.9, 2.0, 5.0), "n1"), 1e-9);
    }

    @Test
    void moderateEvidenceBlendsHalfway() {
        // real evidence 5, blendK 5 ⇒ weight 0.5 ⇒ midpoint of 0.4 and 0.9 = 0.65.
        assertEquals(0.65, EmpiricalPriorBlend.forNode(0.4, source(0.9, 7.0, 5.0), "n1"), 1e-6);
    }

    @Test
    void strongEvidenceLeansEmpirical() {
        double blended = EmpiricalPriorBlend.forNode(0.4, source(0.9, 102.0, 5.0), "n1");
        assertTrue(blended > 0.85, "expected near the empirical 0.9, got " + blended);
    }

    @Test
    void connectionUsesGatedEmpiricalPrior() {
        double blended = EmpiricalPriorBlend.forConnection(0.2, source(0.7, 50, 5), "a", "SHARED_ENTITY", "b");
        assertEquals(0.7, blended, 1e-9);
    }
}
