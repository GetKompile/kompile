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
import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool that searches and retrieves previous conversation transcripts across all agents.
 * <p>
 * Actions:
 * <ul>
 *   <li><b>list</b> - List all saved conversations with metadata</li>
 *   <li><b>read</b> - Read the full transcript of a specific session</li>
 *   <li><b>search</b> - Grep-style search across all transcripts, with regex or literal
 *       pattern, optional filters (agent, session_id), configurable context lines, case
 *       sensitivity, inverted match, files-with-matches mode, and line numbers.</li>
 *   <li><b>recent</b> - Get the N most recent conversation summaries</li>
 * </ul>
 */
public class TranscriptSearchTool implements CliTool {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_TRANSCRIPT_LINES = 500;

    @Override
    public String id() { return "transcript_search"; }

    @Override
    public String description() {
        return "Grep across saved conversation transcripts from all agents. Actions: " +
                "'list' (all conversations), 'read' (full transcript by session_id), " +
                "'recent' (N most recent), and 'search' (grep-style search). " +
                "'search' supports regex or literal patterns, agent/session filters, " +
                "context lines (before/after/context), case sensitivity, inverted match, " +
                "files-with-matches mode, line numbers, and a result cap. " +
                "Useful for recalling context from prior sessions after compaction.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        prop(props, "action", "string",
                "Action: 'list', 'read', 'search', or 'recent'");
        prop(props, "session_id", "string",
                "Exact session ID for 'read'. For 'search', restricts to conversations whose " +
                        "session ID equals or starts with this value.");
        prop(props, "pattern", "string",
                "Search pattern for 'search' (regex by default; set 'literal' to true for plain text).");
        prop(props, "query", "string",
                "Alias for 'pattern' (backward compatibility).");
        prop(props, "literal", "boolean",
                "If true, treat 'pattern' as literal text, not a regex (default: false).");
        prop(props, "case_sensitive", "boolean",
                "If true, search is case-sensitive (default: false).");
        prop(props, "invert", "boolean",
                "If true, return lines that do NOT match the pattern (grep -v, default: false).");
        prop(props, "before", "integer",
                "Lines of context before each match (grep -B). Default: 1.");
        prop(props, "after", "integer",
                "Lines of context after each match (grep -A). Default: 1.");
        prop(props, "context", "integer",
                "Lines of context before AND after (grep -C). If set, overrides 'before' and 'after'.");
        prop(props, "agent", "string",
                "Filter to conversations whose recorded agent name contains this value " +
                        "(case-insensitive substring). Example: 'claude', 'gpt'.");
        prop(props, "max_results", "integer",
                "Maximum total matches to return across all transcripts (default: 50).");
        prop(props, "files_with_matches", "boolean",
                "If true, print only session IDs that contain a match (grep -l, default: false).");
        prop(props, "line_numbers", "boolean",
                "If true, prefix each match line with its line number (default: true).");
        prop(props, "count", "integer",
                "Number of results for 'recent' action (default: 5).");

