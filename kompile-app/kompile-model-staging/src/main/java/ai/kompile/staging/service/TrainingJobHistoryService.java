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

package ai.kompile.staging.service;

import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.domain.TrainingJobHistory.*;
import ai.kompile.staging.repository.TrainingJobHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service for managing training job history.
 */
@Service
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class TrainingJobHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TrainingJobHistoryService.class);

    private final TrainingJobHistoryRepository repository;

    @Value("${kompile.training.job-history.retention-days:30}")
    private int retentionDays;

    @Value("${kompile.training.job-history.max-records:10000}")
    private int maxRecords;

    public TrainingJobHistoryService(TrainingJobHistoryRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    public void cleanupOrphanedJobsOnStartup() {
        try {
            List<TrainingJobHistory> orphanedJobs = repository.findActiveJobs();
            if (orphanedJobs.isEmpty()) return;

            log.info("Found {} orphaned training jobs from previous run, marking as FAILED", orphanedJobs.size());
            for (TrainingJobHistory job : orphanedJobs) {
                job.setStatus(JobStatus.FAILED);
                job.setFailureReason(FailureReason.UNKNOWN);
                job.setErrorMessage("Job interrupted by server restart or crash");
                job.setEndTime(Instant.now());
                if (job.getStartTime() != null) {
                    job.setTotalDurationMs(Duration.between(job.getStartTime(), Instant.now()).toMillis());
                }
                repository.save(job);
                log.info("Marked orphaned training job {} as FAILED", job.getTaskId());
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup orphaned training jobs on startup: {}", e.getMessage());
        }
    }

    // ===================== LIFECYCLE METHODS =====================

    @Transactional
    public TrainingJobHistory createJob(String taskId, TrainingType trainingType,
                                         String modelId, String datasetId) {
        Optional<TrainingJobHistory> existing = repository.findByTaskId(taskId);
        if (existing.isPresent()) {
            log.warn("Training job history already exists for taskId: {}", taskId);
            return existing.get();
        }

        TrainingJobHistory job = TrainingJobHistory.createQueued(taskId, trainingType, modelId, datasetId);
        TrainingJobHistory saved = repository.save(job);
        repository.flush();
        log.debug("Created training job history for taskId: {}", taskId);
        return saved;
    }

    @Transactional
    public void markRunning(String taskId) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markRunning();
            repository.save(job);
            log.debug("Marked training job {} as RUNNING", taskId);
        });
    }

    @Transactional
    public void updateProgress(String taskId, int epoch, long step, double loss, double lr) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.updateProgress(epoch, step, loss, lr);
            repository.save(job);
        });
    }

    @Transactional
    public void markCompleted(String taskId, double finalLoss, double finalEvalLoss,
                               long totalSteps, String outputPath) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markCompleted(finalLoss, finalEvalLoss, totalSteps, outputPath);
            repository.save(job);
            log.info("Marked training job {} as COMPLETED (duration: {}ms)", taskId, job.getTotalDurationMs());
        });
    }

    @Transactional
    public void markFailed(String taskId, String errorMessage, Throwable exception, FailureReason reason) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markFailed(errorMessage, exception, reason);
            repository.save(job);
            log.warn("Marked training job {} as FAILED (reason: {})", taskId, reason);
        });
    }

    @Transactional
    public void markCancelled(String taskId, String reason) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markCancelled(reason);
            repository.save(job);
            log.info("Marked training job {} as CANCELLED", taskId);
        });
    }

    @Transactional
    public void markMemoryKilled(String taskId, double memoryPercent) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            job.markMemoryKilled(memoryPercent);
            repository.save(job);
            log.warn("Marked training job {} as MEMORY_KILLED at {}%", taskId, memoryPercent);
        });
    }

    @Transactional
    public void updateTrainingParameters(String taskId, Integer batchSize, String lrSchedule,
                                          Double warmupRatio, Double maxGradNorm, Boolean fp16,
                                          Boolean bf16, String peftType, Integer seed) {
        repository.findByTaskId(taskId).ifPresent(job -> {
            if (batchSize != null) job.setBatchSize(batchSize);
            if (lrSchedule != null) job.setLrSchedule(lrSchedule);
            if (warmupRatio != null) job.setWarmupRatio(warmupRatio);
            if (maxGradNorm != null) job.setMaxGradNorm(maxGradNorm);
            if (fp16 != null) job.setFp16(fp16);
            if (bf16 != null) job.setBf16(bf16);
            if (peftType != null) job.setPeftType(peftType);
            if (seed != null) job.setSeed(seed);
            repository.save(job);
        });
    }

    // ===================== QUERY METHODS =====================

    @Transactional(readOnly = true)
    public Optional<TrainingJobHistory> getJob(String taskId) {
        return repository.findByTaskId(taskId);
    }

    @Transactional(readOnly = true)
    public Page<TrainingJobHistory> getAllJobs(int page, int size) {
        return repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime")));
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getJobsByStatus(JobStatus status) {
        return repository.findByStatusOrderByStartTimeDesc(status);
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getJobsByTrainingType(TrainingType trainingType) {
        return repository.findByTrainingTypeOrderByStartTimeDesc(trainingType);
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getJobsByModelId(String modelId) {
        return repository.findByModelIdOrderByStartTimeDesc(modelId);
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getRecentJobs(int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return repository.findRecentJobs(since);
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getActiveJobs() {
        return repository.findActiveJobs();
    }

    @Transactional(readOnly = true)
    public List<TrainingJobHistory> getMostRecentJobs(int limit) {
        return repository.findTop100ByOrderByStartTimeDesc().stream().limit(limit).toList();
    }

    // ===================== STATISTICS =====================

    @Transactional(readOnly = true)
    public Map<String, Object> getJobStatistics(int lastHours) {
        Instant start = Instant.now().minus(Duration.ofHours(lastHours));
        Instant end = Instant.now();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalJobs", repository.count());
        stats.put("completedJobs", repository.countByStatus(JobStatus.COMPLETED));
        stats.put("failedJobs", repository.countByStatus(JobStatus.FAILED));
        stats.put("cancelledJobs", repository.countByStatus(JobStatus.CANCELLED));
        stats.put("activeJobs", repository.findActiveJobs().size());

        List<Object[]> statusStats = repository.getJobStatisticsByStatus(start, end);
        List<Map<String, Object>> statusBreakdown = new ArrayList<>();
        for (Object[] row : statusStats) {
            Map<String, Object> item = new HashMap<>();
            item.put("status", row[0]);
            item.put("count", row[1]);
            item.put("avgDurationMs", row[2]);
            statusBreakdown.add(item);
        }
        stats.put("statusBreakdown", statusBreakdown);
        return stats;
    }

    // ===================== CLEANUP =====================

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = repository.deleteJobsOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} old training job records (older than {} days)", deleted, retentionDays);
        }
    }

    @Transactional
    public int forceCleanup(int days) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int deleted = repository.deleteJobsOlderThan(cutoff);
        log.info("Force cleanup removed {} training job records older than {} days", deleted, days);
        return deleted;
    }

    @Transactional
    public boolean deleteJob(String taskId) {
        Optional<TrainingJobHistory> job = repository.findByTaskId(taskId);
        if (job.isPresent()) {
            repository.delete(job.get());
            log.info("Deleted training job history for taskId: {}", taskId);
            return true;
        }
        return false;
    }
}
