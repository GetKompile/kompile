package ai.kompile.chat.local;

/**
 * Unchecked exception thrown by chat model operations — generation errors,
 * missing backends, HTTP failures, and similar conditions.
 */
public class ChatException extends RuntimeException {

    /**
     * Create an exception with a message.
     *
     * @param message description of the error
     */
    public ChatException(String message) {
        super(message);
    }

    /**
     * Create an exception with a message and a cause.
     *
     * @param message description of the error
     * @param cause   the underlying exception
     */
    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
