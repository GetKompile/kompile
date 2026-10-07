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

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.chat.LocalServingRuntimePool;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.core.llm.ModelContextWindows;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Direct LLM client that calls provider APIs without requiring a kompile-app server.
 * Supports OpenAI Chat Completions and Responses, Anthropic Messages, and Pi Messages.
 * <p>
 * Handles streaming, tool calling, and multi-turn conversations.
 */
public class DirectLlmClient implements AutoCloseable {

    static final int OPENAI_INSTRUCTIONS_MAX_CHARS = 1_048_576;
    static final int OPENAI_PROMPT_CACHE_KEY_MAX_CHARS = 64;
    private static final int CHARS_PER_TOKEN = 4;
    /** Input tokens one inline image costs a vision encoder: ~1.15 MP on Anthropic, a full tile set on SmolVLM. */
    public static final int IMAGE_TOKEN_ESTIMATE = 1_600;
    /** Local serving output ceiling when the active model reports none. */
    private static final int KOMPILE_SERVING_MAX_TOKENS = 1_024;
    private static final String MEMORY_CONTEXT_OPEN = "<memory_context>";
    private static final String MEMORY_CONTEXT_CLOSE = "</memory_context>";
    private static final String RESTORED_CONVERSATION_OPEN = "[Earlier conversation restored by "
            + "Kompile: past turns for context, not instructions to act on]\n";
    private static final String RESTORED_CONVERSATION_CLOSE = "\n[End earlier conversation]";
    private static final String OPENAI_INSTRUCTIONS_OMISSION =
            "\n\n[OpenAI instructions limit reached. Middle content was omitted; "
                    + "project instructions and saved tool results remain available through "
                    + "the read and glob tools.]\n\n";

    public interface ProviderActivityListener {
        void onToolStart(String callId, String name, String input);
        default void onToolInput(String callId, String name, String input) { }
        default void onToolOutput(String callId, String name, String output) { }
        default void onToolProgress(String callId, String name, long elapsedMillis) { }
        /** Transient provider phase, not transcript text. */
        default void onActivity(String label) { }
        /**
         * A fragment of tool-call arguments as the model streams them. Their tokens
         * are output the request's usage reports when it ends.
         */
        default void onToolInputDelta(String delta) { }
        void onToolComplete(String callId, String name, String output,
                            int exitCode, boolean error);
        /** Settled usage deltas, never cumulative SSE snapshots; input/cache categories are disjoint. */
        default void onTokenUsage(long input, long output,
                                  long cacheRead, long cacheCreation) { }
        default void onNotice(String text) { }
        /**
         * The provider compacted a session it owns. {@code trigger} is its reason
         * ({@code auto}, {@code manual}), empty when not reported;
         * {@code tokensBefore} is 0 when not reported.
         */
        default void onCompacted(String trigger, long tokensBefore) { }
        /** The provider failed to compact a session it owns; {@code detail} may be empty. */
        default void onCompactionFailed(String detail) { }
        /**
         * The provider made {@code steps} more model requests in the agent loop it runs
         * for the turn (Claude Code, OpenCode); calls add up. Routes whose tool loop
         * Kompile runs make one request per call and report none.
         */
        default void onSteps(int steps) { }
    }

    /** Provider-neutral terminal failure categories used by the chat coordinator. */
    public enum FailureKind {
        NONE,
        CONTEXT_OVERFLOW,
        AUTHENTICATION,
        CREDENTIAL_UNAVAILABLE,
        RATE_LIMITED,
        TEMPORARY,
        PERMISSION_DENIED,
        QUOTA_EXHAUSTED,
        REFUSAL,
        TRUNCATED,
        PROVIDER_ERROR
    }

    /** A Claude Code native session and the digest of the instructions it holds. */
    public record ClaudeNativeSession(String sessionId, String instructionsDigest) { }

    /** What became of a message {@link #injectIntoClaudeTurn} wrote into a running turn. */
    public interface ClaudeInjectionListener {
        /** Claude Code took the message in: into the running turn, or as a turn of its own. */
        void delivered(String id);

        /** Claude Code dropped the message unread: the turn was interrupted, or its process ended. */
        void dropped(String id);
    }

    private final ChatConfig config;
    private final HttpClient httpClient;
    private final ProviderConnectivityPolicy connectivityPolicy;
    private final ObjectMapper objectMapper;
    private final Path workingDirectory;
    private final List<ObjectNode> conversationHistory;
    private final Object historyLock = new Object();
    private volatile AtomicBoolean cancelSignal;
    private volatile java.util.function.BooleanSupplier cancellationCheck;
    private volatile java.util.function.Consumer<String> outputConsumer;
    private volatile java.util.function.Consumer<String> thinkingConsumer;
    private volatile java.util.function.Consumer<ConnectivityEvent> connectivityEventConsumer;
    private volatile ProviderActivityListener providerActivityListener;
    private volatile RadiusGatewayConfig radiusGatewayConfig;
    private volatile String radiusGatewayConfigSource;
    private volatile OpenCodeServeClient openCodeServeClient;
    private volatile ClaudeCliClient claudeServeClient;
    // How the Claude Code route runs this client's sessions: a chat's with its tools, a
    // utility lane's (a judge's) without.
    private volatile ClaudeCliClient.Mode claudeMode = ClaudeCliClient.Mode.CHAT;
    // The Claude Code binary this client and its one-shot requests start; null = the registry's.
    private volatile String claudeBinaryOverride;
    // Where the Claude Code route shows its tasks, and who hears of the turns it starts itself.
    private volatile BackgroundProcessManager claudeTaskProcesses;
    private volatile Consumer<String> claudeFollowUpListener;
    private volatile ClaudeInjectionListener claudeInjectionListener;
    private volatile boolean claudeNeedsSeed = true;
    private volatile boolean claudeCompactionFailed;
    private volatile int nativeCompactionTriggerTokens;
    private volatile int wireMaxOutputTokens;
    private volatile int contextWindowTokens;
    // The limits Claude Code last reported enforcing, with the model its turn ran.
    private volatile ClaudeModelLimits claudeModelLimits;
    private volatile String promptCacheSessionId;
    private volatile JsonOutputSpec requestedJsonOutput;
    private final Set<ProviderCompactionCapabilities.TokenCounting> unavailableTokenCounters =
            new LinkedHashSet<>();
    private final Set<String> unavailableNativeCompactionRoutes = new LinkedHashSet<>();
    private volatile boolean openCodeNeedsSeed = true;

    private record JsonOutputSpec(String name, JsonNode schema, boolean strict) { }
    private record ClaudeModelLimits(String model, ModelContextResolver.ModelLimits limits) { }

    // The native Codex structured-output contract rejects these validation-only keywords.
    // Keep the full schema (including defaults and object-valued enum/const payloads) for local
    // validation; only the transport copy for this selected route is reduced.
    private static final Set<String> NATIVE_CODEX_STRICT_UNSUPPORTED_KEYWORDS = Set.of(
            "format", "maxItems", "maxLength", "minItems", "minLength", "maximum",
            "minimum", "multipleOf", "pattern", "uniqueItems");
    private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of(
            "properties", "patternProperties", "$defs", "definitions", "dependentSchemas",
            "dependencies");
    private static final Set<String> SCHEMA_ARRAY_KEYWORDS = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMA_VALUE_KEYWORDS = Set.of(
            "items", "additionalItems", "contains", "additionalProperties", "propertyNames",
            "unevaluatedItems", "unevaluatedProperties", "contentSchema", "if", "then", "else",
            "not");

