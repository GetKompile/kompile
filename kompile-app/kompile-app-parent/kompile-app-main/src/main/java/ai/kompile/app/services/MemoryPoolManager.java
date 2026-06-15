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
import ai.kompile.app.config.MemoryPoolConfig;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages separate memory pools (Weights / Activations / KV Cache) per GPU device.
 * Configuration persisted to ~/.kompile/config/memory-pool-config.json.
 */
@Service
public class MemoryPoolManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryPoolManager.class);
    private static final String CONFIG_FILENAME = "memory-pool-config.json";

    private final GpuResourceManager gpuResourceManager;
    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile MemoryPoolConfig currentConfig;

    /** Per-device pools: deviceName -> poolType -> MemoryPool */
    private final Map<String, Map<MemoryPool.PoolType, MemoryPool>> devicePools = new ConcurrentHashMap<>();

    @Autowired
    public MemoryPoolManager(
            GpuResourceManager gpuResourceManager,
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.gpuResourceManager = gpuResourceManager;
        this.objectMapper = new ObjectMapper();
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = MemoryPoolConfig.defaults();
    }

    @PostConstruct
    public void init() {
        loadPersistedConfig();
        if (currentConfig.isEnabled()) {
            initializePools();
        }
        log.info("MemoryPoolManager initialized: enabled={}", currentConfig.isEnabled());
    }

    private void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = objectMapper.readValue(json, MemoryPoolConfig.class);
                log.info("Loaded memory pool config from {}", configFilePath);
            } catch (Exception e) {
                log.warn("Failed to load memory pool config from {}: {}", configFilePath, e.getMessage());
                currentConfig = MemoryPoolConfig.defaults();
            }
        }
    }

    private void initializePools() {
        devicePools.clear();
        for (GpuDevice device : gpuResourceManager.getDevices()) {
            long totalBytes = device.totalMemoryBytes();
            Map<MemoryPool.PoolType, MemoryPool> pools = new EnumMap<>(MemoryPool.PoolType.class);
            pools.put(MemoryPool.PoolType.WEIGHTS,
                    new MemoryPool(MemoryPool.PoolType.WEIGHTS, (long) (totalBytes * currentConfig.getWeightsFraction())));
            pools.put(MemoryPool.PoolType.ACTIVATIONS,
                    new MemoryPool(MemoryPool.PoolType.ACTIVATIONS, (long) (totalBytes * currentConfig.getActivationsFraction())));
            pools.put(MemoryPool.PoolType.KV_CACHE,
                    new MemoryPool(MemoryPool.PoolType.KV_CACHE, (long) (totalBytes * currentConfig.getKvCacheFraction())));
            devicePools.put(device.name(), pools);
            log.info("Initialized memory pools for {}: weights={}MB, activations={}MB, kv_cache={}MB",
                    device.name(),
                    pools.get(MemoryPool.PoolType.WEIGHTS).getCapacityBytes() / (1024 * 1024),
                    pools.get(MemoryPool.PoolType.ACTIVATIONS).getCapacityBytes() / (1024 * 1024),
                    pools.get(MemoryPool.PoolType.KV_CACHE).getCapacityBytes() / (1024 * 1024));
        }
    }

    public MemoryPoolConfig getConfiguration() {
        return currentConfig;
    }

    public void saveConfiguration(MemoryPoolConfig config) throws IOException {
        this.currentConfig = config;
        persistToDisk();
        if (config.isEnabled()) {
            initializePools();
        } else {
            devicePools.clear();
        }
        log.info("Saved memory pool config: enabled={}", config.isEnabled());
    }

    public void resetToDefaults() throws IOException {
        saveConfiguration(MemoryPoolConfig.defaults());
    }

    public boolean isEnabled() {
        return currentConfig.isEnabled();
    }

    /**
     * Try to allocate from a specific pool on a device.
     */
    public boolean allocate(GpuDevice device, MemoryPool.PoolType poolType, long bytes, String owner) {
        if (!currentConfig.isEnabled()) return true; // passthrough when disabled
        Map<MemoryPool.PoolType, MemoryPool> pools = devicePools.get(device.name());
        if (pools == null) return true;
        MemoryPool pool = pools.get(poolType);
        if (pool == null) return true;
        boolean success = pool.tryAllocate(bytes);
        if (success) {
            log.debug("Pool allocation: {} on {} for '{}': {}MB (utilization: {:.1f}%)",
                    poolType, device.name(), owner, bytes / (1024 * 1024), pool.getUtilization() * 100);
        }
        return success;
    }

    /**
     * Free bytes from a specific pool.
     */
    public void free(GpuDevice device, MemoryPool.PoolType poolType, long bytes, String owner) {
        if (!currentConfig.isEnabled()) return;
        Map<MemoryPool.PoolType, MemoryPool> pools = devicePools.get(device.name());
        if (pools == null) return;
        MemoryPool pool = pools.get(poolType);
        if (pool != null) {
            pool.free(bytes);
        }
    }

    /**
     * Check if a pool is under pressure (above threshold).
     */
    public boolean isPoolUnderPressure(GpuDevice device, MemoryPool.PoolType poolType) {
        if (!currentConfig.isEnabled()) return false;
        Map<MemoryPool.PoolType, MemoryPool> pools = devicePools.get(device.name());
        if (pools == null) return false;
        MemoryPool pool = pools.get(poolType);
        return pool != null && pool.getUtilization() >= currentConfig.getPressureThreshold();
    }

    public void setWeightsFraction(double fraction) {
        currentConfig.setWeightsFraction(fraction);
        if (currentConfig.isEnabled()) initializePools();
    }

    public void setActivationsFraction(double fraction) {
        currentConfig.setActivationsFraction(fraction);
        if (currentConfig.isEnabled()) initializePools();
    }

    public void setKvCacheFraction(double fraction) {
        currentConfig.setKvCacheFraction(fraction);
        if (currentConfig.isEnabled()) initializePools();
    }

    /**
     * Get pool status for all devices.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", currentConfig.isEnabled());
        status.put("weightsFraction", currentConfig.getWeightsFraction());
        status.put("activationsFraction", currentConfig.getActivationsFraction());
        status.put("kvCacheFraction", currentConfig.getKvCacheFraction());
        status.put("pressureThreshold", currentConfig.getPressureThreshold());

        Map<String, Object> devices = new LinkedHashMap<>();
        for (var entry : devicePools.entrySet()) {
            Map<String, Object> deviceInfo = new LinkedHashMap<>();
            for (var poolEntry : entry.getValue().entrySet()) {
                MemoryPool pool = poolEntry.getValue();
                Map<String, Object> poolInfo = new LinkedHashMap<>();
                poolInfo.put("capacityMb", pool.getCapacityBytes() / (1024 * 1024));
                poolInfo.put("allocatedMb", pool.getAllocatedBytes() / (1024 * 1024));
                poolInfo.put("availableMb", pool.getAvailableBytes() / (1024 * 1024));
                poolInfo.put("utilization", pool.getUtilization());
                poolInfo.put("underPressure", pool.getUtilization() >= currentConfig.getPressureThreshold());
                deviceInfo.put(poolEntry.getKey().name(), poolInfo);
            }
            devices.put(entry.getKey(), deviceInfo);
        }
        status.put("devices", devices);
        return status;
    }

    private void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
        Files.writeString(configFilePath, json);
    }
}
