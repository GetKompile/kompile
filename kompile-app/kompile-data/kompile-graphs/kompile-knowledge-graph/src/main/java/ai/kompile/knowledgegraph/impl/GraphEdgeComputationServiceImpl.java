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
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
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

    private GraphNodeRepository nodeRepository;
    private GraphEdgeRepository edgeRepository;
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private EntityMentionRepository entityMentionRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger lastEdgesCreated = new AtomicInteger(0);
    private volatile String currentOperation = "idle";

    public GraphEdgeComputationServiceImpl(GraphNodeRepository nodeRepository,
                                            GraphEdgeRepository edgeRepository,
                                            KnowledgeGraphService knowledgeGraphService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected GraphEdgeComputationServiceImpl() {}


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
            // Get all DOCUMENT nodes — similarity edges connect documents
            List<GraphNode> docNodes = factSheetId != null
                    ? nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.DOCUMENT)
                    : nodeRepository.findByNodeType(NodeLevel.DOCUMENT);
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
                        // Check if edge already exists (bidirectional)
                        if (edgeRepository.findEdgeBetweenNodesBidirectional(id1, id2).isEmpty()) {
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

        Optional<GraphNode> nodeOpt = nodeRepository.findByNodeId(nodeId);
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
        List<GraphNode> docNodes = factSheetId != null
                ? nodeRepository.findByFactSheetIdAndNodeType(factSheetId, node.getNodeType())
                : nodeRepository.findByNodeType(node.getNodeType());
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
                    if (edgeRepository.findEdgeBetweenNodesBidirectional(nodeId, other.getNodeId()).isEmpty()) {
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
        if (entityMentionRepository == null) {
            log.warn("EntityMentionRepository not available — cannot compute shared entity edges");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Edge computation already running");
            return;
        }
        cancelled.set(false);
        currentOperation = "shared_entity";
        int edgesCreated = 0;

        try {
            List<Object[]> pairs = factSheetId != null
                    ? entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(factSheetId, minSharedEntities)
                    : entityMentionRepository.findNodePairsWithSharedEntities(minSharedEntities);
            log.info("Found {} node pairs sharing >= {} entities (factSheetId={})",
                    pairs.size(), minSharedEntities, factSheetId);

            for (Object[] pair : pairs) {
                if (cancelled.get()) break;

                Long node1DbId = (Long) pair[0];
                Long node2DbId = (Long) pair[1];
                Long sharedCount = (Long) pair[2];

                GraphNode node1 = nodeRepository.findById(node1DbId).orElse(null);
                GraphNode node2 = nodeRepository.findById(node2DbId).orElse(null);

                if (node1 != null && node2 != null) {
                    if (edgeRepository.findEdgeBetweenNodesBidirectional(
                            node1.getNodeId(), node2.getNodeId()).isEmpty()) {
                        double weight = Math.min(1.0, sharedCount / 10.0);
                        knowledgeGraphService.createEdge(
                                node1.getNodeId(), node2.getNodeId(),
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
    @Transactional
    public int pruneWeakEdges(double minWeight, LocalDateTime olderThan) {
        int pruned = 0;
        List<GraphEdge> similarityEdges = edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY);
        for (GraphEdge edge : similarityEdges) {
            if (edge.getWeight() != null && edge.getWeight() < minWeight) {
                edgeRepository.delete(edge);
                pruned++;
            } else if (olderThan != null && edge.getComputedAt() != null
                    && edge.getComputedAt().isBefore(olderThan)) {
                edgeRepository.delete(edge);
                pruned++;
            }
        }
        List<GraphEdge> sharedEdges = edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY);
        for (GraphEdge edge : sharedEdges) {
            if (edge.getWeight() != null && edge.getWeight() < minWeight) {
                edgeRepository.delete(edge);
                pruned++;
            }
        }
        log.info("Pruned {} weak/stale edges", pruned);
        return pruned;
    }

    @Override
    @Transactional
    public int deleteAllComputedEdges() {
        int deleted = 0;
        List<GraphEdge> similarityEdges = edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY);
        edgeRepository.deleteAll(similarityEdges);
        deleted += similarityEdges.size();

        List<GraphEdge> sharedEdges = edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY);
        edgeRepository.deleteAll(sharedEdges);
        deleted += sharedEdges.size();

        log.info("Deleted {} computed edges", deleted);
        return deleted;
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
        status.put("entityMentionRepoAvailable", entityMentionRepository != null);
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
