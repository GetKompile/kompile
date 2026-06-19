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

package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted chat configuration stored at ~/.kompile/chat-config.json.
 * Contains LLM provider settings, API keys, and default preferences.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatConfig {

    private static final String CONFIG_FILE = "chat-config.json";
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty
    private String provider; // kompile, openai, anthropic, gemini, ollama, openrouter, custom

    @JsonProperty
    private String apiKey;

    @JsonProperty
    private String model;

    @JsonProperty
    private String baseUrl; // null = use provider default

    @JsonProperty
    private String defaultAgent = "coder";

    @JsonProperty
    private boolean defaultRag = false;

    @JsonProperty
    private boolean defaultMemory = true;

    @JsonProperty
    private String cancelKey = "ESCAPE";

    @JsonProperty
    private String chatMode = "standard"; // "standard" or "passthrough"

    @JsonProperty
    private String passthroughAgent = "claude"; // claude, codex, gemini, qwen, opencode

    @JsonProperty
    private boolean passthroughManaged = true; // true = kompile REPL wraps agent subprocess

    public ChatConfig() {}

    public ChatConfig(String provider, String apiKey, String model, String baseUrl) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    // --- Getters/Setters ---

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getDefaultAgent() { return defaultAgent; }
    public void setDefaultAgent(String defaultAgent) { this.defaultAgent = defaultAgent; }

    public boolean isDefaultRag() { return defaultRag; }
    public void setDefaultRag(boolean defaultRag) { this.defaultRag = defaultRag; }

    public boolean isDefaultMemory() { return defaultMemory; }
    public void setDefaultMemory(boolean defaultMemory) { this.defaultMemory = defaultMemory; }

    public String getCancelKey() { return cancelKey; }
    public void setCancelKey(String cancelKey) { this.cancelKey = cancelKey; }

    public String getChatMode() { return chatMode; }
    public void setChatMode(String chatMode) { this.chatMode = chatMode; }

    public String getPassthroughAgent() { return passthroughAgent; }
    public void setPassthroughAgent(String passthroughAgent) { this.passthroughAgent = passthroughAgent; }

    public boolean isPassthroughManaged() { return passthroughManaged; }
    public void setPassthroughManaged(boolean passthroughManaged) { this.passthroughManaged = passthroughManaged; }

    /**
     * Resolve the actual API base URL for the configured provider.
     */
    public String resolveBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        return getDefaultBaseUrl(provider);
    }

    /**
     * Check if this config has enough info to make LLM calls.
     */
    @JsonIgnore
    public boolean isValid() {
        // Passthrough mode doesn't need provider/model/key - the agent handles its own auth
        if ("passthrough".equals(chatMode)) return true;
        if (provider == null || provider.isBlank()) return false;
        // Kompile server mode doesn't need model or API key
        if ("kompile".equals(provider)) return true;
        if (model == null || model.isBlank()) return false;
        // Ollama doesn't need an API key
        if ("ollama".equals(provider)) return true;
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Whether this provider connects to a kompile-app instance (server mode).
     */
    @JsonIgnore
    public boolean isKompileServer() {
        return "kompile".equals(provider);
    }

    // --- Static helpers ---

    public static String getDefaultBaseUrl(String provider) {
        if (provider == null) return null;
        switch (provider.toLowerCase()) {
            case "kompile":    return "http://localhost:8080";
            case "openai":     return "https://api.openai.com/v1";
            case "anthropic":  return "https://api.anthropic.com";
            case "gemini":     return "https://generativelanguage.googleapis.com/v1beta/openai";
            case "ollama":     return "http://localhost:11434/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "deepseek":   return "https://api.deepseek.com/v1";
            case "groq":       return "https://api.groq.com/openai/v1";
            default:           return null;
        }
    }

    public static String[] getDefaultModels(String provider) {
        if (provider == null) return new String[0];
        switch (provider.toLowerCase()) {
            case "kompile":    return new String[0]; // kompile uses server-side agents, not model names
            case "openai":     return new String[]{"gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"};
            case "anthropic":  return new String[]{"claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-4-20250514"};
            case "gemini":     return new String[]{"gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"};
            case "ollama":     return new String[]{"llama3.3", "qwen2.5-coder:32b", "codellama:34b", "deepseek-coder-v2"};
            case "openrouter": return new String[]{"anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-pro"};
            case "deepseek":   return new String[]{"deepseek-chat", "deepseek-coder", "deepseek-reasoner"};
            case "groq":       return new String[]{"llama-3.3-70b-versatile", "mixtral-8x7b-32768"};
            default:           return new String[0];
        }
    }

    /**
     * Whether this provider uses the Anthropic Messages API format
     * (vs OpenAI Chat Completions format).
     */
    @JsonIgnore
    public boolean isAnthropicFormat() {
        return "anthropic".equals(provider);
    }

    /**
     * Whether this provider uses OpenAI-compatible Chat Completions format.
     */
    @JsonIgnore
    public boolean isOpenAiCompatible() {
        return !isAnthropicFormat();
    }

    // --- Persistence ---

    private static Path configPath() {
        return KompileHome.homeDirectory().toPath().resolve(CONFIG_FILE);
    }

    public static boolean exists() {
        return Files.exists(configPath());
    }

    public static ChatConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), ChatConfig.class);
        } catch (IOException e) {
            System.err.println("Warning: Could not load chat config: " + e.getMessage());
            return null;
        }
    }

    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), this);
    }

    /**
     * Load config, falling back to environment variables if no config file.
     */
    public static ChatConfig loadOrFromEnv() {
        ChatConfig config = load();
        if (config != null && config.isValid()) {
            return config;
        }

        // Try environment variables
        config = new ChatConfig();

        String openaiKey = System.getenv("OPENAI_API_KEY");
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String geminiKey = System.getenv("GOOGLE_API_KEY");

        if (anthropicKey != null && !anthropicKey.isBlank()) {
            config.setProvider("anthropic");
            config.setApiKey(anthropicKey);
            config.setModel("claude-sonnet-4-20250514");
        } else if (openaiKey != null && !openaiKey.isBlank()) {
            config.setProvider("openai");
            config.setApiKey(openaiKey);
            config.setModel("gpt-4o");
        } else if (geminiKey != null && !geminiKey.isBlank()) {
            config.setProvider("gemini");
            config.setApiKey(geminiKey);
            config.setModel("gemini-2.5-flash");
        }

        if (config.isValid()) {
            return config;
        }

        return null;
    }

    // Available provider names for the setup wizard
    public static final Map<String, String> PROVIDERS = Map.ofEntries(
            Map.entry("kompile", "Kompile (connect to a running kompile-app instance)"),
            Map.entry("openai", "OpenAI (GPT-4o, o4-mini)"),
            Map.entry("anthropic", "Anthropic (Claude Sonnet/Opus)"),
            Map.entry("gemini", "Google Gemini (2.5 Pro/Flash)"),
            Map.entry("ollama", "Ollama (local models, no API key needed)"),
            Map.entry("openrouter", "OpenRouter (multi-provider gateway)"),
            Map.entry("deepseek", "DeepSeek (DeepSeek-V3/Coder)"),
            Map.entry("groq", "Groq (fast inference)")
    );

    // Ordered list for display — kompile first
    public static final String[] PROVIDER_ORDER = {
            "kompile", "anthropic", "openai", "gemini", "ollama", "openrouter", "deepseek", "groq"
    };

    // Available passthrough agents — derived from CliAgentRegistry (single source of truth).
    // Computed lazily to avoid baking empty results into native image heap at build time.
    public static Map<String, String> getPassthroughAgents() {
        Map<String, String> agents = new LinkedHashMap<>();
        for (AgentProvider p : CliAgentRegistry.loadAll()) {
            agents.put(p.getCommand(), p.getDisplayName());
        }
        return agents;
    }

    public static List<String> getPassthroughAgentOrder() {
        return new ArrayList<>(getPassthroughAgents().keySet());
    }

    public static boolean isValidPassthroughAgent(String agent) {
        return getPassthroughAgents().containsKey(agent.toLowerCase());
    }

    @Override
    public String toString() {
        return "ChatConfig{provider=" + provider +
                ", model=" + model +
                ", baseUrl=" + (baseUrl != null ? baseUrl : "(default)") +
                ", hasApiKey=" + (apiKey != null && !apiKey.isBlank()) + "}";
    }
}
