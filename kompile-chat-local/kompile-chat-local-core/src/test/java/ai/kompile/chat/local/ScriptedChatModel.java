package ai.kompile.chat.local;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Test helper: a {@link ChatModel} that returns pre-canned responses in order.
 */
public final class ScriptedChatModel implements ChatModel {

    private final Queue<String> responses;
    private boolean available = true;

    private ScriptedChatModel(Queue<String> responses) {
        this.responses = responses;
    }

    /**
     * Create a scripted model that returns the given responses in order.
     *
     * @param responses responses to return on successive {@link #generate} calls
     * @return scripted model
     */
    public static ScriptedChatModel of(String... responses) {
        Queue<String> q = new ArrayDeque<>(List.of(responses));
        return new ScriptedChatModel(q);
    }

    /** Force {@link #isAvailable()} to return {@code false}. */
    public ScriptedChatModel unavailable() {
        this.available = false;
        return this;
    }

    @Override
    public String generate(List<Message> messages, GenOptions opts) throws ChatException {
        String next = responses.poll();
        if (next == null) {
            throw new ChatException("ScriptedChatModel: no more scripted responses");
        }
        return next;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String modelId() {
        return "scripted";
    }
}
