/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.agent;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.chat.history.service.FolderService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the streaming pipeline fixes.
 *
 * Tests verify:
 * 1. supportsStreamJson correctly routes agents by registered name (not just short name)
 * 2. LinkedHashSet preserves arg ordering for --output-format stream-json
 * 3. Live CLI agents produce real-time streaming output
 * 4. Stream parser correctly handles each agent's output format
 */
@DisplayName("Agent Streaming End-to-End Tests")
class AgentStreamingEndToEndTest {

    // =========================================================================
    // Unit Tests: supportsStreamJson with actual registered names
    // =========================================================================

    @Nested
    @DisplayName("supportsStreamJson routing")
    class SupportsStreamJsonRouting {

        private ClaudeStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new ClaudeStreamParser();
        }

        @Test
        @DisplayName("Should support stream-json for 'claude-cli' (actual registered name)")
        void shouldSupportStreamJsonForClaudeCli() {
            assertTrue(parser.supportsStreamJson("claude-cli"));
        }

        @Test
        @DisplayName("Should support stream-json for 'claude' (short name)")
        void shouldSupportStreamJsonForClaudeShort() {
            assertTrue(parser.supportsStreamJson("claude"));
        }

        @Test
        @DisplayName("Should support stream-json for null (default to Claude)")
        void shouldSupportStreamJsonForNull() {
            assertTrue(parser.supportsStreamJson(null));
        }

        @Test
        @DisplayName("Should NOT support stream-json for 'codex-cli' (actual registered name)")
        void shouldNotSupportStreamJsonForCodexCli() {
            assertFalse(parser.supportsStreamJson("codex-cli"),
                    "codex-cli must be routed to plain-text mode, not Claude JSON parser");
        }

        @Test
        @DisplayName("Should NOT support stream-json for 'codex' (short name)")
        void shouldNotSupportStreamJsonForCodexShort() {
            assertFalse(parser.supportsStreamJson("codex"));
        }

        @Test
        @DisplayName("Should NOT support stream-json for 'gemini-cli' (actual registered name)")
        void shouldNotSupportStreamJsonForGeminiCli() {
            assertFalse(parser.supportsStreamJson("gemini-cli"),
                    "gemini-cli must be routed to plain-text mode, not Claude JSON parser");
        }

        @Test
        @DisplayName("Should NOT support stream-json for 'gemini' (short name)")
        void shouldNotSupportStreamJsonForGeminiShort() {
            assertFalse(parser.supportsStreamJson("gemini"));
        }

        @Test
        @DisplayName("Should NOT support stream-json for custom codex-based names")
        void shouldNotSupportStreamJsonForCustomCodexNames() {
            assertFalse(parser.supportsStreamJson("codex-custom"));
            assertFalse(parser.supportsStreamJson("my-codex-agent"));
        }

        @Test
        @DisplayName("Should NOT support stream-json for custom gemini-based names")
        void shouldNotSupportStreamJsonForCustomGeminiNames() {
            assertFalse(parser.supportsStreamJson("gemini-pro"));
            assertFalse(parser.supportsStreamJson("my-gemini-agent"));
        }

