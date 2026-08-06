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

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Modifier;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        ReflectionTestUtils.setField(backend, "launcher", launcher);

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
        ReflectionTestUtils.setField(backend, "launcher", launcher);

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
        ReflectionTestUtils.setField(backend, "launcher", launcher);

        assertEquals("answer",
                backend.generateForModel("lfm2.5-1.2b-instruct", "prompt", 1536));
        verify(launcher).generateForModel("lfm2.5-1.2b-instruct", "prompt", 1536);
        verify(launcher, never()).generateForModel("lfm2.5-1.2b-instruct", "prompt");
    }

    @Test
    void unexpectedChildExitClearsPublishedServingStateAndCanBeCleanedUp() {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        Process exited = mock(Process.class);
        when(exited.exitValue()).thenReturn(137);
        ReflectionTestUtils.setField(launcher, "process", exited);
        ReflectionTestUtils.setField(launcher, "activeModelId", "lfm2.5-1.2b-instruct");
        ((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(launcher, "running")).set(true);

        launcher.handleProcessExit(exited);

        assertFalse(launcher.isRunning());
        assertNull(launcher.getActiveModelId());
        launcher.stop();
        assertNull(ReflectionTestUtils.getField(launcher, "process"));
    }

    @Test
    void modelSwapAndModelBoundGenerationShareTheLifecycleMonitor() throws Exception {
        int loadModifiers = ServingSubprocessLauncher.class
                .getDeclaredMethod("loadModel", String.class, String.class, Map.class)
                .getModifiers();
        int generateModifiers = ServingSubprocessLauncher.class
                .getDeclaredMethod("generateForModel", String.class, String.class)
                .getModifiers();
        int boundedGenerateModifiers = ServingSubprocessLauncher.class
                .getDeclaredMethod("generateForModel", String.class, String.class, int.class)
                .getModifiers();

        assertTrue(Modifier.isSynchronized(loadModifiers),
                "loadModel must serialize model transitions");
        assertTrue(Modifier.isSynchronized(generateModifiers),
                "generateForModel must keep the identity check and generation atomic");
        assertTrue(Modifier.isSynchronized(boundedGenerateModifiers),
                "bounded generateForModel must keep the identity check and generation atomic");
    }
}
