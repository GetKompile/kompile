/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services;

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.GpuDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModelLifecycleManager}.
 * Tests service registration, GPU acquisition/release with eviction,
 * job-level tracking, SmartLifecycle, stale job detection, and event publishing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelLifecycleManager Tests")
class ModelLifecycleManagerTest {

    private static final long ONE_GB = 1024L * 1024L * 1024L;
    private static final GpuDevice GPU_4090 = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);
    private static final GpuDevice GPU_3070 = GpuDevice.local(1, 0, "RTX 3070 Ti", 8L * ONE_GB);

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GpuResourceManager gpuResourceManager;
    private ModelLifecycleManager manager;

    @BeforeEach
    void setUp() {
        gpuResourceManager = new GpuResourceManager();
        gpuResourceManager.initForTesting();
        gpuResourceManager.registerDevice(GPU_4090);
        gpuResourceManager.registerDevice(GPU_3070);

        // No DeviceRoutingConfigService — auto-select mode
        manager = new ModelLifecycleManager(
                gpuResourceManager, null, eventPublisher,
                60, 120, 3600);
    }

    /**
     * Creates a simple mock ManagedService that immediately succeeds on suspend/resume.
     */
    private ModelLifecycleManager.ManagedService createMockService(
            String serviceType, boolean running, boolean suspended) {
        ModelLifecycleManager.ManagedService service = mock(ModelLifecycleManager.ManagedService.class);
        lenient().when(service.getServiceType()).thenReturn(serviceType);
        // Make isRunning() return false after suspend() is called
        final boolean[] isRunning = {running};
        final boolean[] isSuspended = {suspended};
        lenient().when(service.isRunning()).thenAnswer(inv -> isRunning[0]);
        lenient().when(service.isSuspended()).thenAnswer(inv -> isSuspended[0]);
        lenient().when(service.suspend(anyString())).thenAnswer(inv -> {
            isRunning[0] = false;
            isSuspended[0] = true;
            return true;
        });
        lenient().when(service.resume()).thenAnswer(inv -> {
            isRunning[0] = true;
            isSuspended[0] = false;
            return true;
        });
        return service;
    }

    // ==================== Service Registration ====================

    @Nested
    @DisplayName("Service registration")
    class ServiceRegistration {

        @Test
        @DisplayName("should register a managed service")
        void registerService() {
            var service = createMockService("embedding", true, false);
            manager.registerService(service);

            assertTrue(manager.getService("embedding").isPresent());
        }

        @Test
        @DisplayName("should unregister a managed service")
        void unregisterService() {
            var service = createMockService("embedding", true, false);
            manager.registerService(service);
            manager.unregisterService("embedding");

            assertTrue(manager.getService("embedding").isEmpty());
        }

        @Test
        @DisplayName("should return empty for unregistered service type")
        void getUnregisteredService() {
            assertTrue(manager.getService("nonexistent").isEmpty());
        }
    }

    // ==================== GPU Acquisition (singleton) ====================

    @Nested
    @DisplayName("GPU acquisition (singleton)")
    class GpuAcquisition {

        @Test
        @DisplayName("should acquire GPU without eviction when space available")
        void acquireWithoutEviction() {
            GpuDevice device = manager.acquireGpu("embedding");

            assertNotNull(device);
            assertTrue(gpuResourceManager.hasReservation("embedding"));
        }

        @Test
        @DisplayName("should select largest device for VLM")
        void selectsLargestForVlm() {
            GpuDevice device = manager.acquireGpu("vlm");

            assertEquals("RTX 4090", device.name());
        }

        @Test
        @DisplayName("should evict lower-priority service when needed")
        void evictsLowerPriority() {
            // Register embedding as managed service
            var embeddingService = createMockService("embedding", true, false);
            manager.registerService(embeddingService);

            // Fill GPU so VLM doesn't fit
            gpuResourceManager.reserve("embedding", GPU_4090, 8L * ONE_GB);

            // VLM needs 18GB, only 16GB free -> must evict embedding
            GpuDevice device = manager.acquireGpu("vlm");

            assertNotNull(device);
            verify(embeddingService).suspend(anyString());
            assertTrue(gpuResourceManager.hasReservation("vlm"));
            assertFalse(gpuResourceManager.hasReservation("embedding")); // evicted
        }

        @Test
        @DisplayName("should throw when no GPU available at all")
        void throwsWhenNoGpu() {
            // Create manager with no GPUs
            GpuResourceManager emptyManager = new GpuResourceManager();
            emptyManager.initForTesting();
            var emptyLifecycleManager = new ModelLifecycleManager(
                    emptyManager, null, null, 60, 120, 3600);

            assertThrows(IllegalStateException.class, () ->
                    emptyLifecycleManager.acquireGpu("vlm"));
        }

        @Test
        @DisplayName("should throw when device is full and no lower-priority services")
        void throwsWhenNoEvictionPossible() {
            // Reserve all memory on 4090 with VLM (priority 100)
            gpuResourceManager.reserveWithId("vlm-4090", "vlm", GPU_4090, 24L * ONE_GB);
            // Reserve all memory on 3070 with VLM too (priority 100) — use reserveWithId
            // to avoid overwriting the first reservation
            gpuResourceManager.reserveWithId("vlm-3070", "vlm", GPU_3070, 8L * ONE_GB);

            // Embedding (priority 10) can't evict VLM (priority 100) on either device
            assertThrows(IllegalStateException.class, () ->
                    manager.acquireGpu("embedding"));
        }
    }

    // ==================== GPU Release (singleton) ====================

    @Nested
    @DisplayName("GPU release (singleton)")
    class GpuRelease {

        @Test
        @DisplayName("should release GPU reservation")
        void releaseReservation() {
            manager.acquireGpu("embedding");
            manager.releaseGpu("embedding");

            assertFalse(gpuResourceManager.hasReservation("embedding"));
        }

        @Test
        @DisplayName("should restore evicted services after release")
        void restoresEvictedServices() {
            var embeddingService = createMockService("embedding", true, false);
            manager.registerService(embeddingService);

            // Fill GPU so VLM causes eviction
            gpuResourceManager.reserve("embedding", GPU_4090, 8L * ONE_GB);

            manager.acquireGpu("vlm");
            manager.releaseGpu("vlm");

            // Embedding should be restored
            verify(embeddingService).resume();
        }
    }

    // ==================== hasGpuReservation ====================

    @Nested
    @DisplayName("hasGpuReservation")
    class HasGpuReservation {

        @Test
        @DisplayName("should return true when service has reservation")
        void trueWhenReserved() {
            manager.acquireGpu("embedding");
            assertTrue(manager.hasGpuReservation("embedding"));
        }

        @Test
        @DisplayName("should return false when service has no reservation")
        void falseWhenNotReserved() {
            assertFalse(manager.hasGpuReservation("embedding"));
        }

        @Test
        @DisplayName("should return true when job-level reservation exists for service type")
        void trueForJobReservation() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "test");

            assertTrue(manager.hasGpuReservation("vlm"));
        }
    }

    // ==================== Job-Level GPU Tracking ====================

    @Nested
    @DisplayName("Job-level GPU tracking")
    class JobLevelTracking {

        @BeforeEach
        void startManager() {
            manager.start();
        }

        @Test
        @DisplayName("should acquire GPU for a job")
        void acquireGpuForJob() {
            GpuDevice device = manager.acquireGpuForJob("job-1", "vlm", "VLM test");

            assertNotNull(device);
            assertTrue(manager.hasJobGpuHold("job-1"));
            assertTrue(gpuResourceManager.hasReservation("job-1"));
        }

        @Test
        @DisplayName("should release GPU for a job")
        void releaseGpuForJob() {
            manager.acquireGpuForJob("job-1", "vlm", "VLM test");
            manager.releaseGpuForJob("job-1");

            assertFalse(manager.hasJobGpuHold("job-1"));
            assertFalse(gpuResourceManager.hasReservation("job-1"));
        }

        @Test
        @DisplayName("releaseGpuForJob should be idempotent")
        void releaseIdempotent() {
            manager.acquireGpuForJob("job-1", "vlm", "VLM test");
            manager.releaseGpuForJob("job-1");
            // Second release should not throw
            manager.releaseGpuForJob("job-1");

            assertFalse(manager.hasJobGpuHold("job-1"));
        }

        @Test
        @DisplayName("should support multiple concurrent jobs of different service types")
        void multipleConcurrentJobs() {
            manager.acquireGpuForJob("ingest-1", "ingest", "Ingest doc1");
            manager.acquireGpuForJob("ingest-2", "ingest", "Ingest doc2");

            assertEquals(2, manager.getActiveJobHolds().size());
            assertTrue(manager.hasJobGpuHold("ingest-1"));
            assertTrue(manager.hasJobGpuHold("ingest-2"));
        }

        @Test
        @DisplayName("should evict services when job needs GPU")
        void jobEvictsServices() {
            var embeddingService = createMockService("embedding", true, false);
            manager.registerService(embeddingService);

            // Fill GPU
            gpuResourceManager.reserve("embedding", GPU_4090, 8L * ONE_GB);

            // Job-level VLM acquisition
            GpuDevice device = manager.acquireGpuForJob("vlm-job-1", "vlm", "VLM test");

            assertNotNull(device);
            verify(embeddingService).suspend(anyString());
        }

        @Test
        @DisplayName("should restore evicted services on job release")
        void jobReleaseRestoresServices() {
            var embeddingService = createMockService("embedding", true, false);
            manager.registerService(embeddingService);

            // Fill GPU
            gpuResourceManager.reserve("embedding", GPU_4090, 8L * ONE_GB);

            manager.acquireGpuForJob("vlm-job-1", "vlm", "VLM test");
            manager.releaseGpuForJob("vlm-job-1");

            verify(embeddingService).resume();
        }

        @Test
        @DisplayName("should track job hold details")
        void jobHoldDetails() {
            manager.acquireGpuForJob("job-1", "vlm", "VLM test: doc.pdf");

            var holds = manager.getActiveJobHolds();
            assertEquals(1, holds.size());

            var hold = holds.get("job-1");
            assertNotNull(hold);
            assertEquals("job-1", hold.jobId());
            assertEquals("vlm", hold.serviceType());
            assertEquals("VLM test: doc.pdf", hold.description());
            assertNotNull(hold.acquiredAt());
            assertNotNull(hold.device());
        }

        @Test
        @DisplayName("should throw when manager is not running")
        void throwsWhenNotRunning() {
            manager.stop();

            assertThrows(IllegalStateException.class, () ->
                    manager.acquireGpuForJob("job-1", "vlm", "test"));
        }

        @Test
        @DisplayName("active job holds map should be unmodifiable")
        void unmodifiableJobHolds() {
            var holds = manager.getActiveJobHolds();
            assertThrows(UnsupportedOperationException.class, () ->
                    holds.put("test", null));
        }
    }

    // ==================== Convenience Methods ====================

    @Nested
    @DisplayName("Convenience methods")
    class ConvenienceMethods {

        @BeforeEach
        void startManager() {
            manager.start();
        }

        @Test
        @DisplayName("acquireGpuForVlm should use VLM service type")
        void acquireGpuForVlm() {
            GpuDevice device = manager.acquireGpuForVlm("vlm-task-1");
            assertNotNull(device);
            assertTrue(manager.hasJobGpuHold("vlm-task-1"));

            var hold = manager.getActiveJobHolds().get("vlm-task-1");
            assertEquals(DeviceRoutingConfig.SERVICE_VLM, hold.serviceType());
        }

        @Test
        @DisplayName("releaseGpuForVlm should release VLM job")
        void releaseGpuForVlm() {
            manager.acquireGpuForVlm("vlm-task-1");
            manager.releaseGpuForVlm("vlm-task-1");
            assertFalse(manager.hasJobGpuHold("vlm-task-1"));
        }

        @Test
        @DisplayName("acquireGpuForIngest should use INGEST service type")
        void acquireGpuForIngest() {
            GpuDevice device = manager.acquireGpuForIngest("ingest-1", "doc.pdf");
            assertNotNull(device);

            var hold = manager.getActiveJobHolds().get("ingest-1");
            assertEquals(DeviceRoutingConfig.SERVICE_INGEST, hold.serviceType());
            assertTrue(hold.description().contains("doc.pdf"));
        }

        @Test
        @DisplayName("acquireGpuForVectorPopulation should use correct service type")
        void acquireGpuForVectorPop() {
            GpuDevice device = manager.acquireGpuForVectorPopulation("vecpop-1");
            assertNotNull(device);

            var hold = manager.getActiveJobHolds().get("vecpop-1");
            assertEquals(DeviceRoutingConfig.SERVICE_VECTOR_POPULATION, hold.serviceType());
        }

        @Test
        @DisplayName("acquireGpuForModelInit should use correct service type")
        void acquireGpuForModelInit() {
            GpuDevice device = manager.acquireGpuForModelInit("init-1");
            assertNotNull(device);

            var hold = manager.getActiveJobHolds().get("init-1");
            assertEquals(DeviceRoutingConfig.SERVICE_MODEL_INIT, hold.serviceType());
        }
    }

    // ==================== Deprecated Methods ====================

    @Nested
    @DisplayName("Deprecated methods (backward compatibility)")
    class DeprecatedMethods {

        @Test
        @DisplayName("deprecated acquireGpuForVlm() should use singleton reservation")
        @SuppressWarnings("deprecation")
        void deprecatedAcquire() {
            GpuDevice device = manager.acquireGpuForVlm();
            assertNotNull(device);
            assertTrue(gpuResourceManager.hasReservation("vlm"));
        }

        @Test
        @DisplayName("deprecated releaseGpuForVlm() should release singleton reservation")
        @SuppressWarnings("deprecation")
        void deprecatedRelease() {
            manager.acquireGpuForVlm();
            manager.releaseGpuForVlm();
            assertFalse(gpuResourceManager.hasReservation("vlm"));
        }
    }

    // ==================== SmartLifecycle ====================

    @Nested
    @DisplayName("SmartLifecycle")
    class SmartLifecycleTests {

        @Test
        @DisplayName("should start and set running to true")
        void startSetsRunning() {
            assertFalse(manager.isRunning());
            manager.start();
            assertTrue(manager.isRunning());
        }

        @Test
        @DisplayName("should stop and set running to false")
        void stopSetsNotRunning() {
            manager.start();
            manager.stop();
            assertFalse(manager.isRunning());
        }

        @Test
        @DisplayName("should have high phase for late start / early stop")
        void hasHighPhase() {
            assertEquals(Integer.MAX_VALUE - 100, manager.getPhase());
        }

        @Test
        @DisplayName("start should be idempotent")
        void startIdempotent() {
            manager.start();
            manager.start(); // Should not throw or change state
            assertTrue(manager.isRunning());
        }

        @Test
        @DisplayName("stop should be idempotent")
        void stopIdempotent() {
            manager.start();
            manager.stop();
            manager.stop(); // Should not throw
            assertFalse(manager.isRunning());
        }

        @Test
        @DisplayName("stop should release all job holds")
        void stopReleasesJobHolds() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "test");
            manager.acquireGpuForJob("job-2", "ingest", "test");

            manager.stop();

            assertTrue(manager.getActiveJobHolds().isEmpty());
            assertFalse(gpuResourceManager.hasReservation("job-1"));
            assertFalse(gpuResourceManager.hasReservation("job-2"));
        }

        @Test
        @DisplayName("stop should suspend running managed services")
        void stopSuspendsServices() {
            var service = createMockService("embedding", true, false);
            manager.registerService(service);
            manager.start();

            manager.stop();

            verify(service).suspend("Application shutdown");
        }

        @Test
        @DisplayName("stop should not suspend already-suspended services")
        void stopSkipsAlreadySuspended() {
            var service = createMockService("embedding", true, true);
            manager.registerService(service);
            manager.start();

            manager.stop();

            verify(service, never()).suspend(anyString());
        }
    }

    // ==================== Shutdown (@PreDestroy) ====================

    @Nested
    @DisplayName("Shutdown (@PreDestroy)")
    class ShutdownTests {

        @Test
        @DisplayName("should clean up if SmartLifecycle.stop() was not called")
        void shutdownWithoutStop() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "test");

            manager.shutdown();

            assertFalse(manager.isRunning());
            assertTrue(manager.getActiveJobHolds().isEmpty());
        }

        @Test
        @DisplayName("should be safe if already stopped via SmartLifecycle")
        void shutdownAfterStop() {
            manager.start();
            manager.stop();

            // Should not throw
            manager.shutdown();
            assertFalse(manager.isRunning());
        }
    }

    // ==================== Event Publishing ====================

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        @DisplayName("should publish LIFECYCLE_STARTED event on start()")
        void publishesStartEvent() {
            manager.start();

            ArgumentCaptor<GpuLifecycleEvent> captor = ArgumentCaptor.forClass(GpuLifecycleEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertEquals(GpuLifecycleEvent.EventType.LIFECYCLE_STARTED, captor.getValue().getEventType());
        }

        @Test
        @DisplayName("should publish LIFECYCLE_STOPPED event on stop()")
        void publishesStopEvent() {
            manager.start();
            reset(eventPublisher);

            manager.stop();

            ArgumentCaptor<GpuLifecycleEvent> captor = ArgumentCaptor.forClass(GpuLifecycleEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertEquals(GpuLifecycleEvent.EventType.LIFECYCLE_STOPPED, captor.getValue().getEventType());
        }

        @Test
        @DisplayName("should publish GPU_ACQUIRED event on job acquisition")
        void publishesAcquiredEvent() {
            manager.start();
            reset(eventPublisher);

            manager.acquireGpuForJob("job-1", "vlm", "test");

            ArgumentCaptor<GpuLifecycleEvent> captor = ArgumentCaptor.forClass(GpuLifecycleEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            GpuLifecycleEvent event = captor.getValue();
            assertEquals(GpuLifecycleEvent.EventType.GPU_ACQUIRED, event.getEventType());
            assertEquals("job-1", event.getJobId());
            assertEquals("vlm", event.getServiceType());
        }

        @Test
        @DisplayName("should publish GPU_RELEASED event on job release")
        void publishesReleasedEvent() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "test");
            reset(eventPublisher);

            manager.releaseGpuForJob("job-1");

            ArgumentCaptor<GpuLifecycleEvent> captor = ArgumentCaptor.forClass(GpuLifecycleEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            GpuLifecycleEvent event = captor.getValue();
            assertEquals(GpuLifecycleEvent.EventType.GPU_RELEASED, event.getEventType());
            assertEquals("job-1", event.getJobId());
        }

        @Test
        @DisplayName("should handle null event publisher gracefully")
        void handlesNullPublisher() {
            var managerNoPublisher = new ModelLifecycleManager(
                    gpuResourceManager, null, null, 60, 120, 3600);

            // Should not throw
            managerNoPublisher.start();
            managerNoPublisher.stop();
        }

        @Test
        @DisplayName("should publish SERVICE_EVICTED event during eviction")
        void publishesEvictedEvent() {
            var embeddingService = createMockService("embedding", true, false);
            manager.registerService(embeddingService);
            gpuResourceManager.reserve("embedding", GPU_4090, 8L * ONE_GB);

            manager.start();
            reset(eventPublisher);

            manager.acquireGpuForJob("vlm-1", "vlm", "test");

            ArgumentCaptor<GpuLifecycleEvent> captor = ArgumentCaptor.forClass(GpuLifecycleEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            boolean foundEvicted = captor.getAllValues().stream()
                    .anyMatch(e -> e.getEventType() == GpuLifecycleEvent.EventType.SERVICE_EVICTED);
            assertTrue(foundEvicted, "Expected SERVICE_EVICTED event");
        }
    }

    // ==================== Stale Job Detection ====================

    @Nested
    @DisplayName("Stale job detection")
    class StaleJobDetection {

        @Test
        @DisplayName("should not detect stale jobs when no jobs active")
        void noStaleJobsWhenEmpty() {
            manager.start();
            // Should not throw
            manager.detectStaleJobs();
        }

        @Test
        @DisplayName("should not detect stale jobs when not running")
        void noDetectionWhenNotRunning() {
            // Should not throw — manager is not running
            manager.detectStaleJobs();
            verify(eventPublisher, never()).publishEvent(any(GpuLifecycleEvent.class));
        }

        @Test
        @DisplayName("should not flag fresh jobs as stale")
        void freshJobsNotStale() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "test");
            reset(eventPublisher);

            // Jobs just created should not be stale (threshold is 3600s)
            manager.detectStaleJobs();

            // Should not have published any STALE_JOB_DETECTED events
            verify(eventPublisher, never()).publishEvent(any(GpuLifecycleEvent.class));
        }
    }

    // ==================== Configurable Timeouts ====================

    @Nested
    @DisplayName("Configurable timeouts")
    class ConfigurableTimeouts {

        @Test
        @DisplayName("should expose eviction timeout")
        void evictionTimeout() {
            assertEquals(60, manager.getEvictionTimeoutSeconds());
        }

        @Test
        @DisplayName("should expose resume timeout")
        void resumeTimeout() {
            assertEquals(120, manager.getResumeTimeoutSeconds());
        }

        @Test
        @DisplayName("should expose stale job threshold")
        void staleJobThreshold() {
            assertEquals(3600, manager.getStaleJobThresholdSeconds());
        }

        @Test
        @DisplayName("should accept custom timeout values")
        void customTimeouts() {
            var customManager = new ModelLifecycleManager(
                    gpuResourceManager, null, null, 30, 45, 1800);

            assertEquals(30, customManager.getEvictionTimeoutSeconds());
            assertEquals(45, customManager.getResumeTimeoutSeconds());
            assertEquals(1800, customManager.getStaleJobThresholdSeconds());
        }
    }

    // ==================== Status ====================

    @Nested
    @DisplayName("Status reporting")
    class StatusReporting {

        @Test
        @DisplayName("should report running state")
        void reportsRunningState() {
            var status = manager.getStatus();
            assertEquals(false, status.get("running"));

            manager.start();
            status = manager.getStatus();
            assertEquals(true, status.get("running"));
        }

        @Test
        @DisplayName("should report managed services")
        void reportsManagedServices() {
            var service = createMockService("embedding", true, false);
            manager.registerService(service);

            var status = manager.getStatus();
            @SuppressWarnings("unchecked")
            var services = (java.util.List<Map<String, Object>>) status.get("managedServices");
            assertEquals(1, services.size());
            assertEquals("embedding", services.get(0).get("serviceType"));
        }

        @Test
        @DisplayName("should report active job holds")
        void reportsJobHolds() {
            manager.start();
            manager.acquireGpuForJob("job-1", "vlm", "Test VLM");

            var status = manager.getStatus();
            assertEquals(1, status.get("totalActiveJobs"));

            @SuppressWarnings("unchecked")
            var jobHolds = (java.util.List<Map<String, Object>>) status.get("activeJobHolds");
            assertEquals(1, jobHolds.size());
            assertEquals("job-1", jobHolds.get(0).get("jobId"));
            assertEquals("vlm", jobHolds.get(0).get("serviceType"));
        }

        @Test
        @DisplayName("should include GPU resource status")
        void includesGpuStatus() {
            var status = manager.getStatus();
            assertNotNull(status.get("gpuResources"));
        }
    }
}
