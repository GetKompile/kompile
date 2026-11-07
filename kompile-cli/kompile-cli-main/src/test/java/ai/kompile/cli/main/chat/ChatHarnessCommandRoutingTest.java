package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import ai.kompile.cli.main.chat.exec.WebChatInput;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ChatHarnessCommandRoutingTest {
    @TempDir Path project;

    @Test void commandOptionsPreserveActualPrincipalTimeoutAndWebIdentity() throws Exception {
        ChatCommand command = new ChatCommand();
        new CommandLine(command).parseArgs("--working-dir", project.toString(), "--session-id", "cli-session",
                "--agent", "coder", "--role", "restricted", "--timeout", "7", "--dangerously-skip-permissions");
        var input = ChatCommand.class.getDeclaredField("webInput"); input.setAccessible(true);
        input.set(command, new WebChatInput(1, "/subagents saved", "", "web-session"));
        var method = ChatCommand.class.getDeclaredMethod("webCommandOptions", ChatConfig.class, HeadlessAgentRunner.OutputMode.class);
        method.setAccessible(true);
        ChatConfig primary = new ChatConfig("custom", "test-key", "primary", "http://example.test");
        var options = (HeadlessAgentRunner.Options) method.invoke(command, primary, HeadlessAgentRunner.OutputMode.JSON);
        assertEquals("web-session", options.sessionId());
        assertEquals("coder", options.agentName());
        assertEquals("restricted", options.roleName());
        assertEquals(7000, options.timeoutMs());
        assertTrue(options.autoApproveTools());
        assertNotSame(primary, options.chatConfig());
        assertEquals("primary", primary.getModel());
    }

    @Test void savedChildrenAndMilestonesRemainHarnessCommandsOnANativeRoute() throws Exception {
        String session = UUID.randomUUID().toString();
        nativeRoute(session);
        String list = execute("/subagents saved", session, 0);
        assertTrue(list.contains("\"type\":\"command\""), list);
        assertTrue(list.contains("durable-subagents"), list);
        assertFalse(list.contains("\"type\":\"tool\""), list);
        String milestones = execute("/test-milestones", session, 0);
        assertTrue(milestones.contains("\"type\":\"command\""), milestones);
        assertTrue(milestones.contains("Scope:"), milestones);
    }

    @Test void nativeResumeAndExplicitWorkflowCannotBecomeVendorPromptText() throws Exception {
        String session = UUID.randomUUID().toString();
        nativeRoute(session);
        String resume = "/subagents resume {\"session_id\":\"subagent-missing\",\"prompt\":\"continue\"}";
        String denied = execute(resume, session, 2);
        assertTrue(denied.contains("\"type\":\"command\""), denied);
        assertFalse(denied.contains("\"type\":\"tool\""), denied);
        String workflow = execute("/subagents saved", session, 2, "--workflow", "not-started");
        assertTrue(workflow.contains("Durable child commands are unavailable in workflow runs"), workflow);
        assertTrue(workflow.contains("\"type\":\"command\""), workflow);
    }

    private void nativeRoute(String id) throws Exception {
        ChatConfig config = new ChatConfig(null, null, "unused-model", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("never-launch-this-vendor");
        config.setPassthroughManaged(true);
        config.bindSession(id);
    }

    private String execute(String raw, String session, int expected, String... extra) {
        var oldIn = System.in; var oldOut = System.out; var oldErr = System.err;
        var bytes = new ByteArrayOutputStream();
        var input = JsonUtils.standardMapper().createObjectNode().put("version", 1)
                .put("sessionId", session).put("rawInput", raw).toString() + "\n";
        var args = new ArrayList<>(List.of("--working-dir", project.toString(), "--input-format", "web-json", "--output-format", "stream-json", "--local"));
        args.addAll(List.of(extra)); args.add("-");
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(out); System.setErr(out);
            assertEquals(expected, new CommandLine(new ChatCommand()).execute(args.toArray(String[]::new)), bytes.toString(StandardCharsets.UTF_8));
            return bytes.toString(StandardCharsets.UTF_8);
        } finally { System.setIn(oldIn); System.setOut(oldOut); System.setErr(oldErr); }
    }
}
