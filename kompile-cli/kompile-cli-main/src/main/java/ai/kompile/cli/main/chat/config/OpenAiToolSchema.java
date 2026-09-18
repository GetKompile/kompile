package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Transport projection of MCP tool schemas for OpenAI Chat Completions. */
final class OpenAiToolSchema {
    private static final List<String> ROOT_COMBINATORS = List.of("anyOf", "oneOf", "allOf");

    private OpenAiToolSchema() { }

    static ObjectNode parameters(JsonNode schema) {
        ObjectNode result = schema != null && schema.isObject()
                ? ((ObjectNode) schema).deepCopy() : JsonNodeFactory.instance.objectNode();
        result.put("type", "object");
        if (!result.path("properties").isObject()) {
            result.putObject("properties");
        }

        // Chat Completions rejects these at the root even with strict=false. Keep
        // the argument envelope flat: wrapping it would change tool execution.
        // Branch constraints remain instructions; tools retain their validation.
        // only the outgoing copy is relaxed, never the registry/MCP schema.
        ObjectNode constraints = JsonNodeFactory.instance.objectNode();
        ObjectNode properties = (ObjectNode) result.get("properties");
        for (String keyword : ROOT_COMBINATORS) {
            JsonNode branches = result.remove(keyword);
            if (branches == null) continue;
            constraints.set(keyword, branches);
            collectBranchProperties(branches, properties);
        }
        if (!constraints.isEmpty()) {
            String description = result.path("description").asText("");
            result.put("description", (description.isBlank() ? "" : description + "\n")
                    + "Arguments must satisfy these constraints: " + constraints);
        }
        return result;
    }

    private static void collectBranchProperties(JsonNode branches, ObjectNode properties) {
        if (!branches.isArray()) return;
        for (JsonNode branch : branches) {
            branch.path("properties").fields().forEachRemaining(entry -> {
                JsonNode existing = properties.get(entry.getKey());
                JsonNode candidate = entry.getValue();
                if (existing == null) {
                    properties.set(entry.getKey(), candidate.deepCopy());
                } else if (!existing.equals(candidate)) {
                    // Preserve every branch's possible field type. Nested anyOf
                    // is allowed; do not strip nested schemas or literal data.
                    ObjectNode alternatives = JsonNodeFactory.instance.objectNode();
                    alternatives.putArray("anyOf").add(existing).add(candidate.deepCopy());
                    properties.set(entry.getKey(), alternatives);
                }
            });
            for (String keyword : ROOT_COMBINATORS) {
                collectBranchProperties(branch.path(keyword), properties);
            }
        }
    }
}
