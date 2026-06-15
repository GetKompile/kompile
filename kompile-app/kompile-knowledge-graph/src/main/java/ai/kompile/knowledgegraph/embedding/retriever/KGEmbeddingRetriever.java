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

package ai.kompile.knowledgegraph.embedding.retriever;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.kgembedding.EmbeddingScore;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.GraphRAGConfig;
import ai.kompile.knowledgegraph.embedding.impl.RotatEModel;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GraphRAG retriever that combines knowledge graph embeddings with text retrieval.
 *
 * <p>This retriever enhances traditional document retrieval by:
 * <ol>
 *   <li>Finding entities in the query using text embedding similarity</li>
 *   <li>Using KG embeddings to find related entities via link prediction</li>
 *   <li>Expanding the context using the knowledge graph structure</li>
 *   <li>Returning enriched documents with graph-based context</li>
 * </ol>
 *
 * <p>The retriever can operate in two modes:
 * <ul>
 *   <li><b>Entity-centric</b>: Find entities matching the query and expand via graph</li>
 *   <li><b>Hybrid</b>: Combine entity retrieval with traditional text retrieval</li>
 * </ul>
 */
@Component
public class KGEmbeddingRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingRetriever.class);

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final KGEmbeddingStorageService storageService;
    private final KGEmbeddingConfigService configService;
    private final EmbeddingModel textEmbeddingModel;

    // Optional base retriever for hybrid mode
    private final DocumentRetriever baseRetriever;

    // Cache loaded KG models per fact sheet
    private final Map<Long, KGEmbeddingModel> loadedModels = new ConcurrentHashMap<>();

    @Autowired
    public KGEmbeddingRetriever(
            GraphNodeRepository nodeRepository,
            GraphEdgeRepository edgeRepository,
            KGEmbeddingStorageService storageService,
            KGEmbeddingConfigService configService,
            @Autowired(required = false) EmbeddingModel textEmbeddingModel,
            @Autowired(required = false) @org.springframework.beans.factory.annotation.Qualifier("baseDocumentRetriever") DocumentRetriever baseRetriever
    ) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.storageService = storageService;
        this.configService = configService;
        this.textEmbeddingModel = textEmbeddingModel;
        this.baseRetriever = baseRetriever;

        log.info("KGEmbeddingRetriever initialized (config-driven)");
    }

    /**
     * Checks if GraphRAG is enabled via configuration.
     */
    public boolean isEnabled() {
        return configService.getGraphRAGConfig().enabled();
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        List<RetrievedDoc> docs = retrieveWithDetails(query, maxResults);
        return docs.stream()
                .map(RetrievedDoc::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Get current config values
        GraphRAGConfig config = configService.getGraphRAGConfig();

        // Check if GraphRAG is enabled
        if (!config.enabled()) {
            log.debug("GraphRAG is disabled, falling back to base retriever");
            return fallbackToBaseRetriever(query, maxResults);
        }

        log.debug("GraphRAG retrieving for query: '{}', maxResults: {}", query, maxResults);

        try {
            // Step 1: Find relevant entities from the knowledge graph
            List<ScoredEntity> kgEntities = findRelevantEntities(query, config.defaultFactSheetId(), config.topKEntities());

            if (kgEntities.isEmpty()) {
                log.debug("No KG entities found for query, falling back to base retriever");
                return fallbackToBaseRetriever(query, maxResults);
            }

            // Step 2: Expand entity set using KG link prediction
            Set<String> expandedEntities = expandEntitiesViaGraph(
                    kgEntities.stream().map(e -> e.entityName).collect(Collectors.toSet()),
                    config.defaultFactSheetId(),
                    config.expansionHops()
            );

            log.debug("Expanded from {} entities to {} entities", kgEntities.size(), expandedEntities.size());

            // Step 3: Build context from the expanded entity set
            List<RetrievedDoc> kgDocs = buildContextFromEntities(expandedEntities, kgEntities, config.defaultFactSheetId());

            // Step 4: If hybrid mode enabled, combine with text retrieval
            if (baseRetriever != null && config.textWeight() > 0) {
                return combineWithTextRetrieval(query, kgDocs, maxResults, config);
            }

            // Return KG-only results
            return kgDocs.stream()
                    .sorted(Comparator.comparing(RetrievedDoc::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(maxResults)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error in GraphRAG retrieval: {}", e.getMessage(), e);
            return fallbackToBaseRetriever(query, maxResults);
        }
    }

    /**
     * Finds entities relevant to the query using text embedding similarity.
     */
    private List<ScoredEntity> findRelevantEntities(String query, Long factSheetId, int topK) {
        if (textEmbeddingModel == null) {
            log.warn("No text embedding model available, using keyword matching");
            return findEntitiesByKeywordMatch(query, factSheetId, topK);
        }

        // Get query embedding
        INDArray queryEmbedding = textEmbeddingModel.embed(query);
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("Failed to embed query, falling back to keyword match");
            return findEntitiesByKeywordMatch(query, factSheetId, topK);
        }

        // Get all entities with KG embeddings
        List<GraphNode> nodesWithEmbeddings = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);

        if (nodesWithEmbeddings.isEmpty()) {
            log.debug("No nodes with KG embeddings found");
            return Collections.emptyList();
        }

        // Score entities by combining:
        // 1. Text similarity (query text vs entity name/description)
        // 2. KG embedding relevance (if we can project query into KG space)
        List<ScoredEntity> scoredEntities = new ArrayList<>();

        for (GraphNode node : nodesWithEmbeddings) {
            // Calculate text similarity score
            String entityText = node.getTitle() + " " + (node.getDescription() != null ? node.getDescription() : "");
            INDArray entityTextEmb = textEmbeddingModel.embed(entityText);

            if (entityTextEmb == null || entityTextEmb.isEmpty()) {
                continue;
            }

            double textSimilarity = cosineSimilarity(queryEmbedding, entityTextEmb);

            // Optionally close the embedding if possible
            try {
                if (!entityTextEmb.wasClosed()) {
                    entityTextEmb.close();
                }
            } catch (Exception e) {
                log.warn("Failed to close entity text embedding for '{}': {}", node.getTitle(), e.getMessage());
            }

            scoredEntities.add(new ScoredEntity(
                    node.getTitle(),
                    node.getNodeId(),
                    textSimilarity,
                    node.getKgEmbedding()
            ));
        }

        // Sort by score and take top K
        scoredEntities.sort(Comparator.comparing(e -> -e.score));

        // Clean up query embedding
        try {
            if (!queryEmbedding.wasClosed()) {
                queryEmbedding.close();
            }
        } catch (Exception e) {
            log.warn("Failed to close query embedding: {}", e.getMessage());
        }

        return scoredEntities.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Fallback: Find entities using simple keyword matching.
     */
    private List<ScoredEntity> findEntitiesByKeywordMatch(String query, Long factSheetId, int topK) {
        // Split query into keywords
        String[] keywords = query.toLowerCase().split("\\s+");

        List<GraphNode> entities = nodeRepository.findEntitiesByFactSheet(factSheetId);
        List<ScoredEntity> scored = new ArrayList<>();

        for (GraphNode entity : entities) {
            String title = entity.getTitle().toLowerCase();
            String desc = entity.getDescription() != null ? entity.getDescription().toLowerCase() : "";

            int matchCount = 0;
            for (String keyword : keywords) {
                if (keyword.length() > 2) { // Skip very short words
                    if (title.contains(keyword) || desc.contains(keyword)) {
                        matchCount++;
                    }
                }
            }

            if (matchCount > 0) {
                double score = (double) matchCount / keywords.length;
                scored.add(new ScoredEntity(
                        entity.getTitle(),
                        entity.getNodeId(),
                        score,
                        entity.getKgEmbedding()
                ));
            }
        }

        scored.sort(Comparator.comparing(e -> -e.score));
        return scored.stream().limit(topK).collect(Collectors.toList());
    }

    /**
     * Expands the entity set using KG link prediction and graph structure.
     */
    private Set<String> expandEntitiesViaGraph(Set<String> seedEntities, Long factSheetId, int hops) {
        Set<String> expanded = new HashSet<>(seedEntities);

        if (hops <= 0) {
            return expanded;
        }

        // Get or load the KG embedding model
        KGEmbeddingModel model = getOrLoadModel(factSheetId);

        Set<String> currentFrontier = new HashSet<>(seedEntities);

        for (int hop = 0; hop < hops; hop++) {
            Set<String> nextFrontier = new HashSet<>();

            for (String entity : currentFrontier) {
                // Method 1: Use graph edges directly
                List<GraphEdge> outgoingEdges = edgeRepository.findBySourceNodeTitleAndFactSheetId(entity, factSheetId);
                List<GraphEdge> incomingEdges = edgeRepository.findByTargetNodeTitleAndFactSheetId(entity, factSheetId);

                for (GraphEdge edge : outgoingEdges) {
                    if (edge.getTargetNode() != null) {
                        nextFrontier.add(edge.getTargetNode().getTitle());
                    }
                }
                for (GraphEdge edge : incomingEdges) {
                    if (edge.getSourceNode() != null) {
                        nextFrontier.add(edge.getSourceNode().getTitle());
                    }
                }

                // Method 2: Use link prediction to find potential connections
                if (model != null) {
                    // Get common relation types
                    Set<String> relationTypes = getCommonRelationTypes(factSheetId);

                    for (String relType : relationTypes) {
                        try {
                            // Predict potential tail entities
                            List<EmbeddingScore> predictions = model.predictTails(entity, relType, 3);
                            for (EmbeddingScore pred : predictions) {
                                if (pred.score() > 0.5) { // Threshold for predicted links
                                    nextFrontier.add(pred.entity());
                                }
                            }
                        } catch (Exception e) {
                            // Entity or relation not in model, skip
                            log.trace("Link prediction skipped for {}->{}: {}", entity, relType, e.getMessage());
                        }
                    }
                }
            }

            // Add new entities not already seen
            nextFrontier.removeAll(expanded);
            expanded.addAll(nextFrontier);
            currentFrontier = nextFrontier;

            if (currentFrontier.isEmpty()) {
                break; // No more expansion possible
            }
        }

        return expanded;
    }

    /**
     * Gets common relation types used in the knowledge graph.
     */
    private Set<String> getCommonRelationTypes(Long factSheetId) {
        List<GraphEdge> edges = edgeRepository.findByFactSheetId(factSheetId);
        return edges.stream()
                .map(e -> e.getEdgeType().name())
                .collect(Collectors.toSet());
    }

    /**
     * Builds context documents from the expanded entity set.
     */
    private List<RetrievedDoc> buildContextFromEntities(
            Set<String> expandedEntities,
            List<ScoredEntity> originalEntities,
            Long factSheetId
    ) {
        List<RetrievedDoc> docs = new ArrayList<>();

        // Build a score map from original entities
        Map<String, Double> entityScores = new HashMap<>();
        for (ScoredEntity se : originalEntities) {
            entityScores.put(se.entityName, se.score);
        }

        // Get nodes for all expanded entities
        List<GraphNode> allNodes = nodeRepository.findByFactSheetId(factSheetId);
        Map<String, GraphNode> nodeByTitle = allNodes.stream()
                .collect(Collectors.toMap(GraphNode::getTitle, n -> n, (a, b) -> a));

        for (String entityName : expandedEntities) {
            GraphNode node = nodeByTitle.get(entityName);
            if (node == null) continue;

            // Build context text for this entity
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("Entity: ").append(node.getTitle()).append("\n");

            if (node.getDescription() != null && !node.getDescription().isEmpty()) {
                contextBuilder.append("Description: ").append(node.getDescription()).append("\n");
            }

            if (node.getContentPreview() != null && !node.getContentPreview().isEmpty()) {
                contextBuilder.append("Content: ").append(node.getContentPreview()).append("\n");
            }

            // Add relationship context
            List<GraphEdge> edges = edgeRepository.findBySourceNodeIdOrTargetNodeId(node.getId());
            if (!edges.isEmpty()) {
                contextBuilder.append("Relationships:\n");
                for (GraphEdge edge : edges) {
                    String sourceName = edge.getSourceNode() != null ? edge.getSourceNode().getTitle() : "?";
                    String targetName = edge.getTargetNode() != null ? edge.getTargetNode().getTitle() : "?";
                    contextBuilder.append("  - ").append(sourceName)
                            .append(" [").append(edge.getEdgeType().name()).append("] ")
                            .append(targetName).append("\n");
                }
            }

            // Calculate score (original query match score, degraded for expanded entities)
            double score = entityScores.getOrDefault(entityName, 0.5);

            // Build metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("entity_name", node.getTitle());
            metadata.put("node_id", node.getNodeId());
            metadata.put("node_type", node.getNodeType().name());
            metadata.put("retriever_type", "kg_embedding");
            metadata.put("fact_sheet_id", factSheetId);
            if (node.getKgEmbeddingAlgorithm() != null) {
                metadata.put("kg_algorithm", node.getKgEmbeddingAlgorithm().name());
            }

            docs.add(RetrievedDoc.builder()
                    .id(node.getNodeId())
                    .text(contextBuilder.toString())
                    .score(score)
                    .metadata(metadata)
                    .build());
        }

        return docs;
    }

    /**
     * Combines KG retrieval results with traditional text retrieval.
     */
    private List<RetrievedDoc> combineWithTextRetrieval(
            String query,
            List<RetrievedDoc> kgDocs,
            int maxResults,
            GraphRAGConfig config
    ) {
        if (baseRetriever == null) {
            return kgDocs;
        }

        double kgWeight = config.kgWeight();
        double textWeight = config.textWeight();

        // Get text retrieval results
        int textK = (int) Math.ceil(maxResults * textWeight);
        List<RetrievedDoc> textDocs = baseRetriever.retrieveWithDetails(query, textK);

        // Build a combined set with weighted scores
        Map<String, RetrievedDoc> combinedById = new LinkedHashMap<>();

        // Add KG docs with KG weight
        for (RetrievedDoc doc : kgDocs) {
            double weightedScore = doc.getScore() != null ? doc.getScore() * kgWeight : kgWeight * 0.5;
            Map<String, Object> newMetadata = new HashMap<>(doc.getMetadata());
            newMetadata.put("source", "kg_embedding");
            newMetadata.put("kg_score", doc.getScore());

            combinedById.put(doc.getId(), RetrievedDoc.builder()
                    .id(doc.getId())
                    .text(doc.getText())
                    .score(weightedScore)
                    .metadata(newMetadata)
                    .build());
        }

        // Add or merge text docs with text weight
        for (RetrievedDoc doc : textDocs) {
            String docId = doc.getId();
            double textScore = doc.getScore() != null ? doc.getScore() * textWeight : textWeight * 0.5;

            if (combinedById.containsKey(docId)) {
                // Merge: combine scores
                RetrievedDoc existing = combinedById.get(docId);
                double combinedScore = existing.getScore() + textScore;
                Map<String, Object> mergedMetadata = new HashMap<>(existing.getMetadata());
                mergedMetadata.put("text_score", doc.getScore());
                mergedMetadata.put("source", "hybrid");

                combinedById.put(docId, RetrievedDoc.builder()
                        .id(docId)
                        .text(existing.getText())
                        .score(combinedScore)
                        .metadata(mergedMetadata)
                        .build());
            } else {
                // Add new text-only doc
                Map<String, Object> newMetadata = new HashMap<>(doc.getMetadata());
                newMetadata.put("source", "text_retrieval");
                newMetadata.put("text_score", doc.getScore());

                combinedById.put(docId, RetrievedDoc.builder()
                        .id(docId)
                        .text(doc.getText())
                        .score(textScore)
                        .metadata(newMetadata)
                        .build());
            }
        }

        // Sort by combined score and return top results
        return combinedById.values().stream()
                .sorted(Comparator.comparing(RetrievedDoc::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * Falls back to base retriever when KG retrieval fails.
     */
    private List<RetrievedDoc> fallbackToBaseRetriever(String query, int maxResults) {
        if (baseRetriever != null) {
            return baseRetriever.retrieveWithDetails(query, maxResults);
        }
        return Collections.emptyList();
    }

    /**
     * Gets or loads a KG embedding model for a fact sheet.
     */
    private KGEmbeddingModel getOrLoadModel(Long factSheetId) {
        return loadedModels.computeIfAbsent(factSheetId, id -> {
            KGEmbeddingAlgorithm algorithm = storageService.getStoredAlgorithm(id);
            if (algorithm == null) {
                return null;
            }

            KGEmbeddingModel model = switch (algorithm) {
                case TRANSE -> new TransEModel();
                case ROTATE -> new RotatEModel();
            };

            if (storageService.loadEmbeddings(model, id)) {
                log.info("Loaded {} model for fact sheet {}", algorithm, id);
                return model;
            }
            return null;
        });
    }

    /**
     * Calculates cosine similarity between two vectors.
     */
    private double cosineSimilarity(INDArray a, INDArray b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        // Flatten if needed
        INDArray aFlat = a.isVector() ? a : a.ravel();
        INDArray bFlat = b.isVector() ? b : b.ravel();

        // Ensure same length
        if (aFlat.length() != bFlat.length()) {
            return 0.0;
        }

        double dotProduct = Nd4j.getBlasWrapper().dot(aFlat, bFlat);
        double normA = aFlat.norm2Number().doubleValue();
        double normB = bFlat.norm2Number().doubleValue();

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (normA * normB);
    }

    /**
     * Internal class for scored entities during retrieval.
     */
    private record ScoredEntity(
            String entityName,
            String nodeId,
            double score,
            INDArray kgEmbedding
    ) {}

    /**
     * Clears the cached models for a fact sheet.
     */
    public void clearCachedModel(Long factSheetId) {
        loadedModels.remove(factSheetId);
    }

    /**
     * Clears all cached models.
     */
    public void clearAllCachedModels() {
        loadedModels.clear();
    }
}
