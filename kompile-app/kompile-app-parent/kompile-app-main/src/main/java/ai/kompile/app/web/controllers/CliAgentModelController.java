/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.CliAgentModelService;
import ai.kompile.app.services.agent.CliAgentModelService.AgentModelInfo;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for CLI agent model discovery and configuration.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET  /api/agents/models}            — list all agents with available models</li>
 *   <li>{@code GET  /api/agents/models/{name}}      — get models for a specific agent</li>
 *   <li>{@code POST /api/agents/models/{name}}      — set model for a specific agent</li>
 *   <li>{@code GET  /api/agents/models/providers}   — list extraction LLM providers with effective models</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/models")
public class CliAgentModelController {

    private final CliAgentModelService modelService;

    @Autowired(required = false)
    private ExtractionLlmServiceRegistry extractionRegistry;

    public CliAgentModelController(CliAgentModelService modelService) {
        this.modelService = modelService;
    }

    /**
     * List all CLI agents with their available models and current selection.
     *
     * @param refresh if true, re-run model discovery (ignoring cache)
     */
    @GetMapping
    public ResponseEntity<List<AgentModelInfo>> listAllAgentModels(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(modelService.getAllAgentModels(refresh));
    }

    /**
     * Get available models for a specific agent.
     *
     * @param name    agent name (e.g., "claude-cli", "opencode-cli")
     * @param refresh if true, re-run model discovery
     */
    @GetMapping("/{name}")
    public ResponseEntity<AgentModelInfo> getAgentModels(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean refresh) {
        AgentModelInfo info = modelService.getModelsForAgent(name, refresh);
        if (!info.available() && info.availableModels().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(info);
    }

    /**
     * Set the model for a specific agent. Persists to config and applies at runtime.
     *
     * @param name agent name
     * @param body request body with "model" field (null or empty to clear)
     */
    @PostMapping("/{name}")
    public ResponseEntity<Map<String, Object>> setAgentModel(
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        String model = body.get("model");
        boolean success = modelService.setAgentModel(name, model);
        if (!success) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "agent", name,
                "model", model != null ? model : "",
                "applied", true
        ));
    }

    /**
     * List extraction LLM providers with their effective models.
     * This shows the providers registered in the ExtractionLlmServiceRegistry.
     */
    @GetMapping("/providers")
    public ResponseEntity<?> listProviders() {
        if (extractionRegistry == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(extractionRegistry.listProviders());
    }
}
