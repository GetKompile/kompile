/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.process.discovery;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Uses an LLM to discover business processes from knowledge graph data.
 * <p>
 * The service serializes graph nodes and edges into a structured text context,
 * prompts the LLM to identify business processes (with phases, steps, actors,
 * and hierarchies), and parses the JSON response into {@link ProcessSuggestion}
 * objects that can be accepted into the process engine.
 * <p>
 * This complements the pattern-based discovery in {@link ProcessDiscoveryServiceImpl}
 * by handling cases where processes are described in natural language rather than
 * expressed through structural graph patterns (e.g., an email that describes a
 * multi-step approval workflow, or a document that outlines an onboarding procedure).
 */
@Slf4j
@Service
@ConditionalOnBean({KnowledgeGraphService.class, ExtractionLlmServiceRegistry.class})
public class LlmProcessDiscoveryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_NODES_FOR_CONTEXT = 200;
    private static final int MAX_SNIPPETS_PER_DOCUMENT = 10;
    private static final int MAX_CONTENT_CHARS = 6000;

    private final KnowledgeGraphService knowledgeGraphService;
    private final ExtractionLlmServiceRegistry llmServiceRegistry;
    private GraphNodeRepository graphNodeRepository;

    @Autowired
    public LlmProcessDiscoveryService(KnowledgeGraphService knowledgeGraphService,
                                       ExtractionLlmServiceRegistry llmServiceRegistry) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.llmServiceRegistry = llmServiceRegistry;
    }

    @Autowired(required = false)
    public void setGraphNodeRepository(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    /**
     * Use an LLM to discover processes from knowledge graph data.
     *
     * @param graphNodeIds optional scope (null = all nodes)
     * @param options      options including "llmProvider" (String) and "minConfidence" (Double)
     * @return list of LLM-discovered process suggestions
     */
    public List<ProcessSuggestion> discoverProcesses(List<String> graphNodeIds, Map<String, Object> options) {
        String preferredProvider = options != null ? (String) options.get("llmProvider") : null;
        ExtractionLlmService llmService = llmServiceRegistry.getOrFallback(preferredProvider);
        if (llmService == null) {
            log.warn("No LLM provider available for process discovery");
            return List.of();
        }

        // Gather graph context
        GraphContext ctx = buildGraphContext(graphNodeIds);
        if (ctx.nodes.isEmpty()) {
            log.debug("No graph nodes found for LLM process discovery");
            return List.of();
        }

        // Build the prompt
        String prompt = buildPrompt(ctx);

        // Call the LLM
        log.info("Calling LLM ({}) for process discovery with {} nodes, {} snippets, {} edges",
                llmService.getId(), ctx.nodes.size(), ctx.snippets.size(), ctx.edges.size());
        String response;
        try {
            response = llmService.complete(prompt);
        } catch (Exception e) {
            log.error("LLM process discovery call failed: {}", e.getMessage());
            return List.of();
        }

        // Parse the response (wrap in mutable list for filtering)
        List<ProcessSuggestion> suggestions = new ArrayList<>(parseResponse(response));

        // Apply confidence filter
        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }
        double finalMinConfidence = minConfidence;
        suggestions.removeIf(s -> s.getConfidence() < finalMinConfidence);

        log.info("LLM process discovery found {} suggestions (provider: {})",
                suggestions.size(), llmService.getId());
        return suggestions;
    }

    /**
     * Check if any LLM provider is available for process discovery.
     */
    public boolean isAvailable() {
        return llmServiceRegistry.getOrFallback(null) != null;
    }

    /**
     * Discover processes scoped to a specific fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @param options     options including "llmProvider" and "minConfidence"
     * @return LLM-discovered process suggestions with factSheetId set
     */
    public List<ProcessSuggestion> discoverProcessesForFactSheet(Long factSheetId, Map<String, Object> options) {
        String preferredProvider = options != null ? (String) options.get("llmProvider") : null;
        ExtractionLlmService llmService = llmServiceRegistry.getOrFallback(preferredProvider);
        if (llmService == null) {
            log.warn("No LLM provider available for process discovery");
            return List.of();
        }

        // Gather graph context scoped to the fact sheet
        GraphContext ctx = buildGraphContextForFactSheet(factSheetId);
        if (ctx.nodes.isEmpty()) {
            log.debug("No graph nodes found for factSheetId={}", factSheetId);
            return List.of();
        }

        String prompt = buildPrompt(ctx);

        log.info("Calling LLM ({}) for fact-sheet process discovery (factSheetId={}) with {} nodes, {} snippets, {} edges",
                llmService.getId(), factSheetId, ctx.nodes.size(), ctx.snippets.size(), ctx.edges.size());
        String response;
        try {
            response = llmService.complete(prompt);
        } catch (Exception e) {
            log.error("LLM process discovery call failed for factSheetId={}: {}", factSheetId, e.getMessage());
            return List.of();
        }

        List<ProcessSuggestion> suggestions = new ArrayList<>(parseResponse(response));

        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }
        double finalMinConfidence = minConfidence;
        suggestions.removeIf(s -> s.getConfidence() < finalMinConfidence);

        // Stamp factSheetId on all suggestions
        suggestions.forEach(s -> s.setFactSheetId(factSheetId));

        log.info("LLM process discovery for factSheetId={} found {} suggestions (provider: {})",
                factSheetId, suggestions.size(), llmService.getId());
        return suggestions;
    }

    // ── Graph context gathering ─────────────────────────────────────────────

    record GraphContext(List<GraphNode> nodes, List<GraphNode> snippets, List<GraphEdge> edges) {}

    GraphContext buildGraphContext(List<String> graphNodeIds) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphNode> snippets = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();

        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (seenNodeIds.add(node.getNodeId())) nodes.add(node);
                });
                // Include immediate neighbors for richer context
                List<GraphNode> connected = knowledgeGraphService.getConnectedNodes(nodeId, 1);
                for (GraphNode n : connected) {
                    if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                }
            }
        } else {
            // Fetch document, entity, and table nodes
            for (NodeLevel level : List.of(NodeLevel.DOCUMENT, NodeLevel.ENTITY, NodeLevel.TABLE)) {
                List<GraphNode> levelNodes = knowledgeGraphService.searchNodes("", level, MAX_NODES_FOR_CONTEXT);
                for (GraphNode n : levelNodes) {
                    if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                    if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
                }
                if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
            }
        }

        // For every DOCUMENT node, fetch its SNIPPET children — these contain
        // the actual chunk content where processes are described
        for (GraphNode node : new ArrayList<>(nodes)) {
            if (node.getNodeType() == NodeLevel.DOCUMENT) {
                List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                int count = 0;
                for (GraphNode child : children) {
                    if (child.getNodeType() == NodeLevel.SNIPPET && seenNodeIds.add(child.getNodeId())) {
                        snippets.add(child);
                        if (++count >= MAX_SNIPPETS_PER_DOCUMENT) break;
                    }
                }
            }
        }

        // Gather edges for the main nodes (not snippets — they only add bulk)
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seenEdgeKeys = new HashSet<>();
        for (GraphNode node : nodes) {
            List<GraphEdge> nodeEdges = knowledgeGraphService.getEdgesForNode(node.getNodeId());
            for (GraphEdge edge : nodeEdges) {
                String edgeKey = edge.getSourceNode().getNodeId() + "->" + edge.getTargetNode().getNodeId()
                        + ":" + (edge.getLabel() != null ? edge.getLabel() : edge.getDescription());
                if (seenEdgeKeys.add(edgeKey)) {
                    edges.add(edge);
                }
            }
        }

        return new GraphContext(nodes, snippets, edges);
    }

    /**
     * Builds graph context scoped to a specific fact sheet using the repository
     * for efficient fact-sheet-level queries.
     */
    GraphContext buildGraphContextForFactSheet(Long factSheetId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphNode> snippets = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();

        if (graphNodeRepository != null) {
            // Use repository for efficient fact-sheet-scoped queries
            for (NodeLevel level : List.of(NodeLevel.DOCUMENT, NodeLevel.ENTITY, NodeLevel.TABLE)) {
                List<GraphNode> levelNodes = graphNodeRepository.findByFactSheetIdAndNodeType(factSheetId, level);
                for (GraphNode n : levelNodes) {
                    if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                    if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
                }
                if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
            }
        } else {
            // Fallback: collect all node IDs for the fact sheet via KG service
            log.warn("GraphNodeRepository not available for fact-sheet context — falling back to global search");
            return buildGraphContext(null);
        }

        // Fetch SNIPPET children for DOCUMENT nodes
        for (GraphNode node : new ArrayList<>(nodes)) {
            if (node.getNodeType() == NodeLevel.DOCUMENT) {
                List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                int count = 0;
                for (GraphNode child : children) {
                    if (child.getNodeType() == NodeLevel.SNIPPET && seenNodeIds.add(child.getNodeId())) {
                        snippets.add(child);
                        if (++count >= MAX_SNIPPETS_PER_DOCUMENT) break;
                    }
                }
            }
        }

        // Gather edges for the main nodes
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seenEdgeKeys = new HashSet<>();
        for (GraphNode node : nodes) {
            List<GraphEdge> nodeEdges = knowledgeGraphService.getEdgesForNode(node.getNodeId());
            for (GraphEdge edge : nodeEdges) {
                String edgeKey = edge.getSourceNode().getNodeId() + "->" + edge.getTargetNode().getNodeId()
                        + ":" + (edge.getLabel() != null ? edge.getLabel() : edge.getDescription());
                if (seenEdgeKeys.add(edgeKey)) {
                    edges.add(edge);
                }
            }
        }

        return new GraphContext(nodes, snippets, edges);
    }

    // ── Prompt construction ─────────────────────────────────────────────────

    String buildPrompt(GraphContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a business process analyst. Analyze the following knowledge graph data ");
        sb.append("and identify business processes, workflows, or procedures that are described or implied.\n\n");
        sb.append("A business process is a repeatable sequence of steps performed by people or systems ");
        sb.append("to achieve a goal. Look for:\n");
        sb.append("- Procedures described in documents (e.g., \"Step 1: Fill out form, Step 2: Get approval\")\n");
        sb.append("- Workflows implied by email communications (e.g., request → review → approve → notify)\n");
        sb.append("- Data processing pipelines (e.g., input data → compute → validate → report)\n");
        sb.append("- Multi-document processes where one document describes how to use another\n");
        sb.append("- Approval chains, onboarding procedures, reporting cycles\n");
        sb.append("- Processes that contain sub-processes (hierarchical workflows)\n\n");
        sb.append("The data includes document-level nodes (metadata) AND their chunk content (actual text ");
        sb.append("from the indexed documents). Pay close attention to the DOCUMENT CHUNKS section — ");
        sb.append("that is where process descriptions, step-by-step instructions, and workflow details ");
        sb.append("are most likely to appear.\n\n");

        // Serialize nodes
        sb.append("=== KNOWLEDGE GRAPH NODES ===\n");
        for (GraphNode node : ctx.nodes) {
            sb.append("Node[").append(node.getNodeId()).append("]: ");
            sb.append("type=").append(node.getNodeType());
            if (node.getTitle() != null) sb.append(", title=\"").append(node.getTitle()).append("\"");
            if (node.getDescription() != null) {
                String desc = truncate(node.getDescription(), 200);
                sb.append(", description=\"").append(desc).append("\"");
            }
            if (node.getContentPreview() != null) {
                String preview = truncate(node.getContentPreview(), 300);
                sb.append(", content=\"").append(preview).append("\"");
            }
            if (node.getMetadataJson() != null) {
                String entityType = extractEntityType(node.getMetadataJson());
                if (entityType != null) sb.append(", entity_type=").append(entityType);
            }
            sb.append("\n");
        }

        // Serialize document chunk content — this is where process descriptions live
        if (!ctx.snippets.isEmpty()) {
            sb.append("\n=== DOCUMENT CHUNKS (actual content from indexed documents) ===\n");

            // Group snippets by parent document
            Map<String, List<GraphNode>> snippetsByParent = new LinkedHashMap<>();
            for (GraphNode snippet : ctx.snippets) {
                String parentId = snippet.getParent() != null ? snippet.getParent().getNodeId() : "unknown";
                snippetsByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(snippet);
            }

            for (Map.Entry<String, List<GraphNode>> entry : snippetsByParent.entrySet()) {
                // Find the parent document title
                String parentTitle = entry.getKey();
                for (GraphNode node : ctx.nodes) {
                    if (node.getNodeId().equals(entry.getKey()) && node.getTitle() != null) {
                        parentTitle = node.getTitle();
                        break;
                    }
                }
                sb.append("\n--- Document: ").append(parentTitle)
                        .append(" [").append(entry.getKey()).append("] ---\n");

                for (GraphNode snippet : entry.getValue()) {
                    if (snippet.getContentPreview() != null && !snippet.getContentPreview().isBlank()) {
                        sb.append("  Chunk ").append(snippet.getTitle() != null ? snippet.getTitle() : "")
                                .append(": ");
                        sb.append(truncate(snippet.getContentPreview(), 400));
                        sb.append("\n");
                    }
                }
            }
        }

        // Serialize edges
        sb.append("\n=== KNOWLEDGE GRAPH EDGES ===\n");
        for (GraphEdge edge : ctx.edges) {
            sb.append("Edge: ");
            sb.append(nodeRef(edge.getSourceNode()));
            sb.append(" --[").append(edge.getLabel() != null ? edge.getLabel() : "RELATED_TO").append("]--> ");
            sb.append(nodeRef(edge.getTargetNode()));
            if (edge.getDescription() != null) {
                sb.append(" (").append(truncate(edge.getDescription(), 100)).append(")");
            }
            sb.append("\n");
        }

        // Output schema
        sb.append("\n=== OUTPUT FORMAT ===\n");
        sb.append(getProcessDiscoveryPromptInstructions());

        // Truncate the overall prompt if it exceeds the limit
        if (sb.length() > MAX_CONTENT_CHARS * 3) {
            sb.setLength(MAX_CONTENT_CHARS * 3);
            sb.append("\n... (truncated)\n\n");
            sb.append(getProcessDiscoveryPromptInstructions());
        }

        return sb.toString();
    }

    private String nodeRef(GraphNode node) {
        if (node == null) return "?";
        String title = node.getTitle() != null ? node.getTitle() : node.getNodeId();
        return "\"" + truncate(title, 60) + "\"[" + node.getNodeId() + "]";
    }

    private String extractEntityType(String metadataJson) {
        if (metadataJson == null) return null;
        int idx = metadataJson.indexOf("\"entity_type\":\"");
        if (idx < 0) return null;
        int start = idx + "\"entity_type\":\"".length();
        int end = metadataJson.indexOf("\"", start);
        if (end < 0) return null;
        return metadataJson.substring(start, end);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static String getProcessDiscoveryPromptInstructions() {
        return """
                You MUST respond with a JSON object matching this exact schema:
                {
                  "processes": [
                    {
                      "name": "Quarterly Budget Review",
                      "description": "End-to-end quarterly budget review workflow",
                      "confidence": 0.85,
                      "discoverySource": "LLM_ANALYSIS",
                      "evidence": ["Email from CFO describes 4-step review process", "Budget.xlsx referenced as input"],
                      "sourceNodeIds": ["node-id-1", "node-id-2"],
                      "phases": [
                        {
                          "name": "Data Collection",
                          "description": "Gather budget data from departments",
                          "steps": [
                            {
                              "name": "Distribute budget template",
                              "stepType": "AUTO",
                              "description": "Send budget spreadsheet to department heads",
                              "actor": "Finance Team",
                              "nodeId": "node-id-1"
                            },
                            {
                              "name": "Fill in department figures",
                              "stepType": "HUMAN",
                              "description": "Each department head fills in their budget figures",
                              "actor": "Department Heads"
                            }
                          ]
                        }
                      ],
                      "childProcesses": [
                        {
                          "name": "Spreadsheet Computation",
                          "description": "Calculate totals in budget spreadsheet",
                          "confidence": 0.8,
                          "phases": [...]
                        }
                      ]
                    }
                  ]
                }

                Rules:
                - Each process MUST have: name, description, confidence (0.0-1.0), phases (at least one)
                - Each process SHOULD have: evidence (text snippets supporting this process), sourceNodeIds (KG node IDs involved)
                - Each phase MUST have: name, steps (at least one)
                - Each step MUST have: name, stepType, description
                - stepType must be one of: AUTO, HUMAN, APPROVE, TOOL_CALL, EXCEL_COMPUTE, SCRIPT, HTTP_CALL
                - Each step SHOULD have: actor (who performs it), nodeId (KG node ID if linked to a graph node)
                - childProcesses: optional array of sub-processes (same schema minus childProcesses)
                - confidence should reflect how clearly the process is described or implied in the data
                - discoverySource should be "LLM_ANALYSIS"
                - Output ONLY valid JSON, no markdown fences, no explanations
                """;
    }

    // ── Response parsing ────────────────────────────────────────────────────

    public List<ProcessSuggestion> parseResponse(String response) {
        if (response == null || response.isBlank()) return List.of();

        // Strip markdown fences if present
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();
        }

        // Find the outermost JSON object
        int braceStart = cleaned.indexOf('{');
        int braceEnd = cleaned.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            log.warn("LLM process discovery response contains no valid JSON object");
            return List.of();
        }
        cleaned = cleaned.substring(braceStart, braceEnd + 1);

        try {
            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode processesNode = root.get("processes");
            if (processesNode == null || !processesNode.isArray()) {
                log.warn("LLM response missing 'processes' array");
                return List.of();
            }

            List<ProcessSuggestion> suggestions = new ArrayList<>();
            for (JsonNode processNode : processesNode) {
                ProcessSuggestion suggestion = parseProcessNode(processNode);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
            return suggestions;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM process discovery response: {}", e.getMessage());
            return List.of();
        }
    }

    private ProcessSuggestion parseProcessNode(JsonNode processNode) {
        String name = textOrNull(processNode, "name");
        if (name == null) return null;

        String description = textOrNull(processNode, "description");
        double confidence = processNode.has("confidence") ? processNode.get("confidence").asDouble(0.5) : 0.5;
        String discoverySource = textOrNull(processNode, "discoverySource");
        if (discoverySource == null) discoverySource = "LLM_ANALYSIS";

        // Parse evidence
        List<String> evidence = new ArrayList<>();
        if (processNode.has("evidence") && processNode.get("evidence").isArray()) {
            for (JsonNode e : processNode.get("evidence")) {
                if (e.isTextual()) evidence.add(e.asText());
            }
        }

        // Parse source node IDs
        List<String> sourceNodeIds = new ArrayList<>();
        if (processNode.has("sourceNodeIds") && processNode.get("sourceNodeIds").isArray()) {
            for (JsonNode n : processNode.get("sourceNodeIds")) {
                if (n.isTextual()) sourceNodeIds.add(n.asText());
            }
        }

        // Parse phases
        List<ProcessSuggestion.SuggestedPhase> phases = new ArrayList<>();
        if (processNode.has("phases") && processNode.get("phases").isArray()) {
            for (JsonNode phaseNode : processNode.get("phases")) {
                ProcessSuggestion.SuggestedPhase phase = parsePhaseNode(phaseNode);
                if (phase != null) phases.add(phase);
            }
        }
        // If no phases were parsed, create a default one
        if (phases.isEmpty()) {
            phases.add(ProcessSuggestion.SuggestedPhase.builder()
                    .name("Main")
                    .description(description != null ? description : name)
                    .steps(List.of(ProcessSuggestion.SuggestedStep.builder()
                            .name(name)
                            .stepType("HUMAN")
                            .description(description != null ? description : name)
                            .build()))
                    .build());
        }

        // Parse child processes
        List<ProcessSuggestion> childSuggestions = new ArrayList<>();
        if (processNode.has("childProcesses") && processNode.get("childProcesses").isArray()) {
            for (JsonNode childNode : processNode.get("childProcesses")) {
                ProcessSuggestion child = parseProcessNode(childNode);
                if (child != null) {
                    child.setParentSuggestionId(name);
                    childSuggestions.add(child);
                }
            }
        }

        return ProcessSuggestion.builder()
                .name(name)
                .description(description != null ? description : name)
                .discoverySource(discoverySource)
                .confidence(confidence)
                .phases(phases)
                .sourceGraphNodeIds(sourceNodeIds)
                .evidence(evidence)
                .childSuggestions(childSuggestions)
                .build();
    }

    private ProcessSuggestion.SuggestedPhase parsePhaseNode(JsonNode phaseNode) {
        String name = textOrNull(phaseNode, "name");
        if (name == null) return null;

        String description = textOrNull(phaseNode, "description");

        List<ProcessSuggestion.SuggestedStep> steps = new ArrayList<>();
        if (phaseNode.has("steps") && phaseNode.get("steps").isArray()) {
            for (JsonNode stepNode : phaseNode.get("steps")) {
                ProcessSuggestion.SuggestedStep step = parseStepNode(stepNode);
                if (step != null) steps.add(step);
            }
        }

        if (steps.isEmpty()) return null;

        return ProcessSuggestion.SuggestedPhase.builder()
                .name(name)
                .description(description != null ? description : name)
                .steps(steps)
                .build();
    }

    private ProcessSuggestion.SuggestedStep parseStepNode(JsonNode stepNode) {
        String name = textOrNull(stepNode, "name");
        if (name == null) return null;

        String stepType = textOrNull(stepNode, "stepType");
        if (stepType == null) stepType = "HUMAN";
        // Normalize to valid step types
        stepType = normalizeStepType(stepType);

        String description = textOrNull(stepNode, "description");
        String actor = textOrNull(stepNode, "actor");
        String nodeId = textOrNull(stepNode, "nodeId");
        String toolName = textOrNull(stepNode, "toolName");

        List<String> graphNodeIds = new ArrayList<>();
        if (nodeId != null) graphNodeIds.add(nodeId);

        return ProcessSuggestion.SuggestedStep.builder()
                .name(name)
                .stepType(stepType)
                .description(description != null ? description : name)
                .suggestedAssignee(actor)
                .graphNodeIds(graphNodeIds)
                .toolName(toolName)
                .build();
    }

    private String normalizeStepType(String stepType) {
        if (stepType == null) return "HUMAN";
        return switch (stepType.toUpperCase()) {
            case "AUTO", "AUTOMATIC", "AUTOMATED", "SYSTEM" -> "AUTO";
            case "HUMAN", "MANUAL", "USER" -> "HUMAN";
            case "APPROVE", "APPROVAL", "REVIEW" -> "APPROVE";
            case "TOOL_CALL", "TOOL" -> "TOOL_CALL";
            case "EXCEL_COMPUTE", "COMPUTE", "CALCULATE", "EXCEL" -> "EXCEL_COMPUTE";
            case "SCRIPT", "CODE", "TRANSFORM" -> "SCRIPT";
            case "HTTP_CALL", "API", "HTTP", "REST" -> "HTTP_CALL";
            default -> "HUMAN";
        };
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode val = node.get(field);
        if (val.isNull() || !val.isTextual()) return null;
        String text = val.asText();
        return text.isBlank() ? null : text;
    }
}
