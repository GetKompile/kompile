/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.diagram.service;

import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between Mermaid flowchart diagrams and kompile ProcessDefinitions.
 *
 * <h3>Mermaid → ProcessDefinition</h3>
 * <ul>
 *   <li>Subgraphs → {@link ProcessPhase} (ordered by appearance)</li>
 *   <li>Nodes → {@link ProcessStep} (shape determines default {@link StepType})</li>
 *   <li>Edges → {@code dependsOn} relationships between steps</li>
 * </ul>
 *
 * <h3>Shape → StepType mapping</h3>
 * <table>
 *   <tr><td>{@code A[text]}</td><td>Rectangle</td><td>AUTO</td></tr>
 *   <tr><td>{@code A{text}}</td><td>Diamond</td><td>CONTROL_GATE</td></tr>
 *   <tr><td>{@code A([text])}</td><td>Stadium</td><td>HUMAN</td></tr>
 *   <tr><td>{@code A[[text]]}</td><td>Subroutine</td><td>PIPELINE</td></tr>
 *   <tr><td>{@code A((text))}</td><td>Circle</td><td>AUTO (start/end marker)</td></tr>
 *   <tr><td>{@code A[/text/]}</td><td>Parallelogram</td><td>SCRIPT</td></tr>
 *   <tr><td>{@code A>text]}</td><td>Asymmetric</td><td>TOOL_CALL</td></tr>
 *   <tr><td>{@code A{{text}}}</td><td>Hexagon</td><td>DROOLS_RULE</td></tr>
 *   <tr><td>{@code A(text)}</td><td>Rounded</td><td>AUTO</td></tr>
 * </table>
 *
 * <h3>ProcessDefinition → Mermaid</h3>
 * Reverses the mapping: phases become subgraphs, steps become nodes with
 * shape determined by stepType, and dependsOn edges become arrows.
 */
@Component
public class MermaidProcessConverter {

    // --- Node definition patterns (id + shape + label) ---
    // Order matters: more specific patterns first.
    // These patterns are NOT anchored — tryParseNode prepends the trimmed line check,
    // and extractInlineNodes scans anywhere in the line.
    private static final List<NodeShapePattern> NODE_PATTERNS = List.of(
            new NodeShapePattern(Pattern.compile("(\\w+)\\(\\[(.+?)]\\)"), StepType.HUMAN),       // A([text])  stadium
            new NodeShapePattern(Pattern.compile("(\\w+)\\[\\[(.+?)]]"),   StepType.PIPELINE),     // A[[text]]  subroutine
            new NodeShapePattern(Pattern.compile("(\\w+)\\(\\((.+?)\\)\\)"), StepType.AUTO),       // A((text))  circle
            new NodeShapePattern(Pattern.compile("(\\w+)\\[/(.+?)/]"),     StepType.SCRIPT),       // A[/text/]  parallelogram
            new NodeShapePattern(Pattern.compile("(\\w+)>(.+?)]"),         StepType.TOOL_CALL),    // A>text]    asymmetric
            new NodeShapePattern(Pattern.compile("(\\w+)\\{\\{(.+?)}}"),   StepType.DROOLS_RULE),  // A{{text}}  hexagon
            new NodeShapePattern(Pattern.compile("(\\w+)\\{(.+?)}"),       StepType.CONTROL_GATE), // A{text}    diamond
            new NodeShapePattern(Pattern.compile("(\\w+)\\((.+?)\\)"),     StepType.AUTO),         // A(text)    rounded
            new NodeShapePattern(Pattern.compile("(\\w+)\\[(.+?)]"),       StepType.AUTO)          // A[text]    rectangle
    );

    // Edge pattern: A -->|label| B  or  A --> B  (with optional style like -->, --->, -.->)
    private static final Pattern EDGE_PATTERN = Pattern.compile(
            "(\\w+)\\s*(-+\\.?->|=+>|~+>|-+->)\\s*(?:\\|([^|]*?)\\|\\s*)?(\\w+)");

