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
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.archive.ArchiveManifest;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.crawl.graph.archive.DocumentArchiveStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk-backed implementation of {@link CrawlStepArchiveService}.
 *
 * <p>Lives in {@code kompile-app-main} (the composition root) so it can reach the JPA job-history
 * layer. It writes each archived step's chunks + config under
 * {@code {stateDir}/checkpoints/crawl-{jobId}/{STEP}/} and a {@code manifest.json} at the job root,
 * and lights up the existing-but-unused {@code checkpointPath}/{@code resumable} columns on
 * {@link IndexingJobHistory} so a crawl with archived steps survives a restart and shows up as
 * resumable. Step <em>execution</em> stays in the crawl-graph service; this class only persists and
 * reloads the inputs.</p>
 */
@Slf4j
@Service
public class CrawlStepArchiveServiceImpl implements CrawlStepArchiveService {

    private static final String MANIFEST_FILE = "manifest.json";

    private final ObjectMapper objectMapper;
    private final DocumentArchiveStore archiveStore;
    private final IndexingJobHistoryService jobHistoryService;
    private final String stateDir;

    public CrawlStepArchiveServiceImpl(
            ObjectMapper objectMapper,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService,
            @Value("${kompile.ingest.state-directory:${user.home}/.kompile/state}") String stateDir) {
        this.objectMapper = objectMapper;
        this.archiveStore = new DocumentArchiveStore(objectMapper);
        this.jobHistoryService = jobHistoryService;
        this.stateDir = stateDir;
    }

