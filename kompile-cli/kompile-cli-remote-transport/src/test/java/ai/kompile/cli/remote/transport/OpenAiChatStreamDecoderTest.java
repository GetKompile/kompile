package ai.kompile.cli.remote.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatStreamDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesDialectUtf8AndCallbackStateOrder() throws Exception {
        var state = new OpenAiChatStreamDecoder.State();
        List<String> events = new ArrayList<>();
        String wire = ": keepalive\nevent: message\ndata:{\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n"
                + "data: not-json\n\ndata: null\n"
                + delta(mapper.createObjectNode().put("refusal", "No ").put("content", "café")
                        .put("reasoning_content", "private"), null)
                + chunk("{\"usage\":{\"prompt_tokens\":2},\"choices\":[{\"finish_reason\":\"stop\"}]}")
                + chunk("{\"usage\":{\"completion_tokens\":3}}") + "data: [DONE]\n"
                + delta(mapper.createObjectNode().put("content", "not read"), null);
        TrackingInput input = new TrackingInput(wire);
        OpenAiChatStreamDecoder.decode(input, mapper, () -> false, new OpenAiChatStreamDecoder.Listener() {
            @Override public void onRefusal(String text) {
                assertTrue(state.refusalDetected);
                events.add("refusal:" + text + ":before=" + state.text);
            }
            @Override public void onText(String text) { events.add("text:" + text + ":before=" + state.text); }
            @Override public void onReasoning(String text) { events.add("reasoning:" + text); }
            @Override public void onUsage(JsonNode usage) { events.add("usage:" + usage); }
            @Override public void onFinish(String reason) {
                assertTrue(state.terminalEventSeen);
                assertTrue(state.toolCalls.isEmpty());
                events.add("finish:" + reason);
            }
        }, state);
        assertEquals(List.of("refusal:No :before=", "text:café:before=No ", "reasoning:private",
                "usage:{\"prompt_tokens\":2}", "finish:stop", "usage:{\"completion_tokens\":3}"), events);
        assertEquals("No café", state.text);
        assertTrue(state.terminalEventSeen);
        assertFalse(state.silentStreamDrop);
        assertTrue(input.closed);
    }

    @Test
    void assemblesInterleavedIndexedToolsAndFinalizesAfterFinishCallback() throws Exception {
        var state = new OpenAiChatStreamDecoder.State();
        List<String> deltas = new ArrayList<>();
        String wire = tool(1, "b", "second", "{\"y\":")
                + tool(0, "a", "first", "{\"x\":")
                + tool(1, null, null, "2}") + tool(0, null, null, "1}")
                + delta(mapper.createObjectNode(), "tool_calls")
                + chunk("{\"usage\":{\"total_tokens\":4}}") + "data: [DONE]\n";
        OpenAiChatStreamDecoder.decode(input(wire), mapper, () -> false, new OpenAiChatStreamDecoder.Listener() {
            @Override public void onToolInputDelta(String args) { deltas.add(args); }
            @Override public void onFinish(String reason) { assertTrue(state.toolCalls.isEmpty()); }
            @Override public void onUsage(JsonNode usage) { assertEquals(2, state.toolCalls.size()); }
        }, state);
        assertEquals(List.of("{\"y\":", "{\"x\":", "2}", "1}"), deltas);
        assertEquals(List.of(new OpenAiChatStreamDecoder.ToolCall("a", "first", mapper.readTree("{\"x\":1}")),
                new OpenAiChatStreamDecoder.ToolCall("b", "second", mapper.readTree("{\"y\":2}"))), state.toolCalls);
    }

    @Test
    void deliversEachUsageInlineWithTopLevelPrecedenceAndGroqFallback() throws Exception {
        List<String> events = new ArrayList<>();
        var state = new OpenAiChatStreamDecoder.State();
        String wire = chunk("{\"usage\":{\"total_tokens\":1},\"x_groq\":{\"usage\":{\"total_tokens\":99}}}")
                + chunk("{\"usage\":{},\"x_groq\":{\"usage\":{\"total_tokens\":2}}}")
                + delta(mapper.createObjectNode().put("content", "answer"), "stop")
                + chunk("{\"usage\":null,\"x_groq\":{\"usage\":{\"total_tokens\":3}}}")
                + chunk("{\"usage\":{}}") + "data: [DONE]\n";
        OpenAiChatStreamDecoder.decode(input(wire), mapper, () -> false, new OpenAiChatStreamDecoder.Listener() {
            @Override public void onUsage(JsonNode usage) { events.add("usage:" + usage.path("total_tokens").asInt()); }
            @Override public void onText(String text) { events.add("text"); }
            @Override public void onFinish(String reason) { events.add("finish"); }
        }, state);
        assertEquals(List.of("usage:1", "usage:2", "text", "finish", "usage:3"), events);
    }

    @Test
    void terminalReviewIncludesLateRefusalWithoutInventingFinishReasons() throws Exception {
        var state = new OpenAiChatStreamDecoder.State();
        List<String> reasons = new ArrayList<>();
        List<Boolean> reviewedRefusals = new ArrayList<>();
        String wire = delta(mapper.createObjectNode().put("content", "answer"), "stop")
                + delta(mapper.createObjectNode().put("refusal", "late"), null)
                + chunk("{\"usage\":{\"total_tokens\":3}}") + "data: [DONE]\n";
        OpenAiChatStreamDecoder.decode(input(wire), mapper, () -> false, new OpenAiChatStreamDecoder.Listener() {
            @Override public void onFinish(String reason) { reasons.add(reason); }
            @Override public void onTerminalChunk() { reviewedRefusals.add(state.refusalDetected); }
        }, state);
        assertEquals(List.of("stop"), reasons);
        assertEquals(List.of(false, true, true), reviewedRefusals);
    }

    @Test
    void rawErrorStopsWithoutInventingFinishOrTerminalEvent() throws Exception {
        JsonNode error = mapper.readTree("{\"message\":\"upstream\",\"metadata\":{\"raw\":\"detail\"}}");
        var state = new OpenAiChatStreamDecoder.State();
        List<JsonNode> errors = new ArrayList<>();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("error", error);
        envelope.putObject("usage").put("total_tokens", 99);
        OpenAiChatStreamDecoder.decode(input(delta(mapper.createObjectNode().put("content", "prefix"), null)
                + chunk(envelope.toString()) + "data: [DONE]\n"), mapper, () -> false,
                new OpenAiChatStreamDecoder.Listener() {
                    @Override public void onError(JsonNode raw) {
                        assertTrue(state.protocolError);
                        errors.add(raw);
                    }
                    @Override public void onUsage(JsonNode usage) { fail("Error envelope terminates before usage"); }
                    @Override public void onFinish(String reason) { fail("No finish event on error"); }
                }, state);
        assertEquals(List.of(error), errors);
        assertEquals("prefix", state.text);
        assertFalse(state.terminalEventSeen);
        assertFalse(state.silentStreamDrop);
    }

    @Test
    void refusalAndTruncationSuppressToolsButDrainUsage() throws Exception {
        for (String reason : List.of("content_filter", "length", "stop")) {
            var state = new OpenAiChatStreamDecoder.State();
            List<JsonNode> usages = new ArrayList<>();
            ObjectNode outcomeDelta = mapper.createObjectNode();
            if (reason.equals("stop")) outcomeDelta.put("refusal", "cannot comply");
            String wire = tool(0, "call", "lookup", "{}") + delta(outcomeDelta, reason)
                    + chunk("{\"usage\":{\"total_tokens\":5}}") + "data: [DONE]\n";
            OpenAiChatStreamDecoder.decode(input(wire), mapper, () -> false,
                    new OpenAiChatStreamDecoder.Listener() {
                        @Override public void onFinish(String finish) {
                            assertTrue(state.refusalDetected || state.truncatedDetected);
                        }
                        @Override public void onUsage(JsonNode usage) { usages.add(usage); }
                    }, state);
            assertTrue(state.toolCalls.isEmpty());
            assertEquals(1, usages.size());
            assertEquals(reason.equals("length"), state.truncatedDetected);
            assertEquals(!reason.equals("length"), state.refusalDetected);
            assertTrue(state.terminalEventSeen);
        }
    }

    @Test
    void doneAndUnrecognizedFinishReasonBothPermitLegacyFallback() throws Exception {
        for (String ending : List.of("data: [DONE]\n", delta(mapper.createObjectNode(), "custom_end"))) {
            var state = new OpenAiChatStreamDecoder.State();
            decode(tool(2, null, "lookup", "{broken") + ending, state);
            assertTrue(state.terminalEventSeen);
            assertEquals(List.of(new OpenAiChatStreamDecoder.ToolCall("call_0", "lookup", mapper.createObjectNode())),
                    state.toolCalls);
        }
        var emptyArgs = new OpenAiChatStreamDecoder.State();
        decode(tool(0, null, "empty", "") + "data: [DONE]\n", emptyArgs);
        assertEquals(mapper.createObjectNode(), emptyArgs.toolCalls.get(0).arguments());
        var doneOnly = new OpenAiChatStreamDecoder.State();
        decode("data: [DONE]\n", doneOnly);
        assertTrue(doneOnly.terminalEventSeen);
        assertEquals("", doneOnly.text);
    }

    @Test
    void emptyNonterminalEofThrowsButPartialTextMarksSilentDrop() throws Exception {
        for (String wire : List.of("", ": keepalive\n", "data: invalid\n", tool(0, "a", "lookup", "{}"))) {
            TrackingInput input = new TrackingInput(wire);
            assertThrows(EOFException.class, () -> OpenAiChatStreamDecoder.decode(input, mapper, () -> false,
                    new OpenAiChatStreamDecoder.Listener() { }, new OpenAiChatStreamDecoder.State()));
            assertTrue(input.closed);
        }
        var partial = new OpenAiChatStreamDecoder.State();
        decode(delta(mapper.createObjectNode().put("content", "half"), null)
                + tool(0, "a", "lookup", "{}"), partial);
        assertEquals("half", partial.text);
        assertTrue(partial.silentStreamDrop);
        assertFalse(partial.terminalEventSeen);
        assertTrue(partial.toolCalls.isEmpty()); // Do not finalize half-streamed tool envelopes at nonterminal EOF.
    }

    @Test
    void boundsMalformedToolIndicesWithoutChangingLastValidIndex() throws Exception {
        var state = new OpenAiChatStreamDecoder.State();
        String huge = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":4294967296,"
                + "\"function\":{\"name\":\"invalid\",\"arguments\":\"{}\"}}]}}]}\n";
        for (String invalid : List.of(tool(-1, null, "negative", "{}"), tool(1024, null, "oversize", "{}"), huge)) {
            var rejected = new OpenAiChatStreamDecoder.State();
            decode(invalid + "data: [DONE]\n", rejected);
            assertTrue(rejected.protocolError, "Out-of-range tool calls must fail explicitly, not disappear");
            assertTrue(rejected.toolCalls.isEmpty());
            assertFalse(rejected.terminalEventSeen);
        }
        decode(tool(1023, null, "last_valid", "{}") + "data: [DONE]\n", state);
        assertEquals(1, state.toolCalls.size());
        assertEquals("last_valid", state.toolCalls.get(0).name());
    }

    @Test
    void cancellationClosesInputAndOnlySuppressesCancellationReadFailures() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            @Override public int read() throws IOException {
                cancelled.set(true); // Equivalent to a concurrent body close unblocking the read.
                throw new IOException("closed");
            }
            @Override public void close() { closed.set(true); }
        };
        var state = new OpenAiChatStreamDecoder.State();
        OpenAiChatStreamDecoder.decode(body, mapper, cancelled::get, new OpenAiChatStreamDecoder.Listener() { }, state);
        assertTrue(state.cancelled);
        assertFalse(state.silentStreamDrop);
        assertTrue(closed.get());

        IOException genuine = new IOException("broken transport");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw genuine; }
        };
        assertSame(genuine, assertThrows(IOException.class, () -> OpenAiChatStreamDecoder.decode(broken, mapper,
                () -> false, new OpenAiChatStreamDecoder.Listener() { }, new OpenAiChatStreamDecoder.State())));
        TrackingInput preCancelled = new TrackingInput("data: [DONE]\n");
        var preCancelledState = new OpenAiChatStreamDecoder.State();
        OpenAiChatStreamDecoder.decode(preCancelled, mapper, () -> true,
                new OpenAiChatStreamDecoder.Listener() { }, preCancelledState);
        assertTrue(preCancelled.closed);
        assertTrue(preCancelledState.cancelled);
        assertFalse(preCancelledState.terminalEventSeen);
    }

    @Test
    void listenerExceptionsPropagateAndStillCloseOwnedStream() {
        Exception failure = new Exception("listener failed");
        TrackingInput input = new TrackingInput(delta(mapper.createObjectNode().put("content", "x"), null));
        assertSame(failure, assertThrows(Exception.class, () -> OpenAiChatStreamDecoder.decode(input, mapper,
                () -> false, new OpenAiChatStreamDecoder.Listener() {
                    @Override public void onText(String text) throws Exception { throw failure; }
                }, new OpenAiChatStreamDecoder.State())));
        assertTrue(input.closed);
    }

    private void decode(String wire, OpenAiChatStreamDecoder.State state) throws Exception {
        OpenAiChatStreamDecoder.decode(input(wire), mapper, () -> false, new OpenAiChatStreamDecoder.Listener() { }, state);
    }

    private ByteArrayInputStream input(String wire) {
        return new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8));
    }

    private String tool(int index, String id, String name, String args) {
        ObjectNode delta = mapper.createObjectNode();
        ObjectNode tool = delta.putArray("tool_calls").addObject().put("index", index);
        if (id != null) tool.put("id", id);
        ObjectNode function = tool.putObject("function");
        if (name != null) function.put("name", name);
        if (args != null) function.put("arguments", args);
        return delta(delta, null);
    }

    private String delta(ObjectNode delta, String finish) {
        ObjectNode chunk = mapper.createObjectNode();
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.set("delta", delta);
        if (finish != null) choice.put("finish_reason", finish);
        return chunk(chunk.toString());
    }

    private String chunk(String json) {
        return "data: " + json + "\n\n";
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInput(String wire) { super(wire.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
