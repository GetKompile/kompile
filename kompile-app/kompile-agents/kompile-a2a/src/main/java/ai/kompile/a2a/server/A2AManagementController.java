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

package ai.kompile.a2a.server;

import ai.kompile.a2a.client.A2AClient;
import ai.kompile.a2a.client.A2AClientException;
import ai.kompile.a2a.model.A2AAgentConfig;
import ai.kompile.a2a.model.A2ATask;
import ai.kompile.a2a.service.A2ARegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ai.kompile.a2a.config.A2AConfigService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for managing A2A agent connections.
 * <p>
 * Provides CRUD operations for remote A2A agent registrations,
 * discovery, health checking, and task delegation to remote agents.
 */
@RestController
@RequestMapping("/api/a2a")
public class A2AManagementController {

    private static final Logger logger = LoggerFactory.getLogger(A2AManagementController.class);

    private final A2ARegistryService registryService;
    private final A2AConfigService configService;

    @Autowired
    public A2AManagementController(A2ARegistryService registryService, A2AConfigService configService) {
        this.registryService = registryService;
        this.configService = configService;
    }

    /**
     * List all registered remote A2A agents.
     */
    @GetMapping(value = "/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listAgents() {
        List<A2AAgentConfig> agents = registryService.listAgents();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", agents.size());
        result.put("agents", agents);
        return ResponseEntity.ok(result);
    }

    /**
     * Get details for a specific remote A2A agent.
     */
    @GetMapping(value = "/agents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getAgent(@PathVariable String id) {
        return registryService.getAgentConfig(id)
                .map(config -> ResponseEntity.ok((Object) config))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Discover and register a remote A2A agent by URL.
     * Fetches the agent card from {@code /.well-known/agent-card.json}.
     */
    @PostMapping(value = "/agents/discover", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> discoverAgent(@RequestBody Map<String, String> body) {
        String baseUrl = body.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "baseUrl is required"));
        }

        try {
            A2AAgentConfig config = registryService.discoverAgent(baseUrl);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Agent discovered and registered");
            result.put("agent", config);
            return ResponseEntity.ok(result);
        } catch (A2AClientException e) {
            logger.warn("Failed to discover A2A agent at {}: {}", baseUrl, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "Discovery failed: " + e.getMessage()));
        }
    }

    /**
     * Manually register a remote A2A agent.
     */
    @PostMapping(value = "/agents", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> registerAgent(@RequestBody Map<String, String> body) {
        String id = body.getOrDefault("id", UUID.randomUUID().toString());
        String name = body.get("name");
        String baseUrl = body.get("baseUrl");

        if (baseUrl == null || baseUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "baseUrl is required"));
        }

        A2AAgentConfig config = registryService.registerAgent(id, name != null ? name : id, baseUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("agent", config);
        return ResponseEntity.ok(result);
    }

    /**
     * Unregister a remote A2A agent.
     */
    @DeleteMapping(value = "/agents/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> unregisterAgent(@PathVariable String id) {
        boolean removed = registryService.unregisterAgent(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "success", "message", "Agent unregistered: " + id));
    }

    /**
     * Enable or disable a remote A2A agent.
     */
    @PutMapping(value = "/agents/{id}/enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        boolean updated = registryService.setEnabled(id, enabled);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "enabled", enabled));
    }

    /**
     * Ping a remote A2A agent to check connectivity.
     */
    @PostMapping(value = "/agents/{id}/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pingAgent(@PathVariable String id) {
        boolean reachable = registryService.pingAgent(id);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "agentId", id,
                "reachable", reachable));
    }

    /**
     * Send a task to a remote A2A agent.
     */
    @PostMapping(value = "/agents/{id}/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sendTask(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "message is required"));
        }

        Optional<A2AClient> clientOpt = registryService.getClient(id);
        if (clientOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            A2ATask task = clientOpt.get().sendMessage(message);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("task", task);
            return ResponseEntity.ok(result);
        } catch (A2AClientException e) {
            logger.warn("Failed to send task to A2A agent {}: {}", id, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "Send failed: " + e.getMessage()));
        }
    }

    /**
     * Get A2A module status summary.
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStatus() {
        List<A2AAgentConfig> agents = registryService.listAgents();
        long reachable = agents.stream().filter(A2AAgentConfig::isReachable).count();
        long enabledAgents = agents.stream().filter(A2AAgentConfig::isEnabled).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", configService.isEnabled());
        result.put("serverEnabled", configService.isServerEnabled());
        result.put("totalRemoteAgents", agents.size());
        result.put("enabledAgents", enabledAgents);
        result.put("reachableAgents", reachable);
        result.put("protocolVersion", "1.0");
        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Get A2A module configuration.
     */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<A2AConfigService.A2AConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update A2A module configuration.
     * Supports partial updates (only specified fields are changed).
     */
    @PutMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        A2AConfigService.A2AConfig updated = configService.updateConfig(updates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("config", updated);
        return ResponseEntity.ok(result);
    }

    /**
     * Enable or disable the A2A module at runtime.
     */
    @PutMapping(value = "/config/enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        configService.setEnabled(enabled);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("enabled", enabled);
        result.put("message", enabled ? "A2A module enabled" : "A2A module disabled");
        return ResponseEntity.ok(result);
    }
}
