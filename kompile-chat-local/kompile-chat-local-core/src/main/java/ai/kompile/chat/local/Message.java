package ai.kompile.chat.local;

/**
 * An immutable chat message with a role and text content.
 *
 * <p>Roles follow the OpenAI convention: {@code "system"}, {@code "user"},
 * {@code "assistant"}, {@code "tool_result"}.</p>
 */
public record Message(String role, String content) {

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

    /**
     * Create a tool-result message encoding the output of a tool call.
     *
     * <p>Content format: {@code "TOOL_RESULT <tool>: <json>"} — this prefix makes the
     * tool result unambiguous in conversation history passed to models that do not have a
     * native tool-result role.</p>
     *
     * @param tool the tool name that produced this result
     * @param json the JSON result string returned by the tool
     * @return a new tool_result message
     */
    public static Message toolResult(String tool, String json) {
        return new Message("tool_result", "TOOL_RESULT " + tool + ": " + json);
    }
}
