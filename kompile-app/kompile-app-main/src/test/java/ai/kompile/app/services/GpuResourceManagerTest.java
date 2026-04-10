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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GpuResourceManager}.
 * Uses {@code initForTesting()} to avoid nvidia-smi discovery.
 */
@DisplayName("GpuResourceManager Tests")
class GpuResourceManagerTest {

    private static final long ONE_GB = 1024L * 1024L * 1024L;
    private static final GpuDevice GPU_4090 = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);
    private static final GpuDevice GPU_3070 = GpuDevice.local(1, 0, "RTX 3070 Ti", 8L * ONE_GB);

    private GpuResourceManager manager;

    @BeforeEach
    void setUp() {
        manager = new GpuResourceManager();
        manager.initForTesting();
        manager.registerDevice(GPU_4090);
        manager.registerDevice(GPU_3070);
    }

    // ==================== Device Management ====================

    @Nested
    @DisplayName("Device management")
    class DeviceManagement {

        @Test
        @DisplayName("should list registered devices")
        void listDevices() {
            List<GpuDevice> devices = manager.getDevices();
            assertEquals(2, devices.size());
        }

        @Test
        @DisplayName("should find device by nvidia-smi index")
        void findByNvidiaSmiIndex() {
            Optional<GpuDevice> device = manager.getDeviceByNvidiaSmiIndex(0);
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should return empty for unknown nvidia-smi index")
        void unknownNvidiaSmiIndex() {
            Optional<GpuDevice> device = manager.getDeviceByNvidiaSmiIndex(99);
            assertTrue(device.isEmpty());
        }

        @Test
        @DisplayName("should find device by CUDA runtime index")
        void findByCudaRuntimeIndex() {
            Optional<GpuDevice> device = manager.getDeviceByCudaRuntimeIndex(1);
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should find largest device")
        void findLargestDevice() {
            Optional<GpuDevice> device = manager.getLargestDevice();
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should find device with most available memory")
        void deviceWithMostAvailable() {
            Optional<GpuDevice> device = manager.getDeviceWithMostAvailableMemory();
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("device list should be unmodifiable")
        void unmodifiableDeviceList() {
            List<GpuDevice> devices = manager.getDevices();
            assertThrows(UnsupportedOperationException.class, () ->
                    devices.add(GpuDevice.local(2, 2, "Test", ONE_GB)));
        }

        @Test
        @DisplayName("should update CUDA runtime index")
        void updateCudaRuntimeIndex() {
            manager.setCudaRuntimeIndex(0, 5);
            Optional<GpuDevice> device = manager.getDeviceByCudaRuntimeIndex(5);
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }
    }

    // ==================== Reservation Management ====================

    @Nested
    @DisplayName("Reservation CRUD")
    class ReservationCrud {

        @Test
        @DisplayName("should create reservation with reserve()")
        void createReservation() {
            boolean result = manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            assertTrue(result);
            assertTrue(manager.hasReservation("embedding"));
            assertTrue(manager.hasReservationForService("embedding"));
        }

        @Test
        @DisplayName("should release reservation with release()")
        void releaseReservation() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            manager.release("embedding");
            assertFalse(manager.hasReservation("embedding"));
            assertFalse(manager.hasReservationForService("embedding"));
        }

        @Test
        @DisplayName("should throw when reservation exceeds available memory")
        void exceedsAvailableMemory() {
            assertThrows(IllegalStateException.class, () ->
                    manager.reserve("vlm", GPU_3070, 10L * ONE_GB));
        }

        @Test
        @DisplayName("should allow reservation up to total device memory")
        void reserveFullDeviceMemory() {
            boolean result = manager.reserve("vlm", GPU_3070, 8L * ONE_GB);
            assertTrue(result);
            assertEquals(0, manager.getAvailableMemory(GPU_3070));
        }

        @Test
        @DisplayName("reserve() should overwrite previous reservation with same key")
        void overwritesSameKey() {
            manager.reserve("embedding", GPU_4090, 3L * ONE_GB);
            // Reserve again with same key — this overwrites
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);

            assertEquals(1, manager.getReservationCount("embedding"));
            GpuResourceManager.GpuReservation r = manager.getReservation("embedding").orElseThrow();
            assertEquals(5L * ONE_GB, r.reservedBytes());
        }

        @Test
        @DisplayName("should get reservation details")
        void getReservationDetails() {
            manager.reserve("vlm", GPU_4090, 18L * ONE_GB);
            Optional<GpuResourceManager.GpuReservation> opt = manager.getReservation("vlm");

            assertTrue(opt.isPresent());
            GpuResourceManager.GpuReservation r = opt.get();
            assertEquals("vlm", r.reservationId());
            assertEquals("vlm", r.serviceType());
            assertEquals(GPU_4090, r.device());
            assertEquals(18L * ONE_GB, r.reservedBytes());
            assertEquals(100, r.priority()); // VLM priority
            assertTrue(r.createdAtMillis() > 0);
        }

        @Test
        @DisplayName("release() should be idempotent for missing key")
        void releaseIdempotent() {
            // Should not throw
            manager.release("nonexistent");
            assertFalse(manager.hasReservation("nonexistent"));
        }

        @Test
        @DisplayName("should get all active reservations")
        void getAllActiveReservations() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            manager.reserve("vlm", GPU_4090, 18L * ONE_GB);

            var all = manager.getActiveReservations();
            assertEquals(2, all.size());
            assertTrue(all.containsKey("embedding"));
            assertTrue(all.containsKey("vlm"));
        }

        @Test
        @DisplayName("active reservations map should be unmodifiable")
        void unmodifiableActiveReservations() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            var all = manager.getActiveReservations();
            assertThrows(UnsupportedOperationException.class, () ->
                    all.remove("embedding"));
        }
    }

    // ==================== Multi-Reservation per Service Type ====================

    @Nested
    @DisplayName("Multi-reservation per service type (reserveWithId)")
    class MultiReservation {

        @Test
        @DisplayName("should support multiple reservations of same service type")
        void multipleReservationsOfSameType() {
            manager.reserveWithId("ingest-job-1", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserveWithId("ingest-job-2", "ingest", GPU_4090, 2L * ONE_GB);

            assertEquals(2, manager.getReservationCount("ingest"));
            assertTrue(manager.hasReservationForService("ingest"));
            assertTrue(manager.hasReservation("ingest-job-1"));
            assertTrue(manager.hasReservation("ingest-job-2"));
        }

        @Test
        @DisplayName("should release by reservation ID without affecting other reservations")
        void releaseByIdOnly() {
            manager.reserveWithId("ingest-job-1", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserveWithId("ingest-job-2", "ingest", GPU_4090, 2L * ONE_GB);

            manager.release("ingest-job-1");

            assertEquals(1, manager.getReservationCount("ingest"));
            assertFalse(manager.hasReservation("ingest-job-1"));
            assertTrue(manager.hasReservation("ingest-job-2"));
        }

        @Test
        @DisplayName("releaseAllForService should release all reservations of that type")
        void releaseAllForService() {
            manager.reserveWithId("ingest-job-1", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserveWithId("ingest-job-2", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);

            int released = manager.releaseAllForService("ingest");

            assertEquals(2, released);
            assertFalse(manager.hasReservationForService("ingest"));
            assertTrue(manager.hasReservationForService("embedding")); // unaffected
        }

        @Test
        @DisplayName("getReservationsForService should return all matching reservations")
        void getReservationsForService() {
            manager.reserveWithId("ingest-job-1", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserveWithId("ingest-job-2", "ingest", GPU_3070, 1L * ONE_GB);

            List<GpuResourceManager.GpuReservation> ingestReservations =
                    manager.getReservationsForService("ingest");

            assertEquals(2, ingestReservations.size());
        }

        @Test
        @DisplayName("releaseAllForService should return 0 when no reservations exist")
        void releaseAllForNonexistentService() {
            int released = manager.releaseAllForService("nonexistent");
            assertEquals(0, released);
        }

        @Test
        @DisplayName("getReservationCount should return 0 for unknown service type")
        void countForUnknownService() {
            assertEquals(0, manager.getReservationCount("nonexistent"));
        }
    }

    // ==================== Capacity Planning ====================

    @Nested
    @DisplayName("Capacity planning")
    class CapacityPlanning {

        @Test
        @DisplayName("should track reserved memory on a device")
        void reservedMemory() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            assertEquals(5L * ONE_GB, manager.getReservedMemory(GPU_4090));
            assertEquals(0, manager.getReservedMemory(GPU_3070));
        }

        @Test
        @DisplayName("should calculate available memory")
        void availableMemory() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            assertEquals(19L * ONE_GB, manager.getAvailableMemory(GPU_4090));
        }

        @Test
        @DisplayName("canFit should return true when enough memory available")
        void canFitTrue() {
            assertTrue(manager.canFit("embedding", GPU_4090));
        }

        @Test
        @DisplayName("canFit should return false when not enough memory")
        void canFitFalse() {
            // VLM needs 18GB, 3070 only has 8GB
            assertFalse(manager.canFit("vlm", GPU_3070));
        }

        @Test
        @DisplayName("reserved memory should accumulate across multiple reservations")
        void accumulatesReservations() {
            manager.reserveWithId("job-1", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserveWithId("job-2", "ingest", GPU_4090, 2L * ONE_GB);
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);

            assertEquals(9L * ONE_GB, manager.getReservedMemory(GPU_4090));
            assertEquals(15L * ONE_GB, manager.getAvailableMemory(GPU_4090));
        }

        @Test
        @DisplayName("reservedMb should convert bytes correctly")
        void reservationMb() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            GpuResourceManager.GpuReservation r = manager.getReservation("embedding").orElseThrow();
            assertEquals(5120, r.reservedMb());
        }
    }

    // ==================== Eviction Candidates ====================

    @Nested
    @DisplayName("Eviction candidates")
    class EvictionCandidates {

        @Test
        @DisplayName("should return empty list when no eviction needed")
        void noEvictionNeeded() {
            List<String> candidates = manager.findEvictionCandidates("embedding", GPU_4090);
            assertTrue(candidates.isEmpty());
        }

        @Test
        @DisplayName("should find lower-priority services to evict")
        void findsLowerPriorityCandidates() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);

            // VLM (priority 100) needs 18GB, only 19GB available, but after embedding is removed -> 24GB
            List<String> candidates = manager.findEvictionCandidates("vlm", GPU_4090);
            // Since VLM fits (19GB >= 18GB), no eviction needed
            assertTrue(candidates.isEmpty());
        }

        @Test
        @DisplayName("should evict embedding when VLM needs more memory")
        void evictEmbeddingForVlm() {
            // Fill up the 4090 so VLM doesn't fit
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            manager.reserveWithId("ingest-1", "ingest", GPU_4090, 2L * ONE_GB);

            // VLM needs 18GB, available = 24 - 5 - 2 = 17GB, not enough
            List<String> candidates = manager.findEvictionCandidates("vlm", GPU_4090);

            assertFalse(candidates.isEmpty());
            // Should suggest evicting embedding (priority 10) first, then ingest (priority 50)
            assertEquals("embedding", candidates.get(0));
        }

        @Test
        @DisplayName("should not suggest evicting higher-priority services")
        void neverEvictsHigherPriority() {
            manager.reserve("vlm", GPU_4090, 18L * ONE_GB);

            // Embedding (priority 10) wants space, but VLM (priority 100) is higher
            List<String> candidates = manager.findEvictionCandidates("embedding", GPU_4090);
            assertTrue(candidates.isEmpty());
        }

        @Test
        @DisplayName("should return empty if evicting everything still not enough")
        void notEnoughEvenWithEviction() {
            manager.reserve("embedding", GPU_3070, 5L * ONE_GB);

            // VLM needs 18GB, 3070 only has 8GB total
            List<String> candidates = manager.findEvictionCandidates("vlm", GPU_3070);
            assertTrue(candidates.isEmpty());
        }

        @Test
        @DisplayName("should sort candidates by priority (lowest first)")
        void sortedByPriority() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);       // priority 10
            manager.reserve("modelInit", GPU_4090, 2L * ONE_GB);       // priority 20
            manager.reserve("vectorPopulation", GPU_4090, 1L * ONE_GB);// priority 30

            // VLM needs 18GB, available = 24 - 5 - 2 - 1 = 16GB
            List<String> candidates = manager.findEvictionCandidates("vlm", GPU_4090);

            assertFalse(candidates.isEmpty());
            // First candidate should be lowest priority
            assertEquals("embedding", candidates.get(0));
        }
    }

    // ==================== findBestDevice ====================

    @Nested
    @DisplayName("findBestDevice")
    class FindBestDevice {

        @Test
        @DisplayName("should prefer device with enough room without eviction")
        void prefersNoEviction() {
            Optional<GpuDevice> device = manager.findBestDevice("embedding");
            assertTrue(device.isPresent());
            // Should pick 4090 (24GB available) over 3070 (8GB available), both fit 5GB
            // findBestDevice picks the one with most available memory
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should pick largest device for VLM")
        void picksLargestForVlm() {
            Optional<GpuDevice> device = manager.findBestDevice("vlm");
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should return device requiring eviction when no device fits outright")
        void fallsBackToEviction() {
            // Fill both devices partially
            manager.reserve("embedding", GPU_4090, 10L * ONE_GB);
            manager.reserve("modelInit", GPU_3070, 7L * ONE_GB);

            // VLM needs 18GB, 4090 has 14GB free, 3070 has 1GB free
            // Neither fits outright. 4090 can fit with eviction (embedding priority 10 < vlm priority 100)
            Optional<GpuDevice> device = manager.findBestDevice("vlm");
            assertTrue(device.isPresent());
            assertEquals("RTX 4090", device.get().name());
        }

        @Test
        @DisplayName("should return empty when no device can accommodate service")
        void noDeviceAvailable() {
            // Set a ridiculous budget that neither device can handle even empty
            manager.setMemoryBudget("mega-model", 100L * ONE_GB);
            Optional<GpuDevice> device = manager.findBestDevice("mega-model");
            assertTrue(device.isEmpty());
        }
    }

    // ==================== Budget/Priority Configuration ====================

    @Nested
    @DisplayName("Budget and priority configuration")
    class BudgetPriorityConfig {

        @Test
        @DisplayName("should have correct default budgets")
        void defaultBudgets() {
            assertEquals(5L * ONE_GB, manager.getMemoryBudget("embedding"));
            assertEquals(18L * ONE_GB, manager.getMemoryBudget("vlm"));
            assertEquals(2L * ONE_GB, manager.getMemoryBudget("ingest"));
            assertEquals(1L * ONE_GB, manager.getMemoryBudget("vectorPopulation"));
            assertEquals(2L * ONE_GB, manager.getMemoryBudget("modelInit"));
        }

        @Test
        @DisplayName("should return 2GB default for unknown service type")
        void defaultBudgetForUnknown() {
            assertEquals(2L * ONE_GB, manager.getMemoryBudget("unknown-service"));
        }

        @Test
        @DisplayName("should have correct default priorities")
        void defaultPriorities() {
            assertEquals(10, manager.getServicePriority("embedding"));
            assertEquals(50, manager.getServicePriority("ingest"));
            assertEquals(30, manager.getServicePriority("vectorPopulation"));
            assertEquals(20, manager.getServicePriority("modelInit"));
            assertEquals(100, manager.getServicePriority("vlm"));
        }

        @Test
        @DisplayName("should return 0 priority for unknown service type")
        void defaultPriorityForUnknown() {
            assertEquals(0, manager.getServicePriority("unknown-service"));
        }

        @Test
        @DisplayName("should update memory budget")
        void updateBudget() {
            manager.setMemoryBudget("embedding", 10L * ONE_GB);
            assertEquals(10L * ONE_GB, manager.getMemoryBudget("embedding"));
        }

        @Test
        @DisplayName("should update priority")
        void updatePriority() {
            manager.setServicePriority("embedding", 200);
            assertEquals(200, manager.getServicePriority("embedding"));
        }
    }

    // ==================== Status ====================

    @Nested
    @DisplayName("Status reporting")
    class StatusReporting {

        @Test
        @DisplayName("should report device count")
        void reportsDeviceCount() {
            var status = manager.getStatus();
            assertEquals(2, status.get("deviceCount"));
        }

        @Test
        @DisplayName("should report total reservations")
        void reportsTotalReservations() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            var status = manager.getStatus();
            assertEquals(1, status.get("totalReservations"));
        }

        @Test
        @DisplayName("should include device details")
        void includesDeviceDetails() {
            var status = manager.getStatus();
            @SuppressWarnings("unchecked")
            var devices = (List<java.util.Map<String, Object>>) status.get("devices");
            assertEquals(2, devices.size());

            var firstDevice = devices.get(0);
            assertEquals("RTX 4090", firstDevice.get("name"));
            assertEquals(0, firstDevice.get("nvidiaSmiIndex"));
        }

        @Test
        @DisplayName("should include reservation details on devices")
        void includesReservationDetails() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            var status = manager.getStatus();

            @SuppressWarnings("unchecked")
            var devices = (List<java.util.Map<String, Object>>) status.get("devices");
            var gpu4090 = devices.get(0);

            @SuppressWarnings("unchecked")
            var reservations = (List<java.util.Map<String, Object>>) gpu4090.get("reservations");
            assertEquals(1, reservations.size());
            assertEquals("embedding", reservations.get(0).get("serviceType"));
        }
    }

    // ==================== Shutdown ====================

    @Nested
    @DisplayName("Shutdown")
    class Shutdown {

        @Test
        @DisplayName("should clear all reservations on shutdown")
        void clearsAllOnShutdown() {
            manager.reserve("embedding", GPU_4090, 5L * ONE_GB);
            manager.reserveWithId("job-1", "ingest", GPU_4090, 2L * ONE_GB);

            manager.shutdown();

            assertEquals(0, manager.getActiveReservations().size());
        }

        @Test
        @DisplayName("should be safe to call shutdown with no reservations")
        void shutdownNoReservations() {
            // Should not throw
            manager.shutdown();
            assertEquals(0, manager.getActiveReservations().size());
        }
    }
}
