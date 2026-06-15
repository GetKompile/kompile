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

package ai.kompile.staging.web;

import ai.kompile.staging.archive.*;
import ai.kompile.staging.catalog.remote.RemoteCatalog;
import ai.kompile.staging.catalog.remote.RemoteCatalogService;
import ai.kompile.modelmanager.registry.ModelRegistry;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.transfer.ArchiveDownloader;
import ai.kompile.staging.update.UpdateInfo;
import ai.kompile.staging.update.UpdateService;
import ai.kompile.staging.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for Kompile archive operations.
 */
@RestController
@RequestMapping("/api/staging/archives")
@CrossOrigin(origins = "*")
public class ArchiveController {

    private static final Logger log = LoggerFactory.getLogger(ArchiveController.class);

    private final RegistryService registryService;
    private final ArchiveExporter archiveExporter;
    private final ArchiveImporter archiveImporter;
    private final ArchiveDownloader archiveDownloader;
    private final RemoteCatalogService remoteCatalogService;
    private final UpdateService updateService;

    @Autowired
    public ArchiveController(RegistryService registryService,
                             ArchiveExporter archiveExporter,
                             ArchiveImporter archiveImporter,
                             ArchiveDownloader archiveDownloader,
                             RemoteCatalogService remoteCatalogService,
                             UpdateService updateService) {
        this.registryService = registryService;
        this.archiveExporter = archiveExporter;
        this.archiveImporter = archiveImporter;
        this.archiveDownloader = archiveDownloader;
        this.remoteCatalogService = remoteCatalogService;
        this.updateService = updateService;
    }

    // ==================== Archive List Endpoints ====================

    /**
     * List all installed archives.
     */
    @GetMapping
    public List<ModelRegistry.ArchiveInstallInfo> listArchives() {
        ModelRegistry registry = registryService.loadRegistry();
        return registry.getInstalledArchives();
    }