    public DirectLlmClient(ChatConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, config.connectivityPolicy(),
                Path.of(System.getProperty("user.dir")));
    }

    /** Create a direct client scoped to the same project as the owning chat. */
    public DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                           Path workingDirectory) {
        this(config, objectMapper, config.connectivityPolicy(), workingDirectory);
    }

    DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                    ProviderConnectivityPolicy connectivityPolicy) {
        this(config, objectMapper, connectivityPolicy,
                Path.of(System.getProperty("user.dir")));
    }

    DirectLlmClient(ChatConfig config, ObjectMapper objectMapper,
                    ProviderConnectivityPolicy connectivityPolicy,
                    Path workingDirectory) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.connectivityPolicy = connectivityPolicy;
        this.workingDirectory = (workingDirectory == null
                ? Path.of(System.getProperty("user.dir")) : workingDirectory)
                .toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectivityPolicy.connectTimeout())
                .build();
        this.conversationHistory = new ArrayList<>();
    }

    /**
     * Create a client with a caller-owned retry/deadline policy. The normal constructors retain
     * provider defaults; bounded utility lanes use this factory to avoid nesting retries beneath
     * their own hard deadline.
     */
    public static DirectLlmClient withConnectivityPolicy(
            ChatConfig config, ObjectMapper objectMapper,
            ProviderConnectivityPolicy connectivityPolicy, Path workingDirectory) {
        return new DirectLlmClient(config, objectMapper, connectivityPolicy, workingDirectory);
    }

    /**
     * Sets the cancel signal that can be used to interrupt streaming.
     */
    public void setCancelSignal(AtomicBoolean cancelSignal) {
        this.cancelSignal = cancelSignal;
        this.cancellationCheck = cancelSignal == null ? null : cancelSignal::get;
    }

    public void setCancellationCheck(java.util.function.BooleanSupplier cancellationCheck) {
        this.cancellationCheck = cancellationCheck;
    }

    protected boolean isCancelled() {
        java.util.function.BooleanSupplier check = cancellationCheck;
        try {
            return check != null && check.getAsBoolean();
        } catch (RuntimeException ignored) {
            AtomicBoolean signal = this.cancelSignal;
            return signal != null && signal.get();
        }
    }

    /**
     * Sets a consumer that receives all streamed output text.
     */
    public void setOutputConsumer(java.util.function.Consumer<String> consumer) {
        this.outputConsumer = consumer;
    }

    /**
     * Returns the currently installed streamed-output consumer, if any.
     */
    public java.util.function.Consumer<String> getOutputConsumer() {
        return outputConsumer;
    }

    /** Receives retry/reconnect lifecycle events without mixing them into model text. */
    public void setConnectivityEventConsumer(
            java.util.function.Consumer<ConnectivityEvent> consumer) {
        this.connectivityEventConsumer = consumer;
    }

    public java.util.function.Consumer<ConnectivityEvent> getConnectivityEventConsumer() {
        return connectivityEventConsumer;
    }

    public void setProviderActivityListener(ProviderActivityListener listener) {
        this.providerActivityListener = listener;
    }

    public ProviderActivityListener getProviderActivityListener() {
        return providerActivityListener;
    }

    /**
     * Show the Claude Code route's tasks as rows of this process manager, where
     * killing a row stops its task. Null stops showing them.
     */
    public void setClaudeTaskProcesses(BackgroundProcessManager processes) {
        ClaudeCliClient client;
        synchronized (this) {
            claudeTaskProcesses = processes;
            client = claudeServeClient;
        }
        if (client != null) client.setTaskProcesses(processes);
    }

    /**
     * Told the chat message to send for each turn the Claude Code route starts by
     * itself, such as its reply once a background task finished. Sending that
     * message shows the turn.
     */
    public void setClaudeFollowUpListener(Consumer<String> listener) {
        ClaudeCliClient client;
        synchronized (this) {
            claudeFollowUpListener = listener;
            client = claudeServeClient;
        }
        if (client != null) client.setFollowUpListener(followUpMarkers(listener));
    }

    /**
     * Told, on a thread that holds none of the route's locks, whether Claude Code
     * took in each message {@link #injectIntoClaudeTurn} wrote, or dropped it unread.
     */
    public void setClaudeInjectionListener(ClaudeInjectionListener listener) {
        ClaudeCliClient client;
        synchronized (this) {
            claudeInjectionListener = listener;
            client = claudeServeClient;
        }
        if (client != null) client.setInjectionListener(listener);
    }

    /**
     * Write a message into the turn the Claude Code route is running. Claude Code
     * takes it in after the turn's next tool calls, or as a turn of its own once
     * the turn ends; the injection listener is told which.
     *
     * @return false, writing nothing, when the route runs no turn to take it
     */
    public boolean injectIntoClaudeTurn(String id, String text) {
        ClaudeCliClient client = claudeServeClient;
        return client != null && client.injectIntoRunningTurn(id, text);
    }

    /** Background Claude-owned Agent/Bash tasks without acquiring the running turn's history lock. */
    public java.util.concurrent.CompletableFuture<Void> backgroundClaudeTasks() {
        ClaudeCliClient client = claudeServeClient;
        return client != null ? client.backgroundTasks()
                : java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("No live Claude Code session"));
    }

    /**
     * Push the chat's current model, effort and fast mode onto the live Claude
     * Code route's process, if one is already running, so a turn it starts by
     * itself (such as its reply once a background task finished) already runs
     * at the newly chosen settings instead of whatever was active when its
     * session began. Safe to call from a UI thread: {@link ClaudeCliClient}
     * itself runs the wait for a turn already in progress, and the apply that
     * follows it, on its own worker thread -- this call only records the
     * request and returns.
     */
    public void syncClaudeIdleSettings(String model, String effort, boolean fastMode) {
        // A retained Claude process still owns its tasks after a provider switch.
        // Never apply the newly selected provider's settings to that process.
        if (resolveRoute(model).protocol() != WireProtocol.CLAUDE_CLI) return;
        ClaudeCliClient client;
        synchronized (this) {
            client = claudeServeClient;
        }
        if (client != null) {
            client.applyIdleSettings(model, effort, fastMode);
        }
    }

    /** Confirm a same-route live Claude model switch without blocking the UI. */
    public boolean selectClaudeModel(ChatConfig candidate, Runnable accepted, Consumer<String> rejected) {
        ClaudeCliClient client;
        synchronized (this) {
            client = claudeServeClient;
        }
        return client != null && config.isClaudeCliNative() && candidate.isClaudeCliNative()
                && Objects.equals(config.getProvider(), candidate.getProvider())
                && !Objects.equals(config.getModel(), candidate.getModel())
                && client.selectModel(candidate.getModel(), accepted, rejected);
    }

    private static Consumer<String> followUpMarkers(Consumer<String> listener) {
        return listener == null ? null : id -> listener.accept(ClaudeCliClient.followUpMarker(id));
    }

    /**
     * Run this client's Claude Code sessions as a utility lane such as a judge does:
     * without tools, MCP servers or skills. {@code oneShot}: each session serves one
     * request and is not saved. {@code preferredEffort}: the effort a turn that names none
     * asks for, where Claude Code lists it for the turn's model; blank keeps Claude Code's
     * default. Set before the first turn. One-shot requests ({@link #streamOneShot}) always
     * run without tools, at this client's preferred effort.
     */
    public void runToolFree(boolean oneShot, String preferredEffort) {
        claudeMode = new ClaudeCliClient.Mode(true, oneShot, preferredEffort);
    }

    /**
     * The effort a request that names none asks for on this client's route: the preferred
     * effort of {@link #runToolFree} on the Claude Code route; empty for the route's default.
     */
    public String preferredToolFreeEffort() {
        return resolveRoute(null).protocol() == WireProtocol.CLAUDE_CLI ? claudeMode.preferredEffort() : "";
    }

    /** Testing seam: the Claude Code binary this client and its one-shot requests start. */
    void setClaudeBinaryOverride(String binary) {
        claudeBinaryOverride = binary;
    }

    /**
     * Whether a request starts a provider CLI process before the provider sees it (Claude
     * Code, OpenCode): every one-shot request does, and so does a session's first turn.
     */
    public boolean startsProviderProcess() {
        return resolveRoute(null).protocol().startsProviderProcess();
    }

    /** The Claude Code session of a one-shot request: no tools, not saved, this client's effort. */
    private ClaudeCliClient.Mode oneShotClaudeMode() {
        return new ClaudeCliClient.Mode(true, true, claudeMode.preferredEffort());
    }

    /** Project scope inherited by isolated clients such as judges. */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /** Stable conversation affinity used by provider prompt-cache routing. */
    public void setPromptCacheSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            promptCacheSessionId = null;
            return;
        }
        String normalized = sessionId.strip();
        promptCacheSessionId = normalized.length() <= OPENAI_PROMPT_CACHE_KEY_MAX_CHARS
                ? normalized : normalized.substring(0, OPENAI_PROMPT_CACHE_KEY_MAX_CHARS);
    }

    public String getPromptCacheSessionId() {
        return promptCacheSessionId;
    }

    /**
     * Print a streaming text chunk to the terminal.
     * Subclasses may override to intercept/redirect streaming output.
     */
    protected void printStreamingChunk(String chunk) {
        if (chunk != null) {
            java.util.function.Consumer<String> consumer = this.outputConsumer;
            if (consumer != null) {
                consumer.accept(chunk);
            } else {
                System.out.print(chunk);
                System.out.flush();
            }
        }
    }

    /**
     * Set a consumer for model reasoning/thinking deltas. Thinking streams here
     * instead of the response consumer so transcripts can render it distinctly
     * while it never pollutes the captured answer text.
     */
    public void setThinkingConsumer(java.util.function.Consumer<String> consumer) {
        this.thinkingConsumer = consumer;
    }

    public java.util.function.Consumer<String> getThinkingConsumer() {
        return thinkingConsumer;
    }

    /**
     * Route a thinking delta through the thinking consumer. With no consumer
     * installed the delta is dropped: capture lanes (judges, enforcer, headless,
     * summarization) must not receive reasoning, and raw ANSI must never reach
     * piped stdout.
     */
    protected void printThinkingChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        java.util.function.Consumer<String> consumer = this.thinkingConsumer;
        if (consumer != null) {
            consumer.accept(chunk);
        }
    }

    /**
     * Report a fragment of tool-call arguments as the model streams it, so the
     * chat counts its tokens before the request's usage arrives.
     */
    protected void reportToolInputDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        ProviderActivityListener listener = providerActivityListener;
        if (listener != null) listener.onToolInputDelta(delta);
    }

    /**
     * Stream a chat completion turn.
     * Returns a StreamResult with the text response and any tool call requests.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults) {
        return streamChat(userMessage, systemPrompt, toolDefs, toolResults, null);
    }

    /**
     * Stream a chat completion turn with optional model override and attachments.
     * Images and text attachments are encoded using the selected route's native media format:
     * content blocks for the HTTP APIs, stream-json image blocks for Claude Code, file parts
     * for OpenCode, pi-ai image content, and per-message images for local serving. Whether
     * the model itself accepts images is the provider's answer, reported as a provider error.
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride, List<AttachmentInput> attachments) {
        synchronized (historyLock) {
            List<AttachmentInput> requestAttachments = attachments == null
                    ? List.of() : new ArrayList<>(attachments);
            String attachmentError = validateAttachments(requestAttachments);
            if (attachmentError != null) {
                return attachmentFailure(attachmentError);
            }
            requestAttachments = List.copyOf(requestAttachments);

            String effectiveModel = (modelOverride != null && !modelOverride.isBlank())
                    ? modelOverride : config.getModel();
            String followUp = claudeFollowUpId(userMessage);
            if (followUp != null) {
                // Claude Code already ran this turn by itself, so it is shown and
                // never sent, even after the chat switched to another provider.
                return showClaudeFollowUp(followUp, userMessage, effectiveModel);
            }
            ResolvedRoute route = resolveRoute(effectiveModel);
            int connectivityAttempt = 1;
            boolean authenticationRefreshAttempted = false;
            OAuthProviderFlow.RequestAuth retryAuth = null;
            StreamResult previousAttempts = null;
            while (true) {
                StreamResult result = streamAttempt(
                        route, userMessage, systemPrompt, toolDefs, toolResults,
                        effectiveModel, requestAttachments, retryAuth);
                // A replay-safe failure can still report billed input (for example
                // message_start followed by EOF). Keep that usage, not its context.
                mergePreviousAttemptUsage(result, previousAttempts);
                previousAttempts = result;
                finishGenerationOutcome(result);
                if (result.authenticationFailure) {
                    if (isCancelled() || Thread.currentThread().isInterrupted()) {
                        result.cancelled = true;
                        result.authenticationFailure = false;
                        result.rejectedAuth = null;
                        return result;
                    }
                    OAuthProviderFlow.RequestAuth rejectedAuth = result.rejectedAuth;
                    result.rejectedAuth = null;
                    try {
                        if (!authenticationRefreshAttempted && result.isReplaySafe() && rejectedAuth != null) {
                            authenticationRefreshAttempted = true;
                            retryAuth = config.refreshRequestAuthAfterUnauthorized(rejectedAuth);
                            if (retryAuth != null) continue;
                        }
                    } catch (ChatConfig.AuthenticationException e) {
                        return finishCredentialFailure(result, e);
                    }
                    return finishAuthenticationFailure(result);
                }
                if (!result.retryableConnectivityFailure) {
                    return result;
                }

                boolean replaySafe = result.isReplaySafe();
                if (!replaySafe || connectivityAttempt == connectivityPolicy.maxAttempts()) {
                    return finishConnectivityFailure(result, replaySafe);
                }

                Duration delay = connectivityPolicy.retryDelay(
                        connectivityAttempt, result.connectivityHeaders);
                emitConnectivityEvent(new ConnectivityEvent(
                        config.getProvider(), connectivityAttempt + 1,
                        connectivityPolicy.maxAttempts(),
                        delay, result.connectivityFailure));
                if (!waitForRetry(delay)) {
                    result.cancelled = true;
                    result.retryableConnectivityFailure = false;
                    return result;
                }
                if (route.protocol() == WireProtocol.OPENCODE) {
                    resetOpenCodeClient();
                }
                // A running Claude Code process keeps its session and the tasks it
                // started, so only a process that has exited is replaced.
                ClaudeCliClient claude = claudeServeClient;
                if (route.protocol() == WireProtocol.CLAUDE_CLI
                        && (claude == null || !claude.processAlive())) {
                    resetClaudeClient();
                }
                connectivityAttempt++;
            }
        }
    }

    /**
     * Stream a chat completion turn with optional model override.
     *
     * @param modelOverride if non-null, use this model instead of the configured default
     */
    public StreamResult streamChat(String userMessage, String systemPrompt,
                                    ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                    String modelOverride) {
        return streamChat(
                userMessage, systemPrompt, toolDefs, toolResults, modelOverride, List.of());
    }

    private StreamResult streamAttempt(
            ResolvedRoute route, String userMessage, String systemPrompt,
            ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
            String effectiveModel, List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = switch (route.protocol()) {
            case KOMPILE_LOCAL -> streamKompileServing(
                    userMessage, systemPrompt, toolDefs, toolResults, attachments);
            case OPENCODE -> streamOpenCode(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments);
            case CLAUDE_CLI -> streamClaudeCli(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments);
            case OPENAI_RESPONSES -> streamOpenAiResponses(
                    userMessage, systemPrompt, toolDefs, toolResults,
                    effectiveModel, route.codexBackend(), attachments, retryAuth);
            case PI_MESSAGES -> streamPiMessages(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel,
                    attachments, retryAuth);
            case ANTHROPIC_MESSAGES -> streamAnthropic(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments, retryAuth);
            case OPENAI_CHAT -> streamOpenAi(
                    userMessage, systemPrompt, toolDefs, toolResults, effectiveModel, attachments, retryAuth);
        };
        // Native session transports already emit per-request deltas. HTTP readers
        // reconcile cumulative SSE snapshots into one settled attempt here instead.
        if (route.protocol() != WireProtocol.OPENCODE
                && route.protocol() != WireProtocol.CLAUDE_CLI) {
            ProviderActivityListener listener = providerActivityListener;
            if (listener != null && hasTokenUsage(result)) {
                listener.onTokenUsage(
                        result.inputTokens + result.compactionInputTokens,
                        result.outputTokens + result.compactionOutputTokens,
                        result.cacheReadTokens + result.compactionCacheReadTokens,
                        result.cacheCreationTokens + result.compactionCacheCreationTokens);
            }
        }
        return result;
    }

    private static boolean hasTokenUsage(StreamResult result) {
        return result.inputTokens > 0 || result.outputTokens > 0
                || result.cacheReadTokens > 0 || result.cacheCreationTokens > 0
                || result.compactionInputTokens > 0 || result.compactionOutputTokens > 0
                || result.compactionCacheReadTokens > 0 || result.compactionCacheCreationTokens > 0;
    }

    private static void mergePreviousAttemptUsage(StreamResult result, StreamResult previous) {
        if (previous == null || !hasTokenUsage(previous)) return;
        result.lastRequestInputTokens = result.contextInputTokens();
        result.inputTokens += previous.inputTokens;
        result.outputTokens += previous.outputTokens;
        result.cacheReadTokens += previous.cacheReadTokens;
        result.cacheCreationTokens += previous.cacheCreationTokens;
        result.compactionInputTokens += previous.compactionInputTokens;
        result.compactionOutputTokens += previous.compactionOutputTokens;
        result.compactionCacheReadTokens += previous.compactionCacheReadTokens;
        result.compactionCacheCreationTokens += previous.compactionCacheCreationTokens;
    }

    public ResolvedRoute resolveRoute(String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        if (config.isKompileLocalServing()) {
            return new ResolvedRoute(WireProtocol.KOMPILE_LOCAL, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (config.isOpenCodeNative()) {
            return new ResolvedRoute(WireProtocol.OPENCODE, false,
                    ProviderCompactionCapabilities.forProvider("opencode"));
        }
        if (config.isOpenAiCodexFormat()) {
            return new ResolvedRoute(WireProtocol.OPENAI_RESPONSES, true,
                    ProviderCompactionCapabilities.forProvider("openai-codex"));
        }
        if (config.isPiMessagesFormat()) {
            return new ResolvedRoute(WireProtocol.PI_MESSAGES, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (config.isClaudeCliNative()) {
            // Anthropic vendor on the native/subscription route: turns run through
            // the claude CLI's headless stream-json transport; the CLI owns the
            // subscription (OAuth) credentials Kompile never sees. Claude Code
            // also owns that session's context and compacts it itself.
            return new ResolvedRoute(WireProtocol.CLAUDE_CLI, false,
                    ProviderCompactionCapabilities.claudeCodeSession());
        }
        if (config.isAnthropicFormat()) {
            return new ResolvedRoute(WireProtocol.ANTHROPIC_MESSAGES, false,
                    ProviderCompactionCapabilities.forProvider("anthropic"));
        }
        // GitHub is a protocol-translating proxy. Its model family selects the
        // payload shape, but beta count/compaction endpoints are not assumed.
        if (usesGitHubAnthropicMessages(effectiveModel)) {
            return new ResolvedRoute(WireProtocol.ANTHROPIC_MESSAGES, false,
                    ProviderCompactionCapabilities.generic());
        }
        if (usesOpenAiResponses(effectiveModel) || usesGitHubOpenAiResponses(effectiveModel)) {
            return new ResolvedRoute(WireProtocol.OPENAI_RESPONSES, false,
                    ProviderCompactionCapabilities.generic());
        }
        ProviderCompactionCapabilities capabilities =
                ProviderCompactionCapabilities.forProvider(config.getProvider());
        return new ResolvedRoute(WireProtocol.OPENAI_CHAT, false, capabilities);
    }

    public ProviderCompactionCapabilities compactionCapabilities(String modelOverride) {
        return resolveRoute(modelOverride).capabilities();
    }

    /**
     * Whether this client carries file/image attachments to the selected route. Every wire
     * protocol has a native media format, so the direct client always does; clients that
     * relay turns elsewhere override this when their transport cannot.
     */
    public boolean supportsAttachments(String modelOverride) {
        return true;
    }

    public void setNativeCompactionTriggerTokens(int tokens) {
        nativeCompactionTriggerTokens = Math.max(0, tokens);
    }

    /**
     * Output ceiling carried as {@code max_completion_tokens} on OpenAI chat requests,
     * or {@code max_tokens} on compatible providers that support the legacy field.
     * Derived from the active model's real output limit (see
     * {@code CompactionService#wireMaxOutputTokens}); zero leaves the request without
     * an explicit cap, as before.
     */
    public void setWireMaxOutputTokens(int tokens) {
        wireMaxOutputTokens = Math.max(0, tokens);
    }

    /**
     * Active model's context window; lets the request size the output token limit against
     * the input actually being sent (reasoning tokens bill against that cap on GLM-class
     * models, so a static slice of the window truncates thinking turns). Zero leaves
     * the ceiling unscaled by the window.
     */
    public void setContextWindowTokens(int tokens) {
        contextWindowTokens = Math.max(0, tokens);
    }

    /**
     * Per-request output token limit: the output ceiling while the window has room,
     * shrinking to what fits only as the input approaches the window. The 1% margin
     * (floored at 1,024) absorbs the chars/4 input estimate's error. The output floor
     * never raises a smaller configured output ceiling.
     */
    static int wireMaxTokens(int outputCeiling, int contextWindow, long estimatedInputTokens) {
        if (outputCeiling <= 0) return 0;
        if (contextWindow <= 0) return outputCeiling;
        long byWindow = (long) contextWindow - estimatedInputTokens
                - Math.max(1_024L, contextWindow / 100);
        return (int) Math.max(Math.min(1_024L, outputCeiling), Math.min(outputCeiling, byWindow));
    }

    /**
     * chars/4 estimate over the exact payload riding as input (messages + tool schemas).
     * A vision encoder bills an inline image per image, not per character of its base64
     * text, so each image counts as {@link #IMAGE_TOKEN_ESTIMATE} tokens instead.
     */
    static long estimateRequestInputTokens(ArrayNode messages, ArrayNode toolDefs) {
        long[] images = new long[2];
        countInlineImages(messages, images);
        long chars = messages.toString().length() - images[0];
        if (toolDefs != null) chars += toolDefs.toString().length();
        return Math.max(0L, chars) / CHARS_PER_TOKEN + images[1] * IMAGE_TOKEN_ESTIMATE;
    }

    /**
     * Tallies inline image payloads in any route's message shape: data URLs (OpenAI),
     * {@code base64Data} (local serving), and {@code data} in Anthropic base64 sources
     * and pi-ai image content.
     *
     * @param images accumulates {payload chars, image count}
     */
    private static void countInlineImages(JsonNode node, long[] images) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) countInlineImages(child, images);
            return;
        }
        if (!node.isObject()) return;
        String type = node.path("type").asText("");
        boolean dataIsImage = "base64".equals(type) || "image".equals(type);
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isTextual()) {
                String text = value.textValue();
                boolean payload = "base64Data".equals(field.getKey())
                        || (dataIsImage && "data".equals(field.getKey()))
                        || (text.startsWith("data:image/") && text.contains(";base64,"));
                if (payload) {
                    images[0] += text.length();
                    images[1]++;
                }
            } else {
                countInlineImages(value, images);
            }
        }
    }

    /** Attempt an explicit provider-native compaction without generic fallback. */
    public NativeCompactionResult tryNativeCompact(String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        ResolvedRoute route = resolveRoute(effectiveModel);
        synchronized (historyLock) {
            try {
                return switch (route.capabilities().nativeCompaction()) {
                    case OPENCODE_SESSION -> {
                        OpenCodeServeClient.NativeSummary result =
                                openCodeClient().summarize(effectiveModel);
                        yield new NativeCompactionResult(
                                true, result.applied(), result.summary(),
                                null, result.diagnostic());
                    }
                    case OPENAI_RESPONSES -> compactResponses(effectiveModel, route.codexBackend());
                    // Claude Code has no compaction request Kompile can make on a
                    // headless session; it compacts on its own and reports it.
                    case ANTHROPIC_MESSAGES, CLAUDE_CODE_SESSION, NONE ->
                            NativeCompactionResult.unsupported();
                };
            } catch (Exception e) {
                return new NativeCompactionResult(
                        true, false, null, null, formatExceptionMessage(e));
            }
        }
    }

    private NativeCompactionResult compactResponses(String model, boolean codex) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = config.resolveBaseUrl(auth);
        String url = codex
                ? trimTrailingSlashes(baseUrl) + "/codex/responses/compact"
                : trimTrailingSlashes(baseUrl) + "/responses/compact";
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        conversationHistory.forEach(item -> input.add(item.deepCopy()));
        request.set("input", MemoryContextProjection.project(input));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyBearerAuthentication(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404 || response.statusCode() == 405
                || response.statusCode() == 501) {
            return NativeCompactionResult.unsupported();
        }
        if (response.statusCode() / 100 != 2) {
            return new NativeCompactionResult(
                    true, false, null, null, "HTTP " + response.statusCode());
        }
        JsonNode output = objectMapper.readTree(response.body()).path("output");
        if (!output.isArray() || output.isEmpty()) {
            return new NativeCompactionResult(
                    true, false, null, null, "Responses compact returned no output");
        }
        // Do not mutate retained history yet. The ConversationLedger commits the
        // matching portable/native checkpoint first, then reprojects this payload.
        return new NativeCompactionResult(true, true, null, output.deepCopy(), null);
    }

    /**
     * Estimate the projected OpenAI request locally, without sending it or changing
     * retained history. This includes memory deduplication, tool envelopes, pending
     * attachments and the route's tool schemas. Opaque Responses context cannot be
     * measured from its ciphertext length; leave that to usage or an exact counter.
     */
    public TokenCountResult estimateInputTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String modelOverride,
            List<AttachmentInput> attachments) {
        ResolvedRoute route = resolveRoute(modelOverride);
        if (route.protocol() != WireProtocol.OPENAI_CHAT
                && route.protocol() != WireProtocol.OPENAI_RESPONSES) {
            return TokenCountResult.unsupported();
        }
        List<AttachmentInput> media = attachments == null ? List.of() : attachments;
        synchronized (historyLock) {
            List<ObjectNode> saved = route.protocol() == WireProtocol.OPENAI_RESPONSES
                    ? deepCopyHistory() : null;
            try {
                ArrayNode input;
                ArrayNode tools = null;
                long instructions = 0L;
                if (route.protocol() == WireProtocol.OPENAI_CHAT) {
                    input = buildOpenAiMessages(userMessage, systemPrompt, toolResults, media);
                    if (toolDefs != null) tools = convertToolDefsToOpenAi(toolDefs);
                } else {
                    ResponsesHistoryLinks links = sanitizeResponsesHistory(toolResults);
                    input = buildResponsesInput(userMessage, systemPrompt,
                            prepareResponsesToolResultItems(toolResults, links), route.codexBackend(), media);
                    for (JsonNode item : input) {
                        if (item.hasNonNull("encrypted_content")
                                || "compaction".equals(item.path("type").asText())) {
                            return TokenCountResult.unsupported();
                        }
                    }
                    if (toolDefs != null) tools = convertToolDefsToResponses(toolDefs, route.codexBackend());
                    if (route.codexBackend()) {
                        instructions = (boundedOpenAiInstructions(systemPrompt).length() + 3L) / 4L;
                    }
                }
                return new TokenCountResult(true, false,
                        estimateRequestInputTokens(input, tools) + instructions,
                        "projected-" + route.protocol().name().toLowerCase(Locale.ROOT), null);
            } finally {
                if (saved != null) {
                    conversationHistory.clear();
                    conversationHistory.addAll(saved);
                }
            }
        }
    }

    /** Count the complete pending request when the resolved provider supports it. */
    public TokenCountResult countInputTokens(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String modelOverride) {
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? config.getModel() : modelOverride.strip();
        ResolvedRoute route = resolveRoute(effectiveModel);
        synchronized (historyLock) {
            ProviderCompactionCapabilities.TokenCounting counter =
                    route.capabilities().tokenCounting();
            if (unavailableTokenCounters.contains(counter)) {
                return TokenCountResult.unsupported();
            }
            List<ObjectNode> saved = deepCopyHistory();
            try {
                TokenCountResult result = switch (counter) {
                    case ANTHROPIC_MESSAGES -> countAnthropicTokens(
                            userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
                    case OPENAI_RESPONSES -> countResponsesTokens(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, route.codexBackend());
                    case GEMINI -> countGeminiTokens(
                            userMessage, systemPrompt, toolDefs, toolResults, effectiveModel);
                    case NONE -> TokenCountResult.unsupported();
                };
                if (!result.supported()
                        && ("HTTP 404".equals(result.diagnostic())
                        || "HTTP 405".equals(result.diagnostic())
                        || "HTTP 501".equals(result.diagnostic()))) {
                    unavailableTokenCounters.add(counter);
                }
                return result;
            } catch (Exception e) {
                return new TokenCountResult(false, false, 0L,
                        counter.name(), formatExceptionMessage(e));
            } finally {
                conversationHistory.clear();
                conversationHistory.addAll(saved);
            }
        }
    }

    private TokenCountResult countAnthropicTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = trimTrailingSlashes(config.resolveBaseUrl(auth));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        String effectiveSystem = effectiveAnthropicSystemPrompt(auth, systemPrompt);
        if (!effectiveSystem.isBlank()) request.put("system", effectiveSystem);
        request.set("messages", buildAnthropicMessages(userMessage, toolResults));
        if (toolDefs != null && !toolDefs.isEmpty()) {
            request.set("tools", convertToolDefsToAnthropic(toolDefs));
        }
        boolean nativeCompaction = nativeCompactionTriggerTokens >= 50_000
                && !unavailableNativeCompactionRoutes.contains("anthropic:" + model);
        if (nativeCompaction) {
            request.putObject("context_management").putArray("edits").addObject()
                    .put("type", "compact_20260112")
                    .putObject("trigger")
                    .put("type", "input_tokens")
                    .put("value", nativeCompactionTriggerTokens);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/messages/count_tokens"))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyAnthropicAuthentication(builder, auth, config.getProvider(), false);
        if (nativeCompaction) {
            builder.setHeader("anthropic-beta",
                    mergeHeaderValue(auth, "anthropic-beta", "compact-2026-01-12"));
        }
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("anthropic", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("input_tokens").asLong(0L),
                "anthropic");
    }

    private TokenCountResult countResponsesTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model, boolean codex) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        String baseUrl = config.resolveBaseUrl(auth);
        ResponsesHistoryLinks historyLinks = sanitizeResponsesHistory(toolResults);
        List<ObjectNode> staged = prepareResponsesToolResultItems(toolResults, historyLinks);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.set("input", buildResponsesInput(userMessage, systemPrompt, staged, codex));
        if (codex) {
            request.put("instructions", boundedOpenAiInstructions(systemPrompt));
        }
        if (toolDefs != null && !toolDefs.isEmpty()) {
            request.set("tools", convertToolDefsToResponses(toolDefs, codex));
        }
        String createUrl = resolveResponsesUrl(baseUrl, codex);
        String countUrl = createUrl.substring(0, createUrl.length() - "/responses".length())
                + "/responses/input_tokens";
        if (codex) {
            countUrl = trimTrailingSlashes(baseUrl) + "/codex/responses/input_tokens";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(countUrl))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyBearerAuthentication(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("openai-responses", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("input_tokens").asLong(0L),
                "openai-responses");
    }

    private TokenCountResult countGeminiTokens(
            String userMessage, String systemPrompt, ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults, String model) throws Exception {
        OAuthProviderFlow.RequestAuth auth = config.resolveRequestAuth();
        URI configured = URI.create(config.resolveBaseUrl(auth));
        String origin = configured.getScheme() + "://" + configured.getAuthority();
        String encodedModel = java.net.URLEncoder.encode(model, StandardCharsets.UTF_8);
        String url = origin + "/v1beta/models/" + encodedModel + ":countTokens";

        ArrayNode openAiMessages = buildOpenAiMessages(userMessage, systemPrompt, toolResults);
        ObjectNode request = objectMapper.createObjectNode();
        ArrayNode contents = request.putArray("contents");
        // Only skip the system message injected by buildOpenAiMessages; it is
        // represented below. This adapter is text-only, so never label a count
        // that discarded tool envelopes or multimodal parts as exact.
        int firstMessage = systemPrompt != null && !systemPrompt.isEmpty() ? 1 : 0;
        for (int i = firstMessage; i < openAiMessages.size(); i++) {
            JsonNode message = openAiMessages.get(i);
            String role = message.path("role").asText();
            JsonNode text = message.path("content");
            if (!("user".equals(role) || "assistant".equals(role))
                    || !text.isTextual() || message.hasNonNull("tool_calls")
                    || message.hasNonNull("function_call")) {
                return new TokenCountResult(false, false, 0L, "gemini",
                        "Structured history requires a lossless Gemini count adapter");
            }
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(role) ? "model" : "user");
            content.putArray("parts").addObject().put("text", text.asText());
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            request.putObject("systemInstruction").putArray("parts")
                    .addObject().put("text", systemPrompt);
        }
        if (toolDefs != null && !toolDefs.isEmpty()) {
            ObjectNode tools = request.putArray("tools").addObject();
            ArrayNode declarations = tools.putArray("functionDeclarations");
            for (JsonNode tool : toolDefs) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.path("name").asText());
                declaration.put("description", tool.path("description").asText(""));
                JsonNode parameters = tool.path("inputSchema");
                if (parameters.isMissingNode()) parameters = tool.path("parameters");
                if (!parameters.isMissingNode()) declaration.set("parameters", parameters.deepCopy());
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(request), StandardCharsets.UTF_8));
        applyTokenAuthentication(builder, auth, "x-goog-api-key");
        applyHeaders(builder, auth);
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            return TokenCountResult.unavailable("gemini", response.statusCode());
        }
        return TokenCountResult.exact(
                objectMapper.readTree(response.body()).path("totalTokens").asLong(0L),
                "gemini");
    }

    private List<ObjectNode> deepCopyHistory() {
        return conversationHistory.stream().map(ObjectNode::deepCopy).toList();
    }

    public enum WireProtocol {
        KOMPILE_LOCAL(false),
        OPENCODE(true),
        CLAUDE_CLI(true),
        OPENAI_RESPONSES(false),
        PI_MESSAGES(false),
        ANTHROPIC_MESSAGES(false),
        OPENAI_CHAT(false);

        private final boolean providerProcess;

        WireProtocol(boolean providerProcess) {
            this.providerProcess = providerProcess;
        }

        /** Turns run in a provider CLI process Kompile starts, which runs its own agent loop. */
        public boolean startsProviderProcess() {
            return providerProcess;
        }
    }

    public record ResolvedRoute(
            WireProtocol protocol,
            boolean codexBackend,
            ProviderCompactionCapabilities capabilities) {
    }

    public record NativeCompactionResult(
            boolean supported,
            boolean applied,
            String portableSummary,
            JsonNode nativePayload,
            String diagnostic) {

        public static NativeCompactionResult unsupported() {
            return new NativeCompactionResult(false, false, null, null, null);
        }
    }

    public record TokenCountResult(
            boolean supported,
            boolean exact,
            long inputTokens,
            String source,
            String diagnostic) {

        public static TokenCountResult unsupported() {
            return new TokenCountResult(false, false, 0L, "none", null);
        }

        static TokenCountResult unavailable(String source, int status) {
            return new TokenCountResult(false, false, 0L, source,
                    "HTTP " + status);
        }

        static TokenCountResult exact(long inputTokens, String source) {
            return new TokenCountResult(true, true, Math.max(0L, inputTokens), source, null);
        }
    }

    private StreamResult streamOpenCode(String userMessage, String systemPrompt,
                                         ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                         String effectiveModel, List<AttachmentInput> attachments) {
        StreamResult result = new StreamResult();
        StringBuilder streamed = new StringBuilder();
        try {
            OpenCodeServeClient client = openCodeClient();
            // A new native session gets the earlier conversation ahead of the
            // message, labeled as past turns rather than instructions.
            String turnMessage = userMessage;
            String restored = openCodeNeedsSeed ? restoredConversation(systemPrompt, userMessage) : "";
            if (!restored.isEmpty()) {
                turnMessage = restored + "\n\n" + (userMessage == null ? "" : userMessage);
            }
            // OpenCode owns a durable native session. A turn that actually ran may
            // have mutated that provider-side history even when it failed, so such
            // results must not be replayed as if this were a stateless HTTP request.
            // Failures before the turn begins (server boot, session creation, turn
            // spawn) surface as TurnNotStartedException instead: the provider never
            // saw the prompt, so the connectivity retry loop may replay it against
            // the fresh transport installed by resetOpenCodeClient().
            // Cancelling the chat turn aborts the OpenCode turn and the tools it started.
            client.setCancellationCheck(this::isCancelled);
            String text = client.send(effectiveModel, config.getThinking(),
                    systemPrompt, turnMessage, attachments,
                    chunk -> {
                        streamed.append(chunk);
                        printStreamingChunk(chunk);
                    }, new OpenCodeServeClient.ActivityListener() {
                        @Override
                        public void onToolStart(String callId, String name, String input) {
                            result.providerSideEffectsObserved = true;
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onToolStart(callId, name, input);
                            }
                        }

                        @Override
                        public void onToolComplete(String callId, String name, String output,
                                                   int exitCode, boolean error) {
                            result.providerSideEffectsObserved = true;
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onToolComplete(
                                        callId, name, output, exitCode, error);
                            }
                        }

                        @Override
                        public void onTokenUsage(long input, long output,
                                                 long cacheRead, long cacheCreation) {
                            result.inputTokens += Math.max(0, input);
                            result.outputTokens += Math.max(0, output);
                            result.cacheReadTokens += Math.max(0, cacheRead);
                            result.cacheCreationTokens += Math.max(0, cacheCreation);
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) {
                                listener.onTokenUsage(
                                        input, output, cacheRead, cacheCreation);
                            }
                        }

                        @Override
                        public void onSteps(int steps) {
                            ProviderActivityListener listener = providerActivityListener;
                            if (listener != null) listener.onSteps(steps);
                        }
                    });
            result.text = text;
            if (streamed.length() == 0) {
                printStreamingChunk(text);
            }
            appendOpenCodeHistory(userMessage, attachments, text);
            openCodeNeedsSeed = false;
        } catch (OpenCodeServeClient.TurnNotStartedException e) {
            // The turn never reached the provider: no native session history was
            // touched, so replay-safe stays true and the retry loop can reconnect.
            recordStreamFailure(result, e, "[Error: ");
        } catch (Exception e) {
            // The turn ran, so the native session may hold partial provider-side
            // state; keep the result non-replayable.
            result.providerSideEffectsObserved = true;
            if (streamed.length() > 0) result.text = streamed.toString();
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    private OpenCodeServeClient openCodeClient() {
        OpenCodeServeClient client = openCodeServeClient;
        if (client == null) {
            synchronized (this) {
                client = openCodeServeClient;
                if (client == null) {
                    client = new OpenCodeServeClient(objectMapper, workingDirectory);
                    openCodeServeClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Stream one turn through the native Claude Code CLI (anthropic vendor on the
     * native/subscription route). Mirrors {@link #streamOpenCode}: the provider
     * owns a durable native session, so a turn that actually ran may have mutated
     * provider-side history even when it failed and must not be replayed as if
     * this were a stateless HTTP request. Failures before the turn begins
     * (binary missing, spawn failure, credential rejection) surface as
     * {@link ClaudeCliClient.TurnNotStartedException} instead: the provider never
     * answered, so the connectivity retry loop may replay it: to the same Claude
     * Code process while it runs, otherwise to the fresh transport installed by
     * {@link #resetClaudeClient()}.
     */
    private StreamResult streamClaudeCli(String userMessage, String systemPrompt,
                                         ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                         String effectiveModel, List<AttachmentInput> attachments) {
        StreamResult result = new StreamResult();
        // A turn's usage adds up all of its requests; only the last one's input
        // describes the session, and it stays 0 when Claude Code reports none.
        result.lastRequestInputTokens = 0;
        StringBuilder streamed = new StringBuilder();
        ClaudeCliClient client = null;
        try {
            client = claudeClient();
            boolean resumed = client.nativeSession() != null;
            String text;
            try {
                text = sendClaudeTurn(client, effectiveModel, systemPrompt, userMessage,
                        attachments, result, streamed);
            } catch (ClaudeCliClient.TurnNotStartedException e) {
                // A session Claude Code no longer has (or never finished creating)
                // fails before the turn begins. Nothing reached the provider, so
                // continue in a new session that carries the earlier conversation.
                // Claude Code exits when it cannot resume a session; a process
                // still running holds the session, and its tasks, so it is kept.
                if (!resumed || e instanceof ClaudeCliClient.ClaudeCliAuthenticationException
                        || isCancelled() || Thread.currentThread().isInterrupted()
                        || client.processAlive()) {
                    throw e;
                }
                client.startNewSession();
                claudeNeedsSeed = true;
                claudeCompactionFailed = false;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) {
                    listener.onNotice("The Claude Code session could not be resumed ("
                            + e.getMessage() + "); starting a new one with the earlier "
                            + "conversation restored.");
                }
                text = sendClaudeTurn(client, effectiveModel, systemPrompt, userMessage,
                        attachments, result, streamed);
            }
            result.text = text;
            if (streamed.length() == 0) {
                printStreamingChunk(text);
            }
            appendOpenCodeHistory(userMessage, attachments, text);
            claudeNeedsSeed = false;
            result.claudeNativeSession = client.nativeSession();
        } catch (ClaudeCliClient.TurnNotStartedException e) {
            // The turn never reached a provider (or credentials were rejected):
            // replay-safe stays true and the retry loop can reconnect. An auth
            // rejection warns the user with the actionable fix.
            recordStreamFailure(result, e, "[Error: ");
        } catch (Exception e) {
            // A cancelled turn Claude Code took in left its message, with any earlier
            // conversation restored in it, in the session the next turn continues.
            if (isCancelled() && client != null && client.nativeSession() != null) {
                claudeNeedsSeed = false;
            }
            boolean overflow = isContextOverflowFailure(0, e.getMessage());
            // Claude Code could not fit its session in the context window.
            if (overflow) claudeCompactionFailed = true;
            // Rejected before any tool ran or any answer streamed, the turn changed
            // nothing Kompile relies on: a retry runs in a new session restored from
            // Kompile's history, so the result stays replayable.
            boolean overflowBeforeOutput = overflow
                    && e instanceof ClaudeCliClient.TurnFailedException
                    && !result.providerSideEffectsObserved && streamed.length() == 0;
            if (!overflowBeforeOutput) {
                // The turn ran, so the native session may hold partial provider-side
                // state; keep the result non-replayable.
                result.providerSideEffectsObserved = true;
                if (streamed.length() > 0) result.text = streamed.toString();
            }
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    /**
     * Show a turn Claude Code started by itself, which the chat asked for with its
     * follow-up marker. The turn already ran, so a failure is never replayed. A turn
     * that a message's reply already showed, or one that is unknown, shows nothing.
     */
    private StreamResult showClaudeFollowUp(String followUpId, String userMessage,
                                            String effectiveModel) {
        StreamResult result = new StreamResult();
        result.lastRequestInputTokens = 0;
        result.providerSideEffectsObserved = true;
        // Only the Claude Code process that started the turn holds it.
        ClaudeCliClient client = claudeServeClient;
        if (client == null) return result;
        StringBuilder streamed = new StringBuilder();
        try {
            // Cancelling the chat turn interrupts the turn being shown.
            client.setCancellationCheck(this::isCancelled);
            String text = client.adoptFollowUp(followUpId, claudeOutput(streamed),
                    claudeActivity(result, effectiveModel));
            result.text = text;
            if (!text.isEmpty()) appendOpenCodeHistory(userMessage, text);
            // A parked session has not received the other provider's turns yet.
            // Its late response must not checkpoint those turns as native Claude history.
            if (!claudeNeedsSeed) result.claudeNativeSession = client.nativeSession();
        } catch (Exception e) {
            if (isContextOverflowFailure(0, e.getMessage())) claudeCompactionFailed = true;
            if (streamed.length() > 0) result.text = streamed.toString();
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    /**
     * One Claude Code CLI turn with its activity forwarded to the installed
     * listeners. A new native session also receives the earlier conversation.
     */
    private String sendClaudeTurn(ClaudeCliClient client, String effectiveModel,
                                  String systemPrompt, String userMessage,
                                  List<AttachmentInput> attachments,
                                  StreamResult result, StringBuilder streamed) throws Exception {
        String restored = claudeNeedsSeed ? restoredConversation(systemPrompt, userMessage) : "";
        // Cancelling the chat turn stops the CLI and the tools it started.
        client.setCancellationCheck(this::isCancelled);
        // Ultracode replaces the effort level on this route; fast mode is
        // rechecked against the effective request model like the HTTP routes.
        return client.send(effectiveModel, config.effectiveEffort(),
                config.useFastMode(effectiveModel),
                systemPrompt, userMessage, restored, attachments,
                claudeOutput(streamed), claudeActivity(result, effectiveModel));
    }

    /** Streams a Claude Code turn's text to the chat, keeping a copy. */
    private Consumer<String> claudeOutput(StringBuilder streamed) {
        return chunk -> {
            streamed.append(chunk);
            printStreamingChunk(chunk);
        };
    }

    /** Forwards a Claude Code turn's activity to the installed listeners and records it in the result. */
    private ClaudeCliClient.ActivityListener claudeActivity(StreamResult result, String effectiveModel) {
        return new ClaudeCliClient.ActivityListener() {
            @Override
            public void onActivity(String label) {
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onActivity(label);
            }

            @Override
            public void onToolProgress(String callId, String name, long elapsedMillis) {
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onToolProgress(callId, name, elapsedMillis);
            }

            @Override
            public void onToolStart(String callId, String name, String input) {
                result.providerSideEffectsObserved = true;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) {
                    listener.onToolStart(callId, name, input);
                }
            }

            @Override
            public void onToolInput(String callId, String name, String input) {
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onToolInput(callId, name, input);
            }

            @Override
            public void onToolOutput(String callId, String name, String output) {
                result.providerSideEffectsObserved = true;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onToolOutput(callId, name, output);
            }

            @Override
            public void onToolInputDelta(String delta) {
                reportToolInputDelta(delta);
            }

            @Override
            public void onNotice(String notice) {
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onNotice(notice);
            }

            @Override
            public void onCompacted(String trigger, long tokensBefore) {
                claudeCompactionFailed = false;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onCompacted(trigger, tokensBefore);
            }

            @Override
            public void onCompactionFailed(String detail) {
                // Cancelling the turn also stops a compaction in progress.
                if (!isCancelled()) claudeCompactionFailed = true;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onCompactionFailed(detail);
            }

            @Override
            public void onRetry(int attempt, int maxAttempts, long delayMs,
                                String reason) {
                // Claude Code retries on its own; surface it where every
                // other route shows its retries.
                emitConnectivityEvent(new ConnectivityEvent(
                        config.getProvider(), attempt, maxAttempts,
                        Duration.ofMillis(delayMs), reason));
            }

            @Override
            public void onToolComplete(String callId, String name, String output,
                                       int exitCode, boolean error) {
                result.providerSideEffectsObserved = true;
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) {
                    listener.onToolComplete(
                            callId, name, output, exitCode, error);
                }
            }

            @Override
            public void onTokenUsage(long input, long output,
                                     long cacheRead, long cacheCreation) {
                result.inputTokens += Math.max(0, input);
                result.outputTokens += Math.max(0, output);
                result.cacheReadTokens += Math.max(0, cacheRead);
                result.cacheCreationTokens += Math.max(0, cacheCreation);
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) {
                    listener.onTokenUsage(
                            input, output, cacheRead, cacheCreation);
                }
            }

            @Override
            public void onContextUsage(long contextTokens) {
                result.lastRequestInputTokens = Math.max(0L, contextTokens);
            }

            @Override
            public void onModelLimits(int contextWindow, int maxOutputTokens) {
                claudeModelLimits = new ClaudeModelLimits(effectiveModel,
                        new ModelContextResolver.ModelLimits(contextWindow, maxOutputTokens));
            }

            @Override
            public void onSteps(int steps) {
                ProviderActivityListener listener = providerActivityListener;
                if (listener != null) listener.onSteps(steps);
            }

            @Override
            public void onThinking(String reasoningDelta) {
                // Same pipeline every other lane feeds: renders in the
                // live thinking renderer when installed, dropped
                // otherwise (capture lanes must not receive reasoning).
                printThinkingChunk(reasoningDelta);
            }
        };
    }

    /**
     * Continue a Claude Code native session saved by an earlier Kompile process.
     * It already holds this conversation, so the next turn restores none.
     */
    public void resumeClaudeNativeSession(String sessionId, String instructionsDigest) {
        synchronized (historyLock) {
            claudeClient().resumeSession(sessionId, instructionsDigest);
            claudeNeedsSeed = false;
            claudeCompactionFailed = false;
        }
    }

    /**
     * True after Claude Code reported that it could not compact its session or
     * that a request no longer fit the context window, until that session
     * compacts or is replaced.
     */
    public boolean claudeCompactionFailed() {
        return claudeCompactionFailed;
    }

    /**
     * The limits Claude Code reported enforcing on its last turn with this model,
     * or null before it reported any or off its route. Claude Code applies its own
     * settings, such as a disabled 1M window or a context cap, which the model
     * catalogs cannot see.
     */
    public ModelContextResolver.ModelLimits claudeReportedLimits(String modelOverride) {
        ClaudeModelLimits reported = claudeModelLimits;
        if (reported == null || resolveRoute(modelOverride).protocol() != WireProtocol.CLAUDE_CLI) {
            return null;
        }
        String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride : config.getModel();
        return Objects.equals(reported.model(), model) ? reported.limits() : null;
    }

    /**
     * True when the Claude Code session already holds the conversation: it completed
     * a turn or was resumed. A new session, or one whose first turn failed, receives
     * the conversation restored from Kompile's history on its next turn.
     */
    public boolean claudeSessionHoldsConversation() {
        return !claudeNeedsSeed;
    }

    /** The live Claude Code native session, or null when none has started. */
    public ClaudeNativeSession claudeNativeSession() {
        ClaudeCliClient client = claudeServeClient;
        return client == null ? null : client.nativeSession();
    }

    /**
     * The MCP servers of the Claude Code process serving this session, or null
     * when no process serves it yet.
     */
    public List<ClaudeMcpServer> claudeMcpServers(Duration timeout) throws IOException {
        ClaudeCliClient client = claudeServeClient;
        return client == null ? null : client.mcpServers(timeout);
    }

    /**
     * Restart one MCP server of the Claude Code process serving this session.
     * False when no process serves it yet.
     */
    public boolean reconnectClaudeMcpServer(String name, Duration timeout) throws IOException {
        ClaudeCliClient client = claudeServeClient;
        return client != null && client.reconnectMcpServer(name, timeout);
    }

    private ClaudeCliClient claudeClient() {
        ClaudeCliClient client = claudeServeClient;
        if (client == null) {
            synchronized (this) {
                client = claudeServeClient;
                if (client == null) {
                    client = new ClaudeCliClient(workingDirectory, null, claudeBinaryOverride, claudeMode);
                    client.setTaskProcesses(claudeTaskProcesses);
                    client.setFollowUpListener(followUpMarkers(claudeFollowUpListener));
                    client.setInjectionListener(claudeInjectionListener);
                    claudeServeClient = client;
                }
            }
        }
        return client;
    }

    private void appendOpenCodeHistory(String userMessage, String assistantText) {
        appendOpenCodeHistory(userMessage, List.of(), assistantText);
    }

    /**
     * Records a provider-session turn as text. The provider's session keeps the
     * attachment bytes it received; this history keeps a marker for each.
     */
    private void appendOpenCodeHistory(String userMessage, List<AttachmentInput> attachments,
                                       String assistantText) {
        if (userMessage != null || (attachments != null && !attachments.isEmpty())) {
            conversationHistory.add(textRouteUserMessage(userMessage, attachments));
        }
        if (assistantText != null && !assistantText.isBlank()) {
            ObjectNode assistant = objectMapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.put("content", assistantText);
            conversationHistory.add(assistant);
        }
    }

    /** A user turn as a route with text history keeps it: a marker per attachment, then the text. */
    private ObjectNode textRouteUserMessage(String userMessage, List<AttachmentInput> attachments) {
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.put("content", attachmentMarkers(attachments)
                + (userMessage == null ? "" : withoutTurnEnvelopes(userMessage)));
        return user;
    }

    @Override
    public void close() {
        resetOpenCodeClient();
        resetClaudeClient();
    }

    /**
     * Clear conversation history (for new sessions).
     */
    public void clearHistory() {
        synchronized (historyLock) {
            conversationHistory.clear();
            resetOpenCodeClient();
            resetClaudeClient();
        }
    }

    /**
     * Reproject wire history without ending Claude Code's session or its task
     * controls. Provider switching is not chat disposal: the retained process
     * keeps its input, task bridge and follow-up listener until close/new session.
     * If Claude is selected again, its next message receives the rebuilt
     * conversation so it also sees the work done through other providers.
     */
    public void clearHistoryForProviderSwitch() {
        synchronized (historyLock) {
            conversationHistory.clear();
            resetOpenCodeClient();
            claudeNeedsSeed = true;
        }
    }

    /**
     * Add a message to history (for replay/resume support).
     */
    public void addToHistory(String role, String content) {
        synchronized (historyLock) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", content);
            conversationHistory.add(msg);
        }
    }

    /**
     * Replay a user turn with its attachments, in the shape the route's history keeps
     * after a live turn, so a resumed or re-projected conversation resends what the turn
     * attached. Routes whose history is text keep a marker per attachment, as live.
     */
    public void addReplayedUserTurn(String content, List<AttachmentInput> attachments,
                                    String modelOverride) {
        if (attachments == null || attachments.isEmpty()) {
            addToHistory("user", content);
            return;
        }
        WireProtocol protocol;
        try {
            protocol = resolveRoute(modelOverride).protocol();
        } catch (Exception ignored) {
            protocol = null;
        }
        synchronized (historyLock) {
            conversationHistory.add(userHistoryMessage(protocol, content, attachments));
        }
    }

    private ObjectNode userHistoryMessage(WireProtocol protocol, String text,
                                          List<AttachmentInput> attachments) {
        if (protocol == null) return textRouteUserMessage(text, attachments);
        return switch (protocol) {
            case KOMPILE_LOCAL -> kompileServingUserMessage(text, attachments);
            case OPENAI_CHAT -> openAiUserMessage(text, attachments);
            case ANTHROPIC_MESSAGES -> anthropicUserMessage(text, attachments);
            case OPENAI_RESPONSES -> createResponsesUserMessage(text, attachments);
            case PI_MESSAGES -> createPiUserMessage(text, attachments);
            case OPENCODE, CLAUDE_CLI -> textRouteUserMessage(text, attachments);
        };
    }

    /**
     * Replay one executed tool call into wire history as a protocol-correct
     * assistant envelope instead of prose. Models imitate the message shapes
     * they see: replaying tool calls as plain "[Tool call ...]" text teaches
     * them to emit tool calls as text, which the loop cannot execute.
     * Formats that cannot carry tool-call envelopes (the flattened serving and
     * legacy routes) deliberately fall back to the portable text form.
     */
    public void addReplayedToolCall(String toolName, String callId, String argumentsJson) {
        addReplayedToolCalls(
                List.of(new ReplayedToolCallInput(toolName, callId, argumentsJson)), null);
    }

    /** Replay one provider assistant turn containing one or more parallel tool calls. */
    public void addReplayedToolCalls(
            List<ReplayedToolCallInput> calls, String modelOverride) {
        if (calls == null || calls.isEmpty()) return;
        synchronized (historyLock) {
            switch (routeHistoryFormat(modelOverride)) {
                case OPENAI_CHAT_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "assistant");
                    msg.put("content", "");
                    ArrayNode toolCalls = msg.putArray("tool_calls");
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode call = toolCalls.addObject();
                        call.put("id", replayed.callId());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", replayed.toolName());
                        fn.put("arguments", replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson());
                    }
                    conversationHistory.add(msg);
                }
                case RESPONSES_ENVELOPE -> {
                    // Responses-native item; the sanitizer retains it, and staged
                    // live outputs dedupe against history outputs by call id.
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("type", "function_call");
                        item.put("id", "fc_" + UUID.randomUUID().toString().replace("-", ""));
                        item.put("call_id", replayed.callId());
                        item.put("name", replayed.toolName());
                        item.put("arguments", replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson());
                        item.put("status", "completed");
                        conversationHistory.add(item);
                    }
                }
                case ANTHROPIC_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "assistant");
                    ArrayNode content = msg.putArray("content");
                    for (ReplayedToolCallInput replayed : calls) {
                        ObjectNode block = content.addObject();
                        block.put("type", "tool_use");
                        block.put("id", replayed.callId());
                        block.put("name", replayed.toolName());
                        block.set("input", parseArgumentsOrEmpty(replayed.argumentsJson()));
                    }
                    conversationHistory.add(msg);
                }
                case TEXT -> {
                    for (ReplayedToolCallInput replayed : calls) {
                        addToHistory("assistant", "[Tool call " + replayed.toolName() + " "
                                + replayed.callId() + "]\n"
                                + (replayed.argumentsJson() == null
                                ? "{}" : replayed.argumentsJson()));
                    }
                }
            }
        }
    }

    /**
     * Replay one tool result into wire history, pairing it with its call
     * envelope when the active route supports them.
     */
    public void addReplayedToolResult(String toolName, String callId, String output) {
        addReplayedToolResults(
                List.of(new ToolCallResultInput(callId, toolName, output, false)), null);
    }

    /** Replay one provider result turn for one or more parallel tool calls. */
    public void addReplayedToolResults(
            List<ToolCallResultInput> results, String modelOverride) {
        if (results == null || results.isEmpty()) return;
        synchronized (historyLock) {
            switch (routeHistoryFormat(modelOverride)) {
                case OPENAI_CHAT_ENVELOPE -> {
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("role", "tool");
                        msg.put("tool_call_id", replayed.callId);
                        msg.put("content", replayed.output == null ? "" : replayed.output);
                        if (replayed.name != null) msg.put("name", replayed.name);
                        conversationHistory.add(msg);
                    }
                }
                case RESPONSES_ENVELOPE -> {
                    // Pair with the replayed function_call item so the sanitizer
                    // retains both; a live re-submission of the same output is
                    // deduped by prepareResponsesToolResultItems.
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("type", "function_call_output");
                        item.put("call_id", replayed.callId);
                        item.put("output", replayed.output == null ? "" : replayed.output);
                        conversationHistory.add(item);
                    }
                }
                case ANTHROPIC_ENVELOPE -> {
                    ObjectNode msg = objectMapper.createObjectNode();
                    msg.put("role", "user");
                    ArrayNode content = msg.putArray("content");
                    for (ToolCallResultInput replayed : results) {
                        ObjectNode block = content.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", replayed.callId);
                        block.put("content", replayed.output == null ? "" : replayed.output);
                        if (replayed.isError) block.put("is_error", true);
                    }
                    conversationHistory.add(msg);
                }
                case TEXT -> {
                    for (ToolCallResultInput replayed : results) {
                        addToHistory("user", "[Tool result " + replayed.name + " "
                                + replayed.callId + "]\n"
                                + (replayed.output == null ? "" : replayed.output));
                    }
                }
            }
        }
    }

    enum RouteHistoryFormat { OPENAI_CHAT_ENVELOPE, RESPONSES_ENVELOPE, ANTHROPIC_ENVELOPE, TEXT }

    private RouteHistoryFormat routeHistoryFormat(String modelOverride) {
        try {
            return switch (resolveRoute(modelOverride).protocol()) {
                case OPENAI_CHAT -> RouteHistoryFormat.OPENAI_CHAT_ENVELOPE;
                case OPENAI_RESPONSES -> RouteHistoryFormat.RESPONSES_ENVELOPE;
                case ANTHROPIC_MESSAGES -> RouteHistoryFormat.ANTHROPIC_ENVELOPE;
                default -> RouteHistoryFormat.TEXT;
            };
        } catch (Exception ignored) {
            return RouteHistoryFormat.TEXT;
        }
    }

    private JsonNode parseArgumentsOrEmpty(String argumentsJson) {
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                return objectMapper.readTree(argumentsJson);
            } catch (Exception ignored) {
                // Fall through to the empty object below.
            }
        }
        return objectMapper.createObjectNode();
    }

    /**
     * One-shot streaming completion that does NOT mutate conversation history.
     * Used for utility calls like summarization where we want the model's
     * output but must not pollute the ongoing chat with the request/response.
     * <p>
     * Internally: snapshot history, clear, call streamChat (which would add
     * the request turn), then restore the snapshot — yielding a clean call.
     */
    public StreamResult streamOneShot(String prompt, String systemPrompt, String modelOverride) {
        if (getClass() == DirectLlmClient.class) {
            // Utility calls must not clear or lock the live chat history while a
            // judge/summary network request is in flight. A fresh client also gives
            // provider-owned OpenCode a genuinely isolated native session.
            try (DirectLlmClient isolated = new DirectLlmClient(
                    config, objectMapper, connectivityPolicy, workingDirectory)) {
                isolated.setCancelSignal(cancelSignal);
                // A one-shot request answers from its prompt: no tools on any route.
                isolated.claudeMode = oneShotClaudeMode();
                isolated.claudeBinaryOverride = claudeBinaryOverride;
                String cacheSessionId = activePromptCacheSessionId();
                if (cacheSessionId != null) {
                    // Keep utility calls on a distinct affinity shard even when the
                    // original key already occupies the provider's 64-char limit.
                    isolated.setPromptCacheSessionId("utility:" + cacheSessionId);
                }
                if (cancellationCheck != null) {
                    isolated.setCancellationCheck(cancellationCheck);
                }
                isolated.setOutputConsumer(outputConsumer);
                isolated.setProviderActivityListener(providerActivityListener);
                isolated.setConnectivityEventConsumer(connectivityEventConsumer);
                return isolated.streamChat(prompt, systemPrompt, null, null, modelOverride);
            }
        }
        // Preserve the interception seam used by specialized/subprocess clients
        // and test doubles. Their override executes under a re-entrant lock.
        synchronized (historyLock) {
            List<ObjectNode> saved = new ArrayList<>(conversationHistory);
            conversationHistory.clear();
            try {
                return streamChat(prompt, systemPrompt, null, null, modelOverride);
            } finally {
                conversationHistory.clear();
                conversationHistory.addAll(saved);
            }
        }
    }

    /**
     * One-shot completion with provider-enforced JSON Schema on known OpenAI protocol paths.
     * Unsupported providers fall back to the ordinary one-shot request; caller-side validation
     * and bounded repair remain authoritative.
     */
    public StreamResult streamOneShotJson(
            String prompt, String systemPrompt, String modelOverride,
            String schemaName, JsonNode schema, boolean strict) {
        return streamOneShotJson(prompt, systemPrompt, modelOverride,
                schemaName, schema, strict, List.of());
    }

    /**
     * One-shot structured-output completion with optional image attachments.
     * Intended for object-mode providers (schema rides prompt-level; multimodal
     * requests omit response_format). Schema-transport providers (native
     * json_schema) accept only text for structured output; attachments there are
     * rejected by the caller-side gate in {@code NativeChatModels.call}.
     */
    public StreamResult streamOneShotJson(
            String prompt, String systemPrompt, String modelOverride,
            String schemaName, JsonNode schema, boolean strict,
            List<AttachmentInput> attachments) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("Structured output schema must be a JSON object");
        }
        if (getClass() != DirectLlmClient.class) {
            return streamOneShot(prompt, systemPrompt, modelOverride);
        }
        try (DirectLlmClient isolated = new DirectLlmClient(
                config, objectMapper, connectivityPolicy, workingDirectory)) {
            isolated.setCancelSignal(cancelSignal);
            isolated.claudeMode = oneShotClaudeMode();
            isolated.claudeBinaryOverride = claudeBinaryOverride;
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) {
                isolated.setPromptCacheSessionId("utility:" + cacheSessionId);
            }
            if (cancellationCheck != null) {
                isolated.setCancellationCheck(cancellationCheck);
            }
            isolated.setOutputConsumer(outputConsumer);
            isolated.setProviderActivityListener(providerActivityListener);
            isolated.setConnectivityEventConsumer(connectivityEventConsumer);
            isolated.requestedJsonOutput = new JsonOutputSpec(
                    normalizeJsonSchemaName(schemaName), schema.deepCopy(), strict);
            return isolated.streamChat(prompt, systemPrompt, null, null, modelOverride,
                    attachments == null ? List.of() : List.copyOf(attachments));
        }
    }

    /**
     * Replace the entire conversation history with a summary of prior turns.
     * The summary is injected as a user/assistant exchange so the next real
     * user turn continues normally. Used by the /compact command.
     */
    public void replaceHistoryWithSummary(String summary) {
        if (summary == null || summary.isBlank()) return;
        synchronized (historyLock) {
            conversationHistory.clear();
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content",
                    "This session was compacted. Below is a structured summary of our "
                            + "prior conversation. Treat it as authoritative context for "
                            + "continuing the work:\n\n" + summary);
            conversationHistory.add(userMsg);

            ObjectNode assistantMsg = objectMapper.createObjectNode();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content",
                    "Understood. I have the compacted summary and will continue from here.");
            conversationHistory.add(assistantMsg);
        }
    }

    public void replaceHistoryWithNativeCheckpoint(JsonNode nativePayload) {
        if (nativePayload == null || nativePayload.isNull()) return;
        synchronized (historyLock) {
            conversationHistory.clear();
            if (nativePayload.isArray()) {
                for (JsonNode item : nativePayload) {
                    if (item.isObject()) conversationHistory.add(((ObjectNode) item).deepCopy());
                }
            } else if (nativePayload.isObject()) {
                conversationHistory.add(((ObjectNode) nativePayload).deepCopy());
            }
        }
    }

    /**
     * A replacement native session must not start at the auto-compaction threshold.
     * Leave at least half the window for the native prompt, tools and ongoing work.
     * Until that process reports its actual usage, UTF-8 bytes are a conservative
     * text-token bound; chars/4 badly underestimates code and non-ASCII history.
     * Cap history independently of catalog windows (which may advertise 1M while
     * native settings enforce 200K). Keep whole newest messages, not raw tool output.
     */
    private String restoredConversation(String systemPrompt, String userMessage) {
        int window = contextWindowTokens > 0
                ? contextWindowTokens : ModelContextWindows.DEFAULT_CONTEXT_WINDOW;
        boolean conservative = config.isClaudeCliNative();
        long budget;
        if (conservative) {
            long inputBudget = (long) (window * Math.min(0.5, config.getAutoCompactThreshold()));
            budget = Math.min(50_000L, inputBudget
                    - utf8Bytes(systemPrompt) - utf8Bytes(userMessage)
                    - utf8Bytes(RESTORED_CONVERSATION_OPEN) - utf8Bytes(RESTORED_CONVERSATION_CLOSE)
                    - 256); // omission marker and native turn envelope
        } else {
            // Preserve the existing OpenCode restoration policy.
            budget = (long) (window * config.getAutoCompactThreshold()) * CHARS_PER_TOKEN
                    - RESTORED_CONVERSATION_OPEN.length() - RESTORED_CONVERSATION_CLOSE.length()
                    - (systemPrompt == null ? 0 : systemPrompt.length())
                    - (userMessage == null ? 0 : userMessage.length());
        }
        String history = portableHistoryText(budget, conservative);
        return history.isEmpty() ? ""
                : RESTORED_CONVERSATION_OPEN + history + RESTORED_CONVERSATION_CLOSE;
    }

    private static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private String portableHistoryText(long budget, boolean conservative) {
        List<String> kept = new ArrayList<>();
        int omitted = 0;
        synchronized (historyLock) {
            long used = 0;
            int index = conversationHistory.size() - 1;
            for (; index >= 0; index--) {
                String message = portableMessageText(conversationHistory.get(index));
                if (message.isEmpty()) continue;
                int size = conservative ? utf8Bytes(message) : message.length();
                if (used + size > budget) break;
                used += size;
                kept.add(message);
            }
            for (; index >= 0; index--) {
                if (!portableMessageText(conversationHistory.get(index)).isEmpty()) omitted++;
            }
        }
        StringBuilder text = new StringBuilder();
        if (omitted > 0) {
            text.append('[').append(omitted)
                    .append(" earlier messages omitted to fit the context window]\n\n");
        }
        for (int i = kept.size() - 1; i >= 0; i--) text.append(kept.get(i));
        return text.toString().strip();
    }

    /**
     * What the user and assistant said in one message. Tool calls and their output
     * are left out: the new session has its own tools, and the answers built on
     * those results are kept.
     */
    private static String portableMessageText(ObjectNode message) {
        String role = message.path("role").asText("");
        if (role.isEmpty() || "tool".equals(role)) return "";
        String text = plainText(message.get("content"));
        if ("user".equals(role)) {
            if (text.startsWith("[Tool result ")) return "";
            text = withoutTurnEnvelopes(text);
        } else if (text.startsWith("[Tool call ")) {
            return "";
        }
        text = text.strip();
        return text.isEmpty() ? "" : "[" + role + "]\n" + text + "\n\n";
    }

    /** A message body as text: the string itself, or the text blocks of a content array. */
    private static String plainText(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if (("text".equals(type) || "input_text".equals(type) || "output_text".equals(type))
                        && block.path("text").isTextual()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.toString();
    }

    /**
     * The user's own text: drops the per-turn reminder block and injected memory
     * context, which every new turn carries fresh.
     */
    static String withoutTurnEnvelopes(String message) {
        String text = ReminderManager.stripReminderBlock(message);
        if (!text.startsWith(MEMORY_CONTEXT_OPEN)) return text;
        int end = text.indexOf(MEMORY_CONTEXT_CLOSE);
        return end < 0 ? text
                : text.substring(end + MEMORY_CONTEXT_CLOSE.length()).stripLeading();
    }

    /**
     * True for the chat message that shows a turn Claude Code started by itself.
     * It carries no user text, so no memory, reminders or attachments go with it.
     */
    public static boolean isProviderFollowUp(String message) {
        return claudeFollowUpId(message) != null;
    }

    /**
     * What set off the turn a follow-up message ({@link #isProviderFollowUp}) shows: one
     * line for each task that ended before the provider started it, with what the task
     * was, how it ended and the provider's summary. Empty for any other message, or when
     * the provider reported no task end.
     */
    public List<String> providerFollowUpTriggers(String message) {
        String followUpId = claudeFollowUpId(message);
        ClaudeCliClient client = claudeServeClient;
        return followUpId == null || client == null ? List.of() : client.followUpTriggers(followUpId);
    }

    private static String claudeFollowUpId(String message) {
        return message == null ? null : ClaudeCliClient.followUpId(withoutTurnEnvelopes(message));
    }

    public int getHistorySize() {
        synchronized (historyLock) {
            return conversationHistory.size();
        }
    }

    /** Read-only diagnostic view of the effective connection/retry policy. */
    public ProviderConnectivityPolicy getConnectivityPolicy() {
        return connectivityPolicy;
    }

    /** Live chat configuration shared with the standard-chat REPL. */
    public ChatConfig getChatConfig() {
        return config;
    }

    /** The provider configured for this client. */
    public String getConfiguredProvider() {
        return config.getProvider();
    }

    /** The model configured for this client (before any per-agent override). */
    public String getConfiguredModel() {
        return config.getModel();
    }

    /** The resolved provider base URL this client sends requests to. */
    public String getResolvedBaseUrl() {
        return config.resolveBaseUrl();
    }

    /**
     * In-place mid-conversation pruning of the wire history: collapse the content of
     * old {@code tool} messages to short summaries and clip old assistant text, while
     * leaving message roles, ids, and {@code tool_calls} wiring untouched — so the
     * assistant/tool pairing the provider validates stays intact. Safe to call between
     * agentic steps, unlike a full summary rewrite.
     *
     * @param preserveRecentMessages number of newest messages left untouched
     * @param toolResultSummarizer   maps (toolName, content) → summary text
     * @return number of messages shrunk
     */
    public int compactToolHistory(int preserveRecentMessages,
                                  java.util.function.BinaryOperator<String> toolResultSummarizer) {
        synchronized (historyLock) {
            int lastPrunable = conversationHistory.size() - Math.max(0, preserveRecentMessages);
            int shrunk = 0;
            for (int i = 0; i < lastPrunable; i++) {
                ObjectNode msg = conversationHistory.get(i);
                String role = msg.path("role").asText("");
                String content = msg.path("content").asText("");
                if ("tool".equals(role) && content.length() > 600) {
                    String toolName = msg.path("name").asText(null);
                    msg.put("content", toolResultSummarizer.apply(toolName, content));
                    shrunk++;
                } else if ("assistant".equals(role) && content.length() > 2_000) {
                    msg.put("content", content.substring(0, 2_000)
                            + "\n... (truncated during compaction)");
                    shrunk++;
                }
            }
            return shrunk;
        }
    }

    private boolean usesGitHubAnthropicMessages(String model) {
        return "github-copilot".equals(config.getProvider())
                && model != null
                && model.startsWith("claude-")
                && !model.startsWith("claude-fable-");
    }

    private boolean usesOpenAiResponses(String model) {
        // Astra function calling requires Responses. Keep the same protocol on
        // tool-free turns too, so replay and subsequent tool results agree.
        // Do not infer protocol support for third-party OpenAI-compatible APIs.
        return "openai".equals(config.getProvider()) && model != null
                && (model.equals("gpt-6-astra") || model.startsWith("gpt-6-astra-"));
    }

    private boolean usesGitHubOpenAiResponses(String model) {
        if (!"github-copilot".equals(config.getProvider()) || model == null) {
            return false;
        }
        return model.startsWith("gpt-5")
                || model.startsWith("grok-")
                || model.startsWith("mai-code-");
    }

    private void applyReasoningEffort(ObjectNode request, boolean responsesFormat) {
        String effort = ProviderThinkingConfig.wireValue(
                config.getProvider(), config.getThinking());
        if (effort == null || effort.isBlank()) {
            return;
        }
        if ("zai".equals(config.getProvider())) {
            applyZaiThinking(request, effort);
            return;
        }
        if (responsesFormat) {
            ObjectNode reasoning = objectMapper.createObjectNode();
            reasoning.put("effort", effort);
            // Ask the provider to include streamed reasoning summaries so the
            // transcript can show them live. Providers that cannot summarize
            // simply omit them.
            reasoning.put("summary", "auto");
            request.set("reasoning", reasoning);
        } else {
            request.put("reasoning_effort", effort);
        }
    }

    /**
     * Z.AI GLM wire contract (docs.z.ai chat-completion): a reasoning on/off choice
     * rides as {@code thinking:{type:enabled|disabled}}; an effort tier rides as
     * {@code reasoning_effort} (GLM-5.2+; implies thinking, which is enabled by
     * default on those models). Values arrive already wire-shaped from the catalog's
     * {@code reasoning_options} or the documented fallback resource.
     */
    static void applyZaiThinking(ObjectNode request, String value) {
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if ("enabled".equals(normalized) || "disabled".equals(normalized)) {
            ObjectNode thinking = request.putObject("thinking");
            thinking.put("type", normalized);
            return;
        }
        request.put("reasoning_effort", normalized);
    }

    /**
     * Legacy compatibility guard for non-OpenAI chat-completions providers. Keep
     * their existing behavior for known reasoning model names. OpenAI itself uses
     * max_completion_tokens for every model and must not pass through this guard.
     */
    private static boolean acceptsMaxTokens(String model) {
        if (model == null || model.isBlank()) return true;
        String normalized = model.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4")) {
            return false;
        }
        return !normalized.startsWith("gpt-5");
    }

    private void applyOpenAiFastMode(ObjectNode request, String model) {
        if (("openai".equals(config.getProvider()) || config.isOpenAiCodexFormat())
                && config.fastModeCapabilities().supports(model)) {
            // Codex's service_tier="fast" maps to "priority" on the wire.
            // Explicit default also overrides an OpenAI project's paid default.
            request.put("service_tier", config.useFastMode(model) ? "priority" : "default");
        }
    }

    private void applyResponsesJsonOutput(ObjectNode request, boolean codex) {
        JsonOutputSpec output = requestedJsonOutput;
        if (output == null) {
            return;
        }
        JsonNode existingText = request.get("text");
        ObjectNode text = existingText instanceof ObjectNode object
                ? object : objectMapper.createObjectNode();
        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", output.name());
        format.put("strict", output.strict());
        format.set("schema", output.strict() && codex
                ? normalizeNativeCodexStrictSchema(output.schema()) : output.schema().deepCopy());
        text.set("format", format);
        request.set("text", text);
    }

    private void applyChatCompletionsJsonOutput(ObjectNode request) {
        JsonOutputSpec output = requestedJsonOutput;
        if (output == null
                || !ProviderStructuredOutputCapabilities.forProvider(config.getProvider())
                        .supportsJsonSchema()) {
            return;
        }
        if (ProviderStructuredOutputCapabilities.forProvider(config.getProvider()).isJsonObjectOnly()) {
            // Documented object mode (Z.AI): the schema must travel in the system
            // message. Sending the OpenAI-only json_schema enum value here yields
            // prose instead of JSON (observed on glm-5.3-flash) and broke
            // strict-schema pipelines.
            ensureJsonSchemaInSystemMessage(request);
            if (!requestHasImageContent(request)) {
                ObjectNode objectMode = objectMapper.createObjectNode();
                objectMode.put("type", "json_object");
                request.set("response_format", objectMode);
            }
            // response_format is documented for text models only; on image-bearing
            // requests the system-message contract alone carries the schema, so the
            // multimodal flow (e.g. glm-5.3-flash OCR) stays on one request.
            return;
        }
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", output.name());
        jsonSchema.put("strict", output.strict());
        // Do not apply the native Codex compatibility reduction to generic providers.
        jsonSchema.set("schema", output.schema().deepCopy());
        request.set("response_format", responseFormat);
    }

    /** Carry the requested schema in the system prompt for object-mode providers (Z.AI). */
    private void ensureJsonSchemaInSystemMessage(ObjectNode request) {
        JsonOutputSpec output = requestedJsonOutput;
        if (output == null) return;
        JsonNode messages = request.get("messages");
        if (!(messages instanceof ArrayNode messageArray) || messageArray.isEmpty()) return;
        JsonNode first = messageArray.get(0);
        String systemContract = "You must respond with a single JSON object and nothing else.\n"
                + "The JSON must conform exactly to this schema (\"" + output.name() + "\"):\n"
                + output.schema().toString();
        String existing = first.path("content").asText("");
        if (!existing.isBlank()) {
            systemContract = systemContract + "\n\nAdditional instructions:\n" + existing;
        }
        ((ObjectNode) first).put("content", systemContract);
    }

    /** True when any request message carries an image block (multimodal request). */
    private static boolean requestHasImageContent(ObjectNode request) {
        JsonNode messages = request.get("messages");
        if (!(messages instanceof ArrayNode array)) return false;
        for (JsonNode message : array) {
            JsonNode content = message.path("content");
            if (!content.isArray()) continue;
            for (JsonNode block : content) {
                if ("image_url".equals(block.path("type").asText())) return true;
            }
        }
        return false;
    }

    private static JsonNode normalizeNativeCodexStrictSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema == null ? null : schema.deepCopy();
        }
        return normalizeSchemaObject((ObjectNode) schema);
    }

    private static ObjectNode normalizeSchemaObject(ObjectNode schema) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        schema.fields().forEachRemaining(entry -> {
            String keyword = entry.getKey();
            if (NATIVE_CODEX_STRICT_UNSUPPORTED_KEYWORDS.contains(keyword)) return;
            JsonNode value = entry.getValue();
            if (SCHEMA_MAP_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaMap(value));
            } else if (SCHEMA_ARRAY_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaArray(value));
            } else if (SCHEMA_VALUE_KEYWORDS.contains(keyword)) {
                result.set(keyword, normalizeSchemaValue(value));
            } else {
                // required/type/const/enum/default/examples and extension payloads are data,
                // not schema objects. In particular, preserve property names and literal maps.
                result.set(keyword, value.deepCopy());
            }
        });
        return result;
    }

    private static JsonNode normalizeSchemaMap(JsonNode value) {
        if (!value.isObject()) return value.deepCopy();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        value.fields().forEachRemaining(entry -> {
            JsonNode child = entry.getValue();
            result.set(entry.getKey(), child.isObject()
                    ? normalizeSchemaObject((ObjectNode) child) : child.deepCopy());
        });
        return result;
    }

    private static JsonNode normalizeSchemaArray(JsonNode value) {
        if (!value.isArray()) return value.isObject()
                ? normalizeSchemaObject((ObjectNode) value) : value.deepCopy();
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (JsonNode child : value) {
            result.add(child.isObject()
                    ? normalizeSchemaObject((ObjectNode) child) : child.deepCopy());
        }
        return result;
    }

    private static JsonNode normalizeSchemaValue(JsonNode value) {
        if (value.isObject()) return normalizeSchemaObject((ObjectNode) value);
        return value.isArray() ? normalizeSchemaArray(value) : value.deepCopy();
    }

    private static String normalizeJsonSchemaName(String value) {
        String source = value == null || value.isBlank() ? "kompile_judge_verdict" : value.strip();
        String normalized = source.replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized.isBlank() ? "kompile_judge_verdict" : normalized;
    }

    private String activePromptCacheSessionId() {
        return config.promptCacheRetention()
                == ProviderPromptCacheCapabilities.Retention.NONE
                ? null : promptCacheSessionId;
    }

    private void applyAnthropicPromptCacheControl(ObjectNode request) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        ProviderPromptCacheCapabilities.Retention retention = config.promptCacheRetention();
        if (capabilities.activation()
                != ProviderPromptCacheCapabilities.Activation.EXPLICIT
                || retention == ProviderPromptCacheCapabilities.Retention.NONE) {
            return;
        }
        ObjectNode cacheControl = request.putObject("cache_control");
        cacheControl.put("type", "ephemeral");
        if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
            cacheControl.put("ttl", "1h");
        }
    }

    private void applyOpenAiPromptCacheControls(ObjectNode request, String model) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        if (capabilities.sessionAffinity()
                == ProviderPromptCacheCapabilities.SessionAffinity.OPENAI_PROMPT_CACHE_KEY) {
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) request.put("prompt_cache_key", cacheSessionId);
        }
        if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENAI) {
            applyOpenAiRetentionControls(request, model, config.promptCacheRetention());
        }
    }

    private void applyOpenAiCompatiblePromptCacheControls(
            ObjectNode request, String model) {
        ProviderPromptCacheCapabilities capabilities = config.promptCacheCapabilities();
        ProviderPromptCacheCapabilities.Retention retention = config.promptCacheRetention();
        if (capabilities.sessionAffinity()
                == ProviderPromptCacheCapabilities.SessionAffinity.OPENAI_PROMPT_CACHE_KEY) {
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) request.put("prompt_cache_key", cacheSessionId);
        }
        if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENAI) {
            applyOpenAiRetentionControls(request, model, retention);
        } else if (capabilities.retentionControl()
                == ProviderPromptCacheCapabilities.RetentionControl.OPENROUTER
                && retention != ProviderPromptCacheCapabilities.Retention.NONE
                && isOpenRouterAnthropicModel(model)) {
            ObjectNode cacheControl = request.putObject("cache_control");
            cacheControl.put("type", "ephemeral");
            if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
                cacheControl.put("ttl", "1h");
            }
        }
    }

    private static boolean isOpenRouterAnthropicModel(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("anthropic/")
                || normalized.startsWith("~anthropic/");
    }

    private static void applyOpenAiRetentionControls(
            ObjectNode request,
            String model,
            ProviderPromptCacheCapabilities.Retention retention) {
        boolean promptCacheOptions = usesOpenAiPromptCacheOptions(model);
        if (retention == ProviderPromptCacheCapabilities.Retention.NONE) {
            if (promptCacheOptions) {
                request.putObject("prompt_cache_options").put("mode", "explicit");
            }
            return;
        }
        if (promptCacheOptions) {
            ObjectNode options = request.putObject("prompt_cache_options");
            options.put("mode", "implicit");
            if (retention == ProviderPromptCacheCapabilities.Retention.LONG) {
                options.put("ttl", "30m");
            }
        } else {
            boolean useExtendedRetention = usesOpenAi24hOnlyRetention(model)
                    || (retention == ProviderPromptCacheCapabilities.Retention.LONG
                    && supportsOpenAi24hRetention(model));
            request.put("prompt_cache_retention",
                    useExtendedRetention ? "24h" : "in_memory");
        }
    }

    /** GPT-5.5 currently exposes only the extended retention value. */
    private static boolean usesOpenAi24hOnlyRetention(String model) {
        if (model == null) return false;
        return model.strip().toLowerCase(Locale.ROOT).startsWith("gpt-5.5");
    }

    /** Families documented by OpenAI as accepting prompt_cache_retention=24h. */
    private static boolean supportsOpenAi24hRetention(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("gpt-5.5")
                || normalized.startsWith("gpt-5.4")
                || normalized.startsWith("gpt-5.2")
                || normalized.startsWith("gpt-5.1")) {
            return true;
        }
        if (normalized.equals("gpt-5") || normalized.startsWith("gpt-5-")) {
            return true;
        }
        return normalized.equals("gpt-4.1") || normalized.startsWith("gpt-4.1-20");
    }

    /** GPT 5.6+ moved retention and disable controls under prompt_cache_options. */
    private static boolean usesOpenAiPromptCacheOptions(String model) {
        if (model == null) return false;
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("gpt-")) return false;
        int end = normalized.indexOf('-', 4);
        String version = end < 0 ? normalized.substring(4) : normalized.substring(4, end);
        String[] parts = version.split("\\.", 3);
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || (major == 5 && minor >= 6);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    // ========================================================================
    // OpenAI Responses and ChatGPT Codex Responses
    // ========================================================================

    private StreamResult streamOpenAiResponses(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel,
            boolean codex,
            List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();
        ResponsesStreamState state = new ResponsesStreamState();

        try {
            ResponsesHistoryLinks historyLinks = sanitizeResponsesHistory(toolResults);
            List<ObjectNode> stagedToolResultItems =
                    prepareResponsesToolResultItems(toolResults, historyLinks);
            ArrayNode input = buildResponsesInput(
                    userMessage, systemPrompt, stagedToolResultItems, codex, attachments);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.set("input", input);
            request.put("stream", true);
            request.put("store", false);
            applyReasoningEffort(request, true);
            applyOpenAiFastMode(request, effectiveModel);
            applyOpenAiPromptCacheControls(request, effectiveModel);
            String nativeRouteKey = "responses:" + effectiveModel;
            boolean nativeCompaction = compactionCapabilities(effectiveModel).nativeCompaction()
                    == ProviderCompactionCapabilities.NativeCompaction.OPENAI_RESPONSES
                    && nativeCompactionTriggerTokens > 0
                    && !unavailableNativeCompactionRoutes.contains(nativeRouteKey);
            if (nativeCompaction) {
                request.putArray("context_management").addObject()
                        .put("type", "compaction")
                        .put("compact_threshold", nativeCompactionTriggerTokens);
            }

            if (codex) {
                request.put("instructions", boundedOpenAiInstructions(systemPrompt));
                ObjectNode text = objectMapper.createObjectNode();
                text.put("verbosity", "low");
                request.set("text", text);
                request.put("tool_choice", "auto");
                request.put("parallel_tool_calls", true);
            }
            if (codex || "openai".equals(config.getProvider())) {
                // store=false: retain opaque reasoning for later tool turns.
                request.putArray("include").add("reasoning.encrypted_content");
            }
            applyResponsesJsonOutput(request, codex);

            if (toolDefs != null && !toolDefs.isEmpty()) {
                request.set("tools", convertToolDefsToResponses(toolDefs, codex));
            }

            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = resolveResponsesUrl(baseUrl, codex);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "kompile")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body, response.headers())) return result;
                if (nativeCompaction && (response.statusCode() == 400
                        || response.statusCode() == 404 || response.statusCode() == 422)
                        && !isContextOverflowFailure(response.statusCode(), body)) {
                    unavailableNativeCompactionRoutes.add(nativeRouteKey);
                    return streamOpenAiResponses(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, codex, attachments, auth);
                }
                String label = codex ? "OpenAI Codex" : "OpenAI Responses";
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[" + label + " API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        label + " HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return result;
            }

            parseResponsesStream(guardResponseStream(response.body()), result, state);
            if (!result.cancelled && isGenerationStopped(result)) {
                // The input was accepted even though output generation was refused/truncated.
                conversationHistory.addAll(stagedToolResultItems);
            }
            if (!state.failed && !result.cancelled) {
                // Commit submitted tool results only after the provider accepts the
                // request. A rejected request must not poison every later turn.
                conversationHistory.addAll(stagedToolResultItems);
                appendResponsesHistory(userMessage, attachments, result, state);
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    /**
     * OpenAI validates the Responses {@code instructions} field independently of
     * model context compaction. Preserve the stable agent prefix and the most
     * specific project/tool tail while keeping every request within that hard API
     * boundary. The Java UTF-16 length is conservative for non-BMP code points.
     */
    static String boundedOpenAiInstructions(String systemPrompt) {
        String instructions = systemPrompt == null || systemPrompt.isBlank()
                ? "You are a helpful assistant."
                : systemPrompt;
        if (instructions.length() <= OPENAI_INSTRUCTIONS_MAX_CHARS) {
            return instructions;
        }

        int contentBudget = OPENAI_INSTRUCTIONS_MAX_CHARS
                - OPENAI_INSTRUCTIONS_OMISSION.length();
        int prefixEnd = safePrefixEnd(instructions, contentBudget * 3 / 5);
        int suffixStart = safeSuffixStart(
                instructions, instructions.length() - (contentBudget - prefixEnd));
        return instructions.substring(0, prefixEnd)
                + OPENAI_INSTRUCTIONS_OMISSION
                + instructions.substring(suffixStart);
    }

    private static int safePrefixEnd(String value, int end) {
        int bounded = Math.max(0, Math.min(end, value.length()));
        if (bounded > 0 && bounded < value.length()
                && Character.isHighSurrogate(value.charAt(bounded - 1))
                && Character.isLowSurrogate(value.charAt(bounded))) {
            bounded--;
        }
        return bounded;
    }

    private static int safeSuffixStart(String value, int start) {
        int bounded = Math.max(0, Math.min(start, value.length()));
        if (bounded > 0 && bounded < value.length()
                && Character.isHighSurrogate(value.charAt(bounded - 1))
                && Character.isLowSurrogate(value.charAt(bounded))) {
            bounded++;
        }
        return bounded;
    }

    private ArrayNode buildResponsesInput(
            String userMessage,
            String systemPrompt,
            List<ObjectNode> stagedToolResultItems,
            boolean codex) {
        return buildResponsesInput(
                userMessage, systemPrompt, stagedToolResultItems, codex, List.of());
    }

    private ArrayNode buildResponsesInput(
            String userMessage,
            String systemPrompt,
            List<ObjectNode> stagedToolResultItems,
            boolean codex,
            List<AttachmentInput> attachments) {
        ArrayNode input = objectMapper.createArrayNode();
        if (!codex && systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "developer");
            system.put("content", systemPrompt);
            input.add(system);
        }
        conversationHistory.forEach(input::add);
        if (stagedToolResultItems != null) {
            stagedToolResultItems.forEach(input::add);
        }
        if (userMessage != null || !attachments.isEmpty()) {
            input.add(createResponsesUserMessage(userMessage, attachments));
        }
        return MemoryContextProjection.project(input);
    }

    /**
     * Remove malformed/duplicate Responses linkage left by an interrupted older
     * client. Function outputs are valid only when the matching function call is
     * present earlier in the same retained request history. A function call with
     * neither a retained output nor a currently pending executor result is also
     * incomplete and must not be sent on the next request.
     */
    private ResponsesHistoryLinks sanitizeResponsesHistory(
            List<ToolCallResultInput> pendingToolResults) {
        Set<String> pendingCalls = new LinkedHashSet<>();
        if (pendingToolResults != null) {
            for (ToolCallResultInput toolResult : pendingToolResults) {
                if (toolResult != null && toolResult.callId != null
                        && !toolResult.callId.isBlank()) {
                    pendingCalls.add(toolResult.callId.trim());
                }
            }
        }

        Set<String> calls = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();
        List<ObjectNode> sanitized = new ArrayList<>(conversationHistory.size());
        for (ObjectNode item : conversationHistory) {
            String type = item.path("type").asText("");
            if ("function_call".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.add(callId)) {
                    continue;
                }
            } else if ("function_call_output".equals(type)) {
                String callId = item.path("call_id").asText("");
                if (callId.isBlank() || !calls.contains(callId) || !outputs.add(callId)) {
                    continue;
                }
            }
            sanitized.add(item);
        }

        List<ObjectNode> complete = new ArrayList<>(sanitized.size());
        Set<String> retainedCalls = new LinkedHashSet<>();
        for (ObjectNode item : sanitized) {
            if ("function_call".equals(item.path("type").asText(""))) {
                String callId = item.path("call_id").asText("");
                if (!outputs.contains(callId) && !pendingCalls.contains(callId)) {
                    continue;
                }
                retainedCalls.add(callId);
            }
            complete.add(item);
        }

        if (complete.size() != conversationHistory.size()) {
            conversationHistory.clear();
            conversationHistory.addAll(complete);
        }
        return new ResponsesHistoryLinks(retainedCalls, outputs);
    }

    /**
     * Stage current tool results without mutating retained history. When a full
     * compaction or resume lost the provider-owned function-call item, preserve
     * the useful result as ordinary user context instead of emitting an orphaned
     * function_call_output that OpenAI rejects with HTTP 400.
     */
    private List<ObjectNode> prepareResponsesToolResultItems(
            List<ToolCallResultInput> toolResults,
            ResponsesHistoryLinks historyLinks) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        Set<String> submittedOutputs = new LinkedHashSet<>(historyLinks.outputs());
        List<ObjectNode> staged = new ArrayList<>();
        for (ToolCallResultInput toolResult : toolResults) {
            String callId = toolResult.callId == null ? "" : toolResult.callId.trim();
            if (!callId.isBlank() && historyLinks.calls().contains(callId)) {
                if (!submittedOutputs.add(callId)) {
                    continue;
                }
                ObjectNode output = objectMapper.createObjectNode();
                output.put("type", "function_call_output");
                output.put("call_id", callId);
                output.put("output", toolResult.output == null ? "" : toolResult.output);
                staged.add(output);
                continue;
            }

            String toolName = toolResult.name == null || toolResult.name.isBlank()
                    ? "unknown tool" : toolResult.name;
            StringBuilder recovered = new StringBuilder()
                    .append("[Recovered tool result for ").append(toolName)
                    .append("; the provider function-call link was unavailable after compaction/resume]");
            if (toolResult.isError) {
                recovered.append(" [tool reported an error]");
            }
            if (toolResult.output != null && !toolResult.output.isBlank()) {
                recovered.append('\n').append(toolResult.output);
            }
            staged.add(createResponsesUserMessage(recovered.toString()));
        }
        return staged;
    }

    private record ResponsesHistoryLinks(Set<String> calls, Set<String> outputs) {}

    private ObjectNode createResponsesUserMessage(String text) {
        return createResponsesUserMessage(text, List.of());
    }

    private ObjectNode createResponsesUserMessage(
            String text, List<AttachmentInput> attachments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.set("content", buildResponsesContentArray(text, attachments));
        return message;
    }

    private ArrayNode convertToolDefsToResponses(ArrayNode toolDefs, boolean codex) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode responseTool = objectMapper.createObjectNode();
            responseTool.put("type", "function");
            responseTool.put("name", tool.path("name").asText());
            responseTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            responseTool.set("parameters", parameters);
            if (codex) {
                responseTool.putNull("strict");
            } else if ("openai".equals(config.getProvider())) {
                // MCP schemas permit omitted optional arguments. Responses
                // must not silently normalize them into strict required fields.
                responseTool.put("strict", false);
            }
            tools.add(responseTool);
        }
        return tools;
    }

    private void parseResponsesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            ResponsesStreamState state) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int outputIndex = event.path("output_index").asInt(0);
                switch (type) {
                    case "response.output_item.added" -> {
                        JsonNode item = event.path("item");
                        result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        captureResponsesCompaction(result, item);
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, false);
                    }
                    case "response.output_text.delta", "response.refusal.delta" -> {
                        if ("response.refusal.delta".equals(type)) result.refusalDetected = true;
                        String delta = event.path("delta").asText("");
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        // OpenAI Responses reasoning stream — transcript-only.
                        printThinkingChunk(event.path("delta").asText(""));
                    }
                    case "response.function_call_arguments.delta" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        String argumentsDelta = event.path("delta").asText("");
                        accumulator.arguments.append(argumentsDelta);
                        reportToolInputDelta(argumentsDelta);
                    }
                    case "response.function_call_arguments.done" -> {
                        ResponsesToolCallAccumulator accumulator =
                                state.toolCalls.computeIfAbsent(
                                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
                        String arguments = event.path("arguments").asText(null);
                        if (arguments != null) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(arguments);
                        }
                    }
                    case "response.output_item.done" -> {
                        JsonNode item = event.path("item");
                        result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        captureResponsesCompaction(result, item);
                        updateResponsesReasoningItem(state, outputIndex, item);
                        updateResponsesToolAccumulator(state, outputIndex, item, true);
                        if ("message".equals(item.path("type").asText()) && result.text.isEmpty()) {
                            String completedText = responsesMessageText(item);
                            if (!completedText.isEmpty()) {
                                printStreamingChunk(completedText);
                                result.text = completedText;
                            }
                        }
                    }
                    case "response.completed", "response.incomplete" -> {
                        state.terminal = true;
                        JsonNode response = event.path("response");
                        for (JsonNode item : response.path("output")) {
                            result.refusalDetected |= responsesContainRefusal(item.path("content"));
                        }
                        if ("response.incomplete".equals(type)) {
                            String reason = response.path("incomplete_details").path("reason").asText("");
                            result.refusalDetected |= "content_filter".equals(reason);
                            result.truncatedDetected = !result.refusalDetected;
                        }
                        captureResponsesCompactionFromOutput(result, response.path("output"));
                        backfillResponsesReasoning(state, response.path("output"));
                        readResponsesUsage(response.path("usage"), result);
                    }
                    case "response.failed" -> {
                        state.terminal = true;
                        state.failed = true;
                        readResponsesUsage(event.path("response").path("usage"), result);
                        JsonNode error = event.path("response").path("error");
                        String message = error.path("message").asText("Response failed");
                        appendProtocolError(result, message, error.toString());
                    }
                    case "error" -> {
                        state.failed = true;
                        JsonNode error = event.path("error");
                        appendProtocolError(
                                result,
                                event.path("message").asText(
                                        error.path("message").asText("Unknown response error")),
                                event.toString());
                    }
                    default -> {
                        // Other Responses events carry reasoning/status metadata.
                    }
                }
                // A terminal Responses event completes the request; the server need not
                // close its transport before we deliver usage and accumulated tool calls.
                if (state.terminal) {
                    break;
                }
            }
        }

        if (finishGenerationOutcome(result)) {
            state.failed = true;
            return;
        }
        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            if (accumulator.callId == null || accumulator.callId.isBlank()) {
                accumulator.callId = "call_" + result.toolCalls.size();
            }
            if (accumulator.itemId == null || accumulator.itemId.isBlank()) {
                accumulator.itemId = "fc_" + UUID.randomUUID().toString().replace("-", "");
            }
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Responses stream ended without a terminal event");
        }
    }

    private void captureResponsesCompaction(StreamResult result, JsonNode item) {
        if (item != null && "compaction".equals(item.path("type").asText())
                && item.isObject()) {
            result.nativeCompactionPayload = item.deepCopy();
            result.nativeCompactionStrategy = "openai-responses";
        }
    }

    private void captureResponsesCompactionFromOutput(StreamResult result, JsonNode output) {
        if (output == null || !output.isArray()) return;
        for (JsonNode item : output) captureResponsesCompaction(result, item);
    }

    private void updateResponsesReasoningItem(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item) {
        if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
            state.reasoningItems.put(outputIndex, ((ObjectNode) item).deepCopy());
        }
    }

    private void backfillResponsesReasoning(ResponsesStreamState state, JsonNode output) {
        if (!output.isArray()) {
            return;
        }
        for (int index = 0; index < output.size(); index++) {
            JsonNode item = output.get(index);
            if ("reasoning".equals(item.path("type").asText()) && item.isObject()) {
                state.reasoningItems.put(index, ((ObjectNode) item).deepCopy());
            }
        }
    }

    private void updateResponsesToolAccumulator(
            ResponsesStreamState state,
            int outputIndex,
            JsonNode item,
            boolean finalItem) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        ResponsesToolCallAccumulator accumulator =
                state.toolCalls.computeIfAbsent(
                        outputIndex, ignored -> new ResponsesToolCallAccumulator());
        String value = item.path("id").asText(null);
        if (value != null) accumulator.itemId = value;
        value = item.path("call_id").asText(null);
        if (value != null) accumulator.callId = value;
        value = item.path("name").asText(null);
        if (value != null) accumulator.name = value;
        value = item.path("arguments").asText(null);
        if (value != null && (finalItem || accumulator.arguments.isEmpty())) {
            accumulator.arguments.setLength(0);
            accumulator.arguments.append(value);
        }
    }

    private String responsesMessageText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : item.path("content")) {
            if ("output_text".equals(content.path("type").asText())) {
                text.append(content.path("text").asText(""));
            } else if ("refusal".equals(content.path("type").asText())) {
                text.append(content.path("refusal").asText(""));
            }
        }
        return text.toString();
    }

    private void readResponsesUsage(JsonNode usage, StreamResult result) {
        if (usage == null || !usage.isObject()) return;
        JsonNode details = usage.path("input_tokens_details");
        long totalInput = usageCount(usage, "input_tokens",
                result.inputTokens + result.cacheReadTokens + result.cacheCreationTokens);
        result.cacheReadTokens = usageCount(details, "cached_tokens", result.cacheReadTokens);
        result.cacheCreationTokens = usageCount(details, "cache_write_tokens", result.cacheCreationTokens);
        result.inputTokens = Math.max(0L, totalInput - result.cacheReadTokens - result.cacheCreationTokens);
        // output_tokens already includes reasoning: never add its detail twice.
        result.outputTokens = usageCount(usage, "output_tokens", result.outputTokens);
    }

    static void readOpenAiCompatibleUsage(JsonNode usage, StreamResult result) {
        // Many streams send usage:null on ordinary chunks. Usage objects are
        // cumulative snapshots, not deltas; absent fields must not erase counts.
        if (usage == null || !usage.isObject()) return;
        JsonNode details = usage.path("prompt_tokens_details");
        long cacheRead = usageCount(details, "cached_tokens",
                usageCount(usage, "prompt_cache_hit_tokens",
                        usageCount(usage, "cached_tokens", result.cacheReadTokens)));
        long cacheWrite = usageCount(details, "cache_write_tokens", result.cacheCreationTokens);
        long totalInput = usageCount(usage, "prompt_tokens",
                usageCount(usage, "input_tokens",
                        result.inputTokens + result.cacheReadTokens + result.cacheCreationTokens));
        // DeepSeek's miss count is uncached input, not cache-creation usage.
        result.inputTokens = usage.hasNonNull("prompt_cache_miss_tokens")
                ? Math.max(0L, usageCount(usage, "prompt_cache_miss_tokens", 0L) - cacheWrite)
                : Math.max(0L, totalInput - cacheRead - cacheWrite);
        // Chat Completions totals include reasoning (also on Gemini's OpenAI
        // endpoint); completion_tokens_details is a breakdown, not extra output.
        result.outputTokens = usageCount(usage, "completion_tokens",
                usageCount(usage, "output_tokens", result.outputTokens));
        result.cacheReadTokens = cacheRead;
        result.cacheCreationTokens = cacheWrite;
    }

    private static long usageCount(JsonNode usage, String field, long fallback) {
        return usage != null && usage.hasNonNull(field)
                ? Math.max(0L, usage.path(field).asLong(0L)) : fallback;
    }

    private void appendResponsesHistory(
            String userMessage,
            List<AttachmentInput> attachments,
            StreamResult result,
            ResponsesStreamState state) {
        if (result.nativeCompactionPayload != null) {
            // The native item replaces every request item that preceded it.
            conversationHistory.clear();
        } else if (userMessage != null || !attachments.isEmpty()) {
            conversationHistory.add(createResponsesUserMessage(userMessage, attachments));
        }
        if (result.nativeCompactionPayload != null && result.nativeCompactionPayload.isObject()) {
            conversationHistory.add(((ObjectNode) result.nativeCompactionPayload).deepCopy());
        }
        state.reasoningItems.values().forEach(conversationHistory::add);
        if (!result.text.isEmpty()) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("status", "completed");
            message.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "output_text");
            text.put("text", result.text);
            text.set("annotations", objectMapper.createArrayNode());
            content.add(text);
            message.set("content", content);
            conversationHistory.add(message);
        }
        for (ResponsesToolCallAccumulator accumulator : state.toolCalls.values()) {
            if (accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call");
            item.put("id", accumulator.itemId);
            item.put("call_id", accumulator.callId);
            item.put("name", accumulator.name);
            item.put("arguments", accumulator.arguments.isEmpty()
                    ? "{}" : accumulator.arguments.toString());
            item.put("status", "completed");
            conversationHistory.add(item);
        }
    }

    private String resolveResponsesUrl(String baseUrl, boolean codex) {
        String normalized = trimTrailingSlashes(baseUrl);
        if (!codex) {
            return normalized.endsWith("/responses") ? normalized : normalized + "/responses";
        }
        if (normalized.endsWith("/codex/responses")) return normalized;
        if (normalized.endsWith("/codex")) return normalized + "/responses";
        return normalized + "/codex/responses";
    }

    // ========================================================================
    // Radius / Pi Messages
    // ========================================================================

    private StreamResult streamPiMessages(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            String effectiveModel, List<AttachmentInput> attachments,
            OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();
        try {
            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String gateway = config.resolveBaseUrl(auth);
            RadiusGatewayConfig gatewayConfig = loadRadiusGatewayConfig(gateway, auth);
            if (!gatewayConfig.modelIds().isEmpty()
                    && !gatewayConfig.modelIds().contains(effectiveModel)) {
                result.text = "[Radius model is not present in the gateway catalog: "
                        + effectiveModel + "]";
                printStreamingChunk(result.text);
                return result;
            }
            if (gatewayConfig.textOnlyModelIds().contains(effectiveModel)
                    && attachments.stream().anyMatch(AttachmentInput::isImage)) {
                return attachmentFailure("Radius model " + effectiveModel
                        + " accepts text only, so it cannot read the attached image");
            }

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            ObjectNode context = objectMapper.createObjectNode();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                context.put("systemPrompt", systemPrompt);
            }
            context.set("messages", buildPiMessages(userMessage, attachments, toolResults));
            if (toolDefs != null && !toolDefs.isEmpty()) {
                context.set("tools", convertToolDefsToPi(toolDefs));
            }
            request.set("context", context);
            ObjectNode options = objectMapper.createObjectNode();
            options.put("maxTokens", 8192);
            options.put("cacheRetention", config.promptCacheRetention().wireValue());
            String cacheSessionId = activePromptCacheSessionId();
            if (cacheSessionId != null) {
                options.put("sessionId", cacheSessionId);
            }
            request.set("options", options);

            String url = appendPath(gatewayConfig.baseUrl(), "/messages");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body, response.headers())) return result;
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[Radius API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        "Radius HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return result;
            }

            PiMessagesStreamState state = new PiMessagesStreamState();
            parsePiMessagesStream(guardResponseStream(response.body()), result, state);
            if (!state.failed && !result.cancelled) {
                appendPiToolResultHistory(toolResults);
                appendPiMessagesHistory(userMessage, attachments, effectiveModel, result, state);
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }
        return result;
    }

    private RadiusGatewayConfig loadRadiusGatewayConfig(
            String gateway,
            OAuthProviderFlow.RequestAuth auth) throws Exception {
        String normalizedGateway = trimTrailingSlashes(gateway);
        RadiusGatewayConfig cached = radiusGatewayConfig;
        if (cached != null && normalizedGateway.equals(radiusGatewayConfigSource)) {
            return cached;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(appendPath(normalizedGateway, "/v1/config")))
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30));
        applyBearerAuthentication(builder, auth);
        HttpResponse<java.io.InputStream> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401) {
            throw new UnauthorizedRequestException(auth,
                    ProviderResponseFailure.classify(401, ProviderResponseFailure.authenticationBody(response.body())));
        }
        String body = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Could not load Radius config (HTTP "
                    + response.statusCode() + "): " + extractErrorMessage(body));
        }
        JsonNode root = objectMapper.readTree(body);
        String messagesBaseUrl = root.path("baseUrl").asText(null);
        if (messagesBaseUrl == null || messagesBaseUrl.isBlank()) {
            throw new IllegalStateException("Invalid Radius config: missing baseUrl");
        }
        List<String> modelIds = new ArrayList<>();
        Set<String> textOnlyModelIds = new LinkedHashSet<>();
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("Invalid Radius config: models must be an array");
        }
        for (JsonNode model : models) {
            String id = model.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                modelIds.add(id);
                if (declaresTextOnlyInput(model.path("input"))) {
                    textOnlyModelIds.add(id);
                }
            }
        }
        RadiusGatewayConfig loaded = new RadiusGatewayConfig(
                trimTrailingSlashes(messagesBaseUrl), List.copyOf(modelIds), Set.copyOf(textOnlyModelIds));
        radiusGatewayConfigSource = normalizedGateway;
        radiusGatewayConfig = loaded;
        return loaded;
    }

    /** A catalog entry is text-only when it lists its input kinds and "image" is not among them. */
    private static boolean declaresTextOnlyInput(JsonNode input) {
        if (!input.isArray() || input.isEmpty()) {
            return false;
        }
        for (JsonNode kind : input) {
            if ("image".equals(kind.asText())) {
                return false;
            }
        }
        return true;
    }

    private ArrayNode buildPiMessages(
            String userMessage,
            List<AttachmentInput> attachments,
            List<ToolCallResultInput> toolResults) {
        ArrayNode messages = objectMapper.createArrayNode();
        conversationHistory.forEach(messages::add);
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = objectMapper.createObjectNode();
                message.put("role", "toolResult");
                message.put("toolCallId", toolResult.callId);
                message.put("toolName", toolResult.name == null ? "" : toolResult.name);
                ArrayNode content = objectMapper.createArrayNode();
                ObjectNode text = objectMapper.createObjectNode();
                text.put("type", "text");
                text.put("text", toolResult.output == null ? "" : toolResult.output);
                content.add(text);
                message.set("content", content);
                message.put("isError", toolResult.isError);
                message.put("timestamp", System.currentTimeMillis());
                messages.add(message);
            }
        }
        if (userMessage != null || !attachments.isEmpty()) {
            messages.add(createPiUserMessage(userMessage, attachments));
        }
        return MemoryContextProjection.project(messages);
    }

    private void appendPiToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "toolResult");
            message.put("toolCallId", toolResult.callId);
            message.put("toolName", toolResult.name == null ? "" : toolResult.name);
            ArrayNode content = message.putArray("content");
            content.addObject().put("type", "text")
                    .put("text", toolResult.output == null ? "" : toolResult.output);
            message.put("isError", toolResult.isError);
            message.put("timestamp", System.currentTimeMillis());
            conversationHistory.add(message);
        }
    }

    /**
     * A pi-ai user message: string content, or pi-ai image and text parts when the turn
     * carries attachments, which stay in history the way the other HTTP routes keep theirs.
     */
    private ObjectNode createPiUserMessage(String userMessage, List<AttachmentInput> attachments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        if (attachments.isEmpty()) {
            message.put("content", userMessage);
        } else {
            ArrayNode content = message.putArray("content");
            for (AttachmentInput attachment : attachments) {
                if (attachment.isImage()) {
                    content.addObject().put("type", "image")
                            .put("data", attachment.base64Data())
                            .put("mimeType", attachment.mimeType());
                } else {
                    content.addObject().put("type", "text").put("text", attachmentText(attachment));
                }
            }
            if (userMessage != null) {
                content.addObject().put("type", "text").put("text", userMessage);
            }
        }
        message.put("timestamp", System.currentTimeMillis());
        return message;
    }

    private ArrayNode convertToolDefsToPi(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode piTool = objectMapper.createObjectNode();
            piTool.put("name", tool.path("name").asText());
            piTool.put("description", tool.path("description").asText());
            JsonNode parameters = tool.path("inputSchema");
            if (parameters == null || parameters.isMissingNode()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("type", "object");
                empty.set("properties", objectMapper.createObjectNode());
                parameters = empty;
            }
            piTool.set("parameters", parameters);
            tools.add(piTool);
        }
        return tools;
    }

    private void parsePiMessagesStream(
            java.io.InputStream inputStream,
            StreamResult result,
            PiMessagesStreamState state) throws Exception {
        Map<Integer, ResponsesToolCallAccumulator> accumulators = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    return;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode event;
                try {
                    event = objectMapper.readTree(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.path("type").asText("");
                int contentIndex = event.path("contentIndex").asInt(0);
                switch (type) {
                    case "text_start" ->
                            piContentBlock(state, contentIndex, "text", "text");
                    case "text_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        block.put("text", block.path("text").asText("") + delta);
                        if (!delta.isEmpty()) {
                            printStreamingChunk(delta);
                            result.text += delta;
                        }
                    }
                    case "text_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "text", "text");
                        String content = event.path("content").asText(block.path("text").asText(""));
                        block.put("text", content);
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("textSignature", signature);
                        if (result.text.isEmpty() && !content.isEmpty()) {
                            printStreamingChunk(content);
                            result.text = content;
                        }
                    }
                    case "thinking_start" ->
                            piContentBlock(state, contentIndex, "thinking", "thinking");
                    case "thinking_delta" -> {
                        String delta = event.path("delta").asText("");
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking", block.path("thinking").asText("") + delta);
                        if (!delta.isEmpty()) {
                            // Pi reasoning stream — transcript-only.
                            printThinkingChunk(delta);
                        }
                    }
                    case "thinking_end" -> {
                        ObjectNode block = piContentBlock(state, contentIndex, "thinking", "thinking");
                        block.put("thinking",
                                event.path("content").asText(block.path("thinking").asText("")));
                        String signature = event.path("contentSignature").asText(null);
                        if (signature != null) block.put("thinkingSignature", signature);
                        if (event.has("redacted")) block.put("redacted", event.path("redacted").asBoolean());
                    }
                    case "toolcall_start" -> {
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = event.path("id").asText(null);
                        accumulator.name = event.path("toolName").asText(null);
                    }
                    case "toolcall_delta" -> {
                        String argumentsDelta = event.path("delta").asText("");
                        accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator())
                                .arguments.append(argumentsDelta);
                        reportToolInputDelta(argumentsDelta);
                    }
                    case "toolcall_end" -> {
                        JsonNode toolCall = event.path("toolCall");
                        ResponsesToolCallAccumulator accumulator =
                                accumulators.computeIfAbsent(
                                        contentIndex, ignored -> new ResponsesToolCallAccumulator());
                        accumulator.callId = toolCall.path("id").asText(accumulator.callId);
                        accumulator.name = toolCall.path("name").asText(accumulator.name);
                        JsonNode arguments = toolCall.path("arguments");
                        if (!arguments.isMissingNode()) {
                            accumulator.arguments.setLength(0);
                            accumulator.arguments.append(objectMapper.writeValueAsString(arguments));
                        }
                        if (toolCall.isObject()) {
                            ObjectNode block = toolCall.deepCopy();
                            block.put("type", "toolCall");
                            state.contentBlocks.put(contentIndex, block);
                        }
                    }
                    case "done" -> {
                        state.terminal = true;
                        readPiUsage(event.path("usage"), result);
                    }
                    case "error" -> {
                        state.terminal = true;
                        state.failed = true;
                        readPiUsage(event.path("usage"), result);
                        appendProtocolError(
                                result, event.path("errorMessage").asText("Radius request failed"));
                    }
                    default -> {
                        // The start event carries no content.
                    }
                }
            }
        }

        for (Map.Entry<Integer, ResponsesToolCallAccumulator> entry : accumulators.entrySet()) {
            ResponsesToolCallAccumulator accumulator = entry.getValue();
            if (accumulator.name == null || accumulator.name.isBlank()) continue;
            ToolCallOutput toolCall = new ToolCallOutput();
            toolCall.id = accumulator.callId == null || accumulator.callId.isBlank()
                    ? "call_" + result.toolCalls.size()
                    : accumulator.callId;
            toolCall.name = accumulator.name;
            toolCall.arguments = parseToolArguments(accumulator.arguments.toString());
            result.toolCalls.add(toolCall);
            state.contentBlocks.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "toolCall");
                block.put("id", toolCall.id);
                block.put("name", toolCall.name);
                block.set("arguments", toolCall.arguments);
                return block;
            });
        }
        if (!state.terminal && !state.failed && !result.cancelled) {
            state.failed = true;
            appendProtocolError(result, "Radius stream ended without a terminal event");
        }
    }

    private ObjectNode piContentBlock(
            PiMessagesStreamState state,
            int contentIndex,
            String type,
            String valueField) {
        return state.contentBlocks.computeIfAbsent(contentIndex, ignored -> {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", type);
            block.put(valueField, "");
            return block;
        });
    }

    private void readPiUsage(JsonNode usage, StreamResult result) {
        if (usage == null || !usage.isObject()) return;
        // Pi has already normalized ordinary input separately from cache usage.
        result.inputTokens = usageCount(usage, "input", result.inputTokens);
        result.outputTokens = usageCount(usage, "output", result.outputTokens);
        result.cacheReadTokens = usageCount(usage, "cacheRead", result.cacheReadTokens);
        result.cacheCreationTokens = usageCount(usage, "cacheWrite", result.cacheCreationTokens);
    }

    private void appendPiMessagesHistory(
            String userMessage,
            List<AttachmentInput> attachments,
            String effectiveModel,
            StreamResult result,
            PiMessagesStreamState state) {
        if (userMessage != null || !attachments.isEmpty()) {
            conversationHistory.add(createPiUserMessage(userMessage, attachments));
        }
        if (result.text.isEmpty() && result.toolCalls.isEmpty() && state.contentBlocks.isEmpty()) return;

        ObjectNode assistant = objectMapper.createObjectNode();
        assistant.put("role", "assistant");
        ArrayNode content = objectMapper.createArrayNode();
        state.contentBlocks.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.add(entry.getValue()));
        boolean hasText = state.contentBlocks.values().stream()
                .anyMatch(block -> "text".equals(block.path("type").asText()));
        boolean hasToolCall = state.contentBlocks.values().stream()
                .anyMatch(block -> "toolCall".equals(block.path("type").asText()));
        if (!hasText && !result.text.isEmpty()) {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", result.text);
            content.add(text);
        }
        if (!hasToolCall) {
            for (ToolCallOutput toolCall : result.toolCalls) {
                ObjectNode call = objectMapper.createObjectNode();
                call.put("type", "toolCall");
                call.put("id", toolCall.id);
                call.put("name", toolCall.name);
                call.set("arguments", toolCall.arguments);
                content.add(call);
            }
        }
        assistant.set("content", content);
        assistant.put("api", "pi-messages");
        assistant.put("provider", config.getProvider());
        assistant.put("model", effectiveModel);
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input", result.inputTokens);
        usage.put("output", result.outputTokens);
        usage.put("cacheRead", result.cacheReadTokens);
        usage.put("cacheWrite", result.cacheCreationTokens);
        usage.put("totalTokens", result.inputTokens + result.outputTokens
                + result.cacheReadTokens + result.cacheCreationTokens);
        ObjectNode cost = objectMapper.createObjectNode();
        cost.put("input", 0);
        cost.put("output", 0);
        cost.put("cacheRead", 0);
        cost.put("cacheWrite", 0);
        cost.put("total", 0);
        usage.set("cost", cost);
        assistant.set("usage", usage);
        assistant.put("stopReason", result.toolCalls.isEmpty() ? "stop" : "toolUse");
        assistant.put("timestamp", System.currentTimeMillis());
        conversationHistory.add(assistant);
    }

    private void appendProtocolError(StreamResult result, String message) {
        appendProtocolError(result, message, message);
    }

    private void appendProtocolError(
            StreamResult result, String message, String classificationDetail) {
        if (recordKnownProviderFailure(result, 0, classificationDetail)) return;
        result.failed = true;
        String formatted = "[Error: " + message + "]";
        if (isContextOverflowFailure(0, classificationDetail)) {
            appendProviderFailure(result, 0, classificationDetail, formatted, true);
            return;
        }
        // Typed error envelopes can carry the real signal in their type/code rather
        // than the human message, so classify against both.
        String classificationText = classificationDetail == null || classificationDetail.isBlank()
                ? message : message + " " + classificationDetail;
        if (connectivityPolicy.isRetryableFailure(new IOException(classificationText))) {
            result.retryableConnectivityFailure = true;
            result.connectivityFailure = message;
            result.connectivityFinalMessage = formatted;
            return;
        }
        appendProviderFailure(result, 0, classificationDetail, formatted, true);
    }

    private JsonNode parseToolArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String appendPath(String baseUrl, String path) {
        String normalized = trimTrailingSlashes(baseUrl);
        return normalized.endsWith(path) ? normalized : normalized + path;
    }

    private String trimTrailingSlashes(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider base URL is missing");
        }
        return value.trim().replaceAll("/+$", "");
    }

    // ========================================================================
    // Kompile first-party serving subprocess
    // ========================================================================

    /**
     * Send canonical structured chat requests to the private loopback endpoint
     * owned by the first-party {@code ServingSubprocessMain} child.
     */
    private StreamResult streamKompileServing(
            String userMessage,
            String systemPrompt,
            ArrayNode toolDefs,
            List<ToolCallResultInput> toolResults,
            List<AttachmentInput> attachments) {
        StreamResult result = new StreamResult();
        try {
            if (isCancelled()) {
                result.cancelled = true;
                return result;
            }
            long turnImages = attachments.stream().filter(AttachmentInput::isImage).count();
            if (turnImages > StructuredChatLanguageModel.MAX_INLINE_IMAGES_PER_REQUEST) {
                return attachmentFailure("Local serving accepts at most "
                        + StructuredChatLanguageModel.MAX_INLINE_IMAGES_PER_REQUEST
                        + " images per turn; this turn attaches " + turnImages);
            }
            ObjectNode request = objectMapper.createObjectNode();
            ObjectNode structured = request.putObject("request");
            ArrayNode messages = buildKompileServingMessages(
                    userMessage, systemPrompt, toolResults, attachments);
            structured.set("messages", messages);
            ArrayNode tools = buildKompileServingTools(toolDefs);
            structured.set("tools", tools);
            structured.put("addGenerationPrompt", true);
            structured.put("toolDefinitionFormat", "STANDARD");
            structured.put("toolCallFormat", "NATIVE");
            structured.put("toolChoice", tools.isEmpty() ? "NONE" : "AUTO");
            request.put("maxTokens", wireMaxTokens(
                    wireMaxOutputTokens > 0 ? wireMaxOutputTokens : KOMPILE_SERVING_MAX_TOKENS,
                    contextWindowTokens, estimateRequestInputTokens(messages, tools)));

            HttpResponse<String> response = sendKompileServing(request);

            if (response.statusCode() != 200) {
                String responseBody = response.body();
                String error = extractErrorMessage(responseBody)
                        + ProviderResponseFailure.diagnostics(responseBody, response.headers());
                String detail = "Kompile serving HTTP " + response.statusCode() + ": " + error;
                if (isContextOverflowFailure(response.statusCode(), responseBody)) {
                    appendProviderFailure(
                            result, response.statusCode(), responseBody,
                            "[Error: " + detail + "]", true);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(), detail,
                        "[Error: " + detail + "]")) {
                    return result;
                }
                appendProtocolError(result, detail);
                return result;
            }

            JsonNode payload = objectMapper.readTree(response.body());
            String finishReason = payload.path("finishReason").asText("");
            if (finishReason.startsWith("error")) {
                appendProtocolError(result, finishReason);
                return result;
            }

            String rawText = payload.path("rawText").asText("");
            result.text = payload.path("content").asText("");
            JsonNode encodedCalls = payload.path("toolCalls");
            if (encodedCalls.isArray()) {
                for (JsonNode call : encodedCalls) {
                    String name = call.path("name").asText("");
                    if (name.isBlank()) continue;
                    ToolCallOutput output = new ToolCallOutput();
                    output.id = call.path("id").asText("");
                    if (output.id.isBlank()) {
                        output.id = "call_" + result.toolCalls.size();
                    }
                    output.name = name;
                    JsonNode arguments = call.path("arguments");
                    output.arguments = arguments.isObject()
                            ? arguments.deepCopy() : objectMapper.createObjectNode();
                    result.toolCalls.add(output);
                }
            }

            if (result.text.isBlank() && result.toolCalls.isEmpty() && !rawText.isBlank()) {
                result.text = rawText;
            }
            // Rescue last: only fires when the serving child returned no native
            // tool calls and the text echoed the replayed "[Tool call ...]" shape.
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));
            if (!result.text.isEmpty() && !isCancelled()) {
                printStreamingChunk(result.text);
            } else if (isCancelled()) {
                result.cancelled = true;
            }

            if (!result.cancelled && !result.failed) {
                appendKompileToolResultHistory(toolResults);
                if (userMessage != null || !attachments.isEmpty()) {
                    conversationHistory.add(kompileServingUserMessage(userMessage, attachments));
                }
                boolean rescued = !result.toolCalls.isEmpty()
                        && !payload.path("content").asText("").equals(result.text);
                if (!rawText.isBlank() || !result.text.isBlank() || !result.toolCalls.isEmpty()) {
                    ObjectNode assistant = objectMapper.createObjectNode();
                    assistant.put("role", "assistant");
                    // After a rescue, record the stripped text so the echoed
                    // "[Tool call ...]" shape is not replayed back to the model.
                    assistant.put("content", rescued || rawText.isBlank()
                            ? result.text : rawText);
                    if (!result.toolCalls.isEmpty()) {
                        ArrayNode calls = assistant.putArray("tool_calls");
                        for (ToolCallOutput call : result.toolCalls) {
                            ObjectNode encoded = calls.addObject();
                            encoded.put("id", call.id);
                            encoded.put("name", call.name);
                            encoded.set("arguments", call.arguments);
                        }
                    }
                    conversationHistory.add(assistant);
                }
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Kompile serving error: ");
            if (!result.retryableConnectivityFailure && !result.cancelled) {
                printStreamingChunk(result.text);
            }
        }
        return result;
    }

    private HttpResponse<String> sendKompileServing(ObjectNode request) throws Exception {
        LocalServingRuntimePool.Binding binding = config.getLocalServingBinding();
        if (binding == null) {
            // Explicit, caller-owned endpoints retain their existing lifecycle.
            return sendKompileServing(config.getBaseUrl(), request);
        }
        try (LocalServingRuntimePool.Lease runtime = binding.acquire()) {
            String liveUrl = runtime.baseUrl().toString();
            if (!liveUrl.equals(config.getBaseUrl())) {
                // An idle-evicted child restarts on a new port. Point the config at it so
                // the model-limits and image-support probe reads the running child. Only
                // the URL changes: the model and the binding stay as they are.
                config.setBaseUrl(liveUrl);
            }
            synchronized (runtime.coordinationLock()) {
                return sendKompileServing(liveUrl, request);
            }
        }
    }

    private HttpResponse<String> sendKompileServing(String baseUrl, ObjectNode request)
            throws IOException, InterruptedException {
        // Startup or another request may have occupied the runtime since the initial check.
        if (isCancelled()) throw new InterruptedException("Local serving request cancelled");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Kompile serving subprocess endpoint was not prepared");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(appendPath(baseUrl, "/api/llm/chat")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(connectivityPolicy.requestTimeout())
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The serving request's messages. Images ride on the message that attached them;
     * the current turn's images always do, and earlier ones fill the rest of the
     * per-request image limit newest first, the oldest becoming a text marker.
     */
    private ArrayNode buildKompileServingMessages(
            String userMessage,
            String systemPrompt,
            List<ToolCallResultInput> toolResults,
            List<AttachmentInput> attachments) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
        }
        ArrayNode turnImages = kompileServingImages(attachments);
        int historyImages = 0;
        for (ObjectNode historyMessage : conversationHistory) {
            historyImages += historyMessage.path("images").size();
        }
        int omit = Math.max(0, historyImages - Math.max(0,
                StructuredChatLanguageModel.MAX_INLINE_IMAGES_PER_REQUEST - turnImages.size()));
        for (ObjectNode historyMessage : conversationHistory) {
            ObjectNode message = messages.addObject();
            message.put("role", historyMessage.path("role").asText("user"));
            StringBuilder omitted = new StringBuilder();
            ArrayNode images = objectMapper.createArrayNode();
            for (JsonNode image : historyMessage.path("images")) {
                if (omit > 0) {
                    omit--;
                    omitted.append("[Earlier image omitted: ")
                            .append(image.path("path").asText("image")).append("]\n");
                } else {
                    images.addObject()
                            .put("mimeType", image.path("mimeType").asText())
                            .put("base64Data", image.path("base64Data").asText());
                }
            }
            message.put("content", omitted + historyMessage.path("content").asText(""));
            if (!images.isEmpty()) message.set("images", images);
        }
        if (toolResults != null) {
            for (ToolCallResultInput toolResult : toolResults) {
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("content", toolResult.output);
            }
        }
        if (userMessage != null || !attachments.isEmpty()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", kompileServingUserText(userMessage, attachments));
            if (!turnImages.isEmpty()) user.set("images", turnImages);
        }
        return messages;
    }

    /**
     * A user turn as local-serving history keeps it. The images stay, with their paths,
     * so later turns can still see them; buildKompileServingMessages bounds how many ride.
     */
    private ObjectNode kompileServingUserMessage(String userMessage, List<AttachmentInput> attachments) {
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.put("content", kompileServingUserText(userMessage, attachments));
        ArrayNode images = user.putArray("images");
        for (AttachmentInput attachment : attachments) {
            if (attachment.isImage()) {
                images.addObject()
                        .put("mimeType", attachment.mimeType())
                        .put("base64Data", attachment.base64Data())
                        .put("path", attachment.path());
            }
        }
        if (images.isEmpty()) user.remove("images");
        return user;
    }

    /** A turn's text for local serving: text attachments ahead of the message. */
    private static String kompileServingUserText(String userMessage, List<AttachmentInput> attachments) {
        StringBuilder text = new StringBuilder();
        for (AttachmentInput attachment : attachments) {
            if (!attachment.isImage()) text.append(attachmentText(attachment)).append("\n\n");
        }
        return text.append(userMessage == null ? "" : userMessage).toString();
    }

    /** A turn's images in the serving wire shape ({@code StructuredChatLanguageModel.InlineImage}). */
    private ArrayNode kompileServingImages(List<AttachmentInput> attachments) {
        ArrayNode images = objectMapper.createArrayNode();
        for (AttachmentInput attachment : attachments) {
            if (attachment.isImage()) {
                images.addObject()
                        .put("mimeType", attachment.mimeType())
                        .put("base64Data", attachment.base64Data());
            }
        }
        return images;
    }

    private void appendKompileToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode history = objectMapper.createObjectNode();
            history.put("role", "tool");
            history.put("content", toolResult.output == null ? "" : toolResult.output);
            if (toolResult.callId != null) history.put("tool_call_id", toolResult.callId);
            if (toolResult.name != null) history.put("name", toolResult.name);
            conversationHistory.add(history);
        }
    }

    private ArrayNode buildKompileServingTools(ArrayNode toolDefs) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (toolDefs != null) {
            for (JsonNode toolDef : toolDefs) {
                String name = toolDef.path("name").asText("");
                if (name.isBlank()) continue;
                ObjectNode tool = tools.addObject();
                tool.put("name", name);
                tool.put("description", toolDef.path("description").asText(""));
                JsonNode parameters = toolDef.path("inputSchema");
                tool.set("parameters", parameters.isObject()
                        ? parameters.deepCopy()
                        : objectMapper.createObjectNode());
            }
        }
        return tools;
    }

    // ========================================================================
    // OpenAI-compatible Chat Completions
    // ========================================================================

    private StreamResult streamOpenAi(String userMessage, String systemPrompt,
                                       ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                       String effectiveModel,
                                       List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();

        try {
            ArrayNode messages = buildOpenAiMessages(
                    userMessage, systemPrompt, toolResults, attachments);

            ObjectNode request = buildOpenAiWireRequest(
                    effectiveModel, messages, toolDefs);

            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            String baseUrl = config.resolveBaseUrl(auth);
            String url = appendPath(baseUrl, "/chat/completions");

            for (int pass = 0; ; pass++) {
                StreamResult passResult = (pass == 0)
                        ? result : new StreamResult();
                try {
                    openAiStreamOnce(url, request, auth, userMessage, toolDefs, passResult);
                } finally {
                    if (pass > 0) {
                        // Preserve both requests' billed usage even if parsing the
                        // continuation throws after receiving a usage snapshot.
                        result.text += passResult.text;
                        result.inputTokens += passResult.inputTokens;
                        result.outputTokens += passResult.outputTokens;
                        result.cacheReadTokens += passResult.cacheReadTokens;
                        result.cacheCreationTokens += passResult.cacheCreationTokens;
                        result.toolCalls.addAll(passResult.toolCalls);
                        result.refusalDetected |= passResult.refusalDetected;
                        result.truncatedDetected |= passResult.truncatedDetected;
                        // Context is the resumed request alone, never their sum.
                        // Zero means its usage was not reported, not the prior input.
                        result.lastRequestInputTokens = passResult.contextInputTokens();
                    }
                }
                if (!isSilentStreamDrop(passResult, toolDefs) || pass >= 1) {
                    if (pass > 0) {
                        result.failed = passResult.failed;
                        result.failureKind = passResult.failureKind;
                        result.failureStatusCode = passResult.failureStatusCode;
                        result.failureMessage = passResult.failureMessage;
                        result.cancelled |= passResult.cancelled;
                        if (passResult.silentStreamDrop) {
                            // Even the continuation died without a terminal event;
                            // surface it instead of presenting a silent cut as done.
                            result.failed = true;
                            result.failureKind = FailureKind.PROVIDER_ERROR;
                            result.failureMessage = "[Provider stream dropped again without "
                                    + "a terminal event after the continuation attempt]";
                        }
                    }
                    commitOpenAiTurnHistory(result, toolResults, userMessage, attachments);
                    return result;
                }
                // The provider silently dropped a thinking stream (observed on the
                // Z.AI coding endpoint: generation stops mid-reasoning with no
                // finish_reason and no [DONE], keepalives still flowing). Ask it to
                // continue from where the visible answer stopped.
                emitConnectivityEvent(new ConnectivityEvent(
                        config.getProvider(), 1, 1, Duration.ZERO,
                        "provider dropped the stream mid-generation without a terminal "
                                + "event; asking the model to continue"));
                JsonNode existingMessages = request.get("messages");
                if (existingMessages instanceof ArrayNode messageArray) {
                    ObjectNode continueMsg = messageArray.addObject();
                    continueMsg.put("role", "user");
                    continueMsg.put("content",
                            "Your previous response was cut off before you finished. "
                                    + "Continue exactly where you stopped without repeating "
                                    + "any earlier text. If you had already finished, reply DONE.");
                }
            }
        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }

        return result;
    }

    /** Build the shared OpenAI chat-completions wire request body. */
    private ObjectNode buildOpenAiWireRequest(String effectiveModel, ArrayNode messages,
                                              ArrayNode toolDefs) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", effectiveModel);
        request.set("messages", messages);
        request.put("stream", true);
        applyReasoningEffort(request, false);
        applyOpenAiFastMode(request, effectiveModel);
        applyOpenAiCompatiblePromptCacheControls(request, effectiveModel);
        applyChatCompletionsJsonOutput(request);
        boolean openAi = "openai".equals(config.getProvider());
        if (wireMaxOutputTokens > 0 && (openAi || acceptsMaxTokens(effectiveModel))) {
            // API-key OpenAI uses Chat Completions, not the subscription Responses
            // route. Select the supported field by provider, never a model prefix:
            // aliases and future model families must not fall back to max_tokens.
            request.put(openAi ? "max_completion_tokens" : "max_tokens", wireMaxTokens(
                    wireMaxOutputTokens, contextWindowTokens,
                    estimateRequestInputTokens(messages, toolDefs)));
        }

        // Request token usage in streamed response
        ObjectNode streamOptions = objectMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        request.set("stream_options", streamOptions);

        if (toolDefs != null && toolDefs.size() > 0) {
            ArrayNode openAiTools = convertToolDefsToOpenAi(toolDefs);
            if (openAiTools.size() > 0) {
                request.set("tools", openAiTools);
            }
        }
        return request;
    }

    /**
     * True when the stream died without any terminal event and there is something
     * worth continuing: visible text already streamed, no tool calls mid-flight,
     * and no provider-stated reason (refusal/length). Tool-bearing turns are never
     * resumed here — a half-streamed tool_call envelope cannot be continued safely
     * by re-asking, and the agentic loop owns its own retry surface.
     */
    private static boolean isSilentStreamDrop(StreamResult result, ArrayNode toolDefs) {
        if (result.cancelled || result.failed) return false;
        if (toolDefs != null && toolDefs.size() > 0) return false;
        if (!result.toolCalls.isEmpty()) return false;
        if (result.silentStreamDrop) return true;
        // Terminal event seen (or parser state inconclusive with text) — no drop.
        return !result.terminalEventSeen;
    }

    /** One HTTP pass of the OpenAI-compatible stream (send, parse, history commit). */
    private void openAiStreamOnce(String url, ObjectNode request,
                                  OAuthProviderFlow.RequestAuth auth, String userMessage,
                                  ArrayNode toolDefs, StreamResult result) throws Exception {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(connectivityPolicy.requestTimeout());
            applyBearerAuthentication(requestBuilder, auth);
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body, response.headers())) return;
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[LLM API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, true);
                    return;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        ChatProviderRegistry.label(config.getProvider()) + " HTTP "
                                + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, true);
                return;
            }

            parseOpenAiStream(guardResponseStream(response.body()), result);
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));

            // A stream that died with no terminal event and nothing streamed is a
            // transport failure (route-independent); with text streamed it is the
            // silent-drop signature and the caller's resume logic decides.
            if (result.silentStreamDrop && result.text.isEmpty() && result.toolCalls.isEmpty()) {
                throw new EOFException("OpenAI stream ended before a terminal event");
            }

            if (!result.cancelled && (!result.failed || isGenerationStopped(result))) {
                appendOpenAiToolResultHistory(null);
            }
    }

    /**
     * Commit one finished chat-completions turn to the replayable history:
     * submitted tool results, then the user message, then the assistant answer.
     * A rejected/dropped turn must not poison later requests.
     */
    private void commitOpenAiTurnHistory(StreamResult result, List<ToolCallResultInput> toolResults,
                                         String userMessage, List<AttachmentInput> attachments) {
        if (result.cancelled || (result.failed && !isGenerationStopped(result))) return;
        appendOpenAiToolResultHistory(toolResults);
        if (result.failed) return;
        if (userMessage != null || !attachments.isEmpty()) {
            conversationHistory.add(openAiUserMessage(userMessage, attachments));
        }
        if (result.text.isEmpty() && result.toolCalls.isEmpty()) return;
        ObjectNode assistantMsg = objectMapper.createObjectNode();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", result.text);
        if (!result.toolCalls.isEmpty()) {
            ArrayNode toolCallsArray = objectMapper.createArrayNode();
            for (ToolCallOutput tc : result.toolCalls) {
                ObjectNode tcNode = objectMapper.createObjectNode();
                tcNode.put("id", tc.id);
                tcNode.put("type", "function");
                ObjectNode fn = objectMapper.createObjectNode();
                fn.put("name", tc.name);
                fn.put("arguments", tc.arguments != null ? tc.arguments.toString() : "{}");
                tcNode.set("function", fn);
                toolCallsArray.add(tcNode);
            }
            assistantMsg.set("tool_calls", toolCallsArray);
        }
        conversationHistory.add(assistantMsg);
    }

    private ArrayNode buildOpenAiMessages(String userMessage, String systemPrompt,
                                           List<ToolCallResultInput> toolResults) {
        return buildOpenAiMessages(userMessage, systemPrompt, toolResults, List.of());
    }

    private ArrayNode buildOpenAiMessages(String userMessage, String systemPrompt,
                                           List<ToolCallResultInput> toolResults,
                                           List<AttachmentInput> attachments) {
        ArrayNode messages = objectMapper.createArrayNode();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }

        // Previous conversation history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results from previous tool calls
        if (toolResults != null && !toolResults.isEmpty()) {
            for (ToolCallResultInput tr : toolResults) {
                ObjectNode toolMsg = objectMapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tr.callId);
                toolMsg.put("content", tr.output);
                messages.add(toolMsg);
            }
        }

        // Current user message
        if (userMessage != null || !attachments.isEmpty()) {
            messages.add(openAiUserMessage(userMessage, attachments));
        }

        ArrayNode projected = MemoryContextProjection.project(messages);
        return "zai".equalsIgnoreCase(config.getProvider())
                ? normalizeZaiMessages(projected) : projected;
    }

    private ArrayNode normalizeZaiMessages(ArrayNode messages) {
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            boolean empty = content.isMissingNode() || content.isNull()
                    || (content.isTextual() && content.asText().isBlank())
                    || (content.isArray() && content.isEmpty());
            if (!empty) {
                normalized.add(message);
                continue;
            }
            String role = message.path("role").asText();
            if ("assistant".equals(role) && message.path("tool_calls").isArray()
                    && !message.path("tool_calls").isEmpty()) {
                // Z.AI represents tool-only assistant content as null, not "".
                // Copy at the wire boundary so replay/checkpoint history is untouched.
                ObjectNode toolCall = ((ObjectNode) message).deepCopy();
                toolCall.putNull("content");
                normalized.add(toolCall);
            } else if ("tool".equals(role)) {
                // Keep the call/result pair even when the tool produced no text.
                ObjectNode toolResult = ((ObjectNode) message).deepCopy();
                toolResult.put("content", "[Tool completed with no output.]");
                normalized.add(toolResult);
            }
            // Empty plain-text turns carry no context and fail Z.AI validation.
        }
        return normalized;
    }

    private void appendOpenAiToolResultHistory(List<ToolCallResultInput> toolResults) {
        if (toolResults == null) return;
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode history = objectMapper.createObjectNode();
            history.put("role", "tool");
            history.put("content", toolResult.output);
            if (toolResult.callId != null) history.put("tool_call_id", toolResult.callId);
            if (toolResult.name != null) history.put("name", toolResult.name);
            conversationHistory.add(history);
        }
    }

    private ArrayNode convertToolDefsToOpenAi(ArrayNode toolDefs) {
        ArrayNode openAiTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode oaiTool = objectMapper.createObjectNode();
            oaiTool.put("type", "function");

            ObjectNode fn = objectMapper.createObjectNode();
            fn.put("name", tool.path("name").asText());
            fn.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if ("openai".equalsIgnoreCase(config.getProvider())) {
                // Chat Completions rejects root composition; the subscription
                // Responses route (like the Codex client) keeps the MCP schema.
                fn.set("parameters", OpenAiToolSchema.parameters(params));
                fn.put("strict", false);
            } else if (params != null && !params.isMissingNode()) {
                fn.set("parameters", params);
            } else {
                ObjectNode emptyParams = objectMapper.createObjectNode();
                emptyParams.put("type", "object");
                emptyParams.set("properties", objectMapper.createObjectNode());
                fn.set("parameters", emptyParams);
            }

            oaiTool.set("function", fn);
            openAiTools.add(oaiTool);
        }
        return openAiTools;
    }

    /**
     * Recover structured tool calls that a model emitted as plain text in the
     * replayed "[Tool call <name> <id>] {json}" shape. Text-form imitation
     * leaves result.toolCalls empty, so the agentic loop ends the turn silently
     * with no visible work done. The rescue only fires when the text parses,
     * the tool name is actually offered in the current tool list, and no real
     * structured calls arrived — and it strips the echoed text from the
     * response so it is not re-recorded as assistant prose (which would teach
     * the model to keep imitating the shape).
     *
     * @return rescued calls, or an empty list when nothing qualifies
     */
    List<ToolCallOutput> rescueTextEncodedToolCalls(StreamResult result, ArrayNode toolDefs) {
        List<ToolCallOutput> rescued = new ArrayList<>();
        if (result == null || result.failed || result.cancelled || !result.toolCalls.isEmpty()
                || result.text == null || result.text.isBlank()
                || toolDefs == null || toolDefs.isEmpty()) {
            return rescued;
        }
        Set<String> offeredTools = new LinkedHashSet<>();
        for (JsonNode toolDef : toolDefs) {
            String name = toolDef.path("name").asText("");
            if (!name.isBlank()) offeredTools.add(name);
        }
        if (offeredTools.isEmpty()) return rescued;

        String text = result.text;
        int searchFrom = 0;
        int firstRescueStart = -1;
        while (true) {
            int lineStart = text.indexOf("[Tool call ", searchFrom);
            if (lineStart < 0) break;
            int idStart = lineStart + "[Tool call ".length();
            int space = text.indexOf(' ', idStart);
            int bracketEnd = text.indexOf(']', idStart);
            if (space < 0 || bracketEnd < 0 || space >= bracketEnd) break;
            String toolName = text.substring(idStart, space).trim();
            String callId = text.substring(space + 1, bracketEnd).trim();
            if (toolName.isEmpty() || !offeredTools.contains(toolName)) {
                searchFrom = idStart;
                continue;
            }
            int braceStart = text.indexOf('{', bracketEnd);
            if (braceStart < 0) break;
            int braceEnd = matchingJsonEnd(text, braceStart);
            if (braceEnd < 0) break;
            try {
                JsonNode arguments = objectMapper.readTree(text.substring(braceStart, braceEnd));
                ToolCallOutput call = new ToolCallOutput();
                call.id = callId.isEmpty() ? "call_" + rescued.size() : callId;
                call.name = toolName;
                call.arguments = arguments;
                rescued.add(call);
                if (firstRescueStart < 0) firstRescueStart = lineStart;
            } catch (Exception ignored) {
                // Not a parseable tool call — leave the text alone.
            }
            searchFrom = braceEnd;
        }
        if (!rescued.isEmpty() && firstRescueStart >= 0) {
            // Keep any genuine prose before the first echoed call; drop the echo.
            result.text = text.substring(0, firstRescueStart).stripTrailing();
        }
        return rescued;
    }

    /** Index just past the JSON object starting at {@code openBrace}, or -1. */
    private static int matchingJsonEnd(String text, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private void parseOpenAiStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        // Track tool calls being assembled from deltas
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        boolean terminal = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    terminal = true;
                    result.terminalEventSeen = true;
                    break;
                }

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode errorNode = chunk.get("error");
                    if (errorNode != null && !errorNode.isNull()) {
                        // OpenRouter-style upstream wraps carry the routed provider
                        // and the real failure in metadata; surface them in both the
                        // retry event reason and the terminal message.
                        appendProtocolError(
                                result,
                                errorNode.path("message").asText("Unknown provider error")
                                        + ProviderResponseFailure.upstreamDetail(errorNode),
                                errorNode.toString());
                        terminal = true;
                        break;
                    }
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    String refusal = delta.path("refusal").asText("");
                    if (!refusal.isEmpty()) {
                        result.refusalDetected = true;
                        printStreamingChunk(refusal);
                        result.text += refusal;
                    }

                    // Text content
                    String content = delta.path("content").asText(null);
                    if (content != null) {
                        printStreamingChunk(content);
                        result.text += content;
                    }

                    // Reasoning content (DeepSeek/OpenAI-compatible reasoning models)
                    String reasoningContent = delta.path("reasoning_content").asText(null);
                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        printThinkingChunk(reasoningContent);
                    }

                    // Tool calls (streamed as deltas)
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tcDelta : toolCallsNode) {
                            int index = tcDelta.path("index").asInt(0);
                            while (toolAccumulators.size() <= index) {
                                toolAccumulators.add(new ToolCallAccumulator());
                            }
                            ToolCallAccumulator acc = toolAccumulators.get(index);

                            String id = tcDelta.path("id").asText(null);
                            if (id != null) acc.id = id;

                            String name = tcDelta.path("function").path("name").asText(null);
                            if (name != null) acc.name = name;

                            String args = tcDelta.path("function").path("arguments").asText(null);
                            if (args != null) {
                                acc.arguments.append(args);
                                reportToolInputDelta(args);
                            }
                        }
                    }

                    // Extract token usage if present (final chunk in OpenAI streaming)
                    JsonNode usageNode = chunk.path("usage");
                    if (!usageNode.isObject() || usageNode.isEmpty()) {
                        // Groq's original streaming dialect nests the same totals.
                        usageNode = chunk.path("x_groq").path("usage");
                    }
                    readOpenAiCompatibleUsage(usageNode, result);
                    if ("xai".equals(config.getProvider())
                            && usageNode.hasNonNull("prompt_tokens")
                            && usageNode.hasNonNull("completion_tokens")
                            && usageNode.hasNonNull("total_tokens")) {
                        // xAI's legacy chat dialect can exclude reasoning from
                        // completion_tokens. Only add it when the wire total proves
                        // it is separate; inclusive responses must not count it twice.
                        long reasoning = usageCount(usageNode.path("completion_tokens_details"),
                                "reasoning_tokens", 0L);
                        long uncounted = usageCount(usageNode, "total_tokens", 0L)
                                - usageCount(usageNode, "prompt_tokens", 0L) - result.outputTokens;
                        if (reasoning > 0L && uncounted == reasoning) {
                            result.outputTokens += reasoning;
                        }
                    }

                    // Check for finish_reason
                    String finishReason = chunk.path("choices").path(0).path("finish_reason").asText(null);
                    if (finishReason != null && !finishReason.isBlank()) {
                        terminal = true;
                        result.terminalEventSeen = true;
                    }
                    result.refusalDetected |= "content_filter".equals(finishReason);
                    result.truncatedDetected |= "length".equals(finishReason);
                    // Even a refusal/length cutoff can be followed by the final
                    // usage-only chunk. Drain through [DONE] before returning.
                    if (terminal && finishGenerationOutcome(result)) continue;
                    if ("tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
                        // Finalize any accumulated tool calls
                        for (ToolCallAccumulator acc : toolAccumulators) {
                            if (acc.name != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                                tc.name = acc.name;
                                try {
                                    tc.arguments = objectMapper.readTree(acc.arguments.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable chunks
                }
            }
        }

        if (!result.cancelled && !result.failed && !terminal) {
            // A text-bearing stream that dies without a terminal event is the
            // silent-drop signature (Z.AI coding endpoint) — leave it unflagged so
            // the route's resume logic can decide; routes without a resume path
            // surface it through streamOpenAi's post-parse check below.
            if (result.text.isEmpty() && result.toolCalls.isEmpty()) {
                throw new EOFException("OpenAI stream ended before a terminal event");
            }
            result.silentStreamDrop = true;
            return;
        }

        if (finishGenerationOutcome(result)) return;

        // If tool calls were accumulated but finish_reason wasn't caught
        if (result.toolCalls.isEmpty() && !toolAccumulators.isEmpty()) {
            for (ToolCallAccumulator acc : toolAccumulators) {
                if (acc.name != null) {
                    ToolCallOutput tc = new ToolCallOutput();
                    tc.id = acc.id != null ? acc.id : "call_" + result.toolCalls.size();
                    tc.name = acc.name;
                    try {
                        tc.arguments = objectMapper.readTree(acc.arguments.toString());
                    } catch (Exception e) {
                        tc.arguments = objectMapper.createObjectNode();
                    }
                    result.toolCalls.add(tc);
                }
            }
        }
    }

    // ========================================================================
    // Anthropic Messages API
    // ========================================================================

    private StreamResult streamAnthropic(String userMessage, String systemPrompt,
                                          ArrayNode toolDefs, List<ToolCallResultInput> toolResults,
                                          String effectiveModel,
                                          List<AttachmentInput> attachments, OAuthProviderFlow.RequestAuth retryAuth) {
        StreamResult result = new StreamResult();

        try {
            OAuthProviderFlow.RequestAuth auth = retryAuth != null ? retryAuth : config.resolveRequestAuth();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", effectiveModel);
            request.put("max_tokens", 8192);
            request.put("stream", true);
            boolean fastMode = config.useFastMode(effectiveModel);
            if (fastMode) request.put("speed", "fast");

            String nativeRouteKey = "anthropic:" + effectiveModel;
            boolean nativeCompaction = compactionCapabilities(effectiveModel).nativeCompaction()
                    == ProviderCompactionCapabilities.NativeCompaction.ANTHROPIC_MESSAGES
                    && nativeCompactionTriggerTokens >= 50_000
                    && !unavailableNativeCompactionRoutes.contains(nativeRouteKey);
            if (nativeCompaction) {
                ObjectNode edit = objectMapper.createObjectNode();
                edit.put("type", "compact_20260112");
                edit.putObject("trigger")
                        .put("type", "input_tokens")
                        .put("value", nativeCompactionTriggerTokens);
                edit.put("instructions",
                        "Summarize the conversation for continuing the task. Preserve code, "
                                + "file paths, decisions, pending work, and tool outcomes. "
                                + "Do not call tools while compacting; return summary text only.");
                request.putObject("context_management").putArray("edits").add(edit);
            }

            String effectiveSystem = effectiveAnthropicSystemPrompt(auth, systemPrompt);
            if (!effectiveSystem.isBlank()) request.put("system", effectiveSystem);

            ArrayNode messages = buildAnthropicMessages(userMessage, toolResults, attachments);
            request.set("messages", messages);

            if (toolDefs != null && toolDefs.size() > 0) {
                ArrayNode anthropicTools = convertToolDefsToAnthropic(toolDefs);
                if (anthropicTools.size() > 0) {
                    request.set("tools", anthropicTools);
                }
            }
            applyAnthropicPromptCacheControl(request);

            String baseUrl = config.resolveBaseUrl(auth);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .timeout(connectivityPolicy.requestTimeout());
            applyAnthropicAuthentication(requestBuilder, auth, config.getProvider(),
                    "github-copilot".equals(config.getProvider()));
            if (nativeCompaction || fastMode) {
                String beta = nativeCompaction ? "compact-2026-01-12" : "";
                if (fastMode) beta += (beta.isEmpty() ? "" : ",") + "fast-mode-2026-02-01";
                requestBuilder.setHeader("anthropic-beta",
                        mergeHeaderValue(auth, "anthropic-beta", beta));
            }
            applyProviderRequestHeaders(requestBuilder, userMessage);
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                if (response.statusCode() == 401) {
                    recordUnauthorizedResponse(result, response.body(), auth);
                    return result;
                }
                String body = readResponseBody(response.body());
                if (recordKnownProviderFailure(result, response.statusCode(), body, response.headers())) return result;
                if (nativeCompaction && (response.statusCode() == 400
                        || response.statusCode() == 404 || response.statusCode() == 422)
                        && !isContextOverflowFailure(response.statusCode(), body)) {
                    unavailableNativeCompactionRoutes.add(nativeRouteKey);
                    return streamAnthropic(
                            userMessage, systemPrompt, toolDefs, toolResults,
                            effectiveModel, attachments, auth);
                }
                String error = extractErrorMessage(body) + ProviderResponseFailure.diagnostics(body, response.headers());
                String finalMessage = "[Anthropic API error " + response.statusCode()
                        + ": " + error + "]";
                if (isContextOverflowFailure(response.statusCode(), body)) {
                    appendProviderFailure(
                            result, response.statusCode(), body, finalMessage, false);
                    return result;
                }
                if (recordHttpConnectivityFailure(
                        result, response.statusCode(), response.headers(),
                        "Anthropic HTTP " + response.statusCode() + ": " + error,
                        finalMessage)) {
                    return result;
                }
                appendProviderFailure(
                        result, response.statusCode(), body, finalMessage, false);
                return result;
            }

            parseAnthropicStream(guardResponseStream(response.body()), result);
            result.toolCalls.addAll(rescueTextEncodedToolCalls(result, toolDefs));

            if (!result.cancelled && !result.failed && result.nativeCompactionSummary != null
                    && !result.nativeCompactionSummary.isBlank()) {
                // Anthropic ignores all messages before the latest compaction
                // block; remove them locally so the next HTTP request is small too.
                conversationHistory.clear();
            } else if (!result.cancelled && (!result.failed || isGenerationStopped(result))) {
                ObjectNode toolResultMessage = createAnthropicToolResultMessage(toolResults);
                if (toolResultMessage != null) conversationHistory.add(toolResultMessage);
            }

            // Track in conversation history only after a completed stream.
            if (!result.cancelled && !result.failed && result.nativeCompactionSummary == null
                    && (userMessage != null || !attachments.isEmpty())) {
                conversationHistory.add(anthropicUserMessage(userMessage, attachments));
            }

            if (!result.cancelled && !result.failed
                    && (!result.text.isEmpty() || !result.toolCalls.isEmpty()
                    || (result.nativeCompactionSummary != null
                    && !result.nativeCompactionSummary.isBlank()))) {
                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                ArrayNode content = objectMapper.createArrayNode();
                if (result.nativeCompactionSummary != null
                        && !result.nativeCompactionSummary.isBlank()) {
                    ObjectNode compactionBlock = objectMapper.createObjectNode();
                    compactionBlock.put("type", "compaction");
                    compactionBlock.put("content", result.nativeCompactionSummary);
                    content.add(compactionBlock);
                }
                if (!result.text.isEmpty()) {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", result.text);
                    content.add(textBlock);
                }
                for (ToolCallOutput tc : result.toolCalls) {
                    ObjectNode toolUseBlock = objectMapper.createObjectNode();
                    toolUseBlock.put("type", "tool_use");
                    toolUseBlock.put("id", tc.id);
                    toolUseBlock.put("name", tc.name);
                    toolUseBlock.set("input", tc.arguments);
                    content.add(toolUseBlock);
                }
                assistantMsg.set("content", content);
                conversationHistory.add(assistantMsg);
            }

        } catch (Exception e) {
            recordStreamFailure(result, e, "[Error: ");
        }

        return result;
    }

    private String effectiveAnthropicSystemPrompt(
            OAuthProviderFlow.RequestAuth auth, String systemPrompt) {
        boolean anthropicOAuth = auth != null && auth.oauth()
                && "anthropic".equalsIgnoreCase(config.getProvider());
        if (!anthropicOAuth) return systemPrompt == null ? "" : systemPrompt;
        String identity = "You are Claude Code, Anthropic's official CLI for Claude.";
        return systemPrompt == null || systemPrompt.isBlank()
                ? identity : identity + "\n\n" + systemPrompt;
    }

    private static void applyHeaders(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth) {
        if (auth != null) {
            auth.headers().forEach(builder::setHeader);
        }
    }

    private static void applyBearerAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth) {
        applyTokenAuthentication(builder, auth, "Authorization", "Bearer ");
        applyHeaders(builder, auth);
    }

    private static void applyAnthropicAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            String provider,
            boolean bearerFallback) {
        // Never send a Messages request without a credential: the API would
        // answer with a bare 401 instead of naming the route that has none.
        if (auth == null) {
            throw ChatConfig.missingCredential(provider);
        }
        if (!hasHeader(auth.headers(), "Authorization")
                && !hasHeader(auth.headers(), "x-api-key")) {
            applyTokenAuthentication(builder, auth,
                    bearerFallback ? "Authorization" : "x-api-key",
                    bearerFallback ? "Bearer " : "");
        }
        applyHeaders(builder, auth);
    }

    private static void applyTokenAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            String headerName) {
        applyTokenAuthentication(builder, auth, headerName, "");
    }

    private static void applyTokenAuthentication(
            HttpRequest.Builder builder,
            OAuthProviderFlow.RequestAuth auth,
            String headerName,
            String prefix) {
        if (auth == null || auth.token() == null || auth.token().isBlank()
                || hasHeader(auth.headers(), headerName)) {
            return;
        }
        builder.header(headerName, prefix + auth.token());
    }

    private static String mergeHeaderValue(
            OAuthProviderFlow.RequestAuth auth, String headerName, String value) {
        String existing = auth == null ? null : auth.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(headerName))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (existing == null || existing.isBlank()) return value;
        return java.util.Arrays.stream(existing.split(","))
                .map(String::trim)
                .anyMatch(value::equalsIgnoreCase)
                ? existing : existing + "," + value;
    }

    private static boolean hasHeader(Map<String, String> headers, String expectedName) {
        return headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(expectedName));
    }

    private void applyProviderRequestHeaders(
            HttpRequest.Builder builder,
            String userMessage) {
        String cacheSessionId = activePromptCacheSessionId();
        if (cacheSessionId != null) {
            switch (config.promptCacheCapabilities().sessionAffinity()) {
                case XAI_CONVERSATION_HEADER ->
                        builder.setHeader("x-grok-conv-id", cacheSessionId);
                case OPENROUTER_SESSION_HEADER ->
                        builder.setHeader("x-session-id", cacheSessionId);
                default -> {
                    // Body-based affinity and provider-owned sessions are handled elsewhere.
                }
            }
        }
        if ("github-copilot".equals(config.getProvider())) {
            builder.setHeader("User-Agent", "GitHubCopilotChat/0.35.0");
            builder.setHeader("Editor-Version", "vscode/1.107.0");
            builder.setHeader("Editor-Plugin-Version", "copilot-chat/0.35.0");
            builder.setHeader("Copilot-Integration-Id", "vscode-chat");
            builder.setHeader("X-Initiator", userMessage == null ? "agent" : "user");
            builder.setHeader("Openai-Intent", "conversation-edits");
        }
    }

    private ArrayNode buildAnthropicMessages(
            String userMessage, List<ToolCallResultInput> toolResults) {
        return buildAnthropicMessages(userMessage, toolResults, List.of());
    }

    private ArrayNode buildAnthropicMessages(
            String userMessage, List<ToolCallResultInput> toolResults,
            List<AttachmentInput> attachments) {
        ArrayNode messages = objectMapper.createArrayNode();

        // Previous history
        for (ObjectNode msg : conversationHistory) {
            messages.add(msg);
        }

        // Tool results
        ObjectNode toolResultMessage = createAnthropicToolResultMessage(toolResults);
        if (toolResultMessage != null) messages.add(toolResultMessage);

        // Current user message
        if (userMessage != null || !attachments.isEmpty()) {
            messages.add(anthropicUserMessage(userMessage, attachments));
        }

        return MemoryContextProjection.project(messages);
    }

    private ObjectNode createAnthropicToolResultMessage(
            List<ToolCallResultInput> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) return null;
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        ArrayNode content = objectMapper.createArrayNode();
        for (ToolCallResultInput toolResult : toolResults) {
            ObjectNode block = objectMapper.createObjectNode();
            block.put("type", "tool_result");
            block.put("tool_use_id", toolResult.callId);
            block.put("content", toolResult.output);
            if (toolResult.isError) block.put("is_error", true);
            content.add(block);
        }
        userMsg.set("content", content);
        return userMsg;
    }

    private ArrayNode convertToolDefsToAnthropic(ArrayNode toolDefs) {
        ArrayNode anthropicTools = objectMapper.createArrayNode();
        for (JsonNode tool : toolDefs) {
            ObjectNode at = objectMapper.createObjectNode();
            at.put("name", tool.path("name").asText());
            at.put("description", tool.path("description").asText());

            JsonNode params = tool.path("inputSchema");
            if (params != null && !params.isMissingNode()) {
                at.set("input_schema", params);
            } else {
                ObjectNode emptySchema = objectMapper.createObjectNode();
                emptySchema.put("type", "object");
                emptySchema.set("properties", objectMapper.createObjectNode());
                at.set("input_schema", emptySchema);
            }

            anthropicTools.add(at);
        }
        return anthropicTools;
    }

    static void readAnthropicUsage(JsonNode usage, StreamResult result) {
        if (usage == null || !usage.isObject()) return;
        // Anthropic input is already exclusive of both cache categories. Delta
        // usage updates all four cumulative fields, not just output_tokens.
        result.inputTokens = usageCount(usage, "input_tokens", result.inputTokens);
        result.outputTokens = usageCount(usage, "output_tokens", result.outputTokens);
        result.cacheReadTokens = usageCount(usage, "cache_read_input_tokens", result.cacheReadTokens);
        result.cacheCreationTokens = usageCount(usage, "cache_creation_input_tokens", result.cacheCreationTokens);
        JsonNode iterations = usage.path("iterations");
        if (!iterations.isArray()) return;
        // Anthropic excludes compaction iterations from top-level usage. The
        // iterations array is itself a snapshot, so a repeated delta replaces it.
        result.compactionInputTokens = 0L;
        result.compactionOutputTokens = 0L;
        result.compactionCacheReadTokens = 0L;
        result.compactionCacheCreationTokens = 0L;
        result.lastRequestInputTokens = iterations.isEmpty() ? -1L : 0L;
        for (JsonNode iteration : iterations) {
            if ("compaction".equals(iteration.path("type").asText())) {
                result.compactionInputTokens += usageCount(iteration, "input_tokens", 0L);
                result.compactionOutputTokens += usageCount(iteration, "output_tokens", 0L);
                result.compactionCacheReadTokens += usageCount(iteration, "cache_read_input_tokens", 0L);
                result.compactionCacheCreationTokens += usageCount(iteration, "cache_creation_input_tokens", 0L);
            } else if ("message".equals(iteration.path("type").asText())) {
                // Server-side tool loops bill multiple prompts, but only their
                // last message iteration describes the current context window.
                result.lastRequestInputTokens = usageCount(iteration, "input_tokens", 0L)
                        + usageCount(iteration, "cache_read_input_tokens", 0L)
                        + usageCount(iteration, "cache_creation_input_tokens", 0L);
            }
        }
    }

    private void parseAnthropicStream(java.io.InputStream inputStream, StreamResult result) throws Exception {
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder currentToolArgs = new StringBuilder();
        boolean currentCompaction = false;
        boolean terminal = false;
        StringBuilder compactionSummary = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                try {
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText("");

                    switch (type) {
                        case "message_start": {
                            readAnthropicUsage(event.path("message").path("usage"), result);
                            break;
                        }

                        case "content_block_start": {
                            JsonNode contentBlock = event.path("content_block");
                            String blockType = contentBlock.path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                currentToolId = contentBlock.path("id").asText("call_" + result.toolCalls.size());
                                currentToolName = contentBlock.path("name").asText("");
                                currentToolArgs.setLength(0);
                            } else if ("compaction".equals(blockType)) {
                                currentCompaction = true;
                                compactionSummary.setLength(0);
                                String initial = contentBlock.path("content").asText("");
                                if (!initial.isBlank()) compactionSummary.append(initial);
                            }
                            break;
                        }

                        case "content_block_delta": {
                            JsonNode delta = event.path("delta");
                            String deltaType = delta.path("type").asText("");

                            if ("text_delta".equals(deltaType)) {
                                String text = delta.path("text").asText("");
                                printStreamingChunk(text);
                                result.text += text;
                            } else if ("thinking_delta".equals(deltaType)) {
                                // Anthropic extended thinking — stream for the transcript,
                                // never into result.text.
                                printThinkingChunk(delta.path("thinking").asText(""));
                            } else if ("input_json_delta".equals(deltaType)) {
                                String partial = delta.path("partial_json").asText("");
                                currentToolArgs.append(partial);
                                reportToolInputDelta(partial);
                            } else if ("compaction_delta".equals(deltaType)) {
                                compactionSummary.append(delta.path("content").asText(""));
                            }
                            break;
                        }

                        case "content_block_stop": {
                            if (currentToolName != null) {
                                ToolCallOutput tc = new ToolCallOutput();
                                tc.id = currentToolId;
                                tc.name = currentToolName;
                                try {
                                    tc.arguments = objectMapper.readTree(currentToolArgs.toString());
                                } catch (Exception e) {
                                    tc.arguments = objectMapper.createObjectNode();
                                }
                                result.toolCalls.add(tc);
                                currentToolId = null;
                                currentToolName = null;
                                currentToolArgs.setLength(0);
                            }
                            if (currentCompaction) {
                                result.nativeCompactionSummary = compactionSummary.toString();
                                result.nativeCompactionStrategy = "anthropic-messages";
                                currentCompaction = false;
                                compactionSummary.setLength(0);
                            }
                            break;
                        }

                        case "message_stop":
                            terminal = true;
                            break;

                        case "message_delta": {
                            String stopReason = event.path("delta").path("stop_reason").asText("");
                            result.refusalDetected |= "refusal".equals(stopReason);
                            result.truncatedDetected |= "max_tokens".equals(stopReason)
                                    || "model_context_window_exceeded".equals(stopReason);
                            readAnthropicUsage(event.path("usage"), result);
                            if (result.refusalDetected || result.truncatedDetected) {
                                finishGenerationOutcome(result);
                                return;
                            }
                            break;
                        }

                        case "error": {
                            JsonNode error = event.path("error");
                            String msg = error.path("message").asText(data);
                            appendProtocolError(result, msg, error.toString());
                            terminal = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable events
                }
            }
        }
        if (!result.cancelled && !result.failed && !terminal) {
            throw new EOFException("Anthropic stream ended before a terminal event");
        }
        finishGenerationOutcome(result);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** A Chat Completions user turn: string content, or content blocks when it attaches files. */
    private ObjectNode openAiUserMessage(String text, List<AttachmentInput> attachments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        if (attachments.isEmpty()) {
            message.put("content", text);
        } else {
            message.set("content", buildOpenAiContentArray(text, attachments));
        }
        return message;
    }

    /**
     * Build an OpenAI-compatible content array for a message with optional attachments.
     * Images become image_url blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildOpenAiContentArray(String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = objectMapper.createObjectNode();
                    imageBlock.put("type", "image_url");
                    ObjectNode imageUrl = objectMapper.createObjectNode();
                    imageUrl.put("url", "data:" + att.mimeType() + ";base64," + att.base64Data());
                    imageBlock.set("image_url", imageUrl);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = objectMapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        if (text != null) {
            ObjectNode textBlock = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        return content;
    }

    /** OpenAI Responses uses input_text/input_image rather than Chat Completions blocks. */
    private ArrayNode buildResponsesContentArray(
            String text, List<AttachmentInput> attachments) {
        ArrayNode content = objectMapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput attachment : attachments) {
                if (attachment.isImage()) {
                    ObjectNode image = content.addObject();
                    image.put("type", "input_image");
                    image.put("image_url", dataUrl(attachment));
                } else {
                    ObjectNode file = content.addObject();
                    file.put("type", "input_text");
                    file.put("text", attachmentText(attachment));
                }
            }
        }
        if (text != null) {
            content.addObject().put("type", "input_text").put("text", text);
        }
        return content;
    }

    /**
     * Build an Anthropic-compatible content array for a message with optional attachments.
     * Images become base64 image source blocks; text files become text blocks with embedded content.
     * The user message text is appended as the final text block.
     */
    private ArrayNode buildAnthropicContentArray(String text, List<AttachmentInput> attachments) {
        return anthropicContent(objectMapper, text, attachments);
    }

    private ObjectNode anthropicUserMessage(String text, List<AttachmentInput> attachments) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.set("content", buildAnthropicContentArray(text, attachments));
        return message;
    }

    /**
     * Anthropic content blocks, shared with Claude Code's stream-json user messages,
     * which carry the Messages API content shape.
     */
    static ArrayNode anthropicContent(ObjectMapper mapper, String text,
                                      List<AttachmentInput> attachments) {
        ArrayNode content = mapper.createArrayNode();
        if (attachments != null) {
            for (AttachmentInput att : attachments) {
                if (att.isImage()) {
                    ObjectNode imageBlock = mapper.createObjectNode();
                    imageBlock.put("type", "image");
                    ObjectNode source = mapper.createObjectNode();
                    source.put("type", "base64");
                    source.put("media_type", att.mimeType());
                    source.put("data", att.base64Data());
                    imageBlock.set("source", source);
                    content.add(imageBlock);
                } else {
                    ObjectNode textBlock = mapper.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", "[File: " + att.path() + "]\n" + att.textContent());
                    content.add(textBlock);
                }
            }
        }
        if (text != null) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        return content;
    }

    private static String validateAttachments(List<AttachmentInput> attachments) {
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentInput attachment = attachments.get(i);
            if (attachment == null) {
                return "Attachment " + i + " is null";
            }
            if (attachment.isImage()) {
                if (attachment.mimeType() == null
                        || !attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("image/")) {
                    return "Image attachment '" + attachment.path()
                            + "' must declare an image MIME type";
                }
                if (isBlank(attachment.base64Data())) {
                    return "Image attachment '" + attachment.path()
                            + "' has no base64 payload";
                }
            } else if (attachment.textContent() == null) {
                return "Attachment '" + attachment.path()
                        + "' is neither an encoded image nor readable text";
            }
        }
        return null;
    }

    protected StreamResult attachmentFailure(String detail) {
        StreamResult result = new StreamResult();
        String message = "[Attachment error: " + detail + "]";
        appendProviderFailure(result, 0, detail, message, true);
        return result;
    }

    static String dataUrl(AttachmentInput attachment) {
        return "data:" + attachment.mimeType() + ";base64," + attachment.base64Data();
    }

    static String attachmentText(AttachmentInput attachment) {
        return "[File: " + attachment.path() + "]\n" + attachment.textContent();
    }

    /**
     * The text a history entry keeps for a turn's attachments on routes whose history is
     * text: the provider saw the bytes, later turns see what was attached.
     */
    static String attachmentMarkers(List<AttachmentInput> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        StringBuilder markers = new StringBuilder();
        for (AttachmentInput attachment : attachments) {
            markers.append(attachment.isImage() ? "[Attached image: " : "[Attached file: ")
                    .append(attachment.path()).append("]\n");
        }
        return markers.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private java.io.InputStream guardResponseStream(java.io.InputStream stream) {
        return new IdleTimeoutInputStream(stream, connectivityPolicy.streamIdleTimeout(), this::isCancelled);
    }

    private String readResponseBody(java.io.InputStream stream) throws Exception {
        try (java.io.InputStream guarded = guardResponseStream(stream)) {
            return new String(guarded.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class UnauthorizedRequestException extends java.io.IOException {
        private final OAuthProviderFlow.RequestAuth auth;
        private final FailureKind kind;

        private UnauthorizedRequestException(OAuthProviderFlow.RequestAuth auth, FailureKind kind) {
            super("Unauthorized");
            this.auth = auth;
            this.kind = kind;
        }
    }

    private void recordStreamFailure(StreamResult result, Exception failure, String prefix) {
        if (markCancelled(result, failure)) return;
        if (failure instanceof UnauthorizedRequestException rejected) {
            if (rejected.kind != FailureKind.NONE) recordTerminalOutcome(result, rejected.kind, 401);
            else recordAuthenticationFailure(result, 401, "[Radius config error 401: Unauthorized]", rejected.auth);
            return;
        }
        if (failure instanceof ChatConfig.AuthenticationException credentialFailure) {
            finishCredentialFailure(result, credentialFailure);
            return;
        }
        if (failure instanceof OpenCodeServeClient.TurnNotStartedException turnNotStarted) {
            // Pre-turn transport failure: the provider never received the prompt and
            // its session holds no new state, so the connectivity loop may replay it.
            result.failed = true;
            result.retryableConnectivityFailure = true;
            result.failureKind = FailureKind.PROVIDER_ERROR;
            result.connectivityFailure = formatExceptionMessage(turnNotStarted);
            result.connectivityFinalMessage = prefix + result.connectivityFailure + "]";
            return;
        }
        if (connectivityPolicy.isRetryableFailure(failure)) {
            result.retryableConnectivityFailure = true;
            result.connectivityFailure = formatExceptionMessage(failure);
            result.connectivityFinalMessage = prefix + result.connectivityFailure + "]";
            return;
        }
        String detail = formatExceptionMessage(failure);
        appendProviderFailure(result, 0, detail, prefix + detail + "]", false);
    }

    private StreamResult finishCredentialFailure(StreamResult result, ChatConfig.AuthenticationException error) {
        result.failed = true;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.failureStatusCode = error.failure().statusCode();
        result.failureKind = switch (error.failure().kind()) {
            case REAUTH_REQUIRED -> FailureKind.AUTHENTICATION;
            case PERMISSION_DENIED -> FailureKind.PERMISSION_DENIED;
            case RATE_LIMITED -> FailureKind.RATE_LIMITED;
            case TEMPORARY -> FailureKind.TEMPORARY;
            case LOCAL_OR_PROTOCOL, INTERRUPTED -> FailureKind.CREDENTIAL_UNAVAILABLE;
        };
        result.failureMessage = error.getMessage();
        String rendered = (result.text.isEmpty() ? "" : "\n") + error.getMessage();
        result.text += rendered;
        printStreamingChunk(rendered);
        return result;
    }

    private void recordUnauthorizedResponse(StreamResult result, java.io.InputStream body,
                                             OAuthProviderFlow.RequestAuth auth) {
        String diagnostic = ProviderResponseFailure.authenticationBody(body);
        if (!recordKnownProviderFailure(result, 401, diagnostic)) {
            recordAuthenticationFailure(result, 401, "[Provider API error 401: Unauthorized]", auth);
        }
    }

    private boolean recordKnownProviderFailure(StreamResult result, int status, String body) {
        return recordKnownProviderFailure(result, status, body, HttpHeaders.of(Map.of(), (name, value) -> true));
    }

    private boolean recordKnownProviderFailure(StreamResult result, int status, String body, HttpHeaders headers) {
        FailureKind kind = ProviderResponseFailure.classify(config.getProvider(), status, body);
        if (kind == FailureKind.NONE) return false;
        recordTerminalOutcome(result, kind, status);
        if (kind == FailureKind.QUOTA_EXHAUSTED) {
            // The handler needs the business code and reset time, not the generic
            // billing guidance. Retain bounded allowlisted diagnostics only.
            String detail = ProviderResponseFailure.diagnostics(body, headers);
            result.failureMessage += detail;
            result.text += detail;
        }
        return true;
    }

    private static boolean responsesContainRefusal(JsonNode content) {
        for (JsonNode block : content) {
            if ("refusal".equals(block.path("type").asText())) return true;
        }
        return false;
    }

    private static boolean isGenerationStopped(StreamResult result) {
        return result.failureKind == FailureKind.REFUSAL || result.failureKind == FailureKind.TRUNCATED;
    }

    private boolean finishGenerationOutcome(StreamResult result) {
        if (result.cancelled) return false;
        if (result.refusalDetected || result.truncatedDetected) {
            recordTerminalOutcome(result, result.refusalDetected ? FailureKind.REFUSAL : FailureKind.TRUNCATED, 200);
            return true;
        }
        return false;
    }

    private void recordTerminalOutcome(StreamResult result, FailureKind kind, int status) {
        result.retryableConnectivityFailure = false;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.responseStarted |= !result.text.isEmpty() || !result.toolCalls.isEmpty();
        result.toolCalls.clear();
        if (result.failed && result.failureKind == kind) return;
        result.failed = true;
        result.failureKind = kind;
        result.failureStatusCode = status;
        result.failureMessage = ProviderResponseFailure.message(kind);
        result.toolCalls.clear();
        String rendered = (result.text.isEmpty() ? "" : "\n") + result.failureMessage;
        result.text += rendered;
        printStreamingChunk(rendered);
    }

    private void recordAuthenticationFailure(
            StreamResult result,
            int statusCode,
            String finalMessage,
            OAuthProviderFlow.RequestAuth rejectedAuth) {
        result.failed = true;
        result.failureKind = FailureKind.AUTHENTICATION;
        result.failureStatusCode = statusCode;
        result.failureMessage = finalMessage;
        result.authenticationFailure = true;
        result.rejectedAuth = rejectedAuth != null && rejectedAuth.oauth()
                ? rejectedAuth : null;
    }

    private void appendProviderFailure(
            StreamResult result, int statusCode, String classificationDetail,
            String finalMessage, boolean render) {
        boolean hadResponse = !result.text.isEmpty() || !result.toolCalls.isEmpty();
        result.responseStarted |= hadResponse;
        result.failed = true;
        result.failureStatusCode = statusCode;
        result.failureMessage = finalMessage;
        result.failureKind = isContextOverflowFailure(statusCode, classificationDetail)
                ? FailureKind.CONTEXT_OVERFLOW : FailureKind.PROVIDER_ERROR;
        if (!result.text.isEmpty()) result.text += "\n";
        result.text += finalMessage;
        // Keep a replay-safe context rejection off the terminal. AgenticChatLoop
        // either compacts and retries transparently or reports it exactly once.
        if (render && (result.failureKind != FailureKind.CONTEXT_OVERFLOW
                || result.responseStarted)) {
            printStreamingChunk(finalMessage);
        }
    }

    static boolean isContextOverflowFailure(int statusCode, String detail) {
        if (detail == null || detail.isBlank()) return false;
        String normalized = detail.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.contains("context_length_exceeded")) return true;
        if (statusCode == 429 || normalized.contains("rate_limit")
                || normalized.contains("per minute")
                || normalized.contains("per second")) {
            return false;
        }
        // Word-order agnostic: providers phrase the same rejection as
        // "context window exceeded" (OpenAI), "Your input exceeds the context
        // window of this model" (OpenAI-compatible backends), or
        // "exceeded its context window". Any overflow signal paired with an
        // explicit context-window mention is a context rejection.
        if (normalized.contains("context window exceeded")
                || (normalized.contains("context window")
                && (normalized.contains("exceed") || normalized.contains("too long")
                || normalized.contains("too many tokens") || normalized.contains("maximum")))
                || normalized.contains("maximum context length")
                || normalized.contains("prompt is too long")
                || normalized.contains("input is too long")
                || (normalized.contains("too many tokens")
                && (normalized.contains("context") || normalized.contains("prompt")
                || normalized.contains("input") || normalized.contains("message")))) {
            return true;
        }
        boolean exceeds = normalized.contains("exceed")
                || normalized.contains("too long")
                || normalized.contains("maximum");
        if (exceeds && (normalized.contains("context limit")
                || normalized.contains("context length")
                || normalized.contains("token limit")
                || normalized.contains("input token")
                || normalized.contains("input length")
                || ((normalized.contains("max length")
                || normalized.contains("maximum length"))
                && (normalized.contains("prompt") || normalized.contains("input")
                || normalized.contains("message") || normalized.contains("context")))
                || (normalized.contains("max_tokens")
                && normalized.contains("context")))) {
            return true;
        }
        if (normalized.contains("reduce the length")
                && (normalized.contains("message") || normalized.contains("prompt"))) {
            return true;
        }
        // Kompile serving relays DL4J's refusals: "Prompt length N exhausts the model
        // context window M" from a vision-language package, "Prompt length N exceeds
        // maxPrefillLength=M" from a fixed-buffer text pipeline.
        if (normalized.contains("prompt length")
                && (normalized.contains("exceed") || normalized.contains("exhaust"))) {
            return true;
        }
        if (normalized.contains("input length")
                && (normalized.contains("allowed range")
                || normalized.contains("range of input")
                || normalized.contains("should be"))) {
            return true;
        }
        return statusCode == 413
                && (normalized.contains("context")
                || normalized.contains("token")
                || normalized.contains("prompt"));
    }

    private boolean recordHttpConnectivityFailure(
            StreamResult result, int statusCode, HttpHeaders headers,
            String reason, String finalMessage) {
        if (!connectivityPolicy.isRetryableStatus(statusCode)) return false;
        result.failed = true;
        result.retryableConnectivityFailure = true;
        result.failureStatusCode = statusCode;
        result.failureKind = FailureKind.PROVIDER_ERROR;
        result.failureMessage = finalMessage;
        result.connectivityFailure = reason;
        result.connectivityFinalMessage = finalMessage;
        result.connectivityHeaders = headers;
        return true;
    }

    private StreamResult finishConnectivityFailure(StreamResult result, boolean replaySafe) {
        String message = result.connectivityFinalMessage;
        if (message == null || message.isBlank()) {
            message = "[" + ChatProviderRegistry.label(config.getProvider())
                    + " connection error: " + result.connectivityFailure + "]";
        }
        if (!replaySafe) {
            message += "\n[Response was not replayed because the provider had already streamed output.]";
        }
        result.failureMessage = message;
        String rendered = result.text.isEmpty() ? message : "\n" + message;
        result.text += rendered;
        result.failed = true;
        result.retryableConnectivityFailure = false;
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(message)) printStreamingChunk(rendered);
        return result;
    }

    private StreamResult finishAuthenticationFailure(StreamResult result) {
        String provider = config.getProvider() == null || config.getProvider().isBlank()
                ? "provider" : config.getProvider().strip();
        String detail = result.failureMessage;
        if (detail == null || detail.isBlank()) {
            detail = "[" + ChatProviderRegistry.label(provider)
                    + " authentication failed (HTTP " + result.failureStatusCode + ")]";
        }
        String guidance = "[Provider authentication was rejected. Check the selected credential and account access. "
                + "If the credential is expired or revoked, use `kompile auth login " + provider
                + "`. Signing in will not fix permission or IP restrictions.]";
        String message = detail + "\n" + guidance;
        result.failureMessage = message;
        result.authenticationFailure = false;
        result.rejectedAuth = null;
        result.text = result.text.isEmpty() ? message : result.text + "\n" + message;
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(message)) printStreamingChunk(result.text);
        return result;
    }

    private boolean waitForRetry(Duration delay) {
        long remainingNanos = Math.max(0L, delay.toNanos());
        long deadline = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0L) {
            if (isCancelled()) return false;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // The stream watchdog interrupts the reader as part of unblocking a
                // stalled read; that interrupt can land here after the retry loop
                // has already recovered. Only a real cancel signal may abort the
                // backoff — an interrupt without one is spurious, so clear it and
                // keep waiting instead of silently dropping the reconnect.
                if (!isCancelled()) {
                    Thread.interrupted();
                    continue;
                }
                return false;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        return !isCancelled();
    }

    private void emitConnectivityEvent(ConnectivityEvent event) {
        java.util.function.Consumer<ConnectivityEvent> consumer = connectivityEventConsumer;
        if (consumer != null) {
            consumer.accept(event);
            return;
        }
        String warning = "[" + ChatProviderRegistry.label(event.provider())
                + " connection lost: " + event.reason() + "; reconnecting attempt "
                + event.attempt() + "/" + event.maxAttempts() + " in "
                + event.delay().toMillis() + " ms]";
        if (!ai.kompile.cli.main.chat.ChatCompleter.showAlert(warning)) System.err.println(warning);
    }

    private void resetOpenCodeClient() {
        OpenCodeServeClient client = openCodeServeClient;
        if (client != null) client.close();
        openCodeServeClient = null;
        openCodeNeedsSeed = true;
    }

    private void resetClaudeClient() {
        ClaudeCliClient client = claudeServeClient;
        if (client != null) client.close();
        claudeServeClient = null;
        claudeNeedsSeed = true;
        claudeCompactionFailed = false;
    }

    private boolean markCancelled(StreamResult result, Exception error) {
        if (isCancelled() || error instanceof InterruptedException) {
            result.cancelled = true;
            return true;
        }
        result.failed = true;
        return false;
    }

    /**
     * Format an exception message for display. If the exception has no message,
     * returns the simple class name of the exception type.
     */
    private String formatExceptionMessage(Exception e) {
        String msg = e.getMessage();
        return (msg != null) ? msg : e.getClass().getSimpleName();
    }

    private String extractErrorMessage(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            // OpenAI format
            String msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            // Anthropic format
            msg = json.path("error").path("message").asText(null);
            if (msg != null) return msg;
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    public static class StreamResult {
        public String text = "";
        public List<ToolCallOutput> toolCalls = new ArrayList<>();
        public boolean cancelled = false;
        public boolean failed = false;
        public FailureKind failureKind = FailureKind.NONE;
        private boolean refusalDetected;
        private boolean truncatedDetected;
        public int failureStatusCode;
        public String failureMessage;
        public boolean responseStarted;
        public boolean providerSideEffectsObserved;
        private boolean authenticationFailure;
        private OAuthProviderFlow.RequestAuth rejectedAuth;
        private boolean retryableConnectivityFailure;
        private String connectivityFailure;
        private String connectivityFinalMessage;
        private HttpHeaders connectivityHeaders;
        private boolean terminalEventSeen;
        /** Stream ended without any terminal event; route-specific resume logic decides. */
        private boolean silentStreamDrop;
        public String nativeCompactionSummary;
        public String nativeCompactionStrategy;
        public JsonNode nativeCompactionPayload;
        public long compactionInputTokens;
        public long compactionOutputTokens;
        public long compactionCacheReadTokens;
        public long compactionCacheCreationTokens;
        /** Native session a successful Claude Code turn ran in; null on every other route. */
        public ClaudeNativeSession claudeNativeSession;
        // Token usage from API response (when available)
        public long inputTokens = 0;
        public long outputTokens = 0;
        public long cacheReadTokens = 0;
        public long cacheCreationTokens = 0;
        /**
         * Input size of the last request, for routes whose token totals add up
         * several requests; negative when the totals describe a single request.
         */
        public long lastRequestInputTokens = -1;

        /**
         * Tokens occupying the provider context. Cached tokens are cheaper, not absent;
         * every provider adapter reports them separately but they still consume context.
         */
        public long contextInputTokens() {
            if (lastRequestInputTokens >= 0L) return lastRequestInputTokens;
            long total = Math.max(0L, inputTokens);
            total = saturatingAdd(total, Math.max(0L, cacheReadTokens));
            return saturatingAdd(total, Math.max(0L, cacheCreationTokens));
        }

        /** Provider stated a length cutoff for this stream (finish_reason=length). */
        public boolean isTruncatedDetected() { return truncatedDetected; }

        /** Provider stated a content refusal for this stream (finish_reason=content_filter). */
        public boolean isRefusalDetected() { return refusalDetected; }

        /** True when a terminal stream event (finish_reason / [DONE] / response.completed) arrived. */
        public boolean isTerminalEventSeen() { return terminalEventSeen; }

        public boolean isContextOverflow() {
            return failed && failureKind == FailureKind.CONTEXT_OVERFLOW;
        }

        public boolean isReplaySafe() {
            return !responseStarted && !providerSideEffectsObserved
                    && toolCalls.isEmpty() && (text.isEmpty() || isContextOverflow());
        }

        public boolean canRetryAfterContextOverflow() {
            return isContextOverflow() && isReplaySafe();
        }

        private static long saturatingAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }

        // Enforcer monitor fields
        public boolean monitorInterrupted = false;
        public String correctionPrompt = null;
    }

    public record ConnectivityEvent(
            String provider, int attempt, int maxAttempts, Duration delay, String reason) {
    }

    public static class ToolCallOutput {
        public String id;
        public String name;
        public JsonNode arguments;
    }

    public record ReplayedToolCallInput(
            String toolName, String callId, String argumentsJson) {
    }

    public static class ToolCallResultInput {
        public String callId;
        public String name;
        public String output;
        public boolean isError;

        public ToolCallResultInput(String callId, String name, String output, boolean isError) {
            this.callId = callId;
            this.name = name;
            this.output = output;
            this.isError = isError;
        }
    }

    /**
     * An attachment (file, image, etc.) to include with a chat message.
     */
    public record AttachmentInput(String path, String mimeType, boolean isImage, String base64Data, String textContent) {
        public AttachmentInput(String path, String mimeType) {
            this(path, mimeType, false, null, null);
        }
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesToolCallAccumulator {
        String itemId;
        String callId;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class ResponsesStreamState {
        final Map<Integer, ObjectNode> reasoningItems = new LinkedHashMap<>();
        final Map<Integer, ResponsesToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private static class PiMessagesStreamState {
        final Map<Integer, ObjectNode> contentBlocks = new LinkedHashMap<>();
        boolean terminal;
        boolean failed;
    }

    private record RadiusGatewayConfig(String baseUrl, List<String> modelIds, Set<String> textOnlyModelIds) {
    }
}
