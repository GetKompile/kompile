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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ConversationSearchIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Tool that searches and retrieves previous conversation transcripts across all agents.
 * <p>
 * Actions:
 * <ul>
 *   <li><b>list</b> - List saved conversations with optional ID-prefix and agent filters</li>
 *   <li><b>read</b> - Read a session by exact ID or unique ID prefix, with lossless line pagination</li>
 *   <li><b>fulltext</b> - Explicit indexed FTS5 syntax, BM25 ranking and bounded snippets/pages</li>
 *   <li><b>rebuild_index</b> - Recreate projection rows from sources without changing history</li>
 *   <li><b>search</b> - Grep-style search across all transcripts, with regex or literal
 *       pattern, optional filters (agent, session_id), configurable context lines, case
 *       sensitivity, inverted match, files-with-matches mode, and line numbers.
 *       Without a content pattern, session_id performs an ID-prefix lookup.</li>
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
                "'list' (conversations, optionally filtered by session-ID prefix or agent), " +
                "'read' (paged transcript by exact ID or unique prefix), 'recent' (N most recent), " +
                "'fulltext' (explicit indexed FTS5/BM25 search, not regex), 'rebuild_index' (rebuild disposable projection), " +
                "and 'search' (grep-style search, or ID-prefix lookup without a pattern). " +
                "Content search supports regex/literal patterns, agent/session filters, " +
                "context lines (before/after/context), case sensitivity, inverted match, " +
                "files-with-matches mode, line numbers, and offset paging of matches. " +
                "Useful for recalling context from prior sessions after compaction.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        prop(props, "action", "string",
                "Action: 'list', 'read', 'search', 'recent', 'fulltext', or 'rebuild_index'");
        prop(props, "session_id", "string",
                "Session ID or literal, case-sensitive ID prefix for 'list' and 'search'. " +
                        "For 'read', an exact ID wins; a prefix must match exactly one session. " +
                        "Example: 'abc123' matches IDs beginning with 'abc123' (no wildcard needed).");
        prop(props, "pattern", "string",
                "Content pattern for 'search' (regex by default; set 'literal' to true for plain text). " +
                        "Omit when supplying 'session_id' to list matching IDs without searching content.");
        prop(props, "query", "string",
                "For 'search': alias for 'pattern' (backward compatibility). For 'fulltext': raw FTS5 query " +
                        "(tokens, quoted phrases, prefixes, AND/OR/NOT); ranked by BM25, never interpreted as regex.");
        prop(props, "literal", "boolean",
                "If true, treat 'pattern' as literal text, not a regex (default: false).");
        prop(props, "case_sensitive", "boolean",
                "If true, content matching is case-sensitive (default: false). ID prefixes are always case-sensitive.");
        prop(props, "invert", "boolean",
                "If true, return lines that do NOT match the pattern (grep -v, default: false).");
        prop(props, "before", "integer",
                "Lines of context before each match (grep -B). Default: 1.");
        prop(props, "after", "integer",
                "Lines of context after each match (grep -A). Default: 1.");
        prop(props, "context", "integer",
                "Lines of context before AND after (grep -C). If set, overrides 'before' and 'after'.");
        prop(props, "agent", "string",
                "For 'list', 'search', and 'fulltext', filter to conversations whose recorded agent name contains this value " +
                        "(case-insensitive substring). Example: 'claude', 'gpt'.");
        prop(props, "max_results", "integer",
                "Maximum content matches for 'search' (default: 50), or sessions for 'list' / " +
                        "ID-only 'search' (default: all).");
        prop(props, "files_with_matches", "boolean",
                "If true, print only session IDs that contain a match (grep -l, default: false).");
        prop(props, "line_numbers", "boolean",
                "If true, prefix each match line with its line number (default: true).");
        prop(props, "count", "integer",
                "Number of results for 'recent' action (default: 5).");
        prop(props, "offset", "integer",
                "For 'read': starting line (1-based, default 1). For 'search'/'fulltext': match offset (0-based, default 0); continue with metadata.nextOffset.");
        prop(props, "limit", "integer",
                "For 'read': lines per page (1..2000, default 500), preserving entire long lines. " +
                        "For 'fulltext': hits per page (1..200, default 50); metadata.nextOffset continues the page.");
        prop(props, "project", "string", "Fulltext project directory filter: exact path or directory suffix.");
        prop(props, "since", "string", "Fulltext: inclusive recorded session start date/time (YYYY-MM-DD or local YYYY-MM-DD HH:mm:ss).");
        prop(props, "until", "string", "Fulltext: inclusive recorded session start date/time; a date includes that whole day.");

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
                return listConversations(params);
            case "read":
                return readTranscript(params);
            case "fulltext":
                return fulltext(params);
            case "rebuild_index":
                try {
                    searchIndex().rebuild();
                    return ToolResult.success("Rebuilt disposable conversation search index from transcript and catalog sources.");
                } catch (IOException | java.sql.SQLException e) {
                    return ToolResult.error("Error rebuilding conversation search index: " + e.getMessage());
                }
            case "search":
                return searchTranscripts(params);
            case "recent":
                return recentConversations(params.path("count").asInt(5));
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Use 'list', 'read', 'search', 'recent', 'fulltext', or 'rebuild_index'.");
        }
    }

    private List<ChatHistory.ConversationSummary> filteredConversations(JsonNode params) {
        String sessionPrefix = params.path("session_id").asText("").trim();
        String agentFilter = params.path("agent").asText("").trim().toLowerCase(Locale.ROOT);
        return ChatHistory.listConversations().stream()
                .filter(c -> c.sessionId().startsWith(sessionPrefix))
                .filter(c -> agentFilter.isEmpty()
                        || (c.agent() != null && c.agent().toLowerCase(Locale.ROOT).contains(agentFilter)))
                .toList();
    }

    private ToolResult listConversations(JsonNode params) {
        List<ChatHistory.ConversationSummary> convos = filteredConversations(params);
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations found matching the supplied filters.");
        }

        int maxResults = params.path("max_results").asInt(Integer.MAX_VALUE);
        if (maxResults <= 0) maxResults = Integer.MAX_VALUE;
        int limit = Math.min(maxResults, convos.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Saved conversations (").append(limit).append(" of ")
                .append(convos.size()).append(" matching):\n\n");
        for (ChatHistory.ConversationSummary c : convos.subList(0, limit)) {
            sb.append(String.format("  %-24s  %-20s  agent=%-8s  %s%n",
                    c.sessionId(), c.started(), c.agent(),
                    c.title().isEmpty() ? "(empty)" : c.title()));
        }
        if (limit < convos.size()) {
            sb.append("\n... (truncated at max_results=").append(maxResults).append(")\n");
        }
        return ToolResult.success("transcript_search: list", sb.toString(),
                Map.of("count", limit, "total", convos.size(), "truncated", limit < convos.size()));
    }

    private List<String> matchingTranscriptIds(String prefix) throws IOException {
        Path directory = KompileHome.homeDirectory().toPath().resolve("conversations");
        if (!Files.exists(directory)) return List.of();
        // Resolve against filenames, including header-only or unreadable transcripts.
        // Conversation summaries intentionally omit those and cannot establish uniqueness.
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".txt"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .filter(id -> id.startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }

    private ToolResult readTranscript(JsonNode params) {
        String sessionId = params.path("session_id").asText("").trim();
        int offset = params.path("offset").asInt(1);
        int limit = params.path("limit").asInt(MAX_TRANSCRIPT_LINES);
        if (offset < 1 || limit < 1 || limit > 2000) {
            return ToolResult.error("read requires offset >= 1 and limit between 1 and 2000");
        }
        if (sessionId.isEmpty()) {
            return ToolResult.error("session_id is required for 'read' action");
        }
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.equals(".") || sessionId.equals("..")) {
            return ToolResult.error("session_id must be a transcript ID, not a path");
        }

        if (!ChatHistory.exists(sessionId)) {
            List<String> matches;
            try {
                matches = matchingTranscriptIds(sessionId);
            } catch (IOException e) {
                return ToolResult.error("Error resolving transcript prefix: " + e.getMessage());
            }
            if (matches.isEmpty()) {
                return ToolResult.error("No transcript found for session ID or prefix: " + sessionId);
            }
            if (matches.size() > 1) {
                return ToolResult.error("Ambiguous session ID prefix: " + sessionId +
                        " (" + matches.size() + " sessions). Supply a longer prefix or an exact ID.\n" +
                        String.join("\n", matches.subList(0, Math.min(10, matches.size()))));
            }
            sessionId = matches.get(0);
        }

        try {
            Path file = new ChatHistory(sessionId).getTranscriptFile();
            StringBuilder page = new StringBuilder();
            int total = 0, returned = 0;
            boolean nonBlank = false;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    total++;
                    nonBlank |= !line.isBlank();
                    if (total >= offset && returned < limit) {
                        page.append(line).append('\n');
                        returned++;
                    }
                }
            }
            if (!nonBlank) return ToolResult.success("Transcript is empty for session: " + sessionId);
            boolean more = (long) offset + returned <= total;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sessionId", sessionId);
            metadata.put("totalLines", total);
            metadata.put("offset", offset);
            metadata.put("returnedLines", returned);
            metadata.put("truncated", more);
            metadata.put("nextOffset", more ? offset + returned : null);
            if (more) page.append("\n... (continued: read session_id=").append(sessionId)
                    .append(" offset=").append(offset + returned).append(" limit=").append(limit).append(")\n");
            return ToolResult.success("transcript: " + sessionId, page.toString(), metadata);
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
            if (!params.path("session_id").asText("").trim().isEmpty()) {
                return listConversations(params);
            }
            return ToolResult.error("'pattern' (or 'query') or a 'session_id' prefix is required for 'search' action");
        }

        boolean literal = params.path("literal").asBoolean(false);
        boolean caseSensitive = params.path("case_sensitive").asBoolean(false);
        boolean invert = params.path("invert").asBoolean(false);
        boolean filesWithMatches = params.path("files_with_matches").asBoolean(false);
        boolean lineNumbers = params.path("line_numbers").asBoolean(true);
        int maxResults = params.path("max_results").asInt(DEFAULT_MAX_RESULTS);
        if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
        int offset = params.path("offset").asInt(0);
        if (offset < 0 || offset > Integer.MAX_VALUE - maxResults) {
            return ToolResult.error("offset must be non-negative and leave room for a page");
        }

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

        List<ChatHistory.ConversationSummary> convos = filteredConversations(params);
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations to search matching the supplied filters.");
        }

        int scanned = 0;
        int filesMatched = 0;
        int totalMatches = 0;
        int skippedMatches = 0;
        boolean truncated = false;
        StringBuilder sb = new StringBuilder();

        outer:
        for (ChatHistory.ConversationSummary c : convos) {
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

                if (skippedMatches < offset) {
                    skippedMatches++;
                    continue;
                }
                if (totalMatches >= maxResults) {
                    truncated = true;
                    break;
                }
                fileMatchCount++;
                totalMatches++;
                int start = Math.max(0, i - before);
                int end = Math.min(lines.length - 1, i + after);
                matchRanges.add(new int[]{start, i, end});
            }

            if (fileMatchCount == 0) {
                if (truncated) break outer;
                continue;
            }
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

            if (truncated) break outer;
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
        meta.put("offset", offset);
        meta.put("nextOffset", truncated ? offset + totalMatches : null);
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
            sb.append("\n... (truncated at max_results=").append(maxResults)
                    .append("; continue with the same filters and offset=").append(offset + totalMatches).append(")\n");
        }
        return ToolResult.success(title, sb.toString(), meta);
    }

    private ConversationSearchIndex searchIndex() {
        return new ConversationSearchIndex(KompileHome.homeDirectory().toPath().resolve("conversations"));
    }

    private ToolResult fulltext(JsonNode params) {
        String query = params.path("query").asText(params.path("pattern").asText(""));
        if (params.path("literal").asBoolean(false) || params.path("invert").asBoolean(false)
                || params.path("case_sensitive").asBoolean(false) || params.path("files_with_matches").asBoolean(false)) {
            return ToolResult.error("fulltext uses FTS5 syntax, not grep options. Use action=search for exact regex/literal behavior.");
        }
        try {
            ConversationSearchIndex.Page page = searchIndex().search(query,
                    params.path("session_id").asText("").trim(), params.path("agent").asText("").trim(),
                    params.path("project").asText("").trim(), params.path("since").asText("").trim(),
                    params.path("until").asText("").trim(), params.path("offset").asInt(0),
                    params.path("limit").asInt(params.path("max_results").asInt(DEFAULT_MAX_RESULTS)));
            StringBuilder output = new StringBuilder("Indexed fulltext hits (BM25; lower score ranks first):\n");
            for (ConversationSearchIndex.Hit hit : page.hits()) {
                output.append(hit.sessionId()).append(':').append(hit.line()).append(" chunk=")
                        .append(hit.chunk()).append(" agent=").append(hit.agent()).append(" project=")
                        .append(hit.project()).append('\n').append(hit.snippet()).append('\n');
            }
            if (page.hits().isEmpty()) output.append("No fulltext matches.\n");
            if (page.nextOffset() != null) output.append("Continue with offset=").append(page.nextOffset()).append('\n');
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("mode", "fts5"); metadata.put("matches", page.hits().size());
            metadata.put("offset", page.offset()); metadata.put("nextOffset", page.nextOffset());
            metadata.put("truncated", page.nextOffset() != null);
            return ToolResult.success("transcript_search: fulltext", output.toString(), metadata);
        } catch (IllegalArgumentException | IOException | java.sql.SQLException e) {
            return ToolResult.error("Indexed fulltext search failed: " + e.getMessage() +
                    ". FTS5 queries are not regex; use action=search for grep behavior.");
        }
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
