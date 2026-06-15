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
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for storing and retrieving KG embeddings from the database.
 * Works with both JPA (H2/PostgreSQL) storage.
 */
@Service
public class KGEmbeddingStorageService {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingStorageService.class);

    private GraphNodeRepository nodeRepository;
    private GraphEdgeRepository edgeRepository;

    @Autowired
    public KGEmbeddingStorageService(
            GraphNodeRepository nodeRepository,
            GraphEdgeRepository edgeRepository
    ) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected KGEmbeddingStorageService() {}


    // ═══════════════════════════════════════════════════════════════════════════
    // STORING EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stores trained embeddings from a model to database entities.
     *
     * @param model The trained embedding model
     * @param factSheetId The fact sheet ID
     * @param embeddingVersion Version/timestamp for this training run
     * @return Number of entities updated
     */
    @Transactional
    public int storeEmbeddings(KGEmbeddingModel model, Long factSheetId, Long embeddingVersion) {
        log.info("Storing embeddings for fact sheet {} (version {})", factSheetId, embeddingVersion);

        int entitiesUpdated = 0;
        int relationsUpdated = 0;

        // Get all entity embeddings from the model
        Map<String, INDArray> entityEmbeddings = model.getAllEntityEmbeddings();
        Map<String, INDArray> relationEmbeddings = model.getAllRelationEmbeddings();

        // Get all nodes for this fact sheet
        List<GraphNode> nodes = nodeRepository.findByFactSheetId(factSheetId);
        Map<String, GraphNode> nodeByTitle = new HashMap<>();
        for (GraphNode node : nodes) {
            nodeByTitle.put(node.getTitle(), node);
        }

        // Store entity embeddings to nodes
        Instant now = Instant.now();
        for (Map.Entry<String, INDArray> entry : entityEmbeddings.entrySet()) {
            GraphNode node = nodeByTitle.get(entry.getKey());
            if (node != null) {
                node.setKgEmbedding(entry.getValue());
                node.setKgEmbeddingAlgorithm(model.getAlgorithm());
                node.setKgEmbeddingVersion(embeddingVersion);
                node.setKgEmbeddingUpdatedAt(now);
                entitiesUpdated++;
            }
        }
        nodeRepository.saveAll(nodes);

        // Store relation embeddings to edges
        // Group edges by relation type and update all edges of each type
        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
        for (GraphEdge edge : edges) {
            String relationType = edge.getEdgeType().name();
            INDArray relEmb = relationEmbeddings.get(relationType);
            if (relEmb != null) {
                edge.setKgRelationEmbedding(relEmb);
                edge.setKgEmbeddingAlgorithm(model.getAlgorithm());
                edge.setKgEmbeddingVersion(embeddingVersion);
                relationsUpdated++;
            }
        }
        edgeRepository.saveAll(edges);

        log.info("Stored {} entity embeddings and {} relation embeddings for fact sheet {}",
                entitiesUpdated, relationsUpdated, factSheetId);

        return entitiesUpdated + relationsUpdated;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOADING EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads embeddings from database into a model.
     *
     * @param model The model to load embeddings into
     * @param factSheetId The fact sheet ID
     * @return true if embeddings were loaded successfully
     */
    @Transactional(readOnly = true)
    public boolean loadEmbeddings(KGEmbeddingModel model, Long factSheetId) {
        log.info("Loading embeddings for fact sheet {}", factSheetId);

        // Load entity embeddings from nodes
        List<GraphNode> nodesWithEmbeddings = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);

        if (nodesWithEmbeddings.isEmpty()) {
            log.info("No embeddings found for fact sheet {}", factSheetId);
            return false;
        }

        Map<String, INDArray> entityEmbeddings = new HashMap<>();
        for (GraphNode node : nodesWithEmbeddings) {
            if (node.getKgEmbedding() != null) {
                entityEmbeddings.put(node.getTitle(), node.getKgEmbedding());
            }
        }

        // Load relation embeddings from edges
        List<GraphEdge> edgesWithEmbeddings = edgeRepository.findByFactSheetIdAndKgRelationEmbeddingNotNull(factSheetId);

        Map<String, INDArray> relationEmbeddings = new HashMap<>();
        for (GraphEdge edge : edgesWithEmbeddings) {
            String relationType = edge.getEdgeType().name();
            if (!relationEmbeddings.containsKey(relationType) && edge.getKgRelationEmbedding() != null) {
                relationEmbeddings.put(relationType, edge.getKgRelationEmbedding());
            }
        }

        // Import into model
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
    @Transactional(readOnly = true)
    public List<Triple> extractTriples(Long factSheetId) {
        log.info("Extracting triples for fact sheet {}", factSheetId);

        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
        List<Triple> triples = new ArrayList<>(edges.size());

        for (GraphEdge edge : edges) {
            GraphNode source = edge.getSourceNode();
            GraphNode target = edge.getTargetNode();

            if (source != null && target != null) {
                triples.add(new Triple(
                        source.getTitle(),
                        edge.getEdgeType().name(),
                        target.getTitle()
                ));
            }
        }

        log.info("Extracted {} triples for fact sheet {}", triples.size(), factSheetId);
        return triples;
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
    @Transactional(readOnly = true)
    public KGEmbeddingAlgorithm getStoredAlgorithm(Long factSheetId) {
        List<GraphNode> nodesWithEmb = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);
        if (!nodesWithEmb.isEmpty()) {
            return nodesWithEmb.get(0).getKgEmbeddingAlgorithm();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets statistics about embeddings for a fact sheet.
     */
    @Transactional(readOnly = true)
    public EmbeddingStats getStats(Long factSheetId) {
        long totalNodes = nodeRepository.findByFactSheetId(factSheetId).size();
        long nodesWithEmbeddings = nodeRepository.countByFactSheetIdAndKgEmbeddingNotNull(factSheetId);
        long totalEdges = edgeRepository.findByFactSheetId(factSheetId).size();
        long edgesWithEmbeddings = edgeRepository.countByFactSheetIdAndKgRelationEmbeddingNotNull(factSheetId);

        // Get latest version
        Long latestVersion = null;
        List<GraphNode> nodesWithEmb = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);
        if (!nodesWithEmb.isEmpty()) {
            latestVersion = nodesWithEmb.get(0).getKgEmbeddingVersion();
        }

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
    @Transactional
    public void clearEmbeddings(Long factSheetId) {
        log.info("Clearing embeddings for fact sheet {}", factSheetId);

        List<GraphNode> nodes = nodeRepository.findByFactSheetId(factSheetId);
        for (GraphNode node : nodes) {
            node.setKgEmbedding(null);
            node.setKgEmbeddingAlgorithm(null);
            node.setKgEmbeddingVersion(null);
            node.setKgEmbeddingUpdatedAt(null);
        }
        nodeRepository.saveAll(nodes);

        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
        for (GraphEdge edge : edges) {
            edge.setKgRelationEmbedding(null);
            edge.setKgEmbeddingAlgorithm(null);
            edge.setKgEmbeddingVersion(null);
        }
        edgeRepository.saveAll(edges);

        log.info("Cleared embeddings for {} nodes and {} edges in fact sheet {}",
                nodes.size(), edges.size(), factSheetId);
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
