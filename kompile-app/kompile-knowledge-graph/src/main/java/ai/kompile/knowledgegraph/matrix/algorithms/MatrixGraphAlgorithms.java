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

        INDArray adjacency = graph.getCombinedAdjacencyMatrix();

        // Get submatrix for actual nodes
        INDArray adj = adjacency.get(
                NDArrayIndex.indices(createRange(n)),
                NDArrayIndex.indices(createRange(n))
        );

        // Create transition matrix (column-normalized) using vectorized operations
        // transitionMatrix[j,i] = adj[i,j] / outDegree[i], or 1/n for dangling nodes
        INDArray outDegrees = adj.sum(1);  // Shape: [n]

        // Find dangling nodes (out-degree = 0)
        INDArray isDangling = outDegrees.eq(0);  // Boolean mask

        // Avoid division by zero: set zero degrees to 1 temporarily
        INDArray safeDegrees = outDegrees.add(isDangling.castTo(DataType.FLOAT));

        // Transpose adjacency and divide each column by its corresponding out-degree
        // adj.T has shape [n, n], safeDegrees has shape [n]
        // We need to divide column i by safeDegrees[i], which is row-wise division on transpose
        INDArray transitionMatrix = adj.transpose().divColumnVector(safeDegrees.reshape(n, 1));

        // For dangling nodes, set their column (now row after transpose logic) to 1/n
        // Create uniform distribution column
        INDArray uniformCol = Nd4j.ones(DataType.FLOAT, n, 1).div(n);
        for (int i = 0; i < n; i++) {
            if (isDangling.getDouble(i) > 0) {
                transitionMatrix.putColumn(i, uniformCol);
            }
        }
        uniformCol.close();
        isDangling.close();
        safeDegrees.close();

        // Initialize PageRank vector
        INDArray pr = Nd4j.ones(DataType.FLOAT, n, 1).div(n);
        INDArray teleport = Nd4j.ones(DataType.FLOAT, n, 1).div(n);

        // Power iteration
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

        // Convert to map
        Map<String, Double> result = new HashMap<>();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        for (int i = 0; i < n; i++) {
            String nodeId = indexToId.get(i);
            if (nodeId != null) {
                result.put(nodeId, pr.getDouble(i, 0));
            }
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

        INDArray adjacency = graph.getCombinedAdjacencyMatrix();
        INDArray adj = adjacency.get(
                NDArrayIndex.indices(createRange(n)),
                NDArrayIndex.indices(createRange(n))
        );

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
        if (n == 0) {
            return Collections.emptyList();
        }

        INDArray adjacency = graph.getCombinedAdjacencyMatrix();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        // Make symmetric for undirected graph
        INDArray symmetric = adjacency.add(adjacency.transpose());

        boolean[] visited = new boolean[n];
        List<Set<String>> components = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (!visited[i] && indexToId.containsKey(i)) {
                Set<String> component = new HashSet<>();
                Queue<Integer> queue = new LinkedList<>();
                queue.add(i);
                visited[i] = true;

                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    String nodeId = indexToId.get(current);
                    if (nodeId != null) {
                        component.add(nodeId);
                    }

                    for (int j = 0; j < n; j++) {
                        if (!visited[j] && symmetric.getDouble(current, j) > 0 && indexToId.containsKey(j)) {
                            visited[j] = true;
                            queue.add(j);
                        }
                    }
                }

                if (!component.isEmpty()) {
                    components.add(component);
                }
            }
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
        INDArray adjacency = graph.getCombinedAdjacencyMatrix();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        // BFS
        int[] distances = new int[n];
        Arrays.fill(distances, -1);
        distances[sourceIdx] = 0;

        Queue<Integer> queue = new LinkedList<>();
        queue.add(sourceIdx);

        while (!queue.isEmpty()) {
            int current = queue.poll();

            for (int j = 0; j < n; j++) {
                if (distances[j] == -1 && adjacency.getDouble(current, j) > 0) {
                    distances[j] = distances[current] + 1;
                    queue.add(j);
                }
            }
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
     * Computes degree centrality for all nodes.
     *
     * @param graph The graph
     * @return Map of node ID to degree centrality
     */
    public static Map<String, Double> degreeCentrality(AdjacencyMatrixGraph graph) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return Collections.emptyMap();
        }

        INDArray adjacency = graph.getCombinedAdjacencyMatrix();
        Map<Integer, String> indexToId = graph.getIndexToNodeId();

        // Sum of outgoing + incoming edges
        INDArray outDegrees = adjacency.sum(1);
        INDArray inDegrees = adjacency.sum(0).transpose();
        INDArray totalDegrees = outDegrees.add(inDegrees);

        Map<String, Double> result = new HashMap<>();
        double maxDegree = totalDegrees.maxNumber().doubleValue();

        for (int i = 0; i < n; i++) {
            String nodeId = indexToId.get(i);
            if (nodeId != null) {
                double centrality = maxDegree > 0 ? totalDegrees.getDouble(i) / maxDegree : 0;
                result.put(nodeId, centrality);
            }
        }

        return result;
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
