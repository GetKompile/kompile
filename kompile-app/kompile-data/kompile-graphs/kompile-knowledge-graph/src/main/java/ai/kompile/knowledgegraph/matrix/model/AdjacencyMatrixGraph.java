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
package ai.kompile.knowledgegraph.matrix.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Matrix-based graph representation using ND4J for efficient graph operations.
 * <p>
 * This class uses an adjacency matrix stored as an INDArray, enabling efficient
 * matrix operations for graph algorithms like PageRank, random walks, and similarity computations.
 * </p>
 * <p>
 * The graph supports:
 * <ul>
 *   <li>Weighted edges via the adjacency matrix values</li>
 *   <li>Multiple edge types via separate adjacency matrices per type</li>
 *   <li>Node embeddings stored as a separate matrix</li>
 *   <li>Dynamic resizing as nodes are added</li>
 * </ul>
 * </p>
 */
@Data
@Slf4j
public class AdjacencyMatrixGraph implements AutoCloseable {

    /**
     * Unique identifier for this graph.
     */
    private String graphId;

    /**
     * Optional fact sheet ID for scoping.
     */
    private Long factSheetId;

    /**
     * Map from node ID to node data.
     */
    private final Map<String, MatrixGraphNode> nodeById;

    /**
     * Map from matrix index to node ID for reverse lookup.
     */
    private final Map<Integer, String> indexToNodeId;

    /**
     * Map from edge type to adjacency matrix.
     * Each adjacency matrix is of shape [numNodes, numNodes].
     */
    private final Map<String, INDArray> adjacencyMatrices;

    /**
     * Node embeddings matrix of shape [numNodes, embeddingDim].
     * Can be null if embeddings are not stored in the graph.
     */
    private INDArray nodeEmbeddings;

    /**
     * Dimension of node embeddings.
     */
    private int embeddingDimension;

    /**
     * Counter for assigning matrix indices to new nodes.
     */
    private final AtomicInteger nextIndex;

    /**
     * Current capacity of the adjacency matrices.
     */
    private int currentCapacity;

    /**
     * Default initial capacity for the matrices.
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 1024;

    /**
     * Growth factor when resizing matrices.
     */
    private static final double GROWTH_FACTOR = 1.5;

    /**
     * Default edge type for untyped edges.
     */
    public static final String DEFAULT_EDGE_TYPE = "RELATED_TO";

    /**
     * Creates a new empty graph with default capacity.
     */
    public AdjacencyMatrixGraph() {
        this(UUID.randomUUID().toString(), DEFAULT_INITIAL_CAPACITY);
    }

    /**
     * Creates a new empty graph with specified capacity.
     *
     * @param graphId  Unique identifier for the graph
     * @param capacity Initial capacity for the adjacency matrix
     */
    public AdjacencyMatrixGraph(String graphId, int capacity) {
        this.graphId = graphId;
        this.currentCapacity = capacity;
        this.nodeById = new ConcurrentHashMap<>();
        this.indexToNodeId = new ConcurrentHashMap<>();
        this.adjacencyMatrices = new ConcurrentHashMap<>();
        this.nextIndex = new AtomicInteger(0);
        this.embeddingDimension = 0;
    }

    /**
     * Adds a node to the graph.
     *
     * @param node The node to add
     * @return The matrix index assigned to the node
     */
    public int addNode(MatrixGraphNode node) {
        if (nodeById.containsKey(node.getNodeId())) {
            // Update existing node
            MatrixGraphNode existing = nodeById.get(node.getNodeId());
            existing.setTitle(node.getTitle());
            existing.setDescription(node.getDescription());
            existing.setNodeType(node.getNodeType());
            existing.setMetadata(node.getMetadata());
            existing.setUpdatedAt(System.currentTimeMillis());
            return existing.getMatrixIndex();
        }

        int index = nextIndex.getAndIncrement();
        if (index >= currentCapacity) {
            expandCapacity();
        }

        node.setMatrixIndex(index);
        node.setCreatedAt(System.currentTimeMillis());
        node.setUpdatedAt(System.currentTimeMillis());
        nodeById.put(node.getNodeId(), node);
        indexToNodeId.put(index, node.getNodeId());

        return index;
    }

