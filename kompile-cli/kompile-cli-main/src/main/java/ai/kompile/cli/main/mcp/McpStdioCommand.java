/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.main.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@CommandLine.Command(
    name = "mcp-stdio",
    description = "Start MCP stdio server for tool integration (used by Qwen Code, etc.)",
    mixinStandardHelpOptions = true
)
public class McpStdioCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--work-dir"}, description = "Working directory for tools")
    private String workDir;

    @CommandLine.Option(names = {"--url"}, description = "Base URL of the kompile-app instance (e.g. http://localhost:8080)")
    private String baseUrl;

    @CommandLine.Option(names = {"--no-daemon"}, description = "Skip daemon bridge, always run in-process",
            defaultValue = "true")
    private boolean noDaemon;

    @CommandLine.Option(names = {"--profile"},
            description = "Tool profile: full (default, all tools), core (file I/O + search + workflow), " +
                    "explore (read-only + code intelligence), minimal (read + grep + glob only)",
            defaultValue = "full")
    private String profile;

    @CommandLine.Option(names = {"--schema-level"},
            description = "Schema optimization level: none, moderate, aggressive, compact (default: compact)",
            defaultValue = "compact")
    private String schemaLevel;

    // ── Profile definitions ─────────────────────────────────────────────────
    // Maps profile name → set of tool IDs included in that profile.
    // "full" and "custom" profiles are handled separately (full=all, custom=from file).
    private static final Map<String, Set<String>> PROFILE_TOOLS;
    static {
        Map<String, Set<String>> m = new LinkedHashMap<>();

        // minimal: just read + search (~5 tools, ~1000 tokens)
        m.put("minimal", new LinkedHashSet<>(Set.of(
                "read", "grep", "glob", "list", "bash"
        )));

        // explore: read-only + code intelligence (~10 tools, ~2500 tokens)
        m.put("explore", new LinkedHashSet<>(Set.of(
                "read", "grep", "glob", "list", "bash",
                "explore", "code_search", "local_code_index", "code_graph",
                "fetch_result"
        )));

        // core: file I/O + search + workflow (~15 tools, ~3000 tokens)
        m.put("core", new LinkedHashSet<>(Set.of(
                "read", "write", "edit", "grep", "glob", "list", "bash",
                "explore", "fetch_result", "patch",
                "todowrite", "todoread", "memory",
                "webfetch", "websearch"
        )));

        // full: all tools (no restriction) — handled by returning allTools.keySet()
        m.put("full", Set.of());

        PROFILE_TOOLS = Collections.unmodifiableMap(m);
    }

    /** Session tracker for metrics and harness evaluation across the MCP server lifecycle. */
    private volatile ai.kompile.cli.mcp.stdio.McpSessionTracker sessionTracker;

    /** Coordination state manager for cross-agent edit/process/agent coordination. */
    private volatile ai.kompile.cli.main.coordination.CoordinationStateManager coordinator;

    /** Reference cache for large tool outputs — enables handle-based inter-tool communication. */
    private volatile ai.kompile.cli.main.chat.tools.ToolResultReferenceCache resultReferenceCache;

    /** Dynamic tool manager for lazy tool loading — reduces tool schema bloat. */
    private volatile ai.kompile.cli.main.chat.tools.DynamicToolManager dynamicToolManager;

    /** Semantic memory engine for passive vector-based memory retrieval. */
    private volatile ai.kompile.cli.main.chat.tools.SemanticMemoryEngine semanticMemoryEngine;

    /** File watcher service for multi-agent file change notifications. */
    private volatile ai.kompile.cli.main.coordination.FileWatcherService fileWatcherService;

    /** Ambient memory gardener for background memory maintenance. */
    private volatile ai.kompile.cli.main.chat.tools.AmbientMemoryGardener ambientGardener;

    /** Skill registry for MCP prompts endpoint. */
    private volatile ai.kompile.cli.main.chat.skill.SkillRegistry skillRegistry;

    /** Centralized progress logger — writes all tool activity to ~/.kompile/logs/mcp-activity.log. */
    private volatile ai.kompile.cli.mcp.stdio.McpToolProgressLogger progressLogger;

    /** Async executor — runs tools in background when _background=true, supports polling. */
    private volatile ai.kompile.cli.mcp.stdio.AsyncToolExecutor asyncExecutor;

    /** Structured audit logger — writes MCP tool calls to the shared tool-call catalog. */
    private volatile ai.kompile.cli.mcp.stdio.McpToolAuditLogger auditLogger;

    /** CLI-side tool gateway — applies gateway rules outside the Spring MCP registry. */
    private volatile ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor gatewayInterceptor;

    /** Enforcer tool call guard — blocks tool calls that violate active enforcer policy. */
    private volatile ai.kompile.cli.main.chat.enforcer.EnforcerToolCallGuard enforcerGuard;

    /** Output writer for sending JSON-RPC notifications (stored as field for access from helpers). */
    private volatile OutputStreamWriter mcpOut;

    /** Thread pool for executing tool calls without blocking the main event loop.
     *  This prevents long-running tools (e.g. grep ~30s) from blocking ping responses,
     *  which would cause Claude Code to drop the MCP connection. */
    private final ExecutorService toolExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcp-tool-exec");
                t.setDaemon(true);
                return t;
            });

    /** ObjectMapper shared across the session. */
    private volatile ObjectMapper om;

    /** Current log level for notifications/message filtering. */
    private volatile String logLevel = "info";

    /** Flag set inside handleMessage when tools/list actually changed; notification sent after response flush. */
    private volatile boolean pendingToolsListChanged = false;

    /** Flag indicating background tool initialization is complete. */
    private final AtomicBoolean toolsReady = new AtomicBoolean(false);

    /** Tool definitions — populated by background init thread, read by event loop. */
    private final ConcurrentHashMap<String, ToolDef> tools = new ConcurrentHashMap<>();

    /** Ordered log levels for filtering comparison. */
    private static final List<String> LOG_LEVELS = List.of(
            "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    @Override
    public Integer call() {
        Path wd = workDir != null ? Paths.get(workDir) : Paths.get(System.getProperty("user.dir"));

        // Auto-detect kompile-app for tools that need the HTTP backend (RAG, GraphRAG).
        // When launched via stdio (no --url), probe common ports so those tools work
        // without requiring the user to pass --url explicitly.
        if (baseUrl == null || baseUrl.isBlank()) {
            String detected = ai.kompile.cli.main.chat.McpUrlResolver.resolveOnce(null, 0);
            if (detected != null) {
                // Strip /mcp/sse suffix — tools need the base URL (e.g. http://localhost:8080)
                baseUrl = detected.replaceAll("/mcp/sse$", "");
                System.err.println("[MCP] Auto-detected kompile-app at " + baseUrl);
            }
        }

        // Auto-start daemon if needed, then bridge — collapses N MCP processes into one
        if (!noDaemon) {
            ai.kompile.cli.main.serve.DaemonClient client =
                    ai.kompile.cli.main.serve.DaemonClient.ensureDaemon("mcp", wd);
            if (client != null) {
                try {
                    client.bridgeStdio();
                    return 0;
                } catch (Exception e) {
                    System.err.println("[MCP] Daemon bridge failed, falling back to in-process: " + e.getMessage());
                    // Fall through to in-process mode
                }
            }
        }

        // In-process mode — original behavior
        return runInProcess(wd);
    }

    private int runInProcess(Path wd) {
        try {
            // ── Protect stdout: MCP JSON-RPC transport ──────────────────────────
            // Save the real stdout for MCP protocol output, then redirect System.out
            // to stderr so that ANY library code (ND4J, Anserini, etc.) that calls
            // System.out.println() from background threads cannot corrupt the
            // JSON-RPC stream. This must happen before any background threads start.
            PrintStream realStdout = System.out;
            System.setOut(System.err);

            om = new ObjectMapper();

            sessionTracker = new ai.kompile.cli.mcp.stdio.McpSessionTracker(om);
            resultReferenceCache = new ai.kompile.cli.main.chat.tools.ToolResultReferenceCache();
            progressLogger = new ai.kompile.cli.mcp.stdio.McpToolProgressLogger();
            asyncExecutor = new ai.kompile.cli.mcp.stdio.AsyncToolExecutor(progressLogger);
            gatewayInterceptor = ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.fromConfig(om);
            auditLogger = new ai.kompile.cli.mcp.stdio.McpToolAuditLogger(
                    sessionTracker.getMetrics().getSessionId(),
                    "kompile-mcp-stdio",
                    "mcp-stdio",
                    wd,
                    om);

            // Load enforcer tool call guard from environment (if enforcer mode is active)
            enforcerGuard = ai.kompile.cli.main.chat.enforcer.EnforcerToolCallGuard.fromEnvironment(om);

            // ── CRITICAL: Start event loop FIRST, defer heavy init ──────────
            // Claude Code drops the MCP connection if the server doesn't respond
            // to 'initialize' quickly enough. We must start reading stdin and
            // responding to protocol messages immediately. All heavy initialization
            // (tool building, file watchers, coordination, semantic memory) runs
            // on a background thread and tools become available once it completes.
            //
            // Hook configuration for Claude Code (settings.local.json) is done by
            // PassthroughCommand/ChatCommand BEFORE launching the agent — writing
            // to that file after Claude starts triggers inotify and kills the MCP
            // connection.

            // Persist performance data and coordination state on shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (sessionTracker != null) sessionTracker.shutdown();
                if (coordinator != null) coordinator.shutdown();
                if (semanticMemoryEngine != null) semanticMemoryEngine.shutdown();
                if (fileWatcherService != null) fileWatcherService.stop();
                if (ambientGardener != null) ambientGardener.stop();
                if (asyncExecutor != null) asyncExecutor.shutdown();
                // Close cached IndexDatabase connections
                ai.kompile.cli.main.chat.tools.LocalCodeIndexTool.closeAll();
            }, "mcp-harness-shutdown"));

            dynamicToolManager = new ai.kompile.cli.main.chat.tools.DynamicToolManager();
            dynamicToolManager.setDynamicMode(false);

            // Tools map and readiness flag are instance fields (tools, toolsReady)
            // populated by the background init thread below.

            // Resolved profile for filtering
            String resolvedProfile = (profile != null && !profile.isBlank()) ? profile.toLowerCase().trim() : "full";

            // Two-phase initialization: fast tools first (unblocks tools/list quickly),
            // then slow tools (semantic memory, file watcher, delegation) on a second pass.
            // This prevents 30s timeout on MCP clients that call tools/list immediately.
            Thread initThread = new Thread(() -> {
                try {
                    // Platform hooks for non-Claude agents (Codex, OpenCode, Gemini)
                    new PlatformHooksInstaller(wd, System.err).installAll();

                    // Phase 1: Build all tools (fast + slow together)
                    Map<String, ToolDef> builtTools = buildToolMap(om, wd);

                    // Register with dynamic tool manager
                    for (ToolDef td : builtTools.values()) {
                        dynamicToolManager.register(td.name(), td.description(), td.schema());
                    }

                    // Apply profile filter
                    if (!"full".equals(resolvedProfile)) {
                        Set<String> profileToolIds;
                        if (PROFILE_TOOLS.containsKey(resolvedProfile) && !PROFILE_TOOLS.get(resolvedProfile).isEmpty()) {
                            profileToolIds = PROFILE_TOOLS.get(resolvedProfile);
                        } else {
                            profileToolIds = loadCustomProfile(resolvedProfile);
                        }
                        if (!profileToolIds.isEmpty()) {
                            builtTools.keySet().retainAll(new LinkedHashSet<>(profileToolIds));
                            System.err.println("[MCP] Profile '" + resolvedProfile + "': " + builtTools.size() + " tools active");
                        } else {
                            System.err.println("[MCP] Profile '" + resolvedProfile + "': unrecognized or empty — using full set");
                        }
                    }

                    // Publish tools — ConcurrentHashMap.putAll is safe for concurrent reads
                    tools.putAll(builtTools);
                    toolsReady.set(true);

                    System.err.println("[MCP] Kompile CLI MCP stdio server ready (" + tools.size() + " tools)");
                    System.err.println("[MCP] Activity log: " + progressLogger.getLogFile());
                    System.err.flush();
                } catch (Exception e) {
                    System.err.println("[MCP] Error during tool initialization: " + e.getMessage());
                    e.printStackTrace(System.err);
                    toolsReady.set(true); // unblock event loop even on error (with empty tools)
                }
            }, "mcp-tool-init");
            initThread.setDaemon(true);
            initThread.start();

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            mcpOut = new OutputStreamWriter(realStdout, StandardCharsets.UTF_8);

            // Defer startup log notification until after client sends notifications/initialized
            // (sending before handshake can corrupt the stream for some clients)

            String line;
            boolean clientInitialized = false;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonNode msg = om.readTree(line);

                    // Track when client has completed initialization
                    String msgMethod = msg.path("method").asText("");
                    if ("notifications/initialized".equals(msgMethod) && !clientInitialized) {
                        clientInitialized = true;
                        sendLogNotification("info", "Kompile MCP server started with " + tools.size() + " tools");
                    }

                    // Dispatch tool calls to a background thread so the main loop
                    // stays responsive to pings. Without this, a 30s grep blocks
                    // ping responses and Claude Code drops the MCP connection.
                    if ("tools/call".equals(msgMethod)) {
                        final JsonNode toolMsg = msg;
                        toolExecutor.submit(() -> {
                            try {
                                JsonNode response = handleMessage(toolMsg, tools, om);
                                if (response != null) {
                                    synchronized (mcpOut) {
                                        mcpOut.write(om.writeValueAsString(response) + "\n");
                                        mcpOut.flush();
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("[MCP] Error executing tool: " + e.getMessage());
                            } finally {
                                CTX.remove();
                            }
                        });
                    } else {
                        // Non-tool messages (ping, initialize, tools/list, notifications)
                        // are handled inline on the main thread for immediate response.
                        JsonNode response = handleMessage(msg, tools, om);
                        if (response != null) {
                            synchronized (mcpOut) {
                                mcpOut.write(om.writeValueAsString(response) + "\n");
                                mcpOut.flush();
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[MCP] Error handling message: " + e.getMessage());
                } finally {
                    CTX.remove();
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            toolExecutor.shutdownNow();
            if (sessionTracker != null) sessionTracker.shutdown();
            if (coordinator != null) coordinator.shutdown();
        }
        return 0;
    }

    private JsonNode handleMessage(JsonNode msg, Map<String, ToolDef> tools, ObjectMapper om) {
        JsonNode idNode = msg.get("id");
        JsonNode methodNode = msg.get("method");
        if (methodNode == null) return null;

        String method = methodNode.asText();
        JsonNode params = msg.get("params");

        try {
            ObjectNode result = om.createObjectNode();
            result.put("jsonrpc", "2.0");
            if (idNode != null) result.set("id", idNode);

            switch (method) {
                case "initialize" -> {
                    ObjectNode initResult = om.createObjectNode();
                    initResult.put("protocolVersion", "2024-11-05");
                    ObjectNode caps = initResult.putObject("capabilities");
                    // Tools with listChanged=false — Claude Code drops the MCP connection
                    // when it receives tools/list_changed notifications, so we disable the capability.
                    caps.putObject("tools").put("listChanged", false);
                    // Prompts — we expose skills as MCP prompts
                    caps.putObject("prompts").put("listChanged", false);
                    // Logging — we send notifications/message for server events
                    caps.putObject("logging");
                    ObjectNode serverInfo = initResult.putObject("serverInfo");
                    serverInfo.put("name", "kompile-cli");
                    serverInfo.put("version", "0.1.0-SNAPSHOT");
                    // Hint: tools should be loaded eagerly, not deferred
                    ObjectNode hints = serverInfo.putObject("x-hints");
                    hints.put("eagerLoadTools", true);
                    hints.put("toolCount", toolsReady.get() ? tools.size() : 44); // estimate until init completes
                    hints.put("profile", profile != null ? profile : "full");
                    result.set("result", initResult);
                }

                // Notifications — no response needed (no id)
                case "notifications/initialized", "notifications/cancelled",
                     "notifications/progress", "notifications/roots/list_changed" -> {
                    return null; // Notifications never get a response
                }

                case "tools/list" -> {
                    // Wait for background tool initialization to complete (max 90s for native image cold start)
                    if (!toolsReady.get()) {
                        long deadline = System.currentTimeMillis() + 90_000;
                        while (!toolsReady.get() && System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        }
                    }

                    ObjectNode toolsResult = om.createObjectNode();
                    var toolsArray = toolsResult.putArray("tools");

                    // Filter tools based on dynamic tool manager's active set
                    Set<String> activeIds = dynamicToolManager != null
                            ? dynamicToolManager.getActiveToolIds()
                            : tools.keySet();

                    // Build raw tool definitions (only active tools)
                    var rawDefs = om.createArrayNode();
                    // Keep annotations separate since optimizer doesn't handle them
                    Map<String, ai.kompile.cli.main.chat.tools.McpToolAnnotations> annotationsMap = new LinkedHashMap<>();
                    for (ToolDef td : tools.values()) {
                        if (!activeIds.contains(td.name())) continue;
                        var toolObj = rawDefs.addObject();
                        toolObj.put("name", td.name());
                        toolObj.put("description", td.description());
                        toolObj.set("inputSchema", td.schema());
                        if (td.annotations() != null) {
                            annotationsMap.put(td.name(), td.annotations());
                        }
                    }

                    // Apply schema optimization to reduce token footprint.
                    // Level is controlled by --schema-level (default: compact).
                    var optimized = ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.optimize(
                            rawDefs, resolveSchemaLevel());

                    int rawTokens = ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.estimateTokens(rawDefs);
                    int optTokens = ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.estimateTokens(optimized);
                    int totalTools = tools.size();
                    System.err.println("[MCP] tools/list: " + optimized.size() + "/" + totalTools
                            + " tools active, ~" + optTokens + " tokens (saved ~"
                            + (rawTokens - optTokens) + " from schema compression)");

                    // Add optimized tools with annotations
                    for (JsonNode toolNode : optimized) {
                        ObjectNode toolObj = (ObjectNode) toolNode;
                        String toolName = toolObj.path("name").asText();
                        ai.kompile.cli.main.chat.tools.McpToolAnnotations ann = annotationsMap.get(toolName);
                        if (ann != null) {
                            toolObj.set("annotations", ann.toJsonNode());
                        }
                        toolsArray.add(toolObj);
                    }
                    toolsResult.set("tools", toolsArray);
                    // Add metadata hints for clients that support eager tool loading
                    ObjectNode toolsMeta = toolsResult.putObject("_meta");
                    toolsMeta.put("eagerLoad", true);
                    toolsMeta.put("schemaLevel", schemaLevel != null ? schemaLevel : "compact");
                    toolsMeta.put("profile", profile != null ? profile : "full");
                    toolsMeta.put("totalTools", tools.size());
                    toolsMeta.put("activeTools", toolsArray.size());
                    result.set("result", toolsResult);
                }
                case "tools/call" -> {
                    // Wait for background tool initialization if needed
                    if (!toolsReady.get()) {
                        long deadline = System.currentTimeMillis() + 90_000;
                        while (!toolsReady.get() && System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                        }
                    }

                    String toolName = params.path("name").asText();
                    JsonNode args = params.path("arguments");

                    // Extract progress token from _meta if present
                    String progressToken = null;
                    JsonNode metaNode = params.path("_meta");
                    if (metaNode.isObject()) {
                        JsonNode pt = metaNode.get("progressToken");
                        if (pt != null && !pt.isNull()) {
                            progressToken = pt.isTextual() ? pt.asText() : String.valueOf(pt.asInt());
                        }
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> originalArgMap = args != null && !args.isMissingNode() && !args.isNull()
                            ? om.convertValue(args, Map.class)
                            : new LinkedHashMap<>();
                    if (originalArgMap == null) {
                        originalArgMap = new LinkedHashMap<>();
                    }
                    Map<String, Object> effectiveArgMap = new LinkedHashMap<>(originalArgMap);

                    ToolDef td = tools.get(toolName);
                    if (td == null) {
                        ObjectNode callResult = om.createObjectNode();
                        callResult.putArray("content").addObject().put("type", "text").put("text", "Unknown tool: " + toolName);
                        callResult.put("isError", true);
                        result.set("result", callResult);
                        if (sessionTracker != null) sessionTracker.recordToolCall(toolName, true, 0);
                        if (progressLogger != null) progressLogger.toolError("unknown", toolName, 0, "Unknown tool");
                        if (auditLogger != null) {
                            auditLogger.recordDecision(toolName, originalArgMap, null,
                                    "unknown_tool", "Unknown tool", true, 0);
                        }
                    } else {
                        String auditDecision = "executed";
                        String auditReason = null;

                        var gateway = gatewayInterceptor;
                        if (gateway != null && gateway.isEnabled()) {
                            var gatewayDecision = gateway.evaluate(toolName, effectiveArgMap);
                            if (gatewayDecision.action
                                    == ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.InterceptAction.BLOCK) {
                                ObjectNode callResult = om.createObjectNode();
                                String blockMsg = "BLOCKED by gateway: " + gatewayDecision.reason;
                                callResult.putArray("content").addObject().put("type", "text").put("text", blockMsg);
                                callResult.put("isError", true);
                                result.set("result", callResult);
                                if (sessionTracker != null) sessionTracker.recordToolCall(toolName, true, 0);
                                if (progressLogger != null) {
                                    progressLogger.toolError("gateway-blocked", toolName, 0, gatewayDecision.reason);
                                }
                                if (auditLogger != null) {
                                    auditLogger.recordDecision(toolName, originalArgMap, effectiveArgMap,
                                            "gateway_blocked", gatewayDecision.reason, true, 0);
                                }
                                return result;
                            } else if (gatewayDecision.action
                                    == ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.InterceptAction.REWRITE
                                    && gatewayDecision.rewrittenArgs != null) {
                                effectiveArgMap = new LinkedHashMap<>(gatewayDecision.rewrittenArgs);
                                auditDecision = "gateway_rewritten";
                                auditReason = gatewayDecision.reason;
                            }
                        }

                        // Enforcer gate: check the final tool call against policy BEFORE execution.
                        if (enforcerGuard != null && enforcerGuard.isActive()) {
                            var guardDecision = enforcerGuard.evaluate(toolName, effectiveArgMap);
                            if (!guardDecision.isAllowed()) {
                                ObjectNode callResult = om.createObjectNode();
                                String blockMsg = "BLOCKED by enforcer: " + guardDecision.blockMessage();
                                if (!guardDecision.getCorrectionPrompt().isEmpty()) {
                                    blockMsg += "\n\n" + guardDecision.getCorrectionPrompt();
                                }
                                callResult.putArray("content").addObject().put("type", "text").put("text", blockMsg);
                                callResult.put("isError", true);
                                result.set("result", callResult);
                                if (sessionTracker != null) sessionTracker.recordToolCall(toolName, true, 0);
                                if (progressLogger != null) progressLogger.toolError("enforcer-blocked", toolName, 0, guardDecision.blockMessage());
                                if (auditLogger != null) {
                                    auditLogger.recordDecision(toolName, originalArgMap, effectiveArgMap,
                                            "enforcer_blocked", guardDecision.blockMessage(), true, 0);
                                }
                                return result;
                            } else if (guardDecision.isRewrite() && guardDecision.getRewrittenArgs() != null) {
                                effectiveArgMap = new LinkedHashMap<>(guardDecision.getRewrittenArgs());
                                auditDecision = "enforcer_rewritten";
                                auditReason = appendAuditReason(auditReason, guardDecision.getReason());
                            }
                        }

                        // Send progress start notification
                        if (progressToken != null) {
                            sendProgress(progressToken, 0, 1);
                        }

                        Map<String, Object> argMap = new LinkedHashMap<>(effectiveArgMap);

                        // Check for _background mode — run tool async and return immediately.
                        // Delegation tools (task, multi_task, quorum_task) are ALWAYS async
                        // because they spawn external agent processes that can take minutes.
                        boolean background = argMap != null
                                && Boolean.TRUE.equals(argMap.remove("_background"));
                        // Force async for delegation tools to avoid client-side timeouts
                        if (!background && td.annotations() == ai.kompile.cli.main.chat.tools.McpToolAnnotations.DELEGATION) {
                            background = true;
                        }

                        if (background && asyncExecutor != null) {
                            final Map<String, Object> finalArgs = argMap;
                            final Map<String, Object> auditOriginal = new LinkedHashMap<>(originalArgMap);
                            final Map<String, Object> auditEffective = new LinkedHashMap<>(argMap);
                            final String backgroundAuditDecision = "executed".equals(auditDecision)
                                    ? "background"
                                    : auditDecision + "+background";
                            final String backgroundAuditReason = auditReason;
                            String taskId = asyncExecutor.submit(toolName, () -> {
                                long backgroundStart = System.currentTimeMillis();
                                try {
                                    var tr = td.executor().apply(finalArgs);
                                    long backgroundDuration = System.currentTimeMillis() - backgroundStart;
                                    if (sessionTracker != null) {
                                        sessionTracker.recordToolCall(toolName, tr.isError(), backgroundDuration);
                                    }
                                    if (auditLogger != null) {
                                        auditLogger.recordDecision(toolName, auditOriginal, auditEffective,
                                                backgroundAuditDecision, backgroundAuditReason,
                                                tr.isError(), backgroundDuration);
                                    }
                                    return tr;
                                } catch (Exception e) {
                                    long backgroundDuration = System.currentTimeMillis() - backgroundStart;
                                    if (sessionTracker != null) {
                                        sessionTracker.recordToolCall(toolName, true, backgroundDuration);
                                    }
                                    if (auditLogger != null) {
                                        auditLogger.recordDecision(toolName, auditOriginal, auditEffective,
                                                backgroundAuditDecision, appendAuditReason(backgroundAuditReason, e.getMessage()),
                                                true, backgroundDuration);
                                    }
                                    throw e;
                                }
                            });

                            ObjectNode callResult = om.createObjectNode();
                            var content = callResult.putArray("content");
                            var textObj = content.addObject();
                            textObj.put("type", "text");
                            textObj.put("text", "Tool '" + toolName + "' running in background.\n\n"
                                    + "**Task ID**: `" + taskId + "`\n"
                                    + "**Poll**: Call `poll` tool with `task_id=\"" + taskId + "\"`\n"
                                    + "**Log**: `tail -f " + (progressLogger != null ? progressLogger.getLogFile() : "~/.kompile/logs/mcp-activity.log") + "`");
                            callResult.put("isError", false);
                            result.set("result", callResult);

                        } else if ("poll".equals(toolName) && asyncExecutor != null) {
                            // Poll is handled inline — see tool registration below
                            long callStart = System.currentTimeMillis();
                            var tr = td.executor().apply(argMap);
                            long callDuration = System.currentTimeMillis() - callStart;
                            if (sessionTracker != null) sessionTracker.recordToolCall(toolName, tr.isError(), callDuration);
                            if (auditLogger != null) {
                                auditLogger.recordDecision(toolName, originalArgMap, argMap,
                                        auditDecision, auditReason, tr.isError(), callDuration);
                            }
                            result.set("result", buildCallResult(tr));

                        } else {
                            // Standard synchronous execution with logging
                            String callId = progressLogger != null
                                    ? progressLogger.toolStart(toolName, argMap)
                                    : null;

                            long callStart = System.currentTimeMillis();
                            var tr = td.executor().apply(argMap);
                            long callDuration = System.currentTimeMillis() - callStart;

                            // Log completion
                            if (progressLogger != null && callId != null) {
                                if (tr.isError()) {
                                    progressLogger.toolError(callId, toolName, callDuration,
                                            tr.getOutput() != null ? tr.getOutput().lines().findFirst().orElse("") : "");
                                } else {
                                    progressLogger.toolComplete(callId, toolName, callDuration,
                                            ai.kompile.cli.mcp.stdio.McpToolProgressLogger.summarizeResult(tr));
                                }
                            }

                            if (sessionTracker != null) sessionTracker.recordToolCall(toolName, tr.isError(), callDuration);
                            if (auditLogger != null) {
                                auditLogger.recordDecision(toolName, originalArgMap, argMap,
                                        auditDecision, auditReason, tr.isError(), callDuration);
                            }

                            // Send progress complete notification
                            if (progressToken != null) {
                                sendProgress(progressToken, 1, 1);
                            }

                            // Auto-cache large results using reference handles
                            if (!tr.isError() && resultReferenceCache != null
                                    && tr.getOutput() != null
                                    && resultReferenceCache.shouldCache(tr.getOutput())) {
                                tr = resultReferenceCache.storeAndSummarize(
                                        toolName, tr.getTitle(), tr.getOutput(), tr.getMetadata());
                            }

                            result.set("result", buildCallResult(tr));
                        }
                    }
                }

                // Empty list responses for capabilities we don't support —
                // clients may probe these even when not declared in capabilities.
                // Returning nothing would cause the client to hang waiting for a response.
                case "resources/list" -> {
                    ObjectNode listResult = om.createObjectNode();
                    listResult.putArray("resources");
                    result.set("result", listResult);
                }
                case "resources/templates/list" -> {
                    ObjectNode listResult = om.createObjectNode();
                    listResult.putArray("resourceTemplates");
                    result.set("result", listResult);
                }

                // ── MCP Prompts: expose skills as prompts ──────────────────
                case "prompts/list" -> {
                    ObjectNode promptsResult = om.createObjectNode();
                    ArrayNode promptsArray = promptsResult.putArray("prompts");

                    if (skillRegistry != null) {
                        for (var skill : skillRegistry.all()) {
                            ObjectNode prompt = promptsArray.addObject();
                            prompt.put("name", skill.getName());
                            prompt.put("description", skill.getDescription());

                            // Add arguments schema — all skills accept a free-form 'args' argument
                            ArrayNode arguments = prompt.putArray("arguments");
                            ObjectNode argsArg = arguments.addObject();
                            argsArg.put("name", "args");
                            argsArg.put("description", "Arguments to pass to the skill (e.g. file paths, options)");
                            argsArg.put("required", false);
                        }
                    }
                    result.set("result", promptsResult);
                }
                case "prompts/get" -> {
                    String promptName = params != null ? params.path("name").asText("") : "";
                    if (skillRegistry == null || promptName.isEmpty()) {
                        ObjectNode error = result.putObject("error");
                        error.put("code", -32602);
                        error.put("message", "Unknown prompt: " + promptName);
                    } else {
                        var skill = skillRegistry.get(promptName);
                        if (skill == null) {
                            ObjectNode error = result.putObject("error");
                            error.put("code", -32602);
                            error.put("message", "Unknown prompt: " + promptName);
                        } else {
                            // Extract args from params.arguments map
                            String argsValue = "";
                            JsonNode argsNode = params.path("arguments");
                            if (argsNode.isObject()) {
                                JsonNode av = argsNode.get("args");
                                if (av != null && av.isTextual()) {
                                    argsValue = av.asText();
                                }
                            }
                            String expanded = skill.expandTemplate(argsValue);

                            ObjectNode getResult = om.createObjectNode();
                            getResult.put("description", skill.getDescription());
                            ArrayNode messages = getResult.putArray("messages");
                            ObjectNode userMsg = messages.addObject();
                            userMsg.put("role", "user");
                            ObjectNode contentObj = userMsg.putObject("content");
                            contentObj.put("type", "text");
                            contentObj.put("text", expanded);
                            result.set("result", getResult);
                        }
                    }
                }

                // ── Logging ────────────────────────────────────────────────
                case "logging/setLevel" -> {
                    if (params != null && params.has("level")) {
                        String level = params.get("level").asText("info").toLowerCase();
                        if (LOG_LEVELS.contains(level)) {
                            logLevel = level;
                            sendLogNotification("info", "Log level set to: " + level);
                        }
                    }
                    result.set("result", om.createObjectNode());
                }

                case "ping" -> result.set("result", om.createObjectNode());

                default -> {
                    // For requests (have an id), return a proper JSON-RPC error
                    // so the client doesn't hang waiting for a response.
                    // For notifications (no id), silently ignore.
                    if (idNode != null) {
                        ObjectNode error = result.putObject("error");
                        error.put("code", -32601);
                        error.put("message", "Method not found: " + method);
                    } else {
                        return null;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            ObjectNode errResp = om.createObjectNode();
            errResp.put("jsonrpc", "2.0");
            if (idNode != null) errResp.set("id", idNode);
            ObjectNode error = errResp.putObject("error");
            error.put("code", -32603);
            error.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return errResp;
        }
    }

    // ── Auto-configure Claude Code hooks ─────────────────────────────────────

    /**
     * Automatically ensures Claude Code hooks are configured for kompile MCP tool visibility.
     * Writes PreToolUse/PostToolUse hooks into {@code .claude/settings.local.json} (not committed)
     * so users see tool name, parameters, and timing for every kompile tool call.
     *
     * <p>Uses inline bash+jq one-liners — no external scripts needed. Idempotent:
     * skips if hooks are already present.</p>
     */
    private void ensureHooksConfigured(Path wd) {
        try {
            Path settingsDir = wd.resolve(".claude");
            Path settingsFile = settingsDir.resolve("settings.local.json");

            // Read existing settings (if any)
            ObjectNode settings;
            if (Files.exists(settingsFile)) {
                String existing = Files.readString(settingsFile);
                JsonNode parsed = om.readTree(existing);
                if (parsed.isObject()) {
                    settings = (ObjectNode) parsed;
                } else {
                    settings = om.createObjectNode();
                }
                // Remove any existing kompile hooks so we always write the latest version.
                // This ensures hook format updates propagate automatically on MCP server restart.
                JsonNode hooks = settings.get("hooks");
                if (hooks != null && hooks.isObject()) {
                    removeKompileMatchers((ObjectNode) hooks, "PreToolUse");
                    removeKompileMatchers((ObjectNode) hooks, "PostToolUse");
                }
            } else {
                settings = om.createObjectNode();
            }

            // Build hook commands — inline bash+jq for input parsing, stderr-only output.
            // Hooks are display-only: they log tool name, params, and timing to stderr.
            // They do NOT emit permissionDecision — approval is left to the user.

            // Per-project temp file for timing to avoid cross-project contamination
            String wdHash = Integer.toHexString(wd.toAbsolutePath().toString().hashCode() & 0x7fffffff);
            String tsFile = "/tmp/.kompile_hook_ts_" + wdHash;

            // PreToolUse: show tool name + key params
            String preCmd = "bash -c '"
                    + "INPUT=$(cat); "
                    + "TN=$(echo \"$INPUT\" | jq -r \".tool_name // \\\"unknown\\\"\"); "
                    + "SHORT=${TN#mcp__kompile__}; "
                    + "PARAMS=$(echo \"$INPUT\" | jq -rc \".tool_input // {}\" | head -c 120); "
                    + "echo \"$(($(date +%s%N)/1000000))\" > " + tsFile + "; "
                    + "echo >&2 \"[kompile] $SHORT | $PARAMS\""
                    + "'";

            // PostToolUse: show completion + elapsed time
            String postCmd = "bash -c '"
                    + "INPUT=$(cat); "
                    + "TN=$(echo \"$INPUT\" | jq -r \".tool_name // \\\"unknown\\\"\"); "
                    + "SHORT=${TN#mcp__kompile__}; "
                    + "END=$(($(date +%s%N)/1000000)); "
                    + "START=$(cat " + tsFile + " 2>/dev/null || echo $END); "
                    + "MS=$((END - START)); "
                    + "if [ $MS -gt 1000 ]; then FMT=\"$((MS/1000)).$((MS%1000/100))s\"; else FMT=\"${MS}ms\"; fi; "
                    + "echo >&2 \"[kompile] $SHORT done ($FMT)\""
                    + "'";

            // Merge hooks into settings
            ObjectNode hooks = settings.has("hooks") && settings.get("hooks").isObject()
                    ? (ObjectNode) settings.get("hooks")
                    : settings.putObject("hooks");

            // PreToolUse array
            ArrayNode preArray = hooks.has("PreToolUse") && hooks.get("PreToolUse").isArray()
                    ? (ArrayNode) hooks.get("PreToolUse")
                    : hooks.putArray("PreToolUse");

            // --- kompile MCP tool progress hooks ---
            ObjectNode preMatcher = om.createObjectNode();
            preMatcher.put("matcher", ".*");
            ArrayNode preHooks = preMatcher.putArray("hooks");
            preHooks.addObject().put("type", "command").put("command", preCmd);
            preArray.add(preMatcher);

            // PostToolUse array
            ArrayNode postArray = hooks.has("PostToolUse") && hooks.get("PostToolUse").isArray()
                    ? (ArrayNode) hooks.get("PostToolUse")
                    : hooks.putArray("PostToolUse");

            ObjectNode postMatcher = om.createObjectNode();
            postMatcher.put("matcher", ".*");
            ArrayNode postHooks = postMatcher.putArray("hooks");
            postHooks.addObject().put("type", "command").put("command", postCmd);
            postArray.add(postMatcher);

            // Write settings only if content actually changed.
            // Writing unconditionally triggers an inotify event on settings.local.json,
            // which causes Claude Code to restart MCP connections — creating a death spiral
            // where each restart spawns a new MCP server that rewrites the file again.
            Files.createDirectories(settingsDir);
            String newContent = om.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
            String existingContent = Files.exists(settingsFile)
                    ? Files.readString(settingsFile) : "";
            if (!newContent.equals(existingContent)) {
                Files.writeString(settingsFile, newContent);
                System.err.println("[MCP] Auto-configured Claude Code hooks in " + settingsFile);
                System.err.println("[MCP] Tool calls will show name, params, and timing in the terminal");
            } else {
                System.err.println("[MCP] Claude Code hooks already up-to-date");
            }

        } catch (Exception e) {
            // Non-fatal — hooks are a nice-to-have, not required
            System.err.println("[MCP] Could not auto-configure hooks: " + e.getMessage());
        }
    }

    /** Remove any existing kompile-managed matcher entries from a hook array so they can be re-created fresh. */
    private static void removeKompileMatchers(ObjectNode hooks, String hookEvent) {
        JsonNode arr = hooks.get(hookEvent);
        if (arr == null || !arr.isArray()) return;
        ArrayNode arrayNode = (ArrayNode) arr;
        for (int i = arrayNode.size() - 1; i >= 0; i--) {
            JsonNode entry = arrayNode.get(i);
            String matcher = entry.path("matcher").asText("");
            if (matcher.contains("mcp__kompile") || hasKompileHookCommand(entry)) {
                arrayNode.remove(i);
            }
        }
    }

    private static boolean hasKompileHookCommand(JsonNode entry) {
        JsonNode hooks = entry.path("hooks");
        if (!hooks.isArray()) {
            return false;
        }
        for (JsonNode hook : hooks) {
            String command = hook.path("command").asText("");
            if (command.contains("[kompile]") || command.contains("Kompile MCP")) {
                return true;
            }
        }
        return false;
    }

    // ── Notification helpers ────────────────────────────────────────────────

    /** Send a JSON-RPC notification (no id, no response expected). */
    private void sendNotification(String method, ObjectNode params) {
        if (mcpOut == null || om == null) return;
        try {
            ObjectNode notification = om.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) {
                notification.set("params", params);
            }
            synchronized (mcpOut) {
                mcpOut.write(om.writeValueAsString(notification) + "\n");
                mcpOut.flush();
            }
        } catch (Exception e) {
            System.err.println("[MCP] Failed to send notification " + method + ": " + e.getMessage());
        }
    }

    /** Send notifications/tools/list_changed to inform clients the tool list has changed. */
    private void sendToolsListChanged() {
        sendNotification("notifications/tools/list_changed", null);
    }

    /** Send notifications/progress for a tool call with a progress token. */
    private void sendProgress(String progressToken, int progress, int total) {
        ObjectNode params = om.createObjectNode();
        params.put("progressToken", progressToken);
        params.put("progress", progress);
        params.put("total", total);
        sendNotification("notifications/progress", params);
    }

    /** Send notifications/message (MCP logging notification). */
    private void sendLogNotification(String level, String message) {
        // Filter based on current log level
        int currentIdx = LOG_LEVELS.indexOf(logLevel);
        int msgIdx = LOG_LEVELS.indexOf(level);
        if (msgIdx < currentIdx) return;

        ObjectNode params = om.createObjectNode();
        params.put("level", level);
        ObjectNode data = params.putObject("data");
        data.put("message", message);
        data.put("timestamp", System.currentTimeMillis());
        sendNotification("notifications/message", params);
    }

    private static String appendAuditReason(String existing, String next) {
        if (next == null || next.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return next;
        }
        return existing + "; " + next;
    }

    // ── Profile & schema helpers ────────────────────────────────────────────

    /**
     * Resolve the schema optimization level from the --schema-level CLI option.
     * Defaults to COMPACT if the option is unrecognized.
     */
    private ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel resolveSchemaLevel() {
        if (schemaLevel == null) return ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel.COMPACT;
        return switch (schemaLevel.toLowerCase().trim()) {
            case "none"       -> ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel.NONE;
            case "moderate"   -> ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel.MODERATE;
            case "aggressive" -> ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel.AGGRESSIVE;
            default           -> ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer.OptimizationLevel.COMPACT;
        };
    }

    /**
     * Load a custom tool profile from {@code ~/.kompile/config/mcp-profiles/<name>.json}.
     * The JSON file should be an array of tool ID strings, e.g. ["read","write","grep"].
     * Returns an empty set if the file doesn't exist or cannot be parsed.
     */
    private Set<String> loadCustomProfile(String profileName) {
        try {
            Path profilesDir = Paths.get(
                    System.getProperty("user.home"), ".kompile", "config", "mcp-profiles");
            Path profileFile = profilesDir.resolve(profileName + ".json");
            if (!Files.exists(profileFile)) {
                System.err.println("[MCP] Custom profile file not found: " + profileFile);
                return Set.of();
            }
            String content = Files.readString(profileFile, StandardCharsets.UTF_8);
            JsonNode root = om.readTree(content);
            Set<String> toolIds = new LinkedHashSet<>();
            if (root.isArray()) {
                root.forEach(n -> { if (n.isTextual()) toolIds.add(n.asText()); });
            }
            System.err.println("[MCP] Loaded custom profile '" + profileName + "': " + toolIds.size() + " tools");
            return toolIds;
        } catch (Exception e) {
            System.err.println("[MCP] Failed to load custom profile '" + profileName + "': " + e.getMessage());
            return Set.of();
        }
    }

    // ── Tool building ───────────────────────────────────────────────────────

    private Map<String, ToolDef> buildToolMap(ObjectMapper om, Path wd) {
        long t0 = System.currentTimeMillis();
        Map<String, ToolDef> tools = new LinkedHashMap<>();

        // Reuse SharedResourcePool to avoid duplicating registry/config construction
        // that the daemon path already consolidates. Saves ~35MB of redundant class loading.
        var pool = new ai.kompile.cli.main.serve.SharedResourcePool(wd);
        var agentRegistry = pool.agentRegistry();
        var roleManager = pool.roleManager();
        var processManager = new ai.kompile.cli.main.chat.tools.BackgroundProcessManager(System.getProperty("user.dir"));
        var subagentRunner = new ai.kompile.cli.mcp.stdio.DirectSubagentRunnerStdio(wd, roleManager);
        System.err.println("[MCP] SharedResourcePool: " + (System.currentTimeMillis() - t0) + "ms");

        // ── Skills injection for subagents (from shared pool) ────────────
        try {
            skillRegistry = pool.skillRegistry();
            var skillsInjection = new ai.kompile.cli.main.chat.skill.SkillsInjection(skillRegistry, wd);
            subagentRunner.setSkillsInjection(skillsInjection);
            System.err.println("[MCP] Skills injection configured (" + skillRegistry.all().size() + " skills, exposed as MCP prompts)");
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not configure skills injection: " + e.getMessage());
        }

        // ── System prompt injection for subagents ─────────────────────────
        try {
            var spm = ai.kompile.cli.main.chat.config.SystemPromptManager.resolve(null, null, null);
            if (spm != null) {
                subagentRunner.setSystemPromptManager(spm);
                System.err.println("[MCP] System prompt injection configured");
            }
        } catch (Exception e) {
            System.err.println("[MCP] Warning: Could not configure system prompt injection: " + e.getMessage());
        }

        // ── Coordination state manager ────────────────────────────────────
        String coordSessionId = "mcp-stdio-" + System.currentTimeMillis();
        coordinator = new ai.kompile.cli.main.coordination.CoordinationStateManager(wd, coordSessionId, om);
        System.err.println("[MCP] Core init: " + (System.currentTimeMillis() - t0) + "ms");

        // ── File I/O tools ─────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ReadTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.WriteTool(coordinator), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.EditTool(coordinator), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.PatchTool(), om, wd);

        // ── Search tools ───────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.GrepTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.GlobTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ListTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ExploreTool(), om, wd);

        // ── Result reference cache ────────────────────────────────────────
        if (resultReferenceCache != null) {
            registerCliTool(tools,
                    new ai.kompile.cli.main.chat.tools.FetchResultTool(resultReferenceCache), om, wd);
        }

        // ── Dynamic tool activation ──────────────────────────────────────
        if (dynamicToolManager != null) {
            registerCliTool(tools,
                    new ai.kompile.cli.main.chat.tools.ActivateToolsTool(dynamicToolManager), om, wd);
        }

        // ── Execution tools ────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.BashTool(), om, wd);

        // ── Network tools ──────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.WebFetchTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.WebSearchTool(), om, wd);

        // ── Workflow tools ─────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.TodoWriteTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.TodoReadTool(), om, wd);

        // ── Knowledge & memory tools ───────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.TranscriptSearchTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ConversationImportTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.MemoryTool(), om, wd);

        // ── Config tools ──────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ConfigArchiveTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ProjectConfigTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.EnforcerConfigTool(), om, wd);

        // ── Test milestone tracking ───────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.TestMilestoneTool(), om, wd);

        // ── Code search & graph (local fallback when no kompile-app backend) ──
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.CodeSearchTool(baseUrl, om), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.CodeGraphTool(baseUrl, om), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.LocalCodeIndexTool(), om, wd);

        // ── Tool call catalog (search/index tool usage across sessions) ────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ToolCallCatalogTool(), om, wd);

        // ── RAG & Graph search (require kompile-app backend) ──────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.RagSearchTool(baseUrl, om), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.GraphRagSearchTool(baseUrl, om), om, wd);

        // ── Process management ─────────────────────────────────────────────
        var procTool = new ai.kompile.cli.main.chat.tools.ProcessManagementTool(processManager);
        tools.put(procTool.id(), new ToolDef(procTool.id(), procTool.description(), procTool.parameterSchema(),
            procTool.mcpAnnotations(),
            args -> { try { return procTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        // ── Edit coordination ─────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.EditCoordinatorTool(coordinator), om, wd);

        // ── Semantic memory (passive vector retrieval) ────────────────────
        long tSem = System.currentTimeMillis();
        semanticMemoryEngine = new ai.kompile.cli.main.chat.tools.SemanticMemoryEngine();
        semanticMemoryEngine.initialize();
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.SemanticMemoryTool(semanticMemoryEngine), om, wd);
        System.err.println("[MCP] SemanticMemoryEngine: " + (System.currentTimeMillis() - tSem) + "ms");

        // ── File activity tracking (multi-agent file change notifications) ──
        try {
            fileWatcherService = new ai.kompile.cli.main.coordination.FileWatcherService(wd);
            fileWatcherService.start();
            registerCliTool(tools, new ai.kompile.cli.main.chat.tools.FileActivityTool(fileWatcherService), om, wd);
        } catch (IOException e) {
            System.err.println("[MCP] Warning: FileWatcherService not available: " + e.getMessage());
        }

        // ── Browser automation (CDP-based) ────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.BrowserTool(), om, wd);

        // ── Ambient memory gardening ──────────────────────────────────────
        Path memoryDir = Paths.get(System.getProperty("user.home"), ".kompile", "memory");
        ambientGardener = new ai.kompile.cli.main.chat.tools.AmbientMemoryGardener(memoryDir);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.AmbientGardenTool(ambientGardener), om, wd);

        // ── Persistent server mode ────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ServerModeTool(), om, wd);

        // ── Side panel (TUI auxiliary display) ────────────────────────────
        var sidePanelManager = new ai.kompile.cli.main.chat.tui.SidePanelManager();
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.SidePanelTool(sidePanelManager), om, wd);

        // ── Dictation / voice input ───────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.DictationTool(), om, wd);

        // ── Delegation tools ───────────────────────────────────────────────
        var taskTool = new ai.kompile.cli.mcp.stdio.StdioTaskTool(agentRegistry, subagentRunner, om, roleManager, coordinator);
        tools.put(taskTool.id(), new ToolDef(taskTool.id(), taskTool.description(), taskTool.parameterSchema(),
            ai.kompile.cli.main.chat.tools.McpToolAnnotations.DELEGATION,
            args -> { try { return taskTool.execute(args); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        var quorumTool = new ai.kompile.cli.mcp.stdio.StdioQuorumTaskTool(agentRegistry, subagentRunner, om, wd);
        tools.put(quorumTool.id(), new ToolDef(quorumTool.id(), quorumTool.description(), quorumTool.parameterSchema(),
            ai.kompile.cli.main.chat.tools.McpToolAnnotations.DELEGATION,
            args -> { try { return quorumTool.execute(args); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        var multiTool = new ai.kompile.cli.mcp.stdio.StdioMultiTaskTool(agentRegistry, subagentRunner, om, wd, roleManager, coordinator);
        tools.put(multiTool.id(), new ToolDef(multiTool.id(), multiTool.description(), multiTool.parameterSchema(),
            ai.kompile.cli.main.chat.tools.McpToolAnnotations.DELEGATION,
            args -> { try { return multiTool.execute(args); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        var rmTool = new ai.kompile.cli.main.chat.tools.RoleManagerTool(roleManager, om);
        tools.put(rmTool.id(), new ToolDef(rmTool.id(), rmTool.description(), rmTool.parameterSchema(),
            rmTool.mcpAnnotations(),
            args -> { try { return rmTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        var skillTool = new ai.kompile.cli.main.chat.tools.SkillManagerTool(om, wd);
        tools.put(skillTool.id(), new ToolDef(skillTool.id(), skillTool.description(), skillTool.parameterSchema(),
            skillTool.mcpAnnotations(),
            args -> { try { return skillTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        // ── Conversation resume ────────────────────────────────────────────
        try {
            var resumeTool = new ai.kompile.cli.main.chat.tools.ResumeTool(true);
            tools.put(resumeTool.id(), new ToolDef(resumeTool.id(), resumeTool.description(), resumeTool.parameterSchema(),
                resumeTool.mcpAnnotations(),
                args -> { try { return resumeTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));
        } catch (IOException e) {
            System.err.println("[MCP] Warning: ResumeTool not available: " + e.getMessage());
        }

        // ── Performance harness ───────────────────────────────────────────
        var harnessTool = new ai.kompile.cli.mcp.stdio.StdioHarnessTool(om, sessionTracker);
        tools.put(harnessTool.id(), new ToolDef(harnessTool.id(), harnessTool.description(), harnessTool.parameterSchema(),
            ai.kompile.cli.main.chat.tools.McpToolAnnotations.READ_ONLY,
            args -> { try { return harnessTool.execute(args); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        // Wire session tracker into subagent runner for post-completion evaluation
        subagentRunner.setSessionTracker(sessionTracker);


        // ── Poll tool (check status of background tool executions) ───────
        {
            ObjectNode pollSchema = om.createObjectNode();
            pollSchema.put("type", "object");
            ObjectNode pollProps = pollSchema.putObject("properties");
            pollProps.putObject("task_id").put("type", "string")
                    .put("description", "Task ID to check (returned from _background calls). Omit to list all active tasks.");
            pollProps.putObject("action").put("type", "string")
                    .put("description", "Action: 'status' (default), 'list' (all tasks), 'log' (recent log lines)");
            pollProps.putObject("lines").put("type", "integer")
                    .put("description", "Number of recent log lines to return (default 30, max 200). Used with action='log'.");

            tools.put("poll", new ToolDef("poll",
                    "Check status of background tool executions or view the MCP activity log. " +
                    "Any tool can be run in background by adding _background=true to its arguments. " +
                    "Use this tool to poll for results or view real-time progress from the log file.",
                    pollSchema,
                    ai.kompile.cli.main.chat.tools.McpToolAnnotations.READ_ONLY,
                    args -> {
                        try {
                            return executePoll(args);
                        } catch (Exception e) {
                            return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage());
                        }
                    }));
        }

        // ── Custom tools (filesystem convention) ─────────────────────────
        // Off by default for security. Enable via env var or system property.
        boolean customToolsEnabled = Boolean.parseBoolean(
                System.getProperty("kompile.custom.tools.enabled",
                        System.getenv().getOrDefault("KOMPILE_CUSTOM_TOOLS_ENABLED", "false")));
        if (customToolsEnabled) {
            var customLoader = new ai.kompile.cli.main.chat.tools.custom.CustomToolLoader(wd, om);
            var customDefs = customLoader.loadAll();
            if (!customDefs.isEmpty()) {
                for (var def : customDefs.values()) {
                    var bridge = new ai.kompile.cli.main.chat.tools.custom.CustomToolBridge(def);
                    registerCliTool(tools, bridge, om, wd);
                    if (dynamicToolManager != null) {
                        dynamicToolManager.registerCustomToolId(bridge.id());
                    }
                }
                System.err.println("[MCP] Loaded " + customDefs.size() + " custom tool(s) from filesystem");
            }
        }

        System.err.println("[MCP] buildToolMap total: " + (System.currentTimeMillis() - t0) + "ms (" + tools.size() + " tools)");
        return tools;
    }

    /** Execute the poll meta-tool for checking background tasks and viewing logs. */
    private ai.kompile.cli.main.chat.tools.ToolResult executePoll(Map<String, Object> args) throws Exception {
        String action = args != null ? String.valueOf(args.getOrDefault("action", "status")) : "status";
        String taskId = args != null ? (String) args.get("task_id") : null;

        switch (action) {
            case "log" -> {
                // Return recent lines from the activity log
                int lines = 30;
                if (args != null && args.get("lines") != null) {
                    lines = Math.min(200, Math.max(1, Integer.parseInt(String.valueOf(args.get("lines")))));
                }
                if (progressLogger == null) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.error("Progress logger not initialized");
                }
                Path logFile = progressLogger.getLogFile();
                if (!Files.exists(logFile)) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: log",
                            "No activity log yet. Tools will be logged to: " + logFile);
                }
                List<String> allLines = Files.readAllLines(logFile);
                int start = Math.max(0, allLines.size() - lines);
                StringBuilder sb = new StringBuilder();
                sb.append("Recent MCP activity (last ").append(Math.min(lines, allLines.size()))
                        .append(" of ").append(allLines.size()).append(" entries):\n");
                sb.append("Log file: ").append(logFile).append("\n\n");
                for (int i = start; i < allLines.size(); i++) {
                    sb.append(allLines.get(i)).append("\n");
                }
                return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: log", sb.toString());
            }

            case "list" -> {
                if (asyncExecutor == null) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.error("Async executor not initialized");
                }
                var allTasks = asyncExecutor.getAllTasks();
                if (allTasks.isEmpty()) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: list",
                            "No background tasks. Run any tool with `_background: true` to execute it asynchronously.");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Background tasks (").append(allTasks.size()).append("):\n\n");
                for (var t : allTasks) {
                    sb.append("- **").append(t.taskId()).append("** [").append(t.status()).append("] ")
                            .append(t.toolName()).append(" — ").append(t.resultSummary()).append("\n");
                }
                return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: list", sb.toString());
            }

            default -> {
                // status — check specific task or show active
                if (asyncExecutor == null) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.error("Async executor not initialized");
                }
                if (taskId != null && !taskId.isEmpty()) {
                    var status = asyncExecutor.getStatus(taskId);
                    if (status == null) {
                        return ai.kompile.cli.main.chat.tools.ToolResult.error(
                                "Unknown task ID: " + taskId + ". Use action='list' to see all tasks.");
                    }
                    if (status.result() != null) {
                        // Task completed — return the full result
                        var tr = status.result();
                        String title = tr.getTitle() != null ? tr.getTitle() : "";
                        return ai.kompile.cli.main.chat.tools.ToolResult.success(
                                "poll: " + taskId + " [" + status.status() + "]",
                                "Task completed in " + status.elapsedMs() + "ms\n\n" +
                                (title.isEmpty() ? "" : title + "\n") +
                                (tr.getOutput() != null ? tr.getOutput() : ""),
                                Map.of("taskId", taskId, "status", status.status(),
                                        "elapsedMs", status.elapsedMs()));
                    }
                    return ai.kompile.cli.main.chat.tools.ToolResult.success(
                            "poll: " + taskId + " [" + status.status() + "]",
                            "Tool: " + status.toolName() + "\nStatus: " + status.status() +
                            "\nElapsed: " + status.elapsedMs() + "ms\n" + status.resultSummary(),
                            Map.of("taskId", taskId, "status", status.status(),
                                    "elapsedMs", status.elapsedMs()));
                }

                // No specific task — show all active
                var active = asyncExecutor.getActiveTasks();
                if (active.isEmpty()) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: status",
                            "No active background tasks.");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Active background tasks (").append(active.size()).append("):\n\n");
                for (var t : active) {
                    sb.append("- `").append(t.taskId()).append("` ").append(t.toolName())
                            .append(" — running for ").append(t.elapsedMs()).append("ms\n");
                }
                sb.append("\nPoll a specific task with: poll task_id=\"<id>\"");
                return ai.kompile.cli.main.chat.tools.ToolResult.success("poll: status", sb.toString());
            }
        }
    }

    /** Build a standard MCP call result from a ToolResult. */
    private ObjectNode buildCallResult(ai.kompile.cli.main.chat.tools.ToolResult tr) {
        ObjectNode callResult = om.createObjectNode();
        var content = callResult.putArray("content");
        var textObj = content.addObject();
        textObj.put("type", "text");
        String title = tr.getTitle() != null ? tr.getTitle() + "\n" : "";
        textObj.put("text", title + (tr.getOutput() != null ? tr.getOutput() : ""));
        callResult.put("isError", tr.isError());
        return callResult;
    }

    /** Register a standalone CliTool (no special constructor deps) into the MCP tool map. */
    private void registerCliTool(Map<String, ToolDef> tools,
                                  ai.kompile.cli.main.chat.tools.CliTool cliTool,
                                  ObjectMapper om, Path wd) {
        tools.put(cliTool.id(), new ToolDef(
            cliTool.id(), cliTool.description(), cliTool.parameterSchema(),
            cliTool.mcpAnnotations(),
            args -> {
                try {
                    return cliTool.execute(om.valueToTree(args), ctx(wd));
                } catch (Exception e) {
                    return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage());
                }
            }
        ));
    }

    record ToolDef(String name, String description, JsonNode schema,
                   ai.kompile.cli.main.chat.tools.McpToolAnnotations annotations,
                   Function<Map<String,Object>, ai.kompile.cli.main.chat.tools.ToolResult> executor) {}

    private static final ThreadLocal<ai.kompile.cli.main.chat.tools.ToolContext> CTX = ThreadLocal.withInitial(() -> null);

    private ai.kompile.cli.main.chat.tools.ToolContext ctx(Path wd) {
        var ctx = CTX.get();
        if (ctx == null) {
            ctx = new ai.kompile.cli.main.chat.tools.ToolContext(
                "mcp-stdio", null,
                new AllowAllPermissionService(),
                wd, null);
            // Wire output consumer to both stderr and progress logger
            // so tool progress (e.g., "Parsed 100/500 files") is captured in the log
            final var loggerRef = progressLogger;
            ctx.setOutputConsumer(line -> {
                System.err.println("[tool] " + line);
                if (loggerRef != null) {
                    // Use a generic callId — individual tools can override
                    loggerRef.toolProgress("ctx-output", "tool", line);
                }
            });
            CTX.set(ctx);
        }
        return ctx;
    }

    /** No-op permission service that allows everything - stdio mode runs trusted tools. */
    static class AllowAllPermissionService extends ai.kompile.cli.main.chat.permission.PermissionService {
        @Override
        public ai.kompile.cli.main.chat.permission.PermissionService.PermissionResult check(
                ai.kompile.cli.main.chat.agent.AgentConfig agent, String permissionKey, String description) {
            return ai.kompile.cli.main.chat.permission.PermissionService.PermissionResult.ALLOWED;
        }
    }
}
