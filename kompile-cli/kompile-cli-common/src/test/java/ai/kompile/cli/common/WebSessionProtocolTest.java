package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebSessionProtocolTest {
    @Test void readsBootstrapWithoutConsumingTheNextTurn() throws Exception {
        String bootstrap = "{\"version\":1,\"action\":\"session_open\",\"requestId\":\"open\",\"sessionId\":\"session-1\",\"policyDigest\":\"" + "a".repeat(64) + "\"}";
        var input = new ByteArrayInputStream((bootstrap + "\n" + submit("hello") + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("session_open", WebSessionProtocol.read(input).action());
        assertEquals("hello", WebSessionProtocol.read(input).text());
        assertNull(WebSessionProtocol.read(input));
    }

    @Test void strictFramesRejectConfigurationCommandsDuplicatesAndBounds() {
        for (String bad : new String[] {
                submit("/role coder"), submit("  /permissions allow"), submit("/model escape"),
                submit("/tools enable bash"), submit("/whatever"),
                submit("x").replace("\"version\":1", "\"version\":2"),
                submit("x").replace("\"version\":1", "\"version\":1.0"),
                submit("x").replace("\"text\":", "\"role\":\"coder\",\"text\":"),
                submit("x").replace("\"text\":", "\"text\":\"duplicate\",\"text\":"),
                submit("x").replace("\"text\":\"x\"", "\"text\":true"),
                submit(" "), submit("x".repeat(WebSessionProtocol.MAX_TEXT_CHARS + 1)),
                submit("x") + " {}", "[]", "null"}) {
            assertThrows(Exception.class, () -> WebSessionProtocol.parse(bad), bad.substring(0, Math.min(100, bad.length())));
        }
    }

    @Test void supportsOnlyNamedReadAndTargetedTurnCancel() throws Exception {
        var read = WebSessionProtocol.parse("{\"version\":1,\"action\":\"read_capability\",\"requestId\":\"r\",\"capability\":\"connection\"}");
        assertEquals("connection", read.capability());
        var cancel = WebSessionProtocol.parse("{\"version\":1,\"action\":\"turn_cancel\",\"requestId\":\"c\",\"turnId\":\"t\"}");
        assertEquals("t", cancel.turnId());
        assertThrows(Exception.class, () -> WebSessionProtocol.parse("{\"version\":1,\"action\":\"read_capability\",\"requestId\":\"r\",\"tool\":\"bash\"}"));
        assertThrows(Exception.class, () -> WebSessionProtocol.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28, 10})));
    }

    @Test void contentControlsAreStrictVersionOneAndCannotSmuggleAuthority() throws Exception {
        var mapper = ProductChatPolicy.strictMapper();
        assertEquals("content_approvals", WebSessionProtocol.parse(
                "{\"version\":1,\"action\":\"content_approvals\",\"requestId\":\"read\"}").action());
        for (boolean allow : new boolean[]{true, false}) {
            String text = mapper.writeValueAsString(mapper.createObjectNode().put("approvalId", "pending-1").put("allow", allow));
            String wire = mapper.writeValueAsString(mapper.createObjectNode().put("version", 1)
                    .put("action", "content_approval").put("requestId", "resolve").put("text", text));
            var approval = WebSessionProtocol.parseApproval(WebSessionProtocol.parse(wire).text());
            assertEquals("pending-1", approval.approvalId()); assertEquals(allow, approval.allow());
        }
        for (String text : new String[]{"{}", "null", "[]", "{\"approvalId\":\"x\",\"allow\":\"true\"}",
                "{\"approvalId\":\"../x\",\"allow\":true}", "{\"approvalId\":\"x\",\"allow\":1}",
                "{\"approvalId\":\"x\",\"allow\":true,\"approved\":true}",
                "{\"approvalId\":\"x\",\"allow\":true,\"allow\":false}",
                "{\"approvalId\":\"x\",\"allow\":true} {}", "x".repeat(513)})
            assertThrows(Exception.class, () -> WebSessionProtocol.parseApproval(text));
        assertThrows(Exception.class, () -> WebSessionProtocol.parse(
                "{\"version\":1,\"action\":\"content_approvals\",\"requestId\":\"r\",\"text\":\"{}\"}"));
        assertThrows(Exception.class, () -> WebSessionProtocol.approvalId("a".repeat(129)));
    }

    private static String submit(String text) {
        return "{\"version\":1,\"action\":\"turn_submit\",\"requestId\":\"turn-1\",\"text\":\"" + text + "\"}";
    }
}
