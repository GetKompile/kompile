/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DirectSubagentRunnerStdioTest {

    @TempDir
    Path tempDir;

    @Test
    void runSubagentUsesManagedRunnerAndCapturesOutput() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));

        String result = runner.runSubagent(AgentConfig.builder("codex").build(), "inspect managed tools");

        FakeManagedRunner fake = runner.fake;
        assertNotNull(fake, "subagent should create the managed passthrough runner");
        assertEquals("codex", fake.agentName);
        assertTrue(fake.injectMcpTools, "top-level delegated agents should receive MCP tool injection");
        assertTrue(fake.injectMcpToolsCalled, "MCP tool injection should be performed by the managed runner");
        assertTrue(fake.cleanupCalled, "managed runner cleanup should restore injected tools/skills");
        assertEquals("inspect managed tools", fake.message);
        assertEquals("1", fake.extraEnvironment.get("KOMPILE_SUBAGENT_DEPTH"));
        assertEquals("forked-value", fake.extraEnvironment.get("KOMPILE_TEST_FORK_ENV"));
        assertTrue(result.contains("Subagent 'codex' completed"));
        assertTrue(result.contains("managed-output"));
        assertTrue(result.contains("Full output"));
    }

    @Test
    void forkedRunnerStillCarriesManagedEnvironmentIntoManagedRunner() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));

        ManagedTestRunner fork = (ManagedTestRunner) runner.forkForSubagent();
        String result = fork.runSubagent(AgentConfig.builder("opencode").build(), "delegate checks");

        assertTrue(result.contains("managed-output"));
        assertEquals("forked-value", fork.fake.extraEnvironment.get("KOMPILE_TEST_FORK_ENV"));
    }

    @Test
    void cancelForwardsToCurrentManagedRunner() throws Exception {
        ManagedTestRunner runner = new ManagedTestRunner(tempDir);
        runner.blockRunMessage = true;

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.runSubagent(AgentConfig.builder("codex").build(), "long task");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(runner.runnerCreated.await(5, TimeUnit.SECONDS), "managed runner should be created");
        assertTrue(runner.fake.runStarted.await(5, TimeUnit.SECONDS), "managed runner should start its turn");

        runner.cancel();

        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(runner.fake.cancelCalled, "cancel should be forwarded to the managed runner");
        assertTrue(runner.fake.cleanupCalled, "cleanup should still run after cancellation");
    }

    static final class ManagedTestRunner extends DirectSubagentRunnerStdio {
        private final Path testWorkDir;
        final CountDownLatch runnerCreated = new CountDownLatch(1);
        volatile FakeManagedRunner fake;
        volatile boolean blockRunMessage;

        ManagedTestRunner(Path workDir) {
            super(workDir);
            this.testWorkDir = workDir;
        }

        @Override
        DirectSubagentRunnerStdio forkForSubagent() {
            ManagedTestRunner fork = new ManagedTestRunner(testWorkDir);
            fork.setExtraEnvironment(Map.of("KOMPILE_TEST_FORK_ENV", "forked-value"));
            return fork;
        }

        @Override
        SubprocessAgentRunner createManagedRunner(String agentName, boolean injectMcpTools) {
            fake = new FakeManagedRunner(agentName, testWorkDir, injectMcpTools, () -> blockRunMessage);
            runnerCreated.countDown();
            return fake;
        }
    }

    static final class FakeManagedRunner extends SubprocessAgentRunner {
        final String agentName;
        final boolean injectMcpTools;
        final java.util.function.BooleanSupplier blockRunMessage;
        final CountDownLatch runStarted = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile boolean injectMcpToolsCalled;
        volatile boolean cleanupCalled;
        volatile boolean cancelCalled;
        volatile String message;
        volatile Map<String, String> extraEnvironment = Map.of();
        volatile Consumer<String> outputConsumer;

        FakeManagedRunner(String agentName, Path workDir, boolean injectMcpTools,
                          java.util.function.BooleanSupplier blockRunMessage) {
            super(agentName, workDir.toString(), true, injectMcpTools, "", 0, null,
                    new TerminalRenderer(false), new AsciiRenderer(new TerminalRenderer(false), 100));
            this.agentName = agentName;
            this.injectMcpTools = injectMcpTools;
            this.blockRunMessage = blockRunMessage;
        }

        @Override
        public void setExtraEnvironment(Map<String, String> extraEnvironment) {
            this.extraEnvironment = extraEnvironment;
        }

        @Override
        public void setOutputConsumer(Consumer<String> outputConsumer) {
            this.outputConsumer = outputConsumer;
        }

        @Override
        public void setInputProvider(Function<String, String> inputProvider) {
            // No interactive input in fake runs.
        }

        @Override
        public void injectMcpTools() {
            injectMcpToolsCalled = true;
        }

        @Override
        public String runMessage(String message, ChatHistory history, ChatSessionMetrics metrics) {
            this.message = message;
            runStarted.countDown();
            if (blockRunMessage.getAsBoolean()) {
                try {
                    cancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (outputConsumer != null) {
                outputConsumer.accept("managed-output");
            }
            return "managed-output";
        }

        @Override
        public void cancel() {
            cancelCalled = true;
            cancelled.countDown();
        }

        @Override
        public void cleanup() {
            cleanupCalled = true;
        }
    }
}
