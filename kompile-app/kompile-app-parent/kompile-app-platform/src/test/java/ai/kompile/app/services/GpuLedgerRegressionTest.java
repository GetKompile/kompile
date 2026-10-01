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
import ai.kompile.app.services.GpuResourceManager.GpuReservation;
import ai.kompile.app.services.ModelLifecycleManager.GpuAdmission;
import ai.kompile.app.services.ModelLifecycleManager.HoldLifetime;
import ai.kompile.app.services.ModelLifecycleManager.ManagedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GPU reservation ledger ({@link GpuResourceManager}) and the admission, eviction and restore paths
 * of {@link ModelLifecycleManager} on a 24 GB + 8 GB host. The ledger must record what is really in use:
 * a job reserves what its child may use, eviction frees only what suspending a service frees, and an
 * evicted service comes back with a row. Backend-agnostic: devices are registered by hand, services are fakes.
 */
class GpuLedgerRegressionTest {

    private static final long GB = 1L << 30;

    // nvidia-smi and runtime indices differ on purpose, so using one for the other shows
    private static final GpuDevice RTX_4090 = GpuDevice.local(0, 1, "RTX 4090", 24 * GB);
    private static final GpuDevice RTX_3070_TI = GpuDevice.local(1, 0, "RTX 3070 Ti", 8 * GB);

    private GpuResourceManager grm;
    private ModelLifecycleManager mlm;

    @BeforeEach
    void setUp() {
        grm = new GpuResourceManager();
        grm.initForTesting();
        grm.registerDevice(RTX_4090);
        grm.registerDevice(RTX_3070_TI);
        mlm = new ModelLifecycleManager(grm, null, null, 1, 1, 3600);
        mlm.start();
    }

    @AfterEach
    void tearDown() {
        mlm.stop();
    }

    // ==================== Admission: a job reserves what its child may use ====================

    @Test
    void gpuJobTypesRankWithVlmAndCrawlRanksBelowEmbedding() {
        for (String gpuJobType : List.of("llm", "llmServing", "training")) {
            assertEquals(grm.getServicePriority("vlm"), grm.getServicePriority(gpuJobType), gpuJobType);
        }
        int crawl = grm.getServicePriority("unifiedCrawl");
        assertTrue(crawl > 0 && crawl < grm.getServicePriority("embedding"),
                "a crawl must never evict the embedding service its indexing phase needs, got " + crawl);
    }

