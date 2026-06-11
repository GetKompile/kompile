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

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing CLI agent LLM configuration.
 * <p>
 * Configuration is persisted to {@code ~/.kompile/config/cli-llm-config.json}
 * and managed via REST API. No Spring application.properties involved.
 * </p>
 */
@Service
public class CliAgentLlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLlmConfigService.class);
    private static final String CONFIG_FILE = "cli-llm-config.json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Built-in agent definitions — derived from CliAgentRegistry (cli-agents.json).
     */
    private static final Map<String, BuiltInAgent> BUILT_IN_AGENTS;
    static {
        Map<String, BuiltInAgent> agents = new LinkedHashMap<>();
        for (AgentProvider p : CliAgentRegistry.loadAll()) {
            String cmd = p.getCommand();
            String skipFlag = p.getSkipPermissionsFlag();
            String[] defaultArgs = p.getArgs() != null ? p.getArgs().toArray(new String[0]) : new String[0];
            // Claude uses --print for non-interactive single-shot mode
            String printFlag = "claude".equals(cmd) ? "--print" : null;
            agents.put(cmd, new BuiltInAgent(cmd, p.getDisplayName(),
                    p.getDescription() != null ? p.getDescription() : p.getDisplayName() + " CLI",
                    skipFlag, defaultArgs, printFlag));
        }
        BUILT_IN_AGENTS = Map.copyOf(agents);
    }

    private volatile CliLlmConfig config;
    private final Map<String, Boolean> availabilityCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        config = loadConfig();
        refreshAvailability();
        log.info("CLI Agent LLM config loaded: enabled={}, command='{}'",
                config.enabled, config.command);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    public CliLlmConfig getConfig() {
        return config;
    }

    public void updateConfig(CliLlmConfig updated) {
        this.config = updated;
        saveConfig(updated);
        // Re-check availability for the new command
        availabilityCache.remove(updated.command);
        log.info("CLI Agent LLM config updated: enabled={}, command='{}'",
                updated.enabled, updated.command);
    }

    public boolean isEnabled() {
        return config != null && config.enabled;
    }

    public String getActiveCommand() {
        return config != null ? config.command : "claude";
    }

    public boolean isSkipPermissions() {
        return config == null || config.skipPermissions;
    }

    public int getTimeoutSeconds() {
        return config != null ? config.timeoutSeconds : 120;
    }

    public String getWorkingDirectory() {
        return config != null ? config.workingDirectory : null;
    }

    public Map<String, String> getEnvironment() {
        return config != null && config.environment != null ? config.environment : Map.of();
    }

    public String[] getAdditionalArgs() {
        return config != null && config.additionalArgs != null ? config.additionalArgs : new String[]{};
    }

    /**
     * Get the built-in agent definition for a command name.
     */
    public BuiltInAgent getBuiltInAgent(String command) {
        String basename = command.contains("/") ? command.substring(command.lastIndexOf('/') + 1) : command;
        return BUILT_IN_AGENTS.get(basename);
    }

    /**
     * Get all custom agents from config.
     */
    public Map<String, CustomAgentDef> getCustomAgents() {
        return config != null && config.customAgents != null ? config.customAgents : Map.of();
    }

    /**
     * Check if a command is a Claude-based agent (uses stream-json output).
     */
    public boolean isClaudeAgent(String command) {
        String basename = command.contains("/") ? command.substring(command.lastIndexOf('/') + 1) : command;
        return "claude".equals(basename);
    }

    /**
     * List all available agents (built-in + custom) with availability status.
     */
    public List<AgentStatus> listAgents() {
        List<AgentStatus> result = new ArrayList<>();

        for (Map.Entry<String, BuiltInAgent> entry : BUILT_IN_AGENTS.entrySet()) {
            BuiltInAgent agent = entry.getValue();
            boolean available = checkAvailability(agent.command);
            boolean isActive = agent.command.equals(getActiveCommand());
            result.add(new AgentStatus(agent.command, agent.displayName, agent.description,
                    "built-in", available, isActive));
        }

        if (config != null && config.customAgents != null) {
            for (Map.Entry<String, CustomAgentDef> entry : config.customAgents.entrySet()) {
                String name = entry.getKey();
                CustomAgentDef def = entry.getValue();
                String command = def.command != null ? def.command : name;
                boolean available = checkAvailability(command);
                boolean isActive = command.equals(getActiveCommand());
                String displayName = def.displayName != null ? def.displayName : name;
                String description = def.description != null ? def.description : "Custom CLI agent";
                result.add(new AgentStatus(name, displayName, description,
                        "custom", available, isActive));
            }
        }

        return result;
    }

    /**
     * Check if a specific command is available on the system.
     */
    public boolean checkAvailability(String command) {
        return availabilityCache.computeIfAbsent(command, this::probeCommand);
    }

    /**
     * Force re-check availability for all agents.
     */
    public void refreshAvailability() {
        availabilityCache.clear();
        for (String cmd : BUILT_IN_AGENTS.keySet()) {
            availabilityCache.put(cmd, probeCommand(cmd));
        }
        if (config != null && config.customAgents != null) {
            for (CustomAgentDef def : config.customAgents.values()) {
                String cmd = def.command != null ? def.command : "";
                if (!cmd.isEmpty()) {
                    availabilityCache.put(cmd, probeCommand(cmd));
                }
            }
        }
    }

    /**
     * Add or update a custom agent definition.
     */
    public void putCustomAgent(String name, CustomAgentDef def) {
        if (config.customAgents == null) {
            config.customAgents = new LinkedHashMap<>();
        }
        config.customAgents.put(name, def);
        saveConfig(config);
        if (def.command != null) {
            availabilityCache.remove(def.command);
        }
    }

    /**
     * Remove a custom agent definition.
     */
    public boolean removeCustomAgent(String name) {
        if (config.customAgents != null && config.customAgents.remove(name) != null) {
            saveConfig(config);
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private Path getConfigDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config");
    }

    private Path getConfigPath() {
        return getConfigDir().resolve(CONFIG_FILE);
    }

    private CliLlmConfig loadConfig() {
        Path path = getConfigPath();
        if (!Files.exists(path)) {
            log.debug("No CLI LLM config found at {}, using defaults", path);
            return new CliLlmConfig();
        }
        try {
            return objectMapper.readValue(path.toFile(), CliLlmConfig.class);
        } catch (Exception e) {
            log.error("Failed to load CLI LLM config from {}: {}", path, e.getMessage());
            return new CliLlmConfig();
        }
    }

    private void saveConfig(CliLlmConfig config) {
        try {
            Path dir = getConfigDir();
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(getConfigPath().toFile(), config);
            log.debug("Saved CLI LLM config to {}", getConfigPath());
        } catch (Exception e) {
            log.error("Failed to save CLI LLM config: {}", e.getMessage());
        }
    }

    private boolean probeCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String version = reader.readLine();
                    if (version != null) {
                        log.info("CLI agent '{}' detected: {}", command, version.trim());
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("CLI agent '{}' not found: {}", command, e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Persisted configuration for the CLI agent LLM.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CliLlmConfig {
        public boolean enabled = false;
        public String command = "claude";
        public boolean skipPermissions = true;
        public int timeoutSeconds = 120;
        public String workingDirectory;
        public Map<String, String> environment;
        public String[] additionalArgs;
        public Map<String, CustomAgentDef> customAgents;
    }

    /**
     * Definition for a user-registered custom CLI agent.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomAgentDef {
        public String command;
        public String displayName;
        public String skipPermissionsFlag;
        public String outputFormatFlag;
        public String printFlag;
        public String description;
    }

    /**
     * Built-in agent definition (not persisted — hardcoded).
     */
    public record BuiltInAgent(String command, String displayName, String description,
                                String skipPermissionsFlag, String[] defaultArgs, String printFlag) {}

    /**
     * Agent status for listing endpoints.
     */
    public record AgentStatus(String name, String displayName, String description,
                               String type, boolean available, boolean active) {}
}
