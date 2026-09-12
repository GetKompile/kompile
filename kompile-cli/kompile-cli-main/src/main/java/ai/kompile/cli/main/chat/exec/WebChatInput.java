package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/** Opt-in wire protocol. Never infer this format from the contents of a plain exec prompt. */
public record WebChatInput(int version, String rawInput, String supplementalContext, String sessionId) {
    public static final int VERSION = 1;
    public static final String FORMAT = "web-json";

    /** Compatibility constructor: no explicit session id (caller context owns it). */
    public WebChatInput(int version, String rawInput, String supplementalContext) {
        this(version, rawInput, supplementalContext, null);
    }

    public WebChatInput {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported web input version: " + version);
        if (rawInput == null || rawInput.isBlank()) throw new IllegalArgumentException("rawInput must be a nonblank string");
        supplementalContext = supplementalContext == null ? "" : supplementalContext;
        sessionId = sessionId == null ? "" : sessionId.strip();
    }

    public static WebChatInput parse(String json) {
        try {
            JsonNode root = JsonUtils.standardMapper().readTree(json);
            if (root == null || !root.isObject() || !root.path("version").isIntegralNumber()
                    || !root.path("version").canConvertToInt() || !root.path("rawInput").isTextual()
                    || (root.has("supplementalContext") && !root.path("supplementalContext").isTextual())
                    || (root.has("sessionId") && !root.path("sessionId").isTextual())) {
                throw new IllegalArgumentException("Expected web-json object: version, rawInput, "
                        + "supplementalContext (optional string), sessionId (optional string)");
            }
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!java.util.Set.of("version", "rawInput", "supplementalContext", "sessionId").contains(field))
                    throw new IllegalArgumentException("Unknown web input field: " + field);
            }
            return new WebChatInput(root.get("version").intValue(), root.get("rawInput").textValue(),
                    root.path("supplementalContext").asText(""),
                    root.path("sessionId").asText(""));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid web-json input", e);
        }
    }
}
