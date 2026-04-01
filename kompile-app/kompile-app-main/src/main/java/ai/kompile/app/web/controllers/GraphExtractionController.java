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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.GraphExtractionConfigService;
import ai.kompile.app.services.GraphExtractionConfigService.GraphExtractionConfig;
import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing graph extraction configuration.
 * Provides endpoints to get, update, and reset entity/relationship extraction settings.
 */
@RestController
@RequestMapping("/api/graph-extraction")
public class GraphExtractionController {

    private static final Logger logger = LoggerFactory.getLogger(GraphExtractionController.class);

    private final GraphExtractionConfigService configService;
    private final LlmIntegrationService llmIntegrationService;

    public GraphExtractionController(
            GraphExtractionConfigService configService,
            @Autowired(required = false) LlmIntegrationService llmIntegrationService) {
        this.configService = configService;
        this.llmIntegrationService = llmIntegrationService;
    }

    /**
     * Get current graph extraction configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<GraphExtractionConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update graph extraction configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<GraphExtractionConfig> updateConfig(@RequestBody GraphExtractionConfig config) {
        logger.info("Updating graph extraction config");
        GraphExtractionConfig updated = configService.updateConfig(config);
        return ResponseEntity.ok(updated);
    }

    /**
     * Partially update graph extraction configuration.
     */
    @PatchMapping("/config")
    public ResponseEntity<GraphExtractionConfig> patchConfig(@RequestBody GraphExtractionConfig config) {
        logger.info("Patching graph extraction config");
        GraphExtractionConfig updated = configService.updateConfig(config);
        return ResponseEntity.ok(updated);
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/config/reset")
    public ResponseEntity<GraphExtractionConfig> resetConfig() {
        logger.info("Resetting graph extraction config to defaults");
        GraphExtractionConfig defaults = configService.resetToDefaults();
        return ResponseEntity.ok(defaults);
    }

    /**
     * Toggle entity extraction enabled/disabled.
     */
    @PostMapping("/config/toggle")
    public ResponseEntity<GraphExtractionConfig> toggleEnabled() {
        GraphExtractionConfig current = configService.getConfig();
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = !(current.enabled != null && current.enabled);
        GraphExtractionConfig updated = configService.updateConfig(update);
        logger.info("Toggled graph extraction: enabled={}", updated.enabled);
        return ResponseEntity.ok(updated);
    }

    /**
     * Get current enabled status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        GraphExtractionConfig config = configService.getConfig();
        return ResponseEntity.ok(Map.of(
                "enabled", config.enabled != null && config.enabled,
                "batchSize", config.batchSize != null ? config.batchSize : 10,
                "schemaEnforcement", config.schemaEnforcement != null ? config.schemaEnforcement : "LENIENT",
                "neo4jEnabled", config.neo4jEnabled != null && config.neo4jEnabled,
                "neo4jConnected", false // TODO: Add actual connection check
        ));
    }

    /**
     * Get available schema enforcement modes.
     */
    @GetMapping("/schema-modes")
    public ResponseEntity<List<Map<String, String>>> getSchemaModes() {
        return ResponseEntity.ok(List.of(
                Map.of("value", "NONE", "label", "None", "description", "Accept all entity/relationship types from LLM"),
                Map.of("value", "LENIENT", "label", "Lenient", "description", "Warn about unknown types but allow them"),
                Map.of("value", "STRICT", "label", "Strict", "description", "Only accept types defined in schema")
        ));
    }

    /**
     * Get suggested entity types.
     */
    @GetMapping("/suggested-entity-types")
    public ResponseEntity<List<String>> getSuggestedEntityTypes() {
        return ResponseEntity.ok(List.of(
                "PERSON",
                "ORGANIZATION",
                "LOCATION",
                "CONCEPT",
                "EVENT",
                "PRODUCT",
                "TECHNOLOGY",
                "DATE",
                "DOCUMENT",
                "PROCESS"
        ));
    }

    /**
     * Get suggested relationship types.
     */
    @GetMapping("/suggested-relationship-types")
    public ResponseEntity<List<String>> getSuggestedRelationshipTypes() {
        return ResponseEntity.ok(List.of(
                "WORKS_AT",
                "LOCATED_IN",
                "RELATED_TO",
                "PART_OF",
                "CREATED_BY",
                "OWNS",
                "MANAGES",
                "REPORTS_TO",
                "MEMBER_OF",
                "USES",
                "DEPENDS_ON",
                "MENTIONS",
                "OCCURRED_AT",
                "PRECEDED_BY",
                "FOLLOWED_BY"
        ));
    }

    /**
     * Get available model providers for entity extraction.
     * Uses the modular LlmIntegrationService to discover providers on the classpath.
     * Each provider can expose its available models via the getAvailableModels() method.
     */
    @GetMapping("/model-providers")
    public ResponseEntity<List<Map<String, Object>>> getModelProviders() {
        List<Map<String, Object>> providers = new ArrayList<>();

        // Always include the "default" option which uses the currently active LLM
        Map<String, Object> defaultProvider = new HashMap<>();
        defaultProvider.put("id", "default");
        defaultProvider.put("name", "Default (Active LLM)");
        defaultProvider.put("available", true);
        defaultProvider.put("supportsModelListing", false);
        defaultProvider.put("models", List.of());
        providers.add(defaultProvider);

        // Get providers dynamically from the modular LLM integration service
        if (llmIntegrationService != null) {
            List<LlmProvider> llmProviders = llmIntegrationService.getAllProviders();
            for (LlmProvider provider : llmProviders) {
                Map<String, Object> providerInfo = new HashMap<>();
                providerInfo.put("id", provider.getId());
                providerInfo.put("name", provider.getDisplayName());
                providerInfo.put("available", provider.isAvailable());
                providerInfo.put("supportsStreaming", provider.supportsStreaming());
                providerInfo.put("maxTokens", provider.getMaxTokens());
                providerInfo.put("priority", provider.getPriority());
                providerInfo.put("supportsModelListing", provider.supportsModelListing());

                // Get available models from the provider
                List<LlmProvider.ModelInfo> modelInfos = provider.getAvailableModels();
                List<Map<String, Object>> models = new ArrayList<>();
                for (LlmProvider.ModelInfo model : modelInfos) {
                    Map<String, Object> modelMap = new HashMap<>();
                    modelMap.put("id", model.id());
                    modelMap.put("name", model.displayName());
                    if (model.description() != null) {
                        modelMap.put("description", model.description());
                    }
                    if (model.contextWindow() > 0) {
                        modelMap.put("contextWindow", model.contextWindow());
                    }
                    modelMap.put("supportsTools", model.supportsTools());
                    models.add(modelMap);
                }
                providerInfo.put("models", models);

                providers.add(providerInfo);
            }
            logger.debug("Retrieved {} LLM providers from integration service", llmProviders.size());
        } else {
            logger.warn("LlmIntegrationService not available, returning only default provider");
        }

        return ResponseEntity.ok(providers);
    }
}
