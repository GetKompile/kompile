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

import ai.kompile.app.config.ModelAdmissionConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Memory-aware model admission controller.
 * Controls which models are loaded and in what state (GPU_HOT, CPU_WARM, UNLOADED).
 * Prevents OOM by checking available memory before loading and evicting least-recently-used
 * models when capacity is reached.
 */
@Service
public class ModelAdmissionController {

    private static final Logger log = LoggerFactory.getLogger(ModelAdmissionController.class);

    public enum ModelState { LOADING, GPU_HOT, CPU_WARM, UNLOADED, REJECTED }

    public record LoadedModel(String modelId, ModelState state, String device,
                              long memoryBytes, Instant loadedAt, Instant lastUsedAt) {}

    public record AdmissionDecision(boolean admitted, String reason,
                                     long estimatedMemoryBytes, List<String> modelsToEvict) {}

    private final ModelAdmissionConfigService configService;
    private final GpuResourceManager gpuResourceManager;
    private final MemoryPoolManager memoryPoolManager;
    private final ApplicationEventPublisher eventPublisher;

    // Currently tracked models
    private final ConcurrentHashMap<String, LoadedModel> loadedModels = new ConcurrentHashMap<>();

    // Background load thread pool
    private final ExecutorService loadExecutor;
    private final AtomicInteger activeLoads = new AtomicInteger(0);

    // Memory estimation registry: modelId -> estimated bytes
    private final ConcurrentHashMap<String, Long> memoryEstimates = new ConcurrentHashMap<>();

    @Autowired
    public ModelAdmissionController(ModelAdmissionConfigService configService,
                                     @Autowired(required = false) GpuResourceManager gpuResourceManager,
                                     @Autowired(required = false) MemoryPoolManager memoryPoolManager,
                                     ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.gpuResourceManager = gpuResourceManager;
        this.memoryPoolManager = memoryPoolManager;
        this.eventPublisher = eventPublisher;
        ModelAdmissionConfig config = configService.getConfiguration();
        this.loadExecutor = Executors.newFixedThreadPool(config.getBackgroundLoadThreads(),
                r -> {
                    Thread t = new Thread(r, "model-admission-loader");
                    t.setDaemon(true);
                    return t;
                });
        log.info("ModelAdmissionController initialized (maxLoadedModels={}, maxConcurrentLoads={})",
                config.getMaxLoadedModels(), config.getMaxConcurrentLoads());
    }

    /**
     * Register a memory estimate for a model (e.g., from ModelMetadata.estimatedMemoryBytes).
     */
    public void registerMemoryEstimate(String modelId, long estimatedBytes) {
        memoryEstimates.put(modelId, estimatedBytes);
    }

    /**
     * Estimate memory for a model. Uses registered estimate, metadata heuristic, or default.
     */
    public long estimateMemory(String modelId) {
        Long registered = memoryEstimates.get(modelId);
        if (registered != null) return registered;
        return configService.getConfiguration().getDefaultMemoryEstimateBytes();
    }

    /**
     * Pre-check whether a model can be admitted.
     */
    public AdmissionDecision canAdmit(String modelId) {
        ModelAdmissionConfig config = configService.getConfiguration();

        if (!config.isEnabled()) {
            return new AdmissionDecision(true, "Admission control disabled, allowing all loads", 0, List.of());
        }

        // Already loaded?
        LoadedModel existing = loadedModels.get(modelId);
        if (existing != null && existing.state != ModelState.UNLOADED) {
            return new AdmissionDecision(true, "Model already loaded (state=" + existing.state + ")",
                    existing.memoryBytes, List.of());
        }

        long estimated = estimateMemory(modelId);

        // Check model count limit
        long activeCount = loadedModels.values().stream()
                .filter(m -> m.state == ModelState.GPU_HOT || m.state == ModelState.CPU_WARM || m.state == ModelState.LOADING)
                .count();

        List<String> toEvict = new ArrayList<>();

        if (activeCount >= config.getMaxLoadedModels()) {
            // Find LRU model to evict
            Optional<LoadedModel> lru = loadedModels.values().stream()
                    .filter(m -> m.state == ModelState.GPU_HOT || m.state == ModelState.CPU_WARM)
                    .min(Comparator.comparing(LoadedModel::lastUsedAt));

            if (lru.isPresent()) {
                toEvict.add(lru.get().modelId);
            } else {
                return new AdmissionDecision(false,
                        "Max loaded models (" + config.getMaxLoadedModels() + ") reached and no models available to evict",
                        estimated, List.of());
            }
        }

        // Check concurrent load limit
        if (activeLoads.get() >= config.getMaxConcurrentLoads()) {
            return new AdmissionDecision(false,
                    "Max concurrent loads (" + config.getMaxConcurrentLoads() + ") reached, try again later",
                    estimated, List.of());
        }

        return new AdmissionDecision(true,
                toEvict.isEmpty() ? "Admitted" : "Admitted, will evict: " + toEvict,
                estimated, toEvict);
    }

