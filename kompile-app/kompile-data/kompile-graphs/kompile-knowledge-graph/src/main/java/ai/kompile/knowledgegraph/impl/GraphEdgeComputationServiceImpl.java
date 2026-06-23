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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger lastEdgesCreated = new AtomicInteger(0);
    private volatile String currentOperation = "idle";

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
        if (embeddingModel == null) {
            log.warn("EmbeddingModel not available — cannot compute similarity edges");
            return;
        }
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

            // Compute embeddings for all nodes
            Map<String, float[]> embeddings = new LinkedHashMap<>();
            for (GraphNode node : docNodes) {
                if (cancelled.get()) break;
                String text = buildEmbeddingText(node);
                if (text == null || text.isBlank()) continue;
                INDArray embedding = null;
                try {
                    embedding = embeddingModel.embed(text);
                    float[] vector = toUsableEmbeddingVector(embedding, node.getNodeId());
                    if (vector != null) {
                        embeddings.put(node.getNodeId(), vector);
                    }
                } catch (Exception e) {
                    log.debug("Failed to embed node {}: {}", node.getNodeId(), e.getMessage());
                } finally {
                    closeQuietly(embedding);
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

        float[] nodeEmbedding;
        INDArray nodeEmbeddingArray = null;
        try {
            nodeEmbeddingArray = embeddingModel.embed(text);
            nodeEmbedding = toUsableEmbeddingVector(nodeEmbeddingArray, nodeId);
        } catch (Exception e) {
            log.debug("Failed to embed node {}: {}", nodeId, e.getMessage());
            return;
        } finally {
            closeQuietly(nodeEmbeddingArray);
        }
        if (nodeEmbedding == null) return;

        Long factSheetId = node.getFactSheetId();
        // Use agnostic seam for node enumeration — nodeRepository is null on the live path.
        List<GraphNode> docNodes = factSheetId != null
                ? knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, node.getNodeType())
                : knowledgeGraphService.getNodesByType(node.getNodeType());
        for (GraphNode other : docNodes) {
            if (other.getNodeId().equals(nodeId)) continue;
            String otherText = buildEmbeddingText(other);
            if (otherText == null || otherText.isBlank()) continue;

            INDArray otherEmbeddingArray = null;
            try {
                otherEmbeddingArray = embeddingModel.embed(otherText);
                float[] otherEmbedding = toUsableEmbeddingVector(otherEmbeddingArray, other.getNodeId());
                if (otherEmbedding == null) continue;

                double similarity = cosineSimilarity(nodeEmbedding, otherEmbedding);
                if (!Double.isFinite(similarity)) continue;
                if (similarity >= minSimilarity) {
                    // Agnostic edge-exists check — edgeRepository is null on the live path.
                    if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(
                            nodeId, other.getNodeId()).isEmpty()) {
                        knowledgeGraphService.createEdge(nodeId, other.getNodeId(),
                                EdgeType.EMBEDDING_SIMILARITY, similarity,
                                String.format("Cosine similarity: %.3f", similarity));
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to compare node {} with {}: {}", nodeId, other.getNodeId(), e.getMessage());
            } finally {
                closeQuietly(otherEmbeddingArray);
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

    /** Returns nodeIds of all DOCUMENT and ENTITY nodes for edge scanning (used by prune/delete). */
    private List<String> getEdgeSourceNodeIds() {
        List<String> ids = new java.util.ArrayList<>();
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
        }

        // Track already-linked pairs so dual-bucketing (name + email) never creates duplicates.
        Set<String> linkedPairs = new LinkedHashSet<>();

        int edgesCreated = 0;
        for (Map.Entry<String, List<GraphNode>> entry : byNormalizedName.entrySet()) {
            List<GraphNode> group = entry.getValue();
            if (group.size() < 2) continue;

            // Only link nodes from DIFFERENT source documents (different externalId prefix)
            // to avoid noisy self-loops within the same document.
            for (int i = 0; i < group.size(); i++) {
                if (cancelled.get()) return;
                GraphNode a = group.get(i);
                for (int j = i + 1; j < group.size(); j++) {
                    GraphNode b = group.get(j);
                    // Skip if same source document (same sourcePath metadata)
                    if (sameSourceDoc(a, b)) continue;
                    // Canonical pair key (smaller nodeId first) — guards against dual-bucketing
                    // (the same pair appearing under both a "name" bucket and an "email" bucket)
                    // creating a duplicate edge when the graph store's bidirectional check isn't
                    // atomic enough across buckets processed in the same transaction.
                    String pairKey = a.getNodeId().compareTo(b.getNodeId()) <= 0
                            ? a.getNodeId() + "~" + b.getNodeId()
                            : b.getNodeId() + "~" + a.getNodeId();
                    if (!linkedPairs.add(pairKey)) continue; // already processed this pair
                    // Idempotent: skip if edge already exists in either direction — agnostic seam.
                    if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(
                            a.getNodeId(), b.getNodeId()).isPresent()) {
                        continue;
                    }
                    try {
                        knowledgeGraphService.createEdge(
                                a.getNodeId(), b.getNodeId(),
                                EdgeType.SHARED_ENTITY, 1.0,
                                "Name-based cross-doc resolution: " + entry.getKey());
                        edgesCreated++;
                    } catch (Exception e) {
                        log.debug("computeNameBasedCrossDocEdges: could not create edge {} ↔ {}: {}",
                                a.getNodeId(), b.getNodeId(), e.getMessage());
                    }
                }
            }
        }
        log.info("computeNameBasedCrossDocEdges: created {} cross-doc name-resolution edges (factSheetId={})",
                edgesCreated, factSheetId);
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
