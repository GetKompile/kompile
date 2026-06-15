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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies document-type-specific graph extraction using registered
 * {@link ai.kompile.core.graphrag.DocumentGraphExtractor} implementations
 * (PDF, Office, Tika, etc.) — rule-based, no LLM.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.</p>
 */
@Component
class RuleBasedDocumentGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedDocumentGraphExtractor.class);

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private List<ai.kompile.core.graphrag.DocumentGraphExtractor> documentGraphExtractors;

    @Autowired
    private GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    private CrawlDocumentTracker documentTracker;

    /**
     * Applies document-type-specific graph extraction using registered DocumentGraphExtractor
     * implementations (PDF, Office, Tika, etc.) — rule-based, no LLM.
     *
     * @param job       the crawl job (passed directly instead of looked up by ID)
     * @param documents the documents to extract graph entities and relationships from
     */
    void applyDocumentGraphExtraction(UnifiedCrawlJob job, List<Document> documents) {
        if (knowledgeGraphService == null || documents == null
                || documentGraphExtractors == null || documentGraphExtractors.isEmpty()) return;

        if (job != null && isCancelled(job)) {
            return;
        }
        String jobId = job != null ? job.getJobId() : null;

        List<Document> documentSnapshot = new ArrayList<>(documents);
        List<ai.kompile.core.graphrag.DocumentGraphExtractor> extractorSnapshot =
                new ArrayList<>(documentGraphExtractors);
        int entitiesCreated = 0;
        int relationsCreated = 0;
        int entitiesExtracted = 0;
        int relationsExtracted = 0;

        // Hoist per-job constants outside the document loop
        Long factSheetId = jobFactSheetId(job);
        String crawlSource = "crawl:" + jobId;
        // Cross-document cache: same sourcePath → same DOCUMENT node
        Map<String, Optional<GraphNode>> docNodeCache = new HashMap<>();
        // Cross-document cache: same entity ID → same ENTITY node (avoids N+1 DB lookups)
        Map<String, Optional<GraphNode>> entityNodeCache = new HashMap<>();
        // Pre-compute the CONTAINS label — constant for every document in this batch
        String containsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);

        for (Document doc : documentSnapshot) {
            if (job != null && isCancelled(job)) {
                return;
            }
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            documentTracker.recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "RUNNING", 0, 0, 0,
                    "Finding matching deterministic graph extractors", null, null, false);
            // For non-Gmail emails, applyEmailGraphExtraction already creates
            // EMAIL_MESSAGE, PERSON, and ATTACHMENT entities. We still run
            // EmailGraphExtractor here to capture additional entity types
            // (CONVERSATION_TOPIC, URL, CALENDAR_EVENT, MAILING_LIST, etc.)
            // that the inline extraction doesn't produce, but we filter out
            // the types already handled to avoid duplicate nodes.
            boolean isNonGmailEmail = meta.get("email.from") != null && meta.get("gmail.from") == null;

            List<ai.kompile.core.graphrag.DocumentGraphExtractor> matchingExtractors = new ArrayList<>();
            for (ai.kompile.core.graphrag.DocumentGraphExtractor candidate : extractorSnapshot) {
                if (candidate.canExtract(doc)) {
                    matchingExtractors.add(candidate);
                }
            }
            List<String> extractorNames = matchingExtractors.stream()
                    .map(extractor -> extractor.getClass().getSimpleName())
                    .collect(Collectors.toList());
            if (matchingExtractors.isEmpty()) {
                String docType = meta.get(GraphConstants.META_DOCUMENT_TYPE) instanceof String ? (String) meta.get(GraphConstants.META_DOCUMENT_TYPE) : "unknown";
                String fName = meta.get(GraphConstants.META_FILE_NAME) instanceof String ? (String) meta.get(GraphConstants.META_FILE_NAME) : "unknown";
                log.debug("[Job {}] No graph extractor matched document: documentType={}, fileName={}", jobId, docType, fName);
                // Even when no extractor matches, ensure a DOCUMENT node exists so every
                // crawled file has graph presence (entities may be added later via re-extraction)
                String noMatchSourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                // Fallback to file_name when source_path/source are absent
                if (noMatchSourcePath == null) {
                    String fbName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : null;
                    if (fbName != null) noMatchSourcePath = "unnamed:" + fbName;
                }
                if (noMatchSourcePath != null && knowledgeGraphService != null) {
                    var existingDoc = docNodeCache.computeIfAbsent(noMatchSourcePath, sp ->
                            knowledgeGraphService.getNodeByExternalId(sp, NodeLevel.DOCUMENT, factSheetId));
                    if (existingDoc.isEmpty()) {
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : noMatchSourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", jobId);
                        GraphNode created = knowledgeGraphService.addDocument(
                                crawlSource, jobId, sourceType, noMatchSourcePath, fileName, null, docMeta, factSheetId);
                        if (created != null) docNodeCache.put(noMatchSourcePath, Optional.of(created));
                        log.debug("[Job {}] Created fallback DOCUMENT node for unmatched document: {}", jobId, noMatchSourcePath);
                    }
                }
                documentTracker.recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "SKIPPED", 0, 0, 0,
                        "No deterministic graph extractor matched", null, null, false);
                continue;
            }

            try {
                // Merge results from all matching extractors
                List<GraphExtractionSchema.ExtractedEntity> allEntities = new ArrayList<>();
                List<GraphExtractionSchema.ExtractedRelation> allRelations = new ArrayList<>();
                for (ai.kompile.core.graphrag.DocumentGraphExtractor extractor : matchingExtractors) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    GraphExtractionSchema.ExtractionResult partial = extractor.extract(doc);
                    allEntities.addAll(partial.entities());
                    allRelations.addAll(partial.relations());
                }
                if (job != null && isCancelled(job)) {
                    return;
                }
                // For non-Gmail emails, filter out entity types already created by
                // applyEmailGraphExtraction to avoid duplicate nodes with different IDs
                if (isNonGmailEmail) {
                    Set<String> inlineHandledTypes = Set.of("EMAIL_MESSAGE", "PERSON", "ATTACHMENT");
                    Set<String> filteredEntityIds = new HashSet<>();
                    allEntities.removeIf(e -> {
                        if (inlineHandledTypes.contains(e.type())) {
                            filteredEntityIds.add(e.id());
                            return true;
                        }
                        return false;
                    });
                    // Remove relations whose source or target was filtered
                    allRelations.removeIf(r -> filteredEntityIds.contains(r.source())
                            || filteredEntityIds.contains(r.target()));
                }
                GraphExtractionSchema.ExtractionResult result =
                        GraphExtractionSchema.ExtractionResult.of(allEntities, allRelations, null);
                if (result.entities().isEmpty() && result.relations().isEmpty()) {
                    // Still ensure a DOCUMENT node exists even when extraction yielded nothing
                    String emptySourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                            : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                    if (emptySourcePath != null && knowledgeGraphService != null) {
                        var existingDoc = docNodeCache.computeIfAbsent(emptySourcePath, sp ->
                                knowledgeGraphService.getNodeByExternalId(sp, NodeLevel.DOCUMENT, factSheetId));
                        if (existingDoc.isEmpty()) {
                            String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                    ? (String) meta.get(GraphConstants.META_FILE_NAME) : emptySourcePath;
                            String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                    ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                            String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                    ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                            Map<String, Object> docMeta = new LinkedHashMap<>();
                            docMeta.put(GraphConstants.META_LOADER, loaderName);
                            docMeta.put("jobId", jobId);
                            GraphNode created = knowledgeGraphService.addDocument(
                                    crawlSource, jobId, sourceType, emptySourcePath, fileName, null, docMeta, factSheetId);
                            if (created != null) docNodeCache.put(emptySourcePath, Optional.of(created));
                            log.debug("[Job {}] Created DOCUMENT node for zero-entity extraction: {}", jobId, emptySourcePath);
                        }
                    }
                    documentTracker.recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "COMPLETED", 0, 0, 0,
                            "Extractors returned no entities or relationships", null, extractorNames, true);
                    continue;
                }

                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;

                String parentNodeId = null;
                if (sourcePath != null) {
                    Optional<GraphNode> docNode = docNodeCache.computeIfAbsent(sourcePath,
                            sp -> knowledgeGraphService.getNodeByExternalId(sp, NodeLevel.DOCUMENT, factSheetId));
                    if (docNode.isEmpty()) {
                        // Create DOCUMENT node if missing — prevents extracted entities
                        // from being orphaned when the DOCUMENT node hasn't been committed yet
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", jobId);
                        GraphNode created = knowledgeGraphService.addDocument(
                                crawlSource, jobId, sourceType, sourcePath, fileName, null, docMeta, factSheetId);
                        if (created != null) {
                            docNode = Optional.of(created);
                            docNodeCache.put(sourcePath, docNode);
                        }
                    }
                    if (docNode.isPresent()) {
                        parentNodeId = docNode.get().getNodeId();
                    }
                }

                Map<String, String> externalToNodeId = new HashMap<>();
                for (var entity : result.entities()) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    try {
                        String entityType = graphPersistenceHelper.safeEntityType(entity.type());
                        Map<String, Object> entityMeta = new LinkedHashMap<>();
                        entityMeta.put("entity_type", entity.type());
                        entityMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                        if (entity.properties() != null) entityMeta.putAll(entity.properties());

                        final Long eFsId = factSheetId;
                        GraphNode node;
                        Optional<GraphNode> existing = entityNodeCache.computeIfAbsent(entity.id(),
                                eid -> knowledgeGraphService.getNodeByExternalId(eid, NodeLevel.ENTITY, eFsId));
                        if (existing.isPresent()) {
                            node = existing.get();
                            // Merge properties from additional extractors into existing node
                            if (entity.properties() != null && !entity.properties().isEmpty()) {
                                try {
                                    knowledgeGraphService.updateNode(node.getNodeId(), null, null, entityMeta);
                                } catch (Exception mergeEx) {
                                    log.debug("[Job {}] Failed to merge properties into existing entity '{}': {}",
                                            jobId, entity.name(), mergeEx.getMessage());
                                }
                            }
                        } else {
                            node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                    entity.name(), entity.description(), entityMeta, factSheetId);
                            entityNodeCache.put(entity.id(), Optional.of(node));
                            entitiesCreated++;
                        }
                        externalToNodeId.put(entity.id(), node.getNodeId());
                        if (job != null) job.incrementEntityType(entityType);

                        if (parentNodeId != null) {
                            String description = graphPersistenceHelper.semanticRelationDescription(
                                    "Document contains " + entityType + " " + entity.name(), containsLabel);
                            String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                    "document_graph_extractor", sourcePath, entity.id(), containsLabel, description, null,
                                    graphPersistenceHelper.metadataProperties(
                                            "entityType", entity.type(),
                                            "entityName", entity.name()));
                            knowledgeGraphService.createEdgeWithMetadata(parentNodeId, node.getNodeId(),
                                    EdgeType.CONTAINS, 1.0, containsLabel, description, metaJson,
                                    EdgeProvenance.EXTRACTED, factSheetId);
                        }
                    } catch (Exception e) {
                        log.debug("[Job {}] Failed to persist entity '{}': {}", jobId, entity.name(), e.getMessage());
                    }
                }

                for (var rel : result.relations()) {
                    if (job != null && isCancelled(job)) {
                        return;
                    }
                    try {
                        String srcNodeId = externalToNodeId.get(rel.source());
                        String tgtNodeId = externalToNodeId.get(rel.target());
                        if (srcNodeId == null || tgtNodeId == null) continue;
                        String label = graphPersistenceHelper.semanticRelationLabel(rel.type());
                        String description = graphPersistenceHelper.semanticRelationDescription(rel.description(), label);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                "document_graph_extractor", rel.source(), rel.target(), label, description,
                                rel.confidence(), rel.properties());
                        knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId, EdgeType.USER_DEFINED,
                                rel.confidence() != null ? rel.confidence() : 1.0,
                                label, description, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                        if (job != null) job.incrementRelationshipType(label);
                    } catch (Exception e) {
                        log.debug("[Job {}] Failed to persist relation '{}': {}", jobId, rel.type(), e.getMessage());
                    }
                }
                entitiesExtracted += result.entities().size();
                relationsExtracted += result.relations().size();
                if (job == null || !isCancelled(job)) {
                    documentTracker.recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "COMPLETED", 0,
                            result.entities().size(), result.relations().size(),
                            "Deterministic graph extraction complete", null, extractorNames, true);
                }
            } catch (Exception e) {
                String errorDetail = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                log.warn("[Job {}] Document graph extraction failed: {}", jobId, errorDetail, e);
                if (job == null || !isCancelled(job)) {
                    documentTracker.recordDocumentProgress(job, doc, "DOCUMENT_GRAPH", "FAILED", 0, 0, 0,
                            "Deterministic graph extraction failed", errorDetail, extractorNames, true);
                }
            }
        }

        if ((job == null || !isCancelled(job)) && (entitiesExtracted > 0 || relationsExtracted > 0)) {
            if (job != null) {
                job.getEntitiesExtracted().addAndGet(entitiesExtracted);
                job.getRelationshipsExtracted().addAndGet(relationsExtracted);
            }
            log.info("[Job {}] Document graph extraction: {} entities, {} relations extracted ({} entities, {} relations created)",
                    jobId, entitiesExtracted, relationsExtracted, entitiesCreated, relationsCreated);
        }
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }
}
