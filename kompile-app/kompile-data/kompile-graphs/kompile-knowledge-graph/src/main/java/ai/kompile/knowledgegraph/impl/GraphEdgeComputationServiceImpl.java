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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.GraphEdgeComputationService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of {@link GraphEdgeComputationService} that computes
 * embedding similarity edges and shared entity edges in the knowledge graph.
 */
@Service
public class GraphEdgeComputationServiceImpl implements GraphEdgeComputationService {

    private static final Logger log = LoggerFactory.getLogger(GraphEdgeComputationServiceImpl.class);

    // Field injection (NOT constructor) — the @Transactional CGLIB proxy is built via the no-arg
    // constructor, and Spring does NOT apply constructor args to the proxy, so constructor-injected
    // fields were null on the live path (NPE in computeSharedEntityEdges). @Autowired fields ARE
    // applied to the proxy instance.
    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    // Hot-reloadable managed config (no @Value, no hard-coded literals). Optional so the service
    // still functions if the manager bean is absent — it then defaults to star topology.
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger lastEdgesCreated = new AtomicInteger(0);
    private volatile String currentOperation = "idle";

    /** Maximum members per alias bucket that are wired to the hub node in one star pass.
     *  Buckets exceeding this are logged at WARN and the first N members are linked. */
    private static final int HUB_MAX_BUCKET_SIZE = 1_000;

    /** Chunk size for {@link KnowledgeGraphService#createEdgesBatch} calls within one bucket,
     *  bounding the per-call allocation of {@link KnowledgeGraphService.EdgeSpec} lists. */
    private static final int EDGE_BATCH_CHUNK = 500;

    /** All dependencies are field-injected (above) so they survive the @Transactional CGLIB proxy,
     *  which is instantiated via this no-arg constructor — constructor args are NOT applied to the
     *  proxy, which is why constructor injection left knowledgeGraphService null on the live path. */
    public GraphEdgeComputationServiceImpl() {}


    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING SIMILARITY EDGES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void computeEmbeddingSimilarityEdges(double minSimilarity, int maxEdgesPerNode) {
        computeEmbeddingSimilarityEdges(null, minSimilarity, maxEdgesPerNode);
    }

