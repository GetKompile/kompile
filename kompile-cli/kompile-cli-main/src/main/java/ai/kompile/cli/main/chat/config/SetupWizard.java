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

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.NativeCliAuth;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.tools.ResumeTool;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive setup wizard for kompile chat.
 * Presents session mode selection (Standard / Passthrough / Resume) FIRST,
 * then only asks for provider/credential details if Standard mode is chosen.
 * Passthrough mode delegates to CLI agents (claude, codex, gemini, etc.)
 * which handle their own authentication — no API key collection needed.
 * Uses numbered input for selection - bulletproof across all terminal types.
 */
public class SetupWizard {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";

    enum StandardRuntime {
        KOMPILE_LOCAL,
        EXTERNAL_LOCAL,
        DIRECT,
        KOMPILE
    }

    public enum AuthMethod {
        NONE,
        OAUTH,
        API_KEY,
        /** Authentication is owned by the provider CLI (OAuth/API selection stays native). */
        NATIVE
    }

    record ProviderSelection(String vendor, String provider, AuthMethod authMethod) {}

    /** Authentication route selected for a vendor, including a transient API-key input. */
    public record AuthenticationSelection(String provider, AuthMethod authMethod, String apiKey) {}

    /** Exact provider wire value plus the label shown in the setup wizard. */
    public record ThinkingOption(String value, String label) {}

    private record ModelSelection(String model, ModelDiscovery.Result discovery) {}

    private static final List<String> STANDARD_RUNTIME_OPTIONS = List.of(
            "Kompile local model — start the packaged first-party serving subprocess (no full Kompile instance)",
            "External local endpoint — Ollama or OpenAI-compatible (no Kompile instance)",
            "Direct model provider — cloud API (no Kompile instance)",
            "Kompile instance — connect to one or start the installed kompile-chat service"
    );


    /**
     * Run the interactive setup wizard.
     * Returns a valid ChatConfig or null if the user cancels.
     */
    public static ChatConfig run() {
        return run(ChatConfig.Scope.PROJECT, ChatConfig.defaultProjectRoot());
    }

    public static ChatConfig runGlobal() {
        return run(ChatConfig.Scope.GLOBAL, null);
    }

    public static ChatConfig run(ChatConfig.Scope scope) {
        return run(scope, ChatConfig.defaultProjectRoot());
    }

