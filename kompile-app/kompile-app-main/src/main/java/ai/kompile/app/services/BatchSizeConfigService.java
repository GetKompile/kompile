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

import ai.kompile.app.web.dto.*;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.BatchSizeOverride;
import ai.kompile.modelmanager.ModelConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing batch size configuration and running benchmarks.
 * Persists benchmark results to a JSON file for retention across restarts.
 */
@Service
public class BatchSizeConfigService {

    private static final Logger log = LoggerFactory.getLogger(BatchSizeConfigService.class);
    private static final String CONFIG_FILENAME = "batch-size-config.json";

    private final List<EmbeddingModel> embeddingModels;
    private final AnseriniEmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    // Default sample texts for benchmarking - variety of lengths
    private static final List<String> DEFAULT_SAMPLE_TEXTS = List.of(
            "The quick brown fox jumps over the lazy dog.",
            "Machine learning is a subset of artificial intelligence that enables systems to learn and improve from experience.",
            "In the realm of natural language processing, embeddings represent words or sentences as dense vectors in a continuous space, capturing semantic relationships.",
            "The transformer architecture, introduced in the seminal paper 'Attention Is All You Need', revolutionized the field of deep learning by enabling models to process sequential data in parallel through self-attention mechanisms.",
            "Retrieval-augmented generation combines the strengths of dense retrieval systems with large language models, allowing the model to access external knowledge bases during inference, thereby reducing hallucinations and improving factual accuracy in generated responses.",
            "Vector databases have become essential infrastructure for modern AI applications, providing efficient similarity search capabilities through algorithms like HNSW (Hierarchical Navigable Small World) graphs and inverted file indexes, enabling sub-linear time complexity for nearest neighbor queries in high-dimensional spaces.",
            "The emergence of foundation models has transformed the landscape of artificial intelligence, with large pre-trained models serving as the basis for numerous downstream applications across vision, language, and multimodal domains. These models, trained on vast amounts of data using self-supervised objectives, exhibit remarkable transfer learning capabilities, allowing practitioners to achieve strong performance on specialized tasks with minimal fine-tuning. The scaling laws observed in these models suggest that performance continues to improve with increased model size, data volume, and computational resources, driving the development of ever larger systems.",
            "Natural language understanding encompasses a broad range of tasks including named entity recognition, sentiment analysis, question answering, and semantic role labeling.");

    @Autowired
    public BatchSizeConfigService(
            @Autowired(required = false) List<EmbeddingModel> embeddingModels,
            @Autowired(required = false) AnseriniEmbeddingProperties embeddingProperties,
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.embeddingModels = embeddingModels != null ? embeddingModels : List.of();
        this.embeddingProperties = embeddingProperties;
        this.objectMapper = new ObjectMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("BatchSizeConfigService initialized with {} embedding models, config path: {}",
                this.embeddingModels.size(), configFilePath);
    }

