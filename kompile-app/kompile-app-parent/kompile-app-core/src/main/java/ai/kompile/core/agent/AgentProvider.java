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

package ai.kompile.core.agent;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for an AI agent provider.
 * <p>
 * Supports CLI agents (Claude Code, Codex, Gemini CLI) and
 * API agents (OpenAI-compatible endpoints like OpenAI, Ollama, vLLM, LM Studio).
 */
@Getter
@Setter
public class AgentProvider {

    private String name;
    private String displayName;
    private String command;
    private String skipPermissionsFlag;
    private boolean skipPermissions;
    private List<String> args;
    private Map<String, String> environment;
    private boolean available;
    @Getter(value = lombok.AccessLevel.NONE)
    @Setter(value = lombok.AccessLevel.NONE)
    private boolean isDefault;
    private String description;

    // Agent type: CLI (subprocess) or API (OpenAI-compatible endpoint)
    private AgentType agentType = AgentType.CLI;

    // API agent configuration (only used when agentType == API)
    private String endpointUrl;             // e.g., "https://api.openai.com/v1" or "http://localhost:11434/v1"
    private String apiKey;                  // API key for authentication
    private String modelName;               // e.g., "gpt-4o", "llama3", "mistral"
    private double temperature = 0.7;
    private int maxTokens = 4096;

    // Model discovery: command to list available models for this agent
    private List<String> modelListCommand;

    // CLI model selection: the flag used to pass a model to the CLI agent (e.g. "--model").
    // Loaded from cli-agents.json; the chosen model itself is carried in {@link #modelName}.
    private String modelFlag;

    // Interactive mode configuration
    private String interactivePromptPattern; // Regex pattern to detect when agent is waiting for input

    // Stream parsing mode (from cli-agents.json "outputMode"): e.g. "stream-json",
    // "tui-scrape", "kompile-json". Drives how subprocess stdout is parsed into SSE events.
    private String outputMode;

    // MCP Server configuration
    private boolean mcpSupported;
    private String mcpServerFlag;           // e.g., "--mcp-server" for Claude
    private String mcpConfigFlag;           // e.g., "--mcp-config" for config file path
    private String mcpAllowToolsFlag;       // e.g., "--allowedTools" for specifying allowed tools
    private String helpOutput;              // Cached --help output for reference

    public AgentProvider() {
        this.args = new ArrayList<>();
        this.environment = new HashMap<>();
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AgentProvider provider = new AgentProvider();

        public Builder name(String name) {
            provider.name = name;
            return this;
        }

        public Builder displayName(String displayName) {
            provider.displayName = displayName;
            return this;
        }

        public Builder command(String command) {
            provider.command = command;
            return this;
        }

        public Builder skipPermissionsFlag(String flag) {
            provider.skipPermissionsFlag = flag;
            return this;
        }

        public Builder skipPermissions(boolean skip) {
            provider.skipPermissions = skip;
            return this;
        }

        public Builder args(List<String> args) {
            provider.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
            return this;
        }

        public Builder addArg(String arg) {
            provider.args.add(arg);
            return this;
        }

        public Builder environment(Map<String, String> env) {
            provider.environment = env != null ? new HashMap<>(env) : new HashMap<>();
            return this;
        }

        public Builder addEnv(String key, String value) {
            provider.environment.put(key, value);
            return this;
        }

        public Builder available(boolean available) {
            provider.available = available;
            return this;
        }

        public Builder isDefault(boolean isDefault) {
            provider.isDefault = isDefault;
            return this;
        }

        public Builder description(String description) {
            provider.description = description;
            return this;
        }

        public Builder interactivePromptPattern(String pattern) {
            provider.interactivePromptPattern = pattern;
            return this;
        }

        public Builder outputMode(String outputMode) {
            provider.outputMode = outputMode;
            return this;
        }

        public Builder mcpSupported(boolean supported) {
            provider.mcpSupported = supported;
            return this;
        }

        public Builder mcpServerFlag(String flag) {
            provider.mcpServerFlag = flag;
            return this;
        }

        public Builder mcpConfigFlag(String flag) {
            provider.mcpConfigFlag = flag;
            return this;
        }

        public Builder mcpAllowToolsFlag(String flag) {
            provider.mcpAllowToolsFlag = flag;
            return this;
        }

        public Builder helpOutput(String output) {
            provider.helpOutput = output;
            return this;
        }

        public Builder agentType(AgentType agentType) {
            provider.agentType = agentType;
            return this;
        }

        public Builder endpointUrl(String endpointUrl) {
            provider.endpointUrl = endpointUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            provider.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            provider.modelName = modelName;
            return this;
        }

        public Builder modelFlag(String modelFlag) {
            provider.modelFlag = modelFlag;
            return this;
        }

        public Builder modelListCommand(List<String> modelListCommand) {
            provider.modelListCommand = modelListCommand;
            return this;
        }

        public Builder temperature(double temperature) {
            provider.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            provider.maxTokens = maxTokens;
            return this;
        }

        public AgentProvider build() {
            return provider;
        }
    }

    // Safe accessors that never return null
    public List<String> safeArgs() {
        return args != null ? Collections.unmodifiableList(args) : Collections.emptyList();
    }

    public Map<String, String> safeEnvironment() {
        return environment != null ? Collections.unmodifiableMap(environment) : Collections.emptyMap();
    }

    // Manual accessor for 'isDefault' field to avoid Lombok generating 'isIsDefault()'
    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isApiAgent() {
        return agentType == AgentType.API;
    }

    /**
     * Get the API key with masking for display purposes.
     * Shows first 4 chars + **** for security.
     */
    public String getMaskedApiKey() {
        if (apiKey == null || apiKey.length() <= 4) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****";
    }

    @Override
    public String toString() {
        return "AgentProvider{" +
                "name='" + name + '\'' +
                ", displayName='" + displayName + '\'' +
                ", agentType=" + agentType +
                ", command='" + command + '\'' +
                ", available=" + available +
                ", isDefault=" + isDefault +
                ", mcpSupported=" + mcpSupported +
                '}';
    }
}
