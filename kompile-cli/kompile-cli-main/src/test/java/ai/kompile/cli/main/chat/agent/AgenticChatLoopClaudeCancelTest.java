package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pressing Esc on the Claude Code route interrupts the turn and nothing more:
 * Claude Code keeps running with its session and the background tasks it
 * started, and the next message continues that session without Kompile
 * restoring the conversation into it again. Drives the real AgenticChatLoop and
 * DirectLlmClient against a fake {@code claude}. The message that is cancelled
 * starts a background task, streams some text, then runs a 30-second tool
 * until the cancel interrupts it.
 */
@TemporaryUserHome
class AgenticChatLoopClaudeCancelTest {
    private static final String RESTORED = "[Earlier conversation restored by Kompile";

    @TempDir Path directory;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void captureOutput() {
        ChatCompleter.setContentOutput(lines::add);
    }

    @AfterEach
    void releaseOutput() {
        ChatCompleter.setContentOutput(null);
        ChatCompleter.setTranscriptBlockOutput(null);
        ChatCompleter.setActivity(null);
    }

    @Test
    void escapeInterruptsTheTurnButKeepsClaudeCodeAndItsTasksRunning() throws Exception {
        FakeClaudeCode fake = fake(2);
        try (Chat chat = new Chat("claude-cancel-running", fake, List.of())) {
            assertEquals("done", chat.send("first question").strip(), String.valueOf(lines));

            String cancelled = chat.sendAndEscape(fake, "start the task, then run the tool");

            assertTrue(cancelled.contains("working"), "text streamed before Esc is kept: " + cancelled);
            ToolResult interrupted = chat.toolResults.get("tool-run");
            assertNotNull(interrupted, "the running tool's card is closed: " + chat.toolResults);
            assertTrue(interrupted.isError());
            assertEquals(AgenticChatLoop.DEFAULT_STOP_LABEL, interrupted.getOutput());
            assertTrue(alive(fake.path("claude.pid")), "Esc must not stop Claude Code\n" + lines);
            assertTrue(alive(fake.path("task.pid")),
                    "Esc must not stop the background task Claude Code runs\n" + lines);
            assertTrue(fake.awaitControl("interrupt").path("cancel_queued").asBoolean());
            fake.assertToolStopped();

            assertEquals("done", chat.send("what did the task find?").strip(), String.valueOf(lines));
            assertEquals(1, fake.argv().size(),
                    "one Claude Code process serves the whole chat: " + fake.argv());
            List<String> messages = fake.messages();
            assertEquals(3, messages.size(), String.valueOf(messages));
            assertFalse(messages.get(2).contains(RESTORED),
                    "the session still holds the conversation\n" + messages.get(2));
        } finally {
            stopTask(fake.path("task.pid"));
        }
    }

    @Test
    void aCancelledRestoredTurnLeavesTheEarlierConversationInTheSession() throws Exception {
        FakeClaudeCode fake = fake(1);
        try (Chat chat = new Chat("claude-cancel-restored", fake, List.of(
                new ChatHistory.Turn("user", "earlier question"),
                new ChatHistory.Turn("assistant", "earlier answer")))) {
            chat.sendAndEscape(fake, "start the task, then run the tool");
            String seeded = fake.messages().get(0);
            assertTrue(seeded.contains(RESTORED) && seeded.contains("earlier answer"),
                    "the first message restores the earlier conversation\n" + seeded);

            assertEquals("done", chat.send("what did the task find?").strip(), String.valueOf(lines));
            assertEquals(1, fake.argv().size(), String.valueOf(fake.argv()));
            String next = fake.messages().get(1);
            assertFalse(next.contains(RESTORED) || next.contains("earlier answer"),
                    "the earlier conversation is not restored into the session twice\n" + next);
        } finally {
            stopTask(fake.path("task.pid"));
        }
    }

    /**
     * A fake that records its pid. The message numbered {@code cancelled} starts a
     * background task, streams "working" and runs a Bash tool; every other one is
     * answered "done".
     */
    private FakeClaudeCode fake(int cancelled) throws Exception {
        return new FakeClaudeCode(directory.resolve("claude"), """
                ANSWER=done
                startup() {
                  echo $$ > "$DIR/claude.pid"
                }
                turn() {
                  if [ "$1" != @N@ ]; then say_init; say_text "$ANSWER"; say_result; return; fi
                  say_init
                  sleep 30 > /dev/null 2>&1 &
                  echo $! > "$DIR/task.pid"
                  say_text working
                  emit '{"type":"assistant","message":{"id":"msg-run","content":[{"type":"tool_use","id":"tool-run","name":"Bash","input":{"command":"sleep 30"}}]}}'
                  start_tool
                }
                """.replace("@N@", Integer.toString(cancelled)));
    }

    private static Optional<ProcessHandle> process(Path pidFile) throws Exception {
        if (!Files.exists(pidFile)) return Optional.empty();
        return ProcessHandle.of(Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip()));
    }

    private static boolean alive(Path pidFile) throws Exception {
        return process(pidFile).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Stopping Claude Code at the end ends its task too; this covers a failed test. */
    private static void stopTask(Path pidFile) throws Exception {
        process(pidFile)
                .filter(task -> task.info().command().map(c -> c.endsWith("sleep")).orElse(false))
                .ifPresent(ProcessHandle::destroyForcibly);
    }

    /** One chat on the Claude subscription route, its Claude Code turns routed to the fake. */
    private final class Chat implements AutoCloseable {
        private final String session;
        private final DirectLlmClient client;
        private final AgenticChatLoop loop;
        private final AtomicBoolean cancel = new AtomicBoolean();
        /** How each of Claude Code's tool cards ended, by call id. */
        final Map<String, ToolResult> toolResults = new ConcurrentHashMap<>();

        Chat(String session, FakeClaudeCode fake, List<ChatHistory.Turn> earlier) throws Exception {
            this.session = session;
            var mapper = JsonUtils.standardMapper();
            ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
            config.setAuthenticationMethod("oauth");
            config.setDefaultMemory(false);
            config.setContextWindowTokens(200_000);
            config.setMaxOutputTokens(4_096);
            Path project = Files.createDirectories(directory.resolve("project"));
            client = new DirectLlmClient(config, mapper, project);
            loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, null);
            loop.configureConversationSession(session);
            if (!earlier.isEmpty()) loop.restoreHistory(earlier);
            loop.setCancelSignal(cancel);
            loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
                @Override
                public void onToolStart(String callId, String toolName, String rawInput) { }

                @Override
                public void onToolComplete(String callId, String toolName, String rawInput, ToolResult result) {
                    toolResults.put(callId, result);
                }
            });
            // Restoring a conversation resets the transport, so install after it.
            fake.install(client, project, null);
        }

        String send(String message) {
            return loop.chat(message, session, "coder", "default", false);
        }

        /** Send a message and press Esc once Claude Code runs the tool. */
        String sendAndEscape(FakeClaudeCode fake, String message) throws Exception {
            Object transport = transport();
            Thread escape = new Thread(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try {
                    while (!Files.exists(fake.path("tool.pid")) && System.nanoTime() < deadline) {
                        Thread.sleep(20);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                loop.cancelActiveTurn();
            }, "esc");
            escape.setDaemon(true);
            escape.start();
            String reply = send(message);
            escape.join();
            // Checked before any later message: a replaced transport would start the real claude.
            assertSame(transport, transport(), "Esc must keep the Claude Code transport\n" + lines);
            cancel.set(false);
            return reply;
        }

        private Object transport() throws Exception {
            Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
            field.setAccessible(true);
            return field.get(client);
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }
}
