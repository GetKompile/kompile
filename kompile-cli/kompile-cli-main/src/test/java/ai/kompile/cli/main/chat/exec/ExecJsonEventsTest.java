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
}
