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
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.ConceptExtractor.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Implementation of FactSheetGraphService for building knowledge graphs from indexed documents.
 */
@Service
@Slf4j
public class FactSheetGraphServiceImpl implements FactSheetGraphService {

    private GraphNodeRepository nodeRepository;
    private GraphEdgeRepository edgeRepository;
    private EntityMentionRepository entityMentionRepository;
    private KnowledgeGraphService knowledgeGraphService;
    private ConceptExtractor conceptExtractor;
    private SourceLinkingService sourceLinkingService;

    private final Map<String, GraphBuildStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<GraphBuildStatus>> runningJobs = new ConcurrentHashMap<>();
    private final Set<String> cancelledJobs = ConcurrentHashMap.newKeySet();

    /**
     * Short-TTL cache for getGraphStatistics to prevent concurrent GET /job requests
     * (96+ threads observed) from each independently recomputing the live graph summary.
     * Key = factSheetId (as String), Value = [resultMap, expiryMs].
     */
    private final ConcurrentHashMap<String, Object[]> statsCache = new ConcurrentHashMap<>();
    private static final long STATS_CACHE_TTL_MS = 5_000L; // 5-second TTL

    @Autowired
    public FactSheetGraphServiceImpl(
            GraphNodeRepository nodeRepository,
            GraphEdgeRepository edgeRepository,
            EntityMentionRepository entityMentionRepository,
            KnowledgeGraphService knowledgeGraphService,
            ConceptExtractor conceptExtractor,
            SourceLinkingService sourceLinkingService) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.entityMentionRepository = entityMentionRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.conceptExtractor = conceptExtractor;
        this.sourceLinkingService = sourceLinkingService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected FactSheetGraphServiceImpl() {}


    @Override
    public GraphBuildStatus buildGraphFromIndex(Long factSheetId, GraphBuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        GraphBuildStatus status = new GraphBuildStatus(
            jobId, "FactSheet-" + factSheetId, factSheetId,
            "PENDING", 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null, Map.of()
        );
        jobStatuses.put(jobId, status);

        if (config.asyncProcessing()) {
            CompletableFuture.runAsync(() -> executeBuild(jobId, factSheetId, config))
                    .exceptionally(ex -> {
                        jobStatuses.put(jobId, new GraphBuildStatus(
                                jobId, "FactSheet-" + factSheetId, factSheetId,
                                "FAILED", 0, 0, 0, 0, 0, ex.getMessage(),
                                System.currentTimeMillis(), null, Map.of()));
                        return null;
                    });
            return status;
        } else {
            return executeBuild(jobId, factSheetId, config);
        }
    }

    @Override
    @Async
    public CompletableFuture<GraphBuildStatus> buildGraphFromIndexAsync(Long factSheetId, GraphBuildConfig config) {
        String jobId = UUID.randomUUID().toString();
        GraphBuildStatus initialStatus = new GraphBuildStatus(
            jobId, "FactSheet-" + factSheetId, factSheetId,
            "PENDING", 0, 0, 0, 0, 0, null,
            System.currentTimeMillis(), null, Map.of()
        );
        jobStatuses.put(jobId, initialStatus);

        CompletableFuture<GraphBuildStatus> future = CompletableFuture.supplyAsync(
            () -> executeBuild(jobId, factSheetId, config)
        );
        runningJobs.put(jobId, future);

        return future;
    }

    @Override
    public GraphBuildStatus getBuildStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    @Override
    public boolean cancelBuild(String jobId) {
        if (runningJobs.containsKey(jobId)) {
            cancelledJobs.add(jobId);
            CompletableFuture<GraphBuildStatus> future = runningJobs.get(jobId);
            if (future != null) {
                future.cancel(true);
            }
            return true;
        }
        return false;
    }