    @Override
    @Transactional
    public void computeEmbeddingSimilarityEdges(Long factSheetId, double minSimilarity, int maxEdgesPerNode) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Edge computation already running");
            return;
        }
        cancelled.set(false);
        currentOperation = "embedding_similarity";
        int edgesCreated = 0;

        try {
            // Get all DOCUMENT nodes — similarity edges connect documents.
            // Use the store-agnostic KnowledgeGraphService seam (nodeRepository is null on the live
            // matrix/vector store path and must NOT be called there).
            List<GraphNode> docNodes = factSheetId != null
                    ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.DOCUMENT)
                    : knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT);
            if (docNodes.size() < 2) {
                log.info("Not enough DOCUMENT nodes ({}) for similarity computation (factSheetId={})",
                        docNodes.size(), factSheetId);
                return;
            }

            log.info("Computing embedding similarity edges for {} document nodes (factSheetId={}, minSimilarity={})",
                    docNodes.size(), factSheetId, minSimilarity);

            // Reuse vectors already persisted by crawl/KGE learning. Only missing documents are
            // sent to the injected model, avoiding duplicate inference immediately after backfill and
            // preserving similarity computation when the model is temporarily unavailable.
            Map<String, float[]> embeddings = new LinkedHashMap<>();
            Map<String, INDArray> persisted = knowledgeGraphService.exportNodeEmbeddings(factSheetId);
            if (persisted != null && !persisted.isEmpty()) {
                try {
                    for (GraphNode node : docNodes) {
                        INDArray stored = persisted.get(node.getNodeId());
                        float[] vector = toUsableEmbeddingVector(stored, node.getNodeId());
                        if (vector != null) {
                            embeddings.put(node.getNodeId(), vector);
                        }
                    }
                } finally {
                    closeEmbeddings(persisted.values());
                }
            }
            for (GraphNode node : docNodes) {
                if (embeddings.containsKey(node.getNodeId()) || node.getKgEmbedding() == null) {
                    continue;
                }
                float[] vector = toUsableEmbeddingVector(node.getKgEmbedding(), node.getNodeId());
                if (vector != null) {
                    embeddings.put(node.getNodeId(), vector);
                }
            }

            List<String> batchTexts = new ArrayList<>(docNodes.size());
            List<String> batchNodeIds = new ArrayList<>(docNodes.size());
            if (embeddingModel != null) {
                for (GraphNode node : docNodes) {
                    if (cancelled.get()) break;
                    if (embeddings.containsKey(node.getNodeId())) continue;
                    String text = buildEmbeddingText(node);
                    if (text == null || text.isBlank()) continue;
                    batchTexts.add(text);
                    batchNodeIds.add(node.getNodeId());
                }
            }
            if (!cancelled.get() && embeddingModel != null && !batchTexts.isEmpty()) {
                try {
                    List<float[]> vectors = embeddingModel.embedBatch(batchTexts);
                    int n = vectors == null ? 0 : Math.min(vectors.size(), batchNodeIds.size());
                    for (int k = 0; k < n; k++) {
                        float[] vector = toUsableEmbeddingVector(vectors.get(k), batchNodeIds.get(k));
                        if (vector != null) {
                            embeddings.put(batchNodeIds.get(k), vector);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Batch embedding failed for {} node(s): {}", batchTexts.size(), e.getMessage());
                }
            }

            log.info("Computed embeddings for {} of {} nodes", embeddings.size(), docNodes.size());
            if (embeddings.size() < 2) {
                log.info("Not enough usable DOCUMENT embeddings ({}) for similarity computation", embeddings.size());
                return;
            }

            // Compute pairwise cosine similarity and create edges
            List<String> nodeIds = new ArrayList<>(embeddings.keySet());
            Map<String, Integer> edgeCount = new HashMap<>();

            for (int i = 0; i < nodeIds.size() && !cancelled.get(); i++) {
                String id1 = nodeIds.get(i);
                float[] emb1 = embeddings.get(id1);
                int nodeEdges = edgeCount.getOrDefault(id1, 0);
                if (nodeEdges >= maxEdgesPerNode) continue;

                for (int j = i + 1; j < nodeIds.size() && !cancelled.get(); j++) {
                    String id2 = nodeIds.get(j);
                    int node2Edges = edgeCount.getOrDefault(id2, 0);
                    if (node2Edges >= maxEdgesPerNode) continue;

                    float[] emb2 = embeddings.get(id2);
                    double similarity = cosineSimilarity(emb1, emb2);
                    if (!Double.isFinite(similarity)) {
                        continue;
                    }

                    if (similarity >= minSimilarity) {
                        // Check if edge already exists (bidirectional) — agnostic seam, not JPA
                        if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(id1, id2).isEmpty()) {
                            knowledgeGraphService.createEdge(id1, id2,
                                    EdgeType.EMBEDDING_SIMILARITY, similarity,
                                    String.format("Cosine similarity: %.3f", similarity));
                            edgesCreated++;
                            edgeCount.merge(id1, 1, Integer::sum);
                            edgeCount.merge(id2, 1, Integer::sum);
                        }
                    }
                }
            }

            log.info("Created {} embedding similarity edges", edgesCreated);
        } finally {
            lastEdgesCreated.set(edgesCreated);
            currentOperation = "idle";
            running.set(false);
        }
    }

    @Override
    @Transactional
    public int backfillDocumentNodeEmbeddings(Long factSheetId) {
        if (embeddingModel == null || !embeddingModel.canEmbed()) {
            log.info("Embedding model unavailable; DOCUMENT embedding backfill skipped");
            return 0;
        }

        List<GraphNode> documentNodes = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.DOCUMENT)
                : knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT);
        if (documentNodes.isEmpty()) {
            return 0;
        }

        Set<String> existingNodeIds = new HashSet<>();
        Map<String, INDArray> exported = knowledgeGraphService.exportNodeEmbeddings(factSheetId);
        if (exported != null) {
            existingNodeIds.addAll(exported.keySet());
            closeEmbeddings(exported.values());
        }
        for (GraphNode node : documentNodes) {
            if (node.getKgEmbedding() != null && !node.getKgEmbedding().isEmpty()) {
                existingNodeIds.add(node.getNodeId());
            }
        }

        List<GraphNode> missing = documentNodes.stream()
                .filter(node -> node.getNodeId() != null && !existingNodeIds.contains(node.getNodeId()))
                .filter(node -> {
                    String text = buildEmbeddingText(node);
                    return text != null && !text.isBlank();
                })
                .toList();
        if (missing.isEmpty()) {
            return 0;
        }

        int optimalBatch = Math.max(1, embeddingModel.getOptimalBatchSize());
        int maximumBatch = Math.max(1, embeddingModel.getMaxBatchSize());
        int batchSize = Math.min(optimalBatch, maximumBatch);
        int applied = 0;

        for (int start = 0; start < missing.size(); start += batchSize) {
            int end = Math.min(start + batchSize, missing.size());
            List<GraphNode> batchNodes = missing.subList(start, end);
            List<String> texts = batchNodes.stream().map(this::buildEmbeddingText).toList();
            INDArray matrix = null;
            Map<String, INDArray> rows = new LinkedHashMap<>();
            try {
                matrix = embeddingModel.embed(texts);
                if (matrix == null || matrix.isEmpty()) {
                    continue;
                }
                int availableRows = matrix.rank() == 1 ? 1 : (int) matrix.rows();
                int count = Math.min(batchNodes.size(), availableRows);
                for (int i = 0; i < count; i++) {
                    INDArray row = matrix.rank() == 1 ? matrix : matrix.getRow(i);
                    if (toUsableEmbeddingVector(row, batchNodes.get(i).getNodeId()) != null) {
                        rows.put(batchNodes.get(i).getNodeId(), row.dup());
                    }
                }
                if (!rows.isEmpty()) {
                    applied += knowledgeGraphService.applyNodeEmbeddings(rows);
                }
            } catch (RuntimeException ex) {
                log.warn("DOCUMENT embedding backfill failed for batch {}..{}: {}",
                        start, end, ex.getMessage());
            } finally {
                closeEmbeddings(rows.values());
                closeEmbedding(matrix);
            }
        }

        log.info("Backfilled {} of {} missing DOCUMENT node embeddings (factSheetId={})",
                applied, missing.size(), factSheetId);
        return applied;
    }

    @Override
    @Transactional
    public void computeEmbeddingSimilarityEdgesForNode(String nodeId, double minSimilarity) {
        if (embeddingModel == null) {
            log.warn("EmbeddingModel not available — cannot compute similarity edges");
            return;
        }

        // Use agnostic seam — nodeRepository is null on the live matrix/vector store path.
        Optional<GraphNode> nodeOpt = knowledgeGraphService.getNode(nodeId);
        if (nodeOpt.isEmpty()) return;

        GraphNode node = nodeOpt.get();
        String text = buildEmbeddingText(node);
        if (text == null || text.isBlank()) return;

        Long factSheetId = node.getFactSheetId();
        // Use agnostic seam for node enumeration — nodeRepository is null on the live path.
        List<GraphNode> docNodes = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, node.getNodeType())
                : knowledgeGraphService.getNodesByType(node.getNodeType());

        // Collect target node + all candidate nodes into parallel lists for a single embedBatch call.
        // Index 0 is always the target node; subsequent indices are the other nodes (excluding self).
        // Per-item embed() was an embedding-subprocess round-trip per node — O(n) IPC cost.
        // embedBatch sends the entire set in one call; the subprocess sub-batches internally.
        List<String> batchTexts = new ArrayList<>();
        List<String> batchNodeIds = new ArrayList<>();
        batchTexts.add(text);
        batchNodeIds.add(nodeId); // index 0 = target node

        for (GraphNode other : docNodes) {
            if (other.getNodeId().equals(nodeId)) continue;
            String otherText = buildEmbeddingText(other);
            if (otherText == null || otherText.isBlank()) continue;
            batchTexts.add(otherText);
            batchNodeIds.add(other.getNodeId());
        }

        if (batchTexts.size() < 2) return; // only the target node — nothing to compare

        List<float[]> vectors;
        try {
            vectors = embeddingModel.embedBatch(batchTexts);
        } catch (Exception e) {
            log.debug("Failed to embed nodes for {}: {}", nodeId, e.getMessage());
            return;
        }

        if (vectors == null || vectors.isEmpty()) return;

        // Index 0 = target node embedding
        float[] nodeEmbedding = (vectors.size() > 0)
                ? toUsableEmbeddingVector(vectors.get(0), nodeId) : null;
        if (nodeEmbedding == null) return;

        int n = Math.min(vectors.size(), batchNodeIds.size());
        for (int i = 1; i < n; i++) {
            float[] otherEmbedding = toUsableEmbeddingVector(vectors.get(i), batchNodeIds.get(i));
            if (otherEmbedding == null) continue;

            double similarity = cosineSimilarity(nodeEmbedding, otherEmbedding);
            if (!Double.isFinite(similarity)) continue;
            if (similarity >= minSimilarity) {
                // Agnostic edge-exists check — edgeRepository is null on the live path.
                if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(
                        nodeId, batchNodeIds.get(i)).isEmpty()) {
                    knowledgeGraphService.createEdge(nodeId, batchNodeIds.get(i),
                            EdgeType.EMBEDDING_SIMILARITY, similarity,
                            String.format("Cosine similarity: %.3f", similarity));
                }
            }
        }
    }

    @Override
    @Transactional
    public void updateSimilarityEdgesIncremental(List<String> nodeIds, double minSimilarity) {
        for (String nodeId : nodeIds) {
            if (cancelled.get()) break;
            computeEmbeddingSimilarityEdgesForNode(nodeId, minSimilarity);
        }
    }

    private float[] toUsableEmbeddingVector(INDArray embedding, String nodeId) {
        if (embedding == null || embedding.isEmpty()) {
            log.debug("Skipping similarity embedding for node {} because it is empty", nodeId);
            return null;
        }

        float[] vector;
        try {
            vector = embedding.toFloatVector();
        } catch (Exception e) {
            log.debug("Skipping similarity embedding for node {} because conversion failed: {}",
                    nodeId, e.getMessage());
            return null;
        }

        double sumSquares = 0.0;
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (!Float.isFinite(value)) {
                log.debug("Skipping similarity embedding for node {} due non-finite value at [{}]={}",
                        nodeId, i, value);
                return null;
            }
            sumSquares += (double) value * value;
        }
        if (!Double.isFinite(sumSquares) || sumSquares <= 1e-24) {
            log.debug("Skipping similarity embedding for node {} due unusable magnitude {}", nodeId, sumSquares);
            return null;
        }
        return vector;
    }

    /**
     * Validate a {@code float[]} embedding from the batch path — same empty/finite/magnitude checks
     * as the {@link INDArray} variant, without creating an ND4J array in this JVM.
     */
    private float[] toUsableEmbeddingVector(float[] vector, String nodeId) {
        if (vector == null || vector.length == 0) {
            log.debug("Skipping similarity embedding for node {} because it is empty", nodeId);
            return null;
        }
        double sumSquares = 0.0;
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (!Float.isFinite(value)) {
                log.debug("Skipping similarity embedding for node {} due non-finite value at [{}]={}",
                        nodeId, i, value);
                return null;
            }
            sumSquares += (double) value * value;
        }
        if (!Double.isFinite(sumSquares) || sumSquares <= 1e-24) {
            log.debug("Skipping similarity embedding for node {} due unusable magnitude {}", nodeId, sumSquares);
            return null;
        }
        return vector;
    }

    private void closeEmbeddings(Collection<INDArray> embeddings) {
        if (embeddings == null) {
            return;
        }
        for (INDArray embedding : embeddings) {
            closeEmbedding(embedding);
        }
    }

    private void closeEmbedding(INDArray embedding) {
        if (embedding == null || embedding.wasClosed()) {
            return;
        }
        try {
            embedding.close();
        } catch (RuntimeException ex) {
            log.debug("Unable to release temporary embedding: {}", ex.getMessage());
        }
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return Double.NaN;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            float lv = left[i];
            float rv = right[i];
            if (!Float.isFinite(lv) || !Float.isFinite(rv)) {
                return Double.NaN;
            }
            dot += (double) lv * rv;
            leftNorm += (double) lv * lv;
            rightNorm += (double) rv * rv;
        }
        if (leftNorm <= 1e-24 || rightNorm <= 1e-24
                || !Double.isFinite(leftNorm) || !Double.isFinite(rightNorm)) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private void closeQuietly(INDArray array) {
        if (array != null && !array.wasClosed()) {
            try {
                array.close();
            } catch (Exception e) {
                log.trace("Failed to close embedding array: {}", e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED ENTITY EDGES
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void computeSharedEntityEdges(int minSharedEntities) {
        computeSharedEntityEdges(null, minSharedEntities);
    }

    @Override
    @Transactional
    public void computeSharedEntityEdges(Long factSheetId, int minSharedEntities) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Edge computation already running");
            return;
        }
        cancelled.set(false);
        currentOperation = "shared_entity";
        int edgesCreated = 0;

        try {
            // Use the store-agnostic KnowledgeGraphService seam (works on both JPA and
            // the live matrix/vector store path where entityMentionRepository and nodeRepository
            // are null). The seam's implementations route to the correct backend.
            List<Object[]> pairs = factSheetId != null
                    ? knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet(
                            factSheetId, minSharedEntities)
                    : knowledgeGraphService.findNodePairsWithSharedEntities(minSharedEntities);
            log.info("Found {} node pairs sharing >= {} entities (factSheetId={})",
                    pairs.size(), minSharedEntities, factSheetId);

            for (Object[] pair : pairs) {
                if (cancelled.get()) break;

                // The shared-entity seam returns [nodeId1 (String), nodeId2 (String), sharedCount (Long)].
                // On the JPA path pair[0]/pair[1] are Long DB ids; on the agnostic path they are nodeId
                // strings.  We handle both by trying String first, then falling back to getNode(long).
                String nodeId1 = resolveNodeId(pair[0]);
                String nodeId2 = resolveNodeId(pair[1]);
                Long sharedCount = pair[2] instanceof Number ? ((Number) pair[2]).longValue() : 1L;

                if (nodeId1 != null && nodeId2 != null) {
                    // Agnostic bidirectional edge-exists check
                    if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(nodeId1, nodeId2).isEmpty()) {
                        double weight = Math.min(1.0, sharedCount / 10.0);
                        knowledgeGraphService.createEdge(
                                nodeId1, nodeId2,
                                EdgeType.SHARED_ENTITY, weight,
                                "Shares " + sharedCount + " entities");
                        edgesCreated++;
                    }
                }
            }

            log.info("Created {} shared entity edges", edgesCreated);
        } finally {
            lastEdgesCreated.set(edgesCreated);
            currentOperation = "idle";
            running.set(false);
        }
    }

    /**
     * Resolve a node identifier from a shared-entity pair result element.
     *
     * <p>The matrix/vector store implementation returns nodeId (UUID) strings directly.
     * Non-string values (e.g. legacy Long DB ids) are skipped since the JPA store is removed.</p>
     */
    private String resolveNodeId(Object pairElement) {
        if (pairElement == null) return null;
        if (pairElement instanceof String s) {
            return s.isBlank() ? null : s;
        }
        // Non-String: unexpected on the matrix/vector store path — skip.
        log.debug("resolveNodeId: unexpected non-String pair element {} — skipping", pairElement);
        return null;
    }

    @Override
    public void extractEntitiesForNode(String nodeId) {
        // Entity extraction is handled by DocumentGraphExtractor pipeline.
        // This is a no-op placeholder — entity mentions are created during ingest.
        log.debug("Entity extraction for node {} — handled during ingest pipeline", nodeId);
    }

    @Override
    public void extractEntitiesForAllNodes() {
        log.debug("Bulk entity extraction — handled during ingest pipeline");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void recomputeAllEdges() {
        // Delete existing computed edges and recompute
        deleteAllComputedEdges();
        computeSharedEntityEdges(2);
        if (!cancelled.get()) {
            computeEmbeddingSimilarityEdges(0.7, 10);
        }
    }

    @Override
    public int pruneWeakEdges(double minWeight, LocalDateTime olderThan) {
        // Prune weak EMBEDDING_SIMILARITY and SHARED_ENTITY computed edges from the
        // matrix/vector store by iterating edges via the KnowledgeGraphService seam.
        int pruned = 0;
        for (String nodeId : getEdgeSourceNodeIds()) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(nodeId);
            for (GraphEdge edge : edges) {
                EdgeType et = edge.getEdgeType();
                if (et != EdgeType.EMBEDDING_SIMILARITY && et != EdgeType.SHARED_ENTITY) continue;
                boolean tooWeak = edge.getWeight() != null && edge.getWeight() < minWeight;
                boolean tooOld = olderThan != null && edge.getComputedAt() != null
                        && edge.getComputedAt().isBefore(olderThan);
                if (tooWeak || tooOld) {
                    try {
                        knowledgeGraphService.deleteEdge(edge.getEdgeId());
                        pruned++;
                    } catch (Exception e) {
                        log.debug("pruneWeakEdges: could not delete edge {}: {}", edge.getEdgeId(), e.getMessage());
                    }
                }
            }
        }
        log.info("Pruned {} weak/stale edges", pruned);
        return pruned;
    }

    @Override
    public int deleteAllComputedEdges() {
        // Delete all EMBEDDING_SIMILARITY and SHARED_ENTITY edges via the seam.
        int deleted = 0;
        for (String nodeId : getEdgeSourceNodeIds()) {
            List<GraphEdge> edges = knowledgeGraphService.getEdgesForNode(nodeId);
            for (GraphEdge edge : edges) {
                EdgeType et = edge.getEdgeType();
                if (et == EdgeType.EMBEDDING_SIMILARITY || et == EdgeType.SHARED_ENTITY) {
                    try {
                        knowledgeGraphService.deleteEdge(edge.getEdgeId());
                        deleted++;
                    } catch (Exception e) {
                        log.debug("deleteAllComputedEdges: could not delete edge {}: {}", edge.getEdgeId(), e.getMessage());
                    }
                }
            }
        }
        log.info("Deleted {} computed edges", deleted);
        return deleted;
    }

    @Override
    @Transactional
    public Map<String, Object> rebuildCrossDocEdges(Long factSheetId, boolean dryRun) {
        // Collapse the legacy O(k²) clique cross-doc edges and recompute them as a STAR (the fixed
        // topology). Identify clique cross-doc edges by their description marker; leave entity-mention
        // SHARED_ENTITY ("Shares N entities") and EMBEDDING_SIMILARITY edges untouched.
        final String marker = "Name-based cross-doc resolution:";
        int existing = 0;
        List<String> toDelete = new ArrayList<>();
        for (String nodeId : getEdgeSourceNodeIds()) {
            for (GraphEdge edge : knowledgeGraphService.getEdgesForNode(nodeId)) {
                if (edge.getEdgeType() == EdgeType.SHARED_ENTITY
                        && edge.getDescription() != null
                        && edge.getDescription().startsWith(marker)) {
                    existing++;
                    if (!dryRun) toDelete.add(edge.getEdgeId());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factSheetId", factSheetId);
        result.put("existingCrossDocEdges", existing);
        result.put("dryRun", dryRun);
        if (dryRun) {
            result.put("action", "dryRun — would delete " + existing
                    + " clique cross-doc edges then recompute as star");
            log.info("rebuildCrossDocEdges DRY-RUN: {} clique cross-doc edges present (factSheetId={})",
                    existing, factSheetId);
            return result;
        }
        int deleted = 0;
        for (String edgeId : toDelete) {
            try {
                knowledgeGraphService.deleteEdge(edgeId);
                deleted++;
            } catch (Exception e) {
                log.debug("rebuildCrossDocEdges: could not delete edge {}: {}", edgeId, e.getMessage());
            }
        }
        // Recompute the cross-doc edges with the STAR topology (linear, not the clique).
        computeNameBasedCrossDocEdges(factSheetId);
        int after = 0;
        for (String nodeId : getEdgeSourceNodeIds()) {
            for (GraphEdge edge : knowledgeGraphService.getEdgesForNode(nodeId)) {
                if (edge.getEdgeType() == EdgeType.SHARED_ENTITY
                        && edge.getDescription() != null
                        && edge.getDescription().startsWith(marker)) {
                    after++;
                }
            }
        }
        result.put("deleted", deleted);
        result.put("crossDocEdgesAfter", after);
        result.put("netReduction", existing - after);
        log.info("rebuildCrossDocEdges: deleted {} clique cross-doc edges, recomputed to {} star edges, "
                        + "net -{} (factSheetId={})", deleted, after, existing - after, factSheetId);
        return result;
    }

    /** Returns nodeIds of all DOCUMENT and ENTITY nodes for edge scanning (used by prune/delete). */
    private List<String> getEdgeSourceNodeIds() {
        List<String> ids = new ArrayList<>();
        for (GraphNode n : knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)) {
            ids.add(n.getNodeId());
        }
        for (GraphNode n : knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)) {
            ids.add(n.getNodeId());
        }
        return ids;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getComputationStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("currentOperation", currentOperation);
        status.put("cancelled", cancelled.get());
        status.put("lastEdgesCreated", lastEdgesCreated.get());
        status.put("embeddingModelAvailable", embeddingModel != null);
        return status;
    }

    @Override
    public boolean isComputationRunning() {
        return running.get();
    }

    @Override
    public void cancelComputation() {
        cancelled.set(true);
        log.info("Edge computation cancellation requested");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING-FREE CROSS-DOCUMENT ENTITY RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Links ENTITY nodes across different source documents when they share the same
     * normalized name (lowercase + trim + collapse whitespace) AND the same
     * {@code entity_type} or {@code entity_subtype} metadata value.
     *
     * <p>This is intentionally embedding-free so it runs even when the embedding model
     * is offline, giving isolated per-document entity islands a cross-doc connection
     * that prevents the ComponentPruner from treating them as small singletons.</p>
     *
     * <p>Edge type is {@link EdgeType#SHARED_ENTITY} with weight=1.0 and a description
     * indicating name-based resolution; idempotent (skips existing edges).</p>
     */
    @Override
    @Transactional
    public void computeNameBasedCrossDocEdges(Long factSheetId) {
        // Use the store-agnostic seam — nodeRepository is null on the live matrix/vector path.
        List<GraphNode> entities = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)
                : knowledgeGraphService.getNodesByType(NodeLevel.ENTITY);

        if (entities == null || entities.size() < 2) {
            log.debug("computeNameBasedCrossDocEdges: fewer than 2 ENTITY nodes (factSheetId={}) — skipping",
                    factSheetId);
            return;
        }

        // Group by normalized-name + entity-type key → list of nodes with that name.
        // A node may contribute more than one key (e.g. a PERSON with both a display name
        // AND an email address) so we track which pairs we've already linked to stay idempotent.
        Map<String, List<GraphNode>> byNormalizedName = new LinkedHashMap<>();
        for (GraphNode n : entities) {
            // Primary key: normalized title (+ entity_type qualifier)
            String key = buildNameKey(n);
            if (key != null) {
                byNormalizedName.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
            }
            // Secondary key: exact email address (stored in metadataJson["email"]).
            // This bridges the gap where the same person appears as "Alice Smith" in one doc
            // and "alice@example.com" (address-only) in another: both resolve to the same
            // email address and therefore share the "email:<addr>" bucket.
            String emailAddr = metaValue(n, "email");
            if (emailAddr != null && !emailAddr.isBlank()) {
                String emailKey = "email:" + emailAddr.toLowerCase().trim();
                byNormalizedName.computeIfAbsent(emailKey, k -> new ArrayList<>()).add(n);
            }

            // ── Slack / Discord user-ID buckets ──────────────────────────
            // Both SlackGraphExtractor and DiscordGraphExtractor store the stable platform
            // user snowflake in the "userId" metadata field, but on different entity types:
            //   SLACK_USER / SLACK_BOT   → bucket "slack_user:<id>"
            //   DISCORD_USER / DISCORD_BOT → bucket "discord_user:<id>"
            // This resolves the same person across multiple partial exports of the same workspace/server.
            String platformUserId = metaValue(n, "userId");
            if (platformUserId != null && !platformUserId.isBlank()) {
                if (isEntityType(n, "SLACK_USER", "SLACK_BOT")) {
                    byNormalizedName.computeIfAbsent("slack_user:" + platformUserId.trim(),
                            k -> new ArrayList<>()).add(n);
                } else if (isEntityType(n, "DISCORD_USER", "DISCORD_BOT")) {
                    byNormalizedName.computeIfAbsent("discord_user:" + platformUserId.trim(),
                            k -> new ArrayList<>()).add(n);
                }
            }

            // ── Confluence accountId bucket ───────────────────────────────
            // ConfluenceGraphExtractor stores "accountId" (Atlassian account UUID) on PERSON nodes.
            // The same person can be author, editor, and comment author — all share the same accountId.
            String confluenceAccountId = metaValue(n, "accountId");
            if (confluenceAccountId != null && !confluenceAccountId.isBlank()) {
                byNormalizedName.computeIfAbsent("confluence_account:" + confluenceAccountId.trim(),
                        k -> new ArrayList<>()).add(n);
            }

            // ── ORGANIZATION domain bucket ────────────────────────────────
            // EmailGraphExtractor and GmailGraphExtractor write "domain" on ORGANIZATION nodes
            // (e.g. "corp.com" extracted from sender@corp.com).  Two ORGANIZATION nodes with the
            // same domain represent the same org whether they came from email or a web crawl.
            String orgDomain = metaValue(n, "domain");
            if (orgDomain != null && !orgDomain.isBlank()
                    && isEntityType(n, "ORGANIZATION")) {
                byNormalizedName.computeIfAbsent("org_domain:" + orgDomain.toLowerCase().trim(),
                        k -> new ArrayList<>()).add(n);
            }

            // ── ATTACHMENT filename bucket ────────────────────────────────
            // EmailGraphExtractor stores "filename" on ATTACHMENT nodes.  The same attachment
            // (e.g. "Q4_Report.xlsx") forwarded across multiple email threads will produce
            // separate nodes that should collapse.
            String attachFilename = metaValue(n, "filename");
            if (attachFilename != null && !attachFilename.isBlank()
                    && isEntityType(n, "ATTACHMENT")) {
                // Normalise: lowercase, strip extension suffix and common separators
                String normFilename = attachFilename.toLowerCase()
                        .replaceAll("\\.[a-z0-9]{1,5}$", "")   // strip extension
                        .replaceAll("[_\\-\\s]+", " ")
                        .trim();
                if (!normFilename.isEmpty()) {
                    byNormalizedName.computeIfAbsent("attachment:" + normFilename,
                            k -> new ArrayList<>()).add(n);
                }
            }

            // ── WEB_PAGE / WEBSITE canonical URL bucket ───────────────────
            // HtmlWebGraphExtractor stores "url" on WEB_PAGE and WEBSITE nodes.
            // GmailGraphExtractor and GoogleDocsGraphExtractor also store "url" on URL nodes.
            // Normalising to lowercase + stripping trailing slash deduplicates the same page
            // crawled multiple times or referenced from multiple docs.
            String nodeUrl = metaValue(n, "url");
            if (nodeUrl != null && !nodeUrl.isBlank()
                    && isEntityType(n, "WEB_PAGE", "WEBSITE", "HYPERLINK")) {
                String normUrl = nodeUrl.toLowerCase().trim().replaceAll("/$", "");
                byNormalizedName.computeIfAbsent("url:" + normUrl,
                        k -> new ArrayList<>()).add(n);
            }

            // ── SOCIAL_ACCOUNT platform+handle bucket ─────────────────────
            // HtmlWebGraphExtractor stores "platform" + "handle" (or the full profile URL as
            // the node title) on SOCIAL_ACCOUNT nodes.  Two pages that both link to the same
            // @acme Twitter account should be co-resolved.
            String socialHandle = metaValue(n, "handle");
            String socialPlatform = metaValue(n, "platform");
            if (socialHandle != null && !socialHandle.isBlank()
                    && socialPlatform != null && !socialPlatform.isBlank()
                    && isEntityType(n, "SOCIAL_ACCOUNT")) {
                byNormalizedName.computeIfAbsent(
                        "social:" + socialPlatform.toLowerCase() + ":" + socialHandle.toLowerCase().trim(),
                        k -> new ArrayList<>()).add(n);
            }

            // ── Confluence SPACE key bucket ───────────────────────────────
            // ConfluenceGraphExtractor stores "spaceKey" on CONFLUENCE_SPACE nodes.
            // Multiple Confluence exports from the same space should co-resolve.
            String spaceKey = metaValue(n, "spaceKey");
            if (spaceKey != null && !spaceKey.isBlank()
                    && isEntityType(n, "CONFLUENCE_SPACE")) {
                byNormalizedName.computeIfAbsent("confluence_space:" + spaceKey.trim(),
                        k -> new ArrayList<>()).add(n);
            }

            // ── Slack/Discord channel-ID bucket ──────────────────────────
            // Both SlackGraphExtractor and DiscordGraphExtractor store "channelId" on their
            // respective channel entity types.  Cross-export archives of the same channel
            // (e.g. two partial Slack export ZIPs for the same #general) should merge.
            String channelId = metaValue(n, "channelId");
            if (channelId != null && !channelId.isBlank()
                    && isEntityType(n, "SLACK_CHANNEL", "SLACK_THREAD",
                                       "DISCORD_CHANNEL", "DISCORD_THREAD")) {
                byNormalizedName.computeIfAbsent("channel:" + channelId.trim(),
                        k -> new ArrayList<>()).add(n);
            }
        }

        // Track already-linked pairs so dual-bucketing (name + email) never creates duplicates.
        Set<String> linkedPairs = new LinkedHashSet<>();

        // Topology is a hot-reloadable managed knob (KbConfig.crossDocStarTopology, default true).
        // STAR  → O(k) edges per bucket: connect every cross-doc member to a hub. Same connected
        //         component as a clique (which is the entire point — prevent the ComponentPruner
        //         treating cross-doc entity islands as singletons), but linear instead of quadratic.
        // CLIQUE → legacy O(k²): link every pair. Retained as a config fallback only; for generic
        //         structured values (a spreadsheet "Revenue"/"Total" bucket of hundreds of cells)
        //         this is what exploded SHARED_ENTITY to ~703k edges.
        boolean star = (kbConfigManager == null) || kbConfigManager.current().isCrossDocStarTopology();

        int edgesCreated = 0;
        for (Map.Entry<String, List<GraphNode>> entry : byNormalizedName.entrySet()) {
            if (cancelled.get()) return;
            List<GraphNode> group = entry.getValue();
            if (group.size() < 2) continue;
            String bucketKey = entry.getKey();

            if (star) {
                // True star topology with a dedicated synthetic hub node per (factSheet, bucket).
                // Hub type = NodeLevel.ALIAS, keyed deterministically by hubExternalId so the same
                // crawl data always resolves to the same hub on re-runs (fully idempotent).
                // Each member emits ONE EdgeType.ALIAS_OF edge (member → hub): O(N) edges per bucket
                // vs the old O(N²) clique. Members from the same source doc may both connect to the
                // same hub — they only share the hub, not a direct edge, so no false identity link
                // is created between them. Skip the bucket if all members are from a single source doc.

                boolean crossDoc = false;
                for (int k = 1; k < group.size(); k++) {
                    if (!sameSourceDoc(group.get(0), group.get(k))) {
                        crossDoc = true;
                        break;
                    }
                }
                if (!crossDoc) continue;

                if (group.size() > HUB_MAX_BUCKET_SIZE) {
                    log.warn("computeNameBasedCrossDocEdges: bucket '{}' has {} members — "
                            + "capping hub edges at {} to bound memory",
                            bucketKey, group.size(), HUB_MAX_BUCKET_SIZE);
                }

                // Hub externalId encodes factSheetId scope to prevent cross-factSheet nodeId clashes.
                String hubExternalId = (factSheetId != null ? "fs" + factSheetId + "." : "") + bucketKey;
                GraphNode hub = (factSheetId != null
                        ? knowledgeGraphService.getNodeByExternalIdInFactSheet(
                                hubExternalId, NodeLevel.ALIAS, factSheetId)
                        : knowledgeGraphService.getNodeByExternalId(hubExternalId, NodeLevel.ALIAS))
                        .orElseGet(() -> {
                            Map<String, Object> hubMeta = new LinkedHashMap<>();
                            hubMeta.put("bucket_key", bucketKey);
                            hubMeta.put("alias_type", "cross_doc_name");
                            return knowledgeGraphService.createNode(
                                    NodeLevel.ALIAS, hubExternalId,
                                    "Alias hub: " + bucketKey,
                                    "Cross-document canonical alias hub for bucket: " + bucketKey,
                                    hubMeta, factSheetId);
                        });
                String hubId = hub.getNodeId();

                // Collect ALIAS_OF EdgeSpecs for all members (cap at HUB_MAX_BUCKET_SIZE).
                // linkedPairs guards against the same member appearing in multiple alias buckets
                // that map to the same hub (e.g. a name bucket and an email bucket that happen
                // to share a hub key — rare but possible if keys collide after normalisation).
                List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();
                int processed = 0;
                for (GraphNode m : group) {
                    if (cancelled.get()) return;
                    if (processed++ >= HUB_MAX_BUCKET_SIZE) break;
                    String memberId = m.getNodeId();
                    if (memberId == null || memberId.equals(hubId)) continue;
                    String pairKey = memberId + "~" + hubId;
                    if (!linkedPairs.add(pairKey)) continue;
                    edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(memberId, hubId,
                            EdgeType.ALIAS_OF, 1.0,
                            "Cross-doc alias resolution: " + bucketKey));
                }

                // Batch-create edges in bounded chunks to keep per-call allocations small.
                for (int from = 0; from < edgeSpecs.size(); from += EDGE_BATCH_CHUNK) {
                    if (cancelled.get()) return;
                    int to = Math.min(from + EDGE_BATCH_CHUNK, edgeSpecs.size());
                    edgesCreated += knowledgeGraphService.createEdgesBatch(edgeSpecs.subList(from, to));
                }
            } else {
                // Legacy clique (config fallback): link every cross-doc pair.
                for (int i = 0; i < group.size() && !cancelled.get(); i++) {
                    GraphNode a = group.get(i);
                    for (int j = i + 1; j < group.size() && !cancelled.get(); j++) {
                        GraphNode b = group.get(j);
                        if (sameSourceDoc(a, b)) continue;
                        edgesCreated += tryCreateCrossDocEdge(a, b, bucketKey, linkedPairs);
                    }
                }
            }
        }
        log.info("computeNameBasedCrossDocEdges: created {} cross-doc name-resolution edges "
                        + "(factSheetId={}, topology={})",
                edgesCreated, factSheetId, star ? "star" : "clique");
    }

    /**
     * Create one idempotent cross-doc {@link EdgeType#SHARED_ENTITY} edge between two ENTITY nodes,
     * sharing the dedup + existence guards across the star and clique paths. Returns 1 if an edge
     * was created, 0 otherwise (duplicate pair, pre-existing edge, self-pair, or store error).
     *
     * @param bucketKey   the normalized-name bucket the pair came from (used in the edge description)
     * @param linkedPairs canonical {@code "<minId>~<maxId>"} keys already linked this run — guards
     *                    against the same pair appearing under both a name bucket and an alias bucket
     *                    when the store's bidirectional check isn't atomic across buckets in one tx
     */
    private int tryCreateCrossDocEdge(GraphNode a, GraphNode b, String bucketKey, Set<String> linkedPairs) {
        if (a == b) return 0;
        String idA = a.getNodeId();
        String idB = b.getNodeId();
        if (idA == null || idB == null || idA.equals(idB)) return 0;
        String pairKey = idA.compareTo(idB) <= 0 ? idA + "~" + idB : idB + "~" + idA;
        if (!linkedPairs.add(pairKey)) return 0; // already processed this pair this run
        // Idempotent: skip if edge already exists in either direction — agnostic seam.
        if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(idA, idB).isPresent()) {
            return 0;
        }
        try {
            knowledgeGraphService.createEdge(idA, idB,
                    EdgeType.SHARED_ENTITY, 1.0,
                    "Name-based cross-doc resolution: " + bucketKey);
            return 1;
        } catch (Exception e) {
            log.debug("computeNameBasedCrossDocEdges: could not create edge {} ↔ {}: {}",
                    idA, idB, e.getMessage());
            return 0;
        }
    }

    /**
     * Builds a stable grouping key from a node's normalized title and entity-type metadata.
     * Returns null when the node has no usable title (so unnamed nodes are excluded).
     *
     * <p>Special handling for PERSON nodes whose title is a bare email address
     * (e.g. {@code alice@example.com} stored when no display name was available):
     * the key uses only the local-part before {@code @} so that {@code alice@example.com}
     * and a second node titled {@code alice} (from a different doc where the display name
     * was extracted) land in the same bucket.  The email-address secondary bucket added by
     * {@code computeNameBasedCrossDocEdges} handles the definitive match; this keeps the
     * title bucket consistent so both passes agree.</p>
     */
    private static String buildNameKey(GraphNode n) {
        String title = n.getTitle();
        if (title == null || title.isBlank()) return null;
        String entityType = metaValue(n, "entity_type");
        if (entityType == null) entityType = metaValue(n, "entity_subtype");

        String normalized = title.toLowerCase().trim().replaceAll("\\s+", " ");

        // For PERSON nodes: if the title looks like a bare email address (contains '@' and no
        // spaces), reduce it to the local-part so "alice@corp.com" matches "alice" across docs.
        if ("person".equalsIgnoreCase(entityType)
                && normalized.contains("@")
                && !normalized.contains(" ")) {
            int atIdx = normalized.indexOf('@');
            // Replace dots/underscores/hyphens with spaces to turn "alice.smith" → "alice smith"
            normalized = normalized.substring(0, atIdx)
                    .replace('.', ' ').replace('_', ' ').replace('-', ' ')
                    .replaceAll("\\s+", " ").trim();
        }

        return entityType != null ? (normalized + "|" + entityType.toLowerCase()) : normalized;
    }

    /** Quick metadata value extractor without a full JSON parse. */
    private static String metaValue(GraphNode n, String key) {
        String meta = n.getMetadataJson();
        if (meta == null) return null;
        String needle = "\"" + key + "\"";
        int idx = meta.indexOf(needle);
        if (idx < 0) return null;
        int colon = meta.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        // Skip whitespace and opening quote
        int start = colon + 1;
        while (start < meta.length() && (meta.charAt(start) == ' ' || meta.charAt(start) == '\t')) start++;
        if (start >= meta.length() || meta.charAt(start) != '"') return null;
        start++; // skip opening quote
        int end = meta.indexOf('"', start);
        return end > start ? meta.substring(start, end) : null;
    }

    /**
     * Returns true if this node's {@code entity_type} metadata field matches any of the
     * given candidate type strings (case-insensitive).  Used by alias buckets that are
     * only meaningful for a specific entity type (e.g. "userId" appears on both Slack and
     * Discord nodes but the bucket prefix differs, so we guard by type).
     */
    private static boolean isEntityType(GraphNode n, String... candidates) {
        String entityType = metaValue(n, "entity_type");
        if (entityType == null) return false;
        for (String c : candidates) {
            if (c.equalsIgnoreCase(entityType)) return true;
        }
        return false;
    }

    /** Returns true if two nodes come from the same source document (same sourcePath metadata). */
    private static boolean sameSourceDoc(GraphNode a, GraphNode b) {
        String spA = metaValue(a, "source_path");
        String spB = metaValue(b, "source_path");
        if (spA != null && spB != null) {
            return spA.equals(spB);
        }
        // Fall back to externalId prefix comparison: "doc1/entity:X" vs "doc2/entity:Y"
        String extA = a.getExternalId();
        String extB = b.getExternalId();
        if (extA != null && extB != null) {
            // Consider same document if the first path segment before '/' matches
            int slashA = extA.indexOf('/');
            int slashB = extB.indexOf('/');
            if (slashA > 0 && slashB > 0) {
                return extA.substring(0, slashA).equals(extB.substring(0, slashB));
            }
        }
        return false; // assume different docs when uncertain
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════════════════

    private String buildEmbeddingText(GraphNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.getTitle() != null) sb.append(node.getTitle());
        if (node.getDescription() != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(node.getDescription());
        }
        if (node.getContentPreview() != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(node.getContentPreview());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
