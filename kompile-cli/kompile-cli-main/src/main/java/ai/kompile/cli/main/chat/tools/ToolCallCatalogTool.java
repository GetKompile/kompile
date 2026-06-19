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
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.ToolCallRecord;
import ai.kompile.cli.main.chat.TranscriptToolCallIndexer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool for searching, listing, and indexing tool calls across all agent sessions.
 * Provides unified access to the tool call catalog from any agent.
 *
 * Actions:
 * - search: Search tool calls by query with filters
 * - list: List tool calls with filters, sorting, and grouping
 * - stats: Get aggregate statistics
 * - index: Index tool calls from provider transcripts (Claude, Codex, Qwen, OpenCode, Gemini)
 * - filters: Get available filter options
 */
public class ToolCallCatalogTool implements CliTool {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public String id() {
        return "tool_call_catalog";
    }

    @Override
    public String description() {
        return "Search, list, and index tool calls across all agent sessions. " +
                "Actions: 'search' (query tool calls by text, tool name, category, project, agent), " +
                "'list' (list with filters and sorting), 'stats' (aggregate statistics), " +
                "'index' (index tool calls from provider transcripts like Claude, Codex, Qwen, OpenCode, Gemini), " +
                "'filters' (get available filter options). " +
                "Supports grouping by category, project, agent, or tool. " +
                "Useful for understanding what tools were used across sessions and projects.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        prop(props, "action", "string",
                "Action: 'search', 'list', 'stats', 'index', or 'filters'");
        prop(props, "query", "string",
                "Search query (for 'search' action). Matches tool name, input, category, agent, session, project.");
        prop(props, "tool", "string",
                "Filter by tool name (e.g. 'Read', 'Bash', 'Edit')");
        prop(props, "category", "string",
                "Filter by category (filesystem, shell, search, rag, agent, model, web, etc.)");
        prop(props, "agent", "string",
                "Filter by agent name (e.g. 'claude-code', 'codex', 'qwen')");
        prop(props, "session", "string",
                "Filter by session ID");
        prop(props, "project", "string",
                "Filter by project directory (exact or suffix match, e.g. 'kompile')");
        prop(props, "sort_by", "string",
                "Sort field: 'timestamp' (default), 'tool', 'category', 'agent', 'project'");
        prop(props, "sort_dir", "string",
                "Sort direction: 'desc' (default) or 'asc'");
        prop(props, "group_by", "string",
                "Group results by field (for 'list' action): 'category', 'project', 'agent', 'tool'");
        prop(props, "limit", "integer",
                "Maximum results to return (default 50)");
        prop(props, "source", "string",
                "For 'index' action: provider source to index ('all', 'claude-code', 'codex', 'qwen', 'opencode', 'gemini')");
        prop(props, "reindex", "boolean",
                "For 'index' action: re-index already indexed sessions (default false)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("").toLowerCase();

        return switch (action) {
            case "search" -> doSearch(params);
            case "list" -> doList(params);
            case "stats" -> doStats();
            case "index" -> doIndex(params);
            case "filters" -> doFilters();
            default -> ToolResult.error("Unknown action: '" + action
                    + "'. Use 'search', 'list', 'stats', 'index', or 'filters'.");
        };
    }

    private ToolResult doSearch(JsonNode params) {
        String query = params.path("query").asText(null);
        if (query == null || query.isBlank()) {
            return ToolResult.error("'query' parameter is required for 'search' action.");
        }

        ToolCallIndex index = ToolCallIndex.getInstance();
        List<ToolCallRecord> results = index.search(
                query,
                nullIfEmpty(params.path("tool").asText("")),
                nullIfEmpty(params.path("session").asText("")),
                nullIfEmpty(params.path("category").asText("")),
                nullIfEmpty(params.path("agent").asText("")),
                nullIfEmpty(params.path("project").asText("")),
                parseSortField(params.path("sort_by").asText("")),
                parseSortDir(params.path("sort_dir").asText("")),
                params.path("limit").asInt(50));

        return formatResults("search", results, query);
    }

