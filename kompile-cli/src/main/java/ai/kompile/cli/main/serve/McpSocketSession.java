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

package ai.kompile.cli.main.serve;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.*;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Handles a single MCP client connection over a Unix domain socket.
 * <p>
 * Each connection gets its own working directory and tool context,
 * but shares the {@link SharedResourcePool} (ObjectMapper, registries, etc.)
 * with all other connections — this is where the memory savings come from.
 * <p>
 * Protocol: newline-delimited JSON-RPC 2.0, same as {@code McpStdioCommand}.
 */
public class McpSocketSession implements Runnable {

    private final SocketChannel channel;
    private final SharedResourcePool pool;
    private final String sessionId;
    private final Runnable onClose;

    /** Per-session tool map — built lazily in background after first initialize request. */
    private final java.util.concurrent.ConcurrentHashMap<String, ToolDef> tools = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean toolsReady = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Path workDir;
    private volatile CoordinationStateManager coordinator;
    private volatile ai.kompile.cli.main.chat.enforcer.EnforcerToolCallGuard enforcerGuard;
    private volatile ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor gatewayInterceptor;
    private volatile ai.kompile.cli.mcp.stdio.McpToolAuditLogger auditLogger;

    /** Output writer for sending notifications. */
    private volatile OutputStreamWriter mcpOut;
    private volatile ObjectMapper sessionOm;

    public McpSocketSession(SocketChannel channel, SharedResourcePool pool,
                            String sessionId, Runnable onClose) {
        this.channel = channel;
        this.pool = pool;
        this.sessionId = sessionId;
        this.onClose = onClose;
    }

    @Override
    public void run() {
        try (InputStream rawIn = java.nio.channels.Channels.newInputStream(channel);
             OutputStream rawOut = java.nio.channels.Channels.newOutputStream(channel)) {

            BufferedReader in = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));
            mcpOut = new OutputStreamWriter(rawOut, StandardCharsets.UTF_8);
            sessionOm = pool.objectMapper();

            // First line is the protocol header: {"type":"mcp","workDir":"/path"}
            String headerLine = in.readLine();
            if (headerLine == null) return;

            JsonNode header = sessionOm.readTree(headerLine);
            String workDirStr = header.has("workDir") ? header.get("workDir").asText() : null;
            this.workDir = workDirStr != null ? Paths.get(workDirStr) : pool.defaultWorkDir();
            this.gatewayInterceptor = ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.fromConfig(sessionOm);
            this.auditLogger = new ai.kompile.cli.mcp.stdio.McpToolAuditLogger(
                    sessionId, "kompile-daemon", "mcp-daemon", workDir, sessionOm);
            String enforcerPolicyFile = header.has("enforcerPolicyFile")
                    ? header.get("enforcerPolicyFile").asText(null) : null;
            this.enforcerGuard = ai.kompile.cli.main.chat.enforcer.EnforcerToolCallGuard
                    .fromPolicyFile(enforcerPolicyFile, sessionOm);
            if (enforcerGuard != null && enforcerGuard.isActive()) {
                System.err.println("[daemon] Enforcer MCP guard active (" + enforcerGuard.describe() + ")");
            }

            System.err.println("[daemon] MCP session " + sessionId + " started (workDir=" + workDir + ")");

            // Build tools in background so the event loop can handle
            // initialize/ping immediately — prevents Claude Code timeout.
            Thread initThread = new Thread(() -> {
                try {
                    Map<String, ToolDef> builtTools = buildToolMap(sessionOm, workDir);
                    tools.putAll(builtTools);
                    toolsReady.set(true);
                    System.err.println("[daemon] MCP session " + sessionId + " tools ready (" + tools.size() + " tools)");
                } catch (Exception e) {
                    System.err.println("[daemon] MCP session " + sessionId + " tool init error: " + e.getMessage());
                    toolsReady.set(true); // unblock even on error
                }
            }, "daemon-tool-init-" + sessionId);
            initThread.setDaemon(true);
            initThread.start();

