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
package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Discovers and caches available models for each CLI agent.
 * <p>
 * Model discovery is done by running the agent's model list command
 * (e.g., {@code opencode models}, {@code pi --list-models}) and parsing stdout.
 * For agents without a list command, well-known models are provided.
 * <p>
 * Model selections are persisted to {@code ~/.kompile/config/cli-llm-config.json}.
 */
@Service
public class CliAgentModelService {

    private static final Logger log = LoggerFactory.getLogger(CliAgentModelService.class);
    private static final int DISCOVERY_TIMEOUT_SECONDS = 15;

    private final AgentRegistryService agentRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private ExtractionLlmServiceRegistry extractionRegistry;

    // Cache: agentName -> list of discovered model IDs
    private final Map<String, List<String>> modelCache = new ConcurrentHashMap<>();

    // Well-known models for agents that lack a list command
    private static final Map<String, List<String>> WELL_KNOWN_MODELS = Map.of(
            "claude-cli", List.of(
                    "claude-fable-5", "claude-opus-4-8", "opus",
                    "claude-opus-4-7", "claude-sonnet-4-6", "sonnet",
                    "claude-opus-4-6", "claude-haiku-4-5-20251001", "haiku"
            ),
            "codex-cli", List.of(
                    "gpt-5.5", "gpt-5.4", "gpt-5.4-mini",
                    "gpt-5.3-codex", "gpt-5.3-codex-spark", "gpt-5.2-codex"
            ),
            "gemini-cli", List.of(
                    "gemini-3.1-pro", "gemini-3-flash",
                    "gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash"
            ),
            "qwen-cli", List.of(
                    "qwen3-coder", "qwen3-coder-next", "qwen3.7-max",
                    "qwen3.7-plus", "qwen3.6-plus"
            )
    );

    public CliAgentModelService(AgentRegistryService agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * Returned from model listing endpoints.
     */
    public record AgentModelInfo(
            String agentName,
            String displayName,
            boolean available,
            String currentModel,
            List<String> availableModels,
            String modelSource
    ) {}

    /**
     * Get available models for a specific agent.
     * Runs discovery if not cached yet.
     * Always re-checks availability live instead of using cached state.
     */
    public AgentModelInfo getModelsForAgent(String agentName, boolean refresh) {
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            return new AgentModelInfo(agentName, agentName, false, null, List.of(), "unknown");
        }

        AgentProvider agent = agentOpt.get();

        if (refresh) {
            modelCache.remove(agentName);
        }

        // Always re-check availability live — don't rely on cached startup state
        boolean available = agentRegistry.checkAgentAvailability(agentName);

        List<String> models = modelCache.computeIfAbsent(agentName, k -> discoverModels(agent));
        String currentModel = getCurrentModel(agentName);
        String source = agent.getModelListCommand() != null && !agent.getModelListCommand().isEmpty()
                ? "discovered" : "well-known";

        return new AgentModelInfo(
                agentName,
                agent.getDisplayName(),
                available,
                currentModel,
                models,
                source
        );
    }

    /**
     * Get model info for all registered CLI agents.
     */
    public List<AgentModelInfo> getAllAgentModels(boolean refresh) {
        List<AgentModelInfo> result = new ArrayList<>();
        for (AgentProvider agent : agentRegistry.getAllAgents()) {
            if (!agent.isApiAgent()) {
                result.add(getModelsForAgent(agent.getName(), refresh));
            }
        }
        return result;
    }

    /**
     * Set the model for a specific agent. Persists to cli-llm-config.json
     * and applies runtime override to extraction registry.
     */
    public boolean setAgentModel(String agentName, String model) {
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) return false;

        // Persist to config file
        persistModelSelection(agentName, model);

        // Apply runtime override to the extraction registry
        if (extractionRegistry != null) {
            extractionRegistry.setProviderModel(agentName, model);
        }

