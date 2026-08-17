package ai.kompile.chat.local;

import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over a chat-capable language model — local (SDX) or remote (HTTP).
 *
 * <p>Implementations must be thread-safe for single-caller use (sequential calls from
 * {@link ChatEngine}). Multi-threaded concurrent use is not required.</p>
 */
public interface ChatModel {

    /**
     * Generate one structured response. Each backend is responsible for applying
     * the imported/provider model protocol before returning this result.
     */
    default ChatResponse generate(ChatRequest request, GenOptions opts) throws ChatException {
        if (request.toolChoice() != ChatRequest.ToolChoice.NONE
                || !request.parseTools().isEmpty()) {
            throw new ChatException(
                    "This backend does not implement structured tool-capable chat");
        }
        return ChatResponse.content(generate(request.messages(), opts));
    }

    /**
     * Compatibility path for content-only callers. It still uses structured chat;
     * it never invokes a Kompile-side output parser.
     */
    default String generate(List<Message> messages, GenOptions opts) throws ChatException {
        ChatResponse result = generate(
                new ChatRequest(messages, "[]", ChatRequest.ToolChoice.NONE), opts);
        if (!result.isProtocolValid()) {
            throw new ChatException("Model protocol failure: "
                    + String.join("; ", result.protocolErrors()));
        }
        if (!result.toolCalls().isEmpty()) {
            throw new ChatException("Content-only generation unexpectedly returned tool calls");
        }
        return result.content();
    }

    /**
     * Return {@code true} if this model is currently usable — the native library is
     * loaded (SDX) or the base URL is configured (remote).
     *
     * @return availability flag
     */
    boolean isAvailable();

    /**
     * Return a short identifier string for this model, e.g. {@code "sdx-local:phi3.gguf"}
     * or {@code "remote:gpt-4o-mini@https://api.openai.com"}.
     *
     * @return model identifier
     */
    String modelId();

    /**
     * Stream a structured response to a consumer.
     *
     * <p>The default keeps existing backends source-compatible: it performs one
     * structured generation and emits the final content once. Native providers
     * override this path to forward their actual token chunks.</p>
     */
    default ChatResponse generateStreaming(ChatRequest request, GenOptions opts,
                                              Consumer<String> tokenConsumer) throws ChatException {
        ChatResponse result = generate(request, opts);
        if (!result.content().isEmpty()) {
            tokenConsumer.accept(result.content());
        }
        return result;
    }

    /**
     * Stream generated tokens to a consumer.
     *
     * <p>The default implementation calls {@link #generate(List, GenOptions)} and passes
     * the entire result as a single token to {@code tokenConsumer}. Implementations that
     * support true streaming should override this.</p>
     */
    default void generateStreaming(List<Message> messages, GenOptions opts,
                                   Consumer<String> tokenConsumer) throws ChatException {
        String result = generate(messages, opts);
        tokenConsumer.accept(result);
    }
}
