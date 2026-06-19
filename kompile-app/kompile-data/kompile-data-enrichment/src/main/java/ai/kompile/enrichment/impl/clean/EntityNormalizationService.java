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
package ai.kompile.enrichment.impl.clean;

import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Normalizes entity titles, deduplicates aliases, and ensures entity_type is present.
 */
@Service
public class EntityNormalizationService {
    private static final Logger log = LoggerFactory.getLogger(EntityNormalizationService.class);
    private static final Set<String> TITLE_CASE_TYPES = Set.of("PERSON", "ORGANIZATION", "COMPANY", "LOCATION");

    private final KnowledgeGraphService knowledgeGraphService;
    private final EnrichmentAuditService auditService;
    private final ObjectMapper objectMapper;

    public EntityNormalizationService(KnowledgeGraphService knowledgeGraphService,
                                      EnrichmentAuditService auditService,
                                      ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public int normalize(Long factSheetId, String jobId, EnrichmentConfig config) {
        // Use vector store (SSOT) to enumerate ENTITY nodes
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        int normalized = 0;
        for (GraphNode entity : entities) {
            boolean changed = false;
            String beforeTitle = entity.getTitle();
            String beforeMeta = entity.getMetadataJson();

            // Title normalization
            if (entity.getTitle() != null) {
                String cleaned = entity.getTitle().trim().replaceAll("[\\p{Cntrl}&&[^\t\n\r]]", "");
                String entityType = extractEntityType(entity);
                if (entityType != null && TITLE_CASE_TYPES.contains(entityType.toUpperCase())) {
                    cleaned = toTitleCase(cleaned);
                }
                if (!cleaned.equals(entity.getTitle())) {
                    entity.setTitle(cleaned);
                    changed = true;
                }
            }

            // Metadata normalization
            if (entity.getMetadataJson() != null) {
                try {
                    ObjectNode meta = (ObjectNode) objectMapper.readTree(entity.getMetadataJson());
                    boolean metaChanged = false;

                    // Alias dedup
                    JsonNode aliasesNode = meta.path("aliases");
                    if (aliasesNode.isArray() && aliasesNode.size() > 0) {
                        List<String> uniqueAliases = new ArrayList<>();
                        Set<String> seen = new HashSet<>();
                        for (JsonNode alias : aliasesNode) {
                            String normalized2 = alias.asText().trim().toLowerCase();
                            if (!normalized2.isEmpty() && seen.add(normalized2)) {
                                uniqueAliases.add(alias.asText().trim());
                            }
                        }
                        if (uniqueAliases.size() != aliasesNode.size()) {
                            ArrayNode newAliases = meta.putArray("aliases");
                            uniqueAliases.forEach(newAliases::add);
                            metaChanged = true;
                        }
                    }

                    // Ensure entity_type is present — prefer the canonical resolved type (e.g. promote a
                    // known entity_category) and only fall back to keyword inference when nothing is known.
                    if (!meta.has("entity_type") || meta.get("entity_type").asText().isBlank()) {
                        String resolvedType = extractEntityType(entity);
                        if (resolvedType == null) {
                            resolvedType = inferEntityType(entity);
                        }
                        if (resolvedType != null) {
                            meta.put("entity_type", resolvedType);
                            metaChanged = true;
                        }
                    }

                    if (metaChanged) {
                        entity.setMetadataJson(objectMapper.writeValueAsString(meta));
                        changed = true;
                    }
                } catch (Exception e) {
                    log.debug("Could not parse metadataJson for node {}: {}", entity.getNodeId(), e.getMessage());
                }
            }

            if (changed) {
                // Persist via vector store (SSOT): parse updated metadataJson back to Map
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metaMap = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                            ? objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                            : new java.util.LinkedHashMap<>();
                    knowledgeGraphService.updateNode(entity.getNodeId(), entity.getTitle(), entity.getDescription(), metaMap);
                } catch (Exception saveEx) {
                    log.warn("Failed to persist normalized entity {} to vector store: {}", entity.getNodeId(), saveEx.getMessage());
                }
                auditService.logAction(factSheetId, jobId, "CLEAN", "NORMALIZE_ENTITY",
                        entity.getNodeId(), "GRAPH_NODE",
                        String.format("{\"title\":\"%s\",\"metadataJson\":%s}",
                                escapeJson(beforeTitle != null ? beforeTitle : ""),
                                beforeMeta != null ? beforeMeta : "null"),
                        String.format("{\"title\":\"%s\",\"metadataJson\":%s}",
                                escapeJson(entity.getTitle()),
                                entity.getMetadataJson() != null ? entity.getMetadataJson() : "null"),
                        String.format("Normalized entity '%s'", entity.getTitle()));
                normalized++;
            }
        }
        log.info("Normalized {} entities for factSheet {}", normalized, factSheetId);
        return normalized;
    }

    private String extractEntityType(GraphNode node) {
        // Canonical resolution (entity_category -> entity_type -> entity_subtype), shared with the
        // ontology-conformance and compaction paths so every consumer agrees on a node's semantic type.
        // Previously this read only entity_type and so missed types stored under entity_category.
        return GraphNodeTypes.resolveEntityType(node.getMetadata(), null);
    }

    private String inferEntityType(GraphNode node) {
        // Simple heuristic from description keywords
        String desc = node.getDescription() != null ? node.getDescription().toLowerCase() : "";
        String title = node.getTitle() != null ? node.getTitle().toLowerCase() : "";
        String combined = desc + " " + title;

        if (combined.contains("person") || combined.contains("employee") || combined.contains("manager")) {
            return "PERSON";
        }
        if (combined.contains("company") || combined.contains("corporation") || combined.contains("organization")) {
            return "ORGANIZATION";
        }
        if (combined.contains("document") || combined.contains("report") || combined.contains("file")) {
            return "DOCUMENT";
        }
        return null;
    }

    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c) || c == '-' || c == '\'') {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
