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

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.config.ModelWeightCacheConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three-tier weight cache: GPU -> CPU -> Disk.
 * Demotes weights when GPU memory is pressured, promotes on access.
 * Configuration persisted to ~/.kompile/config/model-weight-cache-config.json.
 */
@Service
public class ModelWeightCache {

    private static final Logger log = LoggerFactory.getLogger(ModelWeightCache.class);
    private static final String CONFIG_FILENAME = "model-weight-cache-config.json";

    public enum WeightTier { GPU, CPU, DISK }

    public record WeightBlock(
            String modelId,
            String layerName,
            WeightTier tier,
            long sizeBytes,
            Instant lastAccessed
    ) {}

    private final MemoryPoolManager memoryPoolManager;
    private final GpuResourceManager gpuResourceManager;
    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile ModelWeightCacheConfig currentConfig;

    /** Registered weight blocks: "modelId:layerName" -> WeightBlock */
    private final Map<String, WeightBlock> weightBlocks = new ConcurrentHashMap<>();

    /** GPU-resident weights */
    private final Map<String, INDArray> gpuWeights = new ConcurrentHashMap<>();

    /** CPU-resident weights (demoted from GPU) */
    private final Map<String, INDArray> cpuWeights = new ConcurrentHashMap<>();

    /** Disk paths for offloaded weights */
    private final Map<String, Path> diskWeights = new ConcurrentHashMap<>();

    private final AtomicLong cpuUsedBytes = new AtomicLong(0);

    @Autowired
    public ModelWeightCache(
            @Autowired(required = false) MemoryPoolManager memoryPoolManager,
            GpuResourceManager gpuResourceManager,
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.memoryPoolManager = memoryPoolManager;
        this.gpuResourceManager = gpuResourceManager;
        this.objectMapper = new ObjectMapper();
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = ModelWeightCacheConfig.defaults();
    }

    @PostConstruct
    public void init() {
        loadPersistedConfig();
        if (currentConfig.isEnabled()) {
            try {
                Files.createDirectories(Paths.get(currentConfig.getDiskPath()));
            } catch (IOException e) {
                log.warn("Failed to create weight cache disk path: {}", e.getMessage());
            }
        }
        log.info("ModelWeightCache initialized: enabled={}", currentConfig.isEnabled());
    }

