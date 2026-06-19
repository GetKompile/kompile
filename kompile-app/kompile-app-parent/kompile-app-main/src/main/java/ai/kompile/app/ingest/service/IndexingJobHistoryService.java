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

package ai.kompile.app.ingest.service;

import ai.kompile.app.config.PrimaryDataSourceConfig;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.repository.IndexingJobHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for managing indexing job history.
 * Provides methods for creating, updating, and querying job history records.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kompile.ingest.eventlog.enabled", havingValue = "true", matchIfMissing = true)
public class IndexingJobHistoryService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected IndexingJobHistoryService() {}


    @Autowired
    private IndexingJobHistoryRepository repository;
    @Autowired(required = false)
    private JobLogService jobLogService;

    @Value("${kompile.ingest.job-history.retention-days:30}")
    private int retentionDays;

    @Value("${kompile.ingest.job-history.max-records:10000}")
    private int maxRecords;

    @Value("${kompile.ingest.state-directory:${user.home}/.kompile/state}")
    private String stateDirectory;

    @Autowired
    public IndexingJobHistoryService(
            IndexingJobHistoryRepository repository,
            @Autowired(required = false) JobLogService jobLogService) {
        this.repository = repository;
        this.jobLogService = jobLogService;
    }

    /**
     * On startup, mark any orphaned jobs (RUNNING/QUEUED) as FAILED.
     * These are jobs that were interrupted by a server restart or crash.
     */
    @PostConstruct
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void cleanupOrphanedJobsOnStartup() {
        try {
            List<IndexingJobHistory> orphanedJobs = repository.findActiveJobs();
            if (orphanedJobs.isEmpty()) {
                log.debug("No orphaned jobs found on startup");
                return;
            }

            log.info("Found {} orphaned jobs from previous run, marking as FAILED", orphanedJobs.size());
            Instant now = Instant.now();

            for (IndexingJobHistory job : orphanedJobs) {
                // Determine the phase where it was interrupted
                IngestPhase lastPhase = job.getLastPhase() != null ? job.getLastPhase() : IngestPhase.QUEUED;

                // Mark as failed with appropriate reason
                job.setStatus(JobStatus.FAILED);
                job.setFailedPhase(lastPhase);
                job.setFailureReason(FailureReason.UNKNOWN);
                job.setErrorMessage("Job interrupted by server restart or crash");
                job.setEndTime(now);

                // Calculate duration if start time is available
                if (job.getStartTime() != null) {
                    long durationMs = Duration.between(job.getStartTime(), now).toMillis();
                    job.setTotalDurationMs(durationMs);
                }

                repository.save(job);
                log.info("Marked orphaned job {} ({}) as FAILED - was in phase {}",
                        job.getTaskId(), job.getFileName(), lastPhase);
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup orphaned jobs on startup: {}", e.getMessage());
            // Don't fail startup if cleanup fails
        }
    }

    // ===================== JOB LIFECYCLE METHODS =====================

    /**
     * Create a new job history entry when a job is queued.
     * Uses optimistic approach: try to save and handle unique constraint violation.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public IndexingJobHistory createJob(String taskId, String fileName) {
        // First, try to find existing job to avoid unnecessary save attempts
        Optional<IndexingJobHistory> existing = repository.findByTaskId(taskId);
        if (existing.isPresent()) {
            log.warn("Job history already exists for taskId: {}", taskId);
            return existing.get();
        }

        try {
            IndexingJobHistory job = IndexingJobHistory.createQueued(taskId, fileName);
            IndexingJobHistory saved = repository.save(job);
            repository.flush(); // Force immediate DB write to detect unique constraint violations
            log.debug("Created job history for taskId: {}", taskId);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another thread created the job between check and save
            log.debug("Concurrent job creation detected for taskId: {}, returning existing", taskId);
            return repository.findByTaskId(taskId).orElse(null);
        }
    }

    /**
     * Create a job with additional environment info.
     * Uses optimistic approach: try to save and handle unique constraint violation.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public IndexingJobHistory createJobWithEnvironment(String taskId, String fileName,
            String nd4jEnvironmentJson,
            Long fileSizeBytes,
            String contentType) {
        // First, try to find existing job to avoid unnecessary save attempts
        Optional<IndexingJobHistory> existing = repository.findByTaskId(taskId);
        if (existing.isPresent()) {
            log.warn("Job history already exists for taskId: {}", taskId);
            return existing.get();
        }

        try {
            IndexingJobHistory job = IndexingJobHistory.createQueued(taskId, fileName);
            job.setNd4jEnvironmentJson(nd4jEnvironmentJson);
            job.setFileSizeBytes(fileSizeBytes);
            job.setContentType(contentType);

            // Capture additional environment info
            try {
                String nd4jBackend = System.getProperty("org.nd4j.backend.priority", "unknown");
                job.setNd4jBackend(nd4jBackend);
            } catch (Exception e) {
                log.debug("Could not determine ND4J backend: {}", e.getMessage());
            }

            IndexingJobHistory saved = repository.save(job);
            repository.flush(); // Force immediate DB write to detect unique constraint violations
            log.debug("Created job history with environment for taskId: {}", taskId);
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race condition: another thread created the job between check and save
            log.debug("Concurrent job creation detected for taskId: {}, returning existing", taskId);
            return repository.findByTaskId(taskId).orElse(null);
        }
    }

    /**
     * Mark a job as running.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobRunning(String taskId) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markRunning();
            repository.save(job);
            log.debug("Marked job {} as RUNNING", taskId);
        });
    }

    /**
     * Update job phase and progress.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateJobProgress(String taskId, IngestPhase phase, int progressPercent) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.updatePhase(phase, progressPercent);
            repository.save(job);
        });
    }

    /**
     * Update job phase timing.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updatePhaseTiming(String taskId, IngestPhase phase, long durationMs) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            switch (phase) {
                case LOADING -> job.setLoadingDurationMs(durationMs);
                case CONVERTING -> job.setConversionDurationMs(durationMs);
                case CHUNKING -> job.setChunkingDurationMs(durationMs);
                case EMBEDDING -> job.setEmbeddingDurationMs(durationMs);
                case INDEXING -> job.setIndexingDurationMs(durationMs);
            }
            repository.save(job);
        });
    }

    /**
     * Update job statistics.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateJobStats(String taskId, Integer documentsLoaded, Integer chunksCreated,
            Integer chunksEmbedded, Integer documentsIndexed) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.updateStats(documentsLoaded, chunksCreated, chunksEmbedded, documentsIndexed);
            repository.save(job);
        });
    }

    /**
     * Update processing parameters used.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateJobParameters(String taskId, String loaderUsed, String chunkerUsed,
            String embeddingModelUsed, String indexerUsed,
            Integer chunkSize, Integer chunkOverlap,
            Integer embeddingBatchSize, Integer workerThreads,
            Boolean parallelProcessing, Boolean adaptiveBatching) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setLoaderUsed(loaderUsed);
            job.setChunkerUsed(chunkerUsed);
            job.setEmbeddingModelUsed(embeddingModelUsed);
            job.setIndexerUsed(indexerUsed);
            job.setChunkSize(chunkSize);
            job.setChunkOverlap(chunkOverlap);
            job.setEmbeddingBatchSize(embeddingBatchSize);
            job.setWorkerThreads(workerThreads);
            job.setParallelProcessingEnabled(parallelProcessing);
            job.setAdaptiveBatchingEnabled(adaptiveBatching);
            repository.save(job);
        });
    }

    /**
     * Update the additionalDetails JSON blob for a job.
     * Used to persist the full crawl job snapshot so historical jobs retain rich detail.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateAdditionalDetails(String taskId, String additionalDetailsJson) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setAdditionalDetails(additionalDetailsJson);
            repository.save(job);
        });
    }

    /**
     * Update memory usage.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateMemoryUsage(String taskId, double currentUsagePercent) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.updatePeakMemory(currentUsagePercent);
            repository.save(job);
        });
    }

    /**
     * Mark a job as completed.
     * Also archives the job logs if archiving is enabled.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobCompleted(String taskId) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markCompleted();
            repository.save(job);
            log.info("Marked job {} as COMPLETED (duration: {}ms)", taskId, job.getTotalDurationMs());

            // Archive logs for completed job if archiving is enabled
            if (jobLogService != null && jobLogService.isArchiveEnabled()) {
                try {
                    Path archivePath = jobLogService.archiveLogsForTask(taskId);
                    if (archivePath != null) {
                        log.debug("Archived logs for completed job {} to {}", taskId, archivePath);
                    }
                } catch (IOException e) {
                    log.warn("Failed to archive logs for completed job {}: {}", taskId, e.getMessage());
                    // Non-fatal - logs are still in the database
                }
            }
        });
    }

    /**
     * Mark a job as failed.
     * Also archives the job logs if archiving is enabled.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobFailed(String taskId, IngestPhase failedPhase, String errorMessage,
            Throwable exception, FailureReason reason) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markFailed(failedPhase, errorMessage, exception, reason);
            repository.save(job);
            log.warn("Marked job {} as FAILED in phase {} (reason: {})", taskId, failedPhase, reason);

            // Archive logs for failed job if archiving is enabled
            if (jobLogService != null && jobLogService.isArchiveEnabled()) {
                try {
                    Path archivePath = jobLogService.archiveLogsForTask(taskId);
                    if (archivePath != null) {
                        log.debug("Archived logs for failed job {} to {}", taskId, archivePath);
                    }
                } catch (IOException e) {
                    log.warn("Failed to archive logs for failed job {}: {}", taskId, e.getMessage());
                    // Non-fatal - logs are still in the database
                }
            }
        });
    }

    /**
     * Mark a job as cancelled.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobCancelled(String taskId, IngestPhase currentPhase, String reason) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markCancelled(currentPhase, reason);
            repository.save(job);
            log.info("Marked job {} as CANCELLED in phase {}", taskId, currentPhase);
        });
    }

    /**
     * Mark a job as killed due to memory pressure.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobMemoryKilled(String taskId, IngestPhase currentPhase, double memoryPercent) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markMemoryKilled(currentPhase, memoryPercent);
            repository.save(job);
            log.warn("Marked job {} as MEMORY_KILLED at {}% memory", taskId, memoryPercent);
        });
    }

    /**
     * Set index path for a job.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void setIndexPath(String taskId, String indexPath) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setIndexPath(indexPath);
            repository.save(job);
        });
    }

    /**
     * Set embedding dimension.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void setEmbeddingDimension(String taskId, int dimension) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setEmbeddingDimension(dimension);
            repository.save(job);
        });
    }

    // ===================== RESUME / CHECKPOINT METHODS =====================

    /**
     * Record the checkpoint path for a job, enabling it to be resumed later.
     *
     * @param taskId Task identifier
     * @param checkpointPath Path to the checkpoint file
     * @param phase The ingest phase at which the checkpoint was created
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void recordCheckpointPath(String taskId, String checkpointPath, IngestPhase phase) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setCheckpointPath(checkpointPath);
            job.setResumeFromPhase(phase);
            repository.save(job);
            log.debug("Recorded checkpoint path for job {}: {} at phase {}", taskId, checkpointPath, phase);
        });
    }

    /**
     * Link a resumed job to the original task it was resumed from.
     *
     * @param newTaskId The task ID of the new (resumed) job
     * @param originalTaskId The task ID of the original job that was resumed
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void setResumedFromTaskId(String newTaskId, String originalTaskId) {
        repository.findByTaskId(newTaskId).ifPresent(job -> {
            job.setResumedFromTaskId(originalTaskId);
            repository.save(job);
            log.debug("Linked resumed job {} to original {}", newTaskId, originalTaskId);
        });
    }

    /**
     * Mark (or clear) a crawl job's resumable flag — set when it has archived steps persisted on disk.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markJobResumable(String taskId, boolean resumable) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.setResumable(resumable);
            repository.save(job);
            log.debug("Marked job {} resumable={}", taskId, resumable);
        });
    }

    /**
     * List crawl jobs flagged resumable (archived steps on disk awaiting a later run).
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> listResumableCrawlJobs() {
        return repository.findResumableCrawlJobs();
    }

    /**
     * List all jobs that have a checkpoint and can be resumed.
     * Returns jobs that are in FAILED, MEMORY_KILLED, CANCELLED, or PAUSED status with a checkpoint.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> listResumableJobs() {
        return repository.findAll().stream()
                .filter(IndexingJobHistory::isResumable)
                .toList();
    }

    // ===================== RESTART TRACKING METHODS =====================

    /**
     * Initialize restart tracking for a job with initial configuration.
     *
     * @param taskId Task identifier
     * @param maxAttempts Maximum restart attempts configured
     * @param heapBytes Initial heap size in bytes
     * @param batchSize Initial batch size
     * @param threadCount Initial thread count
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void initializeRestartTracking(String taskId, int maxAttempts, long heapBytes, int batchSize, int threadCount) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.initializeRestartTracking(maxAttempts, heapBytes, batchSize, threadCount);
            repository.save(job);
            log.debug("Initialized restart tracking for job {}: maxAttempts={}, heap={}MB, batch={}, threads={}",
                    taskId, maxAttempts, heapBytes / (1024 * 1024), batchSize, threadCount);
        });
    }

    /**
     * Record a restart attempt for a job.
     *
     * @param taskId Task identifier
     * @param attemptNumber Current attempt number (1-based)
     * @param reason Reason for restart (e.g., "OUT_OF_MEMORY", "OOM_KILLED")
     * @param newHeapBytes New heap size after adjustment
     * @param newBatchSize New batch size after adjustment
     * @param newThreadCount New thread count after adjustment
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void recordRestartAttempt(String taskId, int attemptNumber, String reason,
                                      long newHeapBytes, int newBatchSize, int newThreadCount) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.recordRestartAttempt(attemptNumber, reason, newHeapBytes, newBatchSize, newThreadCount);
            repository.save(job);
            log.info("Recorded restart attempt {} for job {} (reason: {}, heap: {}MB, batch: {}, threads: {})",
                    attemptNumber, taskId, reason, newHeapBytes / (1024 * 1024), newBatchSize, newThreadCount);
        });
    }

    /**
     * Mark a job's restart as successful (job completed after restart).
     *
     * @param taskId Task identifier
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void markRestartSuccessful(String taskId) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markRestartSuccessful();
            repository.save(job);
            log.info("Marked restart as successful for job {} (attempt {})", taskId, job.getRestartAttempts());
        });
    }

    /**
     * Update restart tracking info from callback data.
     *
     * @param taskId Task identifier
     * @param restartAttempts Number of restart attempts (null to not update)
     * @param maxRestartAttempts Max restart attempts (null to not update)
     * @param initialHeapBytes Initial heap bytes (null to not update)
     * @param finalHeapBytes Final heap bytes (null to not update)
     * @param initialBatchSize Initial batch size (null to not update)
     * @param finalBatchSize Final batch size (null to not update)
     * @param initialThreadCount Initial thread count (null to not update)
     * @param finalThreadCount Final thread count (null to not update)
     * @param reason Restart reason (null to not update)
     * @param recoveredAfterRestart Whether recovered after restart (null to not update)
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void updateRestartInfo(String taskId,
                                   Integer restartAttempts,
                                   Integer maxRestartAttempts,
                                   Long initialHeapBytes,
                                   Long finalHeapBytes,
                                   Integer initialBatchSize,
                                   Integer finalBatchSize,
                                   Integer initialThreadCount,
                                   Integer finalThreadCount,
                                   String reason,
                                   Boolean recoveredAfterRestart) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            // Update only non-null fields
            if (restartAttempts != null) {
                // If this is a new restart attempt, record it properly
                if (restartAttempts > 0 && (job.getRestartAttempts() == null || restartAttempts > job.getRestartAttempts())) {
                    job.recordRestartAttempt(
                            restartAttempts,
                            reason,
                            finalHeapBytes != null ? finalHeapBytes : (job.getFinalHeapBytes() != null ? job.getFinalHeapBytes() : 0L),
                            finalBatchSize != null ? finalBatchSize : (job.getFinalBatchSize() != null ? job.getFinalBatchSize() : 0),
                            finalThreadCount != null ? finalThreadCount : (job.getFinalThreadCount() != null ? job.getFinalThreadCount() : 0)
                    );
                } else {
                    job.setRestartAttempts(restartAttempts);
                }
            }
            if (maxRestartAttempts != null) job.setMaxRestartAttempts(maxRestartAttempts);
            if (initialHeapBytes != null) job.setInitialHeapBytes(initialHeapBytes);
            if (finalHeapBytes != null) job.setFinalHeapBytes(finalHeapBytes);
            if (initialBatchSize != null) job.setInitialBatchSize(initialBatchSize);
            if (finalBatchSize != null) job.setFinalBatchSize(finalBatchSize);
            if (initialThreadCount != null) job.setInitialThreadCount(initialThreadCount);
            if (finalThreadCount != null) job.setFinalThreadCount(finalThreadCount);
            if (recoveredAfterRestart != null && recoveredAfterRestart) {
                job.markRestartSuccessful();
            }
            repository.save(job);
            log.debug("Updated restart info for job {}", taskId);
        });
    }

    /**
     * Get jobs that recovered after restart.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsRecoveredAfterRestart() {
        return repository.findByRecoveredAfterRestartTrue();
    }

    /**
     * Get jobs with restart attempts.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsWithRestarts() {
        return repository.findByRestartAttemptsGreaterThan(0);
    }

    // ===================== QUERY METHODS =====================

    /**
     * Get a job by task ID.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Optional<IndexingJobHistory> getJob(String taskId) {
        return repository.findByTaskId(taskId);
    }

    /**
     * Get all jobs with pagination.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<IndexingJobHistory> getAllJobs(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        return repository.findAll(pageable);
    }

    /**
     * Get jobs by status.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsByStatus(JobStatus status) {
        return repository.findByStatusOrderByStartTimeDesc(status);
    }

    /**
     * Get jobs by status within a time range.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsByStatus(JobStatus status, int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return repository.findByStatusAndStartTimeAfter(status, since);
    }

    /**
     * Get jobs by status with pagination.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<IndexingJobHistory> getJobsByStatus(JobStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        return repository.findByStatus(status, pageable);
    }

    /**
     * Get crawl job history entries (taskId starts with "crawl-").
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getCrawlJobs() {
        return repository.findCrawlJobs();
    }

    /**
     * Get crawl job history entries with pagination.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<IndexingJobHistory> getCrawlJobs(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        return repository.findCrawlJobs(pageable);
    }

    /**
     * Get recent jobs (last N hours).
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getRecentJobs(int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return repository.findRecentJobs(since);
    }

    /**
     * Get recent jobs with pagination.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<IndexingJobHistory> getRecentJobs(int hours, int page, int size) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"));
        return repository.findRecentJobs(since, pageable);
    }

    /**
     * Get jobs in a time range.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsBetween(Instant start, Instant end) {
        return repository.findByStartTimeBetweenOrderByStartTimeDesc(start, end);
    }

    /**
     * Get failed jobs.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getFailedJobs() {
        List<IndexingJobHistory> failed = new ArrayList<>();
        failed.addAll(repository.findByStatusOrderByStartTimeDesc(JobStatus.FAILED));
        failed.addAll(repository.findByStatusOrderByStartTimeDesc(JobStatus.MEMORY_KILLED));
        failed.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return failed;
    }

    /**
     * Get jobs by failure reason.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getJobsByFailureReason(FailureReason reason) {
        return repository.findByFailureReasonOrderByStartTimeDesc(reason);
    }

    /**
     * Get currently active jobs.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getActiveJobs() {
        return repository.findActiveJobs();
    }

    /**
     * Get the most recent N jobs.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getMostRecentJobs(int limit) {
        return repository.findTop100ByOrderByStartTimeDesc().stream()
                .limit(limit)
                .toList();
    }

    /**
     * Search jobs by file name pattern.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> searchByFileName(String pattern) {
        return repository.findByFileNamePattern("%" + pattern + "%");
    }

    /**
     * Get jobs with high memory usage.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getHighMemoryJobs(double threshold) {
        return repository.findJobsWithHighMemoryUsage(threshold);
    }

    /**
     * Get long-running jobs.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<IndexingJobHistory> getLongRunningJobs(long thresholdMs) {
        return repository.findLongRunningJobs(thresholdMs);
    }

    // ===================== STATISTICS METHODS =====================

    /**
     * Get job statistics summary.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Map<String, Object> getJobStatistics(int lastHours) {
        Instant start = Instant.now().minus(Duration.ofHours(lastHours));
        Instant end = Instant.now();

        Map<String, Object> stats = new HashMap<>();

        // Status counts - filtered by time range to match the job list
        stats.put("totalJobs", repository.countByStartTimeAfter(start));
        stats.put("completedJobs", repository.countByStatusAndStartTimeAfter(JobStatus.COMPLETED, start));
        stats.put("failedJobs", repository.countByStatusAndStartTimeAfter(JobStatus.FAILED, start));
        stats.put("cancelledJobs", repository.countByStatusAndStartTimeAfter(JobStatus.CANCELLED, start));
        stats.put("memoryKilledJobs", repository.countByStatusAndStartTimeAfter(JobStatus.MEMORY_KILLED, start));
        stats.put("activeJobs", repository.findActiveJobs().size());

        // Status breakdown
        List<Object[]> statusStats = repository.getJobStatisticsByStatus(start, end);
        List<Map<String, Object>> statusBreakdown = new ArrayList<>();
        for (Object[] row : statusStats) {
            Map<String, Object> statusItem = new HashMap<>();
            statusItem.put("status", row[0]);
            statusItem.put("count", row[1]);
            statusItem.put("avgDurationMs", row[2]);
            statusItem.put("totalDocumentsIndexed", row[3]);
            statusBreakdown.add(statusItem);
        }
        stats.put("statusBreakdown", statusBreakdown);

        // Throughput
        List<Object[]> throughput = repository.getThroughputStatistics(start, end);
        if (!throughput.isEmpty() && throughput.get(0) != null) {
            Object[] row = throughput.get(0);
            stats.put("totalDocumentsIndexed", row[0]);
            stats.put("totalChunksCreated", row[1]);
            stats.put("totalProcessingTimeMs", row[2]);
            stats.put("completedJobsInPeriod", row[3]);
        }

        // Average phase timings
        List<Object[]> timings = repository.getAveragePhaseTimings(start, end);
        if (!timings.isEmpty() && timings.get(0) != null) {
            Object[] row = timings.get(0);
            Map<String, Object> avgTimings = new HashMap<>();
            avgTimings.put("loadingMs", row[0]);
            avgTimings.put("conversionMs", row[1]);
            avgTimings.put("chunkingMs", row[2]);
            avgTimings.put("embeddingMs", row[3]);
            avgTimings.put("indexingMs", row[4]);
            stats.put("averagePhaseTimings", avgTimings);
        }

        // Failure analysis
        List<Object[]> failures = repository.getFailureStatistics(start, end);
        List<Map<String, Object>> failureBreakdown = new ArrayList<>();
        for (Object[] row : failures) {
            Map<String, Object> failureItem = new HashMap<>();
            failureItem.put("reason", row[0]);
            failureItem.put("count", row[1]);
            failureItem.put("avgDurationMs", row[2]);
            failureBreakdown.add(failureItem);
        }
        stats.put("failureBreakdown", failureBreakdown);

        // Distinct configurations
        stats.put("embeddingModels", repository.findDistinctEmbeddingModels());
        stats.put("loaders", repository.findDistinctLoaders());
        stats.put("chunkers", repository.findDistinctChunkers());

        return stats;
    }

    /**
     * Get failure rate for a time period.
     */
    @Transactional(value = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public double getFailureRate(int lastHours) {
        long completed = repository.countByStatus(JobStatus.COMPLETED);
        long failed = repository.countByStatus(JobStatus.FAILED);
        long memoryKilled = repository.countByStatus(JobStatus.MEMORY_KILLED);
        long total = completed + failed + memoryKilled;
        if (total == 0)
            return 0.0;
        return (double) (failed + memoryKilled) / total * 100.0;
    }

    // ===================== CLEANUP METHODS =====================

    /**
     * Cleanup old job history records.
     * Runs daily at 3 AM.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void cleanupOldJobs() {
        // 1. Time-based retention
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<IndexingJobHistory> oldJobs = repository.findByStartTimeBefore(cutoff);

        int timeDeleted = 0;
        for (IndexingJobHistory job : oldJobs) {
            // Delete associated checkpoints
            cleanupJobArtifacts(job.getTaskId());
            repository.delete(job);
            timeDeleted++;
        }

        if (timeDeleted > 0) {
            log.info("Cleaned up {} old job history records (older than {} days)", timeDeleted, retentionDays);
        }

        // 2. Max records retention
        try {
            long totalCount = repository.count();
            if (totalCount > maxRecords) {
                long toDelete = totalCount - maxRecords;
                log.info("Total job records ({}) exceeds limit ({}), deleting {} oldest records",
                        totalCount, maxRecords, toDelete);

                // Delete in batches
                int batchSize = 100;
                long deletedCount = 0;

                while (deletedCount < toDelete) {
                    int limit = (int) Math.min(batchSize, toDelete - deletedCount);
                    // Always fetch page 0 as we are deleting them
                    Page<IndexingJobHistory> oldestJobs = repository.findAll(
                            PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "startTime")));

                    if (oldestJobs.isEmpty())
                        break;

                    for (IndexingJobHistory job : oldestJobs) {
                        cleanupJobArtifacts(job.getTaskId());
                        repository.delete(job);
                        deletedCount++;
                    }
                }
                log.info("Cleaned up {} excess job history records due to max-records limit", deletedCount);
            }
        } catch (Exception e) {
            log.error("Failed to enforce max job history records: {}", e.getMessage(), e);
        }
    }

    /**
     * Delete associated job artifacts (checkpoints, logs) from disk and database.
     * Archives logs before deleting if archiving is enabled.
     */
    private void cleanupJobArtifacts(String taskId) {
        if (taskId == null || taskId.isEmpty())
            return;

        // Archive and delete job logs from database
        if (jobLogService != null) {
            try {
                // Archive logs before deleting if archiving is enabled
                if (jobLogService.isArchiveEnabled()) {
                    try {
                        Path archivePath = jobLogService.archiveLogsForTask(taskId);
                        if (archivePath != null) {
                            log.debug("Archived log entries for taskId: {} to {}", taskId, archivePath);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to archive logs for job {} before deletion: {}", taskId, e.getMessage());
                        // Continue with deletion even if archive fails
                    }
                }
                jobLogService.deleteLogsForTask(taskId);
                log.debug("Deleted log entries for taskId: {}", taskId);
            } catch (Exception e) {
                log.warn("Failed to delete logs for job {}: {}", taskId, e.getMessage());
            }
        }

        // Delete checkpoints from disk
        try {
            // Checkpoints are stored in {stateDirectory}/checkpoints/{taskId}
            Path checkpointPath = Paths.get(stateDirectory, "checkpoints", taskId);
            if (Files.exists(checkpointPath)) {
                try (Stream<Path> walk = Files.walk(checkpointPath)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
                log.debug("Deleted checkpoint directory for taskId: {}", taskId);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up checkpoint artifacts for job {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Force cleanup of all jobs older than specified days.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public int forceCleanup(int days) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int deleted = repository.deleteJobsOlderThan(cutoff);
        log.info("Force cleanup removed {} job history records older than {} days", deleted, days);
        return deleted;
    }

    /**
     * Delete a specific job history.
     */
    @Transactional(PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public boolean deleteJob(String taskId) {
        Optional<IndexingJobHistory> job = repository.findByTaskId(taskId);
        if (job.isPresent()) {
            cleanupJobArtifacts(taskId);
            repository.delete(job.get());
            log.info("Deleted job history and artifacts for taskId: {}", taskId);
            return true;
        }
        return false;
    }

}
