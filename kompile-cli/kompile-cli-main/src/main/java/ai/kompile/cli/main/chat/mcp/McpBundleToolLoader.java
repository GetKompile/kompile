/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads bundle-declared HTTP/SSE and stdio MCP servers into the native CLI tool registry. */
public final class McpBundleToolLoader implements AutoCloseable {
    private final ObjectMapper mapper;
    private final List<AutoCloseable> clients = new ArrayList<>();
    private final List<McpTool> tools = new ArrayList<>();

    private McpBundleToolLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static McpBundleToolLoader load(Path workspace, ToolRegistry registry) {
        McpBundleToolLoader loader = new McpBundleToolLoader(registry.getObjectMapper());
        try {
            Path configPath = workspace == null ? null : workspace.resolve(".mcp.json");
            if (configPath == null || !Files.isRegularFile(configPath)) return loader;
            JsonNode root = loader.mapper.readTree(Files.readString(configPath));
            JsonNode servers = root.path("mcpServers");
            if (!servers.isObject()) {
                loader.registerDiscoveryTools(registry);
                return loader;
            }
            servers.fields().forEachRemaining(entry -> loader.connectServer(entry.getKey(), entry.getValue(), workspace, registry));
            loader.registerDiscoveryTools(registry);
        } catch (Exception e) {
            System.err.println("[MCP] Bundle tool discovery failed: " + e.getMessage());
        }
        return loader;
    }

    private void connectServer(String serverId, JsonNode config, Path workspace, ToolRegistry registry) {
        String url = config.path("url").asText("").trim();
        McpEndpoint client = null;
        boolean registered = false;
        try {
            if (!url.isBlank()) {
                client = new SseEndpoint(new McpSseClient(url));
            } else {
                String command = config.path("command").asText("").trim();
                List<String> args = new ArrayList<>();
                if (config.path("args").isArray()) config.path("args").forEach(node -> args.add(node.asText()));
                Map<String, String> env = new LinkedHashMap<>();
                if (config.path("env").isObject()) config.path("env").fields().forEachRemaining(entry -> env.put(entry.getKey(), entry.getValue().asText()));
                client = new StdioEndpoint(new McpStdioClient(mapper, command, args, env, workspace));
            }
            client.initialize();
            clients.add(client);
            registered = true;
            for (RemoteTool info : client.listTools()) {
                McpTool tool = new McpTool(serverId, info, client);
                tools.add(tool);
                registry.register(tool);
            }
            System.err.println("[MCP] Discovered " + tools.stream().filter(t -> t.serverId.equals(serverId)).count()
                    + " tools from " + serverId);
        } catch (Exception e) {
            System.err.println("[MCP] Server unavailable " + serverId + ": " + e.getMessage());
        } finally {
            if (!registered && client != null) {
                try { client.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void registerDiscoveryTools(ToolRegistry registry) {
        registry.register(new CliTool() {
            @Override public String id() { return "mcp_tool_search"; }
            @Override public String description() { return "Search the live MCP tool catalog by name, server, or description."; }
            @Override public JsonNode parameterSchema() {
                ObjectNode schema = mapper.createObjectNode().put("type", "object");
                schema.putObject("properties").putObject("query").put("type", "string");
                return schema;
            }
            @Override public String permissionKey() { return "mcp.discover"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                String query = params.path("query").asText("").toLowerCase(Locale.ROOT);
                String output = tools.stream()
                        .filter(tool -> query.isBlank() || tool.id().toLowerCase(Locale.ROOT).contains(query)
                                || tool.description().toLowerCase(Locale.ROOT).contains(query))
                        .map(tool -> tool.id() + " - " + tool.description())
                        .sorted()
                        .limit(100)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("No MCP tools matched the query.");
                return ToolResult.success("MCP tool search", output);
            }
            @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }
        });
    }

    @Override
    public void close() {
        clients.forEach(client -> {
            try { client.close(); } catch (Exception ignored) { }
        });
        clients.clear();
    }

    record RemoteTool(String name, String description, JsonNode inputSchema) { }

    private interface McpEndpoint extends AutoCloseable {
        void initialize() throws Exception;
        List<RemoteTool> listTools() throws Exception;
        String callTool(String name, JsonNode params) throws Exception;
    }

    private static final class SseEndpoint implements McpEndpoint {
        private final McpSseClient client;
        private SseEndpoint(McpSseClient client) { this.client = client; }
        @Override public void initialize() throws Exception { client.connect(); client.initialize(); }
        @Override public List<RemoteTool> listTools() throws Exception {
            return client.listTools().stream()
                    .map(tool -> new RemoteTool(tool.getName(), tool.getDescription(), tool.getInputSchema()))
                    .toList();
        }
        @Override public String callTool(String name, JsonNode params) throws Exception { return client.callTool(name, params); }
        @Override public void close() { client.close(); }
    }

    private static final class StdioEndpoint implements McpEndpoint {
        private final McpStdioClient client;
        private StdioEndpoint(McpStdioClient client) { this.client = client; }
        @Override public void initialize() throws Exception { client.initialize(); }
        @Override public List<RemoteTool> listTools() throws Exception { return client.listTools(); }
        @Override public String callTool(String name, JsonNode params) throws Exception {
            JsonNode result = client.callTool(name, params);
            return result.isTextual() ? result.asText() : result.toString();
        }
        @Override public void close() { client.close(); }
    }

    private static final class McpTool implements CliTool {
        private final String serverId;
        private final RemoteTool info;
        private final McpEndpoint client;

        private McpTool(String serverId, RemoteTool info, McpEndpoint client) {
            this.serverId = serverId;
            this.info = info;
            this.client = client;
        }

        @Override public String id() { return "mcp__" + normalize(serverId) + "__" + normalize(info.name()); }
        @Override public String description() { return "MCP " + serverId + "/" + info.name() + ": " + info.description(); }
        @Override public JsonNode parameterSchema() { return info.inputSchema(); }
        @Override public String permissionKey() { return "mcp." + serverId; }
        @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
            try {
                return ToolResult.success("MCP " + serverId + "/" + info.name(), client.callTool(info.name(), params));
            } catch (Exception e) {
                throw new ToolExecutionException("MCP tool failed: " + serverId + "/" + info.name(), e);
            }
        }
        @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }
        private static String normalize(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    }
}