            // Standard MCP JSON-RPC loop — same protocol as McpStdioCommand.
            // Tool calls dispatched to a thread pool so long-running tools
            // don't block ping responses (which would cause Claude Code to drop).
            java.util.concurrent.ExecutorService toolExecutor =
                    java.util.concurrent.Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "daemon-tool-exec-" + sessionId);
                        t.setDaemon(true);
                        return t;
                    });
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        JsonNode msg = sessionOm.readTree(line);
                        String msgMethod = msg.path("method").asText("");

                        // Dispatch tool calls to background thread so pings stay responsive
                        if ("tools/call".equals(msgMethod)) {
                            final JsonNode toolMsg = msg;
                            toolExecutor.submit(() -> {
                                try {
                                    JsonNode response = handleMessage(toolMsg, sessionOm);
                                    if (response != null) {
                                        synchronized (mcpOut) {
                                            mcpOut.write(sessionOm.writeValueAsString(response) + "\n");
                                            mcpOut.flush();
                                        }
                                    }
                                } catch (Exception e) {
                                    System.err.println("[daemon] MCP session " + sessionId + " tool exec error: " + e.getMessage());
                                }
                            });
                        } else {
                            JsonNode response = handleMessage(msg, sessionOm);
                            if (response != null) {
                                synchronized (mcpOut) {
                                    mcpOut.write(sessionOm.writeValueAsString(response) + "\n");
                                    mcpOut.flush();
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("[daemon] MCP session " + sessionId + " error: " + e.getMessage());
                    }
                }
            } finally {
                toolExecutor.shutdownNow();
            }
        } catch (IOException e) {
            // Client disconnected — normal for Unix sockets
        } finally {
            System.err.println("[daemon] MCP session " + sessionId + " closed");
            if (enforcerGuard != null) enforcerGuard.close();
            if (coordinator != null) coordinator.shutdown();
            try { channel.close(); } catch (IOException ignored) {}
            onClose.run();
        }
    }

    private JsonNode handleMessage(JsonNode msg, ObjectMapper om) {
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
                    // listChanged=false — Claude Code drops MCP connections when it
                    // receives tools/list_changed notifications.
                    caps.putObject("tools").put("listChanged", false);
                    caps.putObject("prompts").put("listChanged", false);
                    caps.putObject("logging");
                    ObjectNode serverInfo = initResult.putObject("serverInfo");
                    serverInfo.put("name", "kompile-daemon");
                    serverInfo.put("version", "0.1.0-SNAPSHOT");
                    result.set("result", initResult);
                }

                case "notifications/initialized", "notifications/cancelled",
                     "notifications/progress", "notifications/roots/list_changed" -> {
                    return null;
                }

                case "tools/list" -> {
                    // Wait for background tool initialization (max 30s)
                    waitForTools();
                    ObjectNode toolsResult = om.createObjectNode();
                    var toolsArray = toolsResult.putArray("tools");
                    for (ToolDef td : tools.values()) {
                        var toolObj = toolsArray.addObject();
                        toolObj.put("name", td.name());
                        toolObj.put("description", td.description());
                        toolObj.set("inputSchema", td.schema());
                        if (td.annotations() != null) {
                            toolObj.set("annotations", td.annotations().toJsonNode());
                        }
                    }
                    result.set("result", toolsResult);
                }

                case "tools/call" -> {
                    // Wait for background tool initialization if needed
                    waitForTools();
                    String toolName = params.path("name").asText();
                    JsonNode args = params.path("arguments");

                    // Extract progress token
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
                    Map<String, Object> argMap = new LinkedHashMap<>(originalArgMap);

                    ToolDef td = tools.get(toolName);
                    if (td == null) {
                        ObjectNode callResult = om.createObjectNode();
                        callResult.putArray("content").addObject()
                                .put("type", "text").put("text", "Unknown tool: " + toolName);
                        callResult.put("isError", true);
                        result.set("result", callResult);
                        if (auditLogger != null) {
                            auditLogger.recordDecision(toolName, originalArgMap, null,
                                    "unknown_tool", "Unknown tool", true, 0);
                        }
                    } else {
                        String auditDecision = "executed";
                        String auditReason = null;

                        var gatewayDecision = evaluateGatewayToolCall(toolName, argMap);
                        if (gatewayDecision != null) {
                            if (gatewayDecision.action
                                    == ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.InterceptAction.BLOCK) {
                                result.set("result", gatewayBlockedCallResult(om, gatewayDecision.reason));
                                if (auditLogger != null) {
                                    auditLogger.recordDecision(toolName, originalArgMap, argMap,
                                            "gateway_blocked", gatewayDecision.reason, true, 0);
                                }
                                return result;
                            } else if (gatewayDecision.action
                                    == ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.InterceptAction.REWRITE
                                    && gatewayDecision.rewrittenArgs != null) {
                                argMap = new LinkedHashMap<>(gatewayDecision.rewrittenArgs);
                                auditDecision = "gateway_rewritten";
                                auditReason = gatewayDecision.reason;
                            }
                        }

                        var guardDecision = evaluateEnforcerToolCall(toolName, argMap);
                        if (guardDecision != null && !guardDecision.isAllowed()) {
                            result.set("result", blockedCallResult(om, guardDecision));
                            if (auditLogger != null) {
                                auditLogger.recordDecision(toolName, originalArgMap, argMap,
                                        "enforcer_blocked", guardDecision.blockMessage(), true, 0);
                            }
                            return result;
                        } else {
                            if (guardDecision != null && guardDecision.isRewrite()) {
                                argMap = new LinkedHashMap<>(guardDecision.getRewrittenArgs());
                                auditDecision = "enforcer_rewritten";
                                auditReason = appendAuditReason(auditReason, guardDecision.getReason());
                            }
                            if (progressToken != null) sendProgress(om, progressToken, 0, 1);

                            long callStart = System.currentTimeMillis();
                            var tr = td.executor().apply(argMap);
                            long callDuration = System.currentTimeMillis() - callStart;
                            if (auditLogger != null) {
                                auditLogger.recordDecision(toolName, originalArgMap, argMap,
                                        auditDecision, auditReason, tr.isError(), callDuration);
                            }

                            if (progressToken != null) sendProgress(om, progressToken, 1, 1);

                            ObjectNode callResult = om.createObjectNode();
                            var content = callResult.putArray("content");
                            var textObj = content.addObject();
                            textObj.put("type", "text");
                            String title = tr.getTitle() != null ? tr.getTitle() + "\n" : "";
                            textObj.put("text", title + (tr.getOutput() != null ? tr.getOutput() : ""));
                            callResult.put("isError", tr.isError());
                            result.set("result", callResult);
                        }
                    }
                }

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
                    var skillRegistry = pool.skillRegistry();
                    if (skillRegistry != null) {
                        for (var skill : skillRegistry.all()) {
                            ObjectNode prompt = promptsArray.addObject();
                            prompt.put("name", skill.getName());
                            prompt.put("description", skill.getDescription());
                            ArrayNode arguments = prompt.putArray("arguments");
                            ObjectNode argsArg = arguments.addObject();
                            argsArg.put("name", "args");
                            argsArg.put("description", "Arguments to pass to the skill");
                            argsArg.put("required", false);
                        }
                    }
                    result.set("result", promptsResult);
                }
                case "prompts/get" -> {
                    String promptName = params != null ? params.path("name").asText("") : "";
                    var skillRegistry = pool.skillRegistry();
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
                            String argsValue = "";
                            JsonNode argsNode = params.path("arguments");
                            if (argsNode.isObject()) {
                                JsonNode av = argsNode.get("args");
                                if (av != null && av.isTextual()) argsValue = av.asText();
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

                case "logging/setLevel" -> result.set("result", om.createObjectNode());
                case "ping" -> result.set("result", om.createObjectNode());

                default -> {
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
            error.put("message", e.getMessage());
            return errResp;
        }
    }

    /** Block until background tool initialization completes (max 30s). */
    private void waitForTools() {
        if (toolsReady.get()) return;
        long deadline = System.currentTimeMillis() + 30_000;
        while (!toolsReady.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ── Notification helpers ────────────────────────────────────────────────

    private void sendNotification(ObjectMapper om, String method, ObjectNode params) {
        if (mcpOut == null) return;
        try {
            ObjectNode notification = om.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) notification.set("params", params);
            synchronized (mcpOut) {
                mcpOut.write(om.writeValueAsString(notification) + "\n");
                mcpOut.flush();
            }
        } catch (Exception e) {
            System.err.println("[daemon] Failed to send notification " + method + ": " + e.getMessage());
        }
    }

    private void sendProgress(ObjectMapper om, String progressToken, int progress, int total) {
        ObjectNode params = om.createObjectNode();
        params.put("progressToken", progressToken);
        params.put("progress", progress);
        params.put("total", total);
        sendNotification(om, "notifications/progress", params);
    }

    // ── Tool registration (mirrors McpStdioCommand.buildToolMap) ──────────

    /**
     * Tools that host agents already provide natively. When running as an MCP server
     * plugged into Claude Code, Codex, etc., these are skipped to avoid duplicates
     * that confuse the LLM and add latency from unnecessary stdio pipe roundtrips.
     */
    private static final Set<String> HOST_NATIVE_TOOLS = Set.of(
            "read", "write", "edit", "bash", "grep", "glob", "list",
            "webfetch", "websearch", "todowrite", "todoread"
    );

    private Map<String, ToolDef> buildToolMap(ObjectMapper om, Path wd) {
        Map<String, ToolDef> map = new LinkedHashMap<>();

        String coordSessionId = "daemon-mcp-" + sessionId;
        coordinator = new CoordinationStateManager(wd, coordSessionId, om);

        // Skip tools that the host agent already provides natively.
        // Registering duplicates (e.g. mcp__kompile__bash) causes the LLM to pick
        // the MCP version over its own native tool, adding latency and instability.

        // Patch is kompile-specific (multi-hunk unified diff), always register
        register(map, new PatchTool(), om, wd);

        // Knowledge & memory (kompile-specific, always register)
        register(map, new TranscriptSearchTool(), om, wd);
        register(map, new ConversationImportTool(), om, wd);
        register(map, new MemoryTool(), om, wd);

        // Config tools
        register(map, new ConfigArchiveTool(), om, wd);
        register(map, new ProjectConfigTool(), om, wd);
        register(map, new EnforcerConfigTool(), om, wd);

        // Test milestone
        register(map, new TestMilestoneTool(), om, wd);

        // Code search & graph (local fallback, kompile-specific)
        register(map, new CodeSearchTool(null, om), om, wd);
        register(map, new CodeGraphTool(null, om), om, wd);
        register(map, new LocalCodeIndexTool(), om, wd);

        // Tool call catalog
        register(map, new ToolCallCatalogTool(), om, wd);

        // Process management
        var processManager = new BackgroundProcessManager(coordSessionId);
        var procTool = new ProcessManagementTool(processManager);
        map.put(procTool.id(), new ToolDef(procTool.id(), procTool.description(), procTool.parameterSchema(),
                procTool.mcpAnnotations(),
                args -> { try { return procTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        // Edit coordination
        register(map, new EditCoordinatorTool(coordinator), om, wd);

        // Delegation tools — use shared registries from the pool
        var subagentRunner = new ai.kompile.cli.mcp.stdio.DirectSubagentRunnerStdio(wd, pool.roleManager());
        var enforcerTool = new ai.kompile.cli.mcp.stdio.StdioEnforcerTool(subagentRunner, om, wd, processManager);
        map.put(enforcerTool.id(), new ToolDef(enforcerTool.id(), enforcerTool.description(), enforcerTool.parameterSchema(),
                McpToolAnnotations.DELEGATION,
                args -> { try { return enforcerTool.execute(args); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var postFeedbackTool = new ai.kompile.cli.mcp.stdio.StdioPostFeedbackTool(om, wd);
        map.put(postFeedbackTool.id(), new ToolDef(postFeedbackTool.id(), postFeedbackTool.description(), postFeedbackTool.parameterSchema(),
                McpToolAnnotations.DELEGATION,
                args -> { try { return postFeedbackTool.execute(args); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var taskTool = new ai.kompile.cli.mcp.stdio.StdioTaskTool(
                pool.agentRegistry(), subagentRunner, om, pool.roleManager());
        map.put(taskTool.id(), new ToolDef(taskTool.id(), taskTool.description(), taskTool.parameterSchema(),
                McpToolAnnotations.DELEGATION,
                args -> { try { return taskTool.execute(args); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var quorumTool = new ai.kompile.cli.mcp.stdio.StdioQuorumTaskTool(
                pool.agentRegistry(), subagentRunner, om, wd);
        map.put(quorumTool.id(), new ToolDef(quorumTool.id(), quorumTool.description(), quorumTool.parameterSchema(),
                McpToolAnnotations.DELEGATION,
                args -> { try { return quorumTool.execute(args); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var multiTool = new ai.kompile.cli.mcp.stdio.StdioMultiTaskTool(
                pool.agentRegistry(), subagentRunner, om, wd, pool.roleManager());
        map.put(multiTool.id(), new ToolDef(multiTool.id(), multiTool.description(), multiTool.parameterSchema(),
                McpToolAnnotations.DELEGATION,
                args -> { try { return multiTool.execute(args); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var rmTool = new RoleManagerTool(pool.roleManager(), om);
        map.put(rmTool.id(), new ToolDef(rmTool.id(), rmTool.description(), rmTool.parameterSchema(),
                rmTool.mcpAnnotations(),
                args -> { try { return rmTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        var skillTool = new SkillManagerTool(om, wd);
        map.put(skillTool.id(), new ToolDef(skillTool.id(), skillTool.description(), skillTool.parameterSchema(),
                skillTool.mcpAnnotations(),
                args -> { try { return skillTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));

        // Resume tool — use MCP mode to avoid grabbing the system terminal/intercepting Ctrl+C
        try {
            var resumeTool = new ResumeTool(true);
            map.put(resumeTool.id(), new ToolDef(resumeTool.id(), resumeTool.description(), resumeTool.parameterSchema(),
                    resumeTool.mcpAnnotations(),
                    args -> { try { return resumeTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ToolResult.error(e.getMessage()); } }));
        } catch (IOException e) {
            System.err.println("[daemon] Warning: ResumeTool not available: " + e.getMessage());
        }

        return map;
    }

    private void register(Map<String, ToolDef> map, CliTool cliTool, ObjectMapper om, Path wd) {
        map.put(cliTool.id(), new ToolDef(
                cliTool.id(), cliTool.description(), cliTool.parameterSchema(),
                cliTool.mcpAnnotations(),
                args -> {
                    try { return cliTool.execute(om.valueToTree(args), ctx(wd)); }
                    catch (Exception e) { return ToolResult.error(e.getMessage()); }
                }
        ));
    }

    private ToolContext ctx(Path wd) {
        return new ToolContext(sessionId, null, new AllowAllPermissionService(), wd, null);
    }

    private ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision evaluateEnforcerToolCall(
            String toolName, Map<String, Object> argMap) {
        var guard = enforcerGuard;
        if (guard == null || !guard.isActive()) {
            return null;
        }
        return guard.evaluate(toolName, argMap);
    }

    private ai.kompile.cli.main.chat.gateway.CliToolGatewayInterceptor.InterceptResult evaluateGatewayToolCall(
            String toolName, Map<String, Object> argMap) {
        var gateway = gatewayInterceptor;
        if (gateway == null || !gateway.isEnabled()) {
            return null;
        }
        return gateway.evaluate(toolName, argMap);
    }

    private ObjectNode blockedCallResult(ObjectMapper om,
                                         ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision decision) {
        ObjectNode callResult = om.createObjectNode();
        var content = callResult.putArray("content");
        var textObj = content.addObject();
        textObj.put("type", "text");
        textObj.put("text", "Blocked by enforcer before MCP execution: " + decision.blockMessage());
        callResult.put("isError", true);
        return callResult;
    }

    private ObjectNode gatewayBlockedCallResult(ObjectMapper om, String reason) {
        ObjectNode callResult = om.createObjectNode();
        var content = callResult.putArray("content");
        var textObj = content.addObject();
        textObj.put("type", "text");
        textObj.put("text", "Blocked by gateway before MCP execution: "
                + (reason != null && !reason.isBlank() ? reason : "Gateway policy blocked the tool call"));
        callResult.put("isError", true);
        return callResult;
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

    record ToolDef(String name, String description, JsonNode schema,
                   McpToolAnnotations annotations,
                   Function<Map<String, Object>, ToolResult> executor) {}

    /** MCP stdio sessions run trusted — all tools are auto-approved. */
    static class AllowAllPermissionService extends PermissionService {
        @Override
        public PermissionResult check(ai.kompile.cli.main.chat.agent.AgentConfig agent,
                                      String permissionKey, String description) {
            return PermissionResult.ALLOWED;
        }
    }
}
