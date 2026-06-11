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

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes knowledge graph data to discover repeatable process patterns.
 * Uses graph traversal and pattern matching on email flows, Excel formulas,
 * and document relationships.
 */
@Slf4j
@Service
@ConditionalOnBean(KnowledgeGraphService.class)
public class ProcessDiscoveryServiceImpl implements ProcessDiscoveryService {

    private final KnowledgeGraphService knowledgeGraphService;
    private GraphNodeRepository graphNodeRepository;
    private ProcessEngineService processEngineService;
    private LlmProcessDiscoveryService llmProcessDiscoveryService;

    @Autowired
    public ProcessDiscoveryServiceImpl(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Autowired(required = false)
    public void setGraphNodeRepository(GraphNodeRepository graphNodeRepository) {
        this.graphNodeRepository = graphNodeRepository;
    }

    @Autowired(required = false)
    public void setProcessEngineService(ProcessEngineService processEngineService) {
        this.processEngineService = processEngineService;
    }

    @Autowired(required = false)
    public void setLlmProcessDiscoveryService(LlmProcessDiscoveryService llmProcessDiscoveryService) {
        this.llmProcessDiscoveryService = llmProcessDiscoveryService;
    }

    /**
     * Automatically runs process discovery when a graph build completes.
     * Runs asynchronously so it doesn't block the graph build thread.
     */
    @Async
    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        log.info("Graph build completed (job {}): {} entities, {} edges, factSheetId={} — running process discovery",
                event.getJobId(), event.getEntitiesExtracted(), event.getEdgesCreated(), event.getFactSheetId());
        try {
            List<ProcessSuggestion> suggestions;
            if (event.getFactSheetId() != null) {
                suggestions = discoverProcessesForFactSheet(event.getFactSheetId(), Map.of());
            } else {
                suggestions = discoverProcesses(null, Map.of());
            }
            log.info("Process discovery found {} suggestions after graph build {}",
                    suggestions.size(), event.getJobId());
        } catch (Exception e) {
            log.warn("Process discovery failed after graph build {}: {}", event.getJobId(), e.getMessage());
        }
    }

    @Override
    public List<ProcessSuggestion> discoverProcesses(List<String> graphNodeIds, Map<String, Object> options) {
        List<ProcessSuggestion> suggestions = new ArrayList<>();

        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }

        // Analyze email flows
        List<FlowPattern> emailFlows = analyzeEmailFlows(graphNodeIds);
        for (FlowPattern flow : emailFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze Excel computation flows
        List<FlowPattern> excelFlows = analyzeExcelFlows(graphNodeIds);
        for (FlowPattern flow : excelFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze document flows (PDF, Office, etc.)
        List<FlowPattern> documentFlows = analyzeDocumentFlows(graphNodeIds);
        for (FlowPattern flow : documentFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze cross-document flows (email → spreadsheet, document → sub-procedure)
        List<FlowPattern> crossDocFlows = analyzeCrossDocumentFlows(graphNodeIds);
        for (FlowPattern flow : crossDocFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToHierarchicalSuggestion(flow));
            }
        }

        // LLM-based discovery: let the LLM read graph content and identify processes
        // described in natural language (procedures, instructions, workflows)
        boolean useLlm = true;
        if (options != null && Boolean.FALSE.equals(options.get("useLlm"))) {
            useLlm = false;
        }
        if (useLlm && llmProcessDiscoveryService != null && llmProcessDiscoveryService.isAvailable()) {
            try {
                List<ProcessSuggestion> llmSuggestions = llmProcessDiscoveryService.discoverProcesses(
                        graphNodeIds, options);
                suggestions.addAll(llmSuggestions);
            } catch (Exception e) {
                log.warn("LLM process discovery failed, continuing with pattern-based results: {}",
                        e.getMessage());
            }
        }

        // Sort by confidence descending
        suggestions.sort(Comparator.comparingDouble(ProcessSuggestion::getConfidence).reversed());
        return suggestions;
    }

    @Override
    public List<ProcessSuggestion> discoverProcessesWithLlm(List<String> graphNodeIds, Map<String, Object> options) {
        if (llmProcessDiscoveryService == null || !llmProcessDiscoveryService.isAvailable()) {
            log.warn("LLM process discovery not available — no LLM provider configured");
            return List.of();
        }
        return llmProcessDiscoveryService.discoverProcesses(graphNodeIds, options != null ? options : Map.of());
    }

