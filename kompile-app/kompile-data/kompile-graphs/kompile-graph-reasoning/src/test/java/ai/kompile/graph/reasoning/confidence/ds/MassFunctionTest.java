/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence.ds;

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MassFunction}.
 */
@DisplayName("MassFunction — BBA on binary frame")
class MassFunctionTest {

    private static final double EPS = 1e-9;

    // ─── fromOpinion mapping ─────────────────────────────────────────────────

    @Test
    @DisplayName("fromOpinion: mT=b, mF=d, mTF=u")
    void fromOpinionMapping() {
        Opinion op = new Opinion(0.6, 0.2, 0.2, 0.5);
        MassFunction bba = MassFunction.fromOpinion(op);
        assertEquals(op.belief(),      bba.mT(),  EPS, "mT = belief");
        assertEquals(op.disbelief(),   bba.mF(),  EPS, "mF = disbelief");
        assertEquals(op.uncertainty(), bba.mTF(), EPS, "mTF = uncertainty");
        assertEquals(0.0,              bba.mEmpty(), EPS, "mEmpty = 0 (closed-world)");
    }

    // ─── Opinion round-trip ──────────────────────────────────────────────────

    @Test
    @DisplayName("round-trip: Opinion → fromOpinion → toOpinion → same b/d/u")
    void opinionRoundTrip() {
        Opinion original = new Opinion(0.6, 0.2, 0.2, 0.5);
        MassFunction bba    = MassFunction.fromOpinion(original);
        Opinion      reconv = bba.toOpinion(0.5);
        assertEquals(original.belief(),      reconv.belief(),      EPS, "belief");
        assertEquals(original.disbelief(),   reconv.disbelief(),   EPS, "disbelief");
        assertEquals(original.uncertainty(), reconv.uncertainty(), EPS, "uncertainty");
    }

    @Test
    @DisplayName("round-trip with different base rate preserves b/d/u")
    void opinionRoundTripDifferentBaseRate() {
        Opinion original = new Opinion(0.3, 0.4, 0.3, 0.6);
        MassFunction bba    = MassFunction.fromOpinion(original);
        Opinion      reconv = bba.toOpinion(0.6);
        assertEquals(original.belief(),      reconv.belief(),      EPS, "belief");
        assertEquals(original.disbelief(),   reconv.disbelief(),   EPS, "disbelief");
        assertEquals(original.uncertainty(), reconv.uncertainty(), EPS, "uncertainty");
    }

    // ─── of() validation ────────────────────────────────────────────────────

    @Test
    @DisplayName("of() throws when masses sum > 1")
    void ofValidatesSum() {
        assertThrows(IllegalArgumentException.class,
                () -> MassFunction.of(0.6, 0.6, 0.2),
                "Masses sum to 1.4 — should throw");
    }

    @Test
    @DisplayName("of() throws when masses sum < 1")
    void ofValidatesUnder() {
        assertThrows(IllegalArgumentException.class,
                () -> MassFunction.of(0.2, 0.2, 0.2),
                "Masses sum to 0.6 — should throw");
    }

    @Test
    @DisplayName("of() accepts valid masses summing to 1.0")
    void ofAcceptsValid() {
        assertDoesNotThrow(() -> MassFunction.of(0.5, 0.3, 0.2));
    }

    // ─── total() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("total() ≈ 1.0 for closed-world BBA")
    void totalIsOne() {
        MassFunction bba = MassFunction.of(0.5, 0.3, 0.2);
        assertEquals(1.0, bba.total(), EPS);
    }

    @Test
    @DisplayName("total() ≈ 1.0 for open-world BBA")
    void totalIsOneOpenWorld() {
        MassFunction bba = MassFunction.ofOpen(0.3, 0.2, 0.1, 0.4);
        assertEquals(1.0, bba.total(), EPS);
    }

    // ─── Belief and Plausibility ──────────────────────────────────────────────

    @Test
    @DisplayName("belT and plT semantics")
    void belAndPlT() {
        MassFunction bba = MassFunction.of(0.4, 0.2, 0.4);
        assertEquals(0.4,        bba.belT(), EPS, "bel(T) = m(T)");
        assertEquals(0.4 + 0.4, bba.plT(),  EPS, "pl(T)  = m(T) + m(TF)");
    }

    @Test
    @DisplayName("belF and plF semantics")
    void belAndPlF() {
        MassFunction bba = MassFunction.of(0.4, 0.2, 0.4);
        assertEquals(0.2,        bba.belF(), EPS, "bel(F) = m(F)");
        assertEquals(0.2 + 0.4, bba.plF(),  EPS, "pl(F)  = m(F) + m(TF)");
    }

    // ─── closedWorld() ───────────────────────────────────────────────────────

    @Test
    @DisplayName("closedWorld() when mEmpty=0 returns same instance logically")
    void closedWorldNoop() {
        MassFunction bba = MassFunction.of(0.5, 0.3, 0.2);
        MassFunction cw  = bba.closedWorld();
        assertEquals(bba.mT(),  cw.mT(),  EPS);
        assertEquals(bba.mF(),  cw.mF(),  EPS);
        assertEquals(bba.mTF(), cw.mTF(), EPS);
        assertEquals(0.0,       cw.mEmpty(), EPS);
    }

    @Test
    @DisplayName("closedWorld() distributes mEmpty proportionally and total=1")
    void closedWorldDistributes() {
        // m(T)=0.3, m(F)=0.2, m(TF)=0.1, m(∅)=0.4 → total=1.0
        MassFunction bba = MassFunction.ofOpen(0.3, 0.2, 0.1, 0.4);
        MassFunction cw  = bba.closedWorld();
        assertEquals(0.0,  cw.mEmpty(), EPS, "mEmpty should be 0 after closedWorld");
        assertEquals(1.0,  cw.total(),  EPS, "total should be 1.0");
        // All masses >= 0
        assertTrue(cw.mT()  >= 0);
        assertTrue(cw.mF()  >= 0);
        assertTrue(cw.mTF() >= 0);
    }

    // ─── Negative mass guard ─────────────────────────────────────────────────

    @Test
    @DisplayName("constructor throws on negative mass")
    void negativeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new MassFunction(-0.1, 0.5, 0.6, 0.0));
    }
}
