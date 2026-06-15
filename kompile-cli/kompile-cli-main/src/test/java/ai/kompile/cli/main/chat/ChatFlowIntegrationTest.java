package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.PassthroughStreamParser.*;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.StreamingMarkdownRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.AgentOutputAssertions;
import ai.kompile.cli.main.chat.testing.MockAgentScript;
import ai.kompile.cli.main.chat.testing.ParserTestFixtures;
import ai.kompile.cli.main.chat.testing.SubprocessTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end flow tests for the kompile CLI chat system.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>Slash command dispatch to underlying agents (via AgentCommandForwarder)</li>
 *   <li>Prompt → response flows across all agent formats</li>
 *   <li>Multi-tool sequences and interleaved text+tool content</li>
 *   <li>Streaming markdown rendering integration with parsed events</li>
 *   <li>Cross-agent consistency for the same logical flow</li>
 *   <li>Session lifecycle: init → content → turn complete</li>
 *   <li>Error and edge-case handling</li>
 * </ul>
 */
class ChatFlowIntegrationTest {

    // ========================================================================
    // Slash Command Dispatch
    // ========================================================================

    @Nested
    class SlashCommandDispatch {

        private AgentCommandForwarder forwarder;

        @BeforeEach
        void setUp() {
            forwarder = new AgentCommandForwarder();
        }

        /**
         * /model maps correctly for every supported agent.
         */
        @Test
        void modelCommandDispatchesForAllAgents() {
            var claude = forwarder.mapSlashCommand("/model", "/usr/bin/claude", "claude");
            assertNotNull(claude);
            assertEquals(List.of("/usr/bin/claude", "model", "list"), claude.command());

            var codex = forwarder.mapSlashCommand("/model", "/usr/bin/codex", "codex");
            assertNotNull(codex);
            assertEquals(List.of("/usr/bin/codex", "doctor"), codex.command());

            var opencode = forwarder.mapSlashCommand("/model", "/usr/bin/opencode", "opencode");
            assertNotNull(opencode);
            assertEquals(List.of("/usr/bin/opencode", "models"), opencode.command());

            var gemini = forwarder.mapSlashCommand("/model", "/usr/bin/gemini", "gemini");
            assertNotNull(gemini);

            var qwen = forwarder.mapSlashCommand("/model", "/usr/bin/qwen", "qwen");
            assertNotNull(qwen);

            var pi = forwarder.mapSlashCommand("/model", "/usr/bin/pi", "pi");
            assertNotNull(pi);
            assertEquals(List.of("/usr/bin/pi", "--list-models"), pi.command());
        }

        /**
         * /model with an argument triggers set/filter logic per agent.
         */
        @Test
        void modelWithArgTriggersSetForClaude() {
            var cmd = forwarder.mapSlashCommand("/model opus", "/usr/bin/claude", "claude");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/claude", "model", "set", "opus"), cmd.command());
        }

        @Test
        void modelWithArgTriggersFilterForPi() {
            var cmd = forwarder.mapSlashCommand("/model sonnet", "/usr/bin/pi", "pi");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/pi", "--list-models", "sonnet"), cmd.command());
        }

        @Test
        void modelWithArgTriggersFilterForOpencode() {
            var cmd = forwarder.mapSlashCommand("/model anthropic", "/usr/bin/opencode", "opencode");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/opencode", "models", "anthropic"), cmd.command());
        }

        /**
         * /config dispatches differently per agent.
         */
        @Test
        void configCommandDispatchesPerAgent() {
            var claude = forwarder.mapSlashCommand("/config", "/usr/bin/claude", "claude");
            assertNotNull(claude);
            assertEquals(List.of("/usr/bin/claude", "config", "list"), claude.command());

            var opencode = forwarder.mapSlashCommand("/config", "/usr/bin/opencode", "opencode");
            assertNotNull(opencode);
            assertEquals(List.of("/usr/bin/opencode", "providers"), opencode.command());

            var pi = forwarder.mapSlashCommand("/config", "/usr/bin/pi", "pi");
            assertNotNull(pi);
            assertEquals(List.of("/usr/bin/pi", "config"), pi.command());

            // Codex has no /config equivalent
            var codex = forwarder.mapSlashCommand("/config", "/usr/bin/codex", "codex");
            assertNull(codex, "Codex should return null for /config");
        }

        /**
         * /config set key value passes through to agent subcommand.
         */
        @Test
        void configSetPassesThroughForClaude() {
            var cmd = forwarder.mapSlashCommand("/config set theme dark", "/usr/bin/claude", "claude");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/claude", "config", "set", "theme", "dark"), cmd.command());
        }

        /**
         * /help maps to --help for all agents.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini", "qwen", "pi"})
        void helpCommandMapsForAllAgents(String agent) {
            var cmd = forwarder.mapSlashCommand("/help", "/usr/bin/" + agent, agent);
            assertNotNull(cmd, "/help should map for " + agent);
            assertTrue(cmd.command().contains("--help"),
                    "Should contain --help flag for " + agent);
        }

        /**
         * /help <subcommand> maps to <subcommand> --help for Claude.
         */
        @Test
        void helpWithSubcommandForClaude() {
            var cmd = forwarder.mapSlashCommand("/help model", "/usr/bin/claude", "claude");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/claude", "model", "--help"), cmd.command());
        }