    @Override
    public List<FlowPattern> analyzeEmailFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Find all PERSON entities and their email interactions
        List<GraphNode> personNodes;
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            personNodes = new ArrayList<>();
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (isPersonNode(node)) {
                        personNodes.add(node);
                    }
                    // Also check connected nodes
                    List<GraphNode> connected = knowledgeGraphService.getConnectedNodes(nodeId, 1);
                    for (GraphNode n : connected) {
                        if (isPersonNode(n)) personNodes.add(n);
                    }
                });
            }
        } else {
            personNodes = fetchAllNodesPaginated(NodeLevel.ENTITY);
            personNodes.removeIf(n -> !isPersonNode(n));
        }

        // Group emails by sender → recipient pairs
        Map<String, List<GraphEdge>> emailPairs = new LinkedHashMap<>();
        for (GraphNode person : personNodes) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(person.getNodeId());
            for (GraphEdge edge : edges) {
                String label = edge.getLabel() != null ? edge.getLabel() : edge.getDescription();
                if (label != null && (label.contains("SENT_BY") || label.contains("SENT_TO")
                        || label.contains("CC_TO") || label.contains("BCC_TO")
                        || label.contains("REPLIED_TO") || label.contains("FORWARDED_TO"))) {
                    String pairKey = edge.getSourceNode().getNodeId() + "->" + edge.getTargetNode().getNodeId();
                    emailPairs.computeIfAbsent(pairKey, k -> new ArrayList<>()).add(edge);
                }
            }
        }

        // Each repeated sender→recipient pair with attachments is a potential flow
        for (Map.Entry<String, List<GraphEdge>> entry : emailPairs.entrySet()) {
            if (entry.getValue().size() >= 2) {
                FlowPattern pattern = FlowPattern.builder()
                        .type("EMAIL_FLOW")
                        .description("Recurring email exchange: " + entry.getKey())
                        .occurrenceCount(entry.getValue().size())
                        .confidence(Math.min(0.5 + (entry.getValue().size() * 0.1), 0.9))
                        .steps(buildEmailFlowSteps(entry.getValue()))
                        .involvedNodeIds(entry.getValue().stream()
                                .flatMap(e -> List.of(e.getSourceNode().getNodeId(), e.getTargetNode().getNodeId()).stream())
                                .distinct().collect(Collectors.toList()))
                        .build();
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    @Override
    public List<FlowPattern> analyzeExcelFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Find TABLE (SHEET) nodes that are part of Excel workbooks
        List<GraphNode> sheetNodes;
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            sheetNodes = new ArrayList<>();
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (node.getNodeType() == NodeLevel.TABLE && isSheetNode(node)) {
                        sheetNodes.add(node);
                    }
                    // Check children for sheet nodes
                    List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                    for (GraphNode child : children) {
                        if (child.getNodeType() == NodeLevel.TABLE && isSheetNode(child)) {
                            sheetNodes.add(child);
                        }
                    }
                });
            }
        } else {
            // Search for both Excel sheets and generic table nodes
            sheetNodes = new ArrayList<>(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100));
            List<GraphNode> tableNodes = knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100);
            for (GraphNode t : tableNodes) {
                if (sheetNodes.stream().noneMatch(s -> s.getNodeId().equals(t.getNodeId()))) {
                    sheetNodes.add(t);
                }
            }
            sheetNodes.removeIf(n -> !isSheetNode(n));
        }

        for (GraphNode sheetNode : sheetNodes) {
            // Get all ENTITY children (cells) and their dependency edges
            List<GraphNode> cells = knowledgeGraphService.getChildren(sheetNode.getNodeId());
            List<GraphNode> formulaCells = cells.stream()
                    .filter(this::isFormulaCellNode)
                    .collect(Collectors.toList());
            List<GraphNode> inputCells = cells.stream()
                    .filter(c -> !isFormulaCellNode(c))
                    .collect(Collectors.toList());

            if (!formulaCells.isEmpty()) {
                List<FlowPattern.FlowStep> steps = new ArrayList<>();

                // Input step: gather input values
                if (!inputCells.isEmpty()) {
                    steps.add(FlowPattern.FlowStep.builder()
                            .description("Provide input values for " + inputCells.size() + " cells")
                            .actor("user")
                            .action("INPUT")
                            .target(sheetNode.getTitle())
                            .nodeId(sheetNode.getNodeId())
                            .build());
                }

                // Compute step: execute formulas
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Execute " + formulaCells.size() + " formula(s) in " + sheetNode.getTitle())
                        .actor("system")
                        .action("COMPUTE")
                        .target(sheetNode.getTitle())
                        .nodeId(sheetNode.getNodeId())
                        .build());

                // Review step: human reviews output
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Review computed results")
                        .actor("user")
                        .action("APPROVE")
                        .target(sheetNode.getTitle())
                        .build());

                FlowPattern pattern = FlowPattern.builder()
                        .type("EXCEL_COMPUTATION")
                        .description("Excel computation workflow: " + sheetNode.getTitle()
                                + " (" + formulaCells.size() + " formulas, " + inputCells.size() + " inputs)")
                        .occurrenceCount(1)
                        .confidence(0.7 + Math.min(formulaCells.size() * 0.05, 0.2))
                        .steps(steps)
                        .involvedNodeIds(cells.stream()
                                .map(GraphNode::getNodeId)
                                .collect(Collectors.toList()))
                        .build();
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    @Override
    public List<FlowPattern> analyzeDocumentFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Find DOCUMENT nodes to analyze
        List<GraphNode> documentNodes;
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            documentNodes = new ArrayList<>();
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (node.getNodeType() == NodeLevel.DOCUMENT) {
                        documentNodes.add(node);
                    }
                    // Also check children for document nodes
                    List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                    for (GraphNode child : children) {
                        if (child.getNodeType() == NodeLevel.DOCUMENT) {
                            documentNodes.add(child);
                        }
                    }
                });
            }
        } else {
            documentNodes = fetchAllNodesPaginated(NodeLevel.DOCUMENT);
        }

        if (documentNodes.isEmpty()) return patterns;

        // Strategy 1: Author-based document pipelines
        // Group documents by author PERSON entity (via AUTHORED_BY edges)
        patterns.addAll(analyzeAuthorPipelines(documentNodes));

        // Strategy 2: Version chain workflows
        // Find VERSION_OF edge chains that suggest review/approval cycles
        patterns.addAll(analyzeVersionChainWorkflows(documentNodes));

        // Strategy 3: Topic cluster workflows
        // Documents sharing the same TOPIC entities form content pipelines
        patterns.addAll(analyzeTopicClusterWorkflows(documentNodes));

        // Strategy 4: Form-based data collection
        // PDF documents with FORM_FIELD entities suggest data collection workflows
        patterns.addAll(analyzeFormCollectionWorkflows(documentNodes));

        return patterns;
    }

    private List<FlowPattern> analyzeAuthorPipelines(List<GraphNode> documentNodes) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Group documents by author person node
        Map<String, List<GraphNode>> docsByAuthor = new LinkedHashMap<>();
        for (GraphNode doc : documentNodes) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(doc.getNodeId());
            for (GraphEdge edge : edges) {
                String label = edge.getLabel() != null ? edge.getLabel() : edge.getDescription();
                if (label != null && (label.contains("AUTHORED_BY") || label.contains("CREATED_BY"))) {
                    String authorNodeId = edge.getTargetNode().getNodeId();
                    docsByAuthor.computeIfAbsent(authorNodeId, k -> new ArrayList<>()).add(doc);
                }
            }
        }

        // Each author with 2+ documents is a potential authoring pipeline
        for (Map.Entry<String, List<GraphNode>> entry : docsByAuthor.entrySet()) {
            List<GraphNode> authoredDocs = entry.getValue();
            if (authoredDocs.size() < 2) continue;

            String authorName = knowledgeGraphService.getNode(entry.getKey())
                    .map(GraphNode::getTitle).orElse("Unknown Author");

            List<FlowPattern.FlowStep> steps = new ArrayList<>();
            for (GraphNode doc : authoredDocs) {
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Author creates: " + doc.getTitle())
                        .actor(authorName)
                        .action("TRANSFORM")
                        .target(doc.getTitle())
                        .nodeId(doc.getNodeId())
                        .build());
            }
            // Add review step
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Review authored documents")
                    .actor("reviewer")
                    .action("APPROVE")
                    .target(authorName + "'s documents")
                    .build());

            patterns.add(FlowPattern.builder()
                    .type("DOCUMENT_AUTHORING")
                    .description("Document authoring pipeline by " + authorName
                            + " (" + authoredDocs.size() + " documents)")
                    .occurrenceCount(authoredDocs.size())
                    .confidence(Math.min(0.5 + (authoredDocs.size() * 0.1), 0.85))
                    .steps(steps)
                    .involvedNodeIds(authoredDocs.stream()
                            .map(GraphNode::getNodeId).collect(Collectors.toList()))
                    .build());
        }

        return patterns;
    }

    private List<FlowPattern> analyzeVersionChainWorkflows(List<GraphNode> documentNodes) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Find documents with VERSION_OF edges
        Set<String> docNodeIds = documentNodes.stream()
                .map(GraphNode::getNodeId).collect(Collectors.toSet());

        for (GraphNode doc : documentNodes) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(doc.getNodeId());
            List<GraphNode> versionChain = new ArrayList<>();
            versionChain.add(doc);

            for (GraphEdge edge : edges) {
                String label = edge.getLabel() != null ? edge.getLabel() : edge.getDescription();
                if (label != null && label.contains("VERSION_OF")) {
                    String otherNodeId = edge.getSourceNode().getNodeId().equals(doc.getNodeId())
                            ? edge.getTargetNode().getNodeId()
                            : edge.getSourceNode().getNodeId();
                    if (docNodeIds.contains(otherNodeId)) {
                        knowledgeGraphService.getNode(otherNodeId).ifPresent(versionChain::add);
                    }
                }
            }

            if (versionChain.size() >= 2) {
                List<FlowPattern.FlowStep> steps = new ArrayList<>();
                // Draft step
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Create initial draft")
                        .actor("author")
                        .action("TRANSFORM")
                        .target(versionChain.get(0).getTitle())
                        .nodeId(versionChain.get(0).getNodeId())
                        .build());
                // Review/revision steps for intermediate versions
                for (int i = 1; i < versionChain.size(); i++) {
                    steps.add(FlowPattern.FlowStep.builder()
                            .description("Review and revise: " + versionChain.get(i).getTitle())
                            .actor("reviewer")
                            .action("APPROVE")
                            .target(versionChain.get(i).getTitle())
                            .nodeId(versionChain.get(i).getNodeId())
                            .build());
                }

                patterns.add(FlowPattern.builder()
                        .type("DOCUMENT_REVIEW_CYCLE")
                        .description("Document review/revision cycle: " + doc.getTitle()
                                + " (" + versionChain.size() + " versions)")
                        .occurrenceCount(versionChain.size())
                        .confidence(Math.min(0.6 + (versionChain.size() * 0.1), 0.9))
                        .steps(steps)
                        .involvedNodeIds(versionChain.stream()
                                .map(GraphNode::getNodeId).collect(Collectors.toList()))
                        .build());
            }
        }

        return patterns;
    }

    private List<FlowPattern> analyzeTopicClusterWorkflows(List<GraphNode> documentNodes) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Group documents by shared TOPIC entities
        Map<String, List<GraphNode>> docsByTopic = new LinkedHashMap<>();
        for (GraphNode doc : documentNodes) {
            // Check child entities for TOPIC type
            List<GraphNode> children = knowledgeGraphService.getChildren(doc.getNodeId());
            for (GraphNode child : children) {
                if (isTopicNode(child)) {
                    docsByTopic.computeIfAbsent(child.getNodeId(), k -> new ArrayList<>()).add(doc);
                }
            }
            // Also check edges from this document
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(doc.getNodeId());
            for (GraphEdge edge : edges) {
                String label = edge.getLabel() != null ? edge.getLabel() : edge.getDescription();
                if (label != null && label.contains("HAS_TOPIC")) {
                    String topicNodeId = edge.getTargetNode().getNodeId();
                    docsByTopic.computeIfAbsent(topicNodeId, k -> new ArrayList<>()).add(doc);
                }
            }
        }

        // Topic clusters with 3+ documents suggest content pipelines
        for (Map.Entry<String, List<GraphNode>> entry : docsByTopic.entrySet()) {
            List<GraphNode> clusterDocs = entry.getValue();
            if (clusterDocs.size() < 3) continue;

            String topicName = knowledgeGraphService.getNode(entry.getKey())
                    .map(GraphNode::getTitle).orElse("Unknown Topic");

            List<FlowPattern.FlowStep> steps = new ArrayList<>();
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Gather documents on topic: " + topicName)
                    .actor("system")
                    .action("INPUT")
                    .target(topicName)
                    .nodeId(entry.getKey())
                    .build());
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Review " + clusterDocs.size() + " documents in cluster")
                    .actor("user")
                    .action("APPROVE")
                    .target(topicName + " cluster")
                    .build());
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Synthesize findings from topic cluster")
                    .actor("user")
                    .action("TRANSFORM")
                    .target("Summary for " + topicName)
                    .build());

            patterns.add(FlowPattern.builder()
                    .type("TOPIC_CLUSTER_PIPELINE")
                    .description("Topic-based content pipeline: " + topicName
                            + " (" + clusterDocs.size() + " documents)")
                    .occurrenceCount(clusterDocs.size())
                    .confidence(Math.min(0.4 + (clusterDocs.size() * 0.08), 0.8))
                    .steps(steps)
                    .involvedNodeIds(clusterDocs.stream()
                            .map(GraphNode::getNodeId).collect(Collectors.toList()))
                    .build());
        }

        return patterns;
    }

    private List<FlowPattern> analyzeFormCollectionWorkflows(List<GraphNode> documentNodes) {
        List<FlowPattern> patterns = new ArrayList<>();

        for (GraphNode doc : documentNodes) {
            // Find FORM_FIELD child entities
            List<GraphNode> children = knowledgeGraphService.getChildren(doc.getNodeId());
            List<GraphNode> formFields = children.stream()
                    .filter(this::isFormFieldNode)
                    .collect(Collectors.toList());

            if (formFields.size() >= 2) {
                List<FlowPattern.FlowStep> steps = new ArrayList<>();
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Distribute form: " + doc.getTitle())
                        .actor("system")
                        .action("SEND")
                        .target(doc.getTitle())
                        .nodeId(doc.getNodeId())
                        .build());
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Fill " + formFields.size() + " form fields")
                        .actor("user")
                        .action("INPUT")
                        .target(doc.getTitle())
                        .nodeId(doc.getNodeId())
                        .build());
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Review submitted form data")
                        .actor("reviewer")
                        .action("APPROVE")
                        .target(doc.getTitle())
                        .build());

                patterns.add(FlowPattern.builder()
                        .type("FORM_DATA_COLLECTION")
                        .description("Form-based data collection: " + doc.getTitle()
                                + " (" + formFields.size() + " fields)")
                        .occurrenceCount(1)
                        .confidence(0.7 + Math.min(formFields.size() * 0.03, 0.2))
                        .steps(steps)
                        .involvedNodeIds(List.of(doc.getNodeId()))
                        .build());
            }
        }

        return patterns;
    }

    private boolean isTopicNode(GraphNode node) {
        if (node.getNodeType() != NodeLevel.ENTITY) return false;
        String meta = node.getMetadataJson();
        return meta != null && meta.contains("\"entity_type\":\"TOPIC\"");
    }

    private boolean isFormFieldNode(GraphNode node) {
        if (node.getNodeType() != NodeLevel.ENTITY) return false;
        String meta = node.getMetadataJson();
        return meta != null && meta.contains("\"entity_type\":\"FORM_FIELD\"");
    }

    @Override
    public List<FlowPattern> analyzeCrossDocumentFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Strategy 1: Email messages with attachments — the email describes a procedure
        // involving the attachment (e.g., "please fill out the attached spreadsheet")
        patterns.addAll(analyzeEmailAttachmentFlows(graphNodeIds));

        // Strategy 2: Documents that reference other documents via REFERENCES_DOCUMENT,
        // DESCRIBES_PROCEDURE, or INSTRUCTS_USAGE edges
        patterns.addAll(analyzeDocumentReferenceFlows(graphNodeIds));

        // Strategy 3: Detect implicit references — emails/documents whose content or
        // title mentions a spreadsheet/document name that exists in the graph
        patterns.addAll(analyzeImplicitDocumentReferences(graphNodeIds));

        return patterns;
    }

    /**
     * Finds email messages with attachments (spreadsheets, PDFs, etc.) and composes
     * a parent process (email-driven workflow) with child processes for each
     * attachment type.
     */
    private List<FlowPattern> analyzeEmailAttachmentFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Find all EMAIL_MESSAGE or GMAIL_MESSAGE entities
        List<GraphNode> emailNodes = findNodesByEntityType(graphNodeIds, "EMAIL_MESSAGE", "GMAIL_MESSAGE");

        for (GraphNode emailNode : emailNodes) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(emailNode.getNodeId());

            // Find attachment edges and the referenced attachment nodes
            List<GraphNode> attachedSpreadsheets = new ArrayList<>();
            List<GraphNode> attachedDocuments = new ArrayList<>();
            List<GraphNode> senders = new ArrayList<>();
            List<GraphNode> recipients = new ArrayList<>();

            for (GraphEdge edge : edges) {
                String label = edgeLabel(edge);
                if (label == null) continue;

                if (label.contains("HAS_ATTACHMENT") || label.contains("ATTACHMENT")) {
                    GraphNode attached = otherNode(edge, emailNode);
                    if (attached != null) {
                        if (isSpreadsheetNode(attached)) {
                            attachedSpreadsheets.add(attached);
                        } else {
                            attachedDocuments.add(attached);
                        }
                    }
                } else if (label.contains("SENT_BY")) {
                    GraphNode sender = otherNode(edge, emailNode);
                    if (sender != null) senders.add(sender);
                } else if (label.contains("SENT_TO") || label.contains("CC_TO")) {
                    GraphNode recipient = otherNode(edge, emailNode);
                    if (recipient != null) recipients.add(recipient);
                }
            }

            // Only proceed if the email has spreadsheet attachments — this is the
            // "email describes how to use a spreadsheet" scenario
            if (attachedSpreadsheets.isEmpty()) continue;

            // Build child patterns for each attached spreadsheet
            List<FlowPattern> childPatterns = new ArrayList<>();
            List<String> allInvolvedIds = new ArrayList<>();
            allInvolvedIds.add(emailNode.getNodeId());

            for (GraphNode spreadsheet : attachedSpreadsheets) {
                FlowPattern childPattern = buildSpreadsheetSubProcess(spreadsheet);
                if (childPattern != null) {
                    childPattern.setParentFlowType("EMAIL_SPREADSHEET_PROCESS");
                    childPatterns.add(childPattern);
                    allInvolvedIds.addAll(childPattern.getInvolvedNodeIds());
                }
            }

            // Build the parent email-driven process
            List<FlowPattern.FlowStep> parentSteps = new ArrayList<>();

            // Step 1: Receive email
            String senderName = senders.isEmpty() ? "sender" : senders.get(0).getTitle();
            String recipientName = recipients.isEmpty() ? "recipient" : recipients.get(0).getTitle();
            parentSteps.add(FlowPattern.FlowStep.builder()
                    .description("Receive email: " + emailNode.getTitle())
                    .actor(senderName)
                    .action("SEND")
                    .target(recipientName)
                    .nodeId(emailNode.getNodeId())
                    .build());

            // Step 2: Open and review instructions in email
            parentSteps.add(FlowPattern.FlowStep.builder()
                    .description("Review instructions in email")
                    .actor(recipientName)
                    .action("INPUT")
                    .target(emailNode.getTitle())
                    .nodeId(emailNode.getNodeId())
                    .build());

            // Step 3: Execute sub-process(es) for each spreadsheet
            for (int i = 0; i < attachedSpreadsheets.size(); i++) {
                GraphNode ss = attachedSpreadsheets.get(i);
                parentSteps.add(FlowPattern.FlowStep.builder()
                        .description("Execute spreadsheet procedure: " + ss.getTitle())
                        .actor(recipientName)
                        .action("COMPUTE")
                        .target(ss.getTitle())
                        .nodeId(ss.getNodeId())
                        .build());
            }

            // Step 4: Reply/confirm completion
            parentSteps.add(FlowPattern.FlowStep.builder()
                    .description("Confirm completion and reply")
                    .actor(recipientName)
                    .action("SEND")
                    .target(senderName)
                    .build());

            senders.forEach(s -> allInvolvedIds.add(s.getNodeId()));
            recipients.forEach(r -> allInvolvedIds.add(r.getNodeId()));
            attachedSpreadsheets.forEach(s -> allInvolvedIds.add(s.getNodeId()));

            FlowPattern parentPattern = FlowPattern.builder()
                    .type("EMAIL_SPREADSHEET_PROCESS")
                    .description("Email-driven spreadsheet process: " + emailNode.getTitle()
                            + " (" + attachedSpreadsheets.size() + " spreadsheet(s) attached)")
                    .occurrenceCount(1)
                    .confidence(Math.min(0.7 + (childPatterns.size() * 0.05)
                            + (attachedSpreadsheets.size() * 0.05), 0.95))
                    .steps(parentSteps)
                    .involvedNodeIds(allInvolvedIds.stream().distinct().collect(Collectors.toList()))
                    .childPatterns(childPatterns)
                    .build();
            patterns.add(parentPattern);
        }

        return patterns;
    }

    /**
     * Finds documents connected by explicit REFERENCES_DOCUMENT, DESCRIBES_PROCEDURE,
     * or INSTRUCTS_USAGE edges and composes hierarchical flows.
     */
    private List<FlowPattern> analyzeDocumentReferenceFlows(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        List<GraphNode> documentNodes;
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            documentNodes = new ArrayList<>();
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (node.getNodeType() == NodeLevel.DOCUMENT || node.getNodeType() == NodeLevel.ENTITY) {
                        documentNodes.add(node);
                    }
                });
            }
        } else {
            documentNodes = fetchAllNodesPaginated(NodeLevel.DOCUMENT);
        }

        // Track which documents are already part of a composite pattern to avoid duplicates
        Set<String> processedDocIds = new HashSet<>();

        for (GraphNode doc : documentNodes) {
            if (processedDocIds.contains(doc.getNodeId())) continue;

            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(doc.getNodeId());
            List<GraphNode> referencedDocs = new ArrayList<>();
            List<String> referenceTypes = new ArrayList<>();

            for (GraphEdge edge : edges) {
                String label = edgeLabel(edge);
                if (label == null) continue;

                if (label.contains("REFERENCES_DOCUMENT") || label.contains("DESCRIBES_PROCEDURE")
                        || label.contains("INSTRUCTS_USAGE") || label.contains("SUBPROCESS_OF")) {
                    GraphNode referenced = otherNode(edge, doc);
                    if (referenced != null) {
                        referencedDocs.add(referenced);
                        referenceTypes.add(label);
                    }
                }
            }

            if (referencedDocs.isEmpty()) continue;

            // Build child patterns for each referenced document
            List<FlowPattern> childPatterns = new ArrayList<>();
            List<String> allInvolvedIds = new ArrayList<>();
            allInvolvedIds.add(doc.getNodeId());

            for (GraphNode refDoc : referencedDocs) {
                processedDocIds.add(refDoc.getNodeId());

                FlowPattern child;
                if (isSpreadsheetNode(refDoc)) {
                    child = buildSpreadsheetSubProcess(refDoc);
                } else {
                    child = buildDocumentSubProcess(refDoc);
                }
                if (child != null) {
                    child.setParentFlowType("CROSS_DOCUMENT_PROCESS");
                    childPatterns.add(child);
                    allInvolvedIds.addAll(child.getInvolvedNodeIds());
                }
            }

            processedDocIds.add(doc.getNodeId());

            List<FlowPattern.FlowStep> parentSteps = new ArrayList<>();
            parentSteps.add(FlowPattern.FlowStep.builder()
                    .description("Review parent document: " + doc.getTitle())
                    .actor("user")
                    .action("INPUT")
                    .target(doc.getTitle())
                    .nodeId(doc.getNodeId())
                    .build());

            for (GraphNode refDoc : referencedDocs) {
                String action = isSpreadsheetNode(refDoc) ? "COMPUTE" : "TRANSFORM";
                parentSteps.add(FlowPattern.FlowStep.builder()
                        .description("Execute referenced procedure: " + refDoc.getTitle())
                        .actor("user")
                        .action(action)
                        .target(refDoc.getTitle())
                        .nodeId(refDoc.getNodeId())
                        .build());
            }

            parentSteps.add(FlowPattern.FlowStep.builder()
                    .description("Review and approve results")
                    .actor("reviewer")
                    .action("APPROVE")
                    .target(doc.getTitle() + " outputs")
                    .build());

            referencedDocs.forEach(r -> allInvolvedIds.add(r.getNodeId()));

            FlowPattern pattern = FlowPattern.builder()
                    .type("CROSS_DOCUMENT_PROCESS")
                    .description("Cross-document process: " + doc.getTitle()
                            + " references " + referencedDocs.size() + " document(s)")
                    .occurrenceCount(1)
                    .confidence(Math.min(0.65 + (referencedDocs.size() * 0.08), 0.9))
                    .steps(parentSteps)
                    .involvedNodeIds(allInvolvedIds.stream().distinct().collect(Collectors.toList()))
                    .childPatterns(childPatterns)
                    .build();
            patterns.add(pattern);
        }

        return patterns;
    }

    /**
     * Detects implicit references: an email or document whose title/description
     * mentions the name of a spreadsheet or document that exists in the graph.
     * For example, an email titled "Please update Q1 Budget.xlsx" implicitly
     * references the "Q1 Budget.xlsx" document node.
     */
    private List<FlowPattern> analyzeImplicitDocumentReferences(List<String> graphNodeIds) {
        List<FlowPattern> patterns = new ArrayList<>();

        // Get emails/messages
        List<GraphNode> emailNodes = findNodesByEntityType(graphNodeIds, "EMAIL_MESSAGE", "GMAIL_MESSAGE");
        if (emailNodes.isEmpty()) return patterns;

        // Get all spreadsheet/document nodes to match against
        List<GraphNode> spreadsheetNodes;
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            spreadsheetNodes = new ArrayList<>();
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (isSpreadsheetNode(node)) spreadsheetNodes.add(node);
                    List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                    children.stream().filter(this::isSpreadsheetNode).forEach(spreadsheetNodes::add);
                });
            }
        } else {
            spreadsheetNodes = new ArrayList<>();
            List<GraphNode> tableNodes = knowledgeGraphService.searchNodes("", NodeLevel.TABLE, 500);
            tableNodes.stream().filter(this::isSpreadsheetNode).forEach(spreadsheetNodes::add);
            List<GraphNode> docNodes = fetchAllNodesPaginated(NodeLevel.DOCUMENT);
            docNodes.stream().filter(this::isSpreadsheetNode).forEach(spreadsheetNodes::add);
        }

        if (spreadsheetNodes.isEmpty()) return patterns;

        // For each email, check if its title or description mentions a spreadsheet name
        for (GraphNode email : emailNodes) {
            String emailText = (email.getTitle() != null ? email.getTitle() : "")
                    + " " + (email.getDescription() != null ? email.getDescription() : "")
                    + " " + (email.getContentPreview() != null ? email.getContentPreview() : "");
            emailText = emailText.toLowerCase();

            List<GraphNode> matchedSpreadsheets = new ArrayList<>();
            for (GraphNode ss : spreadsheetNodes) {
                if (ss.getTitle() != null && ss.getTitle().length() >= 3
                        && emailText.contains(ss.getTitle().toLowerCase())) {
                    matchedSpreadsheets.add(ss);
                }
            }

            if (matchedSpreadsheets.isEmpty()) continue;

            // Check that this isn't already captured by explicit attachment edges
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(email.getNodeId());
            Set<String> alreadyAttached = new HashSet<>();
            for (GraphEdge edge : edges) {
                String label = edgeLabel(edge);
                if (label != null && (label.contains("HAS_ATTACHMENT") || label.contains("REFERENCES_DOCUMENT"))) {
                    GraphNode other = otherNode(edge, email);
                    if (other != null) alreadyAttached.add(other.getNodeId());
                }
            }
            matchedSpreadsheets.removeIf(ss -> alreadyAttached.contains(ss.getNodeId()));
            if (matchedSpreadsheets.isEmpty()) continue;

            // Build hierarchical pattern
            List<FlowPattern> childPatterns = new ArrayList<>();
            List<String> allInvolvedIds = new ArrayList<>();
            allInvolvedIds.add(email.getNodeId());

            for (GraphNode ss : matchedSpreadsheets) {
                FlowPattern childPattern = buildSpreadsheetSubProcess(ss);
                if (childPattern != null) {
                    childPattern.setParentFlowType("IMPLICIT_REFERENCE_PROCESS");
                    childPatterns.add(childPattern);
                    allInvolvedIds.addAll(childPattern.getInvolvedNodeIds());
                }
                allInvolvedIds.add(ss.getNodeId());
            }

            List<FlowPattern.FlowStep> steps = new ArrayList<>();
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Read instructions from: " + email.getTitle())
                    .actor("recipient")
                    .action("INPUT")
                    .target(email.getTitle())
                    .nodeId(email.getNodeId())
                    .build());
            for (GraphNode ss : matchedSpreadsheets) {
                steps.add(FlowPattern.FlowStep.builder()
                        .description("Execute referenced spreadsheet: " + ss.getTitle())
                        .actor("recipient")
                        .action("COMPUTE")
                        .target(ss.getTitle())
                        .nodeId(ss.getNodeId())
                        .build());
            }
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Confirm completion")
                    .actor("recipient")
                    .action("APPROVE")
                    .target(email.getTitle() + " outputs")
                    .build());

            FlowPattern pattern = FlowPattern.builder()
                    .type("IMPLICIT_REFERENCE_PROCESS")
                    .description("Email implicitly references spreadsheet(s): " + email.getTitle()
                            + " mentions " + matchedSpreadsheets.stream()
                            .map(GraphNode::getTitle).collect(Collectors.joining(", ")))
                    .occurrenceCount(1)
                    .confidence(Math.min(0.5 + (matchedSpreadsheets.size() * 0.1), 0.8))
                    .steps(steps)
                    .involvedNodeIds(allInvolvedIds.stream().distinct().collect(Collectors.toList()))
                    .childPatterns(childPatterns)
                    .build();
            patterns.add(pattern);
        }

        return patterns;
    }

    // ── Sub-process builders ────────────────────────────────────────────────

    /**
     * Builds a spreadsheet sub-process FlowPattern from a spreadsheet graph node.
     * Inspects children for formula cells and input cells to create
     * INPUT → COMPUTE → APPROVE steps.
     */
    private FlowPattern buildSpreadsheetSubProcess(GraphNode spreadsheetNode) {
        List<GraphNode> children = knowledgeGraphService.getChildren(spreadsheetNode.getNodeId());
        List<GraphNode> formulaCells = children.stream()
                .filter(this::isFormulaCellNode).collect(Collectors.toList());
        List<GraphNode> inputCells = children.stream()
                .filter(c -> !isFormulaCellNode(c)).collect(Collectors.toList());

        List<FlowPattern.FlowStep> steps = new ArrayList<>();
        if (!inputCells.isEmpty()) {
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Provide input values for " + inputCells.size() + " cells")
                    .actor("user")
                    .action("INPUT")
                    .target(spreadsheetNode.getTitle())
                    .nodeId(spreadsheetNode.getNodeId())
                    .build());
        }
        if (!formulaCells.isEmpty()) {
            steps.add(FlowPattern.FlowStep.builder()
                    .description("Execute " + formulaCells.size() + " formula(s)")
                    .actor("system")
                    .action("COMPUTE")
                    .target(spreadsheetNode.getTitle())
                    .nodeId(spreadsheetNode.getNodeId())
                    .build());
        }
        steps.add(FlowPattern.FlowStep.builder()
                .description("Review computed results")
                .actor("user")
                .action("APPROVE")
                .target(spreadsheetNode.getTitle())
                .build());

        if (steps.isEmpty()) return null;

        List<String> involvedIds = new ArrayList<>();
        involvedIds.add(spreadsheetNode.getNodeId());
        children.forEach(c -> involvedIds.add(c.getNodeId()));

        return FlowPattern.builder()
                .type("SPREADSHEET_SUBPROCESS")
                .description("Spreadsheet computation: " + spreadsheetNode.getTitle()
                        + " (" + formulaCells.size() + " formulas, " + inputCells.size() + " inputs)")
                .occurrenceCount(1)
                .confidence(0.7 + Math.min(formulaCells.size() * 0.05, 0.2))
                .steps(steps)
                .involvedNodeIds(involvedIds)
                .build();
    }

    /**
     * Builds a generic document sub-process FlowPattern from a document graph node.
     */
    private FlowPattern buildDocumentSubProcess(GraphNode documentNode) {
        List<FlowPattern.FlowStep> steps = new ArrayList<>();
        steps.add(FlowPattern.FlowStep.builder()
                .description("Review document: " + documentNode.getTitle())
                .actor("user")
                .action("INPUT")
                .target(documentNode.getTitle())
                .nodeId(documentNode.getNodeId())
                .build());
        steps.add(FlowPattern.FlowStep.builder()
                .description("Process document content")
                .actor("user")
                .action("TRANSFORM")
                .target(documentNode.getTitle())
                .nodeId(documentNode.getNodeId())
                .build());

        return FlowPattern.builder()
                .type("DOCUMENT_SUBPROCESS")
                .description("Document procedure: " + documentNode.getTitle())
                .occurrenceCount(1)
                .confidence(0.6)
                .steps(steps)
                .involvedNodeIds(List.of(documentNode.getNodeId()))
                .build();
    }

    // ── Node lookup helpers ─────────────────────────────────────────────────

    /**
     * Finds nodes matching the given entity_type values within the specified scope.
     */
    private List<GraphNode> findNodesByEntityType(List<String> graphNodeIds, String... entityTypes) {
        List<GraphNode> result = new ArrayList<>();
        Set<String> types = Set.of(entityTypes);

        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (hasEntityType(node, types)) result.add(node);
                    List<GraphNode> connected = knowledgeGraphService.getConnectedNodes(nodeId, 1);
                    connected.stream().filter(n -> hasEntityType(n, types)).forEach(result::add);
                });
            }
        } else {
            List<GraphNode> allEntities = fetchAllNodesPaginated(NodeLevel.ENTITY);
            allEntities.stream().filter(n -> hasEntityType(n, types)).forEach(result::add);
        }
        return result;
    }

    private boolean hasEntityType(GraphNode node, Set<String> types) {
        String meta = node.getMetadataJson();
        if (meta == null) return false;
        for (String type : types) {
            if (meta.contains("\"entity_type\":\"" + type + "\"")) return true;
        }
        return false;
    }

    private boolean isSpreadsheetNode(GraphNode node) {
        if (node.getNodeType() == NodeLevel.TABLE && isSheetNode(node)) return true;
        String meta = node.getMetadataJson();
        if (meta == null) return false;
        String title = node.getTitle() != null ? node.getTitle().toLowerCase() : "";
        return meta.contains("\"entity_type\":\"SPREADSHEET\"")
                || meta.contains("\"entity_type\":\"SPREADSHEET_SHEET\"")
                || meta.contains("\"entity_subtype\":\"sheet\"")
                || title.endsWith(".xlsx") || title.endsWith(".xls") || title.endsWith(".csv");
    }

    private String edgeLabel(GraphEdge edge) {
        String label = edge.getLabel();
        if (label != null) return label;
        return edge.getDescription();
    }

    private GraphNode otherNode(GraphEdge edge, GraphNode self) {
        if (edge.getSourceNode() != null
                && edge.getSourceNode().getNodeId() != null
                && !edge.getSourceNode().getNodeId().equals(self.getNodeId())) {
            return edge.getSourceNode();
        }
        if (edge.getTargetNode() != null
                && edge.getTargetNode().getNodeId() != null
                && !edge.getTargetNode().getNodeId().equals(self.getNodeId())) {
            return edge.getTargetNode();
        }
        return edge.getTargetNode();
    }

    // ── Hierarchical suggestion builder ─────────────────────────────────────

    /**
     * Converts a hierarchical FlowPattern (with childPatterns) into a
     * ProcessSuggestion with childSuggestions.
     */
    private ProcessSuggestion flowToHierarchicalSuggestion(FlowPattern flow) {
        // Build the parent suggestion from the parent flow's own steps
        ProcessSuggestion parent = flowToSuggestion(flow);

        // Convert each child pattern into a child suggestion
        if (flow.getChildPatterns() != null && !flow.getChildPatterns().isEmpty()) {
            List<ProcessSuggestion> children = new ArrayList<>();
            for (FlowPattern childFlow : flow.getChildPatterns()) {
                ProcessSuggestion child = flowToSuggestion(childFlow);
                child.setParentSuggestionId(parent.getName());
                children.add(child);
            }
            parent.setChildSuggestions(children);
        }

        return parent;
    }

    @Override
    public ProcessDefinition acceptSuggestion(ProcessSuggestion suggestion) {
        List<ProcessPhase> phases = new ArrayList<>();
        int phaseOrder = 1;

        for (ProcessSuggestion.SuggestedPhase sp : suggestion.getPhases()) {
            List<ProcessStep> steps = new ArrayList<>();
            int stepOrder = 1;

            for (ProcessSuggestion.SuggestedStep ss : sp.getSteps()) {
                StepType stepType;
                try {
                    stepType = StepType.valueOf(ss.getStepType());
                } catch (IllegalArgumentException e) {
                    stepType = StepType.AUTO;
                }

                ProcessStep step = ProcessStep.builder()
                        .id(phaseOrder + "." + stepOrder)
                        .name(ss.getName())
                        .description(ss.getDescription())
                        .stepType(stepType)
                        .graphNodeIds(ss.getGraphNodeIds())
                        .toolName(ss.getToolName())
                        .confidence(suggestion.getConfidence())
                        .build();
                steps.add(step);
                stepOrder++;
            }

            ProcessPhase phase = ProcessPhase.builder()
                    .id("phase-" + phaseOrder)
                    .name(sp.getName())
                    .order(phaseOrder)
                    .steps(steps)
                    .build();
            phases.add(phase);
            phaseOrder++;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("discoverySource", suggestion.getDiscoverySource());
        meta.put("discoveryConfidence", suggestion.getConfidence());
        meta.put("sourceGraphNodeIds", suggestion.getSourceGraphNodeIds());
        meta.put("evidence", suggestion.getEvidence());

        String definitionId = "discovered-" + UUID.randomUUID().toString().substring(0, 8);

        // Process child suggestions into child process definitions
        List<String> childProcessIds = new ArrayList<>();
        if (suggestion.getChildSuggestions() != null && !suggestion.getChildSuggestions().isEmpty()) {
            for (ProcessSuggestion childSuggestion : suggestion.getChildSuggestions()) {
                ProcessDefinition childDef = acceptSuggestion(childSuggestion);
                childDef.setParentProcessId(definitionId);
                childProcessIds.add(childDef.getId());

                // Re-persist the child with parent link if engine is available
                if (processEngineService != null) {
                    try {
                        processEngineService.createProcess(childDef);
                    } catch (Exception e) {
                        log.warn("Failed to update child process '{}' with parent link: {}",
                                childDef.getName(), e.getMessage());
                    }
                }
            }
        }

        ProcessDefinition definition = ProcessDefinition.builder()
                .id(definitionId)
                .name(suggestion.getName())
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(phases)
                .metadata(meta)
                .factSheetId(suggestion.getFactSheetId())
                .parentProcessId(suggestion.getParentSuggestionId())
                .childProcessIds(childProcessIds.isEmpty() ? null : childProcessIds)
                .build();

        // Persist the definition via the process engine if available
        if (processEngineService != null) {
            try {
                definition = processEngineService.createProcess(definition);
                log.info("Persisted discovered process '{}' (id={})", definition.getName(), definition.getId());
            } catch (Exception e) {
                log.warn("Failed to persist discovered process '{}': {}", definition.getName(), e.getMessage());
            }
        }

        return definition;
    }

    private ProcessSuggestion flowToSuggestion(FlowPattern flow) {
        List<ProcessSuggestion.SuggestedStep> steps = new ArrayList<>();

        for (FlowPattern.FlowStep fs : flow.getSteps()) {
            String stepType = switch (fs.getAction()) {
                case "SEND", "RECEIVE" -> "AUTO";
                case "INPUT" -> "HUMAN";
                case "COMPUTE" -> "EXCEL_COMPUTE";
                case "APPROVE" -> "APPROVE";
                case "TRANSFORM" -> "SCRIPT";
                default -> "AUTO";
            };

            steps.add(ProcessSuggestion.SuggestedStep.builder()
                    .name(fs.getDescription())
                    .stepType(stepType)
                    .description(fs.getDescription())
                    .graphNodeIds(fs.getNodeId() != null ? List.of(fs.getNodeId()) : List.of())
                    .suggestedAssignee(fs.getActor())
                    .build());
        }

        ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                .name(flow.getType().toLowerCase().replace("_", " "))
                .description(flow.getDescription())
                .steps(steps)
                .build();

        return ProcessSuggestion.builder()
                .factSheetId(flow.getFactSheetId())
                .name("Suggested: " + flow.getDescription())
                .description(flow.getDescription())
                .discoverySource(flow.getType())
                .confidence(flow.getConfidence())
                .phases(List.of(phase))
                .sourceGraphNodeIds(flow.getInvolvedNodeIds())
                .evidence(List.of(flow.getOccurrenceCount() + " occurrences observed"))
                .build();
    }

    private List<FlowPattern.FlowStep> buildEmailFlowSteps(List<GraphEdge> edges) {
        List<FlowPattern.FlowStep> steps = new ArrayList<>();
        for (GraphEdge edge : edges) {
            steps.add(FlowPattern.FlowStep.builder()
                    .description(edge.getDescription())
                    .actor(edge.getSourceNode().getTitle())
                    .action(edge.getLabel() != null ? edge.getLabel() : "EMAIL")
                    .target(edge.getTargetNode().getTitle())
                    .nodeId(edge.getSourceNode().getNodeId())
                    .build());
        }
        return steps;
    }

    private boolean isPersonNode(GraphNode node) {
        if (node.getNodeType() != NodeLevel.ENTITY) return false;
        String meta = node.getMetadataJson();
        if (meta == null) return false;
        // Ingest writes entity_type:"PERSON" (uppercase), check both conventions
        return meta.contains("\"entity_type\":\"PERSON\"")
                || meta.contains("\"entity_subtype\":\"person\"");
    }

    private boolean isSheetNode(GraphNode node) {
        String meta = node.getMetadataJson();
        // Match both Excel sheet nodes and generic table nodes (from HTML, DOCX, PDF tables)
        return meta != null && (meta.contains("\"entity_subtype\":\"sheet\"")
                || meta.contains("\"entity_subtype\":\"table\""));
    }

    private boolean isFormulaCellNode(GraphNode node) {
        String meta = node.getMetadataJson();
        return meta != null && (meta.contains("\"entity_subtype\":\"formula_cell\"")
                || meta.contains("\"cellType\":\"FORMULA\""));
    }

    /**
     * Fetches all nodes of a given type. Uses a large limit to avoid
     * silently truncating results in large knowledge graphs.
     * The searchNodes API doesn't support offset-based pagination,
     * so we request up to 50,000 nodes in a single call.
     */
    private List<GraphNode> fetchAllNodesPaginated(NodeLevel nodeLevel) {
        return new ArrayList<>(knowledgeGraphService.searchNodes("", nodeLevel, 50_000));
    }

    /**
     * Fetches all nodes of a given type scoped to a fact sheet.
     */
    private List<GraphNode> fetchNodesForFactSheet(Long factSheetId, NodeLevel nodeLevel) {
        if (graphNodeRepository == null) {
            log.warn("GraphNodeRepository not available — falling back to global query for nodeLevel={}", nodeLevel);
            return fetchAllNodesPaginated(nodeLevel);
        }
        return new ArrayList<>(graphNodeRepository.findByFactSheetIdAndNodeType(factSheetId, nodeLevel));
    }

    /**
     * Collects all node IDs belonging to a fact sheet for use as graphNodeIds
     * in the existing pattern analysis methods.
     */
    private List<String> collectNodeIdsForFactSheet(Long factSheetId) {
        if (graphNodeRepository == null) {
            log.warn("GraphNodeRepository not available — cannot scope to factSheetId={}", factSheetId);
            return null;
        }
        List<GraphNode> nodes = graphNodeRepository.findByFactSheetId(factSheetId);
        return nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toList());
    }

    // ── Fact-Sheet-Scoped Discovery Implementation ────────────────────────

    @Override
    public List<ProcessSuggestion> discoverProcessesForFactSheet(Long factSheetId, Map<String, Object> options) {
        log.info("Running process discovery for factSheetId={}", factSheetId);
        List<String> nodeIds = collectNodeIdsForFactSheet(factSheetId);

        List<ProcessSuggestion> suggestions = new ArrayList<>();

        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }

        // Analyze email flows scoped to this fact sheet
        List<FlowPattern> emailFlows = analyzeEmailFlowsForFactSheet(factSheetId);
        for (FlowPattern flow : emailFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze Excel computation flows
        List<FlowPattern> excelFlows = analyzeExcelFlowsForFactSheet(factSheetId);
        for (FlowPattern flow : excelFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze document flows
        List<FlowPattern> documentFlows = analyzeDocumentFlowsForFactSheet(factSheetId);
        for (FlowPattern flow : documentFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToSuggestion(flow));
            }
        }

        // Analyze cross-document flows
        List<FlowPattern> crossDocFlows = analyzeCrossDocumentFlowsForFactSheet(factSheetId);
        for (FlowPattern flow : crossDocFlows) {
            if (flow.getConfidence() >= minConfidence) {
                suggestions.add(flowToHierarchicalSuggestion(flow));
            }
        }

        // LLM-based discovery scoped to this fact sheet
        boolean useLlm = true;
        if (options != null && Boolean.FALSE.equals(options.get("useLlm"))) {
            useLlm = false;
        }
        if (useLlm && llmProcessDiscoveryService != null && llmProcessDiscoveryService.isAvailable()) {
            try {
                List<ProcessSuggestion> llmSuggestions = llmProcessDiscoveryService
                        .discoverProcessesForFactSheet(factSheetId, options);
                suggestions.addAll(llmSuggestions);
            } catch (Exception e) {
                log.warn("LLM process discovery failed for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }

        // Stamp factSheetId on all suggestions
        for (ProcessSuggestion s : suggestions) {
            s.setFactSheetId(factSheetId);
        }

        suggestions.sort(Comparator.comparingDouble(ProcessSuggestion::getConfidence).reversed());
        log.info("Process discovery for factSheetId={} found {} suggestions", factSheetId, suggestions.size());
        return suggestions;
    }

    @Override
    public List<FlowPattern> analyzeEmailFlowsForFactSheet(Long factSheetId) {
        List<String> nodeIds = collectNodeIdsForFactSheet(factSheetId);
        List<FlowPattern> patterns = analyzeEmailFlows(nodeIds);
        patterns.forEach(p -> p.setFactSheetId(factSheetId));
        return patterns;
    }

    @Override
    public List<FlowPattern> analyzeExcelFlowsForFactSheet(Long factSheetId) {
        List<String> nodeIds = collectNodeIdsForFactSheet(factSheetId);
        List<FlowPattern> patterns = analyzeExcelFlows(nodeIds);
        patterns.forEach(p -> p.setFactSheetId(factSheetId));
        return patterns;
    }

    @Override
    public List<FlowPattern> analyzeDocumentFlowsForFactSheet(Long factSheetId) {
        List<String> nodeIds = collectNodeIdsForFactSheet(factSheetId);
        List<FlowPattern> patterns = analyzeDocumentFlows(nodeIds);
        patterns.forEach(p -> p.setFactSheetId(factSheetId));
        return patterns;
    }

    @Override
    public List<FlowPattern> analyzeCrossDocumentFlowsForFactSheet(Long factSheetId) {
        List<String> nodeIds = collectNodeIdsForFactSheet(factSheetId);
        List<FlowPattern> patterns = analyzeCrossDocumentFlows(nodeIds);
        patterns.forEach(p -> p.setFactSheetId(factSheetId));
        return patterns;
    }

    @Override
    public List<ProcessSuggestion> discoverProcessesWithLlmForFactSheet(Long factSheetId, Map<String, Object> options) {
        if (llmProcessDiscoveryService == null || !llmProcessDiscoveryService.isAvailable()) {
            log.warn("LLM process discovery not available — no LLM provider configured");
            return List.of();
        }
        List<ProcessSuggestion> suggestions = llmProcessDiscoveryService
                .discoverProcessesForFactSheet(factSheetId, options != null ? options : Map.of());
        suggestions.forEach(s -> s.setFactSheetId(factSheetId));
        return suggestions;
    }
}
