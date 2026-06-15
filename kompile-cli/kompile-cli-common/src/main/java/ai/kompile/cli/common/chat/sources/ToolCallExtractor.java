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

package ai.kompile.cli.common.chat.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Extracts structured tool call records from provider transcript files.
 * Unlike ChatAdapterSupport which only captures tool names as text annotations,
 * this extracts full tool call details (name, input JSON, error status).
 * <p>
 * Shared between CLI (TranscriptToolCallIndexer) and Spring (TranscriptIndexingService).
 */
public class ToolCallExtractor {

    private static final ObjectMapper MAPPER = ChatAdapterSupport.MAPPER;

    /** A single extracted tool call. */
    public record ExtractedToolCall(
            String toolName,
            String toolInput,
            boolean isError,
            String agentName,
            String projectDirectory
    ) {}

    /** Result of extracting from one session. */
    public record ExtractionResult(
            String sessionId,
            String source,
            String projectDirectory,
            List<ExtractedToolCall> toolCalls
    ) {}

    // ── Claude ──────────────────────────────────────────────────────────────

    public static ExtractionResult extractClaude(Path jsonlFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);

                    // Resolve cwd from first line that has it
                    if (workDir == null) {
                        JsonNode cwd = node.path("cwd");
                        if (cwd.isTextual()) workDir = cwd.asText();
                    }

