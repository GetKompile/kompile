package ai.kompile.cli.main.chat.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecJsonEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void session_hasTypeAndFields() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.session(mapper, "exec-123", "claude-x", "/work"));
        assertEquals("session", n.get("type").asText());
        assertEquals("exec-123", n.get("session_id").asText());
        assertEquals("claude-x", n.get("model").asText());
        assertEquals("/work", n.get("cwd").asText());
    }

    @Test
    void session_omitsNullModelAndCwd() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.session(mapper, "s", null, null));
        assertEquals("s", n.get("session_id").asText());
        assertFalse(n.has("model"));
        assertFalse(n.has("cwd"));
    }

    @Test
    void text_escapesSpecialCharsAndStaysSingleLine() throws Exception {
        String chunk = "line1\nline2 \"quoted\" \tend";
        String line = ExecJsonEvents.text(mapper, chunk);
        assertEquals(-1, line.indexOf('\n'), "JSONL line must not contain a raw newline");
        JsonNode n = mapper.readTree(line);
        assertEquals("text", n.get("type").asText());
        assertEquals(chunk, n.get("text").asText());
    }

    @Test
    void tool_carriesNameOkAndMs() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.tool(mapper, "bash", true, 42));
        assertEquals("tool", n.get("type").asText());
        assertEquals("bash", n.get("name").asText());
        assertTrue(n.get("ok").asBoolean());
        assertEquals(42, n.get("ms").asLong());
    }

    @Test
    void tool_okFalseIsPreserved() throws Exception {
        // The !isError mapping happens at the call site (JsonEmittingMetrics);
        // the builder records the boolean it is given verbatim.
        JsonNode n = mapper.readTree(ExecJsonEvents.tool(mapper, "edit", false, 5));
        assertFalse(n.get("ok").asBoolean());
        assertEquals(5, n.get("ms").asLong());
    }

    @Test
    void result_carriesTextSessionToolsExit() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.result(mapper, "done", "exec-9", 3, 0));
        assertEquals("result", n.get("type").asText());
        assertEquals("done", n.get("text").asText());
        assertEquals("exec-9", n.get("session_id").asText());
        assertEquals(3, n.get("tools").asInt());
        assertEquals(0, n.get("exit").asInt());
    }

    @Test
    void error_carriesMessage() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.error(mapper, "boom"));
        assertEquals("error", n.get("type").asText());
        assertEquals("boom", n.get("message").asText());
    }

    @Test
    void nullsDegradeGracefully() throws Exception {
        assertEquals("", mapper.readTree(ExecJsonEvents.text(mapper, null)).get("text").asText());
        assertEquals("", mapper.readTree(ExecJsonEvents.result(mapper, null, "s", 0, 0)).get("text").asText());
        assertEquals("", mapper.readTree(ExecJsonEvents.error(mapper, null)).get("message").asText());
    }

    @Test
    void richEventsPreserveSequenceAndLifecycle() throws Exception {
        JsonNode started = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.started("s", "model", "/work").withSequence(7)));
        assertEquals(7, started.get("seq").asLong());
        assertEquals("session", started.get("type").asText());

        JsonNode delta = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.assistantDelta("s", "hello").withSequence(8)));
        assertEquals(8, delta.get("seq").asLong());
        assertEquals("text", delta.get("type").asText());
        assertEquals("hello", delta.get("text").asText());

        JsonNode toolStart = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.toolStarted("s", "call-1", "bash", "pwd").withSequence(9)));
        assertEquals(9, toolStart.get("seq").asLong());
        assertEquals("tool_start", toolStart.get("type").asText());
        assertEquals("call-1", toolStart.get("call_id").asText());

        JsonNode toolDone = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.toolCompleted("s", "call-1", "bash", "", true, 12).withSequence(10)));
        assertEquals(10, toolDone.get("seq").asLong());
        assertEquals("tool", toolDone.get("type").asText());
        assertEquals(12, toolDone.get("ms").asLong());

        JsonNode result = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.completed("s", "done", 0, 1).withSequence(11)));
        assertEquals(11, result.get("seq").asLong());
        assertEquals("result", result.get("type").asText());
        assertEquals("done", result.get("text").asText());
        assertEquals(1, result.get("tools").asInt());
    }
}
