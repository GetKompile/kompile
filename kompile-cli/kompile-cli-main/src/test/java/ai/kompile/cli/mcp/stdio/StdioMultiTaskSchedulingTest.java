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

    private StdioMultiTaskTool tool(Factory factory, long milliseconds) {
        return new StdioMultiTaskTool(null, factory, JsonUtils.standardMapper(), workDir, null,
                TimeUnit.MILLISECONDS.toNanos(milliseconds));
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
        ToolResult result = tool(factory, 5000).execute(batch("one", "two", "three", "four"));
        assertFalse(result.isError(), result.getOutput());
        assertEquals(4, result.getMetadata().get("succeeded"));
        assertEquals(4, factory.forks.get());
        assertEquals(0, factory.factoryCancels.get());
    }

    @Test
    void stalledChildrenShareOneDeadlineAndReceiveExplicitCancellationEvenWithoutContext() throws Exception {
        Factory factory = new Factory(4);
        long start = System.nanoTime();
        ToolResult result = tool(factory, 300).execute(batch("stall-one", "stall-two", "stall-three", "stall-four"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(result.isError(), result.getOutput());
        assertEquals(4, result.getMetadata().get("timedOut"));
        assertEquals(0, result.getMetadata().get("succeeded"));
        assertEquals("", result.getMetadata().get("succeededSubtasks"));
        assertTrue(factory.cancelled.await(2, TimeUnit.SECONDS), "each timed-out runner must be cancelled");
        assertEquals(0, factory.factoryCancels.get());
        assertTrue(elapsedMs < 1000, "one 300 ms batch budget, not four independent waits: " + elapsedMs);
        System.out.println("multi_task scheduling fixture: four stalled children returned in " + elapsedMs + " ms");
    }

    @Test
    void fastResultBehindStalledFirstChildIsCountedAndNotCancelled() throws Exception {
        Factory factory = new Factory(3);
        ToolContext context = new ToolContext("batch", null, null, workDir, null);
        ToolResult result = tool(factory, 300).execute(batch("stall-first", "quick-second", "stall-third"), context);
        assertFalse(result.isError(), result.getOutput()); // partial success remains partial success
        assertEquals(1, result.getMetadata().get("succeeded"));
        assertEquals(2, result.getMetadata().get("timedOut"));
        assertEquals("quick-second", result.getMetadata().get("succeededSubtasks"));
        assertEquals(0, factory.children.get("quick-second").cancels.get());
        assertFalse(context.isAborted(), "a child's deadline must not abort the parent");
        assertTrue(factory.children.get("stall-first").cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(factory.children.get("stall-third").cancelled.await(2, TimeUnit.SECONDS));
    }

    @Test
    void interruptingBatchCollectorCancelsChildrenWithoutAbortingParentOrFactory() throws Exception {
        Factory factory = new Factory(2);
        ToolContext parent = new ToolContext("batch", null, null, workDir, null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> result = caller.submit(() -> tool(factory, 5000).execute(batch("stall-one", "stall-two"), parent));
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
    void stalledCancellationDoesNotBlockTheBatchDeadline() throws Exception {
        Factory factory = new Factory(2);
        factory.blockCancellation = true;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> result = caller.submit(() -> tool(factory, 200).execute(batch("stall-one", "stall-two")));
            ToolResult timedOut = result.get(2, TimeUnit.SECONDS);
            assertEquals(2, timedOut.getMetadata().get("timedOut"));
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