    private ToolResult doList(JsonNode params) {
        ToolCallIndex index = ToolCallIndex.getInstance();
        String groupBy = nullIfEmpty(params.path("group_by").asText(""));

        if (groupBy != null) {
            return doGroupedList(params, index, groupBy);
        }

        List<ToolCallRecord> results = index.search(
                null,
                nullIfEmpty(params.path("tool").asText("")),
                nullIfEmpty(params.path("session").asText("")),
                nullIfEmpty(params.path("category").asText("")),
                nullIfEmpty(params.path("agent").asText("")),
                nullIfEmpty(params.path("project").asText("")),
                parseSortField(params.path("sort_by").asText("")),
                parseSortDir(params.path("sort_dir").asText("")),
                params.path("limit").asInt(50));

        return formatResults("list", results, null);
    }

    private ToolResult doGroupedList(JsonNode params, ToolCallIndex index, String groupBy) {
        ToolCallIndex.SortField sf = parseSortField(params.path("sort_by").asText(""));
        ToolCallIndex.SortDirection sd = parseSortDir(params.path("sort_dir").asText(""));
        int limit = params.path("limit").asInt(20);

        Map<String, List<ToolCallRecord>> grouped;
        if ("project".equals(groupBy) || "proj".equals(groupBy)) {
            grouped = index.groupByProject(null,
                    nullIfEmpty(params.path("tool").asText("")),
                    nullIfEmpty(params.path("session").asText("")),
                    nullIfEmpty(params.path("category").asText("")),
                    nullIfEmpty(params.path("agent").asText("")),
                    sf, sd, limit);
        } else {
            grouped = index.groupByCategory(null,
                    nullIfEmpty(params.path("tool").asText("")),
                    nullIfEmpty(params.path("session").asText("")),
                    nullIfEmpty(params.path("agent").asText("")),
                    nullIfEmpty(params.path("project").asText("")),
                    sf, sd, limit);
        }

        StringBuilder sb = new StringBuilder();
        int total = grouped.values().stream().mapToInt(List::size).sum();
        sb.append("Tool calls grouped by ").append(groupBy)
                .append(" (").append(total).append(" total, ")
                .append(grouped.size()).append(" groups)\n\n");

        for (var entry : grouped.entrySet()) {
            sb.append("## ").append(entry.getKey())
                    .append(" (").append(entry.getValue().size()).append(")\n");
            for (ToolCallRecord r : entry.getValue()) {
                sb.append(formatRecord(r)).append("\n");
            }
            sb.append("\n");
        }
        return ToolResult.success("Grouped tool calls", sb.toString());
    }

    private ToolResult doStats() {
        ToolCallIndex index = ToolCallIndex.getInstance();
        Map<String, Object> stats = index.getStats();

        StringBuilder sb = new StringBuilder();
        sb.append("Tool Call Statistics\n");
        sb.append("Total: ").append(stats.get("totalToolCalls")).append(" calls, ");
        sb.append(stats.get("totalErrors")).append(" errors, ");
        sb.append(stats.get("sessionCount")).append(" sessions\n\n");

        appendMapSection(sb, "Top Tools", stats, "topTools");
        appendMapSection(sb, "By Category", stats, "byCategory");
        appendMapSection(sb, "By Agent", stats, "byAgent");
        appendMapSection(sb, "By Source", stats, "bySource");
        appendMapSection(sb, "By Project", stats, "byProject");

        return ToolResult.success("Tool call statistics", sb.toString());
    }

    private ToolResult doIndex(JsonNode params) {
        String source = params.path("source").asText("all");
        boolean reindex = params.path("reindex").asBoolean(false);

        TranscriptToolCallIndexer indexer = new TranscriptToolCallIndexer();

        Set<String> sources = null;
        if (!"all".equalsIgnoreCase(source)) {
            sources = Set.of(source);
        }

        TranscriptToolCallIndexer.IndexResult result = indexer.indexAll(sources, reindex);

        StringBuilder sb = new StringBuilder();
        sb.append("Indexing complete.\n");
        sb.append("Sessions scanned: ").append(result.sessionsScanned()).append("\n");
        sb.append("Tool calls indexed: ").append(result.toolCallsIndexed()).append("\n");
        if (result.errors() > 0) {
            sb.append("Errors: ").append(result.errors()).append("\n");
        }
        if (!result.bySource().isEmpty()) {
            sb.append("\nBy source:\n");
            result.bySource().forEach((src, count) ->
                    sb.append("  ").append(src).append(": ").append(count).append("\n"));
        }

        return ToolResult.success("Transcript indexing", sb.toString());
    }