    public static ChatConfig run(ChatConfig.Scope scope, Path projectRoot) {
        ChatConfig.Scope targetScope = scope != null ? scope : ChatConfig.Scope.PROJECT;
        Path targetPath = ChatConfig.configPath(targetScope, projectRoot).toAbsolutePath().normalize();
        ChatConfig existingConfig = targetScope == ChatConfig.Scope.GLOBAL
                ? ChatConfig.loadGlobal()
                : ChatConfig.loadProject(projectRoot);
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.println(BOLD + CYAN + "  │       Kompile Chat Setup             │" + RESET);
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println();
            System.out.println("  " + DIM + "(Config will be saved to "
                    + targetScope.name().toLowerCase() + " " + targetPath + ")" + RESET);
            System.out.println();

            // Step 1: Select chat mode — ALWAYS first
            String chatMode = selectChatMode(reader);
            if (chatMode == null) return null;

            // Handle resume mode - close our terminal first so ResumeTool can own it
            if ("resume".equals(chatMode)) {
                try {
                    terminal.close();
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                terminal = null;

                System.out.println();
                System.out.println(GREEN + "  Launching Resume Tool..." + RESET);
                System.out.println();
                try {
                    ResumeTool resumeTool = new ResumeTool();
                    resumeTool.runInteractiveBrowser();
                    ChatConfig resumeConfig = new ChatConfig(null, null, null, null);
                    resumeConfig.setChatMode("resume");
                    return resumeConfig;
                } catch (IOException e) {
                    System.err.println("Error launching resume tool: " + e.getMessage());
                    return null;
                }
            }

            // Step 2: If passthrough mode, select only the passthrough style and agent.
            // Passthrough is a direct handoff to the native terminal; model and thinking
            // selection belong exclusively to the standalone Kompile Chat flow.
            String passthroughAgent = null;
            boolean passthroughManaged = true;
            Boolean enforcementChoice = null; // null = not asked; the router honors a FALSE
            if ("passthrough".equals(chatMode)) {
                // Ask managed vs direct first
                List<String> styles = List.of(
                    "Kompile managed — kompile REPL with tools, memory, skills (recommended)",
                    "Direct — agent owns the terminal (raw native experience, no kompile features)"
                );
                int styleIdx = selectNumbered(reader, "Select Passthrough Style:", styles);
                if (styleIdx < 0) return null;
                passthroughManaged = (styleIdx == 0);

                System.out.println();
                passthroughAgent = selectPassthroughAgent(reader);
                if (passthroughAgent == null) return null;

                if (passthroughManaged) {
                    // Step 2b: Optional rule enforcement (judge/enforcer).
                    // The Y/N answer is recorded on the ChatConfig (enforcementEnabled) so the
                    // router honors it for THIS session — a stale .kompile/enforcer-config.json
                    // can no longer force enforcement back on after the user answers "N".
                    System.out.println();
                    if (promptYesNo(reader,
                            "Enable rule enforcement (judge/enforcer) for this session?", false)) {
                        Path enforcerWd = Path.of(System.getProperty("user.dir"))
                                .toAbsolutePath().normalize();
                        try {
                            EnforcerConfig enforcerConfig =
                                    EnforcerSetupWizard.runWithReader(reader, enforcerWd, passthroughAgent);
                            if (enforcerConfig != null) {
                                enforcementChoice = Boolean.TRUE;
                                System.out.println(GREEN + "  ✓ Enforcement configured ("
                                        + (enforcerConfig.isKeywordMode() ? "keyword rules" : "LLM judge")
                                        + ") → .kompile/enforcer-config.json" + RESET);
                            } else {
                                enforcementChoice = Boolean.FALSE; // cancelled → no enforcement this run
                                System.out.println(YELLOW
                                        + "  Enforcement setup cancelled — continuing without it." + RESET);
                            }
                        } catch (Exception e) {
                            enforcementChoice = Boolean.FALSE;
                            System.err.println("  Enforcer setup failed: " + e.getMessage());
                        }
                    } else {
                        enforcementChoice = Boolean.FALSE; // explicit opt-out for THIS session
                    }
                } else {
                    enforcementChoice = Boolean.FALSE; // direct style has no managed enforcer layer
                }
            }

            // Step 3: For standard mode, select the runtime before provider details.
            // This keeps instance connectivity separate from model/provider selection and
            // makes the no-instance paths explicit in the normal wizard.
            String provider = null;
            String apiKey = null;
            String model = null;
            String thinking = null;
            String baseUrl = null;
            ModelDiscovery.Result selectedDiscovery = null;
            ProviderSelection providerSelection = null;

            if ("standard".equals(chatMode)) {
                providerSelection = selectStandardProvider(reader);
                if (providerSelection == null) return null;
                AuthenticationSelection authentication = authenticate(
                        reader, providerSelection.vendor(), providerSelection.authMethod());
                if (authentication == null) return null;
                provider = authentication.provider();
                apiKey = authentication.apiKey();
                baseUrl = promptBaseUrl(reader, provider);
                if (existingConfig != null
                        && provider.equalsIgnoreCase(existingConfig.getProvider())
                        && (baseUrl == null || baseUrl.isBlank())) {
                    // A provider switch must not inherit another provider's endpoint, but
                    // re-running setup for the same provider should retain its custom URL.
                    baseUrl = existingConfig.getBaseUrl();
                }

                if (!"kompile".equals(provider)) {
                    boolean sameProvider = existingConfig != null
                            && provider.equalsIgnoreCase(existingConfig.getProvider());
                    ChatConfig discoveryConfig = new ChatConfig(
                            provider,
                            apiKey,
                            sameProvider ? existingConfig.getModel() : null,
                            baseUrl);
                    if (sameProvider && (baseUrl == null || baseUrl.isBlank())) {
                        discoveryConfig.setBaseUrl(existingConfig.getBaseUrl());
                    }

                    ModelSelection selection = selectModel(reader, provider, apiKey, discoveryConfig);
                    if (selection == null || selection.model() == null) return null;
                    model = selection.model();
                    selectedDiscovery = selection.discovery();

                    if (supportsThinkingSelection(
                            provider, model, apiKey, discoveryConfig, selectedDiscovery)) {
                        thinking = selectThinking(
                                reader, provider, model, apiKey, discoveryConfig, selection.discovery());
                        if (thinking == null) return null;
                    }
                }
            }

            // Build and save config
            String selectedModel = model == null || model.isBlank() ? null : model;
            ChatConfig config = new ChatConfig(provider, apiKey, selectedModel, baseUrl);
            config.setThinking(thinking == null || thinking.isBlank() ? null : thinking);
            config.setChatMode(chatMode);
            if (passthroughAgent != null) {
                config.setPassthroughAgent(passthroughAgent);
            }
            config.setPassthroughManaged(passthroughManaged);
            config.setEnforcementEnabled(enforcementChoice);

            try {
                config.save(targetScope, projectRoot);
                System.out.println();
                System.out.println(GREEN + "  ✓ Configuration saved!" + RESET);
                System.out.println();
                System.out.println("  Config:   " + BOLD + targetPath + RESET);
                System.out.println("  Chat Mode: " + BOLD + chatMode + RESET);
                if ("passthrough".equals(chatMode)) {
                    System.out.println("  Agent:     " + BOLD + passthroughAgent + RESET);
                    System.out.println("  Style:     " + BOLD
                            + (passthroughManaged ? "Kompile managed" : "Direct") + RESET);
                    if (passthroughManaged && enforcementChoice != null) {
                        System.out.println("  Enforcer:  " + BOLD
                                + (Boolean.TRUE.equals(enforcementChoice) ? "enabled" : "disabled") + RESET);
                    }
                } else {
                    String displayedProvider = providerSelection == null
                            ? provider
                            : vendorLabel(providerSelection.vendor());
                    System.out.println("  Provider: " + BOLD + displayedProvider + RESET);
                    if (providerSelection != null
                            && providerSelection.authMethod() != AuthMethod.NONE) {
                        System.out.println("  Auth:     " + BOLD
                                + authMethodLabel(providerSelection.authMethod()) + RESET);
                    }
                    if (model != null) {
                        System.out.println("  Model:    " + BOLD + model + RESET);
                    }
                    if (supportsThinkingSelection(
                            provider, model, apiKey, config, selectedDiscovery)) {
                        System.out.println("  Thinking: " + BOLD
                                + (config.getThinking() == null ? "provider/model default" : config.getThinking())
                                + RESET);
                    }
                    if (baseUrl != null) {
                        System.out.println("  Base URL: " + BOLD + baseUrl + RESET);
                    }
                }
                System.out.println();
                System.out.println(DIM + "  You can reconfigure anytime with: /setup" + RESET);
                System.out.println();
            } catch (IOException e) {
                System.err.println("Warning: Could not save config: " + e.getMessage());
                System.err.println("Proceeding with in-memory configuration.");
            }

            return config;

        } catch (Exception e) {
            System.err.println("Setup wizard error: " + e.getMessage());
            return null;
        } finally {
            if (terminal != null) {
                try {
                    terminal.close();
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ── Selection helpers ───────────────────────────────────────────────────

    /**
     * Show a numbered menu and read the user's choice.
     * Accepts either a number (1-N) or a partial name match.
     * Returns the selected index (0-based), or -1 on cancel.
     */
    private static int selectNumbered(LineReader reader, String title, List<String> items) {
        System.out.println(BOLD + "  " + title + RESET);
        System.out.println();
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, items.get(i));
        }
        System.out.println();

        while (true) {
            String input;
            try {
                input = reader.readLine("  Choice (1-" + items.size() + ", or Ctrl+C to cancel): ");
            } catch (Exception e) {
                return -1;
            }
            if (input == null) return -1;
            String trimmed = input.trim();
            if (trimmed.equalsIgnoreCase("q") || trimmed.equalsIgnoreCase("quit") || trimmed.equalsIgnoreCase("cancel")) {
                return -1;
            }

            // Try number
            try {
                int n = Integer.parseInt(trimmed);
                if (n >= 1 && n <= items.size()) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {}

            // Try partial name match
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).toLowerCase().contains(trimmed.toLowerCase())) {
                    return i;
                }
            }

            System.out.println("  " + YELLOW + "Please enter a number 1-" + items.size() + " or type part of the name" + RESET);
        }
    }

