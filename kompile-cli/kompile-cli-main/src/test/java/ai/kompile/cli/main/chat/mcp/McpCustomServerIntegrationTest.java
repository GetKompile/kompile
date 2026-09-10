/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("user.home")
class McpCustomServerIntegrationTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void projectServersOverrideUserServersAndDisabledOrReservedEntriesNeverStart()
            throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase().contains("win"));
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
            Path userScript = fakeServer("user-server.sh", "user");
            Path projectScript = fakeServer("project-server.sh", "project");
            Path userOnlyScript = fakeServer("user-only-server.sh", "user-only");
            Path forbiddenMarker = tempDir.resolve("forbidden-start.txt");
            Path forbiddenScript = tempDir.resolve("forbidden.sh");
            Files.writeString(forbiddenScript,
                    "#!/usr/bin/env bash\nprintf started > '" + forbiddenMarker + "'\n");
            assertTrue(forbiddenScript.toFile().setExecutable(true));

            McpConfigStore store = new McpConfigStore(workspace);
            store.put("shared", stdioConfig(userScript, true), McpConfigStore.Scope.USER, false);
            store.put("user-only", stdioConfig(userOnlyScript, true), McpConfigStore.Scope.USER, false);
            store.put("shared", stdioConfig(projectScript, true), McpConfigStore.Scope.PROJECT, false);
            store.put("disabled", stdioConfig(forbiddenScript, false), McpConfigStore.Scope.PROJECT, false);

            ObjectNode projectRoot = (ObjectNode) mapper.readTree(
                    store.path(McpConfigStore.Scope.PROJECT).toFile());
            projectRoot.with("mcpServers").set("kompile", stdioConfig(forbiddenScript, true));
            Files.writeString(store.path(McpConfigStore.Scope.PROJECT),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectRoot));

            ToolRegistry registry = new ToolRegistry(mapper);
            ToolContext context = context(workspace, registry);
            try (McpBundleToolLoader ignored = McpBundleToolLoader.load(workspace, registry)) {
                assertNotNull(registry.get("mcp__shared__echo_value"));
                assertNotNull(registry.get("mcp__user-only__echo_value"));
                assertFalse(registry.ids().contains("mcp__disabled__echo_value"));
                assertFalse(Files.exists(forbiddenMarker));

                JsonNode input = mapper.createObjectNode().put("value", "ping");
                var projectResult = registry.get("mcp__shared__echo_value")
                        .execute(input, context);
                assertFalse(projectResult.isError());
                assertTrue(projectResult.getOutput().contains("project"));

                var userResult = registry.get("mcp__user-only__echo_value")
                        .execute(input, context);
                assertTrue(userResult.getOutput().contains("user-only"));

                CliTool search = registry.get("mcp_tool_search");
                assertNotNull(search);
                var searchResult = search.execute(
                        mapper.createObjectNode().put("query", "echo_value"), context);
                assertTrue(searchResult.getOutput().contains("mcp__shared__echo_value"));
                assertTrue(searchResult.getOutput().contains("mcp__user-only__echo_value"));
            }
            assertFalse(Files.exists(forbiddenMarker));
        });
    }

    @Test
    void streamableHttpServerIsDiscoveredAndCalledWithSessionAndHeaders()
            throws Exception {
        withIsolatedHome(() -> {
            AtomicReference<String> observedHeader = new AtomicReference<>();
            AtomicReference<String> observedSession = new AtomicReference<>();
            AtomicReference<String> observedProtocol = new AtomicReference<>();
            AtomicInteger initializeCount = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> handleHttpMcp(
                    exchange, observedHeader, observedSession, observedProtocol,
                    initializeCount));
            server.start();
            try {
                Path workspace = Files.createDirectories(tempDir.resolve("http-workspace"));
                McpConfigStore store = new McpConfigStore(workspace);
                ObjectNode config = mapper.createObjectNode();
                config.put("type", "http");
                config.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
                config.put("timeoutSeconds", 5);
                config.putObject("headers").put("X-Test-Header", "configured");
                config.putArray("includeTools").add("echo_value");
                store.put("remote", config, McpConfigStore.Scope.PROJECT, false);

                ToolRegistry registry = new ToolRegistry(mapper);
                try (McpBundleToolLoader ignored = McpBundleToolLoader.load(workspace, registry)) {
                    assertNotNull(registry.get("mcp__remote__echo_value"));
                    assertFalse(registry.ids().contains("mcp__remote__hidden_value"));
                    var result = registry.get("mcp__remote__echo_value").execute(
                            mapper.createObjectNode().put("value", "hello"),
                            context(workspace, registry));
                    assertFalse(result.isError());
                    assertTrue(result.getOutput().contains("remote-pong"));
                    assertEquals("{\"items\":[{\"kind\":\"note\"}]}",
                            String.valueOf(result.getMetadata().get("structuredContent")));
                    assertTrue(result.getMetadata().get("attachments").toString()
                            .contains("image/png"));

                    var search = registry.get("mcp_tool_search").execute(
                            mapper.createObjectNode(), context(workspace, registry));
                    assertTrue(search.getOutput().contains("resource-guide"),
                            "search must surface remote prompts/resources: "
                                    + search.getOutput());
                }

                assertEquals("configured", observedHeader.get());
                assertEquals(2, initializeCount.get());
                assertEquals("session-2", observedSession.get());
                assertEquals("2025-06-18", observedProtocol.get());
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void stdioChildOnlyReceivesSensitiveEnvironmentVariablesExplicitlyConfigured() {
        Map<String, String> processEnvironment = new HashMap<>();
        processEnvironment.put("PATH", "/bin");
        processEnvironment.put("SAFE_SETTING", "safe");
        processEnvironment.put("AWS_SECRET_ACCESS_KEY", "ambient-secret");
        processEnvironment.put("SSH_AUTH_SOCK", "/tmp/agent.sock");

        McpStdioClient.configureEnvironment(processEnvironment, Map.of(
                "AWS_SECRET_ACCESS_KEY", "explicit-secret",
                "EXPLICIT_TOKEN", "explicit-token"));

        assertEquals("/bin", processEnvironment.get("PATH"));
        assertEquals("safe", processEnvironment.get("SAFE_SETTING"));
        assertEquals("explicit-secret", processEnvironment.get("AWS_SECRET_ACCESS_KEY"));
        assertEquals("explicit-token", processEnvironment.get("EXPLICIT_TOKEN"));
        assertFalse(processEnvironment.containsKey("SSH_AUTH_SOCK"));
    }

    @Test
    void normalizedToolIdCollisionFailsInsteadOfOverwritingAnotherServer() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase().contains("win"));
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("collision-workspace"));
            McpConfigStore store = new McpConfigStore(workspace);
            store.put("foo.bar", stdioConfig(fakeServer("dot-server.sh", "dot"), true),
                    McpConfigStore.Scope.PROJECT, false);
            store.put("foo_bar", stdioConfig(fakeServer("underscore-server.sh", "underscore"), true),
                    McpConfigStore.Scope.PROJECT, false);

            ToolRegistry registry = new ToolRegistry(mapper);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> McpBundleToolLoader.load(workspace, registry));
            assertTrue(failure.getMessage().contains("collision"));
            assertFalse(registry.ids().contains("mcp__foo_bar__echo_value"),
                    "aborted load must not leak the first server's tools into the registry");
        });
    }

    @Test
    void stdioToolDiscoveryFollowsPaginationCursors() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase().contains("win"));
        withIsolatedHome(() -> {
            Path workspace = Files.createDirectories(tempDir.resolve("paged-workspace"));
            Path script = tempDir.resolve("paged-server.sh");
            Files.writeString(script, """
                    #!/usr/bin/env bash
                    request_id=0
                    while IFS= read -r line; do
                      case "$line" in
                        *'"method":"initialize"'*)
                          request_id=$((request_id + 1))
                          printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"paged","version":"1"}}}\n' "$request_id"
                          ;;
                        *'"method":"tools/list"'*)
                          request_id=$((request_id + 1))
                          if [[ "$line" == *'"cursor":"page-2"'* ]]; then
                            printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"second_tool","description":"second","inputSchema":{"type":"object"}}]}}\n' "$request_id"
                          else
                            printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"first_tool","description":"first","inputSchema":{"type":"object"}}],"nextCursor":"page-2"}}\n' "$request_id"
                          fi
                          ;;
                      esac
                    done
                    """.stripLeading());
            assertTrue(script.toFile().setExecutable(true));
            new McpConfigStore(workspace).put("paged", stdioConfig(script, true),
                    McpConfigStore.Scope.PROJECT, false);
            ToolRegistry registry = new ToolRegistry(mapper);
            try (McpBundleToolLoader ignored = McpBundleToolLoader.load(workspace, registry)) {
                assertNotNull(registry.get("mcp__paged__first_tool"));
                assertNotNull(registry.get("mcp__paged__second_tool"));
            }
        });
    }

    @Test
    void nestedCustomMcpDepthDisablesRecursiveServerLoading() {
        assertTrue(McpBundleToolLoader.customLoadingAllowed(null));
        assertTrue(McpBundleToolLoader.customLoadingAllowed("0"));
        assertFalse(McpBundleToolLoader.customLoadingAllowed("1"));
        assertFalse(McpBundleToolLoader.customLoadingAllowed("not-a-number"));
    }

    @Test
    void stdioShutdownReapsLauncherDescendants() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase().contains("win"));
        Process launcher = new ProcessBuilder("bash", "-c",
                "sleep 30 & echo $!; wait").start();
        long childPid;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(launcher.getInputStream(), StandardCharsets.UTF_8))) {
            childPid = Long.parseLong(reader.readLine().trim());
        }
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        assertTrue(child.isAlive());
        try {
            McpStdioClient.terminateProcessTree(launcher, 250L);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertFalse(child.isAlive(), "stdio child process survived launcher cleanup");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            if (launcher.isAlive()) launcher.destroyForcibly();
        }
    }

    @Test
    void streamableHttpResponseCannotExceedConfiguredFrameLimit() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[9]);
        IOException failure = assertThrows(IOException.class,
                () -> McpStreamableHttpClient.readBounded(input, 8));
        assertTrue(failure.getMessage().contains("exceeds 8"));
    }

    @Test
    void streamableHttpReturnsMatchingSseResponseWithoutWaitingForEof() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream output = new PipedOutputStream(input);
             McpStreamableHttpClient client = new McpStreamableHttpClient(
                     mapper, "http://127.0.0.1/mcp", Map.of(), 2)) {
            output.write(("event: message\n"
                    + "data: {\"jsonrpc\":\"2.0\",\"id\":\"7\",\"result\":{}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            String response = client.readSseUntilResponse(input, "7", 4096);
            assertEquals("7", mapper.readTree(response).path("id").asText());
            // output deliberately remains open until after the assertion.
        }
    }

    private ObjectNode stdioConfig(Path command, boolean enabled) {
        ObjectNode config = mapper.createObjectNode();
        config.put("type", "stdio");
        config.put("command", command.toString());
        config.put("enabled", enabled);
        config.put("required", true);
        config.put("timeoutSeconds", 5);
        return config;
    }

    private Path fakeServer(String name, String responseText) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, """
                #!/usr/bin/env bash
                request_id=0
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      request_id=$((request_id + 1))
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}}\n' "$request_id"
                      ;;
                    *'"method":"tools/list"'*)
                      request_id=$((request_id + 1))
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo_value","description":"Echo","inputSchema":{"type":"object"}}]}}\n' "$request_id"
                      ;;
                    *'"method":"tools/call"'*)
                      request_id=$((request_id + 1))
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"__RESPONSE__"}],"isError":false}}\n' "$request_id"
                      ;;
                  esac
                done
                """.replace("__RESPONSE__", responseText).stripLeading(), StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private void handleHttpMcp(HttpExchange exchange,
                               AtomicReference<String> observedHeader,
                               AtomicReference<String> observedSession,
                               AtomicReference<String> observedProtocol,
                               AtomicInteger initializeCount) {
        try (exchange) {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            observedHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Header"));
            if (!"initialize".equals(method)) {
                observedSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                observedProtocol.set(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            }
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            long id = request.path("id").asLong();
            ObjectNode response = mapper.createObjectNode()
                    .put("jsonrpc", "2.0").put("id", id);
            if ("initialize".equals(method)) {
                int generation = initializeCount.incrementAndGet();
                ObjectNode result = response.putObject("result");
                result.put("protocolVersion", "2025-06-18");
                result.putObject("capabilities");
                result.putObject("serverInfo")
                        .put("name", "remote-test").put("version", "1");
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-" + generation);
            } else if ("tools/list".equals(method)) {
                if (initializeCount.get() == 1) {
                    byte[] body = "expired".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                var tools = response.putObject("result").putArray("tools");
                if (request.path("params").path("cursor").asText("").isBlank()) {
                    tools.addObject().put("name", "hidden_value").put("description", "Hidden")
                            .putObject("inputSchema").put("type", "object");
                    ((ObjectNode) response.path("result")).put("nextCursor", "page-2");
                } else {
                    tools.addObject().put("name", "echo_value").put("description", "Echo")
                            .putObject("inputSchema").put("type", "object");
                }
                byte[] body = ("event: message\ndata: "
                        + mapper.writeValueAsString(response) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            } else if ("tools/call".equals(method)) {
                var content = response.putObject("result").putArray("content");
                content.addObject().put("type", "text").put("text", "remote-pong");
                content.addObject().put("type", "image").put("mimeType", "image/png")
                        .put("data", "aWNvbg==");
                ((ObjectNode) response.path("result"))
                        .putObject("structuredContent").putArray("items")
                        .addObject().put("kind", "note");
                ((ObjectNode) response.path("result")).put("isError", false);
            } else if ("prompts/list".equals(method)) {
                response.putObject("result").putArray("prompts")
                        .addObject().put("name", "resource-guide").put("description", "Guide");
            } else if ("resources/list".equals(method)) {
                response.putObject("result").putArray("resources")
                        .addObject().put("uri", "file:///guide.md").put("name", "guide");
            }
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ToolContext context(Path workspace, ToolRegistry registry) {
        return new ToolContext("test-session", AgentConfig.builder("test").build(),
                new PermissionService(), workspace, registry);
    }

    private void withIsolatedHome(ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty("user.home");
        Path home = Files.createDirectories(tempDir.resolve("home"));
        try {
            System.setProperty("user.home", home.toString());
            runnable.run();
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
