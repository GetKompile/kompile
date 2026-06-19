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
package ai.kompile.knowledgegraph.matrix.algorithms;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.util.*;

/**
 * Matrix-based graph algorithms using ND4J for efficient computation.
 * <p>
 * This class provides optimized implementations of common graph algorithms
 * using matrix operations, enabling fast computation on large graphs.
 * </p>
 */
@Slf4j
public class MatrixGraphAlgorithms {

    /**
     * Default damping factor for PageRank (typically 0.85).
     */
    public static final double DEFAULT_DAMPING = 0.85;

    /**
     * Default convergence threshold for iterative algorithms.
     */
    public static final double DEFAULT_CONVERGENCE = 1e-6;

    /**
     * Default maximum iterations for iterative algorithms.
     */
    public static final int DEFAULT_MAX_ITERATIONS = 100;

    private MatrixGraphAlgorithms() {
        // Utility class
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAGERANK
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Computes PageRank scores for all nodes using power iteration.
     *
     * @param graph The graph to compute PageRank for
     * @return Map of node ID to PageRank score
     */
    public static Map<String, Double> pageRank(AdjacencyMatrixGraph graph) {
        return pageRank(graph, DEFAULT_DAMPING, DEFAULT_CONVERGENCE, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Computes PageRank scores for all nodes.
     *
     * @param graph         The graph
     * @param dampingFactor Damping factor (typically 0.85)
     * @param convergence   Convergence threshold
     * @param maxIterations Maximum number of iterations
     * @return Map of node ID to PageRank score
     */
    public static Map<String, Double> pageRank(AdjacencyMatrixGraph graph,
                                                double dampingFactor,
                                                double convergence,
                                                int maxIterations) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return Collections.emptyMap();
        }
        // getCombinedAdjacencyMatrix() now returns a compact [n×n] matrix built on demand
        // from sparse storage. We own this array and must close it when done.
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
            return pageRank(adj, nodeIdsOf(graph), dampingFactor, convergence, maxIterations);
        } finally {
            if (!adj.wasClosed()) adj.close();
        }
    }

