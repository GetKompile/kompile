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

import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool that searches and retrieves previous conversation transcripts.
 * Enables agents to recall context from prior sessions, which is especially
 * useful after context compaction when earlier conversation history is lost.
 * <p>
 * Actions:
 * <ul>
 *   <li><b>list</b> - List all saved conversations with metadata</li>
 *   <li><b>read</b> - Read the full transcript of a specific session</li>
 *   <li><b>search</b> - Search across all transcripts for a keyword/pattern</li>
 *   <li><b>recent</b> - Get the N most recent conversation summaries</li>
 * </ul>
 */
public class TranscriptSearchTool implements CliTool {

    private static final int MAX_SEARCH_RESULTS = 50;
    private static final int MAX_TRANSCRIPT_LINES = 500;

    @Override
    public String id() { return "transcript_search"; }

    @Override
    public String description() {
        return "Search and retrieve previous conversation transcripts. Use 'list' to see all saved " +
                "conversations, 'read' to get a full transcript by session ID, 'search' to find " +
                "conversations containing a keyword or pattern, or 'recent' to get the N most recent " +
                "conversations. Useful for recalling context from prior sessions.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: 'list', 'read', 'search', or 'recent'");

        ObjectNode sessionId = props.putObject("session_id");
        sessionId.put("type", "string");
        sessionId.put("description", "Session ID for 'read' action");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search query for 'search' action (regex supported)");

        ObjectNode count = props.putObject("count");
        count.put("type", "integer");
        count.put("description", "Number of results for 'recent' action (default: 5)");

        schema.putArray("required").add("action");
        return schema;
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
                return searchTranscripts(params.path("query").asText(""));
            case "recent":
                return recentConversations(params.path("count").asInt(5));
            default:
                return ToolResult.error("Unknown action: " + action + ". Use 'list', 'read', 'search', or 'recent'.");
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

            // Truncate if too long
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

    private ToolResult searchTranscripts(String query) {
        if (query.isEmpty()) {
            return ToolResult.error("query is required for 'search' action");
        }

        List<ChatHistory.ConversationSummary> convos = ChatHistory.listConversations();
        if (convos.isEmpty()) {
            return ToolResult.success("No saved conversations to search.");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            // Fall back to literal search
            pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        }

        StringBuilder sb = new StringBuilder();
        int matchCount = 0;

        for (ChatHistory.ConversationSummary c : convos) {
            if (matchCount >= MAX_SEARCH_RESULTS) break;

            try {
                ChatHistory history = new ChatHistory(c.sessionId());
                String content = history.readTranscript();
                if (content == null) continue;

                String[] lines = content.split("\n");
                List<String> matches = new java.util.ArrayList<>();

                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        // Include context (1 line before, match, 1 line after)
                        int start = Math.max(0, i - 1);
                        int end = Math.min(lines.length - 1, i + 1);
                        StringBuilder match = new StringBuilder();
                        for (int j = start; j <= end; j++) {
                            match.append(j == i ? ">>> " : "    ").append(lines[j]).append("\n");
                        }
                        matches.add("  Line " + (i + 1) + ":\n" + match);
                        matchCount++;
                        if (matchCount >= MAX_SEARCH_RESULTS) break;
                    }
                }

                if (!matches.isEmpty()) {
                    sb.append("Session: ").append(c.sessionId())
                            .append(" (").append(c.started()).append(")\n");
                    for (String m : matches) {
                        sb.append(m);
                    }
                    sb.append("\n");
                }
            } catch (IOException e) {
                // Skip unreadable transcripts
            }
        }

        if (sb.length() == 0) {
            return ToolResult.success("No matches found for: " + query);
        }

        return ToolResult.success("transcript_search: " + query, sb.toString(),
                Map.of("query", query, "matchCount", matchCount));
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
