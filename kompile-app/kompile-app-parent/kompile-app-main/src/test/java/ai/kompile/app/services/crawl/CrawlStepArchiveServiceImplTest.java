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

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the archive -> (simulated) restart -> resume cycle for {@link CrawlStepArchiveServiceImpl}.
 *
 * <p>A "restart" is simulated by building a SECOND service instance pointed at the same on-disk state
 * directory — i.e. the original in-memory job is gone, exactly as it would be after a JVM restart. The
 * archived chunks + manifest must still load from disk so the step can be run later.</p>
 */
class CrawlStepArchiveServiceImplTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private UnifiedCrawlJob jobWith(String jobId, List<Document> ignored) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name("Test Crawl")
                .factSheetId(7L)
                .build();
        return UnifiedCrawlJob.builder().jobId(jobId).request(req).build();
    }

    private List<Document> chunks(int n) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            docs.add(new Document("chunk-" + i, "passage text number " + i, Map.of("idx", i, "src", "a.txt")));
        }
        return docs;
    }

    @Test
    void archiveThenRestartThenResumeLoadsChunksAndSnapshotFromDisk(@TempDir Path stateDir) {
        String jobId = "job-abc";
        String taskId = "crawl-" + jobId;

        // --- archive (original process) ---
        IndexingJobHistoryService history1 = mock(IndexingJobHistoryService.class);
        CrawlStepArchiveServiceImpl original =
                new CrawlStepArchiveServiceImpl(MAPPER, history1, stateDir.toString());

        UnifiedCrawlJob job = jobWith(jobId, null);
        List<Document> pending = chunks(25);

        String archiveDir = original.archive(job, "VECTOR_INDEXING", pending, Map.of("enabled", true, "batchSize", 8));

        assertNotNull(archiveDir, "archive() returns the archive dir");
        Path root = stateDir.resolve("checkpoints").resolve(taskId);
        assertTrue(Files.exists(root.resolve("manifest.json")), "manifest written");
        assertTrue(Files.exists(root.resolve("VECTOR_INDEXING").resolve("chunks.jsonl")), "chunks written");

        // History pointers were lit up so the job shows resumable.
        verify(history1).recordCheckpointPath(eq(taskId), anyString(), eq(IngestEvent.IngestPhase.INDEXING));
        verify(history1).markJobResumable(eq(taskId), eq(true));

        // --- simulate a JVM restart: brand-new service, same disk, in-memory job gone ---
        IndexingJobHistoryService history2 = mock(IndexingJobHistoryService.class);
        IndexingJobHistory record = new IndexingJobHistory();
        record.setTaskId(taskId);
        when(history2.listResumableCrawlJobs()).thenReturn(List.of(record));

        CrawlStepArchiveServiceImpl afterRestart =
                new CrawlStepArchiveServiceImpl(MAPPER, history2, stateDir.toString());

        // Chunks survive the "restart" and round-trip back intact.
        CrawlStepArchiveService.ArchivedStepData data = afterRestart.load(jobId, "VECTOR_INDEXING");
        assertNotNull(data);
        assertEquals(25, data.chunks().size());
        assertEquals("chunk-0", data.chunks().get(0).getId());
        assertEquals("passage text number 0", data.chunks().get(0).getText());
        assertTrue(data.configJson().contains("batchSize"));

        // Snapshot needed to rehydrate the job is readable from the manifest.
        CrawlStepArchiveService.ArchivedJobSnapshot snap = afterRestart.loadSnapshot(jobId);
        assertNotNull(snap);
        assertEquals(jobId, snap.jobId());
        assertEquals("Test Crawl", snap.name());
        assertEquals(7L, snap.factSheetId());
        assertTrue(snap.archivedSteps().contains("VECTOR_INDEXING"));

        // listResumableCrawlJobs surfaces the job (history says resumable + manifest has archived steps).
        List<CrawlStepArchiveService.ResumableCrawlJob> resumable = afterRestart.listResumableCrawlJobs();
        assertEquals(1, resumable.size());
        assertEquals(jobId, resumable.get(0).jobId());
        assertTrue(resumable.get(0).archivedSteps().contains("VECTOR_INDEXING"));

        // --- after the step is run, it drops out of the archived set and the job stops being resumable ---
        afterRestart.markStepCompleted(jobId, "VECTOR_INDEXING");
        verify(history2).markJobResumable(eq(taskId), eq(false));
        assertTrue(afterRestart.loadSnapshot(jobId).archivedSteps().isEmpty(),
                "completed step is removed from the manifest");
    }

    @Test
    void loadReturnsNullWhenNoArchiveExists(@TempDir Path stateDir) {
        CrawlStepArchiveServiceImpl svc =
                new CrawlStepArchiveServiceImpl(MAPPER, mock(IndexingJobHistoryService.class), stateDir.toString());
        assertEquals(null, svc.load("missing-job", "VECTOR_INDEXING"));
        assertEquals(null, svc.loadSnapshot("missing-job"));
    }

    @Test
    void archiveAccumulatesMultipleStepsInOneManifest(@TempDir Path stateDir) {
        CrawlStepArchiveServiceImpl svc =
                new CrawlStepArchiveServiceImpl(MAPPER, mock(IndexingJobHistoryService.class), stateDir.toString());
        UnifiedCrawlJob job = jobWith("multi", null);

        svc.archive(job, "VECTOR_INDEXING", chunks(3), Map.of("enabled", true));
        svc.archive(job, "GRAPH_EXTRACTION", chunks(2), Map.of("enabled", true));

        CrawlStepArchiveService.ArchivedJobSnapshot snap = svc.loadSnapshot("multi");
        assertNotNull(snap);
        assertTrue(snap.archivedSteps().contains("VECTOR_INDEXING"));
        assertTrue(snap.archivedSteps().contains("GRAPH_EXTRACTION"));
        assertEquals(2, svc.load("multi", "GRAPH_EXTRACTION").chunks().size());
    }
}
