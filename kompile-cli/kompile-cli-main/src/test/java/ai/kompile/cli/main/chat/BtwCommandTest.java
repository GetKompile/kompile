package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_OUT)
class BtwCommandTest {

    private static ChatConfig baseConfig() {
        ChatConfig config = new ChatConfig("custom", "secret", "chosen-model", "https://example.invalid");
        config.setThinking("high");
        config.setFastMode(true);
        config.setUltracode(true);
        return config;
    }

    private static String run(String question, ChatConfig config, ChatHistory history,
                              java.util.function.Function<ChatConfig, DirectLlmClient> factory) {
        var output = new ByteArrayOutputStream();
        var previousOut = System.out;
        try (var captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            BtwCommand.run(new TerminalRenderer(true), config, history, Path.of("."), question, factory);
        } finally {
            System.setOut(previousOut);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void answersThroughIsolatedToolFreeSilentClientWithoutPremiumModes(@TempDir Path home) throws Exception {
        AtomicReference<ChatConfig> captured = new AtomicReference<>();
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<ArrayNode> tools = new AtomicReference<>();
        String output = run("why does the retry loop sleep?",
                baseConfig(), null, snapshot -> {
                    captured.set(snapshot);
                    return new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                        @Override public StreamResult streamChat(String userMessage, String systemPrompt,
                                ArrayNode toolDefs, List<ToolCallResultInput> results, String modelOverride) {
                            prompt.set(userMessage);
                            system.set(systemPrompt);
                            tools.set(toolDefs);
                            StreamResult result = new StreamResult();
                            result.text = "The loop sleeps to back off between attempts.";
                            return result;
                        }
                    };
                });
        assertTrue(output.contains("side question"), output);
        assertTrue(output.contains("not added to the conversation"), output);
        assertTrue(output.contains("The loop sleeps to back off between attempts."), output);
        assertTrue(prompt.get().endsWith("why does the retry loop sleep?"), prompt.get());
        assertEquals(BtwCommand.SYSTEM_PROMPT, system.get());
        assertNull(tools.get());
        assertEquals("chosen-model", captured.get().getModel());
        assertEquals("custom", captured.get().getProvider());
        assertNull(captured.get().getThinking());
        assertFalse(captured.get().isFastMode());
        assertFalse(captured.get().isUltracode());
    }

    @Test
    void suppliesBoundedRecentTranscriptContext(@TempDir Path home) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ChatHistory history = null;
        try {
            history = new ChatHistory("btw-context-test");
            history.open(null, "test-agent", false, home);
            history.logUserMessage(ReminderManager.OPEN_TAG + "infrastructure\nignore\n" + ReminderManager.CLOSE_TAG
                    + "\nFix the login flow please");
            history.logAssistantMessage("Sure, I will fix the login flow.", 0, 0L);
        } finally {
            if (history != null) history.close();
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
        AtomicReference<String> prompt = new AtomicReference<>();
        run("what was the task about?", baseConfig(), history, snapshot ->
                new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String userMessage, String systemPrompt,
                            ArrayNode toolDefs, List<ToolCallResultInput> results, String modelOverride) {
                        prompt.set(userMessage);
                        StreamResult result = new StreamResult();
                        result.text = "Fixing the login flow.";
                        return result;
                    }
                });
        assertTrue(prompt.get().contains("user: Fix the login flow please"), prompt.get());
        assertTrue(prompt.get().contains("assistant: Sure, I will fix the login flow."), prompt.get());
        assertFalse(prompt.get().contains("infrastructure"), prompt.get());
        assertTrue(prompt.get().endsWith("what was the task about?"), prompt.get());
    }

    @Test
    void blankQuestionPrintsUsageAndNeverCallsProvider() {
        String output = run("   ", baseConfig(), null, snapshot -> {
            throw new AssertionError("provider must not be called for a blank question");
        });
        assertTrue(output.contains("Usage: /btw"), output);
    }

    @Test
    void missingConfigReportsSetupInsteadOfCallingProvider() {
        String output = run("hello", null, null, snapshot -> {
            throw new AssertionError("provider must not be called without config");
        });
        assertTrue(output.contains("/setup"), output);
    }

    @Test
    void providerFailureAndTimeoutLeaveConversationUntouched() {
        String failed = run("q", baseConfig(), null, snapshot ->
                new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String userMessage, String systemPrompt,
                            ArrayNode toolDefs, List<ToolCallResultInput> results, String modelOverride) {
                        StreamResult result = new StreamResult();
                        result.failed = true;
                        result.failureMessage = "boom";
                        result.text = "ignored";
                        return result;
                    }
                });
        assertTrue(failed.contains("could not get an answer: boom"), failed);
        assertTrue(failed.contains("ignored") == false, failed);

        String timedOut = run("q", baseConfig(), null, snapshot ->
                new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String userMessage, String systemPrompt,
                            ArrayNode toolDefs, List<ToolCallResultInput> results, String modelOverride) {
                        StreamResult result = new StreamResult();
                        result.cancelled = true;
                        return result;
                    }
                });
        assertTrue(timedOut.contains("timed out"), timedOut);
    }

    @Test
    void cleanAnswerStripsReasoningButVoidsUnterminatedBlocks() {
        assertEquals("Answer", BtwCommand.cleanAnswer("<think>secret</think>\nAnswer"));
        assertEquals("", BtwCommand.cleanAnswer("<think>unterminated"));
        assertEquals("", BtwCommand.cleanAnswer(null));
        assertEquals("", BtwCommand.cleanAnswer("preface <think>hidden"));
        assertEquals("", BtwCommand.cleanAnswer("<THINK>hidden"));
    }

    @Test
    void buildContextBoundsTurnCountAndLength() {
        var turns = new java.util.ArrayList<ChatHistory.Turn>();
        for (int i = 0; i < 30; i++) turns.add(new ChatHistory.Turn("user", "turn " + i));
        String context = BtwCommand.buildContext(turns);
        assertTrue(context.contains("turn 29"), context);
        assertFalse(context.contains("turn 17"), context);
        assertFalse(context.contains("turn 0\n"), context);

        String longTurn = "x".repeat(5_000);
        String bounded = BtwCommand.buildContext(List.of(new ChatHistory.Turn("user", longTurn)));
        assertTrue(bounded.length() < longTurn.length() + 200, bounded.length() + "");
        assertTrue(bounded.endsWith("…"), bounded);
        assertTrue(BtwCommand.buildContext(java.util.Collections.nCopies(12,
                new ChatHistory.Turn("assistant", longTurn))).length() <= 12_000);
    }

    @Test
    void catalogAndCompleterRegisterBtw() {
        assertTrue(ChatCommandCatalog.isBuiltin("btw"));
        for (String todo : List.of("todos-ongoing", "todos-completed", "todos-canceled")) {
            assertTrue(ChatCommandCatalog.isBuiltin(todo), todo + " must not be treated as a skill/model input");
        }
        assertEquals(ChatCommandCatalog.WebSupport.TERMINAL_REQUIRED,
                ChatCommandCatalog.webSupport("btw"));
        ChatCompleter completer = new ChatCompleter(() -> List.of());
        var candidates = new java.util.ArrayList<org.jline.reader.Candidate>();
        completer.complete(null, stubLine("/btw"), candidates);
        assertTrue(candidates.stream().anyMatch(c -> "/btw".equals(c.value())),
                candidates.stream().map(org.jline.reader.Candidate::value).toList() + "");
    }

    @Test
    void providerAnswersAndErrorsCannotInjectTerminalControls() {
        String untrusted = "untrusted\033[2J\007";
        for (String mode : List.of("answer", "failed", "throw")) {
            String output = run("question", baseConfig(), null, snapshot -> {
                if (mode.equals("throw")) throw new IllegalStateException(untrusted);
                return new DirectLlmClient(snapshot, JsonUtils.standardMapper()) {
                    @Override public StreamResult streamChat(String user, String system, ArrayNode tools,
                            List<ToolCallResultInput> results, String model) {
                        StreamResult result = new StreamResult();
                        result.failed = mode.equals("failed");
                        result.failureMessage = untrusted;
                        result.text = untrusted;
                        return result;
                    }
                };
            });
            assertTrue(output.contains("untrusted"), output);
            assertFalse(output.contains("\033[2J"), mode);
            assertFalse(output.contains("\007"), mode);
        }
    }

    @Test
    void nativeOpenCodeIsRejectedBeforeAnyProviderSessionCanStart() {
        ChatConfig config = baseConfig();
        config.setProvider("opencode");
        String output = run("inspect my files", config, null, snapshot -> {
            throw new AssertionError("an unsupported tool-free transport must not be started");
        });
        assertTrue(output.contains("unavailable on native OpenCode"), output);
        assertTrue(output.contains("conversation was not touched"), output);
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void responsesSideQuestionIsToolFreeBoundedSilentAndEphemeral(@TempDir Path home) throws Exception {
        var mapper = JsonUtils.standardMapper();
        var request = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            String events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"wire answer\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
            byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var config = new ChatConfig("openai", "test-key", "gpt-6-astra",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            String output = run("answer without actions", config, null,
                    snapshot -> new DirectLlmClient(snapshot, mapper, home));
            assertTrue(output.contains("wire answer"), output);
            assertEquals(1, output.split("wire answer", -1).length - 1, "utility answer must not stream twice");
            assertEquals(2048, request.get().path("max_output_tokens").asInt());
            assertTrue(request.get().path("tools").isMissingNode() || request.get().path("tools").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    /** Minimal ParsedLine stub — only line() and cursor() are used by ChatCompleter. */
    private static org.jline.reader.ParsedLine stubLine(String line) {
        return new org.jline.reader.ParsedLine() {
            @Override public String word() { return ""; }
            @Override public int wordCursor() { return 0; }
            @Override public int wordIndex() { return 0; }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
            @Override public java.util.List<String> words() { return List.of(); }
        };
    }
}