    // ── Chat mode selection ─────────────────────────────────────────────────

    private static String selectChatMode(LineReader reader) {
        List<String> modes = List.of(
            "Standard Chat — REPL with RAG, memory, and tools",
            "Passthrough — delegate to Claude Code, Codex, Gemini, etc.",
            "Resume Previous Conversation"
        );

        System.out.println();
        int selected = selectNumbered(reader, "Select Chat Mode:", modes);
        if (selected < 0) return null;

        String[] values = {"standard", "passthrough", "resume"};
        System.out.println("  → " + GREEN + Character.toUpperCase(values[selected].charAt(0)) + values[selected].substring(1) + RESET);
        System.out.println();
        return values[selected];
    }

    // ── Passthrough agent selection ─────────────────────────────────────────

    /**
     * Check if an agent binary exists on PATH — delegates to SubprocessAgentRunner.
     */
    private static boolean agentExists(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name) != null;
    }

    private static String selectPassthroughAgent(LineReader reader) {
        // Only show agents that actually exist on the system
        List<String> availableAgents = new ArrayList<>();
        List<String> agentKeys = new ArrayList<>();

        for (String key : ChatConfig.getPassthroughAgentOrder()) {
            if (agentExists(key)) {
                String desc = ChatConfig.getPassthroughAgents().get(key);
                availableAgents.add(desc);
                agentKeys.add(key);
            }
        }

        if (availableAgents.isEmpty()) {
            System.out.println(YELLOW + "  Warning: No registered CLI agents found on PATH." + RESET);
            System.out.println("  Install one of the agents listed in the CLI agent registry.");
            return null;
        }

        int selected = selectNumbered(reader, "Select CLI Agent:", availableAgents);
        if (selected < 0) return null;

        String selectedKey = agentKeys.get(selected);
        System.out.println("  → " + GREEN + availableAgents.get(selected) + RESET);
        System.out.println();
        return selectedKey;
    }

    private static AgentProvider registryDefinition(String agentKey) {
        if (agentKey == null || agentKey.isBlank()) {
            return null;
        }
        return CliAgentRegistry.loadAll().stream()
                .filter(agent -> agentKey.equalsIgnoreCase(agent.getCommand())
                        || agentKey.equalsIgnoreCase(agent.getName()))
                .findFirst()
                .orElse(null);
    }

    // ── Provider selection ──────────────────────────────────────────────────

    static List<String> standardRuntimeOptions() {
        return STANDARD_RUNTIME_OPTIONS;
    }

    static List<String> externalLocalOptions() {
        List<String> options = new ArrayList<>(ChatProviderRegistry.localProviders().stream()
                .map(ChatProvider::displayName)
                .toList());
        options.add("OpenAI-compatible endpoint");
        return List.copyOf(options);
    }

    public static List<String> directVendorOrder() {
        return java.util.stream.Stream.concat(
                        ChatProviderRegistry.directProviders().stream().map(ChatProvider::id),
                        new OAuthProviderRegistry().flows().stream()
                                .map(OAuthProviderFlow::userFacingProviderId))
                .filter(provider -> provider != null && !provider.isBlank())
                .distinct()
                .toList();
    }

    public static List<String> authOptions(String vendor) {
        List<String> options = new ArrayList<>();
        for (AuthMethod method : authMethods(vendor)) {
            options.add(authMethodLabel(method));
        }
        return List.copyOf(options);
    }

    public static String resolveProviderForAuth(String vendor, AuthMethod authMethod) {
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("Vendor is required");
        }
        return switch (authMethod) {
            case NONE -> vendor;
            case NATIVE -> {
                if (!NativeCliAuth.isSupported(vendor)) {
                    throw new IllegalArgumentException(vendor + " does not support native CLI authentication");
                }
                yield vendor;
            }
            case OAUTH -> {
                String oauthProvider = oauthProviderForVendor(vendor);
                if (oauthProvider == null) {
                    throw new IllegalArgumentException(vendor + " does not support OAuth");
                }
                yield oauthProvider;
            }
            case API_KEY -> {
                if (!supportsApiKey(vendor)) {
                    throw new IllegalArgumentException(vendor + " does not support API-key authentication");
                }
                yield vendor;
            }
        };
    }

    /**
     * Provider choices shared by setup and the in-session picker. These are
     * user-facing vendor keys, never provider wire IDs such as openai-codex.
     */
    public static List<String> providerPickerOrder() {
        List<String> providers = new ArrayList<>(directVendorOrder());
        if (!providers.contains("custom")) {
            providers.add("custom");
        }
        return List.copyOf(providers);
    }

    /** Authentication methods for a provider shown in the picker. */
    public static List<AuthMethod> authMethodsForPicker(String vendor) {
        return authMethods(vendor);
    }

    /** Resolve a wire provider back to the vendor shown to users. */
    public static String vendorForProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return provider;
        }
        OAuthProviderFlow flow = new OAuthProviderRegistry().find(provider).orElse(null);
        if (flow != null) {
            return flow.userFacingProviderId();
        }
        ChatProvider chatProvider = ChatProviderRegistry.find(provider);
        return chatProvider == null ? provider : chatProvider.id();
    }

    /** Fetch the provider's current model ids and discovery status. */
    public static ModelDiscovery.Result modelDiscovery(String provider) {
        return ModelDiscoveryHttp.discoverResult(provider, null, null);
    }

    /** Fetch live models with the transient credential and current endpoint. */
    public static ModelDiscovery.Result modelDiscovery(
            String provider, String transientApiKey, ChatConfig config) {
        boolean sameProvider = config != null
                && provider != null
                && provider.equalsIgnoreCase(config.getProvider());
        String baseUrl = sameProvider ? config.getBaseUrl() : null;
        return ModelDiscoveryHttp.discoverResult(provider, transientApiKey, baseUrl);
    }

    /** Force a live provider request, bypassing and refreshing the model cache. */
    public static ModelDiscovery.Result refreshModelDiscovery(String provider) {
        return ModelDiscoveryHttp.refreshResult(provider, null, null);
    }

    /** Force a live request with the transient credential and configured endpoint. */
    public static ModelDiscovery.Result refreshModelDiscovery(
            String provider, String transientApiKey, ChatConfig config) {
        boolean sameProvider = config != null
                && provider != null
                && provider.equalsIgnoreCase(config.getProvider());
        String baseUrl = sameProvider ? config.getBaseUrl() : null;
        return ModelDiscoveryHttp.refreshResult(provider, transientApiKey, baseUrl);
    }

    public static List<String> modelOptions(String provider) {
        return modelIds(modelDiscovery(provider), null);
    }

    /** Fetch live model ids with the transient credential and current endpoint. */
    public static List<String> modelOptions(String provider, String transientApiKey, ChatConfig config) {
        ModelDiscovery.Result discovery = modelDiscovery(provider, transientApiKey, config);
        String currentModel = config != null
                && provider != null
                && provider.equalsIgnoreCase(config.getProvider())
                ? config.getModel() : null;
        return modelIds(discovery, currentModel);
    }

    public static List<String> modelOptions(String provider, ChatConfig config) {
        return modelOptions(provider, null, config);
    }

    /** Convert one discovery result to selectable ids, retaining the configured model. */
    public static List<String> modelOptions(ModelDiscovery.Result discovery, String currentModel) {
        return modelIds(discovery, currentModel);
    }

    private static List<String> modelIds(ModelDiscovery.Result discovery, String currentModel) {
        List<String> ids = new ArrayList<>(discovery == null ? List.of() : discovery.models().stream()
                .map(LiveModelDiscovery.Model::id)
                .toList());
        if (currentModel != null && !currentModel.isBlank()
                && ids.stream().noneMatch(id -> id.equalsIgnoreCase(currentModel))) {
            ids.add(currentModel);
        }
        return List.copyOf(ids);
    }

    /** Resolve the active wire provider's current authentication route. */
    public static AuthMethod authMethodForProvider(String provider) {
        String vendor = vendorForProvider(provider);
        List<AuthMethod> methods = authMethodsForPicker(vendor);
        for (AuthMethod method : methods) {
            try {
                if (resolveProviderForAuth(vendor, method).equalsIgnoreCase(provider)) {
                    return method;
                }
            } catch (IllegalArgumentException ignored) {
                // Keep checking the configured methods.
            }
        }
        return methods.isEmpty() ? AuthMethod.NONE : methods.get(0);
    }

    /**
     * Reuse the startup authentication flow for any provider/model selector.
     * This selects an existing managed credential, performs subscription OAuth
     * when needed, or collects a transient API key for the candidate config.
     */
    public static AuthenticationSelection authenticate(
            LineReader reader, String vendor, AuthMethod authMethod) {
        if (vendor == null || vendor.isBlank() || authMethod == null) {
            return null;
        }
        final String provider;
        try {
            provider = resolveProviderForAuth(vendor, authMethod);
        } catch (IllegalArgumentException e) {
            System.err.println("  Authentication route unavailable: " + e.getMessage());
            return null;
        }
        if (authMethod == AuthMethod.NONE) {
            return new AuthenticationSelection(provider, authMethod, null);
        }
        if (authMethod == AuthMethod.NATIVE) {
            int exitCode = NativeCliAuth.login(provider);
            if (exitCode != 0) {
                System.err.println("  Native authentication failed for " + vendorLabel(vendor)
                        + " (exit code " + exitCode + ").");
                return null;
            }
            return new AuthenticationSelection(provider, authMethod, null);
        }
        if (!selectManagedCredential(reader, provider, authMethod)) {
            return null;
        }

        OAuthProviderFlow.RequestAuth existing = resolveExistingCredential(provider);
        if (authMethod == AuthMethod.OAUTH) {
            if (existing != null && existing.oauth()) {
                System.out.println(GREEN + "  ✓ Using existing OAuth credential for "
                        + vendorLabel(vendor) + RESET);
                return new AuthenticationSelection(provider, authMethod, null);
            }
            return loginWithOAuth(reader, provider)
                    ? new AuthenticationSelection(provider, authMethod, null)
                    : null;
        }

        if (existing != null && !existing.oauth()) {
            System.out.println(GREEN + "  ✓ Using existing managed/environment API key for "
                    + vendorLabel(vendor) + RESET);
            return new AuthenticationSelection(provider, authMethod, null);
        }
        String apiKey = promptApiKey(reader, provider);
        return apiKey == null || apiKey.isBlank()
                ? null
                : new AuthenticationSelection(provider, authMethod, apiKey);
    }

    private static ProviderSelection selectStandardProvider(LineReader reader) {
        int selected = selectNumbered(reader, "Select Standard Chat Runtime:", standardRuntimeOptions());
        if (selected < 0) return null;

        StandardRuntime runtime = StandardRuntime.values()[selected];
        return switch (runtime) {
            case KOMPILE_LOCAL -> {
                System.out.println("  → " + GREEN
                        + "Kompile local model — first-party serving subprocess" + RESET);
                System.out.println();
                yield new ProviderSelection("kompile-local", "kompile-local", AuthMethod.NONE);
            }
            case EXTERNAL_LOCAL -> selectExternalLocalProvider(reader);
            case DIRECT -> selectProvider(reader, directVendorOrder());
            case KOMPILE -> {
                System.out.println("  → " + GREEN + "Kompile instance" + RESET);
                System.out.println();
                yield new ProviderSelection("kompile", "kompile", AuthMethod.NONE);
            }
        };
    }

    private static ProviderSelection selectExternalLocalProvider(LineReader reader) {
        List<ChatProvider> localProviders = ChatProviderRegistry.localProviders();
        int selected = selectNumbered(reader, "Select External Local Endpoint:",
                externalLocalOptions());
        if (selected < 0) return null;
        if (selected < localProviders.size()) {
            ChatProvider provider = localProviders.get(selected);
            System.out.println("  → " + GREEN + provider.displayName() + RESET);
            System.out.println();
            return new ProviderSelection(provider.id(), provider.id(), AuthMethod.NONE);
        }
        System.out.println("  → " + GREEN + "OpenAI-compatible endpoint" + RESET);
        System.out.println();
        return new ProviderSelection("custom", "custom", AuthMethod.NONE);
    }

    private static ProviderSelection selectProvider(LineReader reader, List<String> vendorKeys) {
        List<String> vendors = new ArrayList<>();
        for (String key : vendorKeys) {
            vendors.add(vendorLabel(key));
        }

        int selected = selectNumbered(reader, "Select LLM Vendor:", vendors);
        if (selected < 0) return null;

        String vendor = vendorKeys.get(selected);
        System.out.println("  → " + GREEN + vendorLabel(vendor) + RESET);
        System.out.println();

        AuthMethod authMethod = selectAuthMethod(reader, vendor);
        if (authMethod == null) return null;
        return new ProviderSelection(vendor, resolveProviderForAuth(vendor, authMethod), authMethod);
    }

    private static AuthMethod selectAuthMethod(LineReader reader, String vendor) {
        List<AuthMethod> methods = authMethods(vendor);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("No authentication method is configured for " + vendor);
        }

        AuthMethod authMethod;
        if (methods.size() == 1) {
            authMethod = methods.get(0);
            System.out.println("  Authentication: " + GREEN + authMethodLabel(authMethod) + RESET);
            System.out.println();
        } else {
            int selected = selectNumbered(reader, "Select Authentication:", authOptions(vendor));
            if (selected < 0) return null;
            authMethod = methods.get(selected);
            System.out.println("  → " + GREEN + authMethodLabel(authMethod) + RESET);
            System.out.println();
        }
        return authMethod;
    }

    private static List<AuthMethod> authMethods(String vendor) {
        if ("custom".equalsIgnoreCase(vendor)) {
            return List.of(AuthMethod.NONE);
        }
        if (NativeCliAuth.isSupported(vendor)) {
            return List.of(AuthMethod.NATIVE);
        }
        List<AuthMethod> methods = new ArrayList<>();
        if (oauthProviderForVendor(vendor) != null) {
            methods.add(AuthMethod.OAUTH);
        }
        if (supportsApiKey(vendor)) {
            methods.add(AuthMethod.API_KEY);
        }
        if (methods.isEmpty() && ChatProviderRegistry.find(vendor) != null) {
            methods.add(AuthMethod.NONE);
        }
        return List.copyOf(methods);
    }

    private static String oauthProviderForVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return null;
        }
        OAuthProviderRegistry registry = new OAuthProviderRegistry();
        return registry.oauthProviderForVendor(vendor).orElse(null);
    }

    private static boolean supportsApiKey(String vendor) {
        return new OAuthProviderRegistry().supportsApiKey(vendor);
    }

    public static String vendorLabel(String vendor) {
        ChatProvider provider = ChatProviderRegistry.find(vendor);
        if (provider != null) {
            return provider.displayName();
        }
        return new OAuthProviderRegistry().find(vendor)
                .map(OAuthProviderFlow::displayName)
                .orElseGet(() -> ChatProviderRegistry.label(vendor));
    }

    public static String authMethodLabel(AuthMethod authMethod) {
        return switch (authMethod) {
            case OAUTH -> "OAuth / subscription sign-in";
            case API_KEY -> "API key";
            case NATIVE -> "Native provider authentication";
            case NONE -> "None";
        };
    }

    // ── Model selection ─────────────────────────────────────────────────────

    public static List<ThinkingOption> thinkingOptions(String provider, String model) {
        return thinkingOptionsFromDiscovery(provider, model, null);
    }

    public static List<ThinkingOption> thinkingOptions(
            String provider, String model, String transientApiKey, ChatConfig config) {
        return thinkingOptionsFromDiscovery(
                provider, model, modelDiscovery(provider, transientApiKey, config));
    }

    /**
     * Resolve thinking choices from an already-fetched discovery result.
     * This overload is used by setup and the picker so metadata never causes a
     * second network request.
     */
    public static List<ThinkingOption> thinkingOptions(
            String provider,
            String model,
            String transientApiKey,
            ChatConfig config,
            ModelDiscovery.Result discovery) {
        return thinkingOptionsFromDiscovery(provider, model, discovery);
    }

    private static List<ThinkingOption> thinkingOptionsFromDiscovery(
            String provider, String model, ModelDiscovery.Result discovery) {
        return thinkingOptions(resolveThinkingCapabilities(provider, model, discovery));
    }

    private static ThinkingCapabilityProvider.ThinkingCapabilities resolveThinkingCapabilities(
            String provider, String model, ModelDiscovery.Result discovery) {
        if (provider == null || model == null || model.isBlank()) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }

        ChatProvider providerDescriptor = ChatProviderRegistry.find(provider);
        if (providerDescriptor == null) {
            return ThinkingCapabilityProvider.ThinkingCapabilities.none();
        }

        LiveModelDiscovery.Model liveModel = discovery == null ? null
                : discovery.models().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(model.trim()))
                .findFirst()
                .orElse(null);
        if (liveModel == null) {
            liveModel = new LiveModelDiscovery.Model(model.trim(), List.of());
        }
        return providerDescriptor.thinkingCapabilityProvider().resolve(liveModel);
    }

    private static List<ThinkingOption> thinkingOptions(
            ThinkingCapabilityProvider.ThinkingCapabilities capabilities) {
        if (!capabilities.supported()) {
            return List.of();
        }

        List<ThinkingOption> options = new ArrayList<>();
        String defaultValue = capabilities.defaultValue();
        String defaultLabel = defaultValue == null || defaultValue.isBlank()
                ? "provider/model default (recommended)"
                : "provider/model default (" + defaultValue + ", recommended)";
        if (capabilities.documentedFallback()) {
            defaultLabel += " — " + capabilities.sourceIndicator();
        }
        options.add(new ThinkingOption("", defaultLabel));
        capabilities.options().forEach(option ->
                options.add(new ThinkingOption(option.value(), option.label())));
        return List.copyOf(options);
    }

    public static boolean supportsThinkingSelection(String provider, String model) {
        return thinkingOptions(provider, model).size() > 1;
    }

    public static boolean supportsThinkingSelection(
            String provider, String model, String transientApiKey, ChatConfig config) {
        return thinkingOptions(provider, model, transientApiKey, config).size() > 1;
    }

    public static boolean supportsThinkingSelection(
            String provider,
            String model,
            String transientApiKey,
            ChatConfig config,
            ModelDiscovery.Result discovery) {
        return thinkingOptions(provider, model, transientApiKey, config, discovery).size() > 1;
    }

    /**
     * Keep a previous wire value only when it is valid for the selected model.
     */
    public static String compatibleThinking(
            String provider,
            String model,
            String currentThinking,
            ModelDiscovery.Result discovery) {
        if (currentThinking == null || currentThinking.isBlank()) {
            return null;
        }
        return thinkingOptionsFromDiscovery(provider, model, discovery).stream()
                .map(ThinkingOption::value)
                .anyMatch(currentThinking::equals)
                ? currentThinking : null;
    }

    private static String selectThinking(
            LineReader reader,
            String provider,
            String model,
            String transientApiKey,
            ChatConfig config,
            ModelDiscovery.Result discovery) {
        List<ThinkingOption> options = thinkingOptions(
                provider, model, transientApiKey, config, discovery);
        if (options.size() <= 1) {
            return "";
        }
        ThinkingCapabilityProvider.ThinkingCapabilities capabilities =
                resolveThinkingCapabilities(provider, model, discovery);
        if (capabilities.documentedFallback()) {
            System.out.println("  " + YELLOW + capabilities.sourceIndicator() + RESET);
        }
        List<String> labels = options.stream().map(ThinkingOption::label).toList();
        int selected = selectNumbered(reader,
                "Select " + vendorLabel(provider) + " Thinking Variant:", labels);
        if (selected < 0) return null;

        String effort = options.get(selected).value();
        System.out.println("  → " + GREEN
                + (effort.isBlank() ? options.get(selected).label() : effort) + RESET);
        System.out.println();
        return effort;
    }

    private static ModelSelection selectModel(LineReader reader, String provider) {
        return selectModel(reader, provider, null, null);
    }

    private static ModelSelection selectModel(
            LineReader reader, String provider, String transientApiKey, ChatConfig config) {
        ModelDiscovery.Result discovery = modelDiscovery(provider, transientApiKey, config);
        String currentModel = config != null
                && provider != null
                && provider.equalsIgnoreCase(config.getProvider())
                ? config.getModel() : null;
        List<String> models = modelIds(discovery, currentModel);
        if (!discovery.message().isBlank()) {
            System.err.println("  " + discovery.message());
        }
        if (models.isEmpty()) {
            System.err.println("  Model discovery for " + vendorLabel(provider)
                    + " returned " + discovery.status().name().toLowerCase().replace('_', ' ')
                    + (discovery.message().isBlank() ? "." : ": " + discovery.message()));

            String manual = promptManual(reader, "  Enter model id manually (blank to cancel): ");
            if (manual != null) {
                System.out.println("  → " + GREEN + manual + RESET);
                System.out.println();
            }
            return new ModelSelection(manual, discovery);
        }

        int selected = selectNumbered(reader, "Select Model:", models);
        if (selected < 0) return null;

        String model = models.get(selected);
        System.out.println("  → " + GREEN + model + RESET);
        System.out.println();
        return new ModelSelection(model, discovery);
    }

    // ── Manual text prompt ──────────────────────────────────────────────────

    private static String promptManual(LineReader reader, String promptText) {
        try {
            String input = reader.readLine(promptText);
            if (input == null || input.trim().isEmpty()) return null;
            return input.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Yes/No prompt. Returns {@code defaultYes} when the user just presses Enter.
     */
    private static boolean promptYesNo(LineReader reader, String question, boolean defaultYes) {
        String suffix = defaultYes ? " [Y/n]: " : " [y/N]: ";
        String input = promptManual(reader, "  " + question + suffix);
        if (input == null || input.isBlank()) return defaultYes;
        return input.trim().toLowerCase().startsWith("y");
    }

    // ── API key prompt ──────────────────────────────────────────────────────

    private static boolean selectManagedCredential(
            LineReader reader,
            String provider,
            AuthMethod authMethod) {
        try {
            CredentialStore store = CredentialStore.create();
            List<CredentialStore.CredentialInfo> allCredentials = store.list(provider);
            List<CredentialStore.CredentialInfo> credentials = new ArrayList<>(
                    compatibleCredentials(allCredentials, authMethod));
            if (authMethod == AuthMethod.OAUTH
                    && "openai-codex".equalsIgnoreCase(provider)) {
                allCredentials.stream()
                        .filter(info -> ManagedCredential.API_KEY.equals(info.type()))
                        .filter(info -> isLegacyOpenAiCodexCredential(store, provider, info))
                        .forEach(credentials::add);
            }
            if (credentials.isEmpty()) {
                return true;
            }
            List<String> labels = credentials.stream()
                    .map(info -> info.credentialName() + " — "
                            + (isLegacyOpenAiCodexCredential(store, provider, info)
                            ? ManagedCredential.OAUTH
                            : info.type())
                            + (info.active() ? " (active)" : ""))
                    .toList();
            String prompt = authMethod == AuthMethod.OAUTH
                    ? "Select Subscription:"
                    : "Select Stored Credential:";
            int selected = selectNumbered(reader, prompt, labels);
            if (selected < 0) {
                return false;
            }
            CredentialStore.CredentialInfo selectedCredential = credentials.get(selected);
            String credentialName = selectedCredential.credentialName();
            if (selectedCredential.active()) {
                return true;
            }
            if (!store.switchCredential(provider, credentialName)) {
                System.err.println("  Could not switch to credential '" + credentialName + "'.");
                return false;
            }
            System.out.println(GREEN + "  ✓ Using credential '" + credentialName
                    + "' for " + provider + RESET);
            return true;
        } catch (IOException e) {
            System.err.println("  Could not read managed credentials: " + e.getMessage());
            return false;
        }
    }

    private static boolean isLegacyOpenAiCodexCredential(
            CredentialStore store,
            String provider,
            CredentialStore.CredentialInfo info) {
        if (!"openai-codex".equalsIgnoreCase(provider)
                || !ManagedCredential.API_KEY.equals(info.type())) {
            return false;
        }
        try {
            return OAuthCredentialManager.isLegacyOpenAiCodexApiKey(
                    provider,
                    store.read(provider, info.credentialName()));
        } catch (IOException e) {
            return false;
        }
    }

    static List<CredentialStore.CredentialInfo> compatibleCredentials(
            List<CredentialStore.CredentialInfo> credentials,
            AuthMethod authMethod) {
        String requiredType = authMethod == AuthMethod.OAUTH
                ? ManagedCredential.OAUTH
                : ManagedCredential.API_KEY;
        return credentials.stream()
                .filter(info -> requiredType.equals(info.type()))
                .toList();
    }

    private static OAuthProviderFlow.RequestAuth resolveExistingCredential(String provider) {
        ChatConfig probe = new ChatConfig(provider, null, "credential-probe", null);
        return probe.resolveRequestAuth();
    }

    private static boolean loginWithOAuth(LineReader reader, String provider) {
        try {
            OAuthCredentialManager.create().login(
                    provider,
                    new OAuthProviderFlow.LoginOptions(null, false, null, null),
                    new WizardOAuthInteraction(reader));
            System.out.println(GREEN + "  ✓ OAuth credential saved for " + provider + RESET);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("  OAuth login was interrupted.");
            return false;
        } catch (Exception e) {
            System.err.println("  OAuth login failed: " + e.getMessage());
            return false;
        }
    }

    private static String promptApiKey(LineReader reader, String provider) {
        String envVar = getEnvVarName(provider);
        String envValue = envVar != null ? System.getenv(envVar) : null;

        if (envValue != null && !envValue.isBlank()) {
            String masked = maskKey(envValue);
            System.out.println("  Found " + envVar + " in environment: " + DIM + masked + RESET);
            String use = promptManual(reader, "  Use this key? [Y/n]: ");
            if (use == null) return null;
            if (use.isBlank() || use.toLowerCase().startsWith("y")) {
                return envValue;
            }
        }

        System.out.println(BOLD + "  Enter API Key:" + RESET);
        if (envVar != null) {
            System.out.println("  " + DIM + "(or set " + envVar + " environment variable)" + RESET);
        }

        return promptManual(reader, "  API key: ");
    }

    // ── Base URL prompt ─────────────────────────────────────────────────────

    static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String promptWithDefault(LineReader reader, String promptText, String defaultValue) {
        try {
            return valueOrDefault(reader.readLine(promptText), defaultValue);
        } catch (Exception e) {
            return null;
        }
    }

    public static String promptBaseUrl(LineReader reader, String provider) {
        String defaultUrl = ChatConfig.getDefaultBaseUrl(provider);

        if ("kompile-local".equals(provider)) {
            // The bootstrap assigns a private loopback endpoint for this session.
            return null;
        }

        if ("kompile".equals(provider)) {
            System.out.println();
            System.out.println(BOLD + "  Kompile App URL:" + RESET);
            System.out.println("  Default: " + DIM + defaultUrl + RESET);
            System.out.println("  " + DIM + "(The CLI will connect via MCP SSE to this instance)" + RESET);
            return promptWithDefault(reader, "  URL (Enter to use default): ", defaultUrl);
        }

        if ("ollama".equals(provider)) {
            System.out.println("  Default Ollama URL: " + DIM + defaultUrl + RESET);
            return promptWithDefault(reader, "  Custom URL (Enter to use default): ", defaultUrl);
        }

        if ("custom".equals(provider)) {
            System.out.println(BOLD + "  Enter Base URL:" + RESET);
            System.out.println("  " + DIM + "(OpenAI-compatible Chat Completions endpoint)" + RESET);
            String url = promptManual(reader, "  Base URL: ");
            if (url == null || url.isBlank()) return null;
            return url.trim();
        }

        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String getEnvVarName(String provider) {
        return ChatProviderRegistry.environmentVariable(provider);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static final class WizardOAuthInteraction implements OAuthProviderFlow.Interaction {
        private final LineReader reader;

        private WizardOAuthInteraction(LineReader reader) {
            this.reader = reader;
        }

        @Override
        public void info(String message) {
            System.out.println("  " + message);
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            System.out.println("  " + instructions);
            System.out.println("  " + url);
        }

        @Override
        public void deviceCode(
                String userCode,
                URI verificationUri,
                Integer intervalSeconds,
                Integer expiresInSeconds) {
            System.out.println("  Open " + verificationUri);
            System.out.println("  Enter device code: " + userCode);
        }

        @Override
        public String prompt(String message) throws IOException {
            try {
                return reader.readLine("  " + message + " ");
            } catch (RuntimeException e) {
                throw new IOException("Could not read OAuth input", e);
            }
        }
    }
}
