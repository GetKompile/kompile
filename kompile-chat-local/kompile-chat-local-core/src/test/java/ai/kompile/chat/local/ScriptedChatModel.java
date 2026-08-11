package ai.kompile.chat.local;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Test helper: a {@link ChatModel} that returns pre-canned structured responses.
 */
public final class ScriptedChatModel implements ChatModel {

    private final Queue<ChatResponse> responses;
    private boolean available = true;

    private ScriptedChatModel(Queue<ChatResponse> responses) {
        this.responses = responses;
    }

    /** Create a scripted content-only model. */
    public static ScriptedChatModel of(String... responses) {
        Queue<ChatResponse> values = new ArrayDeque<>();
        for (String response : responses) {
            values.add(ChatResponse.content(response));
        }
        return new ScriptedChatModel(values);
    }

    /** Create a scripted model with explicit structured responses. */
    public static ScriptedChatModel ofResponses(ChatResponse... responses) {
        return new ScriptedChatModel(new ArrayDeque<>(List.of(responses)));
    }

    /** Force {@link #isAvailable()} to return {@code false}. */
    public ScriptedChatModel unavailable() {
        this.available = false;
        return this;
    }

    @Override
    public ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        ChatResponse next = responses.poll();
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
