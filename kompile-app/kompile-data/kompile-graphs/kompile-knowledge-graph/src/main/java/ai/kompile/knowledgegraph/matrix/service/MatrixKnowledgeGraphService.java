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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Matrix-based implementation of KnowledgeGraphService.
 * <p>
 * This implementation uses the matrix-based graph storage and provides
 * compatibility with the existing KnowledgeGraphService interface.
 * </p>
 */
@Service
@Primary
@Slf4j
public class MatrixKnowledgeGraphService implements KnowledgeGraphService {

    @Autowired
    private MatrixGraphStore graphStore;
    @Autowired
    private ObjectMapper objectMapper;

    public MatrixKnowledgeGraphService() {}

    /** Test constructor. */
    public MatrixKnowledgeGraphService(MatrixGraphStore graphStore, ObjectMapper objectMapper) {
        this.graphStore = graphStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Default graph ID for single-graph mode.
     */
    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    /**
     * Mapping of edge type enums to string types used in matrix graph.
     */
    private static final Map<EdgeType, String> EDGE_TYPE_MAP = Map.of(
            EdgeType.HIERARCHICAL, "HIERARCHICAL",
            EdgeType.EMBEDDING_SIMILARITY, "EMBEDDING_SIMILARITY",
            EdgeType.SHARED_ENTITY, "SHARED_ENTITY",
            EdgeType.USER_DEFINED, "USER_DEFINED",
            EdgeType.CITATION, "CITATION",
            EdgeType.TEMPORAL, "TEMPORAL",
            EdgeType.CROSS_SOURCE, "CROSS_SOURCE"
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                               String pathOrUrl, Map<String, Object> metadata) {
        String nodeId = "source_" + externalId;

        Optional<MatrixGraphNode> existing = graphStore.getNode(DEFAULT_GRAPH_ID, nodeId);
        if (existing.isPresent()) {
            MatrixGraphNode node = existing.get();
            node.setTitle(title);
            node.setNodeType("SOURCE");
            if (metadata == null) {
                metadata = new HashMap<>();
            }
            metadata.put("sourceType", sourceType);
            metadata.put("pathOrUrl", pathOrUrl);
            node.setMetadata(metadata);
            graphStore.updateNode(DEFAULT_GRAPH_ID, node);
            return convertToGraphNode(node, externalId);
        }

        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("sourceType", sourceType);
        metadata.put("pathOrUrl", pathOrUrl);

        MatrixGraphNode node = MatrixGraphNode.builder()
                .nodeId(nodeId)
                .nodeType("SOURCE")
                .title(title)
                .metadata(metadata)
                .build();

        graphStore.addNode(DEFAULT_GRAPH_ID, node);
        return convertToGraphNode(node, externalId);
    }

    @Override
    public GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                         Map<String, Object> metadata) {
        String nodeId = "doc_" + docId;

        Optional<MatrixGraphNode> existing = graphStore.getNode(DEFAULT_GRAPH_ID, nodeId);
        if (existing.isPresent()) {
            MatrixGraphNode node = existing.get();
            node.setTitle(title);
            node.setMetadata(metadata);
            graphStore.updateNode(DEFAULT_GRAPH_ID, node);
            return convertToGraphNode(node, docId);
        }

        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("parentNodeId", sourceNode.getNodeId());

        MatrixGraphNode node = MatrixGraphNode.builder()
                .nodeId(nodeId)
                .nodeType("DOCUMENT")
                .title(title)
                .metadata(metadata)
                .build();

        graphStore.addNode(DEFAULT_GRAPH_ID, node);

        // Create hierarchical edge from source to document
        String sourceMatrixId = "source_" + sourceNode.getExternalId();
        graphStore.addEdge(DEFAULT_GRAPH_ID, sourceMatrixId, nodeId, 1.0, "HIERARCHICAL", false);

        return convertToGraphNode(node, docId);
    }

