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

import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerSetupWizard;
import ai.kompile.cli.main.chat.tools.ResumeTool;
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

    enum AuthMethod {
        NONE,
        OAUTH,
        API_KEY
    }

    record ProviderSelection(String vendor, String provider, AuthMethod authMethod) {}

    /** Exact provider wire value plus the label shown in the setup wizard. */
    record ThinkingOption(String value, String label) {}

    private static final List<String> STANDARD_RUNTIME_OPTIONS = List.of(
            "Kompile local model — start the packaged first-party serving subprocess (no full Kompile instance)",
            "External local endpoint — Ollama or OpenAI-compatible (no Kompile instance)",
            "Direct model provider — cloud API (no Kompile instance)",
            "Kompile instance — connect to one or start the installed kompile-chat service"
    );

    private static final List<String> EXTERNAL_LOCAL_OPTIONS = List.of(
            "Ollama",
            "OpenAI-compatible endpoint"
    );

    private static final List<String> CODEX_56_SOL_TERRA_EFFORTS =
            List.of("low", "medium", "high", "xhigh", "max", "ultra");
    private static final List<String> CODEX_56_LUNA_EFFORTS =
            List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> CODEX_CLASSIC_EFFORTS =
            List.of("low", "medium", "high", "xhigh");
    private static final List<String> OPENAI_56_EFFORTS =
            List.of("none", "low", "medium", "high", "xhigh", "max");
    private static final List<String> OPENAI_REASONING_EFFORTS =
            List.of("none", "low", "medium", "high", "xhigh");
    private static final List<String> O_SERIES_EFFORTS =
            List.of("low", "medium", "high");
    private static final List<String> XAI_EFFORTS =
            List.of("low", "medium", "high");

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

            // Step 2: If passthrough mode, select style then agent
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
            ProviderSelection providerSelection = null;

            if ("standard".equals(chatMode)) {
                providerSelection = selectStandardProvider(reader);
                if (providerSelection == null) return null;
                provider = providerSelection.provider();

                if (providerSelection.authMethod() == AuthMethod.OAUTH) {
                    OAuthProviderFlow.RequestAuth existing = resolveExistingCredential(provider);
                    if (existing != null && existing.oauth()) {
                        System.out.println(GREEN + "  ✓ Using existing OAuth credential for "
                                + vendorLabel(providerSelection.vendor()) + RESET);
                    } else if (!loginWithOAuth(reader, provider)) {
                        return null;
                    }
                } else if (providerSelection.authMethod() == AuthMethod.API_KEY) {
                    OAuthProviderFlow.RequestAuth existing = resolveExistingCredential(provider);
                    if (existing != null && !existing.oauth()) {
                        System.out.println(GREEN + "  ✓ Using existing managed/environment API key for "
                                + vendorLabel(providerSelection.vendor()) + RESET);
                    } else {
                        apiKey = promptApiKey(reader, provider);
                        if (apiKey == null) return null;
                    }
                }

                if (!"kompile".equals(provider)) {
                    model = selectModel(reader, provider);
                    if (model == null) return null;

                    if (supportsThinkingSelection(provider, model)) {
                        thinking = selectThinking(reader, provider, model);
                        if (thinking == null) return null;
                    }
                }

                baseUrl = promptBaseUrl(reader, provider);
            }

            // Build and save config
            ChatConfig config = new ChatConfig(provider, apiKey, model, baseUrl);
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
                    if (supportsThinkingSelection(provider, model)) {
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
            System.out.println(YELLOW + "  Warning: No CLI agents found on PATH." + RESET);
            String supportedAgents = String.join(", ", ChatConfig.getPassthroughAgents().values());
            if (supportedAgents.isBlank()) {
                supportedAgents = "Claude Code, Codex, Gemini, OpenCode, Qwen, Pi";
            }
            System.out.println("  Install one of: " + supportedAgents + ".");
            return null;
        }

        int selected = selectNumbered(reader, "Select CLI Agent:", availableAgents);
        if (selected < 0) return null;

        String selectedKey = agentKeys.get(selected);
        System.out.println("  → " + GREEN + availableAgents.get(selected) + RESET);
        System.out.println();
        return selectedKey;
    }

    // ── Provider selection ──────────────────────────────────────────────────

    static List<String> standardRuntimeOptions() {
        return STANDARD_RUNTIME_OPTIONS;
    }

    static List<String> externalLocalOptions() {
        return EXTERNAL_LOCAL_OPTIONS;
    }

    static List<String> directVendorOrder() {
        List<String> vendorKeys = new ArrayList<>();
        for (String key : ChatConfig.PROVIDER_ORDER) {
            if (!"kompile".equals(key)
                    && !"ollama".equals(key)
                    && !"openai-codex".equals(key)) {
                vendorKeys.add(key);
            }
        }
        return List.copyOf(vendorKeys);
    }

    static List<String> authOptions(String vendor) {
        List<String> options = new ArrayList<>();
        for (AuthMethod method : authMethods(vendor)) {
            options.add(authMethodLabel(method));
        }
        return List.copyOf(options);
    }

    static String resolveProviderForAuth(String vendor, AuthMethod authMethod) {
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("Vendor is required");
        }
        return switch (authMethod) {
            case NONE -> vendor;
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
        int selected = selectNumbered(reader, "Select External Local Endpoint:",
                externalLocalOptions());
        if (selected < 0) return null;
        if (selected == 0) {
            System.out.println("  → " + GREEN + "Ollama" + RESET);
            System.out.println();
            return new ProviderSelection("ollama", "ollama", AuthMethod.NONE);
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
        List<AuthMethod> methods = new ArrayList<>();
        if (oauthProviderForVendor(vendor) != null) {
            methods.add(AuthMethod.OAUTH);
        }
        if (supportsApiKey(vendor)) {
            methods.add(AuthMethod.API_KEY);
        }
        return List.copyOf(methods);
    }

    private static String oauthProviderForVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return null;
        }
        if ("openai".equalsIgnoreCase(vendor)) {
            return "openai-codex";
        }
        OAuthProviderRegistry registry = new OAuthProviderRegistry();
        return registry.find(vendor).isPresent() ? vendor : null;
    }

    private static boolean supportsApiKey(String vendor) {
        if (vendor == null || vendor.isBlank()
                || "kompile".equalsIgnoreCase(vendor)
                || "ollama".equalsIgnoreCase(vendor)) {
            return false;
        }
        if ("openai".equalsIgnoreCase(vendor)) {
            return true;
        }
        return !new OAuthProviderRegistry().isOAuthOnly(vendor);
    }

    private static String vendorLabel(String vendor) {
        if ("openai".equalsIgnoreCase(vendor)) {
            return "OpenAI";
        }
        return ChatConfig.PROVIDERS.getOrDefault(vendor, vendor);
    }

    private static String authMethodLabel(AuthMethod authMethod) {
        return switch (authMethod) {
            case OAUTH -> "OAuth / subscription sign-in";
            case API_KEY -> "API key";
            case NONE -> "None";
        };
    }

    // ── Model selection ─────────────────────────────────────────────────────

    static List<ThinkingOption> thinkingOptions(String provider, String model) {
        if (provider == null || model == null || model.isBlank()) {
            return List.of();
        }

        String normalizedProvider = provider.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedModel = model.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> efforts;
        String defaultEffort;

        switch (normalizedProvider) {
            case "openai-codex" -> {
                if (normalizedModel.equals("gpt-5.6-sol")) {
                    efforts = CODEX_56_SOL_TERRA_EFFORTS;
                    defaultEffort = "low";
                } else if (normalizedModel.equals("gpt-5.6-terra")) {
                    efforts = CODEX_56_SOL_TERRA_EFFORTS;
                    defaultEffort = "medium";
                } else if (normalizedModel.equals("gpt-5.6-luna")) {
                    efforts = CODEX_56_LUNA_EFFORTS;
                    defaultEffort = "medium";
                } else {
                    efforts = CODEX_CLASSIC_EFFORTS;
                    defaultEffort = "medium";
                }
            }
            case "github-copilot" -> {
                if (normalizedModel.equals("gpt-5.6-terra")) {
                    efforts = CODEX_56_SOL_TERRA_EFFORTS;
                    defaultEffort = "medium";
                } else if (normalizedModel.startsWith("gpt-5")) {
                    efforts = CODEX_CLASSIC_EFFORTS;
                    defaultEffort = "medium";
                } else if (normalizedModel.startsWith("grok-")
                        || normalizedModel.startsWith("mai-code-")) {
                    efforts = XAI_EFFORTS;
                    defaultEffort = "high";
                } else {
                    return List.of();
                }
            }
            case "openai" -> {
                if (normalizedModel.startsWith("gpt-5.6")) {
                    efforts = OPENAI_56_EFFORTS;
                    defaultEffort = "medium";
                } else if (normalizedModel.startsWith("gpt-5")) {
                    efforts = OPENAI_REASONING_EFFORTS;
                    defaultEffort = "medium";
                } else if (normalizedModel.matches("o[1-9].*")) {
                    efforts = O_SERIES_EFFORTS;
                    defaultEffort = "medium";
                } else {
                    return List.of();
                }
            }
            case "xai" -> {
                if (!normalizedModel.startsWith("grok-4")) {
                    return List.of();
                }
                efforts = XAI_EFFORTS;
                defaultEffort = "high";
            }
            default -> {
                return List.of();
            }
        }

        List<ThinkingOption> options = new ArrayList<>();
        options.add(new ThinkingOption("",
                "default — " + defaultEffort + " (recommended)"));
        for (String effort : efforts) {
            options.add(new ThinkingOption(effort, effort));
        }
        return List.copyOf(options);
    }

    static boolean supportsThinkingSelection(String provider, String model) {
        return thinkingOptions(provider, model).size() > 1;
    }

    private static String selectThinking(LineReader reader, String provider, String model) {
        List<ThinkingOption> options = thinkingOptions(provider, model);
        List<String> labels = options.stream().map(ThinkingOption::label).toList();
        int selected = selectNumbered(reader,
                "Select " + reasoningVendorLabel(provider) + " Reasoning Effort:", labels);
        if (selected < 0) return null;

        String effort = options.get(selected).value();
        System.out.println("  → " + GREEN
                + (effort.isBlank() ? options.get(selected).label() : effort) + RESET);
        System.out.println();
        return effort;
    }

    private static String reasoningVendorLabel(String provider) {
        if (provider == null) return "Model";
        return switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "openai-codex" -> "OpenAI Codex";
            case "openai" -> "OpenAI";
            case "github-copilot" -> "GitHub Copilot";
            case "xai" -> "xAI";
            default -> "Model";
        };
    }

    private static String selectModel(LineReader reader, String provider) {
        String[] defaults = ChatConfig.getDefaultModels(provider);

        if (defaults.length == 0) {
            return promptManual(reader, "  Model name: ");
        }

        List<String> models = new ArrayList<>();
        for (int i = 0; i < defaults.length; i++) {
            String suffix = (i == 0) ? " (recommended)" : "";
            models.add(defaults[i] + suffix);
        }
        models.add("Custom...");

        int selected = selectNumbered(reader, "Select Model:", models);
        if (selected < 0) return null;

        if (selected < defaults.length) {
            String model = defaults[selected];
            System.out.println("  → " + GREEN + model + RESET);
            System.out.println();
            return model;
        } else {
            String prompt = "kompile-local".equals(provider)
                    ? "  Local .gguf/.sdz model path: "
                    : "  Custom model name: ";
            return promptManual(reader, prompt);
        }
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

    private static String promptBaseUrl(LineReader reader, String provider) {
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
        return switch (provider) {
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
