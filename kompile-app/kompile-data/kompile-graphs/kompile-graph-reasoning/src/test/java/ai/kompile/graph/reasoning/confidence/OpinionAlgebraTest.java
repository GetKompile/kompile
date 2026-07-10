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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP4 — the Opinion algebra (conjoin ⊙, discount ⊗, complement, conflict) plus the WP1b
 * {@code fromBetaEvidence} NaN guard. Every operator must preserve the subjective-logic simplex
 * and satisfy its defining identity (Jøsang, Subjective Logic 2016).
 */
@DisplayName("WP4 Opinion algebra")
class OpinionAlgebraTest {

    private static final double EPS = 1e-9;

    private static void assertSimplex(Opinion o) {
        double sum = o.belief() + o.disbelief() + o.uncertainty();
        assertEquals(1.0, sum, EPS, "b+d+u must equal 1: " + o);
        assertTrue(o.belief() >= -EPS && o.disbelief() >= -EPS && o.uncertainty() >= -EPS,
                "components non-negative: " + o);
    }

    @Test
    void conjoin_expectationIsMultiplicative_andSimplexHolds() {
        Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
        Opinion y = new Opinion(0.5, 0.3, 0.2, 0.4);
        Opinion xy = x.conjoin(y);
        assertSimplex(xy);
        // E(x⊙y) = E(x)·E(y) — the defining identity of the Jøsang binomial product.
        assertEquals(x.expectation() * y.expectation(), xy.expectation(), EPS);
    }

    @Test
    void conjoin_isCommutative() {
        Opinion x = new Opinion(0.7, 0.1, 0.2, 0.6);
        Opinion y = new Opinion(0.3, 0.4, 0.3, 0.35);
        Opinion xy = x.conjoin(y);
        Opinion yx = y.conjoin(x);
        assertEquals(xy.belief(), yx.belief(), EPS);
        assertEquals(xy.disbelief(), yx.disbelief(), EPS);
        assertEquals(xy.uncertainty(), yx.uncertainty(), EPS);
        assertEquals(xy.baseRate(), yx.baseRate(), EPS);
    }

    @Test
    void conjoin_withVacuousIsSaneAndMultiplicative() {
        Opinion x = new Opinion(0.6, 0.3, 0.1, 0.5);
        Opinion v = Opinion.vacuous(0.5);
        Opinion r = x.conjoin(v);
        assertSimplex(r);
        assertEquals(x.expectation() * v.expectation(), r.expectation(), EPS);
    }

    @Test
    void conjoinAll_leftFoldsAndStaysOnSimplex() {
        Opinion a = new Opinion(0.8, 0.1, 0.1, 0.5);
        Opinion b = new Opinion(0.7, 0.2, 0.1, 0.5);
        Opinion c = new Opinion(0.9, 0.0, 0.1, 0.5);
        Opinion all = Opinion.conjoinAll(a, b, c);
        assertSimplex(all);
        assertEquals(a.expectation() * b.expectation() * c.expectation(), all.expectation(), EPS);
        assertTrue(Opinion.conjoinAll().isVacuous(), "empty conjoinAll is vacuous");
    }

    @Test
    void discount_oneIsIdentity_zeroIsVacuousSameBaseRate() {
        Opinion x = new Opinion(0.6, 0.25, 0.15, 0.4);
        Opinion identity = x.discount(1.0);
        assertEquals(x.belief(), identity.belief(), EPS);
        assertEquals(x.disbelief(), identity.disbelief(), EPS);
        assertEquals(x.uncertainty(), identity.uncertainty(), EPS);

        Opinion zero = x.discount(0.0);
        assertTrue(zero.isVacuous());
        assertEquals(x.baseRate(), zero.baseRate(), EPS);

        Opinion half = x.discount(0.5);
        assertSimplex(half);
        assertEquals(0.5 * x.belief(), half.belief(), EPS);
        assertEquals(0.5 * x.disbelief(), half.disbelief(), EPS);
    }

    @Test
    void complement_isSelfInverse_andSwapsBeliefDisbelief() {
        Opinion x = new Opinion(0.6, 0.25, 0.15, 0.4);
        Opinion c = x.complement();
        assertSimplex(c);
        assertEquals(x.disbelief(), c.belief(), EPS);
        assertEquals(x.belief(), c.disbelief(), EPS);
        assertEquals(1.0 - x.baseRate(), c.baseRate(), EPS);
        Opinion back = c.complement();
        assertEquals(x.belief(), back.belief(), EPS);
        assertEquals(x.disbelief(), back.disbelief(), EPS);
        assertEquals(x.baseRate(), back.baseRate(), EPS);
    }

    @Test
    void conflict_isSymmetric_zeroForVacuous_oneForDogmaticOpposed() {
        Opinion x = new Opinion(0.7, 0.1, 0.2, 0.5);
        Opinion y = new Opinion(0.2, 0.6, 0.2, 0.5);
        assertEquals(x.conflict(y), y.conflict(x), EPS);

        assertEquals(0.0, Opinion.vacuous().conflict(Opinion.vacuous()), EPS);

        Opinion believe = new Opinion(1.0, 0.0, 0.0, 0.5);
        Opinion disbelieve = new Opinion(0.0, 1.0, 0.0, 0.5);
        assertEquals(1.0, believe.conflict(disbelieve), EPS);
    }

    @Test
    void fromBetaEvidence_zeroTotalIsVacuous_notNaN() {
        // WP1b: pos+neg+k == 0 previously produced 0/0 = NaN.
        Opinion o = Opinion.fromBetaEvidence(0.0, 0.0, 0.5, 0.0);
        assertTrue(o.isVacuous(), "no evidence + no prior strength → vacuous");
        assertSimplex(o);
        assertEquals(0.5, o.baseRate(), EPS);
    }
}
