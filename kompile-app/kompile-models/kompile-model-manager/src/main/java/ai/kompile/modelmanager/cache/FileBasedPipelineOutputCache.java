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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * File-based implementation of {@link PipelineOutputCache}.
 *
 * <p>Stores cached pipeline outputs as JSON files in a configurable directory
 * (default: {@code ~/.kompile/cache/pipeline-outputs/}). Follows the same
 * persistence patterns as {@code ProcessingStateRepository}.</p>
 *
 * <h2>Directory Structure</h2>
 * <pre>
 * ~/.kompile/cache/pipeline-outputs/
 * ├── final/                              # Final pipeline outputs
 * │   ├── {contentHash}_{pipelineId}.json
 * │   └── ...
 * └── checkpoints/                        # Intermediate stage checkpoints
 *     ├── {contentHash}_{pipelineId}_{stageId}.json
 *     └── ...
 * </pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>In-memory index for fast lookups</li>
 *   <li>Async writes to avoid blocking pipeline execution</li>
 *   <li>Atomic file writes (temp + rename) for crash safety</li>
 *   <li>TTL-based eviction with configurable retention period</li>
 *   <li>Size-based eviction with configurable max cache size</li>
 *   <li>Hit/miss statistics tracking</li>
 * </ul>
 */
public class FileBasedPipelineOutputCache implements PipelineOutputCache {

    private static final Logger log = LoggerFactory.getLogger(FileBasedPipelineOutputCache.class);

    private static final String FINAL_DIR = "final";
    private static final String CHECKPOINTS_DIR = "checkpoints";
    private static final String FILE_EXTENSION = ".json";
    private static final int DEFAULT_TTL_DAYS = 30;
    private static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024 * 1024; // 10 GB

    private final Path cacheDir;
    private final Path finalDir;
    private final Path checkpointsDir;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService executor;

    // In-memory index: compositeKey → entry metadata (without serializedOutput for memory efficiency)
    private final ConcurrentHashMap<String, PipelineCacheEntry> index = new ConcurrentHashMap<>();
    private final Set<String> pendingWrites = ConcurrentHashMap.newKeySet();

    // Statistics
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    // Configuration
    private final int ttlDays;
    private final long maxSizeBytes;
    private volatile boolean initialized = false;

    /**
     * Create with default settings (cache at ~/.kompile/cache/pipeline-outputs/).
     */
    public FileBasedPipelineOutputCache() {
        this(Paths.get(System.getProperty("user.home"), ".kompile", "cache", "pipeline-outputs"),
                DEFAULT_TTL_DAYS, DEFAULT_MAX_SIZE_BYTES);
    }

