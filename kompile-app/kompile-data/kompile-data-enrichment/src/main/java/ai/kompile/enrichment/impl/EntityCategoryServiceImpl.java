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
package ai.kompile.enrichment.impl;

import ai.kompile.enrichment.domain.*;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CRUD + mass edit for custom entity categories, merge logic for auto-discovery.
 */
@Service
public class EntityCategoryServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(EntityCategoryServiceImpl.class);

    private EntityCategoryRepository categoryRepository;
    private KnowledgeGraphService knowledgeGraphService;
    private ObjectMapper objectMapper;

    public EntityCategoryServiceImpl(EntityCategoryRepository categoryRepository,
                                     KnowledgeGraphService knowledgeGraphService,
                                     ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected EntityCategoryServiceImpl() {}


    // ── CRUD ────────────────────────────────────────────────────

    public EntityCategory create(Long factSheetId, String label, String description,
                                 String parentCategoryId, String color) {
        String categoryId = toSlug(label);
        if (categoryRepository.existsByFactSheetIdAndCategoryId(factSheetId, categoryId)) {
            categoryId = categoryId + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        EntityCategory cat = EntityCategory.builder()
                .factSheetId(factSheetId)
                .categoryId(categoryId)
                .label(label)
                .description(description)
                .parentCategoryId(parentCategoryId)
                .color(color != null ? color : generateColor(label))
                .source("USER_DEFINED")
                .build();
        return categoryRepository.save(cat);
    }

    public EntityCategory update(String categoryId, String label, String description,
                                 String parentCategoryId, String color) {
        return update(null, categoryId, label, description, parentCategoryId, color);
    }

    public EntityCategory update(Long factSheetId, String categoryId, String label, String description,
                                 String parentCategoryId, String color) {
        EntityCategory cat;
        if (factSheetId != null) {
            cat = categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, categoryId)
                    .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId + " in factSheet " + factSheetId));
        } else {
            cat = categoryRepository.findByCategoryId(categoryId)
                    .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
        }
        if (label != null) cat.setLabel(label);
        if (description != null) cat.setDescription(description);
        if (parentCategoryId != null) {
            cat.setParentCategoryId(parentCategoryId.isEmpty() ? null : parentCategoryId);
        }
        if (color != null) cat.setColor(color);
        return categoryRepository.save(cat);
    }

    @Transactional
    public void delete(String categoryId) {
        delete(null, categoryId);
    }

    @Transactional
    public void delete(Long factSheetId, String categoryId) {
        EntityCategory cat;
        if (factSheetId != null) {
            cat = categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, categoryId).orElse(null);
        } else {
            cat = categoryRepository.findByCategoryId(categoryId).orElse(null);
        }
        if (cat != null) {
            uncategorizeEntitiesInCategory(cat);
            categoryRepository.deleteByCategoryId(categoryId);
        }
    }

    public List<EntityCategory> listByFactSheet(Long factSheetId) {
        return categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId);
    }

    public List<EntityCategory> getTree(Long factSheetId) {
        List<EntityCategory> all = categoryRepository.findByFactSheetIdOrderBySortOrder(factSheetId);
        // Flat list — UI renders as tree using parentCategoryId
        return all;
    }

    public Optional<EntityCategory> getById(String categoryId) {
        return categoryRepository.findByCategoryId(categoryId);
    }

    public Optional<EntityCategory> getById(Long factSheetId, String categoryId) {
        return categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, categoryId);
    }

    // ── Entity Assignment ───────────────────────────────────────

    public void assignEntitiesToCategory(String categoryId, List<String> entityNodeIds) {
        assignEntitiesToCategory(null, categoryId, entityNodeIds);
    }

    public void assignEntitiesToCategory(Long factSheetId, String categoryId, List<String> entityNodeIds) {
        EntityCategory cat;
        if (factSheetId != null) {
            cat = categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, categoryId)
                    .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId + " in factSheet " + factSheetId));
        } else {
            cat = categoryRepository.findByCategoryId(categoryId)
                    .orElseThrow(() -> new NoSuchElementException("Category not found: " + categoryId));
        }

        String domain = findRootDomain(cat);
        for (String nodeId : entityNodeIds) {
            // Use vector store (SSOT)
            knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = node.getMetadataJson() != null && !node.getMetadataJson().isBlank()
                            ? objectMapper.readValue(node.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                            : new java.util.LinkedHashMap<>();
                    meta.put("taxonomyCategory", cat.getLabel());
                    meta.put("taxonomyDomain", domain);
                    knowledgeGraphService.updateNode(node.getNodeId(), node.getTitle(), node.getDescription(), meta);
                } catch (Exception e) {
                    log.warn("Failed to assign entity {} to category {}: {}", nodeId, categoryId, e.getMessage());
                }
            });
        }
    }

    public void removeEntitiesFromCategory(String categoryId, List<String> entityNodeIds) {
        for (String nodeId : entityNodeIds) {
            // Use vector store (SSOT)
            knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = node.getMetadataJson() != null && !node.getMetadataJson().isBlank()
                            ? objectMapper.readValue(node.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                            : new java.util.LinkedHashMap<>();
                    meta.remove("taxonomyCategory");
                    meta.remove("taxonomyDomain");
                    knowledgeGraphService.updateNode(node.getNodeId(), node.getTitle(), node.getDescription(), meta);
                } catch (Exception e) {
                    log.warn("Failed to remove entity {} from category: {}", nodeId, e.getMessage());
                }
            });
        }
    }

    public List<GraphNode> getEntitiesInCategory(String categoryId, int offset, int limit) {
        EntityCategory cat = categoryRepository.findByCategoryId(categoryId).orElse(null);
        if (cat == null) return List.of();
        // Query entities where metadataJson contains the category label (via vector store SSOT)
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(cat.getFactSheetId(), NodeLevel.ENTITY);
        return entities.stream()
                .filter(e -> {
                    try {
                        if (e.getMetadataJson() == null) return false;
                        var meta = objectMapper.readTree(e.getMetadataJson());
                        return cat.getLabel().equals(meta.path("taxonomyCategory").asText());
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public long countEntitiesInCategory(String categoryId) {
        EntityCategory cat = categoryRepository.findByCategoryId(categoryId).orElse(null);
        if (cat == null) return 0;
        // Use vector store (SSOT)
        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(cat.getFactSheetId(), NodeLevel.ENTITY);
        return entities.stream()
                .filter(e -> {
                    try {
                        if (e.getMetadataJson() == null) return false;
                        var meta = objectMapper.readTree(e.getMetadataJson());
                        return cat.getLabel().equals(meta.path("taxonomyCategory").asText());
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .count();
    }

    // ── Mass Edit ───────────────────────────────────────────────

    @Transactional
    public MassEditResult massReassign(Long factSheetId, MassReassignRequest req) {
        EntityCategory targetCat = categoryRepository.findByCategoryId(req.getTargetCategoryId())
                .orElseThrow(() -> new NoSuchElementException("Target category not found"));
        String domain = findRootDomain(targetCat);

        List<GraphNode> entities;
        if (req.getEntityNodeIds() != null && !req.getEntityNodeIds().isEmpty()) {
            // Use vector store (SSOT) for specific node lookup
            entities = req.getEntityNodeIds().stream()
                    .map(id -> knowledgeGraphService.getNode(id).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else if (req.getSourceCategoryId() != null) {
            entities = getEntitiesInCategory(req.getSourceCategoryId(), 0, Integer.MAX_VALUE);
        } else {
            // Uncategorized entities via vector store (SSOT)
            entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY).stream()
                    .filter(e -> {
                        try {
                            if (e.getMetadataJson() == null) return true;
                            var meta = objectMapper.readTree(e.getMetadataJson());
                            return !meta.has("taxonomyCategory") || meta.path("taxonomyCategory").asText().isBlank();
                        } catch (Exception ex) {
                            return true;
                        }
                    })
                    .collect(Collectors.toList());
        }

        int affected = 0;
        List<String> errors = new ArrayList<>();
        for (GraphNode entity : entities) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                        ? objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                        : new java.util.LinkedHashMap<>();
                meta.put("taxonomyCategory", targetCat.getLabel());
                meta.put("taxonomyDomain", domain);
                knowledgeGraphService.updateNode(entity.getNodeId(), entity.getTitle(), entity.getDescription(), meta);
                affected++;
            } catch (Exception e) {
                errors.add("Failed to reassign " + entity.getNodeId() + ": " + e.getMessage());
            }
        }

        return MassEditResult.builder()
                .entitiesAffected(affected)
                .categoriesAffected(1)
                .errors(errors)
                .build();
    }

    @Transactional
    public MassEditResult massUpdate(Long factSheetId, List<CategoryPatch> patches) {
        int catAffected = 0;
        List<String> errors = new ArrayList<>();
        for (CategoryPatch patch : patches) {
            try {
                update(patch.getCategoryId(), patch.getNewLabel(), patch.getNewDescription(),
                        patch.getNewParentCategoryId(), patch.getNewColor());
                catAffected++;
            } catch (Exception e) {
                errors.add("Failed to update " + patch.getCategoryId() + ": " + e.getMessage());
            }
        }
        return MassEditResult.builder()
                .categoriesAffected(catAffected)
                .errors(errors)
                .build();
    }

    @Transactional
    public MassEditResult massDelete(Long factSheetId, List<String> categoryIds) {
        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (String categoryId : categoryIds) {
            try {
                delete(categoryId);
                deleted++;
            } catch (Exception e) {
                errors.add("Failed to delete " + categoryId + ": " + e.getMessage());
            }
        }
        return MassEditResult.builder()
                .categoriesAffected(deleted)
                .errors(errors)
                .build();
    }

    // ── Import / Export ─────────────────────────────────────────

    @Transactional
    public void importFromTaxonomy(Long factSheetId, DomainTaxonomy taxonomy) {
        if (taxonomy == null || taxonomy.getTaxonomyJson() == null) return;
        try {
            List<TaxonomyNode> nodes = objectMapper.readValue(
                    taxonomy.getTaxonomyJson(), new TypeReference<List<TaxonomyNode>>() {});
            for (TaxonomyNode node : nodes) {
                if (node.getLevel() == TaxonomyNode.TaxonomyLevel.ENTITY_TYPE) continue;

                String catId = toSlug(node.getLabel());
                Optional<EntityCategory> existing = categoryRepository.findByFactSheetIdAndCategoryId(factSheetId, catId);
                if (existing.isPresent()) {
                    EntityCategory cat = existing.get();
                    if ("AUTO_DISCOVERED".equals(cat.getSource())) {
                        cat.setLabel(node.getLabel());
                        cat.setDescription(node.getDescription());
                        cat.setParentCategoryId(node.getParentId() != null ? toSlug(findLabelById(node.getParentId(), nodes)) : null);
                        categoryRepository.save(cat);
                    }
                    // USER_DEFINED — leave untouched
                } else {
                    EntityCategory cat = EntityCategory.builder()
                            .factSheetId(factSheetId)
                            .categoryId(catId)
                            .label(node.getLabel())
                            .description(node.getDescription())
                            .parentCategoryId(node.getParentId() != null ? toSlug(findLabelById(node.getParentId(), nodes)) : null)
                            .color(generateColor(node.getLabel()))
                            .source("AUTO_DISCOVERED")
                            .build();
                    if (node.getEntityTypes() != null) {
                        cat.setMetadataJson(objectMapper.writeValueAsString(Map.of("entityTypes", node.getEntityTypes())));
                    }
                    categoryRepository.save(cat);
                }
            }
        } catch (Exception e) {
            log.error("Failed to import taxonomy into categories: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void uncategorizeEntitiesInCategory(EntityCategory cat) {
        List<GraphNode> entities = getEntitiesInCategory(cat.getCategoryId(), 0, Integer.MAX_VALUE);
        for (GraphNode entity : entities) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()
                        ? objectMapper.readValue(entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {})
                        : new java.util.LinkedHashMap<>();
                meta.remove("taxonomyCategory");
                meta.remove("taxonomyDomain");
                knowledgeGraphService.updateNode(entity.getNodeId(), entity.getTitle(), entity.getDescription(), meta);
            } catch (Exception e) {
                log.warn("Failed to uncategorize entity {}: {}", entity.getNodeId(), e.getMessage());
            }
        }
    }

    private ObjectNode parseOrCreateMeta(GraphNode node) throws Exception {
        if (node.getMetadataJson() != null && !node.getMetadataJson().isBlank()) {
            return (ObjectNode) objectMapper.readTree(node.getMetadataJson());
        }
        return objectMapper.createObjectNode();
    }

    private String findRootDomain(EntityCategory cat) {
        if (cat.getParentCategoryId() == null) return cat.getLabel();
        // Scope parent lookup by factSheetId to prevent cross-tenant traversal
        if (cat.getFactSheetId() != null) {
            return categoryRepository.findByFactSheetIdAndCategoryId(cat.getFactSheetId(), cat.getParentCategoryId())
                    .map(this::findRootDomain)
                    .orElse(cat.getLabel());
        }
        return categoryRepository.findByCategoryId(cat.getParentCategoryId())
                .map(this::findRootDomain)
                .orElse(cat.getLabel());
    }

    private String findLabelById(String id, List<TaxonomyNode> nodes) {
        return nodes.stream()
                .filter(n -> id.equals(n.getId()))
                .map(TaxonomyNode::getLabel)
                .findFirst()
                .orElse(id);
    }

    static String toSlug(String label) {
        if (label == null) return "unknown";
        String normalized = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "");
        return normalized.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String generateColor(String label) {
        int hash = label != null ? label.hashCode() : 0;
        int hue = Math.abs(hash) % 360;
        return String.format("#%06x", java.awt.Color.HSBtoRGB(hue / 360f, 0.65f, 0.85f) & 0xFFFFFF);
    }
}
