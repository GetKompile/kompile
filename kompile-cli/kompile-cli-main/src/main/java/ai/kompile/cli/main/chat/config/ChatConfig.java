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
    private String provider; // kompile, kompile-local, opencode, openai, anthropic, gemini, ollama, custom

    /**
     * Legacy/in-memory API key input. It is accepted when reading older config
     * files but is never written back; persisted secrets live in auth.json.
     */
    @JsonProperty(value = "apiKey", access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    @JsonProperty
    private String model;

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

    /**
     * Legacy compatibility accessor. Model catalogs are no longer a source of
     * truth; providers own model discovery and this value is intentionally empty.
     */
    public Map<String, List<String>> getModelCatalog() {
        return Map.of();
    }

    public void setModelCatalog(Map<String, List<String>> modelCatalog) {
        // Kept as a no-op for older config readers. Runtime model discovery is authoritative.
    }

    /** Fetch the provider's current model ids from its live capability endpoint. */
    public List<String> getConfiguredModels(String provider) {
        String discoveryBaseUrl = provider != null && provider.equalsIgnoreCase(this.provider)
                ? getBaseUrl() : null;
        String discoveryApiKey = provider != null && provider.equalsIgnoreCase(this.provider)
                ? getApiKey() : null;
        return ModelDiscoveryHttp.discoverResult(provider, discoveryApiKey, discoveryBaseUrl).models().stream()
                .map(LiveModelDiscovery.Model::id)
                .toList();
    }

    /**
     * Legacy compatibility method. Manual model catalog entries are rejected so
     * the UI cannot bypass provider capability discovery.
     */
    public boolean addModelToCatalog(String provider, String model) {
        return false;
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
                || "custom".equals(provider)
                || isOpenCodeNative()) return true;
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
        if ("kompile".equalsIgnoreCase(provider)) {
            // Server mode talks to /api/agents/chat, which kompile-app-chat owns.
            return KompileServiceEndpoints.resolve(KompileService.CHAT).baseUrl();
        }
        String directUrl = ChatProviderRegistry.defaultBaseUrl(provider);
        if (directUrl != null) {
            return directUrl;
        }
        return new ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry().find(provider)
                .map(ai.kompile.cli.main.auth.oauth.OAuthProviderFlow::defaultBaseUrl)
                .orElse(null);
    }

    public static String[] getDefaultModels(String provider) {
        // Kept only for source compatibility. The wizard and runtime use
        // LiveModelDiscovery; no model id is maintained in application code.
        return new String[0];
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

    /** Whether this provider uses OpenCode's native server/CLI protocol. */
    @JsonIgnore
    public boolean isOpenCodeNative() {
        return "opencode".equalsIgnoreCase(provider);
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
        return !isOpenCodeNative() && !isAnthropicFormat()
                && !isOpenAiCodexFormat() && !isPiMessagesFormat();
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
        return ChatProviderRegistry.environmentVariable(provider);
    }

    private static ChatConfig fromEnv() {
        for (ChatProvider provider : ChatProviderRegistry.directProviders()) {
            String envVar = provider.environmentVariable();
            if (envVar == null || envVar.isBlank()) {
                continue;
            }
            String key = System.getenv(envVar);
            if (key == null || key.isBlank()) {
                continue;
            }
            List<LiveModelDiscovery.Model> models = ModelDiscoveryHttp
                    .discoverResult(provider.id(), key, null).models();
            if (models.isEmpty()) {
                continue;
            }
            ChatConfig config = new ChatConfig(provider.id(), key, models.get(0).id(), null);
            return config.isValid() ? config : null;
        }
        return null;
    }

    /** Compatibility view backed by the runtime provider registries. */
    public static final Map<String, String> PROVIDERS = loadProviderDescriptions();

    /** Compatibility ordering backed by the same runtime provider registry. */
    public static final String[] PROVIDER_ORDER = PROVIDERS.keySet().toArray(String[]::new);

    private static Map<String, String> loadProviderDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("kompile", "Kompile instance");
        descriptions.put("kompile-local", "Kompile local model");
        descriptions.put("custom", "OpenAI-compatible endpoint");
        descriptions.putAll(ChatProviderRegistry.descriptions());
        new ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry().flows().forEach(flow -> {
            descriptions.putIfAbsent(flow.providerId(), flow.displayName());
            descriptions.putIfAbsent(flow.userFacingProviderId(), flow.displayName());
        });
        return java.util.Collections.unmodifiableMap(descriptions);
    }

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
