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

package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for indexing job history.
 * Provides endpoints to query and manage historical indexing job data.
 */
@Slf4j
@RestController
@RequestMapping("/api/indexing/history")
@ConditionalOnBean(IndexingJobHistoryService.class)
public class IndexingJobHistoryController {

    private final IndexingJobHistoryService historyService;
    private final JobLogService jobLogService;

    @Autowired
    public IndexingJobHistoryController(
            IndexingJobHistoryService historyService,
            @Autowired(required = false) JobLogService jobLogService) {
        this.historyService = historyService;
        this.jobLogService = jobLogService;
    }

    /**
     * Get all jobs with pagination.
     */
    @GetMapping
    public ResponseEntity<Page<IndexingJobHistory>> getAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(historyService.getAllJobs(page, size));
    }

    /**
     * Get a specific job by task ID.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<IndexingJobHistory> getJob(@PathVariable String taskId) {
        return historyService.getJob(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get jobs by status, optionally filtered by time range.
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<IndexingJobHistory>> getJobsByStatus(
            @PathVariable JobStatus status,
            @RequestParam(required = false) Integer hours) {
        if (hours != null) {
            return ResponseEntity.ok(historyService.getJobsByStatus(status, hours));
        }
        return ResponseEntity.ok(historyService.getJobsByStatus(status));
    }

    /**
     * Get jobs by status with pagination.
     */
    @GetMapping("/status/{status}/page")
    public ResponseEntity<Page<IndexingJobHistory>> getJobsByStatusPaged(
            @PathVariable JobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(historyService.getJobsByStatus(status, page, size));
    }

    /**
     * Get recent jobs (last N hours).
     */
    @GetMapping("/recent")
    public ResponseEntity<List<IndexingJobHistory>> getRecentJobs(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(historyService.getRecentJobs(hours));
    }

    /**
     * Get recent jobs with pagination.
     */
    @GetMapping("/recent/page")
    public ResponseEntity<Page<IndexingJobHistory>> getRecentJobsPaged(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(historyService.getRecentJobs(hours, page, size));
    }

    /**
     * Get jobs in a time range.
     */
    @GetMapping("/range")
    public ResponseEntity<List<IndexingJobHistory>> getJobsInRange(
            @RequestParam(name = "start") String start,
            @RequestParam(name = "end") String end) {
        Instant startTime = Instant.parse(start);
        Instant endTime = Instant.parse(end);
        return ResponseEntity.ok(historyService.getJobsBetween(startTime, endTime));
    }

    /**
     * Get failed jobs.
     */
    @GetMapping("/failed")
    public ResponseEntity<List<IndexingJobHistory>> getFailedJobs() {
        return ResponseEntity.ok(historyService.getFailedJobs());
    }

    /**
     * Get jobs by failure reason.
     */
    @GetMapping("/failed/{reason}")
    public ResponseEntity<List<IndexingJobHistory>> getJobsByFailureReason(@PathVariable FailureReason reason) {
        return ResponseEntity.ok(historyService.getJobsByFailureReason(reason));
    }

    /**
     * Get currently active jobs.
     */
    @GetMapping("/active")
    public ResponseEntity<List<IndexingJobHistory>> getActiveJobs() {
        return ResponseEntity.ok(historyService.getActiveJobs());
    }

    /**
     * Get the most recent N jobs.
     */
    @GetMapping("/latest")
    public ResponseEntity<List<IndexingJobHistory>> getLatestJobs(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(historyService.getMostRecentJobs(limit));
    }

    /**
     * Search jobs by file name.
     */
    @GetMapping("/search")
    public ResponseEntity<List<IndexingJobHistory>> searchByFileName(@RequestParam(name = "fileName") String fileName) {
        return ResponseEntity.ok(historyService.searchByFileName(fileName));
    }

    /**
     * Get jobs with high memory usage.
     */
    @GetMapping("/high-memory")
    public ResponseEntity<List<IndexingJobHistory>> getHighMemoryJobs(
            @RequestParam(defaultValue = "80") double threshold) {
        return ResponseEntity.ok(historyService.getHighMemoryJobs(threshold));
    }

    /**
     * Get long-running jobs.
     */
    @GetMapping("/long-running")
    public ResponseEntity<List<IndexingJobHistory>> getLongRunningJobs(
            @RequestParam(defaultValue = "60000") long thresholdMs) {
        return ResponseEntity.ok(historyService.getLongRunningJobs(thresholdMs));
    }

    /**
     * Get job statistics summary.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(defaultValue = "24") int lastHours) {
        return ResponseEntity.ok(historyService.getJobStatistics(lastHours));
    }

    /**
     * Get failure rate.
     */
    @GetMapping("/statistics/failure-rate")
    public ResponseEntity<Map<String, Object>> getFailureRate(
            @RequestParam(defaultValue = "24") int lastHours) {
        double rate = historyService.getFailureRate(lastHours);
        return ResponseEntity.ok(Map.of(
                "failureRatePercent", rate,
                "periodHours", lastHours
        ));
    }

    /**
     * Delete a specific job history entry.
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteJob(@PathVariable String taskId) {
        boolean deleted = historyService.deleteJob(taskId);
        if (deleted) {
            return ResponseEntity.ok(Map.of(
                    "deleted", true,
                    "taskId", taskId
            ));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Force cleanup of old job history.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> forceCleanup(
            @RequestParam(defaultValue = "30") int olderThanDays) {
        int deleted = historyService.forceCleanup(olderThanDays);
        return ResponseEntity.ok(Map.of(
                "deleted", deleted,
                "olderThanDays", olderThanDays
        ));
    }

    /**
     * Get summary view of a job for display.
     * Returns a simplified view with key information.
     */
    @GetMapping("/{taskId}/summary")
    public ResponseEntity<Map<String, Object>> getJobSummary(@PathVariable String taskId) {
        Optional<IndexingJobHistory> jobOpt = historyService.getJob(taskId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        IndexingJobHistory job = jobOpt.get();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();

        // Basic info
        summary.put("taskId", job.getTaskId());
        summary.put("fileName", job.getFileName());
        summary.put("status", job.getStatus());
        summary.put("progressPercent", job.getProgressPercent());

        // Timing
        summary.put("startTime", job.getStartTime());
        summary.put("endTime", job.getEndTime());
        summary.put("totalDurationMs", job.getTotalDurationMs());

        // Where it stopped
        summary.put("lastPhase", job.getLastPhase());
        if (job.getFailedPhase() != null) {
            summary.put("failedPhase", job.getFailedPhase());
        }
        if (job.getFailureReason() != null && job.getFailureReason() != FailureReason.NONE) {
            summary.put("failureReason", job.getFailureReason());
        }
        if (job.getErrorMessage() != null) {
            summary.put("errorMessage", job.getErrorMessage());
        }

        // Processing parameters
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("loaderUsed", job.getLoaderUsed());
        parameters.put("chunkerUsed", job.getChunkerUsed());
        parameters.put("embeddingModelUsed", job.getEmbeddingModelUsed());
        parameters.put("chunkSize", job.getChunkSize());
        parameters.put("chunkOverlap", job.getChunkOverlap());
        parameters.put("embeddingBatchSize", job.getEmbeddingBatchSize());
        parameters.put("workerThreads", job.getWorkerThreads());
        summary.put("parameters", parameters);

        // Results
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        results.put("documentsLoaded", job.getDocumentsLoaded());
        results.put("chunksCreated", job.getChunksCreated());
        results.put("chunksEmbedded", job.getChunksEmbedded());
        results.put("documentsIndexed", job.getDocumentsIndexed());
        summary.put("results", results);

        // Phase timings
        Map<String, Object> timings = new java.util.LinkedHashMap<>();
        if (job.getLoadingDurationMs() != null) timings.put("loadingMs", job.getLoadingDurationMs());
        if (job.getConversionDurationMs() != null) timings.put("conversionMs", job.getConversionDurationMs());
        if (job.getChunkingDurationMs() != null) timings.put("chunkingMs", job.getChunkingDurationMs());
        if (job.getEmbeddingDurationMs() != null) timings.put("embeddingMs", job.getEmbeddingDurationMs());
        if (job.getIndexingDurationMs() != null) timings.put("indexingMs", job.getIndexingDurationMs());
        if (!timings.isEmpty()) {
            summary.put("phaseTimings", timings);
        }

        // Environment
        Map<String, Object> environment = new java.util.LinkedHashMap<>();
        environment.put("javaVersion", job.getJavaVersion());
        environment.put("osInfo", job.getOsInfo());
        environment.put("availableProcessors", job.getAvailableProcessors());
        if (job.getMaxHeapMemoryBytes() != null) {
            environment.put("maxHeapMB", job.getMaxHeapMemoryBytes() / (1024 * 1024));
        }
        environment.put("nd4jBackend", job.getNd4jBackend());
        // Include full ND4J environment configuration captured at job start
        if (job.getNd4jEnvironmentJson() != null && !job.getNd4jEnvironmentJson().isEmpty()) {
            environment.put("nd4jEnvironmentJson", job.getNd4jEnvironmentJson());
        }
        summary.put("environment", environment);

        // Memory
        Map<String, Object> memory = new java.util.LinkedHashMap<>();
        memory.put("usageAtStart", job.getMemoryUsagePercentAtStart());
        memory.put("usageAtEnd", job.getMemoryUsagePercentAtEnd());
        memory.put("peakUsage", job.getPeakMemoryUsagePercent());
        summary.put("memoryPercent", memory);

        return ResponseEntity.ok(summary);
    }

    // ========== Embedding Subprocess Logs ==========

    /**
     * Get embedding subprocess logs for a specific model.
     * Logs are stored with task ID format: "embedding-{modelId}"
     */
    @GetMapping("/embedding/{modelId}/logs")
    public ResponseEntity<?> getEmbeddingLogs(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is not enabled",
                    "logs", List.of()
            ));
        }

        String taskId = "embedding-" + modelId;
        Page<JobLogEntry> logs = jobLogService.getLogsForTask(taskId, page, size);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "modelId", modelId,
                "logs", logs.getContent(),
                "totalElements", logs.getTotalElements(),
                "totalPages", logs.getTotalPages(),
                "page", page,
                "size", size
        ));
    }

    /**
     * Get all embedding subprocess logs (all models).
     * Returns logs for all task IDs starting with "embedding-"
     */
    @GetMapping("/embedding/logs")
    public ResponseEntity<?> getAllEmbeddingLogs(
            @RequestParam(defaultValue = "100") int limit) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is not enabled",
                    "logs", List.of()
            ));
        }

        // Get logs for all embedding tasks using the EMBEDDING source type
        List<JobLogEntry> logs = jobLogService.getLogsBySource(
                JobLogEntry.LogSource.EMBEDDING, limit);

        // Also get distinct model IDs that have embedding logs
        List<String> modelTaskIds = jobLogService.getTaskIdsBySource(JobLogEntry.LogSource.EMBEDDING);

        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "count", logs.size(),
                "limit", limit,
                "modelTaskIds", modelTaskIds
        ));
    }

    /**
     * Get embedding log statistics.
     */
    @GetMapping("/embedding/logs/stats")
    public ResponseEntity<?> getEmbeddingLogStats() {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is not enabled"
            ));
        }

        return ResponseEntity.ok(jobLogService.getStatistics());
    }

    /**
     * Get the last N embedding logs (tail).
     */
    @GetMapping("/embedding/{modelId}/logs/tail")
    public ResponseEntity<?> tailEmbeddingLogs(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "50") int lines) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is not enabled",
                    "logs", List.of()
            ));
        }

        String taskId = "embedding-" + modelId;
        List<JobLogEntry> logs = jobLogService.tailLogs(taskId, lines);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "modelId", modelId,
                "logs", logs,
                "count", logs.size()
        ));
    }

    /**
     * Clear embedding logs for a specific model.
     */
    @DeleteMapping("/embedding/{modelId}/logs")
    public ResponseEntity<?> clearEmbeddingLogs(@PathVariable String modelId) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is not enabled"
            ));
        }

        String taskId = "embedding-" + modelId;
        jobLogService.deleteLogsForTask(taskId);
        return ResponseEntity.ok(Map.of(
                "deleted", true,
                "taskId", taskId,
                "modelId", modelId
        ));
    }
}
