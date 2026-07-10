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
import ai.kompile.process.ontology.OntologyRelationSchemaCompiler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSemanticAtomExtractorTest {

    @Test
    void extractsPolicyAndControlAtomsFromGraphEventAttributes() {
        Event validates = new Event("case:1", "Control Validates Close Step",
                LocalDateTime.of(2026, 6, 30, 9, 0), "evt:validates", Map.ofEntries(
                Map.entry("relationType", "VALIDATES"),
                Map.entry("sourceNodeId", "control:C-04"),
                Map.entry("controlId", "C-04"),
                Map.entry("requiredRoles", List.of("Forecast gate owner")),
                Map.entry("confidenceThreshold", 0.85),
                Map.entry("routingPolicy", "below threshold route"),
                Map.entry("actionType", "ESCALATE"),
                Map.entry("slaSeconds", 3600),
                Map.entry("slaBreached", true),
                Map.entry("status", "OVERDUE"),
                Map.entry("remediationAction", "REWORK_FORECAST"),
                Map.entry("confidence", 0.9)));
        Event approves = new Event("case:1", "Approval Gate",
                LocalDateTime.of(2026, 6, 30, 9, 5), "evt:approves", Map.of(
                "relationType", "APPROVED_BY",
                "sourceNodeId", "person:j_park",
                "approvalPolicy", "owner sign-off"));
        Event escalates = new Event("case:1", "Variance Triage",
                LocalDateTime.of(2026, 6, 30, 9, 10), "evt:escalates", Map.of(
                "relationType", "ESCALATED_TO",
                "targetNodeId", "person:mei_chen"));

        Set<String> atoms = ProcessSemanticAtomExtractor.extract(new EventLog(List.of(
                        new Trace("case:1", List.of(validates, approves, escalates)))))
                .stream()
                .map(ProcessSemanticAtomExtractor.SemanticAtom::atomKey)
                .collect(Collectors.toSet());

        assertTrue(atoms.contains("controls(\"C-04\", \"Control Validates Close Step\")"));
        assertTrue(atoms.contains("validates(\"C-04\", \"Control Validates Close Step\")"));
        assertTrue(atoms.contains("hasRole(\"Control Validates Close Step\", \"Forecast gate owner\")"));
        assertTrue(atoms.contains("threshold(\"Control Validates Close Step\", \"confidence\", \">=\", \"0.85\")"));
        assertTrue(atoms.contains("routedBy(\"Control Validates Close Step\", \"below threshold route\")"));
        assertTrue(atoms.contains("action(\"Control Validates Close Step\", \"ESCALATE\")"));
        assertTrue(atoms.contains("sla(\"Control Validates Close Step\", \"slaSeconds\", \"3600\")"));
        assertTrue(atoms.contains("slaBreach(\"Control Validates Close Step\", \"slaBreached\", \"true\")"));
        assertTrue(atoms.contains("status(\"Control Validates Close Step\", \"OVERDUE\")"));
        assertTrue(atoms.contains("remediation(\"Control Validates Close Step\", \"REWORK_FORECAST\")"));
        assertTrue(atoms.contains("approvedBy(\"Approval Gate\", \"person:j_park\")"));
        assertTrue(atoms.contains("requiresApproval(\"Approval Gate\", \"owner sign-off\")"));
        assertTrue(atoms.contains("escalatesTo(\"Variance Triage\", \"person:mei_chen\")"));
    }

    @Test
    void extractsProcessAtomsFromOntologyRelationProfiles() {
        Event event = new Event("case:1", "Approval Relation",
                LocalDateTime.of(2026, 6, 30, 9, 0), "evt:approval", Map.of(
                "relationType", "SIGNED_OFF_BY",
                "confidence", 0.8));
        OntologyRelationSchemaCompiler.ProcessSemanticProfile profile = new OntologyRelationSchemaCompiler.ProcessSemanticProfile(
                "APPROVED_BY",
                "APPROVED_BY",
                Set.of("SIGNED_OFF_BY"),
                Set.of("APPROVAL"),
                Set.of("CLOSE_APPROVAL_GATE"),
                Map.of("approvalPolicy", "owner sign-off", "slaSeconds", 7200));

        Set<String> atoms = ProcessSemanticAtomExtractor.extract(new EventLog(List.of(
                        new Trace("case:1", List.of(event)))), List.of(profile))
                .stream()
                .map(ProcessSemanticAtomExtractor.SemanticAtom::atomKey)
                .collect(Collectors.toSet());

        assertTrue(atoms.contains("action(\"Approval Relation\", \"APPROVAL\")"));
        assertTrue(atoms.contains("controls(\"CLOSE_APPROVAL_GATE\", \"Approval Relation\")"));
        assertTrue(atoms.contains("policy(\"Approval Relation\", \"approvalPolicy\", \"owner sign-off\")"));
        assertTrue(atoms.contains("requiresApproval(\"Approval Relation\", \"owner sign-off\")"));
        assertTrue(atoms.contains("sla(\"Approval Relation\", \"slaSeconds\", \"7200\")"));
    }
}
