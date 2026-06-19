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
package ai.kompile.enrichment.impl.organize;

import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.EntityCategory;
import ai.kompile.enrichment.domain.TaxonomyNode;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Batch-writes taxonomyCategory/taxonomyDomain to entity metadataJson.
 */
@Service
public class EntityCategorizationService {
    private static final Logger log = LoggerFactory.getLogger(EntityCategorizationService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final EntityCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public EntityCategorizationService(KnowledgeGraphService knowledgeGraphService,
                                       EntityCategoryRepository categoryRepository,
                                       ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.categoryRepository = categoryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Labels all ENTITY nodes with taxonomy category/domain from the category tree.
     * Returns count of entities categorized.
     */
    public int categorizeEntities(Long factSheetId, DomainTaxonomy taxonomy) {
        // Build reverse index: entity_type → (domain, category)
        Map<String, String[]> typeToCategory = buildTypeToCategory(factSheetId, taxonomy);

        // Use vector store (SSOT) to enumerate ENTITY nodes
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        int categorized = 0;
        // batch collects (nodeId, title, description, metaMap) to flush via vector store
        List<GraphNode> pendingUpdates = new ArrayList<>();

        for (GraphNode entity : entities) {
            String entityType = extractEntityType(entity);
            String[] domainCategory = entityType != null ? typeToCategory.get(entityType) : null;

            String domain = domainCategory != null ? domainCategory[0] : "Uncategorized";
            String category = domainCategory != null ? domainCategory[1] : "Uncategorized";

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metaMap = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                        ? objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                        : new java.util.LinkedHashMap<>();

                String existingDomain = metaMap.get("taxonomyDomain") instanceof String s ? s : null;
                String existingCategory = metaMap.get("taxonomyCategory") instanceof String s ? s : null;

                if (!domain.equals(existingDomain) || !category.equals(existingCategory)) {
                    metaMap.put("taxonomyDomain", domain);
                    metaMap.put("taxonomyCategory", category);
                    entity.setMetadataJson(objectMapper.writeValueAsString(metaMap));
                    pendingUpdates.add(entity);
                    categorized++;
                }
            } catch (Exception e) {
                log.debug("Failed to update metadataJson for entity {}: {}", entity.getNodeId(), e.getMessage());
            }

            // Flush to vector store in batches of 100
            if (pendingUpdates.size() >= 100) {
                flushToVectorStore(pendingUpdates);
                pendingUpdates.clear();
            }
        }

        if (!pendingUpdates.isEmpty()) {
            flushToVectorStore(pendingUpdates);
        }

        log.info("Categorized {} entities for factSheet {}", categorized, factSheetId);
        return categorized;
    }

    private Map<String, String[]> buildTypeToCategory(Long factSheetId, DomainTaxonomy taxonomy) {
        Map<String, String[]> result = new HashMap<>();

        // From taxonomy JSON
        if (taxonomy != null && taxonomy.getTaxonomyJson() != null) {
            try {
                List<TaxonomyNode> nodes = objectMapper.readValue(
                        taxonomy.getTaxonomyJson(), new TypeReference<List<TaxonomyNode>>() {});

                Map<String, TaxonomyNode> nodeMap = new HashMap<>();
                for (TaxonomyNode node : nodes) {
                    nodeMap.put(node.getId(), node);
                }

                for (TaxonomyNode node : nodes) {
                    if (node.getEntityTypes() != null) {
                        // Walk up to find domain
                        String categoryLabel = node.getLabel();
                        String domainLabel = findDomainLabel(node, nodeMap);
                        for (String entityType : node.getEntityTypes()) {
                            result.put(entityType, new String[]{domainLabel, categoryLabel});
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse taxonomy JSON: {}", e.getMessage());
            }
        }

        // From EntityCategory records (user-defined override auto-discovered)
        List<EntityCategory> categories = categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId);
        for (EntityCategory cat : categories) {
            if (cat.getMetadataJson() != null) {
                try {
                    JsonNode meta = objectMapper.readTree(cat.getMetadataJson());
                    JsonNode entityTypes = meta.path("entityTypes");
                    if (entityTypes.isArray()) {
                        String domain = findParentDomain(cat, categories);
                        for (JsonNode et : entityTypes) {
                            result.put(et.asText(), new String[]{domain, cat.getLabel()});
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse entityTypes from category {} metadata: {}", cat.getCategoryId(), e.getMessage());
                }
            }
        }

        return result;
    }

    private String findDomainLabel(TaxonomyNode node, Map<String, TaxonomyNode> nodeMap) {
        TaxonomyNode current = node;
        while (current.getParentId() != null) {
            current = nodeMap.get(current.getParentId());
            if (current == null) break;
        }
        return current != null ? current.getLabel() : "General";
    }

    private String findParentDomain(EntityCategory category, List<EntityCategory> all) {
        if (category.getParentCategoryId() == null) return category.getLabel();
        for (EntityCategory parent : all) {
            if (parent.getCategoryId().equals(category.getParentCategoryId())) {
                return findParentDomain(parent, all);
            }
        }
        return category.getLabel();
    }

    /** Persists a batch of updated entities to the vector store (SSOT). */
    private void flushToVectorStore(List<GraphNode> nodes) {
        for (GraphNode entity : nodes) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metaMap = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                        ? objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                        : new java.util.LinkedHashMap<>();
                knowledgeGraphService.updateNode(entity.getNodeId(), entity.getTitle(), entity.getDescription(), metaMap);
            } catch (Exception e) {
                log.warn("Failed to flush entity {} to vector store: {}", entity.getNodeId(), e.getMessage());
            }
        }
    }

    private String extractEntityType(GraphNode node) {
        if (node.getMetadataJson() == null) return null;
        try {
            JsonNode meta = objectMapper.readTree(node.getMetadataJson());
            JsonNode typeNode = meta.path("entity_type");
            return typeNode.isMissingNode() || typeNode.isNull() ? null : typeNode.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