        // Also update the agent's model name in the registry
        AgentProvider agent = agentOpt.get();
        agent.setModelName(model);

        log.info("Set model for agent '{}': {}", agentName, model);
        return true;
    }

    /**
     * Get the currently configured model for an agent (from config file).
     */
    public String getCurrentModel(String agentName) {
        try {
            JsonNode config = readCliLlmConfig();
            if (config == null) return null;

            // Check per-agent model first
            JsonNode agentModels = config.get("agentModels");
            if (agentModels != null && agentModels.has(agentName)) {
                JsonNode val = agentModels.get(agentName);
                if (!val.isNull() && !val.asText("").isBlank()) {
                    return val.asText();
                }
            }

            // Fall back to global model
            JsonNode globalModel = config.get("model");
            if (globalModel != null && !globalModel.isNull() && !globalModel.asText("").isBlank()) {
                return globalModel.asText();
            }
        } catch (Exception e) {
            log.debug("Could not read current model for agent {}: {}", agentName, e.getMessage());
        }
        return null;
    }

    /**
     * Discover models for an agent, either via its list command or well-known list.
     */
    private List<String> discoverModels(AgentProvider agent) {
        List<String> modelListCommand = agent.getModelListCommand();

        // If agent has a model list command, try to run it
        if (modelListCommand != null && !modelListCommand.isEmpty()) {
            try {
                List<String> discovered = runModelListCommand(modelListCommand);
                if (!discovered.isEmpty()) {
                    log.info("Discovered {} models for agent '{}'", discovered.size(), agent.getName());
                    return discovered;
                }
            } catch (Exception e) {
                log.warn("Model discovery failed for '{}': {}", agent.getName(), e.getMessage());
            }
        }

        // Fall back to well-known models
        List<String> wellKnown = WELL_KNOWN_MODELS.get(agent.getName());
        if (wellKnown != null) {
            log.debug("Using well-known model list for agent '{}' ({} models)", agent.getName(), wellKnown.size());
            return wellKnown;
        }

        return List.of();
    }

    /**
     * Run a model list command and parse one model per line from stdout.
     */
    private List<String> runModelListCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> models = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                    models.add(trimmed);
                }
            }
        }

        boolean completed = process.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new TimeoutException("Model list command timed out after " + DISCOVERY_TIMEOUT_SECONDS + "s");
        }

        return models;
    }

    /**
     * Persist a model selection to ~/.kompile/config/cli-llm-config.json.
     */
    private void persistModelSelection(String agentName, String model) {
        try {
            Path configPath = getConfigPath();
            ObjectNode config;
            if (Files.exists(configPath)) {
                config = (ObjectNode) objectMapper.readTree(configPath.toFile());
            } else {
                Files.createDirectories(configPath.getParent());
                config = objectMapper.createObjectNode();
            }

            // Ensure agentModels section exists
            ObjectNode agentModels;
            if (config.has("agentModels") && config.get("agentModels").isObject()) {
                agentModels = (ObjectNode) config.get("agentModels");
            } else {
                agentModels = objectMapper.createObjectNode();
                config.set("agentModels", agentModels);
            }

            // Set the model (null to clear)
            if (model == null || model.isBlank()) {
                agentModels.putNull(agentName);
            } else {
                agentModels.put(agentName, model);
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
            log.info("Persisted model selection for '{}': {} -> {}", agentName, model, configPath);
        } catch (Exception e) {
            log.error("Failed to persist model selection for '{}': {}", agentName, e.getMessage(), e);
        }
    }

    private JsonNode readCliLlmConfig() {
        try {
            Path configPath = getConfigPath();
            if (Files.exists(configPath)) {
                return objectMapper.readTree(configPath.toFile());
            }
        } catch (Exception e) {
            log.debug("Could not read cli-llm-config.json: {}", e.getMessage());
        }
        return null;
    }

    private Path getConfigPath() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
    }
}
