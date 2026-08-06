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

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.io.model.EdgeMetadata;
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Matrix-based implementation of KnowledgeGraphService.
 * <p>
 * This implementation uses the matrix-based graph storage and provides
 * compatibility with the existing KnowledgeGraphService interface.
 * </p>
 */
@Service
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
     * Legacy single-graph ID. Retained ONLY as the null-factSheet fallback during the transition to
     * per-fact-sheet segmentation. Every graph operation must resolve its graph id from the owning fact
     * sheet via {@link #graphIdForFactSheet(Long)} so each fact sheet is its own segmented, persistent
     * graph (instead of one global in-memory blob keyed by this constant).
     */
    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    /**
     * Resolve the matrix-store graph id for a fact sheet. The graph IS the fact sheet — each fact sheet's
     * nodes/edges live in their own segmented graph (persisted under that id), so per-fact-sheet queries
     * read a real graph rather than scanning one global graph and filtering by a {@code factSheetId} field
     * (which was never set on most nodes, so tables/entities silently vanished from the index browser).
     * Null falls back to the legacy id only for un-scoped legacy callers during the transition.
     */
    static String graphIdForFactSheet(Long factSheetId) {
        return factSheetId != null ? "factsheet_" + factSheetId : DEFAULT_GRAPH_ID;
    }

    /** All segmented graph ids currently in the store (per-fact-sheet graphs + the legacy default). */
    private List<String> allGraphIds() {
        Set<String> ids = graphStore.getLoadedGraphIds();
        return (ids == null || ids.isEmpty()) ? List.of(DEFAULT_GRAPH_ID) : new ArrayList<>(ids);
    }

    /**
     * The graph id that currently holds {@code nodeId}, searching every segmented (per-fact-sheet) graph.
     * Non-fact-sheet-scoped operations (getNode/getChildren/updateNode/deleteNode/getNeighbors) used to
     * assume one global graph; now that each fact sheet is its own graph they must locate the owning graph.
     * Returns {@link #DEFAULT_GRAPH_ID} as a last resort so callers always have a usable id.
     */
    private String graphIdHolding(String nodeId) {
        for (String gid : graphStore.getLoadedGraphIds()) {
            if (graphStore.getNode(gid, nodeId).isPresent()) {
                return gid;
            }
        }
        return DEFAULT_GRAPH_ID;
    }

    /** Find a node by id across all segmented graphs. */
    private Optional<MatrixGraphNode> findNodeAnyGraph(String nodeId) {
        for (String gid : graphStore.getLoadedGraphIds()) {
            Optional<MatrixGraphNode> n = graphStore.getNode(gid, nodeId);
            if (n.isPresent()) {
                return n;
            }
        }
        return Optional.empty();
    }

    /** All nodes across every segmented graph (for the un-scoped, cross-fact-sheet queries). */
    private List<MatrixGraphNode> allNodesAcrossGraphs() {
        List<MatrixGraphNode> all = new ArrayList<>();
        for (String gid : graphStore.getLoadedGraphIds()) {
            all.addAll(graphStore.getAllNodes(gid));
        }
        return all;
    }

    /**
     * Canonical string form of an edge type for matrix-graph storage.
     * <p>
     * The stored string is always the enum constant name, so every {@link EdgeType}
     * value round-trips losslessly. A hand-maintained map used to live here, but it only
     * covered 7 of the {@link EdgeType} values and silently aliased the rest (CONTAINS,
     * EXTRACTED_FROM, AUTHORED_BY, ADDRESSED_TO, RESOLVES_TO) to "RELATED_TO" — collapsing
     * five distinct types into one indistinguishable bucket and leaving a non-enum string
     * that {@link #edgeTypeFromString} (formerly {@code EdgeType.valueOf}) would throw on.
     */
    private static String edgeTypeToString(EdgeType edgeType) {
        return (edgeType != null ? edgeType : EdgeType.USER_DEFINED).name();
    }

    /**
     * Parse a stored edge-type string back to an {@link EdgeType}. Unknown or legacy
     * strings (e.g. the historical "RELATED_TO" alias) fall back to {@link EdgeType#USER_DEFINED}
     * rather than throwing, matching the lenient convention used by graph IO import.
     */
    private static EdgeType edgeTypeFromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return EdgeType.USER_DEFINED;
        }
        try {
            return EdgeType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return EdgeType.USER_DEFINED;
        }
    }

    /** Generic/default adjacency keys that carry no ontology relationship meaning (excluded from
     *  {@code relationType} so they don't generate conformance noise). */
    private static final Set<String> GENERIC_EDGE_KEYS = Set.of("RELATED_TO");

    /**
     * Set of all valid {@link NodeLevel} name strings, built once at class-load time.
     *
     * <p>Used by {@link #isUserNodeType(String)} to distinguish real graph nodes
     * (SOURCE / DOCUMENT / ENTITY / …) from internal pseudo-nodes whose type strings
     * start with {@code "_"} (e.g. {@code "_KGE_EDGE_TYPE"} created by KGE embedding
     * training). Pseudo-nodes must be excluded from bulk user-facing node queries —
     * otherwise {@link NodeLevel#valueOf(String)} throws {@link IllegalArgumentException}
     * inside {@link #convertToGraphNode}, which propagates through stream pipelines and
     * silently aborts the graph-projection step of the ENRICHMENT crawl stage, leaving
     * the PSL FactStore empty and causing all enrichment metrics to be zero.</p>
     */
    private static final Set<String> KNOWN_NODE_LEVELS;

    static {
        Set<String> levels = new LinkedHashSet<>();
        for (NodeLevel level : NodeLevel.values()) {
            levels.add(level.name());
        }
        KNOWN_NODE_LEVELS = Collections.unmodifiableSet(levels);
    }

    /**
     * Returns {@code true} when {@code type} is a real {@link NodeLevel} name that should
     * be exposed to user-facing node queries and to the PSL graph-projection step.
     * Returns {@code false} for internal pseudo-node types (e.g. {@code "_KGE_EDGE_TYPE"})
     * that must be skipped by bulk-scan methods to avoid {@link IllegalArgumentException}
     * in {@link #convertToGraphNode}.
     */
    private static boolean isUserNodeType(String type) {
        return type != null && KNOWN_NODE_LEVELS.contains(type);
    }

    /**
     * The semantic relation type for a stored adjacency edge-type key: the key itself when it is a
     * meaningful semantic relation (e.g. "WORKS_AT", "FEEDS_INTO"), or {@code null} when it is a
     * structural {@link EdgeType} constant or a generic default — those have no ontology relationship
     * meaning. The matrix store keys adjacency by the extractor's relation string, so this recovers it.
     */
    private static String semanticRelationType(String edgeTypeKey) {
        if (edgeTypeKey == null || edgeTypeKey.isBlank()
                || GENERIC_EDGE_KEYS.contains(edgeTypeKey.toUpperCase(Locale.ROOT))) {
            return null;
        }
        try {
            EdgeType.valueOf(edgeTypeKey);
            return null; // structural enum constant
        } catch (IllegalArgumentException ex) {
            return edgeTypeKey; // semantic relation
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                               String pathOrUrl, Map<String, Object> metadata) {
        String nodeId = "source_" + externalId;

        // No factSheetId in this signature — locate any existing node across segmented graphs; new nodes go to the default graph.
        String gid = graphIdHolding(nodeId);
        Optional<MatrixGraphNode> existing = graphStore.getNode(gid, nodeId);
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
            graphStore.updateNode(gid, node);
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

        graphStore.addNode(graphIdForFactSheet(null), node);
        return convertToGraphNode(node, externalId);
    }

    @Override
    public GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                         Map<String, Object> metadata) {
        String nodeId = "doc_" + docId;

        // Scope to the source node's fact sheet so the document lives in the same segmented graph.
        Long fsId = sourceNode.getFactSheetId();
        String gid = graphIdForFactSheet(fsId);
        Optional<MatrixGraphNode> existing = graphStore.getNode(gid, nodeId);
        if (existing.isPresent()) {
            MatrixGraphNode node = existing.get();
            node.setTitle(title);
            node.setMetadata(metadata);
            graphStore.updateNode(gid, node);
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
                .factSheetId(fsId)
                .build();

        graphStore.addNode(gid, node);

        // Create hierarchical edge from source to document
        String sourceMatrixId = "source_" + sourceNode.getExternalId();
        graphStore.addEdge(gid, sourceMatrixId, nodeId, 1.0, "HIERARCHICAL", false);

        return convertToGraphNode(node, docId);
    }

    @Override
    public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                        int chunkIndex) {
        String nodeId = "snippet_" + snippetId;
        String preview = content.length() > 500 ? content.substring(0, 500) + "..." : content;

        Long fsId = documentNode.getFactSheetId();
        String gid = graphIdForFactSheet(fsId);
        Optional<MatrixGraphNode> existing = graphStore.getNode(gid, nodeId);
        if (existing.isPresent()) {
            MatrixGraphNode node = existing.get();
            node.setDescription(preview);
            graphStore.updateNode(gid, node);
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
                .factSheetId(fsId)
                .build();

        graphStore.addNode(gid, node);

        // Create hierarchical edge from document to snippet
        String docMatrixId = NodeLevel.DOCUMENT.name().toLowerCase() + "_" + documentNode.getExternalId();
        graphStore.addEdge(gid, docMatrixId, nodeId, 1.0, "HIERARCHICAL", false);

        return convertToGraphNode(node, snippetId);
    }

    /**
     * Bulk SNIPPET creation — groups specs by fact-sheet, calls {@code graphStore.addNodesBatch}
     * ONCE per group (one Lucene write instead of N), then adds per-edge HIERARCHICAL links
     * in-process (cheap — in-memory adjacency in the subprocess). Mirrors the shape of
     * {@link #createSnippetNode} exactly: nodeId {@code "snippet_"+snippetId}, nodeType SNIPPET,
     * title {@code "Chunk "+(chunkIndex+1)}, description = content truncated to 500 chars,
     * metadata = {@code {chunkIndex, parentNodeId:"document_"+parentExternalId}}.
     */
    @Override
    public List<GraphNode> createSnippetNodesBatch(List<KnowledgeGraphService.SnippetSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        // Group specs by graphId (fact-sheet) so we can batch per segment
        Map<String, List<KnowledgeGraphService.SnippetSpec>> byGraph = new LinkedHashMap<>();
        for (KnowledgeGraphService.SnippetSpec s : specs) {
            String gid = graphIdForFactSheet(s.parentFactSheetId());
            byGraph.computeIfAbsent(gid, k -> new ArrayList<>()).add(s);
        }

        // Build a global index: snippetId → result position so we can return in input order
        Map<String, GraphNode> resultMap = new HashMap<>(specs.size() * 2);

        for (Map.Entry<String, List<KnowledgeGraphService.SnippetSpec>> entry : byGraph.entrySet()) {
            String gid = entry.getKey();
            List<KnowledgeGraphService.SnippetSpec> group = entry.getValue();
            List<MatrixGraphNode> matrixNodes = new ArrayList<>(group.size());
            for (KnowledgeGraphService.SnippetSpec s : group) {
                String nodeId = "snippet_" + s.snippetId();
                // Skip if already present (idempotent — same semantics as createSnippetNode's upsert check)
                if (graphStore.getNode(gid, nodeId).isPresent()) {
                    // Re-use existing — fetch and register
                    graphStore.getNode(gid, nodeId).ifPresent(existing ->
                            resultMap.put(s.snippetId(), convertToGraphNode(existing, s.snippetId())));
                    continue;
                }
                String preview = s.content() != null && s.content().length() > 500
                        ? s.content().substring(0, 500) + "..." : s.content();
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("chunkIndex", s.chunkIndex());
                String docMatrixId = NodeLevel.DOCUMENT.name().toLowerCase() + "_" + s.parentExternalId();
                metadata.put("parentNodeId", docMatrixId);
                MatrixGraphNode node = MatrixGraphNode.builder()
                        .nodeId(nodeId)
                        .nodeType("SNIPPET")
                        .title("Chunk " + (s.chunkIndex() + 1))
                        .description(preview)
                        .metadata(metadata)
                        .factSheetId(s.parentFactSheetId())
                        .build();
                matrixNodes.add(node);
                resultMap.put(s.snippetId(), convertToGraphNode(node, s.snippetId()));
            }
            if (!matrixNodes.isEmpty()) {
                graphStore.addNodesBatch(gid, matrixNodes);
            }
            // Add HIERARCHICAL edges from document → snippet (in-process, cheap)
            for (KnowledgeGraphService.SnippetSpec s : group) {
                try {
                    String docMatrixId = NodeLevel.DOCUMENT.name().toLowerCase() + "_" + s.parentExternalId();
                    graphStore.addEdge(gid, docMatrixId, "snippet_" + s.snippetId(), 1.0, "HIERARCHICAL", false);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }

        // Return in the original input order
        List<GraphNode> result = new ArrayList<>(specs.size());
        for (KnowledgeGraphService.SnippetSpec s : specs) {
            result.add(resultMap.get(s.snippetId()));
        }
        return result;
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

        graphStore.addNode(graphIdForFactSheet(factSheetId), matrixNode);

        return convertToGraphNode(matrixNode, externalId);
    }

    /**
     * Bulk node creation — collapses N per-node {@link #createNode} calls (each a single
     * {@code vectorStore.add(oneDoc)} Lucene write) into ONE batched {@code addNodesBatch}
     * write. Node IDs are deterministic ({@code type_externalId}), so this is semantically
     * identical to looping {@link #createNode}, just without the per-node store round-trips.
     * This is the structural-graph (formula/table/cell) construction fast path.
     */
    @Override
    public List<GraphNode> createNodesBatch(List<NodeSpec> specs, Long factSheetId) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<MatrixGraphNode> matrixNodes = new ArrayList<>(specs.size());
        List<GraphNode> result = new ArrayList<>(specs.size());
        for (NodeSpec s : specs) {
            String nodeId = s.nodeType().name().toLowerCase() + "_" + s.externalId();
            MatrixGraphNode matrixNode = MatrixGraphNode.builder()
                    .nodeId(nodeId)
                    .nodeType(s.nodeType().name())
                    .title(s.title())
                    .description(s.description())
                    .metadata(s.metadata() != null ? s.metadata() : new HashMap<>())
                    .factSheetId(factSheetId)
                    .build();
            matrixNodes.add(matrixNode);
            result.add(convertToGraphNode(matrixNode, s.externalId()));
        }
        graphStore.addNodesBatch(graphIdForFactSheet(factSheetId), matrixNodes);
        return result;
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
        // Persist the SOURCE node scoped to the fact sheet so it lives in the same segmented graph as the
        // DOCUMENT/ENTITY/TABLE nodes (and shows up in getSourcesInFactSheet / the index browser).
        Map<String, Object> sourceMeta = docMeta != null ? new HashMap<>(docMeta) : new HashMap<>();
        sourceMeta.put("sourceType", sourceType != null ? sourceType : "FILE");
        sourceMeta.put("pathOrUrl", sourcePath);
        GraphNode sourceNode = createNode(NodeLevel.SOURCE, sourceExternalId, jobId,
                null, sourceMeta, factSheetId);

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
            graphStore.addEdge(graphIdForFactSheet(factSheetId), sourceNode.getNodeId(), docNode.getNodeId(),
                    1.0, "HIERARCHICAL", false);
        } catch (Exception ignored) {
            // best-effort
        }

        return docNode;
    }

    @Override
    public Optional<GraphNode> getNode(String nodeId) {
        return findNodeAnyGraph(nodeId)
                .filter(n -> isUserNodeType(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())));
    }

    @Override
    public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType) {
        String nodeId = nodeType.name().toLowerCase() + "_" + externalId;
        return getNode(nodeId);
    }

    @Override
    public List<GraphNode> getChildren(String parentNodeId) {
        String graphId = graphIdHolding(parentNodeId);
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(graphId, parentNodeId, "HIERARCHICAL");

        return edges.stream()
                .map(entry -> graphStore.getNode(graphId, entry.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(n -> isUserNodeType(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public GraphNode updateNode(String nodeId, String title, String description,
                                 Map<String, Object> metadata) {
        String graphId = graphIdHolding(nodeId);
        Optional<MatrixGraphNode> nodeOpt = graphStore.getNode(graphId, nodeId);
        if (nodeOpt.isEmpty()) {
            throw new IllegalArgumentException("Node not found: " + nodeId);
        }

        MatrixGraphNode node = nodeOpt.get();
        boolean embeddingTextChanged = false;
        if (title != null) { node.setTitle(title); embeddingTextChanged = true; }
        if (description != null) { node.setDescription(description); embeddingTextChanged = true; }
        if (metadata != null) node.setMetadata(metadata);

        // Only re-embed when the embedding-relevant text (title/description) actually changed.
        // A metadata-only update (e.g. stashing extracted graph JSON on a DOCUMENT node) reuses
        // the existing vector — avoids the slow per-node 1-text-at-a-time re-embed.
        if (embeddingTextChanged) {
            graphStore.updateNode(graphId, node);
        } else {
            graphStore.updateNodeMetadata(graphId, node);
        }
        return convertToGraphNode(node, extractExternalId(nodeId));
    }

    @Override
    public void awaitPendingEmbeddings() {
        graphStore.awaitPendingEmbeddings();
    }

    /**
     * Batch-update KGE structural-embedding metadata for many nodes in a single in-process pass
     * WITHOUT triggering sentence re-embeds.
     *
     * <p>For each update the KGE keys are MERGED into the node's existing metadata (not replaced)
     * and the node is persisted via {@link MatrixGraphStore#updateNodeMetadata} which reuses the
     * cached sentence embedding vector rather than re-embedding the node's text.  All 5 903 entity
     * updates share one server-side loop (one subprocess RPC covers the whole batch), replacing
     * the previous 5 903 individual {@code updateNode} RPCs.</p>
     */
    @Override
    public int updateNodeKgeMetadataBatch(List<KnowledgeGraphService.NodeMetadataUpdate> updates) {
        if (updates == null || updates.isEmpty()) return 0;
        int count = 0;
        for (KnowledgeGraphService.NodeMetadataUpdate u : updates) {
            if (u.nodeId() == null || u.additionalMetadata() == null || u.additionalMetadata().isEmpty()) {
                continue;
            }
            try {
                String graphId = graphIdHolding(u.nodeId());
                Optional<MatrixGraphNode> nodeOpt = graphStore.getNode(graphId, u.nodeId());
                if (nodeOpt.isEmpty()) continue;
                MatrixGraphNode node = nodeOpt.get();
                // MERGE: preserve existing metadata, overwrite only the supplied KGE keys
                Map<String, Object> meta = node.getMetadata() != null
                        ? new HashMap<>(node.getMetadata()) : new HashMap<>();
                meta.putAll(u.additionalMetadata());
                node.setMetadata(meta);
                // updateNodeMetadata does NOT re-embed: it reuses the cached sentence vector
                graphStore.updateNodeMetadata(graphId, node);
                count++;
            } catch (Exception e) {
                log.debug("updateNodeKgeMetadataBatch: skipped node {} — {}", u.nodeId(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Batch-create edges, skipping pairs that already have an edge between them.
     *
     * <p>Server-side idempotency check: for each spec, checks {@link #edgeExists} before
     * calling {@link #createEdge} so the per-edge check happens locally (no extra RPC per edge).</p>
     */
    @Override
    public int createEdgesBatch(List<KnowledgeGraphService.EdgeSpec> specs) {
        if (specs == null || specs.isEmpty()) return 0;
        int created = 0;
        for (KnowledgeGraphService.EdgeSpec s : specs) {
            if (s.sourceNodeId() == null || s.targetNodeId() == null) continue;
            try {
                if (!edgeExists(s.sourceNodeId(), s.targetNodeId())) {
                    createEdgeWithMetadata(
                            s.sourceNodeId(), s.targetNodeId(), s.edgeType(), s.weight(),
                            s.label(), s.description(), s.metaJson(), s.provenance(),
                            s.factSheetId());
                    created++;
                }
            } catch (Exception e) {
                log.debug("createEdgesBatch: skipped edge {}->{} — {}", s.sourceNodeId(), s.targetNodeId(), e.getMessage());
            }
        }
        return created;
    }

    @Override
    public void deleteNode(String nodeId) {
        graphStore.removeNode(graphIdHolding(nodeId), nodeId);
    }

    @Override
    public List<GraphNode> getAllSources() {
        return allNodesAcrossGraphs().stream()
                .filter(n -> "SOURCE".equals(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> searchNodes(String query, NodeLevel type, int limit) {
        List<MatrixGraphNode> results = new ArrayList<>();
        for (String gid : allGraphIds()) {
            results.addAll(graphStore.searchNodes(gid, query, limit * 2));
        }

        return results.stream()
                .filter(n -> type == null || type.name().equals(n.getNodeType()))
                .filter(n -> isUserNodeType(n.getNodeType()))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type, int limit) {
        return allNodesAcrossGraphs().stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getAllNodes(int limit) {
        return allNodesAcrossGraphs().stream()
                .filter(n -> isUserNodeType(n.getNodeType()))
                .limit(limit)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type) {
        return allNodesAcrossGraphs().stream()
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
        return allNodesAcrossGraphs().stream()
                .filter(n -> idSet.contains(n.getNodeId()))
                .filter(n -> isUserNodeType(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED NODE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    /**
     * Count ENTITY nodes in a fact sheet via a streaming count over the in-memory
     * {@link ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph} without
     * allocating {@link ai.kompile.knowledgegraph.domain.GraphNode} wrapper objects.
     */
    @Override
    public long countEntityNodesInFactSheet(Long factSheetId) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> NodeLevel.ENTITY.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .count();
    }

    /**
     * Return one bounded page of ENTITY nodes from the in-memory graph using a streaming
     * skip+limit.  Only the nodes in the requested page are converted to
     * {@link ai.kompile.knowledgegraph.domain.GraphNode} wrappers, so the heap impact is
     * proportional to {@code pageSize} rather than the total entity count.
     */
    @Override
    public List<GraphNode> getEntityNodesInFactSheetPage(Long factSheetId, int offset, int pageSize) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> NodeLevel.ENTITY.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .skip(offset)
                .limit(pageSize)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<GraphNode> getNodesInFactSheet(Long factSheetId) {
        List<GraphNode> nodes = new ArrayList<>();
        int cursor = 0;
        GraphPage<GraphNode> page;
        do {
            page = getNodesInFactSheetPage(factSheetId, cursor, 1_000);
            nodes.addAll(page.items());
            cursor = page.nextCursor();
        } while (page.hasMore());
        return nodes;
    }

    @Override
    public GraphPage<GraphNode> getNodesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        MatrixGraphStore.ScanPage<MatrixGraphNode> page =
                graphStore.scanNodes(graphIdForFactSheet(factSheetId), cursor, pageSize);
        List<GraphNode> nodes = page.items().stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> isUserNodeType(n.getNodeType()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
        return new GraphPage<>(nodes, page.nextCursor(), page.hasMore());
    }

    @Override
    public List<GraphNode> getSourcesInFactSheet(Long factSheetId) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> "SOURCE".equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type, Long factSheetId) {
        String nodeId = type.name().toLowerCase() + "_" + externalId;
        return graphStore.getNode(graphIdForFactSheet(factSheetId), nodeId)
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .map(n -> convertToGraphNode(n, externalId));
    }

    @Override
    public List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit) {
        String lowerQuery = query != null ? query.toLowerCase() : "";
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> isUserNodeType(n.getNodeType()))
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
        List<GraphEdge> edges = new ArrayList<>();
        int cursor = 0;
        GraphPage<GraphEdge> page;
        do {
            page = getEdgesInFactSheetPage(factSheetId, cursor, 1_000);
            edges.addAll(page.items());
            cursor = page.nextCursor();
        } while (page.hasMore());
        return edges;
    }

    @Override
    public GraphPage<GraphEdge> getEdgesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        MatrixGraphStore.ScanPage<MatrixGraphStore.StoredEdge> page =
                graphStore.scanEdges(graphIdForFactSheet(factSheetId), cursor, pageSize);
        List<GraphEdge> edges = page.items().stream()
                .map(this::createEdgeObject)
                .collect(Collectors.toList());
        return new GraphPage<>(edges, page.nextCursor(), page.hasMore());
    }

    @Override
    public List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType) {
        String edgeTypeStr = edgeTypeToString(edgeType);
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdForFactSheet(factSheetId));
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
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(graphIdHolding(sourceNodeId), sourceNodeId, null);
        for (Map.Entry<String, Double> edge : edges) {
            if (edge.getKey().equals(targetNodeId)) {
                return createEdgeObject(sourceNodeId, targetNodeId,
                        EdgeType.USER_DEFINED, edge.getValue(), null);
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MENTION OPERATIONS — derived from ENTITY nodes whose
    // _sourceDocumentId metadata points to the given document node.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns synthetic {@link EntityMention} objects for all ENTITY nodes whose
     * {@code _sourceDocumentId} metadata key matches {@code node.getNodeId()} (after
     * stripping the {@code "document_"} prefix to recover the raw sourcePath).
     *
     * <p>The matrix store never persisted EntityMention rows, so we derive them on the fly
     * by scanning ENTITY nodes — same approach as {@link #getEntityNamesForNode}.</p>
     */
    @Override
    public List<EntityMention> getEntityMentionsForNode(GraphNode node) {
        if (node == null || node.getNodeId() == null) return Collections.emptyList();
        return getEntityMentionsForNode(node.getNodeId());
    }

    /**
     * Returns synthetic {@link EntityMention} objects for all ENTITY nodes whose
     * {@code _sourceDocumentId} metadata matches the document identified by {@code nodeId}.
     *
     * <p>Algorithm mirrors {@link #getEntityNamesForNode}: strip the {@code "document_"} prefix
     * to get the raw sourcePath, then scan ENTITY nodes for a matching {@code _sourceDocumentId}.
     * Each matching ENTITY becomes one synthetic mention (count=1) with the entity's factSheetId.</p>
     */
    @Override
    public List<EntityMention> getEntityMentionsForNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return Collections.emptyList();

        String prefix = NodeLevel.DOCUMENT.name().toLowerCase() + "_";
        String sourcePath = nodeId.startsWith(prefix) ? nodeId.substring(prefix.length()) : nodeId;

        List<EntityMention> result = new ArrayList<>();
        // Look up the GraphNode for the document so we can attach it to each mention
        GraphNode docNode = findNodeAnyGraph(nodeId)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .orElseGet(() -> GraphNode.builder().nodeId(nodeId).build());

        for (MatrixGraphNode mn : allNodesAcrossGraphs()) {
            if (!NodeLevel.ENTITY.name().equals(mn.getNodeType())) continue;
            Map<String, Object> meta = mn.getMetadata();
            if (meta == null) continue;
            Object sdId = meta.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID);
            if (sdId == null || !sourcePath.equals(String.valueOf(sdId))) continue;

            String name = normalizeEntityName(mn.getTitle());
            if (name == null || name.isBlank()) continue;

            // Extract entity_type from the MatrixGraphNode metadata directly
            String entityType = extractEntityTypeFromMetadata(mn.getMetadata());

            result.add(EntityMention.builder()
                    .node(docNode)
                    .entityName(name)
                    .entityType(entityType)
                    .mentionCount(1)
                    .factSheetId(mn.getFactSheetId())
                    .build());
        }
        log.debug("getEntityMentionsForNode: docNodeId={} → {} mentions", nodeId, result.size());
        return result;
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

    /**
     * Global (cross-fact-sheet) twin of {@link #findNodePairsWithSharedEntitiesInFactSheet}.
     *
     * <p>Aggregates across ALL fact sheets: loads every ENTITY node, groups by normalized title,
     * and emits {@code Object[]{docNodeId1, docNodeId2, sharedCount}} triples where
     * {@code sharedCount >= minShared}.  This is the same O(N) grouping algorithm used by the
     * fact-sheet-scoped variant — no entity-mention table required.</p>
     */
    @Override
    public List<Object[]> findNodePairsWithSharedEntities(int minShared) {
        List<GraphNode> entityNodes = getNodesByType(NodeLevel.ENTITY);
        if (entityNodes.isEmpty()) {
            log.debug("findNodePairsWithSharedEntities: no ENTITY nodes in graph");
            return Collections.emptyList();
        }

        // name → set of docNodeIds (across all fact sheets)
        Map<String, Set<String>> entityNameToDocIds = new LinkedHashMap<>();
        for (GraphNode entity : entityNodes) {
            String sourcePath = extractSourceDocumentId(entity);
            if (sourcePath == null || sourcePath.isBlank()) continue;
            String docNodeId = NodeLevel.DOCUMENT.name().toLowerCase() + "_" + sourcePath;
            String name = normalizeEntityName(entity.getTitle());
            if (name == null || name.isBlank()) continue;
            entityNameToDocIds.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(docNodeId);
        }

        Map<String, long[]> pairCount = new LinkedHashMap<>();
        for (Set<String> docIds : entityNameToDocIds.values()) {
            if (docIds.size() < 2) continue;
            List<String> docList = new ArrayList<>(docIds);
            for (int i = 0; i < docList.size(); i++) {
                for (int j = i + 1; j < docList.size(); j++) {
                    String d1 = docList.get(i);
                    String d2 = docList.get(j);
                    String pairKey = d1.compareTo(d2) <= 0 ? d1 + "||" + d2 : d2 + "||" + d1;
                    pairCount.computeIfAbsent(pairKey, k -> new long[]{0})[0]++;
                }
            }
        }

        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : pairCount.entrySet()) {
            long count = entry.getValue()[0];
            if (count >= minShared) {
                String[] ids = entry.getKey().split("\\|\\|", 2);
                result.add(new Object[]{ids[0], ids[1], count});
            }
        }
        log.info("findNodePairsWithSharedEntities: found {} doc pairs with >={} shared entities",
                result.size(), minShared);
        return result;
    }

    /**
     * Derives shared-entity document pairs from the live matrix/vector store WITHOUT the JPA
     * EntityMention table (which does not exist on this path).
     *
     * <p>Algorithm (O(N) in entity count, no O(N²) doc comparisons):</p>
     * <ol>
     *   <li>Load all ENTITY nodes scoped to {@code factSheetId}.</li>
     *   <li>For each entity, read {@code _sourceDocumentId} from its metadata to find which
     *       DOCUMENT it belongs to. Derive the DOCUMENT nodeId as {@code "document_" + sourcePath}
     *       (matching the {@link #addDocument} convention).</li>
     *   <li>Normalize the entity title (lower-case trim) as a stand-in for entity name.
     *       Entities with the same normalized title are considered the "same entity".</li>
     *   <li>Invert the name→docNodeId mapping into docPair→sharedCount using a set per entity
     *       name (O(N·D) where D = average documents per entity, typically very small).</li>
     *   <li>Return pairs whose shared count ≥ {@code minShared} as
     *       {@code Object[]{docNodeId1, docNodeId2, sharedCount}} — the same shape consumed by
     *       {@link ai.kompile.knowledgegraph.impl.GraphEdgeComputationServiceImpl}.</li>
     * </ol>
     */
    @Override
    public List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared) {
        if (factSheetId == null) return Collections.emptyList();

        // Step 1: load ENTITY nodes for this fact sheet
        List<GraphNode> entityNodes = getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        if (entityNodes.isEmpty()) {
            log.debug("findNodePairsWithSharedEntitiesInFactSheet: no ENTITY nodes for factSheet={}", factSheetId);
            return Collections.emptyList();
        }

        // Step 2: build name → set-of-docNodeIds map
        // _sourceDocumentId holds the sourcePath; DOCUMENT nodeId = "document_" + sourcePath
        Map<String, Set<String>> entityNameToDocIds = new LinkedHashMap<>();
        int entitiesWithDoc = 0;
        for (GraphNode entity : entityNodes) {
            String sourcePath = extractSourceDocumentId(entity);
            if (sourcePath == null || sourcePath.isBlank()) continue;
            String docNodeId = NodeLevel.DOCUMENT.name().toLowerCase() + "_" + sourcePath;
            String name = normalizeEntityName(entity.getTitle());
            if (name == null || name.isBlank()) continue;
            entityNameToDocIds.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(docNodeId);
            entitiesWithDoc++;
        }
        log.debug("findNodePairsWithSharedEntitiesInFactSheet: factSheet={} entities={} withDoc={} distinctNames={}",
                factSheetId, entityNodes.size(), entitiesWithDoc, entityNameToDocIds.size());

        // Step 3: invert to docPair → shared-entity count (O(N) pass — no N² doc comparisons)
        Map<String, long[]> pairCount = new LinkedHashMap<>();
        for (Set<String> docIds : entityNameToDocIds.values()) {
            if (docIds.size() < 2) continue; // entity appears in only one document
            List<String> docList = new ArrayList<>(docIds);
            for (int i = 0; i < docList.size(); i++) {
                for (int j = i + 1; j < docList.size(); j++) {
                    String d1 = docList.get(i);
                    String d2 = docList.get(j);
                    // Canonical key: alphabetically ordered pair so (A,B) and (B,A) collapse
                    String pairKey = d1.compareTo(d2) <= 0 ? d1 + "||" + d2 : d2 + "||" + d1;
                    pairCount.computeIfAbsent(pairKey, k -> new long[]{0})[0]++;
                }
            }
        }

        // Step 4: filter by minShared and emit Object[] triples
        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : pairCount.entrySet()) {
            long count = entry.getValue()[0];
            if (count >= minShared) {
                String[] ids = entry.getKey().split("\\|\\|", 2);
                result.add(new Object[]{ids[0], ids[1], count});
            }
        }
        log.info("findNodePairsWithSharedEntitiesInFactSheet: factSheet={} found {} doc pairs with >={} shared entities",
                factSheetId, result.size(), minShared);
        return result;
    }

    /**
     * Returns the normalized entity names (lower-case trimmed titles) of all ENTITY nodes
     * whose {@code _sourceDocumentId} provenance key points to the document identified by
     * {@code docNodeId}.
     *
     * <p>The DOCUMENT nodeId convention on the matrix store is {@code "document_" + sourcePath}
     * (see {@link #addDocument}), so the reverse mapping is {@code sourcePath = docNodeId.substring(9)}.
     * We scan ENTITY nodes across all fact sheets rather than scoping to one, so callers that
     * don't have a fact-sheet context still get meaningful results.</p>
     *
     * @param docNodeId the DOCUMENT node's nodeId (e.g. {@code "document_/path/to/file.pdf"})
     * @return normalized entity names mentioned in that document; empty list if none found
     */
    @Override
    public List<String> getEntityNamesForNode(String docNodeId) {
        if (docNodeId == null || docNodeId.isBlank()) return Collections.emptyList();

        // Strip the "document_" prefix to recover the sourcePath / _sourceDocumentId value
        String prefix = NodeLevel.DOCUMENT.name().toLowerCase() + "_";
        String sourcePath = docNodeId.startsWith(prefix) ? docNodeId.substring(prefix.length()) : docNodeId;

        List<String> names = new ArrayList<>();
        for (MatrixGraphNode node : allNodesAcrossGraphs()) {
            if (!NodeLevel.ENTITY.name().equals(node.getNodeType())) continue;
            Map<String, Object> meta = node.getMetadata();
            if (meta == null) continue;
            Object sdId = meta.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID);
            if (sdId == null) continue;
            if (!sourcePath.equals(String.valueOf(sdId))) continue;
            MatrixGraphNode mn = node;
            String name = normalizeEntityName(mn.getTitle());
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Extracts the entity_type string from a raw MatrixGraphNode metadata map.
     * Returns null if no entity type key is found.
     */
    private static String extractEntityTypeFromMetadata(Map<String, Object> meta) {
        if (meta == null) return null;
        for (String key : new String[]{"entity_type", "entityType", "entity_category", "entityCategory"}) {
            Object val = meta.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /** Extracts the {@code _sourceDocumentId} value from a GraphNode's metadata. */
    private static String extractSourceDocumentId(GraphNode node) {
        Map<String, Object> meta = node.getMetadata();
        if (meta == null) return null;
        Object val = meta.get(GraphProvenanceKeys.SOURCE_DOCUMENT_ID);
        return val != null ? String.valueOf(val) : null;
    }

    /** Normalizes an entity title for shared-entity matching: lower-case, trimmed, collapsed whitespace. */
    private static String normalizeEntityName(String title) {
        if (title == null) return null;
        return title.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Returns ENTITY nodes whose normalized title matches {@code entityName} (case-insensitive,
     * trimmed, collapsed whitespace).  On the matrix store there is no EntityMention index, so
     * we scan ENTITY nodes and compare normalized titles — consistent with the normalization
     * used by {@link #findNodePairsWithSharedEntitiesInFactSheet} and the JPA mention table.
     */
    @Override
    public List<GraphNode> getNodesWithEntity(String entityName) {
        if (entityName == null || entityName.isBlank()) return Collections.emptyList();
        String normalizedTarget = normalizeEntityName(entityName);
        if (normalizedTarget == null || normalizedTarget.isBlank()) return Collections.emptyList();

        List<GraphNode> result = new ArrayList<>();
        for (MatrixGraphNode mn : allNodesAcrossGraphs()) {
            if (!NodeLevel.ENTITY.name().equals(mn.getNodeType())) continue;
            String name = normalizeEntityName(mn.getTitle());
            if (normalizedTarget.equals(name)) {
                result.add(convertToGraphNode(mn, extractExternalId(mn.getNodeId())));
            }
        }
        log.debug("getNodesWithEntity: entityName='{}' → {} nodes", entityName, result.size());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 Double weight, String description) {
        String edgeTypeStr = edgeTypeToString(edgeType);
        boolean bidirectional = edgeType != EdgeType.HIERARCHICAL;

        graphStore.addEdge(graphIdHolding(sourceNodeId), sourceNodeId, targetNodeId,
                weight != null ? weight : 1.0, edgeTypeStr, bidirectional);

        return createEdgeObject(sourceNodeId, targetNodeId, edgeType, weight, description);
    }

    @Override
    public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 String relationType, Double weight, String description) {
        // Store the semantic relation as an explicit first-class field on the vector store; keep using
        // it as the adjacency routing key too (backward-compatible with the extractor's convention,
        // and required so multiple distinct relations between the same pair stay separate).
        boolean hasRelation = relationType != null && !relationType.isBlank();
        String storeKey = hasRelation ? relationType : edgeTypeToString(edgeType);
        boolean bidirectional = edgeType != EdgeType.HIERARCHICAL;
        graphStore.addEdge(graphIdHolding(sourceNodeId), sourceNodeId, targetNodeId,
                weight != null ? weight : 1.0, storeKey, bidirectional, hasRelation ? relationType : null);
        GraphEdge edge = createEdgeObject(sourceNodeId, targetNodeId, storeKey, weight, description);
        if (hasRelation) {
            edge.setRelationType(relationType);
            edge.setEdgeId(sourceNodeId + "::" + targetNodeId + "::" + relationType);
        }
        return edge;
    }

    /**
     * [H-2 / M-7] Matrix store override that parses confidence and provenance from
     * {@code metaJson} and persists them as first-class edge fields in the adjacency store so they
     * survive vector-store round-trips. This is the path used by {@link ai.kompile.knowledgegraph.io.GraphIOService}
     * on import to restore edge quality fields that were exported in the portable JSON.
     */
    @Override
    public GraphEdge createEdgeWithMetadata(String sourceNodeId, String targetNodeId,
                                             EdgeType edgeType, Double weight,
                                             String label, String description,
                                             String metaJson, EdgeProvenance provenance,
                                             Long factSheetId) {
        boolean hasRelation = label != null && !label.isBlank();
        String storeKey = hasRelation ? label : edgeTypeToString(edgeType);
        // Parse the edge metaJson once into a typed view (no stringly-typed key lookups).
        EdgeMetadata meta = parseEdgeMetadata(metaJson);
        EdgeProvenance pType = parseProvenanceType(meta.provenanceType());
        if (pType == null) {
            pType = provenance;
        }
        String sourceProvenance = meta.provenance() != null
                ? meta.provenance() : provenance == null ? null : provenance.name();

        // Preserve the open, decision-relevant metadata bag across the matrix/vector boundary.
        // Provenance classification is duplicated into the bag because the matrix adjacency record
        // has no dedicated fields for it; the typed GraphEdge fields remain authoritative in memory.
        Map<String, Object> storedMetadata = new LinkedHashMap<>();
        if (meta.metadata() != null) {
            storedMetadata.putAll(meta.metadata());
        }
        if (sourceProvenance != null) {
            storedMetadata.putIfAbsent("provenance", sourceProvenance);
        }
        if (pType != null) {
            storedMetadata.putIfAbsent("provenanceType", pType.name());
        }
        if (meta.similarityScore() != null) {
            storedMetadata.putIfAbsent("similarityScore", meta.similarityScore());
        }

        // [M-3] Honor an explicitly-imported bidirectional flag instead of always deriving it from
        // the edge type — otherwise a bidirectional edge silently becomes directional after a clone.
        boolean bidirectional = meta.bidirectional() != null
                ? meta.bidirectional() : (edgeType != EdgeType.HIERARCHICAL);
        Double confidence = meta.confidence();
        String desc = description != null ? description : meta.description();

        // Prefer the explicit fact sheet when provided (import path), else locate the edge's owning graph.
        String edgeGid = factSheetId != null ? graphIdForFactSheet(factSheetId) : graphIdHolding(sourceNodeId);
        graphStore.addEdge(edgeGid, sourceNodeId, targetNodeId,
                weight != null ? weight : 1.0, storeKey, bidirectional,
                hasRelation ? label : null, confidence, desc);
        if (!storedMetadata.isEmpty()) {
            graphStore.mergeEdgeMetadata(edgeGid, sourceNodeId, targetNodeId, storeKey, storedMetadata);
        }

        GraphEdge edge = createEdgeObject(sourceNodeId, targetNodeId, storeKey, weight, desc);
        if (hasRelation) {
            edge.setRelationType(label);
            edge.setEdgeId(sourceNodeId + "::" + targetNodeId + "::" + label);
        }
        if (confidence != null) {
            edge.setConfidence(confidence);
        }
        edge.setBidirectional(bidirectional);
        edge.setProvenance(sourceProvenance);
        edge.setMetadataJson(serializeMetadata(storedMetadata));
        if (factSheetId != null) {
            edge.setFactSheetId(factSheetId);
        }
        if (meta.label() != null) {
            edge.setLabel(meta.label());
        }
        if (meta.sharedEntitiesJson() != null) {
            edge.setSharedEntitiesJson(meta.sharedEntitiesJson());
        }
        if (meta.similarityScore() != null) {
            edge.setSimilarityScore(meta.similarityScore());
        }
        if (pType != null) {
            edge.setProvenanceType(pType);
        }
        return edge;
    }

    /** Parse an {@link EdgeProvenance} name; null/blank/unknown → null. */
    private static EdgeProvenance parseProvenanceType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return EdgeProvenance.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Parse the edge {@code metaJson} into a typed {@link EdgeMetadata}; never null. */
    private EdgeMetadata parseEdgeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return EdgeMetadata.EMPTY;
        }
        try {
            return objectMapper.readValue(json, EdgeMetadata.class);
        } catch (Exception ignored) {
            return EdgeMetadata.EMPTY;
        }
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

        List<Map.Entry<String, Double>> edges = graphStore.getEdges(graphIdHolding(sourceId), sourceId, null);
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
        // Iterate per stored edge type so the real EdgeType + semantic relationType are preserved
        // (rather than flattening every edge to USER_DEFINED).
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdHolding(nodeId));
        if (graphOpt.isEmpty()) {
            return Collections.emptyList();
        }
        AdjacencyMatrixGraph graph = graphOpt.get();
        List<GraphEdge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String edgeTypeStr : graph.getEdgeTypes()) {
            for (Map.Entry<String, Double> neighbor : graph.getNeighbors(nodeId, edgeTypeStr)) {
                String key = neighbor.getKey() + "::" + edgeTypeStr;
                if (seen.add(key)) {
                    result.add(buildEdgeWithRelation(graph, nodeId, neighbor.getKey(),
                            edgeTypeStr, neighbor.getValue()));
                }
            }
        }
        return result;
    }

    @Override
    public List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType) {
        String edgeTypeStr = edgeTypeToString(edgeType);
        List<Map.Entry<String, Double>> edges = graphStore.getEdges(graphIdHolding(nodeId), nodeId, edgeTypeStr);

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

        graphStore.addEdge(graphIdHolding(sourceId), sourceId, targetId,
                weight != null ? weight : 1.0, edgeType, false);

        EdgeType type = edgeTypeFromString(edgeType);

        return createEdgeObject(sourceId, targetId, type, weight, description);
    }

    @Override
    public int updateEdgeMetadataBatch(List<KnowledgeGraphService.EdgeMetadataUpdate> updates) {
        if (updates == null || updates.isEmpty()) return 0;
        int count = 0;
        for (KnowledgeGraphService.EdgeMetadataUpdate update : updates) {
            if (update == null || update.edgeId() == null
                    || update.additionalMetadata() == null || update.additionalMetadata().isEmpty()) {
                continue;
            }
            String[] parts = update.edgeId().split("::", 3);
            if (parts.length < 3) {
                log.debug("updateEdgeMetadataBatch: skipped invalid edge id {}", update.edgeId());
                continue;
            }
            String sourceId = parts[0];
            String targetId = parts[1];
            String edgeTypeKey = parts[2];
            try {
                if (graphStore.mergeEdgeMetadata(graphIdHolding(sourceId), sourceId, targetId,
                        edgeTypeKey, update.additionalMetadata())) {
                    count++;
                }
            } catch (Exception e) {
                log.debug("updateEdgeMetadataBatch: skipped edge {} — {}", update.edgeId(), e.getMessage());
            }
        }
        return count;
    }

    @Override
    public void deleteEdge(String edgeId) {
        String[] parts = edgeId.split("::");
        if (parts.length >= 2) {
            String edgeType = parts.length >= 3 ? parts[2] : null;
            graphStore.removeEdge(graphIdHolding(parts[0]), parts[0], parts[1], edgeType);
        }
    }

    @Override
    public boolean edgeExists(String sourceNodeId, String targetNodeId) {
        return graphStore.hasEdge(graphIdHolding(sourceNodeId), sourceNodeId, targetNodeId, null);
    }

    @Override
    public List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit) {
        // Matrix-based implementation: search edges by filtering on type across all segmented graphs.
        // This is a simplified implementation - full text search would require additional indexing.
        List<GraphEdge> results = new ArrayList<>();
        String edgeTypeStr = edgeType != null ? edgeTypeToString(edgeType) : null;
        String lowerQuery = query != null ? query.toLowerCase() : null;

        for (String gid : allGraphIds()) {
            Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(gid);
            if (graphOpt.isEmpty()) {
                continue;
            }
            AdjacencyMatrixGraph graph = graphOpt.get();

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
                            .edgeType(edgeTypeFromString(type))
                            .weight(weight)
                            .build();
                        results.add(edge);

                        if (results.size() >= limit) {
                            return results;
                        }
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
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdHolding(nodeId));
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
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdHolding(nodeId));
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
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdHolding(queryNodeId));
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
        Map<String, Object> stats = new LinkedHashMap<>();

        // Aggregate across every segmented (per-fact-sheet) graph so global stats stay correct
        // now that nodes/edges live in per-fact-sheet graphs rather than one global graph.
        List<String> graphIds = allGraphIds();
        long totalNodes = 0;
        long totalEdges = 0;
        Set<String> edgeTypes = new LinkedHashSet<>();
        for (String gid : graphIds) {
            Map<String, Object> ms = graphStore.getGraphStatistics(gid);
            totalNodes += asLong(ms.get("nodeCount"));
            totalEdges += asLong(ms.get("edgeCount"));
            Object et = ms.get("edgeTypes");
            if (et instanceof Set<?> s) {
                for (Object o : s) {
                    edgeTypes.add(String.valueOf(o));
                }
            }
        }
        stats.put("totalNodes", totalNodes);

        // Count by type across all graphs
        List<MatrixGraphNode> allNodes = allNodesAcrossGraphs();
        Map<String, Long> typeCounts = allNodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getNodeType() != null ? n.getNodeType() : "UNKNOWN",
                        Collectors.counting()
                ));

        // Census EVERY NodeLevel (incl. TABLE, ATTACHMENT) into nodesByType — identical contract
        // to the JPA store so the index-browser renders the same regardless of active backend.
        Map<String, Long> nodesByType = new LinkedHashMap<>();
        for (NodeLevel level : NodeLevel.values()) {
            nodesByType.put(level.name(), typeCounts.getOrDefault(level.name(), 0L));
        }
        stats.put("nodesByType", nodesByType);

        stats.put("sourceCount", nodesByType.getOrDefault("SOURCE", 0L));
        stats.put("documentCount", nodesByType.getOrDefault("DOCUMENT", 0L));
        stats.put("snippetCount", nodesByType.getOrDefault("SNIPPET", 0L));
        stats.put("entityCount", nodesByType.getOrDefault("ENTITY", 0L));
        stats.put("customCount", nodesByType.getOrDefault("CUSTOM", 0L));
        stats.put("tableCount", nodesByType.getOrDefault("TABLE", 0L));
        stats.put("attachmentCount", nodesByType.getOrDefault("ATTACHMENT", 0L));
        stats.put("totalEdges", totalEdges);

        // Edge type counts aggregated across all segmented graphs
        Map<String, Long> edgesByType = new LinkedHashMap<>();
        for (String edgeType : edgeTypes) {
            long count = 0;
            for (String gid : graphIds) {
                count += countEdgesByType(gid, edgeType);
            }
            edgesByType.put(edgeType, count);
            stats.put("edges_" + edgeType.toLowerCase(), count);
        }
        stats.put("edgesByType", edgesByType);

        return stats;
    }

    /** Null-safe Number→long for matrix-store stat values (Integer or Long). */
    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes) {
        // Read the segmented graph holding the root node, or aggregate across all segmented
        // (per-fact-sheet) graphs when no root is given.
        List<String> graphIds = rootNodeId != null
                ? List.of(graphIdHolding(rootNodeId))
                : allGraphIds();

        List<MatrixGraphNode> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> nodeData = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        int totalNodes = 0;
        long totalEdgesAvail = 0;
        boolean loadedAny = false;

        for (String gid : graphIds) {
            Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(gid);
            if (graphOpt.isEmpty()) {
                continue;
            }
            loadedAny = true;
            AdjacencyMatrixGraph graph = graphOpt.get();
            totalNodes += graph.getNodeCount();
            totalEdgesAvail += graph.getEdgeCount();

            // Select this graph's visible nodes (root-scoped BFS, or whole-graph capped).
            List<MatrixGraphNode> gNodes;
            if (rootNodeId != null) {
                Set<String> visitedIds = new HashSet<>();
                gNodes = new ArrayList<>();
                graph.getNode(rootNodeId).ifPresent(gNodes::add);
                visitedIds.add(rootNodeId);
                collectNodesAtDepth(graph, rootNodeId, depth, visitedIds, gNodes, maxNodes);
            } else {
                gNodes = graph.getAllNodes();
                if (maxNodes > 0 && nodes.size() + gNodes.size() > maxNodes) {
                    int room = Math.max(0, maxNodes - nodes.size());
                    gNodes = gNodes.stream().limit(room).collect(Collectors.toList());
                }
            }
            if (gNodes.isEmpty()) {
                continue;
            }
            nodes.addAll(gNodes);

            // Collect edges between visible nodes WITHIN this graph.
            Set<String> gNodeIds = gNodes.stream().map(MatrixGraphNode::getNodeId).collect(Collectors.toSet());
            for (MatrixGraphNode node : gNodes) {
                for (String edgeType : graph.getEdgeTypes()) {
                    List<Map.Entry<String, Double>> typeEdges = graph.getNeighbors(node.getNodeId(), edgeType);
                    for (Map.Entry<String, Double> edge : typeEdges) {
                        if (gNodeIds.contains(edge.getKey())) {
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

            // Convert to visualization format (pair each node with its OWN graph).
            for (MatrixGraphNode n : gNodes) {
                nodeData.add(nodeToVisualizationMap(n, graph));
            }
        }

        if (!loadedAny) {
            return Map.of("nodes", List.of(), "edges", List.of(),
                    "metadata", Map.of(
                            "nodeCount", 0, "edgeCount", 0,
                            "returnedNodes", 0, "returnedEdges", 0,
                            "totalAvailableNodes", 0, "totalAvailableEdges", 0,
                            "capped", false));
        }

        // Rich metadata so the frontend can show "N of M", render a per-type legend, and run its own
        // client-side filtering. The full graph is returned by default (maxNodes <= 0); a positive
        // maxNodes is the only thing that caps, and then totalAvailable* reveals what was held back.
        boolean capped = nodes.size() < totalNodes;
        long totalEdges = capped ? totalEdgesAvail : edges.size();

        Map<String, Integer> nodeTypeCounts = new LinkedHashMap<>();
        for (MatrixGraphNode n : nodes) {
            nodeTypeCounts.merge(n.getNodeType() != null ? n.getNodeType() : "UNKNOWN", 1, Integer::sum);
        }
        Map<String, Integer> edgeTypeCounts = new LinkedHashMap<>();
        for (Map<String, Object> e : edges) {
            edgeTypeCounts.merge(String.valueOf(e.get("type")), 1, Integer::sum);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeCount", nodes.size());
        metadata.put("edgeCount", edges.size());
        metadata.put("returnedNodes", nodes.size());
        metadata.put("returnedEdges", edges.size());
        metadata.put("totalAvailableNodes", totalNodes);
        metadata.put("totalAvailableEdges", totalEdges);
        metadata.put("capped", capped);
        metadata.put("maxNodes", maxNodes);
        metadata.put("nodeTypeCounts", nodeTypeCounts);
        metadata.put("edgeTypeCounts", edgeTypeCounts);

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edges);
        result.put("metadata", metadata);

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

    /**
     * Builds a {@link GraphEdge} from a stored adjacency edge-type key, deriving the structural
     * {@link EdgeType} and the semantic {@code relationType} (the key itself when semantic, else null).
     * For semantic edges the edgeId incorporates the relation so distinct relations between the same
     * pair stay distinct.
     */
    private GraphEdge createEdgeObject(String sourceId, String targetId, String edgeTypeKey,
                                        Double weight, String description) {
        GraphEdge edge = createEdgeObject(sourceId, targetId, edgeTypeFromString(edgeTypeKey), weight, description);
        String relationType = semanticRelationType(edgeTypeKey);
        if (relationType != null) {
            edge.setRelationType(relationType);
            edge.setEdgeId(sourceId + "::" + targetId + "::" + relationType);
        }
        return edge;
    }

    /**
     * Builds a {@link GraphEdge} for a stored adjacency edge, surfacing the relation from the explicit
     * first-class {@code relationType} field when present, and falling back to the adjacency-key
     * heuristic for edges persisted before relation types were stored as a field.
     */
    private GraphEdge buildEdgeWithRelation(AdjacencyMatrixGraph graph, String sourceId,
                                            String targetId, String edgeTypeKey, Double weight) {
        GraphEdge edge = createEdgeObject(sourceId, targetId, edgeTypeKey, weight, null);
        String explicit = graph.getEdgeRelationType(edgeTypeKey, sourceId, targetId);
        if (explicit != null && !explicit.isBlank()) {
            edge.setRelationType(explicit);
            edge.setEdgeId(sourceId + "::" + targetId + "::" + explicit);
        }
        AdjacencyMatrixGraph.EdgeMeta meta = graph.getEdgeMeta(edgeTypeKey, sourceId, targetId);
        if (meta != null) {
            if (meta.confidence() != null) edge.setConfidence(meta.confidence());
            if (meta.bidirectional() != null) edge.setBidirectional(meta.bidirectional());
            if (meta.description() != null && !meta.description().isBlank()) {
                edge.setDescription(meta.description());
            }
            if (meta.metadata() != null && !meta.metadata().isEmpty()) {
                edge.setMetadataJson(serializeMetadata(meta.metadata()));
            }
        }
        return edge;
    }

    private GraphEdge createEdgeObject(MatrixGraphStore.StoredEdge stored) {
        EdgeType edgeType = edgeTypeFromString(stored.edgeType());
        String identity = stored.relationType() != null && !stored.relationType().isBlank()
                ? stored.relationType() : edgeType.name();
        GraphEdge edge = GraphEdge.builder()
                .edgeId(stored.sourceNodeId() + "::" + stored.targetNodeId() + "::" + identity)
                .sourceNode(GraphNode.builder().nodeId(stored.sourceNodeId()).build())
                .targetNode(GraphNode.builder().nodeId(stored.targetNodeId()).build())
                .edgeType(edgeType)
                .relationType(stored.relationType())
                .weight(stored.weight())
                .description(stored.description())
                .confidence(stored.confidence())
                .bidirectional(stored.bidirectional())
                .build();
        if (stored.metadata() != null && !stored.metadata().isEmpty()) {
            edge.setMetadataJson(serializeMetadata(stored.metadata()));
        }
        return edge;
    }

    private GraphEdge createEdgeObject(String sourceId, String targetId, EdgeType edgeType,
                                        Double weight, String description) {
        // Populate sourceNode and targetNode so JSON serialization includes
        // sourceNodeId/targetNodeId and node titles for the entity browser.
        GraphNode sourceNode = findNodeAnyGraph(sourceId)
                .map(n -> convertToGraphNode(n, extractExternalId(n.getNodeId())))
                .orElseGet(() -> GraphNode.builder().nodeId(sourceId).build());
        GraphNode targetNode = findNodeAnyGraph(targetId)
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
        // Must match KnowledgeGraphServiceImpl.getTypeOrder(NodeLevel) so visualization ordering
        // is identical across stores; TABLE sorts right after DOCUMENT (not last).
        return switch (type) {
            case "SOURCE" -> 0;
            case "DOCUMENT" -> 1;
            case "TABLE" -> 2;
            case "ENTITY" -> 3;
            case "CUSTOM" -> 4;
            case "SNIPPET" -> 5;
            case "ATTACHMENT" -> 6;
            default -> 7;
        };
    }

    private boolean isHierarchicalEdge(String sourceId, String targetId) {
        return graphStore.hasEdge(graphIdHolding(sourceId), sourceId, targetId, "HIERARCHICAL");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE EMBEDDINGS (store-agnostic portability — used by GraphEmbeddingSidecar)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Export the live matrix/vector-store embeddings for a fact sheet's nodes. Reads the
     * per-node vectors held in {@link AdjacencyMatrixGraph} (the text embeddings computed by
     * {@code MatrixGraphConstructor}); all-zero rows (nodes that were never embedded) are skipped.
     */
    @Override
    public Map<String, INDArray> exportNodeEmbeddings(Long factSheetId) {
        String gid = graphIdForFactSheet(factSheetId);
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(gid);
        if (graphOpt.isEmpty()) {
            return Map.of();
        }
        AdjacencyMatrixGraph graph = graphOpt.get();
        Map<String, INDArray> result = new LinkedHashMap<>();
        for (MatrixGraphNode n : graphStore.getAllNodes(gid)) {
            if (factSheetId != null && !factSheetId.equals(n.getFactSheetId())) {
                continue;
            }
            INDArray emb = graph.getNodeEmbedding(n.getNodeId());
            // getNodeEmbedding returns a (possibly zero) matrix row; only keep nodes that
            // actually carry a vector so we don't serialize empty placeholders.
            if (emb != null && emb.amaxNumber().doubleValue() > 0.0) {
                result.put(n.getNodeId(), emb.dup());
            }
        }
        return result;
    }

    /**
     * Reattach node embeddings to the live store in one batched call, which also re-indexes
     * them into the vector store (warming similarity search after a clone). Embeddings for
     * nodeIds that are not present in the rehydrated graph are skipped.
     */
    @Override
    public int applyNodeEmbeddings(Map<String, INDArray> embeddingsByNodeId) {
        if (embeddingsByNodeId == null || embeddingsByNodeId.isEmpty()) {
            return 0;
        }
        // Group embeddings by the segmented graph that holds each node, then store per graph in one
        // batched call each (nodeIds may belong to different per-fact-sheet graphs after a clone).
        Map<String, List<String>> idsByGraph = new LinkedHashMap<>();
        Map<String, List<INDArray>> rowsByGraph = new LinkedHashMap<>();
        int dim = -1;
        for (Map.Entry<String, INDArray> e : embeddingsByNodeId.entrySet()) {
            INDArray vec = e.getValue();
            if (vec == null) {
                continue;
            }
            String gid = graphIdHolding(e.getKey());
            if (graphStore.getNode(gid, e.getKey()).isEmpty()) {
                continue; // node must exist in a rehydrated graph to attach an embedding
            }
            int len = (int) vec.length();
            if (dim < 0) {
                dim = len;
            } else if (len != dim) {
                continue; // skip rows whose dimension disagrees with the batch
            }
            idsByGraph.computeIfAbsent(gid, k -> new ArrayList<>()).add(e.getKey());
            rowsByGraph.computeIfAbsent(gid, k -> new ArrayList<>()).add(vec.reshape(1, len));
        }
        if (idsByGraph.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (Map.Entry<String, List<String>> entry : idsByGraph.entrySet()) {
            String gid = entry.getKey();
            List<String> nodeIds = entry.getValue();
            List<INDArray> rows = rowsByGraph.get(gid);
            INDArray stacked = Nd4j.create(nodeIds.size(), dim);
            for (int i = 0; i < rows.size(); i++) {
                stacked.putRow(i, rows.get(i));
            }
            graphStore.storeNodeEmbeddings(gid, nodeIds, stacked);
            applied += nodeIds.size();
        }
        return applied;
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
        // maxNodes <= 0 means unlimited — only the depth bound stops the traversal.
        if (depth <= 0 || (maxNodes > 0 && result.size() >= maxNodes)) {
            return;
        }

        List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(nodeId, null);
        for (Map.Entry<String, Double> neighbor : neighbors) {
            if (maxNodes > 0 && result.size() >= maxNodes) {
                break;
            }
            if (!visited.contains(neighbor.getKey())) {
                visited.add(neighbor.getKey());
                graph.getNode(neighbor.getKey()).ifPresent(result::add);
                collectNodesAtDepth(graph, neighbor.getKey(), depth - 1, visited, result, maxNodes);
            }
        }
    }

    private Map<String, Object> nodeToVisualizationMap(MatrixGraphNode node, AdjacencyMatrixGraph graph) {
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

        // Include childCount and edgeCount for parity with JPA visualization (graph passed in — no reload).
        List<Map.Entry<String, Double>> children = graph.getNeighbors(node.getNodeId(), "HIERARCHICAL");
        map.put("childCount", children != null ? children.size() : 0);
        List<Map.Entry<String, Double>> allEdges = graph.getNeighbors(node.getNodeId(), null);
        map.put("edgeCount", allEdges != null ? allEdges.size() : 0);

        // Parsed metadata so the visualizer can render TABLE / structured nodes (parity with JPA).
        map.put("metadata", node.getMetadata() != null
                ? node.getMetadata() : Collections.<String, Object>emptyMap());

        return map;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNT / STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public long countNodesByType(NodeLevel type) {
        return allNodesAcrossGraphs().stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .count();
    }

    @Override
    public long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> type.name().equals(n.getNodeType()))
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .count();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * [FIX-2] Flush all in-memory graph state (including adjacency matrices carrying SHARED_ENTITY
     * and other cross-doc edges) to the persistent vector store.
     *
     * <p>Prior behaviour was a no-op — edges added via {@link #createEdge} / {@link #createEdgeWithMetadata}
     * only mutated the in-memory {@link ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph};
     * nothing persisted until {@link ai.kompile.knowledgegraph.matrix.constructor.MatrixGraphConstructor}
     * flushed during GRAPH_EXTRACTION.  SHARED_ENTITY and embedding-similarity edges created in
     * EDGE_COMPUTATION were therefore silently lost on JVM restart.</p>
     *
     * <p>Now delegates to {@link ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore#flush()}
     * which saves every cached {@link ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph}
     * and calls {@code vectorStore.flushAndCommit()}, making the edges durable.</p>
     */
    @Override
    public void flushPendingNodes() {
        log.info("MatrixKnowledgeGraphService.flushPendingNodes: persisting in-memory graph state to vector store");
        graphStore.flush();
    }

    @Override
    public void deleteByFactSheetId(Long factSheetId) {
        String gid = graphIdForFactSheet(factSheetId);
        List<MatrixGraphNode> toDelete = graphStore.getAllNodes(gid).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .collect(Collectors.toList());
        for (MatrixGraphNode node : toDelete) {
            graphStore.removeNode(gid, node.getNodeId());
        }
        log.info("Deleted {} nodes for factSheetId={}", toDelete.size(), factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNING / MAINTENANCE QUERIES — matrix/vector-store backend
    //
    // "Stale" is tracked in node/edge metadata under the "_stale" key (Boolean
    // true).  Nodes whose matrixStore-level metadata contains "_stale=true" are
    // considered soft-deleted; hardDeleteStaleNodes purges them once the grace
    // period (stored as "_staleAt" epoch-millis string) has elapsed.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns nodeIds of nodes in the fact sheet with degree 0 (no edges to or from them)
     * whose {@link NodeLevel} is in {@code levels} and that are not already soft-deleted.
     * MatrixGraphNode stores its level as a String, so the requested levels are matched by name.
     */
    @Override
    public List<String> findOrphanNodeIds(Long factSheetId, Set<NodeLevel> levels) {
        Set<String> wanted =
                ((levels == null || levels.isEmpty()) ? DEFAULT_ORPHAN_LEVELS : levels)
                        .stream().map(Enum::name).collect(Collectors.toSet());
        String gid = graphIdForFactSheet(factSheetId);
        return graphStore.getAllNodes(gid).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> n.getNodeType() != null && wanted.contains(n.getNodeType()))
                .filter(n -> !isMatrixNodeStale(n))
                .filter(n -> {
                    List<Map.Entry<String, Double>> edges =
                            graphStore.getEdges(gid, n.getNodeId(), null);
                    return edges == null || edges.isEmpty();
                })
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    /**
     * Returns nodeIds of non-stale nodes in the fact sheet whose metadata
     * "confidence" value is below {@code minConfidence}.
     */
    @Override
    public List<String> findLowConfidenceNodeIds(Long factSheetId, double minConfidence) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> !isMatrixNodeStale(n))
                .filter(n -> {
                    Object conf = n.getMetadata() != null ? n.getMetadata().get("confidence") : null;
                    if (conf instanceof Number num) {
                        return num.doubleValue() < minConfidence;
                    }
                    return false;
                })
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    /**
     * Returns the edgeIds of edges in {@code factSheetId} whose stored confidence is below
     * {@code minConfidence}.
     *
     * <p>Edge confidence is stored in {@link AdjacencyMatrixGraph.EdgeMeta} (set via
     * {@link #createEdgeWithMetadata}).  We enumerate edges via {@link #getEdgesInFactSheet},
     * then look up each edge's {@code EdgeMeta} from the adjacency store using the edgeType key
     * encoded in the {@code edgeId} ({@code "srcId::tgtId::edgeTypeKey"}).  Edges with no stored
     * confidence metadata are treated as fully confident (skipped).</p>
     */
    @Override
    public List<String> findLowConfidenceEdgeIds(Long factSheetId, double minConfidence) {
        if (factSheetId == null) return Collections.emptyList();
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphIdForFactSheet(factSheetId));
        if (graphOpt.isEmpty()) return Collections.emptyList();
        AdjacencyMatrixGraph graph = graphOpt.get();

        List<String> result = new ArrayList<>();
        for (GraphEdge edge : getEdgesInFactSheet(factSheetId)) {
            // edgeId format: "sourceId::targetId::edgeTypeKey"
            String edgeId = edge.getEdgeId();
            if (edgeId == null) continue;
            String[] parts = edgeId.split("::", 3);
            if (parts.length < 3) continue;
            String srcId = parts[0];
            String tgtId = parts[1];
            String edgeKey = parts[2];

            AdjacencyMatrixGraph.EdgeMeta meta = graph.getEdgeMeta(edgeKey, srcId, tgtId);
            if (meta == null || meta.confidence() == null) continue;  // no confidence stored → treat as high confidence
            if (meta.confidence() < minConfidence) {
                result.add(edgeId);
            }
        }
        log.debug("findLowConfidenceEdgeIds: factSheetId={} minConfidence={} → {} low-confidence edges",
                factSheetId, minConfidence, result.size());
        return result;
    }

    /**
     * Count non-stale nodes in the fact sheet (all types).
     */
    @Override
    public long countActiveNodes(Long factSheetId) {
        return graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> !isMatrixNodeStale(n))
                .count();
    }

    /**
     * Returns the edgeIds of all active (non-stale) edges in {@code factSheetId}.
     *
     * <p>The matrix store does not independently track edge staleness — staleness is a node-level
     * concept (see {@code _stale} in node metadata).  All edges returned by
     * {@link #getEdgesInFactSheet} connect existing (non-deleted) nodes, so they are considered
     * active.  We filter out edges whose <em>source</em> node is stale as a best-effort proxy,
     * since pruning stale nodes also prunes their edges.</p>
     */
    @Override
    public List<String> findActiveEdgeIds(Long factSheetId) {
        if (factSheetId == null) return Collections.emptyList();
        String gid = graphIdForFactSheet(factSheetId);
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(gid);
        if (graphOpt.isEmpty()) return Collections.emptyList();
        AdjacencyMatrixGraph graph = graphOpt.get();

        // Build a set of stale source node ids for quick lookup
        Set<String> staleNodeIds = graphStore.getAllNodes(gid).stream()
                .filter(n -> factSheetId.equals(n.getFactSheetId()))
                .filter(n -> Boolean.TRUE.equals(n.getMetadata() != null ? n.getMetadata().get("_stale") : null))
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toSet());

        List<String> result = new ArrayList<>();
        for (GraphEdge edge : getEdgesInFactSheet(factSheetId)) {
            String srcId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
            if (srcId != null && staleNodeIds.contains(srcId)) continue;
            if (edge.getEdgeId() != null) result.add(edge.getEdgeId());
        }
        log.debug("findActiveEdgeIds: factSheetId={} → {} active edge ids", factSheetId, result.size());
        return result;
    }

    /**
     * Soft-delete: sets {@code _stale=true} and {@code _staleAt} in node metadata.
     * Hard-delete: calls {@link MatrixGraphStore#removeNode} immediately.
     * Dry-run: returns the IDs without mutating.
     */
    @Override
    public GraphPruneResult pruneNodes(Collection<String> nodeIds,
                                       boolean softDelete,
                                       Duration grace,
                                       boolean dryRun) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(nodeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            String nowStr = String.valueOf(System.currentTimeMillis());
            for (String nodeId : ids) {
                String gid = graphIdHolding(nodeId);
                graphStore.getNode(gid, nodeId).ifPresent(n -> {
                    if (n.getMetadata() == null) n.setMetadata(new HashMap<>());
                    n.getMetadata().put("_stale", true);
                    n.getMetadata().put("_staleAt", nowStr);
                    graphStore.updateNode(gid, n);
                });
            }
            return GraphPruneResult.ofSoftDelete(ids, false);
        } else {
            int deleted = 0;
            for (String nodeId : ids) {
                try {
                    graphStore.removeNode(graphIdHolding(nodeId), nodeId);
                    deleted++;
                } catch (Exception e) {
                    log.warn("pruneNodes: could not remove node {}: {}", nodeId, e.getMessage());
                }
            }
            return new GraphPruneResult(ids, deleted, deleted, false);
        }
    }

    /**
     * The matrix store does not have edge IDs; hard-delete removes the underlying
     * edge via source::target parsing.  Soft-delete is not supported for edges
     * (no metadata on adjacency entries) — soft path immediately removes.
     */
    @Override
    public GraphPruneResult pruneEdges(Collection<String> edgeIds,
                                       boolean softDelete,
                                       boolean dryRun) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(edgeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        int deleted = 0;
        for (String edgeId : ids) {
            // Edge ID format in the matrix store: "sourceId::targetId::TYPE"
            String[] parts = edgeId.split("::");
            if (parts.length >= 2) {
                String edgeType = parts.length >= 3 ? parts[2] : null;
                try {
                    graphStore.removeEdge(graphIdHolding(parts[0]), parts[0], parts[1], edgeType);
                    deleted++;
                } catch (Exception e) {
                    log.warn("pruneEdges: could not remove edge {}: {}", edgeId, e.getMessage());
                }
            }
        }
        return new GraphPruneResult(ids, deleted, deleted, false);
    }

    /**
     * Purges nodes in the fact sheet whose {@code _stale=true} metadata was
     * written before the grace period cutoff.
     */
    @Override
    public GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
        long graceDays = grace != null ? grace.toDays() : 7L;
        long cutoffMillis = System.currentTimeMillis() - graceDays * 86_400_000L;
        String gid = graphIdForFactSheet(factSheetId);
        List<MatrixGraphNode> expired = graphStore.getAllNodes(gid).stream()
                .filter(n -> factSheetId != null && factSheetId.equals(n.getFactSheetId()))
                .filter(n -> {
                    if (n.getMetadata() == null) return false;
                    Object staleFlag = n.getMetadata().get("_stale");
                    if (!Boolean.TRUE.equals(staleFlag)) return false;
                    Object staleAt = n.getMetadata().get("_staleAt");
                    if (staleAt == null) return true; // no timestamp → assume expired
                    try {
                        long ts = Long.parseLong(staleAt.toString());
                        return ts < cutoffMillis;
                    } catch (NumberFormatException ex) {
                        return true; // unparseable → assume expired
                    }
                })
                .collect(Collectors.toList());
        int deleted = 0;
        for (MatrixGraphNode n : expired) {
            try {
                graphStore.removeNode(gid, n.getNodeId());
                deleted++;
            } catch (Exception e) {
                log.warn("hardDeleteStaleNodes: could not remove node {}: {}", n.getNodeId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("hardDeleteStaleNodes: permanently removed {} expired stale nodes for factSheetId={}",
                    deleted, factSheetId);
        }
        return GraphPruneResult.ofHardDelete(deleted, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers for stale-flag checking in the matrix store
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isMatrixNodeStale(MatrixGraphNode n) {
        if (n.getMetadata() == null) return false;
        return Boolean.TRUE.equals(n.getMetadata().get("_stale"));
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

    // ═══════════════════════════════════════════════════════════════════════════
    // KG EMBEDDING STORAGE (TransE/RotatE — stored in node/edge-type metadata)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Metadata key prefix for structural KGE node embeddings. */
    private static final String META_KGE_EMB     = "_kge_emb";
    private static final String META_KGE_ALGO    = "_kge_algo";
    private static final String META_KGE_VER     = "_kge_ver";
    private static final String META_KGE_UPD_AT  = "_kge_updatedAt";

    /**
     * Pseudo-nodeId used to store per-fact-sheet edge-type KGE embeddings.
     * Format: "kge_edge:<factSheetId>:<edgeTypeName>"
     */
    private static String kgeEdgeNodeId(Long factSheetId, String edgeTypeName) {
        return "_kge_edge:" + factSheetId + ":" + edgeTypeName;
    }

    private static final INDArrayConverter KGE_CONVERTER = new INDArrayConverter();

    @Override
    public void storeNodeKgEmbedding(String nodeId, INDArray embedding,
                                      KGEmbeddingAlgorithm algorithm,
                                      Long version, Instant updatedAt) {
        if (nodeId == null || embedding == null) return;
        String gid = graphIdHolding(nodeId);
        Optional<MatrixGraphNode> opt = graphStore.getNode(gid, nodeId);
        if (opt.isEmpty()) {
            log.debug("storeNodeKgEmbedding: node {} not found, skipping", nodeId);
            return;
        }
        MatrixGraphNode node = opt.get();
        if (node.getMetadata() == null) node.setMetadata(new HashMap<>());
        byte[] bytes = KGE_CONVERTER.convertToDatabaseColumn(embedding);
        if (bytes == null) return;
        node.getMetadata().put(META_KGE_EMB, Base64.getEncoder().encodeToString(bytes));
        if (algorithm != null) node.getMetadata().put(META_KGE_ALGO, algorithm.name());
        if (version != null)   node.getMetadata().put(META_KGE_VER,  version.toString());
        if (updatedAt != null) node.getMetadata().put(META_KGE_UPD_AT, String.valueOf(updatedAt.toEpochMilli()));
        graphStore.updateNode(gid, node);
    }

    @Override
    public INDArray getNodeKgEmbedding(String nodeId) {
        if (nodeId == null) return null;
        return findNodeAnyGraph(nodeId)
                .map(n -> n.getMetadata() == null ? null : decodeKgeEmbedding(n.getMetadata().get(META_KGE_EMB)))
                .orElse(null);
    }

    @Override
    public List<GraphNode> findNodesWithKgEmbedding(Long factSheetId) {
        List<MatrixGraphNode> src = factSheetId != null
                ? graphStore.getAllNodes(graphIdForFactSheet(factSheetId))
                : allNodesAcrossGraphs();
        return src.stream()
                .filter(n -> factSheetId == null || factSheetId.equals(n.getFactSheetId()))
                .filter(n -> n.getMetadata() != null && n.getMetadata().containsKey(META_KGE_EMB))
                .map(n -> {
                    GraphNode gn = convertToGraphNode(n, n.getNodeId());
                    // Attach the deserialized embedding so callers can use it without a second lookup
                    INDArray emb = decodeKgeEmbedding(n.getMetadata().get(META_KGE_EMB));
                    gn.setKgEmbedding(emb);
                    Object algo = n.getMetadata().get(META_KGE_ALGO);
                    if (algo != null) {
                        try {
                            gn.setKgEmbeddingAlgorithm(KGEmbeddingAlgorithm.valueOf(algo.toString()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                    Object ver = n.getMetadata().get(META_KGE_VER);
                    if (ver != null) {
                        try { gn.setKgEmbeddingVersion(Long.parseLong(ver.toString())); }
                        catch (NumberFormatException ignored) {}
                    }
                    Object updAt = n.getMetadata().get(META_KGE_UPD_AT);
                    if (updAt != null) {
                        try { gn.setKgEmbeddingUpdatedAt(Instant.ofEpochMilli(Long.parseLong(updAt.toString()))); }
                        catch (NumberFormatException ignored) {}
                    }
                    return gn;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void storeEdgeTypeKgEmbedding(String edgeTypeName, INDArray embedding,
                                          KGEmbeddingAlgorithm algorithm,
                                          Long version, Long factSheetId) {
        if (edgeTypeName == null || embedding == null || factSheetId == null) return;
        String gid = graphIdForFactSheet(factSheetId);
        String pseudoNodeId = kgeEdgeNodeId(factSheetId, edgeTypeName);
        Optional<MatrixGraphNode> opt = graphStore.getNode(gid, pseudoNodeId);
        MatrixGraphNode node;
        if (opt.isPresent()) {
            node = opt.get();
        } else {
            node = MatrixGraphNode.builder()
                    .nodeId(pseudoNodeId)
                    .nodeType("_KGE_EDGE_TYPE")
                    .title("KGE edge-type: " + edgeTypeName)
                    .factSheetId(factSheetId)
                    .metadata(new HashMap<>())
                    .build();
            graphStore.addNode(gid, node);
        }
        if (node.getMetadata() == null) node.setMetadata(new HashMap<>());
        byte[] bytes = KGE_CONVERTER.convertToDatabaseColumn(embedding);
        if (bytes == null) return;
        node.getMetadata().put(META_KGE_EMB, Base64.getEncoder().encodeToString(bytes));
        node.getMetadata().put("_kge_edgeType", edgeTypeName);
        if (algorithm != null) node.getMetadata().put(META_KGE_ALGO, algorithm.name());
        if (version != null)   node.getMetadata().put(META_KGE_VER,  version.toString());
        graphStore.updateNode(gid, node);
    }

    @Override
    public Map<String, INDArray> getEdgeTypeKgEmbeddings(Long factSheetId) {
        if (factSheetId == null) return Map.of();
        Map<String, INDArray> result = new LinkedHashMap<>();
        graphStore.getAllNodes(graphIdForFactSheet(factSheetId)).stream()
                .filter(n -> "_KGE_EDGE_TYPE".equals(n.getNodeType()))
                .filter(n -> factSheetId.equals(n.getFactSheetId()))
                .filter(n -> n.getMetadata() != null && n.getMetadata().containsKey(META_KGE_EMB))
                .forEach(n -> {
                    Object edgeType = n.getMetadata().get("_kge_edgeType");
                    if (edgeType == null) return;
                    INDArray emb = decodeKgeEmbedding(n.getMetadata().get(META_KGE_EMB));
                    if (emb != null) result.put(edgeType.toString(), emb);
                });
        return result;
    }

    @Override
    public KGEmbeddingAlgorithm getStoredKgAlgorithm(Long factSheetId) {
        List<MatrixGraphNode> src = factSheetId != null
                ? graphStore.getAllNodes(graphIdForFactSheet(factSheetId))
                : allNodesAcrossGraphs();
        return src.stream()
                .filter(n -> factSheetId == null || factSheetId.equals(n.getFactSheetId()))
                .filter(n -> n.getMetadata() != null && n.getMetadata().containsKey(META_KGE_ALGO))
                .map(n -> {
                    try { return KGEmbeddingAlgorithm.valueOf(n.getMetadata().get(META_KGE_ALGO).toString()); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void clearKgEmbeddings(Long factSheetId) {
        // Read the scoped graph when a fact sheet is given, else every segmented graph. Re-read per
        // pass (Supplier) so the second pass doesn't see pseudo-nodes the first pass already removed.
        Supplier<List<MatrixGraphNode>> source = () -> factSheetId != null
                ? graphStore.getAllNodes(graphIdForFactSheet(factSheetId))
                : allNodesAcrossGraphs();
        // Remove pseudo edge-type nodes
        source.get().stream()
                .filter(n -> "_KGE_EDGE_TYPE".equals(n.getNodeType()))
                .filter(n -> factSheetId == null || factSheetId.equals(n.getFactSheetId()))
                .forEach(n -> {
                    try { graphStore.removeNode(graphIdHolding(n.getNodeId()), n.getNodeId()); }
                    catch (Exception ignored) {}
                });
        // Clear embedding metadata from regular nodes
        source.get().stream()
                .filter(n -> factSheetId == null || factSheetId.equals(n.getFactSheetId()))
                .filter(n -> n.getMetadata() != null && n.getMetadata().containsKey(META_KGE_EMB))
                .forEach(n -> {
                    n.getMetadata().remove(META_KGE_EMB);
                    n.getMetadata().remove(META_KGE_ALGO);
                    n.getMetadata().remove(META_KGE_VER);
                    n.getMetadata().remove(META_KGE_UPD_AT);
                    graphStore.updateNode(graphIdHolding(n.getNodeId()), n);
                });
        log.info("clearKgEmbeddings: removed KGE data for factSheetId={}", factSheetId);
    }

    private static INDArray decodeKgeEmbedding(Object b64value) {
        if (b64value == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(b64value.toString());
            return KGE_CONVERTER.convertToEntityAttribute(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOD — LEVEL-OF-DETAIL GRAPH ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Top-K-by-centrality LOD view. Loads the per-fact-sheet {@link AdjacencyMatrixGraph},
     * runs the requested centrality algorithm (PageRank / degree / betweenness), selects the
     * top-K nodes by score desc, and returns the induced subgraph (edges with BOTH endpoints
     * in the top-K set) in the standard viz shape.
     *
     * <p>ND4J stays inside the subprocess — this method is invoked via the existing JDK-proxy
     * client seam and never runs in-heap in the main app.</p>
     */
    @Override
    public Map<String, Object> getTopKVisualizationData(Long factSheetId, int k, String metric) {
        List<String> graphIds = factSheetId != null
                ? List.of(graphIdForFactSheet(factSheetId))
                : allGraphIds();

        List<Map<String, Object>> nodeData = new ArrayList<>();
        List<Map<String, Object>> edgeData = new ArrayList<>();
        int totalNodes = 0;
        Set<String> seenEdges = new HashSet<>();

        for (String gid : graphIds) {
            Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(gid);
            if (graphOpt.isEmpty()) {
                continue;
            }
            AdjacencyMatrixGraph graph = graphOpt.get();
            int n = graph.getNodeCount();
            totalNodes += n;
            if (n == 0) {
                continue;
            }

            // Compute centrality scores using the existing MatrixGraphAlgorithms methods.
            Map<String, Double> scores;
            String metricLower = metric != null ? metric.toLowerCase(Locale.ROOT) : "pagerank";
            switch (metricLower) {
                case "degree":
                    scores = MatrixGraphAlgorithms.degreeCentrality(graph);
                    break;
                case "betweenness":
                    scores = MatrixGraphAlgorithms.betweennessCentrality(graph, Math.min(n, 100));
                    break;
                default: // "pagerank" and anything else
                    scores = MatrixGraphAlgorithms.pageRank(graph);
                    break;
            }

            // Sort by score desc, take top-K node IDs.
            List<String> topKIds = scores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(k)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            Set<String> topKSet = new HashSet<>(topKIds);

            // Build visualization maps for the top-K nodes.
            for (String nodeId : topKIds) {
                graph.getNode(nodeId)
                        .filter(node -> isUserNodeType(node.getNodeType()))
                        .map(node -> nodeToVisualizationMap(node, graph))
                        .ifPresent(nodeData::add);
            }

            // Collect induced edges — only edges with BOTH endpoints in the top-K set.
            for (String nodeId : topKIds) {
                for (String edgeType : graph.getEdgeTypes()) {
                    for (Map.Entry<String, Double> neighbor : graph.getNeighbors(nodeId, edgeType)) {
                        if (topKSet.contains(neighbor.getKey())) {
                            String edgeKey = nodeId + "::" + neighbor.getKey() + "::" + edgeType;
                            if (seenEdges.add(edgeKey)) {
                                Map<String, Object> edgeMap = new LinkedHashMap<>();
                                edgeMap.put("id", edgeKey);
                                edgeMap.put("source", nodeId);
                                edgeMap.put("target", neighbor.getKey());
                                edgeMap.put("type", edgeType);
                                edgeMap.put("weight", neighbor.getValue());
                                edgeMap.put("bidirectional", !"HIERARCHICAL".equals(edgeType));
                                edgeData.add(edgeMap);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalAvailableNodes", totalNodes);
        statistics.put("returnedNodes", nodeData.size());
        statistics.put("returnedEdges", edgeData.size());
        statistics.put("k", k);
        statistics.put("metric", metric != null ? metric : "pagerank");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edgeData);
        result.put("links", edgeData); // synonym for frontend compatibility
        result.put("statistics", statistics);
        return result;
    }

    /**
     * 1-hop neighborhood expand for a single node. Reads the node's direct neighbors from the
     * in-memory adjacency store (no full-graph scan), sorts by edge weight desc, caps at
     * {@code maxNeighbors}, then returns the seed + neighbors + connecting edges in viz shape.
     *
     * <p>The induced edges include not just seed→neighbor but also neighbor→neighbor links that
     * are both in the returned set, so the visualizer can render the local cluster.</p>
     */
    @Override
    public Map<String, Object> expandNeighborhoodVisualization(String nodeId, int maxNeighbors,
                                                                 List<String> edgeTypeFilter) {
        String graphId = graphIdHolding(nodeId);
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphId);
        if (graphOpt.isEmpty() || graphOpt.get().getNode(nodeId).isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("nodes", Collections.emptyList());
            empty.put("edges", Collections.emptyList());
            empty.put("links", Collections.emptyList());
            empty.put("statistics", Map.of("totalAvailableNodes", 0, "nodeId", nodeId));
            return empty;
        }
        AdjacencyMatrixGraph graph = graphOpt.get();

        // Determine which edge types to traverse.
        Set<String> typeFilter = (edgeTypeFilter != null && !edgeTypeFilter.isEmpty())
                ? new HashSet<>(edgeTypeFilter) : null;
        List<String> effectiveTypes = typeFilter != null
                ? graph.getEdgeTypes().stream().filter(typeFilter::contains).collect(Collectors.toList())
                : new ArrayList<>(graph.getEdgeTypes());

        // Collect all unique neighbors across requested edge types.
        List<Map.Entry<String, Double>> allNeighbors = new ArrayList<>();
        Set<String> seenNeighborIds = new HashSet<>();
        for (String et : effectiveTypes) {
            for (Map.Entry<String, Double> nb : graph.getNeighbors(nodeId, et)) {
                if (seenNeighborIds.add(nb.getKey())) {
                    allNeighbors.add(nb);
                }
            }
        }

        // Sort by weight desc, cap at maxNeighbors.
        allNeighbors.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Map.Entry<String, Double>> cappedNeighbors =
                allNeighbors.subList(0, Math.min(maxNeighbors, allNeighbors.size()));

        // Build the returned node set: seed + capped neighbors.
        Set<String> returnedIds = new LinkedHashSet<>();
        returnedIds.add(nodeId);
        for (Map.Entry<String, Double> nb : cappedNeighbors) {
            returnedIds.add(nb.getKey());
        }

        // Build node visualization maps.
        List<Map<String, Object>> nodeData = new ArrayList<>();
        for (String nid : returnedIds) {
            graph.getNode(nid)
                    .filter(n -> isUserNodeType(n.getNodeType()))
                    .map(n -> nodeToVisualizationMap(n, graph))
                    .ifPresent(nodeData::add);
        }

        // Collect edges: both endpoints must be in the returned set.
        List<Map<String, Object>> edgeData = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        for (String nid : returnedIds) {
            for (String et : effectiveTypes) {
                for (Map.Entry<String, Double> nb : graph.getNeighbors(nid, et)) {
                    if (returnedIds.contains(nb.getKey())) {
                        String edgeKey = nid + "::" + nb.getKey() + "::" + et;
                        if (seenEdges.add(edgeKey)) {
                            Map<String, Object> edgeMap = new LinkedHashMap<>();
                            edgeMap.put("id", edgeKey);
                            edgeMap.put("source", nid);
                            edgeMap.put("target", nb.getKey());
                            edgeMap.put("type", et);
                            edgeMap.put("weight", nb.getValue());
                            edgeMap.put("bidirectional", !"HIERARCHICAL".equals(et));
                            edgeData.add(edgeMap);
                        }
                    }
                }
            }
        }

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalAvailableNodes", graph.getNodeCount());
        statistics.put("nodeId", nodeId);
        statistics.put("totalNeighbors", allNeighbors.size());
        statistics.put("returnedNeighbors", cappedNeighbors.size());
        statistics.put("returnedNodes", nodeData.size());
        statistics.put("returnedEdges", edgeData.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodeData);
        result.put("edges", edgeData);
        result.put("links", edgeData); // synonym for frontend compatibility
        result.put("statistics", statistics);
        return result;
    }
}
