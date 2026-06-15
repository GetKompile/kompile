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

import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ApiAgentChatExecutor;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing API agent configurations.
 * <p>
 * Provides CRUD endpoints for adding, updating, and removing
 * OpenAI-compatible API agent endpoints at runtime.
 * Configurations are persisted to ~/.kompile/config/api-agents.json.
 */
@RestController
@RequestMapping("/api/agents/api-config")
public class ApiAgentConfigController {

    private static final Logger log = LoggerFactory.getLogger(ApiAgentConfigController.class);

    private final AgentRegistryService agentRegistryService;
    private final ApiAgentChatExecutor apiAgentChatExecutor;

    @Autowired
    public ApiAgentConfigController(
            AgentRegistryService agentRegistryService,
            @Autowired(required = false) ApiAgentChatExecutor apiAgentChatExecutor) {
        this.agentRegistryService = agentRegistryService;
        this.apiAgentChatExecutor = apiAgentChatExecutor;
    }

    /**
     * List all configured API agents (with masked API keys).
     */
    @GetMapping
    public ResponseEntity<List<AgentProvider>> listApiAgents() {
        List<AgentProvider> apiAgents = agentRegistryService.getAllAgents().stream()
                .filter(AgentProvider::isApiAgent)
                .toList();
        // Mask API keys
        apiAgents.forEach(a -> {
            if (a.getApiKey() != null && !a.getApiKey().isEmpty()) {
                a.setApiKey(a.getMaskedApiKey());
            }
        });
        return ResponseEntity.ok(apiAgents);
    }

    /**
     * Add a new API agent endpoint.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addApiAgent(@RequestBody ApiAgentRequest request) {
        log.info("Adding API agent: {} (endpoint: {})", request.name, request.endpointUrl);

        // Validate
        if (request.name == null || request.name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        if (request.endpointUrl == null || request.endpointUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Endpoint URL is required"));
        }
        if (request.modelName == null || request.modelName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model name is required"));
        }

        // Check if name already exists
        if (agentRegistryService.getAgent(request.name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Agent with name '" + request.name + "' already exists"));
        }

        AgentProvider agent = AgentProvider.builder()
                .name(request.name)
                .displayName(request.displayName != null ? request.displayName : request.name)
                .agentType(AgentType.API)
                .endpointUrl(request.endpointUrl)
                .apiKey(request.apiKey)
                .modelName(request.modelName)
                .temperature(request.temperature != null ? request.temperature : 0.7)
                .maxTokens(request.maxTokens != null ? request.maxTokens : 4096)
                .description(request.description != null ? request.description : "OpenAI-compatible API endpoint")
                .isDefault(false)
                .build();

        agentRegistryService.registerApiAgent(agent);

        return ResponseEntity.ok(Map.of(
                "name", agent.getName(),
                "available", agent.isAvailable(),
                "message", "API agent added successfully"));
    }

    /**
     * Update an existing API agent configuration.
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> updateApiAgent(
            @PathVariable String name,
            @RequestBody ApiAgentRequest request) {
        log.info("Updating API agent: {}", name);

        var existingOpt = agentRegistryService.getAgent(name);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!existingOpt.get().isApiAgent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot update a CLI agent as an API agent"));
        }

        AgentProvider existing = existingOpt.get();

        AgentProvider updated = AgentProvider.builder()
                .name(name)
                .displayName(request.displayName != null ? request.displayName : existing.getDisplayName())
                .agentType(AgentType.API)
                .endpointUrl(request.endpointUrl != null ? request.endpointUrl : existing.getEndpointUrl())
                .apiKey(resolveApiKey(request.apiKey, existing.getApiKey()))
                .modelName(request.modelName != null ? request.modelName : existing.getModelName())
                .temperature(request.temperature != null ? request.temperature : existing.getTemperature())
                .maxTokens(request.maxTokens != null ? request.maxTokens : existing.getMaxTokens())
                .description(request.description != null ? request.description : existing.getDescription())
                .isDefault(existing.isDefault())
                .build();

        boolean success = agentRegistryService.updateApiAgent(name, updated);
        if (!success) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to update API agent"));
        }

        return ResponseEntity.ok(Map.of(
                "name", name,
                "available", updated.isAvailable(),
                "message", "API agent updated successfully"));
    }

    /**
     * Remove an API agent configuration.
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteApiAgent(@PathVariable String name) {
        log.info("Deleting API agent: {}", name);

        var existingOpt = agentRegistryService.getAgent(name);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!existingOpt.get().isApiAgent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete a CLI agent via API config endpoint"));
        }

        boolean removed = agentRegistryService.unregisterAgent(name);
        return ResponseEntity.ok(Map.of(
                "name", name,
                "deleted", removed));
    }

    /**
     * Test connectivity to an API agent's endpoint.
     */
    @PostMapping("/{name}/test")
    public ResponseEntity<Map<String, Object>> testApiAgent(@PathVariable String name) {
        log.info("Testing API agent connectivity: {}", name);

        var agentOpt = agentRegistryService.getAgent(name);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AgentProvider agent = agentOpt.get();
        if (!agent.isApiAgent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not an API agent"));
        }

        if (apiAgentChatExecutor == null) {
            return ResponseEntity.status(503).body(Map.of("error", "API agent executor not available"));
        }

        Map<String, Object> result = apiAgentChatExecutor.testEndpoint(
                agent.getEndpointUrl(), agent.getApiKey());
        result.put("agentName", name);
        return ResponseEntity.ok(result);
    }

    /**
     * Test connectivity to an arbitrary endpoint (without saving).
     */
    @PostMapping("/test-endpoint")
    public ResponseEntity<Map<String, Object>> testEndpoint(@RequestBody ApiAgentRequest request) {
        if (apiAgentChatExecutor == null) {
            return ResponseEntity.status(503).body(Map.of("error", "API agent executor not available"));
        }

        Map<String, Object> result = apiAgentChatExecutor.testEndpoint(
                request.endpointUrl, request.apiKey);
        return ResponseEntity.ok(result);
    }

    /**
     * Resolve API key - if the incoming value looks like a mask (e.g., "sk-1****"),
     * keep the existing key unchanged.
     */
    private String resolveApiKey(String newKey, String existingKey) {
        if (newKey == null || newKey.isEmpty()) {
            return existingKey;
        }
        // If it contains "****", it's likely the masked version - keep existing
        if (newKey.contains("****")) {
            return existingKey;
        }
        return newKey;
    }

    /**
     * Request body for API agent configuration.
     */
    public static class ApiAgentRequest {
        public String name;
        public String displayName;
        public String endpointUrl;
        public String apiKey;
        public String modelName;
        public Double temperature;
        public Integer maxTokens;
        public String description;
    }
}