    /**
     * Get details of a specific installed archive.
     */
    @GetMapping("/{archiveId}")
    public ResponseEntity<ModelRegistry.ArchiveInstallInfo> getArchive(@PathVariable String archiveId) {
        ModelRegistry registry = registryService.loadRegistry();
        Optional<ModelRegistry.ArchiveInstallInfo> archive = registry.getInstalledArchive(archiveId);
        return archive.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Export Endpoint ====================

    /**
     * Export models to a Kompile archive (.karch).
     */
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportArchive(@RequestBody ArchiveExportRequest request) {
        try {
            // Determine output path
            Path outputPath;
            if (request.getOutputPath() != null && !request.getOutputPath().isBlank()) {
                outputPath = Paths.get(request.getOutputPath());
                if (!request.getOutputPath().endsWith(KompileArchive.EXTENSION)) {
                    outputPath = Paths.get(request.getOutputPath() + KompileArchive.EXTENSION);
                }
            } else {
                // Use archives directory as default output location
                String archiveId = request.getArchiveId() != null ? request.getArchiveId() : "kompile-models";
                String version = request.getVersion() != null ? request.getVersion() : "1.0.0";
                String filename = archiveId + "-" + version + KompileArchive.EXTENSION;
                outputPath = registryService.getArchivesDir().resolve(filename);
            }

            // Build export options
            ArchiveExporter.ExportOptions.ExportOptionsBuilder optionsBuilder =
                    ArchiveExporter.ExportOptions.builder()
                            .archiveId(request.getArchiveId() != null ? request.getArchiveId() : "kompile-models")
                            .version(request.getVersion() != null ? request.getVersion() : "1.0.0")
                            .description(request.getDescription());

            // Add publisher if specified
            if (request.getPublisherName() != null) {
                optionsBuilder.publisher(ArchivePublisher.builder()
                        .name(request.getPublisherName())
                        .url(request.getPublisherUrl())
                        .build());
            }

            // Add compatibility if specified
            if (request.getMinKompileVersion() != null) {
                optionsBuilder.compatibility(ArchiveCompatibility.builder()
                        .minKompileVersion(request.getMinKompileVersion())
                        .build());
            }

            ArchiveExporter.ExportOptions options = optionsBuilder.build();

            // Execute export
            ArchiveExporter.ExportResult result;
            if (request.isExportAll()) {
                result = archiveExporter.exportAll(outputPath, options);
            } else {
                result = archiveExporter.export(request.getModelIds(), outputPath, options);
            }

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "archivePath", result.getArchivePath().toString(),
                        "archiveId", result.getManifest().getArchiveId(),
                        "version", result.getManifest().getContentVersion(),
                        "modelCount", result.getModelCount(),
                        "archiveSize", result.getArchiveSize(),
                        "checksum", result.getArchiveChecksum()
                ));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "success", false,
                        "error", result.getErrorMessage()
                ));
            }
        } catch (Exception e) {
            log.error("Export failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // ==================== Import Endpoint ====================

    /**
     * Import a Kompile archive (.karch).
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importArchive(@RequestBody ArchiveImportRequest request) {
        try {
            Path archivePath = Paths.get(request.getArchivePath());

            ArchiveImporter.ImportOptions options = ArchiveImporter.ImportOptions.builder()
                    .verifyChecksums(request.isVerifyChecksums())
                    .forceOverwrite(request.isForceOverwrite())
                    .skipCompatibilityCheck(request.isSkipCompatibilityCheck())
                    .build();

            ArchiveImporter.ImportResult result = archiveImporter.importArchive(archivePath, options);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "archiveId", result.getManifest().getArchiveId(),
                        "version", result.getManifest().getContentVersion(),
                        "importedCount", result.getImportedCount(),
                        "skippedCount", result.getSkippedCount(),
                        "importedModels", result.getImportedModels(),
                        "skippedModels", result.getSkippedModels()
                ));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "success", false,
                        "error", result.getErrorMessage()
                ));
            }
        } catch (Exception e) {
            log.error("Import failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // ==================== Download Endpoint ====================

    /**
     * Download a Kompile archive from a URL.
     */
    @PostMapping("/download")
    public ResponseEntity<Map<String, Object>> downloadArchive(@RequestBody ArchiveDownloadRequest request) {
        try {
            ArchiveDownloader.DownloadOptions.DownloadOptionsBuilder optionsBuilder =
                    ArchiveDownloader.DownloadOptions.builder()
                            .allowResume(request.isResumeEnabled())
                            .verifyChecksum(request.isVerifyChecksum())
                            .expectedChecksum(request.getExpectedChecksum());

            if (request.getDestinationDir() != null) {
                optionsBuilder.destinationPath(Paths.get(request.getDestinationDir()));
            }

            ArchiveDownloader.DownloadOptions options = optionsBuilder.build();

            ArchiveDownloader.DownloadResult result;
            if (request.isAutoImport()) {
                ArchiveImporter.ImportOptions importOptions = ArchiveImporter.ImportOptions.builder()
                        .forceOverwrite(request.isForceOverwrite())
                        .build();
                result = archiveDownloader.downloadAndImport(
                        request.getUrl(), options, importOptions, progress -> {});
            } else {
                result = archiveDownloader.download(request.getUrl(), options);
            }

            if (result.isSuccess()) {
                Map<String, Object> response = new java.util.HashMap<>(Map.of(
                        "success", true,
                        "archivePath", result.getArchivePath().toString(),
                        "totalBytes", result.getBytesDownloaded(),
                        "checksum", result.getChecksum() != null ? result.getChecksum() : ""
                ));

                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> errorResponse = new java.util.HashMap<>(Map.of(
                        "success", false,
                        "error", result.getErrorMessage()
                ));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
            }
        } catch (Exception e) {
            log.error("Download failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // ==================== Remote Catalog Endpoints ====================

    /**
     * Get the remote catalog of available archives.
     */
    @GetMapping("/catalog/remote")
    public RemoteCatalog getRemoteCatalog(@RequestParam(defaultValue = "false") boolean refresh) {
        return remoteCatalogService.getCatalog(refresh);
    }

    /**
     * Get cache status for remote catalogs.
     */
    @GetMapping("/catalog/cache")
    public Map<String, RemoteCatalogService.CacheStatus> getCatalogCacheStatus() {
        return remoteCatalogService.getCacheStatus();
    }

    /**
     * Clear the catalog cache.
     */
    @DeleteMapping("/catalog/cache")
    public ResponseEntity<Map<String, Object>> clearCatalogCache() {
        remoteCatalogService.clearCache();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Catalog cache cleared"
        ));
    }

    // ==================== Update Endpoints ====================

    /**
     * Check for updates for all installed archives.
     */
    @GetMapping("/updates")
    public ArchiveUpdateResponse checkForUpdates(@RequestParam(defaultValue = "false") boolean refresh) {
        UpdateService.UpdateSummary summary = updateService.getUpdateSummary();
        return ArchiveUpdateResponse.fromSummary(
                summary.getTotalInstalled(),
                summary.getUpdatesAvailable(),
                summary.getMajorUpdates(),
                summary.getUpdates()
        );
    }

    /**
     * Check for update for a specific archive.
     */
    @GetMapping("/updates/{archiveId}")
    public ResponseEntity<UpdateInfo> checkForUpdate(@PathVariable String archiveId) {
        UpdateInfo updateInfo = updateService.checkForUpdate(archiveId);
        if (updateInfo.getArchiveId() != null) {
            return ResponseEntity.ok(updateInfo);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Apply update for a specific archive.
     */
    @PostMapping("/updates/{archiveId}/apply")
    public ResponseEntity<Map<String, Object>> applyUpdate(@PathVariable String archiveId) {
        try {
            UpdateService.UpdateResult result = updateService.applyUpdate(archiveId);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "archiveId", result.getArchiveId(),
                        "previousVersion", result.getPreviousVersion() != null ? result.getPreviousVersion() : "",
                        "newVersion", result.getNewVersion() != null ? result.getNewVersion() : "",
                        "modelsUpdated", result.getModelsUpdated()
                ));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "success", false,
                        "error", result.getErrorMessage()
                ));
            }
        } catch (Exception e) {
            log.error("Update failed for archive: {}", archiveId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Apply all available updates.
     */
    @PostMapping("/updates/apply-all")
    public ResponseEntity<Map<String, Object>> applyAllUpdates() {
        try {
            List<UpdateService.UpdateResult> results = updateService.applyAllUpdates();

            int successCount = (int) results.stream().filter(UpdateService.UpdateResult::isSuccess).count();
            int failCount = results.size() - successCount;

            List<Map<String, Object>> resultDetails = results.stream()
                    .map(r -> Map.<String, Object>of(
                            "archiveId", r.getArchiveId(),
                            "success", r.isSuccess(),
                            "previousVersion", r.getPreviousVersion() != null ? r.getPreviousVersion() : "",
                            "newVersion", r.getNewVersion() != null ? r.getNewVersion() : "",
                            "error", r.getErrorMessage() != null ? r.getErrorMessage() : ""
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success", failCount == 0,
                    "successCount", successCount,
                    "failCount", failCount,
                    "results", resultDetails
            ));
        } catch (Exception e) {
            log.error("Batch update failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // ==================== Uninstall Endpoint ====================

    /**
     * Uninstall an archive (remove from registry, optionally delete models).
     */
    @DeleteMapping("/{archiveId}")
    public ResponseEntity<Map<String, Object>> uninstallArchive(
            @PathVariable String archiveId,
            @RequestParam(defaultValue = "false") boolean deleteModels) {
        try {
            ModelRegistry registry = registryService.loadRegistry();
            Optional<ModelRegistry.ArchiveInstallInfo> archiveOpt = registry.getInstalledArchive(archiveId);

            if (archiveOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            ModelRegistry.ArchiveInstallInfo archive = archiveOpt.get();
            List<String> modelIds = archive.getModelIds();

            // Remove from registry
            registry.removeInstalledArchive(archiveId);
            registryService.saveRegistry(registry);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "archiveId", archiveId,
                    "modelsRemoved", deleteModels ? modelIds.size() : 0,
                    "message", deleteModels
                            ? "Archive and models removed"
                            : "Archive removed from registry (models retained)"
            ));
        } catch (Exception e) {
            log.error("Uninstall failed for archive: {}", archiveId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }
}
