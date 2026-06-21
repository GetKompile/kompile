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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 durability: the {@link DistributedCrawlSessionStore} round-trips a session manifest to disk and back,
 * and {@link DistributedCrawlSession#fromManifest} rehydrates the in-memory session — the foundation that lets a
 * coordinator restart recover in-flight distributed crawls instead of losing them.
 */
class DistributedCrawlSessionStoreTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private DistributedCrawlSessionStore store(Path dir) {
        return new DistributedCrawlSessionStore(dir.toString(), mapper());
    }

    private DistributedCrawlSession runningSession(String id) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name("dist " + id)
                .sources(List.of(
                        UnifiedCrawlSource.builder().label("A").pathOrUrl("a").build(),
                        UnifiedCrawlSource.builder().label("B").pathOrUrl("b").build()))
                .build();
        DistributedCrawlSession s = DistributedCrawlSession.builder()
                .sessionId(id).originalRequest(req)
                .status(DistributedCrawlSession.Status.RUNNING)
                .totalWorkers(1).startedAt(Instant.now()).build();
        s.addWorker(id + "-worker-0", req.getSources());
        s.workerDispatched(id + "-worker-0", "ext-1");
        return s;
    }

    @Test
    void persistThenLoadRoundTrips(@TempDir Path dir) {
        DistributedCrawlSessionStore store = store(dir);
        store.persist(runningSession("s1"));

        List<DistributedCrawlSession.Manifest> all = store.loadAll();
        assertEquals(1, all.size());
        DistributedCrawlSession.Manifest m = all.get(0);
        assertEquals("s1", m.getSessionId());
        assertEquals(DistributedCrawlSession.Status.RUNNING, m.getStatus());
        assertEquals(1, m.getTotalWorkers());
        assertNotNull(m.getOriginalRequest());
        assertEquals(2, m.getOriginalRequest().getSources().size());
        assertEquals(1, m.getWorkers().size());
        assertEquals("ext-1", m.getWorkers().get(0).getExternalRef());
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING, m.getWorkers().get(0).getStatus());
    }

    @Test
    void fromManifestRehydratesSession(@TempDir Path dir) {
        DistributedCrawlSessionStore store = store(dir);
        store.persist(runningSession("s2"));

        DistributedCrawlSession restored = DistributedCrawlSession.fromManifest(store.loadAll().get(0));
        assertEquals("s2", restored.getSessionId());
        assertEquals(DistributedCrawlSession.Status.RUNNING, restored.getStatus());
        assertEquals(1, restored.getWorkers().size());
        DistributedCrawlSession.WorkerInfo w = restored.getWorkers().values().iterator().next();
        assertEquals(DistributedCrawlSession.WorkerStatus.RUNNING, w.getStatus());
        assertEquals("ext-1", w.getExternalRef());
        assertEquals(2, w.getSources().size());
    }

    @Test
    void deleteRemovesFile(@TempDir Path dir) {
        DistributedCrawlSessionStore store = store(dir);
        store.persist(runningSession("s3"));
        assertEquals(1, store.loadAll().size());
        store.delete("s3");
        assertEquals(0, store.loadAll().size());
    }

    @Test
    void loadAllOnMissingDirIsEmpty(@TempDir Path dir) {
        DistributedCrawlSessionStore store = new DistributedCrawlSessionStore(
                dir.resolve("does-not-exist").toString(), mapper());
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void completedSourceKeysAreDerivedFromSnapshotAndPersist(@TempDir Path dir) {
        DistributedCrawlSessionStore store = store(dir);
        DistributedCrawlSession s = runningSession("s4");
        String workerId = s.getWorkers().keySet().iterator().next();
        // The worker reports source A finished, source B still running.
        s.updateWorkerSnapshot(workerId, UnifiedCrawlJob.ProgressSnapshot.builder()
                .sourceProgress(List.of(
                        UnifiedCrawlJob.SourceProgress.builder()
                                .label("A").pathOrUrl("a").status(UnifiedCrawlJob.Status.COMPLETED).build(),
                        UnifiedCrawlJob.SourceProgress.builder()
                                .label("B").pathOrUrl("b").status(UnifiedCrawlJob.Status.RUNNING).build()))
                .build());
        store.persist(s);

        List<String> persistedKeys = store.loadAll().get(0).getWorkers().get(0).getCompletedSourceKeys();
        assertTrue(persistedKeys.contains("A"), "completed source A should persist");
        assertFalse(persistedKeys.contains("B"), "in-progress source B should not be marked complete");

        // And a restored worker reports the same completed-source set (used by reassignment after a restart).
        DistributedCrawlSession restored = DistributedCrawlSession.fromManifest(store.loadAll().get(0));
        DistributedCrawlSession.WorkerInfo w = restored.getWorkers().values().iterator().next();
        assertTrue(w.completedSourceKeys().contains("A"));
    }
}
