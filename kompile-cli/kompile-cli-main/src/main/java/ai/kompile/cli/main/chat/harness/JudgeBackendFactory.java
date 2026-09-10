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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.JudgeDefaults;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory that builds the appropriate {@link JudgeBackend} based on
 * {@link HarnessConfig#getJudgeMode()}.
 * <p>
 * Resolution order for <b>auto</b> (default):
 * <ol>
 *   <li>CLI agent — use an installed CLI agent (claude, codex, gemini, etc.)
 *       which is already authenticated through its own login flow</li>
 *   <li>kompile staging — if kompile-model-staging is running on port 8090</li>
 *   <li>local — in-process SameDiff inference if samediff-llm is on classpath</li>
 *   <li>remote — dedicated judge provider or chat-config fallback (API-based)</li>
 * </ol>
 * <p>
 * Explicit modes: {@code cli}, {@code remote}, {@code local}, {@code auto-server}.
 */
public class JudgeBackendFactory {

    private static final JudgeBackend GLOBALLY_DISABLED = new JudgeBackend() {
        @Override
        public String generate(String userPrompt, String systemPrompt) {
            throw new IllegalStateException("Judge is disabled by the global judge setting");
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String describe() {
            return "disabled(global)";
        }
    };

    /**
     * Build a judge backend for the main chat loop (has access to main chat's LLM client).
     */
    public static JudgeBackend create(DirectLlmClient mainChatClient, HarnessConfig config,
                                       ObjectMapper objectMapper) {
        if (!config.isJudgeGlobalEnabled()) {
            return GLOBALLY_DISABLED;
        }
        Path workingDirectory = mainChatClient == null ? null : mainChatClient.getWorkingDirectory();
        String mode = config.getJudgeMode();
        if (mode == null) mode = "auto";

        JudgeBackend primary = switch (mode.toLowerCase()) {
            case "cli" -> new CliJudgeBackend(config.getJudgeModel(), workingDirectory);
            case "remote" -> createRemote(mainChatClient, config, objectMapper, workingDirectory);
            case "local" -> createLocal(config);
            case "auto-server" -> createAutoServer(config, objectMapper, workingDirectory);
            default -> createAuto(mainChatClient, config, objectMapper, workingDirectory);
        };
        return withResilience(
                primary, config, objectMapper,
                mainChatClient == null ? null : mainChatClient.getChatConfig(), workingDirectory);
    }

    /**
     * Build a judge backend for headless/MCP mode (no main chat client available).
     * Uses the same auto resolution as the main chat loop.
     */
    public static JudgeBackend create(HarnessConfig config, ObjectMapper objectMapper) {
        return create(config, objectMapper, null);
    }

    /** Build a headless judge using the owning project's profiles and chat configuration. */
    public static JudgeBackend create(HarnessConfig config, ObjectMapper objectMapper, Path workingDirectory) {
        if (!config.isJudgeGlobalEnabled()) {
            return GLOBALLY_DISABLED;
        }
        DirectLlmClient fallbackClient = null;
        ChatConfig fallbackChatConfig = null;
        if ((config.getJudgeProvider() == null || config.getJudgeProvider().isBlank())) {
            ChatConfig chatConfig = ChatConfig.loadOrFromEnv(workingDirectory);
            if (chatConfig != null && chatConfig.isValid()) {
                fallbackClient = createDirectJudgeClient(
                        chatConfig, null, null, config.getJudgeModel(), null,
                        objectMapper, workingDirectory);
                fallbackChatConfig = fallbackClient == null
                        ? null : fallbackClient.getChatConfig();
            }
        }

        String mode = config.getJudgeMode();
        if (mode == null) mode = "auto";

        JudgeBackend primary;
        try {
            primary = switch (mode.toLowerCase()) {
                case "cli" -> new CliJudgeBackend(config.getJudgeModel(), workingDirectory);
                case "remote" -> createRemote(fallbackClient, config, objectMapper, workingDirectory);
                case "local" -> createLocal(config);
                case "auto-server" -> createAutoServer(config, objectMapper, workingDirectory);
                default -> createAuto(fallbackClient, config, objectMapper, workingDirectory);
            };
        } finally {
            // createRemote clones the route into an owned low-latency judge client. The temporary
            // headless fallback is never retained by the selected backend.
            if (fallbackClient != null) {
                fallbackClient.close();
            }
        }
        return withResilience(
                primary, config, objectMapper,
                fallbackChatConfig, workingDirectory);
    }

    /**
     * Wrap a resolved judge backend with model-swap + per-call-deadline resilience. Backups are
     * built from {@link HarnessConfig#getJudgeSwapCandidates()} when a dedicated judge client can
     * be constructed (i.e. a judge provider/key is configured). The wrapper is added whenever a
     * deadline is set or backups exist, so all judge paths (chat, enforcer, MCP) get it.
     */
    public static JudgeBackend withResilience(JudgeBackend primary, HarnessConfig config,
                                              ObjectMapper objectMapper) {
        return withResilience(primary, config, objectMapper, null, null);
    }

    /** Apply resilience with the effective chat route available for inherited backup models. */
    public static JudgeBackend withResilience(
            JudgeBackend primary, HarnessConfig config, ObjectMapper objectMapper,
            ChatConfig fallbackChatConfig, Path workingDirectory) {
        if (primary == null) {
            return null;
        }
        long deadlineMs = config.getJudgeDeadlineMs();
        long cooldownMs = config.getRateLimitCooldownMs();
        List<JudgeBackend> backups = new ArrayList<>();
        List<String> candidates = config.getJudgeSwapCandidates();
        List<String> validCandidates = candidates == null ? List.of() : candidates.stream()
                .filter(model -> model != null && !model.isBlank())
                .map(String::trim)
                .toList();
        if (!validCandidates.isEmpty()) {
            DirectLlmClient judgeClient = buildDedicatedJudgeClient(config, objectMapper, workingDirectory);
            if (judgeClient == null && fallbackChatConfig != null) {
                judgeClient = createDirectJudgeClient(
                        fallbackChatConfig, config.getJudgeProvider(), config.getJudgeApiKey(),
                        config.getJudgeModel(), config.getJudgeBaseUrl(), objectMapper,
                        workingDirectory);
            }
            if (judgeClient != null) {
                try {
                    for (String model : validCandidates) {
                        DirectLlmClient backup = createDirectJudgeClient(judgeClient.getChatConfig(),
                                null, null, model, null, objectMapper, workingDirectory);
                        if (backup != null) {
                            backups.add(new RemoteJudgeBackend(backup, null, backup.getConfiguredProvider()));
                        }
                    }
                } finally {
                    judgeClient.close();
                }
            }
        }
        if (deadlineMs <= 0 && backups.isEmpty()) {
            return primary; // nothing to add
        }
        return new ResilientJudgeBackend(primary, backups, deadlineMs, cooldownMs);
    }

    /**
     * Auto mode: CLI agents first (already authenticated), then kompile staging,
     * then local SameDiff, then remote API as last resort.
     */
    private static JudgeBackend createAuto(DirectLlmClient mainChatClient,
                                            HarnessConfig config, ObjectMapper objectMapper, Path workingDirectory) {
        // 1. CLI agents — pre-authenticated, no config needed, always prefer these
        if (CliJudgeBackend.anyAgentAvailable()) {
            return new CliJudgeBackend(null, workingDirectory); // auto-detect best available
        }

        // 2. Kompile staging server — our own inference platform
        ServerJudgeBackend kompileBackend = new ServerJudgeBackend(
                ServerJudgeBackend.ServerType.KOMPILE, config.getJudgeModel(),
                config.getJudgeServerPort(), objectMapper, workingDirectory);
        if (kompileBackend.isAvailable()) {
            return kompileBackend;
        }

        // 3. Local SameDiff inference (if samediff-llm is on classpath)
        if (LocalJudgeBackend.checkClassesAvailable()) {
            JudgeBackend local = createLocal(config);
            if (local.isAvailable()) {
                return local;
            }
        }

        // 4. Remote API (dedicated judge provider or chat-config fallback)
        JudgeBackend remote = createRemote(mainChatClient, config, objectMapper, workingDirectory);
        if (remote != null && remote.isAvailable()) {
            return remote;
        }

        // Nothing available
        return new CliJudgeBackend(null, workingDirectory); // will report isAvailable=false
    }

    private static JudgeBackend createRemote(DirectLlmClient mainChatClient,
                                              HarnessConfig config, ObjectMapper objectMapper, Path workingDirectory) {
        // Try dedicated judge client first
        DirectLlmClient judgeClient = buildDedicatedJudgeClient(config, objectMapper, workingDirectory);
        if (judgeClient != null) {
            return new RemoteJudgeBackend(judgeClient, null, config.getJudgeProvider());
        }

        // Fall back to an isolated copy of the main chat route. Judge verdicts must not inherit
        // main-chat history, high reasoning effort, or its multi-attempt retry budget.
        if (mainChatClient != null) {
            DirectLlmClient isolated = createDirectJudgeClient(
                    mainChatClient.getChatConfig(), config.getJudgeProvider(),
                    config.getJudgeApiKey(), config.getJudgeModel(), config.getJudgeBaseUrl(),
                    objectMapper, workingDirectory);
            if (isolated != null) {
                return new RemoteJudgeBackend(isolated, null, "main-chat");
            }
        }

        return null;
    }

    private static JudgeBackend createLocal(HarnessConfig config) {
        return new LocalJudgeBackend(config.getJudgeLocalModel(), config.getJudgeLocalQuant());
    }

    private static JudgeBackend createAutoServer(HarnessConfig config, ObjectMapper objectMapper, Path workingDirectory) {
        ServerJudgeBackend.ServerType serverType = ServerJudgeBackend.ServerType.KOMPILE;
        if ("ollama".equalsIgnoreCase(config.getJudgeServerType())) {
            serverType = ServerJudgeBackend.ServerType.OLLAMA;
        }
        return new ServerJudgeBackend(
                serverType, config.getJudgeModel(), config.getJudgeServerPort(), objectMapper, workingDirectory);
    }

    /**
     * Build a dedicated DirectLlmClient for judge calls when the judge provider
     * is explicitly configured with its own credentials.
     */
    private static DirectLlmClient buildDedicatedJudgeClient(HarnessConfig config,
                                                               ObjectMapper objectMapper, Path workingDirectory) {
        String provider = config.getJudgeProvider();
        if (provider == null || provider.isBlank()) return null;

        String apiKey = config.getJudgeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = resolveApiKeyFromEnv(provider);
        }
        String model = JudgeDefaults.resolve(provider, workingDirectory, config.getJudgeModel(), null).model();
        ChatConfig judgeConfig = new ChatConfig(provider, apiKey, model, config.getJudgeBaseUrl());
        OAuthProviderFlow.RequestAuth requestAuth = judgeConfig.resolveRequestAuth();
        if ((requestAuth == null || requestAuth.token() == null || requestAuth.token().isBlank())
                && !"ollama".equalsIgnoreCase(provider)
                && !"kompile".equalsIgnoreCase(provider)) {
            return null;
        }
        if (model == null || model.isBlank()) {
            model = judgeConfig.getConfiguredModels(provider).stream().findFirst().orElse(null);
        }
        if (model == null || model.isBlank()) return null;
        judgeConfig.setModel(model);
        judgeConfig.setThinking(JudgeDefaults.resolve(provider, workingDirectory, model, null).thinking());
        return DirectLlmClient.withConnectivityPolicy(
                judgeConfig, objectMapper,
                judgeConfig.connectivityPolicy().withMaxAttempts(1), workingDirectory);
    }

    /**
     * Build a private direct-provider client for a judge lane. It preserves the selected
     * provider's authentication route without sharing mutable chat configuration, disables an
     * inherited reasoning override in favor of supported low effort, and uses one provider
     * attempt beneath the judge deadline.
     */
    public static DirectLlmClient createDirectJudgeClient(
            ChatConfig baseChatConfig,
            String providerOverride,
            String apiKeyOverride,
            String modelOverride,
            String baseUrlOverride,
            ObjectMapper objectMapper,
            Path workingDirectory) {
        if (baseChatConfig == null) {
            return null;
        }
        String provider = providerOverride == null || providerOverride.isBlank()
                ? baseChatConfig.getProvider() : providerOverride.trim();
        boolean sameProvider = baseChatConfig.getProvider() != null
                && provider != null && provider.equalsIgnoreCase(baseChatConfig.getProvider());
        JudgeDefaults.Selection selection = JudgeDefaults.resolve(provider, workingDirectory, modelOverride,
                sameProvider ? baseChatConfig.getModel() : null);
        String model = selection.model();
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return null;
        }
        String apiKey = apiKeyOverride == null || apiKeyOverride.isBlank()
                ? null : apiKeyOverride;
        String authenticationMethod = null;
        if (apiKey != null) {
            authenticationMethod = "api-key";
        } else if (sameProvider) {
            authenticationMethod = baseChatConfig.getAuthenticationMethod();
            OAuthProviderFlow.RequestAuth inherited = baseChatConfig.resolveRequestAuth();
            if (inherited != null && !inherited.oauth()) {
                apiKey = inherited.token();
                authenticationMethod = "api-key";
            }
        }

        String baseUrl = baseUrlOverride == null || baseUrlOverride.isBlank()
                ? (sameProvider ? baseChatConfig.getBaseUrl() : null) : baseUrlOverride;
        ChatConfig judgeConfig = new ChatConfig(provider, apiKey, model, baseUrl);
        judgeConfig.setAuthenticationMethod(authenticationMethod);
        judgeConfig.setPromptCacheRetention(baseChatConfig.getPromptCacheRetention());
        if (sameProvider && judgeConfig.isKompileLocalServing()
                && baseChatConfig.getLocalServingBinding() != null
                && java.util.Objects.equals(baseUrl, baseChatConfig.getBaseUrl())) {
            // Copy the restart route, not just a port that may disappear during idle eviction.
            judgeConfig.setLocalServingBinding(
                    baseChatConfig.getLocalServingBinding().forChatConfig(judgeConfig));
        }
        judgeConfig.setThinking(selection.thinking());
        return DirectLlmClient.withConnectivityPolicy(
                judgeConfig, objectMapper,
                judgeConfig.connectivityPolicy().withMaxAttempts(1), workingDirectory);
    }

    private static String resolveApiKeyFromEnv(String provider) {
        String environmentVariable = ChatProviderRegistry.environmentVariable(provider);
        return environmentVariable == null ? null : System.getenv(environmentVariable);
    }
}
