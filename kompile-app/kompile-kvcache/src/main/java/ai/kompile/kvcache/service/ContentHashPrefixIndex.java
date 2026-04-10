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

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-hash index for O(1) prefix block matching across requests.
 * Complements the RadixPrefixCache by indexing block content hashes
 * for fast cross-request KV reuse with shared prompts.
 */
@Slf4j
public class ContentHashPrefixIndex {

    public record PrefixMatchResult(int matchedTokens, int[] matchedBlockIds, int[] remainingTokens) {
        public boolean hasMatch() { return matchedTokens > 0; }
    }

    /** hash of block-size token chunk -> list of block IDs containing those tokens */
    private final ConcurrentHashMap<Long, List<Integer>> hashToBlocks = new ConcurrentHashMap<>();

    /** block ID -> hash, for cleanup on free */
    private final ConcurrentHashMap<Integer, Long> blockToHash = new ConcurrentHashMap<>();

    private final int blockSize;
    private final int maxEntries;
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalBlocksIndexed = new AtomicLong();

    public ContentHashPrefixIndex(int blockSize, int maxEntries) {
        this.blockSize = blockSize;
        this.maxEntries = maxEntries;
        log.info("ContentHashPrefixIndex initialized (blockSize={}, maxEntries={})", blockSize, maxEntries);
    }

    /**
     * Called when a block is fully filled with tokens during append.
     * Indexes the block by its content hash for future lookups.
     */
    public void onBlockFilled(int blockId, int[] tokenIds) {
        if (hashToBlocks.size() >= maxEntries) {
            // Evict oldest entry (simple FIFO via first key)
            var firstKey = hashToBlocks.keys().nextElement();
            List<Integer> removed = hashToBlocks.remove(firstKey);
            if (removed != null) {
                for (int bid : removed) blockToHash.remove(bid);
            }
        }

        long hash = computeHash(tokenIds);
        hashToBlocks.computeIfAbsent(hash, k -> Collections.synchronizedList(new ArrayList<>())).add(blockId);
        blockToHash.put(blockId, hash);
        totalBlocksIndexed.incrementAndGet();
    }

    /**
     * Called when a block is freed (sequence freed or evicted).
     * Removes the block from the index.
     */
    public void onBlockFreed(int blockId) {
        Long hash = blockToHash.remove(blockId);
        if (hash != null) {
            List<Integer> blocks = hashToBlocks.get(hash);
            if (blocks != null) {
                blocks.remove(Integer.valueOf(blockId));
                if (blocks.isEmpty()) {
                    hashToBlocks.remove(hash);
                }
            }
        }
    }

    /**
     * Find cached prefix blocks matching the given token sequence.
     * Scans through blockSize-aligned chunks looking for hash matches.
     */
    public PrefixMatchResult findCachedPrefix(int[] tokenIds) {
        totalLookups.incrementAndGet();

        if (tokenIds == null || tokenIds.length < blockSize) {
            return new PrefixMatchResult(0, new int[0], tokenIds != null ? tokenIds : new int[0]);
        }

        List<Integer> matchedBlocks = new ArrayList<>();
        int matchedTokens = 0;

        for (int offset = 0; offset + blockSize <= tokenIds.length; offset += blockSize) {
            int[] chunk = Arrays.copyOfRange(tokenIds, offset, offset + blockSize);
            long hash = computeHash(chunk);
            List<Integer> candidates = hashToBlocks.get(hash);

            if (candidates != null && !candidates.isEmpty()) {
                matchedBlocks.add(candidates.get(0)); // Use first matching block
                matchedTokens += blockSize;
            } else {
                break; // Prefix must be contiguous
            }
        }

        if (matchedTokens > 0) {
            totalHits.incrementAndGet();
        }

        int[] remaining = matchedTokens < tokenIds.length
                ? Arrays.copyOfRange(tokenIds, matchedTokens, tokenIds.length)
                : new int[0];

        return new PrefixMatchResult(
                matchedTokens,
                matchedBlocks.stream().mapToInt(Integer::intValue).toArray(),
                remaining);
    }

    /**
     * Get statistics for monitoring.
     */
    public Map<String, Object> getStats() {
        long lookups = totalLookups.get();
        long hits = totalHits.get();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("indexSize", hashToBlocks.size());
        stats.put("maxEntries", maxEntries);
        stats.put("totalBlocksIndexed", totalBlocksIndexed.get());
        stats.put("totalLookups", lookups);
        stats.put("totalHits", hits);
        stats.put("hitRate", lookups > 0 ? (double) hits / lookups : 0.0);
        stats.put("blockSize", blockSize);
        return stats;
    }

    public int size() {
        return hashToBlocks.size();
    }

    private long computeHash(int[] tokenIds) {
        return Arrays.hashCode(tokenIds);
    }
}