    private void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = objectMapper.readValue(json, ModelWeightCacheConfig.class);
            } catch (Exception e) {
                log.warn("Failed to load weight cache config: {}", e.getMessage());
                currentConfig = ModelWeightCacheConfig.defaults();
            }
        }
    }

    public ModelWeightCacheConfig getConfiguration() {
        return currentConfig;
    }

    public void saveConfiguration(ModelWeightCacheConfig config) throws IOException {
        this.currentConfig = config;
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) Files.createDirectories(configDir);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
        Files.writeString(configFilePath, json);
        log.info("Saved weight cache config: enabled={}", config.isEnabled());
    }

    /**
     * Register weights for a model layer. Initially placed in GPU tier.
     */
    public void registerWeights(String modelId, String layerName, INDArray weights) {
        if (!currentConfig.isEnabled()) return;
        String key = modelId + ":" + layerName;
        long sizeBytes = weights.length() * weights.dataType().width();
        weightBlocks.put(key, new WeightBlock(modelId, layerName, WeightTier.GPU, sizeBytes, Instant.now()));
        gpuWeights.put(key, weights);
        log.debug("Registered GPU weights: {} ({}MB)", key, sizeBytes / (1024 * 1024));
    }

    /**
     * Get weights for a model layer. Auto-promotes from lower tiers if needed.
     */
    public INDArray getWeights(String modelId, String layerName) {
        String key = modelId + ":" + layerName;
        WeightBlock block = weightBlocks.get(key);
        if (block == null) return null;

        // Update access time
        weightBlocks.put(key, new WeightBlock(block.modelId(), block.layerName(),
                block.tier(), block.sizeBytes(), Instant.now()));

        switch (block.tier()) {
            case GPU:
                return gpuWeights.get(key);
            case CPU:
                // Auto-promote to GPU
                INDArray cpuArr = cpuWeights.get(key);
                if (cpuArr != null) {
                    promoteToGpu(modelId, layerName);
                    return gpuWeights.get(key);
                }
                return cpuArr;
            case DISK:
                // Auto-promote to CPU then GPU
                promoteFromDisk(key);
                promoteToGpu(modelId, layerName);
                return gpuWeights.get(key);
            default:
                return null;
        }
    }

    /**
     * Demote weights from GPU to CPU.
     */
    public void demoteToHost(String modelId, String layerName) {
        String key = modelId + ":" + layerName;
        INDArray gpuArr = gpuWeights.remove(key);
        if (gpuArr == null) return;

        long sizeBytes = gpuArr.length() * gpuArr.dataType().width();
        // Duplicate to host memory
        INDArray cpuArr = gpuArr.dup();
        cpuWeights.put(key, cpuArr);
        cpuUsedBytes.addAndGet(sizeBytes);

        weightBlocks.put(key, new WeightBlock(modelId, layerName, WeightTier.CPU, sizeBytes, Instant.now()));
        log.info("Demoted to CPU: {} ({}MB)", key, sizeBytes / (1024 * 1024));
    }

    /**
     * Demote all weights for a model to CPU.
     */
    public void demoteAllToHost(String modelId) {
        weightBlocks.entrySet().stream()
                .filter(e -> e.getValue().modelId().equals(modelId) && e.getValue().tier() == WeightTier.GPU)
                .map(e -> e.getValue().layerName())
                .toList()
                .forEach(layer -> demoteToHost(modelId, layer));
    }

    /**
     * Demote weights from CPU to disk.
     */
    public void demoteToDisk(String modelId, String layerName) {
        String key = modelId + ":" + layerName;
        INDArray cpuArr = cpuWeights.remove(key);
        if (cpuArr == null) return;

        long sizeBytes = cpuArr.length() * cpuArr.dataType().width();
        cpuUsedBytes.addAndGet(-sizeBytes);

        try {
            Path diskFile = Paths.get(currentConfig.getDiskPath(), key.replace(":", "_") + ".bin");
            Nd4j.saveBinary(cpuArr, diskFile.toFile());
            diskWeights.put(key, diskFile);
            weightBlocks.put(key, new WeightBlock(modelId, layerName, WeightTier.DISK, sizeBytes, Instant.now()));
            log.info("Demoted to disk: {} ({}MB) -> {}", key, sizeBytes / (1024 * 1024), diskFile);
        } catch (IOException e) {
            log.error("Failed to demote {} to disk: {}", key, e.getMessage());
            // Put back in CPU
            cpuWeights.put(key, cpuArr);
            cpuUsedBytes.addAndGet(sizeBytes);
        }
    }

    /**
     * Promote weights from CPU to GPU.
     */
    public void promoteToGpu(String modelId, String layerName) {
        String key = modelId + ":" + layerName;
        INDArray cpuArr = cpuWeights.remove(key);
        if (cpuArr == null) return;

        long sizeBytes = cpuArr.length() * cpuArr.dataType().width();
        cpuUsedBytes.addAndGet(-sizeBytes);

        gpuWeights.put(key, cpuArr); // ND4J handles device placement
        weightBlocks.put(key, new WeightBlock(modelId, layerName, WeightTier.GPU, sizeBytes, Instant.now()));
        log.info("Promoted to GPU: {} ({}MB)", key, sizeBytes / (1024 * 1024));
    }

    /**
     * Promote all weights for a model to GPU.
     */
    public void promoteAllToGpu(String modelId) {
        weightBlocks.entrySet().stream()
                .filter(e -> e.getValue().modelId().equals(modelId) && e.getValue().tier() != WeightTier.GPU)
                .map(e -> e.getValue().layerName())
                .toList()
                .forEach(layer -> {
                    WeightBlock block = weightBlocks.get(modelId + ":" + layer);
                    if (block != null && block.tier() == WeightTier.DISK) {
                        promoteFromDisk(modelId + ":" + layer);
                    }
                    promoteToGpu(modelId, layer);
                });
    }

    private void promoteFromDisk(String key) {
        Path diskFile = diskWeights.remove(key);
        if (diskFile == null || !Files.exists(diskFile)) return;

        try {
            INDArray arr = Nd4j.readBinary(diskFile.toFile());
            long sizeBytes = arr.length() * arr.dataType().width();
            cpuWeights.put(key, arr);
            cpuUsedBytes.addAndGet(sizeBytes);

            String[] parts = key.split(":", 2);
            weightBlocks.put(key, new WeightBlock(parts[0], parts.length > 1 ? parts[1] : "",
                    WeightTier.CPU, sizeBytes, Instant.now()));
            log.info("Promoted from disk to CPU: {} ({}MB)", key, sizeBytes / (1024 * 1024));
        } catch (IOException e) {
            log.error("Failed to promote {} from disk: {}", key, e.getMessage());
        }
    }

    /**
     * Periodic pressure check. Demotes least-recently-accessed GPU weights when pool is pressured.
     */
    @Scheduled(fixedDelayString = "${kompile.model-weight-cache.pressure-check-interval-ms:30000}")
    public void checkPressure() {
        if (!currentConfig.isEnabled()) return;

        for (GpuDevice device : gpuResourceManager.getDevices()) {
            if (memoryPoolManager != null && memoryPoolManager.isPoolUnderPressure(device, MemoryPool.PoolType.WEIGHTS)) {
                // Find LRA GPU weight block and demote
                weightBlocks.entrySet().stream()
                        .filter(e -> e.getValue().tier() == WeightTier.GPU)
                        .min(Comparator.comparing(e -> e.getValue().lastAccessed()))
                        .ifPresent(entry -> {
                            log.info("GPU weight pool under pressure on {} — demoting LRA block: {}",
                                    device.name(), entry.getKey());
                            demoteToHost(entry.getValue().modelId(), entry.getValue().layerName());
                        });
            }
        }

        // Check CPU budget
        if (cpuUsedBytes.get() > currentConfig.getCpuBudgetBytes()) {
            cpuWeights.entrySet().stream()
                    .min(Comparator.comparing(e -> weightBlocks.getOrDefault(e.getKey(),
                            new WeightBlock("", "", WeightTier.CPU, 0, Instant.MIN)).lastAccessed()))
                    .ifPresent(entry -> {
                        String[] parts = entry.getKey().split(":", 2);
                        log.info("CPU weight budget exceeded — demoting to disk: {}", entry.getKey());
                        demoteToDisk(parts[0], parts.length > 1 ? parts[1] : "");
                    });
        }
    }

    /**
     * Get status for monitoring.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", currentConfig.isEnabled());

        long gpuCount = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.GPU).count();
        long cpuCount = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.CPU).count();
        long diskCount = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.DISK).count();

        long gpuBytes = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.GPU).mapToLong(WeightBlock::sizeBytes).sum();
        long cpuBytes = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.CPU).mapToLong(WeightBlock::sizeBytes).sum();
        long diskBytes = weightBlocks.values().stream().filter(b -> b.tier() == WeightTier.DISK).mapToLong(WeightBlock::sizeBytes).sum();

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("gpu", Map.of("blocks", gpuCount, "sizeMb", gpuBytes / (1024 * 1024)));
        tiers.put("cpu", Map.of("blocks", cpuCount, "sizeMb", cpuBytes / (1024 * 1024),
                "budgetMb", currentConfig.getCpuBudgetBytes() / (1024 * 1024)));
        tiers.put("disk", Map.of("blocks", diskCount, "sizeMb", diskBytes / (1024 * 1024),
                "path", currentConfig.getDiskPath()));

        status.put("tiers", tiers);
        status.put("totalBlocks", weightBlocks.size());
        status.put("gpuPressureThreshold", currentConfig.getGpuPressureThreshold());

        // Per-model summary
        Map<String, Map<String, Long>> perModel = new LinkedHashMap<>();
        for (WeightBlock block : weightBlocks.values()) {
            perModel.computeIfAbsent(block.modelId(), k -> new LinkedHashMap<>())
                    .merge(block.tier().name(), block.sizeBytes(), Long::sum);
        }
        status.put("perModel", perModel);

        return status;
    }
}
