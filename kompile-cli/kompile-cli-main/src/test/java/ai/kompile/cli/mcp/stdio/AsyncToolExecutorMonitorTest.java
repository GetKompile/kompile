package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AsyncToolExecutorMonitorTest {
    @Test
    void heldMonitorPublishesOnlyTheFinalResultAndNotifiesExactlyOnce() throws Exception {
        AsyncToolExecutor executor = new AsyncToolExecutor(null);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        List<AsyncToolExecutor.TaskStatus> notices = new CopyOnWriteArrayList<>();
        try {
            String id = executor.submit("task", () -> {
                release.await();
                return ToolResult.success("done", "child result", Map.of());
            }, status -> { notices.add(status); notified.countDown(); });
            assertEquals("running", executor.awaitCompletion(id, 1).status());
            assertTrue(notices.isEmpty(), "a hold timeout must not fake completion");
            release.countDown();
            var result = executor.awaitCompletion(id, 3000);
            assertEquals("completed", result.status());
            assertEquals("child result", result.result().getOutput());
            assertTrue(notified.await(3, TimeUnit.SECONDS));
            assertEquals(1, notices.size());
            assertEquals(id, notices.get(0).taskId());
            executor.awaitCompletion(id, 1);
            assertEquals(1, notices.size(), "reading a completed result must not notify again");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void failuresAndWorkerErrorsNotifyAndRetainTheirFinalErrorResult() throws Exception {
        AsyncToolExecutor executor = new AsyncToolExecutor(null);
        CountDownLatch notified = new CountDownLatch(3);
        List<AsyncToolExecutor.TaskStatus> notices = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<AsyncToolExecutor.TaskStatus> monitor = status -> {
            notices.add(status);
            notified.countDown();
        };
        try {
            String returnedError = executor.submit("task", () -> ToolResult.error("returned failure"), monitor);
            String thrownError = executor.submit("task", () -> { throw new IllegalStateException("thrown failure"); }, monitor);
            String workerError = executor.submit("task", () -> { throw new AssertionError("worker failure"); }, monitor);
            assertEquals(3, java.util.Set.of(returnedError, thrownError, workerError).size());
            for (String id : List.of(returnedError, thrownError, workerError)) {
                var result = executor.awaitCompletion(id, 3000);
                assertEquals("failed", result.status());
                assertNotNull(result.result());
                assertTrue(result.result().isError());
                assertTrue(result.result().getOutput().contains("failure"));
            }
            assertTrue(notified.await(3, TimeUnit.SECONDS));
            assertEquals(3, notices.size());
            assertTrue(notices.stream().allMatch(status -> status.status().equals("failed")));
        } finally {
            executor.shutdown();
        }
    }
}
