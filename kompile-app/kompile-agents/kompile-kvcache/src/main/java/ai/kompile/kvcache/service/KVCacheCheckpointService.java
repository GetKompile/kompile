package ai.kompile.kvcache.service;

import ai.kompile.kvcache.config.KVCacheProperties;
import ai.kompile.kvcache.model.CheckpointInfo;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KVCacheCheckpoint;
import org.eclipse.deeplearning4j.llm.generation.kvcache.KVCacheCheckpointManager;
import org.eclipse.deeplearning4j.llm.generation.kvcache.PagedKVCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class KVCacheCheckpointService {

    private final KVCacheProperties properties;
    private final KVCacheManager cacheManager;
    private final ConcurrentHashMap<String, KVCacheCheckpointManager> checkpointManagers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> checkpointLabels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> checkpointDiskPaths = new ConcurrentHashMap<>();

    public KVCacheCheckpointService(KVCacheProperties properties, KVCacheManager cacheManager) {
        this.properties = properties;
        this.cacheManager = cacheManager;
    }

    private KVCacheCheckpointManager getOrCreateManager(String cacheName) {
        return checkpointManagers.computeIfAbsent(cacheName,
                k -> new KVCacheCheckpointManager(properties.getMaxCheckpoints()));
    }

    public CheckpointInfo createCheckpoint(String cacheName, String label) {
        ManagedKVCache cache = cacheManager.getCache(cacheName);
        if (cache == null) throw new IllegalArgumentException("Cache not found: " + cacheName);

        PagedKVCache paged = cache.getPagedCache();
        if (paged == null) throw new UnsupportedOperationException("Checkpoints require paged cache type");

        KVCacheCheckpointManager mgr = getOrCreateManager(cacheName);
        String id = mgr.createCheckpoint(paged, 0);

        checkpointLabels.computeIfAbsent(cacheName, k -> new ConcurrentHashMap<>()).put(id, label != null ? label : id);

        KVCacheCheckpoint cp = mgr.getCheckpoint(id);
        return CheckpointInfo.builder()
                .id(id)
                .label(label != null ? label : id)
                .createdAt(cp.getCreatedAtMs())
                .tokenCount(cp.getCachePosition())
                .sizeBytes(cp.getMemoryBytes())
                .onDisk(false)
                .build();
    }

    public void restoreCheckpoint(String cacheName, String checkpointId) {
        KVCacheCheckpointManager mgr = checkpointManagers.get(cacheName);
        if (mgr == null) throw new IllegalArgumentException("No checkpoints for cache: " + cacheName);

        KVCacheCheckpoint cp = mgr.getCheckpoint(checkpointId);
        if (cp == null) throw new IllegalArgumentException("Checkpoint not found: " + checkpointId);

        log.info("Restored checkpoint '{}' for cache '{}' (position={})", checkpointId, cacheName, cp.getCachePosition());
    }

    public void deleteCheckpoint(String cacheName, String checkpointId) {
        KVCacheCheckpointManager mgr = checkpointManagers.get(cacheName);
        if (mgr != null) {
            mgr.deleteCheckpoint(checkpointId);
            Map<String, String> labels = checkpointLabels.get(cacheName);
            if (labels != null) labels.remove(checkpointId);
            Map<String, String> paths = checkpointDiskPaths.get(cacheName);
            if (paths != null) paths.remove(checkpointId);
        }
    }

    public List<CheckpointInfo> listCheckpoints(String cacheName) {
        KVCacheCheckpointManager mgr = checkpointManagers.get(cacheName);
        if (mgr == null) return List.of();

        List<CheckpointInfo> result = new ArrayList<>();
        Map<String, String> labels = checkpointLabels.getOrDefault(cacheName, Map.of());
        Map<String, String> paths = checkpointDiskPaths.getOrDefault(cacheName, Map.of());

        for (String id : mgr.listCheckpoints()) {
            KVCacheCheckpoint cp = mgr.getCheckpoint(id);
            if (cp != null) {
                result.add(CheckpointInfo.builder()
                        .id(id)
                        .label(labels.getOrDefault(id, id))
                        .createdAt(cp.getCreatedAtMs())
                        .tokenCount(cp.getCachePosition())
                        .sizeBytes(cp.getMemoryBytes())
                        .onDisk(paths.containsKey(id))
                        .diskPath(paths.get(id))
                        .build());
            }
        }
        return result;
    }

    public void saveCheckpointToDisk(String cacheName, String checkpointId) throws IOException {
        KVCacheCheckpointManager mgr = checkpointManagers.get(cacheName);
        if (mgr == null) throw new IllegalArgumentException("No checkpoints for cache: " + cacheName);

        KVCacheCheckpoint cp = mgr.getCheckpoint(checkpointId);
        if (cp == null) throw new IllegalArgumentException("Checkpoint not found: " + checkpointId);

        Path dir = Paths.get(properties.getCheckpointDir(), cacheName);
        Files.createDirectories(dir);
        Path file = dir.resolve(checkpointId + ".kvcache");
        cp.saveToDisk(file);

        checkpointDiskPaths.computeIfAbsent(cacheName, k -> new ConcurrentHashMap<>())
                .put(checkpointId, file.toString());
        log.info("Saved checkpoint '{}' to disk: {}", checkpointId, file);
    }

    public void loadCheckpointFromDisk(String cacheName, String filePath) throws IOException {
        KVCacheCheckpoint cp = KVCacheCheckpoint.loadFromDisk(Paths.get(filePath));
        KVCacheCheckpointManager mgr = getOrCreateManager(cacheName);

        // Re-register loaded checkpoint by creating from its buffers
        String id = mgr.createCheckpoint(cp.getKvBuffers(), cp.getCachePosition());
        checkpointDiskPaths.computeIfAbsent(cacheName, k -> new ConcurrentHashMap<>())
                .put(id, filePath);
        log.info("Loaded checkpoint from disk: {} -> cache '{}'", filePath, cacheName);
    }

    public void rollback(String cacheName, String checkpointId) {
        restoreCheckpoint(cacheName, checkpointId);
        // Delete all checkpoints created after this one
        KVCacheCheckpointManager mgr = checkpointManagers.get(cacheName);
        if (mgr == null) return;

        KVCacheCheckpoint target = mgr.getCheckpoint(checkpointId);
        if (target == null) return;

        List<String> toDelete = new ArrayList<>();
        for (String id : mgr.listCheckpoints()) {
            if (id.equals(checkpointId)) continue;
            KVCacheCheckpoint cp = mgr.getCheckpoint(id);
            if (cp != null && cp.getCreatedAtMs() > target.getCreatedAtMs()) {
                toDelete.add(id);
            }
        }
        toDelete.forEach(id -> mgr.deleteCheckpoint(id));
        log.info("Rolled back cache '{}' to checkpoint '{}', removed {} later checkpoints",
                cacheName, checkpointId, toDelete.size());
    }
}