                    String type = node.path("type").asText("");
                    if ("assistant".equals(type)) {
                        JsonNode content = node.path("message").path("content");
                        if (content.isArray()) {
                            for (JsonNode block : content) {
                                if ("tool_use".equals(block.path("type").asText(""))) {
                                    String toolName = block.path("name").asText("unknown");
                                    JsonNode input = block.path("input");
                                    String inputStr = input.isMissingNode() ? "{}" : input.toString();
                                    String agent = "claude-code";
                                    if ("Agent".equals(toolName)) {
                                        String sub = input.path("subagent_type").asText("unknown");
                                        toolName = "Agent:" + sub;
                                    }
                                    calls.add(new ExtractedToolCall(toolName, inputStr, false, agent, null));
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        // Set projectDirectory on all calls
        String wd = workDir;
        if (wd != null) {
            calls = calls.stream()
                    .map(c -> new ExtractedToolCall(c.toolName, c.toolInput, c.isError, c.agentName, wd))
                    .toList();
        }
        return new ExtractionResult(sessionId, "claude-code", workDir, calls);
    }

    // ── Pi ──────────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from a Pi agent JSONL session file.
     * Pi uses the same Anthropic-style content blocks as Claude ({@code tool_use}),
     * but the working directory is in the session header (line 1: {@code type=session, cwd=...}).
     */
    public static ExtractionResult extractPi(Path jsonlFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String type = node.path("type").asText("");

                    // Session header carries the cwd
                    if ("session".equals(type) && workDir == null) {
                        String cwd = node.path("cwd").asText("");
                        if (!cwd.isEmpty()) workDir = cwd;
                        continue;
                    }

                    // Assistant entries contain tool_use blocks in content array
                    if ("assistant".equals(type)) {
                        JsonNode content = node.path("message").path("content");
                        if (content.isArray()) {
                            for (JsonNode block : content) {
                                if ("tool_use".equals(block.path("type").asText(""))) {
                                    String toolName = block.path("name").asText("unknown");
                                    JsonNode input = block.path("input");
                                    String inputStr = input.isMissingNode() ? "{}" : input.toString();
                                    calls.add(new ExtractedToolCall(toolName, inputStr, false, "pi", null));
                                }
                            }
                        }
                    }

                    // toolResult entries carry error status
                    if ("toolResult".equals(type)) {
                        JsonNode message = node.path("message");
                        boolean isError = message.path("isError").asBoolean(false);
                        if (isError) {
                            String toolName = message.path("name").asText(
                                    node.path("toolName").asText("unknown"));
                            String content = message.path("content").isTextual()
                                    ? message.path("content").asText() : "{}";
                            calls.add(new ExtractedToolCall(toolName, content, true, "pi", null));
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        String wd = workDir;
        if (wd != null) {
            calls = calls.stream()
                    .map(c -> new ExtractedToolCall(c.toolName, c.toolInput, c.isError, c.agentName, wd))
                    .toList();
        }
        return new ExtractionResult(sessionId, "pi", workDir, calls);
    }

    // ── Codex ───────────────────────────────────────────────────────────────

    public static ExtractionResult extractCodex(Path jsonlFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    JsonNode payload = node.path("payload");

                    if ("session_meta".equals(type) && workDir == null) {
                        String cwd = payload.path("cwd").asText("");
                        if (!cwd.isEmpty()) workDir = cwd;
                    }

                    if ("response_item".equals(type)) {
                        String pType = payload.path("type").asText("");
                        if ("function_call".equals(pType)) {
                            calls.add(new ExtractedToolCall(
                                    payload.path("name").asText("unknown"),
                                    payload.path("arguments").toString(),
                                    false, "codex", null));
                        } else if ("custom_tool_call".equals(pType)) {
                            boolean err = "error".equalsIgnoreCase(payload.path("status").asText(""));
                            calls.add(new ExtractedToolCall(
                                    payload.path("name").asText("unknown"),
                                    payload.path("arguments").toString(),
                                    err, "codex", null));
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        String wd = workDir;
        if (wd != null) {
            calls = calls.stream()
                    .map(c -> new ExtractedToolCall(c.toolName, c.toolInput, c.isError, c.agentName, wd))
                    .toList();
        }
        return new ExtractionResult(sessionId, "codex", workDir, calls);
    }

    // ── Qwen ────────────────────────────────────────────────────────────────

    public static ExtractionResult extractQwen(Path jsonlFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);

                    if (workDir == null) {
                        JsonNode cwd = node.path("cwd");
                        if (cwd.isTextual()) workDir = cwd.asText();
                    }

                    if ("assistant".equals(node.path("type").asText(""))) {
                        JsonNode parts = node.path("message").path("parts");
                        if (parts.isArray()) {
                            for (JsonNode part : parts) {
                                if (part.path("thought").asBoolean(false)) continue;
                                JsonNode funcCall = part.path("functionCall");
                                if (!funcCall.isMissingNode()) {
                                    calls.add(new ExtractedToolCall(
                                            funcCall.path("name").asText("unknown"),
                                            funcCall.path("args").toString(),
                                            false, "qwen", null));
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        String wd = workDir;
        if (wd != null) {
            calls = calls.stream()
                    .map(c -> new ExtractedToolCall(c.toolName, c.toolInput, c.isError, c.agentName, wd))
                    .toList();
        }
        return new ExtractionResult(sessionId, "qwen", workDir, calls);
    }

    // ── OpenCode ────────────────────────────────────────────────────────────

    public static ExtractionResult extractOpenCode(String sessionId) throws IOException {
        Path dbPath = ChatAdapterSupport.userHome().resolve(".local/share/opencode/opencode.db");
        if (!Files.exists(dbPath)) {
            dbPath = ChatAdapterSupport.userHome().resolve(".opencode/opencode.db");
            if (!Files.exists(dbPath)) return new ExtractionResult(sessionId, "opencode", null, Collections.emptyList());
        }

        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (java.sql.Statement s = conn.createStatement()) { s.execute("PRAGMA busy_timeout = 5000"); }

            // Resolve directory
            try (PreparedStatement stmt = conn.prepareStatement("SELECT directory FROM session WHERE id = ?")) {
                stmt.setString(1, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) workDir = rs.getString("directory");
                }
            }

            // Extract tool invocations
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT data FROM message WHERE session_id = ? ORDER BY time_created ASC")) {
                stmt.setString(1, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String data = rs.getString("data");
                        if (data == null || data.isBlank()) continue;
                        try {
                            JsonNode node = MAPPER.readTree(data);
                            JsonNode parts = node.path("parts");
                            if (!parts.isArray()) continue;
                            for (JsonNode part : parts) {
                                if ("tool-invocation".equals(part.path("type").asText(""))) {
                                    String toolName = part.path("toolName").asText(
                                            part.path("name").asText("unknown"));
                                    boolean isError = "error".equals(part.path("state").asText(""));
                                    calls.add(new ExtractedToolCall(toolName,
                                            part.path("input").toString(), isError, "opencode", workDir));
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Error reading OpenCode database: " + e.getMessage(), e);
        }
        return new ExtractionResult(sessionId, "opencode", workDir, calls);
    }

    // ── Gemini ──────────────────────────────────────────────────────────────

    public static ExtractionResult extractGemini(Path file, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        if (file.toString().endsWith(".json")) {
            JsonNode root = MAPPER.readTree(file.toFile());
            String wd = root.path("workingDirectory").asText("");
            if (!wd.isEmpty()) workDir = wd;
            JsonNode messages = root.isArray() ? root : root.path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    extractGeminiToolCalls(msg, calls);
                }
            }
        } else {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = MAPPER.readTree(line);
                        extractGeminiToolCalls(node, calls);
                    } catch (Exception ignore) {}
                }
            }
        }

        String wd = workDir;
        if (wd != null) {
            calls = calls.stream()
                    .map(c -> new ExtractedToolCall(c.toolName, c.toolInput, c.isError, c.agentName, wd))
                    .toList();
        }
        return new ExtractionResult(sessionId, "gemini", workDir, calls);
    }

    private static void extractGeminiToolCalls(JsonNode node, List<ExtractedToolCall> calls) {
        String role = node.path("role").asText("");
        if ("tool".equals(role)) {
            calls.add(new ExtractedToolCall(
                    node.path("name").asText("unknown"),
                    node.has("input") ? node.path("input").toString() : "{}",
                    node.path("error").asBoolean(false),
                    "gemini", null));
        }
        // Also check for functionCall in parts
        JsonNode parts = node.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode funcCall = part.path("functionCall");
                if (!funcCall.isMissingNode()) {
                    calls.add(new ExtractedToolCall(
                            funcCall.path("name").asText("unknown"),
                            funcCall.path("args").toString(),
                            false, "gemini", null));
                }
            }
        }
    }

    // ── Aider ──────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from an Aider Markdown history file.
     * Aider doesn't use structured tool_use blocks, but embeds tool-like commands
     * in assistant text (e.g., file edits, shell commands). We extract these by
     * scanning for aider command patterns in assistant responses.
     */
    public static ExtractionResult extractAider(Path mdFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = mdFile.getParent() != null ? mdFile.getParent().toString() : null;

        String content = new String(Files.readAllBytes(mdFile), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        boolean inAssistant = false;

        for (String line : lines) {
            // Session boundary
            if (line.startsWith("# aider chat started at ")) {
                inAssistant = false;
                continue;
            }
            // User turn start
            if (line.startsWith("#### ") || line.startsWith("#### >")) {
                inAssistant = true;
                continue;
            }
            if (!inAssistant) continue;

            // Detect aider tool patterns in assistant output:
            // File edits use SEARCH/REPLACE blocks or unified diff blocks
            if (line.startsWith("<<<<<<< SEARCH")) {
                calls.add(new ExtractedToolCall("edit_file", "{}", false, "aider", workDir));
            }
            // Shell command execution
            else if (line.startsWith("```bash") || line.startsWith("```shell") || line.startsWith("```sh")) {
                calls.add(new ExtractedToolCall("shell_command", "{}", false, "aider", workDir));
            }
            // File creation markers
            else if (line.matches("^\\+\\+\\+ b/.+$")) {
                String fileName = line.substring(6);
                calls.add(new ExtractedToolCall("write_file",
                        "{\"file\":\"" + fileName.replace("\"", "\\\"") + "\"}", false, "aider", workDir));
            }
        }

        return new ExtractionResult(sessionId, "aider", workDir, calls);
    }

    // ── Cline ──────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from a Cline api_conversation_history.json file.
     * Cline uses Anthropic-style content blocks with {@code tool_use} entries.
     */
    public static ExtractionResult extractCline(Path jsonFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        // Check for task_metadata.json in the same directory for cwd
        Path metadataFile = jsonFile.getParent() != null ? jsonFile.getParent().resolve("task_metadata.json") : null;
        if (metadataFile != null && Files.exists(metadataFile)) {
            try {
                JsonNode meta = MAPPER.readTree(metadataFile.toFile());
                for (String field : new String[]{"cwd", "workspace", "workingDirectory"}) {
                    JsonNode cwd = meta.path(field);
                    if (cwd.isTextual() && !cwd.asText().isEmpty()) {
                        workDir = cwd.asText();
                        break;
                    }
                }
            } catch (Exception ignore) {}
        }

        JsonNode root = MAPPER.readTree(jsonFile.toFile());
        if (root.isArray()) {
            for (JsonNode msg : root) {
                String role = msg.path("role").asText("");
                JsonNode content = msg.path("content");
                if ("assistant".equals(role) && content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_use".equals(block.path("type").asText(""))) {
                            String toolName = block.path("name").asText("unknown");
                            JsonNode input = block.path("input");
                            String inputStr = input.isMissingNode() ? "{}" : input.toString();
                            calls.add(new ExtractedToolCall(toolName, inputStr, false, "cline", workDir));
                        }
                    }
                }
                // tool_result entries carry error status
                if ("user".equals(role) && content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_result".equals(block.path("type").asText(""))) {
                            boolean isError = block.path("is_error").asBoolean(false);
                            if (isError) {
                                String toolUseId = block.path("tool_use_id").asText("unknown");
                                calls.add(new ExtractedToolCall(toolUseId, "{}", true, "cline", workDir));
                            }
                        }
                    }
                }
            }
        }
        return new ExtractionResult(sessionId, "cline", workDir, calls);
    }

    // ── Cursor ─────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from a Cursor SQLite state.vscdb database for a given session.
     * Cursor stores conversations in the cursorDiskKV table with composerData: keys.
     */
    public static ExtractionResult extractCursor(String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();

        Path configRoot = ChatAdapterSupport.userConfigDir().resolve("Cursor").resolve("User");
        List<Path> dbs = new ArrayList<>();
        Path global = configRoot.resolve("globalStorage").resolve("state.vscdb");
        if (Files.exists(global)) dbs.add(global);
        Path workspaceStorage = configRoot.resolve("workspaceStorage");
        if (Files.isDirectory(workspaceStorage)) {
            try (Stream<Path> stream = Files.list(workspaceStorage)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.resolve("state.vscdb"))
                        .filter(Files::exists)
                        .forEach(dbs::add);
            }
        }

        for (Path db : dbs) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT value FROM cursorDiskKV WHERE key = ?")) {
                    ps.setString(1, "composerData:" + sessionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String json = rs.getString(1);
                            extractCursorToolCalls(json, calls);
                            if (!calls.isEmpty()) break;
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

        return new ExtractionResult(sessionId, "cursor", null, calls);
    }

    private static void extractCursorToolCalls(String json, List<ExtractedToolCall> calls) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = MAPPER.readTree(json);
            // Try conversation array
            JsonNode conversation = root.path("conversation");
            if (conversation.isArray()) {
                for (JsonNode msg : conversation) {
                    extractCursorMsgToolCalls(msg, calls);
                }
                return;
            }
            // Try tabs/bubbles
            JsonNode tabs = root.path("tabs");
            if (tabs.isArray()) {
                for (JsonNode tab : tabs) {
                    JsonNode bubbles = tab.path("bubbles");
                    if (bubbles.isArray()) {
                        for (JsonNode bubble : bubbles) {
                            extractCursorMsgToolCalls(bubble, calls);
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    private static void extractCursorMsgToolCalls(JsonNode msg, List<ExtractedToolCall> calls) {
        String role = msg.path("role").asText(msg.path("type").asText(""));
        if ("assistant".equals(role) || "2".equals(role) || "ai".equals(role)) {
            JsonNode content = msg.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_use".equals(block.path("type").asText(""))) {
                        String toolName = block.path("name").asText("unknown");
                        JsonNode input = block.path("input");
                        String inputStr = input.isMissingNode() ? "{}" : input.toString();
                        calls.add(new ExtractedToolCall(toolName, inputStr, false, "cursor", null));
                    }
                }
            }
            // Cursor also uses toolCalls array in some formats
            JsonNode toolCalls = msg.path("toolCalls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    String toolName = tc.path("function").path("name").asText(
                            tc.path("name").asText("unknown"));
                    String args = tc.path("function").path("arguments").asText(
                            tc.path("arguments").asText("{}"));
                    calls.add(new ExtractedToolCall(toolName, args, false, "cursor", null));
                }
            }
        }
    }

    // ── Continue ────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from a Continue session JSON file.
     * Continue uses OpenAI-style messages which may contain tool_calls arrays.
     */
    public static ExtractionResult extractContinue(Path jsonFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();

        JsonNode root = MAPPER.readTree(jsonFile.toFile());
        JsonNode history = root.isArray() ? root : null;
        if (history == null) {
            for (String field : new String[]{"history", "messages", "conversation"}) {
                JsonNode arr = root.path(field);
                if (arr.isArray()) { history = arr; break; }
            }
        }

        if (history != null && history.isArray()) {
            for (JsonNode msg : history) {
                String role = msg.path("role").asText("");
                if (!"assistant".equals(role)) continue;

                // OpenAI-style tool_calls array
                JsonNode toolCalls = msg.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String toolName = tc.path("function").path("name").asText("unknown");
                        String args = tc.path("function").path("arguments").asText("{}");
                        calls.add(new ExtractedToolCall(toolName, args, false, "continue", null));
                    }
                }

                // Also check content array for tool_use blocks (some providers)
                JsonNode content = msg.path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_use".equals(block.path("type").asText(""))) {
                            String toolName = block.path("name").asText("unknown");
                            JsonNode input = block.path("input");
                            String inputStr = input.isMissingNode() ? "{}" : input.toString();
                            calls.add(new ExtractedToolCall(toolName, inputStr, false, "continue", null));
                        }
                    }
                }
            }
        }

        return new ExtractionResult(sessionId, "continue", null, calls);
    }

    /**
     * Extract tool calls from a Continue SQLite session database.
     */
    public static ExtractionResult extractContinueSqlite(Path dbFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:file:" + dbFile.toAbsolutePath() + "?mode=ro")) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT role, content FROM session_history WHERE session_id = ? ORDER BY rowid ASC")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString(1);
                        String content = rs.getString(2);
                        if (!"assistant".equalsIgnoreCase(role) || content == null) continue;

                        String trimmed = content.trim();
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            try {
                                JsonNode node = MAPPER.readTree(trimmed);
                                // Check for tool_calls in the message
                                JsonNode toolCalls = node.path("tool_calls");
                                if (toolCalls.isArray()) {
                                    for (JsonNode tc : toolCalls) {
                                        String toolName = tc.path("function").path("name").asText("unknown");
                                        String args = tc.path("function").path("arguments").asText("{}");
                                        calls.add(new ExtractedToolCall(toolName, args, false, "continue", null));
                                    }
                                }
                                // Check for content blocks
                                JsonNode contentArr = node.path("content");
                                if (contentArr.isArray()) {
                                    for (JsonNode block : contentArr) {
                                        if ("tool_use".equals(block.path("type").asText(""))) {
                                            String toolName = block.path("name").asText("unknown");
                                            JsonNode input = block.path("input");
                                            String inputStr = input.isMissingNode() ? "{}" : input.toString();
                                            calls.add(new ExtractedToolCall(toolName, inputStr, false, "continue", null));
                                        }
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Error reading Continue database: " + e.getMessage(), e);
        }
        return new ExtractionResult(sessionId, "continue", null, calls);
    }

    // ── Kompile ────────────────────────────────────────────────────────────

    /**
     * Extract tool calls from a Kompile .txt transcript file.
     * Tool calls appear as {@code [tool:name]} lines in the assistant's output.
     */
    public static ExtractionResult extractKompile(Path txtFile, String sessionId) throws IOException {
        List<ExtractedToolCall> calls = new ArrayList<>();
        String workDir = null;

        try (BufferedReader reader = Files.newBufferedReader(txtFile, StandardCharsets.UTF_8)) {
            String line;
            boolean inHeader = true;

            while ((line = reader.readLine()) != null) {
                if (inHeader) {
                    if (line.startsWith("CWD:")) {
                        workDir = line.substring("CWD:".length()).trim();
                    }
                    if (line.isBlank()) {
                        inHeader = false;
                    }
                    continue;
                }

                // Tool call lines: [tool:name] or [tool:name input...]
                if (line.startsWith("[tool:")) {
                    int endBracket = line.indexOf(']', 6);
                    if (endBracket > 6) {
                        String toolContent = line.substring(6, endBracket);
                        // May contain tool name and input separated by space
                        int spaceIdx = toolContent.indexOf(' ');
                        String toolName;
                        String toolInput;
                        if (spaceIdx > 0) {
                            toolName = toolContent.substring(0, spaceIdx);
                            toolInput = toolContent.substring(spaceIdx + 1).trim();
                        } else {
                            toolName = toolContent;
                            toolInput = "{}";
                        }
                        calls.add(new ExtractedToolCall(toolName, toolInput, false, "kompile", workDir));
                    }
                }

                // Subagent lines: [subagent:name]
                if (line.startsWith("[subagent:")) {
                    int endBracket = line.indexOf(']', 10);
                    if (endBracket > 10) {
                        String agentName = line.substring(10, endBracket);
                        calls.add(new ExtractedToolCall("subagent:" + agentName, "{}", false, "kompile", workDir));
                    }
                }
            }
        }
        return new ExtractionResult(sessionId, "kompile", workDir, calls);
    }

    // ── Adapter file lookup ─────────────────────────────────────────────────

    /**
     * Locate a session file using the adapter's findSessionFile method,
     * falling back to a directory walk.
     */
    public static Optional<Path> findSessionFile(ChatSourceAdapter adapter, String sessionId) {
        try {
            var method = adapter.getClass().getDeclaredMethod("findSessionFile", String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Optional<Path> result = (Optional<Path>) method.invoke(adapter, sessionId);
            if (result.isPresent()) return result;
        } catch (Exception ignore) {}

        // Fallback: walk known directories
        return findByWalk(adapter.id(), sessionId);
    }

    private static Optional<Path> findByWalk(String sourceId, String sessionId) {
        Path home = ChatAdapterSupport.userHome();
        Path searchRoot = switch (sourceId) {
            case "claude-code" -> home.resolve(".claude").resolve("projects");
            case "codex" -> home.resolve(".codex").resolve("sessions");
            case "qwen" -> home.resolve(".qwen").resolve("projects");
            case "gemini" -> home.resolve(".gemini");
            case "pi" -> home.resolve(".pi").resolve("agent").resolve("sessions");
            case "kompile" -> home.resolve(".kompile").resolve("conversations");
            case "continue" -> home.resolve(".continue").resolve("sessions");
            default -> null;
        };
        if (searchRoot == null || !Files.isDirectory(searchRoot)) return Optional.empty();

        String needle = sessionId.endsWith(".jsonl") ? sessionId : sessionId + ".jsonl";
        String needleJson = sessionId.endsWith(".json") ? sessionId : sessionId + ".json";
        String needleTxt = sessionId.endsWith(".txt") ? sessionId : sessionId + ".txt";
        try (Stream<Path> stream = Files.walk(searchRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals(needle) || name.equals(needleJson)
                                || name.equals(needleTxt)
                                || name.equals(sessionId) || name.contains(sessionId);
                    })
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
