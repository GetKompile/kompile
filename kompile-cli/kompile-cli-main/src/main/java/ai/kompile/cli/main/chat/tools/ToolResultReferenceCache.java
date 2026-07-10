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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /** Default character threshold above which results are cached (~16K chars ≈ 4K tokens): large
     *  enough that ordinary reads/greps still return inline, so caching fires only on genuinely
     *  large output that would otherwise flood the context window. */
    public static final int DEFAULT_CACHE_THRESHOLD_CHARS = 16000;

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

        StringBuilder summary = new StringBuilder();
        summary.append("[Full result cached as ref:").append(handle).append(" — ")
                .append(totalChars).append(" chars, ").append(totalLines).append(" lines. ")
                .append("Normal, not an error: large outputs are held out of context so they don't flood it.]\n");
        summary.append("[READ it with fetch_result(result_id=\"").append(handle)
                .append("\", offset=<1-based line>, limit=<lines>) — or pattern=<regex> to return only the ")
                .append("matching lines (like grep over the result) so you pull just what you need. Do NOT ")
                .append("re-run the tool or fall back to bash to avoid this — the result already exists; ")
                .append("re-run only for a genuinely narrower query. Expires ~15 min.]\n\n");
        summary.append("Preview: first & last lines (use pattern= to find the rest)\n");
        summary.append(buildPreview(output));

        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
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
        if (id.startsWith("ref:")) id = id.substring(4);
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
        return getSlice(id, offset, limit, null);
    }

    /**
     * Retrieve a slice of the cached content, or — when {@code pattern} is set — only the lines
     * matching that pattern (regex, case-insensitive; literal substring fallback on an invalid
     * regex), each prefixed with its 1-based line number. Filtering lets an agent pull exactly the
     * lines it needs from a large cached result instead of paging blindly or re-running the tool.
     *
     * @param id      the reference handle
     * @param offset  starting line (0-based); in pattern mode, the starting match index
     * @param limit   maximum lines (or matches) to return
     * @param pattern optional regex/substring filter; {@code null}/blank returns a raw slice
     */
    public ToolResult getSlice(String id, int offset, int limit, String pattern) {
        Optional<CacheEntry> entry = get(id);
        if (entry.isEmpty()) {
            return ToolResult.error("Result '" + id + "' not found or expired. Reference handles "
                    + "live ~15 min — re-run the original tool to regenerate it, ideally narrower "
                    + "(path / glob / limit) so the result returns inline.");
        }

        CacheEntry e = entry.get();
        String[] lines = e.output.split("\n", -1);
        int totalLines = lines.length;

        if (offset < 0) offset = 0;
        if (limit <= 0) limit = 200;

        if (pattern != null && !pattern.isBlank()) {
            return filterSlice(id, e, lines, totalLines, offset, limit, pattern);
        }

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
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("result_id", id);
        meta.put("toolName", e.toolName);
        meta.put("totalLines", totalLines);
        meta.put("linesReturned", end - offset);
        meta.put("truncated", truncated);

        return ToolResult.success("fetch_result: " + id + " [" + e.toolName + "]",
                sb.toString(), meta);
    }

    /** Return only the lines matching {@code pattern}, each prefixed with its 1-based line number. */
    private ToolResult filterSlice(String id, CacheEntry e, String[] lines, int totalLines,
                                   int offset, int limit, String pattern) {
        Predicate<String> matcher = buildMatcher(pattern);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < totalLines; i++) {
            if (matcher.test(lines[i])) matches.add(i);
        }
        int totalMatches = matches.size();
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("result_id", id);
        meta.put("toolName", e.toolName);
        meta.put("totalLines", totalLines);
        meta.put("matchCount", totalMatches);

        if (totalMatches == 0) {
            return ToolResult.success("fetch_result: " + id + " /" + pattern + "/",
                    "(no lines match /" + pattern + "/ in " + totalLines + " lines)", meta);
        }
        int end = Math.min(offset + limit, totalMatches);
        if (offset >= totalMatches) {
            return ToolResult.success("fetch_result: " + id + " /" + pattern + "/",
                    "(offset " + offset + " past " + totalMatches + " matches)", meta);
        }

        StringBuilder sb = new StringBuilder();
        for (int k = offset; k < end; k++) {
            int i = matches.get(k);
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        boolean truncated = end < totalMatches;
        meta.put("matchesReturned", end - offset);
        meta.put("truncated", truncated);
        return ToolResult.success(
                "fetch_result: " + id + " /" + pattern + "/ [" + e.toolName + "] " + totalMatches + " matches",
                sb.toString() + (truncated
                        ? "\n... (" + (totalMatches - end) + " more matches — raise offset/limit)" : ""),
                meta);
    }

    /** Number of live entries. */
    public int size() {
        return cache.size();
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Build a structured preview — the first several lines plus the last few — so the agent sees
     * the shape (beginning and end) of a large result and can decide what to fetch, rather than a
     * raw character prefix that may be entirely boilerplate. Long lines are clipped.
     */
    private String buildPreview(String output) {
        String[] lines = output.split("\n", -1);
        int head = Math.min(10, lines.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head; i++) {
            sb.append(clip(lines[i])).append("\n");
        }
        int tailStart = Math.max(head, lines.length - 4);
        if (tailStart > head) {
            sb.append("… (").append(tailStart - head).append(" lines omitted — use offset/limit or pattern=) …\n");
            for (int i = tailStart; i < lines.length; i++) {
                sb.append(clip(lines[i])).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String clip(String line) {
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }

    /**
     * A line matcher for the {@code pattern} filter: a case-insensitive regex, falling back to a
     * case-insensitive literal substring match if the pattern is not a valid regex (so an agent can
     * pass raw text without worrying about regex metacharacters).
     */
    private static Predicate<String> buildMatcher(String pattern) {
        try {
            Pattern p =
                    Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return line -> p.matcher(line).find();
        } catch (PatternSyntaxException ex) {
            String needle = pattern.toLowerCase(Locale.ROOT);
            return line -> line.toLowerCase(Locale.ROOT).contains(needle);
        }
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
