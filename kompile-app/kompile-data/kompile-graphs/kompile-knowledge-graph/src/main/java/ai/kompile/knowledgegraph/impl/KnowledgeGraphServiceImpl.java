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

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JPA-based implementation of the KnowledgeGraphService interface.
 * MatrixKnowledgeGraphService is the primary implementation.
 */
@Service
@Slf4j
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {

    private GraphNodeRepository nodeRepository;
    private GraphEdgeRepository edgeRepository;
    private EntityMentionRepository entityMentionRepository;
    private ObjectMapper objectMapper;

    @Autowired
    public KnowledgeGraphServiceImpl(GraphNodeRepository nodeRepository,
                                      GraphEdgeRepository edgeRepository,
                                      EntityMentionRepository entityMentionRepository,
                                      ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.entityMentionRepository = entityMentionRepository;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected KnowledgeGraphServiceImpl() {}


    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                               String pathOrUrl, Map<String, Object> metadata) {
        return nodeRepository.findByExternalIdAndNodeType(externalId, NodeLevel.SOURCE)
            .map(existing -> {
                existing.setTitle(title);
                existing.setSourceType(sourceType);
                existing.setPathOrUrl(pathOrUrl);
                existing.setMetadataJson(serializeMetadata(metadata));
                return nodeRepository.save(existing);
            })
            .orElseGet(() -> {
                GraphNode node = GraphNode.builder()
                    .externalId(externalId)
                    .nodeType(NodeLevel.SOURCE)
                    .title(title)
                    .sourceType(sourceType)
                    .pathOrUrl(pathOrUrl)
                    .metadataJson(serializeMetadata(metadata))
                    .build();
                return nodeRepository.save(node);
            });
    }

    @Override
    @Transactional
    public GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                         Map<String, Object> metadata) {
        // Check if already exists
        Optional<GraphNode> existing = nodeRepository.findByExternalIdAndNodeType(docId, NodeLevel.DOCUMENT);
        if (existing.isPresent()) {
            GraphNode node = existing.get();
            node.setTitle(title);
            node.setMetadataJson(serializeMetadata(metadata));
            return nodeRepository.save(node);
        }

        GraphNode docNode = GraphNode.builder()
            .externalId(docId)
            .nodeType(NodeLevel.DOCUMENT)
            .title(title)
            .parent(sourceNode)
            .sourceNode(sourceNode)
            .metadataJson(serializeMetadata(metadata))
            .build();

        GraphNode saved = nodeRepository.save(docNode);

        // Update parent's child count
        sourceNode.incrementChildCount();
        nodeRepository.save(sourceNode);

        // Create hierarchical edge
        createEdge(sourceNode.getNodeId(), saved.getNodeId(),
                   EdgeType.HIERARCHICAL, 1.0, "Contains document: " + title);

        return saved;
    }

    @Override
    @Transactional
    public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                        int chunkIndex) {
        String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;

        // Check if already exists
        Optional<GraphNode> existing = nodeRepository.findByExternalIdAndNodeType(snippetId, NodeLevel.SNIPPET);
        if (existing.isPresent()) {
            GraphNode node = existing.get();
            node.setContentPreview(preview);
            return nodeRepository.save(node);
        }

        GraphNode snippetNode = GraphNode.builder()
            .externalId(snippetId)
            .nodeType(NodeLevel.SNIPPET)
            .title("Chunk " + (chunkIndex + 1))
            .contentPreview(preview)
            .parent(documentNode)
            .sourceNode(documentNode.getSourceNode())
            .build();

        GraphNode saved = nodeRepository.save(snippetNode);

        // Update parent's child count
        documentNode.incrementChildCount();
        nodeRepository.save(documentNode);

        // Create hierarchical edge
        createEdge(documentNode.getNodeId(), saved.getNodeId(),
                   EdgeType.HIERARCHICAL, 1.0, "Contains chunk " + (chunkIndex + 1));

        return saved;
    }

    @Override
    @Transactional
    public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                 String description, Map<String, Object> metadata) {
        GraphNode node = GraphNode.builder()
            .externalId(externalId)
            .nodeType(nodeType)
            .title(title)
            .description(description)
            .metadataJson(serializeMetadata(metadata))
            .build();
        return nodeRepository.save(node);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GraphNode> getNode(String nodeId) {
        return nodeRepository.findByNodeId(nodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType) {
        return nodeRepository.findByExternalIdAndNodeType(externalId, nodeType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getChildren(String parentNodeId) {
        return nodeRepository.findChildrenByParentId(parentNodeId);
    }

    @Override
    @Transactional
    public GraphNode updateNode(String nodeId, String title, String description,
                                 Map<String, Object> metadata) {
        GraphNode node = nodeRepository.findByNodeId(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        if (title != null) node.setTitle(title);
        if (description != null) node.setDescription(description);
        if (metadata != null) node.setMetadataJson(serializeMetadata(metadata));

        return nodeRepository.save(node);
    }

    @Override
    @Transactional
    public void deleteNode(String nodeId) {
        GraphNode node = nodeRepository.findByNodeId(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        // Delete all edges connected to this node
        edgeRepository.deleteAllEdgesForNode(node);

        // Update parent's child count
        if (node.getParent() != null) {
            node.getParent().decrementChildCount();
            nodeRepository.save(node.getParent());
        }

        // Delete the node (children will be deleted via cascade)
        nodeRepository.delete(node);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getAllSources() {
        return nodeRepository.findAllSources();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> searchNodes(String query, NodeLevel type, int limit) {
        PageRequest pageable = PageRequest.of(0, limit);
        if (type != null) {
            return nodeRepository.searchByTitleOrDescriptionAndType(query, type, pageable).getContent();
        }
        return nodeRepository.searchByTitleOrDescription(query, pageable).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesByType(NodeLevel type, int limit) {
        return nodeRepository.findByNodeType(type, PageRequest.of(0, limit)).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesByType(NodeLevel type) {
        return nodeRepository.findByNodeType(type);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesByIds(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyList();
        }
        return nodeRepository.findByNodeIdIn(nodeIds);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED NODE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return nodeRepository.findByFactSheetIdAndNodeType(factSheetId, type);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesInFactSheet(Long factSheetId) {
        return nodeRepository.findByFactSheetId(factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getSourcesInFactSheet(Long factSheetId) {
        return nodeRepository.findSourcesByFactSheet(factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type, Long factSheetId) {
        return nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(externalId, type, factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit) {
        return nodeRepository.searchByFactSheetAndQuery(factSheetId, query, PageRequest.of(0, limit)).getContent();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 Double weight, String description) {
        GraphNode source = nodeRepository.findByNodeId(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));
        GraphNode target = nodeRepository.findByNodeId(targetNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));

        // Check for existing edge
        Optional<GraphEdge> existing = edgeRepository.findEdgeBetweenNodes(sourceNodeId, targetNodeId);
        if (existing.isPresent()) {
            GraphEdge edge = existing.get();
            edge.setWeight(weight);
            edge.setDescription(description);
            if (edgeType == EdgeType.EMBEDDING_SIMILARITY || edgeType == EdgeType.SHARED_ENTITY) {
                edge.markComputed();
            }
            return edgeRepository.save(edge);
        }

        GraphEdge edge = GraphEdge.builder()
            .sourceNode(source)
            .targetNode(target)
            .edgeType(edgeType)
            .weight(weight)
            .description(description)
            .bidirectional(edgeType != EdgeType.HIERARCHICAL)
            .build();

        if (edgeType == EdgeType.EMBEDDING_SIMILARITY || edgeType == EdgeType.SHARED_ENTITY) {
            edge.markComputed();
        }

        GraphEdge saved = edgeRepository.save(edge);

        // Update edge counts
        source.incrementEdgeCount();
        target.incrementEdgeCount();
        nodeRepository.save(source);
        nodeRepository.save(target);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GraphEdge> getEdge(String edgeId) {
        return edgeRepository.findByEdgeId(edgeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> getEdgesForNode(String nodeId) {
        return edgeRepository.findAllEdgesForNodeId(nodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType) {
        GraphNode node = nodeRepository.findByNodeId(nodeId).orElse(null);
        if (node == null) return Collections.emptyList();
        return edgeRepository.findEdgesByNodeAndType(node, edgeType, PageRequest.of(0, 100));
    }

    @Override
    @Transactional
    public GraphEdge updateEdge(String edgeId, Double weight, String description) {
        GraphEdge edge = edgeRepository.findByEdgeId(edgeId)
            .orElseThrow(() -> new IllegalArgumentException("Edge not found: " + edgeId));

        if (weight != null) edge.setWeight(weight);
        if (description != null) edge.setDescription(description);

        return edgeRepository.save(edge);
    }

    @Override
    @Transactional
    public void deleteEdge(String edgeId) {
        GraphEdge edge = edgeRepository.findByEdgeId(edgeId)
            .orElseThrow(() -> new IllegalArgumentException("Edge not found: " + edgeId));

        // Update edge counts
        edge.getSourceNode().decrementEdgeCount();
        edge.getTargetNode().decrementEdgeCount();
        nodeRepository.save(edge.getSourceNode());
        nodeRepository.save(edge.getTargetNode());

        edgeRepository.delete(edge);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean edgeExists(String sourceNodeId, String targetNodeId) {
        return edgeRepository.findEdgeBetweenNodesBidirectional(sourceNodeId, targetNodeId).isPresent();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED EDGE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> getEdgesForNodeInFactSheet(String nodeId, Long factSheetId) {
        return edgeRepository.findAllEdgesForNodeIdInFactSheet(nodeId, factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean edgeExistsInFactSheet(String sourceNodeId, String targetNodeId, Long factSheetId) {
        return edgeRepository.findEdgeBetweenNodesInFactSheet(sourceNodeId, targetNodeId, factSheetId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> getEdgesInFactSheet(Long factSheetId) {
        return edgeRepository.findByFactSheetId(factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType) {
        return edgeRepository.findByFactSheetIdAndEdgeType(factSheetId, edgeType);
    }

    @Override
    @Transactional(readOnly = true)
    public GraphEdge findEdgeBetweenNodes(String sourceNodeId, String targetNodeId) {
        return edgeRepository.findEdgeBetweenNodes(sourceNodeId, targetNodeId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit) {
        if (query == null || query.isBlank()) {
            if (edgeType != null) {
                return edgeRepository.findByEdgeType(edgeType, PageRequest.of(0, limit)).getContent();
            }
            return edgeRepository.findAll(PageRequest.of(0, limit)).getContent();
        }

        String searchQuery = "%" + query.toLowerCase() + "%";

        // Search in edge descriptions and connected node titles
        List<GraphEdge> results = new ArrayList<>();

        // First, get edges with matching descriptions
        List<GraphEdge> byDescription = edgeRepository.searchByDescription(searchQuery, edgeType, PageRequest.of(0, limit));
        results.addAll(byDescription);

        // If we need more results, search by connected node titles
        if (results.size() < limit) {
            // Search nodes that match the query
            List<GraphNode> matchingNodes = nodeRepository.searchByTitleOrDescription(query, PageRequest.of(0, 50)).getContent();
            Set<String> matchingNodeIds = matchingNodes.stream()
                .map(GraphNode::getNodeId)
                .collect(Collectors.toSet());

            // Get edges connected to matching nodes
            for (GraphNode node : matchingNodes) {
                if (results.size() >= limit) break;
                List<GraphEdge> nodeEdges = edgeRepository.findAllEdgesForNode(node);
                for (GraphEdge edge : nodeEdges) {
                    if (results.size() >= limit) break;
                    if (edgeType == null || edge.getEdgeType() == edgeType) {
                        if (!results.contains(edge)) {
                            results.add(edge);
                        }
                    }
                }
            }
        }

        return results.stream().limit(limit).collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TRAVERSAL
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getConnectedNodes(String nodeId, int depth) {
        Set<String> visited = new HashSet<>();
        List<GraphNode> result = new ArrayList<>();

        GraphNode startNode = nodeRepository.findByNodeId(nodeId).orElse(null);
        if (startNode == null) return result;

        Queue<NodeWithDepth> queue = new LinkedList<>();
        queue.add(new NodeWithDepth(startNode, 0));
        visited.add(nodeId);

        while (!queue.isEmpty()) {
            NodeWithDepth current = queue.poll();
            GraphNode node = current.node;
            int currentDepth = current.depth;

            if (currentDepth > 0) {
                result.add(node);
            }

            if (currentDepth < depth) {
                List<GraphEdge> edges = edgeRepository.findOutgoingEdges(node);
                for (GraphEdge edge : edges) {
                    GraphNode neighbor = edge.getTargetNode().equals(node) ?
                        edge.getSourceNode() : edge.getTargetNode();
                    if (!visited.contains(neighbor.getNodeId())) {
                        visited.add(neighbor.getNodeId());
                        queue.add(new NodeWithDepth(neighbor, currentDepth + 1));
                    }
                }
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> findRelatedNodes(String nodeId, int maxResults) {
        // Get nodes connected via non-hierarchical edges, ordered by weight
        GraphNode node = nodeRepository.findByNodeId(nodeId).orElse(null);
        if (node == null) return Collections.emptyList();

        List<GraphEdge> edges = edgeRepository.findAllEdgesForNode(node);

        return edges.stream()
            .filter(e -> e.getEdgeType() != EdgeType.HIERARCHICAL)
            .sorted((a, b) -> Double.compare(b.getWeight(), a.getWeight()))
            .limit(maxResults)
            .map(e -> e.getSourceNode().equals(node) ? e.getTargetNode() : e.getSourceNode())
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Double> computeNodeRelevance(String queryNodeId, List<String> candidateNodeIds) {
        Map<String, Double> relevanceMap = new HashMap<>();

        for (String candidateId : candidateNodeIds) {
            // Check for direct edge
            Optional<GraphEdge> edge = edgeRepository.findEdgeBetweenNodesBidirectional(queryNodeId, candidateId);
            if (edge.isPresent()) {
                relevanceMap.put(candidateId, edge.get().getWeight());
            } else {
                // Default low relevance for non-connected nodes
                relevanceMap.put(candidateId, 0.1);
            }
        }

        return relevanceMap;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & VISUALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getGraphStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalNodes", nodeRepository.count());

        // Census EVERY NodeLevel (incl. TABLE, ATTACHMENT) into a single nodesByType map.
        // The index-browser / entity-browser read statistics.nodesByType[<TYPE>], so this is the
        // canonical, store-agnostic shape — never a hardcoded subset that silently drops a type.
        Map<String, Long> nodesByType = new LinkedHashMap<>();
        for (NodeLevel level : NodeLevel.values()) {
            nodesByType.put(level.name(), nodeRepository.countByNodeType(level));
        }
        stats.put("nodesByType", nodesByType);

        // Flat convenience keys (retained for existing consumers, e.g. unified-crawl graph summary).
        stats.put("sourceCount", nodesByType.getOrDefault(NodeLevel.SOURCE.name(), 0L));
        stats.put("documentCount", nodesByType.getOrDefault(NodeLevel.DOCUMENT.name(), 0L));
        stats.put("snippetCount", nodesByType.getOrDefault(NodeLevel.SNIPPET.name(), 0L));
        stats.put("entityCount", nodesByType.getOrDefault(NodeLevel.ENTITY.name(), 0L));
        stats.put("customCount", nodesByType.getOrDefault(NodeLevel.CUSTOM.name(), 0L));
        stats.put("tableCount", nodesByType.getOrDefault(NodeLevel.TABLE.name(), 0L));
        stats.put("attachmentCount", nodesByType.getOrDefault(NodeLevel.ATTACHMENT.name(), 0L));
        stats.put("totalEdges", edgeRepository.count());

        Map<String, Long> edgesByType = new LinkedHashMap<>();
        for (EdgeType type : EdgeType.values()) {
            long count = edgeRepository.countByEdgeType(type);
            edgesByType.put(type.name(), count);
            stats.put("edges_" + type.name().toLowerCase(), count);
        }
        stats.put("edgesByType", edgesByType);

        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes) {
        List<GraphNode> nodes;
        List<GraphEdge> edges;

        if (rootNodeId != null) {
            // Get nodes from root with depth limit
            nodes = new ArrayList<>();
            nodes.add(nodeRepository.findByNodeId(rootNodeId).orElseThrow());
            nodes.addAll(getConnectedNodes(rootNodeId, depth));

            // Limit nodes
            if (nodes.size() > maxNodes) {
                nodes = nodes.subList(0, maxNodes);
            }

            // Get edges between these nodes
            Set<String> nodeIds = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
            edges = edgeRepository.findAll().stream()
                .filter(e -> nodeIds.contains(e.getSourceNode().getNodeId()) &&
                             nodeIds.contains(e.getTargetNode().getNodeId()))
                .collect(Collectors.toList());
        } else {
            // Get all nodes with limit
            nodes = nodeRepository.findAll();
            if (nodes.size() > maxNodes) {
                // Prioritize sources and documents
                nodes = nodes.stream()
                    .sorted((a, b) -> {
                        int typeOrder = getTypeOrder(a.getNodeType()) - getTypeOrder(b.getNodeType());
                        if (typeOrder != 0) return typeOrder;
                        return Integer.compare(b.getChildCount(), a.getChildCount());
                    })
                    .limit(maxNodes)
                    .collect(Collectors.toList());
            }

            Set<String> nodeIds = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
            edges = edgeRepository.findAll().stream()
                .filter(e -> nodeIds.contains(e.getSourceNode().getNodeId()) &&
                             nodeIds.contains(e.getTargetNode().getNodeId()))
                .collect(Collectors.toList());
        }

        // Convert to D3-friendly format
        List<Map<String, Object>> nodeData = nodes.stream()
            .map(this::nodeToVisualizationMap)
            .collect(Collectors.toList());

        List<Map<String, Object>> edgeData = edges.stream()
            .map(this::edgeToVisualizationMap)
            .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edgeData);
        result.put("metadata", Map.of(
            "nodeCount", nodes.size(),
            "edgeCount", edges.size()
        ));

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    private int getTypeOrder(NodeLevel type) {
        return switch (type) {
            case SOURCE -> 0;
            case DOCUMENT -> 1;
            case TABLE -> 2;
            case ENTITY -> 3;
            case CUSTOM -> 4;
            case SNIPPET -> 5;
            case ATTACHMENT -> 6;
        };
    }

    private Map<String, Object> nodeToVisualizationMap(GraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.getNodeId());
        map.put("type", node.getNodeType().name());
        map.put("label", node.getTitle());
        map.put("title", node.getTitle());
        map.put("description", node.getDescription());
        map.put("sourceType", node.getSourceType());
        map.put("childCount", node.getChildCount());
        map.put("edgeCount", node.getEdgeCount());
        // Parsed metadata so the visualizer can render TABLE / structured nodes (not a JSON blob).
        map.put("metadata", node.getMetadata());
        if (node.getParent() != null) {
            map.put("parentId", node.getParent().getNodeId());
        }
        if (node.getSourceNode() != null) {
            map.put("sourceId", node.getSourceNode().getNodeId());
        }
        if (node.getOccurredAt() != null) {
            map.put("occurredAt", node.getOccurredAt().toString());
        }
        return map;
    }

    private Map<String, Object> edgeToVisualizationMap(GraphEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", edge.getEdgeId());
        map.put("source", edge.getSourceNode().getNodeId());
        map.put("target", edge.getTargetNode().getNodeId());
        map.put("type", edge.getEdgeType().name());
        map.put("weight", edge.getWeight());
        map.put("label", edge.getLabel());
        map.put("description", edge.getDescription());
        map.put("bidirectional", edge.getBidirectional());
        if (edge.getOccurredAt() != null) {
            map.put("occurredAt", edge.getOccurredAt().toString());
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEMPORAL QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getVisualizationDataInTimeRange(java.time.LocalDateTime from,
                                                                java.time.LocalDateTime to,
                                                                int maxNodes) {
        // Get edges in the time range
        List<GraphEdge> edges = edgeRepository.findByOccurredAtBetween(from, to,
                PageRequest.of(0, maxNodes * 3));

        // Collect nodes connected by those edges
        Set<String> nodeIds = new LinkedHashSet<>();
        for (GraphEdge e : edges) {
            nodeIds.add(e.getSourceNode().getNodeId());
            nodeIds.add(e.getTargetNode().getNodeId());
        }

        // Load the nodes
        List<GraphNode> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            if (nodes.size() >= maxNodes) break;
            nodeRepository.findByNodeId(nodeId).ifPresent(nodes::add);
        }

        // Filter edges to only include those between loaded nodes
        Set<String> loadedNodeIds = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        edges = edges.stream()
                .filter(e -> loadedNodeIds.contains(e.getSourceNode().getNodeId()) &&
                             loadedNodeIds.contains(e.getTargetNode().getNodeId()))
                .collect(Collectors.toList());

        // Convert to D3-friendly format
        List<Map<String, Object>> nodeData = nodes.stream()
                .map(this::nodeToVisualizationMap)
                .collect(Collectors.toList());

        List<Map<String, Object>> edgeData = edges.stream()
                .map(this::edgeToVisualizationMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edgeData);
        result.put("metadata", Map.of(
                "nodeCount", nodes.size(),
                "edgeCount", edges.size()
        ));
        result.put("temporalBounds", getTemporalBounds());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphEdge> searchEdgesByTimeRange(java.time.LocalDateTime from,
                                                   java.time.LocalDateTime to,
                                                   int limit) {
        return edgeRepository.findByOccurredAtBetween(from, to, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getTemporalBounds() {
        List<Object[]> bounds = edgeRepository.findTemporalBounds();
        if (bounds.isEmpty() || bounds.get(0)[0] == null) {
            return Map.of();
        }
        Object[] row = bounds.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("earliest", row[0].toString());
        result.put("latest", row[1].toString());
        result.put("temporalEdgeCount", edgeRepository.countWithOccurredAt());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MENTION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<EntityMention> getEntityMentionsForNode(GraphNode node) {
        return entityMentionRepository.findByNode(node);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntityMention> getEntityMentionsForNode(String nodeId) {
        GraphNode node = nodeRepository.findByNodeId(nodeId).orElse(null);
        if (node == null) {
            return Collections.emptyList();
        }
        return entityMentionRepository.findByNode(node);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EntityMention> findEntityMention(GraphNode node, String entityName) {
        return entityMentionRepository.findByNodeAndEntityName(node, entityName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EntityMention> findEntityMentionInFactSheet(GraphNode node, String entityName, Long factSheetId) {
        return entityMentionRepository.findByNodeAndEntityNameAndFactSheet(node, entityName, factSheetId);
    }

    @Override
    @Transactional
    public EntityMention saveEntityMention(EntityMention mention) {
        return entityMentionRepository.save(mention);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findNodePairsWithSharedEntities(int minShared) {
        return entityMentionRepository.findNodePairsWithSharedEntities(minShared);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared) {
        return entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(factSheetId, minShared);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getEntityNamesForNode(String nodeId) {
        return entityMentionRepository.findEntitiesByNodeId(nodeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GraphNode> getNodesWithEntity(String entityName) {
        return entityMentionRepository.findNodesWithEntity(entityName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNT / STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public long countNodesByType(NodeLevel type) {
        return nodeRepository.countByNodeType(type);
    }

    @Override
    public long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return nodeRepository.countByFactSheetIdAndNodeType(factSheetId, type);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void flushPendingNodes() {
        // JPA flushes automatically at transaction boundaries; this is a no-op for the JPA implementation.
    }

    @Override
    @Transactional
    public void deleteByFactSheetId(Long factSheetId) {
        entityMentionRepository.deleteByFactSheetId(factSheetId);
        int edgesDeleted = edgeRepository.deleteByFactSheetId(factSheetId);
        int nodesDeleted = nodeRepository.deleteByFactSheetId(factSheetId);
        log.info("Deleted {} edges and {} nodes for factSheetId={}", edgesDeleted, nodesDeleted, factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNING / MAINTENANCE QUERIES — JPA backend (bulk @Modifying ops)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<String> findOrphanNodeIds(Long factSheetId) {
        return nodeRepository.findGraphOrphanEntities(factSheetId).stream()
                .map(GraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findLowConfidenceNodeIds(Long factSheetId, double minConfidence) {
        return nodeRepository.findLowConfidenceEntities(factSheetId, minConfidence).stream()
                .map(GraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findLowConfidenceEdgeIds(Long factSheetId, double minConfidence) {
        return edgeRepository.findLowConfidenceEdges(factSheetId, minConfidence).stream()
                .map(GraphEdge::getEdgeId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveNodes(Long factSheetId) {
        return nodeRepository.countActiveNodes(factSheetId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findActiveEdgeIds(Long factSheetId) {
        return edgeRepository.findActiveEdges(factSheetId).stream()
                .map(GraphEdge::getEdgeId)
                .collect(Collectors.toList());
    }

    /**
     * JPA soft-delete/hard-delete of nodes.
     *
     * <p>Uses the repository's {@code @Modifying} {@link ai.kompile.knowledgegraph.repository.GraphNodeRepository#bulkMarkStale}
     * for soft-delete, or individual {@link #deleteNode} calls for hard-delete.
     * Nodes are resolved from nodeId strings to database-id longs in one batch.</p>
     */
    @Override
    @Transactional
    public GraphPruneResult pruneNodes(java.util.Collection<String> nodeIds,
                                       boolean softDelete,
                                       Duration grace,
                                       boolean dryRun) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(nodeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            // Resolve nodeId (UUID string) → database id (Long) for the bulk op
            List<Long> dbIds = ids.stream()
                    .map(nid -> nodeRepository.findByNodeId(nid).map(GraphNode::getId).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            if (!dbIds.isEmpty()) {
                nodeRepository.bulkMarkStale(dbIds, java.time.LocalDateTime.now());
            }
            return GraphPruneResult.ofSoftDelete(ids, false);
        } else {
            int deleted = 0;
            for (String nodeId : ids) {
                try {
                    deleteNode(nodeId);
                    deleted++;
                } catch (Exception e) {
                    log.warn("pruneNodes: could not delete node {}: {}", nodeId, e.getMessage());
                }
            }
            return new GraphPruneResult(ids, deleted, deleted, false);
        }
    }

    /**
     * JPA soft-delete/hard-delete of edges.
     *
     * <p>Uses the repository's {@code @Modifying}
     * {@link ai.kompile.knowledgegraph.repository.GraphEdgeRepository#bulkMarkStale}
     * for the soft-delete path.</p>
     */
    @Override
    @Transactional
    public GraphPruneResult pruneEdges(java.util.Collection<String> edgeIds,
                                       boolean softDelete,
                                       boolean dryRun) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(edgeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            List<Long> dbIds = ids.stream()
                    .map(eid -> edgeRepository.findByEdgeId(eid).map(GraphEdge::getId).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            if (!dbIds.isEmpty()) {
                edgeRepository.bulkMarkStale(dbIds, java.time.LocalDateTime.now());
            }
            return GraphPruneResult.ofSoftDelete(ids, false);
        } else {
            int deleted = 0;
            for (String edgeId : ids) {
                try {
                    deleteEdge(edgeId);
                    deleted++;
                } catch (Exception e) {
                    log.warn("pruneEdges: could not delete edge {}: {}", edgeId, e.getMessage());
                }
            }
            return new GraphPruneResult(ids, deleted, deleted, false);
        }
    }

    /**
     * JPA hard-delete of stale nodes past the grace period.
     *
     * <p>Delegates directly to
     * {@link ai.kompile.knowledgegraph.repository.GraphNodeRepository#hardDeleteStaleNodes}.</p>
     */
    @Override
    @Transactional
    public GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
        int graceDays = (int) (grace != null ? grace.toDays() : 7);
        java.time.LocalDateTime graceCutoff = java.time.LocalDateTime.now().minusDays(graceDays);
        int hardDeleted = nodeRepository.hardDeleteStaleNodes(factSheetId, graceCutoff);
        if (hardDeleted > 0) {
            log.info("hardDeleteStaleNodes: permanently removed {} stale nodes for factSheetId={}", hardDeleted, factSheetId);
        }
        return GraphPruneResult.ofHardDelete(hardDeleted, false);
    }

    // Helper class for BFS traversal
    private static class NodeWithDepth {
        final GraphNode node;
        final int depth;

        NodeWithDepth(GraphNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}
