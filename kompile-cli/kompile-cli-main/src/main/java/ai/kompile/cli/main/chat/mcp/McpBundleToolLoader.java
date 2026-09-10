/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads bundle-declared HTTP/SSE and stdio MCP servers into the native CLI tool registry. */
public final class McpBundleToolLoader implements AutoCloseable {
    static final String CUSTOM_MCP_DEPTH_ENV = "KOMPILE_CUSTOM_MCP_DEPTH";
    static final String HOST_LOADS_PROJECT_ENV = "KOMPILE_MCP_HOST_LOADS_PROJECT";
    private final ObjectMapper mapper;
    private final String transcriptId;
    private final List<McpEndpoint> clients = new ArrayList<>();
    private final List<McpTool> tools = new ArrayList<>();
    private volatile ToolRegistry registry;
    private volatile McpConfigStore.DashboardConfig dashboardConfig;
    private volatile Path workspace;

    private McpBundleToolLoader(ObjectMapper mapper, String transcriptId) {
        this.mapper = mapper;
        this.transcriptId = transcriptId == null || transcriptId.isBlank()
                ? null : transcriptId.trim();
    }

    public static McpBundleToolLoader load(Path workspace, ToolRegistry registry) {
        return load(workspace, registry, TranscriptLogScope.currentTranscriptId());
    }

    public static McpBundleToolLoader load(
            Path workspace, ToolRegistry registry, String transcriptId) {
        if (!customLoadingAllowed(System.getenv(CUSTOM_MCP_DEPTH_ENV))) {
            return new McpBundleToolLoader(registry.getObjectMapper(), transcriptId);
        }
        boolean includeProject = !Boolean.parseBoolean(
                System.getenv().getOrDefault(HOST_LOADS_PROJECT_ENV, "false"));
        return load(workspace, registry, transcriptId, includeProject);
    }

