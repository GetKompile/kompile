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
public class AgentProvider {

    private String name;
    private String displayName;
    private String command;
    private String skipPermissionsFlag;
    private boolean skipPermissions;
    private List<String> args;
    private Map<String, String> environment;
    private boolean available;
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

    // Interactive mode configuration
    private String interactivePromptPattern; // Regex pattern to detect when agent is waiting for input

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

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getSkipPermissionsFlag() {
        return skipPermissionsFlag;
    }

    public void setSkipPermissionsFlag(String skipPermissionsFlag) {
        this.skipPermissionsFlag = skipPermissionsFlag;
    }

    public boolean isSkipPermissions() {
        return skipPermissions;
    }

    public void setSkipPermissions(boolean skipPermissions) {
        this.skipPermissions = skipPermissions;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isMcpSupported() {
        return mcpSupported;
    }

    public void setMcpSupported(boolean mcpSupported) {
        this.mcpSupported = mcpSupported;
    }

    public String getMcpServerFlag() {
        return mcpServerFlag;
    }

    public void setMcpServerFlag(String mcpServerFlag) {
        this.mcpServerFlag = mcpServerFlag;
    }

    public String getMcpConfigFlag() {
        return mcpConfigFlag;
    }

    public void setMcpConfigFlag(String mcpConfigFlag) {
        this.mcpConfigFlag = mcpConfigFlag;
    }

    public String getMcpAllowToolsFlag() {
        return mcpAllowToolsFlag;
    }

    public void setMcpAllowToolsFlag(String mcpAllowToolsFlag) {
        this.mcpAllowToolsFlag = mcpAllowToolsFlag;
    }

    public String getHelpOutput() {
        return helpOutput;
    }

    public void setHelpOutput(String helpOutput) {
        this.helpOutput = helpOutput;
    }

    public String getInteractivePromptPattern() {
        return interactivePromptPattern;
    }

    public void setInteractivePromptPattern(String interactivePromptPattern) {
        this.interactivePromptPattern = interactivePromptPattern;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
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
