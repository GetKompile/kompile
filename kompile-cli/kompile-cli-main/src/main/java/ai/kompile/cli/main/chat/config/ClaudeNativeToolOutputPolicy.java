package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolResultReferenceCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Model-facing native output projection, applied by Claude's PostToolUse hook before delivery. */
final class ClaudeNativeToolOutputPolicy {
    static final String CALLBACK_ID = "kompile-native-output-v1";
    static final String MATCHER = "^(Read|Grep|Bash)$";
    static final int INLINE_CHARS = 16_000;
    private static final int PREVIEW_CHARS = 1_000;
    private final ObjectMapper mapper;
    private final ToolResultReferenceCache cache;

    ClaudeNativeToolOutputPolicy(ObjectMapper mapper, ToolResultReferenceCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** Unknown/multimodal shapes remain untouched; no permission or execution-status changes. */
    ObjectNode project(JsonNode event, String ownerSession) {
        ObjectNode unchanged = mapper.createObjectNode();
        if (!"PostToolUse".equals(event.path("hook_event_name").asText())) return unchanged;
        String name = event.path("tool_name").asText("");
        JsonNode original = event.path("tool_response");
        if (!original.isObject()) return unchanged;
        ObjectNode output = original.deepCopy();
        List<String> notes = new ArrayList<>();
        String callId = event.path("tool_use_id").asText("");
        try {
            switch (name) {
                case "Read" -> {
                    JsonNode file = original.path("file");
                    if (!"text".equals(original.path("type").asText())
                            || !file.path("filePath").isTextual() || !file.path("content").isTextual()
                            || !file.path("numLines").isIntegralNumber()
                            || !file.path("startLine").isIntegralNumber()
                            || !file.path("totalLines").isIntegralNumber()
                            || file.path("content").asText().length() < INLINE_CHARS) return unchanged;
                    ObjectNode projected = (ObjectNode) output.path("file");
                    String preview = retain(ownerSession, name, "file.content", callId,
                            file.path("content").asText(), notes);
                    projected.put("content", preview);
                    projected.put("numLines", lineCount(preview));
                    projected.put("truncatedByTokenCap", true);
                }
                case "Bash" -> {
                    if (!original.path("stdout").isTextual() || !original.path("stderr").isTextual()
                            || !original.path("interrupted").isBoolean()
                            || original.path("isImage").asBoolean()
                            || original.hasNonNull("structuredContent")) return unchanged;
                    String stdout = original.path("stdout").asText();
                    String stderr = original.path("stderr").asText();
                    if ((long) stdout.length() + stderr.length() < INLINE_CHARS) return unchanged;
                    if (stdout.length() > PREVIEW_CHARS) output.put("stdout",
                            retain(ownerSession, name, "stdout", callId, stdout, notes));
                    if (stderr.length() > PREVIEW_CHARS) output.put("stderr",
                            retain(ownerSession, name, "stderr", callId, stderr, notes));
                }
                case "Grep" -> {
                    JsonNode names = original.path("filenames");
                    if (!names.isArray() || !original.path("numFiles").isIntegralNumber()
                            || (original.has("content") && !original.path("content").isTextual())) return unchanged;
                    StringBuilder paths = new StringBuilder();
                    for (JsonNode path : names) {
                        if (!path.isTextual()) return unchanged;
                        if (!paths.isEmpty()) paths.append('\n');
                        paths.append(path.asText());
                    }
                    String content = original.path("content").asText("");
                    if ((long) paths.length() + content.length() < INLINE_CHARS) return unchanged;
                    if (content.length() > PREVIEW_CHARS) {
                        String preview = retain(ownerSession, name, "content", callId, content, notes);
                        output.put("content", preview);
                        output.put("numLines", lineCount(preview));
                    }
                    if (paths.length() > PREVIEW_CHARS) {
                        // JSON preserves exact path boundaries, including embedded newlines.
                        retain(ownerSession, name, "filenames (JSON array)", callId, names.toString(), notes);
                        ArrayNode preview = mapper.createArrayNode();
                        int chars = 0;
                        for (JsonNode path : names) {
                            if (preview.size() >= 10 || chars + path.asText().length() > PREVIEW_CHARS) break;
                            preview.add(path.asText());
                            chars += path.asText().length();
                        }
                        output.set("filenames", preview);
                        if (!original.has("totalFiles")) output.set("totalFiles", original.path("numFiles"));
                        output.put("numFiles", preview.size());
                    }
                }
                default -> { return unchanged; }
            }
        } catch (RuntimeException failure) {
            // Full data wins over a pretend bound when durable publication fails.
            ObjectNode specific = unchanged.putObject("hookSpecificOutput");
            specific.put("hookEventName", "PostToolUse");
            specific.put("additionalContext", "Kompile could not durably cache this native tool output; "
                    + "the original result is retained unchanged. No recovery handle was issued.");
            return unchanged;
        }
        if (notes.isEmpty()) return unchanged;
        ObjectNode specific = unchanged.putObject("hookSpecificOutput");
        specific.put("hookEventName", "PostToolUse");
        specific.set("updatedToolOutput", output);
        specific.put("additionalContext", "Kompile bounded oversized native text output. Excerpts may end mid-line. "
                + "Do not infer omitted content is absent or advance past a partial source line; "
                + "use the durable references below for the rest. Tool execution/status and source files are unchanged.\n"
                + String.join("\n", notes));
        return unchanged;
    }

    private String retain(String owner, String tool, String field, String callId, String text, List<String> notes) {
        ToolResult stored = cache.storeAndSummarize(owner, tool, tool + " " + field, text,
                Map.of("nativeClaude", true, "field", field, "toolUseId", callId));
        if (!Boolean.TRUE.equals(stored.getMetadata().get("durable"))
                || !Boolean.TRUE.equals(stored.getMetadata().get("cached"))
                || !(stored.getMetadata().get("result_id") instanceof String handle)) {
            throw new IllegalStateException("Full native output was not durably published");
        }
        notes.add(tool + " " + field + ": " + text.length() + " characters retained as ref:" + handle
                + ". Retrieve with fetch_result(result_id=\"" + handle
                + "\", offset=1, limit=40), optionally pattern=<regex>. Follow nextOffset/character_offset "
                + "for long-line continuation. Do not re-run the command or bulk-read the transcript to recover it.");
        return prefix(text);
    }

    private static String prefix(String text) {
        int end = Math.min(PREVIEW_CHARS, text.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        if (end > 0 && text.charAt(end - 1) == '\r') end--;
        return text.substring(0, end);
    }

    private static long lineCount(String text) {
        return text.lines().count();
    }
}
