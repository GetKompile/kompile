package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The judge weighs a Claude Code turn against what the turn did and what it was asked.
 * The tools Claude Code ran itself and the model requests it made count in the judge's
 * prompt. A turn Claude Code started by itself is judged against the background tasks
 * whose end set it off, not against the marker that shows the turn.
 */
@TemporaryUserHome
class AgenticChatLoopProviderJudgeMetricsTest {

    private static final String VERDICT = "{\"correctness\":5,\"completeness\":5,"
            + "\"design_quality\":null,\"thinking_coherence\":null,"
            + "\"reasoning\":\"The turn did what it was asked.\"}";

    /**
     * The first turn starts two background tasks. Once the test writes to the FIFO, one of
     * them ends, a task the session never saw fails, and Claude Code starts a turn.
     */
    private static final String TASKS_END_AFTER_THE_TURN = """
            turn() {
              say_init
              if [ "$1" = 1 ]; then
                emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-9","description":"Run the build","task_type":"local_bash"}'
                emit '{"type":"system","subtype":"task_started","task_id":"task-2","tool_use_id":"tool-10","description":"Watch the logs","task_type":"local_bash"}'
                mkfifo "$DIR/go"
                (
                  read -t 10 -r _ <> "$DIR/go"
                  emit '{"type":"system","subtype":"task_notification","task_id":"task-1","status":"completed","summary":"Build passed"}'
                  emit '{"type":"system","subtype":"task_notification","task_id":"task-3","status":"failed","summary":""}'
                  say_init
                  say_text 'the build passed'
                  say_result
                ) &
              fi
              say_text "$ANSWER"
              say_result
            }
            """;

    @TempDir
    Path directory;

    private final List<String> judgePrompts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void resetOutput() {
        ChatCompleter.setContentOutput(null);
        ChatCompleter.setTranscriptBlockOutput(null);
        ChatCompleter.setActivity(null);
    }