    private ToolResult doFilters() {
        ToolCallIndex index = ToolCallIndex.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("Available filter options:\n\n");
        sb.append("Tools: ").append(String.join(", ", index.getDistinctToolNames())).append("\n\n");
        sb.append("Categories: ").append(String.join(", ", index.getDistinctCategories())).append("\n\n");
        sb.append("Projects: ").append(String.join(", ", index.getDistinctProjects())).append("\n\n");
        sb.append("Sessions: ").append(index.getDistinctSessionIds().size()).append(" session(s)\n");
        return ToolResult.success("Filter options", sb.toString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ToolResult formatResults(String action, List<ToolCallRecord> results, String query) {
        if (results.isEmpty()) {
            String msg = query != null
                    ? "No tool calls found matching: " + query
                    : "No tool calls found.";
            return ToolResult.success(msg, msg);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" tool call(s)");
        if (query != null) sb.append(" matching \"").append(query).append("\"");
        sb.append("\n\n");
        for (ToolCallRecord r : results) {
            sb.append(formatRecord(r)).append("\n");
        }
        return ToolResult.success(action + ": " + results.size() + " results", sb.toString());
    }

    private String formatRecord(ToolCallRecord r) {
        String ts = r.getTimestamp();
        if (ts != null && ts.length() > 19) ts = ts.substring(0, 19).replace('T', ' ');
        String proj = r.getProjectDirectory();
        String projShort = "";
        if (proj != null) {
            String[] parts = proj.replace("\\", "/").split("/");
            projShort = parts[parts.length - 1];
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(ts != null ? ts : "?").append("] ");
        sb.append(r.getToolName()).append(" (").append(r.getCategory()).append(")");
        if (!projShort.isEmpty()) sb.append(" project=").append(projShort);
        sb.append(" agent=").append(r.getAgentName() != null ? r.getAgentName() : "?");
        if (r.getToolInputSummary() != null && !r.getToolInputSummary().isEmpty()) {
            sb.append("\n  ").append(r.getToolInputSummary());
        }
        if (r.isError()) sb.append("\n  [ERROR]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendMapSection(StringBuilder sb, String title, Map<String, Object> stats, String key) {
        Object val = stats.get(key);
        if (val instanceof Map<?, ?> map && !map.isEmpty()) {
            sb.append(title).append(":\n");
            ((Map<String, ?>) map).forEach((k, v) ->
                    sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
    }

    private static ToolCallIndex.SortField parseSortField(String s) {
        if (s == null || s.isEmpty()) return ToolCallIndex.SortField.TIMESTAMP;
        return switch (s.toLowerCase()) {
            case "tool", "toolname", "tool_name" -> ToolCallIndex.SortField.TOOL_NAME;
            case "category", "cat" -> ToolCallIndex.SortField.CATEGORY;
            case "agent" -> ToolCallIndex.SortField.AGENT;
            case "project", "proj" -> ToolCallIndex.SortField.PROJECT;
            default -> ToolCallIndex.SortField.TIMESTAMP;
        };
    }

    private static ToolCallIndex.SortDirection parseSortDir(String s) {
        if (s == null || s.isEmpty()) return ToolCallIndex.SortDirection.DESC;
        return "asc".equalsIgnoreCase(s) ? ToolCallIndex.SortDirection.ASC : ToolCallIndex.SortDirection.DESC;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static void prop(ObjectNode props, String name, String type, String desc) {
        ObjectNode p = props.putObject(name);
        p.put("type", type);
        p.put("description", desc);
    }
}
