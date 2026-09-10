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
import ai.kompile.cli.main.auth.oauth.CredentialFailure;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.chat.LocalServingRuntimePool;
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

    /** Explicit opt-in to the selected provider's premium fast mode. */
    @JsonProperty
    private volatile boolean fastMode;

    @JsonProperty
    private String baseUrl; // null = use provider default

    /** In-memory launch binding; retaining a chat configuration must not pin a loaded model. */
    @JsonIgnore
    private transient LocalServingRuntimePool.Binding localServingBinding;

    /** Selected non-secret authentication route: none, native, oauth, or api-key. */
    @JsonProperty
    private String authenticationMethod;

    /** Session pins are non-secret names in the managed credential store. */
    @JsonProperty
    private Map<String, String> credentialNames = new LinkedHashMap<>();
    @JsonProperty
    private String authenticationScope = "session";
    @JsonIgnore
    private transient Path sessionSettingsPath;

    public String getAuthenticationScope() { return authenticationScope; }
    public void setAuthenticationScope(String scope) {
        if (!"session".equals(scope) && !"global".equals(scope))
            throw new IllegalArgumentException("Authentication scope must be session or global");
        authenticationScope = scope;
    }
    @JsonIgnore
    public String getCredentialName() {
        return "global".equals(authenticationScope) || provider == null ? null
                : credentialNames.get(provider.toLowerCase(java.util.Locale.ROOT));
    }
    public void setCredentialName(String name) {
        if (provider == null) throw new IllegalStateException("Select a provider first");
        String key = provider.toLowerCase(java.util.Locale.ROOT);
        if (name == null) credentialNames.remove(key); else credentialNames.put(key, name);
        apiKey = null;
    }
    public void pinActiveCredential() throws IOException {
        pinActiveCredential(System::getenv);
    }

    void pinActiveCredential(java.util.function.Function<String, String> environment) throws IOException {
        if (!"session".equals(authenticationScope) || provider == null || getCredentialName() != null
                || "none".equalsIgnoreCase(authenticationMethod) || "native".equalsIgnoreCase(authenticationMethod)) return;
        CredentialStore store = CredentialStore.create();
        String name = store.activeCredentialName(provider);
        if (name == null && !"oauth".equalsIgnoreCase(authenticationMethod)) {
            String variable = getEnvironmentVariable(provider);
            String key = variable == null ? null : environment.apply(variable);
            if (key != null && !key.isBlank()) {
                name = "session-" + java.util.UUID.randomUUID();
                store.putApiKey(provider, name, key, false);
            }
        }
        if (name != null) setCredentialName(name);
    }

    public static Path sessionConfigPath(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Invalid conversation id");
        return KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(sessionId + ".chat-config.json");
    }

    public static ChatConfig loadSession(String sessionId) {
        Path path = sessionConfigPath(sessionId);
        if (!Files.exists(path)) return null;
        try {
            ChatConfig config = MAPPER.readValue(path.toFile(), ChatConfig.class);
            config.sessionSettingsPath = path;
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load session authentication settings", e);
        }
    }

    public void bindSession(String sessionId) throws IOException {
        sessionSettingsPath = sessionConfigPath(sessionId);
        // Preserve explicitly supplied keys before pinning an existing account.
        if (apiKey == null || apiKey.isBlank()) pinActiveCredential();
        saveLoadedOrGlobal();
    }

    /** Provider cache preference: none, short, or long. Defaults to short-lived caching. */
    @JsonProperty
    private String promptCacheRetention =
            ProviderPromptCacheCapabilities.Retention.SHORT.wireValue();

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
     * Legacy programmatic one-run judge-policy override. The setup wizard no longer asks
     * an activation question; interactive control lives under {@code /judge}.
     * Transient and never persisted. {@code null} uses project auto-detection.
     */
    @JsonIgnore
    private Boolean enforcementEnabled;

    @JsonIgnore
    private Path loadedFrom;

    public enum Scope {
        PROJECT,
        GLOBAL
    }

    /** A managed credential must not silently fall back to another identity or no auth. */
    public static final class AuthenticationException extends IllegalStateException {
        private final CredentialFailure failure;

        public AuthenticationException(String provider) {
            this(provider, CredentialFailure.reauthRequired(), false);
        }

        public AuthenticationException(String provider, IOException cause) {
            this(provider, CredentialFailure.classify(cause), false);
        }

        private AuthenticationException(String provider, CredentialFailure failure, boolean afterUnauthorized) {
            // Never retain the original exception: provider bodies and causes may echo tokens.
            // Recovery may fail on local I/O before any token refresh reaches the provider.
            super((afterUnauthorized ? "Credential recovery after HTTP 401 failed for "
                    : "Could not prepare credentials for ") + provider + ". " + failure.message()
                    + " " + failure.diagnostic()
                    + (failure.kind() == CredentialFailure.Kind.REAUTH_REQUIRED
                    ? " Run `kompile auth login " + provider + "` to sign in again." : ""), null);
            this.failure = failure;
        }

        public CredentialFailure failure() {
            return failure;
        }
    }

    public ChatConfig() {}

    public ChatConfig(String provider, String apiKey, String model, String baseUrl) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    /**
     * Isolated in-memory settings for a child conversation. Do not round-trip JSON:
     * credentials and the local serving binding are deliberately not serialized.
     */
    public ChatConfig copy() {
        ChatConfig copy = new ChatConfig(provider, apiKey, model, baseUrl);
        copy.thinking = thinking;
        copy.fastMode = fastMode;
        copy.localServingBinding = localServingBinding;
        copy.authenticationMethod = authenticationMethod;
        copy.authenticationScope = authenticationScope;
        copy.credentialNames = new LinkedHashMap<>(credentialNames);
        copy.promptCacheRetention = promptCacheRetention;
        copy.autoCompactEnabled = autoCompactEnabled;
        copy.autoCompactThreshold = autoCompactThreshold;
        copy.compactionReserveTokens = compactionReserveTokens;
        copy.contextWindowTokens = contextWindowTokens;
        copy.maxOutputTokens = maxOutputTokens;
        copy.defaultAgent = defaultAgent;
        copy.defaultRag = defaultRag;
        copy.defaultMemory = defaultMemory;
        copy.cancelKey = cancelKey;
        copy.chatMode = chatMode;
        copy.passthroughAgent = passthroughAgent;
        copy.passthroughManaged = passthroughManaged;
        copy.enforcementEnabled = enforcementEnabled;
        copy.loadedFrom = loadedFrom;
        return copy;
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
        if ("none".equalsIgnoreCase(authenticationMethod)
                || "native".equalsIgnoreCase(authenticationMethod)) {
            return null;
        }
        boolean oauthOnly = "oauth".equalsIgnoreCase(authenticationMethod);
        boolean apiKeyOnly = "api-key".equalsIgnoreCase(authenticationMethod);
        if (!oauthOnly && apiKey != null && !apiKey.isBlank()) {
            return OAuthProviderFlow.RequestAuth.apiKey(apiKey);
        }
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            OAuthProviderFlow.RequestAuth stored = OAuthCredentialManager.create().resolve(provider,
                    oauthOnly ? "oauth" : apiKeyOnly ? "api_key" : null, getCredentialName());
            if (stored != null
                    && (!oauthOnly || stored.oauth())
                    && (!apiKeyOnly || !stored.oauth())) {
                return stored;
            }
        } catch (IOException e) {
            throw new AuthenticationException(provider, e);
        }
        if (oauthOnly || getCredentialName() != null) throw new AuthenticationException(provider);
        String environmentName = getEnvironmentVariable(provider);
        if (environmentName == null) {
            return null;
        }
        String value = System.getenv(environmentName);
        return value == null || value.isBlank()
                ? null
                : OAuthProviderFlow.RequestAuth.apiKey(value);
    }

    /**
     * Refresh a provider-managed OAuth credential after an HTTP 401. API keys are
     * not retried because resending the same rejected secret cannot recover.
     */
    @JsonIgnore
    public OAuthProviderFlow.RequestAuth refreshRequestAuthAfterUnauthorized(
            OAuthProviderFlow.RequestAuth rejectedAuth) {
        if (rejectedAuth == null || !rejectedAuth.oauth()
                || provider == null || provider.isBlank()) {
            return null;
        }
        try {
            return getCredentialName() != null
                    ? OAuthCredentialManager.create().refreshNamedAfterUnauthorized(provider, rejectedAuth)
                    : OAuthCredentialManager.create().refreshAfterUnauthorized(provider, rejectedAuth);
        } catch (IOException e) {
            throw new AuthenticationException(provider, CredentialFailure.classify(e), true);
        }
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
        OAuthProviderFlow.RequestAuth discoveryAuth =
                provider != null && provider.equalsIgnoreCase(this.provider)
                        ? resolveRequestAuth() : null;
        return ModelDiscoveryHttp.discoverResultWithAuth(
                        provider, discoveryAuth, discoveryBaseUrl).models().stream()
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

    public boolean isFastMode() { return fastMode; }
    public void setFastMode(boolean fastMode) { this.fastMode = fastMode; }

    @JsonIgnore
    public ProviderFastModeCapabilities fastModeCapabilities() {
        return ProviderFastModeCapabilities.forProvider(provider);
    }

    @JsonIgnore
    public boolean supportsFastMode() {
        return fastModeCapabilities().supports(model);
    }

    /** Recheck the effective request model, including per-request overrides. */
    public boolean useFastMode(String requestModel) {
        return fastMode && fastModeCapabilities().supports(requestModel);
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    @JsonIgnore
    public LocalServingRuntimePool.Binding getLocalServingBinding() { return localServingBinding; }
    public void setLocalServingBinding(LocalServingRuntimePool.Binding binding) {
        this.localServingBinding = binding;
    }

    public String getAuthenticationMethod() { return authenticationMethod; }
    public void setAuthenticationMethod(String authenticationMethod) {
        this.authenticationMethod = authenticationMethod;
    }

    public String getPromptCacheRetention() {
        return promptCacheRetention().wireValue();
    }

    public void setPromptCacheRetention(String promptCacheRetention) {
        this.promptCacheRetention = ProviderPromptCacheCapabilities.Retention
                .from(promptCacheRetention).wireValue();
    }

    @JsonIgnore
    public ProviderPromptCacheCapabilities.Retention promptCacheRetention() {
        return ProviderPromptCacheCapabilities.Retention.from(promptCacheRetention);
    }

    @JsonIgnore
    public ProviderPromptCacheCapabilities promptCacheCapabilities() {
        ChatProvider descriptor = ChatProviderRegistry.find(provider);
        return descriptor == null
                ? ProviderPromptCacheCapabilities.forProvider(provider)
                : descriptor.promptCacheCapabilities();
    }

    /** Resolve timeout and retry behavior from the selected provider descriptor. */
    @JsonIgnore
    public ProviderConnectivityPolicy connectivityPolicy() {
        ChatProvider descriptor = ChatProviderRegistry.find(provider);
        return descriptor == null
                ? ProviderConnectivityPolicy.forProvider(provider)
                : descriptor.connectivityPolicy();
    }

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
        this.fastMode = source.useFastMode(source.model);
        this.baseUrl = source.baseUrl;
        this.localServingBinding = source.localServingBinding;
        this.authenticationMethod = source.authenticationMethod;
        this.authenticationScope = source.authenticationScope;
        this.credentialNames = new LinkedHashMap<>(source.credentialNames);
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
                || isOpenCodeNative()) return true;
        if ("custom".equals(provider)) {
            if (baseUrl == null || baseUrl.isBlank()) return false;
            if ("oauth".equalsIgnoreCase(authenticationMethod)
                    || "native".equalsIgnoreCase(authenticationMethod)) return false;
            if ("api-key".equalsIgnoreCase(authenticationMethod)) {
                return hasUsableCredential();
            }
            return true;
        }
        return hasUsableCredential();
    }

    private boolean hasUsableCredential() {
        try {
            String token = getApiKey();
            return token != null && !token.isBlank();
        } catch (AuthenticationException e) {
            return false; // Configuration validation may offer setup; requests still fail closed.
        }
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
        if (project != null) {
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
        if (sessionSettingsPath != null) {
            if (apiKey != null && !apiKey.isBlank() && provider != null) {
                if ("global".equals(authenticationScope)) {
                    CredentialStore.create().putApiKey(provider, apiKey);
                    apiKey = null;
                } else {
                    String name = "session-" + java.util.UUID.randomUUID();
                    CredentialStore.create().putApiKey(provider, name, apiKey, false);
                    setCredentialName(name);
                }
            }
            Files.createDirectories(sessionSettingsPath.getParent());
            Path temporary = Files.createTempFile(sessionSettingsPath.getParent(), ".chat-config-", ".tmp");
            try {
                MAPPER.writeValue(temporary.toFile(), this);
                try {
                    Files.move(temporary, sessionSettingsPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, sessionSettingsPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
        } else if (loadedFrom != null) {
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
        if (config != null) {
            return config;
        }
        return fromEnv();
    }

    public static ChatConfig loadGlobalOrFromEnv() {
        ChatConfig config = loadGlobal();
        if (config != null) {
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

    // Available passthrough agents. General-purpose agents come from CliAgentRegistry.
    // DeepSeek Harness is intentionally scoped to this surface: its shipped headless
    // profile is one-shot and is not compatible with every persistent registry consumer.
    // Computed lazily to avoid baking empty results into native image heap at build time.
    public static Map<String, String> getPassthroughAgents() {
        Map<String, String> agents = new LinkedHashMap<>();
        for (AgentProvider p : CliAgentRegistry.loadAll()) {
            String label = p.getDisplayName();
            if (p.getModelListCommand() != null && !p.getModelListCommand().isEmpty()) {
                label += " (requires installed '" + p.getCommand() + "' CLI)";
            }
            agents.put(p.getCommand(), label);
        }
        agents.putIfAbsent("dsh",
                "DeepSeek Harness (developer preview; managed one-shot; requires installed 'dsh' CLI)");
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
