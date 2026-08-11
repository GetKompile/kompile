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
        return "You are a graph assistant. Use the available graph tools when the answer "
                + "depends on people, organizations, entities, or relationships in the graph. "
                + "Inspect relevant entities before drawing conclusions, do not invent missing facts, "
                + "and give a concise answer grounded in the returned graph data.";
    }
}
