package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.Map;
import java.util.Optional;

/**
 * Parses a potential tool-call JSON object from raw model output.
 *
 * <p>The model is prompted to emit a bare JSON object when it wants to call a tool:
 * <pre>
 * {"tool": "tool_name", "args": {...}}
 * </pre>
 * or it may wrap it in a fenced code block:
 * <pre>
 * ```json
 * {"tool": "tool_name", "args": {...}}
 * ```
 * </pre>
 *
 * <p>Two extraction strategies are tried in order:</p>
 * <ol>
 *   <li>Fenced block: find {@code ```json} … {@code ```}.</li>
 *   <li>Bare object: find the first {@code '{' } and last {@code '}'} and try that substring.</li>
 * </ol>
 */
public final class ToolCallParser {

    private ToolCallParser() {}

    /**
     * A parsed tool call with the tool name and its argument map.
     *
     * @param tool the tool name string
     * @param args the argument map (may be empty, never null)
     */
    public record ToolCall(String tool, Map<String, Object> args) {}

    /**
     * Attempt to parse a tool call from the model's raw output.
     *
     * @param modelOutput the raw string returned by the model
     * @return an {@link Optional} containing the parsed {@link ToolCall}, or empty
     *         if no valid tool-call JSON was found
     */
    @SuppressWarnings("unchecked")
    public static Optional<ToolCall> parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return Optional.empty();
        }

        // Strategy 1: fenced ```json ... ``` block
        String fenced = extractFencedBlock(modelOutput);
        if (fenced != null) {
            Optional<ToolCall> result = tryParse(fenced);
            if (result.isPresent()) {
                return result;
            }
        }

        // Strategy 2: first '{' … last '}'
        int firstBrace = modelOutput.indexOf('{');
        int lastBrace = modelOutput.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String candidate = modelOutput.substring(firstBrace, lastBrace + 1);
            Optional<ToolCall> result = tryParse(candidate);
            if (result.isPresent()) {
                return result;
            }
        }

        return Optional.empty();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extract the content of a {@code ```json ... ```} fenced block from the string,
     * or return {@code null} if no such block is found.
     */
    private static String extractFencedBlock(String text) {
        String fence = "```json";
        int start = text.indexOf(fence);
        if (start < 0) {
            return null;
        }
        int contentStart = start + fence.length();
        // skip optional newline immediately after the opening fence
        if (contentStart < text.length() && text.charAt(contentStart) == '\n') {
            contentStart++;
        }
        int end = text.indexOf("```", contentStart);
        if (end < 0) {
            return null;
        }
        return text.substring(contentStart, end).trim();
    }

    /**
     * Try to parse a candidate string as a tool-call JSON object.
     * Returns a populated {@link ToolCall} if the JSON has {@code "tool"} (String) and
     * {@code "args"} (Map) keys; {@link Optional#empty()} otherwise.
     */
    @SuppressWarnings("unchecked")
    private static Optional<ToolCall> tryParse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> parsed = MiniJson.parseObject(candidate);
            Object toolObj = parsed.get("tool");
            Object argsObj = parsed.get("args");

            if (!(toolObj instanceof String tool) || tool.isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> args;
            if (argsObj instanceof Map<?, ?> rawArgs) {
                args = (Map<String, Object>) rawArgs;
            } else {
                args = Map.of();
            }
            return Optional.of(new ToolCall(tool, args));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
