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

package ai.kompile.modelmanager.cache;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core interface for caching pipeline outputs.
 *
 * <p>Provides content-addressable caching for pipeline stage and final outputs.
 * Implementations can be file-based (default), backed by a vector store, or use
 * any other persistence mechanism.</p>
 *
 * <h2>Cache Hierarchy</h2>
 * <pre>
 * Pipeline Output Cache
 * ├── Final Outputs         (content_hash + pipeline_id → complete result)
 * └── Stage Checkpoints     (content_hash + pipeline_id + stage_id → intermediate result)
 * </pre>
 *
 * <h2>Deduplication</h2>
 * <p>Cache keys are derived from SHA-256 content hashes combined with pipeline/stage
 * identifiers, ensuring:</p>
 * <ul>
 *   <li>Same input + same pipeline = cache hit (no reprocessing)</li>
 *   <li>Same input + different pipeline = separate cache entry</li>
 *   <li>Different input + same pipeline = separate cache entry</li>
 * </ul>
 *
 * <h2>Crash Recovery</h2>
 * <p>Stage checkpoints allow resuming a pipeline from the last completed stage
 * after a crash, rather than restarting from scratch. This is critical for
 * expensive operations like OCR (13+ seconds per page).</p>
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Thread-safe for concurrent reads and writes</li>
 *   <li>Durable across application restarts (file or database backed)</li>
 *   <li>Support eviction policies (TTL, LRU, size-based)</li>
 * </ul>
 *
 * @see PipelineCacheKey
 * @see PipelineCacheEntry
 */
public interface PipelineOutputCache {

    // ========== Core Operations ==========

    /**
     * Get a cached entry by key.
     *
     * @param key the cache key
     * @return the cached entry, or empty if not found or expired
     */
    Optional<PipelineCacheEntry> get(PipelineCacheKey key);

    /**
     * Store a cache entry.
     *
     * @param key   the cache key
     * @param entry the entry to store
     */
    void put(PipelineCacheKey key, PipelineCacheEntry entry);

    /**
     * Check if a cache entry exists for the given key.
     *
     * @param key the cache key
     * @return true if a valid (non-expired) entry exists
     */
    boolean contains(PipelineCacheKey key);

    /**
     * Remove a cache entry.
     *
     * @param key the cache key
     * @return true if an entry was removed
     */
    boolean remove(PipelineCacheKey key);

    // ========== Batch Operations ==========

    /**
     * Get all stage checkpoints for a given content hash and pipeline.
     * Useful for crash recovery to find the last completed stage.
     *
     * @param contentHash the content hash of the input
     * @param pipelineId  the pipeline identifier
     * @return list of stage checkpoint entries, ordered by stage order
     */
    List<PipelineCacheEntry> getStageCheckpoints(String contentHash, String pipelineId);

    /**
     * Remove all stage checkpoints for a given content hash and pipeline.
     * Typically called after successfully storing the final output.
     *
     * @param contentHash the content hash
     * @param pipelineId  the pipeline identifier
     * @return number of entries removed
     */
    int removeStageCheckpoints(String contentHash, String pipelineId);

    // ========== Cache Management ==========

    /**
     * Remove all entries for a given content hash (all pipelines, all stages).
     *
     * @param contentHash the content hash
     * @return number of entries removed
     */
    int removeByContentHash(String contentHash);

    /**
     * Remove all entries for a given pipeline.
     *
     * @param pipelineId the pipeline identifier
     * @return number of entries removed
     */
    int removeByPipeline(String pipelineId);

    /**
     * Remove expired entries based on the cache's TTL configuration.
     *
     * @return number of entries evicted
     */
    int evictExpired();

    /**
     * Clear all cache entries.
     */
    void clear();

    // ========== Browsing ==========

    /**
     * List all cache entries (metadata only, without serialized output).
     * Used for UI browsing of the cache.
     *
     * @param offset pagination offset
     * @param limit  max entries to return
     * @return list of cache entries (serializedOutput may be truncated or null)
     */
    default List<PipelineCacheEntry> listEntries(int offset, int limit) {
        return Collections.emptyList();
    }

    /**
     * List cache entries filtered by entry type.
     *
     * @param entryType filter by type (FINAL_OUTPUT or STAGE_CHECKPOINT)
     * @param offset    pagination offset
     * @param limit     max entries to return
     * @return filtered entries
     */
    default List<PipelineCacheEntry> listEntries(PipelineCacheEntry.EntryType entryType, int offset, int limit) {
        return Collections.emptyList();
    }

    /**
     * Get the total count of entries, optionally filtered by type.
     *
     * @param entryType if non-null, count only this type
     * @return entry count
     */
    default long countEntries(PipelineCacheEntry.EntryType entryType) {
        return 0;
    }

    // ========== Statistics ==========

    /**
     * Get cache statistics.
     *
     * @return map of statistic name to value
     */
    CacheStats getStats();

    /**
     * Cache statistics.
     */
    record CacheStats(
            long totalEntries,
            long finalOutputEntries,
            long stageCheckpointEntries,
            long totalSizeBytes,
            long hitCount,
            long missCount,
            double hitRate
    ) {
        public static CacheStats empty() {
            return new CacheStats(0, 0, 0, 0, 0, 0, 0.0);
        }
    }

    // ========== Lifecycle ==========

    /**
     * Returns whether this cache implementation is available and initialized.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Flush any pending writes to durable storage.
     */
    default void flush() {
        // no-op by default
    }
}
