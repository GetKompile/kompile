package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class StdioMultiTaskSchedulingTest {
    @TempDir Path workDir;

    private StdioMultiTaskTool tool(Factory factory) {
        return new StdioMultiTaskTool(null, factory, JsonUtils.standardMapper(), workDir, null);
    }

    private static Map<String, Object> task(String name) {
        return Map.of("name", name, "prompt", name, "agent", "kompile");
    }

    private static Map<String, Object> batch(String... names) {
        return Map.of("description", "scheduling fixture", "subtasks",
                java.util.Arrays.stream(names).map(StdioMultiTaskSchedulingTest::task).toList());
    }

    @Test
    void everyChildStartsBeforeAnyChildReturns() {
        Factory factory = new Factory(4);
        ToolResult result = tool(factory).execute(batch("one", "two", "three", "four"));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(4, result.getMetadata().get("succeeded"));
        assertEquals(4, factory.forks.get());
        assertEquals(0, factory.factoryCancels.get());
    }

    @Test
    void batchHasNoWallClockLimitAndWaitsForEverySlowChild() throws Exception {
        Factory factory = new Factory(3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> tool(factory).execute(batch("stall-one", "quick-two", "stall-three")));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            Thread.sleep(500);
            assertFalse(pending.isDone(), "multi_task must wait for slow children like task does");
            factory.children.values().forEach(child -> child.release.countDown());
            ToolResult result = pending.get(2, TimeUnit.SECONDS);
            assertFalse(result.isError(), result.getOutput());
            assertEquals(3, result.getMetadata().get("succeeded"));
            assertEquals(0, result.getMetadata().get("timedOut"));
            factory.children.values().forEach(child -> assertEquals(0, child.cancels.get()));
            assertEquals(0, factory.factoryCancels.get());
        } finally {
            factory.children.values().forEach(child -> child.release.countDown());
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void parentAbortCancelsStalledChildrenWithoutCancellingFactory() throws Exception {
        Factory factory = new Factory(2);
        ToolContext context = new ToolContext("batch", null, null, workDir, null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> tool(factory).execute(batch("stall-one", "stall-two"), context));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            context.abort();
            ToolResult result = pending.get(2, TimeUnit.SECONDS);
            assertTrue(result.isError(), result.getOutput());
            assertTrue(factory.cancelled.await(2, TimeUnit.SECONDS), "each unfinished runner must be cancelled");
            assertEquals(0, factory.factoryCancels.get());
        } finally {
            factory.children.values().forEach(child -> child.release.countDown());
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptingBatchCollectorCancelsChildrenWithoutAbortingParentOrFactory() throws Exception {
        Factory factory = new Factory(2);
        ToolContext parent = new ToolContext("batch", null, null, workDir, null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> result = caller.submit(() -> tool(factory).execute(batch("stall-one", "stall-two"), parent));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            result.cancel(true);
            assertTrue(factory.cancelled.await(2, TimeUnit.SECONDS));
            assertFalse(parent.isAborted());
            assertEquals(0, factory.factoryCancels.get());
        } finally {
            factory.children.values().forEach(child -> child.release.countDown());
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void stalledCancellationDoesNotBlockParentAbort() throws Exception {
        Factory factory = new Factory(2);
        factory.blockCancellation = true;
        ToolContext context = new ToolContext("batch", null, null, workDir, null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> result = caller.submit(() -> tool(factory).execute(batch("stall-one", "stall-two"), context));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            context.abort();
            ToolResult cancelled = result.get(2, TimeUnit.SECONDS);
            assertTrue(cancelled.isError(), cancelled.getOutput());
            assertTrue(factory.cancelled.await(2, TimeUnit.SECONDS));
        } finally {
            factory.allowCancellation.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private final class Factory extends DirectSubagentRunnerStdio {
        final CountDownLatch started;
        final CountDownLatch cancelled;
        final AtomicInteger forks = new AtomicInteger();
        final AtomicInteger factoryCancels = new AtomicInteger();
        final ConcurrentMap<String, Child> children = new ConcurrentHashMap<>();
        final CountDownLatch allowCancellation = new CountDownLatch(1);
        volatile boolean blockCancellation;

        Factory(int count) {
            super(workDir);
            started = new CountDownLatch(count);
            cancelled = new CountDownLatch(count);
        }

        @Override DirectSubagentRunnerStdio forkForSubagent() {
            forks.incrementAndGet();
            return new Child(this);
        }
        @Override public void cancel() { factoryCancels.incrementAndGet(); }
    }

    private final class Child extends DirectSubagentRunnerStdio {
        final Factory factory;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicBoolean firstCancel = new AtomicBoolean();
        Child(Factory factory) { super(workDir); this.factory = factory; }
        @Override public String runSubagent(AgentConfig agent, String prompt) throws Exception {
            factory.children.put(prompt, this);
            factory.started.countDown();
            assertTrue(factory.started.await(2, TimeUnit.SECONDS), "launches must not be serialized");
            if (prompt.startsWith("stall")) release.await();
            return prompt + " complete";
        }
        @Override public void cancel() {
            cancels.incrementAndGet();
            if (firstCancel.compareAndSet(false, true)) {
                cancelled.countDown();
                factory.cancelled.countDown();
            }
            if (factory.blockCancellation) {
                boolean done = false;
                while (!done) {
                    try { factory.allowCancellation.await(); done = true; }
                    catch (InterruptedException ignored) { /* deliberately uncooperative fixture */ }
                }
            }
            release.countDown();
        }
    }
}
