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

import ai.kompile.app.ingest.service.JobLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing job log configuration.
 * Provides endpoints for viewing and updating log settings.
 */
@RestController
@RequestMapping("/api/config/logs")
@CrossOrigin(origins = "*")
@ConditionalOnBean(JobLogService.class)
public class LogConfigController {

    private static final Logger logger = LoggerFactory.getLogger(LogConfigController.class);

    private final JobLogService jobLogService;

    @Autowired
    public LogConfigController(@Autowired(required = false) JobLogService jobLogService) {
        this.jobLogService = jobLogService;
    }

    /**
     * Get current log configuration.
     */
    @GetMapping
    public ResponseEntity<?> getConfiguration() {
        if (jobLogService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "JobLogService not available"
            ));
        }

        try {
            Map<String, Object> config = jobLogService.getConfiguration();
            Map<String, Object> status = jobLogService.getStatus();

            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "config", config,
                    "status", status
            ));
        } catch (Exception e) {
            logger.error("Error getting log configuration: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to get configuration: " + e.getMessage()
            ));
        }
    }

    /**
     * Update log configuration.
     */
    @PutMapping
    public ResponseEntity<?> updateConfiguration(@RequestBody LogConfigUpdateRequest request) {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            jobLogService.updateConfiguration(
                    request.enabled(),
                    request.retentionDays(),
                    request.maxEntriesPerJob(),
                    request.maxTotalEntries(),
                    request.archiveEnabled(),
                    request.archivePath(),
                    request.archiveOnCleanup()
            );

            Map<String, Object> updatedConfig = jobLogService.getConfiguration();
            Map<String, Object> status = jobLogService.getStatus();

            logger.info("Log configuration updated: {}", updatedConfig);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "config", updatedConfig,
                    "status", status,
                    "message", "Configuration updated successfully"
            ));
        } catch (Exception e) {
            logger.error("Error updating log configuration: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to update configuration: " + e.getMessage()
            ));
        }
    }

    /**
     * Get log storage status.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        if (jobLogService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false
            ));
        }

        try {
            Map<String, Object> status = jobLogService.getStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error getting log status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to get status: " + e.getMessage()
            ));
        }
    }

    /**
     * Trigger manual cleanup.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<?> triggerCleanup(
            @RequestParam(defaultValue = "168") int hoursToKeep) { // Default: 7 days = 168 hours

        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            Map<String, Object> statusBefore = jobLogService.getStatus();
            int deleted = jobLogService.forceCleanup(hoursToKeep);
            Map<String, Object> statusAfter = jobLogService.getStatus();

            logger.info("Manual log cleanup completed: deleted {} entries (keeping last {} hours)",
                    deleted, hoursToKeep);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "deletedCount", deleted,
                    "hoursRetained", hoursToKeep,
                    "statusBefore", statusBefore,
                    "statusAfter", statusAfter
            ));
        } catch (Exception e) {
            logger.error("Error during manual cleanup: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to run cleanup: " + e.getMessage()
            ));
        }
    }

    /**
     * Enable job logging.
     */
    @PostMapping("/enable")
    public ResponseEntity<?> enable() {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            jobLogService.updateConfiguration(true, null, null, null);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "enabled", true,
                    "message", "Job logging enabled"
            ));
        } catch (Exception e) {
            logger.error("Error enabling log service: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to enable: " + e.getMessage()
            ));
        }
    }

    /**
     * Disable job logging.
     */
    @PostMapping("/disable")
    public ResponseEntity<?> disable() {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            jobLogService.updateConfiguration(false, null, null, null);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "enabled", false,
                    "message", "Job logging disabled"
            ));
        } catch (Exception e) {
            logger.error("Error disabling log service: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to disable: " + e.getMessage()
            ));
        }
    }

    // ========== Archive Endpoints ==========

    /**
     * List available log archives.
     */
    @GetMapping("/archives")
    public ResponseEntity<?> listArchives() {
        if (jobLogService == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "archives", List.of()
            ));
        }

        try {
            List<Map<String, Object>> archives = jobLogService.listArchives();
            return ResponseEntity.ok(Map.of(
                    "available", true,
                    "archiveEnabled", jobLogService.isArchiveEnabled(),
                    "archivePath", jobLogService.getArchivePath(),
                    "archiveOnCleanup", jobLogService.isArchiveOnCleanup(),
                    "archives", archives
            ));
        } catch (IOException e) {
            logger.error("Error listing archives: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to list archives: " + e.getMessage()
            ));
        }
    }

    /**
     * Create a full archive of all current logs.
     */
    @PostMapping("/archives/create")
    public ResponseEntity<?> createArchive() {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            Path archivePath = jobLogService.createFullArchive();
            if (archivePath == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "No logs to archive"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "archivePath", archivePath.toString(),
                    "fileName", archivePath.getFileName().toString(),
                    "message", "Archive created successfully"
            ));
        } catch (IOException e) {
            logger.error("Error creating archive: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create archive: " + e.getMessage()
            ));
        }
    }

    /**
     * Archive logs for a specific task.
     */
    @PostMapping("/archives/task/{taskId}")
    public ResponseEntity<?> archiveTaskLogs(@PathVariable String taskId) {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            Path archivePath = jobLogService.archiveLogsForTask(taskId);
            if (archivePath == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "No logs found for task: " + taskId
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "taskId", taskId,
                    "archivePath", archivePath.toString(),
                    "fileName", archivePath.getFileName().toString(),
                    "message", "Task logs archived successfully"
            ));
        } catch (IOException e) {
            logger.error("Error archiving task logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to archive task logs: " + e.getMessage()
            ));
        }
    }

    /**
     * Download an archive file.
     */
    @GetMapping("/archives/download/{fileName}")
    public ResponseEntity<Resource> downloadArchive(@PathVariable String fileName) {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Path archiveFile = jobLogService.getArchiveFile(fileName);
            if (archiveFile == null) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(archiveFile);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (SecurityException e) {
            logger.warn("Security exception downloading archive: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete an archive file.
     */
    @DeleteMapping("/archives/{fileName}")
    public ResponseEntity<?> deleteArchive(@PathVariable String fileName) {
        if (jobLogService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "JobLogService not available"
            ));
        }

        try {
            boolean deleted = jobLogService.deleteArchive(fileName);
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "fileName", fileName,
                        "message", "Archive deleted successfully"
                ));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (SecurityException e) {
            logger.warn("Security exception deleting archive: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid archive path"
            ));
        } catch (IOException e) {
            logger.error("Error deleting archive: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to delete archive: " + e.getMessage()
            ));
        }
    }

    /**
     * Request record for log configuration updates.
     */
    public record LogConfigUpdateRequest(
            Boolean enabled,
            Integer retentionDays,
            Integer maxEntriesPerJob,
            Long maxTotalEntries,
            Boolean archiveEnabled,
            String archivePath,
            Boolean archiveOnCleanup
    ) {}
}
