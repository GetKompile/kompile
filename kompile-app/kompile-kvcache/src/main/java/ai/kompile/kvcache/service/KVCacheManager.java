package ai.kompile.kvcache.service;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.KVCacheConfig;
import ai.kompile.kvcache.model.KVCacheStats;
import ai.kompile.kvcache.model.KVCacheSummary;
import ai.kompile.kvcache.model.StatsSample;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class KVCacheManager {

    private final KVCacheProperties properties;
    private final KVCacheStatisticsCollector statsCollector;
    private final PriorityEvictionPolicy priorityEvictionPolicy;
    private final ContentHashPrefixIndex contentHashPrefixIndex;
    private final ConcurrentHashMap<String, ManagedKVCache> caches = new ConcurrentHashMap<>();

    public KVCacheManager(KVCacheProperties properties, KVCacheStatisticsCollector statsCollector) {
        this(properties, statsCollector, null, null);
    }

    public KVCacheManager(KVCacheProperties properties, KVCacheStatisticsCollector statsCollector,
                          PriorityEvictionPolicy priorityEvictionPolicy,
                          ContentHashPrefixIndex contentHashPrefixIndex) {
        this.properties = properties;
        this.statsCollector = statsCollector;
        this.priorityEvictionPolicy = priorityEvictionPolicy;
        this.contentHashPrefixIndex = contentHashPrefixIndex;
        log.info("KVCacheManager initialized (enabled={}, defaultType={}, blockSize={}, maxBatchSize={})",
                properties.isEnabled(), properties.getDefaultType(), properties.getBlockSize(), properties.getMaxBatchSize());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public ManagedKVCache createCache(String name, KVCacheConfig config) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("KV Cache is disabled. Enable it first via the configuration panel.");
        }
        if (caches.containsKey(name)) {
            throw new IllegalArgumentException("Cache with name '" + name + "' already exists");
        }

        // Fill in defaults from properties
        KVCacheConfig resolved = KVCacheConfig.builder()
                .type(config.getType() != null ? config.getType() : properties.getDefaultType())
                .blockSize(config.getBlockSize() != null ? config.getBlockSize() : properties.getBlockSize())
                .maxBatchSize(config.getMaxBatchSize() != null ? config.getMaxBatchSize() : properties.getMaxBatchSize())
                .maxSeqLen(config.getMaxSeqLen() != null ? config.getMaxSeqLen() : properties.getMaxSeqLen())
                .numKvHeads(config.getNumKvHeads() != null ? config.getNumKvHeads() : properties.getNumKvHeads())
                .headDim(config.getHeadDim() != null ? config.getHeadDim() : properties.getHeadDim())
                .dataType(config.getDataType() != null ? config.getDataType() : properties.getDataType())
                .poolSizeFactor(config.getPoolSizeFactor() != null ? config.getPoolSizeFactor() : properties.getPoolSizeFactor())
                .evictionPolicy(config.getEvictionPolicy() != null ? config.getEvictionPolicy() : properties.getEvictionPolicy())
                .tokenBudget(config.getTokenBudget() != null ? config.getTokenBudget() : properties.getTokenBudget())
                .quantFormat(config.getQuantFormat() != null ? config.getQuantFormat() : properties.getQuantFormat())
                .tieredEnabled(config.getTieredEnabled() != null ? config.getTieredEnabled() : properties.isTieredEnabled())
                .gpuPressureThreshold(config.getGpuPressureThreshold() != null ? config.getGpuPressureThreshold() : properties.getGpuPressureThreshold())
                .hostPoolMaxBlocks(config.getHostPoolMaxBlocks() != null ? config.getHostPoolMaxBlocks() : properties.getHostPoolMaxBlocks())
                .diskOffloadPath(config.getDiskOffloadPath() != null ? config.getDiskOffloadPath() : properties.getDiskOffloadPath())
                .build();

        ManagedKVCache cache = new ManagedKVCache(name, resolved, statsCollector,
                properties.isPriorityEvictionEnabled() ? priorityEvictionPolicy : null,
                properties.isPrefixHashEnabled() ? contentHashPrefixIndex : null);
        caches.put(name, cache);
        log.info("Created KV cache '{}' (type={}, blocks={})", name, resolved.getType(), cache.getTotalBlocks());
        return cache;
    }

    public ManagedKVCache getCache(String name) {
        return caches.get(name);
    }

    public void destroyCache(String name) {
        ManagedKVCache cache = caches.remove(name);
        if (cache != null) {
            cache.close();
            log.info("Destroyed KV cache '{}'", name);
        }
    }

    public List<KVCacheSummary> listCaches() {
        List<KVCacheSummary> summaries = new ArrayList<>();
        for (var entry : caches.entrySet()) {
            ManagedKVCache c = entry.getValue();
            summaries.add(KVCacheSummary.builder()
                    .name(c.getName())
                    .type(c.getType())
                    .createdAt(c.getCreatedAt())
                    .memoryUsageBytes(c.getMemoryUsageBytes())
                    .activeSequences(c.getActiveSequenceCount())
                    .freeBlocks(c.getFreeBlocks())
                    .totalBlocks(c.getTotalBlocks())
                    .build());
        }
        return summaries;
    }

    public KVCacheStats getStats(String cacheName) {
        ManagedKVCache cache = caches.get(cacheName);
        if (cache == null) return null;

        KVCacheStatisticsCollector.CacheCounters counters = statsCollector.getCounters(cacheName);
        long appends = counters != null ? counters.appends.get() : 0;
        long evictions = counters != null ? counters.evictions.get() : 0;
        long frees = counters != null ? counters.frees.get() : 0;
        long hits = counters != null ? counters.hits.get() : 0;
        long misses = counters != null ? counters.misses.get() : 0;
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;

        long memUsed = cache.getMemoryUsageBytes();
        int totalBlocks = cache.getTotalBlocks();
        int freeBlocks = cache.getFreeBlocks();
        int blockSize = cache.getConfig().getBlockSize() != null ? cache.getConfig().getBlockSize() : 64;
        int kvHeads = cache.getConfig().getNumKvHeads() != null ? cache.getConfig().getNumKvHeads() : 32;
        int hDim = cache.getConfig().getHeadDim() != null ? cache.getConfig().getHeadDim() : 128;
        long capacityBytes = totalBlocks * 2L * kvHeads * hDim * blockSize * 4;
        double utilization = capacityBytes > 0 ? (double) memUsed / capacityBytes : 0.0;

        List<StatsSample> samples = statsCollector.getTimeSeries(cacheName, properties.getStatsWindowSeconds());

        return KVCacheStats.builder()
                .cacheName(cacheName)
                .cacheType(cache.getType())
                .totalAppends(appends)
                .totalEvictions(evictions)
                .totalFrees(frees)
                .hitCount(hits)
                .missCount(misses)
                .hitRate(hitRate)
                .memoryUsedBytes(memUsed)
                .memoryCapacityBytes(capacityBytes)
                .memoryUtilization(utilization)
                .activeSequences(cache.getActiveSequenceCount())
                .freeBlocks(freeBlocks)
                .totalBlocks(totalBlocks)
                .perSequenceTokenCounts(cache.getPerSequenceTokenCounts())
                .recentSamples(samples)
                .collectedAt(System.currentTimeMillis())
                .build();
    }

    public KVCacheStats getAggregateStats() {
        long totalAppends = 0, totalEvictions = 0, totalFrees = 0;
        long totalHits = 0, totalMisses = 0;
        long totalMemUsed = 0, totalCapacity = 0;
        int totalSeqs = 0, totalFreeBlocks = 0, totalBlocksAll = 0;

        for (var entry : caches.entrySet()) {
            KVCacheStats s = getStats(entry.getKey());
            if (s == null) continue;
            totalAppends += s.getTotalAppends();
            totalEvictions += s.getTotalEvictions();
            totalFrees += s.getTotalFrees();
            totalHits += s.getHitCount();
            totalMisses += s.getMissCount();
            totalMemUsed += s.getMemoryUsedBytes();
            totalCapacity += s.getMemoryCapacityBytes();
            totalSeqs += s.getActiveSequences();
            totalFreeBlocks += s.getFreeBlocks();
            totalBlocksAll += s.getTotalBlocks();
        }

        double hitRate = (totalHits + totalMisses) > 0 ? (double) totalHits / (totalHits + totalMisses) : 0.0;
        double utilization = totalCapacity > 0 ? (double) totalMemUsed / totalCapacity : 0.0;

        return KVCacheStats.builder()
                .cacheName("__aggregate__")
                .cacheType("aggregate")
                .totalAppends(totalAppends)
                .totalEvictions(totalEvictions)
                .totalFrees(totalFrees)
                .hitCount(totalHits)
                .missCount(totalMisses)
                .hitRate(hitRate)
                .memoryUsedBytes(totalMemUsed)
                .memoryCapacityBytes(totalCapacity)
                .memoryUtilization(utilization)
                .activeSequences(totalSeqs)
                .freeBlocks(totalFreeBlocks)
                .totalBlocks(totalBlocksAll)
                .recentSamples(Collections.emptyList())
                .collectedAt(System.currentTimeMillis())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down KVCacheManager, closing {} caches", caches.size());
        for (var entry : caches.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing cache '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        caches.clear();
    }
}
