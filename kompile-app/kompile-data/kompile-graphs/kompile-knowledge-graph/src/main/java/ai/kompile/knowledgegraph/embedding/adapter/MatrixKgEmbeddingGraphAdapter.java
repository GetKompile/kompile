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
package ai.kompile.knowledgegraph.embedding.adapter;

import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live-store {@link KgEmbeddingGraphAdapter} backed by the vector/matrix graph store. This is what
 * un-orphans the KG-embedding pipeline: triples are extracted from, and trained structural embeddings
 * written back to, the same graph the {@code MatrixGraphRagService} retriever actually queries.
 *
 * <p>Triples are keyed by stable node id (not title), so the model's entity-embedding keys are node
 * ids; {@link #storeEmbeddings} writes each entity vector into the corresponding node's metadata under
 * {@link #KGE_EMBEDDING_KEY} (round-tripped to the vector store via the node document), where the
 * retriever can read it back. Highest priority, so it wins whenever the live store is populated.</p>
 *
 * <p>[FIX-1] Added {@link KnowledgeGraphService} injection so {@link #hasGraphData},
 * {@link #extractTriples}, and {@link #storeEmbeddings} use the seam (which queries the
 * in-memory graph by factSheetId) instead of {@link MatrixGraphStore#listGraphsByFactSheet}
 * which returns [] because the live "default-knowledge-graph" container has factSheetId=null.</p>
 */
@Component
public class MatrixKgEmbeddingGraphAdapter implements KgEmbeddingGraphAdapter {

    private static final Logger log = LoggerFactory.getLogger(MatrixKgEmbeddingGraphAdapter.class);

    /** Node metadata key holding the comma-separated structural (KGE) embedding vector. */
    public static final String KGE_EMBEDDING_KEY = "kgeEmbedding";
    /** Node metadata key holding the algorithm name (TRANSE/ROTATE) used to train the vector. */
    public static final String KGE_ALGORITHM_KEY = "kgeAlgorithm";
    /** Node metadata key holding the embedding version (training-run timestamp). */
    public static final String KGE_VERSION_KEY = "kgeVersion";

    /**
     * Metadata key prefix for persisted KGE relation embeddings.
     * Each relation type's vector is stored as {@code KGE_RELATION_EMBEDDING_PREFIX + relationType}
     * in the per-fact-sheet sentinel node's metadata.
     */
    public static final String KGE_RELATION_EMBEDDING_PREFIX = "kgeRelEmb:";

    /**
     * Node-ID template for the synthetic sentinel node that carries all relation embeddings
     * for a fact sheet.  Relations are edge TYPES with no natural per-node home in the graph,
     * so we dedicate one CUSTOM node per fact sheet.  The node is invisible to graph queries
     * (it has no edges) but survives restarts / clone via the same metadata-JSON path that
     * entity embeddings use.
     *
     * <p>Format: {@code __kge_relations__<factSheetId>}</p>
     */
    static String relationSentinelNodeId(Long factSheetId) {
        return "__kge_relations__" + factSheetId;
    }

    private MatrixGraphStore store;

    /**
     * [FIX-1] KnowledgeGraphService seam: provides fact-sheet-scoped edge queries via
     * {@link KnowledgeGraphService#getEdgesInFactSheet(Long)} so we can extract triples
     * for nodes whose factSheetId is set at the node level (not the container graph level).
     */
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public MatrixKgEmbeddingGraphAdapter(MatrixGraphStore store) {
        this.store = store;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MatrixKgEmbeddingGraphAdapter() {}

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String storeType() {
        return "matrix";
    }

    /**
     * [FIX-1] Checks whether the fact sheet has any edges in the live graph.
     *
     * <p>Previously delegated to {@link MatrixGraphStore#listGraphsByFactSheet} which returns []
     * because the live "default-knowledge-graph" container has {@code factSheetId=null}. Now uses
     * {@link KnowledgeGraphService#getEdgesInFactSheet(Long)} as the primary — and sole — path when
     * the seam is wired, to avoid the broken subprocess-mode store RPC.</p>
     *
     * <p>[FIX-2] When {@code knowledgeGraphService} is non-null its answer is authoritative; the
     * {@link MatrixGraphStore#listGraphsByFactSheet} fallback is skipped entirely. In subprocess mode
     * that RPC returns a transport error (NPE with null message) because the store-level
     * {@code listGraphsByFactSheet} call is unreachable while the seam's
     * {@code getEdgesInFactSheet} path is fully routed through the working HTTP proxy. The store
     * fallback is only safe when the seam is absent (non-Spring / in-process store).</p>
     */
    @Override
    public boolean hasGraphData(Long factSheetId) {
        // [FIX-2] When seam is wired, use it exclusively — never fall through to store.listGraphsByFactSheet
        if (knowledgeGraphService != null) {
            try {
                boolean hasEdges = !knowledgeGraphService.getEdgesInFactSheet(factSheetId).isEmpty();
                log.debug("[FIX-2] hasGraphData({}): seam returned hasEdges={}", factSheetId, hasEdges);
                return hasEdges;
            } catch (Exception e) {
                log.warn("[FIX-2] hasGraphData({}): KnowledgeGraphService seam threw ({}); " +
                        "returning false to avoid broken store RPC in subprocess mode",
                        factSheetId, e.getMessage());
                return false;
            }
        }
        // Seam not wired: use store fallback (safe only when store is local / in-process)
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            Optional<AdjacencyMatrixGraph> g = store.loadGraph(graphId);
            if (g.isPresent() && g.get().getEdgeCount() > 0) {
                return true;
            }
        }
        // Last resort: check the default graph directly (factSheetId=null container)
        try {
            Optional<AdjacencyMatrixGraph> defaultGraph = store.loadGraph("default-knowledge-graph");
            if (defaultGraph.isPresent() && defaultGraph.get().getEdgeCount() > 0) {
                log.debug("[FIX-2] hasGraphData({}): found edges in default-knowledge-graph container", factSheetId);
                return true;
            }
        } catch (Exception e) {
            log.debug("[FIX-2] hasGraphData({}): default graph check failed: {}", factSheetId, e.getMessage());
        }
        return false;
    }

    /**
     * [FIX-1] Extracts triples for KGE training via the {@link KnowledgeGraphService} seam.
     *
     * <p>Previously used {@link MatrixGraphStore#listGraphsByFactSheet} which returns [] because
     * the live container has {@code factSheetId=null}. Now iterates the live graph's edges directly
     * via {@link KnowledgeGraphService#getEdgesInFactSheet(Long)}, producing
     * {@link Triple}(sourceNodeId, relation, targetNodeId) entries usable by the KGE trainer.</p>
     *
     * <p>[FIX-2] When {@code knowledgeGraphService} is non-null its result is returned
     * unconditionally — even when it returns 0 edges. The store fallback is intentionally
     * skipped: in subprocess mode {@link MatrixGraphStore#listGraphsByFactSheet} issues a broken
     * HTTP RPC that throws a transport NPE (null message), which is the root cause of the
     * {@code KGE training ended with status=FAILED} error. The store fallback is only reached
     * when the seam is absent (non-Spring / in-process store).</p>
     */
    @Override
    public List<Triple> extractTriples(Long factSheetId) {
        List<Triple> triples = new ArrayList<>();

        // [FIX-2] When seam is wired, use it exclusively — never fall through to store.listGraphsByFactSheet
        if (knowledgeGraphService != null) {
            try {
                List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
                for (GraphEdge edge : edges) {
                    // GraphEdge uses sourceNode/targetNode (GraphNode objects); extract the nodeId strings
                    String srcId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                    String tgtId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                    if (srcId == null || tgtId == null) {
                        continue;
                    }
                    String relation = edge.getRelationType() != null && !edge.getRelationType().isBlank()
                            ? edge.getRelationType()
                            : (edge.getEdgeType() != null ? edge.getEdgeType().name() : "USER_DEFINED");
                    triples.add(new Triple(srcId, relation, tgtId));
                }
                log.info("[FIX-2] Extracted {} triples via KnowledgeGraphService seam for fact sheet {}",
                        triples.size(), factSheetId);
                // Return seam result unconditionally — do NOT fall through to store.listGraphsByFactSheet
                return triples;
            } catch (Exception e) {
                log.warn("[FIX-2] extractTriples({}): KnowledgeGraphService seam threw ({}); " +
                        "returning empty to avoid broken store RPC in subprocess mode",
                        factSheetId, e.getMessage());
                return triples; // empty — caller will skip KGE (0 triples)
            }
        }

        // Seam not wired: use store fallback (safe only when store is local / in-process)
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            AdjacencyMatrixGraph graph = store.loadGraph(graphId).orElse(null);
            if (graph == null) {
                continue;
            }
            buildTriplesFromGraph(graph, triples);
        }
        // Last resort: check the default graph directly (factSheetId=null container)
        if (triples.isEmpty()) {
            try {
                AdjacencyMatrixGraph defaultGraph = store.loadGraph("default-knowledge-graph").orElse(null);
                if (defaultGraph != null) {
                    buildTriplesFromGraph(defaultGraph, triples);
                    log.debug("[FIX-2] extractTriples({}): used default-knowledge-graph container, {} triples",
                            factSheetId, triples.size());
                }
            } catch (Exception e) {
                log.debug("[FIX-2] extractTriples({}): default graph fallback failed: {}", factSheetId, e.getMessage());
            }
        }
        log.info("Extracted {} triples from live (matrix) store for fact sheet {}", triples.size(), factSheetId);
        return triples;
    }

    /** Build {@link Triple}s from all typed outgoing edges in an {@link AdjacencyMatrixGraph}. */
    private static void buildTriplesFromGraph(AdjacencyMatrixGraph graph, List<Triple> triples) {
        for (String edgeType : graph.getEdgeTypes()) {
            for (String sourceId : new ArrayList<>(graph.getNodeById().keySet())) {
                for (Map.Entry<String, Double> neighbor : graph.getNeighbors(sourceId, edgeType)) {
                    triples.add(new Triple(sourceId, edgeType, neighbor.getKey()));
                }
            }
        }
    }

    /**
     * [FIX-1] Stores KGE embeddings via the {@link KnowledgeGraphService} seam when available,
     * falling back to the direct store approach only when the seam is absent.
     *
     * <p>[FIX-2] When {@code knowledgeGraphService} is non-null the seam result is returned
     * unconditionally — even when {@code updated == 0} (no matching nodes). The store fallback
     * is skipped: in subprocess mode it would call {@link MatrixGraphStore#listGraphsByFactSheet}
     * which issues a broken HTTP RPC. The store path is only safe when the seam is absent.</p>
     */
    @Override
    public int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long version) {
        Map<String, INDArray> entityEmbeddings = model.getAllEntityEmbeddings();
        if (entityEmbeddings == null || entityEmbeddings.isEmpty()) {
            return 0;
        }
        String algorithm = model.getAlgorithm() != null ? model.getAlgorithm().name() : "UNKNOWN";

        // [FIX-2] When seam is wired, use it exclusively — never fall through to store.listGraphsByFactSheet
        if (knowledgeGraphService != null) {
            int updated = 0;
            try {
                // Step 1: Wait for all async sentence-embedding tasks to complete so the adjacency-matrix
                // cache has the sentence vectors for every entity node. Without this, the subsequent
                // updateNodeKgeMetadataBatch call would find no cached vector for each node and fall back
                // to enqueueing 5 903 sentence re-embeds in the async pool — the root cause of the OOM.
                log.info("[KGE] Awaiting pending sentence embeddings before batch KGE metadata write …");
                knowledgeGraphService.awaitPendingEmbeddings();

                // Step 2: Build the batch update list (one entry per entity, NO per-node RPC yet)
                List<KnowledgeGraphService.NodeMetadataUpdate> updates = new ArrayList<>(entityEmbeddings.size());
                for (Map.Entry<String, INDArray> entry : entityEmbeddings.entrySet()) {
                    String nodeId = entry.getKey();
                    INDArray vec = entry.getValue();
                    if (vec == null) continue;
                    Map<String, Object> kgeMeta = new HashMap<>();
                    kgeMeta.put(KGE_EMBEDDING_KEY, encode(vec));
                    kgeMeta.put(KGE_ALGORITHM_KEY, algorithm);
                    kgeMeta.put(KGE_VERSION_KEY, version);
                    updates.add(new KnowledgeGraphService.NodeMetadataUpdate(nodeId, kgeMeta));
                }

                // Step 3: One batched subprocess RPC instead of 5 903 individual updateNode RPCs.
                // The batch method merges the KGE keys into existing metadata (no metadata wipe)
                // and calls graphStore.updateNodeMetadata (no sentence re-embed) for each node.
                updated = knowledgeGraphService.updateNodeKgeMetadataBatch(updates);
                log.info("[KGE] Stored {} structural (KGE) embeddings via batch KnowledgeGraphService call "
                        + "for fact sheet {}", updated, factSheetId);

                // Step 4: Flush so the in-memory metadata changes are persisted to Lucene for
                // restart survival (since updateNodeMetadata skips Lucene when no cached vector).
                try {
                    knowledgeGraphService.flushPendingNodes();
                } catch (Exception flushEx) {
                    log.warn("[KGE] storeEmbeddings({}): non-fatal flush error after batch write: {}",
                            factSheetId, flushEx.getMessage());
                }

                // Return seam result unconditionally — do NOT fall through to store.listGraphsByFactSheet
                return updated;
            } catch (Exception e) {
                log.warn("[KGE] storeEmbeddings({}): batch KGE write threw ({}); " +
                        "skipping store fallback to avoid broken store RPC in subprocess mode",
                        factSheetId, e.getMessage());
                return updated; // partial result
            }
        }

        // Seam not wired: use store fallback (safe only when store is local / in-process)
        int updated = 0;
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            AdjacencyMatrixGraph graph = store.loadGraph(graphId).orElse(null);
            if (graph == null) {
                continue;
            }
            for (String nodeId : new ArrayList<>(graph.getNodeById().keySet())) {
                INDArray vec = entityEmbeddings.get(nodeId);
                if (vec == null) {
                    continue;
                }
                MatrixGraphNode node = graph.getNode(nodeId).orElse(null);
                if (node == null) {
                    continue;
                }
                if (node.getMetadata() == null) {
                    node.setMetadata(new HashMap<>());
                }
                node.getMetadata().put(KGE_EMBEDDING_KEY, encode(vec));
                node.getMetadata().put(KGE_ALGORITHM_KEY, algorithm);
                node.getMetadata().put(KGE_VERSION_KEY, version);
                store.updateNode(graphId, node);
                updated++;
            }
        }
        log.info("Stored {} structural (KGE) entity embeddings into the live (matrix) store for fact sheet {}",
                updated, factSheetId);

        // ── Relation-embedding persistence: deliberately NOT done via a synthetic graph node ──
        // Relations are edge TYPES with no per-node home. A prior attempt attached them to a CUSTOM
        // "sentinel" node, but that pollutes the live graph (the node surfaces in node stats,
        // browsing, retrieval and would project as a stray atom). Until relation embeddings have a
        // clean live-path home — a per-fact-sheet native graph section. Relation embeddings are
        // not persisted here yet. Entity warm-start is unaffected; relations
        // simply cold-start (today's behaviour) rather than via a polluting workaround. The warm-start
        // plumbing (loadRelationEmbeddings → launcher relations:{} → importRelationEmbeddings) stays
        // wired so the follow-up only needs to fill the store + reader. See loadRelationEmbeddings().
        return updated;
    }

    /** Serializes a vector to a comma-separated string for lossless round-trip through node metadata JSON. */
    static String encode(INDArray vec) {
        float[] values = vec.toFloatVector();
        StringBuilder sb = new StringBuilder(values.length * 8);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /** Parses a vector previously written by {@link #encode}; returns {@code null} for absent/blank values. */
    public static INDArray decode(Object metadataValue) {
        if (metadataValue == null) {
            return null;
        }
        String s = metadataValue.toString();
        if (s.isBlank()) {
            return null;
        }
        String[] parts = s.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return Nd4j.create(values);
    }

    /**
     * Reads back persisted KGE entity embeddings for the given fact sheet from node metadata.
     *
     * <p>Each node whose metadata contains a {@link #KGE_EMBEDDING_KEY} entry contributes one
     * entry to the returned map. The key is the node's {@code nodeId} — the same ID used as
     * the entity key during training. Nodes with a missing or blank embedding value are skipped.</p>
     *
     * <p>Returns an empty map when no nodes have been embedded yet (first-ever crawl). This is
     * the cold-start signal: the caller should fall back to random init + full {@code epochs}.</p>
     *
     * @param factSheetId fact sheet to read embeddings for
     * @return map from entity ID to its persisted {@link INDArray} vector; never {@code null}
     */
    public Map<String, INDArray> loadEmbeddings(Long factSheetId) {
        Map<String, INDArray> result = new HashMap<>();

        // [FIX-2] When seam is wired, use it exclusively — never fall through to store.listGraphsByFactSheet
        if (knowledgeGraphService != null) {
            try {
                List<GraphNode> nodes = knowledgeGraphService.getNodesInFactSheet(factSheetId);
                for (GraphNode node : nodes) {
                    if (node == null || node.getNodeId() == null) continue;
                    Map<String, Object> meta = node.getMetadata();
                    if (meta == null) continue;
                    Object raw = meta.get(KGE_EMBEDDING_KEY);
                    if (raw == null) continue;
                    INDArray vec = decode(raw);
                    if (vec != null) {
                        result.put(node.getNodeId(), vec);
                    }
                }
                log.info("[loadEmbeddings] Loaded {} persisted KGE entity embeddings via seam for fact sheet {}",
                        result.size(), factSheetId);
                // Return seam result unconditionally — do NOT fall through to store.listGraphsByFactSheet
                return result;
            } catch (Exception e) {
                log.warn("[loadEmbeddings] KnowledgeGraphService seam threw for factSheet={}: {}; " +
                        "skipping store fallback to avoid broken store RPC in subprocess mode",
                        factSheetId, e.getMessage());
                return result; // empty — caller treats as cold-start (no prior embeddings)
            }
        }

        // Seam not wired: use store fallback (safe only when store is local / in-process)
        for (String graphId : store.listGraphsByFactSheet(factSheetId)) {
            AdjacencyMatrixGraph graph = store.loadGraph(graphId).orElse(null);
            if (graph == null) continue;
            for (Map.Entry<String, MatrixGraphNode> entry : graph.getNodeById().entrySet()) {
                MatrixGraphNode node = entry.getValue();
                if (node == null || node.getMetadata() == null) continue;
                Object raw = node.getMetadata().get(KGE_EMBEDDING_KEY);
                if (raw == null) continue;
                INDArray vec = decode(raw);
                if (vec != null) {
                    result.put(entry.getKey(), vec);
                }
            }
        }
        // Last resort: default graph
        if (result.isEmpty()) {
            try {
                AdjacencyMatrixGraph defaultGraph = store.loadGraph("default-knowledge-graph").orElse(null);
                if (defaultGraph != null) {
                    for (Map.Entry<String, MatrixGraphNode> entry : defaultGraph.getNodeById().entrySet()) {
                        MatrixGraphNode node = entry.getValue();
                        if (node == null || node.getMetadata() == null) continue;
                        Object raw = node.getMetadata().get(KGE_EMBEDDING_KEY);
                        if (raw == null) continue;
                        INDArray vec = decode(raw);
                        if (vec != null) {
                            result.put(entry.getKey(), vec);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[loadEmbeddings] default graph fallback failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }
        log.info("[loadEmbeddings] Loaded {} persisted KGE entity embeddings (store fallback) for fact sheet {}",
                result.size(), factSheetId);
        return result;
    }

    /**
     * Reads back persisted KGE <em>relation</em> embeddings for the given fact sheet from the
     * per-fact-sheet sentinel node.
     *
     * <p>Returns an empty map on the first-ever crawl (cold start) or when the sentinel node does
     * not yet exist. Never throws; errors degrade gracefully to cold-start.</p>
     *
     * @param factSheetId fact sheet to read relation embeddings for
     * @return map from relation type to its persisted {@link INDArray} vector; never {@code null}
     */
    @Override
    public Map<String, INDArray> loadRelationEmbeddings(Long factSheetId) {
        // Relation embeddings are not persisted on the live path yet (see storeEmbeddings: the
        // synthetic-sentinel-node approach was rejected to avoid polluting the live graph). Returning
        // empty makes relations cold-start — the safe, honest default — until the per-fact-sheet JSON
        // relation store (mirroring MebnWeightPersistenceAdapter) lands. The warm-start plumbing
        // downstream already tolerates an empty map. NO graph node is read here by design.
        return new HashMap<>();
    }
}
