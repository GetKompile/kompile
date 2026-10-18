package ai.kompile.cli.common.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolPresentationV1Test {
    private final ObjectMapper json = new ObjectMapper();
    private static final String PAYLOAD = "{\"presentation\":{\"version\":1,\"kind\":\"vendor.board\",\"data\":{\"savedId\":7}}}";

    @Test void unknownKindsAreInertAndOnlyHostCanBindSourceAndIdentity() {
        var candidate = ToolPresentationV1.candidateFromPayload(PAYLOAD);
        var sourced = ToolPresentationV1.fromCandidate(candidate, "actual-server", "show_board");
        assertFalse(sourced.has("identity"));
        var bound = ToolPresentationV1.identify(sourced, "session", "request-1", "call-1");
        assertEquals("actual-server", bound.path("source").path("serverId").asText());
        assertEquals("show_board", bound.path("source").path("toolName").asText());
        assertEquals(bound, ToolPresentationV1.project(bound, "session", "request-1", "call-1"));
        assertNull(ToolPresentationV1.project(bound, "other-session", "request-1", "call-1"));
        assertNull(ToolPresentationV1.project(bound, "session", "other-turn", "call-1"));
        assertNull(ToolPresentationV1.project(bound, "session", "request-1", "other-call"));
        assertNull(ToolPresentationV1.identify(bound, "session", "request-1", "call-1"));
        assertNull(ToolPresentationV1.identify(sourced, "session", null, "call-1"));
        assertFalse(candidate.has("source"), "projection must not mutate the producer tree");
    }

    @Test void rejectsProsePreviewsTruncationTrailingTokensDuplicatesAndNonObjects() {
        for (String payload : new String[]{"Here is a map: " + PAYLOAD, "```json\n" + PAYLOAD + "\n```",
                PAYLOAD.substring(0, PAYLOAD.length() - 2), PAYLOAD + " {}", "[]", "null",
                "{\"presentation\":{\"version\":1,\"version\":1,\"kind\":\"vendor.board\",\"data\":{}}}"})
            assertNull(ToolPresentationV1.candidateFromPayload(payload), payload);
    }

    @Test void rejectsSpoofedEnvelopeFieldsVersionsKindsAndOversizeData() throws Exception {
        ObjectNode base = (ObjectNode) json.readTree(PAYLOAD).path("presentation");
        for (String field : new String[]{"source", "identity", "url", "html", "component", "renderer"}) {
            ObjectNode spoof = base.deepCopy().put(field, "untrusted");
            assertNull(ToolPresentationV1.fromCandidate(spoof, "server", "tool"), field);
        }
        assertNull(ToolPresentationV1.fromCandidate(base.deepCopy().put("version", 2), "server", "tool"));
        assertNull(ToolPresentationV1.fromCandidate(base.deepCopy().put("version", 1.0), "server", "tool"));
        assertNull(ToolPresentationV1.fromCandidate(base.deepCopy().put("kind", "<script>"), "server", "tool"));
        assertNull(ToolPresentationV1.fromCandidate(base, "https://attacker", "tool"));
        assertNull(ToolPresentationV1.fromCandidate(base, "server", "x".repeat(257)));
        ObjectNode oversized = base.deepCopy();
        ((ObjectNode) oversized.path("data")).put("text", "x".repeat(4096));
        assertNull(ToolPresentationV1.fromCandidate(oversized, "server", "tool"));
        ObjectNode deep = base.deepCopy(), cursor = (ObjectNode) deep.path("data");
        for (int index = 0; index < 10; index++) cursor = cursor.putObject("nested");
        assertNull(ToolPresentationV1.fromCandidate(deep, "server", "tool"));
        ObjectNode many = base.deepCopy();
        for (int index = 0; index < 100; index++) ((ObjectNode) many.path("data")).put("k" + index, index);
        assertNull(ToolPresentationV1.fromCandidate(many, "server", "tool"));
    }
}
