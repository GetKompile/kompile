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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST controller for managing MCP CLI tool injection configuration.
 * Shows injection status across supported coding agents and allows
 * configuration of injection settings.
 */
@RestController
@RequestMapping("/api/mcp/cli-injection")
public class McpCliInjectionController {

    private static final Logger logger = LoggerFactory.getLogger(McpCliInjectionController.class);
    private final ObjectMapper objectMapper;

    public McpCliInjectionController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Agent config file locations and expected key structure.
     */
    private static final Map<String, AgentConfig> AGENT_CONFIGS = Map.of(
            "claude", new AgentConfig(
                    "Claude Code",
                    ".mcp.json",
                    "mcpServers.kompile",
                    "Anthropic Claude Code CLI"
            ),
            "codex", new AgentConfig(
                    "Codex",
                    ".codex/config.toml",
                    "mcp_servers.kompile",
                    "OpenAI Codex CLI"
            ),
            "agy", new AgentConfig(
                    "Antigravity CLI",
                    ".agy/settings.json",
                    "mcpServers.kompile",
                    "Antigravity CLI"
            ),
            "opencode", new AgentConfig(
                    "OpenCode",
                    "opencode.json",
                    "mcpServers.kompile",
                    "OpenCode CLI"
            ),
            "qwen", new AgentConfig(
                    "Qwen Code",
                    ".qwen/settings.json",
                    "mcpServers.kompile",
                    "Alibaba Qwen Code CLI"
            )
    );

    record AgentConfig(String displayName, String configPath, String jsonPath, String description) {}

    @GetMapping("/status")
    public ResponseEntity<?> getInjectionStatus(@RequestParam(required = false) String workDir) {
        Path workingDir = workDir != null ? Paths.get(workDir) : Paths.get(System.getProperty("user.dir"));
        Path homeDir = Paths.get(System.getProperty("user.home"));

        List<Map<String, Object>> agents = new ArrayList<>();

        for (Map.Entry<String, AgentConfig> entry : AGENT_CONFIGS.entrySet()) {
            String agentId = entry.getKey();
            AgentConfig config = entry.getValue();

            Map<String, Object> agentInfo = new LinkedHashMap<>();
            agentInfo.put("id", agentId);
            agentInfo.put("name", config.displayName());
            agentInfo.put("description", config.description());
            agentInfo.put("configFile", config.configPath());

            // Check both project-local and home-directory config
            Path projectConfig = workingDir.resolve(config.configPath());
            Path homeConfig = homeDir.resolve(config.configPath());

            boolean injectedProject = isKompileInjected(projectConfig, agentId);
            boolean injectedHome = isKompileInjected(homeConfig, agentId);

            agentInfo.put("injectedProject", injectedProject);
            agentInfo.put("injectedHome", injectedHome);
            agentInfo.put("injected", injectedProject || injectedHome);
            agentInfo.put("projectConfigExists", Files.exists(projectConfig));
            agentInfo.put("homeConfigExists", Files.exists(homeConfig));

            // Check for backup files (indicates active injection)
            Path backup = workingDir.resolve(config.configPath() + ".kompile-backup");
            agentInfo.put("hasBackup", Files.exists(backup));

            agents.add(agentInfo);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workingDir", workingDir.toString());
        response.put("agents", agents);
        response.put("totalAgents", agents.size());
        response.put("injectedCount", agents.stream().filter(a -> (boolean) a.get("injected")).count());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/config/{agentId}")
    public ResponseEntity<?> getAgentConfig(@PathVariable String agentId,
                                             @RequestParam(required = false) String workDir) {
        AgentConfig config = AGENT_CONFIGS.get(agentId);
        if (config == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown agent: " + agentId));
        }

        Path workingDir = workDir != null ? Paths.get(workDir) : Paths.get(System.getProperty("user.dir"));
        Path configFile = workingDir.resolve(config.configPath());

        if (!Files.exists(configFile)) {
            return ResponseEntity.ok(Map.of(
                    "agentId", agentId,
                    "exists", false,
                    "configPath", configFile.toString()
            ));
        }

        try {
            String content = Files.readString(configFile);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agentId", agentId);
            result.put("exists", true);
            result.put("configPath", configFile.toString());
            result.put("content", content);
            if (configFile.toString().endsWith(".json")) {
                result.put("parsed", objectMapper.readTree(content));
            }
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.ok(Map.of(
                    "agentId", agentId,
                    "exists", true,
                    "configPath", configFile.toString(),
                    "error", "Failed to read: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/cli-command")
    public ResponseEntity<?> getCliCommand(@RequestParam(required = false) String profile,
                                            @RequestParam(required = false) String workDir) {
        String profileArg = profile != null ? " --profile=" + profile : "";
        String workDirArg = workDir != null ? " --work-dir=" + workDir : "";

        Map<String, Object> commands = new LinkedHashMap<>();
        commands.put("inject", "kompile mcp-stdio" + profileArg + workDirArg);
        commands.put("profiles", List.of(
                Map.of("name", "full", "description", "All tools enabled (default)", "toolCount", "40+"),
                Map.of("name", "core", "description", "Essential file and search tools", "toolCount", "~15"),
                Map.of("name", "explore", "description", "Read-only exploration tools", "toolCount", "~10"),
                Map.of("name", "minimal", "description", "Minimal set for constrained environments", "toolCount", "~5")
        ));
        commands.put("schemaLevels", List.of(
                Map.of("name", "none", "description", "No schema compression"),
                Map.of("name", "moderate", "description", "Moderate schema compression"),
                Map.of("name", "aggressive", "description", "Aggressive compression, shorter descriptions"),
                Map.of("name", "compact", "description", "Maximum compression (default)")
        ));

        return ResponseEntity.ok(commands);
    }

    private boolean isKompileInjected(Path configFile, String agentId) {
        if (!Files.exists(configFile)) return false;

        try {
            String content = Files.readString(configFile);
            if (configFile.toString().endsWith(".toml")) {
                return content.contains("kompile");
            }
            JsonNode root = objectMapper.readTree(content);
            JsonNode servers = root.get("mcpServers");
            if (servers != null && servers.has("kompile")) {
                return true;
            }
            // Also check alternate key used by some agents
            JsonNode mcp = root.get("mcp");
            if (mcp != null && mcp.has("kompile")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.trace("Could not parse config file {}: {}", configFile, e.getMessage());
            return false;
        }
    }
}
