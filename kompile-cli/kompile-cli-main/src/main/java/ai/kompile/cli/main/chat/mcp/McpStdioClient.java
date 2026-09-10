/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.mcp.stdio.McpStderrLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal JSON-lines MCP client for bundle-declared stdio servers. */
final class McpStdioClient implements AutoCloseable {
    static final int MAX_FRAME_CHARS = Integer.getInteger(
            "kompile.mcp.stdio.maxFrameChars", 8 * 1024 * 1024);
    private static final int MAX_BUFFERED_EVENTS = Integer.getInteger(
            "kompile.mcp.stdio.maxBufferedEvents", 256);
    private static final java.util.regex.Pattern SENSITIVE_ENVIRONMENT_NAME =
            java.util.regex.Pattern.compile(
                    "(?i).*(TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY|ACCESS_KEY|AUTH|CREDENTIAL).*");
    private static final boolean TRACE_PROTOCOL_IDS = Boolean.getBoolean(
            "kompile.mcp.stdio.traceProtocolIds");

    private final ObjectMapper mapper;
    private final Process process;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final long responseTimeoutMillis;
    // Bound unsolicited notifications and apply stdout backpressure instead of
    // allowing an idle MCP child to grow this process without limit.
    private final BlockingQueue<ReaderEvent> readerEvents =
            new ArrayBlockingQueue<>(Math.max(1, MAX_BUFFERED_EVENTS));
    private final Thread responseReader;
    private final AtomicLong nextId = new AtomicLong(1);
    private final McpStderrLogger childStderrLogger;
    private final McpStderrLogger stdoutDiagnosticLogger;
    private final Thread stderrReader;

    McpStdioClient(ObjectMapper mapper, String command, List<String> args,
                   Map<String, String> environment, Path workingDirectory,
                   long responseTimeoutSeconds)
            throws IOException {
        this(mapper, command, args, environment, workingDirectory,
                responseTimeoutSeconds, null, null);
    }

    McpStdioClient(ObjectMapper mapper, String command, List<String> args,
                   Map<String, String> environment, Path workingDirectory,
                   long responseTimeoutSeconds, String serverId, String transcriptId)
            throws IOException {
        if (command == null || command.isBlank()) throw new IOException("MCP stdio command is empty");
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) commandLine.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        configureEnvironment(builder.environment(), environment);
        this.mapper = mapper;
        String effectiveTranscriptId = transcriptId == null || transcriptId.isBlank()
                ? null : transcriptId.trim();
        if (effectiveTranscriptId != null) {
            builder.environment().put(
                    TranscriptLogScope.TRANSCRIPT_ID_ENV, effectiveTranscriptId);
            Path mcpLogDir = LogPaths.ensureTranscriptDirectory(effectiveTranscriptId)
                    .toPath().resolve("mcp");
            Files.createDirectories(mcpLogDir);
            String safeServerId = LogPaths.safePathSegment(
                    serverId == null || serverId.isBlank() ? "stdio" : serverId);
            Path stderrLogFile = mcpLogDir.resolve(
                    "bundle-" + safeServerId + ".stderr.log");
            Path stdoutDiagnosticLogFile = mcpLogDir.resolve(
                    "bundle-" + safeServerId + ".stdout-diagnostics.log");
            requireWritableFile(stderrLogFile);
            requireWritableFile(stdoutDiagnosticLogFile);
            this.childStderrLogger = new McpStderrLogger(stderrLogFile);
            this.stdoutDiagnosticLogger = new McpStderrLogger(stdoutDiagnosticLogFile);
            appendLifecycle("launch command=" + command);
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
        } else {
            this.childStderrLogger = null;
            this.stdoutDiagnosticLogger = null;
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        this.process = builder.start();
        this.stderrReader = childStderrLogger == null ? null : startDiagnosticReader(
                process.getErrorStream(), childStderrLogger.getPrintStream(),
                "mcp-stderr-reader-" + process.pid());
        appendLifecycle("started pid=" + process.pid());
        this.input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.responseTimeoutMillis = TimeUnit.SECONDS.toMillis(
                Math.max(1L, responseTimeoutSeconds));
        this.responseReader = new Thread(this::readOutputLoop,
                "mcp-stdio-reader-" + process.pid());
        this.responseReader.setDaemon(true);
        this.responseReader.start();
    }

    /**
     * Do not leak ambient credentials into arbitrary third-party server processes.
     * A sensitive variable is forwarded only when the server config names it explicitly.
     */
    static void configureEnvironment(Map<String, String> processEnvironment,
                                     Map<String, String> configuredEnvironment) {
        Set<String> explicit = configuredEnvironment == null
                ? Set.of() : Set.copyOf(configuredEnvironment.keySet());
        processEnvironment.keySet().removeIf(name ->
                SENSITIVE_ENVIRONMENT_NAME.matcher(name).matches()
                        && !explicit.contains(name));
        if (configuredEnvironment != null) {
            processEnvironment.putAll(configuredEnvironment);
        }
    }

    long childProcessPid() {
        return process.pid();
    }

