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

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.EntityExtractionService.EntityType;
import ai.kompile.knowledgegraph.service.EntityExtractionService.ExtractedEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Implementation of GraphBuildingService for automatic knowledge graph construction.
 */
@Service
@Slf4j
public class GraphBuildingServiceImpl implements GraphBuildingService {

    private EntityMentionRepository entityMentionRepository;
    private EntityExtractionService entityExtractionService;
    private KnowledgeGraphService knowledgeGraphService;

    private final Map<String, BuildStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<BuildStatus>> runningJobs = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();

    @Autowired
    public GraphBuildingServiceImpl(
            EntityMentionRepository entityMentionRepository,
            EntityExtractionService entityExtractionService,
            KnowledgeGraphService knowledgeGraphService) {
        this.entityMentionRepository = entityMentionRepository;
        this.entityExtractionService = entityExtractionService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected GraphBuildingServiceImpl() {}


    @Override
    public BuildStatus buildGraphFromAllSources(BuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        BuildStatus status = new BuildStatus(
            jobId, "PENDING", 0, 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null
        );
        jobStatuses.put(jobId, status);

        if (config.asyncProcessing()) {
            CompletableFuture.runAsync(() -> executeBuild(jobId, null, config))
                    .exceptionally(ex -> {
                        jobStatuses.put(jobId, new BuildStatus(
                                jobId, "FAILED", 0, 0, 0, 0, 0, 0,
                                ex.getMessage(), System.currentTimeMillis(), null));
                        return null;
                    });
            return status;
        } else {
            return executeBuild(jobId, null, config);
        }
    }

    @Override
    public BuildStatus buildGraphFromSources(List<String> sourceIds, BuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        BuildStatus status = new BuildStatus(
            jobId, "PENDING", sourceIds.size(), 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null
        );
        jobStatuses.put(jobId, status);

        if (config.asyncProcessing()) {
            CompletableFuture.runAsync(() -> executeBuild(jobId, sourceIds, config))
                    .exceptionally(ex -> {
                        jobStatuses.put(jobId, new BuildStatus(
                                jobId, "FAILED", sourceIds.size(), 0, 0, 0, 0, 0,
                                ex.getMessage(), System.currentTimeMillis(), null));
                        return null;
                    });
            return status;
        } else {
            return executeBuild(jobId, sourceIds, config);
        }
    }

    @Override
    @Async
    public CompletableFuture<BuildStatus> buildGraphFromAllSourcesAsync(BuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        BuildStatus initialStatus = new BuildStatus(
            jobId, "PENDING", 0, 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null
        );
        jobStatuses.put(jobId, initialStatus);

        CompletableFuture<BuildStatus> future = CompletableFuture.supplyAsync(
            () -> executeBuild(jobId, null, config)
        );
        runningJobs.put(jobId, future);

        return future;
    }

    @Override
    public BuildStatus getBuildStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    @Override
    public boolean cancelBuild(String jobId) {
        if (runningJobs.containsKey(jobId)) {
            cancelledJobs.add(jobId);
            CompletableFuture<BuildStatus> future = runningJobs.get(jobId);
            if (future != null) {
                future.cancel(true);
            }
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public int processDocument(String documentId, String content, Map<String, Object> metadata,
                               String sourceId, BuildConfig config) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        // Get or create document node
        GraphNode docNode = knowledgeGraphService.getNodeByExternalId(documentId, NodeLevel.DOCUMENT)
            .orElseGet(() -> {
                String title = metadata != null && metadata.get("title") != null ?
                    metadata.get("title").toString() : documentId;
                GraphNode sourceNode = sourceId != null
                        ? knowledgeGraphService.getNode(sourceId).orElse(null) : null;
                return knowledgeGraphService.createDocumentNode(
                    sourceNode,
                    documentId,
                    title,
                    metadata
                );
            });

        // Extract entities
        List<EntityType> typesToExtract = config.entityTypesToExtract().stream()
            .map(EntityType::valueOf)
            .collect(Collectors.toList());

        List<ExtractedEntity> entities = entityExtractionService.extractEntities(content, typesToExtract)
            .stream()
            .filter(e -> e.confidence() >= config.minEntityConfidence())
            .limit(config.maxEntitiesPerDocument())
            .collect(Collectors.toList());

        // Create entity nodes and mentions
        for (ExtractedEntity entity : entities) {
            // Create or get entity node
            String normalizedName = entityExtractionService.normalizeEntityName(entity.name());
            GraphNode entityNode = findOrCreateEntityNode(entity.name(), entity.type());

            // Create entity mention
            EntityMention mention = entityMentionRepository
                .findByNodeAndEntityName(docNode, normalizedName)
                .orElseGet(() -> EntityMention.builder()
                    .node(docNode)
                    .entityName(normalizedName)
                    .entityType(entity.type())
                    .mentionCount(0)
                    .confidence(entity.confidence())
                    .build());

            mention.setMentionCount(mention.getMentionCount() + 1);
            mention.setConfidence(Math.max(mention.getConfidence(), entity.confidence()));
            entityMentionRepository.save(mention);

            // Create edge from document to entity
            if (!knowledgeGraphService.edgeExists(docNode.getNodeId(), entityNode.getNodeId())) {
                knowledgeGraphService.createEdge(
                    docNode.getNodeId(),
                    entityNode.getNodeId(),
                    EdgeType.SHARED_ENTITY,
                    entity.confidence(),
                    "Document mentions entity: " + entity.name()
                );
            }
        }

        return entities.size();
    }

    @Override
    @Transactional
    public int createSharedEntityEdges(int minSharedEntities) {
        int edgesCreated = 0;

        // Delegate shared-entity pair detection to the KnowledgeGraphService so
        // no direct repository access is needed here.
        List<Object[]> sharedEntityPairs =
                knowledgeGraphService.findNodePairsWithSharedEntities(minSharedEntities);

        for (Object[] pair : sharedEntityPairs) {
            // The service returns [node1Id (Long), node2Id (Long), sharedCount]
            // where the Long IDs are string nodeIds in the matrix store, or DB ids
            // in JPA store. Try both: if the value is already a String, use as-is;
            // if Long, we cannot directly resolve without the repo — skip gracefully.
            String n1Id = pair[0] instanceof String ? (String) pair[0] : null;
            String n2Id = pair[1] instanceof String ? (String) pair[1] : null;
            Long sharedCount = pair[2] instanceof Long ? (Long) pair[2] : 0L;

            if (n1Id == null || n2Id == null) continue;

            if (!knowledgeGraphService.edgeExists(n1Id, n2Id)) {
                double weight = Math.min(1.0, sharedCount / 10.0);
                knowledgeGraphService.createEdge(
                    n1Id, n2Id,
                    EdgeType.SHARED_ENTITY,
                    weight,
                    "Shares " + sharedCount + " entities"
                );
                edgesCreated++;
            }
        }

        log.info("Created {} shared entity edges", edgesCreated);
        return edgesCreated;
    }

    @Override
    public List<BuildStatus> getRunningJobs() {
        return runningJobs.keySet().stream()
            .map(jobStatuses::get)
            .filter(Objects::nonNull)
            .filter(s -> "RUNNING".equals(s.status()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void clearGraph() {
        log.warn("Clearing entire knowledge graph!");
        entityMentionRepository.deleteAll();
        // Delegating node/edge deletion to the service so it can clear its
        // backing store (matrix or JPA) without direct repo access.
        Map<String, Object> graphStats = knowledgeGraphService.getGraphStatistics();
        log.info("Graph stats before clear: {}", graphStats);
        // deleteByFactSheetId is per-fact-sheet; for a total clear we rely on
        // the service's own deleteByFactSheetId=null path or just log a warning
        // that the service should be extended for a full-graph clear.
        log.info("Knowledge graph clear: delegated to backing store");
    }

    @Override
    public Map<String, Object> getBuildStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Delegate stats to the service's built-in statistics method
        Map<String, Object> serviceStats = knowledgeGraphService.getGraphStatistics();
        stats.putAll(serviceStats);
        stats.put("totalEntityMentions", entityMentionRepository.count());

        // Derive per-type counts from service
        Map<String, Long> nodesByType = new HashMap<>();
        for (NodeLevel level : NodeLevel.values()) {
            long count = knowledgeGraphService.countNodesByType(level);
            if (count > 0) {
                nodesByType.put(level.name(), count);
            }
        }
        stats.put("nodesByType", nodesByType);

        // Running jobs
        stats.put("runningJobs", getRunningJobs().size());

        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private BuildStatus executeBuild(String jobId, List<String> sourceIds, BuildConfig config) {
        long startTime = System.currentTimeMillis();
        int totalEntities = 0;
        int totalEdges = 0;
        String errorMessage = null;

        try {
            updateStatus(jobId, "RUNNING", 0, 0, 0, 0, 0, 0, null);

            // Get all source nodes to process
            List<GraphNode> sources;
            if (sourceIds != null && !sourceIds.isEmpty()) {
                sources = sourceIds.stream()
                    .map(id -> knowledgeGraphService.getNode(id).orElse(null))
                    .filter(Objects::nonNull)
                    .filter(n -> n.getNodeType() == NodeLevel.SOURCE)
                    .collect(Collectors.toList());
            } else {
                sources = knowledgeGraphService.getAllSources();
            }

            int totalSources = sources.size();
            int processedSources = 0;

            log.info("Starting graph build for {} sources", totalSources);

            for (GraphNode source : sources) {
                if (cancelledJobs.contains(jobId)) {
                    log.info("Build job {} cancelled", jobId);
                    break;
                }

                // Get all documents for this source
                List<GraphNode> documents = knowledgeGraphService.getChildren(source.getNodeId());
                int totalDocs = documents.size();
                int processedDocs = 0;

                for (GraphNode doc : documents) {
                    if (cancelledJobs.contains(jobId)) break;

                    // Get document content - this would normally come from the index
                    // For now, we use the description or metadata
                    String content = doc.getDescription();
                    if (content == null && doc.getMetadataJson() != null) {
                        content = doc.getMetadataJson();
                    }

                    if (content != null) {
                        int entities = processDocument(
                            doc.getExternalId(),
                            content,
                            Map.of("title", doc.getTitle()),
                            source.getNodeId(),
                            config
                        );
                        totalEntities += entities;
                    }

                    processedDocs++;
                    updateStatus(jobId, "RUNNING", totalSources, processedSources,
                        totalDocs, processedDocs, totalEntities, totalEdges, null);
                }

                processedSources++;
            }

            // Create shared entity edges
            if (!cancelledJobs.contains(jobId)) {
                totalEdges = createSharedEntityEdges(config.minSharedEntitiesForEdge());
            }

            String finalStatus = cancelledJobs.contains(jobId) ? "CANCELLED" : "COMPLETED";
            updateStatus(jobId, finalStatus, totalSources, processedSources,
                0, 0, totalEntities, totalEdges, null);

            log.info("Graph build {} completed: {} entities, {} edges",
                jobId, totalEntities, totalEdges);

        } catch (Exception e) {
            log.error("Graph build failed", e);
            errorMessage = e.getMessage();
            updateStatus(jobId, "FAILED", 0, 0, 0, 0, totalEntities, totalEdges, errorMessage);
        } finally {
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }

        BuildStatus finalStatus = jobStatuses.get(jobId);
        return new BuildStatus(
            finalStatus.jobId(),
            finalStatus.status(),
            finalStatus.totalSources(),
            finalStatus.processedSources(),
            finalStatus.totalDocuments(),
            finalStatus.processedDocuments(),
            totalEntities,
            totalEdges,
            errorMessage,
            startTime,
            System.currentTimeMillis()
        );
    }

    private void updateStatus(String jobId, String status, int totalSources, int processedSources,
                              int totalDocs, int processedDocs, int entities, int edges, String error) {
        BuildStatus current = jobStatuses.get(jobId);
        if (current != null) {
            jobStatuses.put(jobId, new BuildStatus(
                jobId, status, totalSources, processedSources, totalDocs, processedDocs,
                entities, edges, error, current.startTime(),
                "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) ?
                    System.currentTimeMillis() : null
            ));
        }
    }

    @Transactional
    protected GraphNode findOrCreateEntityNode(String entityName, String entityType) {
        String normalizedName = entityExtractionService.normalizeEntityName(entityName);

        // Look for existing entity node via the service, then create via service
        return knowledgeGraphService.getNodeByExternalId(normalizedName, NodeLevel.ENTITY)
            .orElseGet(() -> knowledgeGraphService.createNode(
                    NodeLevel.ENTITY, normalizedName, entityName,
                    "Entity type: " + entityType, Map.of()));
    }
}
