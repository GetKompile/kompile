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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.ProvenanceCheck;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates that the source documents referenced in entity-node metadata still
 * exist in the graph and have not been marked stale.
 *
 * <p>For each ENTITY node in the fact sheet the validator:</p>
 * <ol>
 *   <li>Parses the entity's {@code metadataJson} for a {@code sourceDocumentId}
 *       field (several common key names are checked).</li>
 *   <li>Looks up each referenced document in the DOCUMENT nodes of the same
 *       fact sheet.</li>
 *   <li>Reports whether the referenced document is valid (present and not stale)
 *       or invalid (absent or stale).</li>
 *   <li>Sets {@code allSourcesInvalid = true} when every referenced document
 *       is invalid, flagging the entity as potentially unsupported.</li>
 * </ol>
 */
@Slf4j
@Component
public class ProvenanceValidator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Metadata key names used to store source document references. */
    private static final List<String> SOURCE_DOC_KEYS = List.of(
            "sourceDocumentId", "source_document_id",
            "sourceDocId", "source_doc_id",
            "documentId", "document_id"
    );

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final ObjectMapper objectMapper;

    public ProvenanceValidator(GraphNodeRepository nodeRepository,
                               GraphEdgeRepository edgeRepository,
                               ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Validate the provenance of all entity nodes in the given fact sheet.
     *
     * @param factSheetId the fact sheet to validate
     * @return a list of {@link ProvenanceCheck} records, one per entity that
     *         has at least one source document reference in its metadata
     */
    public List<ProvenanceCheck> validate(Long factSheetId) {
        // Load all ENTITY nodes for the fact sheet
        List<GraphNode> entities = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
        log.debug("ProvenanceValidator: checking {} entities for factSheet={}", entities.size(), factSheetId);

        // Load all DOCUMENT nodes for fast lookup (use externalId as key since that is the natural doc ID)
        List<GraphNode> allDocuments = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.DOCUMENT);
        Map<String, GraphNode> docByExternalId = new HashMap<>();
        Map<String, GraphNode> docByNodeId = new HashMap<>();
        for (GraphNode doc : allDocuments) {
            if (doc.getExternalId() != null) docByExternalId.put(doc.getExternalId(), doc);
            if (doc.getNodeId() != null) docByNodeId.put(doc.getNodeId(), doc);
        }

        List<ProvenanceCheck> results = new ArrayList<>();

        for (GraphNode entity : entities) {
            List<String> referencedDocIds = extractSourceDocIds(entity);
            if (referencedDocIds.isEmpty()) {
                // No provenance references — skip (not an error, just no metadata)
                continue;
            }

            int validSources = 0;
            int invalidSources = 0;

            for (String docId : referencedDocIds) {
                // Try both externalId and nodeId lookups
                GraphNode doc = docByExternalId.get(docId);
                if (doc == null) {
                    doc = docByNodeId.get(docId);
                }

                if (doc == null) {
                    invalidSources++;
                    log.debug("ProvenanceValidator: entity {} references missing document {}",
                            entity.getNodeId(), docId);
                } else if (Boolean.TRUE.equals(doc.getStale())) {
                    invalidSources++;
                    log.debug("ProvenanceValidator: entity {} references stale document {}",
                            entity.getNodeId(), docId);
                } else {
                    validSources++;
                }
            }

            boolean allInvalid = invalidSources > 0 && validSources == 0;
            if (allInvalid) {
                log.warn("ProvenanceValidator: entity {} has all-invalid sources for factSheet={}",
                        entity.getNodeId(), factSheetId);
            }

            results.add(new ProvenanceCheck(
                    entity.getNodeId(),
                    referencedDocIds,
                    validSources,
                    invalidSources,
                    allInvalid
            ));
        }

        long orphaned = results.stream().filter(ProvenanceCheck::allSourcesInvalid).count();
        log.info("ProvenanceValidator: checked {} entities, {} have all-invalid sources for factSheet={}",
                results.size(), orphaned, factSheetId);

        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract source document ID references from the entity's metadataJson.
     * Returns an empty list if no references are found or parsing fails.
     */
    private List<String> extractSourceDocIds(GraphNode entity) {
        if (entity.getMetadataJson() == null || entity.getMetadataJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> meta = objectMapper.readValue(entity.getMetadataJson(), MAP_TYPE);

            // Single-value keys
            for (String key : SOURCE_DOC_KEYS) {
                Object val = meta.get(key);
                if (val == null) continue;
                if (val instanceof List<?> list) {
                    List<String> ids = new ArrayList<>();
                    for (Object item : list) {
                        if (item != null) ids.add(item.toString());
                    }
                    if (!ids.isEmpty()) return ids;
                }
                String strVal = val.toString().trim();
                if (!strVal.isBlank()) {
                    return List.of(strVal);
                }
            }

            // Array variant: sourceDocumentIds
            for (String key : List.of("sourceDocumentIds", "source_document_ids", "sourceDocIds")) {
                Object val = meta.get(key);
                if (val instanceof List<?> list) {
                    List<String> ids = new ArrayList<>();
                    for (Object item : list) {
                        if (item != null) ids.add(item.toString());
                    }
                    if (!ids.isEmpty()) return ids;
                }
            }
        } catch (Exception e) {
            log.warn("ProvenanceValidator: could not parse metadataJson for entity {}: {}",
                    entity.getNodeId(), e.getMessage());
        }
        return List.of();
    }
}
