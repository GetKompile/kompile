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

import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.staging.StagingStatus;
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
 * REST controller for managing extraction model selection from the staging registry.
 * Lists available models that can be used for entity/relationship extraction.
 */
@RestController
@RequestMapping("/api/graph/extraction-models")
public class GraphExtractionModelController {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionModelController.class);

    @Autowired(required = false)
    private StagingService stagingService;

    @Autowired(required = false)
    private List<GraphConstructor> graphConstructors;

    /**
     * List available models for entity extraction.
     * Combines built-in providers with staged models.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listExtractionModels() {
        List<Map<String, Object>> models = new ArrayList<>();

        // Add built-in providers
        models.add(buildProviderEntry("default", "Default LLM", "Uses the configured default LLM provider", true));
        models.add(buildProviderEntry("openai", "OpenAI", "GPT-4o, GPT-4, etc.", false));
        models.add(buildProviderEntry("anthropic", "Anthropic", "Claude models", false));
        models.add(buildProviderEntry("ollama", "Ollama (Local)", "Local models via Ollama", false));

        // Add staged models if staging service is available
        if (stagingService != null) {
            try {
                List<StagingModelInfo> stagedModels = stagingService.getStagingModels();
                if (stagedModels != null) {
                    for (StagingModelInfo model : stagedModels) {
                        if (model.getStatus() == StagingStatus.READY || model.getStatus() == StagingStatus.COMPLETED) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("provider", "staged");
                            entry.put("modelId", model.getModelId());
                            entry.put("name", model.getModelId());
                            entry.put("description", "Staged model: " + model.getModelId());
                            entry.put("available", true);
                            entry.put("source", "staging");
                            models.add(entry);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch staged models: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(models);
    }

    /**
     * Configure the extraction model for a specific graph constructor.
     */
    @PostMapping("/configure")
    public ResponseEntity<Map<String, String>> configureExtractionModel(
            @RequestBody Map<String, Object> config) {
        String provider = (String) config.getOrDefault("provider", "default");
        String modelName = (String) config.get("modelName");
        Double temperature = config.containsKey("temperature") ?
                ((Number) config.get("temperature")).doubleValue() : 0.0;
        Integer maxTokens = config.containsKey("maxTokens") ?
                ((Number) config.get("maxTokens")).intValue() : 4096;

        GraphConstructor.ExtractionModelConfig extractionConfig =
                new GraphConstructor.ExtractionModelConfig(provider, modelName, temperature, maxTokens, null);

        if (graphConstructors != null) {
            for (GraphConstructor constructor : graphConstructors) {
                constructor.configure(extractionConfig);
            }
        }

        log.info("Configured extraction model: provider={}, model={}", provider, modelName);
        return ResponseEntity.ok(Map.of(
                "status", "configured",
                "provider", provider,
                "modelName", modelName != null ? modelName : "default"
        ));
    }

    private Map<String, Object> buildProviderEntry(String provider, String name, String description, boolean isDefault) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("provider", provider);
        entry.put("name", name);
        entry.put("description", description);
        entry.put("available", true);
        entry.put("isDefault", isDefault);
        entry.put("source", "built-in");
        return entry;
    }
}
