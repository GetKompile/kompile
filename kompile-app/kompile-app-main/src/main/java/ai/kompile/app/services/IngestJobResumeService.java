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

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import ai.kompile.app.subprocess.IngestCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for listing and resuming ingest jobs from checkpoints.
 * Combines IndexingJobHistory data (DB) with IngestCheckpoint data (files)
 * to provide a unified view of resumable jobs and the ability to restart them.
 */
@Service
public class IngestJobResumeService {

    private static final Logger log = LoggerFactory.getLogger(IngestJobResumeService.class);

    private final IndexingJobHistoryService historyService;

    @Autowired(required = false)
    private SubprocessIngestLauncher subprocessLauncher;

    @Autowired(required = false)
    private DocumentIngestService documentIngestService;

    public IngestJobResumeService(IndexingJobHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * List all ingest jobs that have a checkpoint and can be resumed.
     * Enriches the DB records with checkpoint file data where available.
     */
    public List<ResumableJobSummary> listResumableJobs() {
        List<IndexingJobHistory> resumableJobs = historyService.listResumableJobs();
        return resumableJobs.stream()
                .map(this::toResumableSummary)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Resume an ingest job from its checkpoint.
     * Creates a new task ID so the original history record is preserved.
     *
     * @param originalTaskId the task ID of the job to resume
     * @return the new task ID for the resumed job
     * @throws IllegalArgumentException if the job is not found or not resumable
     * @throws IllegalStateException if no subprocess launcher is available
     */
    public String resumeJob(String originalTaskId) {
        Optional<IndexingJobHistory> jobOpt = historyService.getJob(originalTaskId);
        if (jobOpt.isEmpty()) {
            throw new IllegalArgumentException("Job not found: " + originalTaskId);
        }

        IndexingJobHistory job = jobOpt.get();
        if (!job.isResumable()) {
            throw new IllegalArgumentException("Job is not resumable: " + originalTaskId
                    + " (status=" + job.getStatus() + ", checkpoint=" + job.getCheckpointPath() + ")");
        }

        String checkpointPath = job.getCheckpointPath();
        if (checkpointPath == null || !Files.exists(Path.of(checkpointPath))) {
            throw new IllegalArgumentException("Checkpoint file not found: " + checkpointPath);
        }

        // Load the checkpoint to get file path and settings
        IngestCheckpoint checkpoint = IngestCheckpoint.loadOrCreate(
                Path.of(checkpointPath), originalTaskId, originalTaskId, job.getFileName());

        // Generate new task ID for the resumed job
        String newTaskId = "resume-" + originalTaskId + "-" + System.currentTimeMillis();

        // Build options with resume flag
        Map<String, Object> options = new HashMap<>();
        options.put("resume", true);
        options.put("checkpointPath", checkpointPath);
        options.put("originalTaskId", originalTaskId);

        // Use subprocess launcher (which has the checkpoint resume logic)
        if (subprocessLauncher != null) {
            Path filePath = Path.of(checkpoint.getFilePath());
            subprocessLauncher.launchIngest(
                    newTaskId,
                    filePath,
                    job.getLoaderUsed(),
                    job.getChunkerUsed(),
                    options);
            log.info("Resumed ingest job {} from checkpoint of {} (file: {})",
                    newTaskId, originalTaskId, filePath);
        } else if (documentIngestService != null) {
            // Fall back to subprocess mode (which supports checkpoint resume)
            Path inProcessFilePath = Path.of(job.getFileName());
            documentIngestService.processDocumentAsync(
                    newTaskId,
                    inProcessFilePath,
                    job.getLoaderUsed(),
                    job.getChunkerUsed(),
                    DocumentIngestService.ProcessingMode.SUBPROCESS,
                    options);
            log.info("Resumed ingest job {} via subprocess from {} (file: {})",
                    newTaskId, originalTaskId, job.getFileName());
        } else {
            throw new IllegalStateException("No ingest service available for resume");
        }

        // Mark the new job record with the original task ID for lineage tracking
        setResumedFromTaskId(newTaskId, originalTaskId);

        return newTaskId;
    }

    /**
     * Get checkpoint status details for a job.
     */
    public Map<String, Object> getCheckpointStatus(String taskId) {
        Optional<IndexingJobHistory> jobOpt = historyService.getJob(taskId);
        if (jobOpt.isEmpty()) {
            return Map.of("error", "Job not found", "taskId", taskId);
        }

        IndexingJobHistory job = jobOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", job.getStatus().name());
        result.put("checkpointPath", job.getCheckpointPath());
        result.put("resumeFromPhase", job.getResumeFromPhase() != null ? job.getResumeFromPhase().name() : null);
        result.put("isResumable", job.isResumable());

        // Try to load checkpoint file for detailed progress
        if (job.getCheckpointPath() != null && Files.exists(Path.of(job.getCheckpointPath()))) {
            IngestCheckpoint checkpoint = IngestCheckpoint.loadOrCreate(
                    Path.of(job.getCheckpointPath()), taskId, taskId, job.getFileName());
            result.put("checkpointExists", true);
            result.put("totalChunks", checkpoint.getTotalChunks());
            result.put("chunksEmbedded", checkpoint.getEmbeddedCount());
            result.put("chunksIndexed", checkpoint.getIndexedCount());
            result.put("currentPhase", checkpoint.getCurrentPhase());
            result.put("needsResume", checkpoint.needsResume());
            result.put("nextChunkToEmbed", checkpoint.getNextChunkToEmbed());
        } else {
            result.put("checkpointExists", false);
        }

        return result;
    }

    /**
     * Set the resumedFromTaskId on the new job's history record.
     * This may need to retry briefly since the subprocess creates the record asynchronously.
     */
    private void setResumedFromTaskId(String newTaskId, String originalTaskId) {
        // The subprocess launcher creates the history record asynchronously,
        // so we retry a few times with a short delay
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<IndexingJobHistory> newJob = historyService.getJob(newTaskId);
            if (newJob.isPresent()) {
                historyService.setResumedFromTaskId(newTaskId, originalTaskId);
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // If we can't find it yet, schedule a delayed set
        log.warn("Could not set resumedFromTaskId on {} immediately - will be set when job record appears", newTaskId);
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(2000);
                    Optional<IndexingJobHistory> job = historyService.getJob(newTaskId);
                    if (job.isPresent()) {
                        historyService.setResumedFromTaskId(newTaskId, originalTaskId);
                        log.info("Delayed set of resumedFromTaskId={} on job {}", originalTaskId, newTaskId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.debug("Retry {} to set resumedFromTaskId on {}: {}", i, newTaskId, e.getMessage());
                }
            }
        }, "resume-linker-" + newTaskId).start();
    }

    private ResumableJobSummary toResumableSummary(IndexingJobHistory job) {
        int chunksEmbedded = job.getChunksEmbedded() != null ? job.getChunksEmbedded() : 0;
        int chunksIndexed = job.getDocumentsIndexed() != null ? job.getDocumentsIndexed() : 0;
        int totalChunks = job.getChunksCreated() != null ? job.getChunksCreated() : 0;

        // Try to get more accurate data from checkpoint file
        if (job.getCheckpointPath() != null && Files.exists(Path.of(job.getCheckpointPath()))) {
            IngestCheckpoint checkpoint = IngestCheckpoint.loadOrCreate(
                    Path.of(job.getCheckpointPath()),
                    job.getTaskId(), job.getTaskId(), job.getFileName());
            if (checkpoint.getTotalChunks() > 0) {
                totalChunks = checkpoint.getTotalChunks();
            }
            chunksEmbedded = checkpoint.getEmbeddedCount();
            chunksIndexed = checkpoint.getIndexedCount();
        }

        return new ResumableJobSummary(
                job.getTaskId(),
                job.getFileName(),
                job.getCheckpointPath(),
                job.getResumeFromPhase() != null ? job.getResumeFromPhase().name() : null,
                chunksEmbedded,
                chunksIndexed,
                totalChunks,
                job.getEndTime() != null ? job.getEndTime().toString()
                        : (job.getStartTime() != null ? job.getStartTime().toString() : null),
                job.getStatus().name(),
                job.getErrorMessage(),
                job.getLoaderUsed(),
                job.getChunkerUsed(),
                job.getEmbeddingModelUsed()
        );
    }

    /**
     * Summary of a resumable ingest job.
     */
    public record ResumableJobSummary(
            String taskId,
            String fileName,
            String checkpointPath,
            String resumeFromPhase,
            int chunksEmbedded,
            int chunksIndexed,
            int totalChunks,
            String stoppedAt,
            String status,
            String errorMessage,
            String loaderUsed,
            String chunkerUsed,
            String embeddingModelUsed
    ) {}
}
