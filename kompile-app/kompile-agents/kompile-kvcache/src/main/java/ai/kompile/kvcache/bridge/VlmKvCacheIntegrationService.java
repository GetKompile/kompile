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

package ai.kompile.kvcache.bridge;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.KVCacheConfig;
import ai.kompile.kvcache.service.KVCacheManager;
import ai.kompile.kvcache.service.KVCacheStatisticsCollector;
import ai.kompile.kvcache.service.ManagedKVCache;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.ModelIOConfig;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheManager;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.kvcache.UnifiedKvCacheManager;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that creates and manages persistent KV caches for VLM models, bridging
 * kompile's KV cache management infrastructure with DL4J's decode loop.
 *
 * <p>Each VLM model (identified by modelId) gets its own persistent KV cache that:</p>
 * <ul>
 *   <li>Is visible in the KV cache browser UI via the REST API at {@code /api/kvcache}</li>
 *   <li>Has live statistics (appends, memory usage, active sequences)</li>
 *   <li>Survives across inference calls (the same {@link KompileKvCacheAdapter} is reused)</li>
 *   <li>Can be monitored, checkpointed, and managed through the Developer Hub</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <p>This service is injected into the VLM pipeline. When a VLM model loads and
 * {@code kompile.kvcache.enabled=true}, the pipeline calls
 * {@link #getOrCreateKvCacheManager(String)} to obtain a DL4J-compatible
 * {@link KvCacheManager} that also feeds into kompile's monitoring.</p>
 *
 * <h3>Cache Naming Convention</h3>
 * <p>Caches are named {@code vlm:<modelId>} (e.g., {@code vlm:smoldocling-256m}).
 * This naming makes them identifiable in the UI as VLM-associated caches.</p>
 *
 * <h3>Dual Registration</h3>
 * <p>The adapter is registered in two places:</p>
 * <ol>
 *   <li>Kompile's {@link KVCacheManager}: Creates a {@link ManagedKVCache} so the
 *       cache appears in REST API listings with proper config/stats</li>
 *   <li>Kompile's {@link KVCacheStatisticsCollector}: The adapter directly records
 *       append/scatter events for live statistics</li>
 * </ol>
 *
 * @see KompileKvCacheAdapter
 * @see KVCacheManager
 */
@Slf4j
public class VlmKvCacheIntegrationService {

    private final KVCacheProperties properties;
    private final KVCacheManager kompileCacheManager;
    private final KVCacheStatisticsCollector statsCollector;

    /** Persistent adapters keyed by model ID. Reused across inference calls. */
    private final ConcurrentHashMap<String, KompileKvCacheAdapter> adapters = new ConcurrentHashMap<>();

    /** Managed caches registered with kompile for UI visibility, keyed by cache name. */
    private final ConcurrentHashMap<String, ManagedKVCache> managedCaches = new ConcurrentHashMap<>();

    public VlmKvCacheIntegrationService(KVCacheProperties properties,
                                         KVCacheManager kompileCacheManager,
                                         KVCacheStatisticsCollector statsCollector) {
        this.properties = properties;
        this.kompileCacheManager = kompileCacheManager;
        this.statsCollector = statsCollector;
        log.info("VlmKvCacheIntegrationService initialized (kvcache.enabled={})", properties.isEnabled());
    }

    /**
     * Returns whether the KV cache integration is enabled and available.
     *
     * @return true if kompile.kvcache.enabled=true
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * Gets or creates a persistent DL4J-compatible KvCacheManager for the given VLM model
     * using the default STATIC strategy.
     *
     * @param modelId VLM model identifier (e.g., "smoldocling-256m")
     * @return a KvCacheManager that can be set as externalKvCacheManager on VisionLanguageModel
     * @throws IllegalStateException if KV cache is not enabled
     */
    public KvCacheManager getOrCreateKvCacheManager(String modelId) {
        return getOrCreateKvCacheManager(modelId, KvCacheStrategy.STATIC);
    }

    /**
     * Gets or creates a persistent DL4J-compatible KvCacheManager for the given VLM model
     * with the specified cache strategy.
     *
     * <p>On first call for a model ID, this creates:</p>
     * <ol>
     *   <li>A {@link ManagedKVCache} registered with kompile's cache manager (for REST API/UI)</li>
     *   <li>A {@link KompileKvCacheAdapter} wrapping the appropriate DL4J KvCacheManager
     *       implementation based on the strategy (Static, Quantized, etc.)</li>
     * </ol>
     *
     * <p>On subsequent calls, returns the existing adapter. The DL4J decode loop will
     * re-initialize the adapter's internal buffers on each prefill, but the adapter
     * itself and its kompile registration persist.</p>
     *
     * @param modelId  VLM model identifier (e.g., "smoldocling-256m")
     * @param strategy the KV cache strategy to use (STATIC, QUANTIZED, PAGED)
     * @return a KvCacheManager that can be set as externalKvCacheManager on VisionLanguageModel
     * @throws IllegalStateException if KV cache is not enabled
     */
    public KvCacheManager getOrCreateKvCacheManager(String modelId, KvCacheStrategy strategy) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "KV cache is disabled. Enable it via kompile.kvcache.enabled=true or the Configuration UI.");
        }

        return adapters.computeIfAbsent(modelId, id -> {
            String cacheName = toCacheName(id);
            log.info("Creating persistent KV cache for VLM model '{}' -> cache name '{}', strategy={}",
                    id, cacheName, strategy);

            // Create a ManagedKVCache in kompile's manager so it shows up in the REST API.
            KVCacheConfig config = KVCacheConfig.builder()
                    .type(properties.getDefaultType())
                    .blockSize(properties.getBlockSize())
                    .maxBatchSize(1) // VLM uses single-sequence decode
                    .maxSeqLen(properties.getMaxSeqLen())
                    .numKvHeads(properties.getNumKvHeads())
                    .headDim(properties.getHeadDim())
                    .dataType(properties.getDataType())
                    .poolSizeFactor(properties.getPoolSizeFactor())
                    .build();

            // Register managed cache with kompile for UI visibility.
            try {
                ManagedKVCache managedCache = kompileCacheManager.createCache(cacheName, config);
                managedCaches.put(cacheName, managedCache);
                log.info("Registered ManagedKVCache '{}' for VLM model '{}'", cacheName, id);
            } catch (IllegalArgumentException e) {
                log.info("ManagedKVCache '{}' already exists, reusing for model '{}'", cacheName, id);
                ManagedKVCache existing = kompileCacheManager.getCache(cacheName);
                if (existing != null) {
                    managedCaches.put(cacheName, existing);
                }
            }

            // Create the DL4J KvCacheManager based on the requested strategy
            KvCacheManager innerManager = createInnerKvCacheManager(strategy);

            // Wrap in adapter that feeds statistics to kompile
            KompileKvCacheAdapter adapter = new KompileKvCacheAdapter(
                    cacheName, id, innerManager, statsCollector);

            log.info("Created KompileKvCacheAdapter for VLM model '{}' (cache='{}', strategy={})",
                    id, cacheName, strategy);
            return adapter;
        });
    }

    /**
     * Creates the appropriate DL4J KvCacheManager based on the requested strategy.
     *
     * @param strategy the KV cache strategy
     * @return a new KvCacheManager instance
     */
    private KvCacheManager createInnerKvCacheManager(KvCacheStrategy strategy) {
        log.info("Creating UnifiedKvCacheManager with strategy={}", strategy);
        return new UnifiedKvCacheManager(strategy, ModelIOConfig.builder().build());
    }

    /**
     * Removes and closes the persistent KV cache for a model.
     * Called when a VLM model is unloaded.
     *
     * @param modelId VLM model identifier
     */
    public void destroyKvCacheManager(String modelId) {
        String cacheName = toCacheName(modelId);
        KompileKvCacheAdapter adapter = adapters.remove(modelId);
        if (adapter != null) {
            adapter.close();
            log.info("Closed KompileKvCacheAdapter for model '{}'", modelId);
        }

        // Remove from kompile's managed caches
        managedCaches.remove(cacheName);
        try {
            kompileCacheManager.destroyCache(cacheName);
            log.info("Destroyed ManagedKVCache '{}' for model '{}'", cacheName, modelId);
        } catch (Exception e) {
            log.debug("Could not destroy ManagedKVCache '{}': {}", cacheName, e.getMessage());
        }
    }

    /**
     * Checks if a persistent KV cache already exists for the given model.
     *
     * @param modelId VLM model identifier
     * @return true if a cache adapter exists for this model
     */
    public boolean hasKvCacheManager(String modelId) {
        return adapters.containsKey(modelId);
    }

    /**
     * Gets the adapter for a model (or null if not created yet).
     *
     * @param modelId VLM model identifier
     * @return the adapter, or null
     */
    public KompileKvCacheAdapter getAdapter(String modelId) {
        return adapters.get(modelId);
    }

    /**
     * Converts a model ID to the kompile cache name.
     * Convention: {@code vlm:<modelId>}
     */
    static String toCacheName(String modelId) {
        return "vlm:" + modelId;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down VlmKvCacheIntegrationService, closing {} adapters", adapters.size());
        for (var entry : adapters.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing adapter for model '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        adapters.clear();

        // Managed caches will be cleaned up by KVCacheManager.shutdown()
        managedCaches.clear();
    }
}