    @Override
    public GraphVisualizationData getVisualizationData(Long factSheetId, int maxNodes, int maxEdges) {
        // Vector store is the SINGLE SOURCE OF TRUTH — read all nodes via the matrix service.
        List<GraphNode> nodes = new ArrayList<>(knowledgeGraphService.getNodesInFactSheet(factSheetId));

        // Apply maxNodes limit preserving type priority: SOURCE > DOCUMENT > ENTITY > others
        if (maxNodes > 0 && nodes.size() > maxNodes) {
            int[] typeOrder = new int[NodeLevel.values().length];
            for (NodeLevel lvl : NodeLevel.values()) {
                typeOrder[lvl.ordinal()] = switch (lvl) {
                    case SOURCE -> 0;
                    case DOCUMENT -> 1;
                    case TABLE -> 2;
                    case ENTITY -> 3;
                    case CUSTOM -> 4;
                    case SNIPPET -> 5;
                    case ATTACHMENT -> 6;
                    case IDENTIFIER -> 7;
                };
            }
            nodes = nodes.stream()
                    .sorted(Comparator.comparingInt(n -> typeOrder[n.getNodeType().ordinal()]))
                    .limit(maxNodes)
                    .collect(Collectors.toList());
        }

        // Collect edges between visible nodes from the vector store.
        Set<String> nodeIds = nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId).stream()
                .filter(e -> e.getSourceNode() != null && e.getTargetNode() != null
                        && nodeIds.contains(e.getSourceNode().getNodeId())
                        && nodeIds.contains(e.getTargetNode().getNodeId()))
                .collect(Collectors.toList());
        if (maxEdges > 0 && edges.size() > maxEdges) {
            edges = edges.subList(0, maxEdges);
        }

        // Convert to D3 format
        List<Map<String, Object>> nodeData = nodes.stream()
            .map(this::nodeToD3Format)
            .collect(Collectors.toList());

        List<Map<String, Object>> edgeData = edges.stream()
            .map(this::edgeToD3Format)
            .collect(Collectors.toList());

        Map<String, Object> metadata = Map.of(
            "factSheetId", factSheetId,
            "totalNodes", nodes.size(),
            "totalEdges", edgeData.size(),
            "nodeTypes", countByNodeType(nodes),
            "edgeTypes", countByEdgeType(edges)
        );

