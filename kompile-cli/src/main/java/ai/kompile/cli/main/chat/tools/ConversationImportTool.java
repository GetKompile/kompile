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
import ai.kompile.cli.common.chat.aggregate.AggregateChatSourceService;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Imports conversations from external CLI tools into kompile's plain-text
 * transcript format at {@code ~/.kompile/conversations/}. All per-source
 * parsing is delegated to {@link ChatSourceAdapter} instances resolved via
 * {@link ChatSourceRegistry}.
 */
public class ConversationImportTool implements CliTool {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final String SEPARATOR = "──────────────────────────────────";

    @Override
    public String id() {
        return "conversation_import";
    }

    @Override
    public String description() {
        return "Import conversations from external CLI tools into kompile's transcript format. " +
                "Supports Claude Code (~/.claude/projects/), OpenCode (SQLite database), " +
                "Codex (~/.codex/history.jsonl), and Qwen (~/.qwen/projects/). " +
                "Use 'discover' to find available sources, 'list' to see conversations from a source, " +
                "'import' to import a specific conversation, or 'import-all' to import everything from a source.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: 'discover', 'list', 'import', or 'import-all'");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "Source to import from: 'claude-code', 'opencode', or 'codex'. Required for 'list', 'import', and 'import-all'.");

        ObjectNode conversationId = props.putObject("conversation_id");
        conversationId.put("type", "string");
        conversationId.put("description", "Conversation ID or filename to import. Required for 'import' action.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "conversation_import";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Import conversations from external CLI tools");

        String action = params.path("action").asText("");
        String source = params.path("source").asText("");
        String conversationId = params.path("conversation_id").asText("");

        switch (action) {
            case "discover":
                return doDiscover();
            case "list":
                return doList(source);
            case "import":
                return doImport(source, conversationId);
            case "import-all":
                return doImportAll(source);
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Use 'discover', 'list', 'import', or 'import-all'.");
        }
    }

    // ─── discover ───────────────────────────────────────────────────────

    private ToolResult doDiscover() {
        AggregateChatSourceService aggregate = new AggregateChatSourceService();
        List<SourceInfo> infos = aggregate.discoverAll();

        StringBuilder sb = new StringBuilder();
        sb.append("Conversation source discovery:\n\n");
        int totalFound = 0;
        for (SourceInfo info : infos) {
            sb.append("  ").append(info.source()).append(": ").append(info.path()).append("\n");
            if (info.available()) {
                sb.append("    Found ").append(info.sessionCount()).append(" session(s)\n\n");
                totalFound += info.sessionCount();
            } else {
                sb.append("    unavailable");
                if (info.reason() != null && !info.reason().isBlank()) {
                    sb.append(" (").append(info.reason()).append(")");
                }
                sb.append("\n\n");
            }
        }
        sb.append("Total found: ").append(totalFound).append(" conversation(s)");
        return ToolResult.success("conversation_import: discover", sb.toString(),
                Map.of("totalFound", totalFound, "sourceCount", infos.size()));
    }

    // ─── list ───────────────────────────────────────────────────────────

    private ToolResult doList(String source) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'list' action.");
        }
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);
        if (adapter == null) {
            return ToolResult.error("Unknown source: " + source
                    + " (known: " + ChatSourceRegistry.getInstance().ids() + ")");
        }
        try {
            List<ChatSessionSummary> sessions = adapter.list();
            if (sessions.isEmpty()) {
                return ToolResult.success("No conversations found for " + source);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(adapter.displayName()).append(" conversations (")
                    .append(sessions.size()).append("):\n\n");
            for (ChatSessionSummary s : sessions) {
                sb.append("  ").append(s.sessionId()).append("  ");
                String title = (s.title() == null || s.title().isBlank()) ? "(no title)" : s.title();
                sb.append(title);
                sb.append("  (").append(s.messageCount()).append(" messages)\n");
            }
            return ToolResult.success("conversation_import: list " + source, sb.toString(),
                    Map.of("count", sessions.size()));
        } catch (IOException e) {
            return ToolResult.error("Failed to list " + source + " conversations: " + e.getMessage());
        }
    }

    // ─── import (single) ────────────────────────────────────────────────

    private ToolResult doImport(String source, String conversationId) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'import' action.");
        }
        if (conversationId.isEmpty()) {
            return ToolResult.error("'conversation_id' is required for 'import' action.");
        }
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);
        if (adapter == null) {
            return ToolResult.error("Unknown source: " + source);
        }
        try {
            List<ChatTurn> turns = adapter.readTurns(conversationId);
            if (turns.isEmpty()) {
                return ToolResult.error("No messages found for " + source + " session: " + conversationId);
            }
            String targetId = sanitizeId(conversationId);
            Path target = writeTranscript(targetId, source, turns);
            return ToolResult.success(
                    "conversation_import: " + source + "/" + targetId,
                    "Imported " + turns.size() + " message(s) from " + source
                            + " conversation " + conversationId + " to " + target,
                    Map.of("imported", turns.size(), "target", target.toString()));
        } catch (IOException e) {
            return ToolResult.error("Failed to import " + source + " session "
                    + conversationId + ": " + e.getMessage());
        }
    }

    // ─── import-all ─────────────────────────────────────────────────────

    private ToolResult doImportAll(String source) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'import-all' action.");
        }
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);
        if (adapter == null) {
            return ToolResult.error("Unknown source: " + source);
        }
        try {
            List<ChatSessionSummary> sessions = adapter.list();
            if (sessions.isEmpty()) {
                return ToolResult.success("No conversations found for " + source);
            }
            int imported = 0;
            int skipped = 0;
            int failed = 0;
            StringBuilder details = new StringBuilder();
            for (ChatSessionSummary s : sessions) {
                try {
                    List<ChatTurn> turns = adapter.readTurns(s.sessionId());
                    if (turns.isEmpty()) {
                        skipped++;
                        details.append("  [SKIP] ").append(s.sessionId()).append(" (no messages)\n");
                        continue;
                    }
                    Path target = writeTranscript(sanitizeId(s.sessionId()), source, turns);
                    imported++;
                    details.append("  [OK]   ").append(s.sessionId())
                            .append(" -> ").append(target).append("\n");
                } catch (Exception e) {
                    failed++;
                    details.append("  [FAIL] ").append(s.sessionId())
                            .append(": ").append(e.getMessage()).append("\n");
                }
            }
            return buildImportResult(source, imported, skipped, failed, details);
        } catch (IOException e) {
            return ToolResult.error("Failed to import-all from " + source + ": " + e.getMessage());
        }
    }

    private ToolResult buildImportResult(String source, int imported, int skipped,
                                         int failed, StringBuilder details) {
        StringBuilder out = new StringBuilder();
        out.append("Imported ").append(imported).append(" conversation(s) from ").append(source);
        if (skipped > 0) out.append(" (skipped ").append(skipped).append(")");
        if (failed > 0) out.append(" (failed ").append(failed).append(")");
        out.append(":\n\n").append(details);
        return ToolResult.success(
                "conversation_import: import-all " + source,
                out.toString(),
                Map.of("imported", imported, "skipped", skipped, "failed", failed));
    }

    // ─── transcript output ──────────────────────────────────────────────

    private Path writeTranscript(String targetId, String source, List<ChatTurn> turns) throws IOException {
        Path conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        Files.createDirectories(conversationsDir);

        Path targetFile = conversationsDir.resolve(targetId + ".txt");
        String agentName = getAgentName(source);

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(targetFile.toFile(), false),
                        StandardCharsets.UTF_8)), true)) {

            writer.println("──── Conversation: " + targetId + " ────");
            writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
            writer.println("Server:  (imported from " + source + ")");
            writer.println("Agent:   " + agentName);
            writer.println("RAG:     disabled");
            writer.println();
            writer.println(SEPARATOR);
            writer.println();

            for (ChatTurn turn : turns) {
                if ("user".equals(turn.role())) {
                    writer.println("> " + turn.content());
                } else {
                    writer.println(turn.content());
                }
                writer.println();
            }
        }
        return targetFile;
    }

    // ─── helpers (retained for legacy reflection-based tests) ───────────

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String getAgentName(String source) {
        switch (source) {
            case "claude-code":
                return "claude";
            case "opencode":
                return "opencode";
            case "codex":
                return "codex";
            case "qwen":
                return "qwen";
            default:
                return "unknown";
        }
    }
}
