package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MessageQueueTest {
    private final String sessionId = "queue-dedup-test-" + UUID.randomUUID();
    private final MessageQueue queue = new MessageQueue(sessionId);

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(queueFile());
    }

    @Test
    void duplicateAdmissionPreservesIdentityOrderAndPersistence() throws Exception {
        var first = queue.enqueue("Reminder: run the tests");
        var second = queue.enqueue("Distinct follow-up");
        String persisted = Files.readString(queueFile());

        assertNull(queue.enqueue(first.getContent()));
        assertEquals(List.of(first, second), queue.getAll());
        assertEquals(persisted, Files.readString(queueFile()));
        assertEquals(List.of(first.getContent(), second.getContent()),
                new MessageQueue(sessionId).getAll().stream()
                        .map(MessageQueue.QueuedMessage::getContent).toList());
    }

    @Test
    void deduplicationIsExactAndOnlyAppliesWhileQueued() {
        var first = queue.enqueue("Reminder");
        assertNotNull(queue.enqueue("reminder"));
        assertNotNull(queue.enqueue("Reminder\n"));
        assertSame(first, queue.dequeue());
        assertNotNull(queue.enqueue("Reminder"));
        queue.clear();
        assertNotNull(queue.enqueue("Reminder"));
    }

    @Test
    void duplicateCannotBypassEditingLease() {
        var first = queue.enqueue("Reminder");
        assertTrue(queue.beginEdit(first.getId()));
        assertNull(queue.enqueue("Reminder"));
        assertNull(queue.dequeue());
        assertTrue(queue.cancelEdit(first.getId()));
        assertEquals(first.getId(), queue.dequeue().getId());
    }

    @Test
    void editingToExistingContentCoalescesWithoutBlockingQueue() {
        var first = queue.enqueue("Reminder");
        var second = queue.enqueue("Another reminder");
        assertTrue(queue.beginEdit(second.getId()));
        assertTrue(queue.update(second.getId(), first.getContent()));
        assertEquals(List.of(first), queue.getAll());
        assertSame(first, queue.dequeue());
    }

    @Test
    void rollbackKeepsOriginalHeadAndDropsDuplicateAddedDuringClaim() {
        var first = queue.enqueue("Reminder");
        var second = queue.enqueue("Distinct follow-up");
        assertSame(first, queue.dequeue());
        assertNotNull(queue.enqueue(first.getContent()));
        queue.requeueFirst(first);
        queue.requeueFirst(first);
        assertEquals(List.of(first, second), queue.getAll());
    }

    @Test
    void loadingOldQueueSilentlyDropsDuplicatesAndReleasesEditLease() throws Exception {
        var first = new MessageQueue.QueuedMessage("Reminder");
        first.setStatus(MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING);
        var duplicate = new MessageQueue.QueuedMessage("Reminder");
        var second = new MessageQueue.QueuedMessage("Distinct follow-up");
        Files.writeString(queueFile(), JsonUtils.standardMapper()
                .writeValueAsString(List.of(first, duplicate, second)));

        var restored = new MessageQueue(sessionId);
        assertEquals(2, restored.size());
        assertEquals(first.getId(), restored.dequeue().getId());
        assertEquals(second.getId(), restored.dequeue().getId());
    }

    @Test
    void concurrentProducersAdmitOnlyOneCopy() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<MessageQueue.QueuedMessage>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return queue.enqueue("Reminder");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS) != null) accepted++;
            }
            assertEquals(1, accepted);
            assertEquals(1, queue.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateQueueCommandsAndBusyPassthroughAreSilent() throws Exception {
        queue.enqueue("Reminder");
        // No renderer/metrics/repl needed: a dropped addition has no side effects.
        var manager = new MessageQueueManager(null, queue, null, null, null, null, null, true);
        var passthrough = new EmulatedPassthroughCommand();
        Field queueField = EmulatedPassthroughCommand.class.getDeclaredField("messageQueue");
        queueField.setAccessible(true);
        queueField.set(passthrough, queue);
        Field busy = EmulatedPassthroughCommand.class.getDeclaredField("agentBusy");
        busy.setAccessible(true);
        busy.set(passthrough, true);
        Method enqueue = EmulatedPassthroughCommand.class.getDeclaredMethod(
                "enqueueMessage", String.class, ChatSessionMetrics.class);
        enqueue.setAccessible(true);
        Method admit = EmulatedPassthroughCommand.class.getDeclaredMethod(
                "admitUserMessage", String.class, ChatHistory.class, ChatSessionMetrics.class);
        admit.setAccessible(true);
        var output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (var capture = new PrintStream(output)) {
            System.setOut(capture);
            manager.enqueueMessage("Reminder");
            enqueue.invoke(passthrough, "Reminder", null);
            assertEquals(false, admit.invoke(passthrough, "Reminder", null, null));
        } finally {
            System.setOut(original);
        }
        assertEquals("", output.toString());
        assertEquals(1, queue.size());
    }

    private Path queueFile() throws Exception {
        Field file = MessageQueue.class.getDeclaredField("queueFile");
        file.setAccessible(true);
        return (Path) file.get(queue);
    }
}
