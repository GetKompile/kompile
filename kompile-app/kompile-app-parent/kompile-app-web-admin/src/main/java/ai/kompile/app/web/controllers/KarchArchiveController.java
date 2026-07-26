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

import ai.kompile.app.web.dto.karch.*;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * REST Controller for managing Kompile Archives (.karch).
 * Provides endpoints for loading, listing, and querying archives.
 */
@RestController
@RequestMapping("/api/archives")
@CrossOrigin(origins = "*")
public class KarchArchiveController {

    private static final Logger log = LoggerFactory.getLogger(KarchArchiveController.class);
    private static final String MANIFEST_FILE = "manifest.karch.json";
    private static final Path DEFAULT_ARCHIVES_DIR = Paths.get(
            System.getProperty("user.home"), ".kompile", "archives");

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @Value("${kompile.archive.path:}")
    private String configuredArchivePath;

    @Value("${kompile.archive.auto-load:true}")
    private boolean autoLoadArchive;

    // Current loaded archive state
    private ArchiveManifest loadedManifest;
    private Path loadedArchivePath;
    private Instant loadedAt;

    /**
     * Get list of available archives in the archives directory.
     */
    @GetMapping
    public ResponseEntity<List<ArchiveInfo>> listArchives() {
        try {
            List<ArchiveInfo> archives = new ArrayList<>();

            // Check default archives directory
            if (Files.exists(DEFAULT_ARCHIVES_DIR)) {
                try (var stream = Files.list(DEFAULT_ARCHIVES_DIR)) {
                    stream.filter(p -> p.toString().endsWith(".karch"))
                          .forEach(p -> {
                              try {
                                  ArchiveManifest manifest = readManifest(p);
                                  archives.add(new ArchiveInfo(
                                          p.getFileName().toString(),
                                          p.toString(),
                                          manifest != null ? manifest.archiveId : null,
                                          manifest != null ? manifest.contentVersion : null,
                                          manifest != null ? manifest.description : null,
                                          manifest != null ? manifest.models.size() : 0,
                                          Files.size(p),
                                          Files.getLastModifiedTime(p).toInstant().toString(),
                                          isLoaded(p)
                                  ));
                              } catch (Exception e) {
                                  log.warn("Failed to read archive info: {}", p, e);
                              }
                          });
                }
            }

            // Check configured archive path
            if (configuredArchivePath != null && !configuredArchivePath.isEmpty()) {
                Path configPath = Paths.get(configuredArchivePath);
                if (Files.exists(configPath) && !containsPath(archives, configPath)) {
                    try {
                        ArchiveManifest manifest = readManifest(configPath);
                        archives.add(new ArchiveInfo(
                                configPath.getFileName().toString(),
                                configPath.toString(),
                                manifest != null ? manifest.archiveId : null,
                                manifest != null ? manifest.contentVersion : null,
                                manifest != null ? manifest.description : null,
                                manifest != null ? manifest.models.size() : 0,
                                Files.size(configPath),
                                Files.getLastModifiedTime(configPath).toInstant().toString(),
                                isLoaded(configPath)
                        ));
                    } catch (Exception e) {
                        log.warn("Failed to read configured archive: {}", configPath, e);
                    }
                }
            }

            return ResponseEntity.ok(archives);
        } catch (Exception e) {
            log.error("Failed to list archives", e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Get currently loaded archive status.
     */
    @GetMapping("/status")
    public ResponseEntity<ArchiveStatus> getStatus() {
        ArchiveStatus status = new ArchiveStatus();
        status.loaded = loadedManifest != null;
        status.archivePath = loadedArchivePath != null ? loadedArchivePath.toString() : null;
        status.loadedAt = loadedAt != null ? loadedAt.toString() : null;

        if (loadedManifest != null) {
            status.archiveId = loadedManifest.archiveId;
            status.contentVersion = loadedManifest.contentVersion;
            status.description = loadedManifest.description;
            status.modelCount = loadedManifest.models.size();
            status.encoderCount = (int) loadedManifest.models.stream()
                    .filter(m -> "encoder".equalsIgnoreCase(m.type)).count();
            status.crossEncoderCount = (int) loadedManifest.models.stream()
                    .filter(m -> "cross_encoder".equalsIgnoreCase(m.type)).count();
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Get models from currently loaded archive.
     */
    @GetMapping("/models")
    public ResponseEntity<List<ArchiveModelInfo>> getModels() {
        if (loadedManifest == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<ArchiveModelInfo> models = loadedManifest.models.stream()
                .map(m -> new ArchiveModelInfo(
                        m.modelId,
                        m.type,
                        m.path,
                        m.embeddingDim,
                        m.maxSequenceLength,
                        m.description
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(models);
    }

    /**
     * Get models by type from currently loaded archive.
     */
    @GetMapping("/models/{type}")
    public ResponseEntity<List<ArchiveModelInfo>> getModelsByType(@PathVariable String type) {
        if (loadedManifest == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<ArchiveModelInfo> models = loadedManifest.models.stream()
                .filter(m -> type.equalsIgnoreCase(m.type))
                .map(m -> new ArchiveModelInfo(
                        m.modelId,
                        m.type,
                        m.path,
                        m.embeddingDim,
                        m.maxSequenceLength,
                        m.description
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(models);
    }

    /**
     * Load an archive from a file path.
     */
    @PostMapping("/load")
    public ResponseEntity<ArchiveStatus> loadArchive(@RequestBody LoadArchiveRequest request) {
        try {
            Path archivePath = Paths.get(request.archivePath);
            if (!Files.exists(archivePath)) {
                return ResponseEntity.badRequest().build();
            }

            ArchiveManifest manifest = readManifest(archivePath);
            if (manifest == null) {
                log.error("Failed to read manifest from archive: {}", archivePath);
                return ResponseEntity.badRequest().build();
            }

            // Store loaded state
            this.loadedManifest = manifest;
            this.loadedArchivePath = archivePath;
            this.loadedAt = Instant.now();

            log.info("Loaded archive: {} ({})", manifest.archiveId, manifest.contentVersion);

            return getStatus();
        } catch (Exception e) {
            log.error("Failed to load archive: {}", request.archivePath, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Unload the currently loaded archive.
     */
    @PostMapping("/unload")
    public ResponseEntity<ArchiveStatus> unloadArchive() {
        loadedManifest = null;
        loadedArchivePath = null;
        loadedAt = null;
        log.info("Unloaded archive");
        return getStatus();
    }

    /**
     * Extract a model from the loaded archive to a destination directory.
     */
    @PostMapping("/extract")
    public ResponseEntity<ExtractResult> extractModel(@RequestBody ExtractModelRequest request) {
        if (loadedManifest == null || loadedArchivePath == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            // Find the model in manifest
            ArchiveModelEntry model = loadedManifest.models.stream()
                    .filter(m -> m.modelId.equals(request.modelId))
                    .findFirst()
                    .orElse(null);

            if (model == null) {
                return ResponseEntity.notFound().build();
            }

            // Determine destination
            Path destDir = request.destinationPath != null
                    ? Paths.get(request.destinationPath)
                    : Paths.get(System.getProperty("user.home"), ".kompile", "models", request.modelId);

            Files.createDirectories(destDir);

            // Extract model files from archive
            String modelPrefix = "models/" + model.path;
            int extractedCount = extractFromArchive(loadedArchivePath, modelPrefix, destDir);

            ExtractResult result = new ExtractResult();
            result.success = true;
            result.modelId = request.modelId;
            result.destinationPath = destDir.toString();
            result.filesExtracted = extractedCount;

            log.info("Extracted model {} to {} ({} files)", request.modelId, destDir, extractedCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to extract model: {}", request.modelId, e);
            ExtractResult result = new ExtractResult();
            result.success = false;
            result.error = e.getMessage();
            return ResponseEntity.ok(result);
        }
    }

    // ==================== Helper Methods ====================

    private boolean isLoaded(Path path) {
        return loadedArchivePath != null && loadedArchivePath.equals(path);
    }

    private boolean containsPath(List<ArchiveInfo> archives, Path path) {
        return archives.stream().anyMatch(a -> a.path.equals(path.toString()));
    }

    private ArchiveManifest readManifest(Path archivePath) {
        try (InputStream fis = Files.newInputStream(archivePath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {

            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.getName().equals(MANIFEST_FILE) || entry.getName().endsWith("/" + MANIFEST_FILE)) {
                    byte[] content = tis.readAllBytes();
                    return objectMapper.readValue(content, ArchiveManifest.class);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read manifest from {}: {}", archivePath, e.getMessage());
        }
        return null;
    }

    private int extractFromArchive(Path archivePath, String prefix, Path destDir) throws IOException {
        int count = 0;
        try (InputStream fis = Files.newInputStream(archivePath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarArchiveInputStream tis = new TarArchiveInputStream(gis)) {

            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.getName().startsWith(prefix) && !entry.isDirectory()) {
                    // Get relative path from prefix
                    String relativePath = entry.getName().substring(prefix.length());
                    if (relativePath.startsWith("/")) {
                        relativePath = relativePath.substring(1);
                    }
                    Path destFile = destDir.resolve(relativePath);
                    Files.createDirectories(destFile.getParent());
                    Files.copy(tis, destFile, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
        }
        return count;
    }

}
