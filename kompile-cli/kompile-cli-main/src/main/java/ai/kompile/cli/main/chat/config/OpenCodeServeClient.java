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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ObjectMapper objectMapper;
    private final Path workingDirectory;
    private final HttpClient httpClient;
    private final ProviderConnectivityPolicy connectivityPolicy;
    private final StringBuilder serverOutput = new StringBuilder();

    private Process serverProcess;
    private volatile CompletableFuture<HttpResponse<String>> activeTurnRequest;
    private String baseUrl;
    private String sessionId;
    private boolean closed;

    OpenCodeServeClient(ObjectMapper objectMapper, Path workingDirectory) {
        this.objectMapper = objectMapper;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
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
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.connectivityPolicy = ProviderConnectivityPolicy.forProvider("opencode");
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.sessionId = sessionId;
    }

    /** Send one turn through the native OpenCode session. */
    synchronized String send(String model, String variant, String systemPrompt,
                              String userMessage, Consumer<String> output) throws Exception {
        return send(model, variant, systemPrompt, userMessage, output, null);
    }

    synchronized String send(String model, String variant, String systemPrompt,
                              String userMessage, Consumer<String> output,
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
        String prompt = composePrompt(systemPrompt, userMessage);
        EventBusListener listener = new EventBusListener(sessionId, output, activityListener);
        listener.start();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("providerID", modelReference.providerId());
        body.put("modelID", modelReference.modelId());
        if (variant != null && !variant.isBlank()) {
            body.put("variant", variant.trim());
        }
        body.putArray("parts").addObject()
                .put("type", "text")
                .put("text", prompt);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/session/" + sessionId + "/message"))
                .header("content-type", "application/json")
                .timeout(TURN_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        activeTurnRequest = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long turnDeadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
        try {
            while (!activeTurnRequest.isDone()) {
                long now = System.nanoTime();
                if (!listener.isDegraded() && now - listener.lastActivityNanos()
                        >= connectivityPolicy.subprocessIdleTimeout().toNanos()) {
                    activeTurnRequest.cancel(true);
                    throw new IllegalStateException("OpenCode provider connection was idle for "
                            + connectivityPolicy.subprocessIdleTimeout().toMinutes() + " minutes");
                }
                if (now >= turnDeadline) {
                    activeTurnRequest.cancel(true);
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
            String text = extractText(objectMapper, response.body());
            if (text.isBlank()) {
                throw new IllegalStateException("OpenCode returned no assistant text");
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
            activeTurnRequest.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            activeTurnRequest = null;
            listener.stop();
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
        private final AtomicBoolean degraded = new AtomicBoolean(false);
        private volatile InputStream eventStream;

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

        void stop() {
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
                    while ((line = reader.readLine()) != null) {
                        lastActivity.set(System.nanoTime());
                        if (line.startsWith("data:")) {
                            handleEvent(line.substring(5).trim());
                        }
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // The turn future and turn deadline remain authoritative.
            } finally {
                degraded.set(true);
            }
        }

        private void handleEvent(String payload) {
            if (payload.isEmpty()) return;
            JsonNode node;
            try {
                node = objectMapper.readTree(payload);
            } catch (IOException ignored) {
                return;
            }
            JsonNode properties = node.path("properties");
            String eventSession = properties.path("sessionID").asText(
                    node.path("sessionID").asText(""));
            if (!sessionId.equals(eventSession)) {
                return;
            }
            switch (node.path("type").asText("")) {
                case "message.part.delta" -> {
                    if (!"text".equals(properties.path("field").asText("text"))) return;
                    String delta = properties.path("delta").asText("");
                    if (!delta.isEmpty() && output != null) {
                        output.accept(delta);
                    }
                }
                case "message.part.updated" -> handlePartUpdated(properties.path("part"));
                case "message.updated" -> handleTokens(properties.path("info"));
                default -> {
                    // session.updated / session.status / session.idle and friends
                    // carry no turn payload; their arrival already refreshed the
                    // liveness timestamp.
                }
            }
        }

        private void handlePartUpdated(JsonNode part) {
            if (activity == null || !"tool".equals(part.path("type").asText(""))) return;
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
                    activity.onToolStart(callId, tool, inputText);
                }
            } else if ("completed".equals(status) || "error".equals(status)) {
                if (completedCalls.add(callId)) {
                    String outputText = state.path("output").asText("");
                    int exit = state.path("metadata").path("exit").asInt(0);
                    boolean error = "error".equals(status) || exit != 0;
                    activity.onToolComplete(callId, tool, outputText, exit, error);
                }
            }
        }

        private void handleTokens(JsonNode info) {
            if (activity == null || !"assistant".equals(info.path("role").asText(""))) return;
            JsonNode tokens = info.path("tokens");
            long input = tokens.path("input").asLong(0);
            long outputTokens = tokens.path("output").asLong(0);
            long cacheRead = tokens.path("cache").path("read").asLong(0);
            long cacheWrite = tokens.path("cache").path("write").asLong(0);
            if (input > 0 || outputTokens > 0 || cacheRead > 0 || cacheWrite > 0) {
                activity.onTokenUsage(input, outputTokens, cacheRead, cacheWrite);
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
