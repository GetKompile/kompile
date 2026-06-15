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

package ai.kompile.app.tools;

import ai.kompile.kvcache.service.KVCacheCheckpointService;
import ai.kompile.kvcache.service.KVCacheManager;
import ai.kompile.kvcache.service.KVCachePrefixService;
import ai.kompile.kvcache.service.PriorityEvictionPolicy;
import ai.kompile.kvcache.service.ContentHashPrefixIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KVCacheTool {

    private static final Logger logger = LoggerFactory.getLogger(KVCacheTool.class);

    private final KVCacheManager kvCacheManager;
    private final KVCacheCheckpointService checkpointService;
    private final PriorityEvictionPolicy priorityEvictionPolicy;
    private final ContentHashPrefixIndex contentHashPrefixIndex;

    @Autowired
    public KVCacheTool(
            @Autowired(required = false) KVCacheManager kvCacheManager,
            @Autowired(required = false) KVCacheCheckpointService checkpointService,
            @Autowired(required = false) PriorityEvictionPolicy priorityEvictionPolicy,
            @Autowired(required = false) ContentHashPrefixIndex contentHashPrefixIndex) {
        this.kvCacheManager = kvCacheManager;
        this.checkpointService = checkpointService;
        this.priorityEvictionPolicy = priorityEvictionPolicy;
        this.contentHashPrefixIndex = contentHashPrefixIndex;
        logger.info("KVCacheTool initialized");
    }

    public record GetKVCacheStatusInput() {}
    public record ListKVCachesInput() {}
    public record DestroyKVCacheInput(String cacheName) {}
    public record GetKVCacheStatsInput(String cacheName) {}
    public record CreateCheckpointInput(String cacheName, String label) {}
    public record RestoreCheckpointInput(String cacheName, String checkpointId) {}
    public record ListCheckpointsInput(String cacheName) {}

    @Tool(name = "get_kvcache_status",
            description = "Gets the overall KV cache status including whether it's enabled and aggregate statistics.")
    public Map<String, Object> getKVCacheStatus(GetKVCacheStatusInput input) {
        try {
            if (kvCacheManager == null) return Map.of("status", "error", "error", "KVCacheManager not available (is kompile.kvcache.enabled=true?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("enabled", kvCacheManager.isEnabled());
            result.put("aggregateStats", kvCacheManager.getAggregateStats());
            return result;
        } catch (Exception e) {
            logger.error("Error getting KV cache status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_kv_caches",
            description = "Lists all active KV caches with their names, types, and summary statistics.")
    public Map<String, Object> listKVCaches(ListKVCachesInput input) {
        try {
            if (kvCacheManager == null) return Map.of("status", "error", "error", "KVCacheManager not available");
            var caches = kvCacheManager.listCaches();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", caches.size());
            result.put("caches", caches);
            return result;
        } catch (Exception e) {
            logger.error("Error listing KV caches: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "destroy_kv_cache",
            description = "Destroys a specific KV cache by name, freeing all associated memory.")
    public Map<String, Object> destroyKVCache(DestroyKVCacheInput input) {
        try {
            if (kvCacheManager == null) return Map.of("status", "error", "error", "KVCacheManager not available");
            if (input.cacheName() == null) return Map.of("status", "error", "error", "cacheName is required");
            kvCacheManager.destroyCache(input.cacheName());
            return Map.of("status", "success", "message", "KV cache destroyed", "cacheName", input.cacheName());
        } catch (Exception e) {
            logger.error("Error destroying KV cache: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_kvcache_stats",
            description = "Gets detailed statistics for a specific KV cache by name including hit rates, memory usage, and occupancy.")
    public Map<String, Object> getKVCacheStats(GetKVCacheStatsInput input) {
        try {
            if (kvCacheManager == null) return Map.of("status", "error", "error", "KVCacheManager not available");
            if (input.cacheName() == null) return Map.of("status", "error", "error", "cacheName is required");
            var stats = kvCacheManager.getStats(input.cacheName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("cacheName", input.cacheName());
            result.put("stats", stats);
            return result;
        } catch (Exception e) {
            logger.error("Error getting KV cache stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "create_kvcache_checkpoint",
            description = "Creates a checkpoint of a KV cache for later restoration. Provide cacheName and an optional label.")
    public Map<String, Object> createCheckpoint(CreateCheckpointInput input) {
        try {
            if (checkpointService == null) return Map.of("status", "error", "error", "KVCacheCheckpointService not available");
            if (input.cacheName() == null) return Map.of("status", "error", "error", "cacheName is required");
            var checkpoint = checkpointService.createCheckpoint(input.cacheName(), input.label());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("checkpoint", checkpoint);
            result.put("message", "Checkpoint created");
            return result;
        } catch (Exception e) {
            logger.error("Error creating checkpoint: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "restore_kvcache_checkpoint",
            description = "Restores a KV cache from a previously created checkpoint.")
    public Map<String, Object> restoreCheckpoint(RestoreCheckpointInput input) {
        try {
            if (checkpointService == null) return Map.of("status", "error", "error", "KVCacheCheckpointService not available");
            if (input.cacheName() == null || input.checkpointId() == null)
                return Map.of("status", "error", "error", "cacheName and checkpointId are required");
            checkpointService.restoreCheckpoint(input.cacheName(), input.checkpointId());
            return Map.of("status", "success", "message", "Checkpoint restored",
                    "cacheName", input.cacheName(), "checkpointId", input.checkpointId());
        } catch (Exception e) {
            logger.error("Error restoring checkpoint: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_kvcache_checkpoints",
            description = "Lists all checkpoints for a specific KV cache.")
    public Map<String, Object> listCheckpoints(ListCheckpointsInput input) {
        try {
            if (checkpointService == null) return Map.of("status", "error", "error", "KVCacheCheckpointService not available");
            if (input.cacheName() == null) return Map.of("status", "error", "error", "cacheName is required");
            var checkpoints = checkpointService.listCheckpoints(input.cacheName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("cacheName", input.cacheName());
            result.put("count", checkpoints.size());
            result.put("checkpoints", checkpoints);
            return result;
        } catch (Exception e) {
            logger.error("Error listing checkpoints: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Priority Eviction Tools ====================

    public record SetKVBlockPriorityInput(Integer blockId, Integer priority) {}
    public record GetKVEvictionOrderInput(Integer numBlocks) {}

    @Tool(name = "set_kv_block_priority",
            description = "Sets priority for a KV cache block. Higher priority blocks are evicted last. Standard tiers: SYSTEM_PROMPT=90, CACHED_PREFIX=70, USER_CONTEXT=50, EVICTABLE=10.")
    public Map<String, Object> setKVBlockPriority(SetKVBlockPriorityInput input) {
        try {
            if (priorityEvictionPolicy == null) return Map.of("status", "error", "error", "PriorityEvictionPolicy not available (is priority eviction enabled?)");
            if (input.blockId() == null || input.priority() == null)
                return Map.of("status", "error", "error", "blockId and priority are required");
            priorityEvictionPolicy.setPriority(input.blockId(), input.priority());
            return Map.of("status", "success", "blockId", input.blockId(), "priority", input.priority());
        } catch (Exception e) {
            logger.error("Error setting block priority: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_kv_eviction_order",
            description = "Gets the current KV cache eviction order based on block priorities. Shows which blocks would be evicted first.")
    public Map<String, Object> getKVEvictionOrder(GetKVEvictionOrderInput input) {
        try {
            if (priorityEvictionPolicy == null) return Map.of("status", "error", "error", "PriorityEvictionPolicy not available");
            int numBlocks = input.numBlocks() != null ? input.numBlocks() : 10;
            List<Integer> evictionOrder = priorityEvictionPolicy.getEvictionOrder(numBlocks);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("evictionOrder", evictionOrder);
            result.put("totalTrackedBlocks", priorityEvictionPolicy.size());
            result.put("tierCounts", priorityEvictionPolicy.getTierCounts());
            return result;
        } catch (Exception e) {
            logger.error("Error getting eviction order: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // ==================== Prefix Hash Index Tools ====================

    public record GetPrefixIndexStatsInput() {}

    @Tool(name = "get_prefix_index_stats",
            description = "Gets content-hash prefix index statistics including size, hit rate, and cache efficiency.")
    public Map<String, Object> getPrefixIndexStats(GetPrefixIndexStatsInput input) {
        try {
            if (contentHashPrefixIndex == null) return Map.of("status", "error", "error", "ContentHashPrefixIndex not available (is prefix hashing enabled?)");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(contentHashPrefixIndex.getStats());
            return result;
        } catch (Exception e) {
            logger.error("Error getting prefix index stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
