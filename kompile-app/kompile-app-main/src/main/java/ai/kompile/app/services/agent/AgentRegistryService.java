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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.AgentType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing available AI agent providers.
 * <p>
 * Supports CLI-based agents (Claude Code, Codex CLI, Gemini CLI) and
 * API-based agents (OpenAI-compatible endpoints).
 * API agent configurations are persisted to ~/.kompile/config/api-agents.json.
 */
@Service
public class AgentRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryService.class);
    private static final String API_AGENTS_CONFIG_FILE = "api-agents.json";

    private final Map<String, AgentProvider> agents = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private ApiAgentChatExecutor apiAgentChatExecutor;

    @PostConstruct
    public void initialize() {
        log.info("Initializing agent registry...");

        // Claude Code CLI (local installation, not API)
        AgentProvider claude = AgentProvider.builder()
                .name("claude-cli")
                .displayName("Claude Code")
                .command("claude")
                .skipPermissionsFlag("--dangerously-skip-permissions")
                .skipPermissions(true)
                .addArg("--output-format")
                .addArg("stream-json")
                .addArg("--verbose")
                .available(false) // Will be checked below
                .isDefault(true)
                .description("Anthropic's Claude Code CLI - requires local installation (not API)")
                .build();
        agents.put(claude.getName(), claude);

        // OpenAI Codex CLI (local installation, not API)
        AgentProvider codex = AgentProvider.builder()
                .name("codex-cli")
                .displayName("Codex")
                .command("codex")
                .skipPermissionsFlag("--full-auto")
                .skipPermissions(true)
                .available(false) // Will be checked below
                .isDefault(false)
                .description("OpenAI's Codex CLI - requires local installation (not API)")
                .build();
        agents.put(codex.getName(), codex);

        // Google Gemini CLI (local installation, not API)
        AgentProvider gemini = AgentProvider.builder()
                .name("gemini-cli")
                .displayName("Gemini")
                .command("gemini")
                .skipPermissionsFlag("--yolo")
                .skipPermissions(true)
                .available(false) // Will be checked below
                .isDefault(false)
                .description("Google's Gemini CLI - requires local installation (not API)")
                .build();
        agents.put(gemini.getName(), gemini);

        log.info("Agent registry initialized with {} CLI agents, checking availability...", agents.size());

        // Check actual availability of CLI agents on startup
        checkAllAgentsAvailability();

        // Load persisted API agent configurations
        loadApiAgentsFromConfig();

        int availableCount = getAvailableAgentCount();
        log.info("Agent availability check complete: {} of {} agents available", availableCount, agents.size());
    }

    /**
     * Check availability of all registered agents and parse their MCP capabilities.
     */
    public void checkAllAgentsAvailability() {
        for (AgentProvider agent : agents.values()) {
            if (agent.isApiAgent()) {
                // API agents: check endpoint reachability
                boolean available = checkApiAgentAvailability(agent);
                agent.setAvailable(available);
                log.info("API agent '{}' availability: {}", agent.getName(), available);
            } else {
                boolean available = checkAgentAvailability(agent);
                agent.setAvailable(available);
                log.info("CLI agent '{}' availability: {}", agent.getName(), available);

                // If available, parse --help to detect MCP support
                if (available) {
                    parseAgentHelpForMcpSupport(agent);
                }
            }
        }
    }

    /**
     * Parse agent's --help output to detect MCP server configuration flags.
     */
    private void parseAgentHelpForMcpSupport(AgentProvider agent) {
        try {
            ProcessBuilder pb = new ProcessBuilder(agent.getCommand(), "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder helpOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    helpOutput.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Timeout parsing --help for agent '{}'", agent.getName());
                return;
            }

            String help = helpOutput.toString();
            agent.setHelpOutput(help);

            // Parse for MCP-related flags
            parseMcpFlags(agent, help);

            if (agent.isMcpSupported()) {
                log.info("Agent '{}' supports MCP: serverFlag={}, configFlag={}",
                        agent.getName(), agent.getMcpServerFlag(), agent.getMcpConfigFlag());
            } else {
                log.debug("Agent '{}' does not appear to support MCP servers", agent.getName());
            }

        } catch (Exception e) {
            log.debug("Error parsing --help for agent '{}': {}", agent.getName(), e.getMessage());
        }
    }

    /**
     * Parse help text to identify MCP-related command flags.
     */
    private void parseMcpFlags(AgentProvider agent, String helpText) {
        String helpLower = helpText.toLowerCase();

        // Check for MCP server support - look for common patterns
        boolean hasMcpSupport = helpLower.contains("mcp") ||
                helpLower.contains("model context protocol") ||
                helpLower.contains("--mcp");

        if (!hasMcpSupport) {
            agent.setMcpSupported(false);
            return;
        }

        agent.setMcpSupported(true);

        // Parse for specific MCP flags based on common patterns
        // Claude CLI patterns
        if (helpText.contains("--mcp-server") || helpText.contains("-mcp-server")) {
            agent.setMcpServerFlag("--mcp-server");
        }

        // Look for MCP config file flag
        if (helpText.contains("--mcp-config")) {
            agent.setMcpConfigFlag("--mcp-config");
        }

        // Look for allowed tools flag (some CLIs have this)
        if (helpText.contains("--allowedTools") || helpText.contains("--allowed-tools")) {
            agent.setMcpAllowToolsFlag(helpText.contains("--allowedTools") ? "--allowedTools" : "--allowed-tools");
        }

        // If we found MCP mention but no specific flags, try to extract them
        if (agent.getMcpServerFlag() == null) {
            // Try regex patterns to find MCP-related flags
            java.util.regex.Pattern mcpPattern = java.util.regex.Pattern.compile(
                    "(-{1,2}[a-zA-Z-]*mcp[a-zA-Z-]*)\\s",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = mcpPattern.matcher(helpText);

            while (matcher.find()) {
                String flag = matcher.group(1).trim();
                if (flag.toLowerCase().contains("server") || flag.toLowerCase().contains("config")) {
                    if (flag.toLowerCase().contains("server")) {
                        agent.setMcpServerFlag(flag);
                    } else if (flag.toLowerCase().contains("config")) {
                        agent.setMcpConfigFlag(flag);
                    }
                } else if (agent.getMcpServerFlag() == null) {
                    // Generic MCP flag
                    agent.setMcpServerFlag(flag);
                }
            }
        }

        // Log what we found
        log.debug("Parsed MCP flags for '{}': supported={}, serverFlag={}, configFlag={}, allowToolsFlag={}",
                agent.getName(), agent.isMcpSupported(),
                agent.getMcpServerFlag(), agent.getMcpConfigFlag(), agent.getMcpAllowToolsFlag());
    }

    /**
     * Check if a specific agent is available (CLI installed and accessible).
     */
    public boolean checkAgentAvailability(AgentProvider agent) {
        try {
            ProcessBuilder pb = new ProcessBuilder(agent.getCommand(), "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }

            if (process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String version = reader.readLine();
                    if (version != null) {
                        log.info("Agent '{}' available: {}", agent.getName(), version.trim());
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("Agent '{}' not available: {}", agent.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Check availability of a specific agent by name.
     */
    public boolean checkAgentAvailability(String agentName) {
        AgentProvider agent = agents.get(agentName);
        if (agent == null) {
            return false;
        }
        boolean available = checkAgentAvailability(agent);
        agent.setAvailable(available);
        return available;
    }

    /**
     * Get all registered agents.
     */
    public List<AgentProvider> getAllAgents() {
        return new ArrayList<>(agents.values());
    }

    /**
     * Get only available agents.
     */
    public List<AgentProvider> getAvailableAgents() {
        return agents.values().stream()
                .filter(AgentProvider::isAvailable)
                .toList();
    }

    /**
     * Get agent by name.
     */
    public Optional<AgentProvider> getAgent(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    /**
     * Get the default agent.
     */
    public Optional<AgentProvider> getDefaultAgent() {
        return agents.values().stream()
                .filter(a -> a.isDefault() && a.isAvailable())
                .findFirst()
                .or(() -> agents.values().stream()
                        .filter(AgentProvider::isAvailable)
                        .findFirst());
    }

    /**
     * Register a custom agent provider.
     */
    public void registerAgent(AgentProvider agent) {
        if (agent.isApiAgent()) {
            boolean available = checkApiAgentAvailability(agent);
            agent.setAvailable(available);
        } else {
            boolean available = checkAgentAvailability(agent);
            agent.setAvailable(available);
        }
        agents.put(agent.getName(), agent);
        log.info("Registered custom agent '{}' ({}), available: {}",
                agent.getName(), agent.getAgentType(), agent.isAvailable());
    }

    /**
     * Register an API agent and persist to config.
     */
    public void registerApiAgent(AgentProvider agent) {
        agent.setAgentType(AgentType.API);
        boolean available = checkApiAgentAvailability(agent);
        agent.setAvailable(available);
        agents.put(agent.getName(), agent);
        saveApiAgentsToConfig();
        log.info("Registered API agent '{}' (endpoint: {}), available: {}",
                agent.getName(), agent.getEndpointUrl(), available);
    }

    /**
     * Update an existing API agent configuration.
     */
    public boolean updateApiAgent(String name, AgentProvider updated) {
        AgentProvider existing = agents.get(name);
        if (existing == null || !existing.isApiAgent()) {
            return false;
        }
        updated.setAgentType(AgentType.API);
        updated.setName(name);
        boolean available = checkApiAgentAvailability(updated);
        updated.setAvailable(available);
        agents.put(name, updated);
        saveApiAgentsToConfig();
        return true;
    }

    /**
     * Unregister an agent provider.
     */
    public boolean unregisterAgent(String name) {
        AgentProvider removed = agents.remove(name);
        if (removed != null) {
            log.info("Unregistered agent '{}'", name);
            if (removed.isApiAgent()) {
                saveApiAgentsToConfig();
            }
            return true;
        }
        return false;
    }

    /**
     * Check availability of an API agent by hitting its /models endpoint.
     */
    private boolean checkApiAgentAvailability(AgentProvider agent) {
        if (agent.getEndpointUrl() == null || agent.getEndpointUrl().isEmpty()) {
            return false;
        }
        if (apiAgentChatExecutor != null) {
            try {
                Map<String, Object> result = apiAgentChatExecutor.testEndpoint(
                        agent.getEndpointUrl(), agent.getApiKey());
                return Boolean.TRUE.equals(result.get("reachable"));
            } catch (Exception e) {
                log.debug("API agent '{}' availability check failed: {}", agent.getName(), e.getMessage());
                return false;
            }
        }
        // If executor not available, mark as available (will fail on actual use)
        return true;
    }

    /**
     * Get count of available agents.
     */
    public int getAvailableAgentCount() {
        return (int) agents.values().stream()
                .filter(AgentProvider::isAvailable)
                .count();
    }

    /**
     * Check if any agents are available.
     */
    public boolean hasAvailableAgents() {
        return agents.values().stream().anyMatch(AgentProvider::isAvailable);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // API AGENT PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════════

    private Path getConfigDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config");
    }

    private Path getApiAgentsConfigPath() {
        return getConfigDir().resolve(API_AGENTS_CONFIG_FILE);
    }

    /**
     * Load API agent configurations from ~/.kompile/config/api-agents.json.
     */
    private void loadApiAgentsFromConfig() {
        Path configPath = getApiAgentsConfigPath();
        if (!Files.exists(configPath)) {
            log.debug("No API agents config file found at {}", configPath);
            return;
        }

        try {
            List<ApiAgentConfig> configs = objectMapper.readValue(
                    configPath.toFile(),
                    new TypeReference<List<ApiAgentConfig>>() {});

            for (ApiAgentConfig config : configs) {
                AgentProvider agent = AgentProvider.builder()
                        .name(config.name)
                        .displayName(config.displayName)
                        .agentType(AgentType.API)
                        .endpointUrl(config.endpointUrl)
                        .apiKey(config.apiKey)
                        .modelName(config.modelName)
                        .temperature(config.temperature)
                        .maxTokens(config.maxTokens)
                        .description(config.description != null ? config.description : "OpenAI-compatible API endpoint")
                        .available(false)
                        .isDefault(config.isDefault)
                        .build();

                boolean available = checkApiAgentAvailability(agent);
                agent.setAvailable(available);
                agents.put(agent.getName(), agent);
                log.info("Loaded API agent '{}' from config, available: {}", agent.getName(), available);
            }

            log.info("Loaded {} API agents from config", configs.size());
        } catch (Exception e) {
            log.error("Failed to load API agents config: {}", e.getMessage());
        }
    }

    /**
     * Save all API agent configurations to ~/.kompile/config/api-agents.json.
     */
    private void saveApiAgentsToConfig() {
        try {
            Path configDir = getConfigDir();
            Files.createDirectories(configDir);

            List<ApiAgentConfig> configs = agents.values().stream()
                    .filter(AgentProvider::isApiAgent)
                    .map(agent -> {
                        ApiAgentConfig config = new ApiAgentConfig();
                        config.name = agent.getName();
                        config.displayName = agent.getDisplayName();
                        config.endpointUrl = agent.getEndpointUrl();
                        config.apiKey = agent.getApiKey();
                        config.modelName = agent.getModelName();
                        config.temperature = agent.getTemperature();
                        config.maxTokens = agent.getMaxTokens();
                        config.description = agent.getDescription();
                        config.isDefault = agent.isDefault();
                        return config;
                    })
                    .toList();

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(getApiAgentsConfigPath().toFile(), configs);

            log.info("Saved {} API agents to config", configs.size());
        } catch (Exception e) {
            log.error("Failed to save API agents config: {}", e.getMessage());
        }
    }

    /**
     * Internal config representation for JSON persistence.
     */
    private static class ApiAgentConfig {
        public String name;
        public String displayName;
        public String endpointUrl;
        public String apiKey;
        public String modelName;
        public double temperature = 0.7;
        public int maxTokens = 4096;
        public String description;
        public boolean isDefault;
    }
}
