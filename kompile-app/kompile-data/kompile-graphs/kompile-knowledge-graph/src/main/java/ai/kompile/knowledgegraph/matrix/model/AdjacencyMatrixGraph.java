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
import org.nd4j.linalg.api.ndarray.SparseFormat;
import org.nd4j.linalg.api.ndarray.SparseNDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Matrix-based graph representation using a <em>sparse</em> adjacency structure for low-memory storage,
 * with on-demand compact dense {@link INDArray} materialization for algorithms that require it.
 *
 * <h3>Storage change (sparse refactor)</h3>
 * <p>
 * The previous representation stored one dense {@code FLOAT [capacity × capacity]} {@link INDArray}
 * per edge type, consuming ~544 MB per edge-type matrix at 9,000 nodes (capacity 11,664) — roughly
 * <strong>3.8 GB VRAM</strong> for 7 edge types with a graph that was only 0.02% dense.
 * </p>
 * <p>
 * The new representation stores edges in pure Java:
 * <pre>
 *   Map&lt;String edgeType,
 *       Map&lt;Integer srcIdx, Map&lt;Integer tgtIdx, Float weight&gt;&gt;&gt;
 * </pre>
 * plus a reverse in-degree index:
 * <pre>
 *   Map&lt;String edgeType, Map&lt;Integer tgtIdx, Set&lt;Integer srcIdx&gt;&gt;&gt;
 * </pre>
 * and a per-type edge counter. This stores only the non-zero entries —
 * ~17,000 entries × ~100 bytes overhead ≈ <strong>~1.7 MB</strong> for the same graph.
 * </p>
 *
 * <h3>Algorithms that need a dense matrix</h3>
 * <p>
 * {@link #getCombinedAdjacencyMatrix()} now builds a <em>compact</em> dense INDArray sized
 * {@code [nodeCount × nodeCount]} (not {@code [capacity × capacity]}) on demand from the sparse
 * adjacency data, to be used by PageRank / HITS / betweenness centrality callers and then
 * immediately discarded. The matrix is <strong>not</strong> kept resident between calls.
 * Callers that previously received {@code [capacity × capacity]} now receive
 * {@code [nodeCount × nodeCount]}, which is what all algorithm callers already slice to via
 * {@code NDArrayIndex.indices(createRange(n))} — so numerical results are unchanged.
 * </p>
 *
 * <h3>Backward-compatible public API</h3>
 * <p>
 * All public method signatures are identical. {@link #getAdjacencyMatrices()} returns an
 * <strong>unmodifiable view</strong> whose keyset is the set of known edge types. The values
 * are {@code null} — callers that only need the keyset ({@code MatrixKnowledgeGraphService.searchEdges},
 * {@code VectorStoreMatrixGraphStore.saveAdjacencyMatrices}) must use the keyset only.
 * {@code MatrixKnowledgeGraphService.countEdgesByType} and
 * {@code VectorStoreMatrixGraphStore.saveAdjacencyMatrices} are patched separately to use the new
 * sparse accessors ({@link #getEdgeCountByType(String)} and
 * {@link #getSparseEdges(String)}).
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

    // ── SPARSE ADJACENCY STORAGE ────────────────────────────────────────────────
    //
    // adjacencyData  : edgeType → srcIdx → tgtIdx → weight
    // reverseIndex   : edgeType → tgtIdx → Set<srcIdx>   (for in-degree queries)
    // edgeCountByType: edgeType → number of directed edges stored
    //
    // Replaces the old `Map<String, INDArray> adjacencyMatrices`.
    // All fields are ConcurrentHashMap / CopyOnWriteArraySet analogues for thread safety
    // consistent with the original synchronized putScalar / getDouble usage.

    /**
     * Sparse forward adjacency: edgeType → sourceIdx → targetIdx → weight.
     */
    private final Map<String, Map<Integer, Map<Integer, Float>>> adjacencyData;

    /**
     * Per-edge semantic relation type, parallel to {@link #adjacencyData}:
     * edgeType → sourceIdx → targetIdx → relationType. Only populated for edges that carry a
     * semantic relation (e.g. "WORKS_AT"); structural edges have no entry. Persisted alongside the
     * weights so the relation is a first-class, explicitly-stored field on the vector store rather
     * than something inferred from the adjacency key on read-back.
     */
    private final Map<String, Map<Integer, Map<Integer, String>>> relationTypeData;

    /**
     * [M-7] Per-edge quality/provenance metadata, parallel to {@link #adjacencyData}:
     * edgeType → sourceIdx → targetIdx → EdgeMeta. Carries confidence, bidirectional flag,
     * and description so these fields survive vector-store round-trips on the @Primary live path
     * (previously they were accepted by createEdge but never written to the adjacency document).
     */
    private final Map<String, Map<Integer, Map<Integer, EdgeMeta>>> edgeMetaData;

    /**
     * [M-7] Immutable per-edge quality/provenance carrier stored in {@link #edgeMetaData}.
     */
    public record EdgeMeta(Double confidence, Boolean bidirectional, String description,
                           Map<String, Object> metadata) {
        public EdgeMeta(Double confidence, Boolean bidirectional, String description) {
            this(confidence, bidirectional, description, Collections.emptyMap());
        }

        public EdgeMeta {
            metadata = metadata == null || metadata.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        /** Returns {@code true} if all fields are null/default — avoids storing empty records. */
        public boolean isEmpty() {
            return confidence == null && (bidirectional == null || Boolean.FALSE.equals(bidirectional))
                    && (description == null || description.isBlank())
                    && metadata.isEmpty();
        }
    }

    /**
     * Sparse reverse adjacency for in-degree lookups: edgeType → targetIdx → Set of sourceIdx.
     */
    private final Map<String, Map<Integer, Set<Integer>>> reverseIndex;

    /**
     * Per-edge-type edge counter (avoids scanning the sparse maps to count).
     */
    private final Map<String, AtomicInteger> edgeCountByType;

    // ── PER-EDGE-TYPE CSR CACHE ──────────────────────────────────────────────────
    //
    // Lazily populated on first post-crawl read; evicted on any adjacency mutation for
    // the affected edgeType.  Eliminates the O(nnz·log nnz) stable sort on every
    // GraphToSameDiffDataset.buildRelationCsr call after the crawl is complete.
    //
    // Thread-safety: ConcurrentHashMap for the map itself + volatile csrCacheNodeCount
    // to detect node-count growth (rowPtr length depends on N at build time).

    /**
     * Per-edge-type CSR cache: edgeType → CSR {@link SparseNDArray} built from the
     * current {@link #adjacencyData}.  Entries are evicted on any mutation of the
     * corresponding edge type and when the node count grows.
     */
    private final Map<String, SparseNDArray> csrCache = new ConcurrentHashMap<>();

    /**
     * Node count at the time each CSR cache entry was last built.  When
     * {@link #getNodeCount()} exceeds this value the entire cache is stale — rowPtr
     * has length {@code cachedN + 1}, not {@code currentN + 1}.
     */
    private volatile int csrCacheNodeCount = 0;

    // ── NODE EMBEDDINGS (kept as dense INDArray — tiny relative to adj matrices) ──

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
     * Current capacity (retained for API compatibility; no longer drives INDArray allocation).
     */
    private int currentCapacity;

    /**
     * Default initial capacity (kept for API compatibility; no INDArray is pre-allocated).
     */
    private static final int DEFAULT_INITIAL_CAPACITY = 1024;

    /**
     * Growth factor (kept for API compatibility; no INDArray resize occurs).
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
     * @param capacity Initial capacity (no longer allocates dense storage; kept for compatibility)
     */
    public AdjacencyMatrixGraph(String graphId, int capacity) {
        this.graphId = graphId;
        this.currentCapacity = capacity;
        this.nodeById = new ConcurrentHashMap<>();
        this.indexToNodeId = new ConcurrentHashMap<>();
        this.adjacencyData = new ConcurrentHashMap<>();
        this.relationTypeData = new ConcurrentHashMap<>();
        this.edgeMetaData = new ConcurrentHashMap<>();
        this.reverseIndex = new ConcurrentHashMap<>();
        this.edgeCountByType = new ConcurrentHashMap<>();
        this.nextIndex = new AtomicInteger(0);
        this.embeddingDimension = 0;
    }

    // ── Shell marker (subprocess mode) ───────────────────────────────────────────

    /** True when this instance is a metadata-only shell; the real matrix lives elsewhere. */
    private volatile boolean shell = false;
    /** Node count reported by the owning subprocess when this is a shell. */
    private volatile int shellNodeCount = 0;
    /** Edge count reported by the owning subprocess when this is a shell. */
    private volatile int shellEdgeCount = 0;

    /**
     * Mark this instance as a metadata-only shell: the real adjacency data lives in a graph
     * subprocess and only the reported counts are known locally. Graph algorithms should check
     * {@link #isShell()} and warn instead of silently operating on an empty matrix.
     *
     * @param nodeCount node count reported by the subprocess
     * @param edgeCount edge count reported by the subprocess
     */
    public void markAsShell(int nodeCount, int edgeCount) {
        this.shell = true;
        this.shellNodeCount = Math.max(0, nodeCount);
        this.shellEdgeCount = Math.max(0, edgeCount);
    }

    /** Whether this instance is a metadata-only shell (subprocess-owned matrix). */
    public boolean isShell() {
        return shell;
    }

    /** Reported node count when {@link #isShell()}; 0 otherwise. */
    public int getShellNodeCount() {
        return shellNodeCount;
    }

    /** Reported edge count when {@link #isShell()}; 0 otherwise. */
    public int getShellEdgeCount() {
        return shellEdgeCount;
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
        // Capacity is a soft limit; sparse storage expands without reallocation.
        // Grow the capacity field in lock-step so callers observing currentCapacity
        // see a consistent value.
        if (index >= currentCapacity) {
            expandCapacity();
        }

        node.setMatrixIndex(index);
        // Preserve timestamps that were already set — a node rehydrated from the vector store on
        // startup carries its original createdAt/updatedAt via deserializeNodeFromMetadata. Stamping
        // now() unconditionally reset EVERY node's createdAt to boot time on each restart, which made
        // time-based pruning impossible (all nodes reported the same app-startup timestamp). Only
        // stamp genuinely-new nodes (timestamp unset, <= 0).
        long now = System.currentTimeMillis();
        if (node.getCreatedAt() <= 0L) {
            node.setCreatedAt(now);
        }
        if (node.getUpdatedAt() <= 0L) {
            node.setUpdatedAt(now);
        }
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
        return addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional, null);
    }

    /**
     * Adds an edge carrying an explicit semantic {@code relationType} (e.g. "WORKS_AT"), stored as a
     * first-class per-edge field in addition to the structural {@code edgeType} routing key. The
     * relation is persisted/restored and surfaced authoritatively on read (no key heuristic).
     *
     * @param relationType semantic relation, or {@code null} for a purely structural edge
     */
    public boolean addEdge(String sourceNodeId, String targetNodeId, double weight,
                          String edgeType, boolean bidirectional, String relationType) {
        return addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional, relationType, null, null);
    }

    /**
     * [M-7] Adds an edge with full metadata: relationType, confidence, and description are stored
     * as first-class per-edge fields so they survive vector-store serialization round-trips. The
     * {@code bidirectional} flag is carried from the caller rather than inferred post-restore.
     *
     * @param relationType  semantic relation, or {@code null}
     * @param confidence    edge confidence score [0,1], or {@code null}
     * @param description   human-readable description, or {@code null}
     */
    public boolean addEdge(String sourceNodeId, String targetNodeId, double weight,
                           String edgeType, boolean bidirectional, String relationType,
                           Double confidence, String description) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);

        if (source == null || target == null) {
            log.warn("Cannot add edge: source or target node not found. Source: {}, Target: {}",
                    sourceNodeId, targetNodeId);
            return false;
        }

        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        int srcIdx = source.getMatrixIndex();
        int tgtIdx = target.getMatrixIndex();
        float w = (float) weight;

        addSparseEdge(type, srcIdx, tgtIdx, w, relationType);
        if (bidirectional) {
            addSparseEdge(type, tgtIdx, srcIdx, w, relationType);
        }

        // [M-7] Persist quality/provenance fields alongside the adjacency data.
        EdgeMeta meta = new EdgeMeta(confidence, bidirectional, description);
        if (!meta.isEmpty()) {
            edgeMetaData
                    .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(srcIdx, k -> new ConcurrentHashMap<>())
                    .put(tgtIdx, meta);
        }

        return true;
    }

    /**
     * [M-7] Returns the {@link EdgeMeta} for a specific directed edge, or {@code null} if none was stored.
     */
    public EdgeMeta getEdgeMeta(String edgeType, String sourceNodeId, String targetNodeId) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);
        if (source == null || target == null) return null;
        Map<Integer, Map<Integer, EdgeMeta>> typeMap = edgeMetaData.get(edgeType);
        if (typeMap == null) return null;
        Map<Integer, EdgeMeta> row = typeMap.get(source.getMatrixIndex());
        if (row == null) return null;
        return row.get(target.getMatrixIndex());
    }

    /**
     * Merge arbitrary per-edge metadata into an existing directed edge. Used by neural overlays
     * (link prediction, GNN scores) so they can publish scores without rewriting the symbolic edge.
     */
    public boolean mergeEdgeMetadata(String edgeType, String sourceNodeId, String targetNodeId,
                                     Map<String, Object> additionalMetadata) {
        if (additionalMetadata == null || additionalMetadata.isEmpty()) {
            return false;
        }
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);
        if (source == null || target == null) return false;
        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        int srcIdx = source.getMatrixIndex();
        int tgtIdx = target.getMatrixIndex();
        Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(type);
        Map<Integer, Float> row = typeMap != null ? typeMap.get(srcIdx) : null;
        if (row == null || !row.containsKey(tgtIdx)) {
            return false;
        }

        EdgeMeta existing = getEdgeMeta(type, sourceNodeId, targetNodeId);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null && existing.metadata() != null) {
            merged.putAll(existing.metadata());
        }
        merged.putAll(additionalMetadata);
        EdgeMeta updated = new EdgeMeta(
                existing != null ? existing.confidence() : null,
                existing != null ? existing.bidirectional() : null,
                existing != null ? existing.description() : null,
                merged);
        setEdgeMeta(type, srcIdx, tgtIdx, updated);
        return true;
    }

    /**
     * [M-7] Restores edge metadata (confidence, bidirectional, description) from deserialized data.
     * Called by the vector-store restore path to re-populate {@link #edgeMetaData} without going
     * through the full {@link #addEdge} flow.
     */
    public void setEdgeMeta(String edgeType, int srcIdx, int tgtIdx, EdgeMeta meta) {
        if (meta == null || meta.isEmpty()) return;
        edgeMetaData
                .computeIfAbsent(edgeType, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(srcIdx, k -> new ConcurrentHashMap<>())
                .put(tgtIdx, meta);
    }

    // ── PRIVATE SPARSE EDGE HELPERS ────────────────────────────────────────────

    /**
     * Inserts or updates a single directed sparse edge entry.
     */
    private void addSparseEdge(String type, int srcIdx, int tgtIdx, float weight, String relationType) {
        // Evict the cached CSR for this type — the structure is about to change.
        evictCsrCache(type);
        Map<Integer, Map<Integer, Float>> typeMap =
                adjacencyData.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
        Map<Integer, Float> targets =
                typeMap.computeIfAbsent(srcIdx, k -> new ConcurrentHashMap<>());

        boolean isNew = !targets.containsKey(tgtIdx);
        targets.put(tgtIdx, weight);

        // Store the explicit semantic relation (first-class field) when present.
        if (relationType != null && !relationType.isBlank()) {
            relationTypeData
                    .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(srcIdx, k -> new ConcurrentHashMap<>())
                    .put(tgtIdx, relationType);
        }

        // Update reverse index
        reverseIndex
                .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(tgtIdx, k -> ConcurrentHashMap.newKeySet())
                .add(srcIdx);

        // Update edge counter only for truly new entries
        if (isNew) {
            edgeCountByType
                    .computeIfAbsent(type, k -> new AtomicInteger(0))
                    .incrementAndGet();
        }
    }

    /**
     * Removes a single directed sparse edge entry.
     */
    private void removeSparseEdge(String type, int srcIdx, int tgtIdx) {
        // Evict the cached CSR for this type — an edge is about to be removed.
        evictCsrCache(type);
        Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(type);
        if (typeMap == null) return;
        Map<Integer, Float> targets = typeMap.get(srcIdx);
        if (targets == null) return;
        Float removed = targets.remove(tgtIdx);
        if (removed != null) {
            AtomicInteger counter = edgeCountByType.get(type);
            if (counter != null) counter.decrementAndGet();

            // Drop the parallel relation entry, if any.
            Map<Integer, Map<Integer, String>> rtType = relationTypeData.get(type);
            if (rtType != null) {
                Map<Integer, String> rtSrc = rtType.get(srcIdx);
                if (rtSrc != null) rtSrc.remove(tgtIdx);
            }

            // Clean up reverse index
            Map<Integer, Set<Integer>> rev = reverseIndex.get(type);
            if (rev != null) {
                Set<Integer> srcs = rev.get(tgtIdx);
                if (srcs != null) {
                    srcs.remove(srcIdx);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────

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
        Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(type);
        if (typeMap == null) return 0.0;
        Map<Integer, Float> targets = typeMap.get(source.getMatrixIndex());
        if (targets == null) return 0.0;
        Float w = targets.get(target.getMatrixIndex());
        return w != null ? w : 0.0;
    }

    /**
     * Returns the explicit semantic relation type stored for a directed edge, or {@code null} when the
     * edge carries no semantic relation (structural edge), was stored before relation types were
     * persisted, or does not exist. This is the authoritative first-class relation field — distinct
     * from inferring it from the adjacency {@code edgeType} key.
     */
    public String getEdgeRelationType(String edgeType, String sourceNodeId, String targetNodeId) {
        MatrixGraphNode source = nodeById.get(sourceNodeId);
        MatrixGraphNode target = nodeById.get(targetNodeId);
        if (source == null || target == null) {
            return null;
        }
        String type = edgeType != null ? edgeType : DEFAULT_EDGE_TYPE;
        Map<Integer, Map<Integer, String>> typeMap = relationTypeData.get(type);
        if (typeMap == null) return null;
        Map<Integer, String> targets = typeMap.get(source.getMatrixIndex());
        if (targets == null) return null;
        return targets.get(target.getMatrixIndex());
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
        removeSparseEdge(type, source.getMatrixIndex(), target.getMatrixIndex());
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

        int nodeIndex = node.getMatrixIndex();
        List<Map.Entry<String, Double>> neighbors = new ArrayList<>();

        if (edgeType != null) {
            Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(edgeType);
            if (typeMap != null) {
                collectNeighborsFromTypeMap(typeMap, nodeIndex, neighbors);
            }
        } else {
            for (Map<Integer, Map<Integer, Float>> typeMap : adjacencyData.values()) {
                collectNeighborsFromTypeMap(typeMap, nodeIndex, neighbors);
            }
        }

        return neighbors;
    }

    private void collectNeighborsFromTypeMap(Map<Integer, Map<Integer, Float>> typeMap,
                                              int nodeIndex,
                                              List<Map.Entry<String, Double>> neighbors) {
        Map<Integer, Float> targets = typeMap.get(nodeIndex);
        if (targets == null) return;
        for (Map.Entry<Integer, Float> entry : targets.entrySet()) {
            String neighborId = indexToNodeId.get(entry.getKey());
            if (neighborId != null) {
                neighbors.add(new AbstractMap.SimpleEntry<>(neighborId, (double) entry.getValue()));
            }
        }
    }

    /**
     * Builds a compact dense {@link INDArray} of shape {@code [n × n]} (where {@code n = getNodeCount()})
     * summing all edge types. This is created on demand for algorithm use and must be closed by the
     * caller after use to release native memory. It is <strong>not</strong> kept resident.
     *
     * <p>Callers in {@link ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms} previously
     * indexed this with {@code .get(NDArrayIndex.indices(createRange(n)), ...)} to obtain the active
     * sub-matrix. That slice is now unnecessary since the returned matrix is already {@code [n × n]},
     * but the existing index operations are safe no-ops on an {@code n×n} matrix when {@code n} equals
     * the matrix dimension.</p>
     *
     * @return A newly allocated compact {@code [nodeCount × nodeCount]} INDArray; caller owns and
     *         must close it.
     */
    public INDArray getCombinedAdjacencyMatrix() {
        int n = getNodeCount();
        if (n == 0) {
            return Nd4j.zeros(DataType.FLOAT, 0, 0);
        }

        INDArray combined = Nd4j.zeros(DataType.FLOAT, n, n);
        for (Map<Integer, Map<Integer, Float>> typeMap : adjacencyData.values()) {
            for (Map.Entry<Integer, Map<Integer, Float>> rowEntry : typeMap.entrySet()) {
                int srcIdx = rowEntry.getKey();
                if (srcIdx >= n) continue;
                for (Map.Entry<Integer, Float> colEntry : rowEntry.getValue().entrySet()) {
                    int tgtIdx = colEntry.getKey();
                    if (tgtIdx >= n) continue;
                    // addScalar to combine — equivalent to addi(adj) over all types
                    combined.putScalar(srcIdx, tgtIdx,
                            combined.getFloat(srcIdx, tgtIdx) + colEntry.getValue());
                }
            }
        }
        return combined;
    }

    /**
     * Returns the number of edges for a given edge type without any INDArray operation.
     * O(1) — backed by a maintained counter.
     *
     * @param edgeType the edge type key
     * @return the number of directed edges of this type
     */
    public long getEdgeCountByType(String edgeType) {
        AtomicInteger counter = edgeCountByType.get(edgeType);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Returns a read-only view of the sparse edges for a given edge type, as a list of
     * {@code [sourceIdx, targetIdx, weight]} int-int-float triples — suitable for
     * persistence serialization (replaces the old dense INDArray scan in
     * {@link ai.kompile.knowledgegraph.matrix.store.VectorStoreMatrixGraphStore}).
     *
     * <p>Each entry is a {@code int[3]} where {@code [0]=srcIdx, [1]=tgtIdx},
     * and the weight is a separate {@code float[]} at the same position — returned
     * as a pair of parallel arrays wrapped in {@link SparseEdgeData}.</p>
     *
     * @param edgeType the edge type
     * @return immutable sparse edge data for this type
     */
    public SparseEdgeData getSparseEdges(String edgeType) {
        Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(edgeType);
        if (typeMap == null) {
            return new SparseEdgeData(Collections.emptyList());
        }

        Map<Integer, Map<Integer, String>> rtType = relationTypeData.get(edgeType);
        Map<Integer, Map<Integer, EdgeMeta>> metaType = edgeMetaData.get(edgeType);  // [M-7]

        List<int[]> entries = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        List<String> relationTypes = new ArrayList<>();
        List<EdgeMeta> edgeMetas = new ArrayList<>();  // [M-7]
        for (Map.Entry<Integer, Map<Integer, Float>> rowEntry : typeMap.entrySet()) {
            int src = rowEntry.getKey();
            Map<Integer, String> rtRow = rtType != null ? rtType.get(src) : null;
            Map<Integer, EdgeMeta> metaRow = metaType != null ? metaType.get(src) : null;  // [M-7]
            for (Map.Entry<Integer, Float> colEntry : rowEntry.getValue().entrySet()) {
                int tgt = colEntry.getKey();
                entries.add(new int[]{src, tgt});
                weights.add(colEntry.getValue());
                relationTypes.add(rtRow != null ? rtRow.get(tgt) : null);
                edgeMetas.add(metaRow != null ? metaRow.get(tgt) : null);  // [M-7]
            }
        }

        return new SparseEdgeData(entries, weights, relationTypes, edgeMetas);
    }

    /**
     * Immutable carrier for sparse edge data returned by {@link #getSparseEdges(String)}.
     */
    public static final class SparseEdgeData {
        /** Parallel list of [srcIdx, tgtIdx] pairs. */
        public final List<int[]> indices;
        /** Parallel list of weights; same size as {@code indices}. */
        public final List<Float> weights;
        /** Parallel list of semantic relation types (entries may be null); same size as {@code indices}. */
        public final List<String> relationTypes;
        /** [M-7] Parallel list of edge quality/provenance metadata (entries may be null); same size as {@code indices}. */
        public final List<EdgeMeta> edgeMetas;

        SparseEdgeData(List<int[]> indices) {
            this.indices = Collections.unmodifiableList(indices);
            this.weights = Collections.emptyList();
            this.relationTypes = Collections.emptyList();
            this.edgeMetas = Collections.emptyList();
        }

        SparseEdgeData(List<int[]> indices, List<Float> weights) {
            this.indices = Collections.unmodifiableList(indices);
            this.weights = Collections.unmodifiableList(weights);
            this.relationTypes = Collections.emptyList();
            this.edgeMetas = Collections.emptyList();
        }

        SparseEdgeData(List<int[]> indices, List<Float> weights, List<String> relationTypes) {
            this.indices = Collections.unmodifiableList(indices);
            this.weights = Collections.unmodifiableList(weights);
            this.relationTypes = Collections.unmodifiableList(relationTypes);
            this.edgeMetas = Collections.emptyList();
        }

        SparseEdgeData(List<int[]> indices, List<Float> weights, List<String> relationTypes,
                       List<EdgeMeta> edgeMetas) {
            this.indices = Collections.unmodifiableList(indices);
            this.weights = Collections.unmodifiableList(weights);
            this.relationTypes = Collections.unmodifiableList(relationTypes);
            this.edgeMetas = Collections.unmodifiableList(edgeMetas);
        }

        public int size() { return indices.size(); }
    }

    // ── CSR CACHE ─────────────────────────────────────────────────────────────────

    /**
     * Returns the per-edge-type CSR adjacency as a {@link SparseNDArray}, building and
     * caching it on first call and returning the cached copy on subsequent calls.
     *
     * <p>The cache is invalidated automatically on every adjacency mutation (via
     * {@link #addSparseEdge}, {@link #removeSparseEdge}, and {@link #removeNode}) so
     * callers never observe a stale CSR.  If {@link #getNodeCount()} has grown since
     * the last build (the {@code rowPtr} would be too short) the entire cache is flushed
     * before the requested type is rebuilt.
     *
     * <p>The returned {@link SparseNDArray} is in {@link SparseFormat#CSR} format.  Its
     * component arrays ({@code getRowPtr()}, {@code getColIdx()}, {@code getValues()})
     * use the same INT32 / FLOAT32 types as {@code GraphToSameDiffDataset.cooToCsr}.
     *
     * <p>For an edge type with zero edges an empty-but-valid CSR is returned
     * ({@code rowPtr = [0, 0, …, 0]}, zero-length {@code colIdx} and {@code values}).
     *
     * @param edgeType the edge type key (use {@link #DEFAULT_EDGE_TYPE} for untyped edges)
     * @return a CSR {@link SparseNDArray} of logical shape [nodeCount, nodeCount]
     */
    public SparseNDArray getCsrForEdgeType(String edgeType) {
        int currentN = getNodeCount();
        if (currentN != csrCacheNodeCount) {
            // Node count grew — all cached rowPtr arrays are too short; flush and reset.
            csrCache.clear();
            csrCacheNodeCount = currentN;
        }
        return csrCache.computeIfAbsent(edgeType, type -> buildCsrForType(type, currentN));
    }

    /**
     * Builds a CSR {@link SparseNDArray} of shape {@code [n, n]} from the current COO
     * data for {@code edgeType} via {@link Nd4j#sparseFromEdges} → {@code .toCsr()}.
     *
     * <p>Entries with src/tgt indices ≥ n are filtered out (same guard as
     * {@code GraphToSameDiffDataset.collectCoo}) so the shape contract is always met.
     */
    private SparseNDArray buildCsrForType(String edgeType, int n) {
        SparseEdgeData sed = getSparseEdges(edgeType);
        int nnz = sed.size();
        if (n == 0 || nnz == 0) {
            // Empty CSR: values=[], colIdx=[], rowPtr=[0,…,0] (length n+1)
            INDArray emptyVals = Nd4j.create(DataType.FLOAT, 0);
            INDArray emptyCol  = Nd4j.create(DataType.INT32, 0);
            INDArray zeroPtr   = Nd4j.zeros(DataType.INT32, n + 1);
            return new SparseNDArray(emptyVals, emptyCol, zeroPtr,
                    new long[]{n, n}, SparseFormat.CSR);
        }

        // Collect valid COO entries (same guard as GraphToSameDiffDataset.collectCoo)
        int[] srcArr = new int[nnz];
        int[] dstArr = new int[nnz];
        float[] wArr  = new float[nnz];
        int valid = 0;
        for (int i = 0; i < nnz; i++) {
            int src = sed.indices.get(i)[0];
            int tgt = sed.indices.get(i)[1];
            if (src >= n || tgt >= n) continue;
            srcArr[valid] = src;
            dstArr[valid] = tgt;
            wArr[valid]   = (sed.weights != null && i < sed.weights.size()
                    && sed.weights.get(i) != null) ? sed.weights.get(i) : 1.0f;
            valid++;
        }
        if (valid == 0) {
            INDArray emptyVals = Nd4j.create(DataType.FLOAT, 0);
            INDArray emptyCol  = Nd4j.create(DataType.INT32, 0);
            INDArray zeroPtr   = Nd4j.zeros(DataType.INT32, n + 1);
            return new SparseNDArray(emptyVals, emptyCol, zeroPtr,
                    new long[]{n, n}, SparseFormat.CSR);
        }
        // Trim to valid count if any entries were filtered
        if (valid < nnz) {
            srcArr = Arrays.copyOf(srcArr, valid);
            dstArr = Arrays.copyOf(dstArr, valid);
            wArr   = Arrays.copyOf(wArr, valid);
        }

        INDArray srcNd  = Nd4j.createFromArray(srcArr).castTo(DataType.INT64);
        INDArray dstNd  = Nd4j.createFromArray(dstArr).castTo(DataType.INT64);
        INDArray wNd    = Nd4j.create(wArr, new long[]{valid}, DataType.FLOAT);
        SparseNDArray coo = Nd4j.sparseFromEdges(srcNd, dstNd, wNd, n, n);
        return coo.toCsr();
    }

    /**
     * Removes the cached CSR for a single edge type.  Called on every adjacency mutation
     * so consumers of {@link #getCsrForEdgeType} never observe stale data.
     */
    private void evictCsrCache(String edgeType) {
        csrCache.remove(edgeType);
    }

    /**
     * Returns a <strong>keyset-only</strong> view for backward compatibility with callers that
     * only access {@code graph.getAdjacencyMatrices().keySet()} (e.g. {@code searchEdges} in
     * {@code MatrixKnowledgeGraphService} and the persistence helper in
     * {@code VectorStoreMatrixGraphStore}).
     *
     * <p><strong>WARNING:</strong> The map values are {@code null}. Callers that previously
     * retrieved INDArray values from this map must be migrated to
     * {@link #getCombinedAdjacencyMatrix()}, {@link #getEdgeCountByType(String)}, or
     * {@link #getSparseEdges(String)} as appropriate.</p>
     *
     * @return an unmodifiable map whose keyset equals {@code getEdgeTypes()} and whose values are null
     */
    public Map<String, INDArray> getAdjacencyMatrices() {
        // Build a null-value map whose keyset mirrors adjacencyData's keyset.
        // Using a simple HashMap is acceptable here since this is a compatibility
        // shim accessed infrequently (only by searchEdges and saveAdjacencyMatrices).
        Map<String, INDArray> view = new HashMap<>();
        for (String key : adjacencyData.keySet()) {
            view.put(key, null);  // values intentionally null — keyset access only
        }
        return Collections.unmodifiableMap(view);
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
        int needed = Math.max(currentCapacity, getNodeCount());
        if (nodeEmbeddings == null || nodeEmbeddings.rows() < needed) {
            if (nodeEmbeddings != null && !nodeEmbeddings.wasClosed()) {
                nodeEmbeddings.close();
            }
            nodeEmbeddings = Nd4j.zeros(DataType.FLOAT, needed, embeddingDimension);
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
     * Expands the logical capacity field.
     * With sparse storage this is a bookkeeping operation only — no INDArray reallocation occurs.
     */
    private synchronized void expandCapacity() {
        int newCapacity = (int) (currentCapacity * GROWTH_FACTOR);
        log.debug("Expanding sparse graph capacity field from {} to {} (no INDArray allocation)",
                currentCapacity, newCapacity);
        currentCapacity = newCapacity;
    }

    /**
     * Gets the number of nodes in the graph.
     */
    public int getNodeCount() {
        return nextIndex.get();
    }

    /**
     * Gets the total number of edges across all types.  O(numEdgeTypes) — reads per-type counters.
     */
    public long getEdgeCount() {
        long total = 0;
        for (AtomicInteger counter : edgeCountByType.values()) {
            total += counter.get();
        }
        return total;
    }

    /**
     * Gets all edge types in the graph.
     */
    public Set<String> getEdgeTypes() {
        return new HashSet<>(adjacencyData.keySet());
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

        // Node removal touches adjacency across all edge types (outgoing row + incoming
        // entries in all type maps). Flush the entire CSR cache rather than tracking
        // which types were actually affected.
        csrCache.clear();

        int idx = node.getMatrixIndex();
        indexToNodeId.remove(idx);

        // Drop this node's outgoing relation entries (incoming stale entries are unreachable —
        // getEdgeRelationType / getSparseEdges resolve through nodeById/adjacencyData, neither of
        // which still references this node).
        relationTypeData.values().forEach(typeMap -> typeMap.remove(idx));

        // Remove all outgoing and incoming edges for this node from all edge-type maps
        for (String type : adjacencyData.keySet()) {
            // Remove outgoing edges (row removal)
            Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(type);
            if (typeMap != null) {
                Map<Integer, Float> outgoing = typeMap.remove(idx);
                if (outgoing != null) {
                    AtomicInteger counter = edgeCountByType.get(type);
                    if (counter != null) counter.addAndGet(-outgoing.size());
                }
            }

            // Remove incoming edges (reverse lookup then remove from forward map)
            Map<Integer, Set<Integer>> rev = reverseIndex.get(type);
            if (rev != null) {
                Set<Integer> incomingSrcs = rev.remove(idx);
                if (incomingSrcs != null) {
                    for (int srcIdx : incomingSrcs) {
                        Map<Integer, Map<Integer, Float>> tm = adjacencyData.get(type);
                        if (tm != null) {
                            Map<Integer, Float> tgts = tm.get(srcIdx);
                            if (tgts != null && tgts.remove(idx) != null) {
                                AtomicInteger counter = edgeCountByType.get(type);
                                if (counter != null) counter.decrementAndGet();
                            }
                        }
                    }
                }
                // Also clean up any reverse-index entries that pointed to this node as a source
                rev.values().forEach(srcs -> srcs.remove(idx));
            }
        }

        // Clear embedding row if present (write zeros via putRow)
        if (nodeEmbeddings != null && embeddingDimension > 0
                && idx < nodeEmbeddings.rows()) {
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
     * Gets the degree (number of connections) for a node. All operations are O(degree) with
     * zero INDArray / GPU involvement.
     */
    public int getNodeDegree(String nodeId, String edgeType, boolean outgoing) {
        MatrixGraphNode node = nodeById.get(nodeId);
        if (node == null) {
            return 0;
        }

        int idx = node.getMatrixIndex();
        int degree = 0;

        Collection<String> types = edgeType != null
                ? Collections.singleton(edgeType)
                : adjacencyData.keySet();

        for (String type : types) {
            if (outgoing) {
                Map<Integer, Map<Integer, Float>> typeMap = adjacencyData.get(type);
                if (typeMap != null) {
                    Map<Integer, Float> targets = typeMap.get(idx);
                    if (targets != null) degree += targets.size();
                }
            } else {
                Map<Integer, Set<Integer>> rev = reverseIndex.get(type);
                if (rev != null) {
                    Set<Integer> srcs = rev.get(idx);
                    if (srcs != null) degree += srcs.size();
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
        // Sparse maps hold no native resources; clear them.
        adjacencyData.clear();
        relationTypeData.clear();
        reverseIndex.clear();
        edgeCountByType.clear();

        // CSR cache entries wrap INDArray component arrays — clear to release references.
        csrCache.clear();
        csrCacheNodeCount = 0;

        // Close embeddings (the only remaining INDArray)
        if (nodeEmbeddings != null && !nodeEmbeddings.wasClosed()) {
            nodeEmbeddings.close();
            nodeEmbeddings = null;
        }
    }
}
