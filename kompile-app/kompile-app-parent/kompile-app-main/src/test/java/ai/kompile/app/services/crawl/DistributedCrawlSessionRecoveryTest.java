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
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 recovery behavior: checkpoint-aware reassignment (don't re-crawl already-completed sources) and
 * startup reconcile of persisted sessions against the live scheduler (resume / complete / fail).
 */
class DistributedCrawlSessionRecoveryTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** Delegate that captures submitted metadata and returns a scriptable job status. */
    static class CapturingDelegate implements ExternalJobSchedulerDelegate {
        final List<Map<String, Object>> submissions = new CopyOnWriteArrayList<>();
        volatile String statusToReturn = "RUNNING";

        @Override public String getMode() { return "mock"; }

        @Override
        public CompletableFuture<ExternalJobRef> submitJob(String jobId, String jobType, String description,
                                                           JobResourceProfile profile, Map<String, Object> meta) {
            submissions.add(meta);
            return CompletableFuture.completedFuture(new ExternalJobRef("ext-" + jobId, "PENDING", "ok"));
        }

        @Override
        public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(new ExternalJobStatus(externalRef, statusToReturn, "", Map.of()));
        }

        @Override public boolean isAvailable() { return true; }
    }

    private ResourceSchedulerConfigService cfgService(boolean reassignOnLoss, boolean orchestrator) {
        ResourceSchedulerConfig cfg = new ResourceSchedulerConfig();
        cfg.setExternalSchedulerMode("mock");
        cfg.setClusterReassignOnLoss(reassignOnLoss);
        cfg.setClusterRole(orchestrator ? "orchestrator" : "worker");
        // clusterSessionPersistenceEnabled defaults true
        ResourceSchedulerConfigService svc = mock(ResourceSchedulerConfigService.class);
        when(svc.getConfiguration()).thenReturn(cfg);
        return svc;
    }

    private CrawlWorkerRegistry registryWith(WorkerCapabilities... workers) {
        CrawlWorkerRegistry reg = mock(CrawlWorkerRegistry.class);
        when(reg.liveWorkers(anyLong())).thenReturn(List.of(workers));
        return reg;
    }

    private WorkerCapabilities liveCrawlWorker(String id, String baseUrl) {
        return new WorkerCapabilities(id, baseUrl, "worker", List.of("CPU"),
                0, 0L, 8, List.of("crawl"), 4, 0, 0.1, 0.0, "NOMINAL", "NOMINAL", true, 0L,
                0.3, "NOMINAL", List.of(), false);
    }

    private UnifiedCrawlSource src(String label, String path) {
        return UnifiedCrawlSource.builder().label(label).pathOrUrl(path).build();
    }

    private DistributedCrawlSession runningSession(String id, List<UnifiedCrawlSource> sources, String externalRef) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder().name("r-" + id).sources(sources).build();
        DistributedCrawlSession s = DistributedCrawlSession.builder()
                .sessionId(id).originalRequest(req).status(DistributedCrawlSession.Status.RUNNING)
                .totalWorkers(1).startedAt(Instant.now()).build();
        s.addWorker(id + "-worker-0", sources);
        s.workerDispatched(id + "-worker-0", externalRef);
        return s;
    }

    // ---- checkpoint-aware reassignment ----

    @Test
    void reassignmentSkipsAlreadyCompletedSources() {
        CapturingDelegate delegate = new CapturingDelegate();
        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(delegate), cfgService(true, true), mapper());
        ReflectionTestUtils.setField(coordinator, "workerRegistry",
                registryWith(liveCrawlWorker("w2", "http://w2")));

        DistributedCrawlSession session =
                runningSession("sx", List.of(src("A", "a"), src("B", "b")), "ext-old");
        DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get("sx-worker-0");
        worker.setLatestSnapshot(UnifiedCrawlJob.ProgressSnapshot.builder()
                .sourceProgress(List.of(
                        UnifiedCrawlJob.SourceProgress.builder().label("A").pathOrUrl("a")
                                .status(UnifiedCrawlJob.Status.COMPLETED).build(),
                        UnifiedCrawlJob.SourceProgress.builder().label("B").pathOrUrl("b")
                                .status(UnifiedCrawlJob.Status.RUNNING).build()))
                .build());

        assertTrue(coordinator.reassignWorkerPartition(session, worker));

        assertEquals(1, delegate.submissions.size(), "should re-dispatch exactly once");
        String json = (String) delegate.submissions.get(0).get("crawlRequestJson");
        assertNotNull(json);
        assertTrue(json.contains("\"label\":\"B\""), "remaining source B must be re-crawled: " + json);
        assertFalse(json.contains("\"label\":\"A\""), "completed source A must be dropped: " + json);
    }

    @Test
    void reassignmentWithAllSourcesCompletedMarksDoneWithoutRedispatch() {
        CapturingDelegate delegate = new CapturingDelegate();
        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(delegate), cfgService(true, true), mapper());
        ReflectionTestUtils.setField(coordinator, "workerRegistry",
                registryWith(liveCrawlWorker("w2", "http://w2")));

        DistributedCrawlSession session =
                runningSession("sy", List.of(src("A", "a"), src("B", "b")), "ext-old");
        DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get("sy-worker-0");
        worker.setLatestSnapshot(UnifiedCrawlJob.ProgressSnapshot.builder()
                .sourceProgress(List.of(
                        UnifiedCrawlJob.SourceProgress.builder().label("A").pathOrUrl("a")
                                .status(UnifiedCrawlJob.Status.COMPLETED).build(),
                        UnifiedCrawlJob.SourceProgress.builder().label("B").pathOrUrl("b")
                                .status(UnifiedCrawlJob.Status.COMPLETED).build()))
                .build());

        assertTrue(coordinator.reassignWorkerPartition(session, worker));
        assertEquals(0, delegate.submissions.size(), "nothing left to crawl → no re-dispatch");
        assertEquals(DistributedCrawlSession.WorkerStatus.COMPLETED, worker.getStatus());
    }

    // ---- startup reconcile ----

    @Test
    void reconcileCompletesWorkerThatFinishedDuringDowntime(@TempDir Path dir) {
        DistributedCrawlSessionStore store = new DistributedCrawlSessionStore(dir.toString(), mapper());
        store.persist(runningSession("rc1", List.of(src("A", "a")), "ext-1"));

        CapturingDelegate delegate = new CapturingDelegate();
        delegate.statusToReturn = "COMPLETED"; // the external job finished while the coordinator was down
        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(delegate), cfgService(false, true), mapper());
        ReflectionTestUtils.setField(coordinator, "sessionStore", store);

        coordinator.reconcilePersistedSessions();
        store.flush(); // drain the async persist so @TempDir cleanup doesn't race the writer

        DistributedCrawlSession loaded = coordinator.getSession("rc1").orElseThrow();
        assertEquals(DistributedCrawlSession.Status.COMPLETED, loaded.getStatus());
        assertEquals(DistributedCrawlSession.WorkerStatus.COMPLETED,
                loaded.getWorkers().get("rc1-worker-0").getStatus());
    }

    @Test
    void reconcileResumesWorkerStillRunning(@TempDir Path dir) {
        DistributedCrawlSessionStore store = new DistributedCrawlSessionStore(dir.toString(), mapper());
        store.persist(runningSession("rc2", List.of(src("A", "a")), "ext-1"));

        CapturingDelegate delegate = new CapturingDelegate();
        delegate.statusToReturn = "RUNNING"; // still alive on the worker
        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(delegate), cfgService(false, true), mapper());
        ReflectionTestUtils.setField(coordinator, "sessionStore", store);

        coordinator.reconcilePersistedSessions();
        store.flush(); // drain the async persist so @TempDir cleanup doesn't race the writer

        DistributedCrawlSession loaded = coordinator.getSession("rc2").orElseThrow();
        assertEquals(DistributedCrawlSession.Status.RUNNING, loaded.getStatus());
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING,
                loaded.getWorkers().get("rc2-worker-0").getStatus());
    }

    @Test
    void reconcileFailsUnrecoverableWorkerWhenReassignDisabled(@TempDir Path dir) {
        DistributedCrawlSessionStore store = new DistributedCrawlSessionStore(dir.toString(), mapper());
        store.persist(runningSession("rc3", List.of(src("A", "a")), "ext-1"));

        CapturingDelegate delegate = new CapturingDelegate();
        delegate.statusToReturn = "FAILED"; // gone, and reassignment is off
        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(delegate), cfgService(false, true), mapper());
        ReflectionTestUtils.setField(coordinator, "sessionStore", store);

        coordinator.reconcilePersistedSessions();
        store.flush(); // drain the async persist so @TempDir cleanup doesn't race the writer

        DistributedCrawlSession loaded = coordinator.getSession("rc3").orElseThrow();
        assertEquals(DistributedCrawlSession.WorkerStatus.FAILED,
                loaded.getWorkers().get("rc3-worker-0").getStatus());
        assertNotEquals(DistributedCrawlSession.Status.RUNNING, loaded.getStatus());
    }

    @Test
    void reconcileSkippedForNonOrchestrator(@TempDir Path dir) {
        DistributedCrawlSessionStore store = new DistributedCrawlSessionStore(dir.toString(), mapper());
        store.persist(runningSession("rc4", List.of(src("A", "a")), "ext-1"));

        DistributedCrawlCoordinator coordinator = new DistributedCrawlCoordinator(
                List.of(new CapturingDelegate()), cfgService(false, false), mapper()); // role=worker
        ReflectionTestUtils.setField(coordinator, "sessionStore", store);

        coordinator.reconcilePersistedSessions();
        store.flush(); // drain the async persist so @TempDir cleanup doesn't race the writer

        assertTrue(coordinator.getSession("rc4").isEmpty(), "a non-orchestrator must not reload sessions");
    }
}
