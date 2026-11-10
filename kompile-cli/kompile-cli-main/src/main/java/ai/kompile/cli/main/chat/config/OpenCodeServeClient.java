/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.NativeCliProcess;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.agent.SubagentContainment;
import ai.kompile.cli.main.chat.agent.SubagentDelegationGuard;
import ai.kompile.cli.main.chat.mcp.McpDiagnostics;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native OpenCode transport for the standalone Kompile Chat provider.
 *
 * <p>This is intentionally a provider adapter, not a passthrough agent. It
 * starts the same supervised {@code opencode serve} backend used by the
 * existing OpenCode integration, creates one native session for the chat,
 * and invokes {@code opencode run --attach} for each turn. Using the native
 * command for turns is important: the command owns the provider/model syntax
 * and the provider-specific {@code --variant} (thinking) flag.</p>
 */
final class OpenCodeServeClient implements AutoCloseable {

    interface ActivityListener {
        void onToolStart(String callId, String name, String input);
        void onToolComplete(String callId, String name, String output,
                            int exitCode, boolean error);
        void onTokenUsage(long input, long output, long cacheRead, long cacheCreation);
        /** Full input of the latest request, not the sum of this turn's billing deltas. */
        default void onContextUsage(long inputTokens) { }
        /**
         * OpenCode started {@code steps} more model requests in the turn's agent
         * loop (its {@code step-start} parts); calls add up.
         */
        default void onSteps(int steps) { }

        /**
         * Reasoning/thinking deltas stream to their own lane, mirroring Claude's
         * ActivityListener: rendered by the live thinking renderer when
         * installed, dropped by capture lanes.
         */
        default void onThinking(String reasoningDelta) { }

        /**
         * OpenCode parked this turn waiting for a permission request it can
         * only get from an interactive client. Kompile's client auto-rejects
         * the request (provider-owned tools run with grants instead), so this
         * is informational feed-through: the ask reached the turn.
         */
        default void onPermissionRequest(String requestId, String permission,
                                         List<String> patterns) { }
    }

    /**
     * The turn failed before any provider interaction began — server boot, session
     * creation or turn-process spawn. No provider-side session history was touched,
     * so the caller may safely replay the turn (after discarding this transport's
     * server, session and any dead turn process).
     */
    static final class TurnNotStartedException extends IllegalStateException {
        TurnNotStartedException(String message) {
            super(message);
        }

        TurnNotStartedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    /** Session creation runs right after server boot; give it boot-scale patience. */
    private static final Duration SESSION_CREATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);
    /** Bounds the abort request and the wait for the aborted turn to end. */
    private static final Duration ABORT_TIMEOUT = Duration.ofSeconds(5);
    /** Per-launch opencode config payload; see {@link McpToolInjection}. */
    private static final String OPENCODE_CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT";

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ObjectMapper objectMapper;
    private final Path workingDirectory;
    private final HttpClient httpClient;
    private final ProviderConnectivityPolicy connectivityPolicy;
    private final StringBuilder serverOutput = new StringBuilder();
    /** Memory files and skills this session used, re-delivered after OpenCode compacts it. */
    private final NativeCompactionReload compactionReload;

    private Process serverProcess;
    private volatile CompletableFuture<HttpResponse<String>> activeTurnRequest;
    /** Returns true once the running turn is cancelled; null when nothing can cancel it. */
    private volatile BooleanSupplier cancellationCheck;
    private String baseUrl;
    private String sessionId;
    private boolean closed;
    /** Kompile MCP launch config this client's server was booted with; removed on close. */
    private Path injectedTools;
    /** The chat's process panel; its session parents the Kompile MCP server the server starts. */
    private volatile BackgroundProcessManager processPanel;
    /** KOMPILE_SUBAGENT_DEPTH of the agent this client serves; 0 for a top-level chat. */
    private volatile int subagentDepth;
    /** This chat's configuration; null or the native route injects no provider options. */
    private final ChatConfig chatConfig;

    OpenCodeServeClient(ObjectMapper objectMapper, Path workingDirectory) {
        this(objectMapper, workingDirectory, null);
    }

    /**
     * @param config this chat's configuration; when it is on OpenCode's API-key
     *               route, its resolved key is injected into the server launch as
     *               per-launch provider options so a stored key survives new
     *               sessions. Null or the native route boots opencode unchanged.
     */
    OpenCodeServeClient(ObjectMapper objectMapper, Path workingDirectory, ChatConfig config) {
        this.objectMapper = objectMapper;
        this.chatConfig = config;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.compactionReload = new NativeCompactionReload(this.workingDirectory, objectMapper);
        this.connectivityPolicy = ProviderConnectivityPolicy.forProvider("opencode");
        this.httpClient = HttpClient.newBuilder()
                // Java's HttpClient default negotiates an h2c upgrade, sending
                // "Connection: Upgrade, HTTP2-Settings / Upgrade: h2c" headers.
                // OpenCode's Bun server accepts such POSTs (the session row is even
                // persisted) but never writes the response, so every request times
                // out — while HTTP/1.1-pinned requests answer in milliseconds.
                // Curl never sends these headers, which is why it appeared healthy.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectivityPolicy.connectTimeout())
                .build();
    }

    OpenCodeServeClient(ObjectMapper objectMapper, Path workingDirectory,
                        HttpClient httpClient, String baseUrl, String sessionId) {
        this.objectMapper = objectMapper;
        this.chatConfig = null;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.compactionReload = new NativeCompactionReload(this.workingDirectory, objectMapper);
        this.connectivityPolicy = ProviderConnectivityPolicy.forProvider("opencode");
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
    }

