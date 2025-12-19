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

import ai.kompile.staging.registry.ModelEntry;
import ai.kompile.staging.registry.RegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * Service for exporting models to Kompile Archive (.karch) format.
 * Creates versioned, checksummed archives for distribution and air-gap transfer.
 */
@Service
public class ArchiveExporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiveExporter.class);
    private static final int BUFFER_SIZE = 8192;

    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ArchiveExporter(RegistryService registryService) {
        this.registryService = registryService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Export specified models to a .karch archive.
     *
     * @param modelIds List of model IDs to export
     * @param outputPath Path for the output .karch file
     * @param options Export options including version and metadata
     * @return Result of the export operation
     */
    public ExportResult export(List<String> modelIds, Path outputPath, ExportOptions options) {
        return export(modelIds, outputPath, options, progress -> {});
    }

    /**
     * Export specified models to a .karch archive with progress callback.
     *
     * @param modelIds List of model IDs to export
     * @param outputPath Path for the output .karch file
     * @param options Export options
     * @param progressCallback Callback for progress updates
     * @return Result of the export operation
     */
    public ExportResult export(List<String> modelIds, Path outputPath, ExportOptions options,
                               Consumer<ExportProgress> progressCallback) {
        log.info("Exporting {} models to {}", modelIds.size(), outputPath);

        // Ensure .karch extension
        if (!KompileArchive.isKarchFile(outputPath)) {
            outputPath = outputPath.resolveSibling(outputPath.getFileName() + KompileArchive.EXTENSION);
        }

        ArchiveManifest manifest = createManifest(options);
        long totalBytes = 0;
        int processedModels = 0;

        try {
            // Ensure output path has a parent directory
            Path parentDir = outputPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            progressCallback.accept(ExportProgress.starting(modelIds.size()));

            try (OutputStream fo = new BufferedOutputStream(Files.newOutputStream(outputPath));
                 GzipCompressorOutputStream gzo = new GzipCompressorOutputStream(fo);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(gzo)) {

                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

                // Export each model
                for (String modelId : modelIds) {
                    Optional<ModelEntry> entryOpt = registryService.getModel(modelId);
                    if (entryOpt.isEmpty()) {
                        log.warn("Model not found: {}", modelId);
                        continue;
                    }

                    ModelEntry entry = entryOpt.get();
                    Path modelDir = registryService.getModelDir().resolve(entry.getPath());

                    if (!Files.exists(modelDir)) {
                        log.warn("Model directory not found: {}", modelDir);
                        continue;
                    }

                    progressCallback.accept(ExportProgress.processing(modelId, processedModels, modelIds.size()));

                    // Add model files to archive under models/ prefix
                    String archivePath = "models/" + entry.getPath();
                    long modelBytes = addDirectoryToTar(tar, modelDir, archivePath, manifest);
                    totalBytes += modelBytes;

                    // Create archive model entry
                    ArchiveModelEntry archiveEntry = ArchiveModelEntry.fromModelEntry(entry, options.getModelVersion());
                    archiveEntry.setSizeBytes(modelBytes);
                    manifest.addModel(archiveEntry);

                    processedModels++;
                    log.info("Added model: {} ({} bytes)", modelId, modelBytes);
                }

                // Add README if provided
                if (options.getReadme() != null) {
                    addTextFile(tar, "README.md", options.getReadme(), manifest);
                }

                // Add CHANGELOG if provided
                if (options.getChangelog() != null) {
                    addTextFile(tar, "CHANGELOG.md", options.getChangelog(), manifest);
                    manifest.setChangelog(options.getChangelog());
                }

                // Add checksums file
                addChecksumsFile(tar, manifest);

                // Add manifest
                manifest.setTotalSizeBytes(totalBytes);
                progressCallback.accept(ExportProgress.writingManifest());
                addManifest(tar, manifest);

                tar.finish();
            }

            // Calculate archive checksum
            String archiveChecksum = calculateSha256(outputPath);
            long fileSize = Files.size(outputPath);

            progressCallback.accept(ExportProgress.completed(manifest.getModelCount(), fileSize));

            log.info("Export completed: {} models, {} bytes compressed",
                    manifest.getModelCount(), fileSize);

            return ExportResult.success(outputPath, manifest, archiveChecksum, fileSize);

        } catch (Exception e) {
            log.error("Export failed", e);
            progressCallback.accept(ExportProgress.failed(e.getMessage()));
            return ExportResult.failure("Export failed: " + e.getMessage());
        }
    }

    /**
     * Export all models in the registry.
     */
    public ExportResult exportAll(Path outputPath, ExportOptions options) {
        List<String> modelIds = registryService.loadRegistry().getAllModels().keySet()
                .stream().toList();
        return export(modelIds, outputPath, options);
    }

    /**
     * Generate a standard archive filename.
     */
    public String generateFilename(String archiveId, String version) {
        return KompileArchive.generateFilename(archiveId, version);
    }

    /**
     * Generate a default archive filename with date.
     */
    public String generateDefaultFilename() {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "kompile-models-" + date + KompileArchive.EXTENSION;
    }

    private ArchiveManifest createManifest(ExportOptions options) {
        ArchiveManifest manifest = ArchiveManifest.create(
                options.getArchiveId(),
                options.getVersion(),
                options.getDescription()
        );
        manifest.setArchiveName(options.getArchiveName());
        manifest.setPublisher(options.getPublisher());
        manifest.setCompatibility(options.getCompatibility() != null
                ? options.getCompatibility()
                : ArchiveCompatibility.defaultCompatibility());
        manifest.setReleaseDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        manifest.setKompileVersion(getKompileVersion());
        if (options.getTags() != null) {
            manifest.setTags(options.getTags());
        }
        return manifest;
    }

    private long addDirectoryToTar(TarArchiveOutputStream tar, Path directory,
                                   String basePath, ArchiveManifest manifest) throws IOException {
        long totalBytes = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                String entryName = basePath + "/" + path.getFileName().toString();

                if (Files.isDirectory(path)) {
                    totalBytes += addDirectoryToTar(tar, path, entryName, manifest);
                } else {
                    long fileSize = Files.size(path);
                    totalBytes += fileSize;

                    TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
                    tar.putArchiveEntry(entry);

                    String checksum = copyFileToTarWithChecksum(tar, path);
                    manifest.addChecksum(entryName, checksum);

                    tar.closeArchiveEntry();
                }
            }
        }

        return totalBytes;
    }

    private String copyFileToTarWithChecksum(TarArchiveOutputStream tar, Path file)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    tar.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return formatChecksum(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private void addTextFile(TarArchiveOutputStream tar, String filename, String content,
                             ArchiveManifest manifest) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(filename);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();

        String checksum = calculateSha256(bytes);
        manifest.addChecksum(filename, checksum);
    }

    private void addChecksumsFile(TarArchiveOutputStream tar, ArchiveManifest manifest)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# SHA256 checksums for Kompile Archive\n");
        sb.append("# Generated: ").append(Instant.now()).append("\n\n");

        for (Map.Entry<String, String> entry : manifest.getChecksums().entrySet()) {
            // Format: checksum  filename (SHA256SUMS format)
            String checksum = entry.getValue().replace("sha256:", "");
            sb.append(checksum).append("  ").append(entry.getKey()).append("\n");
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(KompileArchive.CHECKSUMS_FILENAME);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    private void addManifest(TarArchiveOutputStream tar, ArchiveManifest manifest)
            throws IOException {
        byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
        TarArchiveEntry manifestEntry = new TarArchiveEntry(KompileArchive.MANIFEST_FILENAME);
        manifestEntry.setSize(manifestBytes.length);
        tar.putArchiveEntry(manifestEntry);
        tar.write(manifestBytes);
        tar.closeArchiveEntry();
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

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return formatChecksum(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return null;
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
        // Try to get version from package info or return default
        Package pkg = getClass().getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "1.0.0-SNAPSHOT";
    }

    /**
     * Options for archive export.
     */
    @Data
    @Builder
    public static class ExportOptions {
        private String archiveId;
        private String archiveName;
        private String version;
        private String description;
        private String changelog;
        private String readme;
        private String modelVersion;
        private ArchivePublisher publisher;
        private ArchiveCompatibility compatibility;
        private List<String> tags;

        /**
         * Creates default export options.
         */
        public static ExportOptions defaults() {
            String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            return ExportOptions.builder()
                    .archiveId("kompile-models")
                    .archiveName("Kompile Model Archive")
                    .version("1.0.0")
                    .description("Model archive created " + date)
                    .publisher(ArchivePublisher.kompile())
                    .build();
        }

        /**
         * Creates export options with the specified version.
         */
        public static ExportOptions withVersion(String archiveId, String version) {
            return ExportOptions.builder()
                    .archiveId(archiveId)
                    .version(version)
                    .publisher(ArchivePublisher.kompile())
                    .build();
        }
    }

    /**
     * Result of an export operation.
     */
    @Data
    @Builder
    public static class ExportResult {
        private final boolean success;
        private final String errorMessage;
        private final Path archivePath;
        private final ArchiveManifest manifest;
        private final String archiveChecksum;
        private final long archiveSize;

        public static ExportResult success(Path path, ArchiveManifest manifest,
                                           String checksum, long size) {
            return ExportResult.builder()
                    .success(true)
                    .archivePath(path)
                    .manifest(manifest)
                    .archiveChecksum(checksum)
                    .archiveSize(size)
                    .build();
        }

        public static ExportResult failure(String error) {
            return ExportResult.builder()
                    .success(false)
                    .errorMessage(error)
                    .build();
        }

        public int getModelCount() {
            return manifest != null ? manifest.getModelCount() : 0;
        }
    }

    /**
     * Progress update during export.
     */
    @Data
    @Builder
    public static class ExportProgress {
        public enum Phase { STARTING, PROCESSING, WRITING_MANIFEST, COMPLETED, FAILED }

        private final Phase phase;
        private final String currentModel;
        private final int processedCount;
        private final int totalCount;
        private final long bytesWritten;
        private final String message;

        public static ExportProgress starting(int totalModels) {
            return ExportProgress.builder()
                    .phase(Phase.STARTING)
                    .totalCount(totalModels)
                    .message("Starting export of " + totalModels + " models")
                    .build();
        }

        public static ExportProgress processing(String modelId, int processed, int total) {
            return ExportProgress.builder()
                    .phase(Phase.PROCESSING)
                    .currentModel(modelId)
                    .processedCount(processed)
                    .totalCount(total)
                    .message("Processing: " + modelId)
                    .build();
        }

        public static ExportProgress writingManifest() {
            return ExportProgress.builder()
                    .phase(Phase.WRITING_MANIFEST)
                    .message("Writing archive manifest")
                    .build();
        }

        public static ExportProgress completed(int modelCount, long archiveSize) {
            return ExportProgress.builder()
                    .phase(Phase.COMPLETED)
                    .processedCount(modelCount)
                    .bytesWritten(archiveSize)
                    .message("Export completed: " + modelCount + " models")
                    .build();
        }

        public static ExportProgress failed(String error) {
            return ExportProgress.builder()
                    .phase(Phase.FAILED)
                    .message("Export failed: " + error)
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
