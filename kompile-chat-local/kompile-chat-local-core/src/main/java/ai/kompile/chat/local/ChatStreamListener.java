package ai.kompile.chat.local;

import java.util.List;

/**
 * Receives provider-neutral progress from one chat turn.
 *
 * <p>Implementations may update a UI, a transcript, or telemetry. Callbacks are
 * best-effort progress notifications; the completed {@link ChatEngine.TurnResult}
 * remains the authoritative result.</p>
 */
public interface ChatStreamListener {
    ChatStreamListener NO_OP = new ChatStreamListener() {};

    default void onStatus(String status) {}

    default void onText(String text) {}

    default void onToolCall(String tool, String argsJson) {}

    default void onToolResult(String tool, String argsJson, String resultJson) {}

    default void onProtocolExchange(
            String requestJson,
            String rawResponse,
            List<String> protocolErrors) {}

    default void onResponse(ChatResponse response) {}
}
