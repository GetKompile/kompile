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

package ai.kompile.process.discovery;

import ai.kompile.process.workflow.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools for process discovery from knowledge graph analysis.
 * Exposes flow pattern detection, process suggestion, and suggestion acceptance
 * as LLM-accessible Spring AI tools.
 */
@Component
@ConditionalOnBean(ProcessDiscoveryService.class)
public class ProcessDiscoveryTool {

    private static final Logger log = LoggerFactory.getLogger(ProcessDiscoveryTool.class);

    private final ProcessDiscoveryService discoveryService;

    public ProcessDiscoveryTool(ProcessDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    // ---- Input Records ----

    public record DiscoverProcessesInput(
            List<String> graphNodeIds,
            Double minConfidence) {}

    public record AnalyzeEmailFlowsInput(List<String> graphNodeIds) {}

    public record AnalyzeExcelFlowsInput(List<String> graphNodeIds) {}

    public record AnalyzeDocumentFlowsInput(List<String> graphNodeIds) {}

    public record AnalyzeCrossDocumentFlowsInput(List<String> graphNodeIds) {}

    public record LlmDiscoverProcessesInput(
            List<String> graphNodeIds,
            String llmProvider,
            Double minConfidence) {}

    public record AcceptSuggestionInput(
            String name,
            String description,
            String discoverySource,
            double confidence,
            List<String> sourceGraphNodeIds,
            List<String> evidence) {}

    // ---- Fact-Sheet-Scoped Input Records ----

    public record FactSheetDiscoverInput(
            Long factSheetId,
            Double minConfidence,
            Boolean useLlm) {}

    public record FactSheetAnalyzeInput(Long factSheetId) {}

    public record FactSheetLlmDiscoverInput(
            Long factSheetId,
            String llmProvider,
            Double minConfidence) {}

    // ---- Tool Methods ----

    @Tool(name = "process_discovery_suggest",
          description = "Discovers potential repeatable processes from the knowledge graph. " +
                  "Analyzes email flows, Excel computation patterns, and document authoring pipelines " +
                  "to suggest process definitions that can be formalized and automated. " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all). " +
                  "minConfidence: optional minimum confidence threshold (0.0 to 1.0, default 0.5).")
    public Map<String, Object> discoverProcesses(DiscoverProcessesInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (input.minConfidence() != null) {
                options.put("minConfidence", input.minConfidence());
            }
            List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(
                    input.graphNodeIds(), options);

            List<Map<String, Object>> items = suggestions.stream().map(s -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", s.getName());
                item.put("description", s.getDescription());
                item.put("discoverySource", s.getDiscoverySource());
                item.put("confidence", s.getConfidence());
                item.put("phaseCount", s.getPhases() != null ? s.getPhases().size() : 0);
                int stepCount = s.getPhases() != null
                        ? s.getPhases().stream().mapToInt(p -> p.getSteps() != null ? p.getSteps().size() : 0).sum()
                        : 0;
                item.put("totalStepCount", stepCount);
                item.put("sourceGraphNodeIds", s.getSourceGraphNodeIds());
                item.put("evidence", s.getEvidence());
                // Include phase/step details for the agent to reason about
                if (s.getPhases() != null) {
                    List<Map<String, Object>> phases = s.getPhases().stream().map(p -> {
                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("name", p.getName());
                        pm.put("description", p.getDescription());
                        if (p.getSteps() != null) {
                            List<Map<String, Object>> steps = p.getSteps().stream().map(st -> {
                                Map<String, Object> sm = new LinkedHashMap<>();
                                sm.put("name", st.getName());
                                sm.put("stepType", st.getStepType());
                                sm.put("description", st.getDescription());
                                if (st.getToolName() != null) sm.put("toolName", st.getToolName());
                                if (st.getSuggestedAssignee() != null) sm.put("suggestedAssignee", st.getSuggestedAssignee());
                                return sm;
                            }).collect(Collectors.toList());
                            pm.put("steps", steps);
                        }
                        return pm;
                    }).collect(Collectors.toList());
                    item.put("phases", phases);
                }
                return item;
            }).collect(Collectors.toList());

