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

package ai.kompile.knowledgegraph.embedding.service;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service for storing and retrieving KG embeddings (TransE/RotatE) via the
 * {@link KnowledgeGraphService} seam.
 *
 * <p>Embeddings are persisted in node/edge-type metadata inside the @Primary
 * matrix/vector store.  The JPA repositories ({@code GraphNodeRepository},
 * {@code GraphEdgeRepository}) are no longer touched; graph tables are empty on
 * the live path.</p>
 */
@Service
public class KGEmbeddingStorageService {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingStorageService.class);

    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public KGEmbeddingStorageService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected KGEmbeddingStorageService() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // STORING EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stores trained embeddings from a model to the live matrix/vector store.
     *
     * @param model            The trained embedding model
     * @param factSheetId      The fact sheet ID
     * @param embeddingVersion Version/timestamp for this training run
     * @return Number of entities + relation types updated
     */
    public int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long embeddingVersion) {
        log.info("Storing embeddings for fact sheet {} (version {})", factSheetId, embeddingVersion);

        Map<String, INDArray> entityEmbeddings = model.getAllEntityEmbeddings();
        Map<String, INDArray> relationEmbeddings = model.getAllRelationEmbeddings();
        KGEmbeddingAlgorithm algorithm = model.getAlgorithm();
        Instant now = Instant.now();

        int entitiesUpdated = 0;
        int relationsUpdated = 0;

        // Map entity name → node; the seam's getNodesByTypeInFactSheet is the right query.
        List<GraphNode> nodes = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        Map<String, GraphNode> nodeByTitle = new HashMap<>();
        for (GraphNode node : nodes) {
            if (node.getTitle() != null) {
                nodeByTitle.put(node.getTitle(), node);
            }
        }

        for (Map.Entry<String, INDArray> entry : entityEmbeddings.entrySet()) {
            GraphNode node = nodeByTitle.get(entry.getKey());
            if (node == null) continue;
            knowledgeGraphService.storeNodeKgEmbedding(
                    node.getNodeId(), entry.getValue(), algorithm, embeddingVersion, now);
            entitiesUpdated++;
        }

        // Relation embeddings are stored per EdgeType name (type-shared, one per relation).
        for (Map.Entry<String, INDArray> entry : relationEmbeddings.entrySet()) {
            knowledgeGraphService.storeEdgeTypeKgEmbedding(
                    entry.getKey(), entry.getValue(), algorithm, embeddingVersion, factSheetId);
            relationsUpdated++;
        }

        log.info("Stored {} entity embeddings and {} relation embeddings for fact sheet {}",
                entitiesUpdated, relationsUpdated, factSheetId);

        return entitiesUpdated + relationsUpdated;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOADING EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads embeddings from the live store into a model.
     *
     * @param model       The model to load embeddings into
     * @param factSheetId The fact sheet ID
     * @return true if embeddings were loaded successfully
     */
    public boolean loadEmbeddings(KGEmbeddingModel model, Long factSheetId) {
        log.info("Loading embeddings for fact sheet {}", factSheetId);

        List<GraphNode> nodesWithEmbeddings = knowledgeGraphService.findNodesWithKgEmbedding(factSheetId);

        if (nodesWithEmbeddings.isEmpty()) {
            log.info("No embeddings found for fact sheet {}", factSheetId);
            return false;
        }

        Map<String, INDArray> entityEmbeddings = new HashMap<>();
        for (GraphNode node : nodesWithEmbeddings) {
            INDArray emb = node.getKgEmbedding();
            if (emb != null && node.getTitle() != null) {
                entityEmbeddings.put(node.getTitle(), emb);
            }
        }

        Map<String, INDArray> relationEmbeddings = knowledgeGraphService.getEdgeTypeKgEmbeddings(factSheetId);

        model.importEntityEmbeddings(entityEmbeddings);
        model.importRelationEmbeddings(relationEmbeddings);

        log.info("Loaded {} entity embeddings and {} relation embeddings for fact sheet {}",
                entityEmbeddings.size(), relationEmbeddings.size(), factSheetId);

        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXTRACTING TRIPLES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts triples from the knowledge graph for training.
     *
     * @param factSheetId The fact sheet ID
     * @return List of triples
     */
    public List<Triple> extractTriples(Long factSheetId) {
        log.info("Extracting triples for fact sheet {}", factSheetId);

        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        List<Triple> triples = new ArrayList<>(edges.size());

        for (GraphEdge edge : edges) {
            String sourceNodeId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
            String targetNodeId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
            String sourceTitle = resolveNodeTitle(sourceNodeId);
            String targetTitle = resolveNodeTitle(targetNodeId);
            if (sourceTitle != null && targetTitle != null) {
                triples.add(new Triple(
                        sourceTitle,
                        edge.getEdgeType() != null ? edge.getEdgeType().name() : "RELATED_TO",
                        targetTitle
                ));
            }
        }

        log.info("Extracted {} triples for fact sheet {}", triples.size(), factSheetId);
        return triples;
    }

    private String resolveNodeTitle(String nodeId) {
        if (nodeId == null) return null;
        return knowledgeGraphService.getNode(nodeId)
                .map(GraphNode::getTitle)
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALGORITHM DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets the algorithm used for stored embeddings.
     *
     * @param factSheetId The fact sheet ID
     * @return The algorithm used, or null if no embeddings exist
     */
    public KGEmbeddingAlgorithm getStoredAlgorithm(Long factSheetId) {
        return knowledgeGraphService.getStoredKgAlgorithm(factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets statistics about embeddings for a fact sheet.
     */
    public EmbeddingStats getStats(Long factSheetId) {
        long totalNodes = knowledgeGraphService.countActiveNodes(factSheetId);
        List<GraphNode> withEmb = knowledgeGraphService.findNodesWithKgEmbedding(factSheetId);
        long nodesWithEmbeddings = withEmb.size();

        Map<String, INDArray> edgeEmbs = knowledgeGraphService.getEdgeTypeKgEmbeddings(factSheetId);
        long edgesWithEmbeddings = edgeEmbs.size();
        // Total edges approximated; the seam only exposes a count without an accurate by-fact-sheet query
        long totalEdges = edgesWithEmbeddings; // lower-bound; stat table shows "N / N" when all types have embeddings

        Long latestVersion = withEmb.stream()
                .map(GraphNode::getKgEmbeddingVersion)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return new EmbeddingStats(
                totalNodes,
                nodesWithEmbeddings,
                totalEdges,
                edgesWithEmbeddings,
                latestVersion
        );
    }

    /**
     * Clears all embeddings for a fact sheet.
     */
    public void clearEmbeddings(Long factSheetId) {
        log.info("Clearing embeddings for fact sheet {}", factSheetId);
        knowledgeGraphService.clearKgEmbeddings(factSheetId);
        log.info("Cleared KGE embeddings for fact sheet {}", factSheetId);
    }

    /**
     * Statistics about embeddings for a fact sheet.
     */
    public record EmbeddingStats(
            long totalNodes,
            long nodesWithEmbeddings,
            long totalEdges,
            long edgesWithEmbeddings,
            Long latestVersion
    ) {
        public boolean hasEmbeddings() {
            return nodesWithEmbeddings > 0 || edgesWithEmbeddings > 0;
        }

        public double nodeEmbeddingCoverage() {
            return totalNodes > 0 ? (double) nodesWithEmbeddings / totalNodes * 100 : 0;
        }

        public double edgeEmbeddingCoverage() {
            return totalEdges > 0 ? (double) edgesWithEmbeddings / totalEdges * 100 : 0;
        }
    }
}
