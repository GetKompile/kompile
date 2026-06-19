/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Graph persistence utility methods extracted from {@link UnifiedCrawlGraphServiceImpl}.
 *
 * <p>Handles persistence of graph entities, relationships, entity mentions,
 * and document nodes to the knowledge graph service.
 */
@Component
class GraphPersistenceHelper {

    private static final Logger log = LoggerFactory.getLogger(GraphPersistenceHelper.class);

    static final ObjectMapper EDGE_METADATA_MAPPER = JsonUtils.standardMapper();

    private static final Pattern ENTITY_SUFFIX_PATTERN = Pattern.compile(
            "\\b(Inc\\.?|Corp\\.?|Corporation|Ltd\\.?|Limited|LLC|Co\\.?|Company|Group|Plc\\.?)$",
            Pattern.CASE_INSENSITIVE);

    final Map<String, String> labelCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    EntityMentionRepository entityMentionRepository;

    @Autowired
    CrawlDocumentTracker documentTracker;

    // -------------------------------------------------------------------------
    // Graph batch persistence
    // -------------------------------------------------------------------------

    /**
     * Persists all entities and relationships from a constructed graph batch to the knowledge graph.
     *
     * @param job       the crawl job (used for job ID, fact sheet ID, and cancellation checks)
     * @param graph     the extracted graph containing entities and relationships
     * @param batchDocs the source documents for this batch
     * @param config    graph extraction configuration (used for confidence thresholds)
     * @return a result record with the counts of persisted entities and relationships
     */
    GraphPersistResult persistConstructedGraphBatch(UnifiedCrawlJob job,
                                                    Graph graph,
                                                    Collection<RetrievedDoc> batchDocs,
                                                    GraphExtractionConfig config) {
        if (knowledgeGraphService == null || job == null || graph == null || isCancelled(job)) {
            return GraphPersistResult.empty();
        }

        String jobId = job.getJobId();
        Long factSheetId = jobFactSheetId(job);
        Map<String, RetrievedDoc> docsById = new HashMap<>();
        if (batchDocs != null) {
            for (RetrievedDoc doc : batchDocs) {
                if (doc != null && doc.getId() != null) {
                    docsById.put(doc.getId(), doc);
                }
            }
        }

        // Cache parent document nodes by sourcePath to avoid N+1 DB lookups.
        // Multiple entities in the same batch often share the same source document.
        Map<String, Optional<GraphNode>> parentDocCache = new HashMap<>();
        Map<String, String> externalToNodeId = new HashMap<>();
        // Pre-compute the CONTAINS label — constant for every entity in this batch
        String containsLabel = semanticRelationLabel(GraphConstants.REL_CONTAINS);
        int entitiesPersisted = 0;
        int relationshipsPersisted = 0;

        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                if (isCancelled(job)) {
                    return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
                }
                if (entity == null || belowMinConfidence(entity.getConfidence(), config)) {
                    continue;
                }
                try {
                    RetrievedDoc sourceDoc = graphEntitySourceDoc(entity, docsById, batchDocs);
                    String sourcePath = graphEntitySourcePath(entity, sourceDoc, batchDocs);
                    final Long fFactSheetId = factSheetId;
                    final RetrievedDoc fSourceDoc = sourceDoc;
                    Optional<GraphNode> parentDoc = sourcePath != null
                            ? parentDocCache.computeIfAbsent(sourcePath,
                                    sp -> findOrCreateDocumentNode(job, sp, fSourceDoc, fFactSheetId))
                            : findOrCreateDocumentNode(job, sourcePath, sourceDoc, factSheetId);
                    if (factSheetId == null && parentDoc.isPresent()) {
                        factSheetId = parentDoc.get().getFactSheetId();
                    }

                    String externalId = graphConstructorEntityExternalId(entity, sourcePath);
                    String entityType = safeEntityType(entity.getType());
                    String entityTitle = firstNonBlank(entity.getTitle(), entity.getId(), "Entity");
                    Map<String, Object> entityMeta = new LinkedHashMap<>();
                    if (entity.getMetadata() != null) {
                        entityMeta.putAll(entity.getMetadata());
                    }
                    entityMeta.put("entity_type", entityType);
                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                    entityMeta.put("extraction_method", "graph_constructor");
                    String sourceDocumentId = sourceDocumentId(entity.getMetadata());
                    if (sourceDocumentId != null) {
                        entityMeta.put("sourceDocumentId", sourceDocumentId);
                    }
                    if (sourcePath != null) {
                        entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    }
                    if (entity.getTextUnits() != null && !entity.getTextUnits().isEmpty()) {
                        entityMeta.put("textUnits", new ArrayList<>(entity.getTextUnits()));
                    }
                    Double confidence = finiteDouble(entity.getConfidence());
                    if (confidence != null) {
                        entityMeta.put("confidence", confidence);
                    }

                    GraphNode node = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY,
                            externalId,
                            entityTitle,
                            entity.getDescription(),
                            entityMeta,
                            factSheetId);
                    entitiesPersisted++;
                    job.incrementEntityType(entityType);
                    externalToNodeId.put(externalId, node.getNodeId());
                    if (entity.getId() != null && !entity.getId().isBlank()) {
                        externalToNodeId.put(entity.getId(), node.getNodeId());
                    }

                    if (parentDoc.isPresent()) {
                        recordEntityMention(parentDoc.get(),
                                entityTitle,
                                entityType,
                                confidence,
                                factSheetId,
                                "graph_constructor",
                                sourcePath,
                                sourceDocumentId);
                        String description = semanticRelationDescription(
                                "Document contains " + entityType + " " + entityTitle,
                                containsLabel);
                        String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                                "graph_constructor", sourcePath, externalId, containsLabel, description,
                                confidence,
                                metadataProperties(
                                        "entityType", entity.getType(),
                                        "entityName", entityTitle,
                                        "sourceDocumentId", sourceDocumentId));
                        knowledgeGraphService.createEdgeWithMetadata(parentDoc.get().getNodeId(), node.getNodeId(),
                                EdgeType.CONTAINS, 1.0, containsLabel, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                    }
                } catch (Exception e) {
                    log.debug("[Job {}] Failed to persist GraphConstructor entity '{}': {}",
                            jobId, entity.getTitle(), e.getMessage());
                }
            }
        }

        if (graph.getRelationships() != null) {
            for (Relationship rel : graph.getRelationships()) {
                if (isCancelled(job)) {
                    return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
                }
                if (rel == null || belowMinConfidence(rel.getConfidence(), config)) {
                    continue;
                }
                try {
                    String srcNodeId = externalToNodeId.get(rel.getSource());
                    if (srcNodeId == null) {
                        srcNodeId = resolveGraphConstructorNodeId(rel.getSource(), factSheetId);
                        if (srcNodeId != null) {
                            externalToNodeId.put(rel.getSource(), srcNodeId);
                        }
                    }
                    String tgtNodeId = externalToNodeId.get(rel.getTarget());
                    if (tgtNodeId == null) {
                        tgtNodeId = resolveGraphConstructorNodeId(rel.getTarget(), factSheetId);
                        if (tgtNodeId != null) {
                            externalToNodeId.put(rel.getTarget(), tgtNodeId);
                        }
                    }
                    if (srcNodeId == null || tgtNodeId == null) {
                        continue;
                    }

                    RetrievedDoc sourceDoc = docsById.get(sourceDocumentId(rel.getMetadata()));
                    String sourcePath = graphRelationshipSourcePath(rel, sourceDoc, batchDocs);
                    String label = semanticRelationLabel(rel.getType());
                    String description = semanticRelationDescription(rel.getDescription(), label);
                    Double confidence = finiteDouble(rel.getConfidence());
                    Double weight = finiteDouble(rel.getWeight());
                    Map<String, Object> relMeta = new LinkedHashMap<>();
                    if (rel.getMetadata() != null) {
                        relMeta.putAll(rel.getMetadata());
                    }
                    relMeta.put("relationshipType", label);
                    relMeta.put("extractionMethod", "graph_constructor");
                    if (sourcePath != null) {
                        relMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    }
                    if (weight != null) {
                        relMeta.put("weight", weight);
                    }

                    String metaJson = semanticRelationMetadataJson(jobId, sourcePath,
                            "graph_constructor", rel.getSource(), rel.getTarget(), label, description,
                            confidence, relMeta);
                    knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId,
                            EdgeType.USER_DEFINED,
                            confidence != null ? confidence : weight != null ? weight : 1.0,
                            label, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                    relationshipsPersisted++;
                    job.incrementRelationshipType(label);
                } catch (Exception e) {
                    log.debug("[Job {}] Failed to persist GraphConstructor relation '{}': {}",
                            jobId, rel.getType(), e.getMessage());
                }
            }
        }

        return new GraphPersistResult(entitiesPersisted, relationshipsPersisted);
    }

    // -------------------------------------------------------------------------
    // Document node creation
    // -------------------------------------------------------------------------

    Optional<GraphNode> findOrCreateDocumentNode(UnifiedCrawlJob job,
                                                 String sourcePath,
                                                 RetrievedDoc sourceDoc,
                                                 Long factSheetId) {
        if (knowledgeGraphService == null || sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }
        Optional<GraphNode> existing = knowledgeGraphService.getNodeByExternalId(
                sourcePath, NodeLevel.DOCUMENT, factSheetId);
        if (existing.isPresent()) {
            return existing;
        }

        String jobId = job.getJobId();
        Map<String, Object> metadata = sourceDoc != null && sourceDoc.getMetadata() != null
                ? new LinkedHashMap<>(sourceDoc.getMetadata())
                : new LinkedHashMap<>();
        metadata.putIfAbsent("jobId", jobId);
        metadata.putIfAbsent(GraphConstants.META_SOURCE, sourcePath);
        metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, sourcePath);
        String sourceType = firstNonBlank(
                documentTracker.stringMeta(metadata, CrawlDocumentTracker.META_KEYS_SOURCE_TYPE),
                "FILE");
        String fileName = documentTracker.documentFileName(metadata, sourcePath);
        String preview = sourceDoc != null && sourceDoc.getText() != null
                ? sourceDoc.getText().substring(0, Math.min(200, sourceDoc.getText().length()))
                : null;
        try {
            GraphNode node = knowledgeGraphService.addDocument(
                    "crawl:" + jobId, jobId, sourceType, sourcePath, fileName, preview, metadata, factSheetId);
            return Optional.ofNullable(node);
        } catch (Exception e) {
            log.debug("[Job {}] Failed to create DOCUMENT node for GraphConstructor source '{}': {}",
                    jobId, sourcePath, e.getMessage());
            return knowledgeGraphService.getNodeByExternalId(sourcePath, NodeLevel.DOCUMENT, factSheetId);
        }
    }

    String resolveGraphConstructorNodeId(String externalId, Long factSheetId) {
        if (externalId == null || externalId.isBlank() || knowledgeGraphService == null) {
            return null;
        }
        return knowledgeGraphService.getNodeByExternalId(externalId, NodeLevel.ENTITY, factSheetId)
                .map(GraphNode::getNodeId)
                .orElse(null);
    }

    String graphConstructorEntityExternalId(Entity entity, String sourcePath) {
        String entityId = entity != null ? entity.getId() : null;
        if (entityId != null && !entityId.isBlank()) {
            return entityId;
        }
        String title = entity != null ? firstNonBlank(entity.getTitle(), entity.getDescription(), "entity") : "entity";
        String type = entity != null ? safeEntityType(entity.getType()) : "entity";
        return "graph-constructor:" + type + ":" + Integer.toHexString(Objects.hash(sourcePath, title, type));
    }

    // -------------------------------------------------------------------------
    // Entity source document / path resolution
    // -------------------------------------------------------------------------

    RetrievedDoc graphEntitySourceDoc(Entity entity,
                                      Map<String, RetrievedDoc> docsById,
                                      Collection<RetrievedDoc> batchDocs) {
        if (entity != null) {
            String sourceDocId = sourceDocumentId(entity.getMetadata());
            if (sourceDocId != null && docsById != null && docsById.containsKey(sourceDocId)) {
                return docsById.get(sourceDocId);
            }
            if (entity.getTextUnits() != null && docsById != null) {
                for (String textUnit : entity.getTextUnits()) {
                    if (textUnit != null && docsById.containsKey(textUnit)) {
                        return docsById.get(textUnit);
                    }
                }
            }
        }
        if (batchDocs != null && batchDocs.size() == 1) {
            return batchDocs.iterator().next();
        }
        return null;
    }

    String graphEntitySourcePath(Entity entity,
                                 RetrievedDoc sourceDoc,
                                 Collection<RetrievedDoc> batchDocs) {
        String sourcePath = entity != null
                ? documentTracker.stringMeta(entity.getMetadata(), CrawlDocumentTracker.META_KEYS_GRAPH_SOURCE_PATH)
                : null;
        if ((sourcePath == null || sourcePath.isBlank()) && sourceDoc != null) {
            sourcePath = documentTracker.documentSourcePath(sourceDoc.getMetadata(), sourceDoc.getId());
        }
        if ((sourcePath == null || sourcePath.isBlank()) && batchDocs != null && batchDocs.size() == 1) {
            RetrievedDoc onlyDoc = batchDocs.iterator().next();
            sourcePath = documentTracker.documentSourcePath(onlyDoc.getMetadata(), onlyDoc.getId());
        }
        return sourcePath;
    }

    String graphRelationshipSourcePath(Relationship rel,
                                       RetrievedDoc sourceDoc,
                                       Collection<RetrievedDoc> batchDocs) {
        String sourcePath = rel != null
                ? documentTracker.stringMeta(rel.getMetadata(), CrawlDocumentTracker.META_KEYS_GRAPH_SOURCE_PATH)
                : null;
        if ((sourcePath == null || sourcePath.isBlank()) && sourceDoc != null) {
            sourcePath = documentTracker.documentSourcePath(sourceDoc.getMetadata(), sourceDoc.getId());
        }
        if ((sourcePath == null || sourcePath.isBlank()) && batchDocs != null && batchDocs.size() == 1) {
            RetrievedDoc onlyDoc = batchDocs.iterator().next();
            sourcePath = documentTracker.documentSourcePath(onlyDoc.getMetadata(), onlyDoc.getId());
        }
        return sourcePath;
    }

    String sourceDocumentId(Map<String, Object> metadata) {
        return documentTracker.sourceDocumentId(metadata);
    }

    // -------------------------------------------------------------------------
    // Semantic relation label / description / metadata
    // -------------------------------------------------------------------------

    String semanticRelationLabel(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "RELATED_TO";
        }
        return labelCache.computeIfAbsent(relationType, rt -> {
            String normalized = rt.trim()
                    .replaceAll("[^A-Za-z0-9]+", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
            return normalized.isBlank() ? "RELATED_TO" : normalized.toUpperCase(Locale.ROOT);
        });
    }

    String semanticRelationDescription(String description, String label) {
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return label != null && !label.isBlank() ? label : "RELATED_TO";
    }

    String semanticRelationMetadataJson(String jobId,
                                        String sourcePath,
                                        String extractionMethod,
                                        String sourceEntityId,
                                        String targetEntityId,
                                        String label,
                                        String description,
                                        Double confidence,
                                        Map<?, ?> properties) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (properties != null && !properties.isEmpty()) {
            // Single-pass: build propertyCopy and merge into metadata at the same time
            Map<String, Object> propertyCopy = new LinkedHashMap<>();
            properties.forEach((key, value) -> {
                if (key != null && value != null) {
                    String k = String.valueOf(key);
                    propertyCopy.put(k, value);
                    metadata.put(k, value);
                }
            });
            metadata.put("properties", propertyCopy);
        }
        metadata.putIfAbsent("semanticType", label);
        metadata.putIfAbsent("relationshipType", label);
        metadata.putIfAbsent("semanticContext", description);
        metadata.putIfAbsent("sourceEntityId", sourceEntityId);
        metadata.putIfAbsent("targetEntityId", targetEntityId);
        metadata.putIfAbsent("extractionMethod", extractionMethod);
        if (jobId != null) {
            metadata.putIfAbsent("jobId", jobId);
        }
        if (sourcePath != null) {
            metadata.putIfAbsent(GraphConstants.META_SOURCE_PATH, sourcePath);
        }
        if (confidence != null) {
            metadata.putIfAbsent("confidence", confidence);
        }
        try {
            return EDGE_METADATA_MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{\"semanticType\":\"" + label + "\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Entity mention recording
    // -------------------------------------------------------------------------

    void recordEntityMention(GraphNode documentNode,
                             String entityName,
                             String entityType,
                             Double confidence,
                             Long factSheetId,
                             String extractionMethod,
                             String sourcePath,
                             String sourceDocumentId) {
        if (entityMentionRepository == null || documentNode == null || entityName == null || entityName.isBlank()) {
            return;
        }
        String normalizedName = normalizeEntityMentionName(entityName);
        if (normalizedName.isBlank()) {
            return;
        }
        try {
            EntityMention mention = entityMentionRepository
                    .findByNodeAndEntityNameAndFactSheet(documentNode, normalizedName, factSheetId)
                    .orElseGet(() -> EntityMention.builder()
                            .node(documentNode)
                            .entityName(normalizedName)
                            .entityType(entityType.toUpperCase(Locale.ROOT))
                            .mentionCount(0)
                            .confidence(confidence != null ? confidence : 1.0)
                            .factSheetId(factSheetId)
                            .build());
            mention.setMentionCount((mention.getMentionCount() != null ? mention.getMentionCount() : 0) + 1);
            if (confidence != null) {
                mention.setConfidence(mention.getConfidence() != null
                        ? Math.max(mention.getConfidence(), confidence)
                        : confidence);
            }
            mention.setContextJson(entityMentionContextJson(entityName, extractionMethod, sourcePath, sourceDocumentId));
            entityMentionRepository.save(mention);
        } catch (Exception e) {
            log.debug("Failed to persist entity mention '{}' for document node {}: {}",
                    entityName, documentNode.getNodeId(), e.getMessage());
        }
    }

    String normalizeEntityMentionName(String name) {
        String normalized = ENTITY_SUFFIX_PATTERN.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    String entityMentionContextJson(String entityName,
                                    String extractionMethod,
                                    String sourcePath,
                                    String sourceDocumentId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entityName", entityName);
        if (extractionMethod != null) context.put("extractionMethod", extractionMethod);
        if (sourcePath != null) context.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        if (sourceDocumentId != null) context.put("sourceDocumentId", sourceDocumentId);
        try {
            return EDGE_METADATA_MAPPER.writeValueAsString(List.of(context));
        } catch (Exception e) {
            return "[]";
        }
    }

    // -------------------------------------------------------------------------
    // Small scalar utilities
    // -------------------------------------------------------------------------

    boolean belowMinConfidence(Double confidence, GraphExtractionConfig config) {
        Double value = finiteDouble(confidence);
        return value != null && config != null && value < config.getMinConfidence();
    }

    Double finiteDouble(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    Double numberAsDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    Map<String, Object> metadataProperties(Object... keyValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (keyValues == null) {
            return properties;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                properties.put(String.valueOf(key), value);
            }
        }
        return properties;
    }

    double entityResolutionSimilarityThreshold(GraphExtractionConfig config) {
        if (config == null || config.getEntityResolutionSimilarityThreshold() <= 0.0) {
            return 0.85;
        }
        return Math.max(0.0, Math.min(1.0, config.getEntityResolutionSimilarityThreshold()));
    }

    String safeEntityType(String type) {
        return type != null && !type.isBlank() ? type : "entity";
    }

    // -------------------------------------------------------------------------
    // Internal helpers (inlined from orchestrator private methods)
    // -------------------------------------------------------------------------

    /**
     * Returns true if the job has been cancelled, marking its completion timestamp.
     */
    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    /**
     * Extracts the fact-sheet ID from the job's request, or null if not present.
     */
    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    // -------------------------------------------------------------------------
    // GraphPersistResult record
    // -------------------------------------------------------------------------

    /**
     * Result of persisting a batch of graph entities and relationships.
     */
    record GraphPersistResult(int entities, int relationships) {
        private static final GraphPersistResult EMPTY = new GraphPersistResult(0, 0);

        static GraphPersistResult empty() {
            return EMPTY;
        }
    }
}
