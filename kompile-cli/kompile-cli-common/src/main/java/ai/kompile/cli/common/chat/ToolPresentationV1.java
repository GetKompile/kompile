package ai.kompile.cli.common.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bounded inert display data, never model context, renderer selection or execution authority. */
public final class ToolPresentationV1 {
    public static final int MAX_BYTES = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CANDIDATE = Set.of("version", "kind", "data");
    private static final Set<String> SOURCED = Set.of("version", "kind", "data", "source");
    private static final Set<String> IDENTIFIED = Set.of("version", "kind", "data", "source", "identity");

    private ToolPresentationV1() { }

    /** Only call on the complete, successful MCP text/structured payload, never previews/prose. */
    public static JsonNode candidateFromPayload(String payload) {
        JsonNode root = parse(payload, 16384);
        if (root == null || !root.isObject()) return null;
        JsonNode candidate = root.get("presentation");
        return validCandidate(candidate) ? candidate.deepCopy() : null;
    }

    public static ObjectNode fromCandidate(JsonNode candidate, String serverId, String toolName) {
        if (!validCandidate(candidate) || !validId(serverId) || !validId(toolName)) return null;
        ObjectNode result = candidate.deepCopy();
        result.putObject("source").put("serverId", serverId).put("toolName", toolName);
        return bounded(result) ? result : null;
    }

    /** Called at the actual completion callback; producer-supplied identity is never accepted. */
    public static ObjectNode identify(JsonNode sourced, String sessionId, String turnId, String callId) {
        ObjectNode result = validate(sourced, false);
        if (result == null || result.has("identity") || !validId(sessionId)
                || !validId(turnId) || !validId(callId)) return null;
        result.putObject("identity").put("sessionId", sessionId).put("turnId", turnId).put("callId", callId);
        return bounded(result) ? result : null;
    }

    /** Shared live/history projection. Identity is mandatory for managed display history. */
    public static ObjectNode project(JsonNode value, String sessionId, String turnId, String callId) {
        ObjectNode result = validate(value, true);
        if (result == null || !validId(sessionId) || !validId(turnId) || !validId(callId)) return null;
        JsonNode identity = result.path("identity");
        return sessionId.equals(identity.path("sessionId").asText())
                && turnId.equals(identity.path("turnId").asText())
                && callId.equals(identity.path("callId").asText()) ? result : null;
    }

    public static boolean validId(String value) {
        return value != null && value.length() <= 256 && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }

    private static ObjectNode validate(JsonNode value, boolean identityRequired) {
        if (!exact(value, identityRequired ? IDENTIFIED : SOURCED) || !bounded(value)) return null;
        ObjectNode candidate = ((ObjectNode) value).deepCopy();
        candidate.remove("source");
        candidate.remove("identity");
        if (!validCandidate(candidate)) return null;
        JsonNode source = value.path("source");
        if (!exact(source, Set.of("serverId", "toolName"))
                || !id(source.get("serverId")) || !id(source.get("toolName"))) return null;
        if (identityRequired) {
            JsonNode identity = value.path("identity");
            if (!exact(identity, Set.of("sessionId", "turnId", "callId"))
                    || !id(identity.get("sessionId")) || !id(identity.get("turnId"))
                    || !id(identity.get("callId"))) return null;
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static boolean validCandidate(JsonNode value) {
        return exact(value, CANDIDATE) && value.path("version").isIntegralNumber()
                && value.path("version").intValue() == 1 && value.path("version").canConvertToInt()
                && value.path("kind").isTextual() && value.path("kind").textValue().length() <= 96
                && value.path("kind").textValue().matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
                && value.path("data").isObject() && bounded(value);
    }

    private static boolean id(JsonNode node) { return node != null && node.isTextual() && validId(node.textValue()); }

    public static boolean exact(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    private static boolean bounded(JsonNode value) {
        return value != null && value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES
                && tree(value, 0, new int[]{0});
    }

    private static boolean tree(JsonNode node, int depth, int[] keys) {
        if (depth > 7 || node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (++keys[0] > 96 || field.getKey().length() > 128 || !tree(field.getValue(), depth + 1, keys)) return false;
            }
        } else if (node.isArray()) {
            if (node.size() > 64) return false;
            for (JsonNode child : node) if (!tree(child, depth + 1, keys)) return false;
        } else if (node.isTextual()) {
            return node.textValue().length() <= 2048;
        } else if (node.isNumber()) {
            return Double.isFinite(node.doubleValue()) && (!node.isIntegralNumber()
                    || node.canConvertToLong() && Math.abs(node.doubleValue()) <= 9007199254740991d);
        } else if (!node.isBoolean() && !node.isNull()) return false;
        return true;
    }

    /** Strict complete JSON, duplicate-free and depth-bounded before allocating a tree. */
    public static JsonNode parse(String text, int maxBytes) {
        if (text == null || text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) return null;
        try (JsonParser parser = JSON.createParser(text)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            int depth = 0, roots = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0 && token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY) roots++;
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    if (++depth > 12) return null;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
            }
            return roots == 1 && depth == 0 ? JSON.readTree(text) : null;
        } catch (Exception invalid) {
            return null;
        }
    }
}
