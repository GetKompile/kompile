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

package ai.kompile.staging.export;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Service for exporting models to portable bundles for air-gap transfer.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final int BUFFER_SIZE = 8192;

    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ExportService(RegistryService registryService) {
        this.registryService = registryService;
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Export specified models to a bundle.
     *
     * @param modelIds List of model IDs to export
     * @param outputPath Path for the output .tar.gz file
     * @param description Optional description for the bundle
     * @return Result of the export operation
     */
    public ExportResult export(List<String> modelIds, Path outputPath, String description) {
        log.info("Exporting {} models to {}", modelIds.size(), outputPath);

        BundleManifest manifest = BundleManifest.create(
                description != null ? description : "Model bundle created " + LocalDate.now());
        long totalBytes = 0;

        try {
            Files.createDirectories(outputPath.getParent());

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

                    // Add all files from model directory
                    totalBytes += addDirectoryToTar(tar, modelDir, entry.getPath(), manifest);
                    manifest.addModel(entry);
                    log.info("Added model: {}", modelId);
                }

                // Add manifest
                manifest.setTotalSizeBytes(totalBytes);
                byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
                TarArchiveEntry manifestEntry = new TarArchiveEntry("manifest.json");
                manifestEntry.setSize(manifestBytes.length);
                tar.putArchiveEntry(manifestEntry);
                tar.write(manifestBytes);
                tar.closeArchiveEntry();

                tar.finish();
            }

            long fileSize = Files.size(outputPath);
            log.info("Export completed: {} models, {} bytes compressed", manifest.getModelCount(), fileSize);

            return ExportResult.success(outputPath, manifest.getModelCount(), fileSize);

        } catch (Exception e) {
            log.error("Export failed", e);
            return ExportResult.failure("Export failed: " + e.getMessage());
        }
    }

    /**
     * Export all models in the registry.
     */
    public ExportResult exportAll(Path outputPath) {
        List<String> modelIds = registryService.loadRegistry().getAllModels().keySet()
                .stream().toList();
        return export(modelIds, outputPath, "Complete model bundle");
    }

    /**
     * Generate default export filename.
     */
    public String generateBundleFilename() {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "kompile-models-bundle-" + date + ".tar.gz";
    }

    private long addDirectoryToTar(TarArchiveOutputStream tar, Path directory,
                                   String basePath, BundleManifest manifest) throws IOException {
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

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    /**
     * Result of an export operation.
     */
    public static class ExportResult {
        private final boolean success;
        private final String errorMessage;
        private final Path bundlePath;
        private final int modelCount;
        private final long bundleSize;

        private ExportResult(boolean success, String error, Path path, int count, long size) {
            this.success = success;
            this.errorMessage = error;
            this.bundlePath = path;
            this.modelCount = count;
            this.bundleSize = size;
        }

        public static ExportResult success(Path path, int count, long size) {
            return new ExportResult(true, null, path, count, size);
        }

        public static ExportResult failure(String error) {
            return new ExportResult(false, error, null, 0, 0);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public Path getBundlePath() { return bundlePath; }
        public int getModelCount() { return modelCount; }
        public long getBundleSize() { return bundleSize; }
    }
}
