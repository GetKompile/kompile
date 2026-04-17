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
package ai.kompile.knowledgegraph.matrix.integration;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of MatrixGraphStore for integration testing.
 * Stores all graphs in memory without persistence.
 */
public class InMemoryMatrixGraphStore implements MatrixGraphStore {

    private final Map<String, AdjacencyMatrixGraph> graphs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, INDArray>> nodeEmbeddings = new ConcurrentHashMap<>();

    @Override
    public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, 1024);
        graph.setFactSheetId(factSheetId);
        graphs.put(graphId, graph);
        return graph;
    }

    @Override
    public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
        return Optional.ofNullable(graphs.get(graphId));
    }

    @Override
    public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        graphs.put(graph.getGraphId(), graph);
    }

    @Override
    public boolean deleteGraph(String graphId) {
        AdjacencyMatrixGraph removed = graphs.remove(graphId);
        nodeEmbeddings.remove(graphId);
        if (removed != null) {
            removed.close();
            return true;
        }
        return false;
    }

    @Override
    public List<String> listGraphs() {
        return new ArrayList<>(graphs.keySet());
    }

    @Override
    public List<String> listGraphsByFactSheet(Long factSheetId) {
        return graphs.values().stream()
                .filter(g -> Objects.equals(g.getFactSheetId(), factSheetId))
                .map(AdjacencyMatrixGraph::getGraphId)
                .collect(Collectors.toList());
    }

    @Override
    public int addNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addNode(node);
    }

    @Override
    public void updateNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            graph.addNode(node);
        }
    }

    @Override
    public boolean removeNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.removeNode(nodeId);
        }
        return false;
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.getNode(nodeId);
        }
        return Optional.empty();
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.getAllNodes();
        }
        return Collections.emptyList();
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph == null) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();
        return graph.getAllNodes().stream()
                .filter(node -> {
                    String title = node.getTitle() != null ? node.getTitle().toLowerCase() : "";
                    String desc = node.getDescription() != null ? node.getDescription().toLowerCase() : "";
                    return title.contains(lowerQuery) || desc.contains(lowerQuery);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
    }

    @Override
    public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.removeEdge(sourceNodeId, targetNodeId, edgeType);
        }
        return false;
    }

    @Override
    public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.getNeighbors(nodeId, edgeType);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.hasEdge(sourceNodeId, targetNodeId, edgeType);
        }
        return false;
    }

    @Override
    public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        graph.setNodeEmbeddings(nodeIds, embeddings);

        // Also store in our local cache for similarity search
        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.computeIfAbsent(graphId, k -> new ConcurrentHashMap<>());
        for (int i = 0; i < nodeIds.size() && i < embeddings.rows(); i++) {
            graphEmbeddings.put(nodeIds.get(i), embeddings.getRow(i).dup());
        }
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.get(graphId);
        if (graphEmbeddings == null || graphEmbeddings.isEmpty()) {
            return null;
        }

        int dim = (int) graphEmbeddings.values().iterator().next().length();
        INDArray result = Nd4j.zeros(nodeIds.size(), dim);

        for (int i = 0; i < nodeIds.size(); i++) {
            INDArray embedding = graphEmbeddings.get(nodeIds.get(i));
            if (embedding != null) {
                result.putRow(i, embedding);
            }
        }

        return result;
    }

    @Override
    public List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                              int k, double threshold) {
        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.get(graphId);
        if (graphEmbeddings == null || graphEmbeddings.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Double>> results = new ArrayList<>();

        for (Map.Entry<String, INDArray> entry : graphEmbeddings.entrySet()) {
            double similarity = cosineSimilarity(queryEmbedding, entry.getValue());
            if (similarity >= threshold) {
                results.add(new AbstractMap.SimpleEntry<>(entry.getKey(), similarity));
            }
        }

        // Sort by similarity descending and take top k
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .collect(Collectors.toList());
    }

    private double cosineSimilarity(INDArray a, INDArray b) {
        double dotProduct = Nd4j.getBlasWrapper().dot(a, b);
        double normA = a.norm2Number().doubleValue();
        double normB = b.norm2Number().doubleValue();
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dotProduct / (normA * normB);
    }

    @Override
    public int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;
        for (MatrixGraphNode node : nodes) {
            graph.addNode(node);
            count++;
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
        // No-op for in-memory store
    }

    @Override
    public Map<String, Object> getGraphStatistics(String graphId) {
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            return graph.getStatistics();
        }
        return Collections.emptyMap();
    }

    private AdjacencyMatrixGraph getOrCreateGraph(String graphId) {
        return graphs.computeIfAbsent(graphId, id -> new AdjacencyMatrixGraph(id, 1024));
    }

    /**
     * Clears all stored graphs. Useful for test cleanup.
     */
    public void clearAll() {
        for (AdjacencyMatrixGraph graph : graphs.values()) {
            graph.close();
        }
        graphs.clear();
        nodeEmbeddings.clear();
    }
}
