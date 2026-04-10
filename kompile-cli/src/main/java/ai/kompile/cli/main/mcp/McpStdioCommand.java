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
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@CommandLine.Command(
    name = "mcp-stdio",
    description = "Start MCP stdio server for tool integration (used by Qwen Code, etc.)",
    mixinStandardHelpOptions = true
)
public class McpStdioCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--work-dir"}, description = "Working directory for tools")
    private String workDir;

    @Override
    public Integer call() {
        try {
            Path wd = workDir != null ? Paths.get(workDir) : Paths.get(System.getProperty("user.dir"));
            ObjectMapper om = new ObjectMapper();

            Map<String, ToolDef> tools = buildToolMap(om, wd);

            System.err.println("[MCP] Kompile CLI MCP stdio server ready (" + tools.size() + " tools)");
            System.err.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            OutputStreamWriter out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);

            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonNode msg = om.readTree(line);
                    JsonNode response = handleMessage(msg, tools, om);
                    if (response != null) {
                        out.write(om.writeValueAsString(response) + "\n");
                        out.flush();
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
                    initResult.putObject("capabilities").putObject("tools").put("listChanged", false);
                    ObjectNode serverInfo = initResult.putObject("serverInfo");
                    serverInfo.put("name", "kompile-cli");
                    serverInfo.put("version", "0.1.0-SNAPSHOT");
                    result.set("result", initResult);
                }
                case "tools/list" -> {
                    ObjectNode toolsResult = om.createObjectNode();
                    var toolsArray = toolsResult.putArray("tools");
                    for (ToolDef td : tools.values()) {
                        var toolObj = toolsArray.addObject();
                        toolObj.put("name", td.name());
                        toolObj.put("description", td.description());
                        toolObj.set("inputSchema", td.schema());
                    }
                    result.set("result", toolsResult);
                }
                case "tools/call" -> {
                    String toolName = params.path("name").asText();
                    JsonNode args = params.path("arguments");
                    ToolDef td = tools.get(toolName);
                    if (td == null) {
                        ObjectNode callResult = om.createObjectNode();
                        callResult.putArray("content").addObject().put("type", "text").put("text", "Unknown tool: " + toolName);
                        callResult.put("isError", true);
                        result.set("result", callResult);
                    } else {
                        Map<String, Object> argMap = om.convertValue(args, Map.class);
                        var tr = td.executor().apply(argMap);
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
                case "ping" -> result.set("result", om.createObjectNode());
                default -> { return null; }
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

    private Map<String, ToolDef> buildToolMap(ObjectMapper om, Path wd) {
        Map<String, ToolDef> tools = new LinkedHashMap<>();

        var agentRegistry = new ai.kompile.cli.main.chat.agent.AgentRegistry();
        var roleManager = new ai.kompile.cli.main.chat.roles.RoleManager(wd);
        var permissionService = new ai.kompile.cli.main.chat.permission.PermissionService();
        var processManager = new ai.kompile.cli.main.chat.tools.BackgroundProcessManager(System.getProperty("user.dir"));
        var subagentRunner = new ai.kompile.cli.mcp.stdio.DirectSubagentRunnerStdio(wd, roleManager);

        // ── File I/O tools ─────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ReadTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.WriteTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.EditTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.PatchTool(), om, wd);

        // ── Search tools ───────────────────────────────────────────────────
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.GrepTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.GlobTool(), om, wd);
        registerCliTool(tools, new ai.kompile.cli.main.chat.tools.ListTool(), om, wd);

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

        // ── Process management ─────────────────────────────────────────────
        var procTool = new ai.kompile.cli.main.chat.tools.ProcessManagementTool(processManager);
        tools.put(procTool.id(), new ToolDef(procTool.id(), procTool.description(), procTool.parameterSchema(),
            args -> { try { return procTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        // ── Delegation tools ───────────────────────────────────────────────
        var taskTool = new ai.kompile.cli.mcp.stdio.StdioTaskTool(agentRegistry, subagentRunner, om);
        tools.put(taskTool.id(), new ToolDef(taskTool.id(), taskTool.description(), taskTool.parameterSchema(),
            args -> { try { return taskTool.execute(args); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        var rmTool = new ai.kompile.cli.main.chat.tools.RoleManagerTool(roleManager, om);
        tools.put(rmTool.id(), new ToolDef(rmTool.id(), rmTool.description(), rmTool.parameterSchema(),
            args -> { try { return rmTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));

        // ── Conversation resume ────────────────────────────────────────────
        try {
            var resumeTool = new ai.kompile.cli.main.chat.tools.ResumeTool();
            tools.put(resumeTool.id(), new ToolDef(resumeTool.id(), resumeTool.description(), resumeTool.parameterSchema(),
                args -> { try { return resumeTool.execute(om.valueToTree(args), ctx(wd)); } catch (Exception e) { return ai.kompile.cli.main.chat.tools.ToolResult.error(e.getMessage()); } }));
        } catch (java.io.IOException e) {
            System.err.println("[MCP] Warning: ResumeTool not available: " + e.getMessage());
        }

        return tools;
    }

    /** Register a standalone CliTool (no special constructor deps) into the MCP tool map. */
    private void registerCliTool(Map<String, ToolDef> tools,
                                  ai.kompile.cli.main.chat.tools.CliTool cliTool,
                                  ObjectMapper om, Path wd) {
        tools.put(cliTool.id(), new ToolDef(
            cliTool.id(), cliTool.description(), cliTool.parameterSchema(),
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
                   java.util.function.Function<Map<String,Object>, ai.kompile.cli.main.chat.tools.ToolResult> executor) {}

    private static final ThreadLocal<ai.kompile.cli.main.chat.tools.ToolContext> CTX = ThreadLocal.withInitial(() -> null);

    private ai.kompile.cli.main.chat.tools.ToolContext ctx(Path wd) {
        var ctx = CTX.get();
        if (ctx == null) {
            ctx = new ai.kompile.cli.main.chat.tools.ToolContext(
                "mcp-stdio", null,
                new AllowAllPermissionService(),
                wd, null);
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
