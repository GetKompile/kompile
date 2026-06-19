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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only JSONL changelog that records every memory mutation.
 * Stored as {@code .memory-changelog.jsonl} in each memory directory
 * (project and global). Each line is a JSON object with:
 * <ul>
 *   <li>{@code timestamp} — ISO-8601 instant</li>
 *   <li>{@code action} — the mutation type (save, forget, write, append,
 *       create_entity, create_relation, add_observation, delete_entity,
 *       delete_relation, delete_observation)</li>
 *   <li>{@code scope} — "project" or "global"</li>
 *   <li>{@code file} — the affected file name (for flat/typed ops)</li>
 *   <li>{@code summary} — human-readable one-line summary of the change</li>
 *   <li>{@code additions} — count of lines/items added</li>
 *   <li>{@code deletions} — count of lines/items removed</li>
 *   <li>{@code diff} — optional compact diff snippet (for write/append/save)</li>
 *   <li>{@code sessionId} — optional session identifier for agent tracking</li>
 * </ul>
 *
 * <p>The log is capped at {@value #MAX_ENTRIES} entries; when exceeded,
 * the oldest half is pruned on the next write.
 */
public class MemoryChangeLog {

    static final String CHANGELOG_FILE = ".memory-changelog.jsonl";
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_DIFF_SNIPPET_CHARS = 1500;
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    // Pattern for relative time strings like "1h", "24h", "30m", "7d"
    private static final Pattern RELATIVE_TIME = Pattern.compile("^(\\d+)([smhd])$");

    private final Path changelogPath;

    public MemoryChangeLog(Path memoryDir) {
        this.changelogPath = memoryDir.resolve(CHANGELOG_FILE);
    }

    /**
     * Record a memory mutation.
     *
     * @param action    the mutation type (save, forget, write, append, etc.)
     * @param scope     "project" or "global"
     * @param file      affected file name, or null for graph ops
     * @param summary   human-readable one-line summary
     * @param additions count of lines/items added
     * @param deletions count of lines/items removed
     * @param diff      optional compact diff snippet
     * @param sessionId optional session ID for agent tracking
     */
    public void record(String action, String scope, String file, String summary,
                       int additions, int deletions, String diff, String sessionId) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("timestamp", Instant.now().toString());
        entry.put("action", action);
        entry.put("scope", scope);
        if (file != null) entry.put("file", file);
        entry.put("summary", summary);
        entry.put("additions", additions);
        entry.put("deletions", deletions);
        if (diff != null) {
            if (diff.length() > MAX_DIFF_SNIPPET_CHARS) {
                diff = diff.substring(0, MAX_DIFF_SNIPPET_CHARS) + "\n... (truncated)";
            }
            entry.put("diff", diff);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            entry.put("sessionId", sessionId);
        }

        try {
            Files.createDirectories(changelogPath.getParent());
            String line = MAPPER.writeValueAsString(entry) + "\n";
            Files.writeString(changelogPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            pruneIfNeeded();
        } catch (IOException e) {
            // Best-effort — don't fail the actual memory operation
        }
    }

    /**
     * Query the changelog for entries matching the given filters.
     *
     * @param since  only entries after this time; accepts ISO-8601 or relative
     *               strings like "1h", "24h", "30m", "7d". Null for all.
     * @param file   filter to a specific file. Null for all.
     * @param action filter to a specific action type. Null for all.
     * @param limit  max entries to return (most recent first). 0 for default (50).
     * @return list of changelog entries, most recent first
     */
    public List<ChangeLogEntry> query(String since, String file, String action, int limit) {
        if (limit <= 0) limit = 50;
        if (!Files.exists(changelogPath)) return Collections.emptyList();

        Instant sinceInstant = parseSince(since);
        List<ChangeLogEntry> all = loadEntries();
        List<ChangeLogEntry> filtered = new ArrayList<>();

        // Iterate in reverse (most recent first)
        for (int i = all.size() - 1; i >= 0; i--) {
            ChangeLogEntry e = all.get(i);
            if (sinceInstant != null && e.timestamp.isBefore(sinceInstant)) continue;
            if (file != null && !file.isEmpty() && !file.equals(e.file)) continue;
            if (action != null && !action.isEmpty() && !action.equals(e.action)) continue;
            filtered.add(e);
            if (filtered.size() >= limit) break;
        }
        return filtered;
    }

    /**
     * Get a summary of changes: counts by action type since the given time.
     */
    public String summary(String since) {
        List<ChangeLogEntry> entries = query(since, null, null, MAX_ENTRIES);
        if (entries.isEmpty()) {
            return "No memory changes" + (since != null ? " since " + since : "") + ".";
        }

        int saves = 0, forgets = 0, writes = 0, appends = 0, graphOps = 0;
        int totalAdds = 0, totalDels = 0;
        java.util.Set<String> filesChanged = new java.util.LinkedHashSet<>();

        for (ChangeLogEntry e : entries) {
            totalAdds += e.additions;
            totalDels += e.deletions;
            if (e.file != null) filesChanged.add(e.file);
            switch (e.action) {
                case "save": saves++; break;
                case "forget": forgets++; break;
                case "write": writes++; break;
                case "append": appends++; break;
                default:
                    if (e.action.startsWith("create_") || e.action.startsWith("add_")
                            || e.action.startsWith("delete_")) {
                        graphOps++;
                    }
                    break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Memory changes");
        if (since != null) sb.append(" since ").append(since);
        sb.append(": ").append(entries.size()).append(" total");
        sb.append(" (+").append(totalAdds).append(" / -").append(totalDels).append(")\n");

        if (saves > 0) sb.append("  saves: ").append(saves).append("\n");
        if (forgets > 0) sb.append("  forgets: ").append(forgets).append("\n");
        if (writes > 0) sb.append("  writes: ").append(writes).append("\n");
        if (appends > 0) sb.append("  appends: ").append(appends).append("\n");
        if (graphOps > 0) sb.append("  graph ops: ").append(graphOps).append("\n");

        if (!filesChanged.isEmpty()) {
            sb.append("  files touched: ");
            int shown = 0;
            for (String f : filesChanged) {
                if (shown > 0) sb.append(", ");
                sb.append(f);
                shown++;
                if (shown >= 15) {
                    sb.append(" (+").append(filesChanged.size() - shown).append(" more)");
                    break;
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Format entries for display. Shows a compact table with timestamp,
     * action, file, +/- counts, and summary.
     */
    public static String formatEntries(List<ChangeLogEntry> entries) {
        if (entries.isEmpty()) return "No matching changelog entries.";

        StringBuilder sb = new StringBuilder();
        for (ChangeLogEntry e : entries) {
            sb.append(DISPLAY_FMT.format(e.timestamp));
            sb.append("  ").append(String.format("%-16s", e.action));
            if (e.file != null) {
                sb.append(String.format("%-30s", e.file));
            } else {
                sb.append(String.format("%-30s", "(graph)"));
            }
            sb.append(String.format(" +%-4d -%-4d", e.additions, e.deletions));
            sb.append("  ").append(e.summary);
            sb.append("\n");

            if (e.diff != null && !e.diff.isEmpty()) {
                // Indent each diff line
                for (String dl : e.diff.split("\n")) {
                    sb.append("    ").append(dl).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Compute additions/deletions counts between old and new content.
     */
    public static int[] computeAddDel(String oldContent, String newContent) {
        if (oldContent == null) oldContent = "";
        if (newContent == null) newContent = "";

        String[] oldLines = oldContent.isEmpty() ? new String[0] : oldContent.split("\n", -1);
        String[] newLines = newContent.isEmpty() ? new String[0] : newContent.split("\n", -1);

        // Simple: count lines in new not in old (additions) and vice versa (deletions)
        // Use the same prefix/suffix matching as InlineDiff for accuracy
        int prefixLen = 0;
        int minLen = Math.min(oldLines.length, newLines.length);
        while (prefixLen < minLen && oldLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        int oldSuffix = oldLines.length;
        int newSuffix = newLines.length;
        while (oldSuffix > prefixLen && newSuffix > prefixLen
                && oldLines[oldSuffix - 1].equals(newLines[newSuffix - 1])) {
            oldSuffix--;
            newSuffix--;
        }

        int deletions = oldSuffix - prefixLen;
        int additions = newSuffix - prefixLen;
        return new int[]{additions, deletions};
    }

    // ---- Internal ----

    private List<ChangeLogEntry> loadEntries() {
        List<ChangeLogEntry> entries = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(changelogPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    ChangeLogEntry e = new ChangeLogEntry();
                    e.timestamp = Instant.parse(node.path("timestamp").asText());
                    e.action = node.path("action").asText("");
                    e.scope = node.path("scope").asText("");
                    e.file = node.has("file") ? node.path("file").asText() : null;
                    e.summary = node.path("summary").asText("");
                    e.additions = node.path("additions").asInt(0);
                    e.deletions = node.path("deletions").asInt(0);
                    e.diff = node.has("diff") ? node.path("diff").asText() : null;
                    e.sessionId = node.has("sessionId") ? node.path("sessionId").asText() : null;
                    entries.add(e);
                } catch (Exception ex) {
                    // Skip corrupted lines
                }
            }
        } catch (IOException e) {
            // Return empty
        }
        return entries;
    }

    private void pruneIfNeeded() {
        try {
            List<String> lines = Files.readAllLines(changelogPath, StandardCharsets.UTF_8);
            // Filter out blank lines for accurate count
            List<String> nonBlank = new ArrayList<>();
            for (String l : lines) {
                if (!l.isBlank()) nonBlank.add(l);
            }
            if (nonBlank.size() > MAX_ENTRIES) {
                // Keep the most recent half
                int keep = MAX_ENTRIES / 2;
                List<String> kept = nonBlank.subList(nonBlank.size() - keep, nonBlank.size());
                StringBuilder sb = new StringBuilder();
                for (String l : kept) {
                    sb.append(l).append("\n");
                }
                Files.writeString(changelogPath, sb.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Best-effort
        }
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isEmpty()) return null;

        // Try relative time first
        Matcher m = RELATIVE_TIME.matcher(since.trim().toLowerCase());
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s": return Instant.now().minus(amount, ChronoUnit.SECONDS);
                case "m": return Instant.now().minus(amount, ChronoUnit.MINUTES);
                case "h": return Instant.now().minus(amount, ChronoUnit.HOURS);
                case "d": return Instant.now().minus(amount, ChronoUnit.DAYS);
            }
        }

        // Try ISO-8601
        try {
            return Instant.parse(since);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Single changelog entry.
     */
    public static class ChangeLogEntry {
        public Instant timestamp;
        public String action;
        public String scope;
        public String file;
        public String summary;
        public int additions;
        public int deletions;
        public String diff;
        public String sessionId;
    }
}
