package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpProductPolicyTest {
    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    private ProductChatPolicy policy(String config, boolean withServer) throws Exception {
        Files.writeString(workspace.resolve("role.md"), "Frozen role");
        Files.writeString(workspace.resolve("bundle.json"), config);
        var root = mapper.createObjectNode().put("schemaVersion", ProductChatPolicy.SCHEMA).put("id", "play")
                .put("title", "Play").put("role", "gm").put("roleFile", "role.md")
                .put("mcpConfigFile", "bundle.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 1800).put("turnTimeoutSeconds", 300);
        var nativeTools = root.putArray("nativeTools");
        if (withServer) nativeTools.add("mcp_tool_search").add("mcp_tool_call");
        var servers = root.putArray("mcpServers");
        var reads = root.putObject("readCapabilities");
        if (withServer) { servers.add("elthoria"); reads.put("connection", "mcp__elthoria__get_play_connection"); }
        Files.writeString(workspace.resolve("policy.json"), root.toString());
        return ProductChatPolicy.load(workspace.resolve("policy.json"), workspace);
    }

    @Test void productLaunchExpandsReferencesWithoutShellParsing() throws Exception {
        String home = System.getenv("HOME");
        assertNotNull(home);
        assertEquals(home + "/a path/jar", McpBundleToolLoader.productLaunchValue("${HOME}/a path/jar", true));
        assertEquals("${HOME}/literal", McpBundleToolLoader.productLaunchValue("${HOME}/literal", false));
        assertThrows(java.io.IOException.class, () -> McpBundleToolLoader.productLaunchValue("${ELTHORIA_MISSING_QUALIFICATION_ENV_871823}", true));
    }

    @Test void ignoresUnrelatedWorkspaceServersAndFailsClosedOnSnapshotMismatch() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), "{\"mcpServers\":{\"foreign\":{\"command\":\"must-never-start\"}}}");
        ProductChatPolicy empty = policy("{\"mcpServers\":{}}", false);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        registry.freezeProductPolicy(empty, Set.of());
        try (var ignored = McpBundleToolLoader.loadProduct(empty, registry, "product-test")) {
            assertTrue(registry.ids().isEmpty());
        }
        ProductChatPolicy mismatch = policy("{\"mcpServers\":{\"foreign\":{\"command\":\"must-never-start\"}}}", true);
        ToolRegistry next = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        next.freezeProductPolicy(mismatch, Set.of());
        assertThrows(IllegalStateException.class, () -> McpBundleToolLoader.loadProduct(mismatch, next, "product-test"));
        assertTrue(next.ids().isEmpty());
    }

    @Test void productSharedTransportsAreRefusedBeforeOpeningAnHttpSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            calls.incrementAndGet();
            byte[] response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"fixture\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            for (String transport : Set.of("http", "sse")) {
                var bundle = mapper.createObjectNode();
                bundle.putObject("mcpServers").putObject("elthoria").put("transport", transport)
                        .put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
                ProductChatPolicy frozen = policy(bundle.toString(), true);
                ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
                registry.freezeProductPolicy(frozen, Set.of());
                var failure = assertThrows(IllegalStateException.class,
                        () -> McpBundleToolLoader.loadProduct(frozen, registry, "product-closed-transport"));
                StringBuilder causes = new StringBuilder();
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) causes.append(cause.getMessage());
                assertTrue(causes.toString().contains("private stdio MCP child"), causes.toString());
                assertEquals(0, calls.get());
            }
        } finally { server.stop(0); }
    }

    @Test void thrownRemoteToolExceptionIsUnknownNotBusinessValidation() throws Exception {
        ProductChatPolicy frozen = policy("{\"mcpServers\":{\"elthoria\":{}}}", true);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        registry.freezeProductPolicy(frozen, Set.of());
        try (var endpoint = new ProductMcpTestEndpoint(() -> { throw new ToolExecutionException("remote wait failed"); })) {
            registry.registerProductMcp(endpoint.tool(), frozen);
            ToolContext context = new ToolContext("product-unknown", null, new PermissionService(), workspace, registry);
            context.setAutoApproveAll(true);
            registry.openProductTurn();
            assertThrows(ToolExecutionException.class, () -> registry.get(endpoint.tool().id()).execute(mapper.createObjectNode(), context));
            registry.awaitProductQuiescence();
            assertTrue(registry.hasProductUnknownOutcome());
            assertThrows(IllegalStateException.class, registry::openProductTurn);
            assertEquals(1, endpoint.calls());
            assertTrue(endpoint.isOpen(), "unknown outcome never triggers automatic MCP teardown");
        }
    }

    @Test void namedReadKeepsNormalMcpPermissionsAndSameChildAcrossIdle() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path script = workspace.resolve("fixture-mcp.sh"), marker = workspace.resolve("calls");
        Files.writeString(script, """
                #!/usr/bin/env bash
                request_id=0
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      request_id=$((request_id + 1))
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fixture","version":"1"}}}\\n' "$request_id" ;;
                    *'"method":"tools/list"'*)
                      request_id=$((request_id + 1))
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"get_play_connection","description":"Read connection","inputSchema":{"type":"object"}}]}}\\n' "$request_id" ;;
                    *'"method":"tools/call"'*)
                      request_id=$((request_id + 1))
                      printf called >> "$CALL_MARKER"
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"same-child"}],"isError":false}}\\n' "$request_id" ;;
                  esac
                done
                """);
        assertTrue(script.toFile().setExecutable(true));
        var bundled = mapper.createObjectNode();
        bundled.putObject("mcpServers").putObject("elthoria").put("command", script.toString())
                .putObject("env").put("CALL_MARKER", marker.toString());
        ProductChatPolicy frozen = policy(bundled.toString(), true);
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        registry.freezeProductPolicy(frozen, Set.of());
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("mcp.elthoria", PermissionService.PermissionLevel.DENY);
        ToolContext context = new ToolContext("product-permissions-test", null, permissions, workspace, registry);
        try (var loader = McpBundleToolLoader.loadProduct(frozen, registry, "product-permissions-test")) {
            registry.openProductTurn();
            assertThrows(ToolExecutionException.class, () -> loader.callReadCapability("connection", context));
            registry.awaitProductQuiescence();
            assertFalse(Files.exists(marker));
            assertFalse(registry.hasProductUnknownOutcome(), "permission rejection never dispatched remotely");
            assertNull(registry.get("mcp_tool_call"));
            assertThrows(ToolExecutionException.class, () -> loader.callReadCapability("arbitrary", context));
            permissions.setUserOverride("mcp.elthoria", PermissionService.PermissionLevel.ALLOW);
            for (int turn = 0; turn < 2; turn++) {
                registry.openProductTurn();
                assertEquals("same-child", loader.callReadCapability("connection", context).getOutput());
                registry.awaitProductQuiescence(); // idle does not close/recreate the MCP child.
            }
            assertEquals("calledcalled", Files.readString(marker));
        }
        // The model-facing product surface is the same two-schema gateway as the GM harness.
        ToolRegistry gateways = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        gateways.freezeProductPolicy(frozen, Set.of("mcp_tool_search", "mcp_tool_call"));
        ToolContext gatewayContext = new ToolContext("product-gateway-test", null, permissions, workspace, gateways);
        try (var ignored = McpBundleToolLoader.loadProduct(frozen, gateways, "product-gateway-test")) {
            assertEquals(2, gateways.getToolsForAgent(null).size());
            assertTrue(gateways.getToolsForAgent(null).stream().allMatch(tool -> tool.id().startsWith("mcp_tool_")));
            assertNotNull(gateways.get("mcp__elthoria__get_play_connection"), "Remote target stays privately callable");
            gateways.openProductTurn();
            var search = mapper.createObjectNode().put("query", "get_play_connection");
            assertTrue(gateways.get("mcp_tool_search").execute(search, gatewayContext).getOutput()
                    .contains("mcp__elthoria__get_play_connection"));
            var call = mapper.createObjectNode().put("tool", "mcp__elthoria__get_play_connection");
            call.putObject("arguments");
            assertEquals("same-child", gateways.get("mcp_tool_call").execute(call, gatewayContext).getOutput());
            gateways.awaitProductQuiescence();
            assertEquals("calledcalledcalled", Files.readString(marker));
        }
    }
}