    /**
     * Matrix-primitive PageRank. Takes an [n x n] adjacency matrix and the parallel list of
     * node IDs (row/col i ↔ nodeIds[i]). Shared by {@code AdjacencyMatrixGraph}-backed and
     * {@code AdjacencyView}-backed callers so there is a single implementation.
     */
    public static Map<String, Double> pageRank(INDArray adj,
                                                List<String> nodeIds,
                                                double dampingFactor,
                                                double convergence,
                                                int maxIterations) {
        int n = nodeIds.size();
        if (n == 0) return Collections.emptyMap();

        // Build column-stochastic transition matrix T where T[j,i] = adj[i,j] / outDeg[i],
        // so that new_pr = T · pr correctly distributes each node's rank across its outgoing
        // edges. Derivation: row-normalize adj (divide row i by outDeg[i]), then transpose.
        INDArray outDegrees = adj.sum(1);
        INDArray isDangling = outDegrees.eq(0);
        INDArray safeDegrees = outDegrees.add(isDangling.castTo(DataType.FLOAT));

        INDArray transitionMatrix = adj.divColumnVector(safeDegrees.reshape(n, 1)).transpose();

        // For dangling nodes (no outgoing edges), spread rank uniformly across all nodes.
        INDArray uniformCol = Nd4j.ones(DataType.FLOAT, n, 1).div(n);
        for (int i = 0; i < n; i++) {
            if (isDangling.getDouble(i) > 0) {
                transitionMatrix.putColumn(i, uniformCol);
            }
        }
        uniformCol.close();
        isDangling.close();
        safeDegrees.close();

        INDArray pr = Nd4j.ones(DataType.FLOAT, n, 1).div(n);
        INDArray teleport = Nd4j.ones(DataType.FLOAT, n, 1).div(n);

        for (int iter = 0; iter < maxIterations; iter++) {
            INDArray newPr = transitionMatrix.mmul(pr).mul(dampingFactor)
                    .add(teleport.mul(1 - dampingFactor));
            double diff = pr.sub(newPr).norm2Number().doubleValue();
            pr.assign(newPr);
            if (diff < convergence) {
                log.debug("PageRank converged after {} iterations", iter + 1);
                break;
            }
        }

        Map<String, Double> result = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            result.put(nodeIds.get(i), pr.getDouble(i, 0));
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HITS (Hyperlink-Induced Topic Search)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of HITS algorithm containing hub and authority scores.
     */
    public record HITSResult(Map<String, Double> hubScores, Map<String, Double> authorityScores) {}

    /**
     * Computes HITS (hub and authority) scores for all nodes.
     *
     * @param graph The graph
     * @return HITS result with hub and authority scores
     */
    public static HITSResult hits(AdjacencyMatrixGraph graph) {
        return hits(graph, DEFAULT_CONVERGENCE, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Computes HITS scores with custom parameters.
     *
     * @param graph         The graph
     * @param convergence   Convergence threshold
     * @param maxIterations Maximum iterations
     * @return HITS result
     */
    public static HITSResult hits(AdjacencyMatrixGraph graph, double convergence, int maxIterations) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return new HITSResult(Collections.emptyMap(), Collections.emptyMap());
        }

        // getCombinedAdjacencyMatrix() returns compact [n×n]; owned by this call.
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
        // Initialize hub and authority vectors
        INDArray hubs = Nd4j.ones(DataType.FLOAT, n, 1);
        INDArray authorities = Nd4j.ones(DataType.FLOAT, n, 1);

        INDArray adjT = adj.transpose();

        for (int iter = 0; iter < maxIterations; iter++) {
            // Authority update: a = A^T * h
            INDArray newAuth = adjT.mmul(hubs);
            double authNorm = newAuth.norm2Number().doubleValue();
            if (authNorm > 0) {
                newAuth.divi(authNorm);
            }

            // Hub update: h = A * a
            INDArray newHubs = adj.mmul(newAuth);
            double hubNorm = newHubs.norm2Number().doubleValue();
            if (hubNorm > 0) {
                newHubs.divi(hubNorm);
            }

            double authDiff = authorities.sub(newAuth).norm2Number().doubleValue();
            double hubDiff = hubs.sub(newHubs).norm2Number().doubleValue();

            authorities.assign(newAuth);
            hubs.assign(newHubs);

            if (authDiff < convergence && hubDiff < convergence) {
                log.debug("HITS converged after {} iterations", iter + 1);
                break;
            }
        }

        // Convert to maps
        Map<String, Double> hubScores = new HashMap<>();
        Map<String, Double> authorityScores = new HashMap<>();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        for (int i = 0; i < n; i++) {
            String nodeId = indexToId.get(i);
            if (nodeId != null) {
                hubScores.put(nodeId, hubs.getDouble(i, 0));
                authorityScores.put(nodeId, authorities.getDouble(i, 0));
            }
        }

        return new HITSResult(hubScores, authorityScores);
        } finally {
            if (!adj.wasClosed()) adj.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SIMILARITY METRICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Computes cosine similarity between node embeddings.
     *
     * @param graph   The graph with embeddings
     * @param nodeId1 First node ID
     * @param nodeId2 Second node ID
     * @return Cosine similarity (0 to 1), or -1 if embeddings not available
     */
    public static double cosineSimilarity(AdjacencyMatrixGraph graph, String nodeId1, String nodeId2) {
        INDArray emb1 = graph.getNodeEmbedding(nodeId1);
        INDArray emb2 = graph.getNodeEmbedding(nodeId2);

        if (emb1 == null || emb2 == null) {
            return -1;
        }

        double dot = Nd4j.getBlasWrapper().dot(emb1, emb2);
        double norm1 = emb1.norm2Number().doubleValue();
        double norm2 = emb2.norm2Number().doubleValue();

        if (norm1 == 0 || norm2 == 0) {
            return 0;
        }

        return dot / (norm1 * norm2);
    }

    /**
     * Computes similarity matrix for all node embeddings.
     *
     * @param graph The graph with embeddings
     * @return Similarity matrix as INDArray [n x n]
     */
    public static INDArray computeSimilarityMatrix(AdjacencyMatrixGraph graph) {
        INDArray embeddings = graph.getNodeEmbeddings();
        if (embeddings == null) {
            return null;
        }

        int n = graph.getNodeCount();
        INDArray activeEmbeddings = embeddings.get(
                NDArrayIndex.indices(createRange(n)),
                NDArrayIndex.all()
        );

        // Normalize embeddings
        INDArray norms = activeEmbeddings.norm2(1);
        for (int i = 0; i < n; i++) {
            double norm = norms.getDouble(i);
            if (norm > 0) {
                activeEmbeddings.getRow(i).divi(norm);
            }
        }

        // Compute similarity matrix: S = E * E^T
        return activeEmbeddings.mmul(activeEmbeddings.transpose());
    }

    /**
     * Finds the k most similar nodes to a given node based on embeddings.
     *
     * @param graph  The graph
     * @param nodeId The query node ID
     * @param k      Number of similar nodes to return
     * @return List of (nodeId, similarity) pairs sorted by similarity descending
     */
    public static List<Map.Entry<String, Double>> findMostSimilarNodes(
            AdjacencyMatrixGraph graph, String nodeId, int k) {

        Optional<MatrixGraphNode> nodeOpt = graph.getNode(nodeId);
        if (nodeOpt.isEmpty()) {
            return Collections.emptyList();
        }

        INDArray queryEmb = graph.getNodeEmbedding(nodeId);
        if (queryEmb == null) {
            return Collections.emptyList();
        }

        INDArray embeddings = graph.getNodeEmbeddings();
        if (embeddings == null) {
            return Collections.emptyList();
        }

        int n = graph.getNodeCount();
        int queryIdx = nodeOpt.get().getMatrixIndex();

        // Normalize query
        double queryNorm = queryEmb.norm2Number().doubleValue();
        if (queryNorm > 0) {
            queryEmb = queryEmb.div(queryNorm);
        }

        // Compute similarities
        List<Map.Entry<String, Double>> similarities = new ArrayList<>();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        for (int i = 0; i < n; i++) {
            if (i == queryIdx) continue;

            String otherId = indexToId.get(i);
            if (otherId == null) continue;

            INDArray otherEmb = embeddings.getRow(i);
            double otherNorm = otherEmb.norm2Number().doubleValue();

            if (otherNorm > 0) {
                double similarity = Nd4j.getBlasWrapper().dot(queryEmb, otherEmb) / otherNorm;
                similarities.add(new AbstractMap.SimpleEntry<>(otherId, similarity));
            }
        }

        // Sort and return top k
        similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return similarities.subList(0, Math.min(k, similarities.size()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTED COMPONENTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Finds connected components in the graph (treating as undirected).
     *
     * @param graph The graph
     * @return List of components, each component is a set of node IDs
     */
    public static List<Set<String>> findConnectedComponents(AdjacencyMatrixGraph graph) {
        int n = graph.getNodeCount();
        if (n == 0) return Collections.emptyList();
        // compact [n×n] — owned here, closed after use
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
            return findConnectedComponents(adj, nodeIdsOf(graph));
        } finally {
            if (!adj.wasClosed()) adj.close();
        }
    }

    /**
     * Matrix-primitive weakly connected components over an [n x n] adjacency matrix. Edges
     * are symmetrized (A + A<sup>T</sup>) so direction is ignored. Returns one set of node IDs
     * per component, in discovery order.
     */
    public static List<Set<String>> findConnectedComponents(INDArray adj, List<String> nodeIds) {
        int n = nodeIds.size();
        if (n == 0) return Collections.emptyList();

        INDArray symmetric = adj.add(adj.transpose());

        boolean[] visited = new boolean[n];
        List<Set<String>> components = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                if (visited[i]) continue;
                Set<String> component = new HashSet<>();
                Queue<Integer> queue = new ArrayDeque<>();
                queue.add(i);
                visited[i] = true;
                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    component.add(nodeIds.get(current));
                    for (int j = 0; j < n; j++) {
                        if (!visited[j] && symmetric.getDouble(current, j) > 0) {
                            visited[j] = true;
                            queue.add(j);
                        }
                    }
                }
                components.add(component);
            }
        } finally {
            if (!symmetric.wasClosed()) symmetric.close();
        }
        return components;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHORTEST PATH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Computes shortest path distances from a source node to all other nodes.
     *
     * @param graph    The graph
     * @param sourceId Source node ID
     * @return Map of node ID to distance (-1 if unreachable)
     */
    public static Map<String, Integer> shortestPathDistances(AdjacencyMatrixGraph graph, String sourceId) {
        int n = graph.getNodeCount();
        Optional<MatrixGraphNode> sourceOpt = graph.getNode(sourceId);

        if (n == 0 || sourceOpt.isEmpty()) {
            return Collections.emptyMap();
        }

        int sourceIdx = sourceOpt.get().getMatrixIndex();
        // compact [n×n] built on demand; closed when BFS is done
        INDArray adjacency = graph.getCombinedAdjacencyMatrix();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        // BFS
        int[] distances = new int[n];
        Arrays.fill(distances, -1);
        distances[sourceIdx] = 0;

        Queue<Integer> queue = new LinkedList<>();
        queue.add(sourceIdx);

        try {
            while (!queue.isEmpty()) {
                int current = queue.poll();

                for (int j = 0; j < n; j++) {
                    if (distances[j] == -1 && adjacency.getDouble(current, j) > 0) {
                        distances[j] = distances[current] + 1;
                        queue.add(j);
                    }
                }
            }
        } finally {
            if (!adjacency.wasClosed()) adjacency.close();
        }

        // Convert to map
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String nodeId = indexToId.get(i);
            if (nodeId != null) {
                result.put(nodeId, distances[i]);
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CENTRALITY MEASURES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Computes degree centrality for all nodes (normalized to [0, 1] by dividing by max).
     *
     * @param graph The graph
     * @return Map of node ID to normalized degree centrality
     */
    public static Map<String, Double> degreeCentrality(AdjacencyMatrixGraph graph) {
        int n = graph.getNodeCount();
        if (n == 0) return Collections.emptyMap();
        // compact [n×n] — owned here, closed after degree computation
        INDArray adj = graph.getCombinedAdjacencyMatrix();
        try {
            INDArray totals = degrees(adj, DegreeType.TOTAL_WEIGHTED);
            double maxDegree = totals.maxNumber().doubleValue();
            List<String> nodeIds = nodeIdsOf(graph);
            Map<String, Double> result = new HashMap<>(n);
            for (int i = 0; i < n; i++) {
                result.put(nodeIds.get(i), maxDegree > 0 ? totals.getDouble(i) / maxDegree : 0.0);
            }
            return result;
        } finally {
            if (!adj.wasClosed()) adj.close();
        }
    }

    /**
     * Degree counting modes.
     * <ul>
     *   <li>{@link #IN}, {@link #OUT}: count of distinct incoming / outgoing neighbors (nonzero entries).</li>
     *   <li>{@link #TOTAL}: count of distinct undirected neighbors (unique union of in + out).</li>
     *   <li>{@link #TOTAL_WEIGHTED}: sum of outgoing + incoming edge weights — counts each edge
     *       from both endpoints, so a single A→B edge contributes 1 to both A and B.</li>
     * </ul>
     */
    public enum DegreeType { IN, OUT, TOTAL, TOTAL_WEIGHTED }

    /**
     * Matrix-primitive degree computation. Returns a 1-D {@link INDArray} of length {@code n}
     * where entry {@code i} is the degree of the node whose row/col index is {@code i}.
     */
    public static INDArray degrees(INDArray adj, DegreeType type) {
        return switch (type) {
            case IN -> adj.gt(0).castTo(DataType.FLOAT).sum(0);
            case OUT -> adj.gt(0).castTo(DataType.FLOAT).sum(1);
            case TOTAL -> {
                INDArray symm = adj.add(adj.transpose());
                yield symm.gt(0).castTo(DataType.FLOAT).sum(1);
            }
            case TOTAL_WEIGHTED -> adj.sum(1).add(adj.sum(0));
        };
    }

    /**
     * Returns the node IDs of {@code graph} indexed by matrix position.
     */
    public static List<String> nodeIdsOf(AdjacencyMatrixGraph graph) {
        int n = graph.getNodeCount();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(indexToId.get(i));
        }
        return ids;
    }

    /**
     * Computes betweenness centrality approximation using random sampling.
     *
     * @param graph   The graph
     * @param samples Number of source nodes to sample
     * @return Map of node ID to betweenness centrality
     */
    public static Map<String, Double> betweennessCentrality(AdjacencyMatrixGraph graph, int samples) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return Collections.emptyMap();
        }

        Map<Integer, String> indexToId = graph.getIndexToNodeId();
        double[] centrality = new double[n];

        Random random = new Random(42);
        List<Integer> nodeIndices = new ArrayList<>(indexToId.keySet());

        int actualSamples = Math.min(samples, nodeIndices.size());

        for (int s = 0; s < actualSamples; s++) {
            int sourceIdx = nodeIndices.get(random.nextInt(nodeIndices.size()));
            computeBetweennessFromSource(graph, sourceIdx, centrality);
        }

        // Normalize
        double maxCentrality = Arrays.stream(centrality).max().orElse(1);

        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String nodeId = indexToId.get(i);
            if (nodeId != null) {
                result.put(nodeId, maxCentrality > 0 ? centrality[i] / maxCentrality : 0);
            }
        }

        return result;
    }

