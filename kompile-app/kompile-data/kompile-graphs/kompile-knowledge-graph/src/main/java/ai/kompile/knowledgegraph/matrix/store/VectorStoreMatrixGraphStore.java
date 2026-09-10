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
import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.generation.GraphGenerationJournal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>Node metadata: {@code graph:{graphId}:node-meta:{nodeId}}</li>
 *   <li>Node vector: {@code graph:{graphId}:node:{nodeId}}</li>
 *   <li>Adjacency matrix: {@code graph:{graphId}:adj:{edgeType}}</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class VectorStoreMatrixGraphStore implements MatrixGraphStore {

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private GraphGenerationJournal generationJournal;

    public VectorStoreMatrixGraphStore() {}

    /** Test constructor. */
    public VectorStoreMatrixGraphStore(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    /**
     * In-memory cache of loaded graphs.
     */
    private final Map<String, AdjacencyMatrixGraph> graphCache = new ConcurrentHashMap<>();
    private final Map<String, GenerationPointer> generationPointers = new ConcurrentHashMap<>();
    private final Set<String> generationGraphs = ConcurrentHashMap.newKeySet();

    private record GenerationPointer(String active, String previous, long revision) { }

    /**
     * Prefix for graph-related documents in the vector store.
     */
    private static final String GRAPH_PREFIX = "graph:";
    private static final String POINTER_PREFIX = GRAPH_PREFIX + "pointer:";
    private static final String META_SUFFIX = ":meta";
    private static final String NODE_PREFIX = ":node:";
    private static final String NODE_METADATA_PREFIX = ":node-meta:";
    private static final String EDGE_PREFIX = ":edge:";
    /** Legacy read-only aggregate formats. Fresh writes use individual node and edge documents. */
    private static final String ADJ_PREFIX = ":adj:";
    private static final String EMBD_SUFFIX = ":embd";
    private static final int MIN_CANONICAL_DOCUMENT_STORAGE_VERSION = 2;
    private static final int CANONICAL_DOCUMENT_STORAGE_VERSION = 3;
    private static final int STORAGE_VERSION_IN_PROGRESS = -1;

    /**
     * Number of vector-store documents to fetch per page when scanning the index.
     *
     * <p>Keeping this modest (default 2 000) means each page is bounded in heap
     * regardless of total index size.  Only documents matching the target graphId
     * are retained after each page — all others are discarded before the next
     * fetch so heap holds at most {@code vectorScanPageSize} docs at once.</p>
     *
     * <p>Override with {@code -Dkompile.graph.vector-scan-page-size=N} or in
     * {@code application.properties}.</p>
     */
    @Value("${kompile.graph.vector-scan-page-size:2000}")
    private int vectorScanPageSize = 2000;
    @Value("${kompile.graph.max-incident-edges:100000}")
    private int maxIncidentEdges = 100_000;

    /**
     * Whether to eagerly rehydrate all persisted graphs into the in-memory cache on startup.
     *
     * <p>When {@code true} (the default), {@link #rehydrateGraphsOnStartup()} runs in a
     * background thread immediately after the app context is ready so graph data is available
     * in-memory as soon as the scan completes — without blocking Tomcat startup.  The
     * lazy-load-on-first-query path already works, so disabling this only affects warm-cache
     * availability; the first query will trigger an on-demand load instead.</p>
     *
     * <p>Set {@code kompile.graph.eager-rehydration-enabled=false} to skip pre-loading
     * entirely and rely purely on lazy loading.</p>
     */
    @Value("${kompile.graph.eager-rehydration-enabled:false}")
    private boolean eagerRehydrationEnabled = true;

    // ═══════════════════════════════════════════════════════════════════════════
    // STARTUP REHYDRATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * On application ready, eagerly reload every graph whose metadata document
     * exists in the vector store so queries return data immediately after restart
     * without a re-crawl.
     *
     * <p>This resolves the production data-loss symptom: crawl builds ~6 000 nodes /
     * ~70 000 edges → JVM restarts → visualizer and derivation both see zero.  The
     * write side already persists nodes + adjacency matrices to the Lucene index via
     * {@link #saveGraph}; the missing piece was reading them back on startup.</p>
     *
     * <p><b>Async:</b> this method runs in a Spring {@code @Async} thread so it never
     * blocks the Tomcat-startup main thread.  The lazy-load-on-first-query path remains
     * fully functional, so in the window between app-ready and rehydration completion the
     * first graph query simply triggers an on-demand load.  Disable pre-loading entirely
     * with {@code kompile.graph.eager-rehydration-enabled=false}.</p>
     */
    /**
     * Called by {@link GraphRehydrationListener} on {@code ApplicationReadyEvent}.
     * Not annotated with {@code @EventListener} here because this bean is proxied
     * through its {@link MatrixGraphStore} interface, and JDK proxies cannot dispatch
     * {@code @EventListener} methods that are absent from the interface.
     */
    public void rehydrateGraphsOnStartup() {
        if (!eagerRehydrationEnabled) {
            log.info("Graph eager rehydration disabled (kompile.graph.eager-rehydration-enabled=false) — graphs will load lazily on first query.");
            return;
        }
        log.info("Graph rehydration starting in background — scanning vector store for persisted graphs...");
        try {
            List<String> graphIds = listGraphs();
            if (graphIds.isEmpty()) {
                log.info("Graph rehydration: no persisted graphs found in vector store.");
                return;
            }
            int loaded = 0;
            for (String graphId : graphIds) {
                if (graphCache.containsKey(graphId)) {
                    continue; // already warm (loaded by lazy path before this scan finished)
                }
                try {
                    Optional<AdjacencyMatrixGraph> graph = loadGraphFromVectorStore(graphId);
                    if (graph.isPresent()) {
                        loaded++;
                        log.info("Rehydrated graph '{}': {} nodes", graphId,
                                graph.get().getNodeCount());
                    } else {
                        log.warn("Graph '{}' listed in vector store but could not be reconstructed", graphId);
                    }
                } catch (Exception e) {
                    log.error("Failed to rehydrate graph '{}'", graphId, e);
                }
            }
            log.info("Graph rehydration complete: {}/{} graphs loaded into cache.", loaded, graphIds.size());
        } catch (Exception e) {
            log.error("Graph rehydration failed — graphs will be loaded lazily on first access", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public synchronized AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        graphId = resolveGraphId(graphId);
        // Get-or-create: with stable per-fact-sheet graph ids (graphIdForFactSheet) a second
        // createGraph for the same id must NOT wipe the existing graph — that would lose nodes/edges
        // already persisted for that fact sheet. Return the existing graph (back-filling factSheetId
        // if it was created without one) instead of replacing it with an empty one.
        Optional<AdjacencyMatrixGraph> existing = loadGraph(graphId);
        if (existing.isPresent()) {
            AdjacencyMatrixGraph g = existing.get();
            if (factSheetId != null && g.getFactSheetId() == null) {
                g.setFactSheetId(factSheetId);
            }
            return g;
        }

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
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        // Check cache first
        AdjacencyMatrixGraph cached = graphCache.get(graphId);
        if (cached != null) {
            return Optional.of(cached);
        }

        return loadGraphFromVectorStore(graphId);
    }

    @Override
    public synchronized void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        String graphId = graph.getGraphId();
        log.info("Saving graph {} with {} nodes", graphId, graph.getNodeCount());

        // Demote first. If a crash occurs mid-save, readers take the strict recovery/backfill path
        // instead of trusting an older v3 commit marker over a hybrid edge set.
        saveGraphMetadata(graph, STORAGE_VERSION_IN_PROGRESS);

        // Save nodes with embeddings
        saveNodes(graph);

        // Persist each relationship as its own bounded Lucene document. Adjacency matrices
        // remain derived in-memory state and are never a persistence format.
        saveEdges(graph);

        // Metadata is the commit marker. Publish the current storage version only after every
        // canonical node/edge document has been written and stale edges removed.
        saveGraphMetadata(graph);

        // Update cache
        graphCache.put(graphId, graph);

        // Commit to vector store
        if (!vectorStore.flushAndCommit()) {
            throw new IOException("Vector store commit failed for graph " + graphId);
        }
    }

    @Override
    public synchronized boolean deleteGraph(String graphId) {
        String logicalId = graphId;
        if (logicalId.contains("~gen~")) {
            String owner = logicalId.substring(0, logicalId.indexOf("~gen~"));
            if (generationJournal != null) {
                Optional<GraphGeneration.Pointer> authoritative = generationJournal.pointer(owner);
                if (authoritative.isPresent()
                        && (logicalId.equals(authoritative.get().activePhysicalGraphId())
                        || logicalId.equals(authoritative.get().previousPhysicalGraphId()))) {
                    return false;
                }
            }
            refreshGenerationPointersStrict();
            if (generationPointers.values().stream().anyMatch(pointer -> logicalId.equals(pointer.active())
                    || logicalId.equals(pointer.previous()))) {
                return false;
            }
        }
        GenerationPointer pointer = pointer(logicalId, true);
        if (pointer != null || !logicalId.contains("~gen~")) {
            try {
                awaitPendingNodeWrites("delete graph family", logicalId, "*");
                String familyPrefix = GRAPH_PREFIX + logicalId;
                List<String> idsToDelete = collectMatchingIdsStrict(familyPrefix);
                idsToDelete.removeIf(id -> !(id.startsWith(familyPrefix + ":")
                        || id.startsWith(familyPrefix + "~gen~")));
                if (pointer != null) idsToDelete.add(pointerDocId(logicalId));

                Set<String> cachedIds = new LinkedHashSet<>();
                cachedIds.add(logicalId);
                if (pointer != null) {
                    cachedIds.add(pointer.active());
                    if (pointer.previous() != null) cachedIds.add(pointer.previous());
                }
                graphCache.keySet().stream()
                        .filter(id -> id.startsWith(logicalId + "~gen~"))
                        .forEach(cachedIds::add);
                boolean deleted = vectorStore.delete(new ArrayList<>(new LinkedHashSet<>(idsToDelete)));
                if (!deleted || !vectorStore.flushAndCommit()) return false;
                for (String id : cachedIds) {
                    AdjacencyMatrixGraph graph = graphCache.remove(id);
                    if (graph != null) graph.close();
                }
                generationPointers.remove(logicalId);
                generationGraphs.removeIf(id -> id.startsWith(logicalId + "~gen~"));
                return deleted;
            } catch (Exception e) {
                log.error("Failed to delete graph family {}", logicalId, e);
                return false;
            }
        }
        graphId = resolveGraphId(graphId);
        try {
            // Stream-scan the index page-by-page and collect only the IDs belonging
            // to this graph.  We never accumulate docs from other graphs in heap —
            // each page is discarded after filtering.
            awaitPendingNodeWrites("delete graph", graphId, "*");
            String targetPrefix = GRAPH_PREFIX + graphId + ":";
            List<String> idsToDelete = collectMatchingIdsStrict(targetPrefix);

            // Always add the meta doc so the graph header is removed even if the
            // scanner missed it (e.g. offset race on a concurrent write).
            String metaId = GRAPH_PREFIX + graphId + META_SUFFIX;
            if (!idsToDelete.contains(metaId)) {
                idsToDelete.add(metaId);
            }

            if (!idsToDelete.isEmpty()) {
                if (!vectorStore.delete(idsToDelete) || !vectorStore.flushAndCommit()) return false;
            }

            AdjacencyMatrixGraph graph = graphCache.remove(graphId);
            if (graph != null) graph.close();

            return true;
        } catch (Exception e) {
            log.error("Failed to delete graph {}", graphId, e);
            return false;
        }
    }

    @Override
    public Set<String> getLoadedGraphIds() {
        // In-memory cache keys — complete after rehydrateGraphsOnStartup() and updated by
        // getOrCreateGraph/createGraph. O(1) snapshot; avoids the full-index scan of listGraphs().
        Set<String> visible = new LinkedHashSet<>();
        graphCache.keySet().stream()
                .filter(id -> !generationGraphs.contains(id) && !id.contains("~gen~"))
                .forEach(visible::add);
        visible.addAll(generationPointers.keySet());
        return visible;
    }

    @Override
    public List<String> listGraphs() {
        List<String> graphIds = new ArrayList<>();
        try {
            // Stream page-by-page; only retain `:meta` docs — discard everything else
            // per page so heap holds at most vectorScanPageSize docs at a time.
            int offset = 0;
            List<Map<String, Object>> page;
            do {
                page = vectorStore.listVectorDocuments(offset, vectorScanPageSize);
                for (Map<String, Object> rawDoc : page) {
                    String docId = (String) rawDoc.get("id");
                    if (docId == null || !docId.startsWith(GRAPH_PREFIX)) {
                        continue;
                    }
                    if (docId.startsWith(POINTER_PREFIX)) {
                        Map<String, Object> pointerDoc = flattenDoc(rawDoc);
                        String logical = docId.substring(POINTER_PREFIX.length());
                        String active = pointerDoc.get("activePhysicalGraphId") instanceof String value
                                ? value : null;
                        if (active != null && !active.isBlank()) {
                            String previous = pointerDoc.get("previousPhysicalGraphId") instanceof String value
                                    ? value : null;
                            long revision = pointerDoc.get("revision") instanceof Number value
                                    ? value.longValue() : 0L;
                            generationPointers.put(logical, new GenerationPointer(active, previous, revision));
                            if (active.contains("~gen~")) generationGraphs.add(active);
                            if (previous != null && previous.contains("~gen~")) generationGraphs.add(previous);
                        }
                        continue;
                    }
                    if (!docId.endsWith(META_SUFFIX)) continue;
                    // Confirm the document is actually a graph_metadata record (the type field
                    // may be nested under "metadata" in the Anserini VectorStore response).
                    Map<String, Object> doc = flattenDoc(rawDoc);
                    if ("graph_metadata".equals(doc.get("type"))) {
                        String graphId = docId.substring(GRAPH_PREFIX.length(),
                                docId.length() - META_SUFFIX.length());
                        graphIds.add(graphId);
                    }
                    // Non-matching docs are not retained — GC'd at end of page loop
                }
                offset += page.size();
            } while (page.size() == vectorScanPageSize);
        } catch (Exception e) {
            log.error("Failed to list graphs", e);
        }
        graphIds.removeIf(id -> generationGraphs.contains(id) || id.contains("~gen~"));
        generationPointers.keySet().forEach(id -> { if (!graphIds.contains(id)) graphIds.add(id); });
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

    @Override
    public boolean supportsGraphGenerations() { return true; }

    @Override
    public synchronized GraphGeneration.Ref beginGeneration(
            long factSheetId, String logicalGraphId, String generationId) {
        GenerationPointer pointer = pointer(logicalGraphId, true);
        String active = pointer == null ? logicalGraphId : pointer.active();
        long revision = pointer == null ? 0L : pointer.revision();
        String physical = logicalGraphId + "~gen~" + generationId;
        if (loadGraph(physical).isPresent()) throw new IllegalStateException("Generation already exists: " + physical);
        generationGraphs.add(physical);
        createGraph(physical, factSheetId);
        return new GraphGeneration.Ref(factSheetId, logicalGraphId, physical, generationId, active, revision);
    }

    @Override
    public synchronized GraphGeneration.Validation validateGeneration(GraphGeneration.Ref generation) {
        Optional<AdjacencyMatrixGraph> candidate = loadGraph(generation.physicalGraphId());
        if (candidate.isEmpty()) {
            return new GraphGeneration.Validation(false, 0, 0, List.of("Generation does not exist"));
        }
        generationGraphs.add(generation.physicalGraphId());
        AdjacencyMatrixGraph graph = candidate.get();
        List<String> errors = new ArrayList<>();
        if (!Objects.equals(graph.getFactSheetId(), generation.factSheetId())) {
            errors.add("Generation fact sheet does not match its graph");
        }
        int edgeCount = 0;
        int cursor = 0;
        ScanPage<StoredEdge> page;
        do {
            page = scanEdges(generation.physicalGraphId(), cursor, 2_000);
            edgeCount += page.items().size();
            for (StoredEdge edge : page.items()) {
                if (graph.getNode(edge.sourceNodeId()).isEmpty()
                        || graph.getNode(edge.targetNodeId()).isEmpty()) {
                    errors.add("Dangling edge " + edge.sourceNodeId() + "->" + edge.targetNodeId());
                }
            }
            cursor = page.nextCursor();
        } while (page.hasMore());
        return new GraphGeneration.Validation(errors.isEmpty(), graph.getNodeCount(), edgeCount, errors);
    }

    @Override
    public synchronized GraphGeneration.Activation activateGeneration(GraphGeneration.Ref generation) {
        GraphGeneration.Validation validation = validateGeneration(generation);
        if (!validation.valid()) throw new IllegalStateException("Generation validation failed: " + validation.errors());
        GenerationPointer current = pointer(generation.logicalGraphId(), true);
        String active = current == null ? generation.logicalGraphId() : current.active();
        long revision = current == null ? 0L : current.revision();
        if (!Objects.equals(active, generation.expectedActivePhysicalGraphId())
                || revision != generation.expectedRevision()) {
            throw new IllegalStateException("Graph generation activation conflict");
        }
        try {
            saveGraph(graphCache.get(generation.physicalGraphId()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist graph generation before activation", e);
        }
        GenerationPointer next = new GenerationPointer(generation.physicalGraphId(), active, revision + 1);
        writePointer(generation.logicalGraphId(), next);
        generationPointers.put(generation.logicalGraphId(), next);
        return new GraphGeneration.Activation(generation.logicalGraphId(), next.active(), next.previous(),
                next.revision(), Instant.now());
    }

    @Override
    public synchronized void abortGeneration(GraphGeneration.Ref generation) {
        if (generationJournal != null) {
            Optional<GraphGeneration.Pointer> authoritative =
                    generationJournal.pointer(generation.logicalGraphId());
            if (authoritative.isPresent()
                    && (generation.physicalGraphId().equals(
                    authoritative.get().activePhysicalGraphId())
                    || generation.physicalGraphId().equals(
                    authoritative.get().previousPhysicalGraphId()))) {
                throw new IllegalStateException(
                        "Cannot abort an authoritative active or rollback-target generation");
            }
        }
        GenerationPointer pointer = pointer(generation.logicalGraphId(), true);
        if (pointer != null && (generation.physicalGraphId().equals(pointer.active())
                || generation.physicalGraphId().equals(pointer.previous()))) {
            throw new IllegalStateException("Cannot abort an active or rollback-target generation");
        }
        deletePhysicalGraph(generation.physicalGraphId());
    }

    @Override
    public synchronized GraphGeneration.Activation rollbackGeneration(
            long factSheetId, String logicalGraphId, long expectedRevision) {
        GenerationPointer current = pointer(logicalGraphId, true);
        if (current == null || current.previous() == null || current.revision() != expectedRevision) {
            throw new IllegalStateException("Graph generation rollback conflict");
        }
        requireCompleteStorageState(current.previous(), graphStorageMetadata(current.previous()));
        AdjacencyMatrixGraph target = graphCache.get(current.previous());
        if (target == null) target = loadGraphFromVectorStore(current.previous()).orElse(null);
        if (target == null || !Objects.equals(target.getFactSheetId(), factSheetId)) {
            throw new IllegalStateException("Graph generation rollback target is unavailable");
        }
        GenerationPointer next = new GenerationPointer(current.previous(), current.active(), current.revision() + 1);
        writePointer(logicalGraphId, next);
        generationPointers.put(logicalGraphId, next);
        return new GraphGeneration.Activation(logicalGraphId, next.active(), next.previous(),
                next.revision(), Instant.now());
    }

    @Override
    public Optional<GraphGeneration.Pointer> currentGenerationPointer(
            long factSheetId, String logicalGraphId) {
        if (generationJournal != null) {
            Optional<GraphGeneration.Pointer> authoritative = generationJournal.pointer(logicalGraphId);
            if (authoritative.isPresent()) return authoritative;
        }
        GenerationPointer pointer = pointer(logicalGraphId, true);
        return Optional.of(pointer == null
                ? new GraphGeneration.Pointer(factSheetId, logicalGraphId, logicalGraphId, null, 0L)
                : new GraphGeneration.Pointer(factSheetId, logicalGraphId,
                        pointer.active(), pointer.previous(), pointer.revision()));
    }

    @Override
    public boolean physicalGraphExists(String physicalGraphId, long factSheetId) {
        requireCompleteStorageState(physicalGraphId, graphStorageMetadata(physicalGraphId));
        AdjacencyMatrixGraph graph = graphCache.get(physicalGraphId);
        if (graph == null) graph = loadGraphFromVectorStore(physicalGraphId).orElse(null);
        return graph != null && Objects.equals(graph.getFactSheetId(), factSheetId);
    }

    @Override
    public synchronized void flushGeneration(GraphGeneration.Ref generation) {
        AdjacencyMatrixGraph candidate = graphCache.get(generation.physicalGraphId());
        if (candidate == null) {
            candidate = loadGraphFromVectorStore(generation.physicalGraphId()).orElseThrow(
                    () -> new IllegalStateException("Graph generation is unavailable before flush"));
        }
        int expectedNodes = candidate.getNodeCount();
        int expectedEdges = countPersistableEdges(candidate);
        try {
            saveGraph(candidate);
            vectorStore.awaitPendingEmbeddings();
            if (!vectorStore.flushAndCommit()) {
                throw new IllegalStateException("Vector store rejected graph generation commit");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not durably flush graph generation", e);
        }

        graphCache.remove(generation.physicalGraphId());
        AdjacencyMatrixGraph persisted = loadGraphFromVectorStore(generation.physicalGraphId())
                .orElseThrow(() -> new IllegalStateException(
                        "Graph generation was not reloadable after durable flush"));
        if (persisted.getNodeCount() != expectedNodes) {
            throw new IllegalStateException("Graph generation reload count mismatch: expected "
                    + expectedNodes + " nodes but found " + persisted.getNodeCount());
        }
        GraphGeneration.Validation persistedValidation = validateGeneration(generation);
        if (!persistedValidation.valid()) {
            throw new IllegalStateException(
                    "Persisted graph generation validation failed: " + persistedValidation.errors());
        }
        if (persistedValidation.edgeCount() != expectedEdges) {
            throw new IllegalStateException("Graph generation reload count mismatch: expected "
                    + expectedEdges + " edges but found " + persistedValidation.edgeCount());
        }
    }

    @Override
    public synchronized void repairGenerationPointer(GraphGeneration.Pointer pointer) {
        GenerationPointer mirror = new GenerationPointer(
                pointer.activePhysicalGraphId(), pointer.previousPhysicalGraphId(), pointer.revision());
        writePointer(pointer.logicalGraphId(), mirror);
        generationPointers.put(pointer.logicalGraphId(), mirror);
        if (mirror.active().contains("~gen~")) generationGraphs.add(mirror.active());
        if (mirror.previous() != null && mirror.previous().contains("~gen~")) {
            generationGraphs.add(mirror.previous());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public synchronized int addNode(String graphId, MatrixGraphNode node) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        Optional<MatrixGraphNode> previous = graph.getNode(node.getNodeId());
        if (previous.isPresent()) {
            node.setMatrixIndex(previous.get().getMatrixIndex());
            try {
                saveNode(graphId, node, false);
                graph.addNode(node);
                return node.getMatrixIndex();
            } catch (Exception failure) {
                try {
                    persistNodeMetadata(graphId, previous.get());
                } catch (RuntimeException compensation) {
                    failure.addSuppressed(compensation);
                }
                throw new IllegalStateException(
                        "Failed to persist existing node " + node.getNodeId()
                                + " in graph " + graphId, failure);
            }
        }
        int index = graph.addNode(node);

        // Persist node
        try {
            saveNode(graphId, node, false);
        } catch (Exception e) {
            if (!vectorStore.delete(List.of(
                    GRAPH_PREFIX + graphId + NODE_METADATA_PREFIX + node.getNodeId()))) {
                e.addSuppressed(new IllegalStateException(
                        "Could not compensate failed node metadata write"));
            }
            graph.removeNode(node.getNodeId());
            throw new IllegalStateException(
                    "Failed to persist node " + node.getNodeId() + " in graph " + graphId, e);
        }

        return index;
    }

    @Override
    public synchronized void updateNode(String graphId, MatrixGraphNode node) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        Optional<MatrixGraphNode> previous = graph == null
                ? Optional.empty() : graph.getNode(node.getNodeId());
        try {
            saveNode(graphId, node, true);
            if (graph != null) graph.addNode(node);
        } catch (Exception e) {
            if (previous.isPresent()) {
                try { persistNodeMetadata(graphId, previous.get()); }
                catch (RuntimeException compensation) { e.addSuppressed(compensation); }
            }
            throw new IllegalStateException(
                    "Failed to update node " + node.getNodeId() + " in graph " + graphId, e);
        }
    }

    @Override
    public synchronized void updateNodeMetadata(String graphId, MatrixGraphNode node) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        Optional<MatrixGraphNode> previous = graph == null
                ? Optional.empty() : graph.getNode(node.getNodeId());
        try {
            // Metadata has a dedicated document ID and can therefore be updated without replacing
            // the vector-bearing node document. This works in the embedding-disabled subprocess
            // and preserves any existing KNN vector exactly.
            persistNodeMetadata(graphId, node);
            if (graph != null) graph.addNode(node);
        } catch (Exception e) {
            if (previous.isPresent()) {
                try { persistNodeMetadata(graphId, previous.get()); }
                catch (RuntimeException compensation) { e.addSuppressed(compensation); }
            }
            throw new IllegalStateException(
                    "Failed metadata-only update for node " + node.getNodeId()
                            + " in graph " + graphId, e);
        }
    }

    @Override
    public synchronized boolean removeNode(String graphId, String nodeId) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        List<String> incidentEdgeIds = new ArrayList<>();
        try {
            int cursor = 0;
            ScanPage<StoredEdge> page;
            do {
                page = scanEdges(graphId, cursor, 2_000);
                for (StoredEdge edge : page.items()) {
                    if (nodeId.equals(edge.sourceNodeId()) || nodeId.equals(edge.targetNodeId())) {
                        incidentEdgeIds.add(edgeDocumentId(graphId, edge.edgeType(),
                                edge.sourceNodeId(), edge.targetNodeId()));
                    }
                }
                cursor = page.nextCursor();
            } while (page.hasMore());
        } catch (RuntimeException e) {
            log.error("Failed to discover incident edges before removing node {} in graph {}", nodeId, graphId, e);
            return false;
        }
        String vectorDocId = GRAPH_PREFIX + graphId + NODE_PREFIX + nodeId;
        String metadataDocId = GRAPH_PREFIX + graphId + NODE_METADATA_PREFIX + nodeId;
        // add() is asynchronous. Drain all accepted writes while node operations are serialized so
        // an older embedding task cannot recreate the vector document after this deletion.
        boolean barrierSucceeded = awaitPendingNodeWrites("remove", graphId, nodeId);
        incidentEdgeIds.add(vectorDocId);
        incidentEdgeIds.add(metadataDocId);
        boolean deleted = vectorStore.delete(incidentEdgeIds);
        if (deleted && graph != null) graph.removeNode(nodeId);
        return barrierSucceeded && deleted;
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNode(nodeId);
        }

        Map<String, Object> stored = vectorStore.getVectorDocument(
                GRAPH_PREFIX + graphId + NODE_METADATA_PREFIX + nodeId);
        if (stored == null) {
            // Legacy graphs stored metadata only on the vector-bearing node document.
            stored = vectorStore.getVectorDocument(GRAPH_PREFIX + graphId + NODE_PREFIX + nodeId);
        }
        if (stored == null) return Optional.empty();
        MatrixGraphNode decoded = deserializeNodeFromMetadata(flattenDoc(stored));
        return decoded != null && nodeId.equals(decoded.getNodeId())
                ? Optional.of(decoded) : Optional.empty();
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getAllNodes();
        }

        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        List<MatrixGraphNode> nodes = new ArrayList<>();
        int cursor = 0;
        ScanPage<MatrixGraphNode> page;
        do {
            page = scanNodes(graphId, cursor, Math.max(1, vectorScanPageSize));
            nodes.addAll(page.items());
            if (page.hasMore() && page.nextCursor() <= cursor) {
                throw new IllegalStateException("Node scan cursor did not advance for graph " + graphId);
            }
            cursor = page.nextCursor();
        } while (page.hasMore());
        if (storage.version() >= MIN_CANONICAL_DOCUMENT_STORAGE_VERSION
                && storage.nodeCount() <= nodes.size()) {
            return nodes;
        }
        // Read-only compatibility for legacy or partially migrated graphs. Rehydration already
        // merges canonical metadata and legacy node documents by node id.
        return loadGraph(graphId).map(AdjacencyMatrixGraph::getAllNodes).orElse(Collections.emptyList());
    }

    @Override
    public ScanPage<MatrixGraphNode> scanNodes(String graphId, int cursor, int pageSize) {
        graphId = resolveGraphId(graphId);
        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        requireCompleteStorageState(graphId, storage);
        if (storage.version() < MIN_CANONICAL_DOCUMENT_STORAGE_VERSION) {
            Optional<AdjacencyMatrixGraph> legacy = loadGraph(graphId);
            if (legacy.isPresent()) return scanLegacyNodes(legacy.get(), cursor, pageSize);
        }
        return scanGraphDocuments(graphId, cursor, pageSize,
                NODE_METADATA_PREFIX, "graph_node", this::deserializeNodeFromMetadata);
    }

    @Override
    public ScanPage<StoredEdge> scanEdges(String graphId, int cursor, int pageSize) {
        graphId = resolveGraphId(graphId);
        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        requireCompleteStorageState(graphId, storage);
        if (storage.version() < MIN_CANONICAL_DOCUMENT_STORAGE_VERSION) {
            Optional<AdjacencyMatrixGraph> legacy = loadGraph(graphId);
            if (legacy.isPresent()) return scanLegacyEdges(legacy.get(), cursor, pageSize);
        }
        return scanGraphDocuments(graphId, cursor, pageSize,
                EDGE_PREFIX, "graph_edge", this::deserializeStoredEdge);
    }

    @Override
    public synchronized IncidentEdges scanIncidentEdges(
            String graphId, String nodeId, EdgeDirection direction, int maxEdges) {
        graphId = resolveGraphId(graphId);
        EdgeDirection effective = direction == null ? EdgeDirection.BOTH : direction;
        int limit = Math.min(Math.max(0, maxEdges), Math.max(1, maxIncidentEdges));
        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        requireCompleteStorageState(graphId, storage);
        if (storage.version() < MIN_CANONICAL_DOCUMENT_STORAGE_VERSION
                || !ensureEndpointMetadataIndex(graphId)) {
            return MatrixGraphStore.super.scanIncidentEdges(graphId, nodeId, effective, limit);
        }

        LinkedHashMap<String, StoredEdge> matches = new LinkedHashMap<>();
        int fetchLimit = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
        if (effective != EdgeDirection.INCOMING) {
            collectIndexedEdges(graphId, "sourceNodeId", nodeId, false, fetchLimit, matches);
        }
        if (effective != EdgeDirection.OUTGOING) {
            collectIndexedEdges(graphId, "targetNodeId", nodeId, false, fetchLimit, matches);
        }
        if (effective == EdgeDirection.OUTGOING) {
            collectIndexedEdges(graphId, "targetNodeId", nodeId, true, fetchLimit, matches);
        } else if (effective == EdgeDirection.INCOMING) {
            collectIndexedEdges(graphId, "sourceNodeId", nodeId, true, fetchLimit, matches);
        }
        boolean truncated = matches.size() > limit;
        List<StoredEdge> result = matches.values().stream().limit(limit).toList();
        return new IncidentEdges(result, truncated);
    }

    private void collectIndexedEdges(
            String graphId,
            String endpointField,
            String nodeId,
            boolean bidirectionalOnly,
            int limit,
            Map<String, StoredEdge> target) {
        if (target.size() >= limit) return;
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("type", "graph_edge");
        filters.put("graphId", graphId);
        filters.put(endpointField, nodeId);
        if (bidirectionalOnly) filters.put("bidirectional", "true");
        for (Map<String, Object> raw : vectorStore.listVectorDocumentsByMetadata(filters, limit)) {
            StoredEdge edge = deserializeStoredEdge(flattenDoc(raw));
            if (edge == null) continue;
            String key = edge.edgeType() + '\u0000' + edge.sourceNodeId() + '\u0000' + edge.targetNodeId();
            target.putIfAbsent(key, edge);
            if (target.size() >= limit) return;
        }
    }

    private boolean ensureEndpointMetadataIndex(String graphId) {
        if (graphStorageMetadata(graphId).version() >= CANONICAL_DOCUMENT_STORAGE_VERSION) return true;
        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        requireCompleteStorageState(graphId, storage);
        if (storage.version() != MIN_CANONICAL_DOCUMENT_STORAGE_VERSION) {
            throw new IllegalStateException("Graph " + graphId
                    + " is not eligible for endpoint-index promotion from storage version "
                    + storage.version());
        }
        Path spool = null;
        try {
                spool = Files.createTempFile("kompile-graph-endpoint-index-", ".bin");
                long[] spooledEdges = {0};
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(spool))) {
                    String edgePrefix = GRAPH_PREFIX + graphId + EDGE_PREFIX;
                    scanVectorDocumentsStrict(page -> {
                        for (Map<String, Object> raw : page) {
                            String id = raw.get("id") instanceof String value ? value : null;
                            if (id == null || !id.startsWith(edgePrefix)) continue;
                            StoredEdge edge = deserializeStoredEdge(flattenDoc(raw));
                            if (edge == null) continue;
                            writeSpoolEdge(out, edge);
                            spooledEdges[0]++;
                        }
                    });
                }
                if (spooledEdges[0] != storage.edgeCount()) {
                    throw new IOException("Endpoint metadata index edge count does not match graph metadata: "
                            + spooledEdges[0] + " != " + storage.edgeCount());
                }
                try (DataInputStream in = new DataInputStream(Files.newInputStream(spool))) {
                    List<Document> documents = new ArrayList<>(Math.max(1, vectorScanPageSize));
                    while (true) {
                        StoredEdge edge = readSpoolEdge(in);
                        if (edge == null) break;
                        documents.add(createEdgeDocument(
                                graphId, edge.sourceNodeId(), edge.targetNodeId(), edge.weight(),
                                edge.edgeType(), edge.bidirectional(), edge.relationType(),
                                edge.confidence(), edge.description(), edge.metadata()));
                        if (documents.size() >= Math.max(1, vectorScanPageSize)) {
                            persistEndpointIndexBatch(documents);
                            documents.clear();
                        }
                    }
                    persistEndpointIndexBatch(documents);
                }
                markEndpointMetadataIndexed(graphId, spooledEdges[0]);
                if (!vectorStore.flushAndCommit()) {
                    throw new IOException("Endpoint metadata index commit failed for " + graphId);
                }
                return true;
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Could not build endpoint metadata index for graph " + graphId, failure);
        } finally {
                if (spool != null) {
                    try {
                        Files.deleteIfExists(spool);
                    } catch (IOException cleanupFailure) {
                        log.debug("Could not delete endpoint-index spool {}: {}",
                                spool, cleanupFailure.getMessage());
                    }
                }
        }
    }

    private void persistEndpointIndexBatch(List<Document> documents) throws IOException {
        if (!documents.isEmpty()
                && vectorStore.addStoredOnlyDocuments(documents) != documents.size()) {
            throw new IOException("Endpoint metadata index rewrite was incomplete");
        }
    }

    private void writeSpoolEdge(DataOutputStream out, StoredEdge edge) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceNodeId", edge.sourceNodeId());
        value.put("targetNodeId", edge.targetNodeId());
        value.put("edgeType", edge.edgeType());
        value.put("weight", edge.weight());
        value.put("bidirectional", edge.bidirectional());
        value.put("relationType", edge.relationType());
        value.put("confidence", edge.confidence());
        value.put("description", edge.description());
        value.put("metadata", edge.metadata());
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    @SuppressWarnings("unchecked")
    private StoredEdge readSpoolEdge(DataInputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        int second = in.read();
        int third = in.read();
        int fourth = in.read();
        if ((second | third | fourth) < 0) {
            throw new EOFException("Truncated endpoint-index spool row length");
        }
        int length = first << 24 | second << 16 | third << 8 | fourth;
        if (length < 1 || length > 16 * 1024 * 1024) {
            throw new IOException("Invalid endpoint-index spool row length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated endpoint-index spool row");
        Map<String, Object> value = objectMapper.readValue(bytes, Map.class);
        return new StoredEdge(
                String.valueOf(value.get("sourceNodeId")),
                String.valueOf(value.get("targetNodeId")),
                String.valueOf(value.get("edgeType")),
                ((Number) value.get("weight")).doubleValue(),
                Boolean.TRUE.equals(value.get("bidirectional")),
                value.get("relationType") instanceof String relationType ? relationType : null,
                value.get("confidence") instanceof Number confidence ? confidence.doubleValue() : null,
                value.get("description") instanceof String description ? description : null,
                value.get("metadata") instanceof Map<?, ?> metadata
                        ? (Map<String, Object>) metadata : Map.of());
    }

    @SuppressWarnings("unchecked")
    private void markEndpointMetadataIndexed(String graphId, long edgeCount) throws IOException {
        String id = GRAPH_PREFIX + graphId + META_SUFFIX;
        Map<String, Object> raw = vectorStore.getVectorDocument(id);
        if (raw == null) throw new IOException("Graph metadata is missing for " + graphId);
        Object nested = raw.get("metadata");
        Map<String, Object> metadata = nested instanceof Map<?, ?> map
                ? new HashMap<>((Map<String, Object>) map)
                : new HashMap<>(flattenDoc(raw));
        metadata.keySet().removeAll(Set.of("id", "content", "preview", "lucene_internal_id", "metadata"));
        metadata.put("storageVersion", CANONICAL_DOCUMENT_STORAGE_VERSION);
        metadata.put("edgeCount", edgeCount);
        if (vectorStore.addStoredOnlyDocuments(List.of(
                new Document(id, objectMapper.writeValueAsString(metadata), metadata))) != 1) {
            throw new IOException("Could not publish endpoint metadata index version for " + graphId);
        }
    }

    private static void requireCompleteStorageState(
            String graphId, GraphStorageMetadata storage) {
        if (storage.version() == STORAGE_VERSION_IN_PROGRESS) {
            throw new IllegalStateException("Graph " + graphId
                    + " has an interrupted storage transaction and must be rebuilt or rolled back");
        }
    }

    private ScanPage<MatrixGraphNode> scanLegacyNodes(
            AdjacencyMatrixGraph graph, int cursor, int pageSize) {
        if (cursor < 0 || pageSize <= 0) {
            throw new IllegalArgumentException("cursor must be >= 0 and pageSize must be > 0");
        }
        List<MatrixGraphNode> nodes = new ArrayList<>(graph.getAllNodes());
        nodes.sort(Comparator.comparing(MatrixGraphNode::getNodeId));
        int from = Math.min(cursor, nodes.size());
        int to = Math.min(nodes.size(), from + pageSize);
        return new ScanPage<>(new ArrayList<>(nodes.subList(from, to)), to, to < nodes.size());
    }

    private ScanPage<StoredEdge> scanLegacyEdges(
            AdjacencyMatrixGraph graph, int cursor, int pageSize) {
        if (cursor < 0 || pageSize <= 0) {
            throw new IllegalArgumentException("cursor must be >= 0 and pageSize must be > 0");
        }
        List<StoredEdge> page = new ArrayList<>(pageSize);
        int seen = 0;
        boolean hasMore = false;
        List<String> edgeTypes = new ArrayList<>(graph.getEdgeTypes());
        edgeTypes.sort(String::compareTo);
        outer:
        for (String edgeType : edgeTypes) {
            AdjacencyMatrixGraph.SparseEdgeData sparse = graph.getSparseEdges(edgeType);
            for (int i = 0; i < sparse.size(); i++) {
                if (seen < cursor) {
                    seen++;
                    continue;
                }
                if (page.size() == pageSize) {
                    hasMore = true;
                    break outer;
                }
                int[] indices = sparse.indices.get(i);
                String source = graph.getIndexToNodeId().get(indices[0]);
                String target = graph.getIndexToNodeId().get(indices[1]);
                AdjacencyMatrixGraph.EdgeMeta meta = sparse.edgeMetas.isEmpty()
                        ? null : sparse.edgeMetas.get(i);
                String relationType = sparse.relationTypes.isEmpty()
                        ? null : sparse.relationTypes.get(i);
                seen++;
                if (source == null || target == null) continue;
                AdjacencyMatrixGraph.EdgeMeta reverseMeta = graph.getEdgeMeta(
                        edgeType, target, source);
                if (meta == null && reverseMeta != null
                        && Boolean.TRUE.equals(reverseMeta.bidirectional())) {
                    continue;
                }
                page.add(new StoredEdge(source, target, edgeType, sparse.weights.get(i),
                        meta != null && Boolean.TRUE.equals(meta.bidirectional()), relationType,
                        meta != null ? meta.confidence() : null,
                        meta != null ? meta.description() : null,
                        meta != null ? meta.metadata() : Map.of()));
            }
        }
        return new ScanPage<>(page, seen, hasMore);
    }

    private <T> ScanPage<T> scanGraphDocuments(String graphId, int cursor, int pageSize,
                                                String documentMarker,
                                                String expectedType,
                                                java.util.function.Function<Map<String, Object>, T> decoder) {
        if (cursor < 0 || pageSize <= 0) {
            throw new IllegalArgumentException("cursor must be >= 0 and pageSize must be > 0");
        }
        String graphPrefix = GRAPH_PREFIX + graphId + documentMarker;
        List<T> items = new ArrayList<>(pageSize);
        int scanCursor = cursor;
        boolean moreDocuments = true;
        while (items.size() < pageSize && moreDocuments) {
            // Scan full bounded Lucene pages even when only one result slot remains. Graph documents
            // can be sparse in the shared project index; shrinking this to the remaining result count
            // degenerates into one Lucene request per unrelated document.
            int fetchSize = Math.max(1, vectorScanPageSize);
            List<Map<String, Object>> docs = vectorStore.listVectorDocuments(scanCursor, fetchSize);
            if (docs.isEmpty()) {
                moreDocuments = false;
                break;
            }
            for (int i = 0; i < docs.size(); i++) {
                scanCursor++;
                Map<String, Object> raw = docs.get(i);
                String id = raw.get("id") instanceof String value ? value : null;
                if (id == null || !id.startsWith(graphPrefix)) {
                    continue;
                }
                Map<String, Object> flat = flattenDoc(raw);
                if (!expectedType.equals(flat.get("type"))) {
                    continue;
                }
                T decoded = decoder.apply(flat);
                if (decoded != null) {
                    items.add(decoded);
                    if (items.size() == pageSize) {
                        boolean hasMore = docs.size() == fetchSize;
                        if (!hasMore) {
                            for (int j = i + 1; j < docs.size(); j++) {
                                Map<String, Object> remaining = docs.get(j);
                                String remainingId = remaining.get("id") instanceof String value
                                        ? value : null;
                                if (remainingId == null || !remainingId.startsWith(graphPrefix)) continue;
                                Map<String, Object> remainingFlat = flattenDoc(remaining);
                                if (expectedType.equals(remainingFlat.get("type"))
                                        && decoder.apply(remainingFlat) != null) {
                                    hasMore = true;
                                    break;
                                }
                            }
                        }
                        return new ScanPage<>(items, scanCursor, hasMore);
                    }
                }
            }
            moreDocuments = docs.size() == fetchSize;
        }
        return new ScanPage<>(items, scanCursor, moreDocuments);
    }

    @SuppressWarnings("unchecked")
    private StoredEdge deserializeStoredEdge(Map<String, Object> doc) {
        if (!(doc.get("sourceNodeId") instanceof String source)
                || !(doc.get("targetNodeId") instanceof String target)
                || !(doc.get("edgeType") instanceof String type)
                || !(doc.get("weight") instanceof Number weight)) {
            return null;
        }
        return new StoredEdge(source, target, type, weight.doubleValue(),
                Boolean.TRUE.equals(doc.get("bidirectional")),
                doc.get("relationType") instanceof String value ? value : null,
                doc.get("confidence") instanceof Number value ? value.doubleValue() : null,
                doc.get("description") instanceof String value ? value : null,
                doc.get("edgeMetadata") instanceof Map<?, ?> value
                        ? (Map<String, Object>) value : Map.of());
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        String resolvedGraphId = resolveGraphId(graphId);
        // Use vector store similarity search
        List<Document> results = vectorStore.similaritySearch(query, limit);

        String nodePrefix = GRAPH_PREFIX + resolvedGraphId + NODE_PREFIX;
        return results.stream()
                .filter(doc -> doc.getId() != null && doc.getId().startsWith(nodePrefix))
                .map(doc -> {
                    String nodeId = doc.getId().substring(nodePrefix.length());
                    return getNode(resolvedGraphId, nodeId).orElseGet(() -> deserializeNode(doc));
                })
                .filter(Objects::nonNull)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public synchronized boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                          double weight, String edgeType, boolean bidirectional) {
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType,
                bidirectional, null, null, null);
    }

    @Override
    public synchronized boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                          double weight, String edgeType, boolean bidirectional, String relationType) {
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType,
                bidirectional, relationType, null, null);
    }

    /** [M-7] Full-metadata override — persists confidence and description alongside weight/relationType. */
    @Override
    public synchronized boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional,
                           String relationType, Double confidence, String description) {
        graphId = resolveGraphId(graphId);
        String type = edgeType != null ? edgeType : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        boolean added = graph.addEdge(sourceNodeId, targetNodeId, weight, type, bidirectional,
                relationType, confidence, description);
        if (added) {
            try {
                persistEdge(graphId, sourceNodeId, targetNodeId, weight, type,
                        bidirectional, relationType, confidence, description, null);
            } catch (RuntimeException failure) {
                graph.removeEdge(sourceNodeId, targetNodeId, type);
                if (bidirectional) graph.removeEdge(targetNodeId, sourceNodeId, type);
                throw failure;
            }
        }
        return added;
    }

    @Override
    public synchronized boolean mergeEdgeMetadata(String graphId,
                                     String sourceNodeId,
                                     String targetNodeId,
                                     String edgeType,
                                     Map<String, Object> additionalMetadata) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        String type = edgeType != null ? edgeType : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
        StoredEdge stored = storedEdge(graphId, type, sourceNodeId, targetNodeId);
        if (stored == null) return false;
        Map<String, Object> metadata = new LinkedHashMap<>(stored.metadata());
        if (additionalMetadata != null) metadata.putAll(additionalMetadata);
        persistEdge(graphId, stored.sourceNodeId(), stored.targetNodeId(), stored.weight(),
                stored.edgeType(), stored.bidirectional(), stored.relationType(), stored.confidence(),
                stored.description(), metadata);
        return graph.mergeEdgeMetadata(
                type, stored.sourceNodeId(), stored.targetNodeId(), additionalMetadata);
    }

    private StoredEdge storedEdge(
            String graphId, String type, String sourceNodeId, String targetNodeId) {
        Map<String, Object> raw = vectorStore.getVectorDocument(
                edgeDocumentId(graphId, type, sourceNodeId, targetNodeId));
        StoredEdge edge = raw == null ? null : deserializeStoredEdge(flattenDoc(raw));
        if (edge != null && sourceNodeId.equals(edge.sourceNodeId())
                && targetNodeId.equals(edge.targetNodeId()) && type.equals(edge.edgeType())) {
            return edge;
        }
        raw = vectorStore.getVectorDocument(edgeDocumentId(graphId, type, targetNodeId, sourceNodeId));
        edge = raw == null ? null : deserializeStoredEdge(flattenDoc(raw));
        return edge != null && edge.bidirectional()
                && targetNodeId.equals(edge.sourceNodeId())
                && sourceNodeId.equals(edge.targetNodeId()) && type.equals(edge.edgeType())
                ? edge : null;
    }

    @Override
    public synchronized boolean removeEdge(
            String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        graphId = resolveGraphId(graphId);
        String type = edgeType != null ? edgeType : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        boolean existedInCache = graph != null && graph.hasEdge(sourceNodeId, targetNodeId, type);
        AdjacencyMatrixGraph.EdgeMeta cachedMeta = graph == null ? null
                : graph.getEdgeMeta(type, sourceNodeId, targetNodeId);
        boolean cachedCanonicalReverse = false;
        if (cachedMeta == null && graph != null) {
            cachedMeta = graph.getEdgeMeta(type, targetNodeId, sourceNodeId);
            cachedCanonicalReverse = cachedMeta != null
                    && Boolean.TRUE.equals(cachedMeta.bidirectional());
        }
        boolean cachedBidirectional = cachedMeta != null
                && Boolean.TRUE.equals(cachedMeta.bidirectional());
        String edgeId = edgeDocumentId(graphId, type, sourceNodeId, targetNodeId);
        if (cachedCanonicalReverse) {
            edgeId = edgeDocumentId(graphId, type, targetNodeId, sourceNodeId);
        }
        if (existedInCache) {
            if (!vectorStore.delete(List.of(edgeId))) return false;
            graph.removeEdge(sourceNodeId, targetNodeId, type);
            if (cachedBidirectional) graph.removeEdge(targetNodeId, sourceNodeId, type);
            return true;
        }
        Map<String, Object> stored = vectorStore.getVectorDocument(edgeId);
        StoredEdge storedEdge = stored == null ? null : deserializeStoredEdge(flattenDoc(stored));
        if (storedEdge != null && (!sourceNodeId.equals(storedEdge.sourceNodeId())
                || !targetNodeId.equals(storedEdge.targetNodeId())
                || !type.equals(storedEdge.edgeType()))) {
            storedEdge = null;
        }
        if (storedEdge == null) {
            stored = null;
            String reverseId = edgeDocumentId(graphId, type, targetNodeId, sourceNodeId);
            Map<String, Object> reverse = vectorStore.getVectorDocument(reverseId);
            StoredEdge reverseEdge = reverse == null ? null : deserializeStoredEdge(flattenDoc(reverse));
            if (reverseEdge != null && reverseEdge.bidirectional()
                    && targetNodeId.equals(reverseEdge.sourceNodeId())
                    && sourceNodeId.equals(reverseEdge.targetNodeId())
                    && type.equals(reverseEdge.edgeType())) {
                edgeId = reverseId;
                stored = reverse;
                storedEdge = reverseEdge;
            }
        }
        if (storedEdge == null) return false;
        return vectorStore.delete(List.of(edgeId));
    }

    @Override
    public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNeighbors(nodeId, edgeType);
        }
        List<Map.Entry<String, Double>> neighbors = new ArrayList<>();
        int canonicalEdgeCount = 0;
        int cursor = 0;
        ScanPage<StoredEdge> page;
        do {
            page = scanEdges(graphId, cursor, Math.max(1, vectorScanPageSize));
            canonicalEdgeCount += page.items().size();
            for (StoredEdge edge : page.items()) {
                if (edgeType != null && !edgeType.equals(edge.edgeType())) continue;
                if (nodeId.equals(edge.sourceNodeId())) {
                    neighbors.add(Map.entry(edge.targetNodeId(), edge.weight()));
                } else if (edge.bidirectional() && nodeId.equals(edge.targetNodeId())) {
                    neighbors.add(Map.entry(edge.sourceNodeId(), edge.weight()));
                }
            }
            cursor = page.nextCursor();
        } while (page.hasMore());
        GraphStorageMetadata storage = graphStorageMetadata(graphId);
        if (storage.version() < MIN_CANONICAL_DOCUMENT_STORAGE_VERSION
                || storage.edgeCount() > canonicalEdgeCount) {
            return loadGraph(graphId)
                    .map(loaded -> loaded.getNeighbors(nodeId, edgeType))
                    .orElse(neighbors);
        }
        return neighbors;
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        String type = edgeType != null ? edgeType : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.hasEdge(sourceNodeId, targetNodeId, type);
        }
        Map<String, Object> exact = vectorStore.getVectorDocument(
                edgeDocumentId(graphId, type, sourceNodeId, targetNodeId));
        StoredEdge exactEdge = exact == null ? null : deserializeStoredEdge(flattenDoc(exact));
        if (exactEdge != null && sourceNodeId.equals(exactEdge.sourceNodeId())
                && targetNodeId.equals(exactEdge.targetNodeId())
                && type.equals(exactEdge.edgeType())) {
            return true;
        }
        Map<String, Object> reverse = vectorStore.getVectorDocument(
                edgeDocumentId(graphId, type, targetNodeId, sourceNodeId));
        StoredEdge reverseEdge = reverse == null ? null : deserializeStoredEdge(flattenDoc(reverse));
        boolean foundReverse = reverseEdge != null && reverseEdge.bidirectional()
                && targetNodeId.equals(reverseEdge.sourceNodeId())
                && sourceNodeId.equals(reverseEdge.targetNodeId())
                && type.equals(reverseEdge.edgeType());
        if (foundReverse) return true;
        if (graphStorageMetadata(graphId).version() < MIN_CANONICAL_DOCUMENT_STORAGE_VERSION) {
            return loadGraph(graphId)
                    .map(loaded -> loaded.hasEdge(sourceNodeId, targetNodeId, type))
                    .orElse(false);
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public synchronized void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);

        // Store embeddings in vector store
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            Optional<MatrixGraphNode> nodeOpt = graph.getNode(nodeId);
            if (nodeOpt.isPresent()) {
                MatrixGraphNode node = nodeOpt.get();
                Document doc = createNodeVectorDocument(graphId, node);
                documents.add(doc);
            }
        }

        if (documents.size() != nodeIds.size()) {
            throw new IllegalArgumentException("Every embedding row must resolve to a graph node");
        }
        if (!documents.isEmpty()) {
            if (vectorStore.addWithEmbeddings(documents, embeddings) != documents.size()
                    || !vectorStore.flushAndCommit()) {
                throw new IllegalStateException("Node embedding persistence was incomplete");
            }
            graph.setNodeEmbeddings(nodeIds, embeddings);
        }
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
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
        graphId = resolveGraphId(graphId);
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
    public synchronized int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;

        List<Document> metadataDocuments = new ArrayList<>();
        List<Document> vectorDocuments = new ArrayList<>();
        Map<String, MatrixGraphNode> previousNodes = new HashMap<>();
        List<MatrixGraphNode> newNodes = new ArrayList<>();
        for (MatrixGraphNode node : nodes) {
            Optional<MatrixGraphNode> previous = graph.getNode(node.getNodeId());
            if (previous.isPresent()) {
                previousNodes.put(node.getNodeId(), previous.get());
                node.setMatrixIndex(previous.get().getMatrixIndex());
            } else {
                graph.addNode(node);
                newNodes.add(node);
            }
            metadataDocuments.add(createNodeMetadataDocument(graphId, node));
            vectorDocuments.add(createNodeVectorDocument(graphId, node));
            count++;
        }

        if (!metadataDocuments.isEmpty()) {
            try {
                addStoredOnlyOrThrow(metadataDocuments, "graph node batch");
            } catch (RuntimeException failure) {
                String resolvedGraphId = graphId;
                List<String> newMetadataIds = newNodes.stream()
                        .map(node -> GRAPH_PREFIX + resolvedGraphId
                                + NODE_METADATA_PREFIX + node.getNodeId())
                        .toList();
                if (!newMetadataIds.isEmpty() && !vectorStore.delete(newMetadataIds)) {
                    failure.addSuppressed(new IllegalStateException(
                            "Could not remove partially written node metadata"));
                }
                if (!previousNodes.isEmpty()) {
                    try {
                        addStoredOnlyOrThrow(previousNodes.values().stream()
                                        .map(node -> createNodeMetadataDocument(resolvedGraphId, node)).toList(),
                                "graph node batch compensation");
                    } catch (RuntimeException compensation) {
                        failure.addSuppressed(compensation);
                    }
                }
                newNodes.forEach(node -> graph.removeNode(node.getNodeId()));
                throw failure;
            }
            nodes.stream().filter(node -> previousNodes.containsKey(node.getNodeId()))
                    .forEach(graph::addNode);
            // Preserve main-process text embedding behavior. In the graph subprocess add() returns
            // zero, but the canonical metadata documents above are already durable.
            try {
                vectorStore.add(vectorDocuments);
            } catch (RuntimeException vectorFailure) {
                log.warn("Canonical node metadata was stored, but optional vector enrichment failed: {}",
                        vectorFailure.getMessage());
            }
        }

        return count;
    }

    @Override
    public synchronized int addEdgesBatch(String graphId, List<EdgeDefinition> edges) {
        graphId = resolveGraphId(graphId);
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        List<Document> documents = new ArrayList<>();
        List<EdgeDefinition> accepted = new ArrayList<>();
        for (EdgeDefinition edge : edges) {
            if (graph.getNode(edge.sourceNodeId()).isEmpty()
                    || graph.getNode(edge.targetNodeId()).isEmpty()) {
                continue;
            }
            String type = edge.edgeType() != null
                    ? edge.edgeType() : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
            documents.add(createEdgeDocument(graphId, edge.sourceNodeId(), edge.targetNodeId(),
                    edge.weight(), type, edge.bidirectional(), edge.relationType(),
                    null, null, null));
            accepted.add(edge);
        }
        addStoredOnlyOrThrow(documents, "graph edge batch");
        int count = 0;
        for (EdgeDefinition edge : accepted) {
            String type = edge.edgeType() != null
                    ? edge.edgeType() : AdjacencyMatrixGraph.DEFAULT_EDGE_TYPE;
            if (graph.addEdge(edge.sourceNodeId(), edge.targetNodeId(),
                    edge.weight(), type, edge.bidirectional(), edge.relationType())) count++;
        }
        return count;
    }

    @Override
    public synchronized void flush() {
        for (AdjacencyMatrixGraph graph : graphCache.values()) {
            try {
                saveGraph(graph);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to flush graph " + graph.getGraphId(), e);
            }
        }
        if (!vectorStore.flushAndCommit()) {
            throw new IllegalStateException("Final vector store graph commit failed");
        }
    }

    @Override
    public void awaitPendingEmbeddings() {
        vectorStore.awaitPendingEmbeddings();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getGraphStatistics(String graphId) {
        graphId = resolveGraphId(graphId);
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
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
        String resolved = resolveGraphId(graphId);
        return graphCache.computeIfAbsent(resolved, id -> {
            Optional<AdjacencyMatrixGraph> loaded = loadGraph(id);
            if (loaded.isPresent()) {
                return loaded.get();
            }
            // Brand-new graph (e.g. the first addNode for a per-fact-sheet graph). Persist its
            // :meta doc immediately so listGraphs() can discover it — and rehydrateGraphsOnStartup()
            // can reload it — even if a flush()/saveGraph() never runs. Without this, node docs are
            // written under graph:{id}:node:* but the graph has no :meta record, so after a restart it
            // is invisible to listGraphs() and its nodes (tables/entities) silently disappear.
            AdjacencyMatrixGraph g = new AdjacencyMatrixGraph(id, 1024);
            try {
                saveGraphMetadata(g);
            } catch (IOException e) {
                log.error("Failed to persist initial metadata for graph {}", id, e);
            }
            return g;
        });
    }

    private String resolveGraphId(String graphId) {
        if (graphId == null || graphId.contains("~gen~") || generationGraphs.contains(graphId)) return graphId;
        if (generationJournal != null) {
            Optional<GraphGeneration.Pointer> authoritative = generationJournal.pointer(graphId);
            if (authoritative.isPresent()) return authoritative.get().activePhysicalGraphId();
        }
        GenerationPointer pointer = pointer(graphId);
        return pointer == null ? graphId : pointer.active();
    }

    private GenerationPointer pointer(String logicalGraphId) {
        GenerationPointer cached = generationPointers.get(logicalGraphId);
        if (cached != null) return cached;
        return pointer(logicalGraphId, false);
    }

    private GenerationPointer pointer(String logicalGraphId, boolean refresh) {
        GenerationPointer cached = generationPointers.get(logicalGraphId);
        if (!refresh && cached != null) return cached;
        try {
            Map<String, Object> raw = vectorStore.getVectorDocumentStrict(pointerDocId(logicalGraphId));
            if (raw == null) return cached;
            Map<String, Object> doc = flattenDoc(raw);
            String active = doc.get("activePhysicalGraphId") instanceof String value ? value : null;
            if (active == null || active.isBlank()) return cached;
            String previous = doc.get("previousPhysicalGraphId") instanceof String value ? value : null;
            long revision = doc.get("revision") instanceof Number value ? value.longValue() : 0L;
            GenerationPointer loaded = new GenerationPointer(active, previous, revision);
            generationPointers.put(logicalGraphId, loaded);
            if (active.contains("~gen~")) generationGraphs.add(active);
            if (previous != null && previous.contains("~gen~")) generationGraphs.add(previous);
            return loaded;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not read graph generation pointer for " + logicalGraphId, e);
        }
    }

    private void refreshGenerationPointersStrict() {
        try {
            scanVectorDocumentsStrict(page -> {
                for (Map<String, Object> raw : page) {
                    String id = raw.get("id") instanceof String value ? value : null;
                    if (id == null || !id.startsWith(POINTER_PREFIX)) continue;
                    Map<String, Object> doc = flattenDoc(raw);
                    if (!"graph_generation_pointer".equals(doc.get("type"))) {
                        throw new IOException("Malformed graph generation pointer " + id);
                    }
                    String logical = id.substring(POINTER_PREFIX.length());
                    String active = doc.get("activePhysicalGraphId") instanceof String value
                            ? value : null;
                    if (active == null || active.isBlank()) {
                        throw new IOException("Graph generation pointer has no active graph: " + id);
                    }
                    String previous = doc.get("previousPhysicalGraphId") instanceof String value
                            ? value : null;
                    long revision = doc.get("revision") instanceof Number value
                            ? value.longValue() : 0L;
                    generationPointers.put(logical, new GenerationPointer(active, previous, revision));
                    if (active.contains("~gen~")) generationGraphs.add(active);
                    if (previous != null && previous.contains("~gen~")) generationGraphs.add(previous);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not refresh graph generation pointers", failure);
        }
    }

    private void writePointer(String logicalGraphId, GenerationPointer pointer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "graph_generation_pointer");
        metadata.put("logicalGraphId", logicalGraphId);
        metadata.put("activePhysicalGraphId", pointer.active());
        metadata.put("previousPhysicalGraphId", pointer.previous());
        metadata.put("revision", pointer.revision());
        metadata.values().removeIf(Objects::isNull);
        try {
            String json = objectMapper.writeValueAsString(metadata);
            int written = vectorStore.addStoredOnlyDocuments(
                    List.of(new Document(pointerDocId(logicalGraphId), json, metadata)));
            if (written != 1) throw new IllegalStateException("Graph generation pointer was not persisted");
            if (!vectorStore.flushAndCommit()) {
                throw new IllegalStateException("Graph generation pointer commit failed");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode graph generation pointer", e);
        }
    }

    private boolean deletePhysicalGraph(String graphId) {
        try {
            AdjacencyMatrixGraph graph = graphCache.get(graphId);
            awaitPendingNodeWrites("delete graph generation", graphId, "*");
            List<String> ids = collectMatchingIdsStrict(GRAPH_PREFIX + graphId + ":");
            String metaId = GRAPH_PREFIX + graphId + META_SUFFIX;
            if (!ids.contains(metaId)) ids.add(metaId);
            if (!ids.isEmpty()) {
                if (!vectorStore.delete(ids) || !vectorStore.flushAndCommit()) {
                    throw new IllegalStateException("Graph generation deletion was not committed");
                }
            }
            graphCache.remove(graphId);
            if (graph != null) graph.close();
            generationGraphs.remove(graphId);
            return graph != null || !ids.isEmpty();
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete graph generation " + graphId, e);
        }
    }

    private static String pointerDocId(String logicalGraphId) {
        return POINTER_PREFIX + logicalGraphId;
    }

    private void saveGraphMetadata(AdjacencyMatrixGraph graph) throws IOException {
        saveGraphMetadata(graph, CANONICAL_DOCUMENT_STORAGE_VERSION);
    }

    private void saveGraphMetadata(AdjacencyMatrixGraph graph, int storageVersion) throws IOException {
        String docId = GRAPH_PREFIX + graph.getGraphId() + META_SUFFIX;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graph.getGraphId());
        metadata.put("factSheetId", graph.getFactSheetId());
        metadata.put("nodeCount", graph.getNodeCount());
        metadata.put("edgeCount", persistedLogicalEdgeCount(graph));
        metadata.put("edgeTypes", new ArrayList<>(graph.getEdgeTypes()));
        metadata.put("capacity", graph.getCurrentCapacity());
        metadata.put("embeddingDimension", graph.getEmbeddingDimension());
        metadata.put("type", "graph_metadata");
        metadata.put("storageVersion", storageVersion);
        metadata.values().removeIf(Objects::isNull);

        Document doc = new Document(docId, objectMapper.writeValueAsString(metadata), metadata);
        if (vectorStore.addStoredOnlyDocuments(List.of(doc)) != 1) {
            throw new IOException("Could not persist graph metadata for " + graph.getGraphId());
        }
    }

    /**
     * Saves all live nodes with a correctly-aligned embedding matrix.
     *
     * <p><b>Save-side fix:</b> {@link AdjacencyMatrixGraph#getNodeEmbeddings()} may return a
     * matrix whose row count equals {@code nextIndex} (total ever-assigned indices), not
     * {@code nodeById.size()} (live node count after removals). Passing the oversized matrix to
     * {@code addWithEmbeddings} causes a rows≠docs mismatch in the Anserini impl (which then
     * falls back to re-embedding — slow and lossy).
     *
     * <p>We build a compact {@code [liveNodes × embDim]} matrix by reading each live node's
     * persisted {@code matrixIndex} as the row offset into the full embedding array and writing
     * it at position {@code i} (matching {@code documents.get(i)}). Row-to-document
     * correspondence is therefore exact and the rows==docs check passes cleanly.</p>
     */
    private void saveNodes(AdjacencyMatrixGraph graph) {
        INDArray fullEmbeddings = graph.getNodeEmbeddings();
        int embDim = graph.getEmbeddingDimension();
        List<MatrixGraphNode> liveNodes = graph.getAllNodes();

        List<Document> metadataDocuments = liveNodes.stream()
                .map(node -> createNodeMetadataDocument(graph.getGraphId(), node))
                .toList();
        addStoredOnlyOrThrow(metadataDocuments, "graph nodes");

        if (fullEmbeddings == null || embDim <= 0 || liveNodes.isEmpty()) return;
        List<MatrixGraphNode> embeddedNodes = new ArrayList<>();
        List<INDArray> embeddedRows = new ArrayList<>();
        // Bulk-copy once. A row is an NDArray view, so row.data().asFloat() would expose the
        // parent's full backing buffer and could mistake another node's values for this row.
        INDArray contiguousEmbeddings = fullEmbeddings.dup('c');
        try {
            float[] embeddingValues = contiguousEmbeddings.data().asFloat();
            for (MatrixGraphNode node : liveNodes) {
                int idx = node.getMatrixIndex();
                long rowOffset = (long) idx * embDim;
                if (idx < 0 || idx >= fullEmbeddings.rows() || rowOffset > Integer.MAX_VALUE
                        || !hasUsableEmbedding(embeddingValues, (int) rowOffset, embDim)) {
                    continue;
                }
                embeddedNodes.add(node);
                embeddedRows.add(fullEmbeddings.getRow(idx));
            }
        } finally {
            contiguousEmbeddings.close();
        }
        if (embeddedNodes.isEmpty()) return;

        INDArray alignedEmbeddings = Nd4j.zeros(DataType.FLOAT, embeddedNodes.size(), embDim);
        for (int i = 0; i < embeddedRows.size(); i++) {
            alignedEmbeddings.putRow(i, embeddedRows.get(i));
        }
        try {
            int written = vectorStore.addWithEmbeddings(embeddedNodes.stream()
                    .map(node -> createNodeVectorDocument(graph.getGraphId(), node))
                    .toList(), alignedEmbeddings);
            if (written != embeddedNodes.size()) {
                throw new IllegalStateException("Short graph-node embedding write: expected "
                        + embeddedNodes.size() + " but wrote " + written);
            }
        } finally {
            alignedEmbeddings.close();
        }
    }

    private void saveNode(String graphId, MatrixGraphNode node, boolean forceVectorRefresh) {
        persistNodeMetadata(graphId, node);
        // addNode/updateNode are text changes and retain the historical re-embedding behavior.
        // The embedding-disabled subprocess may drop this vector document, but never the metadata.
        if (forceVectorRefresh) {
            awaitPendingNodeWrites("refresh", graphId, node.getNodeId());
            vectorStore.delete(List.of(nodeVectorDocumentId(graphId, node.getNodeId())));
        }
        try {
            vectorStore.add(List.of(createNodeVectorDocument(graphId, node)));
        } catch (RuntimeException vectorFailure) {
            log.warn("Canonical metadata for node {} was stored, but optional vector enrichment failed: {}",
                    node.getNodeId(), vectorFailure.getMessage());
        }
    }

    private boolean awaitPendingNodeWrites(String operation, String graphId, String nodeId) {
        try {
            vectorStore.awaitPendingEmbeddings();
            return true;
        } catch (RuntimeException e) {
            // Anserini's barrier completes all snapshotted futures before reporting aggregate
            // failure. Durable cleanup/replacement is therefore still safe and must be attempted.
            log.warn("Async embedding barrier reported a failure before node {} for {}/{}: {}",
                    operation, graphId, nodeId, e.getMessage());
            return false;
        }
    }

    private void persistNodeMetadata(String graphId, MatrixGraphNode node) {
        addStoredOnlyOrThrow(
                List.of(createNodeMetadataDocument(graphId, node)), "graph node metadata");
    }

    private Document createNodeMetadataDocument(String graphId, MatrixGraphNode node) {
        return createNodeDocument(GRAPH_PREFIX + graphId + NODE_METADATA_PREFIX + node.getNodeId(), node,
                "graph_node");
    }

    private Document createNodeVectorDocument(String graphId, MatrixGraphNode node) {
        return createNodeDocument(nodeVectorDocumentId(graphId, node.getNodeId()), node,
                "graph_node_vector");
    }

    private static String nodeVectorDocumentId(String graphId, String nodeId) {
        return GRAPH_PREFIX + graphId + NODE_PREFIX + nodeId;
    }

    private Document createNodeDocument(String docId, MatrixGraphNode node, String documentType) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", node.getNodeId());
        metadata.put("matrixIndex", node.getMatrixIndex());
        metadata.put("nodeType", node.getNodeType());
        metadata.put("title", node.getTitle());
        metadata.put("description", node.getDescription());
        metadata.put("factSheetId", node.getFactSheetId());
        metadata.put("createdAt", node.getCreatedAt());
        metadata.put("updatedAt", node.getUpdatedAt());
        metadata.put("type", documentType);
        metadata.values().removeIf(Objects::isNull);

        if (node.getMetadata() != null) {
            metadata.put("nodeMetadata", node.getMetadata());
        }

        String content = String.format("%s: %s",
                node.getTitle() != null ? node.getTitle() : node.getNodeId(),
                node.getDescription() != null ? node.getDescription() : "");

        return new Document(docId, content, metadata);
    }

    private static boolean hasUsableEmbedding(float[] values, int offset, int length) {
        if (values == null || offset < 0 || length <= 0 || offset + length > values.length) return false;
        boolean nonZero = false;
        for (int i = offset; i < offset + length; i++) {
            float value = values[i];
            if (!Float.isFinite(value)) return false;
            nonZero |= value != 0.0f;
        }
        return nonZero;
    }

    private long persistedLogicalEdgeCount(AdjacencyMatrixGraph graph) {
        long count = 0;
        for (String edgeType : graph.getEdgeTypes()) {
            AdjacencyMatrixGraph.SparseEdgeData sparse = graph.getSparseEdges(edgeType);
            for (int i = 0; i < sparse.size(); i++) {
                int[] pair = sparse.indices.get(i);
                String source = graph.getIndexToNodeId().get(pair[0]);
                String target = graph.getIndexToNodeId().get(pair[1]);
                if (source == null || target == null) continue;
                AdjacencyMatrixGraph.EdgeMeta meta = sparse.edgeMetas.isEmpty()
                        ? null : sparse.edgeMetas.get(i);
                AdjacencyMatrixGraph.EdgeMeta reverseMeta = graph.getEdgeMeta(edgeType, target, source);
                if (meta == null && reverseMeta != null
                        && Boolean.TRUE.equals(reverseMeta.bidirectional())) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private void saveEdges(AdjacencyMatrixGraph graph) throws IOException {
        String graphId = graph.getGraphId();

        Map<Integer, String> nodeIdsByIndex = new HashMap<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            nodeIdsByIndex.put(node.getMatrixIndex(), node.getNodeId());
        }

        List<Document> batch = new ArrayList<>(1000);
        for (String edgeType : graph.getEdgeTypes()) {
            AdjacencyMatrixGraph.SparseEdgeData sparseData = graph.getSparseEdges(edgeType);
            boolean hasRelationTypes = !sparseData.relationTypes.isEmpty();
            boolean hasEdgeMetas = !sparseData.edgeMetas.isEmpty();
            for (int i = 0; i < sparseData.size(); i++) {
                int[] pair = sparseData.indices.get(i);
                String sourceNodeId = nodeIdsByIndex.get(pair[0]);
                String targetNodeId = nodeIdsByIndex.get(pair[1]);
                if (sourceNodeId == null || targetNodeId == null) {
                    continue;
                }
                String relationType = hasRelationTypes ? sparseData.relationTypes.get(i) : null;
                AdjacencyMatrixGraph.EdgeMeta meta =
                        hasEdgeMetas ? sparseData.edgeMetas.get(i) : null;
                AdjacencyMatrixGraph.EdgeMeta reverseMeta = graph.getEdgeMeta(
                        edgeType, targetNodeId, sourceNodeId);
                // addEdge(..., bidirectional=true) creates two sparse directions but stores the
                // logical-edge metadata on the caller's original orientation only. Emit exactly
                // that orientation; serializing the metadata-free reverse would duplicate or
                // silently downgrade the relationship after restart.
                if (meta == null && reverseMeta != null
                        && Boolean.TRUE.equals(reverseMeta.bidirectional())) {
                    continue;
                }
                boolean bidirectional = meta != null && Boolean.TRUE.equals(meta.bidirectional());
                Document document = createEdgeDocument(graphId, sourceNodeId, targetNodeId,
                        sparseData.weights.get(i), edgeType, bidirectional, relationType,
                        meta != null ? meta.confidence() : null,
                        meta != null ? meta.description() : null,
                        meta != null ? meta.metadata() : null);
                batch.add(document);
                if (batch.size() == 1000) {
                    addStoredOnlyOrThrow(batch, "graph edges");
                    batch = new ArrayList<>(1000);
                }
            }
        }
        if (!batch.isEmpty()) {
            addStoredOnlyOrThrow(batch, "graph edges");
        }
        deleteStaleEdgeDocuments(graph);

        List<String> aggregates = collectMatchingIdsStrict(GRAPH_PREFIX + graphId + ADJ_PREFIX);
        aggregates.add(GRAPH_PREFIX + graphId + EMBD_SUFFIX);
        if (!vectorStore.delete(aggregates)) {
            throw new IOException("Could not delete legacy adjacency artifacts for graph " + graphId);
        }
    }

    private void deleteStaleEdgeDocuments(AdjacencyMatrixGraph graph) throws IOException {
        String prefix = GRAPH_PREFIX + graph.getGraphId() + EDGE_PREFIX;
        Path spool = Files.createTempFile("kompile-stale-graph-edges-", ".bin");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(spool))) {
                scanVectorDocumentsStrict(page -> {
                    for (Map<String, Object> raw : page) {
                        String id = raw.get("id") instanceof String value ? value : null;
                        if (id == null || !id.startsWith(prefix)) continue;
                        StoredEdge edge = deserializeStoredEdge(flattenDoc(raw));
                        if (edge != null && isCanonicalLiveEdge(graph, edge)) continue;
                        writeSpoolString(out, id);
                    }
                });
            }
            try (DataInputStream in = new DataInputStream(Files.newInputStream(spool))) {
                List<String> ids = new ArrayList<>(1_000);
                while (true) {
                    String id = readSpoolString(in);
                    if (id == null) break;
                    ids.add(id);
                    if (ids.size() == 1_000) {
                        deleteEdgeIds(ids);
                        ids.clear();
                    }
                }
                deleteEdgeIds(ids);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private static boolean isCanonicalLiveEdge(AdjacencyMatrixGraph graph, StoredEdge edge) {
        if (!graph.hasEdge(edge.sourceNodeId(), edge.targetNodeId(), edge.edgeType())) return false;
        AdjacencyMatrixGraph.EdgeMeta meta = graph.getEdgeMeta(
                edge.edgeType(), edge.sourceNodeId(), edge.targetNodeId());
        AdjacencyMatrixGraph.EdgeMeta reverse = graph.getEdgeMeta(
                edge.edgeType(), edge.targetNodeId(), edge.sourceNodeId());
        return meta != null || reverse == null || !Boolean.TRUE.equals(reverse.bidirectional());
    }

    private void deleteEdgeIds(List<String> ids) throws IOException {
        if (!ids.isEmpty() && !vectorStore.delete(new ArrayList<>(ids))) {
            throw new IOException("Could not delete stale graph edge documents");
        }
    }

    private static void writeSpoolString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readSpoolString(DataInputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        int second = in.read();
        int third = in.read();
        int fourth = in.read();
        if ((second | third | fourth) < 0) throw new EOFException("Truncated edge-id spool length");
        int length = first << 24 | second << 16 | third << 8 | fourth;
        if (length < 1 || length > 16 * 1024 * 1024) {
            throw new IOException("Invalid edge-id spool length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated edge-id spool value");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void addStoredOnlyOrThrow(List<Document> documents, String description) {
        if (documents == null || documents.isEmpty()) return;
        int written = vectorStore.addStoredOnlyDocuments(documents);
        if (written == documents.size()) return;
        // Deterministic IDs make singleton retries idempotent. This repairs partial batch writers
        // without accepting an ambiguous short count as success.
        for (Document document : documents) {
            if (vectorStore.addStoredOnlyDocuments(List.of(document)) != 1) {
                throw new IllegalStateException("Short stored-only write for " + description
                        + ": expected " + documents.size() + " but initial batch wrote " + written
                        + " and singleton retry failed for " + document.getId());
            }
        }
    }

    private int countPersistableEdges(AdjacencyMatrixGraph graph) {
        int count = 0;
        Map<Integer, String> nodeIdsByIndex = new HashMap<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            nodeIdsByIndex.put(node.getMatrixIndex(), node.getNodeId());
        }
        for (String edgeType : graph.getEdgeTypes()) {
            AdjacencyMatrixGraph.SparseEdgeData sparse = graph.getSparseEdges(edgeType);
            boolean hasEdgeMetas = !sparse.edgeMetas.isEmpty();
            for (int i = 0; i < sparse.size(); i++) {
                int[] pair = sparse.indices.get(i);
                String source = nodeIdsByIndex.get(pair[0]);
                String target = nodeIdsByIndex.get(pair[1]);
                if (source == null || target == null) continue;
                AdjacencyMatrixGraph.EdgeMeta meta = hasEdgeMetas ? sparse.edgeMetas.get(i) : null;
                AdjacencyMatrixGraph.EdgeMeta reverse = graph.getEdgeMeta(edgeType, target, source);
                if (meta == null && reverse != null && Boolean.TRUE.equals(reverse.bidirectional())) continue;
                count++;
            }
        }
        return count;
    }

    private void persistEdge(String graphId, String sourceNodeId, String targetNodeId,
                             double weight, String edgeType, boolean bidirectional,
                             String relationType, Double confidence, String description,
                             Map<String, Object> metadata) {
        addStoredOnlyOrThrow(List.of(createEdgeDocument(
                graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional,
                relationType, confidence, description, metadata)), "graph edge");
    }

    private Document createEdgeDocument(String graphId, String sourceNodeId, String targetNodeId,
                                        double weight, String edgeType, boolean bidirectional,
                                        String relationType, Double confidence, String description,
                                        Map<String, Object> edgeMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "graph_edge");
        metadata.put("graphId", graphId);
        metadata.put("sourceNodeId", sourceNodeId);
        metadata.put("targetNodeId", targetNodeId);
        metadata.put("edgeType", edgeType);
        metadata.put("weight", weight);
        metadata.put("bidirectional", bidirectional);
        metadata.put("relationType", relationType);
        metadata.put("confidence", confidence);
        metadata.put("description", description);
        if (edgeMetadata != null && !edgeMetadata.isEmpty()) {
            metadata.put("edgeMetadata", edgeMetadata);
        }
        metadata.values().removeIf(Objects::isNull);
        String content = relationType != null ? relationType : edgeType;
        return new Document(edgeDocumentId(graphId, edgeType, sourceNodeId, targetNodeId),
                content, metadata);
    }

    private String edgeDocumentId(String graphId, String edgeType,
                                  String sourceNodeId, String targetNodeId) {
        String key = edgeType + "\u0000" + sourceNodeId + "\u0000" + targetNodeId;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return GRAPH_PREFIX + graphId + EDGE_PREFIX + encoded;
    }

    private void saveAdjacencyMatrices(AdjacencyMatrixGraph graph) throws IOException {
        // Serialize each edge type directly from sparse storage — no INDArray needed.
        for (String edgeType : graph.getEdgeTypes()) {
            saveAdjacencyMatrix(graph.getGraphId(), edgeType,
                    graph.getSparseEdges(edgeType));
        }
    }

    private void saveAdjacencyMatrix(String graphId, String edgeType,
                                     AdjacencyMatrixGraph.SparseEdgeData sparseData)
            throws IOException {
        String docId = GRAPH_PREFIX + graphId + ADJ_PREFIX + edgeType;

        // Build the same JSON format as before (list of {source, target, weight} objects),
        // now extended with confidence, bidirectional, and description (M-7) so they survive
        // vector-store round-trips on the @Primary live path. restoreAdjacencyMatrix() reads
        // these fields via the same map-key names — old files without these fields are safe
        // (missing keys are treated as null/absent, preserving backward compatibility).
        List<Map<String, Object>> edges = new ArrayList<>(sparseData.size());
        boolean hasRelationTypes = !sparseData.relationTypes.isEmpty();
        boolean hasEdgeMetas = !sparseData.edgeMetas.isEmpty();  // [M-7]
        for (int i = 0; i < sparseData.size(); i++) {
            int[] pair = sparseData.indices.get(i);
            float weight = sparseData.weights.get(i);
            Map<String, Object> edge = new HashMap<>();
            edge.put("source", pair[0]);
            edge.put("target", pair[1]);
            edge.put("weight", weight);
            // Persist the explicit semantic relation as a first-class field alongside the weight.
            String relationType = hasRelationTypes ? sparseData.relationTypes.get(i) : null;
            if (relationType != null) {
                edge.put("relationType", relationType);
            }
            // [M-7] Persist confidence, bidirectional, description when present.
            if (hasEdgeMetas) {
                AdjacencyMatrixGraph.EdgeMeta meta = sparseData.edgeMetas.get(i);
                if (meta != null) {
                    if (meta.confidence() != null)   edge.put("confidence",   meta.confidence());
                    if (Boolean.TRUE.equals(meta.bidirectional())) edge.put("bidirectional", true);
                    if (meta.description() != null && !meta.description().isBlank())
                        edge.put("description", meta.description());
                    if (meta.metadata() != null && !meta.metadata().isEmpty())
                        edge.put("metadata", meta.metadata());
                }
            }
            edges.add(edge);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graphId);
        metadata.put("edgeType", edgeType);
        metadata.put("edgeCount", edges.size());
        metadata.put("type", "adjacency_matrix");

        String content = objectMapper.writeValueAsString(edges);
        Document doc = new Document(docId, content, metadata);
        vectorStore.addStoredOnlyDocuments(List.of(doc));
    }

    /**
     * Persists the node embedding matrix from {@code graph.getNodeEmbeddings()} to the vector
     * store as a single document with id {@code graph:{graphId}:embd}.
     *
     * <p>Format: a JSON array where each entry is
     * {@code {"storedIndex": N, "values": [f0, f1, …, fd-1]}} using the node's current
     * {@code matrixIndex} as the {@code storedIndex}.  On reload,
     * {@link #restoreNodeEmbeddings} uses the same {@code storedIndexToNodeId} map that
     * edge-restore uses, so a reordered reload still aligns every vector to the right node.
     * If no embeddings are present the document is not written (cold case is handled gracefully
     * in the load path).</p>
     */
    private void saveNodeEmbeddings(AdjacencyMatrixGraph graph) throws IOException {
        INDArray embd = graph.getNodeEmbeddings();
        if (embd == null) {
            return; // nothing to persist
        }

        String graphId = graph.getGraphId();
        int dim = (int) embd.columns();

        // Build a list of { storedIndex, values } entries — one per node that has a non-zero row.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            int idx = node.getMatrixIndex();
            if (idx < 0 || idx >= embd.rows()) {
                continue;
            }
            INDArray row = embd.getRow(idx);
            List<Float> values = new ArrayList<>(dim);
            for (int c = 0; c < dim; c++) {
                values.add(row.getFloat(c));
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("storedIndex", idx);
            entry.put("values", values);
            rows.add(entry);
        }

        if (rows.isEmpty()) {
            return; // all-zero or no nodes → skip
        }

        String docId = GRAPH_PREFIX + graphId + EMBD_SUFFIX;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graphId);
        metadata.put("embeddingDim", dim);
        metadata.put("type", "node_embeddings");

        String content = objectMapper.writeValueAsString(rows);
        Document doc = new Document(docId, content, metadata);
        vectorStore.addStoredOnlyDocuments(List.of(doc));
        log.debug("Persisted node embeddings for graph '{}': {} rows × {} dim", graphId, rows.size(), dim);
    }

    /**
     * Streams through the entire vector-store index page-by-page and returns the
     * IDs of every document whose {@code id} starts with {@code idPrefix}.
     *
     * <p>Unlike the old {@code listAllVectorDocuments()} — which accumulated every
     * document in heap — this method retains only the matching IDs (strings, not
     * the full map payload).  Heap usage per call is therefore bounded by
     * {@code vectorScanPageSize} raw docs (transient) plus the matched ID strings
     * (persistent), never the full index.  The VectorStore API does not expose a
     * native prefix/term query, so a full scan is unavoidable; the key improvement
     * is that non-matching docs are discarded inside the loop rather than
     * accumulated into a giant list.</p>
     *
     * @param idPrefix the document-ID prefix to match (e.g. {@code "graph:myId"})
     * @return mutable list of matching document IDs
     */
    private List<String> collectMatchingIds(String idPrefix) {
        List<String> matched = new ArrayList<>();
        int offset = 0;
        List<Map<String, Object>> page;
        do {
            page = vectorStore.listVectorDocuments(offset, vectorScanPageSize);
            for (Map<String, Object> doc : page) {
                String docId = (String) doc.get("id");
                if (docId != null && docId.startsWith(idPrefix)) {
                    matched.add(docId);
                }
                // Non-matching docs are not retained — released at end of page
            }
            offset += page.size();
        } while (page.size() == vectorScanPageSize);
        return matched;
    }

    private List<String> collectMatchingIdsStrict(String idPrefix) throws IOException {
        List<String> matched = new ArrayList<>();
        scanVectorDocumentsStrict(page -> {
            for (Map<String, Object> document : page) {
                Object rawId = document.get("id");
                if (rawId instanceof String id && id.startsWith(idPrefix)) matched.add(id);
            }
        });
        return matched;
    }

    private void scanVectorDocumentsStrict(VectorStore.DocumentPageConsumer consumer)
            throws IOException {
        boolean[] invoked = {false};
        vectorStore.scanVectorDocumentsStrict(Math.max(1, vectorScanPageSize), page -> {
            invoked[0] = true;
            consumer.accept(page);
        });
        // Compatibility for stores/proxies that predate the strict snapshot callback. The Anserini
        // implementation either invokes the callback or throws, so storage failures still fail closed.
        if (!invoked[0]) {
            int offset = 0;
            List<Map<String, Object>> page;
            do {
                page = vectorStore.listVectorDocuments(offset, Math.max(1, vectorScanPageSize));
                consumer.accept(page);
                offset += page.size();
            } while (page.size() == Math.max(1, vectorScanPageSize));
        }
    }

    /**
     * Flatten the map returned by {@link VectorStore#listVectorDocuments} so that
     * per-document metadata fields are accessible at the top level.
     *
     * <p>{@code listVectorDocuments} returns a map whose top-level keys are
     * {@code "id"}, {@code "content"} (or {@code "preview"}), and
     * {@code "metadata"} — a nested {@code Map<String,Object>} parsed from the
     * Lucene {@code "metadata"} stored-field JSON.  All the graph-specific fields
     * (e.g. {@code "type"}, {@code "nodeId"}, {@code "edgeType"}, …) live inside
     * that nested map.  The deserialization helpers expect them at the top level,
     * so we merge the nested map up before passing the doc along.</p>
     */
    private GraphStorageMetadata graphStorageMetadata(String graphId) {
        String metadataId = GRAPH_PREFIX + graphId + META_SUFFIX;
        Map<String, Object> raw = vectorStore.getVectorDocumentStrict(metadataId);
        if (raw == null) raw = vectorStore.getVectorDocument(metadataId);
        if (raw == null || raw.isEmpty()) return GraphStorageMetadata.LEGACY_UNKNOWN;
        Map<String, Object> metadata = flattenDoc(raw);
        int version = metadata.get("storageVersion") instanceof Number value
                ? value.intValue() : 1;
        int nodeCount = metadata.get("nodeCount") instanceof Number value
                ? Math.max(0, value.intValue()) : 0;
        long edgeCount = metadata.get("edgeCount") instanceof Number value
                ? Math.max(0L, value.longValue()) : 0L;
        return new GraphStorageMetadata(version, nodeCount, edgeCount);
    }

    private record GraphStorageMetadata(int version, int nodeCount, long edgeCount) {
        private static final GraphStorageMetadata LEGACY_UNKNOWN =
                new GraphStorageMetadata(1, 0, 0L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenDoc(Map<String, Object> doc) {
        Object nested = doc.get("metadata");
        if (!(nested instanceof Map)) {
            return doc; // nothing to flatten — return as-is
        }
        Map<String, Object> flat = new HashMap<>(doc);
        flat.putAll((Map<String, Object>) nested);
        return flat;
    }

    private Optional<AdjacencyMatrixGraph> loadGraphFromVectorStore(String graphId) {
        requireCompleteStorageState(graphId, graphStorageMetadata(graphId));
        // Page through the full index and keep ONLY docs whose id starts with the
        // target graph prefix.  Non-matching docs are discarded after each page so
        // heap holds at most vectorScanPageSize raw docs at a time — never the whole
        // index at once (which OOM'd at 6 000-node / 70 000-edge scale).
        String graphPrefix = GRAPH_PREFIX + graphId + ":";

        // Find metadata document
        AtomicReference<Map<String, Object>> metaDocHolder = new AtomicReference<>();
        Map<String, Map<String, Object>> nodeDocs = new LinkedHashMap<>();
        List<Map<String, Object>> edgeDocs = new ArrayList<>();
        List<Map<String, Object>> adjDocs = new ArrayList<>();
        AtomicReference<Map<String, Object>> embdDocHolder = new AtomicReference<>();

        try {
            scanVectorDocumentsStrict(page -> {
            for (Map<String, Object> rawDoc : page) {
                String docId = (String) rawDoc.get("id");
                if (docId == null || !docId.startsWith(graphPrefix)) {
                    // Not this graph's doc — drop it immediately, do not accumulate
                    continue;
                }

                // Flatten nested "metadata" map to the top level so that "type",
                // "nodeId", "edgeType", etc. are directly accessible.
                Map<String, Object> doc = flattenDoc(rawDoc);

                String type = (String) doc.get("type");
                if ("graph_metadata".equals(type)
                        && docId.equals(GRAPH_PREFIX + graphId + META_SUFFIX)) {
                    metaDocHolder.set(doc);
                } else if ("graph_node".equals(type)) {
                    String nodeId = doc.get("nodeId") instanceof String value ? value : docId;
                    // The dedicated metadata document is canonical. Legacy vector-bearing node
                    // documents remain readable only when no canonical record exists.
                    if (docId.contains(NODE_METADATA_PREFIX)) {
                        nodeDocs.put(nodeId, doc);
                    } else {
                        nodeDocs.putIfAbsent(nodeId, doc);
                    }
                } else if ("graph_edge".equals(type)) {
                    edgeDocs.add(doc);
                } else if ("adjacency_matrix".equals(type)) {
                    adjDocs.add(doc);
                } else if ("node_embeddings".equals(type)) {
                    embdDocHolder.set(doc);
                }
                // Unknown types are silently discarded
            }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Strict graph snapshot scan failed for " + graphId, failure);
        }

        Map<String, Object> metaDoc = metaDocHolder.get();
        Map<String, Object> embdDoc = embdDocHolder.get();

        if (metaDoc == null) {
            log.debug("No graph_metadata document found for graphId='{}' in vector store", graphId);
            return Optional.empty();
        }
        int scannedVersion = metaDoc.get("storageVersion") instanceof Number value
                ? value.intValue() : 1;
        requireCompleteStorageState(graphId, new GraphStorageMetadata(scannedVersion, 0, 0));

        // Reconstruct graph
        Object capObj = metaDoc.get("capacity");
        int capacity = (capObj instanceof Number) ? ((Number) capObj).intValue() : 1024;
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, capacity);

        Object factSheetIdObj = metaDoc.get("factSheetId");
        if (factSheetIdObj instanceof Number) {
            graph.setFactSheetId(((Number) factSheetIdObj).longValue());
        }

        // Add nodes and build a persisted-index → nodeId lookup.
        // AdjacencyMatrixGraph.addNode() assigns NEW runtime indices via nextIndex;
        // it does NOT honour the stored matrixIndex.  Edges in the adjacency-matrix
        // documents are keyed by the STORED indices, so we need this side-map to
        // translate them back to nodeIds before calling graph.addEdge(nodeId, …).
        Map<Integer, String> storedIndexToNodeId = new HashMap<>();
        for (Map<String, Object> nodeDoc : nodeDocs.values()) {
            MatrixGraphNode node = deserializeNodeFromMetadata(nodeDoc);
            if (node != null) {
                // Capture the persisted index BEFORE addNode() overwrites it.
                int persistedIndex = node.getMatrixIndex();
                graph.addNode(node);
                if (persistedIndex >= 0) {
                    storedIndexToNodeId.put(persistedIndex, node.getNodeId());
                }
            }
        }

        int totalEdgesFailed = 0;
        // Restore legacy aggregates first, then overlay the complete canonical edge set. Before
        // applying canonical records, clear both sparse orientations for every canonical endpoint
        // pair so a directed canonical edge cannot retain a stale legacy reverse direction.
        if (scannedVersion < CANONICAL_DOCUMENT_STORAGE_VERSION) {
            for (Map<String, Object> adjDoc : adjDocs) {
                totalEdgesFailed += restoreAdjacencyMatrix(graph, adjDoc, storedIndexToNodeId);
            }
        }
        for (Map<String, Object> edgeDoc : edgeDocs) {
            clearCanonicalEdgeEndpoints(graph, edgeDoc);
        }
        for (Map<String, Object> edgeDoc : edgeDocs) {
            totalEdgesFailed += restoreEdgeDocument(graph, edgeDoc);
        }
        if (totalEdgesFailed > 0) {
            log.warn("Graph '{}': {} edges could not be resolved and were dropped "
                    + "(nodes may have been removed since the last save)", graphId, totalEdgesFailed);
        }

        // Restore node embedding matrix (if one was persisted).
        if (embdDoc != null && scannedVersion < CANONICAL_DOCUMENT_STORAGE_VERSION) {
            restoreNodeEmbeddings(graph, embdDoc, storedIndexToNodeId);
        }

        log.info("Loaded graph '{}' from vector store: {} nodes, {} edge documents, embeddings={}",
                graphId, graph.getNodeCount(), edgeDocs.size(), embdDoc != null);
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
            String nodeId = (String) metadata.get("nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                return null; // skip non-node docs that slipped through
            }
            MatrixGraphNode.MatrixGraphNodeBuilder builder = MatrixGraphNode.builder()
                    .nodeId(nodeId)
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

    /**
     * Restores an adjacency matrix from a persisted document, using the provided
     * {@code storedIndexToNodeId} map to translate stored integer indices back to
     * node IDs.
     *
     * <p><b>Why a separate index map?</b>
     * {@link AdjacencyMatrixGraph#addNode} always assigns <em>new</em> runtime indices
     * via an internal {@code AtomicInteger}, ignoring the {@code matrixIndex} field stored
     * in the node document.  The adjacency-matrix JSON encodes edges by the <em>stored</em>
     * (persisted) indices, not the new runtime ones.  Passing in the persisted mapping
     * (built in {@link #loadGraphFromVectorStore} before any node is added) ensures we
     * look up the right nodeIds regardless of the order in which nodes are re-added.</p>
     *
     * @return the count of edges that could NOT be resolved (for aggregate logging)
     */
    @SuppressWarnings("unchecked")
    private void clearCanonicalEdgeEndpoints(
            AdjacencyMatrixGraph graph, Map<String, Object> edgeDoc) {
        String source = edgeDoc.get("sourceNodeId") instanceof String value ? value : null;
        String target = edgeDoc.get("targetNodeId") instanceof String value ? value : null;
        String edgeType = edgeDoc.get("edgeType") instanceof String value ? value : null;
        if (source == null || target == null || edgeType == null) return;
        graph.removeEdge(source, target, edgeType);
        graph.removeEdge(target, source, edgeType);
    }

    @SuppressWarnings("unchecked")
    private int restoreEdgeDocument(AdjacencyMatrixGraph graph, Map<String, Object> edgeDoc) {
        try {
            String sourceNodeId = (String) edgeDoc.get("sourceNodeId");
            String targetNodeId = (String) edgeDoc.get("targetNodeId");
            String edgeType = (String) edgeDoc.get("edgeType");
            if (sourceNodeId == null || targetNodeId == null || edgeType == null
                    || !(edgeDoc.get("weight") instanceof Number weight)) {
                return 1;
            }
            String relationType = edgeDoc.get("relationType") instanceof String value ? value : null;
            Double confidence = edgeDoc.get("confidence") instanceof Number value
                    ? value.doubleValue() : null;
            boolean bidirectional = Boolean.TRUE.equals(edgeDoc.get("bidirectional"));
            String description = edgeDoc.get("description") instanceof String value ? value : null;
            Map<String, Object> metadata = edgeDoc.get("edgeMetadata") instanceof Map<?, ?> value
                    ? (Map<String, Object>) value : null;
            if (!graph.addEdge(sourceNodeId, targetNodeId, weight.doubleValue(), edgeType,
                    bidirectional, relationType, confidence, description)) {
                return 1;
            }
            if (metadata != null && !metadata.isEmpty()) {
                graph.mergeEdgeMetadata(edgeType, sourceNodeId, targetNodeId, metadata);
            }
            return 0;
        } catch (RuntimeException e) {
            log.warn("Skipping malformed graph_edge document", e);
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private int restoreAdjacencyMatrix(AdjacencyMatrixGraph graph, Map<String, Object> adjDoc,
                                       Map<Integer, String> storedIndexToNodeId) {
        int failCount = 0;
        try {
            String edgeType = (String) adjDoc.get("edgeType");
            Object content = adjDoc.get("content");

            if (content instanceof String contentStr) {
                List<Map<String, Object>> edges = objectMapper.readValue(
                        contentStr,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );

                for (Map<String, Object> edge : edges) {
                    if (!(edge.get("source") instanceof Number srcNum)
                            || !(edge.get("target") instanceof Number tgtNum)
                            || !(edge.get("weight") instanceof Number wgtNum)) {
                        log.warn("Skipping edge with missing source/target/weight fields in edgeType={}", edgeType);
                        failCount++;
                        continue;
                    }
                    int source = srcNum.intValue();
                    int target = tgtNum.intValue();
                    double weight = wgtNum.doubleValue();
                    Object rtObj = edge.get("relationType");
                    String relationType = rtObj instanceof String s ? s : null;

                    // [M-7] Read confidence, bidirectional, description from the stored JSON.
                    Double confidence = edge.get("confidence") instanceof Number cn ? cn.doubleValue() : null;
                    boolean bidirectional = Boolean.TRUE.equals(edge.get("bidirectional"));
                    Object descObj = edge.get("description");
                    String description = descObj instanceof String ds ? ds : null;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = edge.get("metadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : null;

                    // Resolve nodeIds from the PERSISTED index map, not the runtime one.
                    // (The runtime map uses new indices assigned by addNode; the stored
                    // indices come from the original crawl and must be resolved separately.)
                    String sourceId = storedIndexToNodeId.get(source);
                    String targetId = storedIndexToNodeId.get(target);

                    if (sourceId != null && targetId != null) {
                        graph.addEdge(sourceId, targetId, weight, edgeType, bidirectional, relationType,
                                confidence, description);  // [M-7]
                        if (metadata != null && !metadata.isEmpty()) {
                            graph.mergeEdgeMetadata(edgeType, sourceId, targetId, metadata);
                        }
                    } else {
                        // Demoted to DEBUG: the caller aggregates the count and emits a single WARN.
                        log.debug("Could not resolve edge source={} target={} for edgeType={}; "
                                + "storedIndexToNodeId has {} entries — node may have been removed",
                                source, target, edgeType, storedIndexToNodeId.size());
                        failCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to restore adjacency matrix", e);
        }
        return failCount;
    }

    /**
     * Reads the persisted node-embedding document and populates {@code graph.nodeEmbeddings}
     * so that {@link AdjacencyMatrixGraph#getNodeEmbeddings()} returns the original matrix.
     *
     * <p>Row alignment: each stored entry carries a {@code storedIndex} (the original
     * {@code matrixIndex} at save time).  We look it up in {@code storedIndexToNodeId} to get
     * the node ID, then look up the node's <em>current</em> runtime {@code matrixIndex} in the
     * freshly-reconstructed graph.  This mirrors exactly the approach used for edge restore,
     * so a reordered reload still aligns each vector to its correct node.</p>
     *
     * <p>Cold case: if {@code embdDoc} is null or the content is empty/unparseable, this method
     * is a no-op (leaves {@code nodeEmbeddings} null).  Dim/shape mismatches are logged and
     * skipped per-row rather than aborting the whole restore.</p>
     */
    @SuppressWarnings("unchecked")
    private void restoreNodeEmbeddings(AdjacencyMatrixGraph graph,
                                       Map<String, Object> embdDoc,
                                       Map<Integer, String> storedIndexToNodeId) {
        try {
            Object dimObj = embdDoc.get("embeddingDim");
            int dim = (dimObj instanceof Number n) ? n.intValue() : 0;
            if (dim <= 0) {
                log.warn("restoreNodeEmbeddings: embeddingDim missing or 0 — skipping");
                return;
            }

            Object content = embdDoc.get("content");
            if (!(content instanceof String json) || json.isBlank()) {
                log.warn("restoreNodeEmbeddings: no content in embeddings doc — skipping");
                return;
            }

            List<Map<String, Object>> rows = objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );

            if (rows == null || rows.isEmpty()) {
                return;
            }

            int nodeCount = graph.getNodeCount();
            INDArray matrix = Nd4j.zeros(DataType.FLOAT, nodeCount, dim);

            int restored = 0;
            for (Map<String, Object> entry : rows) {
                Object siObj = entry.get("storedIndex");
                if (!(siObj instanceof Number siNum)) {
                    continue;
                }
                int storedIdx = siNum.intValue();
                String nodeId = storedIndexToNodeId.get(storedIdx);
                if (nodeId == null) {
                    log.debug("restoreNodeEmbeddings: no nodeId for storedIndex={} — skipping row", storedIdx);
                    continue;
                }

                // Resolve the CURRENT runtime index for this node.
                int currentIdx = graph.getNode(nodeId)
                        .map(MatrixGraphNode::getMatrixIndex)
                        .orElse(-1);
                if (currentIdx < 0 || currentIdx >= nodeCount) {
                    log.debug("restoreNodeEmbeddings: nodeId={} currentIdx={} out of range — skipping", nodeId, currentIdx);
                    continue;
                }

                Object valuesObj = entry.get("values");
                if (!(valuesObj instanceof List<?> valuesList)) {
                    continue;
                }
                if (valuesList.size() != dim) {
                    log.warn("restoreNodeEmbeddings: nodeId={} has {} values, expected {} — skipping row",
                            nodeId, valuesList.size(), dim);
                    continue;
                }

                for (int c = 0; c < dim; c++) {
                    Object v = valuesList.get(c);
                    float f = (v instanceof Number vn) ? vn.floatValue() : 0f;
                    matrix.putScalar(currentIdx, c, f);
                }
                restored++;
            }

            // Values are already written into `matrix` at the correct current indices.
            // Inject directly via the Lombok-generated field setter (avoids the per-nodeId copy
            // loop in the public setNodeEmbeddings(List,INDArray) overload, which would also
            // re-allocate the backing array and overwrite our carefully aligned rows).
            graph.setNodeEmbeddings(matrix);
            graph.setEmbeddingDimension(dim);
            log.debug("Restored node embeddings for graph '{}': {}/{} rows × {} dim",
                    graph.getGraphId(), restored, rows.size(), dim);
        } catch (Exception e) {
            log.error("Failed to restore node embeddings for graph '{}'", graph.getGraphId(), e);
        }
    }
}