    void initialize() throws IOException {
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "kompile-agent").put("version", "0.1");
        request("initialize", params);
        notify("notifications/initialized", mapper.createObjectNode());
    }

    List<McpBundleToolLoader.RemoteTool> listTools() throws IOException {
        List<McpBundleToolLoader.RemoteTool> tools = new ArrayList<>();
        java.util.Set<String> seenCursors = new java.util.HashSet<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request("tools/list", params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpBundleToolLoader.RemoteTool(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema").isObject()
                                ? tool.path("inputSchema") : mapper.createObjectNode()));
            }
            String nextCursor = result.path("nextCursor").asText("").trim();
            if (nextCursor.isBlank()) return tools;
            if (!seenCursors.add(nextCursor)) {
                throw new IOException("MCP tools/list repeated cursor: " + nextCursor);
            }
            cursor = nextCursor;
        }
        throw new IOException("MCP tools/list exceeded 100 pages");
    }

    JsonNode callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    private JsonNode request(String method, JsonNode params) throws IOException {
        long id = nextId.getAndIncrement();
        ObjectNode request = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
        request.set("params", params == null ? mapper.createObjectNode() : params);
        synchronized (this) {
            if (TRACE_PROTOCOL_IDS) {
                appendStdoutDiagnostic("request-id=" + id + " method=" + method);
            }
            output.write(mapper.writeValueAsString(request));
            output.write('\n');
            output.flush();
            return readResponse(id);
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        notification.set("params", params == null ? mapper.createObjectNode() : params);
        synchronized (this) {
            output.write(mapper.writeValueAsString(notification));
            output.write('\n');
            output.flush();
        }
    }

    private JsonNode readResponse(long id) throws IOException {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                terminateTimedOutProcess();
                throw new IOException("Timed out after " + responseTimeoutMillis
                        + "ms waiting for MCP response id=" + id);
            }
            ReaderEvent event;
            try {
                event = readerEvents.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MCP response id=" + id, e);
            }
            if (event == null) continue;
            if (event.failure() != null) throw event.failure();
            if (event.eof()) break;
            String line = event.line();
            if (line == null || line.isBlank()) continue;
            JsonNode response = mapper.readTree(line);
            if (TRACE_PROTOCOL_IDS) {
                appendStdoutDiagnostic("response-id=" + response.path("id").asText("")
                        + " request-id=" + id + " has-result=" + response.has("result")
                        + " has-error=" + response.has("error"));
            }
            if (!response.path("id").isNumber() || response.path("id").asLong() != id) continue;
            if (response.has("error")) throw new IOException("MCP error: " + response.path("error"));
            if (!response.has("result")) {
                appendStdoutDiagnostic("invalid-json-rpc-response: " + line);
                continue;
            }
            return response.path("result");
        }
        if (!process.isAlive()) {
            throw new IOException("MCP stdio server closed its output (exit="
                    + process.exitValue() + ")");
        }
        throw new IOException("MCP stdio server closed its output");
    }

    private void readOutputLoop() {
        try {
            String line;
            while ((line = readBoundedLine(input, MAX_FRAME_CHARS)) != null) {
                if (!isJsonRpcEnvelope(line)) {
                    appendStdoutDiagnostic(line);
                }
                readerEvents.put(new ReaderEvent(line, null, false));
            }
            readerEvents.put(new ReaderEvent(null, null, true));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            try {
                readerEvents.put(new ReaderEvent(null, failure, false));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxChars, 4096));
        while (true) {
            int next = reader.read();
            if (next < 0) return line.isEmpty() ? null : line.toString();
            if (next == '\n') return line.toString();
            if (next == '\r') continue;
            if (line.length() >= maxChars) {
                throw new IOException("MCP stdio frame exceeds " + maxChars + " characters");
            }
            line.append((char) next);
        }
    }

    private void terminateTimedOutProcess() {
        terminateProcessTree(process, 0L);
        try { input.close(); } catch (IOException ignored) { }
    }

    static void terminateProcessTree(Process process, long gracefulWaitMillis) {
        List<ProcessHandle> descendants = new ArrayList<>(
                process.toHandle().descendants().toList());
        Collections.reverse(descendants);
        descendants.forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
        if (process.isAlive()) process.destroy();
        if (gracefulWaitMillis > 0) {
            try {
                process.waitFor(gracefulWaitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        descendants.forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
    }

    @Override
    public void close() {
        try { output.close(); } catch (IOException ignored) { }
        terminateProcessTree(process, 500L);
        try { input.close(); } catch (IOException ignored) { }
        responseReader.interrupt();
        if (stderrReader != null) {
            try {
                stderrReader.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stderrReader.interrupt();
        }
        appendLifecycle(process.isAlive()
                ? "closed process-still-alive"
                : "closed exit=" + process.exitValue());
        if (childStderrLogger != null) childStderrLogger.getPrintStream().close();
        if (stdoutDiagnosticLogger != null) stdoutDiagnosticLogger.getPrintStream().close();
    }

    private void appendLifecycle(String message) {
        if (childStderrLogger == null) return;
        childStderrLogger.getPrintStream().println("[kompile-parent] " + message);
    }

    private void appendStdoutDiagnostic(String line) {
        if (stdoutDiagnosticLogger == null || line == null) return;
        stdoutDiagnosticLogger.getPrintStream().println(line);
    }

    private boolean isJsonRpcEnvelope(String line) {
        if (line == null || line.isBlank()) return false;
        try {
            JsonNode candidate = mapper.readTree(line);
            return candidate != null && candidate.isObject()
                    && "2.0".equals(candidate.path("jsonrpc").asText())
                    && (candidate.has("id") || candidate.has("method")
                    || candidate.has("result") || candidate.has("error"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Thread startDiagnosticReader(
            InputStream source, java.io.PrintStream sink, String name) {
        Thread reader = new Thread(() -> {
            try (InputStream input = source) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    sink.write(buffer, 0, count);
                    sink.flush();
                }
            } catch (IOException e) {
                sink.println("[kompile-parent] stderr reader failed: " + e.getMessage());
            }
        }, name);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void requireWritableFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (java.io.OutputStream ignored = Files.newOutputStream(file,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            // Opening the exact sink is the startup invariant; writes must not fail silently.
        }
    }

    private record ReaderEvent(String line, IOException failure, boolean eof) { }
}
