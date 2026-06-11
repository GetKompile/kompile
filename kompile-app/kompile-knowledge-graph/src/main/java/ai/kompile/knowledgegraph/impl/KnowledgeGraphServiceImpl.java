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
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the KnowledgeGraphService interface.
 */
@Service
@Slf4j
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public KnowledgeGraphServiceImpl(GraphNodeRepository nodeRepository,
                                      GraphEdgeRepository edgeRepository,
                                      ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
    }

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
        stats.put("sourceCount", nodeRepository.countByNodeType(NodeLevel.SOURCE));
        stats.put("documentCount", nodeRepository.countByNodeType(NodeLevel.DOCUMENT));
        stats.put("snippetCount", nodeRepository.countByNodeType(NodeLevel.SNIPPET));
        stats.put("entityCount", nodeRepository.countByNodeType(NodeLevel.ENTITY));
        stats.put("customCount", nodeRepository.countByNodeType(NodeLevel.CUSTOM));
        stats.put("totalEdges", edgeRepository.count());

        for (EdgeType type : EdgeType.values()) {
            stats.put("edges_" + type.name().toLowerCase(), edgeRepository.countByEdgeType(type));
        }

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
        };
    }

    private Map<String, Object> nodeToVisualizationMap(GraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.getNodeId());
        map.put("type", node.getNodeType().name().toLowerCase());
        map.put("label", node.getTitle());
        map.put("title", node.getTitle());
        map.put("description", node.getDescription());
        map.put("sourceType", node.getSourceType());
        map.put("childCount", node.getChildCount());
        map.put("edgeCount", node.getEdgeCount());
        if (node.getParent() != null) {
            map.put("parentId", node.getParent().getNodeId());
        }
        if (node.getSourceNode() != null) {
            map.put("sourceId", node.getSourceNode().getNodeId());
        }
        return map;
    }

    private Map<String, Object> edgeToVisualizationMap(GraphEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", edge.getEdgeId());
        map.put("source", edge.getSourceNode().getNodeId());
        map.put("target", edge.getTargetNode().getNodeId());
        map.put("type", edge.getEdgeType().name().toLowerCase());
        map.put("weight", edge.getWeight());
        map.put("label", edge.getLabel());
        map.put("description", edge.getDescription());
        map.put("bidirectional", edge.getBidirectional());
        return map;
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
