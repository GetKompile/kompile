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

package ai.kompile.modelmanager.registry;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing the model registry file.
 * Provides thread-safe read/write operations for registry.json.
 */
@Service
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);
    private static final String REGISTRY_FILENAME = "registry.json";
    private static final String REGISTRY_BACKUP_FILENAME = "registry.json.bak";

    private final Path modelDir;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ModelRegistry cachedRegistry;
    private long lastModified = -1;

    public RegistryService() {
        this(defaultModelDir());
    }

    @Autowired
    public RegistryService(@Value("${kompile.staging.models-dir:${kompile.staging.model-dir:#{systemProperties['user.home'] + '/.kompile/models'}}}") String modelDir) {
        this(Paths.get(modelDir));
    }

    public RegistryService(Path modelDir) {
        this.modelDir = modelDir;
        this.objectMapper = createObjectMapper();
        ensureDirectoryExists();
    }

    private static Path defaultModelDir() {
        return Paths.get(System.getProperty("user.home"), ".kompile", "models");
    }

    public Path getModelsDir() {
        return modelDir;
    }

    private ObjectMapper createObjectMapper() {
        return JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private void ensureDirectoryExists() {
        try {
            if (!Files.exists(modelDir)) {
                Files.createDirectories(modelDir);
                log.info("Created model directory: {}", modelDir);
            }
        } catch (IOException e) {
            log.error("Failed to create model directory: {}", modelDir, e);
        }
    }

    public Path getRegistryPath() {
        return modelDir.resolve(REGISTRY_FILENAME);
    }

    public Path getModelDir() {
        return modelDir;
    }

    public Path getArchivesDir() {
        Path archivesDir = modelDir.getParent().resolve("archives");
        try {
            if (!Files.exists(archivesDir)) {
                Files.createDirectories(archivesDir);
                log.debug("Created archives directory: {}", archivesDir);
            }
        } catch (IOException e) {
            log.warn("Failed to create archives directory: {}", archivesDir, e);
        }
        return archivesDir;
    }

    public ModelRegistry loadRegistry() {
        lock.readLock().lock();
        try {
            Path registryPath = getRegistryPath();

            if (!Files.exists(registryPath)) {
                log.debug("Registry file does not exist, returning empty registry");
                return ModelRegistry.empty();
            }

            long currentModified = Files.getLastModifiedTime(registryPath).toMillis();
            if (cachedRegistry != null && currentModified == lastModified) {
                return cachedRegistry;
            }

            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                currentModified = Files.getLastModifiedTime(registryPath).toMillis();
                if (cachedRegistry != null && currentModified == lastModified) {
                    return cachedRegistry;
                }

                cachedRegistry = objectMapper.readValue(registryPath.toFile(), ModelRegistry.class);
                lastModified = currentModified;
                log.debug("Loaded registry with {} models", cachedRegistry.getTotalModelCount());
                return cachedRegistry;
            } finally {
                lock.readLock().lock();
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            log.error("Failed to load registry from {}", getRegistryPath(), e);
            return ModelRegistry.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveRegistry(ModelRegistry registry) {
        lock.writeLock().lock();
        try {
            Path registryPath = getRegistryPath();
            Path backupPath = modelDir.resolve(REGISTRY_BACKUP_FILENAME);

            if (Files.exists(registryPath)) {
                Files.copy(registryPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            registry.setUpdatedAt(Instant.now().toString());

            Path tempPath = modelDir.resolve("registry.json.tmp");
            objectMapper.writeValue(tempPath.toFile(), registry);
            Files.move(tempPath, registryPath, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);

            cachedRegistry = registry;
            lastModified = Files.getLastModifiedTime(registryPath).toMillis();

            log.info("Saved registry with {} models", registry.getTotalModelCount());
        } catch (IOException e) {
            log.error("Failed to save registry", e);
            throw new RuntimeException("Failed to save registry", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addModel(ModelEntry entry) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            registry.putModel(entry);
            saveRegistry(registry);
            log.info("Added/updated model: {}", entry.getModelId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ModelEntry> removeModel(String modelId) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry removed = registry.removeModel(modelId);
            if (removed != null) {
                saveRegistry(registry);
                log.info("Removed model: {}", modelId);
            }
            return Optional.ofNullable(removed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ModelEntry> getModel(String modelId) {
        return loadRegistry().findModel(modelId);
    }

    public List<ModelEntry> getModelsByType(ModelType type) {
        return loadRegistry().getModelsByType(type);
    }

    public List<ModelEntry> getActiveEncoders() {
        return loadRegistry().getActiveEncoders();
    }

    public List<ModelEntry> getActiveCrossEncoders() {
        return loadRegistry().getActiveCrossEncoders();
    }

    public Optional<ModelEntry> getActiveModelByType(ModelType type) {
        return loadRegistry().getModelsByType(type).stream()
                .filter(ModelEntry::isActive)
                .findFirst();
    }

    public List<ModelEntry> getAllOcrModels() {
        return loadRegistry().getActiveModels().stream()
                .filter(entry -> entry.getType().isOcr())
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean hasModel(String modelId) {
        return loadRegistry().hasModel(modelId);
    }

    /**
     * Check if a model exists and return info about it.
     */
    public ModelExistsInfo checkModelExists(String modelId) {
        Optional<ModelEntry> entry = getModel(modelId);
        if (entry.isEmpty()) {
            return new ModelExistsInfo(false, null);
        }
        return new ModelExistsInfo(true, entry.get());
    }

    public static class ModelExistsInfo {
        private final boolean present;
        private final ModelEntry entry;

        public ModelExistsInfo(boolean present, ModelEntry entry) {
            this.present = present;
            this.entry = entry;
        }

        public boolean isPresent() { return present; }
        public boolean isInRegistry() { return present; }

        public ModelExistsInfo get() { return this; }

        public ModelType getType() { return entry != null ? entry.getType() : null; }
        public ModelStatus getStatus() { return entry != null ? entry.getStatus() : null; }
        public String getPromotedAt() { return entry != null ? entry.getPromotedAt() : null; }
        public String getPath() { return entry != null ? entry.getPath() : null; }

        public String getSuggestedVersionedId() {
            if (entry == null) return null;
            String base = entry.getModelId();
            String timestamp = java.time.LocalDate.now().toString().replace("-", "");
            return base + "-v" + timestamp;
        }
    }

    /**
     * Delete a model completely from registry and optionally disk.
     */
    public DeleteResult deleteModelCompletely(String modelId, boolean deleteFiles) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry entry = registry.getModel(modelId);
            if (entry == null) {
                return new DeleteResult(false, modelId, "Model not found: " + modelId, false, false, null);
            }

            List<String> deletedFiles = new java.util.ArrayList<>();
            if (deleteFiles && entry.getPath() != null) {
                Path modelPath = modelDir.resolve(entry.getPath());
                if (Files.exists(modelPath)) {
                    try (java.util.stream.Stream<java.nio.file.Path> walkStream = java.nio.file.Files.walk(modelPath)) {
                        walkStream.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.delete(p);
                                        deletedFiles.add(p.toString());
                                    } catch (IOException e) {
                                        log.warn("Failed to delete: {}", p, e);
                                    }
                                });
                    } catch (IOException e) {
                        log.warn("Failed to walk model directory: {}", modelPath, e);
                    }
                }
            }

            registry.removeModel(modelId);
            saveRegistry(registry);
            log.info("Deleted model completely: {} (files deleted: {})", modelId, deletedFiles.size());
            return new DeleteResult(true, modelId, "Model deleted", true, !deletedFiles.isEmpty(), deletedFiles);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static class DeleteResult {
        private final boolean success;
        private final String modelId;
        private final String message;
        private final boolean registryRemoved;
        private final boolean filesDeleted;
        private final List<String> deletedFiles;

        public DeleteResult(boolean success, String modelId, String message,
                            boolean registryRemoved, boolean filesDeleted, List<String> deletedFiles) {
            this.success = success;
            this.modelId = modelId;
            this.message = message;
            this.registryRemoved = registryRemoved;
            this.filesDeleted = filesDeleted;
            this.deletedFiles = deletedFiles;
        }

        public boolean isSuccess() { return success; }
        public String getModelId() { return modelId; }
        public String getMessage() { return message; }
        public boolean isRegistryRemoved() { return registryRemoved; }
        public boolean isFilesDeleted() { return filesDeleted; }
        public List<String> getDeletedFiles() { return deletedFiles; }
    }

    /**
     * Get the directory path for a model by ID.
     */
    public Optional<Path> getModelDirectory(String modelId) {
        return getModel(modelId)
                .map(entry -> modelDir.resolve(entry.getPath()))
                .filter(Files::exists);
    }

    public boolean updateModelStatus(String modelId, ModelStatus newStatus) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry entry = registry.getModel(modelId);
            if (entry == null) {
                return false;
            }
            entry.setStatus(newStatus);
            if (newStatus == ModelStatus.ACTIVE) {
                entry.setPromotedAt(Instant.now().toString());
            }
            saveRegistry(registry);
            log.info("Updated model {} status to {}", modelId, newStatus);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ModelEntry> updateModel(String modelId, ModelEntry updates) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry existing = registry.getModel(modelId);
            if (existing == null) {
                log.warn("Model not found for update: {}", modelId);
                return Optional.empty();
            }

            if (updates.getMetadata() != null) {
                ModelMetadata existingMeta = existing.getMetadata();
                ModelMetadata updateMeta = updates.getMetadata();
                if (existingMeta == null) {
                    existing.setMetadata(updateMeta);
                } else {
                    if (updateMeta.getDescription() != null) {
                        existingMeta.setDescription(updateMeta.getDescription());
                    }
                    if (updateMeta.getEmbeddingDim() != null) {
                        existingMeta.setEmbeddingDim(updateMeta.getEmbeddingDim());
                    }
                    if (updateMeta.getHiddenSize() != null) {
                        existingMeta.setHiddenSize(updateMeta.getHiddenSize());
                    }
                    if (updateMeta.getNumLayers() != null) {
                        existingMeta.setNumLayers(updateMeta.getNumLayers());
                    }
                    if (updateMeta.getMaxSequenceLength() != null) {
                        existingMeta.setMaxSequenceLength(updateMeta.getMaxSequenceLength());
                    }
                    if (updateMeta.getFramework() != null) {
                        existingMeta.setFramework(updateMeta.getFramework());
                    }
                    if (updateMeta.getTrainingData() != null) {
                        existingMeta.setTrainingData(updateMeta.getTrainingData());
                    }
                    if (updateMeta.getSourceOrigin() != null) {
                        existingMeta.setSourceOrigin(updateMeta.getSourceOrigin());
                    }
                    if (updateMeta.getSourceRepository() != null) {
                        existingMeta.setSourceRepository(updateMeta.getSourceRepository());
                    }
                    if (updateMeta.getVocabSize() != null) {
                        existingMeta.setVocabSize(updateMeta.getVocabSize());
                    }
                    // Pipeline identity fields
                    if (updateMeta.getEncoderType() != null) {
                        existingMeta.setEncoderType(updateMeta.getEncoderType());
                    }
                    if (updateMeta.getRagRole() != null) {
                        existingMeta.setRagRole(updateMeta.getRagRole());
                    }
                    if (updateMeta.getVersion() != null) {
                        existingMeta.setVersion(updateMeta.getVersion());
                    }
                    // OCR-specific fields
                    if (updateMeta.getInputHeight() != null) {
                        existingMeta.setInputHeight(updateMeta.getInputHeight());
                    }
                    if (updateMeta.getInputWidth() != null) {
                        existingMeta.setInputWidth(updateMeta.getInputWidth());
                    }
                    if (updateMeta.getSupportedLanguages() != null) {
                        existingMeta.setSupportedLanguages(updateMeta.getSupportedLanguages());
                    }
                    if (updateMeta.getSupportsBatch() != null) {
                        existingMeta.setSupportsBatch(updateMeta.getSupportsBatch());
                    }
                    if (updateMeta.getMaxBatchSize() != null) {
                        existingMeta.setMaxBatchSize(updateMeta.getMaxBatchSize());
                    }
                    if (updateMeta.getSupportsHandwriting() != null) {
                        existingMeta.setSupportsHandwriting(updateMeta.getSupportsHandwriting());
                    }
                    if (updateMeta.getAverageAccuracy() != null) {
                        existingMeta.setAverageAccuracy(updateMeta.getAverageAccuracy());
                    }
                    if (updateMeta.getOcrVocabSize() != null) {
                        existingMeta.setOcrVocabSize(updateMeta.getOcrVocabSize());
                    }
                    if (updateMeta.getUsesCtc() != null) {
                        existingMeta.setUsesCtc(updateMeta.getUsesCtc());
                    }
                    // VLM-specific fields
                    if (updateMeta.getVisionFrames() != null) {
                        existingMeta.setVisionFrames(updateMeta.getVisionFrames());
                    }
                    if (updateMeta.getImageSize() != null) {
                        existingMeta.setImageSize(updateMeta.getImageSize());
                    }
                    if (updateMeta.getTileSize() != null) {
                        existingMeta.setTileSize(updateMeta.getTileSize());
                    }
                    if (updateMeta.getComponents() != null) {
                        existingMeta.setComponents(updateMeta.getComponents());
                    }
                    // Vision encoder IO config
                    if (updateMeta.getVisionEncoderPixelValuesName() != null) {
                        existingMeta.setVisionEncoderPixelValuesName(updateMeta.getVisionEncoderPixelValuesName());
                    }
                    if (updateMeta.getVisionEncoderPixelAttentionMaskName() != null) {
                        existingMeta.setVisionEncoderPixelAttentionMaskName(updateMeta.getVisionEncoderPixelAttentionMaskName());
                    }
                    if (updateMeta.getVisionEncoderPrimaryOutputName() != null) {
                        existingMeta.setVisionEncoderPrimaryOutputName(updateMeta.getVisionEncoderPrimaryOutputName());
                    }
                    if (updateMeta.getVisionEncoderOutputNames() != null) {
                        existingMeta.setVisionEncoderOutputNames(updateMeta.getVisionEncoderOutputNames());
                    }
                }
            }

            if (updates.getTokenizer() != null) {
                existing.setTokenizer(updates.getTokenizer());
            }

            if (updates.getPreprocessor() != null) {
                existing.setPreprocessor(updates.getPreprocessor());
            }

            if (updates.getStatus() != null) {
                existing.setStatus(updates.getStatus());
            }

            if (updates.getType() != null) {
                existing.setType(updates.getType());
            }

            saveRegistry(registry);
            log.info("Updated model: {}", modelId);
            return Optional.of(existing);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void initializeIfNeeded() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(getRegistryPath())) {
                saveRegistry(ModelRegistry.empty());
                log.info("Initialized empty registry at {}", getRegistryPath());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ModelRegistry loadRegistryInternal() {
        try {
            Path registryPath = getRegistryPath();
            if (!Files.exists(registryPath)) {
                return ModelRegistry.empty();
            }
            return objectMapper.readValue(registryPath.toFile(), ModelRegistry.class);
        } catch (IOException e) {
            log.error("Failed to load registry", e);
            return ModelRegistry.empty();
        }
    }

    public void clearCache() {
        lock.writeLock().lock();
        try {
            cachedRegistry = null;
            lastModified = -1;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== Optimization Support ====================

    /**
     * Prepare a model for optimization by creating a backup.
     * Returns the path to the model file if ready for optimization.
     */
    public Optional<Path> prepareForOptimization(String modelId, boolean force) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry entry = registry.getModel(modelId);
            if (entry == null) {
                log.warn("Model not found for optimization: {}", modelId);
                return Optional.empty();
            }

            // Check if already optimized
            if (!force && entry.getMetadata() != null && Boolean.TRUE.equals(entry.getMetadata().getOptimized())) {
                return Optional.empty();
            }

            Path modelPath = modelDir.resolve(entry.getModelFilePath());
            if (!Files.exists(modelPath)) {
                log.warn("Model file not found: {}", modelPath);
                return Optional.empty();
            }

            // Create backup
            Path backupPath = modelPath.getParent().resolve(modelPath.getFileName() + ".bak");
            try {
                Files.copy(modelPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Created backup for {}: {}", modelId, backupPath);
            } catch (IOException e) {
                log.error("Failed to create backup for {}: {}", modelId, e.getMessage());
                return Optional.empty();
            }

            // Store backup path in metadata
            if (entry.getMetadata() == null) {
                entry.setMetadata(ModelMetadata.builder().build());
            }
            entry.getMetadata().setUnoptimizedBackupFile(backupPath.toString());
            saveRegistry(registry);

            return Optional.of(modelPath);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Restore a model from its pre-optimization backup.
     */
    public boolean restoreFromBackup(String modelId) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry entry = registry.getModel(modelId);
            if (entry == null || entry.getMetadata() == null) {
                return false;
            }

            String backupFile = entry.getMetadata().getUnoptimizedBackupFile();
            if (backupFile == null) {
                log.warn("No backup file recorded for model: {}", modelId);
                return false;
            }

            Path backupPath = Path.of(backupFile);
            Path modelPath = modelDir.resolve(entry.getModelFilePath());

            if (!Files.exists(backupPath)) {
                log.warn("Backup file not found: {}", backupPath);
                return false;
            }

            try {
                Files.copy(backupPath, modelPath, StandardCopyOption.REPLACE_EXISTING);
                entry.getMetadata().setOptimized(false);
                entry.getMetadata().setOptimizedAt(null);
                entry.getMetadata().setOptimizationTimeMs(null);
                entry.getMetadata().setAppliedOptimizations(null);
                entry.getMetadata().setOptimizationStats(null);
                entry.getMetadata().setOptimizationConfig(null);
                saveRegistry(registry);
                log.info("Restored model {} from backup", modelId);
                return true;
            } catch (IOException e) {
                log.error("Failed to restore from backup: {}", e.getMessage());
                return false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Complete optimization by updating registry with results.
     */
    public OptimizationResult completeOptimizationWithDetails(
            String modelId, long optimizationTimeMs,
            List<String> appliedOptimizations, ModelMetadata.OptimizationStats stats,
            ModelMetadata.OptimizationConfig optimizationConfig) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry entry = registry.getModel(modelId);
            if (entry == null) {
                return new OptimizationResult(false, null);
            }

            if (entry.getMetadata() == null) {
                entry.setMetadata(ModelMetadata.builder().build());
            }

            ModelMetadata meta = entry.getMetadata();
            meta.setOptimized(true);
            meta.setOptimizedAt(Instant.now().toString());
            meta.setOptimizationTimeMs(optimizationTimeMs);
            meta.setAppliedOptimizations(appliedOptimizations);
            meta.setOptimizationStats(stats);
            meta.setOptimizationConfig(optimizationConfig);

            saveRegistry(registry);
            log.info("Completed optimization for model {}", modelId);

            return new OptimizationResult(true, meta.getUnoptimizedBackupFile());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Result of a registry optimization update.
     */
    public static class OptimizationResult {
        private final boolean success;
        private final String backupFile;

        public OptimizationResult(boolean success, String backupFile) {
            this.success = success;
            this.backupFile = backupFile;
        }

        public boolean isSuccess() { return success; }
        public String getBackupFile() { return backupFile; }
    }
}