        return new GraphVisualizationData(nodeData, edgeData, metadata);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getGraphStatistics(Long factSheetId) {
        // P2: single-flight short-TTL cache — prevents 96 concurrent GET /job calls from
        // each independently recomputing the live graph summary.
        String cacheKey = String.valueOf(factSheetId);
        Object[] cached = statsCache.get(cacheKey);
        if (cached != null && (long) cached[1] > System.currentTimeMillis()) {
            return (Map<String, Object>) cached[0];
        }

        Map<String, Object> stats = new LinkedHashMap<>();

        // --- Vector/matrix store is the SINGLE SOURCE OF TRUTH for all counts ---
        // Per-fact-sheet node counts come directly from the matrix store, where
        // MatrixKnowledgeGraphService.createNode stores factSheetId on each MatrixGraphNode.
        // No JPA reads for graph operations.
        Map<String, Long> nodesByType = new LinkedHashMap<>();
        long totalEdges = 0L;
        Map<String, Long> edgesByType = new LinkedHashMap<>();

        if (knowledgeGraphService != null && factSheetId != null) {
            try {
                for (NodeLevel level : NodeLevel.values()) {
                    long count = knowledgeGraphService.countNodesByTypeInFactSheet(factSheetId, level);
                    if (count > 0) {
                        nodesByType.put(level.name(), count);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to count vector-store nodes for factSheetId={}: {}", factSheetId, e.getMessage());
            }

            // Edge counts: delegate to the global graph statistics from the vector store
            // (edges are not fact-sheet scoped in the matrix adjacency structure, so we
            // read the global edge-type breakdown which is what populates relationshipTypeCounts).
            try {
                Map<String, Object> globalStats = knowledgeGraphService.getGraphStatistics();
                // Collect per-type edge counts from "edges_<type>" keys
                for (Map.Entry<String, Object> entry : globalStats.entrySet()) {
                    String key = entry.getKey();
                    if (key != null && key.startsWith("edges_") && entry.getValue() instanceof Number num) {
                        String edgeType = key.substring("edges_".length()).toUpperCase(java.util.Locale.ROOT);
                        long count = num.longValue();
                        if (count > 0) {
                            edgesByType.put(edgeType, count);
                            totalEdges += count;
                        }
                    }
                }
                // Fallback: if no "edges_*" keys, use the scalar "totalEdges" from global stats
                if (totalEdges == 0 && globalStats.get("totalEdges") instanceof Number num) {
                    totalEdges = num.longValue();
                }
            } catch (Exception e) {
                log.warn("Failed to read edge counts from vector store for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }

        stats.put("nodesByType", nodesByType);
        // Convenience top-level keys for UnifiedCrawlController.buildLiveGraphSummary
        // and the /api/unified-crawl/graph-stats endpoint.
        stats.put("entityCount",   nodesByType.getOrDefault("ENTITY",   0L));
        stats.put("documentCount", nodesByType.getOrDefault("DOCUMENT", 0L));
        stats.put("snippetCount",  nodesByType.getOrDefault("SNIPPET",  0L));
        stats.put("tableCount",    nodesByType.getOrDefault("TABLE",    0L));
        long totalNodes = nodesByType.values().stream().mapToLong(Long::longValue).sum();
        stats.put("totalNodes", totalNodes);

        // Edge counts — all from the vector store
        stats.put("relationshipTypeCounts", edgesByType);
        stats.put("totalEdges", totalEdges);

        // Entity statistics — null-guarded
        long distinctConcepts = 0;
        if (entityMentionRepository != null && factSheetId != null) {
            try {
                distinctConcepts = entityMentionRepository.countDistinctEntitiesByFactSheet(factSheetId);
            } catch (Exception e) {
                log.warn("Failed to count distinct entities for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }
        stats.put("distinctConcepts", distinctConcepts);

        // Top concepts — null-guarded
        List<Map<String, Object>> topConceptsList = Collections.emptyList();
        if (entityMentionRepository != null && factSheetId != null) {
            try {
                List<Object[]> topConcepts = entityMentionRepository.findTopEntitiesByFactSheet(
                        factSheetId, PageRequest.of(0, 10));
                topConceptsList = topConcepts.stream()
                        .filter(arr -> arr != null && arr.length >= 2 && arr[0] != null && arr[1] != null)
                        .map(arr -> Map.<String, Object>of("name", arr[0], "count", arr[1]))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("Failed to fetch top concepts for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }
        stats.put("topConcepts", topConceptsList);

        // Source connectivity — null-guarded
        Object connectivity = Map.of();
        if (sourceLinkingService != null && factSheetId != null) {
            try {
                connectivity = sourceLinkingService.getConnectivitySummary(factSheetId);
            } catch (Exception e) {
                log.warn("Failed to get connectivity summary for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }
        stats.put("sourceConnectivity", connectivity);

        // Populate cache entry for subsequent concurrent calls
        statsCache.put(cacheKey, new Object[]{stats, System.currentTimeMillis() + STATS_CACHE_TTL_MS});

        return stats;
    }

    @Override
    public int clearGraph(Long factSheetId) {
        log.warn("Clearing graph for fact sheet {}", factSheetId);

        // Count nodes in vector store before deletion so we can return a meaningful count.
        long nodesBefore = 0;
        for (NodeLevel level : NodeLevel.values()) {
            nodesBefore += knowledgeGraphService.countNodesByTypeInFactSheet(factSheetId, level);
        }

        // Vector store is the SINGLE SOURCE OF TRUTH — delete via matrix service.
        knowledgeGraphService.deleteByFactSheetId(factSheetId);

        // Also clean up JPA entity mentions (which are still persisted there)
        int mentionsDeleted = 0;
        if (entityMentionRepository != null) {
            try {
                mentionsDeleted = entityMentionRepository.deleteByFactSheetId(factSheetId);
            } catch (Exception e) {
                log.warn("Failed to delete entity mentions for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }

        int nodesDeleted = (int) nodesBefore;
        log.info("Cleared graph for fact sheet {}: ~{} nodes deleted, {} mentions deleted",
            factSheetId, nodesDeleted, mentionsDeleted);

        return nodesDeleted + mentionsDeleted;
    }

    @Override
    public List<GraphBuildStatus> getRunningJobs() {
        return runningJobs.keySet().stream()
            .map(jobStatuses::get)
            .filter(Objects::nonNull)
            .filter(s -> "RUNNING".equals(s.status()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public int processIndexedDocument(Long factSheetId, String documentId, String content,
                                       Map<String, Object> metadata, String sourceId,
                                       GraphBuildConfig config) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        // Get or create source node
        final GraphNode sourceNode;
        if (sourceId != null) {
            sourceNode = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                sourceId, NodeLevel.SOURCE, factSheetId)
                .orElseGet(() -> createSourceNode(factSheetId, sourceId, metadata));
        } else {
            sourceNode = null;
        }

        // Get or create document node
        String title = metadata != null && metadata.get("title") != null ?
            metadata.get("title").toString() : documentId;

        GraphNode docNode = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
            documentId, NodeLevel.DOCUMENT, factSheetId)
            .orElseGet(() -> {
                GraphNode node = GraphNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .nodeType(NodeLevel.DOCUMENT)
                    .externalId(documentId)
                    .title(title)
                    .description(content.length() > 500 ? content.substring(0, 500) + "..." : content)
                    .contentPreview(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                    .parent(sourceNode)
                    .sourceNode(sourceNode)
                    .factSheetId(factSheetId)
                    .build();
                return nodeRepository.save(node);
            });

        // Extract concepts
        ExtractionResult result = conceptExtractor.extractConcepts(content, config.extractionConfig());

        // Create entity nodes and mentions for each concept
        int conceptsAdded = 0;
        for (ExtractedConcept concept : result.concepts()) {
            if (concept.confidence() * 100 >= config.minConceptConfidence()) {
                // Find or create entity node
                GraphNode entityNode = findOrCreateEntityNode(factSheetId, concept);

                // Create entity mention
                EntityMention mention = entityMentionRepository
                    .findByNodeAndEntityNameAndFactSheet(docNode, concept.normalizedName(), factSheetId)
                    .orElseGet(() -> EntityMention.builder()
                        .node(docNode)
                        .entityName(concept.normalizedName())
                        .entityType(concept.category())
                        .mentionCount(0)
                        .confidence(concept.confidence())
                        .factSheetId(factSheetId)
                        .build());

                mention.setMentionCount(mention.getMentionCount() + concept.frequency());
                mention.setConfidence(Math.max(mention.getConfidence(), concept.confidence()));
                mention.setContextJson(concept.context());
                entityMentionRepository.save(mention);

                // Create edge from document to entity (if not exists)
                if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                        docNode.getNodeId(), entityNode.getNodeId(), factSheetId).isEmpty()) {
                    GraphEdge edge = GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .sourceNode(docNode)
                        .targetNode(entityNode)
                        .edgeType(EdgeType.SHARED_ENTITY)
                        .weight(concept.confidence())
                        .description("Document contains concept: " + concept.name())
                        .factSheetId(factSheetId)
                        .build();
                    edgeRepository.save(edge);
                }

                conceptsAdded++;
            }
        }

        // Create hierarchical edge if source exists
        if (sourceNode != null && config.includeHierarchicalEdges()) {
            if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                    sourceNode.getNodeId(), docNode.getNodeId(), factSheetId).isEmpty()) {
                GraphEdge edge = GraphEdge.builder()
                    .edgeId(UUID.randomUUID().toString())
                    .sourceNode(sourceNode)
                    .targetNode(docNode)
                    .edgeType(EdgeType.HIERARCHICAL)
                    .weight(1.0)
                    .description("Contains document")
                    .factSheetId(factSheetId)
                    .build();
                edgeRepository.save(edge);
            }
        }

        return conceptsAdded;
    }

    @Override
    @Transactional
    public int rebuildConceptEdges(Long factSheetId, int minSharedConcepts) {
        int edgesCreated = 0;

        // Find all document pairs with shared concepts
        List<Object[]> pairs = entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(
            factSheetId, minSharedConcepts);

        for (Object[] pair : pairs) {
            Long node1Id = (Long) pair[0];
            Long node2Id = (Long) pair[1];
            Long sharedCount = (Long) pair[2];

            GraphNode node1 = nodeRepository.findById(node1Id).orElse(null);
            GraphNode node2 = nodeRepository.findById(node2Id).orElse(null);

            if (node1 != null && node2 != null) {
                // Check if edge exists
                if (edgeRepository.findEdgeBetweenNodesInFactSheet(
                        node1.getNodeId(), node2.getNodeId(), factSheetId).isEmpty()) {

                    double weight = Math.min(1.0, sharedCount / 10.0);

                    GraphEdge edge = GraphEdge.builder()
                        .edgeId(UUID.randomUUID().toString())
                        .sourceNode(node1)
                        .targetNode(node2)
                        .edgeType(EdgeType.SHARED_ENTITY)
                        .weight(weight)
                        .description("Shares " + sharedCount + " concepts")
                        .bidirectional(true)
                        .factSheetId(factSheetId)
                        .build();
                    edgeRepository.save(edge);
                    edgesCreated++;
                }
            }
        }

        log.info("Created {} concept-based edges for fact sheet {}", edgesCreated, factSheetId);
        return edgesCreated;
    }

    @Override
    public List<Map<String, Object>> getTopConcepts(Long factSheetId, int limit) {
        List<Object[]> topConcepts = entityMentionRepository.findTopEntitiesByFactSheet(
            factSheetId, PageRequest.of(0, limit));

        return topConcepts.stream()
            .map(arr -> {
                Map<String, Object> concept = new HashMap<>();
                concept.put("name", arr[0]);
                concept.put("totalMentions", arr[1]);
                return concept;
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> searchNodes(Long factSheetId, String query, int limit) {
        // Vector store is the SINGLE SOURCE OF TRUTH — delegate to matrix service.
        return knowledgeGraphService.searchNodesInFactSheet(factSheetId, query, limit).stream()
            .map(this::nodeToD3Format)
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getRelatedDocuments(Long factSheetId, String documentNodeId,
                                                          int minSharedConcepts, int limit) {
        // Vector store is the SINGLE SOURCE OF TRUTH — look up the node via matrix service.
        GraphNode docNode = knowledgeGraphService.getNode(documentNodeId).orElse(null);
        if (docNode == null) {
            return List.of();
        }

        // Get concepts in this document
        List<String> docConcepts = entityMentionRepository.findEntitiesByNodeId(documentNodeId);
        if (docConcepts.isEmpty()) {
            return List.of();
        }

        // Find other documents that share concepts
        Map<String, Integer> relatedDocs = new HashMap<>();
        for (String concept : docConcepts) {
            List<EntityMention> mentions = entityMentionRepository.findByEntityNameAndFactSheet(
                concept, factSheetId);
            for (EntityMention mention : mentions) {
                if (!mention.getNode().getNodeId().equals(documentNodeId) &&
                    mention.getNode().getNodeType() == NodeLevel.DOCUMENT) {
                    relatedDocs.merge(mention.getNode().getNodeId(), 1, Integer::sum);
                }
            }
        }

        // Filter by minimum shared concepts and return top results
        return relatedDocs.entrySet().stream()
            .filter(e -> e.getValue() >= minSharedConcepts)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> {
                // Vector store is the SINGLE SOURCE OF TRUTH — look up via matrix service.
                GraphNode node = knowledgeGraphService.getNode(e.getKey()).orElse(null);
                Map<String, Object> result = new HashMap<>();
                if (node != null) {
                    result.put("nodeId", node.getNodeId());
                    result.put("title", node.getTitle());
                    result.put("sharedConcepts", e.getValue());
                    result.put("nodeType", node.getNodeType().name());
                }
                return result;
            })
            .filter(m -> !m.isEmpty())
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphBuildStatus executeBuild(String jobId, Long factSheetId, GraphBuildConfig config) {
        long startTime = System.currentTimeMillis();
        int nodesCreated = 0;
        int edgesCreated = 0;
        int conceptsExtracted = 0;
        String errorMessage = null;

        try {
            updateStatus(jobId, factSheetId, "RUNNING", 0, 0, 0, 0, 0, null);

            // Get all source nodes for this fact sheet
            List<GraphNode> sources = nodeRepository.findSourcesByFactSheet(factSheetId);

            // If no sources exist yet, we need external data
            // This service works with existing graph nodes - actual document ingestion
            // should populate source and document nodes first

            int totalDocs = 0;
            int processedDocs = 0;

            for (GraphNode source : sources) {
                if (cancelledJobs.contains(jobId)) {
                    log.info("Build job {} cancelled", jobId);
                    break;
                }

                // Get documents for this source
                List<GraphNode> documents = nodeRepository.findBySourceIdAndType(
                    source.getNodeId(), NodeLevel.DOCUMENT);

                if (config.maxDocumentsToProcess() > 0) {
                    documents = documents.stream()
                        .limit(config.maxDocumentsToProcess())
                        .collect(Collectors.toList());
                }

                totalDocs += documents.size();

                for (GraphNode doc : documents) {
                    if (cancelledJobs.contains(jobId)) break;

                    // Get content from the document node
                    String content = doc.getDescription();
                    if (content == null && doc.getContentPreview() != null) {
                        content = doc.getContentPreview();
                    }

                    if (content != null && !content.isBlank()) {
                        int concepts = processIndexedDocument(
                            factSheetId,
                            doc.getExternalId(),
                            content,
                            Map.of("title", doc.getTitle()),
                            source.getNodeId(),
                            config
                        );
                        conceptsExtracted += concepts;
                    }

                    processedDocs++;
                    updateStatus(jobId, factSheetId, "RUNNING", totalDocs, processedDocs,
                        nodesCreated, edgesCreated, conceptsExtracted, null);
                }
            }

            // Create shared concept edges
            if (!cancelledJobs.contains(jobId) && config.computeConceptEdges()) {
                edgesCreated = rebuildConceptEdges(factSheetId, config.minSharedConceptsForEdge());
            }

            // Link sources together
            if (!cancelledJobs.contains(jobId) && config.computeSourceLinks()) {
                SourceLinkingService.LinkingResult linkResult =
                    sourceLinkingService.linkAllSources(factSheetId, config.linkingConfig());
                edgesCreated += linkResult.linksCreated();
            }

            // Count created nodes
            nodesCreated = nodeRepository.findByFactSheetId(factSheetId).size();

            String finalStatus = cancelledJobs.contains(jobId) ? "CANCELLED" : "COMPLETED";
            updateStatus(jobId, factSheetId, finalStatus, totalDocs, processedDocs,
                nodesCreated, edgesCreated, conceptsExtracted, null);

            log.info("Graph build {} completed: {} nodes, {} edges, {} concepts",
                jobId, nodesCreated, edgesCreated, conceptsExtracted);

        } catch (Exception e) {
            log.error("Graph build failed for fact sheet {}", factSheetId, e);
            errorMessage = e.getMessage();
            updateStatus(jobId, factSheetId, "FAILED", 0, 0,
                nodesCreated, edgesCreated, conceptsExtracted, errorMessage);
        } finally {
            runningJobs.remove(jobId);
            cancelledJobs.remove(jobId);
        }

        GraphBuildStatus finalStatus = jobStatuses.get(jobId);
        return new GraphBuildStatus(
            finalStatus.jobId(),
            finalStatus.factSheetName(),
            finalStatus.factSheetId(),
            finalStatus.status(),
            finalStatus.totalDocuments(),
            finalStatus.processedDocuments(),
            nodesCreated,
            edgesCreated,
            conceptsExtracted,
            errorMessage,
            startTime,
            System.currentTimeMillis(),
            getGraphStatistics(factSheetId)
        );
    }

    private void updateStatus(String jobId, Long factSheetId, String status,
                              int totalDocs, int processedDocs,
                              int nodes, int edges, int concepts, String error) {
        GraphBuildStatus current = jobStatuses.get(jobId);
        if (current != null) {
            jobStatuses.put(jobId, new GraphBuildStatus(
                jobId,
                current.factSheetName(),
                factSheetId,
                status,
                totalDocs,
                processedDocs,
                nodes,
                edges,
                concepts,
                error,
                current.startTime(),
                "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) ?
                    System.currentTimeMillis() : null,
                Map.of()
            ));
        }
    }

    private GraphNode createSourceNode(Long factSheetId, String sourceId, Map<String, Object> metadata) {
        String title = metadata != null && metadata.get("title") != null ?
            metadata.get("title").toString() : sourceId;
        String sourceType = metadata != null && metadata.get("sourceType") != null ?
            metadata.get("sourceType").toString() : "UNKNOWN";

        GraphNode node = GraphNode.builder()
            .nodeId(UUID.randomUUID().toString())
            .nodeType(NodeLevel.SOURCE)
            .externalId(sourceId)
            .title(title)
            .sourceType(sourceType)
            .factSheetId(factSheetId)
            .build();
        return nodeRepository.save(node);
    }

    @Transactional
    protected GraphNode findOrCreateEntityNode(Long factSheetId, ExtractedConcept concept) {
        return nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
            concept.normalizedName(), NodeLevel.ENTITY, factSheetId)
            .orElseGet(() -> {
                GraphNode entityNode = GraphNode.builder()
                    .nodeId(UUID.randomUUID().toString())
                    .nodeType(NodeLevel.ENTITY)
                    .externalId(concept.normalizedName())
                    .title(concept.name())
                    .description("Concept type: " + concept.category())
                    .factSheetId(factSheetId)
                    .build();
                return nodeRepository.save(entityNode);
            });
    }

    private Map<String, Object> nodeToD3Format(GraphNode node) {
        Map<String, Object> d3Node = new HashMap<>();
        d3Node.put("id", node.getNodeId());
        d3Node.put("label", node.getTitle());
        d3Node.put("type", node.getNodeType().name());
        d3Node.put("externalId", node.getExternalId());
        d3Node.put("childCount", node.getChildCount());
        d3Node.put("edgeCount", node.getEdgeCount());
        if (node.getDescription() != null) {
            d3Node.put("description", node.getDescription());
        }
        if (node.getSourceType() != null) {
            d3Node.put("sourceType", node.getSourceType());
        }
        // Parsed metadata so TABLE / structured nodes render in the fact-sheet graph view.
        d3Node.put("metadata", node.getMetadata());
        return d3Node;
    }

    private Map<String, Object> edgeToD3Format(GraphEdge edge) {
        Map<String, Object> d3Edge = new HashMap<>();
        d3Edge.put("id", edge.getEdgeId());
        d3Edge.put("source", edge.getSourceNode().getNodeId());
        d3Edge.put("target", edge.getTargetNode().getNodeId());
        d3Edge.put("type", edge.getEdgeType().name());
        d3Edge.put("weight", edge.getWeight());
        d3Edge.put("bidirectional", edge.getBidirectional());
        if (edge.getLabel() != null) {
            d3Edge.put("label", edge.getLabel());
        }
        if (edge.getDescription() != null) {
            d3Edge.put("description", edge.getDescription());
        }
        return d3Edge;
    }

    private Map<String, Long> countByNodeType(List<GraphNode> nodes) {
        return nodes.stream()
            .collect(Collectors.groupingBy(n -> n.getNodeType().name(), Collectors.counting()));
    }

    private Map<String, Long> countByEdgeType(List<GraphEdge> edges) {
        return edges.stream()
            .collect(Collectors.groupingBy(e -> e.getEdgeType().name(), Collectors.counting()));
    }
}
