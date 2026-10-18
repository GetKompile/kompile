package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ProductManagedWorkTrackerTest {
    @TempDir Path root;

    @Test void waitsForActualStopNotCancellationOrToolReturnAndRetainsIdentity() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        CountDownLatch cancelled = new CountDownLatch(1);
        tracker.register("pipeline", "owned", cancelled::countDown, stopped);
        assertFalse(tracker.cleanupConfirmed());
        CompletableFuture<Boolean> close = CompletableFuture.supplyAsync(
                () -> tracker.cancelAndAwait(Duration.ofSeconds(5)));
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertFalse(close.isDone(), "cancellation acknowledgement is not worker exit");
            assertFalse(tracker.cleanupConfirmed());
            assertThrows(IllegalStateException.class, () -> tracker.register(
                    "pipeline", "late", () -> {}, new CompletableFuture<>()));
            stopped.complete(null); // worker finally + terminal publication, not Future.cancel
            assertTrue(close.get(2, TimeUnit.SECONDS));
            assertTrue(tracker.cleanupConfirmed());
            assertTrue(tracker.owns("pipeline", "owned"));
            assertFalse(tracker.owns("crawl", "owned"));
            assertFalse(tracker.owns("pipeline", "foreign"));
        } finally { stopped.complete(null); }
    }

    @Test void resistantWorkerAndCancelledStopSignalCannotConfirmCleanup() {
        ProductManagedWorkTracker resistant = new ProductManagedWorkTracker();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        resistant.register("crawl", "resistant", () -> {}, stopped);
        assertFalse(resistant.cancelAndAwait(Duration.ofMillis(30)));
        assertFalse(stopped.isCancelled(), "tracker must never cancel its stop evidence");
        assertFalse(resistant.cleanupConfirmed());
        stopped.complete(null);
        assertTrue(resistant.cancelAndAwait(Duration.ofSeconds(2)));

        ProductManagedWorkTracker unproved = new ProductManagedWorkTracker();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        unproved.register("crawl", "cancelled-future", () -> {}, cancelled);
        assertFalse(unproved.cancelAndAwait(Duration.ofMillis(30)));
        assertFalse(unproved.cleanupConfirmed());
    }

    @Test void blockedCancelHookAlsoRespectsCloseDeadline() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        tracker.register("crawl", "fenced", () -> {
            cancelEntered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, stopped);
        try {
            assertFalse(tracker.cancelAndAwait(Duration.ofMillis(30)));
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
            stopped.complete(null);
            assertFalse(tracker.cleanupConfirmed(), "cancel hook is still inside the publication fence");
        } finally { release.countDown(); stopped.complete(null); }
        assertTrue(tracker.cancelAndAwait(Duration.ofSeconds(2)));
    }

    @Test void executionViewsObserveTrackerButManagedWorkerIsNotAGenericToolCapability() throws Exception {
        ProductManagedWorkTracker tracker = new ProductManagedWorkTracker();
        ToolContext turn = new ToolContext("product", AgentConfig.builder("product")
                .enabledTools(Set.of("*")).build(), new PermissionService(), root, new ToolRegistry(new ObjectMapper()));
        turn.setAutoApproveAll(true);
        AtomicBoolean turnDone = new AtomicBoolean();
        turn.linkAbortCheck(turnDone::get);
        turn.linkProductManagedWork(tracker);
        ToolContext execution = turn.forkForToolExecution();
        assertSame(tracker, execution.getProductManagedWork());
        assertThrows(IllegalStateException.class, () -> turn.forkForManagedWork("crawl", "unregistered"));
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        tracker.register("crawl", "job", () -> {}, stopped);
        ToolContext worker = execution.forkForManagedWork("crawl", "job");
        turnDone.set(true);
        assertTrue(execution.isAborted(), "retained execution view is still revoked");
        assertFalse(worker.isAborted(), "registered work survives completion of its submitting turn");
        assertNull(worker.getToolRegistry());
        worker.setAutoApproveAll(true);
        assertThrows(ToolExecutionException.class, () -> worker.checkPermission("bash", "not a capability"));
        assertThrows(ToolExecutionException.class, () -> worker.forkForToolExecution().checkPermission("read", "still not a capability"));
        tracker.stopAdmission();
        assertTrue(worker.isAborted());
        stopped.complete(null);
        assertTrue(tracker.cleanupConfirmed());
    }
}