        /**
         * /version maps to --version for all agents.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini", "qwen", "pi"})
        void versionCommandMapsForAllAgents(String agent) {
            var cmd = forwarder.mapSlashCommand("/version", "/usr/bin/" + agent, agent);
            assertNotNull(cmd, "/version should map for " + agent);
            assertTrue(cmd.command().contains("--version"),
                    "Should contain --version flag for " + agent);
        }

        /**
         * /doctor is Claude-only — returns null for other agents.
         */
        @Test
        void doctorOnlyForClaude() {
            var claude = forwarder.mapSlashCommand("/doctor", "/usr/bin/claude", "claude");
            assertNotNull(claude);
            assertEquals(List.of("/usr/bin/claude", "doctor"), claude.command());

            for (String agent : List.of("codex", "opencode", "gemini", "qwen", "pi")) {
                var cmd = forwarder.mapSlashCommand("/doctor", "/usr/bin/" + agent, agent);
                assertNull(cmd, "/doctor should return null for " + agent);
            }
        }

        /**
         * Unrecognized slash commands become agent subcommands.
         */
        @Test
        void unknownSlashCommandBecomesSubcommand() {
            var cmd = forwarder.mapSlashCommand("/mcp list", "/usr/bin/claude", "claude");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/claude", "mcp", "list"), cmd.command());
        }

        @Test
        void singleWordUnknownCommand() {
            var cmd = forwarder.mapSlashCommand("/status", "/usr/bin/claude", "claude");
            assertNotNull(cmd);
            assertEquals(List.of("/usr/bin/claude", "status"), cmd.command());
        }

        /**
         * Agent name normalization handles variants.
         */
        @Test
        void agentNameNormalization() {
            assertEquals("claude", AgentCommandForwarder.normalizeAgentKey("claude-cli"));
            assertEquals("claude", AgentCommandForwarder.normalizeAgentKey("Claude-Code"));
            assertEquals("codex", AgentCommandForwarder.normalizeAgentKey("Codex-CLI"));
            assertEquals("opencode", AgentCommandForwarder.normalizeAgentKey("open-code"));
            assertEquals("pi", AgentCommandForwarder.normalizeAgentKey("pi-coding-agent"));
            assertEquals("unknown", AgentCommandForwarder.normalizeAgentKey(null));
        }

        /**
         * Forwardable commands list includes the right commands per agent.
         */
        @Test
        void forwardableCommandsListVariesByAgent() {
            List<String> claudeCmds = forwarder.listForwardableCommands("claude");
            assertTrue(claudeCmds.contains("/model"));
            assertTrue(claudeCmds.contains("/config"));
            assertTrue(claudeCmds.contains("/doctor"));
            assertTrue(claudeCmds.contains("/help"));
            assertTrue(claudeCmds.contains("/version"));

            List<String> codexCmds = forwarder.listForwardableCommands("codex");
            assertTrue(codexCmds.contains("/model"));
            assertFalse(codexCmds.contains("/doctor"));

            List<String> piCmds = forwarder.listForwardableCommands("pi");
            assertTrue(piCmds.contains("/model"));
            assertTrue(piCmds.contains("/config"));
            assertFalse(piCmds.contains("/doctor"));
        }
    }

    // ========================================================================
    // Slash Command Execution
    // ========================================================================

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class SlashCommandExecution {

        private AgentCommandForwarder forwarder;

        @BeforeEach
        void setUp() {
            forwarder = new AgentCommandForwarder();
        }

        @Test
        void successfulCommandExecution() {
            var cmd = new AgentCommandForwarder.AgentCommand(
                    List.of("/bin/echo", "model_list_output"), "model list");
            var result = forwarder.executeWithRealtimeOutput(cmd, 5);

            assertEquals(0, result.exitCode());
            assertFalse(result.timedOut());
            assertTrue(result.success());
            assertTrue(result.output().contains("model_list_output"));
        }

        @Test
        void timedOutCommandReportsTimeout() {
            var cmd = new AgentCommandForwarder.AgentCommand(
                    List.of("/bin/sleep", "60"), "timeout test");
            var result = forwarder.executeWithRealtimeOutput(cmd, 1);

            assertTrue(result.timedOut());
            assertFalse(result.success());
        }

        @Test
        void nonZeroExitCapturesOutput() {
            var cmd = new AgentCommandForwarder.AgentCommand(
                    List.of("/bin/sh", "-c", "echo ERROR_OUTPUT && exit 1"), "fail test");
            var result = forwarder.executeWithRealtimeOutput(cmd, 5);

            assertFalse(result.timedOut());
            assertTrue(result.output().contains("ERROR_OUTPUT"));
        }
    }

    // ========================================================================
    // Cross-Agent Prompt → Response Flows (Parser Layer)
    // ========================================================================

    @Nested
    class CrossAgentParserFlows {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Session init → text response → turn complete: the minimal happy path
         * tested across all agent formats.
         */
        @Test
        void claudeMinimalHappyPath() {
            List<PassthroughEvent> all = new ArrayList<>();
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeSystemEvent("s1")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeTextEvent("Hello!")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeResultEvent(100, 0.001)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(3)
                    .firstEventIs(SessionInit.class)
                    .hasSessionInit("s1")
                    .hasTextChunk("Hello!")
                    .hasTurnComplete();
        }

