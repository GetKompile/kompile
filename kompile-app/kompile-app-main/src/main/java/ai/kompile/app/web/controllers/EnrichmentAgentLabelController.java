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
package ai.kompile.app.web.controllers;

import ai.kompile.enrichment.domain.EntityCategory;
import ai.kompile.enrichment.domain.MassEditResult;
import ai.kompile.enrichment.impl.AutoLabelService;
import ai.kompile.enrichment.impl.EntityCategoryServiceImpl;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bridge controller that uses KClaw agents for entity auto-labeling.
 * Lives in kompile-app-main because it wires kompile-kclaw + kompile-data-enrichment together.
 */
@RestController
@RequestMapping("/api/enrichment")
public class EnrichmentAgentLabelController {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentAgentLabelController.class);

    private final AutoLabelService autoLabelService;
    private final EntityCategoryServiceImpl categoryService;
    private final GraphNodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private KClawAgentService kclawAgentService;

    @Autowired(required = false)
    private AgentRegistry agentRegistry;

    public EnrichmentAgentLabelController(AutoLabelService autoLabelService,
                                          EntityCategoryServiceImpl categoryService,
                                          GraphNodeRepository nodeRepository,
                                          ObjectMapper objectMapper) {
        this.autoLabelService = autoLabelService;
        this.categoryService = categoryService;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Auto-label entities using a KClaw agent. The agent scans entities and assigns categories directly.
     */
    @PostMapping("/{factSheetId}/categories/auto-label/agent")
    public ResponseEntity<MassEditResult> autoLabelViaAgent(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, Object> req) {
        String agentName = (String) req.getOrDefault("agentName", "jarvis");

        if (kclawAgentService == null) {
            return ResponseEntity.ok(MassEditResult.builder()
                    .errors(List.of("KClaw agent service not available"))
                    .build());
        }

        List<EntityCategory> categories = categoryService.listByFactSheet(factSheetId);
        if (categories.isEmpty()) {
            return ResponseEntity.ok(MassEditResult.builder()
                    .errors(List.of("No categories defined. Create categories first or run taxonomy discovery."))
                    .build());
        }

        // Get uncategorized entities
        List<GraphNode> entities = getUncategorizedEntities(factSheetId);
        if (entities.isEmpty()) {
            return ResponseEntity.ok(MassEditResult.builder()
                    .entitiesAffected(0)
                    .suggestions(List.of())
                    .errors(List.of())
                    .build());
        }

        // Build prompt for the agent
        StringBuilder prompt = new StringBuilder();
        prompt.append("Scan and categorize the following entities for fact sheet ").append(factSheetId).append(".\n\n");
        prompt.append("Available categories:\n");
        for (EntityCategory cat : categories) {
            prompt.append(String.format("- %s (id: %s): %s\n", cat.getLabel(), cat.getCategoryId(),
                    cat.getDescription() != null ? cat.getDescription() : "No description"));
        }
        prompt.append("\nEntities to categorize (").append(entities.size()).append(" total):\n");

        // Cap at 100 entities per agent call to keep prompt reasonable
        int limit = Math.min(entities.size(), 100);
        for (int i = 0; i < limit; i++) {
            GraphNode e = entities.get(i);
            String entityType = extractEntityType(e);
            prompt.append(String.format("%d. nodeId=%s | \"%s\" | type=%s | desc=\"%s\"\n",
                    i + 1, e.getNodeId(), e.getTitle(),
                    entityType != null ? entityType : "UNKNOWN",
                    e.getDescription() != null ?
                            e.getDescription().substring(0, Math.min(80, e.getDescription().length())) : ""));
        }
        if (entities.size() > limit) {
            prompt.append("... and ").append(entities.size() - limit).append(" more.\n");
        }

        prompt.append("\nFor each entity, assign the best category. Return ONLY a JSON array:\n");
        prompt.append("[{\"entityNodeId\": \"<nodeId>\", \"suggestedCategoryId\": \"<categoryId>\", \"confidence\": 0.9, \"reasoning\": \"brief reason\"}]\n");
        prompt.append("Return ONLY the JSON array, no other text.");

        try {
            KClawRequest request = KClawRequest.of(agentName, prompt.toString());
            KClawResponse response = kclawAgentService.execute(request);

            if (!response.isSuccess()) {
                return ResponseEntity.ok(MassEditResult.builder()
                        .errors(List.of("Agent error: " + response.getError()))
                        .build());
            }

            // Parse agent response and apply directly (no dry-run)
            String responseText = response.getResponse().trim();
            if (responseText.startsWith("```")) {
                responseText = responseText.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            // Extract JSON array from response (agent may add surrounding text)
            int jsonStart = responseText.indexOf('[');
            int jsonEnd = responseText.lastIndexOf(']');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                responseText = responseText.substring(jsonStart, jsonEnd + 1);
            }

            List<Map<String, Object>> parsed = objectMapper.readValue(responseText,
                    new TypeReference<List<Map<String, Object>>>() {});

            // Apply categorizations directly
            Map<String, EntityCategory> catMap = categories.stream()
                    .collect(Collectors.toMap(EntityCategory::getCategoryId, c -> c, (a, b) -> a));
            int applied = 0;
            List<String> errors = new ArrayList<>();

            for (Map<String, Object> item : parsed) {
                String nodeId = String.valueOf(item.getOrDefault("entityNodeId", ""));
                String categoryId = String.valueOf(item.getOrDefault("suggestedCategoryId", ""));
                EntityCategory cat = catMap.get(categoryId);
                if (cat == null) {
                    errors.add("Unknown category: " + categoryId + " for entity " + nodeId);
                    continue;
                }

                Optional<GraphNode> nodeOpt = nodeRepository.findByNodeId(nodeId);
                if (nodeOpt.isEmpty()) continue;

                GraphNode node = nodeOpt.get();
                try {
                    var meta = node.getMetadataJson() != null && !node.getMetadataJson().isBlank()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(node.getMetadataJson())
                            : objectMapper.createObjectNode();
                    meta.put("taxonomyCategory", cat.getLabel());
                    meta.put("taxonomyDomain", findRootDomain(cat));
                    node.setMetadataJson(objectMapper.writeValueAsString(meta));
                    nodeRepository.save(node);
                    applied++;
                } catch (Exception e) {
                    errors.add("Failed to apply category to " + nodeId + ": " + e.getMessage());
                }
            }

            return ResponseEntity.ok(MassEditResult.builder()
                    .entitiesAffected(applied)
                    .errors(errors)
                    .build());

        } catch (Exception e) {
            log.error("Agent auto-label failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(MassEditResult.builder()
                    .errors(List.of("Agent execution failed: " + e.getMessage()))
                    .build());
        }
    }

    /**
     * List available KClaw agents for the agent picker.
     */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, String>>> listAvailableAgents() {
        if (agentRegistry == null) {
            return ResponseEntity.ok(List.of());
        }
        List<Map<String, String>> agents = agentRegistry.listAgents().stream()
                .map(a -> Map.of(
                        "name", a.getName(),
                        "description", a.getDescription() != null ? a.getDescription() : ""
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(agents);
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
        return categoryService.getById(cat.getParentCategoryId())
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
            log.warn("Failed to extract entity type from metadata", e);
            return null;
        }
    }
}
