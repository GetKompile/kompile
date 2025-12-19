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

package ai.kompile.staging.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        this(Paths.get(System.getProperty("user.home"), ".kompile", "models"));
    }

    public RegistryService(Path modelDir) {
        this.modelDir = modelDir;
        this.objectMapper = createObjectMapper();
        ensureDirectoryExists();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
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

    /**
     * Get the path to the registry file.
     */
    public Path getRegistryPath() {
        return modelDir.resolve(REGISTRY_FILENAME);
    }

    /**
     * Get the model directory path.
     */
    public Path getModelDir() {
        return modelDir;
    }

    /**
     * Get the archives directory path.
     * Archives are stored in ~/.kompile/archives by default.
     */
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

    /**
     * Load the registry from disk.
     * Returns a cached version if the file hasn't changed.
     */
    public ModelRegistry loadRegistry() {
        lock.readLock().lock();
        try {
            Path registryPath = getRegistryPath();

            if (!Files.exists(registryPath)) {
                log.debug("Registry file does not exist, returning empty registry");
                return ModelRegistry.empty();
            }

            // Check if cache is still valid
            long currentModified = Files.getLastModifiedTime(registryPath).toMillis();
            if (cachedRegistry != null && currentModified == lastModified) {
                return cachedRegistry;
            }

            // Upgrade to write lock to update cache
            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                // Double-check after acquiring write lock
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

    /**
     * Save the registry to disk.
     * Creates a backup of the existing registry first.
     */
    public void saveRegistry(ModelRegistry registry) {
        lock.writeLock().lock();
        try {
            Path registryPath = getRegistryPath();
            Path backupPath = modelDir.resolve(REGISTRY_BACKUP_FILENAME);

            // Create backup if registry exists
            if (Files.exists(registryPath)) {
                Files.copy(registryPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Update timestamp
            registry.setUpdatedAt(Instant.now().toString());

            // Write to temp file first, then atomic move
            Path tempPath = modelDir.resolve("registry.json.tmp");
            objectMapper.writeValue(tempPath.toFile(), registry);
            Files.move(tempPath, registryPath, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);

            // Update cache
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

    /**
     * Add or update a model entry in the registry.
     */
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

    /**
     * Remove a model from the registry.
     */
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

    /**
     * Get a model by ID.
     */
    public Optional<ModelEntry> getModel(String modelId) {
        return loadRegistry().findModel(modelId);
    }

    /**
     * Get all models of a specific type.
     */
    public List<ModelEntry> getModelsByType(ModelType type) {
        return loadRegistry().getModelsByType(type);
    }

    /**
     * Get all active encoder models.
     */
    public List<ModelEntry> getActiveEncoders() {
        return loadRegistry().getActiveEncoders();
    }

    /**
     * Get all active cross-encoder models.
     */
    public List<ModelEntry> getActiveCrossEncoders() {
        return loadRegistry().getActiveCrossEncoders();
    }

    /**
     * Check if a model exists in the registry.
     */
    public boolean hasModel(String modelId) {
        return loadRegistry().hasModel(modelId);
    }

    /**
     * Update the status of a model.
     */
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

    /**
     * Update a model entry with new values.
     * Only updates non-null fields from the update entry.
     */
    public Optional<ModelEntry> updateModel(String modelId, ModelEntry updates) {
        lock.writeLock().lock();
        try {
            ModelRegistry registry = loadRegistryInternal();
            ModelEntry existing = registry.getModel(modelId);
            if (existing == null) {
                log.warn("Model not found for update: {}", modelId);
                return Optional.empty();
            }

            // Update metadata if provided
            if (updates.getMetadata() != null) {
                ModelMetadata existingMeta = existing.getMetadata();
                ModelMetadata updateMeta = updates.getMetadata();
                if (existingMeta == null) {
                    existing.setMetadata(updateMeta);
                } else {
                    // Merge metadata fields
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
                }
            }

            // Update tokenizer config if provided
            if (updates.getTokenizer() != null) {
                existing.setTokenizer(updates.getTokenizer());
            }

            // Update status if provided
            if (updates.getStatus() != null) {
                existing.setStatus(updates.getStatus());
            }

            // Update type if provided
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

    /**
     * Initialize the registry if it doesn't exist.
     */
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

    /**
     * Internal load that doesn't use caching (for use within write lock).
     */
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

    /**
     * Clear the cached registry (useful for testing).
     */
    public void clearCache() {
        lock.writeLock().lock();
        try {
            cachedRegistry = null;
            lastModified = -1;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
