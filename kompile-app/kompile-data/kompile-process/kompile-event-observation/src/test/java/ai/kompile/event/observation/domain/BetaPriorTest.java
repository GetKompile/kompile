/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.observation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetaPriorTest {

    @Test
    void uniformMeanIsHalf() {
        assertEquals(0.5, BetaPrior.uniform().mean(), 1e-9);
    }

    @Test
    void updateRaisesMeanWithSuccesses() {
        BetaPrior b = BetaPrior.uniform(); // (1,1)
        b.update(8, 10); // +8 successes, +2 failures -> (9,3)
        assertEquals(9.0 / 12.0, b.mean(), 1e-9);
        assertTrue(b.mean() > 0.5);
    }

    @Test
    void evidenceStrengthGrowsByOpportunities() {
        BetaPrior b = BetaPrior.uniform();
        double before = b.evidenceStrength();
        b.update(3, 5);
        assertEquals(before + 5, b.evidenceStrength(), 1e-9);
    }

    @Test
    void decayFactorFollowsHalfLife() {
        assertEquals(1.0, BetaPrior.decayFactor(0, 10), 1e-9);
        assertEquals(0.5, BetaPrior.decayFactor(10, 10), 1e-9);
        assertEquals(0.25, BetaPrior.decayFactor(20, 10), 1e-9);
    }

    @Test
    void decayTowardRevertsToPriorAtFullDecay() {
        BetaPrior b = BetaPrior.of(9, 3);
        b.decayToward(1, 1, 0.0);
        assertEquals(1.0, b.alpha(), 1e-9);
        assertEquals(1.0, b.beta(), 1e-9);
        assertEquals(0.5, b.mean(), 1e-9);
    }

    @Test
    void decayTowardIsNoOpAtFactorOne() {
        BetaPrior b = BetaPrior.of(9, 3);
        b.decayToward(1, 1, 1.0);
        assertEquals(9, b.alpha(), 1e-9);
        assertEquals(3, b.beta(), 1e-9);
    }

    @Test
    void credibleIntervalBracketsMeanWithinUnit() {
        BetaPrior b = BetaPrior.of(20, 5);
        double[] ci = b.credibleInterval95();
        assertTrue(ci[0] <= b.mean() && b.mean() <= ci[1]);
        assertTrue(ci[0] >= 0.0 && ci[1] <= 1.0);
    }

    @Test
    void rejectsNonPositiveParameters() {
        assertThrows(IllegalArgumentException.class, () -> new BetaPrior(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BetaPrior(1, -1));
    }
}
