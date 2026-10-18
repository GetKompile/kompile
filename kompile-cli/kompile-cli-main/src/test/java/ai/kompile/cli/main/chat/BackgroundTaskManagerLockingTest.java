package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundTaskManagerLockingTest {
    @ParameterizedTest
    @ValueSource(strings = {"start", "background", "detach", "complete", "fail", "detachedFail"})
    void lifecycleListenersNeverHoldManagerMonitor(String transition) {
        BackgroundTaskManager manager = new BackgroundTaskManager();
        var task = manager.startTask("task MCP call");
        AtomicBoolean heldMonitor = new AtomicBoolean();
        AtomicInteger changes = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        manager.addChangeListener(() -> {
            heldMonitor.compareAndSet(false, Thread.holdsLock(manager));
            changes.incrementAndGet();
        });
        manager.addCompletionListener(finished -> {
            heldMonitor.compareAndSet(false, Thread.holdsLock(manager));
            assertSame(task, finished);
            completions.incrementAndGet();
        });

        switch (transition) {
            case "start" -> manager.startTask("next turn");
            case "background" -> assertSame(task, manager.requestBackground());
            case "detach" -> manager.detachTask(task);
            case "complete" -> manager.completeCurrentTask();
            case "fail" -> manager.failCurrentTask(new IllegalArgumentException("Invalid task argument"));
            case "detachedFail" -> manager.completeDetachedTask(task,
                    new IllegalArgumentException("Invalid task argument"));
            default -> fail("Unknown transition");
        }
        assertFalse(heldMonitor.get(), "UI callbacks must not run under the task manager monitor");
        assertEquals(1, changes.get());
        assertEquals(transition.equals("complete") || transition.equals("fail")
                || transition.equals("detachedFail") ? 1 : 0, completions.get());
    }

    @Test
    void failedTaskDoesNotBlockRenderingOrNextKeyboardTurn() throws Exception {
        BackgroundTaskManager manager = new BackgroundTaskManager();
        var failed = manager.startTask("task MCP call with invalid argument");
        Object drawLock = new Object();
        CountDownLatch listenerStarted = new CountDownLatch(1);
        manager.addChangeListener(() -> {
            listenerStarted.countDown();
            synchronized (drawLock) {
                // Models ChatRepl's activityRedraw -> tui.upsertDisplayBlock.
            }
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> failure;
            synchronized (drawLock) {
                failure = executor.submit(() -> manager.failCurrentTask(
                        new IllegalArgumentException("Invalid task argument")));
                assertTrue(listenerStarted.await(2, TimeUnit.SECONDS));
                // StatusBar queries this while the frame/keyboard widget owns drawLock.
                // The old code retained the manager monitor inside the blocked listener.
                var render = executor.submit(manager::isCurrentTaskBackgroundable);
                assertFalse(render.get(2, TimeUnit.SECONDS));
            }
            failure.get(2, TimeUnit.SECONDS);
            assertEquals(BackgroundTaskManager.BackgroundTask.BackgroundTaskStatus.FAILED,
                    failed.getStatus());
            assertNull(manager.getCurrentTask());
            var next = executor.submit(() -> manager.startTask("next keyboard turn"));
            assertSame(next.get(2, TimeUnit.SECONDS), manager.getCurrentTask());
            manager.completeCurrentTask();
            assertNull(manager.getCurrentTask());
        } finally {
            // Leaving drawLock above unblocks the listener even if an assertion failed.
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completionListenerCanStartSuccessorWithoutItBeingCleared() {
        BackgroundTaskManager manager = new BackgroundTaskManager();
        var failed = manager.startTask("invalid MCP task");
        manager.requestBackground();
        AtomicInteger completions = new AtomicInteger();
        manager.addCompletionListener(task -> {
            assertSame(failed, task);
            completions.incrementAndGet();
            manager.startTask("successor");
        });
        manager.failCurrentTask(new IllegalArgumentException("Invalid task argument"));
        assertEquals(1, completions.get());
        assertEquals("successor", manager.getCurrentTask().getDescription());
        assertEquals(java.util.List.of(failed), manager.drainNotifications());
    }
}
