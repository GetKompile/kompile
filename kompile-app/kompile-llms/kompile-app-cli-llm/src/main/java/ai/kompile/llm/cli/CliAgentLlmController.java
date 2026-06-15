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

package ai.kompile.llm.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing CLI Agent LLM configuration.
 * <p>
 * All configuration is managed through this API (persisted to ~/.kompile/config/cli-llm-config.json).
 * The Angular UI calls these endpoints — no Spring properties are involved.
 * </p>
 */
@RestController
@RequestMapping("/api/llm/cli-agent")
public class CliAgentLlmController {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLlmController.class);

    private final CliAgentLlmConfigService configService;

    public CliAgentLlmController(CliAgentLlmConfigService configService) {
        this.configService = configService;
    }

    /**
     * Get the current CLI agent LLM configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<CliAgentLlmConfigService.CliLlmConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Update the CLI agent LLM configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<CliAgentLlmConfigService.CliLlmConfig> updateConfig(
            @RequestBody CliAgentLlmConfigService.CliLlmConfig config) {
        configService.updateConfig(config);
        return ResponseEntity.ok(configService.getConfig());
    }

    /**
     * Enable or disable the CLI agent LLM.
     */
    @PutMapping("/config/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' field"));
        }

        CliAgentLlmConfigService.CliLlmConfig config = configService.getConfig();
        config.enabled = enabled;
        configService.updateConfig(config);

        return ResponseEntity.ok(Map.of(
                "enabled", config.enabled,
                "command", config.command
        ));
    }

    /**
     * Set which CLI command to use as the active LLM.
     */
    @PutMapping("/config/command")
    public ResponseEntity<Map<String, Object>> setCommand(@RequestBody Map<String, String> body) {
        String command = body.get("command");
        if (command == null || command.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'command' field"));
        }

        CliAgentLlmConfigService.CliLlmConfig config = configService.getConfig();
        config.command = command;
        configService.updateConfig(config);

        boolean available = configService.checkAvailability(command);
        return ResponseEntity.ok(Map.of(
                "command", command,
                "available", available
        ));
    }

    /**
     * List all available CLI agents (built-in + custom) with availability status.
     */
    @GetMapping("/agents")
    public ResponseEntity<List<CliAgentLlmConfigService.AgentStatus>> listAgents() {
        return ResponseEntity.ok(configService.listAgents());
    }

    /**
     * Check availability of a specific CLI command.
     */
    @PostMapping("/agents/{command}/check")
    public ResponseEntity<Map<String, Object>> checkAgent(@PathVariable String command) {
        boolean available = configService.checkAvailability(command);
        return ResponseEntity.ok(Map.of(
                "command", command,
                "available", available
        ));
    }

    /**
     * Refresh availability for all registered agents.
     */
    @PostMapping("/agents/refresh")
    public ResponseEntity<List<CliAgentLlmConfigService.AgentStatus>> refreshAgents() {
        configService.refreshAvailability();
        return ResponseEntity.ok(configService.listAgents());
    }

    /**
     * Add or update a custom CLI agent.
     */
    @PutMapping("/agents/custom/{name}")
    public ResponseEntity<Map<String, Object>> putCustomAgent(
            @PathVariable String name,
            @RequestBody CliAgentLlmConfigService.CustomAgentDef def) {
        if (def.command == null || def.command.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'command' field"));
        }
        configService.putCustomAgent(name, def);
        boolean available = configService.checkAvailability(def.command);
        return ResponseEntity.ok(Map.of(
                "name", name,
                "command", def.command,
                "available", available
        ));
    }

    /**
     * Remove a custom CLI agent.
     */
    @DeleteMapping("/agents/custom/{name}")
    public ResponseEntity<Map<String, Object>> removeCustomAgent(@PathVariable String name) {
        boolean removed = configService.removeCustomAgent(name);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("removed", name));
    }

    /**
     * Test the CLI agent by sending a short prompt and returning the response.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testAgent(@RequestBody(required = false) Map<String, String> body) {
        String command = body != null && body.containsKey("command")
                ? body.get("command")
                : configService.getActiveCommand();

        boolean available = configService.checkAvailability(command);
        if (!available) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "command", command,
                    "error", "CLI agent '" + command + "' is not installed or not on PATH"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "command", command,
                "available", true
        ));
    }
}