    @Override
    public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                        int chunkIndex) {
        String nodeId = "snippet_" + snippetId;
        String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;

        Optional<MatrixGraphNode> existing = graphStore.getNode(DEFAULT_GRAPH_ID, nodeId);
        if (existing.isPresent()) {
            MatrixGraphNode node = existing.get();
            node.setDescription(preview);
            graphStore.updateNode(DEFAULT_GRAPH_ID, node);
            return convertToGraphNode(node, snippetId);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("parentNodeId", documentNode.getNodeId());

        MatrixGraphNode node = MatrixGraphNode.builder()
                .nodeId(nodeId)
                .nodeType("SNIPPET")
                .title("Chunk " + (chunkIndex + 1))
                .description(preview)
                .metadata(metadata)
                .build();

        graphStore.addNode(DEFAULT_GRAPH_ID, node);

        // Create hierarchical edge from document to snippet
        String docMatrixId = "doc_" + documentNode.getExternalId();
        graphStore.addEdge(DEFAULT_GRAPH_ID, docMatrixId, nodeId, 1.0, "HIERARCHICAL", false);

        return convertToGraphNode(node, snippetId);
    }

    @Override
    public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                 String description, Map<String, Object> metadata) {
        return createNode(nodeType, externalId, title, description, metadata, null);
    }

    /**
     * Fact-sheet-scoped createNode override.
     * <p>
     * Stores the node in the vector/matrix store with the factSheetId preserved so
     * in-memory queries (countNodesByTypeInFactSheet, getNodesByTypeInFactSheet)
     * can find it. The vector store is the SINGLE SOURCE OF TRUTH — no JPA write-through.
     * </p>
     */
    @Override
    public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                 String description, Map<String, Object> metadata,
                                 Long factSheetId) {
        String nodeId = nodeType.name().toLowerCase() + "_" + externalId;

        MatrixGraphNode matrixNode = MatrixGraphNode.builder()
                .nodeId(nodeId)
                .nodeType(nodeType.name())
                .title(title)
                .description(description)
                .metadata(metadata != null ? metadata : new HashMap<>())
                .factSheetId(factSheetId)
                .build();

        graphStore.addNode(DEFAULT_GRAPH_ID, matrixNode);

        return convertToGraphNode(matrixNode, externalId);
    }

    /**
     * Fact-sheet-scoped addDocument override.
     * <p>
     * Routes through the 6-arg {@link #createNode} which stores factSheetId on the
     * vector-store node, so per-fact-sheet queries (countNodesByTypeInFactSheet,
     * getNodesByTypeInFactSheet) can find DOCUMENT nodes — consistent with ENTITY nodes.
     * The vector store is the SINGLE SOURCE OF TRUTH — no JPA write-through.
     * </p>
     */
    @Override
    public GraphNode addDocument(String sourceExternalId, String jobId, String sourceType,
                                  String sourcePath, String fileName,
                                  String contentPreview, Map<String, Object> docMeta,
                                  Long factSheetId) {
        // Persist the SOURCE node (no factSheetId — SOURCE spans fact sheets)
        Map<String, Object> sourceMeta = docMeta != null ? new HashMap<>(docMeta) : new HashMap<>();
        sourceMeta.put("sourceType", sourceType != null ? sourceType : "FILE");
        sourceMeta.put("pathOrUrl", sourcePath);
        GraphNode sourceNode = createNode(NodeLevel.SOURCE, sourceExternalId, jobId,
                null, sourceMeta, null);

        // Persist the DOCUMENT node with factSheetId so it is scoped in the vector store
        Map<String, Object> documentMeta = docMeta != null ? new HashMap<>(docMeta) : new HashMap<>();
        if (contentPreview != null) {
            documentMeta.put("contentPreview", contentPreview);
        }
        documentMeta.put("parentNodeId", sourceNode.getNodeId());
        String docTitle = fileName != null ? fileName : sourcePath;
        GraphNode docNode = createNode(NodeLevel.DOCUMENT, sourcePath, docTitle,
                contentPreview, documentMeta, factSheetId);

        // Hierarchical edge from SOURCE → DOCUMENT (matrix store only, best-effort)
        try {
            graphStore.addEdge(DEFAULT_GRAPH_ID, sourceNode.getNodeId(), docNode.getNodeId(),
                    1.0, "HIERARCHICAL", false);
        } catch (Exception ignored) {
            // best-effort
        }

        return docNode;
    }

    @Override
    public Optional<GraphNode> getNode(String nodeId) {
        return graphStore.getNode(DEFAULT_GRAPH_ID, nodeId)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())));
    }

    @Override
    public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType) {
        String nodeId = nodeType.name().toLowerCase() + "_" + externalId;
        return getNode(nodeId);
    }

    @Override
    public List<GraphNode> getChildren(String parentNodeId) {
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(DEFAULT_GRAPH_ID, parentNodeId, "HIERARCHICAL");

        return edges.stream()
                .map(entry -> graphStore.getNode(DEFAULT_GRAPH_ID, entry.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public GraphNode updateNode(String nodeId, String title, String description,
                                 Map<String, Object> metadata) {
        Optional<MatrixGraphNode> nodeOpt = graphStore.getNode(DEFAULT_GRAPH_ID, nodeId);
        if (nodeOpt.isEmpty()) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }

        MatrixGraphNode node = nodeOpt.get();
        if (title != null) node.setTitle(title);
        if (description != null) node.setDescription(description);
        if (metadata != null) node.setMetadata(metadata);

        graphStore.updateNode(DEFAULT_GRAPH_ID, node);
        return convertToGraphNode(node, extractExternalId(nodeId));
    }

    @Override
    public void deleteNode(String nodeId) {
        graphStore.removeNode(DEFAULT_GRAPH_ID, nodeId);
    }

    @Override
    public List<GraphNode> getAllSources() {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> "SOURCE".equals(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> searchNodes(String query, NodeLevel type, int limit) {
        List<MatrixGraphNode> results = graphStore.searchNodes(DEFAULT_GRAPH_ID, query, limit * 2);

        return results.stream()
                .filter(n -> type == null || type.name().equals(n.getNodeType()))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type, int limit) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getAllNodes(int limit) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesByIds(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> idSet = new HashSet<>(nodeIds);
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> idSet.contains(n.getNodeId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED NODE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesInFactSheet(Long factSheetId) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getSourcesInFactSheet(Long factSheetId) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> "SOURCE".equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type, Long factSheetId) {
        String nodeId = type.name().toLowerCase() + "_" + externalId;
        return graphStore.getNode(DEFAULT_GRAPH_ID, nodeId)
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, externalId));
    }

    @Override
    public List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit) {
        String lowerQuery = query != null ? query.toLowerCase() : "";
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> (n.getTitle() != null && n.getTitle().toLowerCase().contains(lowerQuery))
                        || (n.getDescription() != null && n.getDescription().toLowerCase().contains(lowerQuery)))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED EDGE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphEdge> getEdgesForNodeInFactSheet(String nodeId, Long factSheetId) {
        // The matrix store doesn't track factSheetId on edges; return all edges for the node.
        return getEdgesForNode(nodeId);
    }

    @Override
    public boolean edgeExistsInFactSheet(String sourceNodeId, String targetNodeId, Long factSheetId) {
        return edgeExists(sourceNodeId, targetNodeId);
    }

    @Override
    public List<GraphEdge> getEdgesInFactSheet(Long factSheetId) {
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }
        AdjacencyMatrixGraph graph = graphOpt.get();
        List<GraphEdge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            if (node.getFactSheetId() == null || !node.getFactSheetId().equals(factSheetId)) {
                continue;
            }
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(node.getNodeId(), null);
            for (Map.Entry<String, Double> neighbor : neighbors) {
                String key = node.getNodeId() + "::" + neighbor.getKey();
                if (seen.add(key)) {
                    result.add(createEdgeObject(node.getNodeId(), neighbor.getKey(),
                            EdgeType.USER_DEFINED, neighbor.getValue(), null));
                }
            }
        }
        return result;
    }

    @Override
    public List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType) {
        String edgeTypeStr = EDGE_TYPE_MAP.getOrDefault(edgeType, "RELATED_TO");
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }
        AdjacencyMatrixGraph graph = graphOpt.get();
        List<GraphEdge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            if (node.getFactSheetId() == null || !node.getFactSheetId().equals(factSheetId)) {
                continue;
            }
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(node.getNodeId(), edgeTypeStr);
            for (Map.Entry<String, Double> neighbor : neighbors) {
                String key = node.getNodeId() + "::" + neighbor.getKey();
                if (seen.add(key)) {
                    result.add(createEdgeObject(node.getNodeId(), neighbor.getKey(),
                            edgeType, neighbor.getValue(), null));
                }
            }
        }
        return result;
    }

    @Override
    public GraphEdge findEdgeBetweenNodes(String sourceNodeId, String targetNodeId) {
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(DEFAULT_GRAPH_ID, sourceNodeId, null);
        for (Map.Entry<String, Double> edge : edges) {
            if (edge.getKey().equals(targetNodeId)) {
                return createEdgeObject(sourceNodeId, targetNodeId,
                        EdgeType.USER_DEFINED, edge.getValue(), null);
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MENTION OPERATIONS — matrix store has no mention persistence;
    // return empty / no-op implementations that are type-correct.
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<EntityMention> getEntityMentionsForNode(GraphNode node) {
        return Collections.emptyList();
    }

    @Override
    public List<EntityMention> getEntityMentionsForNode(String nodeId) {
        return Collections.emptyList();
    }

    @Override
    public Optional<EntityMention> findEntityMention(GraphNode node, String entityName) {
        return Optional.empty();
    }

    @Override
    public Optional<EntityMention> findEntityMentionInFactSheet(GraphNode node, String entityName, Long factSheetId) {
        return Optional.empty();
    }

    @Override
    public EntityMention saveEntityMention(EntityMention mention) {
        // Matrix store is in-memory and does not persist entity mentions.
        return mention;
    }

    @Override
    public List<Object[]> findNodePairsWithSharedEntities(int minShared) {
        return Collections.emptyList();
    }

    @Override
    public List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getEntityNamesForNode(String nodeId) {
        return Collections.emptyList();
    }

    @Override
    public List<GraphNode> getNodesWithEntity(String entityName) {
        return Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 Double weight, String description) {
        String edgeTypeStr = EDGE_TYPE_MAP.getOrDefault(edgeType, "RELATED_TO");
        boolean bidirectional = edgeType != EdgeType.HIERARCHICAL;

        graphStore.addEdge(DEFAULT_GRAPH_ID, sourceNodeId, targetNodeId,
                weight != null ? weight : 1.0, edgeTypeStr, bidirectional);

        return createEdgeObject(sourceNodeId, targetNodeId, edgeType, weight, description);
    }

    @Override
    public Optional<GraphEdge> getEdge(String edgeId) {
        // Edge ID format: "source_id::target_id::type"
        String[] parts = edgeId.split("::");
        if (parts.length < 2) {
            return Optional.empty();
        }

        String sourceId = parts[0];
        String targetId = parts[1];

        List<Map.Entry<String, Double>> edges = graphStore.getEdges(DEFAULT_GRAPH_ID, sourceId, null);
        for (Map.Entry<String, Double> edge : edges) {
            if (edge.getKey().equals(targetId)) {
                return Optional.of(createEdgeObject(sourceId, targetId,
                        EdgeType.USER_DEFINED, edge.getValue(), null));
            }
        }

        return Optional.empty();
    }

    @Override
    public List<GraphEdge> getEdgesForNode(String nodeId) {
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(DEFAULT_GRAPH_ID, nodeId, null);

        return edges.stream()
                .map(e -> createEdgeObject(nodeId, e.getKey(), EdgeType.USER_DEFINED, e.getValue(), null))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType) {
        String edgeTypeStr = EDGE_TYPE_MAP.getOrDefault(edgeType, "RELATED_TO");
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(DEFAULT_GRAPH_ID, nodeId, edgeTypeStr);

        return edges.stream()
                .map(e -> createEdgeObject(nodeId, e.getKey(), edgeType, e.getValue(), null))
                .collect(Collectors.toList());
    }

    @Override
    public GraphEdge updateEdge(String edgeId, Double weight, String description) {
        String[] parts = edgeId.split("::");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid edge ID: " + edgeId);
        }

        String sourceId = parts[0];
        String targetId = parts[1];
        String edgeType = parts[2];

        graphStore.addEdge(DEFAULT_GRAPH_ID, sourceId, targetId,
                weight != null ? weight : 1.0, edgeType, false);

        EdgeType type = EDGE_TYPE_MAP.entrySet().stream()
                .filter(e -> e.getValue().equals(edgeType))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(EdgeType.USER_DEFINED);

        return createEdgeObject(sourceId, targetId, type, weight, description);
    }

    @Override
    public void deleteEdge(String edgeId) {
        String[] parts = edgeId.split("::");
        if (parts.length >= 2) {
            String edgeType = parts.length >= 3 ? parts[2] : null;
            graphStore.removeEdge(DEFAULT_GRAPH_ID, parts[0], parts[1], edgeType);
        }
    }

    @Override
    public boolean edgeExists(String sourceNodeId, String targetNodeId) {
        return graphStore.hasEdge(DEFAULT_GRAPH_ID, sourceNodeId, targetNodeId, null);
    }

    @Override
    public List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit) {
        // Matrix-based implementation: search edges by filtering on type
        // This is a simplified implementation - full text search would require additional indexing
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }

        AdjacencyMatrixGraph graph = graphOpt.get();
        List<GraphEdge> results = new ArrayList<>();
        String edgeTypeStr = edgeType != null ? EDGE_TYPE_MAP.get(edgeType) : null;
        String lowerQuery = query != null ? query.toLowerCase() : null;

        // Iterate through all nodes and their neighbors
        for (String nodeId : graph.getNodeById().keySet()) {
            // Get neighbors for each edge type
            List<String> edgeTypes = edgeTypeStr != null ? List.of(edgeTypeStr) :
                new ArrayList<>(graph.getAdjacencyMatrices().keySet());

            for (String type : edgeTypes) {
                List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(nodeId, type);
                for (Map.Entry<String, Double> neighbor : neighbors) {
                    String targetId = neighbor.getKey();
                    Double weight = neighbor.getValue();

                    // Check if node titles match query
                    if (lowerQuery != null) {
                        Optional<MatrixGraphNode> source = graph.getNode(nodeId);
                        Optional<MatrixGraphNode> target = graph.getNode(targetId);
                        boolean matches = false;
                        if (source.isPresent() && source.get().getTitle() != null &&
                            source.get().getTitle().toLowerCase().contains(lowerQuery)) {
                            matches = true;
                        }
                        if (target.isPresent() && target.get().getTitle() != null &&
                            target.get().getTitle().toLowerCase().contains(lowerQuery)) {
                            matches = true;
                        }
                        if (!matches) {
                            continue;
                        }
                    }

                    // Convert to GraphEdge
                    GraphEdge edge = GraphEdge.builder()
                        .edgeId(nodeId + "_" + targetId + "_" + type)
                        .sourceNode(GraphNode.builder().nodeId(nodeId).build())
                        .targetNode(GraphNode.builder().nodeId(targetId).build())
                        .edgeType(EdgeType.valueOf(type))
                        .weight(weight)
                        .build();
                    results.add(edge);

                    if (results.size() >= limit) {
                        return results;
                    }
                }
            }
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TRAVERSAL
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphNode> getConnectedNodes(String nodeId, int depth) {
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }

        AdjacencyMatrixGraph graph = graphOpt.get();
        Set<String> visited = new HashSet<>();
        List<MatrixGraphNode> result = new ArrayList<>();

        Optional<MatrixGraphNode> startOpt = graph.getNode(nodeId);
        if (startOpt.isEmpty()) {
            return Collections.emptyList();
        }

        Queue<NodeWithDepth> queue = new LinkedList<>();
        queue.add(new NodeWithDepth(nodeId, 0));
        visited.add(nodeId);

        while (!queue.isEmpty()) {
            NodeWithDepth current = queue.poll();

            if (current.depth > 0) {
                graph.getNode(current.nodeId).ifPresent(result::add);
            }

            if (current.depth < depth) {
                List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(current.nodeId, null);
                for (Map.Entry<String, Double> neighbor : neighbors) {
                    if (!visited.contains(neighbor.getKey())) {
                        visited.add(neighbor.getKey());
                        queue.add(new NodeWithDepth(neighbor.getKey(), current.depth + 1));
                    }
                }
            }
        }

        return result.stream()
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> findRelatedNodes(String nodeId, int maxResults) {
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }

        AdjacencyMatrixGraph graph = graphOpt.get();
        List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(nodeId, null);

        return neighbors.stream()
                .filter(e -> !isHierarchicalEdge(nodeId, e.getKey()))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(maxResults)
                .map(e -> graph.getNode(e.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Double> computeNodeRelevance(String queryNodeId, List<String> candidateNodeIds) {
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return candidateNodeIds.stream()
                    .collect(Collectors.toMap(id -> id, id -> 0.1));
        }

        AdjacencyMatrixGraph graph = graphOpt.get();

        // Use PageRank scores combined with direct edge weights
        Map<String, Double> pageRankScores = MatrixGraphAlgorithms.pageRank(graph);

        Map<String, Double> relevanceMap = new HashMap<>();
        for (String candidateId : candidateNodeIds) {
            double directWeight = graph.getEdgeWeight(queryNodeId, candidateId, null);
            double prScore = pageRankScores.getOrDefault(candidateId, 0.0);

            // Combine direct edge weight with PageRank
            double relevance = directWeight > 0
                    ? 0.7 * directWeight + 0.3 * prScore
                    : 0.3 * prScore + 0.1;

            relevanceMap.put(candidateId, Math.min(1.0, relevance));
        }

        return relevanceMap;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & VISUALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getGraphStatistics() {
        Map<String, Object> matrixStats = graphStore.getGraphStatistics(DEFAULT_GRAPH_ID);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalNodes", matrixStats.getOrDefault("nodeCount", 0));

        // Count by type
        List<MatrixGraphNode> allNodes = graphStore.getAllNodes(DEFAULT_GRAPH_ID);
        Map<String, Long> typeCounts = allNodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getNodeType() != null ? n.getNodeType() : "UNKNOWN",
                        Collectors.counting()
                ));

        stats.put("sourceCount", typeCounts.getOrDefault("SOURCE", 0L));
        stats.put("documentCount", typeCounts.getOrDefault("DOCUMENT", 0L));
        stats.put("snippetCount", typeCounts.getOrDefault("SNIPPET", 0L));
        stats.put("entityCount", typeCounts.getOrDefault("ENTITY", 0L));
        stats.put("customCount", typeCounts.getOrDefault("CUSTOM", 0L));
        stats.put("totalEdges", matrixStats.getOrDefault("edgeCount", 0));

        // Edge type counts
        @SuppressWarnings("unchecked")
        Set<String> edgeTypes = (Set<String>) matrixStats.getOrDefault("edgeTypes", Collections.emptySet());
        for (String edgeType : edgeTypes) {
            stats.put("edges_" + edgeType.toLowerCase(),
                    countEdgesByType(DEFAULT_GRAPH_ID, edgeType));
        }

        return stats;
    }

    @Override
    public Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes) {
        List<MatrixGraphNode> nodes;
        List<Map<String, Object>> edges = new ArrayList<>();

        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of(),
                    "metadata", Map.of("nodeCount", 0, "edgeCount", 0));
        }

        AdjacencyMatrixGraph graph = graphOpt.get();

        if (rootNodeId != null) {
            // Get nodes from root with depth limit
            Set<String> visitedIds = new HashSet<>();
            nodes = new ArrayList<>();

            graph.getNode(rootNodeId).ifPresent(nodes::add);
            visitedIds.add(rootNodeId);

            collectNodesAtDepth(graph, rootNodeId, depth, visitedIds, nodes, maxNodes);
        } else {
            // Get all nodes with limit
            nodes = graph.getAllNodes();
            if (nodes.size() > maxNodes) {
                nodes = nodes.stream()
                        .sorted((a, b) -> {
                            int typeOrder = getTypeOrder(a.getNodeType()) - getTypeOrder(b.getNodeType());
                            return typeOrder;
                        })
                        .limit(maxNodes)
                        .collect(Collectors.toList());
            }
        }

        // Collect edges between visible nodes
        Set<String> nodeIds = nodes.stream()
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toSet());

        Set<String> seenEdges = new HashSet<>();
        for (MatrixGraphNode node : nodes) {
            // Iterate over all edge types to capture type information
            for (String edgeType : graph.getEdgeTypes()) {
                List<Map.Entry<String, Double>> typeEdges = graph.getNeighbors(node.getNodeId(), edgeType);
                for (Map.Entry<String, Double> edge : typeEdges) {
                    if (nodeIds.contains(edge.getKey())) {
                        String edgeId = node.getNodeId() + "::" + edge.getKey() + "::" + edgeType;
                        if (seenEdges.add(edgeId)) {
                            Map<String, Object> edgeMap = new LinkedHashMap<>();
                            edgeMap.put("id", edgeId);
                            edgeMap.put("source", node.getNodeId());
                            edgeMap.put("target", edge.getKey());
                            edgeMap.put("type", edgeType);
                            edgeMap.put("weight", edge.getValue());
                            edgeMap.put("bidirectional", !"HIERARCHICAL".equals(edgeType));
                            edges.add(edgeMap);
                        }
                    }
                }
            }
        }

        // Convert to visualization format
        List<Map<String, Object>> nodeData = nodes.stream()
                .map(this::nodeToVisualizationMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edges);
        result.put("metadata", Map.of(
                "nodeCount", nodes.size(),
                "edgeCount", edges.size()
        ));

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode convertToGraphNode(MatrixGraphNode matrixNode, String externalId) {
        return GraphNode.builder()
                .nodeId(matrixNode.getNodeId())
                .externalId(externalId)
                .nodeType(NodeLevel.valueOf(matrixNode.getNodeType()))
                .title(matrixNode.getTitle())
                .description(matrixNode.getDescription())
                .metadataJson(serializeMetadata(matrixNode.getMetadata()))
                .createdAt(LocalDateTime.ofEpochSecond(matrixNode.getCreatedAt() / 1000, 0, ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofEpochSecond(matrixNode.getUpdatedAt() / 1000, 0, ZoneOffset.UTC))
                .factSheetId(matrixNode.getFactSheetId())
                .build();
    }

    private GraphEdge createEdgeObject(String sourceId, String targetId, EdgeType edgeType,
                                        Double weight, String description) {
        // Populate sourceNode and targetNode so JSON serialization includes
        // sourceNodeId/targetNodeId and node titles for the entity browser.
        GraphNode sourceNode = graphStore.getNode(DEFAULT_GRAPH_ID, sourceId)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .orElseGet(() -> GraphNode.builder().nodeId(sourceId).build());
        GraphNode targetNode = graphStore.getNode(DEFAULT_GRAPH_ID, targetId)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .orElseGet(() -> GraphNode.builder().nodeId(targetId).build());

        return GraphEdge.builder()
                .edgeId(sourceId + "::" + targetId + "::" + edgeType.name())
                .sourceNode(sourceNode)
                .targetNode(targetNode)
                .edgeType(edgeType)
                .weight(weight != null ? weight : 1.0)
                .description(description)
                .bidirectional(edgeType != EdgeType.HIERARCHICAL)
                .build();
    }

    private String extractExternalId(String nodeId) {
        int underscoreIdx = nodeId.indexOf('_');
        return underscoreIdx >= 0 ? nodeId.substring(underscoreIdx + 1) : nodeId;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return null;
        }
    }

    private int getTypeOrder(String type) {
        return switch (type) {
            case "SOURCE" -> 0;
            case "DOCUMENT" -> 1;
            case "ENTITY" -> 2;
            case "CUSTOM" -> 3;
            case "SNIPPET" -> 4;
            default -> 5;
        };
    }

    private boolean isHierarchicalEdge(String sourceId, String targetId) {
        return graphStore.hasEdge(DEFAULT_GRAPH_ID, sourceId, targetId, "HIERARCHICAL");
    }

    private long countEdgesByType(String graphId, String edgeType) {
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphId);
        if (graphOpt.isEmpty()) {
            return 0;
        }

        AdjacencyMatrixGraph graph = graphOpt.get();
        if (!graph.getEdgeTypes().contains(edgeType)) {
            return 0;
        }

        // With sparse storage, edge counts are maintained as O(1) counters per type.
        // No INDArray or GPU operation needed.
        return graph.getEdgeCountByType(edgeType);
    }

    private void collectNodesAtDepth(AdjacencyMatrixGraph graph, String nodeId, int depth,
                                     Set<String> visited, List<MatrixGraphNode> result, int maxNodes) {
        if (depth <= 0 || result.size() >= maxNodes) {
            return;
        }

        List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(nodeId, null);
        for (Map.Entry<String, Double> neighbor : neighbors) {
            if (result.size() >= maxNodes) {
                break;
            }
            if (!visited.contains(neighbor.getKey())) {
                visited.add(neighbor.getKey());
                graph.getNode(neighbor.getKey()).ifPresent(result::add);
                collectNodesAtDepth(graph, neighbor.getKey(), depth - 1, visited, result, maxNodes);
            }
        }
    }

    private Map<String, Object> nodeToVisualizationMap(MatrixGraphNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.getNodeId());
        map.put("type", node.getNodeType() != null ? node.getNodeType() : "UNKNOWN");
        map.put("label", node.getTitle());
        map.put("title", node.getTitle());
        map.put("description", node.getDescription());

        if (node.getMetadata() != null) {
            map.put("sourceType", node.getMetadata().get("sourceType"));
            map.put("parentId", node.getMetadata().get("parentNodeId"));
        }

        // Include childCount and edgeCount for parity with JPA visualization
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(DEFAULT_GRAPH_ID);
        if (graphOpt.isPresent()) {
            AdjacencyMatrixGraph graph = graphOpt.get();
            // Count children (HIERARCHICAL outgoing edges)
            List<Map.Entry<String, Double>> children = graph.getNeighbors(node.getNodeId(), "HIERARCHICAL");
            map.put("childCount", children != null ? children.size() : 0);
            // Count all edges
            List<Map.Entry<String, Double>> allEdges = graph.getNeighbors(node.getNodeId(), null);
            map.put("edgeCount", allEdges != null ? allEdges.size() : 0);
        } else {
            map.put("childCount", 0);
            map.put("edgeCount", 0);
        }

        return map;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNT / STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public long countNodesByType(NodeLevel type) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .count();
    }

    @Override
    public long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .count();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void flushPendingNodes() {
        // Matrix store writes are synchronous and in-memory; nothing to flush.
    }

    @Override
    public void deleteByFactSheetId(Long factSheetId) {
        List<MatrixGraphNode> toDelete = graphStore.getAllNodes(DEFAULT_GRAPH_ID).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .collect(Collectors.toList());
        for (MatrixGraphNode node : toDelete) {
            graphStore.removeNode(DEFAULT_GRAPH_ID, node.getNodeId());
        }
        log.info("Deleted {} nodes for factSheetId={}", toDelete.size(), factSheetId);
    }

    /**
     * Helper class for BFS traversal.
     */
    private static class NodeWithDepth {
        final String nodeId;
        final int depth;

        NodeWithDepth(String nodeId, int depth) {
            this.nodeId = nodeId;
            this.depth = depth;
        }
    }
}