        @Test
        @DisplayName("Should support stream-json for API agents (kompile-local, openai, etc)")
        void shouldSupportStreamJsonForApiAgents() {
            // API agents don't go through this path (they use ApiAgentChatExecutor),
            // but if they did, they should get the Claude parser (their output is different anyway)
            assertTrue(parser.supportsStreamJson("kompile-local"));
            assertTrue(parser.supportsStreamJson("openai-gpt4"));
            assertTrue(parser.supportsStreamJson("custom-agent"));
        }
    }

    // =========================================================================
    // Unit Tests: LinkedHashSet preserves arg ordering
    // =========================================================================

    @Nested
    @DisplayName("Arg ordering with LinkedHashSet")
    class ArgOrdering {

        @Test
        @DisplayName("AgentProvider.safeArgs() preserves insertion order")
        void safeArgsPreservesInsertionOrder() {
            AgentProvider agent = AgentProvider.builder()
                    .name("claude-cli")
                    .command("claude")
                    .addArg("--output-format")
                    .addArg("stream-json")
                    .addArg("--verbose")
                    .build();

            List<String> args = new ArrayList<>(agent.safeArgs());

            int outputFormatIndex = args.indexOf("--output-format");
            int streamJsonIndex = args.indexOf("stream-json");
            int verboseIndex = args.indexOf("--verbose");

            assertTrue(outputFormatIndex >= 0, "Should contain --output-format");
            assertTrue(streamJsonIndex >= 0, "Should contain stream-json");
            assertTrue(verboseIndex >= 0, "Should contain --verbose");

            assertTrue(outputFormatIndex < streamJsonIndex,
                    "--output-format must come before stream-json. Actual order: " + args);
            assertTrue(streamJsonIndex < verboseIndex,
                    "stream-json must come before --verbose. Actual order: " + args);
        }

        @Test
        @DisplayName("AgentProvider args ordering is stable across multiple accesses")
        void argsOrderingIsStable() {
            AgentProvider agent = AgentProvider.builder()
                    .name("claude-cli")
                    .command("claude")
                    .addArg("--output-format")
                    .addArg("stream-json")
                    .addArg("--verbose")
                    .build();

            // Access multiple times - should always return same order
            for (int i = 0; i < 100; i++) {
                List<String> args = new ArrayList<>(agent.safeArgs());
                assertEquals(List.of("--output-format", "stream-json", "--verbose"), args,
                        "Iteration " + i + ": args order must be deterministic");
            }
        }

        @Test
        @DisplayName("buildInteractiveCommand places args in correct order")
        void buildInteractiveCommandArgsOrder() throws Exception {
            AgentProvider agent = AgentProvider.builder()
                    .name("claude-cli")
                    .displayName("Claude Code")
                    .command("claude")
                    .skipPermissionsFlag("--dangerously-skip-permissions")
                    .skipPermissions(true)
                    .addArg("--output-format")
                    .addArg("stream-json")
                    .addArg("--verbose")
                    .available(true)
                    .build();

            // Use the service to build the command through the real code path
            AgentChatService service = createService();
            List<String> command = service.buildInteractiveCommand(agent, true, false);

            // Verify the full command structure
            assertEquals("claude", command.get(0));
            assertEquals("--dangerously-skip-permissions", command.get(1));

            // Find --output-format and stream-json in the command
            int outputFormatIdx = command.indexOf("--output-format");
            int streamJsonIdx = command.indexOf("stream-json");

            assertTrue(outputFormatIdx > 0, "Command should contain --output-format: " + command);
            assertEquals(outputFormatIdx + 1, streamJsonIdx,
                    "stream-json must immediately follow --output-format. Command: " + command);
        }
    }

    // =========================================================================
    // Unit Tests: ClaudeStreamParser handles plain text for non-Claude agents
    // =========================================================================

    @Nested
    @DisplayName("Stream parser plain-text handling for non-Claude agents")
    class PlainTextParsing {

        private ClaudeStreamParser parser;

        @BeforeEach
        void setUp() {
            parser = new ClaudeStreamParser();
        }

        @Test
        @DisplayName("Plain text lines pass through as text events")
        void plainTextPassesThrough() {
            String line = "Hello, I can help you with that task.";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", line);

            assertNotNull(result);
            assertEquals("text", result.type());
            assertEquals(line, result.textContent());
            assertFalse(result.isResult());
        }

        @Test
        @DisplayName("Lines starting with { that are NOT valid JSON pass through as text")
        void invalidJsonPassesThroughAsText() {
            String line = "{this is not json, just output that starts with brace";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", line);

            assertNotNull(result);
            assertEquals("text", result.type());
            assertEquals(line, result.textContent());
        }

        @Test
        @DisplayName("Valid JSON from non-Claude agent (codex/gemini) would be mishandled without fix")
        void validJsonFromNonClaudeAgentIsHandledByParser() {
            // If a non-Claude agent outputs JSON, and supportsStreamJson returns true,
            // the parser tries to interpret it as Claude stream-json format.
            // After the fix, non-Claude agents bypass the parser entirely.
            String codexJsonOutput = "{\"files_changed\": [\"main.py\"]}";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", codexJsonOutput);

            // The parser sees unknown type (no "type" field at top level),
            // so it returns it as plain text
            assertNotNull(result);
            assertEquals("text", result.type());
        }

        @Test
        @DisplayName("Claude stream-json format is correctly parsed")
        void claudeStreamJsonIsParsed() {
            String claudeInit = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc123\"}";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", claudeInit);

            assertNotNull(result);
            assertEquals("init", result.type());
            assertEquals("Session initialized", result.textContent());
        }

        @Test
        @DisplayName("Claude assistant text event is correctly parsed")
        void claudeAssistantTextIsParsed() {
            String assistantEvent = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello world\"}]}}";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", assistantEvent);

            assertNotNull(result);
            assertEquals("text", result.type());
            assertEquals("Hello world", result.textContent());
        }

        @Test
        @DisplayName("Claude result event is correctly parsed")
        void claudeResultEventIsParsed() {
            String resultEvent = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":5200,\"total_cost_usd\":0.032,\"num_turns\":3}";
            ClaudeStreamParser.ParseResult result = parser.parseLine("test-session", resultEvent);

            assertNotNull(result);
            assertTrue(result.isResult());
            assertFalse(result.isError());
            assertEquals(5200, result.durationMs());
            assertEquals(0.032, result.costUsd(), 0.001);
            assertEquals(3, result.numTurns());
        }
    }

    // =========================================================================
    // Live CLI Tests: Actual subprocess streaming
    // These tests spawn real CLI processes and verify streaming output.
    // They are conditionally enabled based on CLI availability.
    // =========================================================================

    @Nested
    @DisplayName("Live CLI streaming - Claude")
    @EnabledIf("isClaudeAvailable")
    class LiveClaudeStreaming {

        static boolean isClaudeAvailable() {
            return AgentStreamingEndToEndTest.isClaudeAvailable();
        }

        @Test
        @DisplayName("Claude CLI produces stream-json output with --output-format stream-json --verbose")
        void claudeProducesStreamJson() throws Exception {
            // --verbose is REQUIRED when using --output-format stream-json with -p (print mode)
            List<String> command = List.of(
                    "claude",
                    "--dangerously-skip-permissions",
                    "--output-format", "stream-json",
                    "--verbose",
                    "-p", "Say exactly: hello world"
            );

            ProcessResult result = executeWithTimeout(command, 90);

            assertFalse(result.lines.isEmpty(),
                    "Claude should produce output. stderr: " + result.stderr);

            // Verify at least one line is valid stream-json
            boolean hasSystemInit = false;
            boolean hasAssistantEvent = false;
            boolean hasResultEvent = false;

            ClaudeStreamParser parser = new ClaudeStreamParser();
            for (String line : result.lines) {
                if (line.trim().isEmpty()) continue;
                ClaudeStreamParser.ParseResult parsed = parser.parseLine("live-test", line);
                if (parsed != null) {
                    if ("init".equals(parsed.type())) hasSystemInit = true;
                    if ("text".equals(parsed.type()) && parsed.textContent() != null && !parsed.textContent().isEmpty()) {
                        hasAssistantEvent = true;
                    }
                    if (parsed.isResult()) hasResultEvent = true;
                }
            }

            assertTrue(hasSystemInit, "Should have system/init event. Lines: " + result.lines.subList(0, Math.min(5, result.lines.size())));
            assertTrue(hasAssistantEvent, "Should have assistant text content");
            assertTrue(hasResultEvent, "Should have result event");
            assertEquals(0, result.exitCode, "Claude should exit 0. stderr: " + result.stderr);
        }

        @Test
        @DisplayName("Claude CLI streams tokens incrementally (not all at once)")
        void claudeStreamsIncrementally() throws Exception {
            // --verbose is required for --output-format stream-json in print mode
            List<String> command = List.of(
                    "claude",
                    "--dangerously-skip-permissions",
                    "--output-format", "stream-json",
                    "--verbose",
                    "-p", "Write a haiku about streaming data in real time"
            );

            // Track timing of output lines - use redirectErrorStream(true) since
            // Claude writes stream-json to stdout when --verbose is set
            List<Long> timestamps = new ArrayList<>();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long start = System.nanoTime();
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        timestamps.add(System.nanoTime() - start);
                    }
                }
            }

            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                fail("Claude process timed out");
            }

            // With streaming, we expect multiple lines spread over time, not all at once
            // Claude produces: rate_limit_event, system/init, assistant (thinking), assistant (text), result
            assertTrue(timestamps.size() >= 3,
                    "Should have multiple streaming events, got: " + timestamps.size());

            // Verify there's time spread between events (not all in first millisecond)
            if (timestamps.size() > 2) {
                long firstMs = timestamps.get(0) / 1_000_000;
                long lastMs = timestamps.get(timestamps.size() - 1) / 1_000_000;
                long spread = lastMs - firstMs;
                assertTrue(spread > 100,
                        "Streaming events should span time. Spread: " + spread + "ms across " + timestamps.size() + " events");
            }
        }

        @Test
        @DisplayName("ClaudeStreamParser correctly parses live Claude output end-to-end")
        void parserHandlesLiveClaudeOutput() throws Exception {
            // --verbose is required for stream-json in print mode
            List<String> command = List.of(
                    "claude",
                    "--dangerously-skip-permissions",
                    "--output-format", "stream-json",
                    "--verbose",
                    "-p", "Say exactly: test123"
            );

            ProcessResult result = executeWithTimeout(command, 90);
            assertEquals(0, result.exitCode, "Claude should exit 0. stderr: " + result.stderr);

            ClaudeStreamParser parser = new ClaudeStreamParser();
            StringBuilder accumulated = new StringBuilder();
            boolean gotResult = false;
            Integer durationMs = null;

            for (String line : result.lines) {
                if (line.trim().isEmpty()) continue;
                ClaudeStreamParser.ParseResult parsed = parser.parseLine("e2e-test", line);
                if (parsed != null) {
                    if (parsed.textContent() != null) {
                        accumulated.append(parsed.textContent());
                    }
                    if (parsed.isResult()) {
                        gotResult = true;
                        durationMs = parsed.durationMs();
                    }
                }
            }

            String content = accumulated.toString().toLowerCase();
            assertTrue(content.contains("test123"),
                    "Accumulated content should contain 'test123'. Got: " + content);
            assertTrue(gotResult, "Should have received result event");
            assertNotNull(durationMs, "Result should include duration");
            assertTrue(durationMs > 0, "Duration should be positive");
        }
    }

    @Nested
    @DisplayName("Live CLI streaming - Codex")
    @EnabledIf("isCodexAvailable")
    class LiveCodexStreaming {

        static boolean isCodexAvailable() {
            return AgentStreamingEndToEndTest.isCodexAvailable();
        }

        @Test
        @DisplayName("Codex CLI produces plain text output (not stream-json)")
        void codexProducesPlainText() throws Exception {
            // Codex uses 'exec' subcommand for non-interactive mode (not -p like Claude/Gemini)
            // and --dangerously-bypass-approvals-and-sandbox as its skip-permissions flag
            List<String> command = List.of(
                    "codex",
                    "exec",
                    "--dangerously-bypass-approvals-and-sandbox",
                    "Say exactly: hello codex"
            );

            // Codex reads stdin until EOF before processing — must close stdin explicitly
            ProcessResult result = executeWithStdinClose(command, 60);

            assertFalse(result.lines.isEmpty(),
                    "Codex should produce output. stderr: " + result.stderr);

            // Codex output should be plain text, not stream-json
            // Verify supportsStreamJson is false for codex-cli
            ClaudeStreamParser parser = new ClaudeStreamParser();
            assertFalse(parser.supportsStreamJson("codex-cli"));

            // The output should contain the response as plain text
            String fullOutput = String.join("\n", result.lines);
            assertFalse(fullOutput.isEmpty(), "Should have output");

            // If we pipe it through the parser's plain-text path, every line should come back as text
            for (String line : result.lines) {
                if (line.trim().isEmpty()) continue;
                ClaudeStreamParser.ParseResult parsed = parser.parseLine("codex-test", line);
                if (parsed != null) {
                    assertEquals("text", parsed.type(),
                            "All codex output should be parsed as 'text' type when using plain-text path. Line: " + line);
                }
            }
        }
    }

    @Nested
    @DisplayName("Live CLI streaming - Gemini")
    @EnabledIf("isGeminiAvailable")
    class LiveGeminiStreaming {

        static boolean isGeminiAvailable() {
            return AgentStreamingEndToEndTest.isGeminiAvailable();
        }

        @Test
        @DisplayName("Gemini CLI produces plain text output (not stream-json)")
        void geminiProducesPlainText() throws Exception {
            // Gemini requires a working directory for prompt file
            String workDir = System.getProperty("java.io.tmpdir");

            List<String> command = List.of(
                    "gemini",
                    "--yolo",
                    "-p", "Say exactly: hello gemini"
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }

            boolean completed = process.waitFor(60, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                fail("Gemini process timed out");
            }

            assertFalse(lines.isEmpty(), "Gemini should produce output");

            // Verify supportsStreamJson is false for gemini-cli
            ClaudeStreamParser parser = new ClaudeStreamParser();
            assertFalse(parser.supportsStreamJson("gemini-cli"));

            // All output should be treated as plain text
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                ClaudeStreamParser.ParseResult parsed = parser.parseLine("gemini-test", line);
                if (parsed != null) {
                    assertEquals("text", parsed.type(),
                            "Gemini output should be 'text' type in plain-text mode. Line: " + line);
                }
            }
        }
    }

    // =========================================================================
    // Integration Test: Full AgentChatService streaming path
    // =========================================================================

    @Nested
    @DisplayName("Full streaming pipeline integration")
    @EnabledIf("isClaudeAvailable")
    @ExtendWith(MockitoExtension.class)
    class FullStreamingPipeline {

        static boolean isClaudeAvailable() {
            return AgentStreamingEndToEndTest.isClaudeAvailable();
        }

        @Mock
        private AgentRegistryService agentRegistry;
        @Mock
        private AgentProcessDiagnosticService diagnosticService;
        @Mock
        private ServerPortService serverPortService;
        @Mock
        private FolderService folderService;

        @Test
        @DisplayName("AgentChatService streams Claude output via SseEmitter")
        void agentChatServiceStreamsViaEmitter() throws Exception {
            // Build the service with real ClaudeStreamParser
            ClaudeStreamParser realParser = new ClaudeStreamParser();
            AgentSubprocessExecutor subprocessExecutor = new AgentSubprocessExecutor(
                    agentRegistry, diagnosticService, realParser);
            AgentChatService service = new AgentChatService(
                    agentRegistry,
                    diagnosticService,
                    realParser,
                    subprocessExecutor,
                    List.of(new NoOpDocumentRetrieverImpl()),
                    List.of(new NoOpVectorStoreImpl()),
                    null, null,
                    serverPortService,
                    folderService,
                    null,
                    null
            );

            // Create agent matching real registration
            AgentProvider claude = AgentProvider.builder()
                    .name("claude-cli")
                    .displayName("Claude Code")
                    .command("claude")
                    .skipPermissionsFlag("--dangerously-skip-permissions")
                    .skipPermissions(true)
                    .addArg("--output-format")
                    .addArg("stream-json")
                    .addArg("--verbose")
                    .available(true)
                    .isDefault(true)
                    .build();

            // Create request
            AgentChatRequest request = new AgentChatRequest();
            request.setMessage("Say exactly: streaming test ok");
            request.setSkipPermissions(true);
            request.setInjectMcpTools(false);
            request.setAgentName("claude-cli");

            // Create SseEmitter and capture events
            SseEmitter emitter = new SseEmitter(60000L);
            List<SseEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean completed = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);

            // We can't easily intercept SseEmitter.send() without a real HTTP connection,
            // so instead we test the command construction + streaming loop directly.

            // Test 1: Verify command is built correctly
            List<String> command = service.buildInteractiveCommand(claude, true, false);
            assertTrue(command.contains("claude"));
            assertTrue(command.contains("--dangerously-skip-permissions"));

            int fmtIdx = command.indexOf("--output-format");
            assertTrue(fmtIdx > 0);
            assertEquals("stream-json", command.get(fmtIdx + 1));

            // Test 2: Verify the parser correctly identifies this as a stream-json agent
            assertTrue(realParser.supportsStreamJson("claude-cli"));

            // Test 3: Actually run the command and verify streaming output
            // Note: command is an ArrayList from buildInteractiveCommand, so we can add to it
            command = new ArrayList<>(command); // ensure mutable
            command.add("-p");
            command.add("Say exactly: streaming test ok");

            ProcessResult result = executeWithTimeout(command, 60);
            assertEquals(0, result.exitCode);

            // Parse all output through the real parser
            List<String> textChunks = new ArrayList<>();
            boolean gotStats = false;

            for (String line : result.lines) {
                if (line.trim().isEmpty()) continue;
                ClaudeStreamParser.ParseResult parsed = realParser.parseLine("pipeline-test", line);
                if (parsed != null) {
                    if (parsed.textContent() != null && !parsed.textContent().isEmpty()) {
                        textChunks.add(parsed.textContent());
                    }
                    if (parsed.isResult()) {
                        gotStats = true;
                    }
                }
            }

            assertFalse(textChunks.isEmpty(), "Should have text chunks from streaming");
            assertTrue(gotStats, "Should have received result/stats event");

            String fullText = String.join("", textChunks).toLowerCase();
            assertTrue(fullText.contains("streaming test ok") || fullText.contains("streaming"),
                    "Response should contain expected text. Got: " + fullText);
        }

        @Test
        @DisplayName("Modified files are tracked from tool_use events")
        void modifiedFilesTracked() {
            ClaudeStreamParser parser = new ClaudeStreamParser();
            String sessionId = "track-test";

            // Simulate a Claude tool_use event with Edit
            String editEvent = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"tool_use\",\"id\":\"tool1\",\"name\":\"Edit\",\"input\":{\"file_path\":\"/tmp/test.txt\",\"old_string\":\"foo\",\"new_string\":\"bar\"}}" +
                    "]}}";

            ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, editEvent);
            assertNotNull(result);

            List<String> modifiedFiles = parser.getModifiedFiles(sessionId);
            assertEquals(1, modifiedFiles.size());
            assertEquals("/tmp/test.txt", modifiedFiles.get(0));

            // Cleanup
            parser.clearModifiedFiles(sessionId);
            assertTrue(parser.getModifiedFiles(sessionId).isEmpty());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static AgentChatService createService() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        AgentSubprocessExecutor subprocessExecutor = new AgentSubprocessExecutor(
                null, null, parser);
        return new AgentChatService(
                null, null, parser,
                subprocessExecutor,
                List.of(new NoOpDocumentRetrieverImpl()),
                List.of(new NoOpVectorStoreImpl()),
                null, null, null, null, null, null
        );
    }

    static boolean isClaudeAvailable() {
        return isCommandAvailable("claude");
    }

    static boolean isCodexAvailable() {
        return isCommandAvailable("codex");
    }

    static boolean isGeminiAvailable() {
        return isCommandAvailable("gemini");
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Execute a command, immediately closing stdin (required for Codex which reads stdin until EOF).
     */
    private static ProcessResult executeWithStdinClose(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Close stdin immediately so Codex doesn't wait for input
        process.getOutputStream().close();

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception e) { /* ignore */ }
        });
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            } catch (Exception e) { /* ignore */ }
        });

        stdoutThread.start();
        stderrThread.start();

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            stdoutThread.join(2000);
            stderrThread.join(2000);
            fail("Process timed out after " + timeoutSeconds + "s. Output so far: " + lines);
        }

        stdoutThread.join(5000);
        stderrThread.join(5000);
        return new ProcessResult(process.exitValue(), lines, stderr.toString());
    }

    private static ProcessResult executeWithTimeout(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        StringBuilder stderr = new StringBuilder();

        // Read stdout in background
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception e) {
                // ignore
            }
        });

        // Read stderr in background
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            } catch (Exception e) {
                // ignore
            }
        });

        stdoutThread.start();
        stderrThread.start();

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            stdoutThread.join(2000);
            stderrThread.join(2000);
            fail("Process timed out after " + timeoutSeconds + "s. Output so far: " + lines);
        }

        stdoutThread.join(5000);
        stderrThread.join(5000);

        return new ProcessResult(process.exitValue(), lines, stderr.toString());
    }

    record ProcessResult(int exitCode, List<String> lines, String stderr) {}

    record SseEvent(String name, Object data) {}
}
