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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent JSONL-based index of all tool calls from passthrough and emulated
 * passthrough sessions. Stores one JSONL file per session under
 * {@code ~/.kompile/conversations/tool-calls/} plus a combined index for
 * cross-session search.
 * <p>
 * Thread-safe: multiple threads can record tool calls concurrently.
 */
public class ToolCallIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_CALLS_DIR = "tool-calls";
    private static final String COMBINED_INDEX = "all-tool-calls.jsonl";

    private final Path toolCallsDir;
    private final Path combinedIndexFile;
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    private static ToolCallIndex instance;

    private ToolCallIndex() {
        this.toolCallsDir = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(TOOL_CALLS_DIR);
        this.combinedIndexFile = toolCallsDir.resolve(COMBINED_INDEX);
    }

    public static synchronized ToolCallIndex getInstance() {
        if (instance == null) {
            instance = new ToolCallIndex();
        }
        return instance;
    }

    /**
     * Record a tool call from a passthrough/emulated passthrough session.
     */
    public void record(String sessionId, String toolName, String toolInput,
                       String agentName, String source, boolean isError, long durationMs) {
        record(sessionId, toolName, toolInput, agentName, source, isError, durationMs, null);
    }

    /**
     * Record a tool call with project directory context.
     */
    public void record(String sessionId, String toolName, String toolInput,
                       String agentName, String source, boolean isError, long durationMs,
                       String projectDirectory) {
        String summary = summarize(toolName, toolInput);
        String category = ToolCallRecord.categorize(toolName);
        String id = sessionId + "-" + idGenerator.incrementAndGet();

        ToolCallRecord record = new ToolCallRecord(
                id, sessionId, toolName, toolInput, summary,
                Instant.now(), source, agentName, isError, durationMs, category,
                projectDirectory
        );

        appendRecord(record);
    }

    /**
     * Record a pre-built ToolCallRecord.
     */
    public void record(ToolCallRecord record) {
        appendRecord(record);
    }

    private synchronized void appendRecord(ToolCallRecord record) {
        try {
            Files.createDirectories(toolCallsDir);

            String jsonLine = MAPPER.writeValueAsString(record) + "\n";

            // Append to per-session file
            Path sessionFile = toolCallsDir.resolve(record.getSessionId() + ".jsonl");
            Files.writeString(sessionFile, jsonLine,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Append to combined index
            Files.writeString(combinedIndexFile, jsonLine,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (IOException e) {
            System.err.println("Warning: Failed to index tool call: " + e.getMessage());
        }
    }

    /** Supported sort fields. */
    public enum SortField {
        TIMESTAMP, TOOL_NAME, CATEGORY, AGENT, PROJECT
    }

    /** Supported sort directions. */
    public enum SortDirection {
        ASC, DESC
    }

    /**
     * Search tool calls with full filter, sort, and project directory support.
     */
    public List<ToolCallRecord> search(String query, String toolName, String sessionId,
                                       String category, String agentName,
                                       String projectDirectory,
                                       SortField sortField, SortDirection sortDir,
                                       int limit) {
        String lowerQuery = query != null ? query.toLowerCase() : null;

        Stream<ToolCallRecord> stream = loadRecords(sessionId).stream()
                .filter(r -> toolName == null || r.getToolName().equalsIgnoreCase(toolName))
                .filter(r -> category == null || r.getCategory().equalsIgnoreCase(category))
                .filter(r -> agentName == null || r.getAgentName().equalsIgnoreCase(agentName))
                .filter(r -> projectDirectory == null || matchesProject(r, projectDirectory))
                .filter(r -> lowerQuery == null || matchesQuery(r, lowerQuery));

        Comparator<ToolCallRecord> cmp = buildComparator(
                sortField != null ? sortField : SortField.TIMESTAMP,
                sortDir != null ? sortDir : SortDirection.DESC);
        stream = stream.sorted(cmp);

        return stream.limit(limit > 0 ? limit : 100)
                .collect(Collectors.toList());
    }

    /** Backwards-compatible search (timestamp descending, no project filter). */
    public List<ToolCallRecord> search(String query, String toolName, String sessionId,
                                       String category, String agentName, int limit) {
        return search(query, toolName, sessionId, category, agentName,
                null, SortField.TIMESTAMP, SortDirection.DESC, limit);
    }

    /**
     * List tool calls for a specific session.
     */
    public List<ToolCallRecord> listBySession(String sessionId, int limit) {
        return search(null, null, sessionId, null, null, limit);
    }

    /**
     * Return tool calls grouped by category, each list sorted by the given sort field.
     */
    public Map<String, List<ToolCallRecord>> groupByCategory(String query, String toolName,
                                                              String sessionId, String agentName,
                                                              String projectDirectory,
                                                              SortField sortField, SortDirection sortDir,
                                                              int limitPerGroup) {
        List<ToolCallRecord> all = search(query, toolName, sessionId, null, agentName,
                projectDirectory, sortField, sortDir, Integer.MAX_VALUE);

        Map<String, List<ToolCallRecord>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getCategory() != null ? r.getCategory() : "general",
                        LinkedHashMap::new,
                        Collectors.toList()));

        // Apply per-group limit
        if (limitPerGroup > 0 && limitPerGroup < Integer.MAX_VALUE) {
            grouped.replaceAll((k, v) ->
                    v.size() > limitPerGroup ? v.subList(0, limitPerGroup) : v);
        }
        return grouped;
    }

    /**
     * Return tool calls grouped by project directory.
     */
    public Map<String, List<ToolCallRecord>> groupByProject(String query, String toolName,
                                                             String sessionId, String category,
                                                             String agentName,
                                                             SortField sortField, SortDirection sortDir,
                                                             int limitPerGroup) {
        List<ToolCallRecord> all = search(query, toolName, sessionId, category, agentName,
                null, sortField, sortDir, Integer.MAX_VALUE);

        Map<String, List<ToolCallRecord>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getProjectDirectory() != null ? r.getProjectDirectory() : "(unknown)",
                        LinkedHashMap::new,
                        Collectors.toList()));

        if (limitPerGroup > 0 && limitPerGroup < Integer.MAX_VALUE) {
            grouped.replaceAll((k, v) ->
                    v.size() > limitPerGroup ? v.subList(0, limitPerGroup) : v);
        }
        return grouped;
    }

    /**
     * Get aggregate statistics across all indexed tool calls.
     */
    public Map<String, Object> getStats() {
        List<ToolCallRecord> all = loadAllRecords();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalToolCalls", all.size());

        // Count by tool name
        Map<String, Long> byTool = all.stream()
                .collect(Collectors.groupingBy(ToolCallRecord::getToolName, Collectors.counting()));
        stats.put("byTool", sortByValueDesc(byTool));

        // Count by category
        Map<String, Long> byCategory = all.stream()
                .collect(Collectors.groupingBy(ToolCallRecord::getCategory, Collectors.counting()));
        stats.put("byCategory", sortByValueDesc(byCategory));

        // Count by agent
        Map<String, Long> byAgent = all.stream()
                .filter(r -> r.getAgentName() != null)
                .collect(Collectors.groupingBy(ToolCallRecord::getAgentName, Collectors.counting()));
        stats.put("byAgent", sortByValueDesc(byAgent));

        // Count by source
        Map<String, Long> bySource = all.stream()
                .filter(r -> r.getSource() != null)
                .collect(Collectors.groupingBy(ToolCallRecord::getSource, Collectors.counting()));
        stats.put("bySource", sortByValueDesc(bySource));

        // Count by project directory
        Map<String, Long> byProject = all.stream()
                .filter(r -> r.getProjectDirectory() != null)
                .collect(Collectors.groupingBy(ToolCallRecord::getProjectDirectory, Collectors.counting()));
        stats.put("byProject", sortByValueDesc(byProject));

        // Count by session
        Map<String, Long> bySess = all.stream()
                .collect(Collectors.groupingBy(ToolCallRecord::getSessionId, Collectors.counting()));
        stats.put("sessionCount", bySess.size());

        // Error count
        long errors = all.stream().filter(ToolCallRecord::isError).count();
        stats.put("totalErrors", errors);

        // Top 10 tools
        Map<String, Long> top10 = byTool.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        stats.put("topTools", top10);

        return stats;
    }

    /**
     * Get distinct tool names from the index.
     */
    public List<String> getDistinctToolNames() {
        return loadAllRecords().stream()
                .map(ToolCallRecord::getToolName)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get distinct categories from the index.
     */
    public List<String> getDistinctCategories() {
        return loadAllRecords().stream()
                .map(ToolCallRecord::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get distinct session IDs from the index.
     */
    public List<String> getDistinctSessionIds() {
        return loadAllRecords().stream()
                .map(ToolCallRecord::getSessionId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Get distinct project directories from the index.
     */
    public List<String> getDistinctProjects() {
        return loadAllRecords().stream()
                .map(ToolCallRecord::getProjectDirectory)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private List<ToolCallRecord> loadRecords(String sessionId) {
        if (sessionId != null) {
            Path sessionFile = toolCallsDir.resolve(sessionId + ".jsonl");
            return loadJsonlFile(sessionFile);
        }
        return loadAllRecords();
    }

    private List<ToolCallRecord> loadAllRecords() {
        return loadJsonlFile(combinedIndexFile);
    }

    private List<ToolCallRecord> loadJsonlFile(Path file) {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }

        List<ToolCallRecord> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    records.add(MAPPER.readValue(line, ToolCallRecord.class));
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to read tool call index: " + e.getMessage());
        }
        return records;
    }

    private boolean matchesQuery(ToolCallRecord record, String lowerQuery) {
        if (record.getToolName() != null && record.getToolName().toLowerCase().contains(lowerQuery)) return true;
        if (record.getToolInputSummary() != null && record.getToolInputSummary().toLowerCase().contains(lowerQuery)) return true;
        if (record.getCategory() != null && record.getCategory().toLowerCase().contains(lowerQuery)) return true;
        if (record.getAgentName() != null && record.getAgentName().toLowerCase().contains(lowerQuery)) return true;
        if (record.getSessionId() != null && record.getSessionId().toLowerCase().contains(lowerQuery)) return true;
        if (record.getToolInput() != null && record.getToolInput().toLowerCase().contains(lowerQuery)) return true;
        if (record.getProjectDirectory() != null && record.getProjectDirectory().toLowerCase().contains(lowerQuery)) return true;
        return false;
    }

    /**
     * Check if a record matches a project directory filter.
     * Supports exact match or substring/suffix match for flexibility.
     */
    private boolean matchesProject(ToolCallRecord record, String projectDirectory) {
        String proj = record.getProjectDirectory();
        if (proj == null) return false;
        // Exact match
        if (proj.equals(projectDirectory)) return true;
        // Normalize and try suffix match (e.g. "kompile" matches "/home/user/Documents/GitHub/kompile")
        String normFilter = projectDirectory.replace("\\", "/");
        String normProj = proj.replace("\\", "/");
        return normProj.endsWith("/" + normFilter) || normProj.equals(normFilter);
    }

    private Comparator<ToolCallRecord> buildComparator(SortField field, SortDirection dir) {
        Comparator<ToolCallRecord> cmp = switch (field) {
            case TIMESTAMP -> Comparator.comparing(
                    r -> r.getTimestamp() != null ? r.getTimestamp() : "", Comparator.naturalOrder());
            case TOOL_NAME -> Comparator.comparing(
                    r -> r.getToolName() != null ? r.getToolName() : "", String.CASE_INSENSITIVE_ORDER);
            case CATEGORY -> Comparator.comparing(
                    r -> r.getCategory() != null ? r.getCategory() : "", String.CASE_INSENSITIVE_ORDER);
            case AGENT -> Comparator.comparing(
                    r -> r.getAgentName() != null ? r.getAgentName() : "", String.CASE_INSENSITIVE_ORDER);
            case PROJECT -> Comparator.comparing(
                    r -> r.getProjectDirectory() != null ? r.getProjectDirectory() : "", String.CASE_INSENSITIVE_ORDER);
        };
        return dir == SortDirection.DESC ? cmp.reversed() : cmp;
    }

    /**
     * Create a short human-readable summary of the tool input.
     */
    private String summarize(String toolName, String rawInput) {
        if (rawInput == null || rawInput.isBlank()) return "";
        try {
            var node = MAPPER.readTree(rawInput);
            return switch (toolName) {
                case "Read", "Write" -> node.path("file_path").asText("");
                case "Edit" -> {
                    String file = node.path("file_path").asText("");
                    String old = node.path("old_string").asText("");
                    yield file + (old.isEmpty() ? "" : " (edit: " + truncate(old, 40) + ")");
                }
                case "Bash" -> truncate(node.path("command").asText(""), 80);
                case "Grep" -> {
                    String pattern = node.path("pattern").asText("");
                    String path = node.path("path").asText("");
                    yield pattern + (path.isEmpty() ? "" : " in " + path);
                }
                case "Glob" -> node.path("pattern").asText("");
                case "WebFetch" -> node.path("url").asText("");
                case "WebSearch" -> node.path("query").asText("");
                case "Agent" -> {
                    String desc = node.path("description").asText("");
                    String type = node.path("subagent_type").asText("");
                    yield (type.isEmpty() ? "" : "[" + type + "] ") + desc;
                }
                default -> {
                    // Try common field names
                    for (String field : List.of("file_path", "command", "pattern", "query", "path", "url", "description", "prompt")) {
                        String val = node.path(field).asText("");
                        if (!val.isEmpty()) {
                            yield truncate(val, 80);
                        }
                    }
                    yield truncate(rawInput, 60);
                }
            };
        } catch (Exception e) {
            return truncate(rawInput, 60);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", "");
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static <K> Map<K, Long> sortByValueDesc(Map<K, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<K, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
