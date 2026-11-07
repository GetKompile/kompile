package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class StdioQuorumTaskSchedulingTest {
    @TempDir Path workDir;

    private static final Map<String, Object> QUORUM = Map.of(
            "description", "quorum fixture", "prompt", "same prompt", "agents", List.of("kompile", "kompile"));

    @Test
    void quorumHasNoWallClockLimitAndWaitsForEverySlowAgent() throws Exception {
        Factory factory = new Factory(2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> tool(factory).execute(QUORUM));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            Thread.sleep(500);
            assertFalse(pending.isDone(), "quorum_task must wait for slow agents like task does");
            factory.release.countDown();
            ToolResult result = pending.get(2, TimeUnit.SECONDS);
            assertFalse(result.isError(), result.getOutput());
            assertEquals(2, result.getMetadata().get("succeeded"));
            assertEquals(0, factory.childCancels.get());
            assertEquals(0, factory.factoryCancels.get());
        } finally {
            factory.release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptingQuorumCollectorCancelsEveryAgentWithoutCancellingFactory() throws Exception {
        Factory factory = new Factory(2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> pending = caller.submit(() -> tool(factory).execute(QUORUM));
            assertTrue(factory.started.await(2, TimeUnit.SECONDS));
            pending.cancel(true);
            assertTrue(factory.cancelled.await(2, TimeUnit.SECONDS), "each running agent must be cancelled");
            assertEquals(0, factory.factoryCancels.get());
        } finally {
            factory.release.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private StdioQuorumTaskTool tool(Factory factory) {
        return new StdioQuorumTaskTool(null, factory, JsonUtils.standardMapper(), workDir);
    }

    private final class Factory extends DirectSubagentRunnerStdio {
        final CountDownLatch started;
        final CountDownLatch cancelled;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger childCancels = new AtomicInteger();
        final AtomicInteger factoryCancels = new AtomicInteger();

        Factory(int count) {
            super(workDir);
            started = new CountDownLatch(count);
            cancelled = new CountDownLatch(count);
        }

        @Override DirectSubagentRunnerStdio forkForSubagent() {
            return new Child(this);
        }
        @Override public void cancel() { factoryCancels.incrementAndGet(); }
    }

    private final class Child extends DirectSubagentRunnerStdio {
        final Factory factory;
        final CountDownLatch childRelease = new CountDownLatch(1);
        private boolean cancelledOnce;
        Child(Factory factory) { super(workDir); this.factory = factory; }
        @Override public String runSubagent(AgentConfig agent, String prompt) throws Exception {
            factory.started.countDown();
            while (!factory.release.await(20, TimeUnit.MILLISECONDS)) {
                if (childRelease.await(0, TimeUnit.MILLISECONDS)) throw new InterruptedException("cancelled");
            }
            return prompt + " complete";
        }
        @Override public synchronized void cancel() {
            if (!cancelledOnce) {
                cancelledOnce = true;
                factory.childCancels.incrementAndGet();
                factory.cancelled.countDown();
            }
            childRelease.countDown();
        }
    }
}