    /**
     * The chat's process panel. A server booted afterwards hands the panel's session to
     * the Kompile MCP server it starts, so the processes that server launches for OpenCode
     * are this chat's: their rows belong to it and a monitored one wakes it when it ends.
     */
    /**
     * Serve a Kompile subagent: a server booted afterwards carries the depth into its
     * environment (its guard plugin and Kompile MCP server refuse delegation there) and
     * denies OpenCode's {@code task} tool ({@link SubagentContainment}).
     */
    void setSubagentDepth(int depth) {
        this.subagentDepth = Math.max(0, depth);
    }

    void setProcessPanel(BackgroundProcessManager panel) {
        this.processPanel = panel;
    }

    /**
     * Cancels the running turn once it returns true. The turn then asks the
     * server to abort the session's work, which also stops the tools it started.
     */
    void setCancellationCheck(BooleanSupplier check) {
        cancellationCheck = check;
    }

    /** Send one turn through the native OpenCode session. */
    synchronized String send(String model, String variant, String systemPrompt,
                              String userMessage, Consumer<String> output) throws Exception {
        return send(model, variant, systemPrompt, userMessage, output, null);
    }

    synchronized String send(String model, String variant, String systemPrompt,
                              String userMessage, Consumer<String> output,
                              ActivityListener activityListener) throws Exception {
        return send(model, variant, systemPrompt, userMessage, List.of(), output, activityListener);
    }

