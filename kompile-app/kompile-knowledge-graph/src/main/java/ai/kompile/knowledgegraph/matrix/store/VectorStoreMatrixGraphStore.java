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
package ai.kompile.knowledgegraph.matrix.store;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * VectorStore-backed implementation of MatrixGraphStore.
 * <p>
 * This implementation stores graph data in a vector store, enabling:
 * <ul>
 *   <li>Node embeddings stored as vector store documents</li>
 *   <li>Graph structure (adjacency matrices) stored as serialized metadata</li>
 *   <li>Similarity search for finding related nodes</li>
 *   <li>Persistence and reconstruction of full graph state</li>
 * </ul>
 * </p>
 * <p>
 * Document ID format:
 * <ul>
 *   <li>Graph metadata: {@code graph:{graphId}:meta}</li>
 *   <li>Node: {@code graph:{graphId}:node:{nodeId}}</li>
 *   <li>Adjacency matrix: {@code graph:{graphId}:adj:{edgeType}}</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorStoreMatrixGraphStore implements MatrixGraphStore {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    /**
     * In-memory cache of loaded graphs.
     */
    private final Map<String, AdjacencyMatrixGraph> graphCache = new ConcurrentHashMap<>();

    /**
     * Prefix for graph-related documents in the vector store.
     */
    private static final String GRAPH_PREFIX = "graph:";
    private static final String META_SUFFIX = ":meta";
    private static final String NODE_PREFIX = ":node:";
    private static final String ADJ_PREFIX = ":adj:";

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, 1024);
        graph.setFactSheetId(factSheetId);
        graphCache.put(graphId, graph);

        // Persist initial graph metadata
        try {
            saveGraphMetadata(graph);
        } catch (IOException e) {
            log.error("Failed to save initial graph metadata for {}", graphId, e);
        }

        return graph;
    }

    @Override
    public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
        // Check cache first
        AdjacencyMatrixGraph cached = graphCache.get(graphId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Load from vector store
        try {
            return loadGraphFromVectorStore(graphId);
        } catch (Exception e) {
            log.error("Failed to load graph {}", graphId, e);
            return Optional.empty();
        }
    }

    @Override
    public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        String graphId = graph.getGraphId();
        log.info("Saving graph {} with {} nodes", graphId, graph.getNodeCount());

        // Save metadata
        saveGraphMetadata(graph);

        // Save nodes with embeddings
        saveNodes(graph);

        // Save adjacency matrices
        saveAdjacencyMatrices(graph);

        // Update cache
        graphCache.put(graphId, graph);

        // Commit to vector store
        vectorStore.flushAndCommit();
    }

    @Override
    public boolean deleteGraph(String graphId) {
        try {
            // Remove from cache
            AdjacencyMatrixGraph graph = graphCache.remove(graphId);
            if (graph != null) {
                graph.close();
            }

            // Delete all documents with this graph prefix
            List<String> idsToDelete = new ArrayList<>();
            idsToDelete.add(GRAPH_PREFIX + graphId + META_SUFFIX);

            // Find and delete all node documents
            List<Map<String, Object>> docs = vectorStore.listVectorDocuments(0, 10000);
            for (Map<String, Object> doc : docs) {
                String docId = (String) doc.get("id");
                if (docId != null && docId.startsWith(GRAPH_PREFIX + graphId)) {
                    idsToDelete.add(docId);
                }
            }

            if (!idsToDelete.isEmpty()) {
                vectorStore.delete(idsToDelete);
                vectorStore.flushAndCommit();
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to delete graph {}", graphId, e);
            return false;
        }
    }

    @Override
    public List<String> listGraphs() {
        List<String> graphIds = new ArrayList<>();
        try {
            List<Map<String, Object>> docs = vectorStore.listVectorDocuments(0, 10000);
            for (Map<String, Object> doc : docs) {
                String docId = (String) doc.get("id");
                if (docId != null && docId.startsWith(GRAPH_PREFIX) && docId.endsWith(META_SUFFIX)) {
                    String graphId = docId.substring(GRAPH_PREFIX.length(),
                            docId.length() - META_SUFFIX.length());
                    graphIds.add(graphId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list graphs", e);
        }
        return graphIds;
    }

    @Override
    public List<String> listGraphsByFactSheet(Long factSheetId) {
        return listGraphs().stream()
                .filter(graphId -> {
                    Optional<AdjacencyMatrixGraph> graph = loadGraph(graphId);
                    return graph.isPresent() &&
                            Objects.equals(graph.get().getFactSheetId(), factSheetId);
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int index = graph.addNode(node);

        // Persist node
        try {
            saveNode(graphId, node);
        } catch (Exception e) {
            log.error("Failed to persist node {} in graph {}", node.getNodeId(), graphId, e);
        }

        return index;
    }

    @Override
    public void updateNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            graph.addNode(node); // addNode handles updates
        }

        try {
            saveNode(graphId, node);
        } catch (Exception e) {
            log.error("Failed to update node {} in graph {}", node.getNodeId(), graphId, e);
        }
    }

    @Override
    public boolean removeNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            graph.removeNode(nodeId);
        }

        String docId = GRAPH_PREFIX + graphId + NODE_PREFIX + nodeId;
        return vectorStore.delete(List.of(docId));
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNode(nodeId);
        }

        // Load from vector store
        return loadGraph(graphId).flatMap(g -> g.getNode(nodeId));
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getAllNodes();
        }

        return loadGraph(graphId)
                .map(AdjacencyMatrixGraph::getAllNodes)
                .orElse(Collections.emptyList());
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        // Use vector store similarity search
        List<Document> results = vectorStore.similaritySearch(query, limit);

        String nodePrefix = GRAPH_PREFIX + graphId + NODE_PREFIX;
        return results.stream()
                .filter(doc -> doc.getId() != null && doc.getId().startsWith(nodePrefix))
                .map(doc -> deserializeNode(doc))
                .filter(Objects::nonNull)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                          double weight, String edgeType, boolean bidirectional) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
    }

    @Override
    public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.removeEdge(sourceNodeId, targetNodeId, edgeType);
        }
        return false;
    }

    @Override
    public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNeighbors(nodeId, edgeType);
        }

        return loadGraph(graphId)
                .map(g -> g.getNeighbors(nodeId, edgeType))
                .orElse(Collections.emptyList());
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.hasEdge(sourceNodeId, targetNodeId, edgeType);
        }

        return loadGraph(graphId)
                .map(g -> g.hasEdge(sourceNodeId, targetNodeId, edgeType))
                .orElse(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        graph.setNodeEmbeddings(nodeIds, embeddings);

        // Store embeddings in vector store
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            Optional<MatrixGraphNode> nodeOpt = graph.getNode(nodeId);
            if (nodeOpt.isPresent()) {
                MatrixGraphNode node = nodeOpt.get();
                Document doc = createNodeDocument(graphId, node);
                documents.add(doc);
            }
        }

        if (!documents.isEmpty()) {
            vectorStore.addWithEmbeddings(documents, embeddings);
            vectorStore.flushAndCommit();
        }
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null && graph.getNodeEmbeddings() != null) {
            int dim = graph.getEmbeddingDimension();
            INDArray result = Nd4j.zeros(DataType.FLOAT, nodeIds.size(), dim);

            for (int i = 0; i < nodeIds.size(); i++) {
                INDArray embedding = graph.getNodeEmbedding(nodeIds.get(i));
                if (embedding != null) {
                    result.putRow(i, embedding);
                }
            }
            return result;
        }
        return null;
    }

    @Override
    public List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                             int k, double threshold) {
        List<ScoredDocument> results = vectorStore.similaritySearchWithScores(queryEmbedding, k, threshold);

        String nodePrefix = GRAPH_PREFIX + graphId + NODE_PREFIX;
        return results.stream()
                .filter(sd -> sd.document().getId() != null &&
                        sd.document().getId().startsWith(nodePrefix))
                .map(sd -> {
                    String nodeId = sd.document().getId().substring(nodePrefix.length());
                    return new AbstractMap.SimpleEntry<>(nodeId, sd.score());
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;

        List<Document> documents = new ArrayList<>();
        for (MatrixGraphNode node : nodes) {
            graph.addNode(node);
            documents.add(createNodeDocument(graphId, node));
            count++;
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }

        return count;
    }

    @Override
    public int addEdgesBatch(String graphId, List<EdgeDefinition> edges) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;

        for (EdgeDefinition edge : edges) {
            if (graph.addEdge(edge.sourceNodeId(), edge.targetNodeId(),
                    edge.weight(), edge.edgeType(), edge.bidirectional())) {
                count++;
            }
        }

        return count;
    }

    @Override
    public void flush() {
        // Save all cached graphs
        for (AdjacencyMatrixGraph graph : graphCache.values()) {
            try {
                saveGraph(graph);
            } catch (IOException e) {
                log.error("Failed to flush graph {}", graph.getGraphId(), e);
            }
        }
        vectorStore.flushAndCommit();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getGraphStatistics(String graphId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getStatistics();
        }

        return loadGraph(graphId)
                .map(AdjacencyMatrixGraph::getStatistics)
                .orElse(Collections.emptyMap());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private AdjacencyMatrixGraph getOrCreateGraph(String graphId) {
        return graphCache.computeIfAbsent(graphId, id -> {
            Optional<AdjacencyMatrixGraph> loaded = loadGraph(id);
            return loaded.orElseGet(() -> new AdjacencyMatrixGraph(id, 1024));
        });
    }

    private void saveGraphMetadata(AdjacencyMatrixGraph graph) throws IOException {
        String docId = GRAPH_PREFIX + graph.getGraphId() + META_SUFFIX;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graph.getGraphId());
        metadata.put("factSheetId", graph.getFactSheetId());
        metadata.put("nodeCount", graph.getNodeCount());
        metadata.put("edgeTypes", new ArrayList<>(graph.getEdgeTypes()));
        metadata.put("capacity", graph.getCurrentCapacity());
        metadata.put("embeddingDimension", graph.getEmbeddingDimension());
        metadata.put("type", "graph_metadata");

        Document doc = new Document(docId, objectMapper.writeValueAsString(metadata), metadata);
        vectorStore.add(List.of(doc));
    }

    private void saveNodes(AdjacencyMatrixGraph graph) {
        List<Document> documents = new ArrayList<>();
        INDArray embeddings = graph.getNodeEmbeddings();

        for (MatrixGraphNode node : graph.getAllNodes()) {
            documents.add(createNodeDocument(graph.getGraphId(), node));
        }

        if (!documents.isEmpty()) {
            if (embeddings != null && embeddings.rows() >= documents.size()) {
                vectorStore.addWithEmbeddings(documents, embeddings);
            } else {
                vectorStore.add(documents);
            }
        }
    }

    private void saveNode(String graphId, MatrixGraphNode node) {
        Document doc = createNodeDocument(graphId, node);
        vectorStore.add(List.of(doc));
    }

    private Document createNodeDocument(String graphId, MatrixGraphNode node) {
        String docId = GRAPH_PREFIX + graphId + NODE_PREFIX + node.getNodeId();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", node.getNodeId());
        metadata.put("matrixIndex", node.getMatrixIndex());
        metadata.put("nodeType", node.getNodeType());
        metadata.put("title", node.getTitle());
        metadata.put("description", node.getDescription());
        metadata.put("factSheetId", node.getFactSheetId());
        metadata.put("createdAt", node.getCreatedAt());
        metadata.put("updatedAt", node.getUpdatedAt());
        metadata.put("type", "graph_node");

        if (node.getMetadata() != null) {
            metadata.put("nodeMetadata", node.getMetadata());
        }

        String content = String.format("%s: %s",
                node.getTitle() != null ? node.getTitle() : node.getNodeId(),
                node.getDescription() != null ? node.getDescription() : "");

        return new Document(docId, content, metadata);
    }

    private void saveAdjacencyMatrices(AdjacencyMatrixGraph graph) throws IOException {
        // Serialize each adjacency matrix as a sparse representation
        for (String edgeType : graph.getEdgeTypes()) {
            INDArray adj = graph.getAdjacencyMatrices().get(edgeType);
            if (adj != null) {
                saveAdjacencyMatrix(graph.getGraphId(), edgeType, adj, graph.getNodeCount());
            }
        }
    }

    private void saveAdjacencyMatrix(String graphId, String edgeType, INDArray matrix, int nodeCount)
            throws IOException {
        String docId = GRAPH_PREFIX + graphId + ADJ_PREFIX + edgeType;

        // Store as sparse representation (list of non-zero entries)
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            for (int j = 0; j < nodeCount; j++) {
                double weight = matrix.getDouble(i, j);
                if (weight > 0) {
                    Map<String, Object> edge = new HashMap<>();
                    edge.put("source", i);
                    edge.put("target", j);
                    edge.put("weight", weight);
                    edges.add(edge);
                }
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graphId);
        metadata.put("edgeType", edgeType);
        metadata.put("edgeCount", edges.size());
        metadata.put("type", "adjacency_matrix");

        String content = objectMapper.writeValueAsString(edges);
        Document doc = new Document(docId, content, metadata);
        vectorStore.add(List.of(doc));
    }

    private Optional<AdjacencyMatrixGraph> loadGraphFromVectorStore(String graphId) {
        // Find all documents for this graph
        List<Map<String, Object>> docs = vectorStore.listVectorDocuments(0, 100000);

        String graphPrefix = GRAPH_PREFIX + graphId;

        // Find metadata document
        Map<String, Object> metaDoc = null;
        List<Map<String, Object>> nodeDocs = new ArrayList<>();
        List<Map<String, Object>> adjDocs = new ArrayList<>();

        for (Map<String, Object> doc : docs) {
            String docId = (String) doc.get("id");
            if (docId == null || !docId.startsWith(graphPrefix)) {
                continue;
            }

            String type = (String) doc.get("type");
            if ("graph_metadata".equals(type)) {
                metaDoc = doc;
            } else if ("graph_node".equals(type)) {
                nodeDocs.add(doc);
            } else if ("adjacency_matrix".equals(type)) {
                adjDocs.add(doc);
            }
        }

        if (metaDoc == null) {
            return Optional.empty();
        }

        // Reconstruct graph
        int capacity = (Integer) metaDoc.getOrDefault("capacity", 1024);
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, capacity);

        Object factSheetIdObj = metaDoc.get("factSheetId");
        if (factSheetIdObj instanceof Number) {
            graph.setFactSheetId(((Number) factSheetIdObj).longValue());
        }

        // Add nodes
        for (Map<String, Object> nodeDoc : nodeDocs) {
            MatrixGraphNode node = deserializeNodeFromMetadata(nodeDoc);
            if (node != null) {
                graph.addNode(node);
            }
        }

        // Restore adjacency matrices
        for (Map<String, Object> adjDoc : adjDocs) {
            restoreAdjacencyMatrix(graph, adjDoc);
        }

        graphCache.put(graphId, graph);
        return Optional.of(graph);
    }

    private MatrixGraphNode deserializeNode(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        return deserializeNodeFromMetadata(doc.getMetadata());
    }

    @SuppressWarnings("unchecked")
    private MatrixGraphNode deserializeNodeFromMetadata(Map<String, Object> metadata) {
        try {
            MatrixGraphNode.MatrixGraphNodeBuilder builder = MatrixGraphNode.builder()
                    .nodeId((String) metadata.get("nodeId"))
                    .nodeType((String) metadata.get("nodeType"))
                    .title((String) metadata.get("title"))
                    .description((String) metadata.get("description"));

            Object matrixIndex = metadata.get("matrixIndex");
            if (matrixIndex instanceof Number) {
                builder.matrixIndex(((Number) matrixIndex).intValue());
            }

            Object factSheetId = metadata.get("factSheetId");
            if (factSheetId instanceof Number) {
                builder.factSheetId(((Number) factSheetId).longValue());
            }

            Object createdAt = metadata.get("createdAt");
            if (createdAt instanceof Number) {
                builder.createdAt(((Number) createdAt).longValue());
            }

            Object updatedAt = metadata.get("updatedAt");
            if (updatedAt instanceof Number) {
                builder.updatedAt(((Number) updatedAt).longValue());
            }

            Object nodeMetadata = metadata.get("nodeMetadata");
            if (nodeMetadata instanceof Map) {
                builder.metadata((Map<String, Object>) nodeMetadata);
            }

            return builder.build();
        } catch (Exception e) {
            log.error("Failed to deserialize node", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreAdjacencyMatrix(AdjacencyMatrixGraph graph, Map<String, Object> adjDoc) {
        try {
            String edgeType = (String) adjDoc.get("edgeType");
            Object content = adjDoc.get("content");

            if (content instanceof String) {
                List<Map<String, Object>> edges = objectMapper.readValue(
                        (String) content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );

                for (Map<String, Object> edge : edges) {
                    int source = ((Number) edge.get("source")).intValue();
                    int target = ((Number) edge.get("target")).intValue();
                    double weight = ((Number) edge.get("weight")).doubleValue();

                    // Find node IDs by matrix index
                    String sourceId = graph.getIndexToNodeId().get(source);
                    String targetId = graph.getIndexToNodeId().get(target);

                    if (sourceId != null && targetId != null) {
                        graph.addEdge(sourceId, targetId, weight, edgeType, false);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to restore adjacency matrix", e);
        }
    }
}
