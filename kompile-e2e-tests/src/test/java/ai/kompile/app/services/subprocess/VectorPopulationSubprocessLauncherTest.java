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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VectorPopulationSubprocessLauncher}.
 *
 * <p>The launcher depends on a non-optional {@link SubprocessRestartManager},
 * which itself depends on a {@link SystemMemoryAnalyzer}. Both are instantiated
 * directly without Spring context. All other dependencies are optional (null).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorPopulationSubprocessLauncherTest {

    private VectorPopulationSubprocessLauncher launcher;
    private SubprocessRestartManager restartManager;

    @BeforeEach
    void setUp() {
        SystemMemoryAnalyzer memoryAnalyzer = new SystemMemoryAnalyzer();
        restartManager = new SubprocessRestartManager(memoryAnalyzer, null);

        launcher = new VectorPopulationSubprocessLauncher(
                null,  // SimpMessagingTemplate
                null,  // ServerPortService
                null,  // Nd4jEnvironmentConfigService
                null,  // DeviceRoutingConfigService
                null,  // SubprocessConfigService
                null,  // SubprocessExecutableConfig
                null,  // AnseriniEmbeddingProperties
                null,  // VectorPopulationProgressTracker
                null,  // IngestProgressTracker
                restartManager,
                null,  // IngestEventService
                null   // OpTimingService
        );
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_unknownTask_returnsNull() {
        VectorPopulationSubprocessLauncher.VectorPopulationHandle.Status status =
                launcher.getStatus("unknown-task");
        assertNull(status, "Status for unknown task should be null");
    }

    // ── getAllStatuses ────────────────────────────────────────────────────────

    @Test
    void getAllStatuses_initiallyEmpty() {
        List<VectorPopulationSubprocessLauncher.VectorPopulationHandle.Status> statuses =
                launcher.getAllStatuses();
        assertNotNull(statuses);
        assertTrue(statuses.isEmpty(), "No active processes expected initially");
    }

    // ── cancelVectorPopulation ────────────────────────────────────────────────

    @Test
    void cancelVectorPopulation_unknownTask_returnsFalse() {
        boolean result = launcher.cancelVectorPopulation("nonexistent-task");
        assertFalse(result, "Cancel for unknown task should return false");
    }

    // ── launchVectorPopulation ────────────────────────────────────────────────

    @Test
    void launchVectorPopulation_returnsNonNullFuture() {
        CompletableFuture<VectorPopulationSubprocessLauncher.VectorPopulationResult> future =
                launcher.launchVectorPopulation(
                        "vp-task-1",
                        "/tmp/keyword-index",
                        "/tmp/vector-index",
                        null
                );
        assertNotNull(future, "launchVectorPopulation should return a non-null future");
    }

    @Test
    void launchVectorPopulation_withOptions_returnsNonNullFuture() {
        Map<String, Object> opts = Map.of("embeddingBatchSize", 32, "parallelIndexing", true);
        CompletableFuture<VectorPopulationSubprocessLauncher.VectorPopulationResult> future =
                launcher.launchVectorPopulation(
                        "vp-task-2",
                        "/tmp/keyword-index",
                        "/tmp/vector-index",
                        opts
                );
        assertNotNull(future);
    }

    // ── shutdownAll ───────────────────────────────────────────────────────────

    @Test
    void shutdownAll_whenIdle_doesNotThrow() {
        assertDoesNotThrow(() -> launcher.shutdownAll());
    }

    // ── checkStaleProcesses ───────────────────────────────────────────────────

    @Test
    void checkStaleProcesses_whenIdle_doesNotThrow() {
        assertDoesNotThrow(() -> launcher.checkStaleProcesses());
    }

    // ── checkProgressStalls ───────────────────────────────────────────────────

    @Test
    void checkProgressStalls_whenIdle_doesNotThrow() {
        assertDoesNotThrow(() -> launcher.checkProgressStalls());
    }

    // ── VectorPopulationResult — static factories ──────────────────────────────

    @Test
    void vectorPopulationResult_success_isSuccess() {
        var result = VectorPopulationSubprocessLauncher.VectorPopulationResult
                .success("task-x", 100, 50, 100, 5000L, "/tmp/vindex");
        assertTrue(result.success());
        assertEquals("task-x", result.taskId());
        assertEquals(100, result.documentsLoaded());
        assertEquals(50, result.chunksEmbedded());
        assertEquals(100, result.documentsIndexed());
        assertEquals(5000L, result.totalDurationMs());
        assertEquals("/tmp/vindex", result.vectorIndexPath());
        assertNull(result.errorMessage());
        assertNull(result.errorPhase());
    }

    @Test
    void vectorPopulationResult_failure_isFailure() {
        var result = VectorPopulationSubprocessLauncher.VectorPopulationResult
                .failure("task-y", "EMBEDDING", "OOM error");
        assertFalse(result.success());
        assertEquals("task-y", result.taskId());
        assertEquals("EMBEDDING", result.errorPhase());
        assertEquals("OOM error", result.errorMessage());
        assertEquals(0, result.documentsLoaded());
    }

    // ── MemoryOverrides ────────────────────────────────────────────────────────

    @Test
    void memoryOverrides_none_hasNoOverrides() {
        VectorPopulationSubprocessLauncher.MemoryOverrides none =
                VectorPopulationSubprocessLauncher.MemoryOverrides.none();
        assertNotNull(none);
        assertFalse(none.hasOverrides());
    }

    @Test
    void memoryOverrides_of_hasOverrides() {
        VectorPopulationSubprocessLauncher.MemoryOverrides overrides =
                VectorPopulationSubprocessLauncher.MemoryOverrides.of("8g", 4L * 1024 * 1024 * 1024);
        assertNotNull(overrides);
        assertTrue(overrides.hasOverrides());
        assertEquals("8g", overrides.heapSize());
        assertEquals(4L * 1024 * 1024 * 1024, overrides.offHeapBytes());
    }
}
