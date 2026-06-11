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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Exports kompile conversation turns to native agent session formats.
 * <p>
 * This enables true session continuation by writing converted conversations
 * directly into each agent's native storage, then using their built-in
 * resume flags (--resume, --continue, -s, etc.).
 * <p>
 * Supported agents:
 * <ul>
 *   <li><b>claude-code</b>: JSONL in ~/.claude/projects/&lt;project-dir&gt;/&lt;session-uuid&gt;.jsonl</li>
 *   <li><b>codex</b>: JSONL in ~/.codex/sessions/YYYY/MM/DD/rollout-&lt;timestamp&gt;-&lt;uuid&gt;.jsonl</li>
 *   <li><b>qwen</b>: JSONL in ~/.qwen/projects/&lt;project-dir&gt;/chats/&lt;session-uuid&gt;.jsonl</li>
 *   <li><b>opencode</b>: JSON files in ~/.local/share/opencode/storage/{session,message,part}/</li>
 * </ul>
 */
public class ConversationExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Supported export targets */
    public static final List<String> SUPPORTED_AGENTS = List.of(
            "claude-code", "codex", "qwen", "opencode", "gemini"
    );

    /**
     * Exports conversation turns to a native agent session.
     *
     * @param turns    the conversation turns to export
     * @param agent    target agent: claude-code, codex, qwen, opencode
     * @param sessionId optional session ID (generated if null)
     * @param sourceAgent the original agent source (e.g., "qwen", "claude-code") for model mapping
     * @param workingDirectory the workspace the resumed agent should use
     * @return ExportResult with session ID, agent-specific path, and resume command
     * @throws IOException if writing fails
     */
    public static ExportResult exportToAgent(List<ChatHistory.Turn> turns,
                                              String agent,
                                              String sessionId,
                                              String sourceAgent) throws IOException {
        return exportToAgent(turns, agent, sessionId, sourceAgent,
                Path.of(System.getProperty("user.dir")));
    }

    /**
     * Exports conversation turns to a native agent session using an explicit workspace.
     */
    public static ExportResult exportToAgent(List<ChatHistory.Turn> turns,
                                             String agent,
                                             String sessionId,
                                             String sourceAgent,
                                             Path workingDirectory) throws IOException {
        if (turns == null || turns.isEmpty()) {
            throw new IOException("No conversation turns to export");
        }

        String effectiveSessionId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        Path effectiveWorkingDirectory = normalizeWorkingDirectory(workingDirectory);

        // Dynamic provider/model resolution: discovers authenticated providers
        // from OpenCode's auth.json and session history, then maps the source
        // agent to the best available provider/model combination.
        ProviderModel pm = resolveProviderModel(sourceAgent, effectiveWorkingDirectory);
        String modelId = pm.modelId();
        String providerId = pm.providerId();

        System.err.println("[ConversationExporter] Resolved provider=" + providerId + " model=" + modelId + " for source=" + sourceAgent);

        switch (agent.toLowerCase()) {
            case "claude-code":
            case "claude":
                return exportToClaude(turns, effectiveSessionId, effectiveWorkingDirectory);
            case "codex":
                return exportToCodex(turns, effectiveSessionId, effectiveWorkingDirectory);
            case "qwen":
                return exportToQwen(turns, effectiveSessionId, effectiveWorkingDirectory);
            case "opencode":
                return exportToOpenCode(turns, effectiveSessionId, providerId, modelId, effectiveWorkingDirectory);
            case "gemini":
                return exportToGemini(turns, effectiveSessionId, effectiveWorkingDirectory);
            default:
                throw new IOException("Unsupported agent: " + agent +
                        ". Supported: " + String.join(", ", SUPPORTED_AGENTS));
        }
    }

    /**
     * Resolves the best provider/model pair for importing INTO OpenCode.
     * Uses dynamic discovery: reads OpenCode's auth.json and message history
     * to find actually authenticated providers, then maps the source agent
     * to the best available provider/model combination.
     */
    private static ProviderModel resolveProviderModel(String sourceAgent, Path workingDirectory) {
        String cwd = workingDirectory.toAbsolutePath().normalize().toString();
        Map<String, String> authProviders = discoverAuthProviders();

        // Try to find provider/model from existing OpenCode session history
        ProviderModel fromHistory = findProviderFromSessionHistory(cwd, authProviders);
        if (fromHistory != null) {
            return fromHistory;
        }

        // Fall back to affinity-based resolution using authenticated providers
        ProviderModel fromAffinity = resolveFromAuth(sourceAgent, authProviders);
        if (fromAffinity != null) {
            return fromAffinity;
        }

        // Last resort: hardcoded fallback preferring OpenCode Zen
        return fallbackProviderModel(sourceAgent);
    }

    /**
     * Scans OpenCode's message history for the most recent authenticated provider/model.
     */
    private static ProviderModel findProviderFromSessionHistory(String cwd, Map<String, String> authProviders) {
        String homeDir = System.getProperty("user.home");
        Path opencodeDb = Paths.get(homeDir, ".local", "share", "opencode", "opencode.db");
        if (!Files.exists(opencodeDb)) {
            return null;
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
            String findProjectSql = "SELECT id FROM project WHERE worktree = ?";
            String projectId = null;
            try (PreparedStatement stmt = conn.prepareStatement(findProjectSql)) {
                stmt.setString(1, cwd);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) projectId = rs.getString("id");
                }
            }

            List<String> projectSearch = new ArrayList<>();
            if (projectId != null) projectSearch.add(projectId);
            if (!"global".equals(projectId)) projectSearch.add("global");

            for (String projId : projectSearch) {
                String sql = "SELECT m.data FROM message m INNER JOIN session s ON m.session_id = s.id WHERE s.project_id = ? AND m.data IS NOT NULL AND m.data != '' ORDER BY m.time_created DESC LIMIT 100";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, projId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            String data = rs.getString("data");
                            if (data == null || data.isBlank()) continue;
                            try {
                                JsonNode node = MAPPER.readTree(data);
                                String providerId = null;
                                String modelId = null;
                                JsonNode modelNode = node.get("model");
                                if (modelNode != null && modelNode.isObject()) {
                                    JsonNode pid = modelNode.get("providerID");
                                    JsonNode mid = modelNode.get("modelID");
                                    if (pid != null && !pid.asText().isBlank()) providerId = pid.asText();
                                    if (mid != null && !mid.asText().isBlank()) modelId = mid.asText();
                                }
                                if (providerId == null) {
                                    JsonNode pid = node.get("providerID");
                                    if (pid != null && !pid.asText().isBlank()) providerId = pid.asText();
                                }
                                if (modelId == null) {
                                    JsonNode mid = node.get("modelID");
                                    if (mid != null && !mid.asText().isBlank()) modelId = mid.asText();
                                }
                                if (providerId != null && modelId != null && authProviders.containsKey(providerId)) {
                                    return new ProviderModel(providerId, modelId);
                                }
                            } catch (Exception e) { /* skip malformed */ }
                        }
                    }
                }
            }
        } catch (Exception e) { /* non-fatal */ }
        return null;
    }

    /**
     * Reads ~/.local/share/opencode/auth.json to discover authenticated providers.
     */
    private static Map<String, String> discoverAuthProviders() {
        String homeDir = System.getProperty("user.home");
        Path authFile = Paths.get(homeDir, ".local", "share", "opencode", "auth.json");
        if (!Files.exists(authFile)) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(authFile.toFile());
            Map<String, String> providers = new LinkedHashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    String providerId = entry.getKey();
                    JsonNode auth = entry.getValue();
                    String type = auth.has("type") ? auth.get("type").asText() : "unknown";
                    providers.put(providerId, type);
                });
            }
            return providers;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Maps source agent affinity to the best available authenticated provider.
     * OpenCode Zen providers (opencode, opencode-go, kimi-for-coding) are always
     * tried first since we're importing INTO opencode.
     */
    private static ProviderModel resolveFromAuth(String sourceAgent, Map<String, String> authProviders) {
        String source = sourceAgent != null ? sourceAgent.toLowerCase() : "";

        List<List<String>> affinityChains = switch (source) {
            case "claude-code", "claude" -> List.of(
                    List.of("opencode", "opencode-go", "kimi-for-coding"),
                    List.of("anthropic"),
                    List.of("github-copilot"),
                    List.of("openrouter"),
                    List.of("openai")
            );
            case "codex" -> List.of(
                    List.of("opencode", "opencode-go", "kimi-for-coding"),
                    List.of("github-copilot"),
                    List.of("openrouter"),
                    List.of("openai"),
                    List.of("anthropic")
            );
            case "qwen" -> List.of(
                    List.of("opencode", "opencode-go", "kimi-for-coding"),
                    List.of("openrouter"),
                    List.of("github-copilot"),
                    List.of("openai")
            );
            case "gemini" -> List.of(
                    List.of("opencode", "opencode-go", "kimi-for-coding"),
                    List.of("google", "google-vertex"),
                    List.of("github-copilot"),
                    List.of("openrouter")
            );
            default -> List.of(
                    List.of("opencode", "opencode-go", "kimi-for-coding"),
                    List.of("github-copilot"),
                    List.of("openrouter"),
                    List.of("openai"),
                    List.of("anthropic"),
                    List.of("google")
            );
        };

        String matchedProvider = null;
        for (List<String> chain : affinityChains) {
            for (String candidate : chain) {
                if (authProviders.containsKey(candidate)) {
                    matchedProvider = candidate;
                    break;
                }
            }
            if (matchedProvider != null) break;
        }

        if (matchedProvider == null) return null;

        String modelId = getModelForProvider(matchedProvider, source);
        return new ProviderModel(matchedProvider, modelId);
    }

    /**
     * Returns a sensible model ID for a given provider and source agent.
     */
    private static String getModelForProvider(String provider, String sourceAgent) {
        String source = sourceAgent != null ? sourceAgent.toLowerCase() : "";
        return switch (provider) {
            case "opencode", "opencode-go", "kimi-for-coding" -> {
                if ("claude".equals(source) || "claude-code".equals(source)) yield "claude-sonnet-4-20250514";
                else if ("qwen".equals(source)) yield "qwen/qwen3-coder";
                else if ("gemini".equals(source)) yield "gemini-2.5-pro";
                else yield "kimi-k2.5-free";
            }
            case "github-copilot" -> {
                if ("claude".equals(source) || "claude-code".equals(source)) yield "claude-sonnet-4-20250514";
                else if ("qwen".equals(source)) yield "qwen/qwen3-coder";
                else if ("gemini".equals(source)) yield "gemini-2.5-pro";
                else yield "gpt-4o";
            }
            case "openrouter" -> {
                if ("qwen".equals(source)) yield "qwen/qwen3-coder";
                else if ("claude".equals(source) || "claude-code".equals(source)) yield "anthropic/claude-sonnet-4-20250514";
                else yield "openai/gpt-4o";
            }
            case "openai" -> "gpt-4o";
            case "anthropic" -> "claude-sonnet-4-20250514";
            case "google", "google-vertex" -> "gemini-2.5-pro";
            default -> "kimi-k2.5-free";
        };
    }

    /**
     * Fallback provider/model when no dynamic discovery succeeds.
     * Prefers OpenCode Zen since we are importing INTO opencode.
     */
    private static ProviderModel fallbackProviderModel(String sourceAgent) {
        String source = sourceAgent != null ? sourceAgent.toLowerCase() : "";
        return switch (source) {
            case "claude-code", "claude" -> new ProviderModel("opencode", "claude-sonnet-4-20250514");
            case "qwen" -> new ProviderModel("opencode", "qwen/qwen3-coder");
            case "gemini" -> new ProviderModel("opencode", "gemini-2.5-pro");
            default -> new ProviderModel("opencode", "kimi-k2.5-free");
        };
    }

    /**
     * Simple holder for a resolved provider/model pair.
     */
    private record ProviderModel(String providerId, String modelId) {}

    /**
     * Maps the source agent to an appropriate model ID for the target agent.
     * @deprecated Use {@link #resolveProviderModel(String, Path)} instead.
     */
    @Deprecated
    private static String getModelIdForAgent(String sourceAgent, String targetAgent) {
        if (sourceAgent == null || sourceAgent.isEmpty()) {
            return "kimi-k2.5-free";
        }
        return switch (sourceAgent.toLowerCase()) {
            case "qwen" -> "qwen/qwen3-coder";
            case "claude-code", "claude" -> "claude-sonnet-4-20250514";
            case "codex" -> "gpt-4o";
            case "gemini" -> "gemini-2.5-pro";
            default -> "kimi-k2.5-free";
        };
    }

    /**
     * Maps the source agent to a valid OpenCode providerID.
     * @deprecated Use {@link #resolveProviderModel(String, Path)} instead.
     */
    @Deprecated
    private static String getProviderIdForAgent(String sourceAgent) {
        if (sourceAgent == null || sourceAgent.isEmpty()) {
            return "opencode";
        }
        return switch (sourceAgent.toLowerCase()) {
            case "claude-code", "claude" -> "anthropic";
            case "gemini" -> "google";
            case "qwen" -> "openrouter";
            case "codex", "opencode" -> "opencode";
            default -> "opencode";
        };
    }

    // ─── Claude Code Export ───────────────────────────────────────────────

    /**
     * Exports to Claude Code's native JSONL format.
     * <p>
     * Format per line:
     * <pre>
     * {"type":"user","message":{"role":"user","content":"text"},"uuid":"...","timestamp":"..."}
     * {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"response"}]},"uuid":"...","timestamp":"..."}
     * </pre>
     * <p>
     * Written to: ~/.claude/projects/&lt;project-dir&gt;/&lt;session-uuid&gt;.jsonl
     */
    private static ExportResult exportToClaude(List<ChatHistory.Turn> turns,
                                               String sessionId,
                                               Path workingDirectory) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path claudeDir = Paths.get(homeDir, ".claude", "projects");
        Files.createDirectories(claudeDir);

        String cwd = workingDirectory.toString();
        // Use current working directory as project identifier
        String projectDirName = encodeProjectDir(cwd);
        Path projectDir = claudeDir.resolve(projectDirName);
        Files.createDirectories(projectDir);

        // Write session JSONL
        Path sessionFile = projectDir.resolve(sessionId + ".jsonl");
        StringBuilder jsonl = new StringBuilder();
        Instant now = Instant.now();
        String lastUuid = null;

        for (int i = 0; i < turns.size(); i++) {
            ChatHistory.Turn turn = turns.get(i);
            String uuid = UUID.randomUUID().toString();
            Instant timestamp = now.plusSeconds(i * 5); // Space out timestamps

            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("uuid", uuid);
            entry.put("parentUuid", lastUuid);
            entry.put("sessionId", sessionId);
            entry.put("timestamp", timestamp.toString());
            entry.put("cwd", cwd);
            entry.put("version", "2.1.63");
            entry.put("gitBranch", "");
            entry.put("isSidechain", false);
            entry.put("userType", "external");

            if ("user".equals(turn.role())) {
                entry.put("type", "user");
                entry.put("permissionMode", "bypassPermissions");

                ObjectNode message = entry.putObject("message");
                message.put("role", "user");
                // Use raw content blocks if available (preserves tool_result structure)
                if (turn.rawContentBlocks() != null && !turn.rawContentBlocks().isEmpty()) {
                    message.set("content", turn.rawContentBlocks());
                } else {
                    message.put("content", turn.content());
                }
            } else {
                entry.put("type", "assistant");

                ObjectNode message = entry.putObject("message");
                message.put("model", "claude-sonnet-4-20250514");
                message.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                message.put("type", "message");
                message.put("role", "assistant");

                // Use raw content blocks if available (preserves tool_use structure)
                if (turn.rawContentBlocks() != null && !turn.rawContentBlocks().isEmpty()) {
                    message.set("content", turn.rawContentBlocks());
                    // Set stop_reason based on whether tool_use blocks are present
                    boolean hasToolUse = false;
                    for (JsonNode block : turn.rawContentBlocks()) {
                        if ("tool_use".equals(block.path("type").asText(""))) {
                            hasToolUse = true;
                            break;
                        }
                    }
                    message.put("stop_reason", hasToolUse ? "tool_use" : "end_turn");
                } else {
                    ArrayNode contentArray = message.putArray("content");
                    ObjectNode textBlock = contentArray.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", turn.content());
                    message.put("stop_reason", "end_turn");
                }
                message.putNull("stop_sequence");

                ObjectNode usage = message.putObject("usage");
                usage.put("input_tokens", 0);
                usage.put("output_tokens", 0);
            }

            lastUuid = uuid;
            jsonl.append(entry.toString()).append("\n");
        }

        Files.writeString(sessionFile, jsonl.toString(), StandardCharsets.UTF_8);

        // Update sessions-index.json
        updateClaudeSessionsIndex(projectDir, sessionId, turns);

        String resumeCommand = "claude --resume " + sessionId;
        return new ExportResult(sessionId, "claude-code", sessionFile, resumeCommand, workingDirectory);
    }

    /**
     * Updates or creates the sessions-index.json for Claude Code.
     */
    private static void updateClaudeSessionsIndex(Path projectDir, String sessionId,
                                                   List<ChatHistory.Turn> turns) throws IOException {
        Path indexFile = projectDir.resolve("sessions-index.json");
        ObjectNode index;

        if (Files.exists(indexFile)) {
            index = (ObjectNode) MAPPER.readTree(indexFile.toFile());
        } else {
            index = MAPPER.createObjectNode();
        }

        // Build session metadata
        ObjectNode sessionMeta = index.putObject(sessionId);
        sessionMeta.put("id", sessionId);

        // Generate summary from first user message
        String summary = turns.stream()
                .filter(t -> "user".equals(t.role()))
                .findFirst()
                .map(t -> t.content().length() > 80 ? t.content().substring(0, 77) + "..." : t.content())
                .orElse("Resumed conversation");
        sessionMeta.put("summary", summary);

        sessionMeta.put("messageCount", turns.size());
        sessionMeta.put("createdAt", Instant.now().toString());
        sessionMeta.put("updatedAt", Instant.now().toString());

        // Write index
        Files.writeString(indexFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);
    }

    // ─── Codex Export ──────────────────────────────────────────────────────

    /**
     * Exports to Codex's native JSONL format.
     * <p>
     * Format per line:
     * <pre>
     * {"session_id":"...","ts":1234567890,"text":"message text"}
     * </pre>
     * <p>
     * Written to: ~/.codex/sessions/YYYY/MM/DD/rollout-&lt;timestamp&gt;-&lt;uuid&gt;.jsonl
     */
    private static ExportResult exportToCodex(List<ChatHistory.Turn> turns,
                                              String sessionId,
                                              Path workingDirectory) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path codexDir = Paths.get(homeDir, ".codex", "sessions");
        Files.createDirectories(codexDir);

        // Create date-based directory structure
        Instant now = Instant.now();
        String year = String.valueOf(now.atZone(java.time.ZoneId.systemDefault()).getYear());
        String month = String.format("%02d", now.atZone(java.time.ZoneId.systemDefault()).getMonthValue());
        String day = String.format("%02d", now.atZone(java.time.ZoneId.systemDefault()).getDayOfMonth());

        Path dateDir = codexDir.resolve(year).resolve(month).resolve(day);
        Files.createDirectories(dateDir);

        // Write rollout file (newer Codex format with full conversation)
        long timestamp = now.getEpochSecond();
        String fileName = "rollout-" + now.toString().replace(':', '-').substring(0, 19) + "-" + sessionId + ".jsonl";
        Path sessionFile = dateDir.resolve(fileName);

        StringBuilder jsonl = new StringBuilder();

        // Write session_meta first - matching native Codex format exactly
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("timestamp", now.toString());
        meta.put("type", "session_meta");
        ObjectNode payload = meta.putObject("payload");
        payload.put("id", sessionId);
        payload.put("timestamp", now.toString());
        payload.put("cwd", workingDirectory.toString());
        payload.put("originator", "kompile");
        payload.put("cli_version", "0.0.0");
        payload.putNull("instructions");
        payload.put("source", "cli");
        payload.put("model_provider", "openai");
        jsonl.append(meta.toString()).append("\n");

        // Write each turn as response_item
        for (int i = 0; i < turns.size(); i++) {
            ChatHistory.Turn turn = turns.get(i);
            Instant lineTimestamp = now.plusSeconds(i * 5L);
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("timestamp", lineTimestamp.toString());
            entry.put("type", "response_item");

            ObjectNode itemPayload = entry.putObject("payload");
            itemPayload.put("type", "message");
            itemPayload.put("role", turn.role().equals("assistant") ? "assistant" : "user");

            ArrayNode contentArray = itemPayload.putArray("content");
            ObjectNode textBlock = contentArray.addObject();
            textBlock.put("type", "assistant".equals(turn.role()) ? "output_text" : "input_text");
            textBlock.put("text", turn.content());

            jsonl.append(entry.toString()).append("\n");

            ObjectNode event = MAPPER.createObjectNode();
            event.put("timestamp", lineTimestamp.toString());
            event.put("type", "event_msg");
            ObjectNode eventPayload = event.putObject("payload");
            if ("assistant".equals(turn.role())) {
                eventPayload.put("type", "agent_message");
                eventPayload.put("message", turn.content());
            } else {
                eventPayload.put("type", "user_message");
                eventPayload.put("message", turn.content());
                eventPayload.putArray("images");
            }
            jsonl.append(event.toString()).append("\n");
        }

        Files.writeString(sessionFile, jsonl.toString(), StandardCharsets.UTF_8);

        // Also write to history.jsonl for discovery
        writeCodexHistory(sessionId, turns);

        // Codex resume filters sessions by cwd by default. Use --all to disable cwd
        // filtering so the specific session ID is found regardless of working directory.
        String resumeCommand = "codex resume --all " + sessionId;
        return new ExportResult(sessionId, "codex", sessionFile, resumeCommand, workingDirectory);
    }

    /**
     * Appends entries to Codex's global history.jsonl
     */
    private static void writeCodexHistory(String sessionId, List<ChatHistory.Turn> turns) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path historyFile = Paths.get(homeDir, ".codex", "history.jsonl");

        StringBuilder jsonl = new StringBuilder();
        long timestamp = Instant.now().getEpochSecond();

        for (ChatHistory.Turn turn : turns) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("session_id", sessionId);
            entry.put("ts", timestamp);
            entry.put("text", turn.content());

            jsonl.append(entry.toString()).append("\n");
            timestamp += 10;
        }

        // Append to existing history or create new
        if (Files.exists(historyFile)) {
            Files.writeString(historyFile,
                    Files.readString(historyFile, StandardCharsets.UTF_8) + jsonl.toString(),
                    StandardCharsets.UTF_8);
        } else {
            Files.createDirectories(historyFile.getParent());
            Files.writeString(historyFile, jsonl.toString(), StandardCharsets.UTF_8);
        }
    }

    // ─── Qwen Export ───────────────────────────────────────────────────────

    /**
     * Exports to Qwen Code's native JSONL format.
     * <p>
     * Format per line:
     * <pre>
     * {"uuid":"...","sessionId":"...","type":"user","message":{"role":"user","parts":[{"text":"..."}]}}
     * {"uuid":"...","sessionId":"...","type":"assistant","message":{"role":"model","parts":[{"text":"..."}]}}
     * </pre>
     * <p>
     * Written to: ~/.qwen/projects/&lt;project-dir&gt;/chats/&lt;session-uuid&gt;.jsonl
     */
    private static ExportResult exportToQwen(List<ChatHistory.Turn> turns,
                                             String sessionId,
                                             Path workingDirectory) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path qwenDir = Paths.get(homeDir, ".qwen", "projects");
        Files.createDirectories(qwenDir);

        // Use current working directory as project identifier
        String cwd = workingDirectory.toString();
        String projectDirName = encodeProjectDir(cwd);
        Path projectDir = qwenDir.resolve(projectDirName).resolve("chats");
        Files.createDirectories(projectDir);

        // Write session JSONL
        Path sessionFile = projectDir.resolve(sessionId + ".jsonl");
        StringBuilder jsonl = new StringBuilder();
        Instant now = Instant.now();
        String lastUuid = null;

        for (int i = 0; i < turns.size(); i++) {
            ChatHistory.Turn turn = turns.get(i);
            String uuid = UUID.randomUUID().toString();

            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("uuid", uuid);
            entry.put("parentUuid", lastUuid);
            entry.put("sessionId", sessionId);
            entry.put("timestamp", now.plusSeconds(i * 5).toString());
            entry.put("cwd", cwd);
            entry.put("version", "0.12.3");
            entry.put("gitBranch", "");

            if ("user".equals(turn.role())) {
                entry.put("type", "user");

                ObjectNode message = entry.putObject("message");
                message.put("role", "user");

                ArrayNode parts = message.putArray("parts");
                ObjectNode textPart = parts.addObject();
                textPart.put("text", turn.content());
            } else {
                entry.put("type", "assistant");

                ObjectNode message = entry.putObject("message");
                message.put("role", "model"); // Qwen uses "model" not "assistant"

                ArrayNode parts = message.putArray("parts");
                ObjectNode textPart = parts.addObject();
                textPart.put("text", turn.content());
            }

            lastUuid = uuid;
            jsonl.append(entry.toString()).append("\n");
        }

        Files.writeString(sessionFile, jsonl.toString(), StandardCharsets.UTF_8);

        // Update sessions-index.json
        updateQwenSessionsIndex(projectDir.getParent(), sessionId, turns);

        String resumeCommand = "qwen --resume " + sessionId;
        return new ExportResult(sessionId, "qwen", sessionFile, resumeCommand, workingDirectory);
    }

    /**
     * Updates or creates the sessions-index.json for Qwen.
     */
    private static void updateQwenSessionsIndex(Path projectDir, String sessionId,
                                                  List<ChatHistory.Turn> turns) throws IOException {
        Path indexFile = projectDir.resolve("sessions-index.json");
        ObjectNode index;

        if (Files.exists(indexFile)) {
            index = (ObjectNode) MAPPER.readTree(indexFile.toFile());
        } else {
            index = MAPPER.createObjectNode();
        }

        // Build session metadata
        ObjectNode sessionMeta = index.putObject(sessionId);
        sessionMeta.put("id", sessionId);

        String summary = turns.stream()
                .filter(t -> "user".equals(t.role()))
                .findFirst()
                .map(t -> t.content().length() > 80 ? t.content().substring(0, 77) + "..." : t.content())
                .orElse("Resumed conversation");
        sessionMeta.put("summary", summary);
        sessionMeta.put("messageCount", turns.size());
        sessionMeta.put("createdAt", Instant.now().toString());
        sessionMeta.put("updatedAt", Instant.now().toString());

        Files.writeString(indexFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index),
                StandardCharsets.UTF_8);
    }

    // ─── OpenCode Export ───────────────────────────────────────────────────

    /**
     * Exports to OpenCode's native JSON file structure for import via `opencode import`.
     * <p>
     * OpenCode's import expects a JSON file matching Session.Info schema:
     * <ul>
     *   <li>info: Session metadata with id, slug, projectID, directory, title, version, time</li>
     *   <li>messages: Array of message objects with info and parts</li>
     * </ul>
     * <p>
     * Known bug: opencode import always assigns project_id to "global" regardless of cwd.
     * Workaround: After import, we manually update the session's project_id in the database.
     */
    private static ExportResult exportToOpenCode(List<ChatHistory.Turn> turns,
                                                 String sessionId,
                                                 String providerId,
                                                 String modelId,
                                                 Path workingDirectory) throws IOException {
        String homeDir = System.getProperty("user.home");
        String cwd = workingDirectory.toString();

        // Generate session ID - use ses_ prefix (opencode's actual format)
        String sessId = sessionId.startsWith("ses_") ? sessionId : "ses_" + sessionId.replace("-", "");

        // First, find the correct project ID for current working directory
        String actualProjectId = null;
        Path opencodeDb = Paths.get(homeDir, ".local", "share", "opencode", "opencode.db");
        if (Files.exists(opencodeDb)) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
                String findProjectSql = "SELECT id FROM project WHERE worktree = ?";
                try (PreparedStatement stmt = conn.prepareStatement(findProjectSql)) {
                    stmt.setString(1, cwd);
                    var rs = stmt.executeQuery();
                    if (rs.next()) {
                        actualProjectId = rs.getString("id");
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback: create a project ID if not found
        if (actualProjectId == null) {
            actualProjectId = "proj_" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Generate slug like opencode does
        String slug = generateOpenCodeSlug();

        // Build export JSON matching OpenCode's Session.Info schema EXACTLY
        com.fasterxml.jackson.databind.node.ObjectNode exportJson = MAPPER.createObjectNode();

        // ── Session info (matches Session.Info Zod schema) ──
        com.fasterxml.jackson.databind.node.ObjectNode info = exportJson.putObject("info");
        info.put("id", sessId);
        info.put("slug", slug);
        info.put("projectID", actualProjectId);
        info.put("directory", cwd);
        // NOTE: Don't include parentID and workspaceID if null - Zod schema rejects null
        // Only add them if they have actual values
        // info.putNull("parentID");  // Omitted - optional field
        // info.putNull("workspaceID");  // Omitted - optional field

        String title = turns.stream()
                .filter(t -> "user".equals(t.role()))
                .findFirst()
                .map(t -> t.content().length() > 80 ? t.content().substring(0, 77) + "..." : t.content())
                .orElse("Resumed conversation");
        info.put("title", title);
        info.put("version", "1.3.17");

        // Summary (optional but recommended)
        com.fasterxml.jackson.databind.node.ObjectNode sessionSummary = info.putObject("summary");
        sessionSummary.put("additions", 0);
        sessionSummary.put("deletions", 0);
        sessionSummary.put("files", 0);

        // Time object (required - milliseconds)
        long now = Instant.now().toEpochMilli();
        com.fasterxml.jackson.databind.node.ObjectNode time = info.putObject("time");
        time.put("created", now);
        // updated will be set after the message loop to the actual last timestamp
        time.put("updated", now);

        // ── Messages array ──
        com.fasterxml.jackson.databind.node.ArrayNode messagesArray = exportJson.putArray("messages");

        // Resolve once: both fields are invocation-scoped, not per-turn.
        String effectiveProviderId = providerId != null && !providerId.isEmpty() ? providerId : "opencode";
        String effectiveModelId = modelId != null && !modelId.isEmpty() ? modelId : "kimi-k2.5-free";

        String lastUserMsgId = null;
        String lastMsgId = null;
        long msgTimestamp = now;
        long lastTimestamp = now;

        // Pre-scan: if first turn is assistant, inject a synthetic user message
        // so every assistant message has a valid parentID (required by OpenCode Zod schema)
        boolean needsSyntheticUser = !turns.isEmpty() && "assistant".equals(turns.get(0).role());
        if (needsSyntheticUser) {
            String syntheticUserId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
            com.fasterxml.jackson.databind.node.ObjectNode syntheticMsg = MAPPER.createObjectNode();
            syntheticMsg.put("role", "user");
            com.fasterxml.jackson.databind.node.ObjectNode synthInfo = syntheticMsg.putObject("info");
            synthInfo.put("id", syntheticUserId);
            synthInfo.put("sessionID", sessId);
            synthInfo.put("role", "user");
            synthInfo.put("agent", "general");
            com.fasterxml.jackson.databind.node.ObjectNode synthModel = synthInfo.putObject("model");
            synthModel.put("providerID", effectiveProviderId);
            synthModel.put("modelID", effectiveModelId);
            synthInfo.putObject("summary").putArray("diffs");
            com.fasterxml.jackson.databind.node.ObjectNode synthTools = synthInfo.putObject("tools");
            synthTools.put("task", false);
            synthInfo.putObject("time").put("created", msgTimestamp);
            com.fasterxml.jackson.databind.node.ArrayNode synthParts = syntheticMsg.putArray("parts");
            com.fasterxml.jackson.databind.node.ObjectNode synthPart = MAPPER.createObjectNode();
            synthPart.put("type", "text");
            synthPart.put("text", "[Imported conversation — original user prompt not available]");
            synthPart.put("id", "prt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26));
            synthPart.put("sessionID", sessId);
            synthPart.put("messageID", syntheticUserId);
            com.fasterxml.jackson.databind.node.ObjectNode synthPartTime = synthPart.putObject("time");
            synthPartTime.put("start", msgTimestamp);
            synthPartTime.put("end", msgTimestamp + 1000);
            synthParts.add(synthPart);
            messagesArray.add(syntheticMsg);
            lastUserMsgId = syntheticUserId;
            lastMsgId = syntheticUserId;
            msgTimestamp += 10000;
        }

        for (ChatHistory.Turn turn : turns) {
            String content = turn.content();
            if (content == null || content.isBlank()) {
                continue;
            }

            String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
            String role = "assistant".equals(turn.role()) ? "assistant" : "user";

            com.fasterxml.jackson.databind.node.ObjectNode msgObj = MAPPER.createObjectNode();
            msgObj.put("role", role);

            // Message info
            com.fasterxml.jackson.databind.node.ObjectNode msgInfo = msgObj.putObject("info");
            msgInfo.put("id", msgId);
            msgInfo.put("sessionID", sessId);
            msgInfo.put("role", role);

            if ("user".equals(role)) {
                msgInfo.put("agent", "general");
                com.fasterxml.jackson.databind.node.ObjectNode model = msgInfo.putObject("model");
                model.put("providerID", effectiveProviderId);
                model.put("modelID", effectiveModelId);

                // Summary with diffs array (required for user messages)
                com.fasterxml.jackson.databind.node.ObjectNode msgSummary = msgInfo.putObject("summary");
                msgSummary.putArray("diffs");

                com.fasterxml.jackson.databind.node.ObjectNode tools = msgInfo.putObject("tools");
                tools.put("task", false);

                // Time
                com.fasterxml.jackson.databind.node.ObjectNode msgTime = msgInfo.putObject("time");
                msgTime.put("created", msgTimestamp);
                lastUserMsgId = msgId;
            } else {
                // Chain each message to the immediately preceding one
                if (lastMsgId != null) msgInfo.put("parentID", lastMsgId);
                msgInfo.put("mode", "general");
                msgInfo.put("agent", "general");

                // Path (required for assistant messages)
                com.fasterxml.jackson.databind.node.ObjectNode path = msgInfo.putObject("path");
                path.put("cwd", cwd);
                path.put("root", cwd);

                // Cost and tokens
                msgInfo.put("cost", 0.0);
                msgInfo.put("modelID", effectiveModelId);
                msgInfo.put("providerID", effectiveProviderId);

                com.fasterxml.jackson.databind.node.ObjectNode tokens = msgInfo.putObject("tokens");
                tokens.put("input", 0);
                tokens.put("output", 0);
                tokens.put("reasoning", 0);
                tokens.put("total", 0);
                com.fasterxml.jackson.databind.node.ObjectNode cache = tokens.putObject("cache");
                cache.put("read", 0);
                cache.put("write", 0);

                // Time
                com.fasterxml.jackson.databind.node.ObjectNode msgTime = msgInfo.putObject("time");
                msgTime.put("created", msgTimestamp);
                msgTime.put("completed", msgTimestamp + 5000);
                msgInfo.put("finish", "stop");
            }
            lastMsgId = msgId;

            // Parts array - text only
            com.fasterxml.jackson.databind.node.ArrayNode partsArray = msgObj.putArray("parts");
            com.fasterxml.jackson.databind.node.ObjectNode part = MAPPER.createObjectNode();
            part.put("type", "text");
            part.put("text", content);
            part.put("id", "prt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26));
            part.put("sessionID", sessId);
            part.put("messageID", msgId);
            com.fasterxml.jackson.databind.node.ObjectNode partTime = part.putObject("time");
            partTime.put("start", msgTimestamp);
            partTime.put("end", msgTimestamp + 3000);
            partsArray.add(part);

            messagesArray.add(msgObj);
            lastTimestamp = msgTimestamp + 3000;
            msgTimestamp += 10000;
        }

        // Set the session's updated time to the actual last message timestamp
        time.put("updated", lastTimestamp);

        // Write to temp file and import via opencode CLI
        Path tempFile = Files.createTempFile("opencode-import-", ".json");
        String storedProjectId = actualProjectId; // Capture for post-import fix
        try {
            Files.writeString(tempFile, exportJson.toString(), StandardCharsets.UTF_8);

            // Debug: Log the export file location
            System.err.println("[OpenCode Export] Debug: Export JSON written to: " + tempFile);

            // Import using opencode CLI - capture output for error reporting
            ProcessBuilder pb = new ProcessBuilder("opencode", "import", tempFile.toString());
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Capture all output
            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                    System.err.println("[OpenCode Import] " + line);
                }
                output = sb.toString();
            }

            try {
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    String errorMsg = "opencode import failed with exit code: " + exitCode + "\n" +
                            "Export file: " + tempFile + "\n" +
                            "Output: " + (output.isBlank() ? "(no output)" : output) + "\n" +
                            "Tip: You can manually retry with: opencode import " + tempFile;
                    throw new IOException(errorMsg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("opencode import interrupted", e);
            }

            // WORKAROUND for known bug: opencode import always assigns project_id to "global"
            // Manually update the session with the correct project_id and directory
            fixOpenCodeProjectAssignment(sessId, storedProjectId, cwd);
        } catch (IOException e) {
            // Keep the temp file on error for debugging
            System.err.println("[OpenCode Export] Keeping export file for debugging: " + tempFile);
            throw e;
        } finally {
            // Only delete on success
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }

        // NOTE: Do NOT call writeOpenCodeFileStorage() here — opencode import already
        // writes all messages to the DB and file storage. Calling it again would create
        // duplicate messages with different UUIDs, causing every turn to appear twice.

        // Return result - use -s without --continue (--continue causes hang on imported sessions)
        String resumeCommand = "opencode -s " + sessId;

        Path sessionPath = Paths.get(homeDir, ".local/share/opencode/storage/session/" + storedProjectId + "/" + sessId + ".json");
        return new ExportResult(sessId, "opencode", sessionPath, resumeCommand, workingDirectory);
    }

    /**
     * WORKAROUND: Fixes the known bug where opencode import always assigns project_id to "global".
     * After import, we manually update the session record in the SQLite database.
     */
    private static void fixOpenCodeProjectAssignment(String sessionId, String projectId, String cwd) {
        String homeDir = System.getProperty("user.home");
        Path opencodeDb = Paths.get(homeDir, ".local", "share", "opencode", "opencode.db");
        if (!Files.exists(opencodeDb)) {
            return; // Can't fix if DB doesn't exist
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
            // Set PRAGMAs for better concurrency handling
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 30000");
                stmt.execute("PRAGMA journal_mode = WAL");
            }

            String updateSql = "UPDATE session SET project_id = ?, directory = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, projectId);
                stmt.setString(2, cwd);
                stmt.setString(3, sessionId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    System.err.println("[OpenCode Export] Fixed project assignment for session " + sessionId);
                }
            }
        } catch (Exception e) {
            System.err.println("[OpenCode Export] Warning: Could not fix project assignment: " + e.getMessage());
            // Non-fatal - session may still be usable
        }
    }

    /**
     * Writes file-based storage entries so the TUI can display the imported session.
     */
    private static void writeOpenCodeFileStorage(String sessId, String projectId, String cwd, String title,
                                                   List<ChatHistory.Turn> turns, String providerId, String modelId,
                                                   long now) {
        String homeDir = System.getProperty("user.home");
        Path storageBase = Paths.get(homeDir, ".local", "share", "opencode", "storage");

        try {
            // Session file
            Path sessionDir = storageBase.resolve("session").resolve(projectId);
            Files.createDirectories(sessionDir);
            Path sessionFile = sessionDir.resolve(sessId + ".json");

            ObjectNode sessionJson = MAPPER.createObjectNode();
            sessionJson.put("id", sessId);
            sessionJson.put("slug", generateOpenCodeSlug());
            sessionJson.put("version", "1.14.20");
            sessionJson.put("projectID", projectId);
            sessionJson.put("directory", cwd);
            sessionJson.put("title", title);
            ObjectNode sessTime = sessionJson.putObject("time");
            sessTime.put("created", now);
            // updated will be set after the message loop to the actual last timestamp
            sessTime.put("updated", now);
            ObjectNode sessSummary = sessionJson.putObject("summary");
            sessSummary.put("additions", 0);
            sessSummary.put("deletions", 0);
            sessSummary.put("files", 0);

            Files.writeString(sessionFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sessionJson), StandardCharsets.UTF_8);

            // Message and part files
            String lastUserMsgId = null;
            String lastMsgId = null;
            long msgTimestamp = now;
            long lastTimestamp = now;

            // Pre-scan: if first turn is assistant, inject a synthetic user message
            boolean needsSyntheticUser = !turns.isEmpty() && "assistant".equals(turns.get(0).role());
            if (needsSyntheticUser) {
                String syntheticUserId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
                Path msgDir = storageBase.resolve("message").resolve(sessId);
                Files.createDirectories(msgDir);
                Path msgFile = msgDir.resolve(syntheticUserId + ".json");

                ObjectNode synthMsgJson = MAPPER.createObjectNode();
                synthMsgJson.put("id", syntheticUserId);
                synthMsgJson.put("sessionID", sessId);
                synthMsgJson.put("role", "user");
                synthMsgJson.put("agent", "general");
                ObjectNode synthModel = synthMsgJson.putObject("model");
                synthModel.put("providerID", providerId);
                synthModel.put("modelID", modelId);
                synthMsgJson.putObject("summary").putArray("diffs");
                synthMsgJson.putObject("tools").put("task", false);
                synthMsgJson.putObject("time").put("created", msgTimestamp);
                Files.writeString(msgFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(synthMsgJson), StandardCharsets.UTF_8);

                String synthPartId = "prt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
                Path partDir = storageBase.resolve("part").resolve(syntheticUserId);
                Files.createDirectories(partDir);
                Path partFile = partDir.resolve(synthPartId + ".json");
                ObjectNode synthPartJson = MAPPER.createObjectNode();
                synthPartJson.put("id", synthPartId);
                synthPartJson.put("sessionID", sessId);
                synthPartJson.put("messageID", syntheticUserId);
                synthPartJson.put("type", "text");
                synthPartJson.put("text", "[Imported conversation — original user prompt not available]");
                ObjectNode synthPartTime = synthPartJson.putObject("time");
                synthPartTime.put("start", msgTimestamp);
                synthPartTime.put("end", msgTimestamp + 1000);
                Files.writeString(partFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(synthPartJson), StandardCharsets.UTF_8);

                lastUserMsgId = syntheticUserId;
                lastMsgId = syntheticUserId;
                msgTimestamp += 10000;
            }

            for (ChatHistory.Turn turn : turns) {
                String content = turn.content();
                if (content == null || content.isBlank()) {
                    continue;
                }

                String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
                String role = "assistant".equals(turn.role()) ? "assistant" : "user";

                Path msgDir = storageBase.resolve("message").resolve(sessId);
                Files.createDirectories(msgDir);
                Path msgFile = msgDir.resolve(msgId + ".json");

                ObjectNode msgJson = MAPPER.createObjectNode();
                msgJson.put("id", msgId);
                msgJson.put("sessionID", sessId);
                msgJson.put("role", role);
                ObjectNode msgTime = msgJson.putObject("time");
                msgTime.put("created", msgTimestamp);

                if ("user".equals(role)) {
                    msgJson.put("agent", "general");
                    ObjectNode model = msgJson.putObject("model");
                    model.put("providerID", providerId);
                    model.put("modelID", modelId);
                    ObjectNode summary = msgJson.putObject("summary");
                    summary.putArray("diffs");
                    ObjectNode tools = msgJson.putObject("tools");
                    tools.put("task", false);
                    lastUserMsgId = msgId;
                } else {
                    // Chain each message to the immediately preceding one
                    if (lastMsgId != null) msgJson.put("parentID", lastMsgId);
                    msgJson.put("mode", "general");
                    msgJson.put("agent", "general");
                    ObjectNode path = msgJson.putObject("path");
                    path.put("cwd", cwd);
                    path.put("root", cwd);
                    msgJson.put("cost", 0.0);
                    msgJson.put("modelID", modelId);
                    msgJson.put("providerID", providerId);
                    ObjectNode tokens = msgJson.putObject("tokens");
                    tokens.put("input", 0);
                    tokens.put("output", 0);
                    tokens.put("reasoning", 0);
                    tokens.put("total", 0);
                    ObjectNode cache = tokens.putObject("cache");
                    cache.put("read", 0);
                    cache.put("write", 0);
                    msgTime.put("completed", msgTimestamp + 5000);
                    msgJson.put("finish", "stop");
                }
                lastMsgId = msgId;

                Files.writeString(msgFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgJson), StandardCharsets.UTF_8);

                // Part file
                String partId = "prt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
                Path partDir = storageBase.resolve("part").resolve(msgId);
                Files.createDirectories(partDir);
                Path partFile = partDir.resolve(partId + ".json");

                ObjectNode partJson = MAPPER.createObjectNode();
                partJson.put("id", partId);
                partJson.put("sessionID", sessId);
                partJson.put("messageID", msgId);
                partJson.put("type", "text");
                partJson.put("text", content);
                ObjectNode partTime = partJson.putObject("time");
                partTime.put("start", msgTimestamp);
                partTime.put("end", msgTimestamp + 3000);

                Files.writeString(partFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(partJson), StandardCharsets.UTF_8);

                lastTimestamp = msgTimestamp + 3000;
                msgTimestamp += 10000;
            }

            // Rewrite session file with correct updated timestamp
            sessTime.put("updated", lastTimestamp);
            Files.writeString(sessionFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sessionJson), StandardCharsets.UTF_8);

            System.err.println("[OpenCode Export] Wrote file-based storage for session " + sessId);
        } catch (Exception e) {
            System.err.println("[OpenCode Export] Warning: File storage write failed: " + e.getMessage());
        }
    }

    /**
     * Generates a random slug like OpenCode does (e.g., "nimble-squid")
     */
    private static String generateOpenCodeSlug() {
        String[] adjectives = {"nimble", "swift", "bright", "calm", "eager", "fine", "gentle", "happy", "jolly", "kind", "lively", "merry", "nice", "proud", "quick", "quiet", "smart", "steady", "upright", "wise"};
        String[] nouns = {"squid", "otter", "panda", "tiger", "lion", "eagle", "dolphin", "shark", "whale", "raven", "wolf", "fox", "bear", "hawk", "owl", "deer", "elk", "goose", "duck", "swan"};
        java.util.Random rand = new java.util.Random();
        return adjectives[rand.nextInt(adjectives.length)] + "-" + nouns[rand.nextInt(nouns.length)];
    }

    // ─── Gemini Export ───────────────────────────────────────────────────

    /**
     * Exports to Gemini CLI's native JSON format.
     * <p>
     * Written to: ~/.gemini/tmp/&lt;project-hash&gt;/chats/session-&lt;timestamp&gt;-&lt;uuid&gt;.json
     * <p>
     * Gemini --resume requires an index number (1-based from --list-sessions),
     * not a UUID. After writing the session file, we query Gemini's session list
     * to resolve the correct index for the exported session.
     */
    private static ExportResult exportToGemini(List<ChatHistory.Turn> turns,
                                               String sessionId,
                                               Path workingDirectory) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path geminiDir = Paths.get(homeDir, ".gemini", "tmp");
        Files.createDirectories(geminiDir);

        // Gemini groups sessions by sha256(projectRoot)
        String projectHash = sha256Hex(workingDirectory.toString());
        Path projectDir = geminiDir.resolve(projectHash).resolve("chats");
        Files.createDirectories(projectDir);

        // Write session JSON
        Instant now = Instant.now();
        String timestamp = now.toString().replace(':', '-').substring(0, 19);
        String fileName = "session-" + timestamp.substring(0, 16) + "-" + sessionId.substring(0, Math.min(8, sessionId.length())) + ".json";
        Path sessionFile = projectDir.resolve(fileName);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("sessionId", sessionId);
        root.put("projectHash", projectHash);
        root.put("startTime", now.toString());
        root.put("lastUpdated", now.toString());
        root.put("workingDirectory", workingDirectory.toString());

        ArrayNode messages = root.putArray("messages");
        for (ChatHistory.Turn turn : turns) {
            ObjectNode msg = messages.addObject();
            msg.put("id", UUID.randomUUID().toString());
            msg.put("timestamp", now.toString());
            msg.put("type", turn.role().equals("assistant") ? "gemini" : "user");
            msg.put("content", turn.content());
        }

        Files.writeString(sessionFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);

        // Resolve the Gemini session index by querying gemini --list-sessions
        Integer sessionIndex = resolveGeminiSessionIndex(workingDirectory, sessionId);
        String resumeCommand = sessionIndex != null
                ? "gemini --resume " + sessionIndex
                : "gemini --resume latest";
        return new ExportResult(sessionId, "gemini", sessionFile, resumeCommand, workingDirectory);
    }

    /**
     * Resolves a Gemini session UUID to its 1-based index number
     * by parsing the output of `gemini --list-sessions`.
     */
    static Integer resolveGeminiSessionIndex(Path workingDirectory, String sessionId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("gemini", "--list-sessions");
            pb.directory(workingDirectory.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }

            // Wait briefly - --list-sessions should exit quickly
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            // Parse lines like: "  1. Title text... [UUID]" or "  2. Another title... [UUID]"
            for (String line : output.split("\n")) {
                line = line.trim();
                // Match pattern: "N. ... [UUID]"
                int dotIdx = line.indexOf(". ");
                if (dotIdx <= 0) continue;

                String indexStr = line.substring(0, dotIdx).trim();
                int index;
                try {
                    index = Integer.parseInt(indexStr);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Extract UUID from brackets
                int openBracket = line.lastIndexOf('[');
                int closeBracket = line.lastIndexOf(']');
                if (openBracket > dotIdx && closeBracket > openBracket) {
                    String uuid = line.substring(openBracket + 1, closeBracket);
                    if (uuid.equals(sessionId)) {
                        return index;
                    }
                }
            }
        } catch (Exception e) {
            // Non-fatal - fall back to "latest"
        }
        return null;
    }

    // ─── Utility Methods ───────────────────────────────────────────────────

    /**
     * Encodes the current working directory into a safe project directory name.
     * Matches the encoding used by Claude Code and Qwen (slashes replaced with dashes).
     */
    private static String encodeProjectDir(String dir) {
        // Replace slashes with dashes, ensure it starts with a dash
        String encoded = dir.replace("/", "-").replace("\\", "-");
        if (!encoded.startsWith("-")) {
            encoded = "-" + encoded;
        }
        return encoded;
    }

    private static Path normalizeWorkingDirectory(Path workingDirectory) {
        Path dir = workingDirectory != null ? workingDirectory : Path.of(System.getProperty("user.dir"));
        return dir.toAbsolutePath().normalize();
    }

    private static String sha256Hex(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /**
     * Result of exporting a conversation to a native agent format.
     */
    public static class ExportResult {
        private final String sessionId;
        private final String agent;
        private final Path sessionPath;
        private final String resumeCommand;
        private final Path workingDirectory;

        public ExportResult(String sessionId, String agent, Path sessionPath, String resumeCommand,
                            Path workingDirectory) {
            this.sessionId = sessionId;
            this.agent = agent;
            this.sessionPath = sessionPath;
            this.resumeCommand = resumeCommand;
            this.workingDirectory = workingDirectory;
        }

        public String getSessionId() { return sessionId; }
        public String getAgent() { return agent; }
        public Path getSessionPath() { return sessionPath; }
        public String getResumeCommand() { return resumeCommand; }
        public Path getWorkingDirectory() { return workingDirectory; }

        @Override
        public String toString() {
            return String.format("Exported to %s (session: %s)\n" +
                            "  Path: %s\n" +
                            "  Resume with: %s",
                    agent, sessionId, sessionPath, resumeCommand);
        }
    }

    /**
     * Inserts exported messages into the OpenCode SQLite database.
     * This is required for opencode to actually find and display the conversation.
     */
    private static void insertOpenCodeMessagesToDb(List<ChatHistory.Turn> turns, String sessId, String modelId) throws IOException {
        Path opencodeDb = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
        if (!Files.exists(opencodeDb)) {
            throw new IOException("OpenCode database not found: " + opencodeDb);
        }

        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + opencodeDb.toAbsolutePath())) {
            // Set PRAGMAs for better concurrency handling
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 30000");
                stmt.execute("PRAGMA journal_mode = WAL");
            }

            conn.setAutoCommit(false);

            // First check if session exists, if not create it
            String checkSessionSql = "SELECT id FROM session WHERE id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSessionSql)) {
                checkStmt.setString(1, sessId);
                var rs = checkStmt.executeQuery();
                if (!rs.next()) {
                    // Need to create session - get project ID from existing session or create new
                    String projectId = getOrCreateOpenCodeProject(conn);

                    String insertSessionSql = """
                        INSERT INTO session (id, project_id, slug, directory, title, version, time_created, time_updated)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                    try (PreparedStatement insertSession = conn.prepareStatement(insertSessionSql)) {
                        insertSession.setString(1, sessId);
                        insertSession.setString(2, projectId);
                        insertSession.setString(3, "resumed-" + now);
                        insertSession.setString(4, System.getProperty("user.dir"));
                        insertSession.setString(5, "Migrated conversation");
                        insertSession.setString(6, "2");
                        insertSession.setLong(7, now);
                        insertSession.setLong(8, now + (turns.size() * 10));
                        insertSession.executeUpdate();
                    }
                } else {
                    // Update existing session with new timestamp and title
                    String updateSessionSql = """
                        UPDATE session SET time_updated = ?, title = ? WHERE id = ?
                    """;
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSessionSql)) {
                        updateStmt.setLong(1, now + (turns.size() * 10));
                        updateStmt.setString(2, "Migrated conversation");
                        updateStmt.setString(3, sessId);
                        updateStmt.executeUpdate();
                    }
                }
            }

            // Delete existing messages and parts for this session to avoid duplicates
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM part WHERE session_id = '" + sessId + "'");
                stmt.execute("DELETE FROM message WHERE session_id = '" + sessId + "'");
            }

            // Insert messages
            String insertMsgSql = """
                INSERT INTO message (id, session_id, time_created, time_updated, data)
                VALUES (?, ?, ?, ?, ?)
            """;
            String insertPartSql = """
                INSERT INTO part (id, message_id, session_id, time_created, time_updated, data)
                VALUES (?, ?, ?, ?, ?, ?)
            """;

            long msgTime = now;
            for (ChatHistory.Turn turn : turns) {
                String msgId = "msg_" + UUID.randomUUID().toString().substring(0, 12);

                // Create message data JSON - matching OpenCode's actual schema
                String role = "user".equals(turn.role()) ? "user" : "assistant";
                com.fasterxml.jackson.databind.node.ObjectNode msgData = MAPPER.createObjectNode();
                msgData.put("role", role);
                
                // Mode matches OpenCode format (use "build" as default mode)
                msgData.put("mode", "build");
                msgData.put("agent", "build");
                
                // Model in nested object format (not flat fields)
                com.fasterxml.jackson.databind.node.ObjectNode model = msgData.putObject("model");
                model.put("providerID", "opencode");
                model.put("modelID", modelId);
                
                // Variant is required by OpenCode
                msgData.put("variant", "high");
                
                // Path is required
                com.fasterxml.jackson.databind.node.ObjectNode path = msgData.putObject("path");
                path.put("cwd", System.getProperty("user.dir"));
                path.put("root", System.getProperty("user.dir"));

                // Cost and finish
                msgData.put("cost", 0);
                msgData.put("finish", "stop");

                // Tokens structure - with total
                com.fasterxml.jackson.databind.node.ObjectNode tokens = msgData.putObject("tokens");
                tokens.put("input", 0);
                tokens.put("output", 0);
                tokens.put("reasoning", 0);
                tokens.put("total", 0);
                com.fasterxml.jackson.databind.node.ObjectNode cache = tokens.putObject("cache");
                cache.put("read", 0);
                cache.put("write", 0);

                // Time - just "created" (milliseconds) matching normal sessions
                com.fasterxml.jackson.databind.node.ObjectNode timeObj = msgData.putObject("time");
                timeObj.put("created", msgTime * 1000);
                
                // Summary for user messages (required by OpenCode)
                if ("user".equals(role)) {
                    com.fasterxml.jackson.databind.node.ObjectNode summary = msgData.putObject("summary");
                    summary.putArray("diffs");
                }

                try (PreparedStatement insertMsg = conn.prepareStatement(insertMsgSql)) {
                    insertMsg.setString(1, msgId);
                    insertMsg.setString(2, sessId);
                    insertMsg.setLong(3, msgTime);
                    insertMsg.setLong(4, msgTime + 5);
                    insertMsg.setString(5, msgData.toString());
                    insertMsg.executeUpdate();
                }

                // Insert part - use JSON data column instead of individual columns
                String partId = "part_" + UUID.randomUUID().toString().substring(0, 8);
                com.fasterxml.jackson.databind.node.ObjectNode partData = MAPPER.createObjectNode();
                partData.put("type", "text");
                partData.put("text", turn.content());
                com.fasterxml.jackson.databind.node.ObjectNode partTime = partData.putObject("time");
                partTime.put("start", msgTime);
                partTime.put("end", msgTime + 3);

                try (PreparedStatement insertPart = conn.prepareStatement(insertPartSql)) {
                    insertPart.setString(1, partId);
                    insertPart.setString(2, msgId);
                    insertPart.setString(3, sessId);
                    insertPart.setLong(4, msgTime);
                    insertPart.setLong(5, msgTime + 3);
                    insertPart.setString(6, partData.toString());
                    insertPart.executeUpdate();
                }

                msgTime += 10;
            }

            conn.commit();
        } catch (SQLException e) {
            throw new IOException("Failed to insert messages into OpenCode database: " + e.getMessage(), e);
        }
    }

    /**
     * Gets or creates a project ID for the current working directory.
     */
    private static String getOrCreateOpenCodeProject(Connection conn) throws SQLException {
        String cwd = System.getProperty("user.dir");
        String slug = "project-" + Math.abs(cwd.hashCode());

        String findProjectSql = "SELECT id FROM project WHERE worktree = ?";
        try (PreparedStatement stmt = conn.prepareStatement(findProjectSql)) {
            stmt.setString(1, cwd);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
        }

        // Create new project
        String projectId = "proj_" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis() / 1000;

        String insertProjectSql = """
            INSERT INTO project (id, worktree, name, sandboxes, time_created, time_updated)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement stmt = conn.prepareStatement(insertProjectSql)) {
            stmt.setString(1, projectId);
            stmt.setString(2, cwd);
            stmt.setString(3, "Migrated Project");
            stmt.setString(4, "[]");
            stmt.setLong(5, now);
            stmt.setLong(6, now);
            stmt.executeUpdate();
        }

        return projectId;
    }
}