    /**
     * Send one turn with attachments. Images ride as OpenCode file parts carrying data
     * URLs after the text part, as OpenCode's own clients send them; text files ride as
     * further text parts.
     *
     * @param attachments this turn's images and text files; empty sends the text part alone
     */
    synchronized String send(String model, String variant, String systemPrompt,
                              String userMessage, List<AttachmentInput> attachments,
                              Consumer<String> output,
                              ActivityListener activityListener) throws Exception {
        if (closed) {
            throw new TurnNotStartedException("OpenCode chat transport is closed");
        }
        try {
            ensureSession();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TurnNotStartedException(
                    "OpenCode session creation timed out: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new TurnNotStartedException(
                    "OpenCode session creation failed: " + e.getMessage(), e);
        }
        ModelReference modelReference = parseModelReference(model);

        // Turns run through the REST message endpoint instead of an
        // `opencode run --attach` child process. The CLI turn process boots its
        // location eagerly and intermittently strands there before submitting the
        // prompt (upstream anomalyco/opencode #48669), while this serve path boots
        // lazily and does not hang. The POST is synchronous: its response body is
        // the finished assistant message. The /event bus subscribed below carries
        // the live progress that the child's stdout used to provide — streaming
        // text deltas, tool start/complete activity and token usage — and doubles
        // as the turn's liveness signal for the idle watchdog.
        // After OpenCode compacted the session, the memory files and skills it used
        // ride ahead of this message; the summary keeps at most a mention of them.
        String message = compactionReload.withPendingReload(userMessage);
        compactionReload.recordPrompt(userMessage);
        String prompt = composePrompt(systemPrompt, message);
        EventBusListener listener = new EventBusListener(sessionId, output, activityListener);
        listener.start();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("providerID", modelReference.providerId());
        body.put("modelID", modelReference.modelId());
        if (variant != null && !variant.isBlank()) {
            body.put("variant", variant.trim());
        }
        ArrayNode parts = body.putArray("parts");
        parts.addObject()
                .put("type", "text")
                .put("text", prompt);
        for (AttachmentInput attachment : attachments) {
            if (attachment.isImage()) {
                parts.addObject()
                        .put("type", "file")
                        .put("mime", attachment.mimeType())
                        .put("filename", fileName(attachment.path()))
                        .put("url", DirectLlmClient.dataUrl(attachment));
            } else {
                parts.addObject()
                        .put("type", "text")
                        .put("text", DirectLlmClient.attachmentText(attachment));
            }
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("content-type", "application/json")
                .timeout(TURN_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        activeTurnRequest = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        BooleanSupplier check = cancellationCheck;
        long turnDeadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
        try {
            // Every exit that stops waiting for the turn also aborts it on the
            // server: dropping only the request leaves OpenCode running the turn
            // and its tools.
            while (!activeTurnRequest.isDone()) {
                if (cancelRequested(check)) {
                    abortTurn(activeTurnRequest);
                    throw new CancellationException("OpenCode turn cancelled");
                }
                String stall = listener.permissionStall();
                if (stall != null) {
                    abortTurn(activeTurnRequest);
                    throw new IllegalStateException(stall
                            + ": permission was rejected but the provider asked again");
                }
                long now = System.nanoTime();
                if (!listener.isDegraded() && now - listener.lastActivityNanos()
                        >= connectivityPolicy.subprocessIdleTimeout().toNanos()) {
                    abortTurn(activeTurnRequest);
                    throw new IllegalStateException("OpenCode provider connection was idle for "
                            + connectivityPolicy.subprocessIdleTimeout().toMinutes() + " minutes");
                }
                if (now >= turnDeadline) {
                    abortTurn(activeTurnRequest);
                    throw new IllegalStateException("OpenCode turn timed out");
                }
                Thread.sleep(250);
            }
            HttpResponse<String> response = activeTurnRequest.join();
            if (response.statusCode() / 100 != 2) {
                // The server received and ran (or rejected) the turn: its native
                // session may hold partial state, so this is not replay-safe.
                throw new IllegalStateException("OpenCode turn failed (HTTP "
                        + response.statusCode() + "): " + trimForError(response.body()));
            }
            // The SSE subscription can miss the final update (or be unavailable).
            // Reconcile the REST snapshot through the same per-message counters.
            JsonNode responseRoot = objectMapper.readTree(response.body());
            JsonNode info = responseRoot.path("info");
            listener.handleTokens(info);
            String text = extractText(objectMapper, response.body());
            if (text.isBlank()) {
                // A textless final message is not necessarily a broken turn: a
                // provider failure mid-loop arrives as a 2xx assistant message
                // whose info.error carries the failure, and a turn that reaches
                // its step limit ends in finish="tool-calls" whose final message
                // holds tool bookkeeping only. Surface the real failure first,
                // then reconcile the answer the turn's earlier assistant steps
                // carried, and only then end the turn with the message shape.
                String failure = describeError(info);
                if (failure != null) {
                    throw new IllegalStateException("OpenCode turn failed: " + failure);
                }
                String reconciled = reconcileTurnText(info);
                if (!reconciled.isBlank()) {
                    return reconciled;
                }
                throw new IllegalStateException(
                        "OpenCode returned no assistant text" + blankTurnDiagnostics(responseRoot, info));
            }
            return text;
        } catch (CompletionException e) {
            // Classification mirrors the old process semantics: a failure to
            // reach the server is a turn that never started (replay-safe); any
            // failure after the request reached the server is not.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof HttpConnectTimeoutException
                    || cause instanceof ConnectException) {
                throw new TurnNotStartedException(
                        "OpenCode turn could not reach the server: " + cause.getMessage(), cause);
            }
            if (cause instanceof CancellationException) {
                throw new IllegalStateException("OpenCode turn was cancelled");
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("OpenCode turn request failed", cause);
        } catch (InterruptedException e) {
            abortTurn(activeTurnRequest);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            activeTurnRequest = null;
            listener.stop();
        }
    }

    /**
     * Stop the running turn on the server, which also stops the tools it
     * started, then drop the local request. Waits briefly for the aborted turn
     * to end so the next turn does not reach a session that is still busy.
     */
    private void abortTurn(CompletableFuture<HttpResponse<String>> turn) {
        try {
            abortSession();
            turn.handle((response, error) -> null)
                    .completeOnTimeout(null, ABORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } finally {
            turn.cancel(true);
        }
    }

    /**
     * Ask the server to abort the session's running work. Best effort: joining
     * the async request is not interruptible, so this still reaches the server
     * from a thread that was interrupted.
     */
    private void abortSession() {
        try {
            httpClient.sendAsync(HttpRequest.newBuilder(
                                    URI.create(baseUrl + "/session/" + sessionId + "/abort"))
                            .timeout(ABORT_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.discarding())
                    .join();
        } catch (RuntimeException ignored) {
            // Closing the transport still deletes the session and stops the server.
        }
    }

    private static boolean cancelRequested(BooleanSupplier check) {
        try {
            return check != null && check.getAsBoolean();
        } catch (RuntimeException e) {
            return false; // A failing check must not end the turn.
        }
    }

    /**
     * Live progress lane for a REST turn. Subscribes to the serve's /event SSE
     * stream, filters to this transport's session, and translates the native
     * event shapes into the same callbacks the CLI-line transport produced:
     * streamed text deltas on the output consumer, tool start/complete on the
     * activity listener, token usage from assistant message updates. Losing the
     * bus only removes live progress; the POST future and the hard turn timeout
     * remain authoritative.
     */
    private final class EventBusListener implements Runnable {
        private final String sessionId;
        private final Consumer<String> output;
        private final ActivityListener activity;
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        private final Set<String> startedCalls = new HashSet<>();
        private final Set<String> completedCalls = new HashSet<>();
        private final Set<String> startedSteps = new HashSet<>();
        private final Map<String, long[]> reportedUsage = new HashMap<>();
        /**
         * Delta events carry only {@code partID} and {@code field}, never the
         * part type, and both reasoning and answer text stream as
         * {@code field=text}. Part-type registration comes from
         * {@code message.part.updated}, which always carries the full part.
         */
        private final Map<String, String> partTypes = new HashMap<>();
        private final Set<String> rejectedPermissions = new HashSet<>();
        private volatile String permissionStall;
        private String latestUsageMessage;
        private long latestUsageCreated;
        private final AtomicBoolean degraded = new AtomicBoolean(false);
        private volatile InputStream eventStream;
        private volatile boolean stopped;

        EventBusListener(String sessionId, Consumer<String> output, ActivityListener activity) {
            this.sessionId = sessionId;
            this.output = output;
            this.activity = activity;
        }

        void start() {
            Thread thread = new Thread(sessionContext.wrap(this), "kompile-opencode-events");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void stop() {
            stopped = true;
            try {
                InputStream stream = eventStream;
                if (stream != null) stream.close();
            } catch (IOException ignored) {
                // Closing an already-dead stream is harmless.
            }
        }

        long lastActivityNanos() {
            return lastActivity.get();
        }

        /** True once the event stream ended: idle detection degrades to the hard turn timeout. */
        boolean isDegraded() {
            return degraded.get();
        }

        @Override
        public void run() {
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/event")).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                eventStream = response.body();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(eventStream, StandardCharsets.UTF_8))) {
                    String line;
                    // stop() may have run while the event HTTP request was connecting.
                    while (!stopped && (line = reader.readLine()) != null) {
                        // Socket liveness is not turn progress: heartbeat frames
                        // and SSE separator blanks would otherwise keep the idle
                        // watchdog blind while the turn sits parked on a stall
                        // the provider never reports.
                        if (line.startsWith("data:")) {
                            String payload = line.substring(5);
                            if (!payload.contains("server.heartbeat")) {
                                lastActivity.set(System.nanoTime());
                                handleEvent(payload.trim());
                            }
                        }
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // The turn future and turn deadline remain authoritative.
            } finally {
                degraded.set(true);
            }
        }

        private synchronized void handleEvent(String payload) {
            if (stopped || payload.isEmpty()) return;
            JsonNode node;
            try {
                node = objectMapper.readTree(payload);
            } catch (IOException ignored) {
                return;
            }
            JsonNode properties = node.path("properties");
            String eventSession = properties.path("info").path("sessionID").asText(
                    properties.path("part").path("sessionID").asText(
                            properties.path("sessionID").asText(node.path("sessionID").asText(""))));
            if (!sessionId.equals(eventSession)) {
                return;
            }
            switch (node.path("type").asText("")) {
                case "message.part.delta" -> {
                    String delta = properties.path("delta").asText("");
                    if (delta.isEmpty()) return;
                    // Reasoning deltas use the same field="text" wire shape as
                    // answer text; only the part's type distinguishes them.
                    if ("reasoning".equals(partTypes.get(properties.path("partID").asText("")))) {
                        if (activity != null) activity.onThinking(delta);
                        return;
                    }
                    if (!"text".equals(properties.path("field").asText("text"))) return;
                    if (output != null) {
                        output.accept(delta);
                    }
                }
                case "message.part.updated" -> handlePartUpdated(properties.path("part"));
                case "message.updated" -> handleTokens(properties.path("info"));
                // OpenCode compacts on overflow mid-turn too; the next prompt re-delivers.
                case "session.compacted" -> compactionReload.compacted();
                case "permission.asked" -> handlePermissionAsk(properties);
                default -> {
                    // session.updated / session.status / session.idle and friends
                    // carry no turn payload; their arrival already refreshed the
                    // liveness timestamp.
                }
            }
        }

        private void handlePartUpdated(JsonNode part) {
            String type = part.path("type").asText("");
            // Every full-part update registers its type so late-differing
            // delta frames can route by part identity, not guesswork.
            String partId = part.path("id").asText("");
            if (!partId.isEmpty()) partTypes.put(partId, type);
            if ("step-start".equals(type)) {
                // One part per model request of the turn; a part may be updated again.
                String stepId = part.path("id").asText("");
                if (!stepId.isEmpty() && startedSteps.add(stepId) && activity != null) activity.onSteps(1);
                return;
            }
            if (!"tool".equals(type)) return;
            String callId = part.path("callID").asText("");
            String tool = part.path("tool").asText("");
            if (callId.isEmpty() || tool.isEmpty()) return;
            JsonNode state = part.path("state");
            String status = state.path("status").asText("");
            if ("pending".equals(status) || "running".equals(status)) {
                if (startedCalls.add(callId)) {
                    JsonNode input = state.path("input");
                    String inputText;
                    try {
                        inputText = input.isMissingNode() || input.isNull()
                                ? "" : objectMapper.writeValueAsString(input);
                    } catch (IOException e) {
                        inputText = String.valueOf(input);
                    }
                    if (activity != null) activity.onToolStart(callId, tool, inputText);
                }
            } else if ("completed".equals(status) || "error".equals(status)) {
                if (completedCalls.add(callId)) {
                    String outputText = state.path("output").asText("");
                    int exit = state.path("metadata").path("exit").asInt(0);
                    boolean error = "error".equals(status) || exit != 0;
                    if (!error) {
                        JsonNode input = state.path("input");
                        compactionReload.recordToolUse(tool,
                                input.isObject() ? input.toString() : null, outputText);
                    }
                    if (activity != null) activity.onToolComplete(callId, tool, outputText, exit, error);
                }
            }
        }

        /**
         * OpenCode parks the turn until an interactive client answers a
         * permission request (opencode's default for unevaluated permission
         * kinds is {@code ask}). Kompile's client is not interactive: reject,
         * surface the ask, and abort when the provider re-asks a rejected
         * request instead of burning the turn.
         */
        private void handlePermissionAsk(JsonNode properties) {
            String requestId = properties.path("id").asText("");
            String permission = properties.path("permission").asText("");
            List<String> patterns = new ArrayList<>();
            for (JsonNode pattern : properties.path("patterns")) {
                if (!pattern.isNull()) patterns.add(pattern.asText(""));
            }
            if (activity != null) activity.onPermissionRequest(requestId, permission, patterns);
            rejectPermissionRequest(requestId);
            boolean firstTime = rejectedPermissions
                    .add(permission + " | " + String.join(", ", patterns));
            if (!firstTime) {
                permissionStall = "OpenCode turn is stuck on permission request " + permission
                        + " for [" + String.join(", ", patterns) + "]"
                        + (requestId.isBlank() ? "" : " (request " + requestId + ")");
            }
        }

        private void rejectPermissionRequest(String requestId) {
            if (requestId.isEmpty()) return;
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create(baseUrl + "/session/" + sessionId
                                        + "/permissions/" + requestId))
                        .header("content-type", "application/json")
                        .timeout(ABORT_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"response\":\"reject\"}", StandardCharsets.UTF_8))
                        .build();
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .completeOnTimeout(null, ABORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (RuntimeException ignored) {
                // Best effort: the stall checks still bound the turn.
            }
        }

        /** Stall text once the provider re-asked a rejected permission; null while unremarkable. */
        String permissionStall() {
            return permissionStall;
        }

        private synchronized void handleTokens(JsonNode info) {
            if (stopped || activity == null || !"assistant".equals(info.path("role").asText(""))) return;
            String infoSession = info.path("sessionID").asText(sessionId);
            if (!sessionId.equals(infoSession)) return;
            JsonNode tokens = info.path("tokens");
            if (!tokens.isObject()) return;
            // OpenCode has already normalized cache buckets. Message updates are
            // cumulative snapshots, not increments. REST and SSE share this lock
            // so a late/repeated/stale update cannot count the same usage twice.
            long[] current = {Math.max(0, tokens.path("input").asLong(0)),
                    Math.max(0, tokens.path("output").asLong(0)),
                    Math.max(0, tokens.path("cache").path("read").asLong(0)),
                    Math.max(0, tokens.path("cache").path("write").asLong(0))};
            String messageId = info.path("id").asText("");
            // Legacy frames without identity cannot safely be merged with another request.
            boolean newMessage = !messageId.isBlank() && !reportedUsage.containsKey(messageId);
            long created = info.path("time").path("created").asLong(0);
            if (newMessage && (latestUsageMessage == null || created == 0 || created >= latestUsageCreated)) {
                latestUsageMessage = messageId;
                latestUsageCreated = created;
            }
            long[] previous = messageId.isBlank() ? new long[4]
                    : reportedUsage.computeIfAbsent(messageId, key -> new long[4]);
            long[] delta = new long[4];
            boolean changed = false;
            for (int i = 0; i < current.length; i++) {
                delta[i] = Math.max(0, current[i] - previous[i]);
                previous[i] = Math.max(previous[i], current[i]);
                changed |= delta[i] > 0;
            }
            if (changed) activity.onTokenUsage(delta[0], delta[1], delta[2], delta[3]);
            if (messageId.isBlank() || messageId.equals(latestUsageMessage)) {
                long input = previous[0];
                for (int index : new int[]{2, 3}) {
                    input = Long.MAX_VALUE - input < previous[index] ? Long.MAX_VALUE : input + previous[index];
                }
                if (input > 0) activity.onContextUsage(input);
            }
        }
    }

    /** Ask the provider-owned OpenCode session to compact itself. */
    synchronized NativeSummary summarize(String model) throws Exception {
        if (closed) throw new IllegalStateException("OpenCode chat transport is closed");
        ensureSession();
        ModelReference reference = parseModelReference(model);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("providerID", reference.providerId());
        body.put("modelID", reference.modelId());
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/session/" + sessionId + "/summarize"))
                        .timeout(Duration.ofMinutes(2))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)))
                        .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return new NativeSummary(false, null,
                    "OpenCode summarize returned HTTP " + response.statusCode());
        }
        JsonNode applied = objectMapper.readTree(response.body());
        if (applied.isBoolean() && !applied.asBoolean()) {
            return new NativeSummary(false, null, "OpenCode declined session compaction");
        }
        compactionReload.compacted();

        HttpResponse<String> messages = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
        if (messages.statusCode() / 100 != 2) {
            return new NativeSummary(true, null,
                    "OpenCode compacted the session but its summary could not be read");
        }
        return new NativeSummary(true,
                extractCompactionSummary(objectMapper, messages.body()), null);
    }

