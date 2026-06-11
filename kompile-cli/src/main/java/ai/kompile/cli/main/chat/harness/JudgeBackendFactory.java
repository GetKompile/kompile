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
import com.fasterxml.jackson.databind.ObjectMapper;

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

    /**
     * Build a judge backend for the main chat loop (has access to main chat's LLM client).
     */
    public static JudgeBackend create(DirectLlmClient mainChatClient, HarnessConfig config,
                                       ObjectMapper objectMapper) {
        String mode = config.getJudgeMode();
        if (mode == null) mode = "auto";

        return switch (mode.toLowerCase()) {
            case "cli" -> new CliJudgeBackend(config.getJudgeModel());
            case "remote" -> createRemote(mainChatClient, config, objectMapper);
            case "local" -> createLocal(config);
            case "auto-server" -> createAutoServer(config, objectMapper);
            default -> createAuto(mainChatClient, config, objectMapper);
        };
    }

    /**
     * Build a judge backend for headless/MCP mode (no main chat client available).
     * Uses the same auto resolution as the main chat loop.
     */
    public static JudgeBackend create(HarnessConfig config, ObjectMapper objectMapper) {
        DirectLlmClient fallbackClient = null;
        if ((config.getJudgeProvider() == null || config.getJudgeProvider().isBlank())) {
            ChatConfig chatConfig = ChatConfig.loadOrFromEnv();
            if (chatConfig != null && chatConfig.isValid()) {
                fallbackClient = new DirectLlmClient(chatConfig, objectMapper);
            }
        }

        String mode = config.getJudgeMode();
        if (mode == null) mode = "auto";

        return switch (mode.toLowerCase()) {
            case "cli" -> new CliJudgeBackend(config.getJudgeModel());
            case "remote" -> createRemote(fallbackClient, config, objectMapper);
            case "local" -> createLocal(config);
            case "auto-server" -> createAutoServer(config, objectMapper);
            default -> createAuto(fallbackClient, config, objectMapper);
        };
    }

    /**
     * Auto mode: CLI agents first (already authenticated), then kompile staging,
     * then local SameDiff, then remote API as last resort.
     */
    private static JudgeBackend createAuto(DirectLlmClient mainChatClient,
                                            HarnessConfig config, ObjectMapper objectMapper) {
        // 1. CLI agents — pre-authenticated, no config needed, always prefer these
        if (CliJudgeBackend.anyAgentAvailable()) {
            return new CliJudgeBackend(null); // auto-detect best available
        }

        // 2. Kompile staging server — our own inference platform
        ServerJudgeBackend kompileBackend = new ServerJudgeBackend(
                ServerJudgeBackend.ServerType.KOMPILE, config.getJudgeModel(),
                config.getJudgeServerPort(), objectMapper);
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
        JudgeBackend remote = createRemote(mainChatClient, config, objectMapper);
        if (remote != null && remote.isAvailable()) {
            return remote;
        }

        // Nothing available
        return new CliJudgeBackend(null); // will report isAvailable=false
    }

    private static JudgeBackend createRemote(DirectLlmClient mainChatClient,
                                              HarnessConfig config, ObjectMapper objectMapper) {
        // Try dedicated judge client first
        DirectLlmClient judgeClient = buildDedicatedJudgeClient(config, objectMapper);
        if (judgeClient != null) {
            return new RemoteJudgeBackend(judgeClient, null, config.getJudgeProvider());
        }

        // Fall back to main chat client with model override
        if (mainChatClient != null) {
            return new RemoteJudgeBackend(mainChatClient, config.getJudgeModel(), "main-chat");
        }

        return null;
    }

    private static JudgeBackend createLocal(HarnessConfig config) {
        return new LocalJudgeBackend(config.getJudgeLocalModel(), config.getJudgeLocalQuant());
    }

    private static JudgeBackend createAutoServer(HarnessConfig config, ObjectMapper objectMapper) {
        ServerJudgeBackend.ServerType serverType = ServerJudgeBackend.ServerType.KOMPILE;
        if ("ollama".equalsIgnoreCase(config.getJudgeServerType())) {
            serverType = ServerJudgeBackend.ServerType.OLLAMA;
        }
        return new ServerJudgeBackend(
                serverType, config.getJudgeModel(), config.getJudgeServerPort(), objectMapper);
    }

    /**
     * Build a dedicated DirectLlmClient for judge calls when the judge provider
     * is explicitly configured with its own credentials.
     */
    private static DirectLlmClient buildDedicatedJudgeClient(HarnessConfig config,
                                                               ObjectMapper objectMapper) {
        String provider = config.getJudgeProvider();
        if (provider == null || provider.isBlank()) return null;

        String apiKey = config.getJudgeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = resolveApiKeyFromEnv(provider);
        }
        if ((apiKey == null || apiKey.isBlank()) && !"ollama".equals(provider) && !"kompile".equals(provider)) {
            return null;
        }

        String model = config.getJudgeModel();
        if (model == null || model.isBlank()) {
            String[] defaults = ChatConfig.getDefaultModels(provider);
            model = defaults.length > 0 ? defaults[0] : null;
        }
        if (model == null) return null;

        ChatConfig judgeConfig = new ChatConfig(provider, apiKey, model, config.getJudgeBaseUrl());
        return new DirectLlmClient(judgeConfig, objectMapper);
    }

    private static String resolveApiKeyFromEnv(String provider) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> System.getenv("ANTHROPIC_API_KEY");
            case "openai" -> System.getenv("OPENAI_API_KEY");
            case "gemini" -> System.getenv("GOOGLE_API_KEY");
            case "openrouter" -> System.getenv("OPENROUTER_API_KEY");
            case "deepseek" -> System.getenv("DEEPSEEK_API_KEY");
            case "groq" -> System.getenv("GROQ_API_KEY");
            default -> null;
        };
    }
}
