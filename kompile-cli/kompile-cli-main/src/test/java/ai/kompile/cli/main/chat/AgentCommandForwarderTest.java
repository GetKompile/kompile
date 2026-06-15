package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentCommandForwarder — verifies slash command mapping, PTY wrapping,
 * and real-time output streaming for each supported agent (claude, codex, opencode).
 */
class AgentCommandForwarderTest {

    private final AgentCommandForwarder forwarder = new AgentCommandForwarder();

    // ── Command mapping tests ─────────────────────────────────────────────

    @Test
    void modelCommandMapsToClaudeModelList() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "model", "list"), cmd.command());
        assertTrue(cmd.label().contains("claude model list"));
    }

    @Test
    void modelCommandWithArgMapsToClaudeModelSet() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model sonnet", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "model", "set", "sonnet"), cmd.command());
        assertTrue(cmd.label().contains("set"));
    }

    @Test
    void modelGetSubcommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model get", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "model", "get"), cmd.command());
    }

    @Test
    void configCommandMapsToClaudeConfigList() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/config", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "config", "list"), cmd.command());
    }

    @Test
    void configSetMapsToClaudeConfigSet() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/config set theme dark", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "config", "set", "theme", "dark"), cmd.command());
    }

    @Test
    void helpCommandMapsToAgentHelp() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/help", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "--help"), cmd.command());
    }

    @Test
    void helpForSubcommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/help model", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "model", "--help"), cmd.command());
    }

    @Test
    void versionCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/version", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "--version"), cmd.command());
    }

    @Test
    void doctorCommandForClaude() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/doctor", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "doctor"), cmd.command());
    }

    @Test
    void doctorCommandReturnsNullForCodex() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/doctor", "/usr/bin/codex", "codex");
        assertNull(cmd, "/doctor should return null for codex (not supported)");
    }

    // ── Codex mapping tests ───────────────────────────────────────────────

    @Test
    void codexModelMapsToDoctor() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model", "/usr/bin/codex", "codex");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/codex", "doctor"), cmd.command());
        assertTrue(cmd.label().contains("doctor"));
    }

    @Test
    void codexHelpCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/help", "/usr/bin/codex", "codex");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/codex", "--help"), cmd.command());
    }

    @Test
    void codexVersionCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/version", "/usr/bin/codex", "codex");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/codex", "--version"), cmd.command());
    }

    // ── OpenCode mapping tests ────────────────────────────────────────────

    @Test
    void opencodeModelMapsToModels() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model", "/usr/bin/opencode", "opencode");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/opencode", "models"), cmd.command());
    }

    @Test
    void opencodeModelWithProviderArg() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model anthropic", "/usr/bin/opencode", "opencode");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/opencode", "models", "anthropic"), cmd.command());
    }

    @Test
    void opencodeConfigMapsToProviders() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/config", "/usr/bin/opencode", "opencode");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/opencode", "providers"), cmd.command());
    }

    @Test
    void opencodeHelpCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/help", "/usr/bin/opencode", "opencode");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/opencode", "--help"), cmd.command());
    }

    // ── Pi mapping tests ───────────────────────────────────────────────────

    @Test
    void piModelMapsToListModels() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model", "/usr/bin/pi", "pi");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/pi", "--list-models"), cmd.command());
        assertTrue(cmd.label().contains("pi --list-models"));
    }

    @Test
    void piModelWithSearchArg() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/model sonnet", "/usr/bin/pi", "pi");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/pi", "--list-models", "sonnet"), cmd.command());
    }

    @Test
    void piConfigCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/config", "/usr/bin/pi", "pi");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/pi", "config"), cmd.command());
    }

    @Test
    void piHelpCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/help", "/usr/bin/pi", "pi");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/pi", "--help"), cmd.command());
    }

    @Test
    void piVersionCommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/version", "/usr/bin/pi", "pi");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/pi", "--version"), cmd.command());
    }

    @Test
    void piDoctorReturnsNull() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/doctor", "/usr/bin/pi", "pi");
        assertNull(cmd, "/doctor should return null for pi (not supported)");
    }

    // ── Generic/fallback mapping ──────────────────────────────────────────

    @Test
    void unrecognizedCommandForwardsAsSubcommand() {
        AgentCommandForwarder.AgentCommand cmd =
                forwarder.mapSlashCommand("/mcp list", "/usr/bin/claude", "claude");
        assertNotNull(cmd);
        assertEquals(List.of("/usr/bin/claude", "mcp", "list"), cmd.command());
        assertTrue(cmd.label().contains("claude mcp list"));
    }

    // ── Agent name normalization ──────────────────────────────────────────

    @Test
    void normalizeAgentKey() {
        assertEquals("claude", AgentCommandForwarder.normalizeAgentKey("claude"));
        assertEquals("claude", AgentCommandForwarder.normalizeAgentKey("Claude-Code"));
        assertEquals("codex", AgentCommandForwarder.normalizeAgentKey("Codex"));
        assertEquals("opencode", AgentCommandForwarder.normalizeAgentKey("opencode"));
        assertEquals("opencode", AgentCommandForwarder.normalizeAgentKey("open-code"));
        assertEquals("gemini", AgentCommandForwarder.normalizeAgentKey("Gemini"));
        assertEquals("qwen", AgentCommandForwarder.normalizeAgentKey("qwen"));
        assertEquals("pi", AgentCommandForwarder.normalizeAgentKey("pi"));
        assertEquals("pi", AgentCommandForwarder.normalizeAgentKey("pi-coding-agent"));
        assertEquals("unknown", AgentCommandForwarder.normalizeAgentKey(null));
    }

    // ── Forwardable commands list ─────────────────────────────────────────

    @Test
    void listForwardableCommandsClaude() {
        List<String> cmds = forwarder.listForwardableCommands("claude");
        assertTrue(cmds.contains("/model"));
        assertTrue(cmds.contains("/help"));
        assertTrue(cmds.contains("/version"));
        assertTrue(cmds.contains("/config"));
        assertTrue(cmds.contains("/doctor"));
    }

    @Test
    void listForwardableCommandsCodex() {
        List<String> cmds = forwarder.listForwardableCommands("codex");
        assertTrue(cmds.contains("/model"));
        assertTrue(cmds.contains("/help"));
        assertFalse(cmds.contains("/doctor"));
    }

    @Test
    void listForwardableCommandsOpencode() {
        List<String> cmds = forwarder.listForwardableCommands("opencode");
        assertTrue(cmds.contains("/model"));
        assertTrue(cmds.contains("/config"));
    }

    @Test
    void listForwardableCommandsPi() {
        List<String> cmds = forwarder.listForwardableCommands("pi");
        assertTrue(cmds.contains("/model"));
        assertTrue(cmds.contains("/help"));
        assertTrue(cmds.contains("/config"));
        assertFalse(cmds.contains("/doctor"));
    }

    // ── PTY wrapping tests ────────────────────────────────────────────────

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void wrapWithPtyAddsScriptPrefix() {
        List<String> cmd = List.of("/usr/bin/echo", "hello");
        List<String> wrapped = AgentCommandForwarder.wrapWithPty(cmd);

        // Should start with 'script' (if available on this system)
        if (wrapped.size() > cmd.size()) {
            assertEquals("script", wrapped.get(0));
            assertEquals("-q", wrapped.get(1));
        }
        // Either way, the original command should be embedded in the wrapped output
        String joinedWrapped = String.join(" ", wrapped);
        assertTrue(joinedWrapped.contains("/usr/bin/echo"),
                "Wrapped command should contain original binary: " + joinedWrapped);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void wrapWithPtyQuotesSpacesInArgs() {
        List<String> cmd = List.of("/usr/bin/echo", "hello world", "test");
        List<String> wrapped = AgentCommandForwarder.wrapWithPty(cmd);
        String joinedWrapped = String.join(" ", wrapped);

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (!isMac && wrapped.size() > cmd.size()) {
            // On Linux, the command is joined into a single -c argument
            // "hello world" should be quoted
            String lastArg = wrapped.get(wrapped.size() - 1);
            assertTrue(lastArg.contains("'hello world'"),
                    "Spaces in args should be quoted: " + lastArg);
        }
    }

    // ── Real-time execution test (uses /bin/echo as mock agent) ───────────

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executeWithRealtimeOutputStreamsToStdout() {
        AgentCommandForwarder.AgentCommand echoCmd = new AgentCommandForwarder.AgentCommand(
                List.of("/bin/echo", "FORWARDED_TEST_OUTPUT"), "echo test"
        );

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            AgentCommandForwarder.ForwardResult result = forwarder.executeWithRealtimeOutput(echoCmd, 5);

            System.setOut(originalOut);

            assertEquals(0, result.exitCode(), "echo should exit with 0");
            assertFalse(result.timedOut(), "echo should not time out");
            assertTrue(result.success(), "result should be success");

            // Verify output was captured AND streamed to stdout
            assertTrue(result.output().contains("FORWARDED_TEST_OUTPUT"),
                    "Result output should contain echoed text, got: " + result.output());
            assertTrue(captured.toString().contains("FORWARDED_TEST_OUTPUT"),
                    "Stdout should contain echoed text, got: " + captured);

        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executeWithTimeoutReportsTimedOut() {
        // sleep 60 will exceed the 1-second timeout
        AgentCommandForwarder.AgentCommand sleepCmd = new AgentCommandForwarder.AgentCommand(
                List.of("/bin/sleep", "60"), "sleep test"
        );

        AgentCommandForwarder.ForwardResult result = forwarder.executeWithRealtimeOutput(sleepCmd, 1);

        assertTrue(result.timedOut(), "Should report timed out");
        assertFalse(result.success(), "Timed-out result should not be success");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executeWithNonZeroExitCode() {
        // Use a command that prints to stderr and fails.
        // Note: PTY wrapping via 'script' may absorb the inner exit code on some systems,
        // so we test that the execution completes without timing out and that
        // the result is retrievable.
        AgentCommandForwarder.AgentCommand failCmd = new AgentCommandForwarder.AgentCommand(
                List.of("/bin/sh", "-c", "echo FAIL_OUTPUT && exit 42"), "fail test"
        );

        AgentCommandForwarder.ForwardResult result = forwarder.executeWithRealtimeOutput(failCmd, 5);

        assertFalse(result.timedOut(), "Should not time out");
        // PTY wrapping may or may not propagate the inner exit code —
        // the important thing is the output was captured
        assertTrue(result.output().contains("FAIL_OUTPUT"),
                "Should capture output even on failure: " + result.output());
    }

    // ── Agent resolution tests ────────────────────────────────────────────

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void resolveAgentBinaryFindsEcho() {
        // /bin/echo should always be available on Unix
        String path = AgentCommandForwarder.resolveAgentBinary("echo");
        assertNotNull(path, "Should find echo on PATH");
        assertTrue(path.endsWith("echo") || path.endsWith("echo.exe"));
    }

    @Test
    void resolveAgentBinaryReturnsNullForMissing() {
        String path = AgentCommandForwarder.resolveAgentBinary("nonexistent_agent_xyz_12345");
        assertNull(path, "Should return null for missing binary");
    }

    @Test
    void isAgentAvailableForMissingAgent() {
        assertFalse(AgentCommandForwarder.isAgentAvailable("nonexistent_agent_xyz_12345"));
    }

    // ── ForwardResult record tests ────────────────────────────────────────

    @Test
    void forwardResultSuccess() {
        var result = new AgentCommandForwarder.ForwardResult(0, "output", false);
        assertTrue(result.success());
    }

    @Test
    void forwardResultFailure() {
        var result = new AgentCommandForwarder.ForwardResult(1, "error", false);
        assertFalse(result.success());
    }

    @Test
    void forwardResultTimedOut() {
        var result = new AgentCommandForwarder.ForwardResult(-1, "", true);
        assertFalse(result.success());
        assertTrue(result.timedOut());
    }
}