            result.put("suggestions", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error discovering processes", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_email_flows",
          description = "Analyzes email communication patterns in the knowledge graph to discover " +
                  "recurring person-to-person exchange flows. Patterns with 2+ occurrences become candidates. " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all).")
    public Map<String, Object> analyzeEmailFlows(AnalyzeEmailFlowsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(input.graphNodeIds());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing email flows", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_excel_flows",
          description = "Analyzes Excel/spreadsheet computation patterns in the knowledge graph. " +
                  "Finds sheets with formula cells and builds INPUT→COMPUTE→APPROVE step patterns. " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all).")
    public Map<String, Object> analyzeExcelFlows(AnalyzeExcelFlowsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(input.graphNodeIds());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing Excel flows", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_document_flows",
          description = "Analyzes document authoring and review patterns in the knowledge graph. " +
                  "Detects author pipelines, version-chain review cycles, topic cluster pipelines, " +
                  "and form-based data collection patterns. " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all).")
    public Map<String, Object> analyzeDocumentFlows(AnalyzeDocumentFlowsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(input.graphNodeIds());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing document flows", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_cross_document_flows",
          description = "Analyzes cross-document references to discover hierarchical process patterns. " +
                  "Detects when one document (e.g., an email) references or instructs the usage of another " +
                  "(e.g., a spreadsheet), and creates parent/child process suggestions. Covers: " +
                  "email-with-spreadsheet-attachment flows, explicit cross-document references " +
                  "(REFERENCES_DOCUMENT, DESCRIBES_PROCEDURE edges), and implicit name-based references. " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all).")
    public Map<String, Object> analyzeCrossDocumentFlows(AnalyzeCrossDocumentFlowsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeCrossDocumentFlows(input.graphNodeIds());
            result.put("patterns", serializeHierarchicalFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing cross-document flows", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_llm_discover",
          description = "Uses an LLM to analyze knowledge graph data and discover business processes " +
                  "described in natural language. Unlike pattern-based discovery, this can identify " +
                  "processes from document content, email instructions, and procedural descriptions. " +
                  "For example, an email saying 'Step 1: Fill out the form, Step 2: Get manager approval' " +
                  "will be discovered as a two-phase process. Supports hierarchical processes " +
                  "(parent processes with sub-processes). " +
                  "graphNodeIds: optional list of KG node IDs to scope the analysis (null means all). " +
                  "llmProvider: optional LLM provider ID (e.g., 'llm-chat', 'claude-cli'). " +
                  "minConfidence: optional minimum confidence threshold (0.0 to 1.0, default 0.5).")
    public Map<String, Object> llmDiscoverProcesses(LlmDiscoverProcessesInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (input.llmProvider() != null) options.put("llmProvider", input.llmProvider());
            if (input.minConfidence() != null) options.put("minConfidence", input.minConfidence());

            List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesWithLlm(
                    input.graphNodeIds(), options);

            List<Map<String, Object>> items = suggestions.stream().map(this::serializeSuggestion)
                    .collect(Collectors.toList());

            result.put("suggestions", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error in LLM process discovery", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> serializeSuggestion(ProcessSuggestion s) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", s.getName());
        item.put("description", s.getDescription());
        item.put("discoverySource", s.getDiscoverySource());
        item.put("confidence", s.getConfidence());
        item.put("sourceGraphNodeIds", s.getSourceGraphNodeIds());
        item.put("evidence", s.getEvidence());
        if (s.getPhases() != null) {
            List<Map<String, Object>> phases = s.getPhases().stream().map(p -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.getName());
                pm.put("description", p.getDescription());
                if (p.getSteps() != null) {
                    List<Map<String, Object>> steps = p.getSteps().stream().map(st -> {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("name", st.getName());
                        sm.put("stepType", st.getStepType());
                        sm.put("description", st.getDescription());
                        if (st.getToolName() != null) sm.put("toolName", st.getToolName());
                        if (st.getSuggestedAssignee() != null) sm.put("suggestedAssignee", st.getSuggestedAssignee());
                        return sm;
                    }).collect(Collectors.toList());
                    pm.put("steps", steps);
                }
                return pm;
            }).collect(Collectors.toList());
            item.put("phases", phases);
        }
        if (s.getChildSuggestions() != null && !s.getChildSuggestions().isEmpty()) {
            item.put("childSuggestions", s.getChildSuggestions().stream()
                    .map(this::serializeSuggestion).collect(Collectors.toList()));
        }
        return item;
    }

    @Tool(name = "process_discovery_accept",
          description = "Accepts a process suggestion and converts it into a DRAFT ProcessDefinition " +
                  "in the process engine. The definition can then be reviewed, approved, and executed. " +
                  "name: the process name. description: human-readable description. " +
                  "discoverySource: how it was discovered (EMAIL_FLOW, EXCEL_COMPUTATION, etc.). " +
                  "confidence: the confidence score. sourceGraphNodeIds: originating KG node IDs. " +
                  "evidence: supporting evidence strings.")
    public Map<String, Object> acceptSuggestion(AcceptSuggestionInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name(input.name())
                    .description(input.description())
                    .discoverySource(input.discoverySource())
                    .confidence(input.confidence())
                    .sourceGraphNodeIds(input.sourceGraphNodeIds() != null ? input.sourceGraphNodeIds() : List.of())
                    .evidence(input.evidence() != null ? input.evidence() : List.of())
                    .build();
            ProcessDefinition definition = discoveryService.acceptSuggestion(suggestion);
            result.put("definitionId", definition.getId());
            result.put("name", definition.getName());
            result.put("version", definition.getVersion());
            result.put("processStatus", definition.getStatus() != null ? definition.getStatus().name() : null);
            result.put("phaseCount", definition.getPhases() != null ? definition.getPhases().size() : 0);
            result.put("metadata", definition.getMetadata());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error accepting process suggestion name={}", input.name(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Fact-Sheet-Scoped Tools ----

    @Tool(name = "process_discovery_fact_sheet",
          description = "Discovers all business processes from a specific fact sheet's knowledge graph. " +
                  "Combines pattern-based analysis (email flows, Excel computations, document pipelines) " +
                  "and optionally LLM-based analysis, all scoped to the nodes in the given fact sheet. " +
                  "This is the primary entry point for extracting processes from a fact sheet. " +
                  "factSheetId: the fact sheet ID to analyze. " +
                  "minConfidence: optional minimum confidence threshold (0.0 to 1.0, default 0.5). " +
                  "useLlm: whether to include LLM-based discovery (default true).")
    public Map<String, Object> discoverProcessesForFactSheet(FactSheetDiscoverInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (input.minConfidence() != null) options.put("minConfidence", input.minConfidence());
            if (input.useLlm() != null) options.put("useLlm", input.useLlm());

            List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesForFactSheet(
                    input.factSheetId(), options);

            result.put("factSheetId", input.factSheetId());
            result.put("suggestions", suggestions.stream().map(this::serializeSuggestion)
                    .collect(Collectors.toList()));
            result.put("count", suggestions.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error discovering processes for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_fact_sheet_email_flows",
          description = "Analyzes email communication patterns within a fact sheet's knowledge graph. " +
                  "factSheetId: the fact sheet ID to analyze.")
    public Map<String, Object> analyzeEmailFlowsForFactSheet(FactSheetAnalyzeInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeEmailFlowsForFactSheet(input.factSheetId());
            result.put("factSheetId", input.factSheetId());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing email flows for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_fact_sheet_excel_flows",
          description = "Analyzes Excel/spreadsheet computation patterns within a fact sheet's knowledge graph. " +
                  "factSheetId: the fact sheet ID to analyze.")
    public Map<String, Object> analyzeExcelFlowsForFactSheet(FactSheetAnalyzeInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeExcelFlowsForFactSheet(input.factSheetId());
            result.put("factSheetId", input.factSheetId());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing Excel flows for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_fact_sheet_document_flows",
          description = "Analyzes document authoring and review patterns within a fact sheet's knowledge graph. " +
                  "factSheetId: the fact sheet ID to analyze.")
    public Map<String, Object> analyzeDocumentFlowsForFactSheet(FactSheetAnalyzeInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlowsForFactSheet(input.factSheetId());
            result.put("factSheetId", input.factSheetId());
            result.put("patterns", serializeFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing document flows for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_fact_sheet_cross_doc_flows",
          description = "Analyzes cross-document references within a fact sheet's knowledge graph " +
                  "to discover hierarchical process patterns. " +
                  "factSheetId: the fact sheet ID to analyze.")
    public Map<String, Object> analyzeCrossDocumentFlowsForFactSheet(FactSheetAnalyzeInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<FlowPattern> patterns = discoveryService.analyzeCrossDocumentFlowsForFactSheet(input.factSheetId());
            result.put("factSheetId", input.factSheetId());
            result.put("patterns", serializeHierarchicalFlowPatterns(patterns));
            result.put("count", patterns.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error analyzing cross-document flows for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_discovery_fact_sheet_llm",
          description = "Uses an LLM to discover business processes from a fact sheet's knowledge graph. " +
                  "Reads document content, entity relationships, and structural patterns to identify " +
                  "processes described in natural language within the fact sheet's documents. " +
                  "factSheetId: the fact sheet ID to analyze. " +
                  "llmProvider: optional LLM provider ID. " +
                  "minConfidence: optional minimum confidence threshold (0.0 to 1.0, default 0.5).")
    public Map<String, Object> llmDiscoverProcessesForFactSheet(FactSheetLlmDiscoverInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            if (input.llmProvider() != null) options.put("llmProvider", input.llmProvider());
            if (input.minConfidence() != null) options.put("minConfidence", input.minConfidence());

            List<ProcessSuggestion> suggestions = discoveryService.discoverProcessesWithLlmForFactSheet(
                    input.factSheetId(), options);

            result.put("factSheetId", input.factSheetId());
            result.put("suggestions", suggestions.stream().map(this::serializeSuggestion)
                    .collect(Collectors.toList()));
            result.put("count", suggestions.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error in LLM process discovery for factSheetId={}", input.factSheetId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Helpers ----

    private List<Map<String, Object>> serializeHierarchicalFlowPatterns(List<FlowPattern> patterns) {
        return patterns.stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", p.getType());
            item.put("description", p.getDescription());
            item.put("occurrenceCount", p.getOccurrenceCount());
            item.put("confidence", p.getConfidence());
            item.put("involvedNodeIds", p.getInvolvedNodeIds());
            if (p.getParentFlowType() != null) {
                item.put("parentFlowType", p.getParentFlowType());
            }
            if (p.getSteps() != null) {
                List<Map<String, Object>> steps = p.getSteps().stream().map(s -> {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("description", s.getDescription());
                    sm.put("actor", s.getActor());
                    sm.put("action", s.getAction());
                    sm.put("target", s.getTarget());
                    if (s.getNodeId() != null) sm.put("nodeId", s.getNodeId());
                    return sm;
                }).collect(Collectors.toList());
                item.put("steps", steps);
            }
            if (p.getChildPatterns() != null && !p.getChildPatterns().isEmpty()) {
                item.put("childPatterns", serializeHierarchicalFlowPatterns(p.getChildPatterns()));
            }
            return item;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> serializeFlowPatterns(List<FlowPattern> patterns) {
        return patterns.stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", p.getType());
            item.put("description", p.getDescription());
            item.put("occurrenceCount", p.getOccurrenceCount());
            item.put("confidence", p.getConfidence());
            item.put("involvedNodeIds", p.getInvolvedNodeIds());
            if (p.getSteps() != null) {
                List<Map<String, Object>> steps = p.getSteps().stream().map(s -> {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("description", s.getDescription());
                    sm.put("actor", s.getActor());
                    sm.put("action", s.getAction());
                    sm.put("target", s.getTarget());
                    if (s.getNodeId() != null) sm.put("nodeId", s.getNodeId());
                    return sm;
                }).collect(Collectors.toList());
                item.put("steps", steps);
            }
            return item;
        }).collect(Collectors.toList());
    }
}