    /**
     * Create with a custom cache directory.
     *
     * @param cacheDir     root directory for cache files
     * @param ttlDays      time-to-live for cache entries in days
     * @param maxSizeBytes maximum total cache size in bytes
     */
    public FileBasedPipelineOutputCache(Path cacheDir, int ttlDays, long maxSizeBytes) {
        this.cacheDir = cacheDir;
        this.finalDir = cacheDir.resolve(FINAL_DIR);
        this.checkpointsDir = cacheDir.resolve(CHECKPOINTS_DIR);
        this.ttlDays = ttlDays;
        this.maxSizeBytes = maxSizeBytes;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-cache-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize the cache directory and load existing entries.
     * Call this after construction (or use as @PostConstruct).
     */
    public void init() {
        try {
            Files.createDirectories(finalDir);
            Files.createDirectories(checkpointsDir);
            loadIndex();
            initialized = true;

            // Schedule periodic eviction
            executor.scheduleAtFixedRate(this::evictExpiredInternal,
                    1, 24, TimeUnit.HOURS);

            log.info("Pipeline output cache initialized at {} ({} entries)",
                    cacheDir, index.size());
        } catch (IOException e) {
            log.error("Failed to initialize pipeline output cache at {}: {}",
                    cacheDir, e.getMessage());
        }
    }

    // ========== Core Operations ==========

    @Override
    public Optional<PipelineCacheEntry> get(PipelineCacheKey key) {
        if (!initialized) return Optional.empty();

        String keyStr = key.toKeyString();
        PipelineCacheEntry indexEntry = index.get(keyStr);

        if (indexEntry == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        // Check TTL
        if (isExpired(indexEntry)) {
            remove(key);
            missCount.incrementAndGet();
            return Optional.empty();
        }

        // Load full entry from disk if needed
        try {
            Path filePath = getFilePath(key);
            if (!Files.exists(filePath)) {
                // Index entry exists but file doesn't - clean up
                index.remove(keyStr);
                missCount.incrementAndGet();
                return Optional.empty();
            }

            PipelineCacheEntry fullEntry = objectMapper.readValue(filePath.toFile(), PipelineCacheEntry.class);
            PipelineCacheEntry hitEntry = fullEntry.withHit();

            // Update hit count in index and on disk (async)
            index.put(keyStr, hitEntry);
            persistAsync(key, hitEntry);

            hitCount.incrementAndGet();
            log.debug("Cache hit for key: {}", keyStr);
            return Optional.of(hitEntry);

        } catch (IOException e) {
            log.warn("Failed to read cache entry {}: {}", keyStr, e.getMessage());
            index.remove(keyStr);
            missCount.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void put(PipelineCacheKey key, PipelineCacheEntry entry) {
        if (!initialized) {
            init();
        }

        String keyStr = key.toKeyString();
        index.put(keyStr, entry);
        persistAsync(key, entry);
        log.debug("Cache put for key: {} (type={})", keyStr, entry.getEntryType());
    }

    @Override
    public boolean contains(PipelineCacheKey key) {
        if (!initialized) return false;

        PipelineCacheEntry entry = index.get(key.toKeyString());
        if (entry == null) return false;
        if (isExpired(entry)) {
            remove(key);
            return false;
        }
        return true;
    }

    @Override
    public boolean remove(PipelineCacheKey key) {
        if (!initialized) return false;

        String keyStr = key.toKeyString();
        PipelineCacheEntry removed = index.remove(keyStr);

        if (removed != null) {
            Path filePath = getFilePath(key);
            try {
                Files.deleteIfExists(filePath);
                log.debug("Removed cache entry: {}", keyStr);
            } catch (IOException e) {
                log.warn("Failed to delete cache file {}: {}", filePath, e.getMessage());
            }
            return true;
        }
        return false;
    }

    // ========== Batch Operations ==========

    @Override
    public List<PipelineCacheEntry> getStageCheckpoints(String contentHash, String pipelineId) {
        if (!initialized) return Collections.emptyList();

        String prefix = contentHash + ":" + pipelineId + ":";
        List<PipelineCacheEntry> checkpoints = new ArrayList<>();

        for (Map.Entry<String, PipelineCacheEntry> entry : index.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().isStageCheckpoint()) {
                if (!isExpired(entry.getValue())) {
                    try {
                        Path filePath = checkpointsDir.resolve(
                                entry.getValue().getCacheKey().replace(':', '_') + FILE_EXTENSION);
                        if (Files.exists(filePath)) {
                            PipelineCacheEntry full = objectMapper.readValue(
                                    filePath.toFile(), PipelineCacheEntry.class);
                            checkpoints.add(full);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to load checkpoint: {}", entry.getKey());
                    }
                }
            }
        }

        // Sort by stage ID for pipeline ordering
        checkpoints.sort(Comparator.comparing(e -> e.getStageId() != null ? e.getStageId() : ""));
        return checkpoints;
    }

    @Override
    public int removeStageCheckpoints(String contentHash, String pipelineId) {
        if (!initialized) return 0;

        String prefix = contentHash + ":" + pipelineId + ":";
        int removed = 0;

        Iterator<Map.Entry<String, PipelineCacheEntry>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PipelineCacheEntry> entry = it.next();
            if (entry.getKey().startsWith(prefix) && entry.getValue().isStageCheckpoint()) {
                it.remove();
                Path filePath = checkpointsDir.resolve(
                        entry.getKey().replace(':', '_') + FILE_EXTENSION);
                try {
                    Files.deleteIfExists(filePath);
                    removed++;
                } catch (IOException e) {
                    log.warn("Failed to delete checkpoint file: {}", filePath);
                }
            }
        }

        if (removed > 0) {
            log.debug("Removed {} stage checkpoints for content={}, pipeline={}",
                    removed, contentHash.substring(0, 12), pipelineId);
        }
        return removed;
    }

    // ========== Cache Management ==========

    @Override
    public int removeByContentHash(String contentHash) {
        if (!initialized) return 0;

        String prefix = contentHash + ":";
        int removed = 0;

        Iterator<Map.Entry<String, PipelineCacheEntry>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PipelineCacheEntry> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                it.remove();
                deleteEntryFile(entry.getValue());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int removeByPipeline(String pipelineId) {
        if (!initialized) return 0;

        int removed = 0;
        Iterator<Map.Entry<String, PipelineCacheEntry>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PipelineCacheEntry> entry = it.next();
            if (pipelineId.equals(entry.getValue().getPipelineId())) {
                it.remove();
                deleteEntryFile(entry.getValue());
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int evictExpired() {
        return evictExpiredInternal();
    }

    @Override
    public void clear() {
        index.clear();
        try {
            deleteDirectoryContents(finalDir);
            deleteDirectoryContents(checkpointsDir);
            log.info("Pipeline output cache cleared");
        } catch (IOException e) {
            log.error("Failed to clear cache: {}", e.getMessage());
        }
    }

    // ========== Browsing ==========

    @Override
    public List<PipelineCacheEntry> listEntries(int offset, int limit) {
        if (!initialized) return Collections.emptyList();

        List<PipelineCacheEntry> entries = new ArrayList<>(index.values());
        // Sort by creation time descending (newest first)
        entries.sort(Comparator.comparing(PipelineCacheEntry::getCreatedAt).reversed());

        int end = Math.min(offset + limit, entries.size());
        if (offset >= entries.size()) return Collections.emptyList();
        return entries.subList(offset, end);
    }

    @Override
    public List<PipelineCacheEntry> listEntries(PipelineCacheEntry.EntryType entryType, int offset, int limit) {
        if (!initialized) return Collections.emptyList();

        List<PipelineCacheEntry> filtered = index.values().stream()
                .filter(e -> e.getEntryType() == entryType)
                .sorted(Comparator.comparing(PipelineCacheEntry::getCreatedAt).reversed())
                .toList();

        int end = Math.min(offset + limit, filtered.size());
        if (offset >= filtered.size()) return Collections.emptyList();
        return filtered.subList(offset, end);
    }

    @Override
    public long countEntries(PipelineCacheEntry.EntryType entryType) {
        if (!initialized) return 0;
        if (entryType == null) return index.size();
        return index.values().stream().filter(e -> e.getEntryType() == entryType).count();
    }

    // ========== Statistics ==========

    @Override
    public CacheStats getStats() {
        long totalEntries = index.size();
        long finalCount = index.values().stream().filter(PipelineCacheEntry::isFinalOutput).count();
        long checkpointCount = index.values().stream().filter(PipelineCacheEntry::isStageCheckpoint).count();
        long totalSize = index.values().stream().mapToLong(PipelineCacheEntry::getSizeBytes).sum();
        long hits = hitCount.get();
        long misses = missCount.get();
        double rate = (hits + misses) > 0 ? (double) hits / (hits + misses) : 0.0;

        return new CacheStats(totalEntries, finalCount, checkpointCount,
                totalSize, hits, misses, rate);
    }

    @Override
    public boolean isAvailable() {
        return initialized;
    }

    @Override
    public void flush() {
        // Wait for all pending writes to complete
        if (!pendingWrites.isEmpty()) {
            log.debug("Flushing {} pending cache writes", pendingWrites.size());
            // Give pending writes a chance to complete
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Shut down the cache writer executor.
     */
    public void shutdown() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the cache directory path.
     */
    public Path getCacheDir() {
        return cacheDir;
    }

    // ========== Internal Methods ==========

    private void loadIndex() {
        loadEntriesFromDir(finalDir);
        loadEntriesFromDir(checkpointsDir);
    }

    private void loadEntriesFromDir(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .forEach(path -> {
                        try {
                            PipelineCacheEntry entry = objectMapper.readValue(
                                    path.toFile(), PipelineCacheEntry.class);
                            if (entry.getCacheKey() != null) {
                                index.put(entry.getCacheKey(), entry);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to load cache entry from {}: {}",
                                    path.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not list cache directory {}: {}", dir, e.getMessage());
        }
    }

    private Path getFilePath(PipelineCacheKey key) {
        String fileName = key.toFileSystemKey() + FILE_EXTENSION;
        if (key.isStageKey()) {
            return checkpointsDir.resolve(fileName);
        } else {
            return finalDir.resolve(fileName);
        }
    }

    private void persistAsync(PipelineCacheKey key, PipelineCacheEntry entry) {
        String keyStr = key.toKeyString();
        if (pendingWrites.add(keyStr)) {
            executor.schedule(() -> {
                try {
                    pendingWrites.remove(keyStr);
                    PipelineCacheEntry current = index.get(keyStr);
                    if (current != null) {
                        writeToFile(key, current);
                    }
                } catch (Exception e) {
                    log.error("Failed to persist cache entry {}: {}", keyStr, e.getMessage());
                }
            }, 50, TimeUnit.MILLISECONDS);
        }
    }

    private void writeToFile(PipelineCacheKey key, PipelineCacheEntry entry) {
        Path filePath = getFilePath(key);
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), entry);
            Files.move(tempPath, filePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.trace("Persisted cache entry: {}", key.toKeyString());
        } catch (IOException e) {
            log.error("Failed to write cache file {}: {}", filePath, e.getMessage());
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp cache file {} after write failure: {}", tempPath, cleanupEx.getMessage());
            }
        }
    }

    private void deleteEntryFile(PipelineCacheEntry entry) {
        if (entry.getCacheKey() == null) return;
        String fileName = entry.getCacheKey().replace(':', '_') + FILE_EXTENSION;
        Path dir = entry.isStageCheckpoint() ? checkpointsDir : finalDir;
        try {
            Files.deleteIfExists(dir.resolve(fileName));
        } catch (IOException e) {
            log.warn("Failed to delete cache file: {}", fileName);
        }
    }

    private boolean isExpired(PipelineCacheEntry entry) {
        if (entry.getCreatedAt() == null) return false;
        return entry.getCreatedAt().plus(ttlDays, ChronoUnit.DAYS).isBefore(Instant.now());
    }

    private int evictExpiredInternal() {
        if (!initialized) return 0;

        int evicted = 0;
        Iterator<Map.Entry<String, PipelineCacheEntry>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PipelineCacheEntry> entry = it.next();
            if (isExpired(entry.getValue())) {
                it.remove();
                deleteEntryFile(entry.getValue());
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("Evicted {} expired cache entries", evicted);
        }

        // Check size-based eviction
        evicted += evictBySize();

        return evicted;
    }

    private int evictBySize() {
        long totalSize = index.values().stream().mapToLong(PipelineCacheEntry::getSizeBytes).sum();
        if (totalSize <= maxSizeBytes) return 0;

        // Evict LRU entries until under size limit
        List<Map.Entry<String, PipelineCacheEntry>> sorted = new ArrayList<>(index.entrySet());
        sorted.sort(Comparator.comparing(e -> e.getValue().getLastAccessedAt()));

        int evicted = 0;
        for (Map.Entry<String, PipelineCacheEntry> entry : sorted) {
            if (totalSize <= maxSizeBytes) break;
            totalSize -= entry.getValue().getSizeBytes();
            index.remove(entry.getKey());
            deleteEntryFile(entry.getValue());
            evicted++;
        }

        if (evicted > 0) {
            log.info("Evicted {} cache entries for size limit (target: {} MB)",
                    evicted, maxSizeBytes / (1024 * 1024));
        }
        return evicted;
    }

    private void deleteDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(FILE_EXTENSION))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete: {}", p);
                        }
                    });
        }
    }
}
