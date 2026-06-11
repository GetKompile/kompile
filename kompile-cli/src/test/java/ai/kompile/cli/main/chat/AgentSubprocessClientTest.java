package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.AgentSubprocessClient;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that AgentSubprocessClient streams output to stdout as it arrives
 * and handles subprocess lifecycle correctly.
 * <p>
 * The client uses per-message subprocesses: each streamChat call launches
 * a subprocess, pipes the message to stdin, and parses stream-json from stdout.
 * For non-stream-json agents (like /bin/cat), non-JSON lines are treated as
 * plain text via the parser's catch-all.
 */
class AgentSubprocessClientTest {

    /**
     * Verify streamChat pipes stdin to the subprocess and captures output.
     * Uses /bin/cat as a mock agent — echoes stdin to stdout.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldPrintOutputToStdout() throws Exception {
        TestableSubprocessClient client = new TestableSubprocessClient(
                "test-agent", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/cat");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            DirectLlmClient.StreamResult result = client.streamChat(
                    "hello from test", null, null, null, null);

            System.setOut(originalOut);

            // /bin/cat echoes stdin — the parser treats non-JSON as plain text
            assertTrue(result.text.contains("hello from test"),
                    "streamChat result should contain echoed message, got: " + result.text);
            assertTrue(captured.toString().contains("hello from test"),
                    "streamChat must print output to System.out, got: " + captured);

        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    /**
     * Verify that start() prints the agent-ready message.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void startShouldShowStartupOutput() throws Exception {
        TestableSubprocessClient client = new TestableSubprocessClient(
                "test-agent", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/cat");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));
            client.start();
            System.setOut(originalOut);

            String output = captured.toString();
            assertTrue(output.contains("Agent 'test-agent' ready at:"),
                    "start() should print the ready message, got: " + output);
        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    /**
     * Verify cancellation stops the stream.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldRespectCancelSignal() throws Exception {
        // Use a long-running command so there's something to cancel
        TestableSubprocessClient client = new TestableSubprocessClient(
                "test-agent", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/cat");

        AtomicBoolean cancel = new AtomicBoolean(false);
        client.setCancelSignal(cancel);

        try {
            // Set cancel before call — should exit the read loop immediately
            cancel.set(true);

            DirectLlmClient.StreamResult result = client.streamChat(
                    "this should be cancelled", null, null, null, null);

            assertTrue(result.cancelled, "Result should be marked as cancelled");
        } finally {
            client.close();
        }
    }

    /**
     * Verify that stream-json output from a Claude-like agent is parsed and
     * printed correctly. Uses a bash script that emits valid stream-json events.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldParseStreamJson() throws Exception {
        // Mock agent that reads stdin (so the pipe doesn't break), then outputs stream-json
        String script = "cat > /dev/null; " +
                "echo '{\"type\":\"system\",\"session_id\":\"test-session\"}'; " +
                "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello from mock agent\"}]}}'; " +
                "echo '{\"type\":\"result\",\"duration_ms\":1234,\"cost_usd\":0.005}'";

        TestableSubprocessClient client = new TestableSubprocessClient(
                "claude", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/bash", "-c", script);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            DirectLlmClient.StreamResult result = client.streamChat(
                    "test", null, null, null, null);

            System.setOut(originalOut);

            assertTrue(result.text.contains("Hello from mock agent"),
                    "Should parse assistant text from stream-json, got: " + result.text);
            assertTrue(captured.toString().contains("Hello from mock agent"),
                    "Should print assistant text to stdout, got: " + captured);

        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    /**
     * Codex CLI 0.136 emits assistant text as item.completed/agent_message
     * events, not message.delta/message.completed.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldParseCodexItemCompletedAgentMessage() throws Exception {
        String script = "cat > /dev/null; " +
                "echo '{\"type\":\"thread.started\",\"thread_id\":\"codex-session\"}'; " +
                "echo '{\"type\":\"turn.started\"}'; " +
                "echo '{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"Hello from codex\"}}'; " +
                "echo '{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":12,\"cached_input_tokens\":5,\"output_tokens\":7}}'";

        TestableSubprocessClient client = new TestableSubprocessClient(
                "codex", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/bash", "-c", script);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            DirectLlmClient.StreamResult result = client.streamChat(
                    "test", null, null, null, null);

            System.setOut(originalOut);

            assertTrue(result.text.contains("Hello from codex"),
                    "Should parse assistant text from Codex item.completed, got: " + result.text);
            assertTrue(captured.toString().contains("Hello from codex"),
                    "Should print Codex assistant text to stdout, got: " + captured);
            assertEquals(12, result.inputTokens);
            assertEquals(7, result.outputTokens);
            assertEquals(5, result.cacheReadTokens);

        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void attachmentAwareStreamChatShouldUseSubprocessOverride() throws Exception {
        String script = "cat > /dev/null; " +
                "echo '{\"type\":\"thread.started\",\"thread_id\":\"codex-session\"}'; " +
                "echo '{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"TTY_OK\"}}'; " +
                "echo '{\"type\":\"turn.completed\"}'";

        TestableSubprocessClient client = new TestableSubprocessClient(
                "codex", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/bash", "-c", script);

        try {
            DirectLlmClient.StreamResult result = client.streamChat(
                    "test", null, null, null, null, List.of());

            assertEquals("TTY_OK", result.text);
        } finally {
            client.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldPrintCodexCommandOutputWithoutAddingToAssistantText() throws Exception {
        String script = "cat > /dev/null; " +
                "echo '{\"type\":\"thread.started\",\"thread_id\":\"codex-session\"}'; " +
                "echo '{\"type\":\"turn.started\"}'; " +
                "echo '{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\",\"command\":\"mvn test\",\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}'; " +
                "echo '{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\",\"command\":\"mvn test\",\"aggregated_output\":\"BUILD SUCCESS\\\\n\",\"exit_code\":0,\"status\":\"completed\"}}'; " +
                "echo '{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"agent_message\",\"text\":\"Done\"}}'; " +
                "echo '{\"type\":\"turn.completed\"}'";

        TestableSubprocessClient client = new TestableSubprocessClient(
                "codex", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/bash", "-c", script);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            DirectLlmClient.StreamResult result = client.streamChat(
                    "test", null, null, null, null);

            System.setOut(originalOut);

            assertEquals("Done", result.text);
            assertTrue(captured.toString().contains("BUILD SUCCESS"),
                    "Should print Codex command output, got: " + captured);
            assertTrue(captured.toString().contains("[tool: exec exit 0]"),
                    "Should print Codex tool completion status, got: " + captured);
        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    @Test
    void codexParserShouldParseCommandExecutionItemStarted() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        PassthroughStreamParser.PassthroughEvent event = parser.parseCodexLine(
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\","
                        + "\"command\":\"/bin/bash -lc pwd\",\"status\":\"in_progress\"}}");

        assertTrue(event instanceof PassthroughStreamParser.ToolUse,
                "Expected command_execution item.started to become a tool-use event");
        PassthroughStreamParser.ToolUse toolUse = (PassthroughStreamParser.ToolUse) event;
        assertEquals("exec", toolUse.name());
        assertEquals("/bin/bash -lc pwd", toolUse.input());
    }

    @Test
    void codexParserShouldEmitCommandOutputDeltas() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\","
                + "\"type\":\"command_execution\",\"command\":\"cmd\",\"aggregated_output\":\"\"}}");

        PassthroughStreamParser.PassthroughEvent update = parser.parseCodexLine(
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\","
                        + "\"command\":\"cmd\",\"aggregated_output\":\"one\"}}");
        assertTrue(update instanceof PassthroughStreamParser.ToolOutput);
        assertEquals("one", ((PassthroughStreamParser.ToolOutput) update).output());

        PassthroughStreamParser.PassthroughEvent complete = parser.parseCodexLine(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"command_execution\","
                        + "\"command\":\"cmd\",\"aggregated_output\":\"onetwo\",\"exit_code\":0,\"status\":\"completed\"}}");
        assertTrue(complete instanceof PassthroughStreamParser.ToolComplete);
        PassthroughStreamParser.ToolComplete toolComplete = (PassthroughStreamParser.ToolComplete) complete;
        assertEquals("two", toolComplete.output());
        assertEquals(0, toolComplete.exitCode());
        assertFalse(toolComplete.error());
    }

    @Test
    void codexParserShouldSuppressStdinNotices() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        assertNull(parser.parseCodexLine("Reading prompt from stdin..."));
        assertNull(parser.parseCodexLine("Reading additional input from stdin..."));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void streamChatShouldPrintOpenCodeToolOutputWithoutAddingToAssistantText() throws Exception {
        String script = "cat > /dev/null; " +
                "echo '{\"type\":\"step_start\",\"sessionID\":\"opencode-session\"}'; " +
                "echo '{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"bash\",\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"echo ok\",\"description\":\"Run echo\"},\"output\":\"one\\ntwo\\n\",\"metadata\":{\"output\":\"one\\ntwo\\n\",\"exit\":0}}}}'; " +
                "echo '{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":\"Done\"}}'; " +
                "echo '{\"type\":\"step_finish\",\"part\":{\"tokens\":{\"input\":3,\"output\":2,\"cache\":{\"read\":1,\"write\":0}},\"cost\":0}}'";

        TestableSubprocessClient client = new TestableSubprocessClient(
                "opencode", System.getProperty("user.dir"), new ObjectMapper(),
                "/bin/bash", "-c", script);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(captured, true));

            DirectLlmClient.StreamResult result = client.streamChat(
                    "test", null, null, null, null);

            System.setOut(originalOut);

            assertEquals("Done", result.text);
            assertTrue(captured.toString().contains("one\ntwo"),
                    "Should print OpenCode tool output, got: " + captured);
            assertTrue(captured.toString().contains("[tool: bash exit 0]"),
                    "Should print OpenCode tool completion status, got: " + captured);
            assertEquals(3, result.inputTokens);
            assertEquals(2, result.outputTokens);
            assertEquals(1, result.cacheReadTokens);
        } finally {
            System.setOut(originalOut);
            client.close();
        }
    }

    @Test
    void openCodeParserShouldEmitCompletedToolAndNestedCacheUsage() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        List<PassthroughStreamParser.PassthroughEvent> toolEvents = parser.parseOpenCodeLineMulti(
                "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"bash\","
                        + "\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"echo ok\"},"
                        + "\"output\":\"ok\\n\",\"metadata\":{\"output\":\"ok\\n\",\"exit\":0}}}}");

        assertEquals(2, toolEvents.size());
        assertTrue(toolEvents.get(0) instanceof PassthroughStreamParser.ToolUse);
        assertTrue(toolEvents.get(1) instanceof PassthroughStreamParser.ToolComplete);
        PassthroughStreamParser.ToolComplete complete =
                (PassthroughStreamParser.ToolComplete) toolEvents.get(1);
        assertEquals("ok\n", complete.output());
        assertEquals(0, complete.exitCode());

        List<PassthroughStreamParser.PassthroughEvent> usageEvents = parser.parseOpenCodeLineMulti(
                "{\"type\":\"step_finish\",\"part\":{\"tokens\":{\"input\":10,\"output\":4,"
                        + "\"cache\":{\"read\":7,\"write\":2}},\"cost\":0}}");
        assertTrue(usageEvents.get(0) instanceof PassthroughStreamParser.TokenUsage);
        PassthroughStreamParser.TokenUsage usage =
                (PassthroughStreamParser.TokenUsage) usageEvents.get(0);
        assertEquals(10, usage.inputTokens());
        assertEquals(4, usage.outputTokens());
        assertEquals(7, usage.cacheReadTokens());
        assertEquals(2, usage.cacheCreationTokens());
    }

    @Test
    void piParserShouldStreamTextDeltasWithoutDuplicatingMessageEnd() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        PassthroughStreamParser.PassthroughEvent session = parser.parsePiLine(
                "{\"type\":\"session\",\"id\":\"pi-session\"}");
        assertTrue(session instanceof PassthroughStreamParser.SessionInit);
        assertEquals("pi-session", ((PassthroughStreamParser.SessionInit) session).sessionId());

        PassthroughStreamParser.PassthroughEvent first = parser.parsePiLine(
                "{\"type\":\"message_update\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\"},"
                        + "\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"PI_\"}}");
        PassthroughStreamParser.PassthroughEvent second = parser.parsePiLine(
                "{\"type\":\"message_update\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\"},"
                        + "\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"OK\"}}");
        PassthroughStreamParser.PassthroughEvent end = parser.parsePiLine(
                "{\"type\":\"message_end\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"PI_OK\"}]}}");

        assertEquals("PI_", ((PassthroughStreamParser.TextChunk) first).text());
        assertEquals("OK", ((PassthroughStreamParser.TextChunk) second).text());
        assertNull(end, "message_end should not duplicate text already emitted as deltas");
    }

    @Test
    void piParserShouldEmitToolOutputDeltas() {
        PassthroughStreamParser parser = new PassthroughStreamParser();

        PassthroughStreamParser.PassthroughEvent start = parser.parsePiLine(
                "{\"type\":\"tool_execution_start\",\"toolCallId\":\"call_1\","
                        + "\"toolName\":\"bash\",\"args\":{\"command\":\"echo ok\"}}");
        assertTrue(start instanceof PassthroughStreamParser.ToolUse);

        PassthroughStreamParser.PassthroughEvent update = parser.parsePiLine(
                "{\"type\":\"tool_execution_update\",\"toolCallId\":\"call_1\","
                        + "\"toolName\":\"bash\",\"args\":{\"command\":\"echo ok\"},"
                        + "\"partialResult\":{\"content\":[{\"type\":\"text\",\"text\":\"one\"}]}}");
        assertTrue(update instanceof PassthroughStreamParser.ToolOutput);
        assertEquals("one", ((PassthroughStreamParser.ToolOutput) update).output());

        PassthroughStreamParser.PassthroughEvent complete = parser.parsePiLine(
                "{\"type\":\"tool_execution_end\",\"toolCallId\":\"call_1\","
                        + "\"toolName\":\"bash\",\"args\":{\"command\":\"echo ok\"},"
                        + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"onetwo\"}],"
                        + "\"exitCode\":0},\"isError\":false}");
        assertTrue(complete instanceof PassthroughStreamParser.ToolComplete);
        PassthroughStreamParser.ToolComplete toolComplete =
                (PassthroughStreamParser.ToolComplete) complete;
        assertEquals("two", toolComplete.output());
        assertEquals(0, toolComplete.exitCode());
        assertFalse(toolComplete.error());
    }

    @Test
    void managedCommandShouldUseJsonModeForPiAndOpenCode() {
        CommandInspectingSubprocessClient pi = new CommandInspectingSubprocessClient(
                "pi", System.getProperty("user.dir"), new ObjectMapper(), "/usr/bin/pi");
        CommandInspectingSubprocessClient opencode = new CommandInspectingSubprocessClient(
                "opencode", System.getProperty("user.dir"), new ObjectMapper(), "/usr/bin/opencode");

        assertEquals(List.of("/usr/bin/pi", "--mode", "json", "--print"),
                pi.commandFor("hello"));
        assertEquals(List.of("/usr/bin/opencode", "run", "--format", "json"),
                opencode.commandFor("hello"));
    }

    /**
     * Testable subclass that overrides binary resolution and command building
     * for deterministic unit tests.
     */
    static class TestableSubprocessClient extends AgentSubprocessClient {
        private final String[] overrideCommand;

        TestableSubprocessClient(String agent, String workingDir, ObjectMapper objectMapper,
                                 String... command) {
            super(agent, workingDir, objectMapper, command[0]);
            this.overrideCommand = command;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        protected List<String> buildCommand(String message) {
            // Return the exact test command, bypassing agent-specific flag logic
            return Arrays.asList(overrideCommand);
        }
    }

    static class CommandInspectingSubprocessClient extends AgentSubprocessClient {
        CommandInspectingSubprocessClient(String agent, String workingDir, ObjectMapper objectMapper,
                                          String binaryPath) {
            super(agent, workingDir, objectMapper, binaryPath);
        }

        List<String> commandFor(String message) {
            return buildCommand(message);
        }
    }
}
