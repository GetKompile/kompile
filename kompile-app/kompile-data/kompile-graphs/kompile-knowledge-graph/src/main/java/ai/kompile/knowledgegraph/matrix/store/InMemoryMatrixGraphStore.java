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

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Heap-backed {@link MatrixGraphStore} with no persistence: every graph lives in a
 * {@link ConcurrentHashMap} for the lifetime of the instance.
 *
 * <p>Pair it with {@code MatrixKnowledgeGraphService} to get a fully functional
 * {@code KnowledgeGraphService} without Lucene, subprocesses, or the filesystem — the seam
 * test harnesses use to verify real graph persistence through the crawl pipeline, and a
 * building block for embedded/ephemeral graph use.</p>
 */
public class InMemoryMatrixGraphStore implements MatrixGraphStore {

    private final Map<String, AdjacencyMatrixGraph> graphs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, INDArray>> nodeEmbeddings = new ConcurrentHashMap<>();

    /**
     * Canonical per-edge records (insertion-ordered) so {@link #scanEdges} can report semantic
     * {@code relationType}/confidence/description the way the Lucene store does — the adjacency
     * matrix alone only knows the routing key.
     */
    private final Map<String, Map<String, StoredEdge>> storedEdges = new ConcurrentHashMap<>();

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
        storedEdges.remove(graphId);
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
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional,
                null, null, null);
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional,
                           String relationType) {
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional,
                relationType, null, null);
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional,
                           String relationType, Double confidence, String description) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        boolean added = graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
        recordEdge(graphId, new StoredEdge(sourceNodeId, targetNodeId, edgeType, weight,
                bidirectional, relationType, confidence, description, Map.of()));
        return added;
    }

    @Override
    public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        Map<String, StoredEdge> edges = storedEdges.get(graphId);
        if (edges != null) {
            edges.remove(edgeKey(sourceNodeId, targetNodeId, edgeType));
        }
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
            recordEdge(graphId, new StoredEdge(edge.sourceNodeId(), edge.targetNodeId(),
                    edge.edgeType(), edge.weight(), edge.bidirectional(), edge.relationType(),
                    null, null, Map.of()));
        }
        return count;
    }

    @Override
    public ScanPage<MatrixGraphNode> scanNodes(String graphId, int cursor, int pageSize) {
        return page(getAllNodes(graphId), cursor, pageSize);
    }

    @Override
    public ScanPage<StoredEdge> scanEdges(String graphId, int cursor, int pageSize) {
        Map<String, StoredEdge> edges = storedEdges.get(graphId);
        List<StoredEdge> all = edges == null ? List.of() : List.copyOf(edges.values());
        return page(all, cursor, pageSize);
    }

    private static <T> ScanPage<T> page(List<T> all, int cursor, int pageSize) {
        int from = Math.max(0, cursor);
        if (from >= all.size() || pageSize <= 0) {
            return new ScanPage<>(List.of(), all.size(), false);
        }
        int to = Math.min(all.size(), from + pageSize);
        return new ScanPage<>(all.subList(from, to), to, to < all.size());
    }

    private void recordEdge(String graphId, StoredEdge edge) {
        storedEdges.computeIfAbsent(graphId,
                        k -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(edgeKey(edge.sourceNodeId(), edge.targetNodeId(), edge.edgeType()), edge);
    }

    private static String edgeKey(String sourceNodeId, String targetNodeId, String edgeType) {
        return sourceNodeId + "|" + targetNodeId + "|" + edgeType;
    }

    @Override
    public void flush() {
        // Nothing buffered — every write is immediately visible.
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

    /** Drop every stored graph, edge record and embedding (frees the matrix buffers). */
    public void clearAll() {
        for (AdjacencyMatrixGraph graph : graphs.values()) {
            graph.close();
        }
        graphs.clear();
        nodeEmbeddings.clear();
        storedEdges.clear();
    }
}
