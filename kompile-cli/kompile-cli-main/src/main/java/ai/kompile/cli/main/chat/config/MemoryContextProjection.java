package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.ReminderManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/** Nonmutating HTTP projection of exact repeated persistent-memory sections.
 * Retrieval evidence and different memory snapshots remain at user authority.
 * The newest identical section is retained, including on tool continuations.
 */
final class MemoryContextProjection {
    private static final String PREFIX = "<memory_context>\n[Persistent memory]\n";
    private static final String CLOSE = "</memory_context>";
    private static final String[] RETRIEVAL_HEADERS = {
            "\n[Previous conversations]\n", "\n[Retrieved documents]\n"};

    private MemoryContextProjection() { }

    static ArrayNode project(ArrayNode messages) {
        ArrayNode projected = messages.deepCopy();
        Set<String> seen = new HashSet<>();
        for (int i = projected.size() - 1; i >= 0; i--) {
            JsonNode message = projected.get(i);
            if (!message.isObject() || !"user".equals(message.path("role").asText())) continue;
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                ((ObjectNode) message).put("content", projectText(content.asText(), seen));
            } else if (content.isArray()) {
                for (int j = content.size() - 1; j >= 0; j--) {
                    JsonNode part = content.get(j);
                    String type = part.path("type").asText();
                    if (part.isObject() && ("text".equals(type) || "input_text".equals(type))
                            && part.path("text").isTextual()) {
                        ((ObjectNode) part).put("text", projectText(part.path("text").asText(), seen));
                    }
                }
            }
        }
        return projected;
    }

    private static String projectText(String text, Set<String> seen) {
        String undecorated = ReminderManager.stripReminderBlock(text);
        if (!undecorated.startsWith(PREFIX)) return text;
        int close = undecorated.indexOf(CLOSE, PREFIX.length());
        if (close < 0) return text;
        int persistentStart = PREFIX.length();
        int persistentEnd = close;
        for (String header : RETRIEVAL_HEADERS) {
            int found = undecorated.indexOf(header, persistentStart);
            if (found >= 0 && found < persistentEnd) persistentEnd = found;
        }
        // Do not strip changed snapshots or infer provenance from arbitrary embedded tags.
        String persistent = undecorated.substring(persistentStart, persistentEnd);
        if (persistent.isBlank() || seen.add(persistent)) return text;
        int offset = text.length() - undecorated.length();
        if (persistentEnd == close) {
            return text.substring(0, offset)
                    + undecorated.substring(close + CLOSE.length()).stripLeading();
        }
        return text.substring(0, offset) + "<memory_context>"
                + undecorated.substring(persistentEnd);
    }
}
