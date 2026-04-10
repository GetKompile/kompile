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

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Unified reader that loads conversation turns from kompile sessions or
 * directly from external agent storage (Claude Code, Codex, Qwen, OpenCode)
 * without requiring a prior import.
 */
public class ConversationReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODEX_ROLLOUT_FILENAME =
            Pattern.compile("^rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}(?:-\\d{2}){2}-(.+)\\.jsonl$");
    private static final Pattern GEMINI_SESSION_FILENAME =
            Pattern.compile("^session-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-(.+)\\.json$");
    private static final Pattern GEMINI_DIRECTORY_LISTING =
            Pattern.compile("Directory listing for (/[^\n:]+):");
    private static final Pattern GEMINI_WORKSPACE_HINT =
            Pattern.compile("allowed workspace directories: ([^\\n]+)");

    /**
     * Reads turns from a kompile session by ID.
     *
     * @param sessionId the kompile session ID
     * @return list of turns, empty if session not found
     * @throws IOException if the transcript file cannot be read
     */
    public static List<ChatHistory.Turn> readKompileSession(String sessionId) throws IOException {
        if (!ChatHistory.exists(sessionId)) {
            throw new IOException("Kompile session not found: " + sessionId);
        }
        ChatHistory history = new ChatHistory(sessionId);
        return history.readTurns();
    }

    /**
     * Reads turns directly from an external agent's native storage.
     *
     * @param source     the agent source: claude-code, codex, qwen, opencode
     * @param externalId the session/conversation ID within that agent's storage
     * @return list of turns
     * @throws IOException if the source cannot be read
     */
    public static List<ChatHistory.Turn> readExternalSession(String source, String externalId) throws IOException {
        switch (source.toLowerCase()) {
            case "claude":
            case "claude-code":
                return readClaudeCodeSession(externalId);
            case "codex":
                return readCodexSession(externalId);
            case "qwen":
                return readQwenSession(externalId);
            case "opencode":
                return readOpenCodeSession(externalId);
            case "gemini":
                return readGeminiSession(externalId);
            default:
                throw new IOException("Unknown source: " + source +
                        ". Supported: claude-code, codex, qwen, opencode, gemini");
        }
    }

    /**
     * Resolve the workspace directory associated with an external agent session.
     */
    public static Path resolveExternalWorkingDirectory(String source, String externalId) throws IOException {
        return switch (source.toLowerCase()) {
            case "claude", "claude-code" -> resolveClaudeCodeWorkingDirectory(externalId);
            case "codex" -> resolveCodexWorkingDirectory(externalId);
            case "qwen" -> resolveQwenWorkingDirectory(externalId);
            case "opencode" -> resolveOpenCodeWorkingDirectory(externalId);
            case "gemini" -> resolveGeminiWorkingDirectory(externalId);
            default -> throw new IOException("Unknown source: " + source +
                    ". Supported: claude-code, codex, qwen, opencode, gemini");
        };
    }

    // ─── Claude Code ───────────────────────────────────────────────────

    private static List<ChatHistory.Turn> readClaudeCodeSession(String id) throws IOException {
        Path claudeDir = getClaudeCodeDir();
        if (!Files.isDirectory(claudeDir)) {
            throw new IOException("Claude Code directory not found: " + claudeDir);
        }

        Path file = findClaudeCodeFile(claudeDir, id);
        if (file == null) {
            throw new IOException("Claude Code conversation not found: " + id);
        }

        return parseJsonlTurns(file);
    }

    private static Path resolveClaudeCodeWorkingDirectory(String id) throws IOException {
        Path claudeDir = getClaudeCodeDir();
        if (!Files.isDirectory(claudeDir)) {
            throw new IOException("Claude Code directory not found: " + claudeDir);
        }

        Path file = findClaudeCodeFile(claudeDir, id);
        if (file == null) {
            throw new IOException("Claude Code conversation not found: " + id);
        }
        return extractJsonlWorkingDirectory(file, "cwd");
    }

    private static Path findClaudeCodeFile(Path claudeDir, String id) throws IOException {
        try (Stream<Path> walk = Files.walk(claudeDir, 5)) {
            List<Path> matches = walk
                    .filter(p -> p.toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .filter(p -> {
                        String fileName = p.getFileName().toString();
                        // Match by: full filename, UUID part only, or if id is contained in path
                        if (fileName.equals(id + ".jsonl") || fileName.equals(id)) return true;
                        String uuidPart = fileName.replace(".jsonl", "");
                        if (uuidPart.equals(id)) return true;
                        // Also try matching if the id is contained in the filename (partial match)
                        if (fileName.contains(id) || id.contains(uuidPart)) return true;
                        return false;
                    })
                    .toList();
            return matches.isEmpty() ? null : matches.get(0);
        }
    }

    // ─── Codex ─────────────────────────────────────────────────────────

    private static List<ChatHistory.Turn> readCodexSession(String sessionId) throws IOException {
        // First try to find and read the rollout file (has full conversation with roles)
        Path rolloutFile = findCodexRolloutFile(sessionId);
        if (rolloutFile != null) {
            return parseCodexRolloutFile(rolloutFile, sessionId);
        }

        // Fallback to history.jsonl (user messages only)
        Path codexHistory = getCodexHistoryPath();
        if (!Files.exists(codexHistory)) {
            throw new IOException("Codex history file not found: " + codexHistory);
        }

        List<ChatHistory.Turn> turns = new ArrayList<>();
        List<String> lines = Files.readAllLines(codexHistory, StandardCharsets.UTF_8);

        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                // Codex stores session ID in session_id at root or payload.id
                String nodeSessionId = node.path("session_id").asText("");
                if (nodeSessionId.isEmpty() && node.has("payload")) {
                    nodeSessionId = node.path("payload").path("id").asText("");
                }
                if (!sessionId.equals(nodeSessionId)) continue;

                // history.jsonl only has user text, no roles
                String text = node.path("text").asText("");
                if (!text.isBlank()) {
                    turns.add(new ChatHistory.Turn("user", text));
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }

        if (turns.isEmpty()) {
            throw new IOException("No messages found for Codex session: " + sessionId);
        }
        return turns;
    }

    private static Path resolveCodexWorkingDirectory(String sessionId) throws IOException {
        Path rolloutFile = findCodexRolloutFile(sessionId);
        if (rolloutFile == null) {
            throw new IOException("Codex rollout file not found: " + sessionId);
        }

        List<String> lines = Files.readAllLines(rolloutFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");
                if ("session_meta".equals(type)) {
                    Path resolved = toNormalizedPath(node.path("payload").path("cwd").asText(""));
                    if (resolved != null) {
                        return resolved;
                    }
                } else if ("turn_context".equals(type)) {
                    Path resolved = toNormalizedPath(node.path("payload").path("cwd").asText(""));
                    if (resolved != null) {
                        return resolved;
                    }
                }
            } catch (Exception ignored) {
                // Skip malformed lines and continue searching for cwd metadata.
            }
        }

        throw new IOException("No working directory found for Codex session: " + sessionId);
    }

    /**
     * Parse a Codex rollout JSONL file with full conversation turns.
     */
    private static List<ChatHistory.Turn> parseCodexRolloutFile(Path file, String sessionId) throws IOException {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");
                if (!"response_item".equals(type)) continue;

                JsonNode payload = node.path("payload");
                if (payload.isMissingNode()) continue;

                String role = payload.path("role").asText("");
                if (!"user".equals(role) && !"assistant".equals(role)) continue;

                String text = extractCodexContent(node);
                if (text != null && !text.isBlank()) {
                    turns.add(new ChatHistory.Turn(role.equals("assistant") ? "assistant" : "user", text));
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }

        if (turns.isEmpty()) {
            throw new IOException("No messages found in Codex rollout file: " + file);
        }
        return turns;
    }

    // ─── Qwen ──────────────────────────────────────────────────────────

    private static List<ChatHistory.Turn> readQwenSession(String id) throws IOException {
        Path qwenDir = getQwenDir();
        if (!Files.isDirectory(qwenDir)) {
            throw new IOException("Qwen directory not found: " + qwenDir);
        }

        Path file = findQwenFile(qwenDir, id);
        if (file == null) {
            throw new IOException("Qwen conversation not found: " + id);
        }

        List<ChatHistory.Turn> turns = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");
                if (!"user".equals(type) && !"assistant".equals(type)) continue;

                String role = extractRole(node);
                String content = extractQwenContent(node);
                if (role != null && content != null && !content.isBlank()) {
                    turns.add(new ChatHistory.Turn(role, content));
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }

        if (turns.isEmpty()) {
            throw new IOException("No messages found for Qwen conversation: " + id);
        }
        return turns;
    }

    private static Path resolveQwenWorkingDirectory(String id) throws IOException {
        Path qwenDir = getQwenDir();
        if (!Files.isDirectory(qwenDir)) {
            throw new IOException("Qwen directory not found: " + qwenDir);
        }

        Path file = findQwenFile(qwenDir, id);
        if (file == null) {
            throw new IOException("Qwen conversation not found: " + id);
        }
        return extractJsonlWorkingDirectory(file, "cwd");
    }

    private static Path findQwenFile(Path qwenDir, String id) throws IOException {
        try (Stream<Path> walk = Files.walk(qwenDir, 5)) {
            List<Path> matches = walk
                    .filter(p -> p.toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .filter(p -> {
                        String relPath = qwenDir.relativize(p).toString();
                        String fileId = relPath.replace(File.separator, "_")
                                .replaceAll("\\.jsonl$", "");
                        return fileId.equals(id) || fileId.contains(id) || id.contains(fileId);
                    })
                    .toList();
            return matches.isEmpty() ? null : matches.get(0);
        }
    }

    // ─── OpenCode ──────────────────────────────────────────────────────

    private static List<ChatHistory.Turn> readOpenCodeSession(String sessionId) throws IOException {
        Path opencodeDb = getOpenCodeDbPath();
        if (!Files.exists(opencodeDb)) {
            throw new IOException("OpenCode database not found: " + opencodeDb);
        }

        // Try the ID directly first (it might already be in SQLite format)
        try {
            return parseOpenCodeSqlite(opencodeDb, sessionId);
        } catch (SQLException e) {
            // If that fails, try to resolve the ID from JSON session files
            String resolvedId = resolveOpenCodeSessionIdFromJsonFiles(sessionId);
            if (resolvedId != null) {
                try {
                    return parseOpenCodeSqlite(opencodeDb, resolvedId);
                } catch (SQLException ex) {
                    throw new IOException("Failed to query resolved session: " + ex.getMessage(), ex);
                }
            }
            // If still not found, throw the original exception
            throw new IOException("Failed to query OpenCode database: " + e.getMessage(), e);
        }
    }

    private static Path resolveOpenCodeWorkingDirectory(String sessionId) throws IOException {
        Path sessionFile = findOpenCodeSessionJson(sessionId);
        if (sessionFile != null) {
            try {
                JsonNode sessionJson = MAPPER.readTree(sessionFile.toFile());
                Path resolved = toNormalizedPath(sessionJson.path("directory").asText(""));
                if (resolved == null) {
                    resolved = toNormalizedPath(sessionJson.path("info").path("directory").asText(""));
                }
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception ignored) {
                // Fall through to database lookup.
            }
        }

        Path opencodeDb = getOpenCodeDbPath();
        if (!Files.exists(opencodeDb)) {
            throw new IOException("OpenCode database not found: " + opencodeDb);
        }

        String resolvedId = resolveOpenCodeSessionIdFromJsonFiles(sessionId);
        String effectiveId = resolvedId != null ? resolvedId : sessionId;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
            String query = "SELECT directory FROM session WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, effectiveId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Path resolved = toNormalizedPath(rs.getString("directory"));
                        if (resolved != null) {
                            return resolved;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to query OpenCode working directory: " + e.getMessage(), e);
        }

        throw new IOException("No working directory found for OpenCode session: " + sessionId);
    }

    /**
     * Resolves an OpenCode session ID by looking in JSON session files.
     * This handles the case where the input ID is from the file system (sess_* format)
     * but the database uses a different ID format (ses_* format).
     *
     * @param inputId the session ID from file system or user input
     * @return the actual session ID used in the SQLite database, or null if not found
     */
    private static String resolveOpenCodeSessionIdFromJsonFiles(String inputId) {
        try {
            Path sessionBaseDir = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "storage", "session");
            if (!Files.isDirectory(sessionBaseDir)) {
                return null;
            }

            // Normalize input ID (remove .json extension if present)
            String normalizedInputId = inputId.endsWith(".json") ? inputId.substring(0, inputId.length() - 5) : inputId;

            try (Stream<Path> projectDirs = Files.list(sessionBaseDir)) {
                return projectDirs
                        .filter(Files::isDirectory)
                        .flatMap(projDir -> {
                            try {
                                return Files.list(projDir);
                            } catch (IOException e) {
                                return Stream.empty();
                            }
                        })
                        .filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> {
                            String fileName = p.getFileName().toString();
                            String fileId = fileName.substring(0, fileName.length() - 5); // Remove .json
                            return fileId.equals(normalizedInputId);
                        })
                        .findFirst()
                        .flatMap(p -> {
                            try {
                                JsonNode sessionJson = MAPPER.readTree(p.toFile());
                                if (sessionJson.has("id")) {
                                    return Optional.of(sessionJson.get("id").asText());
                                }
                            } catch (Exception ignored) {
                                // Ignore malformed JSON
                            }
                            return Optional.empty();
                        })
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ChatHistory.Turn> parseOpenCodeSqlite(Path dbPath, String sessionId) throws SQLException {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            String query = """
                SELECT m.id, m.data as message_data, p.data as part_data
                FROM message m
                LEFT JOIN part p ON m.id = p.message_id
                WHERE m.session_id = ?
                ORDER BY m.time_created ASC, p.time_created ASC
            """;
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    String lastMessageId = null;
                    StringBuilder currentContent = new StringBuilder();
                    String currentRole = null;

                    while (rs.next()) {
                        String messageId = rs.getString("id");
                        String messageData = rs.getString("message_data");
                        String partData = rs.getString("part_data");

                        if (!messageId.equals(lastMessageId)) {
                            if (currentRole != null && currentContent.length() > 0) {
                                turns.add(new ChatHistory.Turn(currentRole, currentContent.toString().trim()));
                            }
                            try {
                                JsonNode msgNode = MAPPER.readTree(messageData);
                                String role = msgNode.path("role").asText("");
                                currentRole = "user".equals(role) ? "user" : "assistant";
                            } catch (Exception e) {
                                currentRole = "user";
                            }
                            currentContent.setLength(0);
                            lastMessageId = messageId;
                        }

                        if (partData != null && !partData.isBlank()) {
                            try {
                                JsonNode partNode = MAPPER.readTree(partData);
                                if ("text".equals(partNode.path("type").asText(""))) {
                                    String text = partNode.path("text").asText("");
                                    if (!text.isBlank()) {
                                        if (currentContent.length() > 0) currentContent.append("\n");
                                        currentContent.append(text);
                                    }
                                }
                            } catch (Exception e) {
                                // Skip
                            }
                        }
                    }

                    if (currentRole != null && currentContent.length() > 0) {
                        turns.add(new ChatHistory.Turn(currentRole, currentContent.toString().trim()));
                    }
                }
            }
        }

        if (turns.isEmpty()) {
            throw new SQLException("No messages found for OpenCode session: " + sessionId);
        }
        return turns;
    }

    // ─── Gemini ────────────────────────────────────────────────────────

    private static List<ChatHistory.Turn> readGeminiSession(String sessionId) throws IOException {
        Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini", "tmp");
        if (!Files.isDirectory(geminiDir)) {
            throw new IOException("Gemini sessions directory not found: " + geminiDir);
        }

        Path sessionFile = findGeminiSessionFile(geminiDir, sessionId);
        if (sessionFile == null) {
            throw new IOException("Gemini session not found: " + sessionId);
        }

        JsonNode root = MAPPER.readTree(sessionFile.toFile());
        JsonNode messages = root.path("messages");

        List<ChatHistory.Turn> turns = new ArrayList<>();
        if (messages.isArray()) {
            for (JsonNode msg : messages) {
                String type = msg.path("type").asText("");
                if ("user".equals(type) || "model".equals(type) ||
                        "gemini".equals(type) || "assistant".equals(type)) {
                    String content = msg.path("content").asText("");
                    if (!content.isBlank()) {
                        boolean assistant = "model".equals(type) || "gemini".equals(type) || "assistant".equals(type);
                        turns.add(new ChatHistory.Turn(assistant ? "assistant" : "user", content));
                    }
                }
            }
        }

        if (turns.isEmpty()) {
            throw new IOException("No messages found for Gemini session: " + sessionId);
        }
        return turns;
    }

    private static Path resolveGeminiWorkingDirectory(String sessionId) throws IOException {
        Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini", "tmp");
        if (!Files.isDirectory(geminiDir)) {
            throw new IOException("Gemini sessions directory not found: " + geminiDir);
        }

        Path sessionFile = findGeminiSessionFile(geminiDir, sessionId);
        if (sessionFile == null) {
            throw new IOException("Gemini session not found: " + sessionId);
        }

        JsonNode root = MAPPER.readTree(sessionFile.toFile());
        Path explicit = toNormalizedPath(root.path("workingDirectory").asText(""));
        if (explicit != null) {
            return explicit;
        }

        String projectHash = root.path("projectHash").asText("");
        Path currentWorkingDirectory = toNormalizedPath(System.getProperty("user.dir"));
        if (currentWorkingDirectory != null &&
                projectHash.equals(sha256Hex(currentWorkingDirectory.toString()))) {
            return currentWorkingDirectory;
        }

        Path inferred = inferGeminiWorkingDirectory(root);
        if (inferred != null) {
            return inferred;
        }

        throw new IOException("No working directory found for Gemini session: " + sessionId);
    }

    // ─── Shared JSONL parser ───────────────────────────────────────────

    private static List<ChatHistory.Turn> parseJsonlTurns(Path file) throws IOException {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String role = extractRole(node);
                String content = extractContent(node);
                if (role != null && content != null && !content.isBlank()) {
                    turns.add(new ChatHistory.Turn(role, content));
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }
        return turns;
    }

    // ─── JSON extraction helpers (mirrored from ConversationImportTool) ─

    private static String extractRole(JsonNode node) {
        if (node.has("role")) {
            String role = node.get("role").asText("");
            if ("user".equalsIgnoreCase(role) || "human".equalsIgnoreCase(role)) return "user";
            if ("assistant".equalsIgnoreCase(role) || "ai".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role))
                return "assistant";
        }
        if (node.has("type")) {
            String type = node.get("type").asText("");
            if ("human".equalsIgnoreCase(type) || "user".equalsIgnoreCase(type)) return "user";
            if ("assistant".equalsIgnoreCase(type)) return "assistant";
        }
        if (node.has("sender")) {
            String sender = node.get("sender").asText("");
            if ("user".equalsIgnoreCase(sender) || "human".equalsIgnoreCase(sender)) return "user";
            if ("assistant".equalsIgnoreCase(sender) || "bot".equalsIgnoreCase(sender)) return "assistant";
        }
        return null;
    }

    private static String extractContent(JsonNode node) {
        if (node.has("content")) {
            JsonNode contentNode = node.get("content");
            if (contentNode.isTextual()) return contentNode.asText();
            if (contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentNode) {
                    if (block.isTextual()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.asText());
                    } else if (block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText(""));
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
        }
        // Claude Code format: message is an object with content array inside
        if (node.has("message") && node.get("message").isObject()) {
            JsonNode msgObj = node.get("message");
            if (msgObj.has("content")) {
                JsonNode contentNode = msgObj.get("content");
                if (contentNode.isTextual()) return contentNode.asText();
                if (contentNode.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode block : contentNode) {
                        if (block.isTextual()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(block.asText());
                        } else if (block.has("text")) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(block.get("text").asText(""));
                        } else if (block.has("thinking")) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append("[thinking] " + block.get("thinking").asText(""));
                        }
                    }
                    return sb.length() > 0 ? sb.toString() : null;
                }
            }
            // Fallback to message as text
            if (msgObj.isTextual()) return msgObj.asText();
        }
        if (node.has("message") && node.get("message").isTextual()) return node.get("message").asText();
        if (node.has("text") && node.get("text").isTextual()) return node.get("text").asText();
        return null;
    }

    private static String extractQwenContent(JsonNode node) {
        JsonNode partsNode = node.path("message").path("parts");
        if (partsNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : partsNode) {
                String text = part.path("text").asText();
                if (!text.isBlank()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        String text = node.path("text").asText();
        return text.isBlank() ? null : text;
    }

    /**
     * Extract text content from Codex JSONL lines.
     * Handles both root-level text and nested payload.content arrays.
     */
    private static String extractCodexContent(JsonNode node) {
        // Try payload.content array (newer Codex format)
        if (node.has("payload")) {
            JsonNode payload = node.get("payload");
            if (payload.has("content") && payload.get("content").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : payload.get("content")) {
                    if (block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText(""));
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        }
        // Fallback to root text field
        if (node.has("text") && node.get("text").isTextual()) return node.get("text").asText();
        return null;
    }

    private static Path extractJsonlWorkingDirectory(Path file, String fieldName) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                Path resolved = toNormalizedPath(node.path(fieldName).asText(""));
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception ignored) {
                // Skip malformed entries and keep scanning.
            }
        }
        throw new IOException("No working directory found in " + file);
    }

    private static Path findCodexRolloutFile(String sessionId) throws IOException {
        Path codexDir = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        if (!Files.isDirectory(codexDir)) {
            return null;
        }

        try (Stream<Path> walk = Files.walk(codexDir, 5)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> matchesCodexSessionId(p, sessionId))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean matchesCodexSessionId(Path file, String sessionId) {
        String extracted = extractCodexSessionId(file.getFileName().toString());
        if (extracted != null && (extracted.equals(sessionId) ||
                extracted.contains(sessionId) || sessionId.contains(extracted))) {
            return true;
        }
        return file.getFileName().toString().contains(sessionId);
    }

    private static String extractCodexSessionId(String fileName) {
        Matcher matcher = CODEX_ROLLOUT_FILENAME.matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        if (fileName.startsWith("rollout-") && fileName.endsWith(".jsonl")) {
            String stripped = fileName.substring("rollout-".length(), fileName.length() - ".jsonl".length());
            if (stripped.length() > 20) {
                return stripped.substring(20);
            }
            return stripped;
        }
        return null;
    }

    private static Path findOpenCodeSessionJson(String inputId) {
        try {
            Path sessionBaseDir = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "storage", "session");
            if (!Files.isDirectory(sessionBaseDir)) {
                return null;
            }

            String normalizedInputId = inputId.endsWith(".json") ? inputId.substring(0, inputId.length() - 5) : inputId;
            try (Stream<Path> projectDirs = Files.list(sessionBaseDir)) {
                return projectDirs
                        .filter(Files::isDirectory)
                        .flatMap(projDir -> {
                            try {
                                return Files.list(projDir);
                            } catch (IOException e) {
                                return Stream.empty();
                            }
                        })
                        .filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> {
                            String fileId = p.getFileName().toString().replace(".json", "");
                            return fileId.equals(normalizedInputId) || fileId.contains(normalizedInputId)
                                    || normalizedInputId.contains(fileId);
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findGeminiSessionFile(Path geminiDir, String sessionId) throws IOException {
        try (Stream<Path> projectDirs = Files.list(geminiDir)) {
            for (Path sessionFile : projectDirs
                    .filter(Files::isDirectory)
                    .filter(pd -> !pd.getFileName().toString().equals("bin"))
                    .flatMap(pd -> {
                        Path chatsDir = pd.resolve("chats");
                        if (Files.isDirectory(chatsDir)) {
                            try {
                                return Files.list(chatsDir);
                            } catch (IOException e) {
                                return Stream.empty();
                            }
                        }
                        return Stream.empty();
                    })
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList()) {
                if (matchesGeminiSessionId(sessionFile, sessionId)) {
                    return sessionFile;
                }
            }
        }
        return null;
    }

    private static boolean matchesGeminiSessionId(Path sessionFile, String sessionId) {
        String filename = sessionFile.getFileName().toString();
        Matcher matcher = GEMINI_SESSION_FILENAME.matcher(filename);
        if (matcher.matches()) {
            String shortId = matcher.group(1);
            if (shortId.equals(sessionId) || sessionId.startsWith(shortId)) {
                return true;
            }
        }

        try {
            JsonNode root = MAPPER.readTree(sessionFile.toFile());
            String storedId = root.path("sessionId").asText("");
            return storedId.equals(sessionId) || storedId.startsWith(sessionId) || sessionId.startsWith(storedId);
        } catch (Exception e) {
            return false;
        }
    }

    private static Path inferGeminiWorkingDirectory(JsonNode root) {
        List<Path> candidates = new ArrayList<>();
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                collectGeminiToolPaths(message, candidates);
            }
        }
        return commonExistingPath(candidates);
    }

    private static void collectGeminiToolPaths(JsonNode message, List<Path> candidates) {
        JsonNode toolCalls = message.path("toolCalls");
        if (!toolCalls.isArray()) {
            return;
        }

        for (JsonNode toolCall : toolCalls) {
            JsonNode args = toolCall.path("args");
            addCandidatePath(args.path("dir_path").asText(""), candidates);
            addCandidatePath(args.path("file_path").asText(""), candidates);
            addCandidatePath(args.path("absolute_path").asText(""), candidates);

            JsonNode results = toolCall.path("result");
            if (!results.isArray()) {
                continue;
            }
            for (JsonNode result : results) {
                String output = result.path("functionResponse").path("response").path("output").asText("");
                if (output.isBlank()) {
                    continue;
                }

                Matcher listingMatcher = GEMINI_DIRECTORY_LISTING.matcher(output);
                if (listingMatcher.find()) {
                    addCandidatePath(listingMatcher.group(1), candidates);
                }

                Matcher workspaceMatcher = GEMINI_WORKSPACE_HINT.matcher(output);
                if (workspaceMatcher.find()) {
                    for (String candidate : workspaceMatcher.group(1).split(" or ")) {
                        addCandidatePath(candidate.trim(), candidates);
                    }
                }
            }
        }
    }

    private static void addCandidatePath(String rawPath, List<Path> candidates) {
        Path candidate = toNormalizedPath(rawPath);
        if (candidate == null) {
            return;
        }
        if (candidate.startsWith(Path.of(System.getProperty("user.home"), ".gemini", "tmp"))) {
            return;
        }
        if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
            candidate = candidate.getParent();
        } else if (!Files.exists(candidate) && candidate.getFileName() != null &&
                candidate.getFileName().toString().contains(".")) {
            candidate = candidate.getParent();
        }
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static Path commonExistingPath(List<Path> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        Path common = candidates.get(0);
        for (int i = 1; i < candidates.size() && common != null; i++) {
            common = commonPrefix(common, candidates.get(i));
        }
        if (common == null) {
            return null;
        }

        Path current = common;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current != null ? current.toAbsolutePath().normalize() : common.toAbsolutePath().normalize();
    }

    private static Path commonPrefix(Path left, Path right) {
        int max = Math.min(left.getNameCount(), right.getNameCount());
        Path root = left.getRoot();
        if (root == null || right.getRoot() == null || !root.equals(right.getRoot())) {
            return null;
        }

        Path common = root;
        for (int i = 0; i < max; i++) {
            if (!left.getName(i).equals(right.getName(i))) {
                break;
            }
            common = common.resolve(left.getName(i).toString());
        }
        return common;
    }

    private static Path toNormalizedPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Hex(String value) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    // ─── Path helpers ──────────────────────────────────────────────────

    private static Path getClaudeCodeDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }

    private static Path getCodexHistoryPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "history.jsonl");
    }

    private static Path getQwenDir() {
        return Path.of(System.getProperty("user.home"), ".qwen", "projects");
    }

    private static Path getOpenCodeDbPath() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }
}
