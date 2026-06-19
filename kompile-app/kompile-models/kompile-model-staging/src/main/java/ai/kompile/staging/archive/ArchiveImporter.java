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

package ai.kompile.staging.archive;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Service for importing Kompile Archive (.karch) files.
 * Handles extraction, verification, and registration of models.
 */
@Service
public class ArchiveImporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiveImporter.class);
    private static final int BUFFER_SIZE = 8192;

    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ArchiveImporter(RegistryService registryService) {
        this.registryService = registryService;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Import a .karch archive.
     *
     * @param archivePath Path to the .karch archive file
     * @param options Import options
     * @return Result of the import operation
     */
    public ImportResult importArchive(Path archivePath, ImportOptions options) {
        return importArchive(archivePath, options, progress -> {});
    }

    /**
     * Import a .karch archive with progress callback.
     *
     * @param archivePath Path to the .karch archive file
     * @param options Import options
     * @param progressCallback Callback for progress updates
     * @return Result of the import operation
     */
    public ImportResult importArchive(Path archivePath, ImportOptions options,
                                       Consumer<ImportProgress> progressCallback) {
        log.info("Importing archive: {}", archivePath);

        if (!Files.exists(archivePath)) {
            return ImportResult.failure("Archive file not found: " + archivePath);
        }

        if (!KompileArchive.isKarchFile(archivePath)) {
            log.warn("File does not have .karch extension: {}", archivePath);
        }

        Path tempDir = null;
        try {
            progressCallback.accept(ImportProgress.starting("Extracting archive"));

            // Create temp directory for extraction
            tempDir = Files.createTempDirectory("kompile-import-");
            Map<String, String> extractedChecksums = new HashMap<>();

            // Extract archive
            ArchiveManifest manifest = extractArchive(archivePath, tempDir, extractedChecksums,
                    progressCallback);

            if (manifest == null) {
                return ImportResult.failure("Invalid archive: " + KompileArchive.MANIFEST_FILENAME + " not found");
            }

            log.info("Archive contains {} models (version {})",
                    manifest.getModelCount(), manifest.getContentVersion());

            progressCallback.accept(ImportProgress.verifying("Checking compatibility"));

            // Check compatibility
            if (!options.isSkipCompatibilityCheck() && manifest.getCompatibility() != null) {
                String kompileVersion = getKompileVersion();
                if (!manifest.isCompatible(kompileVersion)) {
                    return ImportResult.failure("Archive not compatible with Kompile " + kompileVersion +
                            ". Required: " + manifest.getCompatibility().toDisplayString());
                }
            }

            // Verify checksums if requested
            if (options.isVerifyChecksums()) {
                progressCallback.accept(ImportProgress.verifying("Verifying checksums"));
                if (!manifest.verifyChecksums(extractedChecksums)) {
                    return ImportResult.failure("Checksum verification failed");
                }
                log.info("Checksum verification passed");
            }

            // Check for existing models if not forcing overwrite
            if (!options.isForceOverwrite()) {
                List<String> existingModels = new ArrayList<>();
                for (ArchiveModelEntry entry : manifest.getModels()) {
                    if (registryService.hasModel(entry.getModelId())) {
                        existingModels.add(entry.getModelId());
                    }
                }
                if (!existingModels.isEmpty()) {
                    log.warn("Models already exist: {}. Use force option to overwrite.", existingModels);
                    if (options.isSkipExisting()) {
                        log.info("Skipping existing models");
                    } else {
                        return ImportResult.failure("Models already exist: " + existingModels +
                                ". Use --force to overwrite or --skip-existing to skip.");
                    }
                }
            }

            // Import each model
            List<String> importedModels = new ArrayList<>();
            List<String> skippedModels = new ArrayList<>();
            List<String> failedModels = new ArrayList<>();

            int total = manifest.getModels().size();
            int processed = 0;

            for (ArchiveModelEntry archiveEntry : manifest.getModels()) {
                String modelId = archiveEntry.getModelId();
                processed++;

                progressCallback.accept(ImportProgress.importing(modelId, processed, total));

                // Skip if exists and not forcing
                if (!options.isForceOverwrite() && registryService.hasModel(modelId)) {
                    if (options.isSkipExisting()) {
                        skippedModels.add(modelId);
                        continue;
                    }
                }

                try {
                    importModel(archiveEntry, tempDir, manifest);
                    importedModels.add(modelId);
                    log.info("Imported model: {}", modelId);
                } catch (Exception e) {
                    log.error("Failed to import model: {}", modelId, e);
                    failedModels.add(modelId);
                }
            }

            // Register the archive installation
            registerArchiveInstallation(manifest);

            progressCallback.accept(ImportProgress.completed(importedModels.size(), skippedModels.size()));

            return ImportResult.success(
                    archivePath,
                    manifest,
                    importedModels,
                    skippedModels,
                    failedModels
            );

        } catch (Exception e) {
            log.error("Import failed", e);
            progressCallback.accept(ImportProgress.failed(e.getMessage()));
            return ImportResult.failure("Import failed: " + e.getMessage());
        } finally {
            // Cleanup temp directory
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException e) {
                    log.warn("Failed to cleanup temp directory", e);
                }
            }
        }
    }

    /**
     * Preview an archive without importing.
     *
     * @param archivePath Path to the .karch archive file
     * @return The archive with manifest, or null if invalid
     */
    public KompileArchive previewArchive(Path archivePath) {
        try {
            ArchiveManifest manifest = readManifest(archivePath);
            if (manifest == null) {
                return null;
            }

            long fileSize = Files.size(archivePath);
            String checksum = calculateSha256(archivePath);

            return KompileArchive.builder()
                    .archivePath(archivePath)
                    .manifest(manifest)
                    .fileSizeBytes(fileSize)
                    .archiveChecksum(checksum)
                    .build();

        } catch (Exception e) {
            log.error("Failed to preview archive", e);
            return null;
        }
    }

    /**
     * Read only the manifest from an archive.
     */
    public ArchiveManifest readManifest(Path archivePath) {
        try (InputStream fi = new BufferedInputStream(Files.newInputStream(archivePath));
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(fi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.getName().equals(KompileArchive.MANIFEST_FILENAME)) {
                    return objectMapper.readValue(tar, ArchiveManifest.class);
                }
            }
        } catch (Exception e) {
            log.error("Failed to read manifest from archive", e);
        }
        return null;
    }

    private ArchiveManifest extractArchive(Path archivePath, Path destination,
                                            Map<String, String> checksums,
                                            Consumer<ImportProgress> progressCallback) throws IOException {
        ArchiveManifest manifest = null;
        int filesExtracted = 0;

        try (InputStream fi = new BufferedInputStream(Files.newInputStream(archivePath));
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(fi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                Path targetPath = destination.resolve(name).normalize();

                // Security check - prevent path traversal
                if (!targetPath.startsWith(destination)) {
                    throw new IOException("Tar entry outside destination: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else if (name.equals(KompileArchive.MANIFEST_FILENAME)) {
                    manifest = objectMapper.readValue(tar, ArchiveManifest.class);
                } else if (name.equals(KompileArchive.CHECKSUMS_FILENAME)) {
                    // Skip checksums file - we calculate our own
                    skipEntry(tar, entry);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    String checksum = extractFileWithChecksum(tar, targetPath);
                    checksums.put(name, checksum);
                    filesExtracted++;

                    if (filesExtracted % 10 == 0) {
                        progressCallback.accept(ImportProgress.extracting(filesExtracted));
                    }
                }
            }
        }

        return manifest;
    }

    private void skipEntry(TarArchiveInputStream tar, TarArchiveEntry entry) throws IOException {
        long remaining = entry.getSize();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = tar.read(buffer, 0, toRead);
            if (read <= 0) break;
            remaining -= read;
        }
    }

    private String extractFileWithChecksum(TarArchiveInputStream tar, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = tar.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return formatChecksum(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private void importModel(ArchiveModelEntry archiveEntry, Path tempDir,
                             ArchiveManifest manifest) throws IOException {
        // Source path in extracted archive (under models/)
        Path sourcePath = tempDir.resolve("models").resolve(archiveEntry.getPath());

        // If not found under models/, try direct path
        if (!Files.exists(sourcePath)) {
            sourcePath = tempDir.resolve(archiveEntry.getPath());
        }

        if (!Files.exists(sourcePath)) {
            throw new IOException("Model directory not found in archive: " + archiveEntry.getPath());
        }

        // Target path in model registry
        Path targetPath = registryService.getModelDir().resolve(archiveEntry.getPath());

        // Create target directory
        Files.createDirectories(targetPath.getParent());

        // Remove existing if present
        if (Files.exists(targetPath)) {
            deleteDirectory(targetPath);
        }

        // Move or copy files
        try {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // If move fails (cross-filesystem), copy
            copyDirectory(sourcePath, targetPath);
        }

        // Convert to registry entry and add to registry
        ModelEntry registryEntry = archiveEntry.toModelEntry();
        registryService.addModel(registryEntry);
    }

    private void registerArchiveInstallation(ArchiveManifest manifest) {
        // This will be enhanced when we add archive tracking to the registry
        log.info("Registered archive installation: {} v{}",
                manifest.getArchiveId(), manifest.getContentVersion());
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path targetPath = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
    }

    private String calculateSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return formatChecksum(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private String formatChecksum(byte[] hashBytes) {
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String getKompileVersion() {
        Package pkg = getClass().getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "1.0.0-SNAPSHOT";
    }

    /**
     * Options for archive import.
     */
    @Data
    @Builder
    public static class ImportOptions {
        /**
         * Verify checksums during import.
         */
        @Builder.Default
        private boolean verifyChecksums = true;

        /**
         * Force overwrite existing models.
         */
        @Builder.Default
        private boolean forceOverwrite = false;

        /**
         * Skip existing models instead of failing.
         */
        @Builder.Default
        private boolean skipExisting = false;

        /**
         * Skip compatibility check.
         */
        @Builder.Default
        private boolean skipCompatibilityCheck = false;

        /**
         * Default import options.
         */
        public static ImportOptions defaults() {
            return ImportOptions.builder().build();
        }

        /**
         * Import options for forced overwrite.
         */
        public static ImportOptions forceOverwrite() {
            return ImportOptions.builder()
                    .forceOverwrite(true)
                    .build();
        }

        /**
         * Import options that skip existing models.
         */
        public static ImportOptions skipExisting() {
            return ImportOptions.builder()
                    .skipExisting(true)
                    .build();
        }
    }

    /**
     * Result of an import operation.
     */
    @Data
    @Builder
    public static class ImportResult {
        private final boolean success;
        private final String errorMessage;
        private final Path archivePath;
        private final ArchiveManifest manifest;
        private final List<String> importedModels;
        private final List<String> skippedModels;
        private final List<String> failedModels;

        public static ImportResult success(Path path, ArchiveManifest manifest,
                                           List<String> imported, List<String> skipped,
                                           List<String> failed) {
            return ImportResult.builder()
                    .success(failed.isEmpty())
                    .archivePath(path)
                    .manifest(manifest)
                    .importedModels(imported)
                    .skippedModels(skipped)
                    .failedModels(failed)
                    .build();
        }

        public static ImportResult failure(String error) {
            return ImportResult.builder()
                    .success(false)
                    .errorMessage(error)
                    .importedModels(List.of())
                    .skippedModels(List.of())
                    .failedModels(List.of())
                    .build();
        }

        public int getImportedCount() {
            return importedModels != null ? importedModels.size() : 0;
        }

        public int getSkippedCount() {
            return skippedModels != null ? skippedModels.size() : 0;
        }

        public int getFailedCount() {
            return failedModels != null ? failedModels.size() : 0;
        }

        public int getTotalCount() {
            return manifest != null ? manifest.getModelCount() : 0;
        }
    }

    /**
     * Progress update during import.
     */
    @Data
    @Builder
    public static class ImportProgress {
        public enum Phase { STARTING, EXTRACTING, VERIFYING, IMPORTING, COMPLETED, FAILED }

        private final Phase phase;
        private final String currentItem;
        private final int processedCount;
        private final int totalCount;
        private final String message;

        public static ImportProgress starting(String message) {
            return ImportProgress.builder()
                    .phase(Phase.STARTING)
                    .message(message)
                    .build();
        }

        public static ImportProgress extracting(int filesExtracted) {
            return ImportProgress.builder()
                    .phase(Phase.EXTRACTING)
                    .processedCount(filesExtracted)
                    .message("Extracted " + filesExtracted + " files")
                    .build();
        }

        public static ImportProgress verifying(String message) {
            return ImportProgress.builder()
                    .phase(Phase.VERIFYING)
                    .message(message)
                    .build();
        }

        public static ImportProgress importing(String modelId, int processed, int total) {
            return ImportProgress.builder()
                    .phase(Phase.IMPORTING)
                    .currentItem(modelId)
                    .processedCount(processed)
                    .totalCount(total)
                    .message("Importing: " + modelId)
                    .build();
        }

        public static ImportProgress completed(int imported, int skipped) {
            return ImportProgress.builder()
                    .phase(Phase.COMPLETED)
                    .processedCount(imported)
                    .message("Import completed: " + imported + " imported, " + skipped + " skipped")
                    .build();
        }

        public static ImportProgress failed(String error) {
            return ImportProgress.builder()
                    .phase(Phase.FAILED)
                    .message("Import failed: " + error)
                    .build();
        }

        public int getProgressPercent() {
            if (phase == Phase.COMPLETED) return 100;
            if (phase == Phase.FAILED) return 0;
            if (totalCount == 0) return 0;
            return (int) ((processedCount * 100.0) / totalCount);
        }
    }
}