    // Subgraph pattern
    private static final Pattern SUBGRAPH_START = Pattern.compile(
            "^\\s*subgraph\\s+(?:\"([^\"]+)\"|(\\S+))(?:\\s+\\[(.+?)])?");
    private static final Pattern SUBGRAPH_END = Pattern.compile("^\\s*end\\s*$");

    // classDef / class lines (skip them)
    private static final Pattern CLASS_DEF = Pattern.compile("^\\s*class(?:Def)?\\s+");

    // Flowchart header
    private static final Pattern FLOWCHART_HEADER = Pattern.compile(
            "^\\s*(?:flowchart|graph)\\s+(TD|TB|LR|RL|BT)");

    // Style / click / linkStyle lines (skip)
    private static final Pattern SKIP_LINE = Pattern.compile(
            "^\\s*(?:style|click|linkStyle|direction)\\s+");

    // ---------- Mermaid → ProcessDefinition ----------

    /**
     * Parses a Mermaid flowchart and produces a ProcessDefinition.
     *
     * @param mermaidCode the Mermaid flowchart source
     * @param processName name for the generated process definition
     * @return a ProcessDefinition in DRAFT status
     */
    public ProcessDefinition fromMermaid(String mermaidCode, String processName) {
        if (mermaidCode == null || mermaidCode.isBlank()) {
            throw new IllegalArgumentException("Mermaid code must not be empty");
        }

        String[] lines = mermaidCode.split("\\n");

        // Parsed state
        Map<String, ParsedNode> allNodes = new LinkedHashMap<>();   // nodeId → ParsedNode
        List<ParsedEdge> allEdges = new ArrayList<>();
        List<ParsedSubgraph> subgraphs = new ArrayList<>();
        ParsedSubgraph currentSubgraph = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("%%")) continue;

            // Skip header
            if (FLOWCHART_HEADER.matcher(line).find()) continue;
            if (CLASS_DEF.matcher(line).find()) continue;
            if (SKIP_LINE.matcher(line).find()) continue;

            // Subgraph start
            Matcher sgStart = SUBGRAPH_START.matcher(line);
            if (sgStart.find()) {
                String label = sgStart.group(1) != null ? sgStart.group(1)
                        : (sgStart.group(3) != null ? sgStart.group(3) : sgStart.group(2));
                String sgId = sgStart.group(2) != null ? sgStart.group(2)
                        : label.replaceAll("[^a-zA-Z0-9_]", "_");
                currentSubgraph = new ParsedSubgraph(sgId, label);
                subgraphs.add(currentSubgraph);
                continue;
            }

            // Subgraph end
            if (SUBGRAPH_END.matcher(line).find()) {
                currentSubgraph = null;
                continue;
            }

            // 1. Extract all inline node definitions (ID + shape + label) from the line.
            //    This handles standalone defs like "A[Label]" as well as inline defs
            //    on edge lines like "A[Label] --> B{Decision}".
            extractInlineNodes(line, allNodes, currentSubgraph);

            // 2. Strip node shapes to get bare IDs, then try edge detection.
            //    "A[Step 1] --> B[Process Data]" becomes "A --> B" for edge parsing.
            String stripped = stripNodeShapes(line);
            List<ParsedEdge> lineEdges = parseEdges(stripped);
            if (!lineEdges.isEmpty()) {
                allEdges.addAll(lineEdges);
                // Ensure edge endpoint IDs are at least registered as bare nodes
                for (ParsedEdge edge : lineEdges) {
                    allNodes.putIfAbsent(edge.from, new ParsedNode(edge.from, edge.from, StepType.AUTO));
                    allNodes.putIfAbsent(edge.to, new ParsedNode(edge.to, edge.to, StepType.AUTO));
                    if (currentSubgraph != null) {
                        currentSubgraph.nodeIds.add(edge.from);
                        currentSubgraph.nodeIds.add(edge.to);
                    }
                }
                continue;
            }

