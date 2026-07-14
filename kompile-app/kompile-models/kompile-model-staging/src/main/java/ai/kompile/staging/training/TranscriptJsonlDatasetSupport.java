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

package ai.kompile.staging.training;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.adapters.OpenCodeAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes native coding-agent transcript streams into the same {@code messages} rows accepted by
 * the generic training JSONL loader.
 *
 * <p>Each native JSONL record is one event, not one supervised example. This reader keeps history
 * per session and emits one example after every real assistant response. The emitted row contains
 * all conversation messages through that response, so the existing ChatML loader can use the final
 * assistant message as the completion and the preceding messages as the prompt.</p>
 */
public final class TranscriptJsonlDatasetSupport {

    public static final String CLAUDE_CODE_JSONL = "CLAUDE_CODE_JSONL";
    public static final String OPENCODE_JSONL = "OPENCODE_JSONL";

    private static final ObjectMapper MAPPER = ChatAdapterSupport.MAPPER;
    private static final String DEFAULT_SESSION = "transcript";

    private TranscriptJsonlDatasetSupport() {
    }

    /**
     * Returns the canonical native-transcript format, or {@code null} when the value is not one of
     * the supported formats.
     */
    public static String canonicalFormat(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }

        String normalized = format.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        return switch (normalized) {
            case "CLAUDE", "CLAUDE_CODE", "CLAUDE_JSONL", "CLAUDE_CODE_JSONL" -> CLAUDE_CODE_JSONL;
            case "OPENCODE", "OPEN_CODE", "OPENCODE_JSONL", "OPEN_CODE_JSONL" -> OPENCODE_JSONL;
            default -> null;
        };
    }

    public static boolean supports(String format) {
        return canonicalFormat(format) != null;
    }

    /**
     * Infers a native format from an explicit compound filename. Plain {@code .jsonl} remains the
     * generic JSONL format so existing datasets are never reinterpreted.
     */
    public static String inferFormat(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".claude-code.jsonl") || name.endsWith(".claude_code.jsonl")
                || name.endsWith(".claude.jsonl")) {
            return CLAUDE_CODE_JSONL;
        }
        if (name.endsWith(".opencode.jsonl") || name.endsWith(".open-code.jsonl")
                || name.endsWith(".open_code.jsonl")) {
            return OPENCODE_JSONL;
        }
        return null;
    }

    /**
     * Detects native rows in ordinary {@code .jsonl} files. Claude Code stores sessions under UUID
     * filenames, so direct-path training cannot rely on a compound filename. Detection requires
     * provider-specific wrapper fields and leaves ordinary training JSONL untouched.
     */
    public static String detectFormat(Path path) {
        String namedFormat = inferFormat(path);
        if (namedFormat != null) {
            return namedFormat;
        }
        if (path == null || path.getFileName() == null || !Files.isRegularFile(path)
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int inspected = 0;
            while ((line = reader.readLine()) != null && inspected < 32) {
                if (line.isBlank()) {
                    continue;
                }
                inspected++;

                JsonNode record = MAPPER.readTree(line);
                if (record == null || !record.isObject()) {
                    return null;
                }

                String type = lower(text(record, "type"));
                JsonNode message = record.path("message");
                boolean claudeMessage = record.hasNonNull("sessionId")
                        && message.isObject()
                        && (type.equals("user") || type.equals("assistant"))
                        && (message.has("content") || message.has("role"));
                if (claudeMessage) {
                    return CLAUDE_CODE_JSONL;
                }

                boolean openCodeMessage = record.hasNonNull("session_id")
                        && record.hasNonNull("id")
                        && record.has("data")
                        && !type.isBlank();
                if (openCodeMessage) {
                    return OPENCODE_JSONL;
                }
            }
        } catch (Exception ignored) {
            // The selected generic JSONL loader will provide its normal line-numbered error.
        }
        return null;
    }

    /**
     * Reads and validates the complete transcript while retaining at most {@code maxRows}
     * normalized examples. A negative limit retains every example.
     */
    public static ScanResult scan(Path path, String format, int maxRows) throws IOException {
        String canonical = canonicalFormat(format);
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported transcript JSONL format: " + format);
        }
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Transcript JSONL file not found: " + path);
        }

        Map<String, List<Map<String, String>>> histories = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long totalSamples = 0L;
        long sourceRecords = 0L;
        long totalTokenCount = 0L;
        int minTokenCount = Integer.MAX_VALUE;
        int maxTokenCount = 0;
        String fallbackSession = fallbackSession(path);

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                sourceRecords++;

                JsonNode record = parseRecord(path, canonical, lineNumber, line);
                ParsedTurn turn = CLAUDE_CODE_JSONL.equals(canonical)
                        ? parseClaudeCodeTurn(record, fallbackSession)
                        : parseOpenCodeTurn(path, record, fallbackSession, lineNumber);
                if (turn == null) {
                    continue;
                }

                List<Map<String, String>> history =
                        histories.computeIfAbsent(turn.sessionId(), ignored -> new ArrayList<>());
                history.add(message(turn.role(), turn.content()));

                if (!turn.trainableResponse()) {
                    continue;
                }

                totalSamples++;
                int tokenCount = approximateTokenCount(history);
                totalTokenCount += tokenCount;
                minTokenCount = Math.min(minTokenCount, tokenCount);
                maxTokenCount = Math.max(maxTokenCount, tokenCount);

                if (maxRows < 0 || rows.size() < maxRows) {
                    rows.add(toNormalizedRow(canonical, turn.sessionId(), lineNumber, history));
                }
            }
        }

        if (totalSamples == 0) {
            minTokenCount = 0;
        }
        return new ScanResult(
                List.copyOf(rows),
                totalSamples,
                sourceRecords,
                totalTokenCount,
                minTokenCount,
                maxTokenCount);
    }

    private static JsonNode parseRecord(Path path, String format, int lineNumber, String line)
            throws IOException {
        try {
            JsonNode record = MAPPER.readTree(line);
            if (record == null || !record.isObject()) {
                throw new IOException("Expected a JSON object");
            }
            return record;
        } catch (Exception e) {
            throw malformed(path, format, lineNumber, e);
        }
    }

    private static ParsedTurn parseClaudeCodeTurn(JsonNode record, String fallbackSession) {
        String type = text(record, "type");
        String normalizedType = lower(type);

        boolean conversationalType = normalizedType.isBlank()
                || normalizedType.equals("user")
                || normalizedType.equals("human")
                || normalizedType.equals("assistant")
                || normalizedType.equals("ai")
                || normalizedType.equals("model")
                || normalizedType.equals("system")
                || normalizedType.equals("tool")
                || normalizedType.equals("tool_result");
        if (!conversationalType) {
            return null;
        }

        String role = ChatAdapterSupport.extractRole(record);
        if (role == null && (normalizedType.equals("system") || normalizedType.equals("tool")
                || normalizedType.equals("tool_result"))) {
            role = normalizedType.startsWith("tool") ? "tool" : "system";
        }
        String content = extractClaudeContent(record);
        if (role == null || content == null || content.isBlank()) {
            return null;
        }

        role = normalizeConversationRole(role);
        if (role == null) {
            return null;
        }
        if ("user".equals(role) && content.startsWith("[tool-result]")) {
            role = "tool";
        }

        String sessionId = firstText(record, "sessionId", "session_id", "sessionID");
        if (sessionId == null) {
            sessionId = firstText(record.path("message"), "sessionId", "session_id", "sessionID");
        }
        if (sessionId == null) {
            sessionId = fallbackSession;
        }

        boolean trainable = normalizedType.equals("assistant")
                || (normalizedType.isBlank() && "assistant".equals(role));
        return new ParsedTurn(sessionId, role, content, trainable);
    }

    private static ParsedTurn parseOpenCodeTurn(
            Path path, JsonNode record, String fallbackSession, int lineNumber) throws IOException {
        JsonNode data = record.get("data");
        if (data == null || data.isNull()) {
            data = record;
        } else if (data.isTextual()) {
            String rawData = data.asText();
            if (rawData.isBlank()) {
                return null;
            }
            try {
                data = MAPPER.readTree(rawData);
            } catch (Exception e) {
                throw malformed(path, OPENCODE_JSONL, lineNumber, e);
            }
        }
        if (!data.isObject()) {
            throw malformed(path, OPENCODE_JSONL, lineNumber,
                    new IOException("OpenCode data must be a JSON object or encoded JSON object"));
        }

        String storedType = firstText(record, "type", "message_type", "messageType");
        if (storedType == null) {
            storedType = firstText(data, "type", "role");
        }
        long created = firstLong(record, "time_created", "timeCreated", "created", "timestamp");

        ChatTurn chatTurn = OpenCodeAdapter.parseSessionMessage(
                storedType, MAPPER.writeValueAsString(data), created);
        if (chatTurn == null || chatTurn.content() == null || chatTurn.content().isBlank()) {
            return null;
        }

        String normalizedType = lower(storedType);
        String role = normalizeConversationRole(chatTurn.role());
        if (role == null) {
            return null;
        }
        if (normalizedType.equals("shell")) {
            role = "tool";
        }

        String sessionId = firstText(record, "session_id", "sessionId", "sessionID");
        if (sessionId == null) {
            sessionId = firstText(data, "session_id", "sessionId", "sessionID");
        }
        if (sessionId == null) {
            sessionId = fallbackSession;
        }

        boolean explicitAssistant = normalizedType.equals("assistant");
        boolean genericMessage = normalizedType.isBlank() || normalizedType.equals("message");
        boolean trainable = explicitAssistant || (genericMessage && chatTurn.isAssistant());
        return new ParsedTurn(sessionId, role, chatTurn.content(), trainable);
    }

    private static Map<String, Object> toNormalizedRow(
            String format,
            String sessionId,
            int sourceLine,
            List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>(history.size());
        for (Map<String, String> item : history) {
            messages.add(new LinkedHashMap<>(item));
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("format", format);
        row.put("session_id", sessionId);
        row.put("source_line", sourceLine);
        row.put("messages", messages);
        return row;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static int approximateTokenCount(List<Map<String, String>> history) {
        int count = 0;
        for (Map<String, String> item : history) {
            String content = item.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            count += content.trim().split("\\s+").length;
        }
        return count;
    }

    private static String extractClaudeContent(JsonNode record) {
        JsonNode message = record.path("message");
        JsonNode content = message.isObject() ? message.path("content") : record.path("content");
        if (!content.isArray()) {
            return ChatAdapterSupport.extractContent(record);
        }

        StringBuilder out = new StringBuilder();
        for (JsonNode block : content) {
            String type = lower(text(block, "type"));
            String value;
            switch (type) {
                case "text":
                    value = text(block, "text");
                    break;
                case "thinking":
                    String thinking = firstText(block, "thinking", "text");
                    value = thinking == null ? null : "[thinking] " + thinking;
                    break;
                case "tool_use":
                    String name = firstText(block, "name", "tool");
                    if (name == null) {
                        name = "unknown";
                    }
                    value = "[tool:" + name + "]";
                    JsonNode input = block.path("input");
                    if (!input.isMissingNode() && !input.isNull() && !"{}".equals(input.toString())) {
                        value += " " + renderJsonValue(input);
                    }
                    break;
                case "tool_result":
                    String result = renderJsonValue(block.path("content"));
                    boolean error = block.path("is_error").asBoolean(false);
                    String marker = error ? "[tool-error]" : "[tool-result]";
                    value = result.isBlank() ? marker : marker + " " + result;
                    break;
                case "image":
                    value = "[image]";
                    break;
                default:
                    value = ChatAdapterSupport.extractContent(block);
                    break;
            }
            appendLine(out, value);
        }
        return out.length() > 0 ? out.toString() : ChatAdapterSupport.extractContent(record);
    }

    private static String renderJsonValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode item : value) {
                String text = ChatAdapterSupport.extractContent(item);
                appendLine(out, text != null && !text.isBlank() ? text : renderJsonValue(item));
            }
            return out.toString();
        }
        return value.toString();
    }

    private static void appendLine(StringBuilder out, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(value);
    }

    private static String normalizeConversationRole(String role) {
        String normalized = lower(role);
        return switch (normalized) {
            case "human", "user" -> "user";
            case "assistant", "ai", "model", "bot", "synthetic" -> "assistant";
            case "system" -> "system";
            case "tool", "tool_result", "tool_use", "shell" -> "tool";
            default -> null;
        };
    }

    private static String fallbackSession(Path path) {
        String fileName = path.getFileName() == null ? DEFAULT_SESSION : path.getFileName().toString();
        int extension = fileName.indexOf('.');
        String base = extension > 0 ? fileName.substring(0, extension) : fileName;
        return base.isBlank() ? DEFAULT_SESSION : base;
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next field.
                }
            }
        }
        return 0L;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static IOException malformed(
            Path path, String format, int lineNumber, Exception cause) {
        String detail = cause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = cause.getClass().getSimpleName();
        }
        return new IOException(
                "Malformed " + format + " record at " + path + ":" + lineNumber + ": " + detail,
                cause);
    }

    private record ParsedTurn(
            String sessionId,
            String role,
            String content,
            boolean trainableResponse) {
    }

    public record ScanResult(
            List<Map<String, Object>> rows,
            long totalSamples,
            long sourceRecords,
            long totalTokenCount,
            int minTokenCount,
            int maxTokenCount) {

        public double averageTokenCount() {
            return totalSamples > 0 ? (double) totalTokenCount / totalSamples : 0.0;
        }
    }
}
