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

package ai.kompile.core.mcp.optimization;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory, TTL-bounded cache for oversized MCP tool responses.
 *
 * <p>Large responses are stored under a UUID and the caller returns a short
 * reference (the {@code result_id}) to the agent. The agent can later call
 * {@code fetch_result} to pull back the full payload — or a slice of it —
 * without re-running the original tool.
 *
 * <p>Two entry classes are stored with independent TTLs:
 * <ul>
 *   <li>General result cache ({@link #store(Object)}) — TTL from
 *       {@link McpOptimizationConfig#getResultCacheTtlSeconds()}.</li>
 *   <li>Filesystem undo cache ({@link #storeFilesystemUndo(String, String)}) —
 *       TTL from {@link McpOptimizationConfig#getFilesystemUndoTtlSeconds()}.
 *       Larger bytes-to-TTL ratio is expected; isolated from general LRU.</li>
 * </ul>
 *
 * <p>Uses plain {@link ConcurrentHashMap} + a {@link ScheduledExecutorService}
 * for TTL eviction to avoid pulling in Caffeine as a new dep.
 */
@Component
public class ResultReferenceCache {

    private static final Logger log = LoggerFactory.getLogger(ResultReferenceCache.class);

    private final McpOptimizationConfigProvider configProvider;
    private final ConcurrentMap<String, CacheEntry> results = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CacheEntry> filesystemUndo = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @Autowired
    public ResultReferenceCache(@Autowired(required = false) McpOptimizationConfigProvider configProvider) {
        this.configProvider = configProvider != null
                ? configProvider
                : McpOptimizationConfigProvider.ofDefaults();
    }

    @PostConstruct
    public void start() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-result-cache-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::reap, 60, 60, TimeUnit.SECONDS);
        log.info("ResultReferenceCache started");
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @EventListener
    public void onConfigChanged(McpOptimizationConfigChangedEvent event) {
        enforceMaxEntries(results, event.getConfig().getResultCacheMaxEntries());
    }

    /**
     * Stores the given payload and returns an opaque handle the agent can
     * later pass to {@code fetch_result} to retrieve it.
     *
     * @param payload the full, uncompressed tool result
     * @return the {@code result_id} handle
     */
    public String store(Object payload) {
        McpOptimizationConfig cfg = configProvider.getConfiguration();
        long ttlSeconds = defaultTtl(cfg.getResultCacheTtlSeconds(), 900L);
        String id = UUID.randomUUID().toString();
        results.put(id, new CacheEntry(payload, now() + TimeUnit.SECONDS.toMillis(ttlSeconds)));
        enforceMaxEntries(results, cfg.getResultCacheMaxEntries());
        return id;
    }

    /**
     * Retrieves a previously stored result. Returns empty if the id is unknown
     * or the entry has expired.
     */
    public Optional<Object> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        CacheEntry e = results.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAt < now()) {
            results.remove(id);
            return Optional.empty();
        }
        return Optional.of(e.payload);
    }

    /**
     * Retrieves a slice of a stored payload. If {@code key} is non-null and the
     * payload is a {@code Map}, the key is traversed first. If the (resulting)
     * value is a {@link Collection} or {@link List}, {@code offset} and
     * {@code limit} are applied; otherwise the value is returned as-is.
     *
     * @param id     stored result handle
     * @param key    optional map key (null to return the root value)
     * @param offset starting index for list/collection slicing
     * @param limit  max elements to return
     */
    public Optional<Object> getSlice(String id, String key, int offset, int limit) {
        Optional<Object> root = get(id);
        if (root.isEmpty()) {
            return root;
        }
        Object value = root.get();
        if (key != null && !key.isBlank() && value instanceof Map<?, ?> map) {
            value = map.get(key);
            if (value == null) {
                return Optional.empty();
            }
        }
        if (value instanceof List<?> list) {
            return Optional.of(sliceList(list, offset, limit));
        }
        if (value instanceof Collection<?> col) {
            return Optional.of(sliceList(List.copyOf(col), offset, limit));
        }
        return Optional.of(value);
    }

    /**
     * Stores a filesystem undo snapshot (the pre-change file content) and
     * returns an {@code undo_token}.
     *
     * @param filePath       the affected path, for audit/logging only
     * @param previousContent the pre-change file content (may be null for a
     *                        pre-existing-file create that had no content)
     * @return the {@code undo_token} the agent passes back to {@code fs_undo}
     */
    public String storeFilesystemUndo(String filePath, String previousContent) {
        McpOptimizationConfig cfg = configProvider.getConfiguration();
        long ttlSeconds = defaultTtl(cfg.getFilesystemUndoTtlSeconds(), 3600L);
        String token = UUID.randomUUID().toString();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("filePath", filePath);
        entry.put("previousContent", previousContent);
        filesystemUndo.put(token, new CacheEntry(entry, now() + TimeUnit.SECONDS.toMillis(ttlSeconds)));
        enforceMaxEntries(filesystemUndo, cfg.getResultCacheMaxEntries());
        return token;
    }

    /**
     * Retrieves a filesystem undo snapshot. The returned map has keys
     * {@code filePath} and {@code previousContent}.
     */
    public Optional<Map<String, Object>> getFilesystemUndo(String undoToken) {
        if (undoToken == null) {
            return Optional.empty();
        }
        CacheEntry e = filesystemUndo.get(undoToken);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiresAt < now()) {
            filesystemUndo.remove(undoToken);
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) e.payload;
        return Optional.of(payload);
    }

    /**
     * Invalidates a filesystem undo token, e.g. after {@code fs_undo} has
     * restored the file.
     */
    public void invalidateFilesystemUndo(String undoToken) {
        if (undoToken != null) {
            filesystemUndo.remove(undoToken);
        }
    }

    /** Current number of live general-result entries (post-TTL). */
    public int size() {
        return results.size();
    }

    /** Current number of live filesystem-undo entries (post-TTL). */
    public int filesystemUndoSize() {
        return filesystemUndo.size();
    }

    private void reap() {
        long cutoff = now();
        results.entrySet().removeIf(e -> e.getValue().expiresAt < cutoff);
        filesystemUndo.entrySet().removeIf(e -> e.getValue().expiresAt < cutoff);
    }

    private void enforceMaxEntries(ConcurrentMap<String, CacheEntry> map, Integer maxEntries) {
        if (maxEntries == null || maxEntries < 1) {
            return;
        }
        while (map.size() > maxEntries) {
            String victim = null;
            long earliestExpiry = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> e : map.entrySet()) {
                if (e.getValue().expiresAt < earliestExpiry) {
                    earliestExpiry = e.getValue().expiresAt;
                    victim = e.getKey();
                }
            }
            if (victim == null) {
                break;
            }
            map.remove(victim);
        }
    }

    private static Object sliceList(List<?> list, int offset, int limit) {
        int total = list.size();
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? total : limit;
        int end = (int) Math.min((long) safeOffset + safeLimit, total);
        List<?> slice = safeOffset < end ? list.subList(safeOffset, end) : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offset", safeOffset);
        result.put("limit", safeLimit);
        result.put("total", total);
        result.put("returned", slice.size());
        result.put("items", slice);
        return result;
    }

    private static long defaultTtl(Long configured, long fallback) {
        return (configured != null && configured > 0) ? configured : fallback;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private record CacheEntry(Object payload, long expiresAt) {
    }
}