            // 3. Bare node reference inside a subgraph (just the ID, no shape)
            if (currentSubgraph != null && line.matches("\\w+")) {
                currentSubgraph.nodeIds.add(line);
                allNodes.putIfAbsent(line, new ParsedNode(line, line, StepType.AUTO));
            }
        }

        // Ensure all edge endpoints are in the node map
        for (ParsedEdge edge : allEdges) {
            allNodes.putIfAbsent(edge.from, new ParsedNode(edge.from, edge.from, StepType.AUTO));
            allNodes.putIfAbsent(edge.to, new ParsedNode(edge.to, edge.to, StepType.AUTO));
        }

        // Build dependsOn map: nodeId → set of predecessor nodeIds
        Map<String, Set<String>> dependsOn = new HashMap<>();
        Map<String, String> edgeLabels = new HashMap<>();
        for (ParsedEdge edge : allEdges) {
            dependsOn.computeIfAbsent(edge.to, k -> new LinkedHashSet<>()).add(edge.from);
            if (edge.label != null && !edge.label.isBlank()) {
                edgeLabels.put(edge.from + "->" + edge.to, edge.label);
            }
        }

        // Build phases
        List<ProcessPhase> phases;
        if (subgraphs.isEmpty()) {
            // No subgraphs — create a single phase with all nodes
            List<ProcessStep> steps = buildSteps(allNodes.values(), allNodes, dependsOn, edgeLabels);
            phases = List.of(ProcessPhase.builder()
                    .id("phase-1")
                    .name("Main")
                    .order(1)
                    .steps(steps)
                    .build());
        } else {
            // Collect nodes already assigned to subgraphs
            Set<String> assignedNodeIds = new HashSet<>();
            phases = new ArrayList<>();
            int order = 1;
            for (ParsedSubgraph sg : subgraphs) {
                List<ParsedNode> phaseNodes = new ArrayList<>();
                for (String nid : sg.nodeIds) {
                    ParsedNode n = allNodes.get(nid);
                    if (n != null) {
                        phaseNodes.add(n);
                        assignedNodeIds.add(nid);
                    }
                }
                if (!phaseNodes.isEmpty()) {
                    phases.add(ProcessPhase.builder()
                            .id("phase-" + order)
                            .name(sg.label)
                            .order(order++)
                            .steps(buildSteps(phaseNodes, allNodes, dependsOn, edgeLabels))
                            .build());
                }
            }

            // Unassigned nodes go into a catch-all phase
            List<ParsedNode> unassigned = new ArrayList<>();
            for (ParsedNode n : allNodes.values()) {
                if (!assignedNodeIds.contains(n.id)) {
                    unassigned.add(n);
                }
            }
            if (!unassigned.isEmpty()) {
                phases.add(ProcessPhase.builder()
                        .id("phase-" + order)
                        .name("Other")
                        .order(order)
                        .steps(buildSteps(unassigned, allNodes, dependsOn, edgeLabels))
                        .build());
            }
        }

        return ProcessDefinition.builder()
                .name(processName != null ? processName : "Converted Process")
                .phases(phases)
                .build();
    }

    // ---------- ProcessDefinition → Mermaid ----------

    /**
     * Renders a ProcessDefinition as a Mermaid flowchart.
     */
    public String toMermaid(ProcessDefinition definition) {
        if (definition == null || definition.getPhases() == null) {
            return "flowchart TD\n    empty[No process defined]";
        }

        StringBuilder sb = new StringBuilder("flowchart TD\n");

        // Collect all edges for rendering after nodes
        List<String> edges = new ArrayList<>();
        boolean useSubgraphs = definition.getPhases().size() > 1;

        for (ProcessPhase phase : definition.getPhases()) {
            if (phase.getSteps() == null || phase.getSteps().isEmpty()) continue;

            if (useSubgraphs) {
                sb.append("    subgraph ").append(sanitizeId(phase.getId()))
                        .append("[\"").append(escapeLabel(phase.getName())).append("\"]\n");
            }

            for (ProcessStep step : phase.getSteps()) {
                String nodeId = sanitizeId(step.getId());
                String label = escapeLabel(step.getName());
                String nodeDecl = renderNodeShape(nodeId, label, step.getStepType());

                sb.append(useSubgraphs ? "        " : "    ")
                        .append(nodeDecl).append("\n");

                // dependsOn edges
                if (step.getDependsOn() != null) {
                    for (String dep : step.getDependsOn()) {
                        String labelText = step.getConditionLabel();
                        if (labelText != null && !labelText.isBlank()) {
                            edges.add("    " + sanitizeId(dep) + " -->|"
                                    + escapeLabel(labelText) + "| " + nodeId);
                        } else {
                            edges.add("    " + sanitizeId(dep) + " --> " + nodeId);
                        }
                    }
                }
            }

            if (useSubgraphs) {
                sb.append("    end\n");
            }
        }

        // Append edges
        if (!edges.isEmpty()) {
            sb.append("\n");
            for (String edge : edges) {
                sb.append(edge).append("\n");
            }
        }

        // Style classes
        sb.append("\n");
        sb.append("    classDef human fill:#e1bee7,stroke:#8e24aa,color:#000\n");
        sb.append("    classDef gate fill:#fff9c4,stroke:#f9a825,color:#000\n");
        sb.append("    classDef pipeline fill:#bbdefb,stroke:#1565c0,color:#000\n");
        sb.append("    classDef script fill:#c8e6c9,stroke:#2e7d32,color:#000\n");
        sb.append("    classDef tool fill:#ffe0b2,stroke:#e65100,color:#000\n");
        sb.append("    classDef drools fill:#dcedc8,stroke:#558b2f,color:#000\n");
        sb.append("    classDef approve fill:#f3e5f5,stroke:#6a1b9a,color:#000\n");

        // Apply classes to nodes
        for (ProcessPhase phase : definition.getPhases()) {
            if (phase.getSteps() == null) continue;
            for (ProcessStep step : phase.getSteps()) {
                String cls = stepTypeToClass(step.getStepType());
                if (cls != null) {
                    sb.append("    class ").append(sanitizeId(step.getId())).append(" ").append(cls).append("\n");
                }
            }
        }

        return sb.toString().stripTrailing();
    }

    // ---------- Internal parsing helpers ----------

    private List<ParsedEdge> parseEdges(String line) {
        List<ParsedEdge> edges = new ArrayList<>();
        Matcher m = EDGE_PATTERN.matcher(line);
        while (m.find()) {
            String from = m.group(1);
            String label = m.group(3);
            String to = m.group(4);
            edges.add(new ParsedEdge(from, to, label, m.group(0), m.group(0)));
        }
        return edges;
    }

    /**
     * Strips node shape suffixes from a line, leaving just bare IDs.
     * E.g. "A[Step 1] --> B{Decision}" becomes "A --> B".
     */
    private String stripNodeShapes(String line) {
        String result = line;
        // Strip from most specific to least specific to avoid partial matches.
        // Order: stadium ([...]), subroutine [[...]], circle ((...)), parallelogram [/.../],
        //        asymmetric >...], hexagon {{...}}, diamond {...}, rounded (...), rectangle [...]
        result = result.replaceAll("(\\w+)\\(\\[.+?]\\)", "$1");   // stadium
        result = result.replaceAll("(\\w+)\\[\\[.+?]]", "$1");    // subroutine
        result = result.replaceAll("(\\w+)\\(\\(.+?\\)\\)", "$1"); // circle
        result = result.replaceAll("(\\w+)\\[/.+?/]", "$1");      // parallelogram
        result = result.replaceAll("(\\w+)>.+?]", "$1");          // asymmetric
        result = result.replaceAll("(\\w+)\\{\\{.+?}}", "$1");    // hexagon
        result = result.replaceAll("(\\w+)\\{.+?}", "$1");        // diamond
        result = result.replaceAll("(\\w+)\\(.+?\\)", "$1");      // rounded
        result = result.replaceAll("(\\w+)\\[.+?]", "$1");        // rectangle
        return result;
    }

    private void extractInlineNodes(String line, Map<String, ParsedNode> nodes,
                                     ParsedSubgraph currentSubgraph) {
        // Scan the line for all node definitions (ID + shape + label).
        // NODE_PATTERNS is ordered from most specific to least specific,
        // so we only keep the first (most specific) match for each ID.
        Set<String> alreadyParsed = new HashSet<>();
        for (NodeShapePattern nsp : NODE_PATTERNS) {
            Matcher m = nsp.pattern.matcher(line);
            int searchFrom = 0;
            while (m.find(searchFrom)) {
                String id = m.group(1);
                if (alreadyParsed.add(id)) {
                    String label = stripLabelQuotes(m.group(2).trim());
                    nodes.put(id, new ParsedNode(id, label, inferStepType(label, nsp.defaultType)));
                    if (currentSubgraph != null) {
                        currentSubgraph.nodeIds.add(id);
                    }
                }
                searchFrom = m.end();
            }
        }
    }

    private ParsedNode tryParseNode(String line) {
        for (NodeShapePattern nsp : NODE_PATTERNS) {
            Matcher m = nsp.pattern.matcher(line);
            if (m.find() && m.start() == 0) {
                String id = m.group(1);
                String label = stripLabelQuotes(m.group(2).trim());
                return new ParsedNode(id, label, inferStepType(label, nsp.defaultType));
            }
        }
        return null;
    }

    private List<ProcessStep> buildSteps(Collection<ParsedNode> nodes,
                                          Map<String, ParsedNode> allNodes,
                                          Map<String, Set<String>> dependsOn,
                                          Map<String, String> edgeLabels) {
        List<ProcessStep> steps = new ArrayList<>();
        for (ParsedNode node : nodes) {
            Set<String> deps = dependsOn.getOrDefault(node.id, Set.of());
            ProcessStep.ProcessStepBuilder builder = ProcessStep.builder()
                    .id(node.id)
                    .name(node.label)
                    .stepType(node.stepType);

            if (!deps.isEmpty()) {
                builder.dependsOn(new ArrayList<>(deps));
            }

            List<String> branchLabels = new ArrayList<>();
            List<String> branchExpressions = new ArrayList<>();
            for (String dep : deps) {
                ParsedNode sourceNode = allNodes.get(dep);
                String edgeLabel = edgeLabels.get(dep + "->" + node.id);
                if (sourceNode != null && isDroolsType(sourceNode.stepType)
                        && edgeLabel != null && !edgeLabel.isBlank()) {
                    branchLabels.add(edgeLabel);
                    branchExpressions.add(droolsDecisionCondition(dep, edgeLabel));
                }
            }
            if (!branchExpressions.isEmpty()) {
                builder.conditionLabel(String.join(",", branchLabels));
                builder.conditionExpression(String.join(" || ", branchExpressions));
            }

            // For CONTROL_GATE steps, add edge labels as metadata hints
            if (node.stepType == StepType.CONTROL_GATE) {
                Map<String, Object> meta = new HashMap<>();
                for (String dep : deps) {
                    String lbl = edgeLabels.get(dep + "->" + node.id);
                    if (lbl != null) meta.put("incomingLabel_" + dep, lbl);
                }
                // Check outgoing labels
                for (Map.Entry<String, String> e : edgeLabels.entrySet()) {
                    if (e.getKey().startsWith(node.id + "->")) {
                        meta.put("outgoingLabel_" + e.getKey().split("->")[1], e.getValue());
                    }
                }
                if (!meta.isEmpty()) {
                    builder.metadata(meta);
                }
            }

            steps.add(builder.build());
        }
        return steps;
    }

    // ---------- Internal rendering helpers ----------

    private String renderNodeShape(String nodeId, String label, StepType stepType) {
        if (stepType == null) stepType = StepType.AUTO;
        return switch (stepType) {
            case HUMAN -> nodeId + "([\"" + label + "\"])";
            case APPROVE -> nodeId + "([\"" + label + "\"])";
            case CONTROL_GATE -> nodeId + "{\"" + label + "\"}";
            case PIPELINE -> nodeId + "[[\"" + label + "\"]]";
            case SCRIPT -> nodeId + "[/\"" + label + "\"/]";
            case TOOL_CALL -> nodeId + ">\"" + label + "\"]";
            case DROOLS_RULE, DROOLS_INFERENCE, DROOLS_DECISION_TABLE -> nodeId + "{{\"" + label + "\"}}";
            default -> nodeId + "[\"" + label + "\"]";
        };
    }

    private String sanitizeId(String id) {
        if (id == null) return "unknown";
        // Mermaid IDs: alphanumeric + underscore
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeLabel(String label) {
        if (label == null) return "";
        return label.replace("\"", "'");
    }

    private String stepTypeToClass(StepType type) {
        if (type == null) return null;
        return switch (type) {
            case HUMAN -> "human";
            case APPROVE -> "approve";
            case CONTROL_GATE -> "gate";
            case PIPELINE -> "pipeline";
            case SCRIPT -> "script";
            case TOOL_CALL -> "tool";
            case DROOLS_RULE, DROOLS_INFERENCE, DROOLS_DECISION_TABLE -> "drools";
            default -> null;
        };
    }

    private StepType inferStepType(String label, StepType defaultType) {
        String normalized = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (normalized.contains("decision table")) {
            return StepType.DROOLS_DECISION_TABLE;
        }
        if (normalized.contains("infer") || normalized.contains("reason")) {
            return StepType.DROOLS_INFERENCE;
        }
        if (normalized.contains("drools")
                || normalized.contains("business rule")
                || normalized.contains("rules")) {
            return StepType.DROOLS_RULE;
        }
        return defaultType;
    }

    private boolean isDroolsType(StepType type) {
        return type == StepType.DROOLS_RULE
                || type == StepType.DROOLS_INFERENCE
                || type == StepType.DROOLS_DECISION_TABLE;
    }

    private String droolsDecisionCondition(String sourceId, String label) {
        String escapedLabel = label.replace("'", "''");
        String sourceDecisionKey = sanitizeId(sourceId) + "_decision";
        return "(#" + sourceDecisionKey + " != null ? #"
                + sourceDecisionKey + ".toString().equalsIgnoreCase('" + escapedLabel + "')"
                + " : (#decision != null && #decision.toString().equalsIgnoreCase('" + escapedLabel + "')))";
    }

    private String stripLabelQuotes(String label) {
        if (label == null || label.length() < 2) {
            return label;
        }
        if (label.startsWith("\"") && label.endsWith("\"")) {
            return label.substring(1, label.length() - 1);
        }
        return label;
    }

    // ---------- Internal data classes ----------

    private record NodeShapePattern(Pattern pattern, StepType defaultType) {}

    static class ParsedNode {
        final String id;
        final String label;
        final StepType stepType;

        ParsedNode(String id, String label, StepType stepType) {
            this.id = id;
            this.label = label;
            this.stepType = stepType;
        }
    }

    private record ParsedEdge(String from, String to, String label, String fromRaw, String toRaw) {}

    private static class ParsedSubgraph {
        final String id;
        final String label;
        final Set<String> nodeIds = new LinkedHashSet<>();

        ParsedSubgraph(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
