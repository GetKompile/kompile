/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.ServerPortService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VlmTestSubprocessLauncher}.
 *
 * <p>{@link SubprocessExecutableConfig} and {@link ServerPortService} are
 * non-optional constructor args and are provided as Mockito mocks. All other
 * optional dependencies are null. Tests avoid launching real OS processes.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VlmTestSubprocessLauncherTest {

    @Mock
    private SubprocessExecutableConfig execConfig;

    @Mock
    private ServerPortService serverPortService;

    private VlmTestSubprocessLauncher launcher;

    @BeforeEach
    void setUp() {
        when(serverPortService.getActualPort()).thenReturn(8080);
        when(serverPortService.getBaseUrl()).thenReturn("http://localhost:8080");

        launcher = new VlmTestSubprocessLauncher(
                execConfig,
                serverPortService,
                null,   // SubprocessConfigService (optional)
                null    // Nd4jEnvironmentConfigService (optional)
        );
    }

    // ── isRunning ─────────────────────────────────────────────────────────────

    @Test
    void isRunning_unknownTask_returnsFalse() {
        assertFalse(launcher.isRunning("nonexistent-task"),
                "isRunning for unknown task should be false");
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_unknownTask_returnsNull() {
        VlmTestSubprocessLauncher.VlmTestStatus status = launcher.getStatus("unknown-task");
        assertNull(status, "Status for unknown task should be null");
    }

    // ── cancelTest ────────────────────────────────────────────────────────────

    @Test
    void cancelTest_unknownTask_returnsFalse() {
        boolean result = launcher.cancelTest("nonexistent-task");
        assertFalse(result, "Cancel for unknown task should return false");
    }

    // ── launchTest ────────────────────────────────────────────────────────────

    @Test
    void launchTest_returnsNonNullFuture() {
        // launchTest is async — it returns a future immediately
        // The future will eventually fail since no real subprocess is launched,
        // but the return value should be non-null right away.
        CompletableFuture<VlmTestSubprocessLauncher.VlmTestResult> future =
                launcher.launchTest("task-1", "/nonexistent/file.pdf", "phi-4-vlm", "DOCTAGS", null);
        assertNotNull(future);
    }

    @Test
    void launchTest_withOptions_returnsNonNullFuture() {
        Map<String, String> opts = Map.of(
                "maxNewTokens", "256",
                "temperature", "0.7",
                "topP", "0.9"
        );
        CompletableFuture<VlmTestSubprocessLauncher.VlmTestResult> future =
                launcher.launchTest("task-opts", "/nonexistent/file.pdf", "phi-4-vlm", "MARKDOWN", opts);
        assertNotNull(future);
    }

    // ── shutdown ──────────────────────────────────────────────────────────────

    @Test
    void shutdown_whenIdle_doesNotThrow() {
        assertDoesNotThrow(() -> launcher.shutdown());
    }

    // ── VlmTestResult ─────────────────────────────────────────────────────────

    @Test
    void vlmTestResult_constructorSetsFields() {
        VlmTestSubprocessLauncher.VlmTestResult result =
                new VlmTestSubprocessLauncher.VlmTestResult("task-x", "/path/to/file.pdf", "COMPLETED");
        assertEquals("task-x", result.taskId);
        assertEquals("/path/to/file.pdf", result.filePath);
        assertEquals("COMPLETED", result.status);
    }

    @Test
    void vlmTestResult_failedStatus() {
        VlmTestSubprocessLauncher.VlmTestResult result =
                new VlmTestSubprocessLauncher.VlmTestResult("task-fail", "/path/to/img.png", "FAILED");
        assertEquals("FAILED", result.status);
    }

    // ── VlmTestStatus ─────────────────────────────────────────────────────────

    @Test
    void vlmTestStatus_recordAccessors() {
        VlmTestSubprocessLauncher.VlmTestStatus status =
                new VlmTestSubprocessLauncher.VlmTestStatus("task-st", "RUNNING", 42, "PAGE_PROCESS", 3);
        assertEquals("task-st", status.taskId());
        assertEquals("RUNNING", status.status());
        assertEquals(42, status.progressPercent());
        assertEquals("PAGE_PROCESS", status.currentPhase());
        assertEquals(3, status.pagesCompleted());
    }

    // ── Multiple tasks ────────────────────────────────────────────────────────

    @Test
    void launchTest_multipleTasks_allReturnFutures() {
        CompletableFuture<VlmTestSubprocessLauncher.VlmTestResult> f1 =
                launcher.launchTest("mt-task-1", "/a.pdf", "model-a", "DOCTAGS", null);
        CompletableFuture<VlmTestSubprocessLauncher.VlmTestResult> f2 =
                launcher.launchTest("mt-task-2", "/b.pdf", "model-b", "MARKDOWN", null);
        assertNotNull(f1);
        assertNotNull(f2);
        assertNotSame(f1, f2);
    }
}
