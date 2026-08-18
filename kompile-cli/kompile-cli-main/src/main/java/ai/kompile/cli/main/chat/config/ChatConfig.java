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
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Persisted chat configuration stored at either project-local
 * {@code .kompile/chat-config.json} or global {@code ~/.kompile/chat-config.json}.
 * Contains standard provider settings and passthrough CLI-agent preferences.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatConfig {

    private static final String CONFIG_FILE = "chat-config.json";
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonProperty
    private String provider; // kompile, kompile-local, openai, anthropic, gemini, ollama, custom

    /**
     * Legacy/in-memory API key input. It is accepted when reading older config
     * files but is never written back; persisted secrets live in auth.json.
     */
    @JsonProperty(value = "apiKey", access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    @JsonProperty
    private String model;

    /**
     * User-added model ids keyed by provider. The built-in catalog remains the
     * source of defaults; this overlay lets the picker discover additional
     * upstream ids without hardcoding them in the application.
     */
    @JsonProperty
    private Map<String, List<String>> modelCatalog = new LinkedHashMap<>();

    /**
     * Optional reasoning effort for standard direct-model chat. A null/blank
     * value leaves the provider's model default unchanged.
     */
    @JsonProperty
    private String thinking;

    @JsonProperty
    private String baseUrl; // null = use provider default

    /** Provider-neutral automatic context compaction policy. */
    @JsonProperty
    private boolean autoCompactEnabled = true;

    /** Fraction of the active model context at which compaction may begin. */
    @JsonProperty
    private double autoCompactThreshold = 0.85d;

    /** Explicit input headroom; zero derives it from the active model output limit. */
    @JsonProperty
    private int compactionReserveTokens = 0;

    /** Optional provider/model context override; zero uses catalog or local serving metadata. */
    @JsonProperty
    private int contextWindowTokens = 0;

    /** Optional provider/model output override; zero uses catalog or local serving metadata. */
    @JsonProperty
    private int maxOutputTokens = 0;

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

    /**
     * The setup wizard's per-session answer to "Enable rule enforcement?".
     * Transient (NOT persisted): it reflects only THIS run's explicit choice so the
     * router can honor an opt-out even when a project {@code .kompile/enforcer-config.json}
     * is present. {@code null} = not asked this run -> fall back to project auto-detection.
     */
    @JsonIgnore
    private Boolean enforcementEnabled;

    @JsonIgnore
    private Path loadedFrom;

    public enum Scope {
        PROJECT,
        GLOBAL
    }

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

    /**
     * Resolve request credentials in Pi-compatible priority order: an explicit
     * in-memory value, the managed credential store, then the provider's
     * environment variable. This computed value is never serialized.
     */
    @JsonIgnore
    public String getApiKey() {
        OAuthProviderFlow.RequestAuth auth = resolveRequestAuth();
        return auth == null ? null : auth.token();
    }

    /** Resolve and, when necessary, refresh the provider's request credential. */
    @JsonIgnore
    public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
        if (apiKey != null && !apiKey.isBlank()) {
            return OAuthProviderFlow.RequestAuth.apiKey(apiKey);
        }
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            OAuthProviderFlow.RequestAuth stored = OAuthCredentialManager.create().resolve(provider);
            if (stored != null) {
                return stored;
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not resolve managed credentials for "
                    + provider + ": " + e.getMessage());
        }
        String environmentName = getEnvironmentVariable(provider);
        if (environmentName == null) {
            return null;
        }
        String value = System.getenv(environmentName);
        return value == null || value.isBlank()
                ? null
                : OAuthProviderFlow.RequestAuth.apiKey(value);
    }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    /** Return the persisted user model overlay keyed by provider. */
    public Map<String, List<String>> getModelCatalog() {
        if (modelCatalog == null) {
            modelCatalog = new LinkedHashMap<>();
        }
        return modelCatalog;
    }

    public void setModelCatalog(Map<String, List<String>> modelCatalog) {
        this.modelCatalog = modelCatalog == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(modelCatalog);
    }

    /**
     * Merge the startup catalog with user-added model ids for this provider.
     * Provider matching is case-insensitive because wire provider ids may come
     * from an auth route rather than the display catalog.
     */
    public List<String> getConfiguredModels(String provider) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (String defaultModel : getDefaultModels(provider)) {
            if (defaultModel != null && !defaultModel.isBlank()) {
                models.add(defaultModel);
            }
        }
        if (provider != null && modelCatalog != null) {
            for (Map.Entry<String, List<String>> entry : modelCatalog.entrySet()) {
                if (!provider.equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                for (String addedModel : entry.getValue()) {
                    if (addedModel != null && !addedModel.isBlank()) {
                        models.add(addedModel.trim());
                    }
                }
            }
        }
        return List.copyOf(models);
    }

    /** Add a model id to the provider overlay unless it is already known. */
    public boolean addModelToCatalog(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        String providerId = provider.trim();
        String modelId = model.trim();
        if (getConfiguredModels(providerId).contains(modelId)) {
            return false;
        }
        List<String> added = getModelCatalog().computeIfAbsent(providerId, ignored -> new ArrayList<>());
        if (added.contains(modelId)) {
            return false;
        }
        added.add(modelId);
        return true;
    }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
    public void setAutoCompactEnabled(boolean autoCompactEnabled) {
        this.autoCompactEnabled = autoCompactEnabled;
    }

    public double getAutoCompactThreshold() {
        return sanitizeAutoCompactThreshold(autoCompactThreshold);
    }
    public void setAutoCompactThreshold(double autoCompactThreshold) {
        this.autoCompactThreshold = sanitizeAutoCompactThreshold(autoCompactThreshold);
    }

    public int getCompactionReserveTokens() { return Math.max(0, compactionReserveTokens); }
    public void setCompactionReserveTokens(int compactionReserveTokens) {
        this.compactionReserveTokens = Math.max(0, compactionReserveTokens);
    }

    public int getContextWindowTokens() { return Math.max(0, contextWindowTokens); }
    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = Math.max(0, contextWindowTokens);
    }

    public int getMaxOutputTokens() { return Math.max(0, maxOutputTokens); }
    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    private static double sanitizeAutoCompactThreshold(double threshold) {
        if (!Double.isFinite(threshold)) return 0.85d;
        return Math.max(0.50d, Math.min(0.95d, threshold));
    }

    /**
     * Hot-apply only the live LLM settings from another configuration.
     * Keeping this object identity lets the active direct client, tool registry,
     * and performance harness observe a provider switch without rebuilding the
     * REPL or changing its session/transcript state.
     */
    public void applyLlmSettingsFrom(ChatConfig source) {
        if (source == null) {
            throw new IllegalArgumentException("Source chat configuration is required");
        }
        this.provider = source.provider;
        this.apiKey = source.apiKey;
        this.model = source.model;
        this.thinking = source.thinking;
        this.baseUrl = source.baseUrl;
        // These limits describe the selected provider/model. The provider-neutral
        // enable/threshold/reserve policy intentionally remains session-wide.
        this.contextWindowTokens = source.contextWindowTokens;
        this.maxOutputTokens = source.maxOutputTokens;
        this.modelCatalog = new LinkedHashMap<>();
        if (source.modelCatalog != null) {
            source.modelCatalog.forEach((providerId, models) ->
                    this.modelCatalog.put(providerId,
                            models == null ? new ArrayList<>() : new ArrayList<>(models)));
        }
        this.loadedFrom = source.loadedFrom;
    }

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

    public Boolean getEnforcementEnabled() { return enforcementEnabled; }
    public void setEnforcementEnabled(Boolean enforcementEnabled) { this.enforcementEnabled = enforcementEnabled; }

    public Path getLoadedFrom() { return loadedFrom; }

    /**
     * Resolve the actual API base URL for the configured provider.
     */
    public String resolveBaseUrl() {
        return resolveBaseUrl(resolveRequestAuth());
    }

    public String resolveBaseUrl(OAuthProviderFlow.RequestAuth auth) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        if (auth != null && auth.baseUrl() != null && !auth.baseUrl().isBlank()) {
            return auth.baseUrl();
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
        // Kompile instance mode doesn't need model or API key.
        if ("kompile".equals(provider)) return true;
        if (model == null || model.isBlank()) return false;
        // First-party Kompile serving and external local endpoints do not require an API key.
        if ("kompile-local".equals(provider)
                || "ollama".equals(provider)
                || "custom".equals(provider)) return true;
        String resolvedApiKey = getApiKey();
        return resolvedApiKey != null && !resolvedApiKey.isBlank();
    }

    /**
     * Whether this provider connects to a kompile-app instance (server mode).
     */
    @JsonIgnore
    public boolean isKompileServer() {
        return "kompile".equals(provider);
    }

    /**
     * Whether this config owns Kompile's first-party serving subprocess.
     */
    @JsonIgnore
    public boolean isKompileLocalServing() {
        return "kompile-local".equals(provider);
    }

    // --- Static helpers ---

    public static String getDefaultBaseUrl(String provider) {
        if (provider == null) return null;
        switch (provider.toLowerCase()) {
            // Server mode talks to /api/agents/chat, which kompile-app-chat owns.
            case "kompile":       return KompileServiceEndpoints.resolve(KompileService.CHAT).baseUrl();
            // The bootstrap assigns a private loopback URL for each local chat session.
            case "kompile-local": return null;
            case "openai":     return "https://api.openai.com/v1";
            case "anthropic":  return "https://api.anthropic.com";
            case "gemini":     return "https://generativelanguage.googleapis.com/v1beta/openai";
            case "ollama":     return "http://localhost:11434/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "xai":        return "https://api.x.ai/v1";
            case "github-copilot": return "https://api.individual.githubcopilot.com";
            case "openai-codex": return "https://chatgpt.com/backend-api";
            case "radius":     return "https://radius.pi.dev";
            case "deepseek":   return "https://api.deepseek.com/v1";
            case "groq":       return "https://api.groq.com/openai/v1";
            default:           return null;
        }
    }

    public static String[] getDefaultModels(String provider) {
        if (provider == null) return new String[0];
        switch (provider.toLowerCase()) {
            case "kompile":       return new String[0]; // instance uses server-side agents
            case "kompile-local": return new String[]{
                    "Qwen2.5-0.5B-Instruct", "Qwen2.5-1.5B-Instruct"};
            case "openai":     return new String[]{"gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"};
            case "anthropic":  return new String[]{"claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-4-20250514"};
            case "gemini":     return new String[]{"gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"};
            case "ollama":     return new String[]{"llama3.3", "qwen2.5-coder:32b", "codellama:34b", "deepseek-coder-v2"};
            case "openrouter": return new String[]{"anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-pro"};
            case "xai":        return new String[]{"grok-4", "grok-4-fast-reasoning"};
            case "github-copilot": return new String[]{
                    "gpt-5.6-terra", "gpt-5.4", "gpt-4.1",
                    "claude-sonnet-4.6", "gemini-3.1-pro-preview"};
            case "openai-codex": return new String[]{
                    "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-luna",
                    "gpt-5.5", "gpt-5.4", "gpt-5.4-mini",
                    "gpt-5.3-codex-spark"};
            case "radius":     return new String[0]; // loaded dynamically from /v1/config
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

    /** Whether this provider uses OpenAI's Responses protocol through ChatGPT. */
    @JsonIgnore
    public boolean isOpenAiCodexFormat() {
        return "openai-codex".equals(provider);
    }

    /** Whether this provider uses Pi's native messages protocol. */
    @JsonIgnore
    public boolean isPiMessagesFormat() {
        return "radius".equals(provider);
    }

    /**
     * Whether this provider uses OpenAI-compatible Chat Completions format.
     */
    @JsonIgnore
    public boolean isOpenAiCompatible() {
        return !isAnthropicFormat() && !isOpenAiCodexFormat() && !isPiMessagesFormat();
    }

    // --- Persistence ---

    public static Path globalConfigPath() {
        return KompileHome.homeDirectory().toPath().resolve(CONFIG_FILE);
    }

    public static Path defaultProjectRoot() {
        return KompileHome.resolvedProjectDirectory().toPath().toAbsolutePath().normalize();
    }

    public static Path projectConfigPath(Path projectRoot) {
        Path root = projectRoot != null ? projectRoot : defaultProjectRoot();
        return root.toAbsolutePath().normalize().resolve(".kompile").resolve(CONFIG_FILE);
    }

    public static Path defaultProjectConfigPath() {
        return projectConfigPath(defaultProjectRoot());
    }

    public static Path configPath(Scope scope, Path projectRoot) {
        return scope == Scope.GLOBAL ? globalConfigPath() : projectConfigPath(projectRoot);
    }

    public static Path configPath(Scope scope) {
        return configPath(scope, defaultProjectRoot());
    }

    public static boolean exists() {
        return existsGlobal();
    }

    public static boolean existsGlobal() {
        return Files.exists(globalConfigPath());
    }

    public static boolean existsProject(Path projectRoot) {
        return Files.exists(projectConfigPath(projectRoot));
    }

    public static boolean existsProject() {
        return existsProject(defaultProjectRoot());
    }

    public static ChatConfig load() {
        return loadGlobal();
    }

    public static ChatConfig loadGlobal() {
        return loadFrom(globalConfigPath());
    }

    public static ChatConfig loadProject(Path projectRoot) {
        return loadFrom(projectConfigPath(projectRoot));
    }

    public static ChatConfig loadProject() {
        return loadProject(defaultProjectRoot());
    }

    public static ChatConfig loadEffective() {
        return loadEffective(defaultProjectRoot());
    }

    public static ChatConfig loadEffective(Path projectRoot) {
        ChatConfig project = loadProject(projectRoot);
        if (project != null && project.isValid()) {
            return project;
        }
        return loadGlobal();
    }

    private static ChatConfig loadFrom(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            ChatConfig config = MAPPER.readValue(path.toFile(), ChatConfig.class);
            config.loadedFrom = path.toAbsolutePath().normalize();
            config.migrateLegacyApiKey(path);
            return config;
        } catch (IOException e) {
            System.err.println("Warning: Could not load chat config: " + e.getMessage());
            return null;
        }
    }

    public void save() throws IOException {
        saveGlobal();
    }

    /** Persist back to the scope this config was loaded from, or globally if new. */
    public void saveLoadedOrGlobal() throws IOException {
        if (loadedFrom != null) {
            saveTo(loadedFrom);
        } else {
            saveGlobal();
        }
    }

    public void saveGlobal() throws IOException {
        saveTo(globalConfigPath());
    }

    public void saveProject(Path projectRoot) throws IOException {
        saveTo(projectConfigPath(projectRoot));
    }

    public void save(Scope scope, Path projectRoot) throws IOException {
        saveTo(configPath(scope, projectRoot));
    }

    private void saveTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        String transientApiKey = apiKey;
        if (transientApiKey != null && !transientApiKey.isBlank()
                && provider != null && !provider.isBlank()) {
            CredentialStore.create().putApiKey(provider, transientApiKey);
            apiKey = null;
        }
        try {
            MAPPER.writeValue(path.toFile(), this);
            loadedFrom = path.toAbsolutePath().normalize();
        } catch (IOException e) {
            apiKey = transientApiKey;
            throw e;
        }
    }

    /**
     * Import a plaintext API key from an older chat-config.json into the private
     * managed store and scrub it from the original config file.
     */
    private void migrateLegacyApiKey(Path path) {
        if (apiKey == null || apiKey.isBlank() || provider == null || provider.isBlank()) {
            return;
        }
        String legacyApiKey = apiKey;
        try {
            CredentialStore.create().putApiKey(provider, legacyApiKey);
            apiKey = null;
            MAPPER.writeValue(path.toFile(), this);
        } catch (IOException e) {
            apiKey = legacyApiKey;
            System.err.println("Warning: Could not migrate legacy API key from "
                    + path + " to managed credential storage: " + e.getMessage());
        }
    }

    /**
     * Load config, falling back to environment variables if no config file.
     */
    public static ChatConfig loadOrFromEnv() {
        return loadOrFromEnv(defaultProjectRoot());
    }

    public static ChatConfig loadOrFromEnv(Path projectRoot) {
        ChatConfig config = loadEffective(projectRoot);
        if (config != null && config.isValid()) {
            return config;
        }
        return fromEnv();
    }

    public static ChatConfig loadGlobalOrFromEnv() {
        ChatConfig config = loadGlobal();
        if (config != null && config.isValid()) {
            return config;
        }
        return fromEnv();
    }

    private static String getEnvironmentVariable(String provider) {
        if (provider == null) return null;
        return switch (provider.toLowerCase()) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GOOGLE_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            case "xai" -> "XAI_API_KEY";
            case "github-copilot" -> "COPILOT_GITHUB_TOKEN";
            case "radius" -> "RADIUS_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            default -> null;
        };
    }

    private static ChatConfig fromEnv() {
        ChatConfig config = new ChatConfig();

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

        return config.isValid() ? config : null;
    }

    // Available provider names for the setup wizard
    public static final Map<String, String> PROVIDERS = Map.ofEntries(
            Map.entry("kompile", "Kompile (connect to a running kompile-app instance)"),
            Map.entry("kompile-local", "Kompile Local (first-party serving subprocess)"),
            Map.entry("openai", "OpenAI (GPT-4o, o4-mini)"),
            Map.entry("anthropic", "Anthropic (Claude Sonnet/Opus)"),
            Map.entry("gemini", "Google Gemini (2.5 Pro/Flash)"),
            Map.entry("ollama", "Ollama (local models, no API key needed)"),
            Map.entry("custom", "OpenAI-compatible endpoint"),
            Map.entry("openrouter", "OpenRouter (multi-provider gateway)"),
            Map.entry("xai", "xAI (Grok)"),
            Map.entry("github-copilot", "GitHub Copilot"),
            Map.entry("openai-codex", "OpenAI Codex (ChatGPT Plus/Pro)"),
            Map.entry("radius", "Radius (dynamic Pi gateway)"),
            Map.entry("deepseek", "DeepSeek (DeepSeek-V3/Coder)"),
            Map.entry("groq", "Groq (fast inference)")
    );

    // Ordered list for display — kompile first
    public static final String[] PROVIDER_ORDER = {
            "kompile", "anthropic", "openai", "gemini", "ollama", "openrouter", "xai",
            "github-copilot", "openai-codex", "radius", "deepseek", "groq"
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
