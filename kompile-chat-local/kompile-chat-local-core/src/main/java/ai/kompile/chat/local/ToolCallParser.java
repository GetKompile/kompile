package ai.kompile.chat.local;

import java.util.Map;
import java.util.Optional;

/**
 * Compatibility adapter for callers that previously consumed a local parser.
 *
 * <p>This class no longer parses model text. It accepts only the canonical
 * structured SDX result JSON after SameDiff has applied the imported model's
 * protocol.</p>
 */
@Deprecated
public final class ToolCallParser {

    private ToolCallParser() {
    }

    /** Compatibility projection of one structured tool call. */
    public record ToolCall(String tool, Map<String, Object> args) {
    }

    /**
     * Read the first call from a canonical structured chat result.
     *
     * @throws ChatException when the input is not a valid structured result or
     *                       SameDiff reported a protocol error
     */
    public static Optional<ToolCall> parse(String structuredResultJson) {
        ChatResponse result = ChatResponse.fromStructuredJson(structuredResultJson);
        if (!result.isProtocolValid()) {
            throw new ChatException("Model protocol failure: "
                    + String.join("; ", result.protocolErrors()));
        }
        if (result.toolCalls().isEmpty()) {
            return Optional.empty();
        }
        ChatToolCall call = result.toolCalls().get(0);
        return Optional.of(new ToolCall(call.name(), call.arguments()));
    }
}
