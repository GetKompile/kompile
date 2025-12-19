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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.ConceptExtractor.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Implementation of FactSheetGraphService for building knowledge graphs from indexed documents.
 */
@Service
@Slf4j
public class FactSheetGraphServiceImpl implements FactSheetGraphService {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final EntityMentionRepository entityMentionRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final ConceptExtractor conceptExtractor;
    private final SourceLinkingService sourceLinkingService;

    private final Map<String, GraphBuildStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<GraphBuildStatus>> runningJobs = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();

    @Autowired
    public FactSheetGraphServiceImpl(
            GraphNodeRepository nodeRepository,
            GraphEdgeRepository edgeRepository,
            EntityMentionRepository entityMentionRepository,
            KnowledgeGraphService knowledgeGraphService,
            ConceptExtractor conceptExtractor,
            SourceLinkingService sourceLinkingService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.entityMentionRepository = entityMentionRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.conceptExtractor = conceptExtractor;
        this.sourceLinkingService = sourceLinkingService;
    }

    @Override
    public GraphBuildStatus buildGraphFromIndex(Long factSheetId, GraphBuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        GraphBuildStatus status = new GraphBuildStatus(
            jobId, "FactSheet-" + factSheetId, factSheetId,
            "PENDING", 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null, Map.of()
        );
        jobStatuses.put(jobId, status);

        if (config.asyncProcessing()) {
            CompletableFuture.runAsync(() -> executeBuild(jobId, factSheetId, config));
            return status;
        } else {
            return executeBuild(jobId, factSheetId, config);
        }
    }

    @Override
    @Async
    public CompletableFuture<GraphBuildStatus> buildGraphFromIndexAsync(Long factSheetId, GraphBuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        GraphBuildStatus initialStatus = new GraphBuildStatus(
            jobId, "FactSheet-" + factSheetId, factSheetId,
            "PENDING", 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null, Map.of()
        );
        jobStatuses.put(jobId, initialStatus);

        CompletableFuture<GraphBuildStatus> future = CompletableFuture.supplyAsync(
            () -> executeBuild(jobId, factSheetId, config)
        );
        runningJobs.put(jobId, future);

        return future;
    }

    @Override
    public GraphBuildStatus getBuildStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    @Override
    public boolean cancelBuild(String jobId) {
        if (runningJobs.containsKey(jobId)) {
            cancelledJobs.add(jobId);
            CompletableFuture<GraphBuildStatus> future = runningJobs.get(jobId);
            if (future != null) {
                future.cancel(true);
            }
            return true;
        }
        return false;
    }

