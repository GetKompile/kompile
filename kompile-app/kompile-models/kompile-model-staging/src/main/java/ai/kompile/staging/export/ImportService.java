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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Service for importing model bundles in air-gapped environments.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int BUFFER_SIZE = 8192;

    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ImportService(RegistryService registryService) {
        this.registryService = registryService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Import a model bundle.
     *
     * @param bundlePath Path to the .tar.gz bundle file
     * @param verifyChecksums Whether to verify file checksums
     * @return Result of the import operation
     */
    public ImportResult importBundle(Path bundlePath, boolean verifyChecksums) {
        log.info("Importing bundle: {}", bundlePath);

        if (!Files.exists(bundlePath)) {
            return ImportResult.failure("Bundle file not found: " + bundlePath);
        }

        Path tempDir = null;
        try {
            // Create temp directory for extraction
            tempDir = Files.createTempDirectory("kompile-import-");
            Map<String, String> extractedChecksums = new HashMap<>();

            // Extract bundle
            BundleManifest manifest = extractBundle(bundlePath, tempDir, extractedChecksums);

            if (manifest == null) {
                return ImportResult.failure("Invalid bundle: manifest.json not found");
            }

            log.info("Bundle contains {} models", manifest.getModelCount());

            // Verify checksums if requested
            if (verifyChecksums) {
                if (!manifest.verifyChecksums(extractedChecksums)) {
                    return ImportResult.failure("Checksum verification failed");
                }
                log.info("Checksum verification passed");
            }

            // Import each model
            int importedCount = 0;
            for (ModelEntry entry : manifest.getModels()) {
                try {
                    importModel(entry, tempDir);
                    importedCount++;
                    log.info("Imported model: {}", entry.getModelId());
                } catch (Exception e) {
                    log.error("Failed to import model: {}", entry.getModelId(), e);
                }
            }

            return ImportResult.success(importedCount, manifest.getModelCount());

        } catch (Exception e) {
            log.error("Import failed", e);
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
     * Preview a bundle without importing.
     */
    public BundleManifest previewBundle(Path bundlePath) {
        try (InputStream fi = new BufferedInputStream(Files.newInputStream(bundlePath));
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(fi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.getName().equals("manifest.json")) {
                    return objectMapper.readValue(tar, BundleManifest.class);
                }
            }
        } catch (Exception e) {
            log.error("Failed to read bundle manifest", e);
        }
        return null;
    }

    private BundleManifest extractBundle(Path bundlePath, Path destination,
                                         Map<String, String> checksums) throws IOException {
        BundleManifest manifest = null;

        try (InputStream fi = new BufferedInputStream(Files.newInputStream(bundlePath));
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(fi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                Path targetPath = destination.resolve(name).normalize();

                // Security check
                if (!targetPath.startsWith(destination)) {
                    throw new IOException("Tar entry outside destination: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else if (name.equals("manifest.json")) {
                    manifest = objectMapper.readValue(tar, BundleManifest.class);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    String checksum = extractFileWithChecksum(tar, targetPath);
                    checksums.put(name, checksum);
                }
            }
        }

        return manifest;
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

    private void importModel(ModelEntry entry, Path tempDir) throws IOException {
        Path sourcePath = tempDir.resolve(entry.getPath());
        Path targetPath = registryService.getModelDir().resolve(entry.getPath());

        if (!Files.exists(sourcePath)) {
            throw new IOException("Model directory not found in bundle: " + entry.getPath());
        }

        // Create target directory
        Files.createDirectories(targetPath.getParent());

        // Move or copy files
        try {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // If move fails, copy
            copyDirectory(sourcePath, targetPath);
        }

        // Add to registry
        registryService.addModel(entry);
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

    /**
     * Result of an import operation.
     */
    public static class ImportResult {
        private final boolean success;
        private final String errorMessage;
        private final int importedCount;
        private final int totalCount;

        private ImportResult(boolean success, String error, int imported, int total) {
            this.success = success;
            this.errorMessage = error;
            this.importedCount = imported;
            this.totalCount = total;
        }

        public static ImportResult success(int imported, int total) {
            return new ImportResult(true, null, imported, total);
        }

        public static ImportResult failure(String error) {
            return new ImportResult(false, error, 0, 0);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public int getImportedCount() { return importedCount; }
        public int getTotalCount() { return totalCount; }
    }
}
