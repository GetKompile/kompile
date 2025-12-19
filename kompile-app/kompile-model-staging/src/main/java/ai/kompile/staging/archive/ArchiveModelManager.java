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

import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.registry.ModelEntry;
import ai.kompile.staging.registry.ModelRegistry;
import ai.kompile.staging.registry.RegistryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model manager that loads models from .karch archives.
 * Supports both embedded classpath archives and file system archives.
 *
 * <p>This manager can operate in several modes:
 * <ul>
 *   <li>Embedded archive: Models bundled in the JAR at classpath</li>
 *   <li>Local archive: Models loaded from a .karch file on disk</li>
 *   <li>Hybrid: Archive as primary, with fallback to registry downloads</li>
 * </ul>
 */
@Slf4j
@Service
public class ArchiveModelManager {

    private final ModelSourceConfiguration config;
    private final RegistryService registryService;
    private final ArchiveImporter archiveImporter;
    private final ResourceLoader resourceLoader;

    private final Map<String, ModelEntry> archiveModels = new ConcurrentHashMap<>();
    private final Set<String> extractedModels = ConcurrentHashMap.newKeySet();
    private ArchiveManifest loadedManifest;
    private Path extractedArchivePath;
    private boolean initialized = false;

    @Autowired
    public ArchiveModelManager(
            ModelSourceConfiguration config,
            RegistryService registryService,
            ArchiveImporter archiveImporter,
            ResourceLoader resourceLoader) {
        this.config = config;
        this.registryService = registryService;
        this.archiveImporter = archiveImporter;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Initialize the archive model manager.
     * If auto-extract is enabled and an archive is configured, extract it.
     */
    @PostConstruct
    public void initialize() {
        if (config.hasArchiveSource() && config.isAutoExtractEmbedded()) {
            try {
                initializeArchive();
                initialized = true;
            } catch (Exception e) {
                log.error("Failed to initialize archive model manager", e);
                if (!config.isAllowFallback()) {
                    throw new RuntimeException("Archive initialization failed and fallback is disabled", e);
                }
            }
        }
    }

    /**
     * Initialize and load the configured archive.
     */
    public void initializeArchive() throws IOException {
        // Try embedded archive first
        if (config.getEmbeddedArchive() != null && !config.getEmbeddedArchive().isBlank()) {
            loadEmbeddedArchive(config.getEmbeddedArchive());
            return;
        }

        // Try local archive file
        if (config.getArchivePath() != null && !config.getArchivePath().isBlank()) {
            loadArchiveFile(Paths.get(config.getArchivePath()));
        }
    }

    /**
     * Load an embedded archive from classpath.
     */
    public void loadEmbeddedArchive(String classpathLocation) throws IOException {
        log.info("Loading embedded archive: {}", classpathLocation);

        Resource resource = resourceLoader.getResource(classpathLocation);
        if (!resource.exists()) {
            throw new IOException("Embedded archive not found: " + classpathLocation);
        }

        // Extract to temp directory
        Path tempArchive = Files.createTempFile("kompile-embedded-", ".karch");
        try (InputStream is = resource.getInputStream()) {
            Files.copy(is, tempArchive, StandardCopyOption.REPLACE_EXISTING);
        }

        loadArchiveFile(tempArchive);

        // Register for cleanup on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(tempArchive);
            } catch (IOException e) {
                // Ignore
            }
        }));
    }

    /**
     * Load an archive file from the file system.
     */
    public void loadArchiveFile(Path archivePath) throws IOException {
        if (!Files.exists(archivePath)) {
            throw new IOException("Archive file not found: " + archivePath);
        }

        log.info("Loading archive: {}", archivePath);

        // Read manifest to catalog available models
        loadedManifest = archiveImporter.readManifest(archivePath);
        if (loadedManifest == null) {
            throw new IOException("Invalid archive: manifest not found");
        }

        log.info("Archive contains {} models (version {})",
                loadedManifest.getModelCount(), loadedManifest.getContentVersion());

        // Index models from manifest
        for (ArchiveModelEntry entry : loadedManifest.getModels()) {
            ModelEntry modelEntry = entry.toModelEntry();
            archiveModels.put(entry.getModelId(), modelEntry);
            log.debug("Indexed model from archive: {}", entry.getModelId());
        }

        // Import archive to registry
        ArchiveImporter.ImportOptions options = ArchiveImporter.ImportOptions.builder()
                .skipExisting(true)
                .verifyChecksums(config.isVerifyChecksums())
                .build();

        ArchiveImporter.ImportResult result = archiveImporter.importArchive(archivePath, options);

        if (!result.isSuccess()) {
            log.warn("Archive import had issues: {}", result.getErrorMessage());
        } else {
            log.info("Imported {} models from archive ({} skipped)",
                    result.getImportedCount(), result.getSkippedCount());
        }

        extractedModels.addAll(result.getImportedModels());
    }

    /**
     * Check if a model is available in the archive.
     */
    public boolean hasModel(String modelId) {
        return archiveModels.containsKey(modelId);
    }

    /**
     * Get a model entry from the archive.
     */
    public Optional<ModelEntry> getModel(String modelId) {
        return Optional.ofNullable(archiveModels.get(modelId));
    }

    /**
     * Get all models available in the archive.
     */
    public List<ModelEntry> getAllModels() {
        return new ArrayList<>(archiveModels.values());
    }

    /**
     * Get the path to an extracted model.
     */
    public Optional<Path> getModelPath(String modelId) {
        ModelEntry entry = archiveModels.get(modelId);
        if (entry == null) {
            return Optional.empty();
        }

        Path modelPath = registryService.getModelDir().resolve(entry.getPath());
        if (Files.exists(modelPath)) {
            return Optional.of(modelPath);
        }

        return Optional.empty();
    }

    /**
     * Get the loaded archive manifest.
     */
    public Optional<ArchiveManifest> getManifest() {
        return Optional.ofNullable(loadedManifest);
    }

    /**
     * Check if the archive manager is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get archive status information.
     */
    public ArchiveStatus getStatus() {
        return ArchiveStatus.builder()
                .initialized(initialized)
                .archiveLoaded(loadedManifest != null)
                .archiveId(loadedManifest != null ? loadedManifest.getArchiveId() : null)
                .archiveVersion(loadedManifest != null ? loadedManifest.getContentVersion() : null)
                .totalModels(archiveModels.size())
                .extractedModels(extractedModels.size())
                .sourceType(config.getSourceType())
                .archivePath(config.getArchivePath())
                .embeddedArchive(config.getEmbeddedArchive())
                .build();
    }

    /**
     * Ensure a specific model is extracted and available.
     * Returns the path to the model directory.
     */
    public Path ensureModelExtracted(String modelId) throws IOException {
        if (!hasModel(modelId)) {
            throw new IOException("Model not found in archive: " + modelId);
        }

        Optional<Path> existingPath = getModelPath(modelId);
        if (existingPath.isPresent() && Files.exists(existingPath.get())) {
            return existingPath.get();
        }

        throw new IOException("Model not extracted: " + modelId);
    }

    /**
     * Status information for the archive manager.
     */
    @Data
    @lombok.Builder
    public static class ArchiveStatus {
        private boolean initialized;
        private boolean archiveLoaded;
        private String archiveId;
        private String archiveVersion;
        private int totalModels;
        private int extractedModels;
        private ModelSourceConfiguration.SourceType sourceType;
        private String archivePath;
        private String embeddedArchive;
    }
}
