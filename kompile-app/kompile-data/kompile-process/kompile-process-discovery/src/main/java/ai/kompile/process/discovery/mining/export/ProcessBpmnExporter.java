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

package ai.kompile.process.discovery.mining.export;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports a {@link ProcessSuggestion} to BPMN 2.0 XML (pure Java, no BPMN library dependency).
 *
 * <h3>Structure produced</h3>
 * <ul>
 *   <li>One {@code <process>} element wrapping the model.</li>
 *   <li>One {@code <laneSet>} with one lane per distinct {@code roleBinding} found across all steps.
 *       Steps with {@code roleBinding == null} or {@code "UNASSIGNED"} are placed in a catch-all
 *       {@code "General"} lane.</li>
 *   <li>Phases become sequential blocks delimited by intermediate events when there is more than
 *       one phase. Within each phase, steps are chained left-to-right with sequence flows.</li>
 *   <li>Each {@code <task>} element carries a {@code <documentation>} child with the step
 *       description and, when grounding evidence is available on the suggestion, the KB
 *       evidence list.</li>
 *   <li>{@code <dataObjectReference>} elements are emitted for each {@code graphNodeId} on a step,
 *       linking the task to its knowledge-graph source via {@code <dataInputAssociation>}.</li>
 *   <li>A {@code <startEvent>} and {@code <endEvent>} bookend the flow.</li>
 * </ul>
 *
 * <p>The output is valid BPMN 2.0 XML (namespace {@code http://www.omg.org/spec/BPMN/20100524/MODEL})
 * consumable by Camunda, Flowable, and Activiti modellers. Swim-lane rendering requires a
 * {@code bpmndi:BPMNDiagram} section which is intentionally omitted here — layout is delegated
 * to the modeller tool.</p>
 */
public final class ProcessBpmnExporter {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String XSI_NS  = "http://www.w3.org/2001/XMLSchema-instance";

    private ProcessBpmnExporter() {
    }

    /**
     * Export a suggestion to BPMN 2.0 XML.
     *
     * @param suggestion the process suggestion to export (must not be null)
     * @return well-formed BPMN 2.0 XML string
     */
    public static String export(ProcessSuggestion suggestion) {
        if (suggestion == null) throw new IllegalArgumentException("suggestion must not be null");

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<definitions xmlns=\"").append(BPMN_NS).append("\"\n");
        xml.append("             xmlns:xsi=\"").append(XSI_NS).append("\"\n");
        xml.append("             targetNamespace=\"https://kompile.ai/bpmn\"\n");
        xml.append("             id=\"kompile_bpmn_").append(safe(suggestion.getId())).append("\">\n");

        String processId = "proc_" + safe(suggestion.getId());
        xml.append("  <process id=\"").append(processId).append("\"\n");
        xml.append("           name=\"").append(escapeXml(suggestion.getName())).append("\"\n");
        xml.append("           isExecutable=\"false\">\n");

        // Collect distinct role bindings → lanes
        List<SuggestedPhase> phases = suggestion.getPhases() != null
                ? suggestion.getPhases() : List.of();
        Map<String, List<SuggestedStep>> laneToSteps = groupByRole(phases);

        // Emit laneSet
        if (!laneToSteps.isEmpty()) {
            xml.append("    <laneSet id=\"ls_").append(processId).append("\">\n");
            for (Map.Entry<String, List<SuggestedStep>> entry : laneToSteps.entrySet()) {
                String laneId = "lane_" + safe(entry.getKey());
                xml.append("      <lane id=\"").append(laneId).append("\"")
                   .append(" name=\"").append(escapeXml(entry.getKey())).append("\">\n");
                for (SuggestedStep step : entry.getValue()) {
                    xml.append("        <flowNodeRef>")
                       .append(stepId(step)).append("</flowNodeRef>\n");
                }
                xml.append("      </lane>\n");
            }
            xml.append("    </laneSet>\n");
        }

        // --- Flow elements ---
        String startId = "start_" + processId;
        String endId   = "end_"   + processId;
        xml.append("    <startEvent id=\"").append(startId)
           .append("\" name=\"Start\"/>\n");

        // Collect all steps in order (across phases) to build the sequence
        List<SuggestedStep> allSteps = new ArrayList<>();
        // Collect data object refs to emit later: [doRefId, displayName, stepId]
        List<String[]> dataObjects = new ArrayList<>();

        for (SuggestedStep step : allStepsInOrder(phases)) {
            allSteps.add(step);
            String sid = stepId(step);
            String taskType = bpmnTaskType(step.getStepType());
            xml.append("    <").append(taskType)
               .append(" id=\"").append(sid).append("\"")
               .append(" name=\"").append(escapeXml(step.getName())).append("\">\n");
            // Documentation: description + grounding evidence
            xml.append("      <documentation>").append(escapeXml(buildDoc(step, suggestion)))
               .append("</documentation>\n");
            // DataInputAssociations for graph node references
            if (step.getGraphNodeIds() != null) {
                List<String> titles = step.getGraphNodeTitles();
                for (int i = 0; i < step.getGraphNodeIds().size(); i++) {
                    String nodeId = step.getGraphNodeIds().get(i);
                    // Use resolved title when available; fall back to raw id
                    String displayName = (titles != null && i < titles.size() && titles.get(i) != null
                            && !titles.get(i).equals(nodeId))
                            ? titles.get(i) : nodeId;
                    String doRefId = "do_" + safe(nodeId);
                    dataObjects.add(new String[]{doRefId, displayName, sid});
                    xml.append("      <dataInputAssociation>\n");
                    xml.append("        <sourceRef>").append(doRefId).append("</sourceRef>\n");
                    xml.append("        <targetRef>").append(sid).append("</targetRef>\n");
                    xml.append("      </dataInputAssociation>\n");
                }
            }
            xml.append("    </").append(taskType).append(">\n");
        }

        xml.append("    <endEvent id=\"").append(endId)
           .append("\" name=\"End\"/>\n");

        // Data object references (deduplicated by doRefId)
        Set<String> emittedDo = new LinkedHashSet<>();
        for (String[] d : dataObjects) {
            if (emittedDo.add(d[0])) {
                xml.append("    <dataObject id=\"").append(d[0])
                   .append("\" name=\"").append(escapeXml(d[1])).append("\"/>\n");
                xml.append("    <dataObjectReference id=\"ref_").append(d[0])
                   .append("\" dataObjectRef=\"").append(d[0]).append("\"/>\n");
            }
        }

        // --- Sequence flows ---
        List<String> flowNodes = new ArrayList<>();
        flowNodes.add(startId);
        for (SuggestedStep step : allSteps) {
            flowNodes.add(stepId(step));
        }
        flowNodes.add(endId);

        for (int i = 0; i < flowNodes.size() - 1; i++) {
            String flowId = "sf_" + i + "_" + processId;
            xml.append("    <sequenceFlow id=\"").append(flowId).append("\"")
               .append(" sourceRef=\"").append(flowNodes.get(i)).append("\"")
               .append(" targetRef=\"").append(flowNodes.get(i + 1)).append("\"/>\n");
        }

        xml.append("  </process>\n");
        xml.append("</definitions>\n");
        return xml.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Groups steps by their roleBinding. Steps without a role go to "General". */
    private static Map<String, List<SuggestedStep>> groupByRole(List<SuggestedPhase> phases) {
        Map<String, List<SuggestedStep>> map = new LinkedHashMap<>();
        for (SuggestedStep step : allStepsInOrder(phases)) {
            String role = (step.getRoleBinding() != null && !step.getRoleBinding().isBlank()
                    && !"UNASSIGNED".equals(step.getRoleBinding()))
                    ? step.getRoleBinding() : "General";
            map.computeIfAbsent(role, k -> new ArrayList<>()).add(step);
        }
        return map;
    }

    private static List<SuggestedStep> allStepsInOrder(List<SuggestedPhase> phases) {
        List<SuggestedStep> out = new ArrayList<>();
        if (phases == null) return out;
        for (SuggestedPhase phase : phases) {
            if (phase.getSteps() != null) out.addAll(phase.getSteps());
        }
        return out;
    }

    /** Unique, XML-id-safe step identifier. */
    private static String stepId(SuggestedStep step) {
        return "task_" + safe(step.getName());
    }

    /** Map our internal stepType to the appropriate BPMN 2.0 task element. */
    private static String bpmnTaskType(String stepType) {
        if (stepType == null) return "task";
        switch (stepType) {
            case "HUMAN":  return "userTask";
            case "APPROVE": return "userTask";
            case "AUTO":   return "serviceTask";
            case "TOOL_CALL": return "serviceTask";
            case "HTTP_CALL": return "sendTask";
            default:       return "task";
        }
    }

    private static String buildDoc(SuggestedStep step, ProcessSuggestion suggestion) {
        StringBuilder sb = new StringBuilder();
        if (step.getDescription() != null) sb.append(step.getDescription());
        // Append grounding evidence from suggestion's structured evidence
        if (suggestion.getGroundedSteps() != null) {
            for (var ge : suggestion.getGroundedSteps()) {
                if (ge.element() == step) {
                    List<String> ev = ge.evidence();
                    if (ev != null && !ev.isEmpty()) {
                        sb.append(" | KB evidence: ").append(String.join("; ", ev));
                    }
                    sb.append(" [confidence=").append(String.format("%.2f", ge.calibratedConfidence()))
                      .append(", band=").append(ge.band()).append("]");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Replace characters illegal in XML IDs with underscores. */
    private static String safe(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