        schema.putArray("required").add("action");
        return schema;
    }

    private static void prop(ObjectNode props, String name, String type, String description) {
        ObjectNode p = props.putObject(name);
        p.put("type", type);
        p.put("description", description);
    }

    @Override
    public String permissionKey() { return "transcript_search"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Search conversation transcripts");

        String action = params.path("action").asText("");

        switch (action) {
            case "list":
                return listConversations();
            case "read":
                return readTranscript(params.path("session_id").asText(""));
            case "search":
                return searchTranscripts(params);
            case "recent":
                return recentConversations(params.path("count").asInt(5));
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Use 'list', 'read', 'search', or 'recent'.");
        }
    }

    private ToolResult listConversations() {
        List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Saved conversations (").append(convos.size()).append(" total):\n\n");
        for (ChatHistory.ConversationSummary c : convos) {
            sb.append(String.format("  %-24s  %-20s  agent=%-8s  %s%n",
                    c.sessionId(), c.started(), c.agent(),
                    c.title().isEmpty() ? "(empty)" : c.title()));
        }
        return ToolResult.success("transcript_search: list", sb.toString(),
                Map.of("count", convos.size()));
    }

    private ToolResult readTranscript(String sessionId) {
        if (sessionId.isEmpty()) {
            return ToolResult.error("session_id is required for 'read' action");
        }

        if (!ChatHistory.exists(sessionId)) {
            return ToolResult.error("No transcript found for session: " + sessionId);
        }

        try {
            ChatHistory history = new ChatHistory(sessionId);
            String content = history.readTranscript();
            if (content == null || content.isBlank()) {
                return ToolResult.success("Transcript is empty for session: " + sessionId);
            }

            String[] lines = content.split("\n");
            if (lines.length > MAX_TRANSCRIPT_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < MAX_TRANSCRIPT_LINES; i++) {
                    sb.append(lines[i]).append("\n");
                }
                sb.append("\n... (truncated, ").append(lines.length - MAX_TRANSCRIPT_LINES).append(" more lines)");
                return ToolResult.success("transcript: " + sessionId, sb.toString(),
                        Map.of("sessionId", sessionId, "totalLines", lines.length, "truncated", true));
            }

            return ToolResult.success("transcript: " + sessionId, content,
                    Map.of("sessionId", sessionId, "totalLines", lines.length, "truncated", false));
        } catch (IOException e) {
            return ToolResult.error("Error reading transcript: " + e.getMessage());
        }
    }

    private ToolResult searchTranscripts(JsonNode params) {
        String pattern = params.path("pattern").asText("");
        if (pattern.isEmpty()) {
            pattern = params.path("query").asText("");
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("'pattern' (or 'query') is required for 'search' action");
        }

        boolean literal = params.path("literal").asBoolean(false);
        boolean caseSensitive = params.path("case_sensitive").asBoolean(false);
        boolean invert = params.path("invert").asBoolean(false);
        boolean filesWithMatches = params.path("files_with_matches").asBoolean(false);
        boolean lineNumbers = params.path("line_numbers").asBoolean(true);
        int maxResults = params.path("max_results").asInt(DEFAULT_MAX_RESULTS);
        if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;

        int before;
        int after;
        if (params.has("context") && !params.get("context").isNull()) {
            int c = Math.max(0, params.get("context").asInt(0));
            before = c;
            after = c;
        } else {
            before = Math.max(0, params.path("before").asInt(1));
            after = Math.max(0, params.path("after").asInt(1));
        }

        String agentFilter = params.path("agent").asText("").trim().toLowerCase(Locale.ROOT);
        String sessionFilter = params.path("session_id").asText("").trim();

        Pattern compiled;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            compiled = literal
                    ? Pattern.compile(Pattern.quote(pattern), flags)
                    : Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid regex pattern: " + e.getMessage() +
                    ". Set 'literal' to true to search literally.");
        }

        List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations to search.");
        }

        int scanned = 0;
        int filesMatched = 0;
        int totalMatches = 0;
        boolean truncated = false;
        StringBuilder sb = new StringBuilder();

        outer:
        for (ChatHistory.ConversationSummary c : convos) {
            if (!agentFilter.isEmpty()) {
                String a = c.agent() == null ? "" : c.agent().toLowerCase(Locale.ROOT);
                if (!a.contains(agentFilter)) continue;
            }
            if (!sessionFilter.isEmpty()) {
                if (!c.sessionId().equals(sessionFilter) && !c.sessionId().startsWith(sessionFilter)) {
                    continue;
                }
            }

            scanned++;

            String content;
            try {
                content = new ChatHistory(c.sessionId()).readTranscript();
            } catch (IOException e) {
                continue;
            }
            if (content == null) continue;

            String[] lines = content.split("\n", -1);
            List<int[]> matchRanges = new ArrayList<>();
            int fileMatchCount = 0;

            for (int i = 0; i < lines.length; i++) {
                boolean matched = compiled.matcher(lines[i]).find();
                if (invert) matched = !matched;
                if (!matched) continue;

                fileMatchCount++;
                totalMatches++;
                int start = Math.max(0, i - before);
                int end = Math.min(lines.length - 1, i + after);
                matchRanges.add(new int[]{start, i, end});
                if (totalMatches >= maxResults) {
                    truncated = true;
                    break;
                }
            }

            if (fileMatchCount == 0) continue;
            filesMatched++;

            if (filesWithMatches) {
                sb.append(c.sessionId())
                        .append("  (agent=").append(c.agent() == null || c.agent().isEmpty() ? "?" : c.agent())
                        .append(", matches=").append(fileMatchCount)
                        .append(")\n");
            } else {
                sb.append("Session: ").append(c.sessionId())
                        .append("  agent=").append(c.agent() == null || c.agent().isEmpty() ? "?" : c.agent())
                        .append("  (").append(c.started()).append(")\n");

                List<int[]> merged = mergeRanges(matchRanges);
                boolean[] isMatchLine = new boolean[lines.length];
                for (int[] m : matchRanges) {
                    isMatchLine[m[1]] = true;
                }

                for (int r = 0; r < merged.size(); r++) {
                    int[] range = merged.get(r);
                    int rStart = range[0];
                    int rEnd = range[1];
                    for (int j = rStart; j <= rEnd; j++) {
                        String prefix = isMatchLine[j] ? ">>> " : "    ";
                        if (lineNumbers) {
                            sb.append(prefix)
                                    .append(String.format("%6d: ", j + 1))
                                    .append(lines[j])
                                    .append("\n");
                        } else {
                            sb.append(prefix).append(lines[j]).append("\n");
                        }
                    }
                    if (r < merged.size() - 1) {
                        sb.append("    --\n");
                    }
                }
                sb.append("\n");
            }

            if (totalMatches >= maxResults) {
                truncated = true;
                break outer;
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pattern", pattern);
        meta.put("literal", literal);
        meta.put("caseSensitive", caseSensitive);
        meta.put("invert", invert);
        meta.put("agent", agentFilter.isEmpty() ? null : agentFilter);
        meta.put("scanned", scanned);
        meta.put("filesMatched", filesMatched);
        meta.put("matches", totalMatches);
        meta.put("truncated", truncated);

        if (totalMatches == 0) {
            return ToolResult.success(
                    "transcript_search: " + pattern,
                    "No matches found for: " + pattern +
                            " (scanned " + scanned + " conversation" + (scanned == 1 ? "" : "s") + ")",
                    meta);
        }

        String title = (filesWithMatches ? "transcript_search -l: " : "transcript_search: ") + pattern;
        if (truncated) {
            sb.append("\n... (truncated at max_results=").append(maxResults).append(")\n");
        }
        return ToolResult.success(title, sb.toString(), meta);
    }

    /**
     * Merge overlapping or adjacent context ranges so shared lines are printed once.
     * Entries are [contextStart, matchLine, contextEnd]; output is [start, end] pairs.
     * Assumes input is already in ascending order (matches are collected top-down).
     */
    private List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> result = new ArrayList<>();
        if (ranges.isEmpty()) return result;
        int curStart = ranges.get(0)[0];
        int curEnd = ranges.get(0)[2];
        for (int k = 1; k < ranges.size(); k++) {
            int s = ranges.get(k)[0];
            int e = ranges.get(k)[2];
            if (s <= curEnd + 1) {
                if (e > curEnd) curEnd = e;
            } else {
                result.add(new int[]{curStart, curEnd});
                curStart = s;
                curEnd = e;
            }
        }
        result.add(new int[]{curStart, curEnd});
        return result;
    }

    private ToolResult recentConversations(int count) {
        List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations found.");
        }

        int limit = Math.min(count, convos.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Recent conversations (").append(limit).append(" of ").append(convos.size()).append("):\n\n");

        for (int i = 0; i < limit; i++) {
            ChatHistory.ConversationSummary c = convos.get(i);
            sb.append(String.format("  %-24s  %-20s  agent=%-8s  %s%n",
                    c.sessionId(), c.started(), c.agent(),
                    c.title().isEmpty() ? "(empty)" : c.title()));
        }

        return ToolResult.success("transcript_search: recent", sb.toString(),
                Map.of("count", limit, "total", convos.size()));
    }
}
