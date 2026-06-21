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

package ai.kompile.app.services.crawl;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.cluster.WorkerCapabilities;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.DistributionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.PartitionStrategy;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartitionLossReaperTest {

    private ResourceSchedulerConfig cfg(boolean reassign, int maxReassign, int timeoutSec) {
        ResourceSchedulerConfig c = new ResourceSchedulerConfig();
        c.setClusterRole("orchestrator");
        c.setClusterReassignOnLoss(reassign);
        c.setClusterMaxReassignments(maxReassign);
        c.setClusterPartitionProgressTimeoutSeconds(timeoutSec);
        return c;
    }

    private WorkerCapabilities crawlWorker(String id) {
        return new WorkerCapabilities(id, "http://" + id, "worker", List.of("CPU"),
                0, 0L, 4, List.of("crawl"), 4, 0, 0.1, 0.0, "NOMINAL", "NOMINAL", true, 0L,
                0.3, "NOMINAL", List.of(), false);
    }

    private WorkerCapabilities crawlWorkerGc(String id, double gcOverhead) {
        return new WorkerCapabilities(id, "http://" + id, "worker", List.of("CPU"),
                0, 0L, 4, List.of("crawl"), 4, 0, 0.1, 0.0, "NOMINAL", "NOMINAL", true, 0L,
                0.3, "NOMINAL", List.of(), false, gcOverhead);
    }

    private record Fixture(DistributedCrawlCoordinator coordinator,
                           PartitionLossReaper reaper,
                           DistributedCrawlCoordinatorTest.MockExternalDelegate delegate,
                           CrawlWorkerRegistry registry) {
    }

    private Fixture fixture(ResourceSchedulerConfig config) {
        DistributedCrawlCoordinatorTest.MockExternalDelegate delegate =
                new DistributedCrawlCoordinatorTest.MockExternalDelegate();
        ResourceSchedulerConfigService cfgService = mock(ResourceSchedulerConfigService.class);
        when(cfgService.getConfiguration()).thenReturn(config);
        DistributedCrawlCoordinator coordinator =
                new DistributedCrawlCoordinator(List.of(delegate), cfgService, new ObjectMapper());
        CrawlWorkerRegistry registry = mock(CrawlWorkerRegistry.class);
        ReflectionTestUtils.setField(coordinator, "workerRegistry", registry);
        PartitionLossReaper reaper = new PartitionLossReaper(coordinator, cfgService);
        ReflectionTestUtils.setField(reaper, "registry", registry);
        return new Fixture(coordinator, reaper, delegate, registry);
    }

    private DistributedCrawlSession startSession(DistributedCrawlCoordinator coordinator) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name("reaper test")
                .sources(List.of(UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()))
                .distribution(DistributionConfig.builder().partitionStrategy(PartitionStrategy.PER_SOURCE).build())
                .build();
        DistributedCrawlSession s = coordinator.startDistributed(req);
        // The synchronous mock delegate completes its submit future before addWorker runs, so workerDispatched
        // is a no-op and workers stay DISPATCHING with null externalRef/lastProgressAt. In production the async
        // dispatch sets these. Simulate a fully-dispatched, running worker so the reaper is exercised.
        s.getWorkers().values().forEach(w -> {
            w.setStatus(DistributedCrawlSession.WorkerStatus.RUNNING);
            w.setExternalRef("ext-" + w.getWorkerId());
            w.setLastProgressAt(Instant.now());
        });
        return s;
    }

    private DistributedCrawlSession.WorkerInfo onlyWorker(DistributedCrawlSession s) {
        return s.getWorkers().values().iterator().next();
    }

    @Test
    void disabledIsNoOp() {
        Fixture f = fixture(cfg(false, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        w.setLastProgressAt(Instant.now().minusSeconds(10_000));
        when(f.registry().liveWorkers(anyLong())).thenReturn(List.of());
        f.reaper().scan();
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING, w.getStatus());
        assertEquals(0, w.getReassignmentCount());
    }

    @Test
    void evictedWorkerReassignedToAnotherWorker() {
        Fixture f = fixture(cfg(true, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        int submitsBefore = f.delegate().submittedJobs.size();
        when(f.registry().liveWorkers(anyLong())).thenReturn(List.of(crawlWorker("target")));
        f.reaper().scan();
        assertEquals(1, w.getReassignmentCount());
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING, w.getStatus());
        assertTrue(f.delegate().submittedJobs.size() > submitsBefore, "a reassignment job was dispatched");
    }

    @Test
    void noTargetMarksFailed() {
        Fixture f = fixture(cfg(true, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        when(f.registry().liveWorkers(anyLong())).thenReturn(List.of()); // evicted + no reassignment target
        f.reaper().scan();
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED, w.getStatus());
        assertEquals(1, s.getFailedWorkers().get());
    }

    @Test
    void maxReassignmentsFails() {
        Fixture f = fixture(cfg(true, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        w.setReassignmentCount(2); // already at the cap
        when(f.registry().liveWorkers(anyLong())).thenReturn(List.of(crawlWorker("target")));
        f.reaper().scan();
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED, w.getStatus());
    }

    @Test
    void progressTimeoutTriggersReassignment() {
        Fixture f = fixture(cfg(true, 2, 60));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        // Worker is still live (ext ref contains the live worker's baseUrl) but hasn't progressed in 10 min.
        w.setExternalRef("http://w0");
        w.setLastProgressAt(Instant.now().minusSeconds(600));
        when(f.registry().liveWorkers(anyLong()))
                .thenReturn(List.of(crawlWorker("w0"), crawlWorker("target")));
        f.reaper().scan();
        assertEquals(1, w.getReassignmentCount());
    }

    @Test
    void completedWorkerNotReassigned() {
        Fixture f = fixture(cfg(true, 2, 60));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        f.coordinator().handleWorkerCallback(s.getSessionId(), w.getWorkerId(), true, "done", null);
        assertEquals(DistributedCrawlSession.WorkerStatus.COMPLETED, w.getStatus());
        when(f.registry().liveWorkers(anyLong())).thenReturn(List.of());
        f.reaper().scan();
        assertEquals(DistributedCrawlSession.WorkerStatus.COMPLETED, w.getStatus());
        assertEquals(0, w.getReassignmentCount());
    }

    @Test
    void gcStallReapsBeforeProgressTimeout() {
        // 300s progress timeout, but defaults give a 60s fast-stall at gc >= 0.5.
        Fixture f = fixture(cfg(true, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        w.setExternalRef("http://w0");
        w.setLastProgressAt(Instant.now().minusSeconds(90)); // > fast-stall(60s), < progress-timeout(300s)
        when(f.registry().liveWorkers(anyLong()))
                .thenReturn(List.of(crawlWorkerGc("w0", 0.7), crawlWorker("target")));
        f.reaper().scan();
        assertEquals(1, w.getReassignmentCount(), "a GC-churning, non-progressing worker is reaped early");
    }

    @Test
    void lowGcNotReapedWithinProgressTimeout() {
        Fixture f = fixture(cfg(true, 2, 300));
        DistributedCrawlSession s = startSession(f.coordinator());
        DistributedCrawlSession.WorkerInfo w = onlyWorker(s);
        w.setExternalRef("http://w0");
        w.setLastProgressAt(Instant.now().minusSeconds(90)); // stale but under the 300s timeout
        when(f.registry().liveWorkers(anyLong()))
                .thenReturn(List.of(crawlWorkerGc("w0", 0.1), crawlWorker("target"))); // healthy GC
        f.reaper().scan();
        assertEquals(0, w.getReassignmentCount(), "a healthy worker under the progress timeout is left alone");
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING, w.getStatus());
    }
}
