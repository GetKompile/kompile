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

package ai.kompile.cli.main.chat.tools;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory cache for large tool outputs, enabling the
 * reference-handle pattern described in MCP resource references research.
 *
 * <p>When a tool output exceeds a character threshold, the full content is
 * stored here and the agent receives a compact summary + reference handle
 * (the {@code result_id}). The agent can later call {@code fetch_result}
 * with the handle to retrieve the full content or a slice of it.
 *
 * <p>This eliminates the need to pass large tool outputs through the LLM
 * context window. Research shows 86-94% context reduction (Cloudflare Code Mode)
 * and 7x token reduction (arxiv 2511.22729) with this approach.
 *
 * <p>No Spring dependencies — suitable for CLI/MCP stdio mode.
 */
public class ToolResultReferenceCache {

    /** Default character threshold above which results are cached. */
    public static final int DEFAULT_CACHE_THRESHOLD_CHARS = 8000;

    /** Default TTL for cached entries (15 minutes). */
    private static final long DEFAULT_TTL_MS = 15 * 60 * 1000L;

    /** Maximum entries before LRU eviction. */
    private static final int MAX_ENTRIES = 500;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final int thresholdChars;
    private final long ttlMs;

    public ToolResultReferenceCache() {
        this(DEFAULT_CACHE_THRESHOLD_CHARS, DEFAULT_TTL_MS);
    }

    public ToolResultReferenceCache(int thresholdChars, long ttlMs) {
        this.thresholdChars = thresholdChars;
        this.ttlMs = ttlMs;
    }

    /**
     * Store a tool result and return a reference handle.
     *
     * @param toolName the tool that produced the output
     * @param output   the full tool output
     * @param metadata tool result metadata
     * @return the reference handle (UUID string)
     */
    public String store(String toolName, String output, Map<String, Object> metadata) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        long expiresAt = System.currentTimeMillis() + ttlMs;
        cache.put(id, new CacheEntry(toolName, output, metadata, expiresAt));
        evictIfNeeded();
        return id;
    }

    /**
     * Check if a tool output should be cached (exceeds threshold).
     */
    public boolean shouldCache(String output) {
        return output != null && output.length() >= thresholdChars;
    }

    /**
     * Store a large result and return a compact summary + handle that the
     * LLM receives instead of the full output.
     *
     * @param toolName the tool that produced the output
     * @param title    the tool result title
     * @param output   the full tool output
     * @param metadata the tool result metadata
     * @return a ToolResult containing the summary + handle
     */
    public ToolResult storeAndSummarize(String toolName, String title, String output,
                                         Map<String, Object> metadata) {
        String handle = store(toolName, output, metadata);

        // Generate a compact summary
        int totalChars = output.length();
        int totalLines = (int) output.lines().count();
        String preview = extractPreview(output, 500);

        StringBuilder summary = new StringBuilder();
        summary.append("[Result stored as ref:").append(handle).append("]\n");
        summary.append("[").append(totalChars).append(" chars, ")
                .append(totalLines).append(" lines — use fetch_result to retrieve]\n\n");
        summary.append("Preview:\n");
        summary.append(preview);
        if (totalChars > 500) {
            summary.append("\n...(").append(totalChars - 500).append(" more chars)");
        }

        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        if (metadata != null) meta.putAll(metadata);
        meta.put("result_id", handle);
        meta.put("cached", true);
        meta.put("totalChars", totalChars);
        meta.put("totalLines", totalLines);

        return ToolResult.success(title, summary.toString(), meta);
    }

    /**
     * Retrieve the full cached content by handle.
     *
     * @param id the reference handle
     * @return the full content, or empty if expired/unknown
     */
    public Optional<CacheEntry> get(String id) {
        if (id == null) return Optional.empty();
        CacheEntry entry = cache.get(id);
        if (entry == null) return Optional.empty();
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(id);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /**
     * Retrieve a slice of the cached content.
     *
     * @param id     the reference handle
     * @param offset starting line (0-based)
     * @param limit  maximum lines to return
     * @return sliced content as a ToolResult, or error if not found
     */
    public ToolResult getSlice(String id, int offset, int limit) {
        Optional<CacheEntry> entry = get(id);
        if (entry.isEmpty()) {
            return ToolResult.error("Result not found or expired: " + id);
        }

        CacheEntry e = entry.get();
        String[] lines = e.output.split("\n", -1);
        int totalLines = lines.length;

        if (offset < 0) offset = 0;
        if (limit <= 0) limit = 200;
        int end = Math.min(offset + limit, totalLines);

        if (offset >= totalLines) {
            return ToolResult.success("fetch_result: " + id,
                    "(offset " + offset + " past end of " + totalLines + " lines)",
                    Map.of("result_id", id, "totalLines", totalLines));
        }

        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }

        boolean truncated = end < totalLines;
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("result_id", id);
        meta.put("toolName", e.toolName);
        meta.put("totalLines", totalLines);
        meta.put("linesReturned", end - offset);
        meta.put("truncated", truncated);

        return ToolResult.success("fetch_result: " + id + " [" + e.toolName + "]",
                sb.toString(), meta);
    }

    /** Number of live entries. */
    public int size() {
        return cache.size();
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private String extractPreview(String output, int maxChars) {
        if (output.length() <= maxChars) return output;
        // Take first maxChars, but try to break at a line boundary
        int cutoff = output.lastIndexOf('\n', maxChars);
        if (cutoff < maxChars / 2) cutoff = maxChars;
        return output.substring(0, cutoff);
    }

    private void evictIfNeeded() {
        // Evict expired entries first
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt < now);

        // If still over max, evict oldest
        while (cache.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestExpiry = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
                if (e.getValue().expiresAt < oldestExpiry) {
                    oldestExpiry = e.getValue().expiresAt;
                    oldest = e.getKey();
                }
            }
            if (oldest != null) cache.remove(oldest);
            else break;
        }
    }

    /** A cached tool output entry. */
    public record CacheEntry(String toolName, String output,
                              Map<String, Object> metadata, long expiresAt) {}
}
