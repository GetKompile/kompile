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

package ai.kompile.app.services.cluster;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CrawlWorkerRegistryTest {

    @Mock
    private ResourceSchedulerConfigService configService;
    @InjectMocks
    private CrawlWorkerRegistry registry;

    @BeforeEach
    void setUp() {
        // Defaults: clusterWorkerTimeoutSeconds = 45.
        lenient().when(configService.getConfiguration()).thenReturn(ResourceSchedulerConfig.defaults());
    }

    private WorkerCapabilities worker(String id, int freeSlots, boolean gpu, double cpuLoad, List<String> jobs) {
        return new WorkerCapabilities(
                id, "http://" + id, "worker",
                gpu ? List.of("CPU", "CUDA") : List.of("CPU"),
                gpu ? 1 : 0, gpu ? 8_000_000_000L : 0L, 8,
                jobs, freeSlots, 0, cpuLoad, 0.1, "NOMINAL", "NOMINAL", true, 0L);
    }

    @Test
    void registersAndListsLiveWorkers() {
        registry.registerOrHeartbeat(worker("w1", 4, true, 0.2, List.of("ingest", "graph")), 1_000L);
        registry.registerOrHeartbeat(worker("w2", 2, false, 0.5, List.of("ingest")), 1_000L);
        assertEquals(2, registry.size(1_000L));
        assertEquals(2, registry.liveWorkers(1_000L).size());
    }

    @Test
    void evictsWorkersPastHeartbeatTimeout() {
        registry.registerOrHeartbeat(worker("w1", 4, true, 0.2, List.of("ingest")), 1_000L);
        long past = 1_000L + 46_000L; // 46s > 45s timeout
        assertEquals(0, registry.size(past));
        assertTrue(registry.liveWorkers(past).isEmpty());
    }

    @Test
    void heartbeatKeepsWorkerAlive() {
        registry.registerOrHeartbeat(worker("w1", 4, true, 0.2, List.of("ingest")), 1_000L);
        registry.registerOrHeartbeat(worker("w1", 3, true, 0.3, List.of("ingest")), 40_000L); // refresh
        assertEquals(1, registry.size(50_000L)); // 50s - 40s = 10s < 45s -> still alive
    }

    @Test
    void selectsCapableWorkerWithMostFreeSlots() {
        registry.registerOrHeartbeat(worker("busy", 1, true, 0.2, List.of("ingest")), 1_000L);
        registry.registerOrHeartbeat(worker("free", 5, true, 0.2, List.of("ingest")), 1_000L);
        Optional<WorkerCapabilities> pick = registry.selectWorker("ingest", false, 1_000L);
        assertTrue(pick.isPresent());
        assertEquals("free", pick.get().workerId());
    }

    @Test
    void skipsWorkersThatCannotRunTheJob() {
        registry.registerOrHeartbeat(worker("cpuonly", 5, false, 0.1, List.of("vectorPopulation")), 1_000L);
        // Job needs a GPU but this worker is CPU-only.
        assertTrue(registry.selectWorker("vectorPopulation", true, 1_000L).isEmpty());
        // Job type not advertised.
        assertTrue(registry.selectWorker("graph", false, 1_000L).isEmpty());
        // Supported and no GPU needed -> selectable.
        assertTrue(registry.selectWorker("vectorPopulation", false, 1_000L).isPresent());
    }

    @Test
    void deregisterRemovesWorker() {
        registry.registerOrHeartbeat(worker("w1", 4, true, 0.2, List.of("ingest")), 1_000L);
        assertTrue(registry.deregister("w1"));
        assertEquals(0, registry.size(1_000L));
    }

    @Test
    void rejectsBlankWorkerId() {
        WorkerCapabilities bad = worker("", 4, true, 0.2, List.of("ingest"));
        assertThrows(IllegalArgumentException.class, () -> registry.registerOrHeartbeat(bad, 1_000L));
    }
}
