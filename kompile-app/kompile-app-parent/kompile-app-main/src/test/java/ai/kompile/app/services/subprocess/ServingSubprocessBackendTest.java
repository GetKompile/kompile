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
package ai.kompile.app.services.subprocess;

import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for exact-model routing and lifecycle serialization in the serving lane.
 * No subprocess or model is started by these tests.
 */
class ServingSubprocessBackendTest {

    @Test
    void matchesOnlyConfirmedActiveModel_notMerelyConfiguredModel() {
        ServingSubprocessLauncher launcher = mock(ServingSubprocessLauncher.class);
        when(launcher.getConfiguredModelId()).thenReturn("requested-but-not-loaded");
        when(launcher.getActiveModelId()).thenReturn("confirmed-active");

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        backend.launcher = launcher;

        assertTrue(backend.matchesModel("confirmed-active"));
        assertFalse(backend.matchesModel("requested-but-not-loaded"));
        assertFalse(backend.matchesModel("  "));
        verify(launcher, never()).getConfiguredModelId();
    }

    @Test
    void generateForModelDelegatesToAtomicModelBoundLauncherOperation() throws Exception {
        ServingSubprocessLauncher launcher = mock(ServingSubprocessLauncher.class);
        when(launcher.generateForModel("lfm2.5-1.2b-instruct", "prompt"))
                .thenReturn("{\"finishReason\":\"stop\",\"generatedText\":\"answer\"}");

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        backend.launcher = launcher;

        assertEquals("answer", backend.generateForModel("lfm2.5-1.2b-instruct", "prompt"));
        verify(launcher).generateForModel("lfm2.5-1.2b-instruct", "prompt");
        verify(launcher, never()).generate("prompt");
    }

    @Test
    void requestTokenBudgetUsesAtomicModelBoundLauncherOperation() throws Exception {
        ServingSubprocessLauncher launcher = mock(ServingSubprocessLauncher.class);
        when(launcher.generateForModel("lfm2.5-1.2b-instruct", "prompt", 1536))
                .thenReturn("{\"finishReason\":\"stop\",\"generatedText\":\"answer\"}");

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        backend.launcher = launcher;

        assertEquals("answer",
                backend.generateForModel("lfm2.5-1.2b-instruct", "prompt", 1536));
        verify(launcher).generateForModel("lfm2.5-1.2b-instruct", "prompt", 1536);
        verify(launcher, never()).generateForModel("lfm2.5-1.2b-instruct", "prompt");
    }

    @Test
    void structuredChatPreservesParsedCallsAcrossSubprocessBridge() throws Exception {
        ServingSubprocessLauncher launcher = mock(ServingSubprocessLauncher.class);
        StructuredChatLanguageModel.Request request = new StructuredChatLanguageModel.Request(
                List.of(new StructuredChatLanguageModel.Message("user", "source")),
                List.of(new StructuredChatLanguageModel.Tool(
                        "submit_graph_delta", "submit", Map.of("type", "object"))));
        when(launcher.generateChatForModel(
                "lfm2.5-1.2b-instruct", request, 256))
                .thenReturn("{\"finishReason\":\"completed\",\"rawText\":\"<native>\","
                        + "\"content\":\"\",\"reasoningContent\":\"inspect source\","
                        + "\"outputBlocks\":[{\"type\":\"think\","
                        + "\"content\":\"inspect source\"},{\"type\":\"analysis\","
                        + "\"content\":\"cite source\"}],\"toolCalls\":[{\"id\":\"call-1\","
                        + "\"name\":\"submit_graph_delta\",\"arguments\":{\"entities\":[],"
                        + "\"relations\":[]}}],\"parseErrors\":[]}");

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        backend.launcher = launcher;

        StructuredChatLanguageModel.Response response = backend.generateChatForModel(
                "lfm2.5-1.2b-instruct", request, 256);

        assertTrue(backend.supportsStructuredChat());
        assertEquals("<native>", response.rawText());
        assertEquals("", response.content());
        assertEquals("inspect source", response.reasoningContent());
        assertEquals(2, response.outputBlocks().size());
        assertEquals("think", response.outputBlocks().get(0).type());
        assertEquals("analysis", response.outputBlocks().get(1).type());
        assertEquals("submit_graph_delta", response.toolCalls().get(0).name());
        verify(launcher).generateChatForModel(
                "lfm2.5-1.2b-instruct", request, 256);
        verify(launcher, never()).generateForModel(
                "lfm2.5-1.2b-instruct", "source", 256);
    }

    @Test
    void unexpectedChildExitClearsPublishedServingStateAndCanBeCleanedUp() {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        Process exited = mock(Process.class);
        when(exited.exitValue()).thenReturn(137);
        launcher.process = exited;
        launcher.activeModelId = "lfm2.5-1.2b-instruct";
        launcher.running.set(true);

        launcher.handleProcessExit(exited);

        assertFalse(launcher.isRunning());
        assertNull(launcher.getActiveModelId());
        launcher.stop();
        assertNull(launcher.process);
    }