    /**
     * Adds an edge between two nodes.
     *
     * @param sourceNodeId Source node ID
     * @param targetNodeId Target node ID
     * @param weight       Edge weight (typically 0.0 to 1.0)
     * @param edgeType     Type of the edge
     * @param bidirectional Whether the edge should be bidirectional
     * @return true if the edge was added successfully
     */
    public boolean addEdge(String sourceNodeId, String targetNodeId, double weight,
                          String edgeType, boolean bidirectional) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);

        if (source == null || target == null) {
            log.warn("Cannot add edge: source or target node not found. Source: {}, Target: {}",
                    sourceNodeId, targetNodeId);
            return false;
        }

        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        INDArray adjacency = getOrCreateAdjacencyMatrix(type);

        int srcIdx = source.getMatrixIndex();
        int tgtIdx = target.getMatrixIndex();

        adjacency.putScalar(srcIdx, tgtIdx, weight);
        if (bidirectional) {
            adjacency.putScalar(tgtIdx, srcIdx, weight);
        }

        return true;
    }

    /**
     * Gets or creates an adjacency matrix for the specified edge type.
     */
    private synchronized INDArray getOrCreateAdjacencyMatrix(String edgeType) {
        return adjacencyMatrices.computeIfAbsent(edgeType,
            k -> Nd4j.zeros(DataType.FLOAT, currentCapacity, currentCapacity));
    }

    /**
     * Gets the edge weight between two nodes.
     *
     * @param sourceNodeId Source node ID
     * @param targetNodeId Target node ID
     * @param edgeType     Edge type (null for default)
     * @return The edge weight, or 0.0 if no edge exists
     */
    public double getEdgeWeight(String sourceNodeId, String targetNodeId, String edgeType) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);

        if (source == null || target == null) {
            return 0.0;
        }

        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        INDArray adjacency = adjacencyMatrices.get(type);

        if (adjacency == null) {
            return 0.0;
        }

        return adjacency.getDouble(source.getMatrixIndex(), target.getMatrixIndex());
    }

    /**
     * Updates an existing edge weight.
     */
    public boolean updateEdgeWeight(String sourceNodeId, String targetNodeId,
                                   double weight, String edgeType) {
        return addEdge(sourceNodeId, targetNodeId, weight, edgeType, false);
    }

    /**
     * Removes an edge between two nodes.
     */
    public boolean removeEdge(String sourceNodeId, String targetNodeId, String edgeType) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);

        if (source == null || target == null) {
            return false;
        }

        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        INDArray adjacency = adjacencyMatrices.get(type);

        if (adjacency == null) {
            return false;
        }

        adjacency.putScalar(source.getMatrixIndex(), target.getMatrixIndex(), 0.0);
        return true;
    }

    /**
     * Gets all neighbors of a node (outgoing edges).
     *
     * @param nodeId   The node ID
     * @param edgeType Edge type (null for all types)
     * @return List of neighbor node IDs with their edge weights
     */
    public List<Map.Entry<String, Double>> getNeighbors(String nodeId, String edgeType) {
        MatrixGraphNode node = nodeById.get(nodeId);
        if (node == null) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Double>> neighbors = new ArrayList<>();
        int nodeIndex = node.getMatrixIndex();

        Collection<INDArray> matrices;
        if (edgeType != null) {
            INDArray adj = adjacencyMatrices.get(edgeType);
            matrices = adj != null ? Collections.singleton(adj) : Collections.emptyList();
        } else {
            matrices = adjacencyMatrices.values();
        }

        for (INDArray adjacency : matrices) {
            for (int i = 0; i < getNodeCount(); i++) {
                double weight = adjacency.getDouble(nodeIndex, i);
                if (weight > 0) {
                    String neighborId = indexToNodeId.get(i);
                    if (neighborId != null) {
                        neighbors.add(new AbstractMap.SimpleEntry<>(neighborId, weight));
                    }
                }
            }
        }

        return neighbors;
    }

    /**
     * Gets the combined adjacency matrix summing all edge types.
     */
    public INDArray getCombinedAdjacencyMatrix() {
        if (adjacencyMatrices.isEmpty()) {
            return Nd4j.zeros(DataType.FLOAT, currentCapacity, currentCapacity);
        }

        INDArray combined = Nd4j.zeros(DataType.FLOAT, currentCapacity, currentCapacity);
        for (INDArray adj : adjacencyMatrices.values()) {
            combined.addi(adj);
        }
        return combined;
    }

    /**
     * Sets node embeddings from an external source.
     *
     * @param nodeIds    List of node IDs in order
     * @param embeddings INDArray of shape [numNodes, embeddingDim]
     */
    public void setNodeEmbeddings(List<String> nodeIds, INDArray embeddings) {
        if (embeddings.rank() != 2) {
            throw new IllegalArgumentException("Embeddings must be 2D array [numNodes, embeddingDim]");
        }

        this.embeddingDimension = (int) embeddings.columns();

        // Initialize or resize embeddings matrix
        if (nodeEmbeddings == null || nodeEmbeddings.rows() < currentCapacity) {
            if (nodeEmbeddings != null && !nodeEmbeddings.wasClosed()) {
                nodeEmbeddings.close();
            }
            nodeEmbeddings = Nd4j.zeros(DataType.FLOAT, currentCapacity, embeddingDimension);
        }

        // Copy embeddings for each node
        for (int i = 0; i < nodeIds.size() && i < embeddings.rows(); i++) {
            String nodeId = nodeIds.get(i);
            MatrixGraphNode node = nodeById.get(nodeId);
            if (node != null) {
                INDArray embedding = embeddings.getRow(i);
                nodeEmbeddings.putRow(node.getMatrixIndex(), embedding);
            }
        }
    }

    /**
     * Gets the embedding for a specific node.
     */
    public INDArray getNodeEmbedding(String nodeId) {
        MatrixGraphNode node = nodeById.get(nodeId);
        if (node == null || nodeEmbeddings == null) {
            return null;
        }
        return nodeEmbeddings.getRow(node.getMatrixIndex());
    }

    /**
     * Expands the capacity of the adjacency matrices.
     */
    private synchronized void expandCapacity() {
        int newCapacity = (int) (currentCapacity * GROWTH_FACTOR);
        log.info("Expanding graph capacity from {} to {}", currentCapacity, newCapacity);

        // Resize each adjacency matrix
        for (Map.Entry<String, INDArray> entry : adjacencyMatrices.entrySet()) {
            INDArray oldMatrix = entry.getValue();
            INDArray newMatrix = Nd4j.zeros(DataType.FLOAT, newCapacity, newCapacity);

            // Copy old data using vectorized submatrix assignment (single operation vs n^2 putScalar calls)
            newMatrix.put(
                    new INDArrayIndex[]{NDArrayIndex.interval(0, currentCapacity), NDArrayIndex.interval(0, currentCapacity)},
                    oldMatrix
            );

            entry.setValue(newMatrix);
            if (!oldMatrix.wasClosed()) {
                oldMatrix.close();
            }
        }

        // Resize embeddings if present
        if (nodeEmbeddings != null) {
            INDArray oldEmbeddings = nodeEmbeddings;
            nodeEmbeddings = Nd4j.zeros(DataType.FLOAT, newCapacity, embeddingDimension);
            for (int i = 0; i < currentCapacity; i++) {
                nodeEmbeddings.putRow(i, oldEmbeddings.getRow(i));
            }
            if (!oldEmbeddings.wasClosed()) {
                oldEmbeddings.close();
            }
        }

        currentCapacity = newCapacity;
    }

    /**
     * Gets the number of nodes in the graph.
     */
    public int getNodeCount() {
        return nextIndex.get();
    }

    /**
     * Gets the number of edges in the graph (across all types).
     */
    public long getEdgeCount() {
        long count = 0;
        int nodeCount = getNodeCount();
        if (nodeCount == 0) {
            return 0;
        }
        for (INDArray adj : adjacencyMatrices.values()) {
            // Get submatrix of actual nodes and count non-zero entries (vectorized)
            INDArray subMatrix = adj.get(
                    NDArrayIndex.interval(0, nodeCount),
                    NDArrayIndex.interval(0, nodeCount)
            );
            // gt(0) creates boolean mask, castTo converts to countable, sumNumber sums all
            count += subMatrix.gt(0).castTo(DataType.LONG).sumNumber().longValue();
        }
        return count;
    }

    /**
     * Gets all edge types in the graph.
     */
    public Set<String> getEdgeTypes() {
        return new HashSet<>(adjacencyMatrices.keySet());
    }

    /**
     * Gets a node by its ID.
     */
    public Optional<MatrixGraphNode> getNode(String nodeId) {
        return Optional.ofNullable(nodeById.get(nodeId));
    }

    /**
     * Gets all nodes in the graph.
     */
    public List<MatrixGraphNode> getAllNodes() {
        return new ArrayList<>(nodeById.values());
    }

    /**
     * Gets nodes by type.
     */
    public List<MatrixGraphNode> getNodesByType(String nodeType) {
        return nodeById.values().stream()
                .filter(n -> nodeType.equals(n.getNodeType()))
                .collect(Collectors.toList());
    }

    /**
     * Removes a node and all its edges.
     */
    public boolean removeNode(String nodeId) {
        MatrixGraphNode node = nodeById.remove(nodeId);
        if (node == null) {
            return false;
        }

        int idx = node.getMatrixIndex();
        indexToNodeId.remove(idx);

        // Clear all edges for this node using vectorized operations
        INDArray zeroRow = Nd4j.zeros(DataType.FLOAT, 1, currentCapacity);
        INDArray zeroCol = Nd4j.zeros(DataType.FLOAT, currentCapacity, 1);
        for (INDArray adj : adjacencyMatrices.values()) {
            // Clear row (outgoing edges) - single operation vs currentCapacity putScalar calls
            adj.putRow(idx, zeroRow);
            // Clear column (incoming edges) - single operation vs currentCapacity putScalar calls
            adj.put(new INDArrayIndex[]{NDArrayIndex.all(), NDArrayIndex.point(idx)}, zeroCol);
        }
        zeroRow.close();
        zeroCol.close();

        // Clear embedding if present using vectorized operation
        if (nodeEmbeddings != null) {
            INDArray zeroEmbed = Nd4j.zeros(DataType.FLOAT, 1, embeddingDimension);
            nodeEmbeddings.putRow(idx, zeroEmbed);
            zeroEmbed.close();
        }

        return true;
    }

    /**
     * Checks if an edge exists between two nodes.
     */
    public boolean hasEdge(String sourceNodeId, String targetNodeId, String edgeType) {
        return getEdgeWeight(sourceNodeId, targetNodeId, edgeType) > 0;
    }

    /**
     * Gets the degree (number of connections) for a node.
     */
    public int getNodeDegree(String nodeId, String edgeType, boolean outgoing) {
        MatrixGraphNode node = nodeById.get(nodeId);
        if (node == null) {
            return 0;
        }

        int idx = node.getMatrixIndex();
        int degree = 0;
        int nodeCount = getNodeCount();

        Collection<INDArray> matrices;
        if (edgeType != null) {
            INDArray adj = adjacencyMatrices.get(edgeType);
            matrices = adj != null ? Collections.singleton(adj) : Collections.emptyList();
        } else {
            matrices = adjacencyMatrices.values();
        }

        for (INDArray adj : matrices) {
            for (int i = 0; i < nodeCount; i++) {
                double weight = outgoing ? adj.getDouble(idx, i) : adj.getDouble(i, idx);
                if (weight > 0) {
                    degree++;
                }
            }
        }

        return degree;
    }

    /**
     * Gets statistics about the graph.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("graphId", graphId);
        stats.put("nodeCount", getNodeCount());
        stats.put("edgeCount", getEdgeCount());
        stats.put("edgeTypes", getEdgeTypes());
        stats.put("capacity", currentCapacity);
        stats.put("hasEmbeddings", nodeEmbeddings != null);
        stats.put("embeddingDimension", embeddingDimension);

        // Node type distribution
        Map<String, Long> nodeTypeCounts = nodeById.values().stream()
                .collect(Collectors.groupingBy(
                        n -> n.getNodeType() != null ? n.getNodeType() : "UNKNOWN",
                        Collectors.counting()
                ));
        stats.put("nodeTypeCounts", nodeTypeCounts);

        return stats;
    }

    @Override
    public void close() {
        // Close all adjacency matrices
        for (INDArray adj : adjacencyMatrices.values()) {
            if (!adj.wasClosed()) {
                adj.close();
            }
        }
        adjacencyMatrices.clear();

        // Close embeddings
        if (nodeEmbeddings != null && !nodeEmbeddings.wasClosed()) {
            nodeEmbeddings.close();
            nodeEmbeddings = null;
        }
    }
}
