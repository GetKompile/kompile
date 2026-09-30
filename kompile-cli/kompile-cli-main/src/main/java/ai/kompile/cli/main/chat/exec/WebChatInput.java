package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Opt-in wire protocol. Never infer this format from the contents of a plain exec prompt.
 *
 * {@code configQuery=true} marks a headless session-configuration read: the CLI answers
 * with a single {@code command} outcome carrying every config menu at once (model / role /
 * fast / reminders / loops / queue) and never spawns a model turn. The browser uses this to
 * populate the Session Configuration dialog quietly — no chat messages are sent. When
 * {@code modelVendor} is set, the snapshot's model section lists that vendor's models
 * instead of the currently configured provider's (quiet vendor browsing).
 *
 * {@code workflowApprove} is the user's gate approval between runs, as {@code /workflow approve}
 * is in the terminal: it approves the named gate of the team the session recorded, or with an
 * empty name the gate that blocks next, and never spawns a model turn.
 *
 * @param configQuery when true, {@code rawInput} may be blank and the resolution returns the
 *                    aggregated config snapshot instead of resolving rawInput
 * @param modelVendor optional user-facing vendor key scoping the snapshot's model section
 * @param workflowApprove the gate to approve, empty for the next one; {@code null} when the input
 *                        is not an approval
 */
public record WebChatInput(int version, String rawInput, String supplementalContext,
                           String sessionId, boolean configQuery, String modelVendor,
                           String workflowApprove) {
    public static final int VERSION = 1;
    public static final String FORMAT = "web-json";
    /** Gate names are short identifiers; the limit only bounds what a request can carry. */
    public static final int MAX_GATE_LENGTH = 256;

    /** Compatibility constructor: no explicit session id (caller context owns it). */
    public WebChatInput(int version, String rawInput, String supplementalContext) {
        this(version, rawInput, supplementalContext, null, false, null);
    }

    /** Compatibility constructor: explicit session id, plain turn/command input. */
    public WebChatInput(int version, String rawInput, String supplementalContext, String sessionId) {
        this(version, rawInput, supplementalContext, sessionId, false, null);
    }

    /** Compatibility constructor: config query without a vendor scope. */
    public WebChatInput(int version, String rawInput, String supplementalContext,
                        String sessionId, boolean configQuery) {
        this(version, rawInput, supplementalContext, sessionId, configQuery, null);
    }

    /** Compatibility constructor: turn, command, or config query input; no gate approval. */
    public WebChatInput(int version, String rawInput, String supplementalContext,
                        String sessionId, boolean configQuery, String modelVendor) {
        this(version, rawInput, supplementalContext, sessionId, configQuery, modelVendor, null);
    }

    public WebChatInput {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported web input version: " + version);
        if (workflowApprove != null) {
            if (configQuery || (rawInput != null && !rawInput.isBlank())) {
                throw new IllegalArgumentException("workflowApprove cannot be combined with rawInput or configQuery");
            }
            workflowApprove = workflowApprove.strip();
            if (workflowApprove.length() > MAX_GATE_LENGTH
                    || workflowApprove.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid workflowApprove gate name");
            }
        } else if (!configQuery && (rawInput == null || rawInput.isBlank())) {
            throw new IllegalArgumentException("rawInput must be a nonblank string");
        }
        rawInput = rawInput == null ? "" : rawInput;
        supplementalContext = supplementalContext == null ? "" : supplementalContext;
        sessionId = sessionId == null ? "" : sessionId.strip();
        modelVendor = modelVendor == null ? "" : modelVendor.strip();
    }

    public static WebChatInput parse(String json) {
        try {
            JsonNode root = JsonUtils.standardMapper().readTree(json);
            if (root == null || !root.isObject() || !root.path("version").isIntegralNumber()
                    || !root.path("version").canConvertToInt()
                    || (root.has("rawInput") && !root.path("rawInput").isTextual())
                    || (root.has("supplementalContext") && !root.path("supplementalContext").isTextual())
                    || (root.has("sessionId") && !root.path("sessionId").isTextual())
                    || (root.has("modelVendor") && !root.path("modelVendor").isTextual())
                    || (root.has("workflowApprove") && !root.path("workflowApprove").isTextual())) {
                throw new IllegalArgumentException("Expected web-json object: version, rawInput, "
                        + "supplementalContext (optional string), sessionId (optional string), "
                        + "configQuery (optional boolean), modelVendor (optional string), "
                        + "workflowApprove (optional string)");
            }
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!java.util.Set.of("version", "rawInput", "supplementalContext",
                        "sessionId", "configQuery", "modelVendor", "workflowApprove").contains(field)) {
                    throw new IllegalArgumentException("Unknown web input field: " + field);
                }
            }
            return new WebChatInput(root.get("version").intValue(),
                    root.path("rawInput").asText(""),
                    root.path("supplementalContext").asText(""),
                    root.path("sessionId").asText(""),
                    root.path("configQuery").asBoolean(false),
                    root.path("modelVendor").asText(""),
                    root.has("workflowApprove") ? root.get("workflowApprove").textValue() : null);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid web-json input", e);
        }
    }
}
