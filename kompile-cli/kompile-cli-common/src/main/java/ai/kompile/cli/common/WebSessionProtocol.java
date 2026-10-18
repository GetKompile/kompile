package ai.kompile.cli.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Trusted-local persistent worker JSONL v1. Every frame has version/action/requestId.
 * session_open adds sessionId/policyDigest; turn_submit adds text; turn_cancel adds
 * turnId; read_capability adds capability; content_approvals/session_close add nothing;
 * content_approval adds text containing strict {approvalId,allow}. No other fields
 * or duplicate JSON keys are accepted. A turnId is the accepted submit requestId.
 * Requests are at-most-once within a worker lifetime (including rejected requests).
 *
 * Output uses the existing ordered stream-json HeadlessRunEvent envelope. Session
 * events are session_ready/session_idle/session_closed/protocol_error, and turn events
 * turn_started/turn_completed. data is JSON with version, requestId, turnId, status,
 * content and policyDigest as applicable. capability_result has capability/content.
 * Bootstrap has no user/model turn. session_idle means genuine task quiescence, not
 * Future.cancel completion. All identifiers are opaque strings, never file paths.
 */
public final class WebSessionProtocol {
    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 131072;
    public static final int MAX_TEXT_CHARS = 65536;
    public static final int MAX_REQUESTS = 10000;
    private WebSessionProtocol() { }

    public record Frame(int version, String action, String requestId, String sessionId,
                        String policyDigest, String text, String turnId, String capability) { }

    public static Frame parse(String line) throws IOException {
        if (line == null || line.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
            throw invalid("frame size");
        JsonNode node = ProductChatPolicy.strictMapper().readTree(line);
        if (node == null || !node.isObject()) throw invalid("frame object");
        if (!node.path("version").isIntegralNumber() || !node.path("version").canConvertToInt()
                || node.path("version").intValue() != VERSION) throw invalid("version");
        String action = string(node, "action", 32);
        Set<String> expected = new LinkedHashSet<>(Set.of("version", "action", "requestId"));
        switch (action) {
            case "session_open" -> expected.addAll(Set.of("sessionId", "policyDigest"));
            case "turn_submit", "content_approval" -> expected.add("text");
            case "content_approvals" -> { }
            case "turn_cancel" -> expected.add("turnId");
            case "read_capability" -> expected.add("capability");
            case "session_close" -> { }
            default -> throw invalid("action");
        }
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid("fields");
        String request = identifier(string(node, "requestId", 128));
        String session = action.equals("session_open") ? identifier(string(node, "sessionId", 128)) : null;
        String digest = action.equals("session_open") ? string(node, "policyDigest", 64) : null;
        if (digest != null && !digest.matches("[a-f0-9]{64}")) throw invalid("policyDigest");
        String text = action.equals("turn_submit") ? string(node, "text", MAX_TEXT_CHARS)
                : action.equals("content_approval") ? string(node, "text", 512) : null;
        if (action.equals("content_approval")) parseApproval(text);
        if (action.equals("turn_submit") && text.stripLeading().startsWith("/")) throw invalid("slash commands are disabled");
        return new Frame(VERSION, action, request, session, digest, text,
                action.equals("turn_cancel") ? identifier(string(node, "turnId", 128)) : null,
                action.equals("read_capability") ? identifier(string(node, "capability", 128)) : null);
    }

    public record Approval(String approvalId, boolean allow) { }

    /** Authority is an opaque pending-intent id plus a real boolean, never tool arguments. */
    public static Approval parseApproval(String text) throws IOException {
        if (text == null || text.length() > 512) throw invalid("approval size");
        JsonNode value = ProductChatPolicy.strictMapper().readTree(text);
        if (value == null || !value.isObject() || value.size() != 2
                || !value.path("allow").isBoolean() || !value.path("approvalId").isTextual())
            throw invalid("approval fields");
        String id = approvalId(value.path("approvalId").textValue());
        return new Approval(id, value.path("allow").booleanValue());
    }

    public static String approvalId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw invalid("approvalId");
        return id;
    }

    /** Bounded, strict UTF-8 reader; does not buffer or steal subsequent frames. */
    public static Frame read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= MAX_FRAME_BYTES) throw invalid("frame size");
            bytes.write(value);
        }
        if (value == -1 && bytes.size() == 0) return null;
        try {
            String line = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return parse(line);
        } catch (CharacterCodingException e) { throw invalid("UTF-8"); }
    }

    private static String string(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > max || value.textValue().indexOf('\0') >= 0) throw invalid(field);
        return value.textValue();
    }
    private static String identifier(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw invalid("identifier");
        return value;
    }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid web session frame: " + field);
    }
}