    @Override
    public GraphVisualizationData getVisualizationData(Long factSheetId, int maxNodes, int maxEdges) {
        List<GraphNode> nodes;
        if (maxNodes > 0) {
            nodes = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SOURCE,
                PageRequest.of(0, maxNodes)).getContent();
            // Add more node types up to limit
            int remaining = maxNodes - nodes.size();
            if (remaining > 0) {
                nodes.addAll(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.DOCUMENT,
                    PageRequest.of(0, remaining)).getContent());
            }
            remaining = maxNodes - nodes.size();
            if (remaining > 0) {
                nodes.addAll(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY,
                    PageRequest.of(0, remaining)).getContent());
            }
        } else {
            nodes = nodeRepository.findByFactSheetId(factSheetId);
        }

        List<GraphEdge> edges;
        if (maxEdges > 0) {
            edges = edgeRepository.findByFactSheetId(factSheetId).stream()
                .limit(maxEdges)
                .collect(Collectors.toList());
        } else {
            edges = edgeRepository.findByFactSheetId(factSheetId);
        }

        // Convert to D3 format
        List<Map<String, Object>> nodeData = nodes.stream()
            .map(this::nodeToD3Format)
            .collect(Collectors.toList());

        Set<String> nodeIds = nodes.stream()
            .map(GraphNode::getNodeId)
            .collect(Collectors.toSet());

        // Only include edges where both endpoints are in our node set
        List<Map<String, Object>> edgeData = edges.stream()
            .filter(e -> nodeIds.contains(e.getSourceNode().getNodeId()) &&
                        nodeIds.contains(e.getTargetNode().getNodeId()))
            .map(this::edgeToD3Format)
            .collect(Collectors.toList());

        Map<String, Object> metadata = Map.of(
            "factSheetId", factSheetId,
            "totalNodes", nodes.size(),
            "totalEdges", edgeData.size(),
            "nodeTypes", countByNodeType(nodes),
            "edgeTypes", countByEdgeType(edges)
        );

        return new GraphVisualizationData(nodeData, edgeData, metadata);
    }

    @Override
    public Map<String, Object> getGraphStatistics(Long factSheetId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Node counts by type
        Map<String, Long> nodesByType = new HashMap<>();
        for (NodeLevel level : NodeLevel.values()) {
            long count = nodeRepository.countByFactSheetIdAndNodeType(factSheetId, level);
            if (count > 0) {
                nodesByType.put(level.name(), count);
            }
        }
        stats.put("nodesByType", nodesByType);

        // Edge counts by type
        Map<String, Long> edgesByType = new HashMap<>();
        for (EdgeType type : EdgeType.values()) {
            long count = edgeRepository.countByFactSheetIdAndEdgeType(factSheetId, type);
            if (count > 0) {
                edgesByType.put(type.name(), count);
            }
        }
        stats.put("edgesByType", edgesByType);

        // Entity statistics
        stats.put("distinctConcepts", entityMentionRepository.countDistinctEntitiesByFactSheet(factSheetId));

        // Top concepts
        List<Object[]> topConcepts = entityMentionRepository.findTopEntitiesByFactSheet(
            factSheetId, PageRequest.of(0, 10));
        stats.put("topConcepts", topConcepts.stream()
            .map(arr -> Map.of("name", arr[0], "count", arr[1]))
            .collect(Collectors.toList()));

        // Connectivity summary from source linking service
        stats.put("sourceConnectivity", sourceLinkingService.getConnectivitySummary(factSheetId));

        return stats;
    }

    @Override
    @Transactional
    public int clearGraph(Long factSheetId) {
        log.warn("Clearing graph for fact sheet {}", factSheetId);

        int mentionsDeleted = entityMentionRepository.deleteByFactSheetId(factSheetId);
        int edgesDeleted = edgeRepository.deleteByFactSheetId(factSheetId);
        int nodesDeleted = nodeRepository.deleteByFactSheetId(factSheetId);

        log.info("Cleared graph for fact sheet {}: {} nodes, {} edges, {} mentions",
            factSheetId, nodesDeleted, edgesDeleted, mentionsDeleted);

        return nodesDeleted + edgesDeleted + mentionsDeleted;
    }

    @Override
    public List<GraphBuildStatus> getRunningJobs() {
        return runningJobs.keySet().stream()
            .map(jobStatuses::get)
            .filter(Objects::nonNull)
            .filter(s -> "RUNNING".equals(s.status()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public int processIndexedDocument(Long factSheetId, String documentId, String content,
                                       Map<String, Object> metadata, String sourceId,
                                       GraphBuildConfig config) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        // Get or create source node
        final GraphNode sourceNode;
        if (sourceId != null) {
            sourceNode = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                sourceId, NodeLevel.SOURCE, factSheetId)
                .orElseGet(() -> createSourceNode(factSheetId, sourceId, metadata));
        } else {
            sourceNode = null;
        }

        // Get or create document node
        String title = metadata != null && metadata.containsKey("title") ?
            metadata.get("title").toString() : documentId;

        GraphNode docNode = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
            documentId, NodeLevel.DOCUMENT, factSheetId)
            .orElseGet(() -> {
                GraphNode node = GraphNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .nodeType(NodeLevel.DOCUMENT)
                    .externalId(documentId)
                    .title(title)
                    .description(content.length() > 500 ? content.substring(0, 500) + "..." : content)
                    .contentPreview(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                    .parent(sourceNode)
                    .sourceNode(sourceNode)
                    .factSheetId(factSheetId)
                    .build();
                return nodeRepository.save(node);
            });

        // Extract concepts
        ExtractionResult result = conceptExtractor.extractConcepts(content, config.extractionConfig());

        // Create entity nodes and mentions for each concept
        int conceptsAdded = 0;
        for (ExtractedConcept concept : result.concepts()) {
            if (concept.confidence() * 100 >= config.minConceptConfidence()) {
                // Find or create entity node
                GraphNode entityNode = findOrCreateEntityNode(factSheetId, concept);

                // Create entity mention
                EntityMention mention = entityMentionRepository
                    .findByNodeAndEntityNameAndFactSheet(docNode, concept.normalizedName(), factSheetId)
                    .orElseGet(() -> EntityMention.builder()
                        .node(docNode)
                        .entityName(concept.normalizedName())
                        .entityType(concept.category())
                        .mentionCount(0)
                        .confidence(concept.confidence())
                        .factSheetId(factSheetId)
                        .build());

                mention.setMentionCount(mention.getMentionCount() + concept.frequency());
                mention.setConfidence(Math.max(mention.getConfidence(), concept.confidence()));
                mention.setContextJson(concept.context());
                entityMentionRepository.save(mention);

                // Create edge from document to entity (if not exists)
                if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                        docNode.getNodeId(), entityNode.getNodeId(), factSheetId).isEmpty()) {
                    GraphEdge edge = GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .sourceNode(docNode)
                        .targetNode(entityNode)
                        .edgeType(EdgeType.SHARED_ENTITY)
                        .weight(concept.confidence())
                        .description("Document contains concept: " + concept.name())
                        .factSheetId(factSheetId)
                        .build();
                    edgeRepository.save(edge);
                }

                conceptsAdded++;
            }
        }

        // Create hierarchical edge if source exists
        if (sourceNode != null && config.includeHierarchicalEdges()) {
            if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                    sourceNode.getNodeId(), docNode.getNodeId(), factSheetId).isEmpty()) {
                GraphEdge edge = GraphEdge.builder()
                    .edgeId(UUID.randomUUID().toString())
                    .sourceNode(sourceNode)
                    .targetNode(docNode)
                    .edgeType(EdgeType.HIERARCHICAL)
                    .weight(1.0)
                    .description("Contains document")
                    .factSheetId(factSheetId)
                    .build();
                edgeRepository.save(edge);
            }
        }

        return conceptsAdded;
    }

    @Override
    @Transactional
    public int rebuildConceptEdges(Long factSheetId, int minSharedConcepts) {
        int edgesCreated = 0;

        // Find all document pairs with shared concepts
        List<Object[]> pairs = entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(
            factSheetId, minSharedConcepts);

        for (Object[] pair : pairs) {
            Long node1Id = (Long) pair[0];
            Long node2Id = (Long) pair[1];
            Long sharedCount = (Long) pair[2];

            GraphNode node1 = nodeRepository.findById(node1Id).orElse(null);
            GraphNode node2 = nodeRepository.findById(node2Id).orElse(null);

            if (node1 != null && node2 != null) {
                // Check if edge exists
                if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                        node1.getNodeId(), node2.getNodeId(), factSheetId).isEmpty()) {

                    double weight = Math.min(1.0, sharedCount / 10.0);

                    GraphEdge edge = GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .sourceNode(node1)
                        .targetNode(node2)
                        .edgeType(EdgeType.SHARED_ENTITY)
                        .weight(weight)
                        .description("Shares " + sharedCount + " concepts")
                        .bidirectional(true)
                        .factSheetId(factSheetId)
                        .build();
                    edgeRepository.save(edge);
                    edgesCreated++;
                }
            }
        }

        log.info("Created {} concept-based edges for fact sheet {}", edgesCreated, factSheetId);
        return edgesCreated;
    }

    @Override
    public List<Map<String, Object>> getTopConcepts(Long factSheetId, int limit) {
        List<Object[]> topConcepts = entityMentionRepository.findTopEntitiesByFactSheet(
            factSheetId, PageRequest.of(0, limit));

        return topConcepts.stream()
            .map(arr -> {
                Map<String, Object> concept = new HashMap<>();
                concept.put("name", arr[0]);
                concept.put("totalMentions", arr[1]);
                return concept;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> searchNodes(Long factSheetId, String query, int limit) {
        return nodeRepository.searchByFactSheetAndQuery(factSheetId, query, PageRequest.of(0, limit))
            .getContent().stream()
            .map(this::nodeToD3Format)
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getRelatedDocuments(Long factSheetId, String documentNodeId,
                                                          int minSharedConcepts, int limit) {
        GraphNode docNode = nodeRepository.findByNodeId(documentNodeId).orElse(null);
        if (docNode == null) {
            return List.of();
        }

        // Get concepts in this document
        List<String> docConcepts = entityMentionRepository.findEntitiesByNodeId(documentNodeId);
        if (docConcepts.isEmpty()) {
            return List.of();
        }

        // Find other documents that share concepts
        Map<String, Integer> relatedDocs = new HashMap<>();
        for (String concept : docConcepts) {
            List<EntityMention> mentions = entityMentionRepository.findByEntityNameAndFactSheet(
                concept, factSheetId);
            for (EntityMention mention : mentions) {
                if (!mention.getNode().getNodeId().equals(documentNodeId) &&
                    mention.getNode().getNodeType() == NodeLevel.DOCUMENT) {
                    relatedDocs.merge(mention.getNode().getNodeId(), 1, Integer::sum);
                }
            }
        }

        // Filter by minimum shared concepts and return top results
        return relatedDocs.entrySet().stream()
            .filter(e -> e.getValue() >= minSharedConcepts)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> {
                GraphNode node = nodeRepository.findByNodeId(e.getKey()).orElse(null);
                Map<String, Object> result = new HashMap<>();
                if (node != null) {
                    result.put("nodeId", node.getNodeId());
                    result.put("title", node.getTitle());
                    result.put("sharedConcepts", e.getValue());
                    result.put("nodeType", node.getNodeType().name());
                }
                return result;
            })
            .filter(m -> !m.isEmpty())
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphBuildStatus executeBuild(String jobId, Long factSheetId, GraphBuildConfig config) {
        long startTime = System.currentTimeMillis();
        int nodesCreated = 0;
        int edgesCreated = 0;
        int conceptsExtracted = 0;
        String errorMessage = null;

        try {
            updateStatus(jobId, factSheetId, "RUNNING", 0, 0, 0, 0, 0, null);

            // Get all source nodes for this fact sheet
            List<GraphNode> sources = nodeRepository.findSourcesByFactSheet(factSheetId);

            // If no sources exist yet, we need external data
            // This service works with existing graph nodes - actual document ingestion
            // should populate source and document nodes first

            int totalDocs = 0;
            int processedDocs = 0;

            for (GraphNode source : sources) {
                if (cancelledJobs.contains(jobId)) {
                    log.info("Build job {} cancelled", jobId);
                    break;
                }

                // Get documents for this source
                List<GraphNode> documents = nodeRepository.findBySourceIdAndType(
                    source.getNodeId(), NodeLevel.DOCUMENT);

                if (config.maxDocumentsToProcess() > 0) {
                    documents = documents.stream()
                        .limit(config.maxDocumentsToProcess())
                        .collect(Collectors.toList());
                }

                totalDocs += documents.size();

                for (GraphNode doc : documents) {
                    if (cancelledJobs.contains(jobId)) break;

                    // Get content from the document node
                    String content = doc.getDescription();
                    if (content == null && doc.getContentPreview() != null) {
                        content = doc.getContentPreview();
                    }

                    if (content != null && !content.isBlank()) {
                        int concepts = processIndexedDocument(
                            factSheetId,
                            doc.getExternalId(),
                            content,
                            Map.of("title", doc.getTitle()),
                            source.getNodeId(),
                            config
                        );
                        conceptsExtracted += concepts;
                    }

                    processedDocs++;
                    updateStatus(jobId, factSheetId, "RUNNING", totalDocs, processedDocs,
                        nodesCreated, edgesCreated, conceptsExtracted, null);
                }
            }

            // Create shared concept edges
            if (!cancelledJobs.contains(jobId) && config.computeConceptEdges()) {
                edgesCreated = rebuildConceptEdges(factSheetId, config.minSharedConceptsForEdge());
            }

            // Link sources together
            if (!cancelledJobs.contains(jobId) && config.computeSourceLinks()) {
                SourceLinkingService.LinkingResult linkResult =
                    sourceLinkingService.linkAllSources(factSheetId, config.linkingConfig());
                edgesCreated += linkResult.linksCreated();
            }

            // Count created nodes
            nodesCreated = nodeRepository.findByFactSheetId(factSheetId).size();

            String finalStatus = cancelledJobs.contains(jobId) ? "CANCELLED" : "COMPLETED";
            updateStatus(jobId, factSheetId, finalStatus, totalDocs, processedDocs,
                nodesCreated, edgesCreated, conceptsExtracted, null);

            log.info("Graph build {} completed: {} nodes, {} edges, {} concepts",
                jobId, nodesCreated, edgesCreated, conceptsExtracted);

        } catch (Exception e) {
            log.error("Graph build failed for fact sheet {}", factSheetId, e);
            errorMessage = e.getMessage();
            updateStatus(jobId, factSheetId, "FAILED", 0, 0,
                nodesCreated, edgesCreated, conceptsExtracted, errorMessage);
        } finally {
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }

        GraphBuildStatus finalStatus = jobStatuses.get(jobId);
        return new GraphBuildStatus(
            finalStatus.jobId(),
            finalStatus.factSheetName(),
            finalStatus.factSheetId(),
            finalStatus.status(),
            finalStatus.totalDocuments(),
            finalStatus.processedDocuments(),
            nodesCreated,
            edgesCreated,
            conceptsExtracted,
            errorMessage,
            startTime,
            System.currentTimeMillis(),
            getGraphStatistics(factSheetId)
        );
    }

    private void updateStatus(String jobId, Long factSheetId, String status,
                              int totalDocs, int processedDocs,
                              int nodes, int edges, int concepts, String error) {
        GraphBuildStatus current = jobStatuses.get(jobId);
        if (current != null) {
            jobStatuses.put(jobId, new GraphBuildStatus(
                jobId,
                current.factSheetName(),
                factSheetId,
                status,
                totalDocs,
                processedDocs,
                nodes,
                edges,
                concepts,
                error,
                current.startTime(),
                "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) ?
                    System.currentTimeMillis() : null,
                Map.of()
            ));
        }
    }

    private GraphNode createSourceNode(Long factSheetId, String sourceId, Map<String, Object> metadata) {
        String title = metadata != null && metadata.containsKey("title") ?
            metadata.get("title").toString() : sourceId;
        String sourceType = metadata != null && metadata.containsKey("sourceType") ?
            metadata.get("sourceType").toString() : "UNKNOWN";

        GraphNode node = GraphNode.builder()
            .nodeId(UUID.randomUUID().toString())
            .nodeType(NodeLevel.SOURCE)
            .externalId(sourceId)
            .title(title)
            .sourceType(sourceType)
            .factSheetId(factSheetId)
            .build();
        return nodeRepository.save(node);
    }

    @Transactional
    protected GraphNode findOrCreateEntityNode(Long factSheetId, ExtractedConcept concept) {
        return nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
            concept.normalizedName(), NodeLevel.ENTITY, factSheetId)
            .orElseGet(() -> {
                GraphNode entityNode = GraphNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .nodeType(NodeLevel.ENTITY)
                    .externalId(concept.normalizedName())
                    .title(concept.name())
                    .description("Concept type: " + concept.category())
                    .factSheetId(factSheetId)
                    .build();
                return nodeRepository.save(entityNode);
            });
    }

    private Map<String, Object> nodeToD3Format(GraphNode node) {
        Map<String, Object> d3Node = new HashMap<>();
        d3Node.put("id", node.getNodeId());
        d3Node.put("label", node.getTitle());
        d3Node.put("type", node.getNodeType().name());
        d3Node.put("externalId", node.getExternalId());
        d3Node.put("childCount", node.getChildCount());
        d3Node.put("edgeCount", node.getEdgeCount());
        if (node.getDescription() != null) {
            d3Node.put("description", node.getDescription());
        }
        if (node.getSourceType() != null) {
            d3Node.put("sourceType", node.getSourceType());
        }
        return d3Node;
    }

    private Map<String, Object> edgeToD3Format(GraphEdge edge) {
        Map<String, Object> d3Edge = new HashMap<>();
        d3Edge.put("id", edge.getEdgeId());
        d3Edge.put("source", edge.getSourceNode().getNodeId());
        d3Edge.put("target", edge.getTargetNode().getNodeId());
        d3Edge.put("type", edge.getEdgeType().name());
        d3Edge.put("weight", edge.getWeight());
        d3Edge.put("bidirectional", edge.getBidirectional());
        if (edge.getLabel() != null) {
            d3Edge.put("label", edge.getLabel());
        }
        if (edge.getDescription() != null) {
            d3Edge.put("description", edge.getDescription());
        }
        return d3Edge;
    }

    private Map<String, Long> countByNodeType(List<GraphNode> nodes) {
        return nodes.stream()
            .collect(Collectors.groupingBy(n -> n.getNodeType().name(), Collectors.counting()));
    }

    private Map<String, Long> countByEdgeType(List<GraphEdge> edges) {
        return edges.stream()
            .collect(Collectors.groupingBy(e -> e.getEdgeType().name(), Collectors.counting()));
    }
}
