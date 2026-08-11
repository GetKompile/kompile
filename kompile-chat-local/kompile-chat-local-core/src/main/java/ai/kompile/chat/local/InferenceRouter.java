package ai.kompile.chat.local;

import java.util.List;

/**
 * Routes inference requests to the best available {@link ChatModel}.
 *
 * <p>Priority order: local SDX model first, remote endpoint second. If neither
 * is available, {@link #generate} (and {@link #active()}) throw {@link ChatException}.</p>
 */
public final class InferenceRouter {

    private final ChatModel local;   // nullable
    private final ChatModel remote;  // nullable

    /**
     * Create a router with an optional local and optional remote model.
     * At least one should be non-null and available at runtime.
     *
     * @param local  local SDX model, or {@code null} to skip
     * @param remote remote HTTP model, or {@code null} to skip
     */
    public InferenceRouter(ChatModel local, ChatModel remote) {
        this.local = local;
        this.remote = remote;
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    /**
     * Return the active {@link ChatModel} according to the priority order.
     *
     * @return the model to use for generation
     * @throws ChatException if no backend is available
     */
    public ChatModel active() throws ChatException {
        if (local != null && local.isAvailable()) {
            return local;
        }
        if (remote != null && remote.isAvailable()) {
            return remote;
        }
        throw new ChatException(
                "No inference backend available (local unavailable, no remote configured)");
    }

    /**
     * Return a human-readable description of which route will be used.
     *
     * @return {@code "LOCAL_SDX"}, {@code "REMOTE"}, or {@code "NONE"}
     */
    public String activeRoute() {
        if (local != null && local.isAvailable()) {
            return "LOCAL_SDX";
        }
        if (remote != null && remote.isAvailable()) {
            return "REMOTE";
        }
        return "NONE";
    }

    /**
     * Generate a response using the active backend.
     *
     * @param messages conversation history
     * @param opts     generation options
     * @return model response text
     * @throws ChatException if no backend is available or generation fails
     */
    public String generate(List<Message> messages, GenOptions opts) throws ChatException {
        return active().generate(messages, opts);
    }

    /** Route one provider-neutral structured chat request. */
    public ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        return active().generate(request, opts);
    }

    /**
     * Return {@code true} if the local SDX model is the active backend.
     *
     * @return {@code true} when local model is available
     */
    public boolean isLocalActive() {
        return local != null && local.isAvailable();
    }

    /**
     * Return {@code true} if the remote model is the active backend
     * (i.e. local is not available but remote is).
     *
     * @return {@code true} when remote is the active backend
     */
    public boolean isRemoteActive() {
        return !isLocalActive() && remote != null && remote.isAvailable();
    }
}