    @Test
    void trainingJobEvictsARunningEmbeddingService() {
        squat("squatter-3070", RTX_3070_TI, 8 * GB);
        FakeService embedding = register("embedding", Suspend.SUCCEEDS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter-4090", RTX_4090, 18 * GB);

        GpuDevice device = mlm.acquireGpuForJob("train-1", "training", "Training");

        assertEquals(RTX_4090, device);
        assertEquals(1, embedding.suspendCalls.get());
        assertFalse(grm.hasReservation("embedding"));
        GpuReservation row = grm.getReservation("train-1").orElseThrow();
        assertEquals(RTX_4090, row.device());
        assertEquals(grm.getMemoryBudget("training"), row.reservedBytes());
    }

    @Test
    void capLargerThanEveryDeviceIsClampedToTheLargest() {
        GpuAdmission admission = mlm.admitJob("training", 40 * GB, null);

        assertTrue(admission.admitted());
        assertEquals(RTX_4090, admission.device());
        assertEquals(24 * GB, admission.reservedBytes());

        mlm.acquireGpuForJob("train-big", "training", "Training", HoldLifetime.BOUNDED, 40 * GB, null);
        assertEquals(24 * GB, grm.getReservation("train-big").orElseThrow().reservedBytes());
    }

    @Test
    void jobReservesItsCapNotTheServiceBudget() {
        GpuDevice device = mlm.acquireGpuForJob("train-2", "training", "Training",
                HoldLifetime.BOUNDED, 18 * GB, null);

        assertEquals(RTX_4090, device);
        assertEquals(18 * GB, grm.getReservation("train-2").orElseThrow().reservedBytes());
    }

    @Test
    void bigJobWaitsForTheDeviceThatCanHoldIt() {
        squat("squatter", RTX_4090, 20 * GB);

        GpuAdmission admission = mlm.admitJob("ingest", 9 * GB, null);

        // The 3070 has room for the 2 GB ingest budget, but never for a 9 GB child
        assertTrue(admission.shortfall());
        assertEquals(RTX_4090, admission.device());
        assertEquals(9 * GB, admission.reservedBytes());
        assertTrue(admission.blockReason().contains("RTX 4090"), admission.blockReason());
    }

    @Test
    void evictableMemoryCountsWhenChoosingTheDevice() {
        register("embedding", Suspend.SUCCEEDS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter-4090", RTX_4090, 15 * GB);
        squat("squatter-3070", RTX_3070_TI, 3 * GB);

        // 3070: 5 GB free, nothing evictable. 4090: 4 GB free plus embedding's 5 GB.
        GpuAdmission admission = mlm.admitJob("ingest", 6 * GB, null);

        assertTrue(admission.admitted());
        assertEquals(RTX_4090, admission.device());
        assertEquals(6 * GB, admission.reservedBytes());
        assertEquals(List.of("embedding"), admission.toEvict());
    }

    @Test
    void explicitDeviceWins() {
        GpuAdmission admission = mlm.admitJob("ingest", 6 * GB, RTX_3070_TI);

        assertTrue(admission.admitted());
        assertEquals(RTX_3070_TI, admission.device());
        assertEquals(6 * GB, admission.reservedBytes());
    }

    // ==================== Eviction: only what suspending a service frees ====================

    @Test
    void jobRowsAreNeverEvictionCandidates() {
        grm.reserveWithId("ingest-job-1", "ingest", RTX_4090, 20 * GB);

        assertTrue(grm.findEvictionCandidates("vlm", RTX_4090).isEmpty(),
                "a job's row belongs to a running child that nothing suspends");
        assertThrows(IllegalStateException.class, () -> mlm.acquireGpu("vlm"));
        assertEquals(20 * GB, grm.getReservation("ingest-job-1").orElseThrow().reservedBytes());
    }

    @Test
    void evictionReleasesOnlyTheServiceSingletonRow() {
        register("embedding", Suspend.SUCCEEDS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        grm.reserveWithId("embed-job-1", "embedding", RTX_4090, 3 * GB);

        assertEquals(RTX_4090, mlm.acquireGpu("vlm"));

        assertFalse(grm.hasReservation("embedding"));
        assertEquals(3 * GB, grm.getReservation("embed-job-1").orElseThrow().reservedBytes(),
                "suspending the service doesn't stop the job's child");
        assertEquals(18 * GB, grm.getReservation("vlm").orElseThrow().reservedBytes());
    }

    @Test
    void serviceThatFailsToSuspendKeepsItsRow() {
        register("embedding", Suspend.RETURNS_FALSE, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter", RTX_4090, 3 * GB);

        assertThrows(IllegalStateException.class, () -> mlm.acquireGpu("vlm"));

        assertEquals(5 * GB, grm.getReservation("embedding").orElseThrow().reservedBytes(),
                "a service that didn't suspend is still using its memory");
        assertFalse(grm.hasReservation("vlm"));
    }

    @Test
    void serviceWhoseSuspendThrowsKeepsItsRow() {
        register("embedding", Suspend.THROWS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter", RTX_4090, 3 * GB);

        assertThrows(IllegalStateException.class, () -> mlm.acquireGpu("vlm"));

        assertEquals(5 * GB, grm.getReservation("embedding").orElseThrow().reservedBytes());
        assertFalse(grm.hasReservation("vlm"));
    }

    @Test
    void rowOfAnUnregisteredServiceIsNotEvictable() {
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter", RTX_4090, 3 * GB);

        assertThrows(IllegalStateException.class, () -> mlm.acquireGpu("vlm"));

        assertEquals(5 * GB, grm.getReservation("embedding").orElseThrow().reservedBytes(),
                "nothing here can suspend an unregistered service");
        assertFalse(grm.hasReservation("vlm"));
    }

    @Test
    void rowOfAServiceThatIsNotRunningIsNotEvictable() {
        FakeService embedding = register("embedding", Suspend.SUCCEEDS, false);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter", RTX_4090, 3 * GB);

        assertThrows(IllegalStateException.class, () -> mlm.acquireGpu("vlm"));

        assertEquals(0, embedding.suspendCalls.get());
        assertEquals(5 * GB, grm.getReservation("embedding").orElseThrow().reservedBytes());
        assertFalse(grm.hasReservation("vlm"));
    }

    // ==================== Restore: evicted services come back with a row ====================

    @Test
    void restoredServiceThatNoLongerFitsGetsAnOverCommitRow() {
        squat("squatter-3070", RTX_3070_TI, 8 * GB);
        FakeService embedding = register("embedding", Suspend.SUCCEEDS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        squat("squatter-4090", RTX_4090, 18 * GB);
        mlm.acquireGpuForJob("ingest-1", "ingest", "Ingest");
        assertFalse(grm.hasReservation("embedding"), "the ingest job evicts embedding");
        // While the job runs, something else takes the room embedding would come back to
        squat("squatter-late", RTX_4090, 4 * GB);

        mlm.releaseGpuForJob("ingest-1");

        assertEquals(1, embedding.resumeCalls.get());
        GpuReservation row = grm.getReservation("embedding").orElseThrow(
                () -> new AssertionError("embedding resumed with no row, so its memory counts as free"));
        assertEquals(RTX_4090, row.device());
        assertEquals(5 * GB, row.reservedBytes());
        assertEquals(-3 * GB, grm.getAvailableMemory(RTX_4090));
    }

    @Test
    void failedAcquireRestoresWhatItEvicted() {
        squat("squatter-3070", RTX_3070_TI, 8 * GB);
        FakeService embedding = register("embedding", Suspend.SUCCEEDS, true);
        grm.reserve("embedding", RTX_4090, 5 * GB);
        register("modelInit", Suspend.RETURNS_FALSE, true);
        grm.reserve("modelInit", RTX_4090, 2 * GB);
        squat("squatter-4090", RTX_4090, 5 * GB);

        // The vlm job needs both evicted: embedding suspends, modelInit refuses, so the job can't fit
        assertThrows(IllegalStateException.class, () -> mlm.acquireGpuForJob("vlm-job-1", "vlm", "VLM"));

        assertEquals(1, embedding.resumeCalls.get(), "embedding was evicted for a job that never ran");
        assertTrue(embedding.isRunning());
        GpuReservation row = grm.getReservation("embedding").orElseThrow();
        assertEquals(RTX_4090, row.device());
        assertEquals(5 * GB, row.reservedBytes());
        assertEquals(2 * GB, grm.getReservation("modelInit").orElseThrow().reservedBytes());
        assertFalse(grm.hasReservation("vlm-job-1"));
        assertFalse(mlm.hasJobGpuHold("vlm-job-1"));
    }

    @Test
    void releaseWithoutAHoldStillRestoresWhatTheJobEvicted() {
        FakeService embedding = register("embedding", Suspend.SUCCEEDS, false);
        // A job whose hold is gone while its eviction record is not; no public path gets here any more
        mlm.evictedByAcquirer.put("ghost-job", new ArrayList<>(List.of("embedding")));

        mlm.releaseGpuForJob("ghost-job");

        assertEquals(1, embedding.resumeCalls.get());
        assertTrue(embedding.isRunning());
        assertTrue(grm.hasReservation("embedding"));
    }

    // ==================== Helpers ====================

    /** A row no eviction may free: a job's, at vlm priority. */
    private void squat(String id, GpuDevice device, long bytes) {
        grm.reserveWithId(id, "vlm", device, bytes);
    }

    private FakeService register(String serviceType, Suspend suspend, boolean running) {
        FakeService service = new FakeService(serviceType, suspend, running);
        mlm.registerService(service);
        return service;
    }

    private enum Suspend { SUCCEEDS, RETURNS_FALSE, THROWS }

    private static final class FakeService implements ManagedService {
        private final String serviceType;
        private final Suspend suspend;
        private final AtomicInteger suspendCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private volatile boolean running;
        private volatile boolean suspended;

        FakeService(String serviceType, Suspend suspend, boolean running) {
            this.serviceType = serviceType;
            this.suspend = suspend;
            this.running = running;
        }

        @Override
        public String getServiceType() {
            return serviceType;
        }

        @Override
        public boolean suspend(String reason) {
            suspendCalls.incrementAndGet();
            if (suspend == Suspend.THROWS) {
                throw new RuntimeException("suspend failed");
            }
            if (suspend == Suspend.RETURNS_FALSE) {
                return false;
            }
            running = false;
            suspended = true;
            return true;
        }

        @Override
        public boolean resume() {
            resumeCalls.incrementAndGet();
            running = true;
            suspended = false;
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isSuspended() {
            return suspended;
        }
    }
}
