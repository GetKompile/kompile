/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.chat.ToolPresentationV1;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.extensions.process.ProcessExtensionManifest;
import ai.kompile.cli.main.extensions.process.ProcessExtensionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manifest-only discovery; each authorized invocation owns one disposable MCP stdio child. */
public final class ProcessExtensionMcpBridge {
    private ProcessExtensionMcpBridge() { }

    /** Does not inspect the filesystem, initialize MCP, or launch the extension. */
    public static CliTool tool(ProcessExtensionStore.InstalledExtension installed,
                               ProcessExtensionManifest.Tool definition,
                               ObjectMapper mapper, String javaExecutable) {
        Objects.requireNonNull(installed, "installed");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(mapper, "mapper");
        var manifest = installed.manifest();
        var declared = manifest.tools().stream()
                .filter(candidate -> candidate.name().equals(definition.name()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Undeclared extension tool"));
        if (!declared.inputSchema().equals(definition.inputSchema()))
            throw new IllegalArgumentException("Extension tool schema differs from its manifest");
        var launch = manifest.mcp().orElseThrow(() -> new IllegalArgumentException("No extension MCP launch"));
        return new CliTool() {
            @Override public String id() { return manifest.toolId(declared.name()); }
            @Override public String description() { return declared.description(); }
            @Override public JsonNode parameterSchema() { return declared.inputSchema(); }
            @Override public String permissionKey() { return "mcp.extension-" + manifest.id(); }
            @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
                context.checkPermission(permissionKey(), "Call extension " + manifest.id() + "/" + declared.name());
                var mandate = ShellMandatePolicy.evaluateFromSerializedArgs(
                        id(), params == null ? "{}" : params.toString(), context.getWorkingDirectory());
                if (mandate != null) return ToolResult.error(mandate.getCorrectionPrompt());
                requireNotAborted(context);
                if (!McpBundleToolLoader.customLoadingAllowed(System.getenv(McpBundleToolLoader.CUSTOM_MCP_DEPTH_ENV))) {
                    throw new ToolExecutionException("Process extensions cannot recursively launch from a custom MCP child");
                }
                boolean callStarted = false;
                boolean resultReceived = false;
                try {
                    // Resolve/validate the installed target only after permission, never via a shell.
                    List<String> command = launch.command(installed.directory(), javaExecutable);
                    requireNotAborted(context);
                    String transcript = TranscriptLogScope.currentTranscriptId();
                    if (transcript == null || transcript.isBlank()) transcript = context.getSessionId();
                    Map<String, String> environment = Map.of(
                            "KOMPILE_EXTENSION_ID", manifest.id(),
                            "KOMPILE_EXTENSION_VERSION", manifest.version(),
                            "KOMPILE_EXTENSION_ROOT", installed.directory().toAbsolutePath().normalize().toString(),
                            McpBundleToolLoader.CUSTOM_MCP_DEPTH_ENV, "1");
                    try (var client = new McpStdioClient(mapper, command.get(0),
                            command.subList(1, command.size()), environment, context.getWorkingDirectory(),
                            manifest.timeoutSeconds(), "extension-" + manifest.id(), transcript, false);
                         var invocation = new Invocation(client, context, manifest.timeoutSeconds())) {
                        invocation.requireActive();
                        client.initialize();
                        verifyCatalog(manifest, client.listToolsStrict());
                        invocation.requireActive();
                        callStarted = true;
                        // The manifest fixes the remote name; arguments cannot retarget this proxy.
                        JsonNode result = client.callTool(declared.name(), params);
                        validateCallResult(result);
                        resultReceived = true;
                        return render(manifest.id(), declared.name(), result);
                    }
                } catch (Exception failure) {
                    // A stopped child is not a rollback. Only a completed response establishes outcome.
                    if (callStarted && !resultReceived && context.getToolRegistry() != null)
                        context.getToolRegistry().markProductUnknownOutcome();
                    if (failure instanceof ToolExecutionException execution) throw execution;
                    throw new ToolExecutionException("Extension MCP tool failed: " + id()
                            + (callStarted && !resultReceived ? " (remote outcome unknown)" : ""), failure);
                }
            }
        };
    }

    private static void verifyCatalog(ProcessExtensionManifest manifest,
                                      List<McpBundleToolLoader.RemoteTool> advertised) throws IOException {
        Map<String, JsonNode> schemas = new HashMap<>();
        for (var tool : advertised) schemas.put(tool.name(), tool.inputSchema());
        for (var tool : manifest.tools()) {
            JsonNode schema = schemas.get(tool.name());
            if (schema == null) throw new IOException("Declared extension MCP tool is missing: " + tool.name());
            if (!tool.inputSchema().equals(schema))
                throw new IOException("Extension MCP input schema mismatch: " + tool.name());
        }
        // Additional advertised tools have no proxy and confer no authority.
    }

    private static void validateCallResult(JsonNode result) throws IOException {
        if (result == null || !result.isObject() || !result.path("content").isArray()
                || result.has("isError") && !result.path("isError").isBoolean()
                || result.has("structuredContent") && !result.path("structuredContent").isObject()) {
            throw new IOException("Invalid MCP CallToolResult; remote outcome unknown");
        }
        for (JsonNode block : result.path("content")) {
            if (!block.isObject() || !block.path("type").isTextual() || block.path("type").asText().isBlank()) {
                throw new IOException("Invalid MCP content block; remote outcome unknown");
            }
        }
    }

    private static ToolResult render(String extension, String name, JsonNode payload) {
        var result = McpBundleToolLoader.renderResult(payload);
        if (result.error()) return ToolResult.error(result.content());
        String title = "MCP extension-" + extension + "/" + name;
        ToolResult output = result.extraContent() == null || result.extraContent().isEmpty()
                ? ToolResult.success(title, result.content())
                : ToolResult.success(title, result.content(), result.extraContent());
        try {
            return McpBundleToolLoader.reattach(output, ToolPresentationV1.fromCandidate(
                    result.presentationCandidate(), "extension-" + extension, name));
        } catch (RuntimeException ignored) {
            return output; // Optional display does not change the action's result.
        }
    }

    private static void requireNotAborted(ToolContext context) throws ToolExecutionException {
        if (context.isAborted() || Thread.currentThread().isInterrupted())
            throw new ToolExecutionException("Extension MCP invocation cancelled");
    }

    /**
     * Bounded daemon watchdog, not a process pool. It interrupts the owner after killing only its
     * child's observed tree: close alone can contend with a blocked pipe writer, and interrupting
     * alone cannot unblock that native write. The client still owns all stream/reader cleanup.
     */
    private static final class Invocation implements AutoCloseable {
        private final McpStdioClient client;
        private final ToolContext context;
        private final Thread owner = Thread.currentThread();
        private final ProcessHandle child;
        private final Map<Long, ProcessHandle> tree = new ConcurrentHashMap<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long deadline;
        private final Thread watchdog;
        private volatile String stopped;
        private volatile boolean ownerWakeup;

        Invocation(McpStdioClient client, ToolContext context, int timeoutSeconds) {
            this.client = client;
            this.context = context;
            this.child = ProcessHandle.of(client.childProcessPid()).orElse(null);
            this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            this.watchdog = new Thread(this::watch, "extension-mcp-watchdog-" + client.childProcessPid());
            watchdog.setDaemon(true);
            watchdog.start();
        }

        private void rememberTree() {
            if (child != null) {
                tree.put(child.pid(), child);
                child.descendants().forEach(handle -> tree.put(handle.pid(), handle));
            }
        }

        private void terminateDescendants() {
            rememberTree();
            tree.values().forEach(handle -> {
                if (handle != child && handle.isAlive()) handle.destroyForcibly();
            });
            // Give the still-live parent a bounded chance to reap children before terminating it.
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (tree.values().stream().anyMatch(handle -> handle != child && handle.isAlive())
                    && System.nanoTime() < until) {
                try { Thread.sleep(10); }
                catch (InterruptedException interruption) { Thread.currentThread().interrupt(); break; }
            }
        }

        private void forceTree() {
            terminateDescendants();
            if (child != null && child.isAlive()) child.destroyForcibly();
        }

        private void watch() {
            try {
                while (!finished.get()) {
                    rememberTree();
                    String reason = context.isAborted() || owner.isInterrupted() ? "cancelled"
                            : System.nanoTime() >= deadline ? "timed out" : null;
                    if (reason != null && !finished.get()) {
                        stopped = reason;
                        forceTree();
                        if (!owner.isInterrupted()) {
                            ownerWakeup = true;
                            owner.interrupt(); // Unblock the response queue as well as pipe I/O.
                        }
                        return;
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) {
                // Invocation.close owns shutdown and joins this thread.
            }
        }

        void requireActive() throws ToolExecutionException {
            if (stopped != null) throw new ToolExecutionException("Extension MCP invocation " + stopped);
            requireNotAborted(context);
            if (System.nanoTime() >= deadline)
                throw new ToolExecutionException("Extension MCP invocation timed out");
        }

        @Override public void close() throws IOException {
            finished.set(true);
            watchdog.interrupt();
            boolean interrupted = Thread.interrupted();
            try {
                long joinUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (watchdog.isAlive() && System.nanoTime() < joinUntil) {
                    try { watchdog.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(joinUntil - System.nanoTime()))); }
                    catch (InterruptedException interruption) { interrupted = true; }
                }
                // The watchdog may have interrupted us between clearing the flag and join().
                interrupted |= Thread.interrupted();
                terminateDescendants();
                client.close();
                forceTree(); // Also terminate observed descendants if their parent exited first.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (tree.values().stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < until) {
                    try { Thread.sleep(10); }
                    catch (InterruptedException interruption) { interrupted = true; }
                }
                if (watchdog.isAlive() || tree.values().stream().anyMatch(ProcessHandle::isAlive))
                    throw new IOException("Extension MCP child cleanup could not be confirmed");
            } finally {
                // Do not leak the watchdog's private wakeup interrupt into the next tool invocation.
                if (interrupted && !ownerWakeup) Thread.currentThread().interrupt();
            }
        }
    }
}
