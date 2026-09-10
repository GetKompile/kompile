package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBundleToolLoaderTest {

    @TempDir
    Path workspace;

    @Test
    void exposesSearchAndCallGatewaysForStdioBundle() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path server = workspace.resolve("fake-mcp.sh");
        Path callMarker = workspace.resolve("tool-called.txt");
        Files.writeString(server, """
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
                      printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"echo_value","description":"Echo a value","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}}]}}\n' "$request_id"
                      ;;
                    *'"method":"tools/call"'*)
                      request_id=$((request_id + 1))
                      printf 'called\n' >> "$MCP_CALL_MARKER"
                      if [[ "$line" == *'"value":"fail"'* ]]; then
                        printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"remote failure"}],"isError":true}}\n' "$request_id"
                      else
                        printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"pong"}],"isError":false}}\n' "$request_id"
                      fi
                      ;;
                  esac
                done
                """.stripLeading(), StandardCharsets.UTF_8);
        assertTrue(server.toFile().setExecutable(true));

        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "fake": {
                      "command": "%s",
                      "env": {"MCP_CALL_MARKER": "%s"}
                    }
                  },
                  "kompile.dashboard": {
                    "server": "fake",
                    "tool": "mcp__fake__echo_value",
                    "readOnly": true,
                    "arguments": {"value": "dashboard"},
                    "refreshAfterTools": ["mcp__fake__echo_value"]
                  }
                }
                """.formatted(
                        server.toString().replace("\\", "\\\\"),
                        callMarker.toString().replace("\\", "\\\\")));

        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(mapper);
        AgentConfig agent = AgentConfig.builder("gm").build();
        ToolContext context = new ToolContext(
                "test-session", agent, new PermissionService(), workspace, registry);

        try (McpBundleToolLoader loader = McpBundleToolLoader.load(workspace, registry)) {
            CliTool search = registry.get("mcp_tool_search");
            CliTool call = registry.get("mcp_tool_call");
            assertNotNull(search);
            assertNotNull(call);
            assertNotNull(registry.get("mcp__fake__echo_value"));
            assertTrue(loader.dashboardConfig().isPresent());
            assertEquals("mcp__fake__echo_value", loader.dashboardConfig().orElseThrow().tool());
            assertEquals("dashboard", loader.dashboardConfig().orElseThrow()
                    .arguments().path("value").asText());
            assertEquals("pong", loader.callDashboardSource(context).content());

            var searchResult = search.execute(
                    mapper.createObjectNode().put("query", "echo_value"), context);
            assertFalse(searchResult.isError());
            assertTrue(searchResult.getOutput().contains("mcp__fake__echo_value"));
            assertTrue(searchResult.getOutput().contains("inputSchema"));

            ObjectNode callInput = mapper.createObjectNode()
                    .put("tool", "mcp__fake__echo_value");
            callInput.putObject("arguments").put("value", "ping");
            var callResult = call.execute(callInput, context);
            assertFalse(callResult.isError());
            assertTrue(callResult.getOutput().contains("pong"));

            callInput.with("arguments").put("value", "fail");
            var failedResult = call.execute(callInput, context);
            assertTrue(failedResult.isError());
            assertTrue(failedResult.getOutput().contains("remote failure"));

            Files.delete(callMarker);
            AgentConfig deniedAgent = AgentConfig.builder("denied")
                    .permissionOverrides(Map.of(
                            "mcp.call", PermissionService.PermissionLevel.DENY))
                    .build();
            ToolContext denied = new ToolContext(
                    "denied-session", deniedAgent, new PermissionService(), workspace, registry);
            assertThrows(ToolExecutionException.class, () -> call.execute(callInput, denied));
            AgentConfig dashboardDeniedAgent = AgentConfig.builder("dashboard-denied")
                    .permissionOverrides(Map.of(
                            "mcp.fake", PermissionService.PermissionLevel.DENY))
                    .build();
            ToolContext dashboardDenied = new ToolContext(
                    "dashboard-denied-session", dashboardDeniedAgent,
                    new PermissionService(), workspace, registry);
            assertThrows(ToolExecutionException.class,
                    () -> loader.callDashboardSource(dashboardDenied));
            assertFalse(Files.exists(callMarker), "denied gateway must not reach remote MCP");
        }
    }

    @Test
    void validatesTheProjectDashboardDeclarationWithoutMutatingServerConfig() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {"game": {"command": "unused"}},
                  "kompile.dashboard": {
                    "server": "game",
                    "tool": "mcp__game__get_dashboard",
                    "readOnly": true,
                    "arguments": {"compact": true},
                    "refreshAfterTools": [
                      "mcp__game__advance",
                      "mcp__game__advance"
                    ]
                  }
                }
                """);

        McpConfigStore store = new McpConfigStore(workspace);
        McpConfigStore.DashboardConfig dashboard = store.projectDashboard().orElseThrow();

        assertTrue(store.hasProjectDashboardConfig());
        assertEquals("game", dashboard.serverName());
        assertEquals("mcp__game__get_dashboard", dashboard.tool());
        assertTrue(dashboard.arguments().path("compact").asBoolean());
        assertEquals(java.util.Set.of("mcp__game__advance"), dashboard.refreshAfterTools());
        assertTrue(new ObjectMapper().readTree(workspace.resolve(".mcp.json").toFile())
                .path("mcpServers").isObject());
    }

    @Test
    void rejectsAnUnnamespacedDashboardTool() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {"mcpServers":{"game":{"command":"unused"}},
                 "kompile.dashboard":{"tool":"dangerous","readOnly":true}}
                """);

        IOException failure = assertThrows(IOException.class,
                () -> new McpConfigStore(workspace).projectDashboard());

        assertTrue(failure.getMessage().contains("exact namespaced MCP tool id"));
    }

    @Test
    void dashboardMustTargetAProjectDeclaredServerAndDeclareReadOnlyIntent() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {"mcpServers":{"project":{"command":"unused"}},
                 "kompile.dashboard":{"server":"user_only","tool":"mcp__user_only__dashboard","readOnly":true}}
                """);
        IOException wrongServer = assertThrows(IOException.class,
                () -> new McpConfigStore(workspace).projectDashboard());
        assertTrue(wrongServer.getMessage().contains("same project file"));

        Files.writeString(workspace.resolve(".mcp.json"), """
                {"mcpServers":{"project":{"command":"unused"}},
                 "kompile.dashboard":{"server":"project","tool":"mcp__project__dashboard"}}
                """);
        IOException notReadOnly = assertThrows(IOException.class,
                () -> new McpConfigStore(workspace).projectDashboard());
        assertTrue(notReadOnly.getMessage().contains("readOnly must be true"));
    }

    @Test
    void dashboardCannotResolveToADifferentServerWithTheSameNormalizedId() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path called = workspace.resolve("wrong-server-called.txt");
        Path server = workspace.resolve("colliding-mcp.sh");
        Files.writeString(server, """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}}\n'
                      ;;
                    *'"method":"tools/list"'*)
                      printf '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"dashboard","description":"Dashboard","inputSchema":{"type":"object"}}]}}\n'
                      ;;
                    *'"method":"tools/call"'*)
                      printf called > "$CALL_MARKER"
                      printf '{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"wrong"}],"isError":false}}\n'
                      ;;
                  esac
                done
                """.stripLeading());
        assertTrue(server.toFile().setExecutable(true));
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "foo_bar": {"command":"%s","env":{"CALL_MARKER":"%s"}},
                    "foo.bar": {"command":"%s","env":{"CALL_MARKER":"%s"}}
                  },
                  "kompile.dashboard": {
                    "server":"foo.bar",
                    "tool":"mcp__foo_bar__dashboard",
                    "readOnly":true
                  }
                }
                """.formatted(server, called, server, called));

        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        ToolContext context = new ToolContext(
                "collision-session", AgentConfig.builder("gm").build(),
                new PermissionService(), workspace, registry);
        try (McpBundleToolLoader loader = McpBundleToolLoader.load(workspace, registry)) {
            assertEquals("foo.bar", loader.dashboardConfig().orElseThrow().serverName());
            IOException failure = assertThrows(IOException.class,
                    () -> loader.callDashboardSource(context));
            assertTrue(failure.getMessage().contains("unavailable"));
            assertFalse(Files.exists(called),
                    "dashboard must never fall back to the colliding foo_bar server");
        }
    }

    @Test
    @ResourceLock("user.home")
    void capturesEachBundleServersStderrUnderTheTranscriptUuid() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        String previousHome = System.getProperty("user.home");
        Path home = workspace.resolve("home");
        Files.createDirectories(home);
        Path server = workspace.resolve("diagnostic-mcp.sh");
        Files.writeString(server, """
                #!/usr/bin/env bash
                printf 'transcript=%s diagnostic-before-init\n' "$KOMPILE_TRANSCRIPT_UUID" >&2
                printf '{"level":"error","message":"structured child diagnostic"}\n'
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}}\n'
                      ;;
                    *'"method":"tools/list"'*)
                      printf '{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}\n'
                      ;;
                  esac
                done
                """.stripLeading());
        assertTrue(server.toFile().setExecutable(true));
        Files.writeString(workspace.resolve(".mcp.json"), """
                {"mcpServers":{"fake":{"command":"%s","required":true}}}
                """.formatted(server.toString().replace("\\", "\\\\")));
        String transcriptId = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", home.toString());
            ToolRegistry registry = new ToolRegistry(new ObjectMapper());
            try (McpBundleToolLoader ignored =
                         McpBundleToolLoader.load(workspace, registry, transcriptId)) {
                Path stderr = LogPaths.transcriptDirectory(transcriptId).toPath()
                        .resolve("mcp").resolve("bundle-fake.stderr.log");
                assertTrue(Files.isRegularFile(stderr));
                String diagnostics = Files.readString(stderr);
                assertTrue(diagnostics.contains("transcript=" + transcriptId));
                assertTrue(diagnostics.contains("diagnostic-before-init"));
                Path stdoutDiagnostics = LogPaths.transcriptDirectory(transcriptId).toPath()
                        .resolve("mcp").resolve("bundle-fake.stdout-diagnostics.log");
                assertTrue(Files.readString(stdoutDiagnostics)
                        .contains("structured child diagnostic"));
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    @ResourceLock("user.home")
    void preservesNonProtocolStdoutFromACrashedMcpJvm(@TempDir Path tempHome)
            throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        String previousHome = System.getProperty("user.home");
        Path server = workspace.resolve("crashing-mcp.sh");
        Files.writeString(server, """
                #!/usr/bin/env bash
                printf '# A fatal error has been detected by the Java Runtime Environment:\n'
                printf '# SIGSEGV (0xb) in child process\n'
                exit 134
                """.stripLeading());
        assertTrue(server.toFile().setExecutable(true));
        String transcriptId = UUID.randomUUID().toString();
        try {
            System.setProperty("user.home", tempHome.toString());
            try (McpStdioClient client = new McpStdioClient(
                    new ObjectMapper(), server.toString(), List.of(), Map.of(),
                    workspace, 2, "crash", transcriptId)) {
                assertThrows(IOException.class, client::initialize);
            }
            Path diagnostics = LogPaths.transcriptDirectory(transcriptId).toPath()
                    .resolve("mcp").resolve("bundle-crash.stdout-diagnostics.log");
            String content = Files.readString(diagnostics);
            assertTrue(content.contains("fatal error"));
            assertTrue(content.contains("SIGSEGV"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void requiredServerFailureAbortsBundleLoading() throws Exception {
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "required": {
                      "command": "/path/that/does/not/exist",
                      "required": true
                    }
                  }
                }
                """);
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        assertThrows(IllegalStateException.class,
                () -> McpBundleToolLoader.load(workspace, registry));
        assertFalse(registry.ids().contains("mcp_tool_call"));
    }

    @Test
    void partialStdioFrameCannotDefeatConfiguredTimeout() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path server = workspace.resolve("partial-frame.sh");
        Files.writeString(server, """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  if [[ "$line" == *'"method":"initialize"'* ]]; then
                    printf '{'
                    exec sleep 30
                  fi
                done
                """.stripLeading());
        assertTrue(server.toFile().setExecutable(true));
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "partial": {
                      "command": "%s",
                      "required": true,
                      "timeoutSeconds": 1
                    }
                  }
                }
                """.formatted(server));

        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        long started = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> McpBundleToolLoader.load(workspace, registry));
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        assertTrue(elapsedMillis < 5_000,
                "partial frame should time out promptly, elapsed=" + elapsedMillis);
    }

    @Test
    void rejectsOversizedStdioFrameWithoutBufferingPastLimit() {
        BufferedReader reader = new BufferedReader(new StringReader("123456789\n"));
        IOException failure = assertThrows(IOException.class,
                () -> McpStdioClient.readBoundedLine(reader, 8));
        assertTrue(failure.getMessage().contains("exceeds 8"));
    }

    @Test
    void interactiveLoaderDoesNotExecuteUntrustedWorkspaceConfig() throws Exception {
        Assumptions.assumeTrue(System.console() == null,
                "test relies on the non-interactive trust default");
        Path marker = workspace.resolve("spawned.txt");
        Path command = workspace.resolve("untrusted.sh");
        Files.writeString(command, "#!/usr/bin/env bash\nprintf spawned > \"$MCP_MARKER\"\n");
        assertTrue(command.toFile().setExecutable(true));
        Files.writeString(workspace.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "untrusted": {
                      "command": "%s",
                      "env": {"MCP_MARKER": "%s"}
                    }
                  }
                }
                """.formatted(command, marker));

        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        try (McpBundleToolLoader ignored =
                     McpBundleToolLoader.loadInteractive(workspace, registry)) {
            assertFalse(Files.exists(marker));
            assertFalse(registry.ids().contains("mcp_tool_call"));
        }
    }
}
