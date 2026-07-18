package ai.kompile.chat.local;

/**
 * Canonical compact prompt shared by desktop and mobile graph-chat clients.
 *
 * <p>The full tool catalog is intentionally not embedded because it can exceed
 * the context budget of the 0.3B-1B models targeted by mobile accelerators.</p>
 */
public final class GraphChatPrompt {

    private GraphChatPrompt() {
    }

    /**
     * Return the provider-neutral graph tool instructions.
     *
     * @return compact system prompt for local graph-assisted chat
     */
    public static String systemPrompt() {
        return "You are a graph assistant. Call tools to answer questions about people and organizations.\n\n"
                + "TOOL: graph_reasoning_query\n"
                + "To search: {\"tool\":\"graph_reasoning_query\",\"args\":{\"operation\":\"SEARCH\",\"queryText\":\"name\"}}\n"
                + "To describe: {\"tool\":\"graph_reasoning_query\",\"args\":{\"operation\":\"DESCRIBE\",\"entityId\":\"id\"}}\n\n"
                + "RULES: Output ONLY the JSON when calling a tool. After TOOL_RESULT, give a short answer.";
    }
}
