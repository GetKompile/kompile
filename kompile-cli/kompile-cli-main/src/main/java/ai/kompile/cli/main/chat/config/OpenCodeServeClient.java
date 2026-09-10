/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.ChatSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(30);

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final ObjectMapper objectMapper;
    private final Path workingDirectory;
    private final HttpClient httpClient;
    private final ProviderConnectivityPolicy connectivityPolicy;
    private final StringBuilder serverOutput = new StringBuilder();

    private Process serverProcess;
    private volatile Process activeTurnProcess;
    private String baseUrl;
    private String sessionId;
    private boolean closed;

    OpenCodeServeClient(ObjectMapper objectMapper, Path workingDirectory) {
        this.objectMapper = objectMapper;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.connectivityPolicy = ProviderConnectivityPolicy.forProvider("opencode");
        this.httpClient = HttpClient.newBuilder()
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
            throw new IllegalStateException("OpenCode chat transport is closed");
        }
        ensureSession();
        ModelReference modelReference = parseModelReference(model);

        List<String> command = new ArrayList<>();
        AgentProvider definition = opencodeDefinition();
        command.add(definition != null && definition.getCommand() != null
                ? definition.getCommand() : "opencode");
        command.add("run");
        command.add("--attach");
        command.add(baseUrl);
        command.add("--session");
        command.add(sessionId);
        command.add("--model");
        command.add(modelReference.asWireValue());
        command.add("--format");
        command.add("json");
        if (variant != null && !variant.isBlank()) {
            command.add("--variant");
            command.add(variant.trim());
        }
        command.add(composePrompt(systemPrompt, userMessage));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        if (definition != null) {
            builder.environment().putAll(definition.safeEnvironment());
        }
        Process turn = builder.start();
        activeTurnProcess = turn;
        StringBuilder rawOutput = new StringBuilder();
        StringBuilder assistantText = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        PassthroughStreamParser streamParser = new PassthroughStreamParser();
        Set<String> startedCalls = new HashSet<>();
        Set<String> completedCalls = new HashSet<>();
        AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
        Thread stderrReader = readLines(turn.getErrorStream(), errorOutput,
                ignored -> lastActivityNanos.set(System.nanoTime()));
        Thread stdoutReader = readLines(
                turn.getInputStream(), rawOutput, line -> {
                    lastActivityNanos.set(System.nanoTime());
                    processProviderLine(line, streamParser, assistantText, output,
                            activityListener, startedCalls, completedCalls);
                }, Integer.MAX_VALUE);

        try {
            long turnDeadline = System.nanoTime() + TURN_TIMEOUT.toNanos();
            while (!turn.waitFor(250, TimeUnit.MILLISECONDS)) {
                long now = System.nanoTime();
                if (now - lastActivityNanos.get()
                        >= connectivityPolicy.subprocessIdleTimeout().toNanos()) {
                    turn.destroyForcibly();
                    throw new IllegalStateException("OpenCode provider connection was idle for "
                            + connectivityPolicy.subprocessIdleTimeout().toMinutes() + " minutes");
                }
                if (now >= turnDeadline) {
                    turn.destroyForcibly();
                    throw new IllegalStateException("OpenCode turn timed out");
                }
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(2));
            stderrReader.join(TimeUnit.SECONDS.toMillis(2));
            if (turn.exitValue() != 0) {
                throw new IllegalStateException("OpenCode turn failed (exit " + turn.exitValue()
                        + "): " + trimForError(errorOutput.toString()));
            }
        } catch (InterruptedException e) {
            turn.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            activeTurnProcess = null;
        }

        String text = assistantText.toString().trim();
        if (text.isBlank()) {
            throw new IllegalStateException("OpenCode returned no assistant text"
                    + (errorOutput.isEmpty() ? "" : ": " + trimForError(errorOutput.toString())));
        }
        return text;
    }

    void processProviderLine(
            String line,
            PassthroughStreamParser parser,
            StringBuilder assistantText,
            Consumer<String> output,
            ActivityListener activity,
            Set<String> startedCalls,
            Set<String> completedCalls) {
        List<PassthroughStreamParser.PassthroughEvent> events =
                parser.parseOpenCodeLineMulti(line);
        String callId = openCodeCallId(line);
        for (PassthroughStreamParser.PassthroughEvent event : events) {
            if (event instanceof PassthroughStreamParser.TextChunk text) {
                assistantText.append(text.text());
                if (output != null && !text.text().isEmpty()) output.accept(text.text());
            } else if (event instanceof PassthroughStreamParser.ToolUse tool && activity != null) {
                String effectiveCallId = callId.isBlank() ? tool.name() : callId;
                if (startedCalls.add(effectiveCallId)) {
                    activity.onToolStart(effectiveCallId, tool.name(), tool.input());
                }
            } else if (event instanceof PassthroughStreamParser.ToolComplete tool
                    && activity != null) {
                String effectiveCallId = callId.isBlank() ? tool.name() : callId;
                if (completedCalls.add(effectiveCallId)) {
                    activity.onToolComplete(effectiveCallId, tool.name(), tool.output(),
                            tool.exitCode(), tool.error());
                }
            } else if (event instanceof PassthroughStreamParser.TokenUsage usage
                    && activity != null) {
                activity.onTokenUsage(usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadTokens(), usage.cacheCreationTokens());
            } else if (event instanceof PassthroughStreamParser.TurnComplete complete
                    && activity != null
                    && (complete.inputTokens() > 0 || complete.outputTokens() > 0
                    || complete.cacheReadTokens() > 0 || complete.cacheCreationTokens() > 0)) {
                activity.onTokenUsage(complete.inputTokens(), complete.outputTokens(),
                        complete.cacheReadTokens(), complete.cacheCreationTokens());
            }
        }
    }

    private String openCodeCallId(String line) {
        try {
            JsonNode part = objectMapper.readTree(line).path("part");
            for (String field : List.of("callID", "call_id", "id")) {
                String id = part.path(field).asText("");
                if (!id.isBlank()) return id;
            }
        } catch (Exception ignored) {
            // Non-JSON output has no provider call id.
        }
        return "";
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
        Process active = activeTurnProcess;
        if (active != null && active.isAlive()) active.destroyForcibly();
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
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    objectMapper.writeValueAsString(body)))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenCode session creation failed ("
                        + response.statusCode() + "): " + trimForError(response.body()));
            }
            sessionId = objectMapper.readTree(response.body()).path("id").asText(null);
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalStateException("OpenCode session creation returned no id");
            }
        }
    }

    private void ensureServer() throws Exception {
        if (serverProcess != null && serverProcess.isAlive() && baseUrl != null) {
            return;
        }
        AgentProvider definition = opencodeDefinition();
        String binary = definition != null && definition.getCommand() != null
                ? definition.getCommand() : "opencode";
        int port = availablePort();
        ProcessBuilder builder = new ProcessBuilder(
                binary, "serve", "--hostname", "127.0.0.1", "--port", String.valueOf(port))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        if (definition != null) {
            builder.environment().putAll(definition.safeEnvironment());
        }
        serverProcess = builder.start();
        baseUrl = "http://127.0.0.1:" + port;
        Thread outputReader = readLines(serverProcess.getInputStream(), serverOutput, null);
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!serverProcess.isAlive()) {
                outputReader.join(500);
                throw new IllegalStateException("OpenCode server exited: "
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
        throw new IllegalStateException("Timed out waiting for OpenCode server: "
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
