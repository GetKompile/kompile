package ai.kompile.cli.remote.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The host's line-oriented OpenAI chat-completions dialect, without provider policy.
 * Owns and closes the UTF-8 input. Callbacks are synchronous, in wire order, and their
 * exceptions propagate rather than being mistaken for malformed JSON.
 */
public final class OpenAiChatStreamDecoder {
    // Fail explicitly on indices outside [0, 1023], rather than allocating an
    // attacker-controlled sparse list or silently reporting a truncated batch as success.
    private static final int MAX_TOOL_CALLS = 1024;

    private OpenAiChatStreamDecoder() {
    }

    /** Per-attempt mutable output, owned by the decoding thread. No tools are executed here. */
    public static final class State {
        public String text = "";
        public final List<ToolCall> toolCalls = new ArrayList<>();
        public boolean cancelled;
        public boolean refusalDetected;
        public boolean truncatedDetected;
        public boolean terminalEventSeen;
        public boolean silentStreamDrop;
        public boolean protocolError;
    }

    public record ToolCall(String id, String name, JsonNode arguments) {
    }

    /** Raw transport events only; normalization, diagnostics, and rendering belong to the host. */
    public interface Listener {
        default void onText(String text) throws Exception { }
        default void onRefusal(String refusal) throws Exception { }
        default void onReasoning(String reasoning) throws Exception { }
        default void onToolInputDelta(String arguments) throws Exception { }
        default void onUsage(JsonNode usage) throws Exception { }
        default void onError(JsonNode error) throws Exception { }
        /** A nonblank wire finish reason, delivered before tool-call finalization. */
        default void onFinish(String reason) throws Exception { }
        /** Review point on every parsed chunk after terminal detection, including trailing usage/refusal chunks. */
        default void onTerminalChunk() throws Exception { }
    }

    public static void decode(InputStream input, ObjectMapper mapper, BooleanSupplier cancelled,
                              Listener listener, State state) throws Exception {
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        boolean terminal = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    state.cancelled = true;
                    break;
                }
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException failure) {
                    // Cancellation closes the body to unblock readLine; genuine I/O failures propagate.
                    if (!cancelled.getAsBoolean()) throw failure;
                    state.cancelled = true;
                    break;
                }
                if (cancelled.getAsBoolean()) {
                    state.cancelled = true;
                    break;
                }
                if (line == null) break;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    terminal = true;
                    state.terminalEventSeen = true;
                    break;
                }

                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (JsonProcessingException malformed) {
                    continue; // Legacy behavior: skip unparseable chunks, not listener failures.
                }
                if (chunk == null) continue;
                JsonNode error = chunk.get("error");
                if (error != null && !error.isNull()) {
                    state.protocolError = true;
                    terminal = true;
                    listener.onError(error);
                    // An error envelope is not a finish_reason or [DONE] event.
                    break;
                }

                JsonNode delta = chunk.path("choices").path(0).path("delta");
                String refusal = delta.path("refusal").asText("");
                if (!refusal.isEmpty()) {
                    state.refusalDetected = true;
                    listener.onRefusal(refusal);
                    state.text += refusal;
                }
                String content = delta.path("content").asText(null);
                if (content != null) {
                    listener.onText(content);
                    state.text += content;
                }
                String reasoning = delta.path("reasoning_content").asText(null);
                if (reasoning != null && !reasoning.isEmpty()) {
                    listener.onReasoning(reasoning);
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode toolDelta : toolCalls) {
                        JsonNode wireIndex = toolDelta.path("index");
                        int index = wireIndex.asInt(0);
                        if ((wireIndex.isIntegralNumber() && !wireIndex.canConvertToInt())
                                || index < 0 || index >= MAX_TOOL_CALLS) {
                            state.protocolError = true;
                            listener.onError(mapper.createObjectNode()
                                    .put("message", "Tool call index outside supported range 0-1023"));
                            state.toolCalls.clear();
                            return;
                        }
                        while (toolAccumulators.size() <= index) {
                            toolAccumulators.add(new ToolCallAccumulator());
                        }
                        ToolCallAccumulator acc = toolAccumulators.get(index);
                        String id = toolDelta.path("id").asText(null);
                        if (id != null) acc.id = id;
                        String name = toolDelta.path("function").path("name").asText(null);
                        if (name != null) acc.name = name;
                        String args = toolDelta.path("function").path("arguments").asText(null);
                        if (args != null) {
                            acc.arguments.append(args);
                            listener.onToolInputDelta(args);
                        }
                    }
                }

                JsonNode usage = chunk.path("usage");
                if (!usage.isObject() || usage.isEmpty()) {
                    usage = chunk.path("x_groq").path("usage");
                }
                if (usage.isObject() && !usage.isEmpty()) listener.onUsage(usage);

                String reason = chunk.path("choices").path(0).path("finish_reason").asText(null);
                if (reason != null && !reason.isBlank()) {
                    terminal = true;
                    state.terminalEventSeen = true;
                }
                state.refusalDetected |= "content_filter".equals(reason);
                state.truncatedDetected |= "length".equals(reason);
                if (reason != null && !reason.isBlank()) listener.onFinish(reason);
                if (terminal) listener.onTerminalChunk();
                // Leave the host a diagnostic point above, then drain usage even for stopped turns.
                if (terminal && (state.refusalDetected || state.truncatedDetected)) {
                    state.toolCalls.clear();
                    continue;
                }
                if ("tool_calls".equals(reason) || "stop".equals(reason)) {
                    finalizeTools(mapper, toolAccumulators, state);
                }
            }
        } catch (IOException failure) {
            // Closing an already cancellation-closed body may itself fail.
            if (!state.cancelled || !cancelled.getAsBoolean()) throw failure;
        }

        if (!state.cancelled && !state.protocolError && !terminal) {
            if (state.text.isEmpty() && state.toolCalls.isEmpty()) {
                throw new EOFException("OpenAI stream ended before a terminal event");
            }
            state.silentStreamDrop = true;
            return;
        }
        if (state.refusalDetected || state.truncatedDetected) {
            state.toolCalls.clear();
            return;
        }
        // Preserve the legacy fallback, including the host's responsibility to honor
        // cancelled/protocolError before acting on any returned tool envelopes.
        if (state.toolCalls.isEmpty() && !toolAccumulators.isEmpty()) {
            finalizeTools(mapper, toolAccumulators, state);
        }
    }

    private static void finalizeTools(ObjectMapper mapper, List<ToolCallAccumulator> accumulators, State state) {
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.name == null) continue;
            JsonNode arguments;
            try {
                arguments = mapper.readTree(acc.arguments.toString());
                if (arguments == null || arguments.isMissingNode()) arguments = mapper.createObjectNode();
            } catch (JsonProcessingException malformed) {
                arguments = mapper.createObjectNode(); // Explicit legacy malformed-argument fallback.
            }
            String id = acc.id != null ? acc.id : "call_" + state.toolCalls.size();
            state.toolCalls.add(new ToolCall(id, acc.name, arguments));
        }
    }

    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
