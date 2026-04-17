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

import ai.kompile.app.config.GpuDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GpuLifecycleEvent}.
 */
@DisplayName("GpuLifecycleEvent Tests")
class GpuLifecycleEventTest {

    private static final GpuDevice GPU_4090 = GpuDevice.local(0, 1, "RTX 4090", 24L * 1024 * 1024 * 1024);
    private static final Object SOURCE = new Object();

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should store all fields")
        void storesAllFields() {
            Map<String, Object> data = Map.of("key", "value");
            GpuLifecycleEvent event = new GpuLifecycleEvent(
                    SOURCE, GpuLifecycleEvent.EventType.GPU_ACQUIRED,
                    "job-1", "vlm", GPU_4090, data);

            assertEquals(GpuLifecycleEvent.EventType.GPU_ACQUIRED, event.getEventType());
            assertEquals("job-1", event.getJobId());
            assertEquals("vlm", event.getServiceType());
            assertEquals(GPU_4090, event.getDevice());
            assertEquals(data, event.getData());
            assertSame(SOURCE, event.getSource());
        }

        @Test
        @DisplayName("should use empty map when data is null")
        void nullDataBecomesEmptyMap() {
            GpuLifecycleEvent event = new GpuLifecycleEvent(
                    SOURCE, GpuLifecycleEvent.EventType.GPU_RELEASED,
                    null, null, null, null);

            assertNotNull(event.getData());
            assertTrue(event.getData().isEmpty());
        }

        @Test
        @DisplayName("should allow null jobId, serviceType, and device")
        void allowsNullFields() {
            GpuLifecycleEvent event = new GpuLifecycleEvent(
                    SOURCE, GpuLifecycleEvent.EventType.LIFECYCLE_STARTED,
                    null, null, null, Map.of());

            assertNull(event.getJobId());
            assertNull(event.getServiceType());
            assertNull(event.getDevice());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("gpuAcquired should set type and budget data")
        void gpuAcquired() {
            GpuLifecycleEvent event = GpuLifecycleEvent.gpuAcquired(
                    SOURCE, "job-1", "vlm", GPU_4090, 18432);

            assertEquals(GpuLifecycleEvent.EventType.GPU_ACQUIRED, event.getEventType());
            assertEquals("job-1", event.getJobId());
            assertEquals("vlm", event.getServiceType());
            assertEquals(GPU_4090, event.getDevice());
            assertEquals(18432L, event.getData().get("budgetMb"));
        }

        @Test
        @DisplayName("gpuReleased should set type and heldForMs data")
        void gpuReleased() {
            GpuLifecycleEvent event = GpuLifecycleEvent.gpuReleased(
                    SOURCE, "job-1", "vlm", GPU_4090, 5000L);

            assertEquals(GpuLifecycleEvent.EventType.GPU_RELEASED, event.getEventType());
            assertEquals("job-1", event.getJobId());
            assertEquals(5000L, event.getData().get("heldForMs"));
        }

        @Test
        @DisplayName("serviceEvicted should set type and requester data")
        void serviceEvicted() {
            GpuLifecycleEvent event = GpuLifecycleEvent.serviceEvicted(
                    SOURCE, "embedding", "vlm");

            assertEquals(GpuLifecycleEvent.EventType.SERVICE_EVICTED, event.getEventType());
            assertNull(event.getJobId());
            assertEquals("embedding", event.getServiceType());
            assertNull(event.getDevice());
            assertEquals("vlm", event.getData().get("requester"));
        }

        @Test
        @DisplayName("serviceRestored should set type and device")
        void serviceRestored() {
            GpuLifecycleEvent event = GpuLifecycleEvent.serviceRestored(
                    SOURCE, "embedding", GPU_4090);

            assertEquals(GpuLifecycleEvent.EventType.SERVICE_RESTORED, event.getEventType());
            assertEquals("embedding", event.getServiceType());
            assertEquals(GPU_4090, event.getDevice());
        }

        @Test
        @DisplayName("staleJobDetected should set type and held duration")
        void staleJobDetected() {
            GpuLifecycleEvent event = GpuLifecycleEvent.staleJobDetected(
                    SOURCE, "job-old", "vlm", GPU_4090, 7200000L);

            assertEquals(GpuLifecycleEvent.EventType.STALE_JOB_DETECTED, event.getEventType());
            assertEquals("job-old", event.getJobId());
            assertEquals(7200000L, event.getData().get("heldForMs"));
        }

        @Test
        @DisplayName("staleJobReleased should set type and held duration")
        void staleJobReleased() {
            GpuLifecycleEvent event = GpuLifecycleEvent.staleJobReleased(
                    SOURCE, "job-old", "vlm", GPU_4090, 7200000L);

            assertEquals(GpuLifecycleEvent.EventType.STALE_JOB_RELEASED, event.getEventType());
            assertEquals("job-old", event.getJobId());
            assertEquals(7200000L, event.getData().get("heldForMs"));
        }

        @Test
        @DisplayName("lifecycleStarted should have no job, service, or device")
        void lifecycleStarted() {
            GpuLifecycleEvent event = GpuLifecycleEvent.lifecycleStarted(SOURCE);

            assertEquals(GpuLifecycleEvent.EventType.LIFECYCLE_STARTED, event.getEventType());
            assertNull(event.getJobId());
            assertNull(event.getServiceType());
            assertNull(event.getDevice());
            assertTrue(event.getData().isEmpty());
        }

        @Test
        @DisplayName("lifecycleStopped should have no job, service, or device")
        void lifecycleStopped() {
            GpuLifecycleEvent event = GpuLifecycleEvent.lifecycleStopped(SOURCE);

            assertEquals(GpuLifecycleEvent.EventType.LIFECYCLE_STOPPED, event.getEventType());
            assertNull(event.getJobId());
            assertNull(event.getServiceType());
            assertNull(event.getDevice());
            assertTrue(event.getData().isEmpty());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include event type, jobId, service, and device name")
        void includesAllFields() {
            GpuLifecycleEvent event = GpuLifecycleEvent.gpuAcquired(
                    SOURCE, "job-1", "vlm", GPU_4090, 18432);
            String str = event.toString();

            assertTrue(str.contains("GPU_ACQUIRED"));
            assertTrue(str.contains("job-1"));
            assertTrue(str.contains("vlm"));
            assertTrue(str.contains("RTX 4090"));
        }

        @Test
        @DisplayName("should handle null device gracefully")
        void handlesNullDevice() {
            GpuLifecycleEvent event = GpuLifecycleEvent.lifecycleStarted(SOURCE);
            String str = event.toString();

            assertTrue(str.contains("LIFECYCLE_STARTED"));
            assertTrue(str.contains("null")); // device is null
        }
    }

    @Nested
    @DisplayName("EventType enum")
    class EventTypeTests {

        @Test
        @DisplayName("should have 8 event types")
        void hasAllEventTypes() {
            assertEquals(8, GpuLifecycleEvent.EventType.values().length);
        }

        @Test
        @DisplayName("should include all expected types")
        void includesExpectedTypes() {
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("GPU_ACQUIRED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("GPU_RELEASED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("SERVICE_EVICTED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("SERVICE_RESTORED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("STALE_JOB_DETECTED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("STALE_JOB_RELEASED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("LIFECYCLE_STARTED"));
            assertNotNull(GpuLifecycleEvent.EventType.valueOf("LIFECYCLE_STOPPED"));
        }
    }
}