    @Override
    public void close() {
        CompletableFuture<HttpResponse<String>> active = activeTurnRequest;
        if (active != null) active.cancel(true);
        synchronized (this) {
            if (closed) return;
            closed = true;
        if (baseUrl != null && sessionId != null) {
            // A turn still running on the server keeps its tools running after
            // the session is deleted and the server is stopped.
            abortSession();
            try {
                httpClient.send(HttpRequest.newBuilder(
                                URI.create(baseUrl + "/session/" + sessionId))
                        .timeout(Duration.ofSeconds(5))
                        .DELETE()
                        .build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // The server is short-lived and cleanup is best effort.
            }
        }
            if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(2, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                serverProcess.destroyForcibly();
            }
            }
        }
        releaseInjectedTools();
    }

    static ModelReference parseModelReference(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("OpenCode requires a provider/model id");
        }
        String value = model.trim();
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            throw new IllegalArgumentException(
                    "OpenCode model ids must use provider/model format: " + value);
        }
        return new ModelReference(value.substring(0, slash), value.substring(slash + 1));
    }

    static String extractText(ObjectMapper objectMapper, String responseBody) throws IOException {
        StringBuilder text = new StringBuilder();
        if (responseBody != null) {
            for (String line : responseBody.split("\\R")) {
                if (!line.isBlank()) {
                    try {
                        collectText(objectMapper.readTree(line), text);
                    } catch (IOException ignored) {
                        // The native JSON stream may include a non-JSON log line.
                    }
                }
            }
            if (text.length() == 0 && !responseBody.isBlank()) {
                collectText(objectMapper.readTree(responseBody), text);
            }
        }
        return text.toString().trim();
    }

    static String extractCompactionSummary(ObjectMapper objectMapper, String responseBody)
            throws IOException {
        if (responseBody == null || responseBody.isBlank()) return "";
        StringBuilder summary = new StringBuilder();
        collectCompactionSummary(objectMapper.readTree(responseBody), summary);
        return summary.toString().trim();
    }

    private static void collectCompactionSummary(JsonNode node, StringBuilder summary) {
        if (node == null) return;
        if (node.isObject()) {
            String type = node.path("type").asText("");
            if ("compaction".equalsIgnoreCase(type)) {
                for (String field : List.of("summary", "content", "text", "recent")) {
                    JsonNode value = node.get(field);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        if (summary.length() > 0) summary.append('\n');
                        summary.append(value.asText());
                    }
                }
            }
            node.forEach(child -> collectCompactionSummary(child, summary));
        } else if (node.isArray()) {
            node.forEach(child -> collectCompactionSummary(child, summary));
        }
    }

    private static void collectText(JsonNode node, StringBuilder text) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            JsonNode value = node.get("text");
            if (type != null && "text".equals(type.asText())
                    && value != null && value.isTextual()) {
                text.append(value.asText());
            }
            node.forEach(child -> collectText(child, text));
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, text));
        }
    }

    /**
     * The failure a finished turn carries on its assistant message —
     * {@code info.error} is opencode's named-error envelope, e.g.
     * {@code {name:"APIError",data:{message:...}}} for an upstream failure, or
     * {@code MessageAbortedError} when the turn was stopped. Null when the
     * message carries no error.
     */
    static String describeError(JsonNode info) {
        JsonNode error = info.path("error");
        if (error.isMissingNode() || error.isNull()) return null;
        String name = error.path("name").asText("");
        JsonNode data = error.path("data");
        String message = "";
        for (String field : List.of("message", "description")) {
            JsonNode value = data.hasNonNull(field) ? data.get(field)
                    : error.hasNonNull(field) ? error.get(field) : null;
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                message = value.asText().trim();
                break;
            }
        }
        String messageBounded = message.isBlank() ? "" : trimForError(message);
        if (name.isBlank() && messageBounded.isEmpty()) return "unknown failure";
        if (messageBounded.isEmpty()) return name;
        if (name.isBlank()) return messageBounded;
        return name + ": " + messageBounded;
    }

    /**
     * Shape of the textless final message for the no-text diagnostic: the
     * finish reason, an overview of the message's parts and the token counters
     * — enough to tell a tool-only step message from a silent model response.
     */
    static String blankTurnDiagnostics(JsonNode root, JsonNode info) {
        StringBuilder diagnostic = new StringBuilder();
        String finish = info.path("finish").asText("");
        diagnostic.append("(finish=").append(finish.isBlank() ? "none" : finish);
        Map<String, Integer> partTypes = new LinkedHashMap<>();
        JsonNode parts = root.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                partTypes.merge(part.path("type").asText("unknown"), 1, Integer::sum);
            }
        }
        if (!partTypes.isEmpty()) {
            diagnostic.append(", parts=");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : partTypes.entrySet()) {
                if (!first) diagnostic.append(", ");
                first = false;
                diagnostic.append(entry.getKey()).append('×').append(entry.getValue());
            }
        }
        JsonNode tokens = info.path("tokens");
        if (tokens.isObject()) {
            diagnostic.append(", tokens in=").append(tokens.path("input").asLong(0))
                    .append(" out=").append(tokens.path("output").asLong(0))
                    .append(" reasoning=").append(tokens.path("reasoning").asLong(0));
        }
        return diagnostic.append(')').toString();
    }

    /**
     * Recover the answer a textless final message hid. A turn splits its text
     * across assistant messages — one per model step — and the final message
     * may only close out the tools. List the session's messages and join the
     * text parts of this turn's assistant messages, named by the turn's user
     * message ({@code info.parentID}); messages that carry their own error are
     * skipped because their text died with it.
     */
    private String reconcileTurnText(JsonNode info) throws IOException {
        String parentMessageId = info.path("parentID").asText("");
        if (parentMessageId.isBlank()) return "";
        HttpResponse<String> messages;
        try {
            messages = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/session/" + sessionId + "/message"))
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        if (messages.statusCode() / 100 != 2 || messages.body() == null
                || messages.body().isBlank()) {
            return "";
        }
        return extractAssistantTextByParent(objectMapper, messages.body(), parentMessageId);
    }

    static String extractAssistantTextByParent(ObjectMapper objectMapper, String messagesBody,
                                               String parentMessageId) throws IOException {
        JsonNode root = objectMapper.readTree(messagesBody);
        if (root == null || root.isMissingNode() || root.isNull() || root.isValueNode()) return "";
        List<JsonNode> messages = new ArrayList<>();
        if (root.isArray()) root.forEach(messages::add);
        // The listing normally arrives chronologically, but no ordering contract
        // is documented: sort by (time.created, id) defensively.
        messages.sort(Comparator
                .comparing((JsonNode message) -> message.path("info").path("time").path("created").asLong(0))
                .thenComparing(message -> message.path("info").path("id").asText("")));
        StringBuilder text = new StringBuilder();
        for (JsonNode message : messages) {
            JsonNode messageInfo = message.path("info");
            if (!"assistant".equals(messageInfo.path("role").asText(""))) continue;
            if (!parentMessageId.equals(messageInfo.path("parentID").asText(""))) continue;
            JsonNode error = messageInfo.path("error");
            if (!error.isMissingNode() && !error.isNull()) continue;
            if (text.length() > 0) text.append('\n');
            collectText(message, text);
        }
        return text.toString().trim();
    }

    private void ensureSession() throws Exception {
        if (baseUrl == null || sessionId == null) {
            ensureServer();
            ObjectNode body = objectMapper.createObjectNode();
            body.put("title", "kompile-chat");
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/session"))
                            .timeout(SESSION_CREATE_TIMEOUT)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    objectMapper.writeValueAsString(body)))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new TurnNotStartedException("OpenCode session creation failed ("
                        + response.statusCode() + "): " + trimForError(response.body()));
            }
            sessionId = objectMapper.readTree(response.body()).path("id").asText(null);
            if (sessionId == null || sessionId.isBlank()) {
                throw new TurnNotStartedException("OpenCode session creation returned no id");
            }
        }
    }

    private void ensureServer() throws Exception {
        if (serverProcess != null && serverProcess.isAlive() && baseUrl != null) {
            return;
        }
        if (baseUrl != null) {
            // An externally supplied URL (tests, remote attach) must never spawn a
            // second local server process.
            return;
        }
        AgentProvider definition = opencodeDefinition();
        String binary = definition != null && definition.getCommand() != null
                ? definition.getCommand() : "opencode";
        int port = availablePort();
        ProcessBuilder builder = processBuilder(List.of(
                        binary, "serve", "--hostname", "127.0.0.1",
                        "--port", String.valueOf(port)))
                .redirectErrorStream(true);
        // OpenCode starts its MCP servers with its own environment, as Claude Code does.
        ClaudeCliClient.withParentSession(builder, processPanel);
        injectKompileTools(builder);
        applyPermissionGrants(builder.environment());
        applyProviderApiKey(builder.environment());
        int depth = subagentDepth;
        if (depth > 0) {
            builder.environment().put(SubagentDelegationGuard.DEPTH_ENV, Integer.toString(depth));
            try {
                // Last: the task deny must follow every permission rule set above.
                SubagentContainment.applyLaunchEnvironment("opencode", builder.environment(), workingDirectory);
            } catch (IOException e) {
                throw new TurnNotStartedException("BLOCKED: could not apply the OpenCode subagent guard, "
                        + "so the subagent is not launched: " + e.getMessage());
            }
        }
        serverProcess = builder.start();
        baseUrl = "http://127.0.0.1:" + port;
        Thread outputReader = readLines(serverProcess.getInputStream(), serverOutput, null);
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!serverProcess.isAlive()) {
                outputReader.join(500);
                throw new TurnNotStartedException("OpenCode server exited: "
                        + trimForError(serverOutput.toString()));
            }
            try {
                HttpResponse<Void> health = httpClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/global/health"))
                                .timeout(CONNECT_TIMEOUT)
                                .GET()
                                .build(), HttpResponse.BodyHandlers.discarding());
                if (health.statusCode() / 100 == 2) {
                    return;
                }
            } catch (Exception ignored) {
                // The server is still booting.
            }
            Thread.sleep(100);
        }
        throw new TurnNotStartedException("Timed out waiting for OpenCode server: "
                + trimForError(serverOutput.toString()));
    }

    private Thread readLines(java.io.InputStream stream, StringBuilder sink,
                                    Consumer<String> eachLine) {
        return readLines(stream, sink, eachLine, 8000);
    }

    /**
     * Directly inject Kompile tools into the server this client boots, exactly as
     * a Claude Code launch is configured: one per-launch Kompile MCP entry wired
     * in at startup, carrying this chat's project. The {@code opencode run} CLI
     * has no config flag, so the per-launch configuration travels in the server's
     * environment instead of on its command line — same deterministic startup
     * wiring, no reliance on project or global {@code opencode.json} discovery.
     */
    private void injectKompileTools(ProcessBuilder builder) {
        releaseInjectedTools();
        try {
            Path tools = McpToolInjection.injectTools(workingDirectory, "opencode", null, Map.of());
            if (tools == null) {
                McpDiagnostics.log("[MCP] Warning: no Kompile CLI launcher resolved; "
                        + "the OpenCode server boots without Kompile tools");
                return;
            }
            McpToolInjection.applyLaunchEnvironment(builder.environment(), tools);
            injectedTools = tools;
            if (compactionReload.enabled()) {
                // The guard plugin hands this file to OpenCode's summarizer when it compacts.
                Path reload = compactionReloadFile(tools);
                compactionReload.mirrorTo(reload);
                builder.environment().put(NativeCompactionReload.FILE_ENV, reload.toString());
            }
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not inject Kompile tools into the "
                    + "OpenCode server: " + e.getMessage());
        }
    }

    private void releaseInjectedTools() {
        Path tools = injectedTools;
        injectedTools = null;
        if (tools != null) {
            McpToolInjection.removeTools(tools);
            compactionReload.mirrorTo(null);
            try {
                Files.deleteIfExists(compactionReloadFile(tools));
            } catch (IOException e) {
                McpDiagnostics.log("[MCP] Warning: could not delete the OpenCode compaction reload: "
                        + e.getMessage());
            }
        }
    }

    /**
     * The launch config's sibling: its name keeps the config's owner pid, so the launch
     * sweep deletes it with the config when this process dies without closing.
     */
    static Path compactionReloadFile(Path launchConfig) {
        String name = launchConfig.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return launchConfig.resolveSibling(base + "-compaction-reload.json");
    }

    /**
     * Provider-owned tool runs must never stall on a permission ask: opencode's
     * default for any tool action without a matching rule is {@code ask}, which
     * parks the turn until an interactive client replies and kompile's client is
     * not one. Grant like Claude's {@code --dangerously-skip-permissions} lane:
     * allow everything at launch, ahead of the rules this launch carries (Kompile's
     * {@code bash: deny} among them). OpenCode's last matching rule wins, so the
     * wildcard goes first; a launch with a wildcard or a single action of its own
     * keeps it.
     */
    /**
     * Inject this chat's resolved API key as OpenCode provider options
     * ({@code provider.<id>.options.apiKey}) in the launch's
     * {@code OPENCODE_CONFIG_CONTENT}, exactly where {@code opencode auth login}
     * stores one. A keyless native login is untouched: the server then reads its
     * own auth.json as before.
     */
    private void applyProviderApiKey(Map<String, String> environment) {
        String apiKey = null;
        try {
            apiKey = chatConfig == null || !"api-key".equalsIgnoreCase(chatConfig.getAuthenticationMethod())
                    ? null : chatConfig.getApiKey();
        } catch (ChatConfig.AuthenticationException e) {
            // No stored key: boot the server with its own native credentials.
        }
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        try {
            String providerId = providerIdOfTurnModel();
            if (providerId == null || providerId.isBlank()) return;
            ObjectNode content = objectMapper.createObjectNode();
            String current = environment.get(OPENCODE_CONFIG_CONTENT_ENV);
            if (current != null && !current.isBlank()) {
                JsonNode parsed = objectMapper.readTree(current);
                if (parsed != null && parsed.isObject()) {
                    content = (ObjectNode) parsed;
                }
            }
            content.withObject("/provider/" + providerId + "/options")
                    .put("apiKey", apiKey);
            environment.put(OPENCODE_CONFIG_CONTENT_ENV, objectMapper.writeValueAsString(content));
        } catch (Exception e) {
            McpDiagnostics.log("[MCP] Warning: Could not inject the OpenCode provider API key: "
                    + e.getMessage());
        }
    }

    /** The provider id of this chat's selected model, e.g. {@code openai} in {@code openai/gpt-4o}. */
    private String providerIdOfTurnModel() {
        if (chatConfig == null) return null;
        String model = chatConfig.getModel();
        if (model == null || model.isBlank()) return null;
        int slash = model.indexOf('/');
        return slash > 0 ? model.substring(0, slash) : "opencode";
    }

    void applyPermissionGrants(Map<String, String> environment) {
        try {
            String current = environment.get(OPENCODE_CONFIG_CONTENT_ENV);
            ObjectNode content = null;
            if (current != null && !current.isBlank()) {
                try {
                    JsonNode parsed = objectMapper.readTree(current);
                    content = parsed.isObject() ? (ObjectNode) parsed : null;
                } catch (IOException e) {
                    content = null;
                }
            }
            if (content == null) {
                content = objectMapper.createObjectNode();
            }
            JsonNode explicit = content.get("permission");
            if (explicit != null && (!explicit.isObject() || explicit.has("*"))) {
                return; // The launch's own wildcard choice owns it.
            }
            ObjectNode grants = objectMapper.createObjectNode();
            grants.put("*", "allow");
            if (explicit != null) {
                grants.setAll((ObjectNode) explicit);
            }
            content.set("permission", grants);
            environment.put(OPENCODE_CONFIG_CONTENT_ENV,
                    objectMapper.writeValueAsString(content));
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: Could not grant OpenCode launch permissions: "
                    + e.getMessage());
        }
    }

    private Thread readLines(java.io.InputStream stream, StringBuilder sink,
                                    Consumer<String> eachLine, int maxChars) {
        Thread thread = new Thread(sessionContext.wrap(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        if (sink.length() < maxChars) {
                            sink.append(line).append('\n');
                        }
                    }
                    if (eachLine != null) {
                        eachLine.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // Process shutdown closes the stream.
            }
        }), "kompile-opencode-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Spawn template for native OpenCode processes (turn and server boot).
     * Delegates to {@link NativeCliProcess} so native CLI processes always get a
     * closed stdin — {@code opencode run} blocks forever on ProcessBuilder's
     * default never-EOF stdin pipe (the zero-byte response failure).
     */
    ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder builder = NativeCliProcess.processBuilder(command, workingDirectory);
        AgentProvider definition = opencodeDefinition();
        if (definition != null) {
            builder.environment().putAll(definition.safeEnvironment());
        }
        return builder;
    }

    private static AgentProvider opencodeDefinition() {
        return CliAgentRegistry.loadAll().stream()
                .filter(agent -> "opencode".equalsIgnoreCase(agent.getCommand())
                        || "opencode".equalsIgnoreCase(agent.getName()))
                .findFirst()
                .orElse(null);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String composePrompt(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return userMessage == null ? "" : userMessage;
        }
        return "[Kompile Chat system instructions]\n"
                + systemPrompt.trim()
                + "\n[End Kompile Chat system instructions]\n\n"
                + (userMessage == null ? "" : userMessage);
    }

    /** The last segment of an attachment path, which OpenCode shows as the file's name. */
    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "attachment";
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        return name.isBlank() ? "attachment" : name;
    }

    private static String trimForError(String value) {
        if (value == null || value.isBlank()) {
            return "no diagnostic output";
        }
        String trimmed = value.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) + "..." : trimmed;
    }

    record ModelReference(String providerId, String modelId) {
        String asWireValue() {
            return providerId + "/" + modelId;
        }
    }

    record NativeSummary(boolean applied, String summary, String diagnostic) {
    }
}
