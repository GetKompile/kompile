package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-neutral structured chat request. It contains semantics only:
 * messages, portable function schemas, and whether tool use is allowed.
 */
public record ChatRequest(List<Message> messages, String toolsJson, ToolChoice toolChoice) {

    public enum ToolChoice {
        AUTO,
        REQUIRED,
        NONE
    }

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolsJson = toolsJson == null || toolsJson.isBlank() ? "[]" : toolsJson;
        toolChoice = toolChoice == null ? ToolChoice.AUTO : toolChoice;
    }

    public static ChatRequest of(List<Message> messages, String toolsJson) {
        return new ChatRequest(messages, toolsJson, ToolChoice.AUTO);
    }

    /** Serialize the transport contract consumed by SameDiff/SDX structured chat. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> encodedMessages = new ArrayList<>();
        for (Message message : messages) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("role", message.role());
            encoded.put("content", message.content());
            if (!message.toolCalls().isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (ChatToolCall call : message.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                    Map<String, Object> value = new LinkedHashMap<>();
                    if (call.id() != null) value.put("id", call.id());
                    value.put("type", "function");
                    value.put("function", function);
                    calls.add(value);
                }
                encoded.put("tool_calls", calls);
            }
            if (message.toolCallId() != null) {
                encoded.put("tool_call_id", message.toolCallId());
            }
            if (message.toolName() != null) {
                encoded.put("name", message.toolName());
            }
            encodedMessages.add(encoded);
        }
        root.put("messages", encodedMessages);
        root.put("tools", toolChoice == ToolChoice.NONE ? List.of() : parseTools());
        root.put("tool_choice", toolChoice.name().toLowerCase(Locale.ROOT));
        root.put("add_generation_prompt", true);
        return MiniJson.write(root);
    }

    @SuppressWarnings("unchecked")
    List<Object> parseTools() {
        Object parsed;
        try {
            parsed = MiniJson.parse(toolsJson);
        } catch (RuntimeException e) {
            throw new ChatException("Graph tool catalog is not valid JSON", e);
        }
        if (!(parsed instanceof List<?> list)) {
            throw new ChatException("Graph tool catalog must be a JSON array");
        }
        return (List<Object>) list;
    }
}