    @Test
    void theToolsClaudeCodeRanAndItsRequestsCountInTheJudgesPrompt() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        String session = "claude-judge-metrics";
        ChatSessionMetrics metrics = new ChatSessionMetrics(session);
        PerformanceHarness harness = harness(mapper, metrics);
        ChatCompleter.setContentOutput(lines::add);
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            AgenticChatLoop loop = loop(mapper, client, metrics, harness, session);
            FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), """
                    turn() { cat "$DIR/claude-stream.jsonl"; }
                    """);
            try (InputStream fixture = getClass().getResourceAsStream("claude-stream-mcp-tool.jsonl")) {
                assertNotNull(fixture, "fixture");
                Files.write(fake.path("claude-stream.jsonl"), fixture.readAllBytes());
            }
            fake.install(client, directory, "claude-native-session");

            loop.chat("read the notes", session, "coder", "default", false);
        } finally {
            harness.shutdown();
        }

        String prompt = judged("[USER REQUEST]\nread the notes\n");
        // The recorded turn makes two model requests around one Kompile tool Claude Code ran.
        assertTrue(prompt.contains("Steps taken: 2\n"), prompt);
        assertTrue(prompt.contains("Tool calls: 1 (0 errors)\n"), prompt);
        assertTrue(prompt.contains("Tools used: mcp__kompile__read 1\n"), prompt);
    }

    @Test
    void aTurnClaudeCodeStartedByItselfIsJudgedAgainstTheTasksThatEnded() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        String session = "claude-judge-follow-up";
        ChatSessionMetrics metrics = new ChatSessionMetrics(session);
        PerformanceHarness harness = harness(mapper, metrics);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();
        ChatCompleter.setContentOutput(lines::add);
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            AgenticChatLoop loop = loop(mapper, client, metrics, harness, session);
            FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), TASKS_END_AFTER_THE_TURN);
            fake.install(client, directory, null);
            client.setClaudeFollowUpListener(announced::add);

            loop.chat("build it", session, "coder", "default", false);
            String marker;
            // Opened for reading and writing, the FIFO holds the line until the fake reads it.
            try (FileChannel go = FileChannel.open(fake.path("go"), StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                go.write(ByteBuffer.wrap("go\n".getBytes(StandardCharsets.UTF_8)));
                marker = announced.poll(10, TimeUnit.SECONDS);
            }
            assertNotNull(marker, "the turn Claude Code started is announced: " + lines);
            String shown = loop.chat(marker, session, "coder", "default", false);
            assertTrue(shown.contains("the build passed"), shown + "\n" + lines);
        } finally {
            harness.shutdown();
        }

        String prompt = judged("the build passed");
        assertTrue(prompt.contains("[USER REQUEST]\nClaude Code started this turn by itself"), prompt);
        assertTrue(prompt.contains("\n- Background task \"Run the build\" completed: Build passed\n"), prompt);
        assertTrue(prompt.contains("\n- Background task task-3 failed\n"), prompt);
        // The turn itself carries no user text, but the judge still needs the ask that led
        // to the tasks it reports on: the "build it" request from the turn before it.
        assertTrue(prompt.contains(
                "The user's last request, which these tasks served:\nbuild it"), prompt);
        assertFalse(prompt.contains("[Claude Code follow-up"), prompt);
    }

    @Test
    void aNormalTurnsJudgedRequestIsUnchangedByFollowUpTracking() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        String session = "claude-judge-normal-turn";
        ChatSessionMetrics metrics = new ChatSessionMetrics(session);
        PerformanceHarness harness = harness(mapper, metrics);
        ChatCompleter.setContentOutput(lines::add);
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            AgenticChatLoop loop = loop(mapper, client, metrics, harness, session);
            FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), """
                    turn() { cat "$DIR/claude-stream.jsonl"; }
                    """);
            try (InputStream fixture = getClass().getResourceAsStream("claude-stream-mcp-tool.jsonl")) {
                assertNotNull(fixture, "fixture");
                Files.write(fake.path("claude-stream.jsonl"), fixture.readAllBytes());
            }
            fake.install(client, directory, "claude-native-session");

            loop.chat("read the notes", session, "coder", "default", false);
        } finally {
            harness.shutdown();
        }

        // A turn started by a real user message is judged against that message alone —
        // remembering it for a later follow-up must not append anything to it here.
        String prompt = judged("[USER REQUEST]\nread the notes\n");
        assertFalse(prompt.contains("The user's last request"), prompt);
    }

    /** The one turn verdict whose prompt contains {@code needle}. */
    private String judged(String needle) {
        synchronized (judgePrompts) {
            return judgePrompts.stream()
                    .filter(prompt -> prompt.contains("[AGENT OUTPUT TO EVALUATE]") && prompt.contains(needle))
                    .findFirst()
                    .orElseGet(() -> fail("no turn verdict mentions " + needle + ": " + judgePrompts));
        }
    }

    /** A harness whose judge records each prompt and finds the turn complete. */
    private PerformanceHarness harness(ObjectMapper mapper, ChatSessionMetrics metrics) {
        JudgeBackend recording = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                judgePrompts.add(userPrompt);
                return VERDICT;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        PerformanceHarness harness = new PerformanceHarness(
                null, claudeConfig(), mapper, new TerminalRenderer(false), metrics, null, recording);
        harness.getConfig().setEnabled(true);
        harness.setJudgeGlobalEnabled(true);
        harness.getConfig().setJudgeEnabled(true);
        harness.getConfig().setEscapeDetectionEnabled(false);
        harness.getConfig().setThinkingAnalysisEnabled(false);
        harness.getConfig().setAutoSwapEnabled(false);
        harness.getConfig().setPersistCrossSession(false);
        return harness;
    }

    private AgenticChatLoop loop(ObjectMapper mapper, DirectLlmClient client, ChatSessionMetrics metrics,
                                 PerformanceHarness harness, String session) {
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new PermissionService(), new AgentRegistry(), directory, client, null);
        loop.configureConversationSession(session);
        loop.setSessionMetrics(metrics);
        loop.setPerformanceHarness(harness);
        return loop;
    }

    private static ChatConfig claudeConfig() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        return config;
    }
}
