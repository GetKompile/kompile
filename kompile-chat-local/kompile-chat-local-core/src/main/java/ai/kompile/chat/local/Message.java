package ai.kompile.chat.local;

import java.util.List;

/**
 * An immutable provider-neutral chat message.
 *
 * <p>Roles follow the OpenAI convention: {@code "system"}, {@code "user"},
 * {@code "assistant"}, and {@code "tool"}.</p>
 */
public record Message(
        String role,
        String content,
        List<ChatToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Message role must not be blank");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public Message(String role, String content) {
        this(role, content, List.of(), null, null);
    }

    /**
     * Create a system-role message.
     *
     * @param content the system prompt text
     * @return a new system message
     */
    public static Message system(String content) {
        return new Message("system", content);
    }

    /**
     * Create a user-role message.
     *
     * @param content the user input text
     * @return a new user message
     */
    public static Message user(String content) {
        return new Message("user", content);
    }

    /**
     * Create an assistant-role message.
     *
     * @param content the assistant response text
     * @return a new assistant message
     */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /** Preserve the backend-decoded tool calls for model-owned history rendering. */
    public static Message assistant(ChatResponse response) {
        return new Message("assistant", response.rawText(), response.toolCalls(), null, null);
    }

    /**
     * Create a structured tool-result message.
     *
     * @param tool the tool name that produced this result
     * @param json the JSON result string returned by the tool
     * @return a new tool_result message
     */
    public static Message toolResult(String tool, String json) {
        return toolResult(null, tool, json);
    }

    public static Message toolResult(String toolCallId, String tool, String json) {
        return new Message("tool", json, List.of(), toolCallId, tool);
    }
}
