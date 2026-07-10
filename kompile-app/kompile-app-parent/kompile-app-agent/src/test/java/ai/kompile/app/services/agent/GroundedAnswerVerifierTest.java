/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grounded-answer verification loop (grounded-RAG rec 1): the pure caveat policy and the per-claim
 * verdict aggregation against a lambda oracle (no Spring/mocks — the aggregation is a pure function).
 */
class GroundedAnswerVerifierTest {

    @Test
    void buildCaveat_allSupported_isReassuring_notAWarning() {
        String c = GroundedAnswerVerifier.buildCaveat(3, 0, 0);
        assertTrue(c.contains("all 3"), c);
        assertFalse(c.startsWith("⚠"), c);
    }

    @Test
    void buildCaveat_refutedOrUnknown_warns() {
        String c = GroundedAnswerVerifier.buildCaveat(1, 1, 2);
        assertTrue(c.startsWith("⚠"), c);
        assertTrue(c.contains("1 claim(s) CONTRADICTED"), c);
        assertTrue(c.contains("2 claim(s) could not be verified"), c);
    }

    @Test
    void buildCaveat_noneChecked_isEmpty() {
        assertEquals("", GroundedAnswerVerifier.buildCaveat(0, 0, 0));
    }

    @Test
    void isActive_falseWithoutConfigOrCollaborators() {
        assertFalse(new GroundedAnswerVerifier().isActive());
    }

    @Test
    void aggregate_talliesPerClaimVerdictsFromTheOracle() {
        Map<String, VerifyResult> oracle = Map.of(
                "worksAt(alice, acme)", new VerifyResult(VerifyResult.Status.SUPPORTED, 0.9, List.of("e1")),
                "ceoOf(bob, acme)", new VerifyResult(VerifyResult.Status.REFUTED, 0.8, List.of()),
                "livesIn(x, y)", new VerifyResult(VerifyResult.Status.UNKNOWN, 0.0, List.of()));

        GroundedAnswerVerifier.AnswerVerification r = GroundedAnswerVerifier.aggregate(
                List.of("worksAt(alice, acme)", "ceoOf(bob, acme)", "livesIn(x, y)"), oracle::get);

        assertTrue(r.ran());
        assertEquals(3, r.verdicts().size());
        assertEquals(1, r.supported());
        assertEquals(1, r.refuted());
        assertEquals(1, r.unknown());
        assertTrue(r.caveat().startsWith("⚠"), r.caveat());
    }

    @Test
    void aggregate_noVerifiableClaims_isNotRun() {
        // a verify fn that returns null for every atom → all skipped → no verdicts → notRun
        GroundedAnswerVerifier.AnswerVerification none =
                GroundedAnswerVerifier.aggregate(List.of("p(a, b)", "q(c, d)"), atom -> null);
        assertFalse(none.ran());
        assertEquals(0, none.verdicts().size());
    }
}
