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

import ai.kompile.cli.common.config.ConfigArchiveManifest;
import ai.kompile.cli.common.config.ConfigArchiveService;
import ai.kompile.cli.common.config.ArchiveInfo;
import ai.kompile.cli.common.config.ConfigArchiveService.ImportResult;
import ai.kompile.cli.common.config.ImportMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for configuration archive management.
 * Provides endpoints for exporting, importing, listing, and deleting config archives.
 */
@RestController
@RequestMapping("/api/config-archives")
@Slf4j
public class ConfigArchiveController {

    /**
     * List all saved configuration archives.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listArchives() {
        try {
            List<ArchiveInfo> archives = ConfigArchiveService.listArchives();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("archives", archives);
            response.put("total", archives.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to list config archives", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list archives: " + e.getMessage()));
        }
    }

    /**
     * Export current configuration as a downloadable zip archive.
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportArchive(
            @RequestParam(required = false) String description) {
        try {
            byte[] archiveBytes = ConfigArchiveService.exportArchiveToBytes(description);
            String filename = "kompile-config-" +
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.now()) + ".zip";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(archiveBytes.length)
                    .body(archiveBytes);
        } catch (Exception e) {
            log.error("Failed to export config archive", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Save an export to the server's archive directory (for later retrieval).
     */
    @PostMapping("/export/save")
    public ResponseEntity<Map<String, Object>> exportAndSave(
            @RequestParam(required = false) String description) {
        try {
            Path archivePath = ConfigArchiveService.exportArchive(null, description);
            ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Archive saved successfully");
            response.put("fileName", archivePath.getFileName().toString());
            response.put("path", archivePath.toString());
            response.put("manifest", manifest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to save config archive", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to save archive: " + e.getMessage()));
        }
    }

    /**
     * Preview the manifest of an uploaded archive without importing.
     */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> previewArchive(
            @RequestParam("file") MultipartFile file) {
        try {
            byte[] archiveBytes = file.getBytes();
            ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archiveBytes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("manifest", manifest);
            response.put("fileSize", file.getSize());
            response.put("fileName", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to preview config archive", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to read archive: " + e.getMessage()));
        }
    }

    /**
     * Import an uploaded configuration archive.
     *
     * @param file the zip archive to import
     * @param mode "append" (merge with existing) or "override" (replace existing)
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importArchive(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "append") String mode) {
        try {
            ImportMode importMode;
            try {
                importMode = ImportMode.valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid mode: " + mode + ". Use 'append' or 'override'."));
            }

            byte[] archiveBytes = file.getBytes();
            ImportResult result = ConfigArchiveService.importArchive(archiveBytes, importMode);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Import completed successfully");
            response.put("mode", mode.toLowerCase());
            response.put("created", result.getCreated());
            response.put("overwritten", result.getOverwritten());
            response.put("merged", result.getMerged());
            response.put("skipped", result.getSkipped());
            response.put("totalProcessed", result.totalProcessed());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to import config archive", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to import archive: " + e.getMessage()));
        }
    }

    /**
     * Import a previously saved archive from the server's archive directory.
     */
    @PostMapping("/import/{fileName}")
    public ResponseEntity<Map<String, Object>> importSavedArchive(
            @PathVariable String fileName,
            @RequestParam(defaultValue = "append") String mode) {
        try {
            ImportMode importMode;
            try {
                importMode = ImportMode.valueOf(mode.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid mode: " + mode + ". Use 'append' or 'override'."));
            }

            // Validate filename to prevent path traversal
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file name"));
            }

            Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("archives").resolve(fileName);
            if (!java.nio.file.Files.isRegularFile(archivePath)) {
                return ResponseEntity.notFound().build();
            }

            ImportResult result = ConfigArchiveService.importArchive(archivePath, importMode);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Import completed successfully");
            response.put("mode", mode.toLowerCase());
            response.put("fileName", fileName);
            response.put("created", result.getCreated());
            response.put("overwritten", result.getOverwritten());
            response.put("merged", result.getMerged());
            response.put("skipped", result.getSkipped());
            response.put("totalProcessed", result.totalProcessed());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to import saved archive", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to import archive: " + e.getMessage()));
        }
    }

    /**
     * Get the manifest of a saved archive.
     */
    @GetMapping("/{fileName}/manifest")
    public ResponseEntity<Map<String, Object>> getManifest(@PathVariable String fileName) {
        try {
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file name"));
            }

            Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("archives").resolve(fileName);
            if (!java.nio.file.Files.isRegularFile(archivePath)) {
                return ResponseEntity.notFound().build();
            }

            ConfigArchiveManifest manifest = ConfigArchiveService.readManifest(archivePath);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("fileName", fileName);
            response.put("manifest", manifest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read archive manifest", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to read manifest: " + e.getMessage()));
        }
    }

    /**
     * Download a saved archive.
     */
    @GetMapping("/{fileName}/download")
    public ResponseEntity<byte[]> downloadArchive(@PathVariable String fileName) {
        try {
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return ResponseEntity.badRequest().build();
            }

            Path archivePath = ai.kompile.cli.common.KompileHome.homeDirectory()
                    .toPath().resolve("archives").resolve(fileName);
            if (!java.nio.file.Files.isRegularFile(archivePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] data = java.nio.file.Files.readAllBytes(archivePath);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(data.length)
                    .body(data);
        } catch (Exception e) {
            log.error("Failed to download archive", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete a saved archive.
     */
    @DeleteMapping("/{fileName}")
    public ResponseEntity<Map<String, Object>> deleteArchive(@PathVariable String fileName) {
        try {
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file name"));
            }

            boolean deleted = ConfigArchiveService.deleteArchive(fileName);
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "Archive deleted", "fileName", fileName));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to delete archive", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to delete archive: " + e.getMessage()));
        }
    }
}
