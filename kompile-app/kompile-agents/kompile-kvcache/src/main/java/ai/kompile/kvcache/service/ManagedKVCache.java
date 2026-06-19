package ai.kompile.kvcache.service;

import ai.kompile.kvcache.model.KVCacheConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.ModelIOConfig;
import org.eclipse.deeplearning4j.llm.generation.kvcache.*;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ManagedKVCache implements AutoCloseable {

    @Getter
    private final String name;
    @Getter
    private final String type;
    @Getter
    private final long createdAt;
    @Getter
    private final KVCacheConfig config;

    private final KVCacheStatisticsCollector statsCollector;

    // Priority eviction integration (may be null if not enabled)
    @Getter
    private PriorityEvictionPolicy priorityEvictionPolicy;

    // Content-hash prefix index integration (may be null if not enabled)
    @Getter
    private ContentHashPrefixIndex contentHashPrefixIndex;

    // Only one of these will be non-null based on cache type
    private PagedKVCache pagedCache;
    private EvictablePagedKVCache evictableCache;
    private QuantizedPagedKVCache quantizedCache;
    private MLAKVCache mlaCache;
    private PerLayerPagedKVCache perLayerCache;
    private UnifiedKvCacheManager turboQuantCache;

    // Track tokens appended per sequence for prefix indexing
    private final Map<Integer, java.util.List<int[]>> sequenceTokenHistory = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int activeSequenceCount = 0;

    public ManagedKVCache(String name, KVCacheConfig config, KVCacheStatisticsCollector statsCollector) {
        this(name, config, statsCollector, null, null);
    }

    public ManagedKVCache(String name, KVCacheConfig config, KVCacheStatisticsCollector statsCollector,
                          PriorityEvictionPolicy priorityEvictionPolicy,
                          ContentHashPrefixIndex contentHashPrefixIndex) {
        this.name = name;
        this.type = config.getType() != null ? config.getType() : "paged";
        this.createdAt = System.currentTimeMillis();
        this.config = config;
        this.statsCollector = statsCollector;
        this.priorityEvictionPolicy = priorityEvictionPolicy;
        this.contentHashPrefixIndex = contentHashPrefixIndex;

        int blockSize = config.getBlockSize() != null ? config.getBlockSize() : 64;
        int maxBatch = config.getMaxBatchSize() != null ? config.getMaxBatchSize() : 8;
        int maxSeq = config.getMaxSeqLen() != null ? config.getMaxSeqLen() : 4096;
        int kvHeads = config.getNumKvHeads() != null ? config.getNumKvHeads() : 32;
        int hDim = config.getHeadDim() != null ? config.getHeadDim() : 128;
        DataType dt = parseDataType(config.getDataType());
        double poolFactor = config.getPoolSizeFactor() != null ? config.getPoolSizeFactor() : 1.2;

        switch (this.type.toLowerCase()) {
            case "evictable":
                int budget = config.getTokenBudget() != null ? config.getTokenBudget() : 2048;
                evictableCache = new EvictablePagedKVCache(maxBatch, maxSeq, kvHeads, hDim, blockSize, dt, poolFactor, budget);
                String policyName = config.getEvictionPolicy() != null ? config.getEvictionPolicy() : "h2o";
                if ("streamingllm".equalsIgnoreCase(policyName)) {
                    evictableCache.setEvictionPolicy(new StreamingLLMEvictionPolicy(4, budget));
                } else {
                    evictableCache.setEvictionPolicy(new H2OEvictionPolicy(maxSeq, 1, budget / 2, budget / 4));
                }
                break;
            case "quantized":
                QuantizedPagedKVCache.QuantFormat qf = parseQuantFormat(config.getQuantFormat());
                quantizedCache = new QuantizedPagedKVCache(maxBatch, maxSeq, kvHeads, hDim, qf, blockSize, dt, poolFactor);
                break;
            case "mla":
                mlaCache = new MLAKVCache(maxBatch, maxSeq, hDim, hDim / 2, kvHeads, dt);
                break;
            case "per-layer":
                PerLayerKVPolicy policy = PerLayerKVPolicy.uniform(kvHeads, maxSeq, blockSize, dt);
                perLayerCache = new PerLayerPagedKVCache(policy, maxBatch, kvHeads, hDim, blockSize);
                break;
            case "turboquant":
                turboQuantCache = new UnifiedKvCacheManager(KvCacheStrategy.TURBOQUANT, ModelIOConfig.builder().build());
                break;
            default: // "paged"
                pagedCache = new PagedKVCache(maxBatch, maxSeq, kvHeads, hDim, blockSize, dt, poolFactor);
                break;
        }

        // Register with stats collector
        statsCollector.registerCache(name, this::getMemoryUsageBytes, this::getActiveSequenceCount);
    }

    public void append(int seqIdx, INDArray newKeys, INDArray newValues) {
        if (pagedCache != null) pagedCache.append(seqIdx, newKeys, newValues);
        else if (evictableCache != null) evictableCache.append(seqIdx, newKeys, newValues);
        else if (quantizedCache != null) quantizedCache.append(seqIdx, newKeys, newValues);

        // Touch priority tracking on append
        if (priorityEvictionPolicy != null) {
            priorityEvictionPolicy.touchBlock(seqIdx);
        }

        statsCollector.recordAppend(name);
        statsCollector.getCounters(name).appendsSinceLastSample.incrementAndGet();
    }

    /**
     * Append with token IDs for prefix indexing support.
     * When a block's worth of tokens accumulates, notifies the ContentHashPrefixIndex.
     */
    public void append(int seqIdx, INDArray newKeys, INDArray newValues, int[] tokenIds) {
        append(seqIdx, newKeys, newValues);

        // Track tokens for content-hash prefix indexing
        if (contentHashPrefixIndex != null && tokenIds != null) {
            int blockSize = config.getBlockSize() != null ? config.getBlockSize() : 64;
            sequenceTokenHistory.computeIfAbsent(seqIdx, k -> new java.util.ArrayList<>());
            var history = sequenceTokenHistory.get(seqIdx);
            history.add(tokenIds);

            // Check if we've accumulated a full block
            int totalTokens = history.stream().mapToInt(arr -> arr.length).sum();
            if (totalTokens >= blockSize) {
                // Flatten and notify for each block-sized chunk
                int[] allTokens = history.stream()
                        .flatMapToInt(java.util.Arrays::stream)
                        .toArray();
                int blocksFilled = totalTokens / blockSize;
                for (int b = 0; b < blocksFilled; b++) {
                    int[] blockTokens = java.util.Arrays.copyOfRange(allTokens, b * blockSize, (b + 1) * blockSize);
                    int blockId = seqIdx * 1000 + b; // synthetic block ID
                    contentHashPrefixIndex.onBlockFilled(blockId, blockTokens);
                }
                // Keep remainder
                int consumed = blocksFilled * blockSize;
                history.clear();
                if (consumed < allTokens.length) {
                    history.add(java.util.Arrays.copyOfRange(allTokens, consumed, allTokens.length));
                }
            }
        }
    }

    public void freeSequence(int seqIdx) {
        if (pagedCache != null) pagedCache.freeSequence(seqIdx);
        else if (evictableCache != null) evictableCache.freeSequence(seqIdx);
        else if (quantizedCache != null) quantizedCache.freeSequence(seqIdx);
        else if (perLayerCache != null) perLayerCache.freeSequence(seqIdx);

        // Clean up priority tracking
        if (priorityEvictionPolicy != null) {
            priorityEvictionPolicy.removeBlock(seqIdx);
        }

        // Clean up prefix index entries for this sequence
        if (contentHashPrefixIndex != null) {
            var history = sequenceTokenHistory.remove(seqIdx);
            // Free synthetic block IDs (seqIdx * 1000 + blockNum)
            // We don't track exact block count, so sweep a reasonable range
            for (int b = 0; b < 256; b++) {
                contentHashPrefixIndex.onBlockFreed(seqIdx * 1000 + b);
            }
        }

        statsCollector.recordFree(name);
        if (activeSequenceCount > 0) activeSequenceCount--;
    }

    public int getSequenceLength(int seqIdx) {
        if (pagedCache != null) return pagedCache.getSequenceLength(seqIdx);
        if (evictableCache != null) return evictableCache.getSequenceLength(seqIdx);
        if (quantizedCache != null) return quantizedCache.getSequenceLength(seqIdx);
        return 0;
    }

    public long getMemoryUsageBytes() {
        // TurboQuant tracks memory via its own buffer maps
        if (turboQuantCache != null) {
            Map<String, INDArray> buffers = turboQuantCache.getStaticKvBuffers();
            if (buffers == null || buffers.isEmpty()) return 0;
            long total = 0;
            for (INDArray buf : buffers.values()) {
                if (buf != null && !buf.wasClosed()) {
                    total += buf.length() * buf.dataType().width();
                }
            }
            return total;
        }
        // Estimate memory based on allocated blocks
        int blocks = getTotalBlocks() - getFreeBlocks();
        int blockSize = config.getBlockSize() != null ? config.getBlockSize() : 64;
        int kvHeads = config.getNumKvHeads() != null ? config.getNumKvHeads() : 32;
        int hDim = config.getHeadDim() != null ? config.getHeadDim() : 128;
        // 2 buffers (K + V) * heads * headDim * blockSize * 4 bytes (float)
        long bytesPerBlock = 2L * kvHeads * hDim * blockSize * 4;
        return blocks * bytesPerBlock;
    }

    public int getActiveSequenceCount() {
        return activeSequenceCount;
    }

    public int getFreeBlocks() {
        if (pagedCache != null) return pagedCache.getNumFreeBlocks();
        if (evictableCache != null) return evictableCache.getNumFreeBlocks();
        if (quantizedCache != null) return quantizedCache.getNumFreeBlocks();
        if (perLayerCache != null) return perLayerCache.getTotalFreeBlocks();
        return 0;
    }

    public int getTotalBlocks() {
        if (pagedCache != null) return pagedCache.getNumBlocks();
        if (evictableCache != null) return evictableCache.getNumBlocks();
        if (quantizedCache != null) return quantizedCache.getNumBlocks();
        if (perLayerCache != null) return perLayerCache.getTotalBlocks();
        return 0;
    }

    public Map<Integer, Integer> getPerSequenceTokenCounts() {
        Map<Integer, Integer> counts = new HashMap<>();
        int maxBatch = config.getMaxBatchSize() != null ? config.getMaxBatchSize() : 8;
        for (int i = 0; i < maxBatch; i++) {
            int len = getSequenceLength(i);
            if (len > 0) {
                counts.put(i, len);
            }
        }
        return counts;
    }

    public PagedKVCache getPagedCache() {
        if (pagedCache != null) return pagedCache;
        if (evictableCache != null) return evictableCache;
        return null;
    }

    /**
     * Returns the TurboQuant KV cache manager if this cache is of type "turboquant".
     */
    public UnifiedKvCacheManager getTurboQuantCache() {
        return turboQuantCache;
    }

    @Override
    public void close() {
        statsCollector.unregisterCache(name);
        try {
            if (pagedCache != null) pagedCache.close();
            if (evictableCache != null) evictableCache.close();
            if (quantizedCache != null) quantizedCache.close();
            if (mlaCache != null) mlaCache.close();
            if (perLayerCache != null) perLayerCache.close();
            if (turboQuantCache != null) turboQuantCache.close();
        } catch (Exception e) {
            log.warn("Error closing cache {}: {}", name, e.getMessage());
        }
    }

    private static DataType parseDataType(String dt) {
        if (dt == null) return DataType.FLOAT;
        try {
            return DataType.valueOf(dt.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DataType.FLOAT;
        }
    }

    private static QuantizedPagedKVCache.QuantFormat parseQuantFormat(String qf) {
        if (qf == null) return QuantizedPagedKVCache.QuantFormat.INT8;
        try {
            return QuantizedPagedKVCache.QuantFormat.valueOf(qf.toUpperCase());
        } catch (IllegalArgumentException e) {
            return QuantizedPagedKVCache.QuantFormat.INT8;
        }
    }
}