    /**
     * Load persisted batch size configurations on startup.
     */
    @PostConstruct
    public void loadPersistedConfig() {
        // Skip loading if configFilePath is null (dataDir not configured)
        if (configFilePath == null) {
            log.warn("Cannot load batch config - kompile.data.dir not configured. Using defaults.");
            return;
        }

        log.info("Loading persisted batch config from: {}", configFilePath);

        if (embeddingProperties == null) {
            log.warn("Anserini embedding properties not available, skipping config load");
            return;
        }

        if (!Files.exists(configFilePath)) {
            log.info("No persisted batch config found at {} - will use defaults until benchmark is run",
                    configFilePath);
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            log.info("Read batch config file, content length: {} bytes", json.length());

            // Use simple Map<String, Map<String, Object>> for reliable Jackson
            // deserialization
            Map<String, Map<String, Object>> configs = objectMapper.readValue(
                    json, new TypeReference<Map<String, Map<String, Object>>>() {
                    });

            int loadedCount = 0;
            for (Map.Entry<String, Map<String, Object>> entry : configs.entrySet()) {
                String modelId = entry.getKey();
                Map<String, Object> config = entry.getValue();

                Integer optimalBatchSize = config.get("optimalBatchSize") != null
                        ? ((Number) config.get("optimalBatchSize")).intValue()
                        : null;
                Integer maxBatchSize = config.get("maxBatchSize") != null
                        ? ((Number) config.get("maxBatchSize")).intValue()
                        : null;
                Double memoryScaleFactor = config.get("memoryScaleFactor") != null
                        ? ((Number) config.get("memoryScaleFactor")).doubleValue()
                        : null;

                // Handle global settings (special key "__global__")
                if ("__global__".equals(modelId)) {
                    if (optimalBatchSize != null) {
                        embeddingProperties.setBaseOptimalBatchSize(optimalBatchSize);
                        log.info("SET baseOptimalBatchSize = {} (from persisted config)", optimalBatchSize);
                    }
                    if (maxBatchSize != null) {
                        embeddingProperties.setBaseMaxBatchSize(maxBatchSize);
                        log.info("SET baseMaxBatchSize = {} (from persisted config)", maxBatchSize);
                    }
                    if (memoryScaleFactor != null) {
                        embeddingProperties.setMemoryScaleFactor(memoryScaleFactor);
                    }

                    // Load timeout settings
                    Long modelLoadTimeoutSeconds = config.get("modelLoadTimeoutSeconds") != null
                            ? ((Number) config.get("modelLoadTimeoutSeconds")).longValue() : null;
                    Long requestTimeoutMs = config.get("requestTimeoutMs") != null
                            ? ((Number) config.get("requestTimeoutMs")).longValue() : null;
                    Long heartbeatTimeoutMs = config.get("heartbeatTimeoutMs") != null
                            ? ((Number) config.get("heartbeatTimeoutMs")).longValue() : null;
                    Long embedTimeoutSeconds = config.get("embedTimeoutSeconds") != null
                            ? ((Number) config.get("embedTimeoutSeconds")).longValue() : null;
                    Long embedBatchTimeoutSeconds = config.get("embedBatchTimeoutSeconds") != null
                            ? ((Number) config.get("embedBatchTimeoutSeconds")).longValue() : null;

                    if (modelLoadTimeoutSeconds != null) {
                        embeddingProperties.setModelLoadTimeoutSeconds(modelLoadTimeoutSeconds);
                    }
                    if (requestTimeoutMs != null) {
                        embeddingProperties.setRequestTimeoutMs(requestTimeoutMs);
                    }
                    if (heartbeatTimeoutMs != null) {
                        embeddingProperties.setHeartbeatTimeoutMs(heartbeatTimeoutMs);
                    }
                    if (embedTimeoutSeconds != null) {
                        embeddingProperties.setEmbedTimeoutSeconds(embedTimeoutSeconds);
                    }
                    if (embedBatchTimeoutSeconds != null) {
                        embeddingProperties.setEmbedBatchTimeoutSeconds(embedBatchTimeoutSeconds);
                    }

                    log.info("Loaded persisted GLOBAL batch config: optimal={}, max={}, scale={}",
                            optimalBatchSize, maxBatchSize, memoryScaleFactor);
                    log.info("Loaded persisted GLOBAL timeout config: modelLoad={}s, request={}ms, heartbeat={}ms, embed={}s, embedBatch={}s",
                            modelLoadTimeoutSeconds, requestTimeoutMs, heartbeatTimeoutMs, embedTimeoutSeconds, embedBatchTimeoutSeconds);
                } else {
                    // Per-model override
                    BatchSizeOverride override = new BatchSizeOverride(optimalBatchSize, maxBatchSize, memoryScaleFactor);
                    embeddingProperties.setModelOverride(modelId, override);
                    log.info("Loaded persisted batch config for model '{}': optimal={}, max={}, scale={}",
                            modelId, optimalBatchSize, maxBatchSize, memoryScaleFactor);
                }
                loadedCount++;
            }

            log.info("Successfully loaded {} persisted batch size configurations from {}", loadedCount, configFilePath);
        } catch (IOException e) {
            log.error("Failed to load persisted batch config from {}: {}", configFilePath, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error loading batch config: {}", e.getMessage(), e);
        }
    }

    /**
     * Persist current batch size configurations to file.
     * Saves both global settings and per-model overrides.
     */
    private void persistConfig() {
        if (embeddingProperties == null) {
            log.warn("Cannot persist config - embeddingProperties is null");
            return;
        }

        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            // Use simple Map structure for reliable Jackson serialization
            Map<String, Map<String, Object>> configs = new HashMap<>();

            // Save global settings under special key "__global__"
            Map<String, Object> globalConfig = new HashMap<>();
            globalConfig.put("optimalBatchSize", embeddingProperties.getBaseOptimalBatchSize());
            globalConfig.put("maxBatchSize", embeddingProperties.getBaseMaxBatchSize());
            globalConfig.put("memoryScaleFactor", embeddingProperties.getMemoryScaleFactor());
            // Save timeout settings
            globalConfig.put("modelLoadTimeoutSeconds", embeddingProperties.getModelLoadTimeoutSeconds());
            globalConfig.put("requestTimeoutMs", embeddingProperties.getRequestTimeoutMs());
            globalConfig.put("heartbeatTimeoutMs", embeddingProperties.getHeartbeatTimeoutMs());
            globalConfig.put("embedTimeoutSeconds", embeddingProperties.getEmbedTimeoutSeconds());
            globalConfig.put("embedBatchTimeoutSeconds", embeddingProperties.getEmbedBatchTimeoutSeconds());
            configs.put("__global__", globalConfig);

            // Save per-model overrides
            for (Map.Entry<String, BatchSizeOverride> entry : embeddingProperties.getAllModelOverrides().entrySet()) {
                String modelId = entry.getKey();
                BatchSizeOverride override = entry.getValue();

                Map<String, Object> configMap = new HashMap<>();
                configMap.put("optimalBatchSize", override.optimalBatchSize());
                configMap.put("maxBatchSize", override.maxBatchSize());
                configMap.put("memoryScaleFactor", override.memoryScaleFactor());
                configs.put(modelId, configMap);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(configs);
            Files.writeString(configFilePath, json);
            log.info("Persisted batch config (global + {} model overrides) to {}",
                    configs.size() - 1, configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist batch config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Gets available embedding models with their configurations.
     */
    public List<EmbeddingModelInfo> getAvailableModels() {
        List<EmbeddingModelInfo> models = new ArrayList<>();

        // Add loaded models
        for (EmbeddingModel model : embeddingModels) {
            String modelId = getModelIdFromModel(model);
            String displayName = getDisplayName(modelId);
            String modelType = "DENSE";

            if (model instanceof AnseriniEmbeddingModelImpl anseriniModel) {
                modelType = anseriniModel.getModelType() != null ? anseriniModel.getModelType().toUpperCase() : "DENSE";
            }

            BatchSizeConfigResponse batchConfig = getConfiguration(modelId);

            models.add(EmbeddingModelInfo.builder()
                    .modelId(modelId)
                    .displayName(displayName)
                    .dimensions(model.dimensions())
                    .modelType(modelType)
                    .isLoaded(true)
                    .implementationClass(model.getClass().getSimpleName())
                    .batchConfig(batchConfig)
                    .build());
        }

        // Add available but not loaded models from ModelConstants
        Set<String> loadedIds = models.stream()
                .map(EmbeddingModelInfo::modelId)
                .collect(Collectors.toSet());

        for (String modelId : ModelConstants.getAvailableEncoderModelIds()) {
            if (!loadedIds.contains(modelId)) {
                Integer dims = ModelConstants.getEmbeddingDimension(modelId);
                String modelType = ModelConstants.getModelType(modelId);

                models.add(EmbeddingModelInfo.builder()
                        .modelId(modelId)
                        .displayName(getDisplayName(modelId))
                        .dimensions(dims != null ? dims : 0)
                        .modelType(modelType != null ? modelType.toUpperCase() : "DENSE")
                        .isLoaded(false)
                        .implementationClass(null)
                        .batchConfig(getConfiguration(modelId))
                        .build());
            }
        }

        return models;
    }

    /**
     * Gets batch size configuration for a model.
     */
    public BatchSizeConfigResponse getConfiguration(String modelId) {
        Runtime runtime = Runtime.getRuntime();
        long availableMemoryMb = runtime.maxMemory() / (1024 * 1024);

        int optimalBatchSize;
        int maxBatchSize;
        double memoryScaleFactor;
        boolean hasOverride = false;

        if (embeddingProperties != null) {
            optimalBatchSize = embeddingProperties.getEffectiveOptimalBatchSize(modelId);
            maxBatchSize = embeddingProperties.getEffectiveMaxBatchSize(modelId);
            memoryScaleFactor = embeddingProperties.getEffectiveMemoryScaleFactor(modelId);
            hasOverride = embeddingProperties.hasModelOverride(modelId);
        } else {
            // Defaults if no properties available - use reasonable batch sizes
            optimalBatchSize = 32;
            maxBatchSize = 64;
            memoryScaleFactor = -1.0;
        }

        // Calculate effective batch size with memory scaling
        int effectiveBatchSize = calculateEffectiveBatchSize(optimalBatchSize, memoryScaleFactor);

        return BatchSizeConfigResponse.builder()
                .modelId(modelId)
                .currentOptimalBatchSize(optimalBatchSize)
                .currentMaxBatchSize(maxBatchSize)
                .absoluteMaxBatchSize(embeddingProperties != null ? embeddingProperties.getAbsoluteMaxBatchSize() : getDefaultAbsoluteMaxBatchSize())
                .memoryScaleFactor(memoryScaleFactor)
                .isAutoScaled(memoryScaleFactor < 0)
                .availableMemoryMb(availableMemoryMb)
                .calculatedEffectiveBatchSize(effectiveBatchSize)
                .hasRuntimeOverride(hasOverride)
                .build();
    }

    /**
     * Updates batch size configuration for a model.
     */
    public BatchSizeConfigResponse updateConfiguration(String modelId, BatchSizeConfigRequest request) {
        request.validate();

        if (embeddingProperties == null) {
            throw new IllegalStateException("Anserini embedding is not enabled - cannot update configuration");
        }

        Integer optimalBatchSize = request.optimalBatchSize();
        Integer maxBatchSize = request.maxBatchSize();
        Double memoryScaleFactor = request.memoryScaleFactor();

        // If modelId is provided, set per-model override
        if (modelId != null && !modelId.isBlank()) {
            // Get existing override or create new
            BatchSizeOverride currentOverride = embeddingProperties.getModelOverride(modelId);

            Integer newOptimal = optimalBatchSize != null ? optimalBatchSize
                    : (currentOverride != null ? currentOverride.optimalBatchSize() : null);
            Integer newMax = maxBatchSize != null ? maxBatchSize
                    : (currentOverride != null ? currentOverride.maxBatchSize() : null);
            Double newScale = memoryScaleFactor != null ? memoryScaleFactor
                    : (currentOverride != null ? currentOverride.memoryScaleFactor() : null);

            embeddingProperties.setModelOverride(modelId, new BatchSizeOverride(newOptimal, newMax, newScale));
            log.info("Updated batch size override for model {}: optimal={}, max={}, scale={}",
                    modelId, newOptimal, newMax, newScale);
        } else {
            // Update global settings
            if (optimalBatchSize != null) {
                embeddingProperties.setBaseOptimalBatchSize(optimalBatchSize);
            }
            if (maxBatchSize != null) {
                embeddingProperties.setBaseMaxBatchSize(maxBatchSize);
            }
            if (memoryScaleFactor != null) {
                embeddingProperties.setMemoryScaleFactor(memoryScaleFactor);
            }

            // IMPORTANT: Clear all per-model overrides when updating global settings
            // Otherwise per-model overrides would take precedence over the new global value
            Map<String, BatchSizeOverride> allOverrides = embeddingProperties.getAllModelOverrides();
            if (!allOverrides.isEmpty()) {
                log.info("Clearing {} per-model overrides to apply global settings", allOverrides.size());
                for (String overrideModelId : allOverrides.keySet()) {
                    embeddingProperties.clearModelOverride(overrideModelId);
                }
            }

            log.info("Updated global batch size settings: optimal={}, max={}, scale={}",
                    optimalBatchSize, maxBatchSize, memoryScaleFactor);
        }

        // Persist configuration to file for retention across restarts
        persistConfig();

        return getConfiguration(modelId != null ? modelId : "global");
    }

    /**
     * Resets batch size configuration to defaults.
     */
    public BatchSizeConfigResponse resetConfiguration(String modelId) {
        if (embeddingProperties == null) {
            throw new IllegalStateException("Anserini embedding is not enabled");
        }

        if (modelId != null && !modelId.isBlank()) {
            embeddingProperties.clearModelOverride(modelId);
            log.info("Cleared batch size override for model: {}", modelId);
        } else {
            // Reset global to defaults
            embeddingProperties.setBaseOptimalBatchSize(8);
            embeddingProperties.setBaseMaxBatchSize(16);
            embeddingProperties.setMemoryScaleFactor(-1.0);
            log.info("Reset global batch size settings to defaults");
        }

        // Persist configuration to file for retention across restarts
        persistConfig();

        return getConfiguration(modelId);
    }

    // ========== TIMEOUT CONFIGURATION ==========

    /**
     * Gets current timeout configuration.
     *
     * @return map containing all timeout settings
     */
    public Map<String, Object> getTimeoutConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();

        if (embeddingProperties != null) {
            config.put("modelLoadTimeoutSeconds", embeddingProperties.getModelLoadTimeoutSeconds());
            config.put("requestTimeoutMs", embeddingProperties.getRequestTimeoutMs());
            config.put("heartbeatTimeoutMs", embeddingProperties.getHeartbeatTimeoutMs());
            config.put("embedTimeoutSeconds", embeddingProperties.getEmbedTimeoutSeconds());
            config.put("embedBatchTimeoutSeconds", embeddingProperties.getEmbedBatchTimeoutSeconds());
        } else {
            // Defaults when properties not available - match AnseriniEmbeddingConfiguration defaults
            config.put("modelLoadTimeoutSeconds", 300L);  // 5 minutes
            config.put("requestTimeoutMs", 60000L);       // 60 seconds
            config.put("heartbeatTimeoutMs", 60000L);     // 60 seconds
            config.put("embedTimeoutSeconds", 120L);      // 2 minutes
            config.put("embedBatchTimeoutSeconds", 300L); // 5 minutes
        }

        config.put("timeoutsEnabled", isAnyTimeoutEnabled());

        return config;
    }

    /**
     * Updates timeout configuration.
     *
     * @param modelLoadTimeoutSeconds timeout for model loading (0 = no timeout)
     * @param requestTimeoutMs timeout for subprocess requests (0 = no timeout)
     * @param heartbeatTimeoutMs timeout for heartbeat detection (0 = no timeout)
     * @param embedTimeoutSeconds timeout for single embed (0 = no timeout)
     * @param embedBatchTimeoutSeconds timeout for batch embed (0 = no timeout)
     * @return updated timeout configuration
     */
    public Map<String, Object> updateTimeoutConfiguration(
            Long modelLoadTimeoutSeconds,
            Long requestTimeoutMs,
            Long heartbeatTimeoutMs,
            Long embedTimeoutSeconds,
            Long embedBatchTimeoutSeconds) {

        if (embeddingProperties == null) {
            throw new IllegalStateException("Anserini embedding is not enabled - cannot update timeout configuration");
        }

        if (modelLoadTimeoutSeconds != null) {
            embeddingProperties.setModelLoadTimeoutSeconds(modelLoadTimeoutSeconds);
        }
        if (requestTimeoutMs != null) {
            embeddingProperties.setRequestTimeoutMs(requestTimeoutMs);
        }
        if (heartbeatTimeoutMs != null) {
            embeddingProperties.setHeartbeatTimeoutMs(heartbeatTimeoutMs);
        }
        if (embedTimeoutSeconds != null) {
            embeddingProperties.setEmbedTimeoutSeconds(embedTimeoutSeconds);
        }
        if (embedBatchTimeoutSeconds != null) {
            embeddingProperties.setEmbedBatchTimeoutSeconds(embedBatchTimeoutSeconds);
        }

        log.info("Updated timeout configuration: modelLoad={}s, request={}ms, heartbeat={}ms, embed={}s, embedBatch={}s",
                embeddingProperties.getModelLoadTimeoutSeconds(),
                embeddingProperties.getRequestTimeoutMs(),
                embeddingProperties.getHeartbeatTimeoutMs(),
                embeddingProperties.getEmbedTimeoutSeconds(),
                embeddingProperties.getEmbedBatchTimeoutSeconds());

        // Persist configuration
        persistConfig();

        return getTimeoutConfiguration();
    }

    /**
     * Resets timeout configuration to defaults (no timeouts).
     *
     * @return updated timeout configuration
     */
    public Map<String, Object> resetTimeoutConfiguration() {
        if (embeddingProperties == null) {
            throw new IllegalStateException("Anserini embedding is not enabled");
        }

        embeddingProperties.setModelLoadTimeoutSeconds(0);
        embeddingProperties.setRequestTimeoutMs(0);
        embeddingProperties.setHeartbeatTimeoutMs(0);
        embeddingProperties.setEmbedTimeoutSeconds(0);
        embeddingProperties.setEmbedBatchTimeoutSeconds(0);

        log.info("Reset timeout configuration to defaults (no timeouts)");

        // Persist configuration
        persistConfig();

        return getTimeoutConfiguration();
    }

    /**
     * Checks if any timeout is enabled.
     */
    private boolean isAnyTimeoutEnabled() {
        if (embeddingProperties == null) {
            return false;
        }
        return embeddingProperties.getModelLoadTimeoutSeconds() > 0 ||
                embeddingProperties.getRequestTimeoutMs() > 0 ||
                embeddingProperties.getHeartbeatTimeoutMs() > 0 ||
                embeddingProperties.getEmbedTimeoutSeconds() > 0 ||
                embeddingProperties.getEmbedBatchTimeoutSeconds() > 0;
    }

    /**
     * Runs batch size benchmark test.
     */
    public BatchSizeTestResponse runBatchSizeTest(BatchSizeTestRequest request) {
        long testStartTime = System.currentTimeMillis();

        // Find the model to test
        EmbeddingModel model = findModel(request.modelId());
        if (model == null) {
            throw new IllegalArgumentException("Model not found: " + request.modelId());
        }

        String modelId = getModelIdFromModel(model);
        String modelName = getDisplayName(modelId);
        int dimensions = model.dimensions();

        // Get sample texts
        List<String> sampleTexts = request.sampleTexts() != null && !request.sampleTexts().isEmpty()
                ? request.sampleTexts()
                : DEFAULT_SAMPLE_TEXTS;

        // Get batch sizes to test
        List<Integer> batchSizes = request.getBatchSizesToTestOrDefault();
        int iterations = request.getIterationsOrDefault();
        int warmupIterations = request.getWarmupIterationsOrDefault();
        int timeoutSeconds = request.getTimeoutSecondsOrDefault();

        log.info("Starting batch size benchmark: model={}, batchSizes={}, iterations={}, warmup={}, texts={}",
                modelId, batchSizes, iterations, warmupIterations, sampleTexts.size());

        // Run benchmarks
        List<BatchSizeTestResult> results = new ArrayList<>();
        int maxSafeBatchSize = 0;

        for (int batchSize : batchSizes) {
            BatchSizeTestResult result = runSingleBatchTest(
                    model, sampleTexts, batchSize, iterations, warmupIterations, timeoutSeconds);
            results.add(result);

            if (result.success()) {
                maxSafeBatchSize = Math.max(maxSafeBatchSize, batchSize);
            }
        }

        // Calculate recommended batch size
        int recommendedBatchSize = calculateRecommendedBatchSize(results);

        // Calculate average sequence length (rough estimate)
        int totalChars = sampleTexts.stream().mapToInt(String::length).sum();
        int avgSeqLength = totalChars / sampleTexts.size() / 4; // rough tokens estimate

        // Build system info
        String systemInfo = buildSystemInfo();

        long testDuration = System.currentTimeMillis() - testStartTime;

        return BatchSizeTestResponse.builder()
                .modelId(modelId)
                .modelName(modelName)
                .embeddingDimensions(dimensions)
                .results(results)
                .recommendedBatchSize(recommendedBatchSize)
                .maxSafeBatchSize(maxSafeBatchSize)
                .testDurationMs(testDuration)
                .systemInfo(systemInfo)
                .sampleTextCount(sampleTexts.size())
                .avgSequenceLength(avgSeqLength)
                .build();
    }

    /**
     * Runs a single batch size test.
     */
    private BatchSizeTestResult runSingleBatchTest(
            EmbeddingModel model,
            List<String> sampleTexts,
            int batchSize,
            int iterations,
            int warmupIterations,
            int timeoutSeconds) {
        log.debug("Testing batch size: {}", batchSize);

        // Prepare batch of texts
        List<String> batchTexts = new ArrayList<>();
        while (batchTexts.size() < batchSize) {
            batchTexts.addAll(sampleTexts);
        }
        batchTexts = batchTexts.subList(0, batchSize);

        List<Long> times = new ArrayList<>();
        long memoryBefore = getUsedMemory();
        long peakMemory = memoryBefore;

        try {
            // Warmup iterations
            for (int i = 0; i < warmupIterations; i++) {
                model.embedBatch(batchTexts);
            }

            // Timed iterations
            for (int i = 0; i < iterations; i++) {
                // Check timeout
                long start = System.currentTimeMillis();
                List<float[]> results = model.embedBatch(batchTexts);
                long elapsed = System.currentTimeMillis() - start;
                times.add(elapsed);

                if (results == null || results.isEmpty()) {
                    return BatchSizeTestResult.failure(batchSize, "Model returned empty results");
                }

                // Track memory
                long currentMemory = getUsedMemory();
                peakMemory = Math.max(peakMemory, currentMemory);

                // Check timeout
                if (elapsed > TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
                    return BatchSizeTestResult.failure(batchSize,
                            "Timeout: single batch took " + elapsed + "ms (limit: " + timeoutSeconds + "s)");
                }
            }

            // Calculate statistics
            double avgTimeMs = times.stream().mapToLong(Long::longValue).average().orElse(0);
            double minTimeMs = times.stream().mapToLong(Long::longValue).min().orElse(0);
            double maxTimeMs = times.stream().mapToLong(Long::longValue).max().orElse(0);
            double stdDevMs = calculateStdDev(times, avgTimeMs);

            // Calculate throughput
            int totalChars = batchTexts.stream().mapToInt(String::length).sum();
            int estimatedTokens = totalChars / 4; // rough estimate
            double tokensPerSecond = avgTimeMs > 0 ? (estimatedTokens * 1000.0 / avgTimeMs) : 0;
            double documentsPerSecond = avgTimeMs > 0 ? (batchSize * 1000.0 / avgTimeMs) : 0;

            long memoryUsed = getUsedMemory() - memoryBefore;

            return BatchSizeTestResult.success(
                    batchSize,
                    avgTimeMs,
                    minTimeMs,
                    maxTimeMs,
                    stdDevMs,
                    tokensPerSecond,
                    documentsPerSecond,
                    memoryUsed,
                    peakMemory - memoryBefore,
                    estimatedTokens * iterations,
                    batchSize * iterations);

        } catch (OutOfMemoryError e) {
            System.gc(); // Try to recover
            return BatchSizeTestResult.failure(batchSize, "Out of memory: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Batch size {} test failed: {}", batchSize, e.getMessage());
            return BatchSizeTestResult.failure(batchSize, e.getMessage());
        }
    }

    /**
     * Calculates recommended batch size based on throughput and memory efficiency.
     */
    private int calculateRecommendedBatchSize(List<BatchSizeTestResult> results) {
        // Find the batch size with best throughput that is stable
        double bestScore = 0;
        int recommended = 1;

        for (BatchSizeTestResult result : results) {
            if (!result.success())
                continue;

            // Score = throughput / variance factor
            // Prefer stable performance (low stdDev)
            double varianceFactor = result.stdDevMs() > 0 ? Math.max(1.0, result.stdDevMs() / result.avgTimeMs()) : 1.0;
            double score = result.tokensPerSecond() / varianceFactor;

            // Penalize very high memory usage
            if (result.peakMemoryBytes() > 500 * 1024 * 1024) { // > 500MB
                score *= 0.8;
            }

            if (score > bestScore) {
                bestScore = score;
                recommended = result.batchSize();
            }
        }

        return recommended;
    }

    /**
     * Finds a model by ID.
     */
    private EmbeddingModel findModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return embeddingModels.isEmpty() ? null : embeddingModels.get(0);
        }

        for (EmbeddingModel model : embeddingModels) {
            String id = getModelIdFromModel(model);
            if (modelId.equals(id) || modelId.equalsIgnoreCase(id)) {
                return model;
            }
        }

        return null;
    }

    /**
     * Extracts model ID from an EmbeddingModel.
     */
    private String getModelIdFromModel(EmbeddingModel model) {
        if (model instanceof AnseriniEmbeddingModelImpl anseriniModel) {
            return anseriniModel.getModelIdentifier();
        }
        return model.getClass().getSimpleName();
    }

    /**
     * Gets display name for a model.
     */
    private String getDisplayName(String modelId) {
        if (modelId == null)
            return "Unknown";

        return switch (modelId.toLowerCase()) {
            case "bge-base-en-v1.5" -> "BGE Base English v1.5";
            case "arctic-embed-l" -> "Arctic Embed Large";
            case "cosdpr-distil" -> "CosDPR Distil";
            case "splade-pp-ed" -> "SPLADE++ Efficient";
            case "splade-pp-sd" -> "SPLADE++ Self-Distillation";
            default -> modelId;
        };
    }

    /**
     * Calculates effective batch size with memory scaling.
     */
    private int calculateEffectiveBatchSize(int baseBatchSize, double memoryScaleFactor) {
        if (memoryScaleFactor < 0) {
            // Auto-detect based on heap
            Runtime runtime = Runtime.getRuntime();
            long maxHeapMb = runtime.maxMemory() / (1024 * 1024);

            if (maxHeapMb < 4096) {
                memoryScaleFactor = 0.5;
            } else if (maxHeapMb < 8192) {
                memoryScaleFactor = 0.75;
            } else {
                memoryScaleFactor = 1.0;
            }
        }

        return Math.max(1, (int) Math.round(baseBatchSize * memoryScaleFactor));
    }

    /**
     * Calculates default absolute max batch size based on available memory.
     * Used when embeddingProperties is not available.
     */
    private int getDefaultAbsoluteMaxBatchSize() {
        Runtime runtime = Runtime.getRuntime();
        long maxHeapMb = runtime.maxMemory() / (1024 * 1024);

        if (maxHeapMb >= 64 * 1024) {      // 64GB+
            return 8192;
        } else if (maxHeapMb >= 32 * 1024) { // 32GB
            return 4096;
        } else if (maxHeapMb >= 16 * 1024) { // 16GB
            return 2048;
        } else if (maxHeapMb >= 8 * 1024) {  // 8GB
            return 1024;
        } else if (maxHeapMb >= 4 * 1024) {  // 4GB
            return 512;
        } else if (maxHeapMb >= 2 * 1024) {  // 2GB
            return 256;
        } else {
            return 128;
        }
    }

    /**
     * Builds system information string.
     */
    private String buildSystemInfo() {
        Runtime runtime = Runtime.getRuntime();
        return String.format("CPUs: %d, Max Heap: %dMB, Used: %dMB, OS: %s",
                runtime.availableProcessors(),
                runtime.maxMemory() / (1024 * 1024),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                System.getProperty("os.name"));
    }

    /**
     * Gets current used memory in bytes.
     */
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Calculates standard deviation.
     */
    private double calculateStdDev(List<Long> values, double mean) {
        if (values.size() < 2)
            return 0;
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
        return Math.sqrt(variance);
    }
}