    @Test
    void modelLoadHoldsLifecycleMonitorAcrossChildStartup() {
        AtomicBoolean held = new AtomicBoolean();
        IOException intercepted = new IOException("test startup intercepted");
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher() {
            @Override
            public void start(String modelId, String modelPath, String tokenizerPath) throws IOException {
                // Deliberately not synchronized: loadModel itself must own the monitor.
                held.set(Thread.holdsLock(this));
                throw intercepted;
            }
        };

        assertSame(intercepted, assertThrows(IOException.class,
                () -> launcher.loadModel("model", "/unused", Map.of())));
        assertTrue(held.get(), "model loading must serialize child startup");
    }

    @Test
    void modelBoundGenerationWaitsForLifecycleMonitorAndRechecksIdentity() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        assertWaitsForModelSwap(launcher, () -> launcher.generateForModel("before-swap", "prompt"));
    }

    @Test
    void boundedGenerationWaitsForLifecycleMonitorAndRechecksIdentity() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        assertWaitsForModelSwap(launcher, () -> launcher.generateForModel("before-swap", "prompt", 256));
    }

    @Test
    void structuredGenerationWaitsForLifecycleMonitorAndRechecksIdentity() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        StructuredChatLanguageModel.Request request = new StructuredChatLanguageModel.Request(
                List.of(new StructuredChatLanguageModel.Message("user", "source")), List.of());
        assertWaitsForModelSwap(launcher,
                () -> launcher.generateChatForModel("before-swap", request, 256));
    }

    @Test
    void modelBoundGenerationHoldsLifecycleMonitorThroughRequest() {
        assertRequestHoldsLifecycleMonitor("/api/llm/generate", Map.of("prompt", "prompt"),
                launcher -> launcher.generateForModel("model", "prompt"));
    }

    @Test
    void boundedGenerationHoldsLifecycleMonitorThroughRequest() {
        assertRequestHoldsLifecycleMonitor("/api/llm/generate",
                Map.of("prompt", "prompt", "maxTokens", 256),
                launcher -> launcher.generateForModel("model", "prompt", 256));
    }

    @Test
    void structuredGenerationHoldsLifecycleMonitorThroughRequest() {
        StructuredChatLanguageModel.Request request = new StructuredChatLanguageModel.Request(
                List.of(new StructuredChatLanguageModel.Message("user", "source")), List.of());
        assertRequestHoldsLifecycleMonitor("/api/llm/chat", Map.of("request", request, "maxTokens", 256),
                launcher -> launcher.generateChatForModel("model", request, 256));
    }

    private static void assertRequestHoldsLifecycleMonitor(
            String expectedPath, Map<String, Object> expectedBody, ModelBoundRequest operation) {
        IOException intercepted = new IOException("test request intercepted before HTTP");
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher() {
            @Override
            String postJson(String path, Object body, Duration timeout) throws IOException {
                // Deliberately not synchronized: the model-bound operation must retain the monitor.
                assertTrue(Thread.holdsLock(this),
                        "generation must hold the lifecycle monitor through check-and-use");
                assertEquals(expectedPath, path);
                assertEquals(expectedBody, body);
                throw intercepted;
            }
        };
        launcher.activeModelId = "model";
        launcher.running.set(true);

        assertSame(intercepted, assertThrows(IOException.class, () -> operation.execute(launcher)));
    }

    @FunctionalInterface
    private interface ModelBoundRequest {
        void execute(ServingSubprocessLauncher launcher) throws IOException, InterruptedException;
    }

    private static void assertWaitsForModelSwap(ServingSubprocessLauncher launcher, Executable operation)
            throws Exception {
        launcher.activeModelId = "before-swap";
        // Leave running=false: even a broken serialization path cannot issue a network request.
        CountDownLatch attempting = new CountDownLatch(1);
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            attempting.countDown();
            try {
                operation.execute();
                outcome.complete(null);
            } catch (Throwable failure) {
                outcome.complete(failure);
            }
        }, "serving-model-swap-test");
        worker.setDaemon(true); // A failed bounded join must not keep the test JVM alive.
        try {
            synchronized (launcher) {
                worker.start();
                assertTrue(attempting.await(5, TimeUnit.SECONDS), "generation worker did not start");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (worker.getState() != Thread.State.BLOCKED && !outcome.isDone()
                        && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertEquals(Thread.State.BLOCKED, worker.getState(),
                        "generation must wait for the lifecycle monitor before checking model identity");
                launcher.activeModelId = "after-swap";
            }
        } finally {
            worker.join(5000);
            assertFalse(worker.isAlive(), "generation worker did not terminate after monitor release");
        }
        IllegalStateException failure = assertInstanceOf(IllegalStateException.class,
                outcome.get(5, TimeUnit.SECONDS));
        assertEquals("Requested serving model 'before-swap' is not active (active=after-swap)",
                failure.getMessage());
    }
}
