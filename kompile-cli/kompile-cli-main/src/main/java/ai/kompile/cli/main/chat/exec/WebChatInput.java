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
 * {@code insightsQuery=true} reads the session's insights panel, the one the terminal's dashboard
 * area shows: the session's judge flags, tool calls and latency, latest test result and crawls.
 * The web chat's insights drawer asks with it between runs; it never spawns a model turn.
 * With {@code insightsTopic} it answers that topic's report instead, the one the {@code insights}
 * tool gives, over every session rather than one, so it needs no session id: the chat app's
 * insights page asks this way for the topics only the CLI can read.
 *
 * @param configQuery when true, {@code rawInput} may be blank and the resolution returns the
 *                    aggregated config snapshot instead of resolving rawInput
 * @param modelVendor optional user-facing vendor key scoping the snapshot's model section
 * @param workflowApprove the gate to approve, empty for the next one; {@code null} when the input
 *                        is not an approval
 * @param insightsQuery when true, the resolution returns the session's insights panel; the input
 *                      then carries no rawInput, configQuery or workflowApprove
 * @param insightsTopic the topic to report on instead of the panel, such as {@code crawl};
 *                      {@code null} for the panel. Only with insightsQuery.
 * @param insightsQuestion the question in plain words, such as {@code failed crawls last 7 days};
 *                         {@code null} when there is none. Only with an insightsTopic.
 */
public record WebChatInput(int version, String rawInput, String supplementalContext,
                           String sessionId, boolean configQuery, String modelVendor,
                           String workflowApprove, boolean insightsQuery, String insightsTopic,
                           String insightsQuestion) {
    public static final int VERSION = 1;
    public static final String FORMAT = "web-json";
    /** Gate names are short identifiers; the limit only bounds what a request can carry. */
    public static final int MAX_GATE_LENGTH = 256;
    /** Topic names are single words; the limit only bounds what a request can carry. */
    public static final int MAX_INSIGHTS_TOPIC_LENGTH = 64;
    public static final int MAX_INSIGHTS_QUESTION_LENGTH = 1_000;

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

    /** Compatibility constructor: turn, command, config query or gate approval; no insights read. */
    public WebChatInput(int version, String rawInput, String supplementalContext,
                        String sessionId, boolean configQuery, String modelVendor,
                        String workflowApprove) {
        this(version, rawInput, supplementalContext, sessionId, configQuery, modelVendor, workflowApprove, false);
    }

    /** Compatibility constructor: any input but an insights topic report. */
    public WebChatInput(int version, String rawInput, String supplementalContext,
                        String sessionId, boolean configQuery, String modelVendor,
                        String workflowApprove, boolean insightsQuery) {
        this(version, rawInput, supplementalContext, sessionId, configQuery, modelVendor, workflowApprove,
                insightsQuery, null, null);
    }

    public WebChatInput {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported web input version: " + version);
        if (insightsQuery) {
            if (configQuery || workflowApprove != null || (rawInput != null && !rawInput.isBlank())) {
                throw new IllegalArgumentException(
                        "insightsQuery cannot be combined with rawInput, configQuery or workflowApprove");
            }
            insightsTopic = insightsField(insightsTopic, MAX_INSIGHTS_TOPIC_LENGTH, "insightsTopic");
            insightsQuestion = insightsQuestion == null || insightsQuestion.isBlank() ? null
                    : insightsField(insightsQuestion, MAX_INSIGHTS_QUESTION_LENGTH, "insightsQuestion");
            if (insightsQuestion != null && insightsTopic == null) {
                throw new IllegalArgumentException("insightsQuestion needs an insightsTopic");
            }
        } else if (insightsTopic != null || insightsQuestion != null) {
            throw new IllegalArgumentException("insightsTopic and insightsQuestion need insightsQuery");
        } else if (workflowApprove != null) {
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

    /** The stripped value, or null when absent; blank, overlong or control characters are refused. */
    private static String insightsField(String value, int maxLength, String name) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength
                || stripped.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return stripped;
    }

    public static WebChatInput parse(String json) {
        try {
            JsonNode root = JsonUtils.standardMapper().readTree(json);
            if (root == null || !root.isObject() || !root.path("version").isIntegralNumber()
                    || !root.path("version").canConvertToInt()
                    || (root.has("rawInput") && !root.path("rawInput").isTextual())
                    || (root.has("supplementalContext") && !root.path("supplementalContext").isTextual())
                    || (root.has("sessionId") && !root.path("sessionId").isTextual())
                    || (root.has("configQuery") && !root.path("configQuery").isBoolean())
                    || (root.has("modelVendor") && !root.path("modelVendor").isTextual())
                    || (root.has("workflowApprove") && !root.path("workflowApprove").isTextual())
                    || (root.has("insightsQuery") && !root.path("insightsQuery").isBoolean())
                    || (root.has("insightsTopic") && !root.path("insightsTopic").isTextual())
                    || (root.has("insightsQuestion") && !root.path("insightsQuestion").isTextual())) {
                throw new IllegalArgumentException("Expected web-json object: version, rawInput, "
                        + "supplementalContext (optional string), sessionId (optional string), "
                        + "configQuery (optional boolean), modelVendor (optional string), "
                        + "workflowApprove (optional string), insightsQuery (optional boolean), "
                        + "insightsTopic (optional string), insightsQuestion (optional string)");
            }
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!java.util.Set.of("version", "rawInput", "supplementalContext", "sessionId",
                        "configQuery", "modelVendor", "workflowApprove", "insightsQuery",
                        "insightsTopic", "insightsQuestion").contains(field)) {
                    throw new IllegalArgumentException("Unknown web input field: " + field);
                }
            }
            return new WebChatInput(root.get("version").intValue(),
                    root.path("rawInput").asText(""),
                    root.path("supplementalContext").asText(""),
                    root.path("sessionId").asText(""),
                    root.path("configQuery").asBoolean(false),
                    root.path("modelVendor").asText(""),
                    root.has("workflowApprove") ? root.get("workflowApprove").textValue() : null,
                    root.path("insightsQuery").asBoolean(false),
                    root.has("insightsTopic") ? root.get("insightsTopic").textValue() : null,
                    root.has("insightsQuestion") ? root.get("insightsQuestion").textValue() : null);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid web-json input", e);
        }
    }
}
