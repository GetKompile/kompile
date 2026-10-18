/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.WebChatContext;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final boolean retainChildOnTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean cleanupConfirmed;
    // Guarded by this, like the serialized request/notification writes. Never cleared.
    private IOException poisonFailure;
    private JsonNode serverCapabilities;
    private static final int MAX_CATALOG_PAGES = 128;
    private static final int MAX_CATALOG_ITEMS = 10_000;
    private static final int MAX_CATALOG_CHARS = 16 * 1024 * 1024;
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
        this(mapper, command, args, environment, workingDirectory,
                responseTimeoutSeconds, serverId, transcriptId, false);
    }

    /**
     * Explicit ownership opt-in: after transport uncertainty, refuse all further RPCs
     * but retain the child, streams and bounded reader until the owner calls close().
     * A late response never clears the poison or proves the remote outcome here.
     */
    McpStdioClient(ObjectMapper mapper, String command, List<String> args,
                   Map<String, String> environment, Path workingDirectory,
                   long responseTimeoutSeconds, String serverId, String transcriptId,
                   boolean retainChildOnTimeout)
            throws IOException {
        if (command == null || command.isBlank()) throw new IOException("MCP stdio command is empty");
        this.retainChildOnTimeout = retainChildOnTimeout;
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
        // A tool server is not another browser host. Do not implicitly inherit its owner/profile.
        WebChatContext.clearEnvironment(processEnvironment);
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
        JsonNode initialized = request("initialize", params);
        serverCapabilities = initialized.path("capabilities").deepCopy();
        notify("notifications/initialized", mapper.createObjectNode());
    }

    List<McpBundleToolLoader.RemoteTool> listTools() throws IOException {
        List<McpBundleToolLoader.RemoteTool> tools = new ArrayList<>();
        // Keep tools/list for legacy servers that omitted capabilities.tools.
        for (JsonNode tool : listCatalog("tools/list", "tools", "name")) {
            tools.add(new McpBundleToolLoader.RemoteTool(
                    tool.path("name").asText(), tool.path("description").asText(""),
                    tool.path("inputSchema").isObject()
                            ? tool.path("inputSchema") : mapper.createObjectNode()));
        }
        return tools;
    }

    boolean advertises(String capability) {
        return serverCapabilities != null && serverCapabilities.path(capability).isObject();
    }

    List<JsonNode> listPrompts() throws IOException {
        requireCapability("prompts");
        return listCatalog("prompts/list", "prompts", "name");
    }

    List<JsonNode> listResources() throws IOException {
        requireCapability("resources");
        return listCatalog("resources/list", "resources", "uri");
    }

    List<JsonNode> listResourceTemplates() throws IOException {
        requireCapability("resources");
        return listCatalog("resources/templates/list", "resourceTemplates", "uriTemplate");
    }

    /**
     * SDK 0.10.0 lists template-backed SyncResourceSpecifications in both catalogs.
     * Collapse a legacy Resource.uri alias only when templates/list independently
     * advertises the identical descriptor. The gateway still validates the template
     * and its variables; a brace in an arbitrary resource URI is not permission to
     * invent a template or a read route.
     */
    List<JsonNode> listResourceCatalog() throws IOException {
        List<JsonNode> resources = listResources();
        List<JsonNode> templates = listResourceTemplates();
        Map<String, JsonNode> advertisedTemplates = new java.util.LinkedHashMap<>();
        for (JsonNode template : templates)
            advertisedTemplates.put(template.path("uriTemplate").asText(), template);
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode resource : resources) {
            String uri = resource.path("uri").asText();
            if (uri.contains("{") || uri.contains("}")) {
                JsonNode advertised = advertisedTemplates.get(uri);
                if (advertised == null || resource.has("uriTemplate"))
                    throw new CatalogException("UNADVERTISED_TEMPLATE", "resources/list");
                ObjectNode promoted = ((ObjectNode) resource).deepCopy();
                promoted.remove("uri");
                promoted.put("uriTemplate", uri);
                if (!promoted.equals(advertised))
                    throw new CatalogException("CONFLICTING_TEMPLATE", "resources/list");
                // Use only the authoritative templates/list row, once.
            } else entries.add(resource);
        }
        entries.addAll(templates);
        return List.copyOf(entries);
    }

    private void requireCapability(String capability) throws IOException {
        // Even an unsupported operation must respect sticky transport quarantine.
        synchronized (this) { requireUsable(); }
        if (!advertises(capability)) throw new CatalogException("NOT_ADVERTISED", capability);
    }

    /** Atomic inventory: never return a partial list as a complete catalog. */
    private List<JsonNode> listCatalog(String method, String field, String identity) throws IOException {
        List<JsonNode> entries = new ArrayList<>();
        Set<String> cursors = new java.util.HashSet<>();
        Set<String> identities = new java.util.HashSet<>();
        String cursor = null;
        long chars = 0;
        for (int page = 0; page < MAX_CATALOG_PAGES; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            // Preserve the original transport exception/cause, especially sticky quarantine.
            JsonNode result = request(method, params);
            if (!result.isObject() || !result.path(field).isArray())
                throw new CatalogException("INVALID_CATALOG", method);
            for (JsonNode entry : result.path(field)) {
                JsonNode key = entry.path(identity);
                if (!entry.isObject() || !key.isTextual() || key.asText().isBlank()
                        || key.asText().length() > 2048)
                    throw new CatalogException("INVALID_IDENTITY", method);
                if (!identities.add(key.asText()))
                    throw new CatalogException("DUPLICATE_IDENTITY", method);
                chars += entry.toString().length();
                if (entries.size() >= MAX_CATALOG_ITEMS || chars > MAX_CATALOG_CHARS)
                    throw new CatalogException("CATALOG_LIMIT", method);
                entries.add(entry.deepCopy());
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull() || (next.isTextual() && next.asText().isEmpty()))
                return List.copyOf(entries);
            if (!next.isTextual() || next.asText().length() > 2048 || next.asText().isBlank())
                throw new CatalogException("INVALID_CURSOR", method);
            cursor = next.asText(); // Cursors are opaque: do not trim or otherwise rewrite.
            if (!cursors.add(cursor)) throw new CatalogException("REPEATED_CURSOR", method);
        }
        throw new CatalogException("PAGE_LIMIT", method);
    }

    /** Bounded diagnostics: do not echo untrusted catalog bodies or cursor values. */
    static final class CatalogException extends IOException {
        final String code;
        CatalogException(String code, String operation) {
            super("MCP catalog " + code + " (" + operation + ")");
            this.code = code;
        }
    }

    JsonNode getPrompt(String name, JsonNode arguments) throws IOException {
        requireCapability("prompts");
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("prompts/get", params);
    }

    JsonNode readResource(String uri) throws IOException {
        requireCapability("resources");
        return request("resources/read", mapper.createObjectNode().put("uri", uri));
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
            requireUsable();
            if (TRACE_PROTOCOL_IDS) {
                appendStdoutDiagnostic("request-id=" + id + " method=" + method);
            }
            try {
                output.write(mapper.writeValueAsString(request));
                output.write('\n');
                output.flush();
                return readResponse(id);
            } catch (IOException failure) {
                poisonIfRetainingChild(failure);
                throw failure;
            }
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        notification.set("params", params == null ? mapper.createObjectNode() : params);
        synchronized (this) {
            requireUsable();
            try {
                output.write(mapper.writeValueAsString(notification));
                output.write('\n');
                output.flush();
            } catch (IOException failure) {
                poisonIfRetainingChild(failure);
                throw failure;
            }
        }
    }

    private void requireUsable() throws IOException {
        if (closed.get()) throw new IOException("MCP stdio client is closed");
        if (poisonFailure != null) {
            throw new IOException("MCP stdio client is poisoned after transport uncertainty; "
                    + "remote outcome remains unknown; explicit close required", poisonFailure);
        }
    }

    private void poisonIfRetainingChild(IOException failure) {
        if (retainChildOnTimeout && poisonFailure == null) poisonFailure = failure;
    }

    private JsonNode readResponse(long id) throws IOException {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                if (!retainChildOnTimeout) terminateTimedOutProcess();
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
            if (retainChildOnTimeout) {
                if (response == null || !response.isObject()
                        || !"2.0".equals(response.path("jsonrpc").asText())) {
                    throw new IOException("Invalid MCP JSON-RPC response for id=" + id);
                }
                // Notifications are not replies and may precede the matching result.
                if (response.path("method").isTextual() && !response.has("id")
                        && !response.has("result") && !response.has("error")) continue;
                if (!response.path("id").isIntegralNumber()
                        || !response.path("id").canConvertToLong()
                        || response.path("id").asLong() != id
                        || response.has("result") == response.has("error")
                        || response.has("method")) {
                    throw new IOException("Invalid MCP JSON-RPC response for id=" + id);
                }
            }
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
        if (!closed.compareAndSet(false, true)) return;
        try { output.close(); } catch (IOException ignored) { }
        if (retainChildOnTimeout) {
            // Product ownership cannot hide a nested forced/orphan boundary from
            // the web owner. EOF first, then actual exit; escalation stays unconfirmed.
            boolean escalated = false;
            List<ProcessHandle> descendants = process.descendants().toList();
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    escalated = true;
                    terminateProcessTree(process, 500L);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                cleanupConfirmed = !escalated && !process.isAlive() && process.exitValue() == 0
                        && descendants.stream().noneMatch(ProcessHandle::isAlive);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminateProcessTree(process, 0L);
            } catch (RuntimeException unavailable) {
                terminateProcessTree(process, 0L);
            }
        } else terminateProcessTree(process, 500L);
        // A reader blocked on a still-live native pipe must never block cleanup reporting.
        if (!process.isAlive()) try { input.close(); } catch (IOException ignored) { }
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

    boolean cleanupConfirmed() { return closed.get() && cleanupConfirmed; }

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
