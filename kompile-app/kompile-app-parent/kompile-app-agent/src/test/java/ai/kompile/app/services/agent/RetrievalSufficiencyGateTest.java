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

import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure sufficiency scorer for the grounded-RAG abstention gate. Verifies the score behaves
 * sensibly (empty → 0, weak → low, strong+broad → high, more on-topic sources → higher, stays in [0,1]).
 */
class RetrievalSufficiencyGateTest {

    private static RetrievedDoc doc(String id, double score) {
        return new RetrievedDoc(id, "text of " + id, Map.of(), score);
    }

    @Test
    void emptyOrNull_scoresZero() {
        assertEquals(0.0, RetrievalSufficiencyGate.score(null, 3), 1e-9);
        assertEquals(0.0, RetrievalSufficiencyGate.score(List.of(), 3), 1e-9);
    }

    @Test
    void weakSingleSource_belowTypicalThreshold() {
        double s = RetrievalSufficiencyGate.score(List.of(doc("a", 0.1)), 3);
        assertTrue(s < 0.3, "one weak source should be insufficient: " + s);
    }

    @Test
    void strongBroadEvidence_scoresHigh() {
        double s = RetrievalSufficiencyGate.score(
                List.of(doc("a", 0.9), doc("b", 0.9), doc("c", 0.9)), 3);
        assertTrue(s > 0.7, "3 strong sources should be sufficient: " + s);
    }

    @Test
    void breadthRewardsMoreOnTopicSources() {
        double few = RetrievalSufficiencyGate.score(List.of(doc("a", 0.5)), 3);
        double more = RetrievalSufficiencyGate.score(
                List.of(doc("a", 0.5), doc("b", 0.5), doc("c", 0.5)), 3);
        assertTrue(more > few, "more on-topic sources → higher sufficiency (" + few + " vs " + more + ")");
    }

    @Test
    void scoreStaysInUnitInterval_evenWithOutOfRangeScores() {
        double s = RetrievalSufficiencyGate.score(List.of(doc("a", 2.0), doc("b", -1.0)), 1);
        assertTrue(s >= 0.0 && s <= 1.0, "score must stay in [0,1]: " + s);
    }
}
