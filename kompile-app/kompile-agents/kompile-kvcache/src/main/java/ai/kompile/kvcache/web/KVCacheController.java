package ai.kompile.kvcache.web;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.*;
import ai.kompile.kvcache.service.KVCacheCheckpointService;
import ai.kompile.kvcache.service.KVCacheConfigPersistence;
import ai.kompile.kvcache.service.KVCacheManager;
import ai.kompile.kvcache.service.KVCachePrefixService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/kvcache")
public class KVCacheController {

    private final KVCacheManager cacheManager;
    private final KVCacheProperties properties;
    private final KVCacheConfigPersistence configPersistence;
    private final KVCacheCheckpointService checkpointService;
    private final KVCachePrefixService prefixService;

    public KVCacheController(KVCacheManager cacheManager,
                             KVCacheProperties properties,
                             KVCacheConfigPersistence configPersistence,
                             KVCacheCheckpointService checkpointService,
                             KVCachePrefixService prefixService) {
        this.cacheManager = cacheManager;
        this.properties = properties;
        this.configPersistence = configPersistence;
        this.checkpointService = checkpointService;
        this.prefixService = prefixService;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STATUS & CONFIGURATION (always available, even when disabled)
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "enabled", properties.isEnabled(),
                "cacheCount", properties.isEnabled() ? cacheManager.listCaches().size() : 0,
                "checkpointsEnabled", properties.isCheckpointEnabled(),
                "prefixCacheEnabled", properties.isPrefixCacheEnabled()
        ));
    }

    @GetMapping("/config")
    public ResponseEntity<KVCacheProperties> getConfig() {
        return ResponseEntity.ok(properties);
    }

    @PutMapping("/config")
    public ResponseEntity<KVCacheProperties> updateConfig(@RequestBody KVCacheProperties newConfig) {
        // Update all mutable properties at runtime
        properties.setEnabled(newConfig.isEnabled());
        if (newConfig.getDefaultType() != null) properties.setDefaultType(newConfig.getDefaultType());
        if (newConfig.getBlockSize() > 0) properties.setBlockSize(newConfig.getBlockSize());
        if (newConfig.getMaxBatchSize() > 0) properties.setMaxBatchSize(newConfig.getMaxBatchSize());
        if (newConfig.getMaxSeqLen() > 0) properties.setMaxSeqLen(newConfig.getMaxSeqLen());
        if (newConfig.getNumKvHeads() > 0) properties.setNumKvHeads(newConfig.getNumKvHeads());
        if (newConfig.getHeadDim() > 0) properties.setHeadDim(newConfig.getHeadDim());
        if (newConfig.getDataType() != null) properties.setDataType(newConfig.getDataType());
        if (newConfig.getPoolSizeFactor() > 0) properties.setPoolSizeFactor(newConfig.getPoolSizeFactor());
        if (newConfig.getEvictionPolicy() != null) properties.setEvictionPolicy(newConfig.getEvictionPolicy());
        if (newConfig.getTokenBudget() > 0) properties.setTokenBudget(newConfig.getTokenBudget());
        if (newConfig.getQuantFormat() != null) properties.setQuantFormat(newConfig.getQuantFormat());
        if (newConfig.getTurboQuantBits() > 0) properties.setTurboQuantBits(newConfig.getTurboQuantBits());
        properties.setTieredEnabled(newConfig.isTieredEnabled());
        if (newConfig.getGpuPressureThreshold() > 0) properties.setGpuPressureThreshold(newConfig.getGpuPressureThreshold());
        if (newConfig.getHostPoolMaxBlocks() > 0) properties.setHostPoolMaxBlocks(newConfig.getHostPoolMaxBlocks());
        if (newConfig.getDiskOffloadPath() != null) properties.setDiskOffloadPath(newConfig.getDiskOffloadPath());
        properties.setPrefixCacheEnabled(newConfig.isPrefixCacheEnabled());
        if (newConfig.getPrefixCacheMaxEntries() > 0) properties.setPrefixCacheMaxEntries(newConfig.getPrefixCacheMaxEntries());
        properties.setCheckpointEnabled(newConfig.isCheckpointEnabled());
        if (newConfig.getMaxCheckpoints() > 0) properties.setMaxCheckpoints(newConfig.getMaxCheckpoints());
        if (newConfig.getCheckpointDir() != null) properties.setCheckpointDir(newConfig.getCheckpointDir());
        if (newConfig.getStatsWindowSeconds() > 0) properties.setStatsWindowSeconds(newConfig.getStatsWindowSeconds());

        // Persist to disk so changes survive restart
        configPersistence.save(properties);
        log.info("KV cache config updated and persisted (enabled={})", properties.isEnabled());
        return ResponseEntity.ok(properties);
    }

    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable() {
        properties.setEnabled(true);
        configPersistence.save(properties);
        log.info("KV cache enabled via API");
        return ResponseEntity.ok(Map.of("enabled", true));
    }

    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable() {
        properties.setEnabled(false);
        configPersistence.save(properties);
        log.info("KV cache disabled via API");
        return ResponseEntity.ok(Map.of("enabled", false));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CACHE CRUD (requires enabled)
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/caches")
    public ResponseEntity<?> listCaches() {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(cacheManager.listCaches());
    }

    @PostMapping("/caches")
    public ResponseEntity<?> createCache(@RequestBody CreateCacheRequest request) {
        try {
            var cache = cacheManager.createCache(request.name(), request.config() != null ? request.config() : new KVCacheConfig());
            return ResponseEntity.ok(KVCacheSummary.builder()
                    .name(cache.getName())
                    .type(cache.getType())
                    .createdAt(cache.getCreatedAt())
                    .memoryUsageBytes(cache.getMemoryUsageBytes())
                    .activeSequences(cache.getActiveSequenceCount())
                    .freeBlocks(cache.getFreeBlocks())
                    .totalBlocks(cache.getTotalBlocks())
                    .build());
        } catch (Exception e) {
            log.error("Error creating cache '{}': {}", request.name(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/caches/{name}")
    public ResponseEntity<?> getCache(@PathVariable String name) {
        var cache = cacheManager.getCache(name);
        if (cache == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(KVCacheSummary.builder()
                .name(cache.getName())
                .type(cache.getType())
                .createdAt(cache.getCreatedAt())
                .memoryUsageBytes(cache.getMemoryUsageBytes())
                .activeSequences(cache.getActiveSequenceCount())
                .freeBlocks(cache.getFreeBlocks())
                .totalBlocks(cache.getTotalBlocks())
                .build());
    }

    @DeleteMapping("/caches/{name}")
    public ResponseEntity<Map<String, Object>> destroyCache(@PathVariable String name) {
        cacheManager.destroyCache(name);
        return ResponseEntity.ok(Map.of("deleted", name));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/caches/{name}/stats")
    public ResponseEntity<?> getCacheStats(@PathVariable String name) {
        KVCacheStats stats = cacheManager.getStats(name);
        if (stats == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/caches/{name}/stats/timeseries")
    public ResponseEntity<?> getTimeSeries(@PathVariable String name,
                                            @RequestParam(defaultValue = "300") int windowSeconds) {
        KVCacheStats stats = cacheManager.getStats(name);
        if (stats == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(stats.getRecentSamples());
    }

    @GetMapping("/stats/aggregate")
    public ResponseEntity<KVCacheStats> getAggregateStats() {
        return ResponseEntity.ok(cacheManager.getAggregateStats());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CHECKPOINTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/caches/{name}/checkpoints")
    public ResponseEntity<?> listCheckpoints(@PathVariable String name) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled. Enable it in the Configuration tab."));
        }
        return ResponseEntity.ok(checkpointService.listCheckpoints(name));
    }

    @PostMapping("/caches/{name}/checkpoints")
    public ResponseEntity<?> createCheckpoint(@PathVariable String name,
                                               @RequestBody(required = false) Map<String, String> body) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        try {
            String label = body != null ? body.get("label") : null;
            return ResponseEntity.ok(checkpointService.createCheckpoint(name, label));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/caches/{name}/checkpoints/{id}/restore")
    public ResponseEntity<?> restoreCheckpoint(@PathVariable String name, @PathVariable String id) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        try {
            checkpointService.restoreCheckpoint(name, id);
            return ResponseEntity.ok(Map.of("restored", id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/caches/{name}/checkpoints/{id}")
    public ResponseEntity<?> deleteCheckpoint(@PathVariable String name, @PathVariable String id) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        checkpointService.deleteCheckpoint(name, id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @PostMapping("/caches/{name}/checkpoints/{id}/save-to-disk")
    public ResponseEntity<?> saveCheckpointToDisk(@PathVariable String name, @PathVariable String id) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        try {
            checkpointService.saveCheckpointToDisk(name, id);
            return ResponseEntity.ok(Map.of("saved", id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/caches/{name}/checkpoints/load-from-disk")
    public ResponseEntity<?> loadCheckpointFromDisk(@PathVariable String name,
                                                     @RequestBody Map<String, String> body) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        try {
            checkpointService.loadCheckpointFromDisk(name, body.get("path"));
            return ResponseEntity.ok(Map.of("loaded", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/caches/{name}/checkpoints/{id}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String name, @PathVariable String id) {
        if (!properties.isCheckpointEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Checkpoint service not enabled"));
        }
        try {
            checkpointService.rollback(name, id);
            return ResponseEntity.ok(Map.of("rolledBack", id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PREFIX CACHE
    // ═══════════════════════════════════════════════════════════════════════════════

    @GetMapping("/prefix-cache/stats")
    public ResponseEntity<?> getPrefixCacheStats() {
        if (!properties.isPrefixCacheEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Prefix cache not enabled. Enable it in the Configuration tab."));
        }
        return ResponseEntity.ok(prefixService.getStats());
    }

    @GetMapping("/prefix-cache/entries")
    public ResponseEntity<?> getPrefixCacheEntries(@RequestParam(defaultValue = "100") int limit) {
        if (!properties.isPrefixCacheEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Prefix cache not enabled"));
        }
        return ResponseEntity.ok(prefixService.listEntries(limit));
    }

    @PostMapping("/prefix-cache/save")
    public ResponseEntity<?> savePrefixCache() {
        if (!properties.isPrefixCacheEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Prefix cache not enabled"));
        }
        try {
            prefixService.saveToDisk();
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/prefix-cache/load")
    public ResponseEntity<?> loadPrefixCache() {
        if (!properties.isPrefixCacheEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Prefix cache not enabled"));
        }
        try {
            prefixService.loadFromDisk();
            return ResponseEntity.ok(Map.of("loaded", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    public record CreateCacheRequest(String name, KVCacheConfig config) {}
}
