package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry;
import ai.kompile.cli.main.chat.render.CompactionService.EntryType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Size-only local diagnostics, deliberately independent of provider usage and wire projection. */
public final class LocalContextInventory {
    static final int MAX_ENTRIES = 10_000;
    private static final int MAX_SCHEMA_NODES = 20_000;
    private static final int MAX_SCHEMA_DEPTH = 32;

    private LocalContextInventory() { }

    /** Only string values and field names, not serialized JSON or provider tokenization. */
    public record SchemaSize(int definitions, long textChars, boolean partial) { }

    public static SchemaSize schemaSize(JsonNode definitions) {
        if (definitions == null) return null;
        SchemaCounter counter = new SchemaCounter();
        counter.visit(definitions, 0);
        return new SchemaSize(definitions.size(), counter.chars, counter.partial);
    }

    /** Fixed-size output; never emits prompt, tool, memory, path, or attachment contents. */
    public static Map<String, String> describe(List<ConversationEntry> entries,
                                              Long systemChars, SchemaSize schemas,
                                              boolean claudeNative) {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("Context inventory", "Local portable text; estimated ceil(chars/4), not request/billed tokens");
        report.put("Prefix (last assembled)", systemChars == null ? "unobserved (not zero)"
                : size(systemChars) + "; agent/project/workflow combined; may be stale");
        report.put("Schemas (last assembled)", schemas == null ? "unobserved (not zero)"
                : schemas.definitions() + " definitions; " + schemas.textChars()
                    + " text chars (fields/strings, not wire JSON)" + (schemas.partial() ? "; partial lower bound" : ""));

        EnumMap<EntryType, Long> chars = new EnumMap<>(EntryType.class);
        long totalChars = 0;
        long estimatedTokens = 0;
        long attachments = 0;
        int available = entries == null ? 0 : entries.size();
        int inspected = Math.min(available, MAX_ENTRIES);
        for (int i = 0; i < inspected; i++) {
            ConversationEntry entry = entries.get(i);
            if (entry == null) continue;
            long length = entry.content == null ? 0 : entry.content.length();
            chars.merge(entry.type, length, Long::sum);
            totalChars += length;
            // Match CompactionService's per-entry text estimate, without assigning media tokens.
            estimatedTokens += (length + 3) / 4;
            attachments += entry.attachments.size();
        }
        report.put("History (current active)", inspected + "/" + available + " entries; "
                + totalChars + " chars; estimated " + estimatedTokens + " text tokens"
                + (available > inspected ? "; partial lower bound" : ""));
        report.put("History text (chars)", "user=" + chars.getOrDefault(EntryType.USER, 0L)
                + "; assistant=" + chars.getOrDefault(EntryType.ASSISTANT, 0L)
                + "; system/summary=" + chars.getOrDefault(EntryType.SYSTEM, 0L));
        report.put("Tool history (chars)", "call arguments=" + chars.getOrDefault(EntryType.TOOL_CALL, 0L)
                + "; results=" + chars.getOrDefault(EntryType.TOOL_RESULT, 0L));
        report.put("Inventory coverage", "Memory not separately identifiable; attachments=" + attachments
                + " in inspected entries (payload excluded); protocol/media excluded");
        report.put("Unattributed", claudeNative
                ? "Claude Code session/tools opaque, provider-owned (not zero); inventory is not native input"
                : "Provider/wire additions unknown; inventory is not a request projection");
        report.put("Accounting", "Independent snapshots, not additive with Context used/provider usage; no combined total");
        return report;
    }

    private static String size(long chars) {
        return chars + " chars; estimated " + ((chars + 3) / 4) + " text tokens";
    }

    private static final class SchemaCounter {
        private int remaining = MAX_SCHEMA_NODES;
        private long chars;
        private boolean partial;

        private void visit(JsonNode node, int depth) {
            if (remaining-- <= 0 || depth > MAX_SCHEMA_DEPTH) {
                partial = true;
                return;
            }
            if (node.isTextual()) {
                chars += node.textValue().length();
            } else if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    if (remaining <= 0) { partial = true; break; }
                    var field = fields.next();
                    chars += field.getKey().length();
                    visit(field.getValue(), depth + 1);
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    if (remaining <= 0) { partial = true; break; }
                    visit(child, depth + 1);
                }
            }
        }
    }
}
