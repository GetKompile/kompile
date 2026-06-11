package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.testing.MockAgentScript;
import ai.kompile.cli.main.chat.testing.SubprocessTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for subprocess-based agent interactions.
 * <p>
 * Each test uses {@link MockAgentScript} to build a bash script that mimics
 * a real LLM CLI's output format, then runs it through the full
 * {@link ai.kompile.cli.main.chat.config.AgentSubprocessClient} pipeline
 * via {@link SubprocessTestHarness} — exercising stdin piping, stdout
 * parsing, event dispatch, and result aggregation.
 */
@DisabledOnOs(OS.WINDOWS)
class SubprocessIntegrationTest {

    // ========================================================================
    // Per-agent format: basic text response
    // ========================================================================

    @Test
    void claudeBasicTextResponse() throws Exception {
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("claude-session-1")
                .textResponse("Hello from Claude")
                .turnComplete(150, 42)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("hi");
            harness.assertResultContains("Hello from Claude")
                    .assertOutputContains("Hello from Claude");
        }
    }

    @Test
    void codexBasicTextResponse() throws Exception {
        MockAgentScript script = MockAgentScript.codex()
                .sessionInit("codex-thread-1")
                .textResponse("Hello from Codex")
                .turnComplete(200, 30)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("codex")
                .script(script).build()) {
            harness.chat("hi");
            harness.assertResultContains("Hello from Codex")
                    .assertOutputContains("Hello from Codex");
        }
    }

    @Test
    void opencodeBasicTextResponse() throws Exception {
        MockAgentScript script = MockAgentScript.opencode()
                .sessionInit("oc-session-1")
                .textResponse("Hello from OpenCode")
                .turnComplete(80, 20)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("opencode")
                .script(script).build()) {
            harness.chat("hi");
            harness.assertResultContains("Hello from OpenCode")
                    .assertOutputContains("Hello from OpenCode");
        }
    }

    @Test
    void piBasicTextResponse() throws Exception {
        MockAgentScript script = MockAgentScript.pi()
                .sessionInit("pi-session-1")
                .textResponse("Hello from Pi")
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("pi")
                .script(script).build()) {
            harness.chat("hi");
            harness.assertResultContains("Hello from Pi")
                    .assertOutputContains("Hello from Pi");
        }
    }

    @Test
    void geminiBasicTextResponse() throws Exception {
        MockAgentScript script = MockAgentScript.gemini()
                .sessionInit("gemini-session-1")
                .textResponse("Hello from Gemini")
                .turnComplete(100, 25)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("gemini")
                .script(script).build()) {
            harness.chat("hi");
            harness.assertResultContains("Hello from Gemini")
                    .assertOutputContains("Hello from Gemini");
        }
    }

    // ========================================================================
    // Claude: multi-event sequence with tool use
    // ========================================================================

    @Test
    void claudeToolUseSequence() throws Exception {
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("session-tools")
                .toolUse("Read", "/tmp/file.txt")
                .textResponse("The file contains hello world")
                .turnComplete(300, 100)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("read /tmp/file.txt");
            harness.assertResultContains("The file contains hello world")
                    .assertToolCallRendered("Read");
        }
    }

    // ========================================================================
    // Codex: command execution with tool output
    // ========================================================================

    @Test
    void codexCommandExecutionSequence() throws Exception {
        MockAgentScript script = MockAgentScript.codex()
                .sessionInit("codex-tools")
                .toolUse("exec", "mvn test")
                .toolComplete("mvn test", "BUILD SUCCESS\n", 0)
                .textResponse("All tests passed")
                .turnComplete(250, 60)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("codex")
                .script(script).build()) {
            harness.chat("run tests");
            harness.assertResultEquals("All tests passed")
                    .assertOutputContains("BUILD SUCCESS")
                    .assertToolCallRendered("exec");
        }
    }

    // ========================================================================
    // OpenCode: tool use with completion embedded
    // ========================================================================

    @Test
    void opencodeToolUseWithCompletionEmbedded() throws Exception {
        // OpenCode emits tool_use with status=completed in a single line
        String toolUseLine = "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"ls -la\"},"
                + "\"output\":\"total 42\\ndrwxr-xr-x\\n\","
                + "\"metadata\":{\"output\":\"total 42\\ndrwxr-xr-x\\n\",\"exit\":0}}}}";

        MockAgentScript script = MockAgentScript.raw()
                .rawLine("{\"type\":\"step_start\",\"sessionID\":\"oc-tools\"}")
                .rawLine(toolUseLine)
                .rawLine("{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":\"Listed files\"}}")
                .rawLine("{\"type\":\"step_finish\",\"part\":{\"tokens\":{\"input\":50,\"output\":15,"
                        + "\"cache\":{\"read\":10,\"write\":3}},\"cost\":0}}")
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("opencode")
                .script(script).build()) {
            harness.chat("list files");
            DirectLlmClient.StreamResult result = harness.getLastResult();
            assertEquals("Listed files", result.text);
            assertEquals(50, result.inputTokens);
            assertEquals(15, result.outputTokens);
            assertEquals(10, result.cacheReadTokens);

            harness.assertOutputContains("total 42")
                    .assertOutputContains("[tool: bash exit 0]");
        }
    }

    // ========================================================================
    // Token usage accumulation
    // ========================================================================

    @Test
    void codexTokenUsageFromTurnCompleted() throws Exception {
        MockAgentScript script = MockAgentScript.codex()
                .sessionInit("codex-tokens")
                .textResponse("response")
                .turnComplete(1500, 300)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("codex")
                .script(script).build()) {
            harness.chat("test");
            harness.assertTokenUsage(1500, 300);
        }
    }

    @Test
    void opencodeTokenUsageWithCacheFields() throws Exception {
        MockAgentScript script = MockAgentScript.raw()
                .rawLine("{\"type\":\"step_start\",\"sessionID\":\"oc-cache\"}")
                .rawLine("{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":\"ok\"}}")
                .rawLine("{\"type\":\"step_finish\",\"part\":{\"tokens\":{\"input\":500,\"output\":120,"
                        + "\"cache\":{\"read\":200,\"write\":50}},\"cost\":0}}")
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("opencode")
                .script(script).build()) {
            harness.chat("test");
            harness.assertTokenUsage(500, 120)
                    .assertCacheTokens(200, 50);
        }
    }

    // ========================================================================
    // Cancellation
    // ========================================================================

    @Test
    void cancelMidStreamStopsProcessing() throws Exception {
        // Script with a long sleep — cancellation should cut it short
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("cancel-test")
                .textResponse("first chunk")
                .sleepMs(5000)
                .textResponse("should not appear")
                .turnComplete(0, 0)
                .build();

        AtomicBoolean cancel = new AtomicBoolean(true); // pre-set cancel

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).cancelSignal(cancel).build()) {
            harness.chat("test");
            harness.assertCancelled();
        }
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    @Test
    void nonZeroExitCodeStillCapturesOutput() throws Exception {
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("error-test")
                .textResponse("partial output before crash")
                .exitCode(1)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("test");
            harness.assertResultContains("partial output before crash");
        }
    }

    // ========================================================================
    // Multi-text: multiple text chunks concatenate
    // ========================================================================

    @Test
    void multipleTextChunksConcatenate() throws Exception {
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("multi-text")
                .textResponse("Hello ")
                .textResponse("world")
                .turnComplete(50, 10)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("test");
            harness.assertResultContains("Hello ")
                    .assertResultContains("world");
        }
    }

    // ========================================================================
    // Pi: tool execution lifecycle
    // ========================================================================

    @Test
    void piToolExecutionLifecycle() throws Exception {
        MockAgentScript script = MockAgentScript.pi()
                .sessionInit("pi-tools")
                .toolUse("bash", "echo hello")
                .toolComplete("bash", "hello\n", 0)
                .textResponse("Command output is hello")
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("pi")
                .script(script).build()) {
            harness.chat("run echo hello");
            harness.assertResultContains("Command output is hello")
                    .assertToolCallRendered("bash")
                    .assertOutputContains("[tool: bash exit 0]");
        }
    }

    // ========================================================================
    // Gemini: tool use event
    // ========================================================================

    @Test
    void geminiToolUseRendered() throws Exception {
        MockAgentScript script = MockAgentScript.gemini()
                .sessionInit("gemini-tools")
                .toolUse("search", "query text")
                .textResponse("Found 3 results")
                .turnComplete(80, 15)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("gemini")
                .script(script).build()) {
            harness.chat("search for something");
            harness.assertResultContains("Found 3 results")
                    .assertToolCallRendered("search");
        }
    }

    // ========================================================================
    // Large output: verify no truncation in normal path
    // ========================================================================

    @Test
    void largeOutputIsFullyCaptured() throws Exception {
        // Generate a ~10KB text response
        StringBuilder bigText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            bigText.append("Line ").append(i).append(": This is a test line with some content. ");
        }
        String expectedText = bigText.toString();

        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("large-output")
                .textResponse(expectedText)
                .turnComplete(5000, 2000)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("generate a lot of text");
            harness.assertResultContains("Line 0:")
                    .assertResultContains("Line 199:");
        }
    }

    // ========================================================================
    // Codex: turn.started event is silently handled
    // ========================================================================

    @Test
    void codexTurnStartedDoesNotProduceOutput() throws Exception {
        // Codex emits turn.started before actual content — should not appear in result
        MockAgentScript script = MockAgentScript.raw()
                .rawLine("{\"type\":\"thread.started\",\"thread_id\":\"codex-s\"}")
                .rawLine("{\"type\":\"turn.started\"}")
                .rawLine("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"Only this\"}}")
                .rawLine("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}")
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("codex")
                .script(script).build()) {
            harness.chat("test");
            harness.assertResultEquals("Only this")
                    .assertTokenUsage(10, 5);
        }
    }

    // ========================================================================
    // Empty message: agent still runs
    // ========================================================================

    @Test
    void emptyMessageStillRunsSubprocess() throws Exception {
        MockAgentScript script = MockAgentScript.claude()
                .sessionInit("empty-msg")
                .textResponse("got empty")
                .turnComplete(10, 5)
                .build();

        try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
                .script(script).build()) {
            harness.chat("");
            harness.assertResultContains("got empty");
        }
    }
}