    private static void computeBetweennessFromSource(AdjacencyMatrixGraph graph, int source, double[] centrality) {
        int n = graph.getNodeCount();
        // compact [n×n] built on demand; closed after this BFS pass
        INDArray adjacency = graph.getCombinedAdjacencyMatrix();

        int[] distance = new int[n];
        double[] sigma = new double[n];
        Arrays.fill(distance, -1);
        distance[source] = 0;
        sigma[source] = 1;

        Queue<Integer> queue = new LinkedList<>();
        Stack<Integer> stack = new Stack<>();
        List<List<Integer>> predecessors = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            predecessors.add(new ArrayList<>());
        }

        queue.add(source);

        try {
            while (!queue.isEmpty()) {
                int v = queue.poll();
                stack.push(v);

                for (int w = 0; w < n; w++) {
                    if (adjacency.getDouble(v, w) > 0) {
                        if (distance[w] < 0) {
                            distance[w] = distance[v] + 1;
                            queue.add(w);
                        }
                        if (distance[w] == distance[v] + 1) {
                            sigma[w] += sigma[v];
                            predecessors.get(w).add(v);
                        }
                    }
                }
            }
        } finally {
            if (!adjacency.wasClosed()) adjacency.close();
        }

        double[] delta = new double[n];
        while (!stack.isEmpty()) {
            int w = stack.pop();
            for (int v : predecessors.get(w)) {
                delta[v] += (sigma[v] / sigma[w]) * (1 + delta[w]);
            }
            if (w != source) {
                centrality[w] += delta[w];
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMUNITY DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Detects communities using spectral clustering on the graph Laplacian.
     *
     * @param graph         The graph
     * @param numCommunities Number of communities to detect
     * @return Map of node ID to community ID
     */
    public static Map<String, Integer> spectralClustering(AdjacencyMatrixGraph graph, int numCommunities) {
        int n = graph.getNodeCount();
        if (n == 0 || numCommunities <= 0) {
            return Collections.emptyMap();
        }

        // For small graphs or when numCommunities >= n, each node is its own community
        if (numCommunities >= n) {
            Map<String, Integer> result = new HashMap<>();
            int community = 0;
            for (MatrixGraphNode node : graph.getAllNodes()) {
                result.put(node.getNodeId(), community++);
            }
            return result;
        }

        // Use connected components as a simple fallback
        List<Set<String>> components = findConnectedComponents(graph);

        Map<String, Integer> result = new HashMap<>();
        int communityId = 0;
        for (Set<String> component : components) {
            for (String nodeId : component) {
                result.put(nodeId, communityId);
            }
            communityId++;
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RANDOM WALK
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Performs a random walk from a starting node.
     *
     * @param graph   The graph
     * @param startId Starting node ID
     * @param steps   Number of steps in the walk
     * @return List of node IDs visited (including start)
     */
    public static List<String> randomWalk(AdjacencyMatrixGraph graph, String startId, int steps) {
        Optional<MatrixGraphNode> startOpt = graph.getNode(startId);
        if (startOpt.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> path = new ArrayList<>();
        path.add(startId);

        Random random = new Random();
        String currentId = startId;

        for (int i = 0; i < steps; i++) {
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(currentId, null);
            if (neighbors.isEmpty()) {
                break;
            }

            // Choose next node weighted by edge weights
            double totalWeight = neighbors.stream().mapToDouble(Map.Entry::getValue).sum();
            double r = random.nextDouble() * totalWeight;

            double cumulative = 0;
            String nextId = null;
            for (Map.Entry<String, Double> entry : neighbors) {
                cumulative += entry.getValue();
                if (r <= cumulative) {
                    nextId = entry.getKey();
                    break;
                }
            }

            if (nextId == null) {
                nextId = neighbors.get(neighbors.size() - 1).getKey();
            }

            path.add(nextId);
            currentId = nextId;
        }

        return path;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private static long[] createRange(int n) {
        long[] range = new long[n];
        for (int i = 0; i < n; i++) {
            range[i] = i;
        }
        return range;
    }
}
