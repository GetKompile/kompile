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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure escalation ladder for CRAG corrective retrieval (grounded-RAG rec 3): widen the graph search
 * mode toward broader recall, broadest-last, and stop once GLOBAL is reached.
 */
class CorrectiveRetrievalPolicyTest {

    @Test
    void localWidensToHybridThenGlobal() {
        assertEquals(List.of("HYBRID", "GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder("LOCAL"));
    }

    @Test
    void hybridWidensToGlobal() {
        assertEquals(List.of("GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder("HYBRID"));
    }

    @Test
    void globalIsAlreadyBroadest() {
        assertEquals(List.of(), CorrectiveRetrievalPolicy.escalationLadder("GLOBAL"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(List.of("GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder("  hybrid "));
    }

    @Test
    void unknownOrReasoningModesFallBackToBroadening() {
        // null / blank / CAUSAL / PROBABILISTIC → try HYBRID then GLOBAL
        assertEquals(List.of("HYBRID", "GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder(null));
        assertEquals(List.of("HYBRID", "GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder(""));
        assertEquals(List.of("HYBRID", "GLOBAL"), CorrectiveRetrievalPolicy.escalationLadder("CAUSAL"));
    }
}
