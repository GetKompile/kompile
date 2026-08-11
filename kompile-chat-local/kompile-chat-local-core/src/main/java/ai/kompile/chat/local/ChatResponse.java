package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structured chat result returned after a backend has applied its model-owned
 * output protocol. Kompile never interprets raw control tokens.
 */
public record ChatResponse(
        String rawText,
        String content,
        String reasoningContent,
        List<ChatToolCall> toolCalls,
        List<String> protocolErrors) {

    public ChatResponse {
        rawText = rawText == null ? "" : rawText;
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        protocolErrors = protocolErrors == null ? List.of() : List.copyOf(protocolErrors);
    }

    public static ChatResponse content(String content) {
        return new ChatResponse(content, content, "", List.of(), List.of());
    }

    public static ChatResponse toolCalls(String rawText, List<ChatToolCall> calls) {
        return new ChatResponse(rawText, "", "", calls, List.of());
    }

    public boolean isProtocolValid() {
        return protocolErrors.isEmpty();
    }

    /** Parse only the canonical SDX structured transport result, never model text. */
    @SuppressWarnings("unchecked")
    public static ChatResponse fromStructuredJson(String json) {
        final Map<String, Object> root;
        try {
            root = MiniJson.parseObject(json);
        } catch (RuntimeException e) {
            throw new ChatException("SDX returned invalid structured chat JSON", e);
        }
        if (!root.containsKey("rawText") || !root.containsKey("content")
                || !root.containsKey("toolCalls") || !root.containsKey("protocolErrors")) {
            throw new ChatException("SDX response was not a canonical structured chat result");
        }
        List<ChatToolCall> calls = new ArrayList<>();
        Object callsValue = root.get("toolCalls");
        if (callsValue != null) {
            if (!(callsValue instanceof List<?> values)) {
                throw new ChatException("SDX structured chat field toolCalls must be an array");
            }
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> rawCall)) {
                    throw new ChatException("SDX structured tool call must be an object");
                }
                Object name = rawCall.get("name");
                Object arguments = rawCall.get("arguments");
                if (!(name instanceof String) || !(arguments instanceof Map<?, ?>)) {
                    throw new ChatException("SDX structured tool call is missing name or arguments");
                }
                calls.add(new ChatToolCall(
                        rawCall.get("id") instanceof String id ? id : null,
                        (String) name,
                        (Map<String, Object>) arguments));
            }
        }
        List<String> errors = new ArrayList<>();
        Object errorsValue = root.get("protocolErrors");
        if (errorsValue != null) {
            if (!(errorsValue instanceof List<?> values)) {
                throw new ChatException("SDX structured chat field protocolErrors must be an array");
            }
            for (Object value : values) {
                if (!(value instanceof String)) {
                    throw new ChatException("SDX protocol error must be a string");
                }
                errors.add((String) value);
            }
        }
        return new ChatResponse(
                stringValue(root.get("rawText")),
                stringValue(root.get("content")),
                stringValue(root.get("reasoningContent")),
                calls,
                errors);
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : "";
    }
}
