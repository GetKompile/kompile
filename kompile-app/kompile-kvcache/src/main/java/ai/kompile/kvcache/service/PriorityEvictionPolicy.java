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

package ai.kompile.kvcache.service;

import ai.kompile.kvcache.model.BlockPriority;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Priority-based eviction policy for KV cache blocks.
 * Blocks with lower priority are evicted first; within the same priority tier,
 * least-recently-accessed blocks are evicted first.
 */
@Slf4j
public class PriorityEvictionPolicy {

    private final ConcurrentHashMap<Integer, BlockPriority> blockPriorities = new ConcurrentHashMap<>();
    private final int defaultPriority;

    public PriorityEvictionPolicy(int defaultPriority) {
        this.defaultPriority = defaultPriority;
    }

    public PriorityEvictionPolicy() {
        this(BlockPriority.USER_CONTEXT);
    }

    /**
     * Set priority for a sequence/block.
     */
    public void setPriority(int seqIdx, int priority) {
        blockPriorities.compute(seqIdx, (k, existing) -> {
            long lastAccessed = existing != null ? existing.lastAccessedMs() : System.currentTimeMillis();
            return new BlockPriority(priority, lastAccessed);
        });
    }

    /**
     * Touch a block to update its last-accessed time.
     */
    public void touchBlock(int blockId) {
        blockPriorities.computeIfPresent(blockId, (k, existing) -> existing.touch());
    }

    /**
     * Register a block with default priority.
     */
    public void registerBlock(int blockId) {
        blockPriorities.putIfAbsent(blockId, BlockPriority.withPriority(defaultPriority));
    }

    /**
     * Remove tracking for a freed block.
     */
    public void removeBlock(int blockId) {
        blockPriorities.remove(blockId);
    }

    /**
     * Get the eviction order: blocks sorted by eviction priority
     * (lowest priority first, oldest within same priority first).
     *
     * @param numBlocksNeeded number of blocks to free
     * @return list of block IDs to evict, in eviction order
     */
    public List<Integer> getEvictionOrder(int numBlocksNeeded) {
        List<Map.Entry<Integer, BlockPriority>> entries = new ArrayList<>(blockPriorities.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getValue));

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < Math.min(numBlocksNeeded, entries.size()); i++) {
            result.add(entries.get(i).getKey());
        }
        return result;
    }

    /**
     * Get the full eviction order (all blocks, sorted).
     */
    public List<Integer> getFullEvictionOrder() {
        return getEvictionOrder(blockPriorities.size());
    }

    /**
     * Get priority for a specific block.
     */
    public BlockPriority getBlockPriority(int blockId) {
        return blockPriorities.getOrDefault(blockId, BlockPriority.withPriority(defaultPriority));
    }

    /**
     * Get the number of tracked blocks.
     */
    public int size() {
        return blockPriorities.size();
    }

    /**
     * Get a snapshot of all block priorities for monitoring.
     */
    public Map<Integer, BlockPriority> getAllPriorities() {
        return Map.copyOf(blockPriorities);
    }

    /**
     * Get counts by priority tier.
     */
    public Map<String, Long> getTierCounts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        long systemPrompt = blockPriorities.values().stream()
                .filter(p -> p.priority() >= BlockPriority.SYSTEM_PROMPT).count();
        long cachedPrefix = blockPriorities.values().stream()
                .filter(p -> p.priority() >= BlockPriority.CACHED_PREFIX && p.priority() < BlockPriority.SYSTEM_PROMPT).count();
        long userContext = blockPriorities.values().stream()
                .filter(p -> p.priority() >= BlockPriority.USER_CONTEXT && p.priority() < BlockPriority.CACHED_PREFIX).count();
        long evictable = blockPriorities.values().stream()
                .filter(p -> p.priority() < BlockPriority.USER_CONTEXT).count();

        counts.put("systemPrompt", systemPrompt);
        counts.put("cachedPrefix", cachedPrefix);
        counts.put("userContext", userContext);
        counts.put("evictable", evictable);
        return counts;
    }
}