    static boolean customLoadingAllowed(String configuredDepth) {
        if (configuredDepth == null || configuredDepth.isBlank()) return true;
        try {
            return Integer.parseInt(configuredDepth.trim()) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static McpBundleToolLoader load(
            Path workspace, ToolRegistry registry, String transcriptId,
            boolean includeProjectConfig) {
        McpBundleToolLoader loader = new McpBundleToolLoader(
                registry.getObjectMapper(), transcriptId);
        loader.registry = registry;
        try {
            McpConfigStore configStore = new McpConfigStore(workspace);
            loader.workspace = configStore.workspace();
            if (includeProjectConfig) {
                try {
                    loader.dashboardConfig = configStore.projectDashboard().orElse(null);
                } catch (IOException dashboardFailure) {
                    System.err.println("[MCP] Ignoring invalid project dashboard config: "
                            + dashboardFailure.getMessage());
                }
            }
            Map<String, McpConfigStore.ConfiguredServer> effectiveServers =
                    configStore.effectiveServers(includeProjectConfig);
            if (effectiveServers.isEmpty()) return loader;
            for (McpConfigStore.ConfiguredServer server : effectiveServers.values()) {
                if (McpConfigStore.RESERVED_SERVER_NAMES.contains(server.name())) {
                    continue;
                }
                loader.connectServer(server.name(), server.config(),
                        configStore.workspace(), registry);
            }
            loader.registerDiscoveryTools(registry);
        } catch (RequiredMcpServerException e) {
            loader.close();
            throw e;
        } catch (Exception e) {
            loader.close();
            System.err.println("[MCP] Bundle tool discovery failed: " + e.getMessage());
        }
        return loader;
    }

    /**
     * Interactive loading requires explicit trust because .mcp.json may execute
     * project-controlled commands. Headless callers retain their existing policy.
     */
    public static McpBundleToolLoader loadInteractive(Path workspace, ToolRegistry registry) {
        return loadInteractive(
                workspace, registry, TranscriptLogScope.currentTranscriptId());
    }

    public static McpBundleToolLoader loadInteractive(
            Path workspace, ToolRegistry registry, String transcriptId) {
        McpConfigStore configStore = new McpConfigStore(workspace);
        boolean includeProjectConfig = true;
        if ((configStore.hasProjectCustomServers()
                || configStore.hasProjectDashboardConfig())
                && !isExplicitlyTrusted(configStore.workspace())
                && !confirmWorkspaceTrust(configStore.workspace())) {
            includeProjectConfig = false;
            System.err.println("[MCP] Skipping untrusted workspace MCP config: "
                    + configStore.path(McpConfigStore.Scope.PROJECT));
        }
        return load(configStore.workspace(), registry, transcriptId, includeProjectConfig);
    }

    /** Optional trusted dashboard declaration loaded from the project MCP bundle. */
    public java.util.Optional<McpConfigStore.DashboardConfig> dashboardConfig() {
        return java.util.Optional.ofNullable(dashboardConfig);
    }

    /**
     * Invoke only the configured dashboard source through the normal per-server
     * permission check and record the automatic call in the tool-call audit index.
     * No arbitrary tool id can be supplied by the caller.
     */
    RemoteCallResult callDashboardSource(ToolContext context) throws Exception {
        McpConfigStore.DashboardConfig config = dashboardConfig;
        if (config == null) {
            throw new IllegalStateException("No project dashboard is configured");
        }
        if (context == null) {
            throw new IllegalArgumentException("Dashboard tool context is required");
        }
        long started = System.nanoTime();
        boolean error = true;
        try {
            McpTool source = tools.stream()
                    .filter(tool -> tool.serverId.equals(config.serverName())
                            && tool.id().equals(config.tool()))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Configured dashboard tool is unavailable: " + config.tool()));
            ToolResult result = source.execute(config.arguments(), context);
            error = result.isError();
            return new RemoteCallResult(result.getOutput(), result.getMetadata(), result.isError());
        } finally {
            long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            ToolCallIndex.getInstance().record(
                    context.getSessionId(), config.tool(),
                    "{\"configuredArgumentFields\":" + config.arguments().size() + "}",
                    context.getAgent() == null ? "dashboard" : context.getAgent().getName(),
                    "mcp-dashboard", error, durationMs,
                    context.getWorkingDirectory() != null
                            ? context.getWorkingDirectory().toString()
                            : workspace == null ? null : workspace.toString());
        }
    }

    private static boolean isExplicitlyTrusted(Path workspace) {
        String configured = System.getenv("KOMPILE_MCP_TRUSTED_WORKSPACE");
        if (configured == null || configured.isBlank() || workspace == null) return false;
        Path normalized = workspace.toAbsolutePath().normalize();
        for (String entry : configured.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank() && normalized.equals(Path.of(entry).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private static boolean confirmWorkspaceTrust(Path workspace) {
        Console console = System.console();
        if (console == null) return false;
        String answer = console.readLine(
                "Workspace %s declares MCP servers and may automatically call a dashboard source. Trust once? [y/N] ",
                workspace.toAbsolutePath().normalize());
        return answer != null && ("y".equalsIgnoreCase(answer.trim())
                || "yes".equalsIgnoreCase(answer.trim()));
    }

    private void connectServer(String serverId, JsonNode config, Path workspace, ToolRegistry registry) {
        if (!config.path("enabled").asBoolean(true)) return;
        boolean required = config.path("required").asBoolean(false);
        McpEndpoint client = null;
        boolean registered = false;
        try {
            McpConfigStore.validateConfig(serverId, config);
            long timeoutSeconds = McpConfigStore.timeoutSeconds(config);
            McpConfigStore.Transport transport = McpConfigStore.transportOf(config);
            Map<String, String> headers = httpHeaders(config);
            String storedToken = storedAccessToken(serverId);
            if (storedToken != null && transport != McpConfigStore.Transport.STDIO) {
                headers.put("Authorization", "Bearer " + storedToken);
            }
            client = switch (transport) {
                case HTTP -> new HttpEndpoint(new McpStreamableHttpClient(
                        mapper, McpConfigStore.endpointOf(config),
                        headers, timeoutSeconds));
                case SSE -> {
                    if (!headers.isEmpty()) {
                        throw new IOException("legacy SSE headers are not supported; use --transport http");
                    }
                    // The extended McpSseClient constructor is package-private to
                    // cli-common; the public constructor carries safe defaults.
                    yield new SseEndpoint(new McpSseClient(
                            legacySseBaseUrl(McpConfigStore.endpointOf(config))));
                }
                case STDIO -> new StdioEndpoint(stdioClient(
                        serverId, config, workspace, timeoutSeconds));
            };
            client.initialize();
            Set<String> includedTools = stringSet(config, "includeTools", "enabled_tools");
            Set<String> excludedTools = stringSet(config, "excludeTools", "disabled_tools");
            List<McpTool> discoveredTools = new ArrayList<>();
            for (RemoteTool info : client.listTools()) {
                if ((!includedTools.isEmpty() && !includedTools.contains(info.name()))
                        || excludedTools.contains(info.name())) {
                    continue;
                }
                McpTool tool = new McpTool(serverId, info, client);
                boolean collision = tools.stream().anyMatch(existing -> existing.id().equals(tool.id()))
                        || discoveredTools.stream().anyMatch(existing -> existing.id().equals(tool.id()))
                        || registry.get(tool.id()) != null;
                if (collision) {
                    throw new IOException("normalized MCP tool id collision: " + tool.id());
                }
                discoveredTools.add(tool);
            }
            tools.addAll(discoveredTools);
            discoveredTools.forEach(registry::register);
            clients.add(client);
            registered = true;
            System.err.println("[MCP] Discovered " + discoveredTools.size() + " tools from " + serverId);
        } catch (Exception e) {
            String message = "MCP server unavailable " + serverId + ": " + e.getMessage();
            if (required) throw new RequiredMcpServerException(message, e);
            System.err.println("[MCP] " + message);
        } finally {
            if (!registered && client != null) {
                try { client.close(); } catch (Exception ignored) { }
            }
        }
    }

    private McpStdioClient stdioClient(
            String serverId, JsonNode config, Path workspace, long timeoutSeconds)
            throws IOException {
        String command = config.path("command").asText("").trim();
        List<String> args = new ArrayList<>();
        if (config.path("args").isArray()) {
            config.path("args").forEach(node -> args.add(node.asText()));
        }
        Map<String, String> environment = new LinkedHashMap<>(
                McpConfigStore.expandedStringMap(config.path("env")));
        environment.put(CUSTOM_MCP_DEPTH_ENV, Integer.toString(
                currentCustomDepth() + 1));
        if (transcriptId != null) {
            environment.put(TranscriptLogScope.TRANSCRIPT_ID_ENV, transcriptId);
        }
        Path processDirectory = workspace;
        String configuredCwd = config.path("cwd").asText("").trim();
        if (!configuredCwd.isBlank()) {
            Path cwd = Path.of(McpConfigStore.expandEnvironmentReferences(configuredCwd));
            processDirectory = (cwd.isAbsolute() ? cwd : workspace.resolve(cwd))
                    .normalize();
        }
        return new McpStdioClient(mapper, command, args, environment,
                processDirectory, timeoutSeconds, serverId, transcriptId);
    }

    private static int currentCustomDepth() {
        String configured = System.getenv(CUSTOM_MCP_DEPTH_ENV);
        if (configured == null || configured.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Map<String, String> httpHeaders(JsonNode config) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.putAll(McpConfigStore.expandedStringMap(config.path("headers")));
        headers.putAll(McpConfigStore.expandedStringMap(config.path("httpHeaders")));
        JsonNode environmentHeaders = config.has("envHeaders")
                ? config.path("envHeaders") : config.path("env_http_headers");
        if (environmentHeaders.isObject()) {
            environmentHeaders.fields().forEachRemaining(entry -> {
                String environmentName = entry.getValue().asText("").trim();
                if (!environmentName.isBlank()) {
                    String value = System.getenv(environmentName);
                    if (value != null) headers.put(entry.getKey(), value);
                }
            });
            for (JsonNode value : environmentHeaders) {
                String environmentName = value.asText("").trim();
                if (!environmentName.isBlank() && System.getenv(environmentName) == null) {
                    throw new IOException("MCP header environment variable is not set: "
                            + environmentName);
                }
            }
        }
        String bearerEnvironment = config.path("bearerTokenEnvVar")
                .asText(config.path("bearer_token_env_var").asText("")).trim();
        if (!bearerEnvironment.isBlank()) {
            String token = System.getenv(bearerEnvironment);
            if (token == null || token.isBlank()) {
                throw new IOException("MCP bearer token environment variable is not set: "
                        + bearerEnvironment);
            }
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    /**
     * Resolve a stored OAuth token for this server (from {@code kompile mcp auth login}),
     * or null when none exists. Chat loads the server once per session, so refresh
     * skew of five minutes is ample for typical tool-call lifetimes.
     */
    private static String storedAccessToken(String serverId) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault(
                "KOMPILE_MCP_AUTH_DISABLED", "false"))) {
            return null;
        }
        try {
            return new ai.kompile.cli.main.auth.oauth.McpServerAuthManager(
                    ai.kompile.cli.main.auth.CredentialStore.create())
                    .resolveAccessToken(serverId);
        } catch (Exception e) {
            System.err.println("[MCP] Could not resolve stored OAuth token for "
                    + serverId + ": " + e.getMessage());
            return null;
        }
    }

    private static Set<String> stringSet(JsonNode config, String primary, String alias) {
        JsonNode values = config.has(primary) ? config.path(primary) : config.path(alias);
        Set<String> result = new LinkedHashSet<>();
        if (values.isArray()) {
            values.forEach(value -> {
                if (!value.asText("").isBlank()) result.add(value.asText());
            });
        }
        return result;
    }

    private static String legacySseBaseUrl(String endpoint) {
        String normalized = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return normalized.endsWith("/sse")
                ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private void registerDiscoveryTools(ToolRegistry registry) {
        registry.register(new CliTool() {
            @Override public String id() { return "mcp_tool_search"; }
            @Override public String description() { return "Search the live MCP catalog — tools, prompts, and resources — by name, server, or description; returns exact tool ids and input schemas."; }
            @Override public JsonNode parameterSchema() {
                ObjectNode schema = mapper.createObjectNode().put("type", "object");
                schema.putObject("properties").putObject("query").put("type", "string");
                return schema;
            }
            @Override public String permissionKey() { return "mcp.discover"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context)
                    throws ToolExecutionException {
                context.checkPermission(permissionKey(), "Search the live MCP tool catalog");
                String query = params == null ? ""
                        : params.path("query").asText("").toLowerCase(Locale.ROOT);
                StringBuilder outputBuilder = new StringBuilder();
                tools.stream()
                        .filter(tool -> query.isBlank() || tool.id().toLowerCase(Locale.ROOT).contains(query)
                                || tool.description().toLowerCase(Locale.ROOT).contains(query))
                        .map(tool -> tool.id() + " - " + tool.description()
                                + "\n  inputSchema: " + tool.parameterSchema())
                        .sorted()
                        .limit(100)
                        .forEach(line -> outputBuilder.append(line).append('\n'));
                for (McpEndpoint client : clients) {
                    client.describePromptResourceCatalog(query).ifPresent(catalog ->
                            outputBuilder.append(catalog).append('\n'));
                }
                String output = outputBuilder.toString().stripTrailing();
                return ToolResult.success("MCP tool search",
                        output.isBlank() ? "No MCP tools matched the query." : output);
            }
            @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.READ_ONLY; }
        });

        registry.register(new CliTool() {
            @Override public String id() { return "mcp_tool_call"; }
            @Override public String description() {
                return "Call a live MCP tool by the exact id returned from mcp_tool_search. "
                        + "Pass that remote tool's normal input object under arguments.";
            }
            @Override public JsonNode parameterSchema() {
                ObjectNode schema = mapper.createObjectNode().put("type", "object");
                ObjectNode properties = schema.putObject("properties");
                properties.putObject("tool")
                        .put("type", "string")
                        .put("description", "Exact Kompile MCP tool id, such as mcp__server__tool_name");
                properties.putObject("arguments")
                        .put("type", "object")
                        .put("description", "Arguments matching the selected tool's inputSchema");
                schema.putArray("required").add("tool");
                schema.put("additionalProperties", false);
                return schema;
            }
            @Override public String permissionKey() { return "mcp.call"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context)
                    throws ToolExecutionException {
                String requested = params == null ? "" : params.path("tool").asText("").trim();
                if (requested.isBlank()) {
                    return ToolResult.error("MCP tool id is required");
                }
                List<McpTool> matches = tools.stream()
                        .filter(tool -> tool.id().equals(requested)
                                || tool.info.name().equals(requested)
                                || (tool.serverId + "/" + tool.info.name()).equals(requested))
                        .toList();
                if (matches.isEmpty()) {
                    return ToolResult.error("Unknown MCP tool: " + requested
                            + ". Use mcp_tool_search first.");
                }
                if (matches.size() > 1) {
                    return ToolResult.error("Ambiguous MCP tool name: " + requested
                            + ". Use the fully namespaced mcp__server__tool id.");
                }
                context.checkPermission(permissionKey(), "Call MCP gateway tool " + requested);
                JsonNode arguments = params.path("arguments");
                if (!arguments.isObject()) {
                    arguments = mapper.createObjectNode();
                }
                return matches.get(0).execute(arguments, context);
            }
            @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }
        });
    }

    @Override
    public void close() {
        ToolRegistry owner = registry;
        if (owner != null) {
            tools.forEach(tool -> owner.unregister(tool.id()));
            owner.unregister("mcp_tool_search");
            owner.unregister("mcp_tool_call");
        }
        clients.forEach(client -> {
            try { client.close(); } catch (Exception ignored) { }
        });
        clients.clear();
    }

    record RemoteTool(String name, String description, JsonNode inputSchema) { }
    record RemoteCallResult(String content, Map<String, Object> extraContent, boolean error) {
        RemoteCallResult(String content, boolean error) {
            this(content, null, error);
        }
    }

    private static final class RequiredMcpServerException extends IllegalStateException {
        private RequiredMcpServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private interface McpEndpoint extends AutoCloseable {
        void initialize() throws Exception;
        List<RemoteTool> listTools() throws Exception;
        RemoteCallResult callTool(String name, JsonNode params) throws Exception;

        /**
         * Best-effort prompts/resources catalog. Empty when the server declares
         * neither capability.
         */
        default java.util.Optional<String> describePromptResourceCatalog(String query) {
            return java.util.Optional.empty();
        }
    }

    private static final class SseEndpoint implements McpEndpoint {
        private final McpSseClient client;
        private SseEndpoint(McpSseClient client) { this.client = client; }
        @Override public void initialize() throws Exception {
            client.connect();
            client.initialize();
            client.notifyInitialized();
        }
        @Override public List<RemoteTool> listTools() throws Exception {
            return client.listTools().stream()
                    .map(tool -> new RemoteTool(tool.getName(), tool.getDescription(), tool.getInputSchema()))
                    .toList();
        }
        @Override public RemoteCallResult callTool(String name, JsonNode params) throws Exception {
            McpSseClient.ToolCallResult result = client.callToolResult(name, params);
            return new RemoteCallResult(result.content(), result.error());
        }
        @Override public void close() { client.close(); }
    }

    private static final class HttpEndpoint implements McpEndpoint {
        private final McpStreamableHttpClient client;
        private HttpEndpoint(McpStreamableHttpClient client) { this.client = client; }
        @Override public void initialize() throws Exception { client.initialize(); }
        @Override public List<RemoteTool> listTools() throws Exception { return client.listTools(); }
        @Override public RemoteCallResult callTool(String name, JsonNode params) throws Exception {
            return renderResult(client.callTool(name, params));
        }
        @Override public java.util.Optional<String> describePromptResourceCatalog(String query) {
            try {
                JsonNode catalog = client.promptResourceCatalog();
                if (catalog.isEmpty()) return java.util.Optional.empty();
                return java.util.Optional.of("prompts/resources: " + catalog);
            } catch (Exception e) {
                return java.util.Optional.empty();
            }
        }
        @Override public void close() { client.close(); }
    }

    private static final class StdioEndpoint implements McpEndpoint {
        private final McpStdioClient client;
        private StdioEndpoint(McpStdioClient client) { this.client = client; }
        @Override public void initialize() throws Exception { client.initialize(); }
        @Override public List<RemoteTool> listTools() throws Exception { return client.listTools(); }
        @Override public RemoteCallResult callTool(String name, JsonNode params) throws Exception {
            return renderResult(client.callTool(name, params));
        }
        @Override public void close() { client.close(); }
    }

    private static RemoteCallResult renderResult(JsonNode result) {
        JsonNode content = result.path("content");
        StringBuilder text = new StringBuilder();
        Map<String, Object> extraContent = new LinkedHashMap<>();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText();
                if ("text".equals(type)) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(item.path("text").asText());
                } else if ("image".equals(type) || "audio".equals(type)) {
                    extraContent.computeIfAbsent("attachments", k -> new ArrayList<String>());
                    @SuppressWarnings("unchecked")
                    List<String> attachments = (List<String>) extraContent.get("attachments");
                    attachments.add(type + ":" + item.path("mimeType").asText("application/octet-stream")
                            + ";base64," + item.path("data").asText(""));
                } else if ("resource".equals(type)) {
                    JsonNode resource = item.path("resource");
                    if (!text.isEmpty()) text.append('\n');
                    text.append("[resource ").append(resource.path("uri").asText("")).append("]\n")
                            .append(resource.path("text").asText(""));
                }
            }
        }
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isMissingNode()) {
            extraContent.put("structuredContent", structured.toString());
        }
        String rendered = text.isEmpty()
                ? (result.isTextual() ? result.asText() : result.toString())
                : text.toString();
        return new RemoteCallResult(rendered, extraContent.isEmpty()
                ? null : extraContent,
                result.path("isError").asBoolean(false));
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
                context.checkPermission(permissionKey(), "Call MCP " + serverId + "/" + info.name());
                RemoteCallResult result = client.callTool(info.name(), params);
                if (result.error()) {
                    return ToolResult.error(result.content());
                }
                return result.extraContent() == null || result.extraContent().isEmpty()
                        ? ToolResult.success("MCP " + serverId + "/" + info.name(), result.content())
                        : ToolResult.success("MCP " + serverId + "/" + info.name(),
                                result.content(), result.extraContent());
            } catch (ToolExecutionException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolExecutionException("MCP tool failed: " + serverId + "/" + info.name(), e);
            }
        }
        @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }
        private static String normalize(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    }
}
