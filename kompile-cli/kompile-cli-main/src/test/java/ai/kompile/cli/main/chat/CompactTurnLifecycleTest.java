package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the mid-session compact deadlock: /compact must never
 * run raw on the REPL reader thread. It has to go through the turn lifecycle
 * so that (a) a backgrounded turn (llmBusy=false, turnActive=true) still
 * blocks it, and (b) an accepted compaction registers a cancellable owner and
 * always releases the reservation so the session can continue.
 */
class CompactTurnLifecycleTest {

    @Test
    void maintenanceTurnIsRefusedWhileBackgroundedTurnStillOwnsTheSession() throws Exception {
        ChatConfig config = new ChatConfig(
                "custom", null, "compact-test",
                "http://127.0.0.1:1");
        ChatRepl repl = new ChatRepl(
                null, null, "compact-test", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);

        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        try {
            // Simulate a backgrounded turn: the busy flag is false (reader can
            // accept input) but the detached owner still holds the session.
            AtomicBoolean accepted = new AtomicBoolean(handler.dispatchMaintenanceTurn(() -> {
                ownerStarted.countDown();
                try {
                    // The owner must be interruptible while it works.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseOwner.countDown();
                }
            }, "simulated-backgrounded-turn"));
            assertTrue(accepted.get(), "first maintenance dispatch should be accepted");

            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS), "owner never started");
            // Emulate requestBackground(): reader sees idle, session stays owned.
            repl.setLlmBusy(false);

            AtomicBoolean secondAccepted = new AtomicBoolean(true);
            Thread contender = new Thread(() -> secondAccepted.set(
                    handler.dispatchMaintenanceTurn(() -> { }, "second-compact")));
            contender.start();
            contender.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(secondAccepted.get(),
                    "a second maintenance turn must be refused while the detached owner lives");

            // The refused contender must not have stolen or cleared ownership:
            // cancel must still reach the ORIGINAL owner.
            assertTrue(handler.requestCancel(), "cancel must reach the registered owner");
            assertTrue(releaseOwner.await(5, TimeUnit.SECONDS),
                    "owner was not released after cancel");
        } finally {
            releaseOwner.countDown();
        }
    }

    @Test
    void maintenanceTurnAlwaysReleasesTheReservationSoTheSessionContinues() throws Exception {
        ChatConfig config = new ChatConfig(
                "custom", null, "compact-test-2",
                "http://127.0.0.1:1");
        ChatRepl repl = new ChatRepl(
                null, null, "compact-test-2", false, "default", false, config);
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);

        CountDownLatch actionDone = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(handler.dispatchMaintenanceTurn(() -> {
            try {
                // Throwing must not leak the reservation either.
                throw new IllegalStateException("compaction backend blew up");
            } finally {
                actionDone.countDown();
            }
        }, "compact-dispatch"));
        assertTrue(accepted.get(), "idle session must accept a maintenance turn");

        assertTrue(actionDone.await(5, TimeUnit.SECONDS), "action never ran");
        boolean released = awaitCondition(
                () -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS);
        assertTrue(released,
                "a failed maintenance turn must still clear the llmBusy reservation");

        // The session must accept a new turn immediately after.
        CountDownLatch nextDone = new CountDownLatch(1);
        AtomicBoolean nextAccepted = new AtomicBoolean(handler.dispatchMaintenanceTurn(() -> {
            nextDone.countDown();
        }, "next-turn"));
        assertTrue(nextAccepted.get(), "session must continue after compaction finished");
        assertTrue(nextDone.await(5, TimeUnit.SECONDS), "follow-up turn never ran");
    }

    @Test
    void cancelKeyPreservesBlockingSubagentUntilDeleteTargetsIt() throws Exception {
        ChatRepl repl = new ChatRepl(
                null, null, "subagent-cancel-policy", false, "default", false,
                new ChatConfig("custom", null, "subagent-cancel-policy", "http://127.0.0.1:1"));
        ChatMessageHandler handler = field(repl, "messageHandler", ChatMessageHandler.class);
        AgenticChatLoop loop = field(repl, "agenticLoop", AgenticChatLoop.class);
        AtomicBoolean subagentInvocation = field(
                loop, "blockingSubagentInvocation", AtomicBoolean.class);
        BackgroundProcessManager processes = field(
                repl, "processManager", BackgroundProcessManager.class);
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);

        try {
            assertTrue(handler.dispatchMaintenanceTurn(() -> {
                ownerStarted.countDown();
                try {
                    releaseOwner.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    ownerInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }, "blocking-subagent-parent"));
            assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));

            subagentInvocation.set(true);
            assertFalse(repl.requestCancelFromInput(),
                    "Escape must not be an implicit subagent kill key");
            assertFalse(ownerInterrupted.await(200, TimeUnit.MILLISECONDS),
                    "the parent owner was interrupted while its subagent should continue");

            subagentInvocation.set(false);
            assertTrue(repl.requestCancelFromInput(),
                    "Escape should resume its normal main-turn cancellation role afterward");
            assertTrue(ownerInterrupted.await(5, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> !repl.isLlmBusy(), 5, TimeUnit.SECONDS),
                    "the cancelled parent owner did not release its reservation");
        } finally {
            subagentInvocation.set(false);
            releaseOwner.countDown();
            processes.close();
            ChatCompleter.setActivity(null);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static boolean awaitCondition(
            java.util.function.BooleanSupplier condition,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