    /**
     * Request a model to be loaded asynchronously.
     */
    public CompletableFuture<LoadedModel> requestLoad(String modelId) {
        AdmissionDecision decision = canAdmit(modelId);
        if (!decision.admitted()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Model not admitted: " + decision.reason()));
        }

        // Evict models if needed
        for (String evictId : decision.modelsToEvict()) {
            unload(evictId);
        }

        long estimated = decision.estimatedMemoryBytes();
        LoadedModel loading = new LoadedModel(modelId, ModelState.LOADING, null,
                estimated, Instant.now(), Instant.now());
        loadedModels.put(modelId, loading);
        activeLoads.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // The actual model loading is handled by ModelLifecycleManager
                // Here we just track the state transition
                LoadedModel loaded = new LoadedModel(modelId, ModelState.GPU_HOT, "gpu:0",
                        estimated, Instant.now(), Instant.now());
                loadedModels.put(modelId, loaded);
                log.info("Model '{}' admitted and loaded (estimated {}MB)",
                        modelId, estimated / (1024 * 1024));
                return loaded;
            } finally {
                activeLoads.decrementAndGet();
            }
        }, loadExecutor);
    }

    /**
     * Mark a model as used (updates lastUsedAt for LRU eviction).
     */
    public void touch(String modelId) {
        LoadedModel existing = loadedModels.get(modelId);
        if (existing != null) {
            loadedModels.put(modelId, new LoadedModel(existing.modelId, existing.state,
                    existing.device, existing.memoryBytes, existing.loadedAt, Instant.now()));
        }
    }

    /**
     * Demote a model from GPU to CPU warm state.
     */
    public void demoteToCpu(String modelId) {
        LoadedModel existing = loadedModels.get(modelId);
        if (existing != null && existing.state == ModelState.GPU_HOT) {
            loadedModels.put(modelId, new LoadedModel(existing.modelId, ModelState.CPU_WARM,
                    "cpu", existing.memoryBytes, existing.loadedAt, existing.lastUsedAt));
            log.info("Model '{}' demoted to CPU_WARM", modelId);
        }
    }

    /**
     * Promote a model from CPU warm state to GPU hot.
     */
    public void promoteToGpu(String modelId) {
        LoadedModel existing = loadedModels.get(modelId);
        if (existing != null && existing.state == ModelState.CPU_WARM) {
            loadedModels.put(modelId, new LoadedModel(existing.modelId, ModelState.GPU_HOT,
                    "gpu:0", existing.memoryBytes, existing.loadedAt, Instant.now()));
            log.info("Model '{}' promoted to GPU_HOT", modelId);
        }
    }

    /**
     * Unload a model entirely.
     */
    public void unload(String modelId) {
        LoadedModel removed = loadedModels.remove(modelId);
        if (removed != null) {
            log.info("Model '{}' unloaded (was {} using {}MB)",
                    modelId, removed.state, removed.memoryBytes / (1024 * 1024));
        }
    }

    /**
     * Get all loaded models.
     */
    public List<LoadedModel> getLoadedModels() {
        return new ArrayList<>(loadedModels.values());
    }

    /**
     * Get status for monitoring.
     */
    public Map<String, Object> getStatus() {
        ModelAdmissionConfig config = configService.getConfiguration();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("maxLoadedModels", config.getMaxLoadedModels());
        status.put("maxConcurrentLoads", config.getMaxConcurrentLoads());
        status.put("memoryReserveBytes", config.getMemoryReserveBytes());
        status.put("activeLoads", activeLoads.get());

        long totalMemory = 0;
        List<Map<String, Object>> models = new ArrayList<>();
        for (var model : loadedModels.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelId", model.modelId);
            m.put("state", model.state.name());
            m.put("device", model.device);
            m.put("memoryBytes", model.memoryBytes);
            m.put("memoryMB", model.memoryBytes / (1024 * 1024));
            m.put("loadedAt", model.loadedAt.toString());
            m.put("lastUsedAt", model.lastUsedAt.toString());
            models.add(m);
            if (model.state == ModelState.GPU_HOT) {
                totalMemory += model.memoryBytes;
            }
        }
        status.put("models", models);
        status.put("totalGpuMemoryUsedBytes", totalMemory);
        status.put("totalModelsLoaded", loadedModels.size());

        Map<String, Long> stateCounts = new LinkedHashMap<>();
        for (ModelState s : ModelState.values()) {
            stateCounts.put(s.name(), loadedModels.values().stream()
                    .filter(m -> m.state == s).count());
        }
        status.put("stateCounts", stateCounts);

        return status;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ModelAdmissionController");
        loadExecutor.shutdownNow();
    }
}
