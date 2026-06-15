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

package ai.kompile.app.services;

import ai.kompile.app.config.GpuDevice;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.CapacitySnapshot;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessingCapacityTrackerImplTest {

    @Mock
    private GpuResourceManager gpuResourceManager;

    private ProcessingCapacityTrackerImpl tracker;

    @BeforeEach
    void setUp() {
        tracker = new ProcessingCapacityTrackerImpl(gpuResourceManager);
    }

    @Test
    void testSelectBackendByPriority() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("api-agent")
                                .type(ProcessingBackendType.API_AGENT)
                                .priority(3)
                                .enabled(true)
                                .build(),
                        ProcessingBackend.builder()
                                .id("local-model")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .enabled(true)
                                .build(),
                        ProcessingBackend.builder()
                                .id("cli-agent")
                                .type(ProcessingBackendType.CLI_AGENT)
                                .priority(2)
                                .enabled(true)
                                .build()
                ))
                .build();

        Optional<ProcessingBackend> selected = tracker.selectBackend("llm", config);

        assertTrue(selected.isPresent());
        assertEquals("local-model", selected.get().getId());
    }

    @Test
    void testSelectBackendSkipsDisabled() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("local-model")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .enabled(false)
                                .build(),
                        ProcessingBackend.builder()
                                .id("cli-agent")
                                .type(ProcessingBackendType.CLI_AGENT)
                                .priority(2)
                                .enabled(true)
                                .build()
                ))
                .build();

        Optional<ProcessingBackend> selected = tracker.selectBackend("llm", config);

        assertTrue(selected.isPresent());
        assertEquals("cli-agent", selected.get().getId());
    }

    @Test
    void testSelectBackendFiltersCapabilities() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("vlm-only")
                                .type(ProcessingBackendType.LOCAL_MODEL)
                                .priority(1)
                                .enabled(true)
                                .capabilities(List.of("vlm"))
                                .build(),
                        ProcessingBackend.builder()
                                .id("llm-capable")
                                .type(ProcessingBackendType.CLI_AGENT)
                                .priority(2)
                                .enabled(true)
                                .capabilities(List.of("llm", "vlm"))
                                .build()
                ))
                .build();

        Optional<ProcessingBackend> selected = tracker.selectBackend("llm", config);

        assertTrue(selected.isPresent());
        assertEquals("llm-capable", selected.get().getId());
    }

    @Test
    void testSelectBackendEmptyCapabilitiesMatchesAll() {
        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(
                        ProcessingBackend.builder()
                                .id("general")
                                .type(ProcessingBackendType.API_AGENT)
                                .priority(1)
                                .enabled(true)
                                .capabilities(List.of()) // empty = supports all
                                .build()
                ))
                .build();

        Optional<ProcessingBackend> selected = tracker.selectBackend("anything", config);

        assertTrue(selected.isPresent());
        assertEquals("general", selected.get().getId());
    }

    @Test
    void testSelectBackendReturnsEmptyWhenNoConfig() {
        assertTrue(tracker.selectBackend("llm", null).isEmpty());
        assertTrue(tracker.selectBackend("llm", ProcessingRouteConfig.builder().build()).isEmpty());
    }

    @Test
    void testCanAcceptWithConfigRespectsMaxConcurrent() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("limited")
                .type(ProcessingBackendType.CLI_AGENT)
                .priority(1)
                .maxConcurrent(2)
                .enabled(true)
                .build();

        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));

        // Dispatch twice to fill capacity
        tracker.recordDispatch("limited", "llm");
        tracker.recordDispatch("limited", "llm");

        // canAcceptWithConfig should now return false
        assertFalse(tracker.canAcceptWithConfig(backend, "llm"));

        // Complete one — should have capacity again
        tracker.recordCompletion("limited", "llm", true);
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testSelectBackendAlwaysPicksLowestPriority() {
        // selectBackend delegates to canAccept() which is a simple interface check;
        // canAcceptWithConfig provides the detailed capacity check.
        // selectBackend should always pick the lowest-priority enabled backend.
        ProcessingBackend primary = ProcessingBackend.builder()
                .id("primary")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .priority(1)
                .maxConcurrent(1)
                .enabled(true)
                .build();

        ProcessingBackend fallback = ProcessingBackend.builder()
                .id("fallback")
                .type(ProcessingBackendType.API_AGENT)
                .priority(2)
                .enabled(true)
                .build();

        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(primary, fallback))
                .build();

        // Even with dispatches, selectBackend picks by priority
        tracker.recordDispatch("primary", "llm");

        Optional<ProcessingBackend> selected = tracker.selectBackend("llm", config);
        assertTrue(selected.isPresent());
        assertEquals("primary", selected.get().getId());
    }

    @Test
    void testRecordDispatchAndCompletion() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("test")
                .type(ProcessingBackendType.CLI_AGENT)
                .priority(1)
                .maxConcurrent(2)
                .enabled(true)
                .build();

        // Initially can accept
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));

        // Dispatch one
        tracker.recordDispatch("test", "llm");
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));

        // Dispatch another to hit limit
        tracker.recordDispatch("test", "llm");
        assertFalse(tracker.canAcceptWithConfig(backend, "llm"));

        // Complete one — should have capacity again
        tracker.recordCompletion("test", "llm", true);
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testRecordCompletionFailure() {
        // Should not throw and should still decrement active count
        tracker.recordDispatch("backend", "llm");
        tracker.recordCompletion("backend", "llm", false);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("backend")
                .type(ProcessingBackendType.API_AGENT)
                .maxConcurrent(1)
                .enabled(true)
                .build();
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testRecordCompletionOnUnknownBackend() {
        // Should not throw
        assertDoesNotThrow(() -> tracker.recordCompletion("unknown", "llm", true));
    }

    @Test
    void testCanAcceptWithGpuMemoryCheck() {
        GpuDevice device = GpuDevice.local(0, 0, "RTX 4090", 24_000_000_000L);
        when(gpuResourceManager.getDevices()).thenReturn(List.of(device));
        when(gpuResourceManager.getAvailableMemory(device)).thenReturn(10_000_000_000L);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxMemoryBytes(18_000_000_000L)
                .enabled(true)
                .build();

        // 10GB available < 18GB required
        assertFalse(tracker.canAcceptWithConfig(backend, "vlm"));
    }

    @Test
    void testCanAcceptWithSufficientGpuMemory() {
        GpuDevice device = GpuDevice.local(0, 0, "RTX 4090", 24_000_000_000L);
        when(gpuResourceManager.getDevices()).thenReturn(List.of(device));
        when(gpuResourceManager.getAvailableMemory(device)).thenReturn(20_000_000_000L);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxMemoryBytes(18_000_000_000L)
                .enabled(true)
                .build();

        // 20GB available > 18GB required
        assertTrue(tracker.canAcceptWithConfig(backend, "vlm"));
    }

    @Test
    void testCanAcceptWithMultipleGpus() {
        GpuDevice gpu0 = GpuDevice.local(0, 0, "RTX 3070 Ti", 8_000_000_000L);
        GpuDevice gpu1 = GpuDevice.local(1, 1, "RTX 4090", 24_000_000_000L);
        when(gpuResourceManager.getDevices()).thenReturn(List.of(gpu0, gpu1));
        when(gpuResourceManager.getAvailableMemory(gpu0)).thenReturn(5_000_000_000L);
        when(gpuResourceManager.getAvailableMemory(gpu1)).thenReturn(15_000_000_000L);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxMemoryBytes(18_000_000_000L) // 18GB needed
                .enabled(true)
                .build();

        // Sum: 5GB + 15GB = 20GB > 18GB required
        assertTrue(tracker.canAcceptWithConfig(backend, "vlm"));
    }

    @Test
    void testCanAcceptNoGpuCheckForNonLocalModel() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("api-backend")
                .type(ProcessingBackendType.API_AGENT)
                .maxMemoryBytes(18_000_000_000L)
                .enabled(true)
                .build();

        // Should not check GPU for API_AGENT type
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
        verifyNoInteractions(gpuResourceManager);
    }

    @Test
    void testCanAcceptZeroMaxMemorySkipsGpuCheck() {
        when(gpuResourceManager.getDevices()).thenReturn(List.of());

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxMemoryBytes(0) // 0 = auto, don't check
                .enabled(true)
                .build();

        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testGetCapacitySnapshot() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("test-backend")
                .type(ProcessingBackendType.CLI_AGENT)
                .maxConcurrent(5)
                .requestsPerMinute(60)
                .enabled(true)
                .build();

        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(backend))
                .build();

        tracker.recordDispatch("test-backend", "llm");
        tracker.recordDispatch("test-backend", "llm");

        List<CapacitySnapshot> snapshots = tracker.getCapacitySnapshot(config);

        assertEquals(1, snapshots.size());
        CapacitySnapshot snapshot = snapshots.get(0);
        assertEquals("test-backend", snapshot.getBackendId());
        assertEquals(ProcessingBackendType.CLI_AGENT, snapshot.getType());
        assertEquals(2, snapshot.getActiveRequests());
        assertEquals(5, snapshot.getMaxConcurrent());
        assertEquals(60, snapshot.getRequestsPerMinute());
        assertTrue(snapshot.isAvailable());
        assertEquals("Ready", snapshot.getStatusMessage());
    }

    @Test
    void testGetCapacitySnapshotShowsUnavailable() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("limited")
                .type(ProcessingBackendType.CLI_AGENT)
                .maxConcurrent(1)
                .enabled(true)
                .build();

        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(backend))
                .build();

        tracker.recordDispatch("limited", "llm");

        List<CapacitySnapshot> snapshots = tracker.getCapacitySnapshot(config);

        assertEquals(1, snapshots.size());
        CapacitySnapshot snapshot = snapshots.get(0);
        assertFalse(snapshot.isAvailable());
        assertTrue(snapshot.getStatusMessage().contains("concurrent limit"));
    }

    @Test
    void testGetCapacitySnapshotNullConfig() {
        List<CapacitySnapshot> snapshots = tracker.getCapacitySnapshot(null);
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void testGetCapacitySnapshotWithGpuInfo() {
        GpuDevice device = GpuDevice.local(0, 0, "RTX 4090", 24_000_000_000L);
        when(gpuResourceManager.getDevices()).thenReturn(List.of(device));
        when(gpuResourceManager.getAvailableMemory(device)).thenReturn(16_000_000_000L);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local-vlm")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxConcurrent(2)
                .enabled(true)
                .build();

        ProcessingRouteConfig config = ProcessingRouteConfig.builder()
                .backends(List.of(backend))
                .build();

        List<CapacitySnapshot> snapshots = tracker.getCapacitySnapshot(config);

        assertEquals(1, snapshots.size());
        CapacitySnapshot snapshot = snapshots.get(0);
        assertEquals(24_000_000_000L, snapshot.getGpuMemoryTotal());
        assertEquals(8_000_000_000L, snapshot.getGpuMemoryUsed()); // 24G - 16G available
    }

    @Test
    void testSlidingWindowCounter() {
        ProcessingCapacityTrackerImpl.SlidingWindowCounter counter =
                new ProcessingCapacityTrackerImpl.SlidingWindowCounter();

        assertEquals(0, counter.getCount());

        counter.increment();
        counter.increment();
        counter.increment();

        assertEquals(3, counter.getCount());
    }

    @Test
    void testRateLimitEnforcement() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("rate-limited")
                .type(ProcessingBackendType.API_AGENT)
                .requestsPerMinute(2)
                .enabled(true)
                .build();

        // Dispatch twice to hit rate limit
        tracker.recordDispatch("rate-limited", "llm");
        tracker.recordDispatch("rate-limited", "llm");

        // Should be at rate limit
        assertFalse(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testZeroLimitsAreUnlimited() {
        ProcessingBackend backend = ProcessingBackend.builder()
                .id("unlimited")
                .type(ProcessingBackendType.API_AGENT)
                .maxConcurrent(0) // unlimited
                .requestsPerMinute(0) // unlimited
                .enabled(true)
                .build();

        // Dispatch many times
        for (int i = 0; i < 100; i++) {
            tracker.recordDispatch("unlimited", "llm");
        }

        // Should still accept — limits are 0 (unlimited)
        assertTrue(tracker.canAcceptWithConfig(backend, "llm"));
    }

    @Test
    void testConstructorWithNullGpuManager() {
        ProcessingCapacityTrackerImpl trackerNoGpu = new ProcessingCapacityTrackerImpl(null);

        ProcessingBackend backend = ProcessingBackend.builder()
                .id("local")
                .type(ProcessingBackendType.LOCAL_MODEL)
                .maxMemoryBytes(10_000_000_000L)
                .enabled(true)
                .build();

        // Should not throw with null GPU manager
        assertTrue(trackerNoGpu.canAcceptWithConfig(backend, "vlm"));
    }
}
