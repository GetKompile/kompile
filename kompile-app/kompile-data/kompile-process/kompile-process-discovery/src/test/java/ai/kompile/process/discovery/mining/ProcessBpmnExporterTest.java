/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.export.ProcessBpmnExporter;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BPMN 2.0 export well-formedness: namespace declaration, required elements, swim-lane structure,
 * sequence flow connectivity, and data object references.
 */
class ProcessBpmnExporterTest {

    private static ProcessSuggestion buildSuggestion(String... roleBindings) {
        List<SuggestedStep> steps = new ArrayList<>();
        String[] names = {"Submit", "Review", "Approve", "Close"};
        String[] types = {"AUTO", "HUMAN", "APPROVE", "AUTO"};
        for (int i = 0; i < names.length; i++) {
            String role = (i < roleBindings.length) ? roleBindings[i] : null;
            steps.add(SuggestedStep.builder()
                    .name(names[i])
                    .stepType(types[i])
                    .description("Step: " + names[i])
                    .roleBinding(role)
                    .graphNodeIds(List.of("node-" + i))
                    .build());
        }
        SuggestedPhase phase = SuggestedPhase.builder()
                .name("Phase 1: Main")
                .steps(steps)
                .build();
        return ProcessSuggestion.builder()
                .id("test-sugg-001")
                .name("Test Process")
                .phases(List.of(phase))
                .confidence(0.75)
                .build();
    }

    @Test
    void export_producesWellFormedXmlWithBpmnNamespace() {
        ProcessSuggestion suggestion = buildSuggestion("INITIATOR", "REVIEWER", "APPROVER", "EXECUTOR");
        String xml = ProcessBpmnExporter.export(suggestion);

        assertNotNull(xml);
        assertFalse(xml.isBlank());
        assertTrue(xml.contains("<?xml version=\"1.0\""), "must start with XML declaration");
        assertTrue(xml.contains("http://www.omg.org/spec/BPMN/20100524/MODEL"),
                "must declare BPMN 2.0 namespace");
        assertTrue(xml.contains("<definitions"), "must have definitions root");
        assertTrue(xml.contains("</definitions>"), "must close definitions");
    }

    @Test
    void export_containsProcessElement() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        assertTrue(xml.contains("<process "), "must contain process element");
        assertTrue(xml.contains("</process>"), "must close process element");
    }

    @Test
    void export_containsStartAndEndEvents() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        assertTrue(xml.contains("<startEvent"), "must have startEvent");
        assertTrue(xml.contains("<endEvent"),   "must have endEvent");
    }

    @Test
    void export_containsSequenceFlows() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        assertTrue(xml.contains("<sequenceFlow"), "must have sequence flows");
        assertTrue(xml.contains("sourceRef="),    "flows must have sourceRef");
        assertTrue(xml.contains("targetRef="),    "flows must have targetRef");
    }

    @Test
    void export_swimLanesByRoleBinding() {
        ProcessSuggestion suggestion = buildSuggestion("INITIATOR", "REVIEWER", "APPROVER", "EXECUTOR");
        String xml = ProcessBpmnExporter.export(suggestion);

        assertTrue(xml.contains("<laneSet"), "must have laneSet when roles present");
        assertTrue(xml.contains("name=\"INITIATOR\""), "INITIATOR lane must appear");
        assertTrue(xml.contains("name=\"REVIEWER\""),  "REVIEWER lane must appear");
        assertTrue(xml.contains("name=\"APPROVER\""),  "APPROVER lane must appear");
        assertTrue(xml.contains("name=\"EXECUTOR\""),  "EXECUTOR lane must appear");
    }

    @Test
    void export_nullRoleBindingGoesToGeneralLane() {
        ProcessSuggestion suggestion = buildSuggestion(null, null, "APPROVER");
        String xml = ProcessBpmnExporter.export(suggestion);

        assertTrue(xml.contains("name=\"General\""), "unassigned steps must go to General lane");
        assertTrue(xml.contains("name=\"APPROVER\""), "explicit role must still appear");
    }

    @Test
    void export_bpmnTaskTypesFromStepType() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        // "Review" has stepType=HUMAN → userTask, "Submit" has AUTO → serviceTask
        assertTrue(xml.contains("<userTask"),    "HUMAN steps must export as userTask");
        assertTrue(xml.contains("<serviceTask"), "AUTO steps must export as serviceTask");
    }

    @Test
    void export_dataObjectReferencesForGraphNodeIds() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        assertTrue(xml.contains("<dataObject"), "must emit data objects for graph node refs");
        assertTrue(xml.contains("<dataInputAssociation"), "must emit data input associations");
    }

    @Test
    void export_stepNamesAppearInTaskNames() {
        ProcessSuggestion suggestion = buildSuggestion();
        String xml = ProcessBpmnExporter.export(suggestion);

        assertTrue(xml.contains("name=\"Submit\""),  "Submit step must appear");
        assertTrue(xml.contains("name=\"Review\""),  "Review step must appear");
        assertTrue(xml.contains("name=\"Approve\""), "Approve step must appear");
        assertTrue(xml.contains("name=\"Close\""),   "Close step must appear");
    }

    @Test
    void export_documentationPerTask() {
        String xml = ProcessBpmnExporter.export(buildSuggestion());
        assertTrue(xml.contains("<documentation>"), "tasks must carry documentation");
    }

    @Test
    void export_nullSuggestionThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProcessBpmnExporter.export(null));
    }

    @Test
    void export_emptyPhasesProducesMinimalProcess() {
        ProcessSuggestion empty = ProcessSuggestion.builder()
                .id("empty-001")
                .name("Empty")
                .build();
        String xml = ProcessBpmnExporter.export(empty);
        // Should still produce valid skeleton with start/end
        assertTrue(xml.contains("<startEvent"), "even empty suggestion needs startEvent");
        assertTrue(xml.contains("<endEvent"),   "even empty suggestion needs endEvent");
    }

    @Test
    void export_fromMinedSuggestion_isWellFormed() {
        // End-to-end: mine a log, convert, export
        List<Trace> ts = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
        for (int i = 0; i < 3; i++) {
            String cid = "c" + i;
            ts.add(new Trace(cid, List.of(
                    Event.of(cid, "Submit",  base.plusMinutes(0), cid + "-n0"),
                    Event.of(cid, "Review",  base.plusMinutes(1), cid + "-n1"),
                    Event.of(cid, "Approve", base.plusMinutes(2), cid + "-n2"))));
        }
        EventLog log = new EventLog(ts);
        ProcessTree tree = new InductiveMiner().mine(log);
        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "E2E Process");
        suggestion.setId("e2e-001");

        String xml = ProcessBpmnExporter.export(suggestion);
        assertTrue(xml.contains("<definitions"), "definitions present");
        assertTrue(xml.contains("<process "),    "process present");
        assertTrue(xml.contains("<sequenceFlow"), "flows present");
        // All three activities should appear as tasks
        assertTrue(xml.contains("Submit"),  "Submit activity present");
        assertTrue(xml.contains("Review"),  "Review activity present");
        assertTrue(xml.contains("Approve"), "Approve activity present");
    }
}