    @Override
    public String archive(UnifiedCrawlJob job, String stepId, List<Document> chunks, Object config) {
        if (job == null || job.getJobId() == null || stepId == null) {
            return null;
        }
        Path archiveRoot = archiveRoot(job.getJobId());
        Path stepDir = archiveRoot.resolve(stepId);
        try {
            Files.createDirectories(stepDir);
            int written = archiveStore.writeChunks(stepDir, chunks != null ? chunks : List.of());
            archiveStore.writeStepConfig(stepDir, config);

            ArchiveManifest manifest = readOrCreateManifest(archiveRoot, job);
            manifest.setUpdatedAt(Instant.now().toString());
            manifest.setProgressSnapshot(safeSnapshot(job));
            if (!manifest.getArchivedSteps().contains(stepId)) {
                manifest.getArchivedSteps().add(stepId);
            }
            manifest.getSteps().put(stepId, ArchiveManifest.StepEntry.builder()
                    .stepId(stepId)
                    .chunkCount(written)
                    .chunksFile(stepId + "/" + DocumentArchiveStore.CHUNKS_FILE)
                    .configFile(stepId + "/" + DocumentArchiveStore.STEP_CONFIG_FILE)
                    .archivedAt(Instant.now().toString())
                    .build());
            writeManifest(archiveRoot, manifest);

            if (jobHistoryService != null) {
                String taskId = historyTaskId(job.getJobId());
                jobHistoryService.recordCheckpointPath(taskId, archiveRoot.toString(),
                        IngestEvent.IngestPhase.INDEXING);
                jobHistoryService.markJobResumable(taskId, true);
            }
            log.info("Archived step {} for job {} ({} chunk(s)) -> {}", stepId, job.getJobId(), written, archiveRoot);
            return archiveRoot.toString();
        } catch (IOException e) {
            log.warn("Failed to archive step {} for job {}: {}", stepId, job.getJobId(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public ArchivedStepData load(String jobId, String stepId) {
        if (jobId == null || stepId == null) {
            return null;
        }
        Path stepDir = archiveRoot(jobId).resolve(stepId);
        if (!Files.exists(stepDir)) {
            return null;
        }
        try {
            List<Document> chunks = archiveStore.readChunks(stepDir);
            String configJson = archiveStore.readStepConfigJson(stepDir);
            return new ArchivedStepData(chunks, configJson);
        } catch (IOException e) {
            log.warn("Failed to load archived step {} for job {}: {}", stepId, jobId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void markStepCompleted(String jobId, String stepId) {
        if (jobId == null || stepId == null) {
            return;
        }
        Path archiveRoot = archiveRoot(jobId);
        ArchiveManifest manifest = readManifest(archiveRoot);
        if (manifest == null) {
            return;
        }
        manifest.getArchivedSteps().remove(stepId);
        manifest.setUpdatedAt(Instant.now().toString());
        try {
            writeManifest(archiveRoot, manifest);
        } catch (IOException e) {
            log.warn("Failed to update manifest after completing step {} for job {}: {}",
                    stepId, jobId, e.getMessage());
        }
        if (manifest.getArchivedSteps().isEmpty() && jobHistoryService != null) {
            jobHistoryService.markJobResumable(historyTaskId(jobId), false);
        }
    }

    @Override
    public void refreshManifest(UnifiedCrawlJob job) {
        if (job == null || job.getJobId() == null) {
            return;
        }
        Path archiveRoot = archiveRoot(job.getJobId());
        ArchiveManifest manifest = readManifest(archiveRoot);
        if (manifest == null) {
            return;
        }
        manifest.setUpdatedAt(Instant.now().toString());
        manifest.setProgressSnapshot(safeSnapshot(job));
        try {
            writeManifest(archiveRoot, manifest);
        } catch (IOException e) {
            log.debug("Failed to refresh manifest for job {}: {}", job.getJobId(), e.getMessage());
        }
    }

    @Override
    public List<ResumableCrawlJob> listResumableCrawlJobs() {
        List<ResumableCrawlJob> out = new ArrayList<>();
        if (jobHistoryService == null) {
            return out;
        }
        for (IndexingJobHistory h : jobHistoryService.listResumableCrawlJobs()) {
            String jobId = stripCrawlPrefix(h.getTaskId());
            Path archiveRoot = archiveRoot(jobId);
            ArchiveManifest manifest = readManifest(archiveRoot);
            if (manifest == null || manifest.getArchivedSteps().isEmpty()) {
                continue;
            }
            out.add(new ResumableCrawlJob(
                    jobId,
                    manifest.getName(),
                    manifest.getFactSheetId(),
                    new ArrayList<>(manifest.getArchivedSteps()),
                    manifest.getUpdatedAt() != null ? manifest.getUpdatedAt() : manifest.getCreatedAt(),
                    archiveRoot.toString()));
        }
        return out;
    }

    @Override
    public ArchivedJobSnapshot loadSnapshot(String jobId) {
        if (jobId == null) {
            return null;
        }
        ArchiveManifest manifest = readManifest(archiveRoot(jobId));
        if (manifest == null) {
            return null;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        if (manifest.getProgressSnapshot() instanceof Map<?, ?> snap) {
            for (Map.Entry<?, ?> e : snap.entrySet()) {
                raw.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return new ArchivedJobSnapshot(
                jobId,
                manifest.getName(),
                manifest.getFactSheetId(),
                new ArrayList<>(manifest.getArchivedSteps()),
                raw);
    }

    // ---- internals ----

    private Path archiveRoot(String jobId) {
        return Paths.get(stateDir, "checkpoints", historyTaskId(jobId));
    }

    private static String historyTaskId(String jobId) {
        return "crawl-" + jobId;
    }

    private static String stripCrawlPrefix(String taskId) {
        return taskId != null && taskId.startsWith("crawl-") ? taskId.substring("crawl-".length()) : taskId;
    }

    private ArchiveManifest readOrCreateManifest(Path archiveRoot, UnifiedCrawlJob job) {
        ArchiveManifest manifest = readManifest(archiveRoot);
        if (manifest != null) {
            return manifest;
        }
        return ArchiveManifest.builder()
                .schemaVersion(1)
                .jobId(job.getJobId())
                .name(job.getRequest() != null ? job.getRequest().getName() : null)
                .factSheetId(job.getRequest() != null ? job.getRequest().getFactSheetId() : null)
                .createdAt(Instant.now().toString())
                .build();
    }

    private ArchiveManifest readManifest(Path archiveRoot) {
        Path file = archiveRoot.resolve(MANIFEST_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), ArchiveManifest.class);
        } catch (IOException e) {
            log.warn("Failed to read archive manifest {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void writeManifest(Path archiveRoot, ArchiveManifest manifest) throws IOException {
        Files.createDirectories(archiveRoot);
        Path file = archiveRoot.resolve(MANIFEST_FILE);
        Path tmp = archiveRoot.resolve(MANIFEST_FILE + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), manifest);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Object safeSnapshot(UnifiedCrawlJob job) {
        try {
            return job.toProgressSnapshot();
        } catch (Exception e) {
            log.debug("Could not capture progress snapshot for job {}: {}", job.getJobId(), e.getMessage());
            return null;
        }
    }
}
