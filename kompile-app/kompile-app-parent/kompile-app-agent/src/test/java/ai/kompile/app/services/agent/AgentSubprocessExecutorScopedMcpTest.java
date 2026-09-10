package ai.kompile.app.services.agent;

import ai.kompile.core.agent.AgentProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentSubprocessExecutorScopedMcpTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsStrictTemporaryConfigAndDeletesItAfterTheTurn() throws Exception {
        AgentSubprocessExecutor executor = executor();
        AgentProvider agent = scopedAgent();
        String endpoint = "http://localhost:8081/mcp/scoped/" + "A".repeat(43) + "/sse";

        AgentSubprocessExecutor.PreparedCommand prepared = executor.buildScopedCommand(
                agent, true, List.of("--verbose"), "question", tempDir.toString(), endpoint);
        Path config = prepared.ephemeralMcpConfig();

        assertTrue(Files.isRegularFile(config));
        assertTrue(Files.readString(config).contains(endpoint));
        assertTrue(prepared.command().contains("--strict-mcp-config"));
        assertEquals(1, prepared.command().stream().filter("--mcp-config"::equals).count());
        assertFalse(prepared.command().contains(endpoint),
                "the bearer URL must stay out of process arguments and diagnostics");

        prepared.close();
        assertFalse(Files.exists(config));
        prepared.close();
    }

    @Test
    void rejectsCallerMcpOverridesButLeavesLegacyNonMcpCommandsUnchanged() {
        AgentSubprocessExecutor executor = executor();
        AgentProvider agent = scopedAgent();
        String endpoint = "http://localhost:8081/mcp/scoped/" + "B".repeat(43) + "/sse";

        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                agent, true, List.of("--mcp-config", "/tmp/attacker.json"),
                "question", tempDir.toString(), endpoint));
        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                agent, true, List.of("--mcp-server=attacker"),
                "question", tempDir.toString(), endpoint));
        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                agent, true, List.of("--strict-mcp-config=false"),
                "question", tempDir.toString(), endpoint));

        AgentSubprocessExecutor.PreparedCommand isolated = executor.buildIsolatedCommand(
                agent, false, List.of(), "isolated", tempDir.toString());
        assertTrue(isolated.command().contains("--strict-mcp-config"));
        assertTrue(readUnchecked(isolated.ephemeralMcpConfig()).contains("\"mcpServers\": {}"));
        isolated.close();

        AgentProvider unsafe = scopedAgent();
        unsafe.setHelpOutput("--mcp-config");
        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                unsafe, true, List.of(), "question", tempDir.toString(), endpoint));

        List<String> legacy = executor.buildCommand(
                agent, false, false, List.of("--verbose"), "legacy", tempDir.toString());
        assertFalse(legacy.contains("--mcp-config"));
        assertFalse(legacy.contains("--strict-mcp-config"));
        assertTrue(legacy.contains("legacy"));
    }

    @Test
    void buildsFileFreeStrictCodexScopeFromPerInvocationConfig() {
        AgentSubprocessExecutor executor = executor();
        AgentProvider codex = AgentProvider.builder()
                .name("codex-cli")
                .displayName("Codex")
                .command("codex")
                .mcpSupported(true)
                .helpOutput("--config\n--ignore-user-config\n--ephemeral")
                .available(true)
                .build();
        String endpoint = "http://localhost:8081/mcp/scoped/" + "C".repeat(43) + "/sse";

        AgentSubprocessExecutor.PreparedCommand scoped = executor.buildScopedCommand(
                codex, false, List.of(), "use the private graph", tempDir.toString(), endpoint);
        assertNull(scoped.ephemeralMcpConfig());
        assertTrue(scoped.command().contains("--ignore-user-config"));
        assertTrue(scoped.command().contains("--ephemeral"));
        assertTrue(scoped.command().contains(
                "mcp_servers.kompile_private_graph.url=\"" + endpoint + "\""));
        assertTrue(scoped.command().indexOf("-c") < scoped.command().indexOf("exec"));
        assertTrue(scoped.command().indexOf("--ignore-user-config")
                > scoped.command().indexOf("exec"));

        AgentSubprocessExecutor.PreparedCommand empty = executor.buildIsolatedCommand(
                codex, false, List.of(), "no tools", tempDir.toString());
        assertFalse(empty.command().contains("-c"));
        assertTrue(empty.command().contains("--ignore-user-config"));

        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                codex, false, List.of("-c", "mcp_servers.attacker.url=\"http://localhost:9\""),
                "question", tempDir.toString(), endpoint));
        assertThrows(IllegalArgumentException.class, () -> executor.buildScopedCommand(
                codex, false, List.of("--profile=attacker"),
                "question", tempDir.toString(), endpoint));
    }

    private AgentSubprocessExecutor executor() {
        return new AgentSubprocessExecutor(
                mock(AgentRegistryService.class),
                mock(AgentProcessDiagnosticService.class),
                mock(ClaudeStreamParser.class),
                tempDir.resolve("config"));
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static AgentProvider scopedAgent() {
        return AgentProvider.builder()
                .name("claude-cli")
                .displayName("Claude")
                .command("claude")
                .mcpSupported(true)
                .mcpConfigFlag("--mcp-config")
                .helpOutput("--mcp-config --strict-mcp-config")
                .available(true)
                .build();
    }
}
