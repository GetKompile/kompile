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
     * Generate a response for the given conversation history.
     *
     * @param messages ordered conversation turns (system, user, assistant, tool_result)
     * @param opts     generation options (temperature, tokens, etc.)
     * @return the model's generated text, trimmed of leading/trailing whitespace
     * @throws ChatException if the model is unavailable or generation fails
     */
    String generate(List<Message> messages, GenOptions opts) throws ChatException;

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
     * Stream generated tokens to a consumer.
     *
     * <p>The default implementation calls {@link #generate(List, GenOptions)} and passes
     * the entire result as a single token to {@code tokenConsumer}. Implementations that
     * support true streaming should override this.</p>
     *
     * @param messages      conversation history
     * @param opts          generation options
     * @param tokenConsumer receives tokens as they are produced
     * @throws ChatException if generation fails
     */
    default void generateStreaming(List<Message> messages, GenOptions opts,
                                   Consumer<String> tokenConsumer) throws ChatException {
        String result = generate(messages, opts);
        tokenConsumer.accept(result);
    }
}
