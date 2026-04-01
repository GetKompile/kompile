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

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.service.JobLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for accessing job log entries.
 * Provides endpoints for retrieving, filtering, searching, and downloading logs.
 */
@RestController
@RequestMapping("/api/indexing/jobs")
@CrossOrigin(origins = "*")
@ConditionalOnBean(JobLogService.class)
public class JobLogController {

    private static final Logger logger = LoggerFactory.getLogger(JobLogController.class);

    private final JobLogService jobLogService;

    @Autowired
    public JobLogController(@Autowired(required = false) JobLogService jobLogService) {
        this.jobLogService = jobLogService;
    }

    /**
     * Get all log entries for a job.
     */
    @GetMapping("/{taskId}/logs")
    public ResponseEntity<?> getLogsForJob(
            @PathVariable String taskId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String levels,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {

        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "logs", List.of(),
                    "totalCount", 0
            ));
        }

        try {
            List<JobLogEntry> logs;
            long totalCount;

            // Parse level filters if provided
            List<LogLevel> levelList = null;
            LogLevel singleLevel = null;

            if (level != null && !level.isBlank()) {
                singleLevel = LogLevel.valueOf(level.toUpperCase());
            } else if (levels != null && !levels.isBlank()) {
                levelList = Arrays.stream(levels.split(","))
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .map(LogLevel::valueOf)
                        .collect(Collectors.toList());
            }

            boolean hasSearch = search != null && !search.isBlank();
            boolean hasLevels = levelList != null && !levelList.isEmpty();
            boolean hasSingleLevel = singleLevel != null;

            Page<JobLogEntry> pagedLogs;

            // Handle all combinations of level + search filtering
            if (hasSearch && hasLevels) {
                // Combined: multiple levels + search
                pagedLogs = jobLogService.searchLogsWithLevels(taskId, search, levelList, page, size);
            } else if (hasSearch && hasSingleLevel) {
                // Combined: single level + search
                pagedLogs = jobLogService.searchLogsWithLevel(taskId, search, singleLevel, page, size);
            } else if (hasSearch) {
                // Search only (no level filter)
                pagedLogs = jobLogService.searchLogs(taskId, search, page, size);
            } else if (hasLevels) {
                // Multiple levels only (no search)
                pagedLogs = jobLogService.getLogsForTask(taskId, levelList, page, size);
            } else if (hasSingleLevel) {
                // Single level only (no search)
                pagedLogs = jobLogService.getLogsForTask(taskId, singleLevel, page, size);
            } else {
                // No filters - return all logs with pagination
                pagedLogs = jobLogService.getLogsForTask(taskId, page, size);
            }

            logs = pagedLogs.getContent();
            totalCount = pagedLogs.getTotalElements();

            // Get counts by level for summary
            Map<LogLevel, Long> levelCounts = jobLogService.getLogCountsByLevel(taskId);

            return ResponseEntity.ok(Map.of(
                    "enabled", true,
                    "taskId", taskId,
                    "logs", logs,
                    "totalCount", totalCount,
                    "page", page,
                    "size", size,
                    "levelCounts", levelCounts
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid log level: " + e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error retrieving logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to retrieve logs: " + e.getMessage()
            ));
        }
    }

    /**
     * Get the last N log entries for a job (tail).
     */
    @GetMapping("/{taskId}/logs/tail")
    public ResponseEntity<?> tailLogs(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "100") int lines) {

        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "logs", List.of()
            ));
        }

        try {
            List<JobLogEntry> logs = jobLogService.tailLogs(taskId, lines);
            return ResponseEntity.ok(Map.of(
                    "enabled", true,
                    "taskId", taskId,
                    "logs", logs,
                    "count", logs.size()
            ));
        } catch (Exception e) {
            logger.error("Error tailing logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to tail logs: " + e.getMessage()
            ));
        }
    }

    /**
     * Download logs as a text file.
     */
    @GetMapping("/{taskId}/logs/download")
    public ResponseEntity<?> downloadLogs(@PathVariable String taskId) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        try {
            String logContent = jobLogService.formatLogsForDownload(taskId);

            if (logContent.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            String filename = "job-logs-" + taskId + ".txt";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(logContent);
        } catch (Exception e) {
            logger.error("Error downloading logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Failed to download logs: " + e.getMessage());
        }
    }

    /**
     * Get log entries that contain errors with stack traces.
     */
    @GetMapping("/{taskId}/logs/errors")
    public ResponseEntity<?> getErrorsWithStackTrace(@PathVariable String taskId) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "errors", List.of()
            ));
        }

        try {
            List<JobLogEntry> errors = jobLogService.getErrorsWithStackTrace(taskId);
            return ResponseEntity.ok(Map.of(
                    "enabled", true,
                    "taskId", taskId,
                    "errors", errors,
                    "count", errors.size()
            ));
        } catch (Exception e) {
            logger.error("Error retrieving errors for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to retrieve errors: " + e.getMessage()
            ));
        }
    }

    /**
     * Get log count for a job.
     */
    @GetMapping("/{taskId}/logs/count")
    public ResponseEntity<?> getLogCount(@PathVariable String taskId) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "count", 0
            ));
        }

        try {
            long count = jobLogService.getLogCount(taskId);
            Map<LogLevel, Long> levelCounts = jobLogService.getLogCountsByLevel(taskId);

            return ResponseEntity.ok(Map.of(
                    "enabled", true,
                    "taskId", taskId,
                    "count", count,
                    "levelCounts", levelCounts
            ));
        } catch (Exception e) {
            logger.error("Error getting log count for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to get log count: " + e.getMessage()
            ));
        }
    }

    /**
     * Delete logs for a specific job.
     */
    @DeleteMapping("/{taskId}/logs")
    public ResponseEntity<?> deleteLogsForJob(@PathVariable String taskId) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Job logging is disabled"
            ));
        }

        try {
            long countBefore = jobLogService.getLogCount(taskId);
            jobLogService.deleteLogsForTask(taskId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "taskId", taskId,
                    "deletedCount", countBefore
            ));
        } catch (Exception e) {
            logger.error("Error deleting logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to delete logs: " + e.getMessage()
            ));
        }
    }

    /**
     * Get log statistics across all jobs.
     */
    @GetMapping("/logs/statistics")
    public ResponseEntity<?> getLogStatistics() {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false
            ));
        }

        try {
            Map<String, Object> stats = jobLogService.getStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error getting log statistics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to get statistics: " + e.getMessage()
            ));
        }
    }

    /**
     * Get logs from archives for a job.
     * This is used when the live logs have been cleaned up but archived.
     */
    @GetMapping("/{taskId}/logs/archive")
    public ResponseEntity<?> getArchivedLogs(@PathVariable String taskId) {
        if (jobLogService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "logs", List.of(),
                    "message", "Job log service not available"
            ));
        }

        try {
            // First check if there are any archives
            boolean hasArchives = jobLogService.hasArchivedLogsForTask(taskId);
            if (!hasArchives) {
                return ResponseEntity.ok(Map.of(
                        "available", false,
                        "taskId", taskId,
                        "logs", List.of(),
                        "message", "No archived logs found for this task"
                ));
            }

            // Read logs from archives
            List<JobLogService.ArchivedLogEntry> archivedLogs = jobLogService.readLogsFromArchive(taskId);

            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "taskId", taskId,
                    "logs", archivedLogs,
                    "totalCount", archivedLogs.size(),
                    "source", "archive"
            ));
        } catch (Exception e) {
            logger.error("Error retrieving archived logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to retrieve archived logs: " + e.getMessage()
            ));
        }
    }

    /**
     * Check if archived logs exist for a job.
     */
    @GetMapping("/{taskId}/logs/archive/check")
    public ResponseEntity<?> checkArchivedLogs(@PathVariable String taskId) {
        if (jobLogService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "hasArchive", false
            ));
        }

        try {
            boolean hasArchives = jobLogService.hasArchivedLogsForTask(taskId);
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "taskId", taskId,
                    "hasArchive", hasArchives
            ));
        } catch (Exception e) {
            logger.error("Error checking archived logs for task {}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to check archived logs: " + e.getMessage()
            ));
        }
    }
}
