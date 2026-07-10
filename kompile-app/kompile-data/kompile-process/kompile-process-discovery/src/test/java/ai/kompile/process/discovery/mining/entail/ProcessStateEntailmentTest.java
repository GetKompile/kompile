/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.discovery.mining.entail;

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStateEntailmentTest {

    @Test
    void entailsProcessStateFactsFromSemanticAtoms() {
        Event gate = new Event("case:1", "Confidence Gate",
                LocalDateTime.of(2026, 6, 30, 9, 0), "evt:gate", Map.of(
                "controlId", "C-04",
                "approvalPolicy", "owner sign-off",
                "routingPolicy", "below threshold route",
                "confidenceThreshold", 0.85,
                "slaSeconds", 3600,
                "slaBreached", true,
                "actionType", "ESCALATE",
                "remediationAction", "REWORK_FORECAST"));
        Event ready = new Event("case:2", "Validated Package",
                LocalDateTime.of(2026, 6, 30, 10, 0), "evt:ready", Map.of(
                "actionType", "VALIDATE",
                "status", "READY"));

        ProcessStateEntailment.Result result = ProcessStateEntailment.entail(new EventLog(List.of(
                new Trace("case:1", List.of(gate)),
                new Trace("case:2", List.of(ready)))));

        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.CONTROLLED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.APPROVAL_REQUIRED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.ROUTED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.THRESHOLD_GUARDED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.SLA_GOVERNED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.SLA_BREACH));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.ESCALATION_REQUIRED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.REMEDIATION_REQUIRED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.CONTROL_EFFECT));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.BLOCKED));
        assertTrue(result.hasState("Confidence Gate", ProcessStateEntailment.StateType.REMEDIATION_SEVERITY));
        assertTrue(result.hasState("Validated Package", ProcessStateEntailment.StateType.VALIDATED));
        assertTrue(result.hasState("Validated Package", ProcessStateEntailment.StateType.CONTROL_EFFECT));
        assertTrue(result.hasState("Validated Package", ProcessStateEntailment.StateType.READY));
        assertEquals("3600", result.byType(ProcessStateEntailment.StateType.SLA_GOVERNED).get(0).value());
        assertTrue(result.byType(ProcessStateEntailment.StateType.REMEDIATION_SEVERITY).stream()
                .anyMatch(state -> "MEDIUM".equals(state.object())));
    }
}
