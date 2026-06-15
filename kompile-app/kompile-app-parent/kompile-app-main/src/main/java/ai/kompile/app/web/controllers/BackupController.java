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

import ai.kompile.app.services.BackupService;
import ai.kompile.app.services.BackupService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for backup operations.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Getting backup service status</li>
 *   <li>Triggering manual backups</li>
 *   <li>Listing available backups</li>
 *   <li>Manually triggering cleanup</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(BackupService.class)
public class BackupController {

    private final BackupService backupService;

    /**
     * Get backup service status.
     *
     * @return Current status including last backup time and result
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            BackupStatus status = backupService.getStatus();

            response.put("enabled", status.enabled());
            response.put("inProgress", status.inProgress());
            response.put("backupPath", status.backupPath());
            response.put("retentionDays", status.retentionDays());
            response.put("format", status.format());
            response.put("intervalHours", status.intervalMs() / 3600000.0);

            if (status.lastBackupTime() != null) {
                response.put("lastBackupTime", status.lastBackupTime().toString());
            }

            if (status.lastResult() != null) {
                Map<String, Object> lastResult = new LinkedHashMap<>();
                lastResult.put("success", status.lastResult().success());
                lastResult.put("message", status.lastResult().message());
                lastResult.put("fileCount", status.lastResult().fileCount());
                lastResult.put("totalMB", status.lastResult().totalBytes() / (1024.0 * 1024.0));
                lastResult.put("durationMs", status.lastResult().durationMs());
                if (!status.lastResult().errors().isEmpty()) {
                    lastResult.put("errors", status.lastResult().errors());
                }
                response.put("lastResult", lastResult);
            }

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting backup status", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Trigger a manual backup.
     *
     * @return Result of the backup operation
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerBackup() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            log.info("Manual backup triggered via REST API");
            BackupResult result = backupService.triggerBackup();

            response.put("success", result.success());
            response.put("message", result.message());
            if (result.backupPath() != null) {
                response.put("backupPath", result.backupPath());
            }
            response.put("fileCount", result.fileCount());
            response.put("totalMB", result.totalBytes() / (1024.0 * 1024.0));
            response.put("durationMs", result.durationMs());

            if (!result.errors().isEmpty()) {
                response.put("errors", result.errors());
            }

            if (result.success()) {
                response.put("status", "success");
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "partial");
                return ResponseEntity.status(207).body(response);
            }

        } catch (Exception e) {
            log.error("Error triggering backup", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * List all available backups.
     *
     * @return List of backup information
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listBackups() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<BackupInfo> backups = backupService.listBackups();

            List<Map<String, Object>> backupList = new ArrayList<>();
            long totalSizeBytes = 0;

            for (BackupInfo backup : backups) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", backup.name());
                info.put("path", backup.path());
                info.put("createdAt", backup.createdAt().toString());
                info.put("sizeMB", backup.sizeBytes() / (1024.0 * 1024.0));
                info.put("format", backup.format());
                backupList.add(info);
                totalSizeBytes += backup.sizeBytes();
            }

            response.put("backups", backupList);
            response.put("count", backups.size());
            response.put("totalSizeMB", totalSizeBytes / (1024.0 * 1024.0));
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error listing backups", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Manually trigger cleanup of old backups.
     *
     * @return Number of backups deleted
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupBackups() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            log.info("Manual backup cleanup triggered via REST API");
            int deletedCount = backupService.cleanupOldBackups();

            response.put("deletedCount", deletedCount);
            response.put("message", String.format("Cleaned up %d old backup(s)", deletedCount));
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error cleaning up backups", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Delete a specific backup by name.
     *
     * @param name The backup name (e.g., "backup-20251220-143000" or "backup-20251220-143000.tar.gz")
     * @return Success or error response
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteBackup(@PathVariable String name) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // Validate name format to prevent path traversal
            if (!name.matches("backup-\\d{8}-\\d{6}(\\.tar\\.gz)?")) {
                response.put("status", "error");
                response.put("message", "Invalid backup name format");
                return ResponseEntity.badRequest().body(response);
            }

            BackupStatus status = backupService.getStatus();
            Path backupPath = Paths.get(status.backupPath(), name);

            if (!Files.exists(backupPath)) {
                response.put("status", "error");
                response.put("message", "Backup not found: " + name);
                return ResponseEntity.notFound().build();
            }

            // Delete the backup
            if (Files.isDirectory(backupPath)) {
                deleteDirectory(backupPath);
            } else {
                Files.deleteIfExists(backupPath);
            }

            log.info("Deleted backup: {}", name);
            response.put("deleted", name);
            response.put("message", "Backup deleted successfully");
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting backup: {}", name, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Restore from a specific backup.
     *
     * @param name The backup name
     * @return Result of the restore operation
     */
    @PostMapping("/{name}/restore")
    public ResponseEntity<Map<String, Object>> restoreBackup(@PathVariable String name) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // Validate name format to prevent path traversal
            if (!name.matches("backup-\\d{8}-\\d{6}(\\.tar\\.gz)?")) {
                response.put("status", "error");
                response.put("message", "Invalid backup name format");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("Restore requested for backup: {}", name);
            RestoreResult result = backupService.restoreBackup(name);

            response.put("success", result.success());
            response.put("message", result.message());
            response.put("durationMs", result.durationMs());

            if (!result.errors().isEmpty()) {
                response.put("errors", result.errors());
            }

            response.put("status", result.success() ? "success" : "error");
            return result.success() ? ResponseEntity.ok(response) : ResponseEntity.internalServerError().body(response);

        } catch (Exception e) {
            log.error("Error restoring backup: {}", name, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Download a backup file.
     *
     * @param name The backup name
     * @return The backup file as a downloadable resource
     */
    @GetMapping("/{name}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String name) {
        try {
            // Validate name format to prevent path traversal
            if (!name.matches("backup-\\d{8}-\\d{6}(\\.tar\\.gz)?")) {
                return ResponseEntity.badRequest().build();
            }

            Path backupFile = backupService.getBackupFile(name);
            if (backupFile == null) {
                return ResponseEntity.notFound().build();
            }

            // If it's a directory, we can't download it directly
            if (Files.isDirectory(backupFile)) {
                // For directory backups, we could zip them on the fly
                // For simplicity, return 400 - client should request compressed backups
                return ResponseEntity.badRequest().build();
            }

            Resource resource = new FileSystemResource(backupFile.toFile());
            String filename = backupFile.getFileName().toString();

            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(Files.size(backupFile)))
                .body(resource);

        } catch (Exception e) {
            log.error("Error downloading backup: {}", name, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                    IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