        @Test
        void codexMinimalHappyPath() {
            List<PassthroughEvent> all = new ArrayList<>();
            add(all, parser.parseCodexLine(ParserTestFixtures.codexThreadStarted("t1")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexAgentMessage("item_0", "Hi!")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexTurnCompleted(100, 50, 20)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(3)
                    .firstEventIs(SessionInit.class)
                    .hasSessionInit("t1")
                    .hasTextChunk("Hi!")
                    .hasTurnComplete();
        }

        @Test
        void geminiMinimalHappyPath() {
            List<PassthroughEvent> all = new ArrayList<>();
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiInit("g1")));
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiMessage("Greetings!")));
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiResult(200, 0)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(3)
                    .firstEventIs(SessionInit.class)
                    .hasSessionInit("g1")
                    .hasTextChunk("Greetings!")
                    .hasTurnComplete();
        }

        @Test
        void opencodeMinimalHappyPath() {
            List<PassthroughEvent> all = new ArrayList<>();
            all.addAll(parser.parseOpenCodeLineMulti(ParserTestFixtures.openCodeStepStart("oc1")));
            all.addAll(parser.parseOpenCodeLineMulti(ParserTestFixtures.openCodeText("Hey!")));
            all.addAll(parser.parseOpenCodeLineMulti(
                    ParserTestFixtures.openCodeStepFinish(100, 50, 30, 10)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(3)
                    .firstEventIs(SessionInit.class)
                    .hasSessionInit("oc1")
                    .hasTextChunk("Hey!")
                    .lastEventIs(TokenUsage.class);
        }

        @Test
        void piMinimalHappyPath() {
            List<PassthroughEvent> all = new ArrayList<>();
            all.addAll(parser.parsePiLineMulti(ParserTestFixtures.piSession("p1")));
            all.addAll(parser.parsePiLineMulti(ParserTestFixtures.piTextDelta("m1", "Howdy!")));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(2)
                    .firstEventIs(SessionInit.class)
                    .hasSessionInit("p1")
                    .hasTextChunk("Howdy!");
        }

        /**
         * Helper to add a non-null event to a list.
         */
        private void add(List<PassthroughEvent> list, PassthroughEvent event) {
            if (event != null) list.add(event);
        }
    }

    // ========================================================================
    // Multi-Tool Sequences (Parser Layer)
    // ========================================================================

    @Nested
    class MultiToolSequences {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Claude: text → read → text → edit → text — verify tool ordering.
         */
        @Test
        void claudeMultiToolSequence() {
            List<PassthroughEvent> all = new ArrayList<>();
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeSystemEvent("s1")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeTextEvent("Let me read the file.")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeToolUseEvent("Read", "{\"path\":\"src/Foo.java\"}")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeTextEvent("Now I'll edit it.")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeToolUseEvent("Edit", "{\"path\":\"src/Foo.java\",\"old\":\"x\",\"new\":\"y\"}")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeTextEvent("Done!")));
            all.addAll(parser.parseClaudeLineMulti(ParserTestFixtures.claudeResultEvent(500, 0.01)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(7)
                    .toolSequenceIs("Read", "Edit")
                    .hasTextContaining("Let me read")
                    .hasTextContaining("Now I'll edit")
                    .hasTextChunk("Done!")
                    .hasTurnComplete();
        }

        /**
         * Claude: mixed text + tool_use in a single assistant message,
         * followed by another separate text message.
         */
        @Test
        void claudeMixedContentBlock() {
            String mixedLine = "{\"type\":\"assistant\",\"message\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"Reading file...\"},"
                    + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"path\":\"/tmp/f.java\"}}"
                    + "]}}";

            List<PassthroughEvent> events = parser.parseClaudeLineMulti(mixedLine);

            AgentOutputAssertions.assertThat(events)
                    .hasEventCount(2)
                    .firstEventIs(TextChunk.class)
                    .lastEventIs(ToolUse.class)
                    .hasTextChunk("Reading file...")
                    .hasToolUse("Read");
        }

        /**
         * Codex: command start → progress update → completion → text response.
         */
        @Test
        void codexCommandLifecycle() {
            List<PassthroughEvent> all = new ArrayList<>();

            add(all, parser.parseCodexLine(ParserTestFixtures.codexThreadStarted("t1")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexItemStarted("item_0", "mvn test")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexItemUpdated("item_0", "Running...")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexItemCompletedCommand("item_0", "Running...BUILD SUCCESS", 0)));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexAgentMessage("item_1", "Tests passed!")));
            add(all, parser.parseCodexLine(ParserTestFixtures.codexTurnCompleted(200, 80, 50)));

            AgentOutputAssertions.assertThat(all)
                    .isNotEmpty()
                    .hasSessionInit("t1")
                    .hasToolUse("exec")
                    .hasTextChunk("Tests passed!")
                    .hasTurnComplete();
        }

        /**
         * OpenCode: completed tool_use yields both ToolUse + ToolComplete events.
         */
        @Test
        void opencodeCompletedToolProducesTwoEvents() {
            List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(
                    ParserTestFixtures.openCodeToolUse("bash", "echo hello", "hello\n", 0));

            AgentOutputAssertions.assertThat(events)
                    .hasEventCount(2)
                    .firstEventIs(ToolUse.class)
                    .lastEventIs(ToolComplete.class)
                    .hasToolUse("bash")
                    .hasToolCompleteWithNoError("bash");
        }

        /**
         * Pi: tool start → update → end lifecycle.
         */
        @Test
        void piToolLifecycle() {
            List<PassthroughEvent> all = new ArrayList<>();
            all.addAll(parser.parsePiLineMulti(ParserTestFixtures.piToolStart("c1", "bash", "{\"command\":\"ls\"}")));
            all.addAll(parser.parsePiLineMulti(ParserTestFixtures.piToolUpdate("c1", "file1.txt")));
            all.addAll(parser.parsePiLineMulti(ParserTestFixtures.piToolEnd("c1", "bash", "file1.txt\nfile2.txt", 0)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(3)
                    .firstEventIs(ToolUse.class)
                    .hasToolUse("bash")
                    .hasToolOutput("file1.txt");

            // The ToolComplete delta should be the new part only ("file2.txt" — the diff from update)
            ToolComplete complete = AgentOutputAssertions.assertThat(all).firstOfType(ToolComplete.class);
            assertNotNull(complete);
            assertEquals(0, complete.exitCode());
            assertFalse(complete.error());
        }

        /**
         * Gemini: tool use → text response.
         */
        @Test
        void geminiToolFollowedByText() {
            List<PassthroughEvent> all = new ArrayList<>();
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiInit("g1")));
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiToolUse("search", "{\"query\":\"test\"}")));
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiMessage("Found 3 results")));
            add(all, parser.parseGeminiLine(ParserTestFixtures.geminiResult(300, 1)));

            AgentOutputAssertions.assertThat(all)
                    .hasEventCount(4)
                    .hasToolUse("search")
                    .hasTextChunk("Found 3 results")
                    .hasTurnComplete();
        }

        private void add(List<PassthroughEvent> list, PassthroughEvent event) {
            if (event != null) list.add(event);
        }
    }

    // ========================================================================
    // Subprocess Prompt → Response (Integration)
    // ========================================================================

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class SubprocessPromptResponse {

        /**
         * Claude: full prompt → text response → turn stats.
         */
        @Test
        void claudeFullFlow() throws Exception {
            MockAgentScript script = MockAgentScript.claude()
                    .sessionInit("session-flow-1")
                    .textResponse("I'll help you with that.")
                    .textResponse(" Here's the answer.")
                    .turnComplete(500, 100)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("claude")
                    .script(script).build()) {
                h.chat("help me");
                h.assertResultContains("I'll help you with that.")
                        .assertResultContains("Here's the answer.")
                        .assertOutputContains("I'll help you with that.");
            }
        }

        /**
         * Codex: text response with token tracking.
         */
        @Test
        void codexWithTokens() throws Exception {
            MockAgentScript script = MockAgentScript.codex()
                    .sessionInit("codex-flow")
                    .textResponse("Codex response here")
                    .turnComplete(800, 200)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("codex")
                    .script(script).build()) {
                h.chat("test");
                h.assertResultContains("Codex response here")
                        .assertTokenUsage(800, 200);
            }
        }

        /**
         * OpenCode: text + tool + token usage in one flow.
         */
        @Test
        void opencodeWithToolAndTokens() throws Exception {
            MockAgentScript script = MockAgentScript.opencode()
                    .sessionInit("oc-flow")
                    .toolUse("bash", "echo hi")
                    .textResponse("It echoed hi")
                    .turnComplete(300, 80)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("opencode")
                    .script(script).build()) {
                h.chat("run echo hi");
                h.assertResultContains("It echoed hi")
                        .assertTokenUsage(300, 80);
            }
        }

        /**
         * Pi: streaming text deltas concatenate.
         */
        @Test
        void piStreamingDeltas() throws Exception {
            MockAgentScript script = MockAgentScript.pi()
                    .sessionInit("pi-flow")
                    .textResponse("Part ")
                    .textResponse("one ")
                    .textResponse("and two")
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("pi")
                    .script(script).build()) {
                h.chat("test");
                h.assertResultContains("Part ")
                        .assertResultContains("one ")
                        .assertResultContains("and two");
            }
        }

        /**
         * Gemini: response with turn stats.
         */
        @Test
        void geminiWithTurnStats() throws Exception {
            MockAgentScript script = MockAgentScript.gemini()
                    .sessionInit("gemini-flow")
                    .textResponse("Gemini says hi")
                    .turnComplete(150, 40)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("gemini")
                    .script(script).build()) {
                h.chat("hi");
                h.assertResultContains("Gemini says hi");
            }
        }

        /**
         * Cross-agent: same logical flow (init → tool → text → complete)
         * produces consistent results regardless of agent format.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini"})
        void crossAgentToolThenTextFlow(String agent) throws Exception {
            MockAgentScript.Builder builder;
            switch (agent) {
                case "claude": builder = MockAgentScript.claude(); break;
                case "codex": builder = MockAgentScript.codex(); break;
                case "opencode": builder = MockAgentScript.opencode(); break;
                case "gemini": builder = MockAgentScript.gemini(); break;
                default: throw new IllegalArgumentException(agent);
            }

            MockAgentScript script = builder
                    .sessionInit(agent + "-cross-test")
                    .toolUse("bash", "echo test")
                    .textResponse("Done with the command")
                    .turnComplete(100, 50)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent(agent)
                    .script(script).build()) {
                h.chat("run echo test");
                h.assertResultContains("Done with the command");
            }
        }
    }

    // ========================================================================
    // Subprocess Tool Call Flows
    // ========================================================================

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class SubprocessToolFlows {

        /**
         * Claude: tool use appears in stdout rendering.
         */
        @Test
        void claudeToolUseRenderedInOutput() throws Exception {
            MockAgentScript script = MockAgentScript.claude()
                    .sessionInit("tools-1")
                    .toolUse("Read", "/tmp/main.java")
                    .textResponse("File read successfully")
                    .turnComplete(200, 80)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("claude")
                    .script(script).build()) {
                h.chat("read the file");
                h.assertResultContains("File read successfully")
                        .assertToolCallRendered("Read");
            }
        }

        /**
         * Codex: full command lifecycle with output rendering.
         */
        @Test
        void codexCommandOutputRendered() throws Exception {
            MockAgentScript script = MockAgentScript.codex()
                    .sessionInit("codex-tools")
                    .toolUse("exec", "git status")
                    .toolComplete("git status", "On branch main\nnothing to commit", 0)
                    .textResponse("Branch is clean")
                    .turnComplete(300, 100)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("codex")
                    .script(script).build()) {
                h.chat("check git status");
                h.assertResultEquals("Branch is clean")
                        .assertOutputContains("On branch main")
                        .assertOutputContains("[tool: exec exit 0]");
            }
        }

        /**
         * Pi: tool lifecycle renders start and completion.
         */
        @Test
        void piToolRendering() throws Exception {
            MockAgentScript script = MockAgentScript.pi()
                    .sessionInit("pi-tools")
                    .toolUse("bash", "pwd")
                    .toolComplete("bash", "/home/user\n", 0)
                    .textResponse("You are in /home/user")
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("pi")
                    .script(script).build()) {
                h.chat("where am I");
                h.assertResultContains("You are in /home/user")
                        .assertToolCallRendered("bash")
                        .assertOutputContains("[tool: bash exit 0]");
            }
        }

        /**
         * Multiple tools in sequence render sequentially.
         */
        @Test
        void claudeMultiToolRendering() throws Exception {
            MockAgentScript script = MockAgentScript.claude()
                    .sessionInit("multi-tools")
                    .toolUse("Read", "src/Main.java")
                    .toolUse("Edit", "src/Main.java")
                    .textResponse("Updated the file")
                    .turnComplete(400, 150)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("claude")
                    .script(script).build()) {
                h.chat("update Main.java");
                h.assertResultContains("Updated the file")
                        .assertToolCallRendered("Read")
                        .assertToolCallRendered("Edit");
            }
        }
    }

    // ========================================================================
    // Streaming Markdown Rendering Integration
    // ========================================================================

    @Nested
    class StreamingRenderingIntegration {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Text chunks from parser feed directly into StreamingMarkdownRenderer
         * and produce formatted output lines.
         */
        @Test
        void parsedTextChunksFeedIntoMarkdownRenderer() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(false); // plain mode
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            // Simulate streaming: parse events, feed text chunks to renderer
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(
                    ParserTestFixtures.claudeTextEvent("# Hello World\n"));
            for (PassthroughEvent event : events) {
                if (event instanceof TextChunk tc) {
                    renderer.accept(tc.text());
                }
            }
            renderer.flush();

            String combined = String.join("\n", outputLines);
            assertTrue(combined.contains("Hello World"),
                    "Rendered output should contain heading text, got: " + combined);
        }

        /**
         * Code block content from parsed events renders as a bordered block.
         */
        @Test
        void parsedCodeBlockRendersFormatted() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(false);
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            // Simulate token-by-token streaming of a code block
            renderer.accept("```java\n");
            renderer.accept("int x = 42;\n");
            renderer.accept("```\n");

            String combined = String.join("\n", outputLines);
            assertTrue(combined.contains("int x = 42"),
                    "Code block should contain code, got: " + combined);
        }

        /**
         * Bold/italic markdown from streaming text produces formatted output.
         */
        @Test
        void markdownFormattingPreservedThroughPipeline() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(false);
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            renderer.accept("This is **bold** and *italic* text\n");

            assertEquals(1, outputLines.size());
            // In plain mode, bold markers are stripped but text is preserved
            assertTrue(outputLines.get(0).contains("bold"),
                    "Should contain bold text");
            assertTrue(outputLines.get(0).contains("italic"),
                    "Should contain italic text");
        }

        /**
         * List items from streaming produce bullet-formatted lines.
         */
        @Test
        void listItemsStreamCorrectly() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(false);
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            renderer.accept("- First item\n");
            renderer.accept("- Second item\n");
            renderer.accept("- Third item\n");

            assertEquals(3, outputLines.size());
            String combined = String.join(" ", outputLines);
            assertTrue(combined.contains("First item"));
            assertTrue(combined.contains("Second item"));
            assertTrue(combined.contains("Third item"));
        }

        /**
         * Full pipeline: parse events → extract text → render markdown →
         * verify output contains the expected content.
         */
        @Test
        void fullPipelineParseToRender() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(true); // ANSI enabled
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            // Simulate a multi-chunk Claude response
            String[] chunks = {
                    ParserTestFixtures.claudeTextEvent("## Summary\n"),
                    ParserTestFixtures.claudeTextEvent("The build **succeeded** with:\n"),
                    ParserTestFixtures.claudeTextEvent("- 42 tests passed\n"),
                    ParserTestFixtures.claudeTextEvent("- 0 failures\n"),
            };

            for (String chunk : chunks) {
                List<PassthroughEvent> events = parser.parseClaudeLineMulti(chunk);
                for (PassthroughEvent event : events) {
                    if (event instanceof TextChunk tc) {
                        renderer.accept(tc.text());
                    }
                }
            }
            renderer.flush();

            String combined = String.join("\n", outputLines);
            String stripped = AsciiRenderer.stripAnsi(combined);

            assertTrue(stripped.contains("Summary"), "Should contain heading");
            assertTrue(stripped.contains("succeeded"), "Should contain bold text");
            assertTrue(stripped.contains("42 tests passed"), "Should contain list item");
            assertTrue(stripped.contains("0 failures"), "Should contain list item");
        }

        /**
         * Partial tokens that arrive character by character still produce
         * correct output once a full line arrives.
         */
        @Test
        void characterByCharacterStreaming() {
            List<String> outputLines = new ArrayList<>();
            TerminalRenderer term = new TerminalRenderer(false);
            AsciiRenderer ascii = new AsciiRenderer(term, 80);
            StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

            String text = "Hello world\n";
            for (char c : text.toCharArray()) {
                renderer.accept(String.valueOf(c));
            }

            assertEquals(1, outputLines.size());
            assertTrue(outputLines.get(0).contains("Hello world"));
        }
    }

    // ========================================================================
    // Tool Call Rendering Integration
    // ========================================================================

    @Nested
    class ToolCallRenderingIntegration {

        /**
         * TerminalRenderer.renderToolCallStart produces expected output
         * for each common tool type.
         */
        @Test
        void toolCallStartRendering() {
            TerminalRenderer renderer = new TerminalRenderer(false);

            String bash = renderer.renderToolCallStart("bash", "{\"command\":\"mvn test\"}");
            assertTrue(bash.contains("Bash"), "Should show prettified name");
            assertTrue(bash.contains("mvn test"), "Should show command");

            String read = renderer.renderToolCallStart("read", "{\"file_path\":\"src/Main.java\"}");
            assertTrue(read.contains("Read"), "Should show prettified name");
            assertTrue(read.contains("src/Main.java"), "Should show file path");

            String grep = renderer.renderToolCallStart("grep", "{\"pattern\":\"TODO\",\"path\":\"src/\"}");
            assertTrue(grep.contains("Grep"), "Should show prettified name");
        }

        /**
         * MCP-prefixed tool names are cleaned up for display.
         */
        @Test
        void mcpPrefixStrippedFromToolNames() {
            TerminalRenderer renderer = new TerminalRenderer(false);

            String result = renderer.renderToolCallStart("mcp__kompile__read", "{\"file_path\":\"f.java\"}");
            assertTrue(result.contains("Read"), "Should strip MCP prefix and capitalize");
            assertFalse(result.contains("mcp__"), "Should not contain raw MCP prefix");
        }

        /**
         * Tool prettification: snake_case → Title Case.
         */
        @Test
        void toolNamePrettification() {
            assertEquals("Read", TerminalRenderer.prettifyToolName("read"));
            assertEquals("Code Search", TerminalRenderer.prettifyToolName("mcp__kompile__code_search"));
            assertEquals("Bash", TerminalRenderer.prettifyToolName("bash"));
            assertEquals("unknown", TerminalRenderer.prettifyToolName(null));
            assertEquals("unknown", TerminalRenderer.prettifyToolName(""));
        }
    }

    // ========================================================================
    // Session Lifecycle
    // ========================================================================

    @Nested
    class SessionLifecycle {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Session ID is captured from the init event for each agent format.
         */
        @Test
        void sessionIdCapturedFromInitEvent() {
            // Claude
            SessionInit claude = AgentOutputAssertions
                    .assertThat(parser.parseClaudeLineMulti(ParserTestFixtures.claudeSystemEvent("claude-123")))
                    .firstOfType(SessionInit.class);
            assertEquals("claude-123", claude.sessionId());

            // Codex
            SessionInit codex = AgentOutputAssertions
                    .assertThat(parser.parseCodexLine(ParserTestFixtures.codexThreadStarted("codex-456")))
                    .firstOfType(SessionInit.class);
            assertEquals("codex-456", codex.sessionId());

            // Gemini
            SessionInit gemini = AgentOutputAssertions
                    .assertThat(parser.parseGeminiLine(ParserTestFixtures.geminiInit("gemini-789")))
                    .firstOfType(SessionInit.class);
            assertEquals("gemini-789", gemini.sessionId());

            // OpenCode
            SessionInit oc = AgentOutputAssertions
                    .assertThat(parser.parseOpenCodeLineMulti(ParserTestFixtures.openCodeStepStart("oc-abc")))
                    .firstOfType(SessionInit.class);
            assertEquals("oc-abc", oc.sessionId());

            // Pi
            SessionInit pi = AgentOutputAssertions
                    .assertThat(parser.parsePiLineMulti(ParserTestFixtures.piSession("pi-xyz")))
                    .firstOfType(SessionInit.class);
            assertEquals("pi-xyz", pi.sessionId());
        }

        /**
         * Turn complete captures duration and cost (Claude), or token counts (Codex/OpenCode).
         */
        @Test
        void turnCompleteCaptures() {
            // Claude: duration + cost
            TurnComplete claude = AgentOutputAssertions
                    .assertThat(parser.parseClaudeLineMulti(ParserTestFixtures.claudeResultEvent(5000, 0.025)))
                    .firstOfType(TurnComplete.class);
            assertEquals(5000, claude.durationMs());
            assertEquals(0.025, claude.costUsd(), 1e-9);

            // Codex: input + output tokens
            TurnComplete codex = (TurnComplete) parser.parseCodexLine(
                    ParserTestFixtures.codexTurnCompleted(1500, 300, 500));
            assertNotNull(codex);
        }

        /**
         * OpenCode token usage captures all four fields including cache.
         */
        @Test
        void opencodeTokenUsageCapturesCache() {
            List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(
                    ParserTestFixtures.openCodeStepFinish(1000, 250, 800, 100));

            TokenUsage usage = AgentOutputAssertions.assertThat(events).firstOfType(TokenUsage.class);
            assertNotNull(usage);
            assertEquals(1000, usage.inputTokens());
            assertEquals(250, usage.outputTokens());
            assertEquals(800, usage.cacheReadTokens());
            assertEquals(100, usage.cacheCreationTokens());
        }
    }

    // ========================================================================
    // Error and Edge Cases
    // ========================================================================

    @Nested
    class ErrorAndEdgeCases {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Malformed JSON falls back to plain text for Claude.
         */
        @Test
        void claudeMalformedJsonFallsBackToText() {
            List<PassthroughEvent> events = parser.parseClaudeLineMulti("not valid json {{{");
            AgentOutputAssertions.assertThat(events)
                    .hasEventCount(1)
                    .firstEventIs(TextChunk.class)
                    .hasTextChunk("not valid json {{{");
        }

        /**
         * Gemini noise lines (YOLO mode, retries) are suppressed.
         */
        @Test
        void geminiNoiseLinesSuppressed() {
            assertNull(parser.parseGeminiLine("YOLO mode is enabled - skipping all confirmations"));
            assertNull(parser.parseGeminiLine("Retrying after 5 seconds..."));
            assertNull(parser.parseGeminiLine("Attempt 3 failed: timeout"));
        }

        /**
         * Codex stdin notices are suppressed.
         */
        @Test
        void codexStdinNoticesSuppressed() {
            assertNull(parser.parseCodexLine("Reading prompt from stdin..."));
            assertNull(parser.parseCodexLine("Reading additional input from stdin..."));
        }

        /**
         * User-role messages in Gemini are skipped.
         */
        @Test
        void geminiUserMessagesSkipped() {
            assertNull(parser.parseGeminiLine(
                    "{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"}"));
        }

        /**
         * Gemini tool_result events are skipped.
         */
        @Test
        void geminiToolResultSkipped() {
            assertNull(parser.parseGeminiLine(
                    "{\"type\":\"tool_result\",\"tool_name\":\"search\",\"output\":\"data\"}"));
        }

        /**
         * Pi message_end after text deltas is suppressed to avoid duplicates.
         */
        @Test
        void piMessageEndSuppressedAfterDeltas() {
            parser.parsePiLine(ParserTestFixtures.piTextDelta("m1", "Hello "));
            parser.parsePiLine(ParserTestFixtures.piTextDelta("m1", "World"));

            PassthroughEvent end = parser.parsePiLine(ParserTestFixtures.piMessageEnd("m1", "Hello World"));
            assertNull(end, "message_end should be suppressed when text_deltas were emitted");
        }

        /**
         * Null/blank inputs handled gracefully for all parsers.
         */
        @Test
        void nullAndBlankInputsHandled() {
            assertTrue(parser.parseClaudeLineMulti(null).isEmpty());
            assertTrue(parser.parseClaudeLineMulti("").isEmpty());
            assertTrue(parser.parseClaudeLineMulti("   ").isEmpty());

            assertNull(parser.parseGeminiLine(null));
            assertNull(parser.parseGeminiLine("   "));

            assertNull(parser.parseCodexLine(null));
            assertNull(parser.parseCodexLine(""));

            assertNull(parser.parseOpenCodeLine(null));
            assertNull(parser.parseOpenCodeLine(""));
        }

        /**
         * Empty script produces no assistant text.
         */
        @Test
        @DisabledOnOs(OS.WINDOWS)
        void emptyScriptProducesNoText() throws Exception {
            MockAgentScript script = MockAgentScript.raw()
                    .rawLine("{\"type\":\"system\",\"session_id\":\"empty\"}")
                    .rawLine("{\"type\":\"result\",\"duration_ms\":0,\"cost_usd\":0}")
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("claude")
                    .script(script).build()) {
                h.chat("test");
                assertEquals("", h.getLastResult().text);
            }
        }

        /**
         * ContentBlock delta events parse correctly.
         */
        @Test
        void contentBlockDeltasParsed() {
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(
                    ParserTestFixtures.claudeContentBlockDelta("incremental "));
            AgentOutputAssertions.assertThat(events)
                    .hasEventCount(1)
                    .hasTextChunk("incremental ");
        }
    }

    // ========================================================================
    // Noise Filtering
    // ========================================================================

    @Nested
    class NoiseFiltering {

        @Test
        void mcpToolListingsDetectedAsNoise() {
            assertTrue(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise(
                    "mcp__kompile__task Spawn a single subagent multi_task mcp__kompile__quorum_task"));
        }

        @Test
        void toolDescriptionHeadersDetectedAsNoise() {
            assertTrue(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise(
                    "Tool Description webfetch Fetch web page Tool Description websearch Search the web"));
        }

        @Test
        void legitimateTextPassesNoiseFilter() {
            assertFalse(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise(
                    "I found the bug in src/Main.java at line 42."));
        }

        @Test
        void shortTextPassesNoiseFilter() {
            assertFalse(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise("OK"));
            assertFalse(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise(null));
            assertFalse(ai.kompile.cli.main.chat.agent.SubprocessAgentRunner.isSystemPromptNoise(""));
        }
    }

    // ========================================================================
    // Cross-Agent Consistency (Same flow, all agents)
    // ========================================================================

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class CrossAgentConsistency {

        /**
         * Every agent that supports sessionInit + textResponse + turnComplete
         * produces a non-empty result text.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini"})
        void basicTextFlowProducesResult(String agent) throws Exception {
            MockAgentScript.Builder builder;
            switch (agent) {
                case "claude": builder = MockAgentScript.claude(); break;
                case "codex": builder = MockAgentScript.codex(); break;
                case "opencode": builder = MockAgentScript.opencode(); break;
                case "gemini": builder = MockAgentScript.gemini(); break;
                default: throw new IllegalArgumentException(agent);
            }

            MockAgentScript script = builder
                    .sessionInit(agent + "-consistency")
                    .textResponse("consistent response")
                    .turnComplete(100, 50)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent(agent)
                    .script(script).build()) {
                h.chat("test");
                h.assertResultContains("consistent response");
            }
        }

        /**
         * Pi (no turnComplete) still produces a valid result.
         */
        @Test
        void piWithoutTurnCompleteStillWorks() throws Exception {
            MockAgentScript script = MockAgentScript.pi()
                    .sessionInit("pi-consistency")
                    .textResponse("pi response")
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent("pi")
                    .script(script).build()) {
                h.chat("test");
                h.assertResultContains("pi response");
            }
        }

        /**
         * Tool use flow works across agents that support it.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini"})
        void toolUseFlowCrossAgent(String agent) throws Exception {
            MockAgentScript.Builder builder;
            switch (agent) {
                case "claude": builder = MockAgentScript.claude(); break;
                case "codex": builder = MockAgentScript.codex(); break;
                case "opencode": builder = MockAgentScript.opencode(); break;
                case "gemini": builder = MockAgentScript.gemini(); break;
                default: throw new IllegalArgumentException(agent);
            }

            MockAgentScript script = builder
                    .sessionInit(agent + "-tool-test")
                    .toolUse("bash", "echo ok")
                    .textResponse("command succeeded")
                    .turnComplete(200, 80)
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent(agent)
                    .script(script).build()) {
                h.chat("run echo ok");
                h.assertResultContains("command succeeded");
            }
        }

        /**
         * Empty message doesn't crash any agent.
         */
        @ParameterizedTest
        @ValueSource(strings = {"claude", "codex", "opencode", "gemini", "pi"})
        void emptyMessageDoesNotCrash(String agent) throws Exception {
            MockAgentScript.Builder builder;
            switch (agent) {
                case "claude": builder = MockAgentScript.claude(); break;
                case "codex": builder = MockAgentScript.codex(); break;
                case "opencode": builder = MockAgentScript.opencode(); break;
                case "gemini": builder = MockAgentScript.gemini(); break;
                case "pi": builder = MockAgentScript.pi(); break;
                default: throw new IllegalArgumentException(agent);
            }

            MockAgentScript script = builder
                    .sessionInit(agent + "-empty")
                    .textResponse("handled empty")
                    .build();

            try (SubprocessTestHarness h = SubprocessTestHarness.forAgent(agent)
                    .script(script).build()) {
                h.chat("");
                h.assertResultContains("handled empty");
            }
        }
    }

    // ========================================================================
    // Codex Delta Accumulation
    // ========================================================================

    @Nested
    class CodexDeltaAccumulation {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Codex item.updated events emit incremental deltas, not full output.
         */
        @Test
        void itemUpdatedEmitsIncrementalDelta() {
            parser.parseCodexLine(ParserTestFixtures.codexItemStarted("item_0", "cmd"));
            PassthroughEvent update1 = parser.parseCodexLine(
                    ParserTestFixtures.codexItemUpdated("item_0", "first"));
            assertTrue(update1 instanceof ToolOutput);
            assertEquals("first", ((ToolOutput) update1).output());

            PassthroughEvent update2 = parser.parseCodexLine(
                    ParserTestFixtures.codexItemUpdated("item_0", "firstsecond"));
            assertTrue(update2 instanceof ToolOutput);
            assertEquals("second", ((ToolOutput) update2).output());
        }

        /**
         * Codex item.completed with command_execution emits a delta
         * (new content since last update) and an exit code.
         */
        @Test
        void itemCompletedEmitsDeltaAndExitCode() {
            parser.parseCodexLine(ParserTestFixtures.codexItemStarted("item_0", "cmd"));
            parser.parseCodexLine(ParserTestFixtures.codexItemUpdated("item_0", "partial"));

            PassthroughEvent complete = parser.parseCodexLine(
                    ParserTestFixtures.codexItemCompletedCommand("item_0", "partialcomplete", 0));
            assertTrue(complete instanceof ToolComplete);
            ToolComplete tc = (ToolComplete) complete;
            assertEquals("complete", tc.output()); // delta from "partial" → "partialcomplete"
            assertEquals(0, tc.exitCode());
            assertFalse(tc.error());
        }

        /**
         * Codex message.delta events produce text chunks.
         */
        @Test
        void messageDeltaProducesTextChunk() {
            PassthroughEvent event = parser.parseCodexLine(
                    ParserTestFixtures.codexMessageDelta("streaming text"));
            assertTrue(event instanceof TextChunk);
            assertEquals("streaming text", ((TextChunk) event).text());
        }
    }

    // ========================================================================
    // Pi Delta Accumulation
    // ========================================================================

    @Nested
    class PiDeltaAccumulation {

        private PassthroughStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new PassthroughStreamParser();
        }

        /**
         * Pi tool updates accumulate, and tool end emits only the new delta.
         */
        @Test
        void toolUpdatesThenEndEmitsDelta() {
            parser.parsePiLine(ParserTestFixtures.piToolStart("c1", "bash", "{\"command\":\"ls\"}"));
            PassthroughEvent update = parser.parsePiLine(
                    ParserTestFixtures.piToolUpdate("c1", "file1"));
            assertTrue(update instanceof ToolOutput);
            assertEquals("file1", ((ToolOutput) update).output());

            PassthroughEvent end = parser.parsePiLine(
                    ParserTestFixtures.piToolEnd("c1", "bash", "file1file2", 0));
            assertTrue(end instanceof ToolComplete);
            assertEquals("file2", ((ToolComplete) end).output()); // delta
            assertEquals(0, ((ToolComplete) end).exitCode());
        }

        /**
         * Multiple text deltas accumulate; message_end is suppressed.
         */
        @Test
        void textDeltasAccumulateAndEndSuppressed() {
            PassthroughEvent d1 = parser.parsePiLine(ParserTestFixtures.piTextDelta("m1", "Hel"));
            PassthroughEvent d2 = parser.parsePiLine(ParserTestFixtures.piTextDelta("m1", "lo!"));

            assertEquals("Hel", ((TextChunk) d1).text());
            assertEquals("lo!", ((TextChunk) d2).text());

            PassthroughEvent end = parser.parsePiLine(ParserTestFixtures.piMessageEnd("m1", "Hello!"));
            assertNull(end, "message_end suppressed after text_delta");
        }
    }
}
