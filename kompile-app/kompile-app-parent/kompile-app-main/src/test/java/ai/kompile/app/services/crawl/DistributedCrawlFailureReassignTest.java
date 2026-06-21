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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2: a worker that <em>reports</em> a retriable failure has its partition re-dispatched (bounded), giving
 * reported failures the same recovery as silent worker loss — while deterministic/fatal failures still fail fast.
 */
class DistributedCrawlFailureReassignTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private ResourceSchedulerConfigService cfg(boolean reassignOnFailure, int maxReassign) {
        ResourceSchedulerConfig c = new ResourceSchedulerConfig();
        c.setExternalSchedulerMode("mock");
        c.setClusterRole("orchestrator");
        c.setClusterReassignOnFailure(reassignOnFailure);
        c.setClusterMaxReassignments(maxReassign);
        ResourceSchedulerConfigService svc = mock(ResourceSchedulerConfigService.class);
        when(svc.getConfiguration()).thenReturn(c);
        return svc;
    }

    private WorkerCapabilities liveWorker(String id) {
        return new WorkerCapabilities(id, "http://" + id, "worker", List.of("CPU"),
                0, 0L, 8, List.of("crawl"), 4, 0, 0.1, 0.0, "NOMINAL", "NOMINAL", true, 0L,
                0.3, "NOMINAL", List.of(), false);
    }

    private DistributedCrawlCoordinator coordinator(DistributedCrawlSessionRecoveryTest.CapturingDelegate d,
                                                    ResourceSchedulerConfigService cfg) {
        DistributedCrawlCoordinator c = new DistributedCrawlCoordinator(List.of(d), cfg, mapper());
        CrawlWorkerRegistry reg = mock(CrawlWorkerRegistry.class);
        when(reg.liveWorkers(anyLong())).thenReturn(List.of(liveWorker("w2")));
        ReflectionTestUtils.setField(c, "workerRegistry", reg);
        return c;
    }

    private DistributedCrawlSession startOneWorker(DistributedCrawlCoordinator c) {
        return c.startDistributed(UnifiedCrawlRequest.builder().name("f")
                .sources(List.of(UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build()))
                .distribution(DistributionConfig.builder().partitionStrategy(PartitionStrategy.PER_SOURCE).build())
                .build());
    }

    @Test
    void retriableFailureIsReassignedWhenEnabled() {
        DistributedCrawlSessionRecoveryTest.CapturingDelegate delegate =
                new DistributedCrawlSessionRecoveryTest.CapturingDelegate();
        DistributedCrawlCoordinator coordinator = coordinator(delegate, cfg(true, 2));
        DistributedCrawlSession session = startOneWorker(coordinator);
        String workerId = session.getWorkers().keySet().iterator().next();
        int dispatched = delegate.submissions.size(); // initial dispatch

        coordinator.handleWorkerCallback(session.getSessionId(), workerId, false,
                "crawl finished with status FAILED", Map.of("status", "FAILED"));

        assertEquals(dispatched + 1, delegate.submissions.size(), "retriable failure should re-dispatch");
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING,
                session.getWorkers().get(workerId).getStatus(), "reassigned worker is RUNNING again");
        assertEquals(0, session.getFailedWorkers().get());
    }

    @Test
    void failureIsTerminalWhenReassignDisabled() {
        DistributedCrawlSessionRecoveryTest.CapturingDelegate delegate =
                new DistributedCrawlSessionRecoveryTest.CapturingDelegate();
        DistributedCrawlCoordinator coordinator = coordinator(delegate, cfg(false, 2));
        DistributedCrawlSession session = startOneWorker(coordinator);
        String workerId = session.getWorkers().keySet().iterator().next();
        int dispatched = delegate.submissions.size();

        coordinator.handleWorkerCallback(session.getSessionId(), workerId, false,
                "crawl finished with status FAILED", Map.of("status", "FAILED"));

        assertEquals(dispatched, delegate.submissions.size(), "no re-dispatch when the feature is off");
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED,
                session.getWorkers().get(workerId).getStatus());
    }

    @Test
    void fatalFailureIsNotReassigned() {
        DistributedCrawlSessionRecoveryTest.CapturingDelegate delegate =
                new DistributedCrawlSessionRecoveryTest.CapturingDelegate();
        DistributedCrawlCoordinator coordinator = coordinator(delegate, cfg(true, 2));
        DistributedCrawlSession session = startOneWorker(coordinator);
        String workerId = session.getWorkers().keySet().iterator().next();
        int dispatched = delegate.submissions.size();

        coordinator.handleWorkerCallback(session.getSessionId(), workerId, false,
                "crawl job missing metadata.crawlRequestJson", Map.of());

        assertEquals(dispatched, delegate.submissions.size(), "a fatal/config failure must not retry");
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED,
                session.getWorkers().get(workerId).getStatus());
    }

    @Test
    void cancelledStatusIsNotReassigned() {
        DistributedCrawlSessionRecoveryTest.CapturingDelegate delegate =
                new DistributedCrawlSessionRecoveryTest.CapturingDelegate();
        DistributedCrawlCoordinator coordinator = coordinator(delegate, cfg(true, 2));
        DistributedCrawlSession session = startOneWorker(coordinator);
        String workerId = session.getWorkers().keySet().iterator().next();
        int dispatched = delegate.submissions.size();

        coordinator.handleWorkerCallback(session.getSessionId(), workerId, false,
                "crawl finished with status CANCELLED", Map.of("status", "CANCELLED"));

        assertEquals(dispatched, delegate.submissions.size(), "a cancellation must not retry");
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED,
                session.getWorkers().get(workerId).getStatus());
    }

    @Test
    void maxReassignmentsBoundsRetries() {
        DistributedCrawlSessionRecoveryTest.CapturingDelegate delegate =
                new DistributedCrawlSessionRecoveryTest.CapturingDelegate();
        DistributedCrawlCoordinator coordinator = coordinator(delegate, cfg(true, 0)); // no retries allowed
        DistributedCrawlSession session = startOneWorker(coordinator);
        String workerId = session.getWorkers().keySet().iterator().next();
        int dispatched = delegate.submissions.size();

        coordinator.handleWorkerCallback(session.getSessionId(), workerId, false,
                "crawl finished with status FAILED", Map.of("status", "FAILED"));

        assertEquals(dispatched, delegate.submissions.size(), "max reassignments=0 → fail immediately");
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED,
                session.getWorkers().get(workerId).getStatus());
    }

    @Test
    void classifierTreatsTransientAsRetriableAndFatalsNot() {
        assertTrue(DistributedCrawlCoordinator.isRetriableFailure(
                "crawl finished with status FAILED", Map.of("status", "FAILED")));
        assertTrue(DistributedCrawlCoordinator.isRetriableFailure("connection reset by peer", Map.of()));
        assertFalse(DistributedCrawlCoordinator.isRetriableFailure(
                "crawl finished with status CANCELLED", Map.of("status", "CANCELLED")));
        assertFalse(DistributedCrawlCoordinator.isRetriableFailure(
                "crawl job missing metadata.crawlRequestJson", Map.of()));
        assertFalse(DistributedCrawlCoordinator.isRetriableFailure("invalid source configuration", null));
        // explicit worker hint wins over heuristics
        assertFalse(DistributedCrawlCoordinator.isRetriableFailure(
                "transient blip", Map.of("retriable", false)));
        assertTrue(DistributedCrawlCoordinator.isRetriableFailure(
                "missing thing but worker says retry", Map.of("retriable", true)));
    }
}
