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
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Persistent JSONL-based index of all tool calls from passthrough and emulated
 * passthrough sessions. Stores one JSONL file per session under
 * {@code ~/.kompile/conversations/tool-calls/} plus a combined index for
 * cross-session compatibility. Queries use a rebuildable SQLite projection;
 * the JSONL files remain the authoritative interchange/history sources.
 * <p>
 * Thread-safe: multiple threads can record tool calls concurrently.
 */
public class ToolCallIndex {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final String TOOL_CALLS_DIR = "tool-calls";
    private static final String COMBINED_INDEX = "all-tool-calls.jsonl";

    private final Path toolCallsDir;
    private final Path combinedIndexFile;
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    private static ToolCallIndex instance;

    private ToolCallIndex(Path toolCallsDir) {
        this.toolCallsDir = toolCallsDir;
        this.combinedIndexFile = toolCallsDir.resolve(COMBINED_INDEX);
    }

    /**
     * Resolves {@code ~/.kompile} on every call like the rest of Kompile's home state,
     * so a process whose {@code user.home} changes (tests) never keeps writing to the
     * previous home.
     */
    public static synchronized ToolCallIndex getInstance() {
        Path toolCallsDir = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(TOOL_CALLS_DIR);
        if (instance == null || !instance.toolCallsDir.equals(toolCallsDir)) {
            instance = new ToolCallIndex(toolCallsDir);
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

    /** Record local chat content under the exact catalog ID, including the provider call ID. */
    public void recordWithDetails(String sessionId, String toolName, String toolInput, String agentName,
            String source, boolean isError, long durationMs, String projectDirectory, String clientRequestId,
            ai.kompile.cli.main.chat.tools.ToolResult result, ai.kompile.cli.main.chat.tools.ToolResult rawResult) {
        String id = sessionId + "-" + idGenerator.incrementAndGet();
        Instant finished = Instant.now();
        ToolCallRecord record = new ToolCallRecord(id, sessionId, toolName, toolInput, summarize(toolName, toolInput),
                finished, source, agentName, isError, durationMs, ToolCallRecord.categorize(toolName), projectDirectory);
        try {
            var context = new ai.kompile.cli.common.metrics.ToolInvocationContext(id, sessionId, "transcript-resolved",
                    null, clientRequestId, null, null, toolName, toolName, agentName, source,
                    finished.toEpochMilli() - Math.max(0, durationMs));
            var details = new ai.kompile.cli.common.metrics.ToolInvocationDetails(toolCallsDir);
            details.request(context, toolInput);
            details.result(context, result == null ? null : result.getOutput(),
                    rawResult == null ? null : rawResult.getOutput(),
                    result == null || result.getMetadata() == null ? null : MAPPER.writeValueAsString(result.getMetadata()));
        } catch (Exception e) {
            System.err.println("Warning: Failed to save invocation details: " + e.getMessage());
        }
        appendRecord(record);
    }

    /**
     * Record a pre-built ToolCallRecord.
     */
    public void record(ToolCallRecord record) {
        appendRecord(record);
    }

    /**
     * Record a tool call WITH usage (typed overload; Task 2). Never packs usage into
     * the argument string. Writes the legacy line (usage block attached via the
     * record's optional usage map) to per-session file + combined index, and journals
     * the same usage into the shared usage journal under the cross-process lock.
     *
     * @param usage    usage payload (may be partially measured; absent fields are absent)
     * @param revision monotonically increasing revision for idempotent finalization
     * @return true when the usage journal write succeeded
     */
    public boolean recordWithUsage(ToolCallRecord record,
                                   ai.kompile.cli.common.metrics.ToolCallUsage usage,
                                   long revision) {
        appendRecord(record);
        return journalFor().record(usage, revision)
                instanceof ai.kompile.cli.common.metrics.ToolCallUsageJournal.WriteResult.Written;
    }

    /**
     * Journal usage WITHOUT writing a legacy catalog line (Task 3): the stdio dispatch
     * loop finalizes usage per exit, and the audit logger owns catalog-line emission.
     * Keeps one authoritative usage stream — no double counting across writers.
     */
    public void recordUsageDirect(ai.kompile.cli.common.metrics.ToolCallUsage usage) {
        journalFor().record(usage,
                usage.finishedEpochMs() != null ? usage.finishedEpochMs() : usage.startedEpochMs());
    }

    /** Lazy shared usage journal rooted in the same tool-calls directory. */
    private ai.kompile.cli.common.metrics.ToolCallUsageJournal journalFor() {
        ai.kompile.cli.common.metrics.ToolCallUsageJournal journal = usageJournal;
        if (journal == null) {
            synchronized (this) {
                if (usageJournal == null) {
                    usageJournal = new ai.kompile.cli.common.metrics.ToolCallUsageJournal(
                            ai.kompile.cli.common.metrics.ToolCallUsageJournal
                                    .defaultJournalFile(toolCallsDir), MAPPER);
                }
                journal = usageJournal;
            }
        }
        return journal;
    }

    private volatile ai.kompile.cli.common.metrics.ToolCallUsageJournal usageJournal;

    private synchronized void appendRecord(ToolCallRecord record) {
        try {
            Files.createDirectories(toolCallsDir);
            ai.kompile.cli.common.metrics.ToolCallUsageJournal
                    .validateSessionKey(record.getSessionId());
            String jsonLine = MAPPER.writeValueAsString(record) + "\n";

            ai.kompile.cli.common.metrics.ToolJournalFiles.appendCatalog(
                    toolCallsDir, record.getSessionId(), jsonLine);

        } catch (IllegalArgumentException unsafeSessionKey) {
            System.err.println("Warning: rejected unsafe session key for tool call index: "
                    + unsafeSessionKey.getMessage());
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
        return search(query, toolName, sessionId, category, agentName, projectDirectory, sortField, sortDir, limit, 0);
    }

    public List<ToolCallRecord> search(String query, String toolName, String sessionId,
                                       String category, String agentName, String projectDirectory,
                                       SortField sortField, SortDirection sortDir, int limit, int offset) {
        try {
            return searchIndex().catalogSearch(query, toolName, sessionId, category, agentName,
                    projectDirectory, sortField, sortDir, limit, offset);
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query rebuildable tool-call search index", e);
        }
    }

    public Map<String, List<ToolCallRecord>> groupedSearch(String groupBy, String query, String toolName,
            String sessionId, String category, String agent, String project,
            SortField sortField, SortDirection sortDir, int limitPerGroup) {
        try {
            List<ToolCallRecord> rows = searchIndex().catalogGrouped(query, toolName, sessionId,
                    category, agent, project, sortField, sortDir, limitPerGroup, groupBy);
            return rows.stream().collect(Collectors.groupingBy(row -> {
                String key = switch (groupBy) {
                    case "category" -> row.getCategory();
                    case "project" -> row.getProjectDirectory();
                    case "agent" -> row.getAgentName();
                    case "tool" -> row.getToolName();
                    default -> throw new IllegalArgumentException("Unsupported group_by: " + groupBy);
                };
                return key == null ? "category".equals(groupBy) ? "general" : "(unknown)" : key;
            }, LinkedHashMap::new, Collectors.toList()));
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query grouped tool-call search index", e);
        }
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
        List<ToolCallRecord> all;
        try {
            all = searchIndex().catalogGrouped(query, toolName, sessionId, null, agentName,
                    projectDirectory, sortField, sortDir, limitPerGroup, false);
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query grouped tool-call search index", e);
        }

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
        List<ToolCallRecord> all;
        try {
            all = searchIndex().catalogGrouped(query, toolName, sessionId, category, agentName,
                    null, sortField, sortDir, limitPerGroup, true);
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query grouped tool-call search index", e);
        }

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
        try {
            return searchIndex().catalogStats();
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query rebuildable tool-call search index", e);
        }
    }

    /**
     * Get distinct tool names from the index.
     */
    public List<String> getDistinctToolNames() {
        return distinct("tool");
    }

    /**
     * Get distinct categories from the index.
     */
    public List<String> getDistinctCategories() {
        return distinct("category");
    }

    /**
     * Get distinct session IDs from the index.
     */
    public List<String> getDistinctSessionIds() {
        return distinct("session");
    }

    /**
     * Get distinct project directories from the index.
     */
    public List<String> getDistinctProjects() {
        return distinct("project");
    }

    private ConversationSearchIndex searchIndex() {
        return new ConversationSearchIndex(toolCallsDir.getParent());
    }

    private List<String> distinct(String column) {
        try {
            return searchIndex().catalogDistinct(column);
        } catch (IOException | java.sql.SQLException e) {
            throw new IllegalStateException("Cannot query rebuildable tool-call search index", e);
        }
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
        return StringUtils.truncate(s, max);
    }

}
