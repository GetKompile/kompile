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

package ai.kompile.cli.main.chat.gateway;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java tool gateway interceptor for the CLI chat loop.
 * <p>
 * Reads configuration from {@code ~/.kompile/config/tool-gateway-config.json}
 * and {@code feature-flags-config.json}. Uses {@link DirectLlmClient} to call
 * the kompile-model-staging server (or whatever is configured) for evaluation.
 * </p>
 * <p>
 * This is the CLI-side equivalent of the server-side {@code ToolGatewayService}.
 * It loads the same rules file and applies the same evaluation logic, but runs
 * outside Spring context.
 * </p>
 * <p>
 * <b>Important:</b> when enabled, {@link #evaluate} performs a synchronous LLM
 * round-trip <em>before</em> the gated tool runs. That network call is bounded by
 * {@code evaluationTimeoutMs} and is skipped entirely for cheap read-only local
 * tools (see {@link #ALWAYS_ALLOW_TOOLS}) so a mundane {@code grep}/{@code read}
 * never blocks on — or times out against — the staging LLM.
 * </p>
 */
public class CliToolGatewayInterceptor {

    /**
     * Cheap, read-only, purely-local tools that must NEVER be gated behind a
     * network LLM evaluation. A catch-all gateway rule (empty {@code toolPatterns}
     * or {@code "*"}) otherwise matches these, turning a local {@code grep} into a
     * blocking LLM call that can time out and surface as an MCP "disconnect".
     */
    static final Set<String> ALWAYS_ALLOW_TOOLS = Set.of(
            "read", "grep", "glob", "list");

    /** Floor for the evaluation timeout so a mis-configured {@code 0} can't insta-fail. */
    private static final long MIN_EVALUATION_TIMEOUT_MS = 1000L;

    private final ObjectMapper objectMapper;
    private final DirectLlmClient llmClient;
    private final CliToolGatewayRulesLoader rulesLoader;
    private final boolean enabled;
    private final boolean failOpen;
    private final boolean dryRun;
    private final boolean verboseLogging;
    private final long evaluationTimeoutMs;

    private CliToolGatewayInterceptor(ObjectMapper objectMapper, DirectLlmClient llmClient,
                                       CliToolGatewayRulesLoader rulesLoader,
                                       boolean enabled, boolean failOpen,
                                       boolean dryRun, boolean verboseLogging,
                                       long evaluationTimeoutMs) {
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.rulesLoader = rulesLoader;
        this.enabled = enabled;
        this.failOpen = failOpen;
        this.dryRun = dryRun;
        this.verboseLogging = verboseLogging;
        this.evaluationTimeoutMs = Math.max(MIN_EVALUATION_TIMEOUT_MS, evaluationTimeoutMs);
    }

    /**
     * Create an interceptor from the standard kompile config files.
     * Returns a no-op interceptor if gateway is disabled or config is missing.
     */
    public static CliToolGatewayInterceptor fromConfig(ObjectMapper objectMapper) {
        Path configDir = Path.of(System.getProperty("user.home"), ".kompile", "config");

        // Check feature flag
        boolean enabled = readFeatureFlag(configDir, objectMapper);
        if (!enabled) {
            return new CliToolGatewayInterceptor(objectMapper, null, null,
                    false, true, false, false, MIN_EVALUATION_TIMEOUT_MS);
        }

        // Load gateway config
        boolean failOpen = true;
        boolean dryRun = false;
        boolean verboseLogging = false;
        long evaluationTimeoutMs = 10_000L;
        String modelSource = "STAGING";

        Path configPath = configDir.resolve("tool-gateway-config.json");
        if (Files.exists(configPath)) {
            try {
                JsonNode config = objectMapper.readTree(Files.readString(configPath));
                failOpen = config.path("failOpen").asBoolean(true);
                dryRun = config.path("dryRun").asBoolean(false);
                verboseLogging = config.path("verboseLogging").asBoolean(false);
                evaluationTimeoutMs = config.path("evaluationTimeoutMs").asLong(10_000L);
                modelSource = config.path("modelSource").asText("STAGING");
            } catch (Exception e) {
                System.err.println("[Gateway] Failed to load config: " + e.getMessage());
            }
        }

        // Build LLM client from existing config
        DirectLlmClient llmClient = buildLlmClient(modelSource, objectMapper);
        CliToolGatewayRulesLoader rulesLoader = new CliToolGatewayRulesLoader(objectMapper);

        return new CliToolGatewayInterceptor(objectMapper, llmClient, rulesLoader,
                true, failOpen, dryRun, verboseLogging, evaluationTimeoutMs);
    }

    /**
     * Evaluate a tool call against gateway rules.
     *
     * @return the intercept result (ALLOW, BLOCK, or REWRITE)
     */
    public InterceptResult evaluate(String toolName, Map<String, Object> args) {
        if (!enabled || llmClient == null || rulesLoader == null) {
            return InterceptResult.allow();
        }

        // Never gate cheap, read-only, local tools behind a network LLM eval —
        // a catch-all rule would otherwise make grep/read/glob/list block on the
        // staging LLM and time out.
        if (isAlwaysAllowed(toolName)) {
            return InterceptResult.allow();
        }

        List<Map<String, Object>> matchingRules = rulesLoader.getMatchingRules(toolName);
        if (matchingRules.isEmpty()) {
            return InterceptResult.allow();
        }

        try {
            String systemPrompt = buildSystemPrompt(rulesLoader.getSystemPrompt());
            String userPrompt = buildUserPrompt(toolName, args, matchingRules);

            // Bound the LLM round-trip by evaluationTimeoutMs. Without this a slow or
            // hung staging server would stall the gated tool call indefinitely (the
            // underlying HttpClient read has no timeout), which the MCP client then
            // sees as a tool/connection timeout.
            String response = evaluateWithTimeout(userPrompt, systemPrompt);
            InterceptResult result = parseResponse(response);

            if (verboseLogging || result.action != InterceptAction.ALLOW) {
                System.err.println("[Gateway] " + result.action + " tool '" + toolName
                        + "': " + result.reason);
            }

            if (dryRun && result.action != InterceptAction.ALLOW) {
                System.err.println("[Gateway] DRY-RUN: would have " + result.action
                        + " but allowing");
                return InterceptResult.allow();
            }

            return result;

        } catch (TimeoutException te) {
            System.err.println("[Gateway] Evaluation timed out after " + evaluationTimeoutMs
                    + "ms for '" + toolName + "'; " + (failOpen ? "allowing" : "blocking"));
            return failOpen ? InterceptResult.allow()
                    : InterceptResult.block("Evaluation timed out after " + evaluationTimeoutMs + "ms");
        } catch (Exception e) {
            System.err.println("[Gateway] Evaluation failed for '" + toolName + "': " + e.getMessage());
            return failOpen ? InterceptResult.allow()
                    : InterceptResult.block("Evaluation failed: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** True for cheap, read-only, local tools that must bypass network gating. */
    static boolean isAlwaysAllowed(String toolName) {
        return toolName != null && ALWAYS_ALLOW_TOOLS.contains(toolName);
    }

    /**
     * Run the gateway LLM evaluation on a daemon worker, bounded by
     * {@code evaluationTimeoutMs}. Cancels the in-flight stream on timeout so the
     * worker can unwind instead of leaking.
     *
     * @throws TimeoutException if the LLM does not respond within the budget
     */
    private String evaluateWithTimeout(String userPrompt, String systemPrompt) throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(false);
        llmClient.setCancelSignal(cancel);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gateway-eval");
            t.setDaemon(true);
            return t;
        });
        Future<String> future = exec.submit(
                () -> llmClient.streamOneShot(userPrompt, systemPrompt, null).text);
        try {
            return future.get(evaluationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            cancel.set(true);      // best-effort: ask the stream to stop
            future.cancel(true);
            throw te;
        } finally {
            exec.shutdownNow();
        }
    }

    private static boolean readFeatureFlag(Path configDir, ObjectMapper objectMapper) {
        Path flagsPath = configDir.resolve("feature-flags-config.json");
        if (!Files.exists(flagsPath)) return false;
        try {
            JsonNode flags = objectMapper.readTree(Files.readString(flagsPath));
            return isGatewayFlagEnabled(flags);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read the tool-gateway feature flag. The canonical key is
     * {@code toolGatewayEnabled} (written by GlobalBootstrap/InitProjectCommand and
     * read by the server-side ToolGatewayConfigService); the shorter
     * {@code toolGateway} key used by some feature-flags files is honored as a
     * fallback so the two formats can't silently disagree.
     */
    static boolean isGatewayFlagEnabled(JsonNode flags) {
        if (flags == null) return false;
        return flags.path("toolGatewayEnabled")
                .asBoolean(flags.path("toolGateway").asBoolean(false));
    }

    private static DirectLlmClient buildLlmClient(String modelSource, ObjectMapper objectMapper) {
        if ("STAGING".equalsIgnoreCase(modelSource)) {
            // Use kompile-model-staging's OpenAI-compatible endpoint
            ChatConfig config = new ChatConfig("kompile", "kompile-gateway",
                    "default", "http://localhost:8090/v1");
            return new DirectLlmClient(config, objectMapper);
        }

        // GLOBAL — try to load from chat-config.json (user's configured provider)
        try {
            ChatConfig chatConfig = ChatConfig.loadOrFromEnv();
            return new DirectLlmClient(chatConfig, objectMapper);
        } catch (Exception e) {
            System.err.println("[Gateway] No LLM client available: " + e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(String customSystemPrompt) {
        StringBuilder sb = new StringBuilder();
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            sb.append(customSystemPrompt).append("\n\n");
        }
        sb.append("""
                You are a tool gateway evaluator. Examine the tool call and determine whether \
                it should be ALLOWED, REWRITTEN, or BLOCKED based on the rules.

                Respond with ONLY a JSON object:
                {"action": "ALLOW"|"REWRITE"|"BLOCK", "matchedRuleId": "<id or null>", \
                "reason": "<brief explanation>", "rewrittenArgs": {...} }
                """);
        return sb.toString();
    }

    private String buildUserPrompt(String toolName, Map<String, Object> args,
                                    List<Map<String, Object>> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(toolName).append("\nArgs: ");
        try {
            sb.append(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            sb.append(args);
        }
        sb.append("\n\nRules:\n");
        for (Map<String, Object> rule : rules) {
            sb.append("- ").append(rule.get("id")).append(": ")
                    .append(rule.get("condition")).append(" → ").append(rule.get("action")).append("\n");
        }
        return sb.toString();
    }

    private InterceptResult parseResponse(String response) {
        try {
            String cleaned = response.strip();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }

            JsonNode root = objectMapper.readTree(cleaned);
            String actionStr = root.path("action").asText("ALLOW");
            InterceptAction action;
            try {
                action = InterceptAction.valueOf(actionStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                action = InterceptAction.ALLOW;
            }

            String reason = root.path("reason").asText("");
            Map<String, Object> rewrittenArgs = null;
            if (action == InterceptAction.REWRITE && root.has("rewrittenArgs")
                    && !root.path("rewrittenArgs").isNull()) {
                rewrittenArgs = objectMapper.convertValue(root.get("rewrittenArgs"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }

            return new InterceptResult(action, reason, rewrittenArgs);
        } catch (Exception e) {
            return InterceptResult.allow();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Result types
    // ═══════════════════════════════════════════════════════════════════════════

    public enum InterceptAction { ALLOW, REWRITE, BLOCK }

    public static class InterceptResult {
        public final InterceptAction action;
        public final String reason;
        public final Map<String, Object> rewrittenArgs;

        public InterceptResult(InterceptAction action, String reason, Map<String, Object> rewrittenArgs) {
            this.action = action;
            this.reason = reason;
            this.rewrittenArgs = rewrittenArgs;
        }

        public static InterceptResult allow() {
            return new InterceptResult(InterceptAction.ALLOW, "", null);
        }

        public static InterceptResult block(String reason) {
            return new InterceptResult(InterceptAction.BLOCK, reason, null);
        }
    }
}
