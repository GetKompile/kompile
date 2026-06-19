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

package ai.kompile.app.services;

import ai.kompile.app.services.pipeline.IngestPipelineConfig;
import ai.kompile.app.web.dto.PipelineConfigDto;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing pipeline configuration.
 * Persists configuration to a JSON file for retention across restarts.
 * Thread-safe with read-write lock.
 */
@Service
public class PipelineConfigService {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigService.class);
    private static final String CONFIG_FILENAME = "pipeline-config.json";
    private static final String[] AVAILABLE_PRESETS = {"defaults", "highThroughput", "lowMemory", "keywordOnly"};

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Current configuration (volatile for visibility)
    private volatile IngestPipelineConfig currentConfig;
    private volatile String currentPreset = "defaults";
    private volatile boolean isModified = false;

    // Graph optimization setting (not part of PipelineConfig, but persisted with it)
    // Default is false - optimization can be slow on some models (constant folding loops)
    // User can enable via UI when desired
    private volatile boolean optimizeGraphOnLoad = false;

    public PipelineConfigService(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = JsonUtils.standardMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);

        // Start with defaults
        this.currentConfig = IngestPipelineConfig.defaults();

        log.info("PipelineConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load persisted pipeline configuration on startup.
     */
    @PostConstruct
    public void loadPersistedConfig() {
        if (configFilePath == null) {
            log.warn("Cannot load pipeline config - kompile.data.dir not configured. Using defaults.");
            return;
        }

        log.info("Loading persisted pipeline config from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted pipeline config found at {} - using defaults", configFilePath);
            return;
        }

        lock.writeLock().lock();
        try {
            String json = Files.readString(configFilePath);
            PipelineConfigDto dto = objectMapper.readValue(json, PipelineConfigDto.class);

            // Build config from DTO
            IngestPipelineConfig.Builder builder = IngestPipelineConfig.builder();

            if (dto.getMinBatchSize() != null) builder.minBatchSize(dto.getMinBatchSize());
            if (dto.getDefaultBatchSize() != null) builder.defaultBatchSize(dto.getDefaultBatchSize());
            if (dto.getMaxBatchSize() != null) builder.maxBatchSize(dto.getMaxBatchSize());
            if (dto.getQueueCapacity() != null) builder.queueCapacity(dto.getQueueCapacity());
            if (dto.getQueuePollTimeoutMs() != null) builder.queuePollTimeoutMs(dto.getQueuePollTimeoutMs());
            if (dto.getMaxBatchWaitMs() != null) builder.maxBatchWaitMs(dto.getMaxBatchWaitMs());
            if (dto.getMinBatchWaitMs() != null) builder.minBatchWaitMs(dto.getMinBatchWaitMs());
            if (dto.getChunkingThreads() != null) builder.chunkingThreads(dto.getChunkingThreads());
            if (dto.getEmbeddingThreads() != null) builder.embeddingThreads(dto.getEmbeddingThreads());
            if (dto.getIndexingThreads() != null) builder.indexingThreads(dto.getIndexingThreads());
            if (dto.getIndexingBatchAccumulationSize() != null) builder.indexingBatchAccumulationSize(dto.getIndexingBatchAccumulationSize());
            if (dto.getSkipEmbedding() != null) builder.skipEmbedding(dto.getSkipEmbedding());
            if (dto.getEmbeddingTimeoutSeconds() != null) builder.embeddingTimeoutSeconds(dto.getEmbeddingTimeoutSeconds());

            currentConfig = builder.build();
            currentPreset = dto.getCurrentPreset();
            isModified = dto.getIsModified() != null ? dto.getIsModified() : true;

            // Load graph optimization setting (default to true if not present)
            if (dto.getOptimizeGraphOnLoad() != null) {
                optimizeGraphOnLoad = dto.getOptimizeGraphOnLoad();
            }

            log.info("Loaded persisted pipeline config: embeddingTimeout={}s, queueCapacity={}, embeddingThreads={}, optimizeGraphOnLoad={}",
                    currentConfig.embeddingTimeoutSeconds(),
                    currentConfig.queueCapacity(),
                    currentConfig.embeddingThreads(),
                    optimizeGraphOnLoad);
        } catch (IOException e) {
            log.error("Failed to load persisted pipeline config from {}: {}", configFilePath, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }

    }

    /**
     * Get the current pipeline configuration.
     */
    public IngestPipelineConfig getConfig() {
        lock.readLock().lock();
        try {
            return currentConfig;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the current configuration as a DTO.
     */
    public PipelineConfigDto getConfigDto() {
        lock.readLock().lock();
        try {
            return PipelineConfigDto.builder()
                    .minBatchSize(currentConfig.minBatchSize())
                    .defaultBatchSize(currentConfig.defaultBatchSize())
                    .maxBatchSize(currentConfig.maxBatchSize())
                    .queueCapacity(currentConfig.queueCapacity())
                    .queuePollTimeoutMs(currentConfig.queuePollTimeoutMs())
                    .maxBatchWaitMs(currentConfig.maxBatchWaitMs())
                    .minBatchWaitMs(currentConfig.minBatchWaitMs())
                    .chunkingThreads(currentConfig.chunkingThreads())
                    .embeddingThreads(currentConfig.embeddingThreads())
                    .indexingThreads(currentConfig.indexingThreads())
                    .indexingBatchAccumulationSize(currentConfig.indexingBatchAccumulationSize())
                    .skipEmbedding(currentConfig.skipEmbedding())
                    .embeddingTimeoutSeconds(currentConfig.embeddingTimeoutSeconds())
                    .optimizeGraphOnLoad(optimizeGraphOnLoad)
                    .currentPreset(currentPreset)
                    .isModified(isModified)
                    .availablePresets(AVAILABLE_PRESETS)
                    .build();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update the pipeline configuration with values from the DTO.
     * Only non-null values in the DTO are applied.
     */
    public PipelineConfigDto updateConfig(PipelineConfigDto dto) {
        dto.validate();

        lock.writeLock().lock();
        try {
            // Build new config from current + updates
            IngestPipelineConfig.Builder builder = IngestPipelineConfig.builder()
                    .minBatchSize(dto.getMinBatchSize() != null ? dto.getMinBatchSize() : currentConfig.minBatchSize())
                    .defaultBatchSize(dto.getDefaultBatchSize() != null ? dto.getDefaultBatchSize() : currentConfig.defaultBatchSize())
                    .maxBatchSize(dto.getMaxBatchSize() != null ? dto.getMaxBatchSize() : currentConfig.maxBatchSize())
                    .queueCapacity(dto.getQueueCapacity() != null ? dto.getQueueCapacity() : currentConfig.queueCapacity())
                    .queuePollTimeoutMs(dto.getQueuePollTimeoutMs() != null ? dto.getQueuePollTimeoutMs() : currentConfig.queuePollTimeoutMs())
                    .maxBatchWaitMs(dto.getMaxBatchWaitMs() != null ? dto.getMaxBatchWaitMs() : currentConfig.maxBatchWaitMs())
                    .minBatchWaitMs(dto.getMinBatchWaitMs() != null ? dto.getMinBatchWaitMs() : currentConfig.minBatchWaitMs())
                    .chunkingThreads(dto.getChunkingThreads() != null ? dto.getChunkingThreads() : currentConfig.chunkingThreads())
                    .embeddingThreads(dto.getEmbeddingThreads() != null ? dto.getEmbeddingThreads() : currentConfig.embeddingThreads())
                    .indexingThreads(dto.getIndexingThreads() != null ? dto.getIndexingThreads() : currentConfig.indexingThreads())
                    .indexingBatchAccumulationSize(dto.getIndexingBatchAccumulationSize() != null ? dto.getIndexingBatchAccumulationSize() : currentConfig.indexingBatchAccumulationSize())
                    .skipEmbedding(dto.getSkipEmbedding() != null ? dto.getSkipEmbedding() : currentConfig.skipEmbedding())
                    .embeddingTimeoutSeconds(dto.getEmbeddingTimeoutSeconds() != null ? dto.getEmbeddingTimeoutSeconds() : currentConfig.embeddingTimeoutSeconds());

            currentConfig = builder.build();
            currentPreset = null; // Custom config
            isModified = true;

            log.info("Updated pipeline config: embeddingTimeout={}s, queueCapacity={}, embeddingThreads={}",
                    currentConfig.embeddingTimeoutSeconds(),
                    currentConfig.queueCapacity(),
                    currentConfig.embeddingThreads());

            // Persist to file
            persistConfig();

            return getConfigDto();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Apply a preset configuration.
     */
    public PipelineConfigDto applyPreset(String presetName) {
        lock.writeLock().lock();
        try {
            currentConfig = switch (presetName.toLowerCase()) {
                case "highthroughput" -> IngestPipelineConfig.highThroughput();
                case "lowmemory" -> IngestPipelineConfig.lowMemory();
                case "keywordonly" -> IngestPipelineConfig.keywordOnly();
                default -> IngestPipelineConfig.defaults();
            };
            currentPreset = presetName.toLowerCase();
            isModified = false;

            log.info("Applied pipeline preset: {}", presetName);

            // Persist to file
            persistConfig();

            return getConfigDto();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reset configuration to defaults.
     */
    public PipelineConfigDto resetToDefaults() {
        return applyPreset("defaults");
    }

    /**
     * Persist current configuration to file.
     */
    private void persistConfig() {
        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            PipelineConfigDto dto = getConfigDto();
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
            Files.writeString(configFilePath, json);
            log.debug("Persisted pipeline config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist pipeline config to {}: {}", configFilePath, e.getMessage());
        }
    }

    /**
     * Get available preset names.
     */
    public String[] getAvailablePresets() {
        return AVAILABLE_PRESETS;
    }

    // ========== GRAPH OPTIMIZATION SETTINGS ==========

    /**
     * Check if graph optimization is enabled.
     * @return true if SameDiff graph optimization is enabled when loading models
     */
    public boolean isOptimizeGraphOnLoad() {
        lock.readLock().lock();
        try {
            return optimizeGraphOnLoad;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Set the graph optimization setting.
     * Note: This just updates the stored setting. The caller is responsible for
     * triggering a model reload if the setting changes from false to true.
     *
     * @param optimize true to enable graph optimization
     * @return true if the value changed, false if it was already set to this value
     */
    public boolean setOptimizeGraphOnLoad(boolean optimize) {
        lock.writeLock().lock();
        try {
            boolean changed = (optimizeGraphOnLoad != optimize);
            if (changed) {
                boolean previousValue = optimizeGraphOnLoad;
                optimizeGraphOnLoad = optimize;
                isModified = true;
                persistConfig();
                log.info("Graph optimization setting changed: {} -> {}", previousValue, optimize);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
