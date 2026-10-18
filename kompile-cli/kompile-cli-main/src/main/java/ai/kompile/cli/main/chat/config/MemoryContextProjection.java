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
    private static final String OPEN = "<memory_context>\n";
    private static final String CLOSE = "</memory_context>";
    private static final String PERSISTENT_HEADER = "[Persistent memory]\n";
    private static final String[] SECTION_HEADERS = {
            PERSISTENT_HEADER, "[Previous conversations]\n", "[Retrieved documents]\n",
            "[Relevant incidents — warn-only historical evidence]\n",
            "[Incident context unavailable; no regression conclusion]\n"};

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

    /** A native session may deduplicate only a snapshot it has acknowledged receiving. */
    static String persistentSnapshot(String text) {
        PersistentSection section = persistentSection(text);
        return section == null ? null : section.snapshot();
    }

    static String withoutPersistentSnapshot(String text, String acknowledgedSnapshot) {
        if (acknowledgedSnapshot == null) return text;
        return projectText(text, new HashSet<>(Set.of(acknowledgedSnapshot)));
    }

    private record PersistentSection(String undecorated, int close, int header, int start, int end) {
        String snapshot() { return undecorated.substring(start, end); }
    }

    private static PersistentSection persistentSection(String text) {
        if (text == null) return null;
        String undecorated = ReminderManager.stripReminderBlock(text);
        if (!undecorated.startsWith(OPEN)) return null;
        int close = undecorated.indexOf(CLOSE, OPEN.length());
        if (close < 0) return null;
        String context = undecorated.substring(0, close);
        if (context.indexOf("<memory_context>", OPEN.length()) >= 0) return null;

        // Only parse the leading generated envelope, beginning with a known section.
        // Never search the user's request (or an arbitrary preamble) for memory tags.
        int section = OPEN.length();
        int persistentHeader = -1;
        int persistentStart = -1;
        int persistentEnd = -1;
        while (section < close) {
            String header = sectionHeader(context, section);
            if (header == null) return null;
            // Generated memory precedes retrieval. A quoted heading inside retrieved
            // evidence must never authorize stripping that evidence as persistent memory.
            if (persistentHeader < 0 && ("[Previous conversations]\n".equals(header)
                    || "[Retrieved documents]\n".equals(header))) return null;
            int body = section + header.length();
            int end = close;
            for (String candidate : SECTION_HEADERS) {
                int found = context.indexOf("\n" + candidate, body - 1);
                if (found >= 0 && found + 1 < end) end = found + 1;
            }
            if (PERSISTENT_HEADER.equals(header)) {
                // Multiple persistent sections are ambiguous, not evidence to deduplicate.
                if (persistentHeader >= 0) return null;
                persistentHeader = section;
                persistentStart = body;
                persistentEnd = end;
            }
            section = end;
        }
        if (persistentHeader < 0 || context.substring(persistentStart, persistentEnd).isBlank()) return null;
        return new PersistentSection(undecorated, close, persistentHeader, persistentStart, persistentEnd);
    }

    private static String projectText(String text, Set<String> seen) {
        PersistentSection section = persistentSection(text);
        if (section == null || seen.add(section.snapshot())) return text;
        String undecorated = section.undecorated();
        int close = section.close();
        int persistentHeader = section.header();
        int persistentEnd = section.end();
        int offset = text.length() - undecorated.length();
        if (persistentHeader == OPEN.length() && persistentEnd == close) {
            String suffix = undecorated.substring(close + CLOSE.length());
            String request = suffix.stripLeading();
            // Do not expose a literal user envelope to parsing on a subsequent projection.
            if (ReminderManager.stripReminderBlock(request).startsWith(OPEN)) {
                return text.substring(0, offset) + OPEN + CLOSE + suffix;
            }
            return text.substring(0, offset) + request;
        }
        return text.substring(0, offset) + undecorated.substring(0, persistentHeader)
                + undecorated.substring(persistentEnd);
    }

    private static String sectionHeader(String text, int start) {
        for (String header : SECTION_HEADERS) {
            if (text.startsWith(header, start)) return header;
        }
        return null;
    }
}
