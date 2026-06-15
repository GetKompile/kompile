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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.enrichment.domain.AutoLabelSuggestion;
import ai.kompile.enrichment.domain.EntityCategory;
import ai.kompile.enrichment.domain.MassEditResult;
import ai.kompile.enrichment.repository.EntityCategoryRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-powered entity→category suggestion engine.
 * Supports dry-run (suggestions only) and apply modes.
 */
@Service
public class AutoLabelService {
    private static final Logger log = LoggerFactory.getLogger(AutoLabelService.class);
    private static final int BATCH_SIZE = 50;

    private final EntityCategoryRepository categoryRepository;
    private final GraphNodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private LLMChat llmChat;

    public AutoLabelService(EntityCategoryRepository categoryRepository,
                            GraphNodeRepository nodeRepository,
                            ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Suggest categories for uncategorized entities.
     *
     * @param dryRun        if true, return suggestions without modifying anything
     * @param minConfidence minimum confidence threshold for suggestions
     */
    public MassEditResult autoLabel(Long factSheetId, List<String> entityNodeIds,
                                    boolean dryRun, double minConfidence) {
        List<EntityCategory> categories = categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId);
        if (categories.isEmpty()) {
            return MassEditResult.builder()
                    .errors(List.of("No categories defined for factSheet " + factSheetId))
                    .build();
        }

        // Get entities to label
        List<GraphNode> entities;
        if (entityNodeIds != null && !entityNodeIds.isEmpty()) {
            entities = entityNodeIds.stream()
                    .map(id -> nodeRepository.findByNodeId(id).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            entities = getUncategorizedEntities(factSheetId);
        }

        if (entities.isEmpty()) {
            return MassEditResult.builder().suggestions(List.of()).build();
        }

        // Process in batches
        List<AutoLabelSuggestion> allSuggestions = new ArrayList<>();
        for (int i = 0; i < entities.size(); i += BATCH_SIZE) {
            List<GraphNode> batch = entities.subList(i, Math.min(i + BATCH_SIZE, entities.size()));
            List<AutoLabelSuggestion> batchSuggestions = suggestForBatch(batch, categories, minConfidence);
            allSuggestions.addAll(batchSuggestions);
        }

        int applied = 0;
        if (!dryRun) {
            applied = applySuggestions(factSheetId, allSuggestions, categories);
        }

        return MassEditResult.builder()
                .entitiesAffected(applied)
                .suggestions(allSuggestions)
                .errors(List.of())
                .build();
    }

    /**
     * Apply pre-approved suggestions (from a dry-run review).
     */
    public MassEditResult applySuggestions(Long factSheetId, List<AutoLabelSuggestion> suggestions) {
        List<EntityCategory> categories = categoryRepository.findByFactSheetIdAndActiveTrue(factSheetId);
        int applied = applySuggestions(factSheetId, suggestions, categories);
        return MassEditResult.builder()
                .entitiesAffected(applied)
                .suggestions(suggestions)
                .errors(List.of())
                .build();
    }

    private List<AutoLabelSuggestion> suggestForBatch(List<GraphNode> batch,
                                                       List<EntityCategory> categories,
                                                       double minConfidence) {
        if (llmChat == null) {
            log.warn("LLMChat not available for auto-labeling");
            return List.of();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are categorizing entities in a knowledge graph into the following categories:\n");
        for (EntityCategory cat : categories) {
            prompt.append(String.format("- %s (id: %s): %s\n", cat.getLabel(), cat.getCategoryId(),
                    cat.getDescription() != null ? cat.getDescription() : ""));
        }

        prompt.append("\nFor each entity below, suggest the best matching category. ");
        prompt.append("If no category fits well, use \"Uncategorized\".\n\n");
        prompt.append("Entities to categorize:\n");

        for (int i = 0; i < batch.size(); i++) {
            GraphNode entity = batch.get(i);
            String entityType = extractEntityType(entity);
            prompt.append(String.format("%d. \"%s\" (type: %s, description: \"%s\")\n",
                    i + 1, entity.getTitle(),
                    entityType != null ? entityType : "UNKNOWN",
                    entity.getDescription() != null ?
                            entity.getDescription().substring(0, Math.min(100, entity.getDescription().length())) : ""));
        }

        prompt.append("\nReturn ONLY a JSON array with NO markdown formatting:\n");
        prompt.append("[{\"entityNodeId\": \"...\", \"suggestedCategoryId\": \"...\", \"confidence\": 0.9, \"reasoning\": \"...\"}]\n");

        try {
            String response = llmChat.prompt()
                    .system("You are a data categorization assistant. Return ONLY valid JSON arrays.")
                    .user(prompt.toString())
                    .call()
                    .content();

            response = response.trim();
            if (response.startsWith("```")) {
                response = response.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            List<Map<String, Object>> parsed = objectMapper.readValue(response,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<AutoLabelSuggestion> suggestions = new ArrayList<>();
            Map<String, EntityCategory> catMap = categories.stream()
                    .collect(Collectors.toMap(EntityCategory::getCategoryId, c -> c, (a, b) -> a));

            for (int i = 0; i < parsed.size() && i < batch.size(); i++) {
                Map<String, Object> item = parsed.get(i);
                String suggestedId = String.valueOf(item.getOrDefault("suggestedCategoryId", ""));
                double confidence = item.containsKey("confidence") ?
                        ((Number) item.get("confidence")).doubleValue() : 0.5;
                String reasoning = String.valueOf(item.getOrDefault("reasoning", ""));

                if (confidence < minConfidence) continue;
                EntityCategory suggestedCat = catMap.get(suggestedId);
                GraphNode entity = batch.get(i);

                suggestions.add(AutoLabelSuggestion.builder()
                        .entityNodeId(entity.getNodeId())
                        .entityTitle(entity.getTitle())
                        .entityType(extractEntityType(entity))
                        .suggestedCategoryId(suggestedId)
                        .suggestedCategoryLabel(suggestedCat != null ? suggestedCat.getLabel() : suggestedId)
                        .confidence(confidence)
                        .reasoning(reasoning)
                        .build());
            }
            return suggestions;
        } catch (Exception e) {
            log.error("Auto-label LLM call failed: {}", e.getMessage());
            return List.of();
        }
    }

    private int applySuggestions(Long factSheetId, List<AutoLabelSuggestion> suggestions,
                                List<EntityCategory> categories) {
        Map<String, EntityCategory> catMap = categories.stream()
                .collect(Collectors.toMap(EntityCategory::getCategoryId, c -> c, (a, b) -> a));
        int applied = 0;
        for (AutoLabelSuggestion suggestion : suggestions) {
            EntityCategory cat = catMap.get(suggestion.getSuggestedCategoryId());
            if (cat == null) continue;

            nodeRepository.findByNodeId(suggestion.getEntityNodeId()).ifPresent(node -> {
                try {
                    ObjectNode meta;
                    if (node.getMetadataJson() != null && !node.getMetadataJson().isBlank()) {
                        meta = (ObjectNode) objectMapper.readTree(node.getMetadataJson());
                    } else {
                        meta = objectMapper.createObjectNode();
                    }
                    meta.put("taxonomyCategory", cat.getLabel());
                    // Walk up to find domain
                    String domain = findRootDomain(cat);
                    meta.put("taxonomyDomain", domain);
                    node.setMetadataJson(objectMapper.writeValueAsString(meta));
                    nodeRepository.save(node);
                } catch (Exception e) {
                    log.warn("Failed to apply suggestion for {}: {}", suggestion.getEntityNodeId(), e.getMessage());
                }
            });
            applied++;
        }
        return applied;
    }

    private List<GraphNode> getUncategorizedEntities(Long factSheetId) {
        return nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY).stream()
                .filter(e -> {
                    try {
                        if (e.getMetadataJson() == null) return true;
                        var meta = objectMapper.readTree(e.getMetadataJson());
                        return !meta.has("taxonomyCategory") ||
                                meta.path("taxonomyCategory").asText().isBlank() ||
                                "Uncategorized".equals(meta.path("taxonomyCategory").asText());
                    } catch (Exception ex) {
                        return true;
                    }
                })
                .collect(Collectors.toList());
    }

    private String findRootDomain(EntityCategory cat) {
        if (cat.getParentCategoryId() == null) return cat.getLabel();
        return categoryRepository.findByCategoryId(cat.getParentCategoryId())
                .map(this::findRootDomain)
                .orElse(cat.getLabel());
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
