package ai.kompile.chat.local;

import java.util.Map;

/** A model-decoded function call returned by a structured chat backend. */
public record ChatToolCall(String id, String name, Map<String, Object> arguments) {

    public ChatToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool-call name must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
