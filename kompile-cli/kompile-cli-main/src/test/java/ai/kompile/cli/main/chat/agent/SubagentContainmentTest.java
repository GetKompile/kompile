package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.mcp.PlatformHooksInstaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
class SubagentContainmentTest {
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final Map<String, String> CHILD = Map.of(SubagentDelegationGuard.DEPTH_ENV, "1");

    @TempDir Path directory;

    static final class FakeProcess extends Process {
        final InputStream stdout;
        boolean killed;

        FakeProcess(String output) {
            this.stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { killed = true; }
        @Override public Process destroyForcibly() { killed = true; return this; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }

    /** A runner whose launches are captured instead of started. */
    final class Stub extends SubprocessAgentRunner {
        final FakeProcess process;
        List<String> command;
        Map<String, String> environment;

        Stub(String agent, String output, Map<String, String> extraEnvironment) {
            super(agent, directory.toString(), false, false, null, 0, null, null, null);
            process = new FakeProcess(output);
            setExtraEnvironment(extraEnvironment);
        }

        @Override protected String resolveHeadlessBinary() { return "agent-binary"; }

        @Override protected Process startHeadlessProcess(ProcessBuilder builder) {
            command = builder.command();
            environment = new HashMap<>(builder.environment());
            return process;
        }
    }

    @Test
    void everyRosterVendorIsRecognizedAndAnythingElseIsRefusedAsAChild() {
        assertEquals(SubagentContainment.Vendor.CLAUDE, SubagentContainment.vendor("claude-cli"));
        assertEquals(SubagentContainment.Vendor.CODEX, SubagentContainment.vendor("codex"));
        assertEquals(SubagentContainment.Vendor.GEMINI, SubagentContainment.vendor("gemini-cli"));
        assertEquals(SubagentContainment.Vendor.AGY, SubagentContainment.vendor("agy-cli"));
        assertEquals(SubagentContainment.Vendor.AGY, SubagentContainment.vendor("antigravity"));
        assertEquals(SubagentContainment.Vendor.OPENCODE, SubagentContainment.vendor("opencode"));
        assertEquals(SubagentContainment.Vendor.PI, SubagentContainment.vendor("pi-cli"));
        assertEquals(SubagentContainment.Vendor.QWEN, SubagentContainment.vendor("qwen"));
        for (String known : List.of("claude", "codex", "gemini", "agy", "opencode", "pi", "qwen")) {
            assertNull(SubagentContainment.refusal(known), known);
        }
        // DeepSeek Harness and unknown CLIs have no verified control over their own agents.
        for (String unknown : List.of("dsh", "kimi", "cursor-agent", "")) {
            String refusal = SubagentContainment.refusal(unknown);
            assertNotNull(refusal, unknown);
            assertTrue(refusal.startsWith("BLOCKED:"), refusal);
        }
    }

    @Test
    void codexChildTurnsItsMultiAgentFeaturesOffBeforeTheSubcommand() {
        Stub child = new Stub("codex", "", CHILD);
        assertEquals(0, child.runHeadless("review", 1000, event -> { }).exitCode());
        assertEquals("agent-binary", child.command.get(0));
        assertEquals(SubagentContainment.CODEX_ARGUMENTS, child.command.subList(1, 5));
        assertTrue(child.command.indexOf("exec") > 4, child.command.toString());

        Stub topLevel = new Stub("codex", "", Map.of());
        assertEquals(0, topLevel.runHeadless("review", 1000, event -> { }).exitCode());
        assertFalse(topLevel.command.contains("features.multi_agent=false"),
                "a top-level session keeps its own features: " + topLevel.command);
    }

    @Test
    void piChildDisablesExtensionDiscovery() throws Exception {
        assertEquals(List.of("--no-extensions"), SubagentContainment.launchArguments("pi"));
        assertEquals(List.of("--exclude-tools", "agent"), SubagentContainment.launchArguments("qwen"));
    }

    @Test
    void agyChildAddsAGuardWorkspaceWhoseHookDeniesEverySubagentTool() throws Exception {
        List<String> arguments = SubagentContainment.launchArguments("agy");
        assertEquals("--add-dir", arguments.get(0));
        Path agents = Path.of(arguments.get(1)).resolve(".agents");
        JsonNode hooks = MAPPER.readTree(Files.readString(agents.resolve("hooks.json")));
        JsonNode group = hooks.path(SubagentContainment.AGY_HOOK_NAME).path("PreToolUse").path(0);
        String matcher = group.path("matcher").asText();
        for (String tool : SubagentContainment.AGY_TOOLS) {
            assertTrue(tool.matches(matcher), tool + " must match " + matcher);
        }
        assertFalse("run_command".matches(matcher), matcher);
        assertFalse("view_file".matches(matcher), matcher);
        String command = group.path("hooks").path(0).path("command").asText();
        assertTrue(command.endsWith("deny.json"), command);
        JsonNode decision = MAPPER.readTree(Files.readString(agents.resolve("deny.json")));
        assertEquals("deny", decision.path("decision").asText());
        // Rewritten only when its bytes change, so concurrent launches share one copy.
        assertEquals(arguments, SubagentContainment.launchArguments("agy"));
    }

    @Test
    void nativeDelegationToolsAreMatchedExactlyPerVendor() {
        assertNotNull(SubagentContainment.violation("claude", "Task"));
        assertNotNull(SubagentContainment.violation("claude", "Agent"));
        assertNotNull(SubagentContainment.violation("codex", "spawn_agent"));
        assertNotNull(SubagentContainment.violation("opencode", "task"));
        assertNotNull(SubagentContainment.violation("agy", "invoke_subagent"));
        assertNotNull(SubagentContainment.violation("qwen", "agent"));
        // Kompile's own tools reach its MCP server, which refuses delegation itself.
        assertNull(SubagentContainment.violation("claude", "mcp__kompile__task"));
        assertNull(SubagentContainment.violation("opencode", "kompile_task"));
        assertNull(SubagentContainment.violation("claude", "Read"));
        assertNull(SubagentContainment.violation("gemini", "run_shell_command"));

        assertNotNull(SubagentContainment.violationInLine("agy",
                "{\"event\":\"step_update\",\"step_update\":{\"subagent_info\":{\"conversation_id\":\"c\"}}}"));
        assertNull(SubagentContainment.violationInLine("agy",
                "{\"event\":\"step_update\",\"step_update\":{\"text_delta\":\"the \\\"subagent_info\\\" key\"}}"));
        assertNull(SubagentContainment.violationInLine("claude", "{\"subagent_info\":{}}"));
    }

    @Test
    void aChildThatCallsItsNativeSubagentToolIsStopped() {
        String agentCall = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"toolu_1\",\"name\":\"Agent\",\"input\":{\"prompt\":\"explore\"}}]}}\n";
        Stub child = new Stub("claude", agentCall, CHILD);
        List<PassthroughStreamParser.PassthroughEvent> events = new ArrayList<>();
        SubprocessAgentRunner.NativeResult result = child.runHeadless("hi", 1000, events::add);
        assertNotEquals(0, result.exitCode());
        assertNotNull(child.getContainmentNotice());
        assertTrue(result.error().contains("'Agent'"), result.error());
        assertTrue(events.stream().noneMatch(e -> e instanceof PassthroughStreamParser.ToolUse),
                "the call is not reported as ordinary work");

        Stub topLevel = new Stub("claude", agentCall, Map.of());
        assertEquals(0, topLevel.runHeadless("hi", 1000, event -> { }).exitCode());
        assertNull(topLevel.getContainmentNotice());
    }

    @Test
    void anUncontainableChildIsNeverStarted() {
        Stub child = new Stub("cursor-agent", "", CHILD);
        SubprocessAgentRunner.NativeResult result = child.runHeadless("hi", 1000, event -> { });
        assertNotEquals(0, result.exitCode());
        assertNull(child.command, "no process may start for a vendor Kompile cannot contain");
    }

    @Test
    void openCodeChildDeniesTaskAfterEveryOtherPermissionRule() throws Exception {
        Map<String, String> environment = new HashMap<>(CHILD);
        environment.put("OPENCODE_CONFIG_CONTENT", "{\"permission\":{\"task\":\"allow\",\"*\":\"allow\"}}");
        SubagentContainment.applyLaunchEnvironment("opencode", environment, directory);
        JsonNode permission = MAPPER.readTree(environment.get("OPENCODE_CONFIG_CONTENT")).path("permission");
        List<String> keys = new ArrayList<>();
        permission.fieldNames().forEachRemaining(keys::add);
        assertEquals("task", keys.get(keys.size() - 1), "OpenCode's last matching rule wins: " + keys);
        assertEquals("deny", permission.path("task").asText());
        String plugin = Files.readString(directory.resolve(".opencode").resolve("plugins").resolve("kompile-hooks.ts"));
        assertEquals(PlatformHooksInstaller.openCodePluginText(), plugin);
        assertTrue(plugin.contains("KOMPILE_SUBAGENT_DEPTH"), "the plugin backs the rule in any child");
    }

    @Test
    void geminiChildReadsSystemSettingsWithItsSubagentsOff() throws Exception {
        Path systemSettings = Files.writeString(directory.resolve("system-settings.json"),
                "{\"ui\":{\"theme\":\"dark\"},\"experimental\":{\"enableAgents\":true}}");
        Map<String, String> environment = new HashMap<>(CHILD);
        environment.put("GEMINI_CLI_SYSTEM_SETTINGS_PATH", systemSettings.toString());
        SubagentContainment.applyLaunchEnvironment("gemini", environment, directory);
        Path launch = Path.of(environment.get("GEMINI_CLI_SYSTEM_SETTINGS_PATH"));
        assertNotEquals(systemSettings, launch, "the shared system settings are left alone");
        JsonNode settings = MAPPER.readTree(Files.readString(launch));
        assertFalse(settings.path("experimental").path("enableAgents").asBoolean(true));
        assertEquals("dark", settings.path("ui").path("theme").asText(), "the system settings are kept");
        assertEquals(directory.resolve("system-defaults.json").toString(),
                environment.get("GEMINI_CLI_SYSTEM_DEFAULTS_PATH"));
        assertTrue(Files.readString(systemSettings).contains("\"enableAgents\":true"));

        // A launch whose system settings are already its own launch config is changed in place.
        Map<String, String> again = new HashMap<>(CHILD);
        again.put("GEMINI_CLI_SYSTEM_SETTINGS_PATH", launch.toString());
        McpToolInjection.disableGeminiSubagents(again);
        assertEquals(launch.toString(), again.get("GEMINI_CLI_SYSTEM_SETTINGS_PATH"));
    }

    @Test
    void topLevelLaunchesAreUntouched() throws Exception {
        assertFalse(SubagentContainment.isChild(Map.of()));
        assertFalse(SubagentContainment.isChild(Map.of(SubagentDelegationGuard.DEPTH_ENV, "0")));
        assertTrue(SubagentContainment.isChild(CHILD));
        assertEquals(Set.of(), SubagentContainment.nativeDelegationTools("gemini"),
                "Gemini subagents are named after the agent; the settings switch is the control");
    }
}
