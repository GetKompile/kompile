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

import ai.kompile.knowledgegraph.generation.GraphGeneration;
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
import java.util.Set;
import java.util.HashSet;
import java.time.Instant;
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
    private final Map<String, GenerationPointer> generationPointers = new ConcurrentHashMap<>();
    private final Set<String> generationGraphs = ConcurrentHashMap.newKeySet();

    private record GenerationPointer(String active, String previous, long revision) { }

    @Override
    public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, 1024);
        graph.setFactSheetId(factSheetId);
        graphs.put(graphId, graph);
        return graph;
    }

    @Override
    public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
        return Optional.ofNullable(graphs.get(resolveGraphId(graphId)));
    }

    @Override
    public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        graphs.put(graph.getGraphId(), graph);
    }

    @Override
    public synchronized boolean deleteGraph(String graphId) {
        if (graphId.contains("~gen~") && generationPointers.values().stream()
                .anyMatch(pointer -> graphId.equals(pointer.active()) || graphId.equals(pointer.previous()))) {
            return false;
        }
        String resolved = resolveGraphId(graphId);
        GenerationPointer pointer = generationPointers.remove(graphId);
        Set<String> deleteIds = new HashSet<>();
        deleteIds.add(resolved);
        deleteIds.add(graphId);
        if (!graphId.contains("~gen~")) {
            String generationPrefix = graphId + "~gen~";
            graphs.keySet().stream().filter(id -> id.startsWith(generationPrefix)).forEach(deleteIds::add);
            nodeEmbeddings.keySet().stream().filter(id -> id.startsWith(generationPrefix)).forEach(deleteIds::add);
            storedEdges.keySet().stream().filter(id -> id.startsWith(generationPrefix)).forEach(deleteIds::add);
            generationGraphs.stream().filter(id -> id.startsWith(generationPrefix)).forEach(deleteIds::add);
        }
        if (pointer != null) deleteIds.add(pointer.active());
        if (pointer != null && pointer.previous() != null) deleteIds.add(pointer.previous());
        AdjacencyMatrixGraph removed = null;
        for (String id : deleteIds) {
            AdjacencyMatrixGraph candidate = graphs.remove(id);
            if (candidate != null) {
                candidate.close();
                removed = candidate;
            }
            nodeEmbeddings.remove(id);
            storedEdges.remove(id);
            generationGraphs.remove(id);
        }
        return removed != null;
    }

    @Override
    public List<String> listGraphs() {
        Set<String> visible = new HashSet<>();
        graphs.keySet().stream()
                .filter(id -> !generationGraphs.contains(id) && !id.contains("~gen~"))
                .forEach(visible::add);
        visible.addAll(generationPointers.keySet());
        return new ArrayList<>(visible);
    }

    @Override
    public List<String> listGraphsByFactSheet(Long factSheetId) {
        return listGraphs().stream()
                .filter(id -> loadGraph(id).map(g -> Objects.equals(g.getFactSheetId(), factSheetId)).orElse(false))
                .collect(Collectors.toList());
    }

    @Override
    public boolean supportsGraphGenerations() { return true; }

    @Override
    public synchronized GraphGeneration.Ref beginGeneration(
            long factSheetId, String logicalGraphId, String generationId) {
        Objects.requireNonNull(logicalGraphId, "logicalGraphId");
        Objects.requireNonNull(generationId, "generationId");
        GenerationPointer pointer = generationPointers.get(logicalGraphId);
        String active = pointer == null ? logicalGraphId : pointer.active();
        long revision = pointer == null ? 0L : pointer.revision();
        String physical = logicalGraphId + "~gen~" + generationId;
        if (graphs.containsKey(physical)) throw new IllegalStateException("Generation already exists: " + physical);
        generationGraphs.add(physical);
        createGraph(physical, factSheetId);
        return new GraphGeneration.Ref(factSheetId, logicalGraphId, physical, generationId, active, revision);
    }

    @Override
    public synchronized GraphGeneration.Validation validateGeneration(GraphGeneration.Ref generation) {
        AdjacencyMatrixGraph graph = graphs.get(generation.physicalGraphId());
        if (graph == null) {
            return new GraphGeneration.Validation(false, 0, 0, List.of("Generation does not exist"));
        }
        generationGraphs.add(generation.physicalGraphId());
        List<String> errors = new ArrayList<>();
        if (!Objects.equals(graph.getFactSheetId(), generation.factSheetId())) {
            errors.add("Generation fact sheet does not match its graph");
        }
        Map<String, StoredEdge> edges = storedEdges.getOrDefault(generation.physicalGraphId(), Map.of());
        for (StoredEdge edge : edges.values()) {
            if (graph.getNode(edge.sourceNodeId()).isEmpty() || graph.getNode(edge.targetNodeId()).isEmpty()) {
                errors.add("Dangling edge " + edge.sourceNodeId() + "->" + edge.targetNodeId());
            }
        }
        return new GraphGeneration.Validation(errors.isEmpty(), graph.getNodeCount(), edges.size(), errors);
    }

    @Override
    public synchronized GraphGeneration.Activation activateGeneration(GraphGeneration.Ref generation) {
        GraphGeneration.Validation validation = validateGeneration(generation);
        if (!validation.valid()) throw new IllegalStateException("Generation validation failed: " + validation.errors());
        GenerationPointer current = generationPointers.get(generation.logicalGraphId());
        String active = current == null ? generation.logicalGraphId() : current.active();
        long revision = current == null ? 0L : current.revision();
        if (!Objects.equals(active, generation.expectedActivePhysicalGraphId())
                || revision != generation.expectedRevision()) {
            throw new IllegalStateException("Graph generation activation conflict");
        }
        long nextRevision = revision + 1;
        generationPointers.put(generation.logicalGraphId(),
                new GenerationPointer(generation.physicalGraphId(), active, nextRevision));
        return new GraphGeneration.Activation(generation.logicalGraphId(), generation.physicalGraphId(),
                active, nextRevision, Instant.now());
    }

    @Override
    public synchronized void abortGeneration(GraphGeneration.Ref generation) {
        GenerationPointer pointer = generationPointers.get(generation.logicalGraphId());
        if (pointer != null && (generation.physicalGraphId().equals(pointer.active())
                || generation.physicalGraphId().equals(pointer.previous()))) {
            throw new IllegalStateException("Cannot abort an active or rollback-target generation");
        }
        deletePhysicalGraph(generation.physicalGraphId());
    }

    @Override
    public synchronized GraphGeneration.Activation rollbackGeneration(
            long factSheetId, String logicalGraphId, long expectedRevision) {
        GenerationPointer pointer = generationPointers.get(logicalGraphId);
        if (pointer == null || pointer.previous() == null || pointer.revision() != expectedRevision) {
            throw new IllegalStateException("Graph generation rollback conflict");
        }
        AdjacencyMatrixGraph target = graphs.get(pointer.previous());
        if (target == null || !Objects.equals(target.getFactSheetId(), factSheetId)) {
            throw new IllegalStateException("Graph generation rollback target is unavailable");
        }
        long nextRevision = pointer.revision() + 1;
        generationPointers.put(logicalGraphId,
                new GenerationPointer(pointer.previous(), pointer.active(), nextRevision));
        return new GraphGeneration.Activation(logicalGraphId, pointer.previous(), pointer.active(),
                nextRevision, Instant.now());
    }

    @Override
    public Optional<GraphGeneration.Pointer> currentGenerationPointer(
            long factSheetId, String logicalGraphId) {
        GenerationPointer pointer = generationPointers.get(logicalGraphId);
        return Optional.of(pointer == null
                ? new GraphGeneration.Pointer(factSheetId, logicalGraphId, logicalGraphId, null, 0L)
                : new GraphGeneration.Pointer(factSheetId, logicalGraphId,
                        pointer.active(), pointer.previous(), pointer.revision()));
    }

    @Override
    public boolean physicalGraphExists(String physicalGraphId, long factSheetId) {
        AdjacencyMatrixGraph graph = graphs.get(physicalGraphId);
        return graph != null && Objects.equals(graph.getFactSheetId(), factSheetId);
    }

    @Override
    public void repairGenerationPointer(GraphGeneration.Pointer pointer) {
        generationPointers.put(pointer.logicalGraphId(), new GenerationPointer(
                pointer.activePhysicalGraphId(), pointer.previousPhysicalGraphId(), pointer.revision()));
    }

    @Override
    public int addNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addNode(node);
    }

    @Override
    public void updateNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            graph.addNode(node);
        }
    }

    @Override
    public boolean removeNode(String graphId, String nodeId) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = graphs.get(graphId);
        if (graph != null) {
            boolean removed = graph.removeNode(nodeId);
            Map<String, StoredEdge> edges = storedEdges.get(graphId);
            if (edges != null) {
                edges.values().removeIf(edge -> nodeId.equals(edge.sourceNodeId())
                        || nodeId.equals(edge.targetNodeId()));
            }
            return removed;
        }
        return false;
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            return graph.getNode(nodeId);
        }
        return Optional.empty();
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            return graph.getAllNodes();
        }
        return Collections.emptyList();
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
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
        graphId = resolveGraphId(graphId);
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
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            return graph.getNeighbors(nodeId, edgeType);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            return graph.hasEdge(sourceNodeId, targetNodeId, edgeType);
        }
        return false;
    }

    @Override
    public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        graph.setNodeEmbeddings(nodeIds, embeddings);

        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.computeIfAbsent(graphId, k -> new ConcurrentHashMap<>());
        for (int i = 0; i < nodeIds.size() && i < embeddings.rows(); i++) {
            graphEmbeddings.put(nodeIds.get(i), embeddings.getRow(i).dup());
        }
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.get(resolveGraphId(graphId));
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
        Map<String, INDArray> graphEmbeddings = nodeEmbeddings.get(resolveGraphId(graphId));
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
        graphId = resolveGraphId(graphId);
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
        Map<String, StoredEdge> edges = storedEdges.get(resolveGraphId(graphId));
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
        graphId = resolveGraphId(graphId);
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
        AdjacencyMatrixGraph graph = graphs.get(resolveGraphId(graphId));
        if (graph != null) {
            return graph.getStatistics();
        }
        return Collections.emptyMap();
    }

    private AdjacencyMatrixGraph getOrCreateGraph(String graphId) {
        String resolved = resolveGraphId(graphId);
        return graphs.computeIfAbsent(resolved, id -> new AdjacencyMatrixGraph(id, 1024));
    }

    private String resolveGraphId(String graphId) {
        if (graphId == null || graphId.contains("~gen~") || generationGraphs.contains(graphId)) return graphId;
        GenerationPointer pointer = generationPointers.get(graphId);
        return pointer == null ? graphId : pointer.active();
    }

    private void deletePhysicalGraph(String graphId) {
        AdjacencyMatrixGraph graph = graphs.remove(graphId);
        if (graph != null) graph.close();
        nodeEmbeddings.remove(graphId);
        storedEdges.remove(graphId);
        generationGraphs.remove(graphId);
    }

    /** Drop every stored graph, edge record and embedding (frees the matrix buffers). */
    public void clearAll() {
        for (AdjacencyMatrixGraph graph : graphs.values()) {
            graph.close();
        }
        graphs.clear();
        nodeEmbeddings.clear();
        storedEdges.clear();
        generationPointers.clear();
        generationGraphs.clear();
    }
}
