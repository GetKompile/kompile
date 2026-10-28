package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StdioTaskCancellationTest {
    @TempDir Path workDir;

    private ToolContext context() {
        return new ToolContext("test", null, null, workDir, null);
    }

    private StdioTaskTool task(Factory factory) {
        return new StdioTaskTool(null, factory, JsonUtils.standardMapper(), null);
    }

    private StdioMultiTaskTool multi(Factory factory) {
        return new StdioMultiTaskTool(null, factory, JsonUtils.standardMapper(), workDir, null);
    }

    private static Map<String, Object> args(String prompt) {
        return Map.of("description", prompt, "prompt", prompt);
    }

    private static Map<String, Object> subtask(String prompt) {
        return Map.of("name", prompt, "prompt", prompt);
    }

    @Test
    void cancellationOnlyReachesTheOwningTaskFork() throws Exception {
        Factory factory = new Factory(2);
        ToolContext cancelled = context();
        ToolContext sibling = context();
        StdioTaskTool task = task(factory);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> task.execute(args("first"), cancelled));
            var second = executor.submit(() -> task.execute(args("second"), sibling));
            assertTrue(factory.started.await(5, TimeUnit.SECONDS));
            cancelled.abort();
            assertTrue(first.get(5, TimeUnit.SECONDS).isError());
            assertTrue(factory.runners.get("first").cancels.get() > 0);
            assertEquals(0, factory.runners.get("second").cancels.get());
            assertFalse(second.isDone());
            assertEquals(0, factory.cancels.get(), "never cancel the shared factory");
            factory.runners.get("second").release.countDown();
            assertFalse(second.get(5, TimeUnit.SECONDS).isError());
            assertTrue(factory.runners.values().stream().allMatch(r -> r.finished.getCount() == 0));
            int cancelCount = factory.runners.get("first").cancels.get();
            Thread.sleep(200);
            assertEquals(cancelCount, factory.runners.get("first").cancels.get(),
                    "cancelled execution must retire its watcher too");
        } finally {
            factory.releaseAll();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void multiTaskCancelsItsActiveForksButNotUnrelatedWork() throws Exception {
        Factory factory = new Factory(3);
        ToolContext batch = context();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var unrelated = executor.submit(() -> task(factory).execute(args("unrelated"), context()));
            var result = executor.submit(() -> multi(factory).execute(Map.of(
                    "description", "batch", "subtasks", List.of(
                            subtask("active-one"), subtask("active-two"))), batch));
            assertTrue(factory.started.await(5, TimeUnit.SECONDS));
            batch.abort();
            assertTrue(result.get(5, TimeUnit.SECONDS).isError());
            assertTrue(factory.runners.get("active-one").cancels.get() > 0);
            assertTrue(factory.runners.get("active-two").cancels.get() > 0);
            assertEquals(0, factory.runners.get("unrelated").cancels.get());
            assertEquals(0, factory.cancels.get());
            assertFalse(unrelated.isDone());
            factory.runners.get("unrelated").release.countDown();
            assertFalse(unrelated.get(5, TimeUnit.SECONDS).isError());
        } finally {
            factory.releaseAll();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preCancelledRequestsDoNotForkOrLaunch() {
        Factory factory = new Factory(0);
        ToolContext context = context();
        context.abort();
        assertTrue(task(factory).execute(args("unused"), context).isError());
        assertTrue(multi(factory).execute(Map.of("subtasks", List.of(
                subtask("one"), subtask("two"))), context).isError());
        assertEquals(0, factory.forks.get());
    }

    @Test
    void cancellationDuringForkPreventsRunnerLaunch() {
        Factory factory = new Factory(0);
        ToolContext context = context();
        factory.abortOnFork = context;
        assertTrue(task(factory).execute(args("unused"), context).isError());
        assertEquals(1, factory.forks.get());
        assertTrue(factory.runners.isEmpty());
        assertEquals(0, factory.cancels.get());
    }

    @Test
    void cancellationIsRetriedAcrossRunnerStartupReset() throws Exception {
        Factory factory = new Factory(1);
        ToolContext context = context();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> task(factory).execute(args("startup-reset"), context));
            assertTrue(factory.started.await(5, TimeUnit.SECONDS));
            context.abort();
            assertTrue(result.get(5, TimeUnit.SECONDS).isError());
            assertTrue(factory.runners.get("startup-reset").cancels.get() >= 2,
                    "a cancel before the managed runner is published must not be the last attempt");
        } finally {
            factory.releaseAll();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedAndFailedExecutionsRetireTheirWatchers() throws Exception {
        Factory factory = new Factory(2);
        ToolContext success = context();
        ToolContext failure = context();
        assertFalse(task(factory).execute(args("done"), success).isError());
        assertTrue(task(factory).execute(args("fail"), failure).isError());
        success.abort();
        failure.abort();
        // Give a leaked watcher several polling intervals to expose itself.
        Thread.sleep(200);
        assertEquals(0, factory.runners.get("done").cancels.get());
        assertEquals(0, factory.runners.get("fail").cancels.get());
    }

    @Test
    void closingWatchStopsItsThreadAndPreventsLateCancellation() throws Exception {
        Factory factory = new Factory(0);
        ToolContext context = context();
        var watch = new StdioTaskTool.CancellationWatch(context, factory);
        try {
            watch.close();
            watch.thread.join(5000);
            assertFalse(watch.thread.isAlive());
            context.abort();
            assertEquals(0, factory.cancels.get());
        } finally {
            watch.close();
        }
    }

    @Test
    void closeDoesNotWaitForACancellingRunnerToExit() throws Exception {
        ToolContext context = context();
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DirectSubagentRunnerStdio runner = new DirectSubagentRunnerStdio(workDir) {
            @Override public void cancel() {
                cancelling.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException ignored) { /* simulate slow process teardown */ }
                }
            }
        };
        var watch = new StdioTaskTool.CancellationWatch(context, runner);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            context.abort();
            assertTrue(cancelling.await(2, TimeUnit.SECONDS));
            closer.submit(watch::close).get(500, TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            watch.close();
            watch.thread.join(2000);
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(watch.thread.isAlive());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(8)
    void delayedInterruptCannotReachUnrelatedWorkOnARecycledExecutorThread() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch returning = new CountDownLatch(1);
        CountDownLatch capturedInterrupt = new CountDownLatch(1);
        CountDownLatch deliverInterrupt = new CountDownLatch(1);
        CountDownLatch unrelatedRelease = new CountDownLatch(1);
        var delayOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        DirectSubagentRunnerStdio runner = new DirectSubagentRunnerStdio(workDir) {
            @Override KompileNativeTaskRunner createNativeRunner() {
                return new KompileNativeTaskRunner(workDir, null, null, null, null) {
                    @Override String run(AgentConfig agent, String prompt,
                                         java.util.concurrent.atomic.AtomicBoolean cancelled) throws Exception {
                        running.countDown();
                        finish.await();
                        returning.countDown();
                        return "done";
                    }
                };
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(work -> new Thread(work) {
            @Override public void interrupt() {
                if (delayOnce.compareAndSet(true, false)) {
                    capturedInterrupt.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try { deliverInterrupt.await(); break; }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                super.interrupt();
            }
        });
        Thread cancelling = new Thread(runner::cancel);
        try {
            var first = executor.submit(() -> runner.executeNativeSubagent(null, "fixture"));
            assertTrue(running.await(2, TimeUnit.SECONDS));
            cancelling.start();
            assertTrue(capturedInterrupt.await(2, TimeUnit.SECONDS));
            finish.countDown();
            assertTrue(returning.await(2, TimeUnit.SECONDS));
            var unrelated = executor.submit(() -> {
                try { unrelatedRelease.await(); return Thread.currentThread().isInterrupted(); }
                catch (InterruptedException leaked) { return true; }
            });
            // Retirement and interrupt delivery are atomic: the worker cannot be
            // recycled while cancellation is paused after obtaining its target.
            assertThrows(java.util.concurrent.TimeoutException.class, () -> first.get(100, TimeUnit.MILLISECONDS));
            deliverInterrupt.countDown();
            cancelling.join(2000);
            assertEquals("done", first.get(2, TimeUnit.SECONDS));
            unrelatedRelease.countDown();
            assertFalse(unrelated.get(2, TimeUnit.SECONDS));
        } finally {
            finish.countDown();
            deliverInterrupt.countDown();
            unrelatedRelease.countDown();
            cancelling.join(2000);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private final class Factory extends DirectSubagentRunnerStdio {
        final CountDownLatch started;
        final Map<String, Child> runners = new ConcurrentHashMap<>();
        final AtomicInteger forks = new AtomicInteger();
        ToolContext abortOnFork;
        final AtomicInteger cancels = new AtomicInteger();

        Factory(int count) {
            super(StdioTaskCancellationTest.this.workDir);
            started = new CountDownLatch(count);
        }

        @Override DirectSubagentRunnerStdio forkForSubagent() {
            forks.incrementAndGet();
            if (abortOnFork != null) abortOnFork.abort();
            return new Child(this);
        }

        @Override public void cancel() { cancels.incrementAndGet(); }

        @Override public String runSubagent(AgentConfig agent, String prompt) {
            throw new AssertionError("shared factory must not execute a request");
        }

        void releaseAll() { runners.values().forEach(r -> r.release.countDown()); }
    }

    private final class Child extends DirectSubagentRunnerStdio {
        final Factory factory;
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        volatile String prompt;

        Child(Factory factory) {
            super(StdioTaskCancellationTest.this.workDir);
            this.factory = factory;
        }

        @Override public String runSubagent(AgentConfig agent, String prompt) throws Exception {
            this.prompt = prompt;
            factory.runners.put(prompt, this);
            factory.started.countDown();
            try {
                if (prompt.equals("fail")) throw new IllegalStateException("test failure");
                if (!prompt.equals("done") && !release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("runner never released: " + prompt);
                }
                return "completed";
            } finally {
                finished.countDown();
            }
        }

        @Override public void cancel() {
            int count = cancels.incrementAndGet();
            if (!"startup-reset".equals(prompt) || count >= 2) release.countDown();
        }
    }
}
