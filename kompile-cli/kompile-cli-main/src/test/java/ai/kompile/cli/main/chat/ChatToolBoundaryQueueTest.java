package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
@TemporaryUserHome
class ChatToolBoundaryQueueTest {
    @TempDir Path directory;

    private static <T> T field(ChatRepl repl, String name, Class<T> type) throws Exception {
        var field = ChatRepl.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(repl));
    }

    @Test
    void nativeInputIsReceivedAfterToolCompletionWithoutWaitingForAFinalResult() throws Exception {
        var fake = new FakeClaudeCode(directory, """
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    emit '{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"tool1","name":"Read","input":{"path":"x"}}]}}'
                    touch "$DIR/started"
                    while [ ! -e "$DIR/complete" ]; do sleep 0.01; done
                    emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool1","content":"done"}]}}'
                    # Deliberately no result: queued input must arrive within this turn.
                  else
                    say_text "received new direction"
                    say_result
                  fi
                }
                """);
        var config = new ChatConfig("anthropic", null, "claude-sonnet-4-6", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        try (var repl = new ChatRepl(null, null, "native-tool-boundary-" + System.nanoTime(),
                false, "default", false, config)) {
            fake.installBinary(repl.getDirectClient());
            repl.agenticLoop.setPerformanceHarness(null);
            var queue = field(repl, "messageQueue", MessageQueue.class);
            if (!repl.isAutoDequeueEnabled()) field(repl, "queueManager", MessageQueueManager.class).toggleAutoDequeue();
            repl.messageHandler.handleChatMessage("start");
            FakeClaudeCode.await("native tool started", () -> Files.exists(fake.path("started")));
            repl.messageHandler.handleChatMessage("new direction");
            assertEquals(1, queue.size());
            Files.writeString(fake.path("complete"), "complete");
            FakeClaudeCode.await("mid-turn input receipt", () -> fake.messages().size() == 2);
            FakeClaudeCode.await("queue acceptance", () -> queue.isEmpty());
            assertTrue(fake.messages().get(1).contains("new direction"));
            assertEquals(2, fake.messages().size(), "the follow-up must be delivered once");
        } finally {
            ChatCompleter.setActivity(null);
        }
    }

    @Test
    void nativeBoundaryPreservesManualQueueAndEditingPolicy() throws Exception {
        var config = new ChatConfig("custom", null, "queue-policy", "http://unused.invalid");
        config.setDefaultMemory(false);
        try (var repl = new ChatRepl(null, null, "native-policy-" + System.nanoTime(),
                false, "default", false, config)) {
            var queue = field(repl, "messageQueue", MessageQueue.class);
            var manager = field(repl, "queueManager", MessageQueueManager.class);
            var active = ChatMessageHandler.class.getDeclaredField("turnActive");
            active.setAccessible(true);
            ((AtomicBoolean) active.get(repl.messageHandler)).set(true);
            repl.setLlmBusy(true);
            if (repl.isAutoDequeueEnabled()) manager.toggleAutoDequeue();
            var queued = queue.enqueue("leave this pending");
            repl.messageHandler.setRunningTurnInjector((id, text) -> {
                fail("manual or editing input must not be sent at a tool boundary");
                return true;
            });
            repl.messageHandler.injectQueuedInputAtToolBoundary();
            assertEquals(queued.getId(), queue.peek().getId());
            assertTrue(queue.beginEdit(queued.getId()));
            manager.toggleAutoDequeue();
            repl.messageHandler.injectQueuedInputAtToolBoundary();
            assertEquals(queued.getId(), queue.peek().getId());
            assertEquals(MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING, queue.peek().getStatus());
            repl.setLlmBusy(false);
        } finally { ChatCompleter.setActivity(null); }
    }

    @Test
    void refusedOrDroppedNativeInputRestoresItsQueueIdentityAndOrder() throws Exception {
        var config = new ChatConfig("custom", null, "queue-test", "http://unused.invalid");
        config.setDefaultMemory(false);
        try (var repl = new ChatRepl(null, null, "native-rollback-" + System.nanoTime(),
                false, "default", false, config)) {
            var queue = field(repl, "messageQueue", MessageQueue.class);
            if (!repl.isAutoDequeueEnabled()) field(repl, "queueManager", MessageQueueManager.class).toggleAutoDequeue();
            var active = ChatMessageHandler.class.getDeclaredField("turnActive");
            active.setAccessible(true);
            ((AtomicBoolean) active.get(repl.messageHandler)).set(true);
            repl.setLlmBusy(true);
            var first = queue.enqueue("first");
            var second = queue.enqueue("second");
            List<String> ids = new ArrayList<>();
            repl.messageHandler.setRunningTurnInjector((id, text) -> { ids.add(id); return false; });
            repl.messageHandler.injectQueuedInputAtToolBoundary();
            assertEquals(List.of(first.getId(), second.getId()),
                    queue.getAll().stream().map(MessageQueue.QueuedMessage::getId).toList());
            repl.messageHandler.setRunningTurnInjector((id, text) -> { ids.add(id); return true; });
            repl.messageHandler.injectQueuedInputAtToolBoundary();
            repl.messageHandler.injectQueuedInputAtToolBoundary();
            assertEquals(2, ids.size(), "only one native delivery may be outstanding");
            assertEquals(second.getId(), queue.peek().getId());
            repl.messageHandler.injectionDropped(ids.get(1));
            assertEquals(List.of(first.getId(), second.getId()),
                    queue.getAll().stream().map(MessageQueue.QueuedMessage::getId).toList());
            repl.setLlmBusy(false);
        } finally {
            ChatCompleter.setActivity(null);
        }
    }
}
