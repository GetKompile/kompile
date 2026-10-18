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
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Durable result references with a bounded legacy in-memory cache, enabling the
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

    /** Legacy/direct-store threshold. Production MCP delivery also applies its configured
     * soft character allowance; character counts are not exact model-token measurements. */
    public static final int DEFAULT_CACHE_THRESHOLD_CHARS = 16000;
    /** A fetch must not bypass the output guard and refill the model's context. */
    public static final int MAX_FETCH_CHARS = 16000;

    /** Default idle TTL for legacy in-memory entries (15 minutes), not durable files. */
    private static final long DEFAULT_TTL_MS = 15 * 60 * 1000L;

    /** Maximum entries before LRU eviction. */
    private static final int MAX_ENTRIES = 500;

    public static final long DEFAULT_MEMORY_BYTE_CAP = 8 * 1024 * 1024L;
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Long> weights = new LinkedHashMap<>();
    private long retainedBytes;
    private final long memoryByteCap;
    private final DurableToolResultStore durableStore;
    private final LongSupplier clock;
    private final int thresholdChars;
    private final long ttlMs;
    private volatile ToolOutputBudget outputBudget;

    public ToolResultReferenceCache() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public ToolResultReferenceCache(Path workingDirectory) {
        this(DEFAULT_CACHE_THRESHOLD_CHARS, DEFAULT_TTL_MS, DEFAULT_MEMORY_BYTE_CAP,
                new DurableToolResultStore(KompileHome.homeDirectory().toPath().resolve("conversations")),
                System::currentTimeMillis);
        configureOutputBudget(workingDirectory);
    }

    /** Socket initialization resolves its client's working directory before registering tools. */
    public void configureOutputBudget(Path workingDirectory) {
        outputBudget = ToolOutputBudget.load(workingDirectory, clock);
    }

    ToolOutputBudget.Reservation reserveFetch(String owner) {
        return outputBudget.reserve(owner, true);
    }

    /** Compatibility/test constructor: ephemeral only, never writes to the real Kompile home. */
    public ToolResultReferenceCache(int thresholdChars, long ttlMs) {
        this(thresholdChars, ttlMs, DEFAULT_MEMORY_BYTE_CAP, null, System::currentTimeMillis);
    }

    ToolResultReferenceCache(int thresholdChars, long ttlMs, long memoryByteCap,
                             DurableToolResultStore durableStore, LongSupplier clock) {
        if (ttlMs < 0 || memoryByteCap < 0) throw new IllegalArgumentException("Negative cache bound");
        this.thresholdChars = thresholdChars;
        this.ttlMs = ttlMs;
        this.memoryByteCap = memoryByteCap;
        this.durableStore = durableStore;
        this.clock = clock;
        this.outputBudget = new ToolOutputBudget(ToolOutputBudget.Settings.legacy(), clock);
    }

    ToolResultReferenceCache(int thresholdChars, long ttlMs, ToolOutputBudget.Settings settings,
                             LongSupplier clock) {
        this(thresholdChars, ttlMs, DEFAULT_MEMORY_BYTE_CAP, null, clock);
        this.outputBudget = new ToolOutputBudget(settings, clock);
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
        return store(null, toolName, output, metadata);
    }

    private synchronized String store(String ownerSession, String toolName, String output,
                                      Map<String, Object> metadata) {
        if (durableStore != null) {
            try {
                return durableStore.store(ownerSession, toolName, output, metadata);
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot persist full tool result", failure);
            }
        }
        evictIfNeeded();
        // Normalize metadata rather than retaining arbitrary caller-owned object graphs.
        Map<String, Object> snapshot;
        long weight;
        try {
            String json = JsonUtils.standardMapper().writeValueAsString(metadata == null ? Map.of() : metadata);
            snapshot = (Map<String, Object>) immutableJson(JsonUtils.standardMapper().readValue(json, Map.class));
            weight = 1024L + 2L * output.length() + 2L * (toolName == null ? 0 : toolName.length())
                    + 32L * json.length(); // conservative allowance for decoded metadata objects
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (weight > memoryByteCap) throw new IllegalStateException("Full result exceeds ephemeral cache byte cap");
        String id = UUID.randomUUID().toString().substring(0, 8);
        while (cache.containsKey(id)) id = UUID.randomUUID().toString().substring(0, 8);
        long expiresAt = clock.getAsLong() + ttlMs;
        cache.put(id, new CacheEntry(toolName, output, snapshot, expiresAt));
        weights.put(id, weight);
        retainedBytes += weight;
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
        return storeAndSummarize(null, toolName, title, output, metadata);
    }

    /** Bound model-facing output without changing error status or re-caching explicit fetches. */
    public ToolResult prepareForDelivery(String ownerSession, String toolName, ToolResult result) {
        // Fetch tools reserve and account their own shared page allowance before slicing.
        if (result == null || "fetch_result".equals(toolName) || "fetch_result_batch".equals(toolName)) return result;
        try (var reservation = outputBudget.reserve(ownerSession, false)) {
            ToolResult delivered = result;
            if (result.getOutput() != null && (shouldCache(result.getOutput())
                    || result.getOutput().length() > reservation.limit())) {
                delivered = storeAndSummarize(ownerSession, toolName, result.getTitle(),
                        result.getOutput(), result.getMetadata(), result.isError(), reservation.limit());
            }
            reservation.complete(delivered.getOutput() == null ? 0 : delivered.getOutput().length());
            return delivered;
        }
    }

    /** Owner is the host-resolved invocation session; null uses the documented MCP fallback. */
    public ToolResult storeAndSummarize(String ownerSession, String toolName, String title,
                                        String output, Map<String, Object> metadata) {
        return storeAndSummarize(ownerSession, toolName, title, output, metadata, false);
    }

    private ToolResult storeAndSummarize(String ownerSession, String toolName, String title,
                                         String output, Map<String, Object> metadata, boolean error) {
        return storeAndSummarize(ownerSession, toolName, title, output, metadata, error, MAX_FETCH_CHARS);
    }

    private ToolResult storeAndSummarize(String ownerSession, String toolName, String title,
                                         String output, Map<String, Object> metadata, boolean error, int previewLimit) {
        final String handle;
        try {
            handle = store(ownerSession, toolName, output, metadata);
        } catch (RuntimeException failure) {
            // Never substitute a bogus handle or silently discard full output on I/O failure.
            LinkedHashMap<String, Object> failedMeta = new LinkedHashMap<>();
            if (metadata != null) failedMeta.putAll(metadata);
            failedMeta.remove("result_id");
            failedMeta.put("cached", false);
            failedMeta.put("durable", false);
            failedMeta.put("resultCacheError", failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            return new ToolResult(title,
                    "[Result cache failed; full output returned inline, no handle issued: "
                            + failedMeta.get("resultCacheError") + "]\n" + output, failedMeta, error);
        }
        boolean durable = DurableToolResultStore.isDurableHandle(handle);

        // Generate a compact summary
        int totalChars = output.length();
        int totalLines = 0;
        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            if (c == '\n' || c == '\r') {
                totalLines++;
                if (c == '\r' && i + 1 < output.length() && output.charAt(i + 1) == '\n') i++;
            }
        }
        if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n'
                && output.charAt(output.length() - 1) != '\r') totalLines++;

        StringBuilder summary = new StringBuilder();
        summary.append("[Full result cached as ref:").append(handle).append(" — ")
                .append(totalChars).append(" chars, ").append(totalLines).append(" lines. ")
                .append(error ? "Tool failed; full diagnostic output is cached for retrieval.]\n"
                        : "Normal, not an error: large outputs are held out of context so they don't flood it.]\n");
        summary.append("[Fetch only needed evidence with fetch_result(result_id=\"").append(handle)
                .append("\", offset=<1-based line>, limit=<lines>) — or pattern=<regex> to return only the ")
                .append("matching lines (like grep over the result) so you pull just what you need. Do NOT ")
                .append("re-run the tool or fall back to bash to avoid this — the result already exists; ")
                .append("re-run only for a genuinely narrower query. ")
                .append(durable ? "Durable until the owning conversation is removed.]\n\n"
                        : "Expires after idle cache TTL.]\n\n");
        summary.append("Preview: first & last lines (use pattern= to find the rest)\n");
        String preview = buildPreview(output);
        int previewChars = Math.max(0, previewLimit - summary.length());
        int end = Math.min(preview.length(), previewChars);
        if (end > 0 && Character.isHighSurrogate(preview.charAt(end - 1))) end--;
        summary.append(preview, 0, end);

        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) meta.putAll(metadata);
        meta.put("result_id", handle);
        meta.put("cached", true);
        meta.put("durable", durable);
        meta.put("totalChars", totalChars);
        meta.put("totalLines", totalLines);

        return new ToolResult(title, summary.toString(), meta, error);
    }

    /**
     * Retrieve the full cached content by handle.
     *
     * @param id the reference handle
     * @return legacy full content, or a small durable entry; large durable entries require getSlice
     */
    public synchronized Optional<CacheEntry> get(String id) {
        if (id == null) return Optional.empty();
        if (id.startsWith("ref:")) id = id.substring(4);
        if (DurableToolResultStore.isDurableHandle(id) && durableStore != null) {
            try {
                return Optional.of(durableStore.getSmallEntry(id, MAX_FETCH_CHARS));
            } catch (IOException failure) {
                throw new UncheckedIOException("Use getSlice for large durable results; read failed", failure);
            }
        }
        evictIfNeeded();
        CacheEntry entry = cache.get(id);
        if (entry == null) return Optional.empty();
        CacheEntry refreshed = new CacheEntry(entry.toolName, entry.output, entry.metadata, clock.getAsLong() + ttlMs);
        cache.put(id, refreshed);
        return Optional.of(refreshed);
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
        return getSlice(id, offset, limit, pattern, 0, MAX_FETCH_CHARS);
    }

    /** Character offset continues a long line; maxChars also bounds each batch share. */
    ToolResult getSlice(String id, int offset, int limit, String pattern,
                        int characterOffset, int maxChars) {
        String bare = id != null && id.startsWith("ref:") ? id.substring(4) : id;
        if (DurableToolResultStore.isDurableHandle(bare)) {
            if (durableStore == null) return ToolResult.error("Durable result storage is not configured");
            try {
                return durableStore.getSlice(bare, offset, limit, pattern, characterOffset, maxChars);
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error("Cannot read durable result: " + failure.getMessage()
                        + ". It may be missing, removed with its conversation, or unsafe; no TTL expiry is assumed.");
            }
        }
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
            return filterSlice(id, e, lines, totalLines, offset, limit, pattern,
                    characterOffset, maxChars);
        }

        if (offset >= totalLines) {
            return ToolResult.success("fetch_result: " + id,
                    "(offset " + offset + " past end of " + totalLines + " lines)",
                    Map.of("result_id", id, "totalLines", totalLines));
        }

        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("result_id", id);
        meta.put("toolName", e.toolName);
        meta.put("totalLines", totalLines);
        String output = boundedSlice(lines, null, offset, limit, characterOffset, maxChars, meta);
        return ToolResult.success("fetch_result: " + id + " [" + e.toolName + "]", output, meta);
    }

    /** Return only the lines matching {@code pattern}, each prefixed with its 1-based line number. */
    private ToolResult filterSlice(String id, CacheEntry e, String[] lines, int totalLines,
                                   int offset, int limit, String pattern,
                                   int characterOffset, int maxChars) {
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
        if (offset >= totalMatches) {
            return ToolResult.success("fetch_result: " + id + " /" + pattern + "/",
                    "(offset " + offset + " past " + totalMatches + " matches)", meta);
        }

        String output = boundedSlice(lines, matches, offset, limit, characterOffset, maxChars, meta);
        return ToolResult.success(
                "fetch_result: " + id + " /" + pattern + "/ [" + e.toolName + "] " + totalMatches + " matches",
                output, meta);
    }

    /** Page without losing the remainder of an oversized line or splitting a surrogate pair. */
    private String boundedSlice(String[] lines, List<Integer> matches, int offset, int limit,
                                int characterOffset, int maxChars, Map<String, Object> meta) {
        int count = matches == null ? lines.length : matches.size();
        int end = (int) Math.min((long) offset + limit, count);
        int budget = Math.max(128, Math.min(MAX_FETCH_CHARS, maxChars) - 256);
        StringBuilder sb = new StringBuilder();
        int cursor = offset;
        int nextCharacter = 0;
        int returned = 0;
        for (; cursor < end; cursor++) {
            int lineIndex = matches == null ? cursor : matches.get(cursor);
            String line = lines[lineIndex];
            int start = cursor == offset ? Math.min(Math.max(0, characterOffset), line.length()) : 0;
            String prefix = matches == null ? "" : (lineIndex + 1) + ": ";
            int available = budget - sb.length() - prefix.length() - 1;
            if (available <= 0 || (line.length() - start > available && !sb.isEmpty())) break;
            int stop = Math.min(line.length(), start + available);
            if (stop < line.length() && stop > start && Character.isHighSurrogate(line.charAt(stop - 1))) stop--;
            sb.append(prefix).append(line, start, stop).append('\n');
            returned++;
            if (stop < line.length()) {
                nextCharacter = stop;
                break;
            }
        }
        boolean truncated = cursor < count;
        meta.put(matches == null ? "linesReturned" : "matchesReturned", returned);
        meta.put("truncated", truncated);
        if (truncated) {
            meta.put("nextOffset", cursor + 1); // external offsets are 1-based (match index in pattern mode)
            meta.put("nextCharacterOffset", nextCharacter);
            sb.append("\n... (page bounded; continue with offset=").append(cursor + 1)
                    .append(", character_offset=").append(nextCharacter).append(")");
        }
        return sb.toString();
    }

    /** Number of live entries. */
    public synchronized int size() {
        evictIfNeeded();
        return cache.size();
    }

    public synchronized long retainedBytes() {
        evictIfNeeded();
        return retainedBytes;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Build a structured preview — the first several lines plus the last few — so the agent sees
     * the shape (beginning and end) of a large result and can decide what to fetch, rather than a
     * raw character prefix that may be entirely boilerplate. Long lines are clipped.
     */
    private String buildPreview(String output) {
        // Bound temporary allocations too: do not split the entire incoming payload for a preview.
        int total = 1;
        for (int i = 0; i < output.length(); i++) if (output.charAt(i) == '\n') total++;
        int head = Math.min(10, total);
        int tailStart = Math.max(head, total - 4);
        StringBuilder sb = new StringBuilder();
        int start = 0;
        for (int line = 0; line < total; line++) {
            int end = output.indexOf('\n', start);
            if (end < 0) end = output.length();
            if (line == head && tailStart > head) {
                sb.append("… (").append(tailStart - head).append(" lines omitted — use offset/limit or pattern=) …\n");
            }
            if (line < head || line >= tailStart) {
                int stop = Math.min(end, start + 200);
                if (stop < end && stop > start && Character.isHighSurrogate(output.charAt(stop - 1))) stop--;
                sb.append(output, start, stop);
                if (stop < end) sb.append('…');
                sb.append('\n');
            }
            start = end + 1;
        }
        return sb.toString().stripTrailing();
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

    /** Do not let callers mutate a returned metadata map into an unaccounted object graph. */
    private static Object immutableJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutableJson(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(immutableJson(item));
            return java.util.Collections.unmodifiableList(copy);
        }
        return value;
    }

    private void evictIfNeeded() {
        // Lazy idle expiry: no scheduler/thread per cache; idle resident data is byte bounded.
        long now = clock.getAsLong();
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().expiresAt < now) {
                retainedBytes -= weights.remove(entry.getKey());
                iterator.remove();
            }
        }
        while (cache.size() > MAX_ENTRIES || retainedBytes > memoryByteCap) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
            retainedBytes -= weights.remove(oldest);
        }
    }

    /** A cached tool output entry. */
    public record CacheEntry(String toolName, String output,
                              Map<String, Object> metadata, long expiresAt) {}
}
