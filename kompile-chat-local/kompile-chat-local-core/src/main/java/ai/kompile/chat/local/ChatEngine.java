package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Drives a multi-turn conversation loop with graph tool-calling.
 *
 * <p>For each user turn the engine:</p>
 * <ol>
 *   <li>Builds the working message list (system prompt prepended if absent)</li>
 *   <li>Calls the active backend with structured messages and graph tool schemas</li>
 *   <li>Dispatches backend-decoded calls through {@link GraphToolBackend}</li>
 *   <li>Retries one protocol-invalid response, then fails the complete turn</li>
 *   <li>When a plain text answer is detected (or max rounds hit), returns a {@link TurnResult}</li>
 * </ol>
 */
public final class ChatEngine {

    private static final Logger log = LoggerFactory.getLogger(ChatEngine.class);

    /** Controls when the graph catalog is attached to a model request. */
    public enum ToolRouting {
        /** Preserve the desktop behavior: expose tools on every turn. */
        ALWAYS,
        /** Expose tools only for explicit graph intent or an active tool exchange. */
        RELEVANT
    }

    private static final List<String> GRAPH_INTENT_MARKERS = List.of(
            "graph", "knowledge base", "kgraph", "entity", "entities", "relationship",
            "relation", "node", "edge", "neighbor", "path", "timeline", "fact",
            "verify", "why not", "rank", "asset", "artifact", "connected",
            "organization", "people", "person");

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";

    private final InferenceRouter router;
    private final GraphToolBackend bridge;
    private final int maxToolRounds;
    private final ToolRouting toolRouting;
    private final String systemPrompt;

    /**
     * Record of a single tool dispatch within a conversation turn.
     *
     * @param tool       tool name that was called
     * @param argsJson   arguments passed to the tool (JSON string)
     * @param resultJson result returned by the tool (JSON string)
     */
    public record ToolRound(String tool, String argsJson, String resultJson) {}

    /**
     * Exact provider-neutral request and model-owned raw response for one inference attempt.
     * Retaining this at the turn boundary makes chat-template and MCP encoding failures debuggable
     * without asking a backend to expose native pointers or implementation-specific state.
     */
    public record ProtocolExchange(
            String requestJson,
            String rawResponse,
            List<String> protocolErrors) {
        public ProtocolExchange {
            requestJson = requestJson == null ? "" : requestJson;
            rawResponse = rawResponse == null ? "" : rawResponse;
            protocolErrors = protocolErrors == null ? List.of() : List.copyOf(protocolErrors);
        }
    }

    /**
     * Result of a single user turn.
     *
     * @param answer    the final assistant response text
     * @param rounds    any tool dispatches that occurred before the final answer
     * @param exchanges raw structured-protocol requests and responses for diagnostics
     */
    public record TurnResult(
            String answer,
            List<ToolRound> rounds,
            List<ProtocolExchange> exchanges) {}

    /**
     * Create a chat engine.
     *
     * @param router        inference backend (local SDX or remote)
     * @param bridge        graph tool dispatcher
     * @param maxToolRounds maximum tool calls per user turn before forcing synthesis
     */
    public ChatEngine(InferenceRouter router, GraphToolBackend bridge, int maxToolRounds) {
        this(router, bridge, maxToolRounds, ToolRouting.ALWAYS);
    }

    /**
     * Create a chat engine with an explicit tool-routing policy.
     *
     * <p>{@link ToolRouting#RELEVANT} is intended for memory-constrained local accelerators. It
     * keeps ordinary conversation out of the model's structured-tool template while preserving
     * graph tools for explicit graph questions and ongoing tool exchanges.</p>
     */
    public ChatEngine(
            InferenceRouter router,
            GraphToolBackend bridge,
            int maxToolRounds,
            ToolRouting toolRouting) {
        this.router = router;
        this.bridge = bridge;
        this.maxToolRounds = maxToolRounds;
        this.toolRouting = toolRouting == null ? ToolRouting.ALWAYS : toolRouting;
        this.systemPrompt = GraphChatPrompt.systemPrompt();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process a single user turn without exposing progress callbacks.
     */
    public TurnResult chat(List<Message> history, String userInput, GenOptions opts)
            throws ChatException {
        return chatStreaming(history, userInput, opts, ChatStreamListener.NO_OP);
    }

    /**
     * Process a turn while forwarding native text, tool, and protocol events.
     *
     * <p>The returned result is still authoritative; callbacks are deliberately
     * progress-only so a dropped UI event cannot change the conversation.</p>
     */
    public TurnResult chatStreaming(
            List<Message> history,
            String userInput,
            GenOptions opts,
            ChatStreamListener listener) throws ChatException {
        ChatStreamListener events = listener == null ? ChatStreamListener.NO_OP : listener;
        events.onStatus("starting");

        List<Message> working = new ArrayList<>(history);
        if (working.isEmpty() || !"system".equals(working.get(0).role())) {
            working.add(0, Message.system(systemPrompt));
        }
        working.add(Message.user(userInput));

        List<ToolRound> rounds = new ArrayList<>();
        List<ProtocolExchange> exchanges = new ArrayList<>();
        boolean toolsEnabled = toolRouting == ToolRouting.ALWAYS
                || hasGraphToolIntent(history, userInput);
        if (!toolsEnabled) {
            events.onStatus("tools_disabled");
            ChatResponse response = generateWithProtocolRetry(
                    working, "[]", ChatRequest.ToolChoice.NONE, opts, exchanges, events);
            if (!response.toolCalls().isEmpty()) {
                throw new ChatException("Model returned tool calls when tool use was disabled");
            }
            events.onStatus("complete");
            return new TurnResult(
                    requireAnswer(response.content()), List.of(), List.copyOf(exchanges));
        }

        events.onStatus("tools_available");
        String toolsJson = bridge.catalogJson();
        for (int round = 0; round < maxToolRounds; round++) {
            events.onStatus("planning_tool_use");
            ChatResponse response = generateWithProtocolRetry(
                    working, toolsJson, ChatRequest.ToolChoice.AUTO, opts, exchanges, events);
            log.debug("Structured model output (round {}): content={}, calls={}, errors={}",
                    round, response.content(), response.toolCalls().size(),
                    response.protocolErrors());

            if (response.toolCalls().isEmpty()) {
                events.onStatus("complete");
                return new TurnResult(
                        requireAnswer(response.content()),
                        List.copyOf(rounds),
                        List.copyOf(exchanges));
            }

            working.add(Message.assistant(response));
            for (ChatToolCall call : response.toolCalls()) {
                String argsJson = MiniJson.write(call.arguments());
                events.onToolCall(call.name(), argsJson);
                events.onStatus("running_tool");
                String toolResult = bridge.execute(call.name(), argsJson);
                events.onToolResult(call.name(), argsJson, toolResult);
                rounds.add(new ToolRound(call.name(), argsJson, toolResult));
                log.debug("Tool '{}' returned: {}", call.name(), toolResult);
                working.add(Message.toolResult(call.id(), call.name(), toolResult));
            }
        }

        working.add(Message.user(
                "Please synthesize an answer from the tool results above without calling more tools."));
        events.onStatus("synthesizing");
        ChatResponse synthesised = generateWithProtocolRetry(
                working, toolsJson, ChatRequest.ToolChoice.NONE, opts, exchanges, events);
        if (!synthesised.toolCalls().isEmpty()) {
            throw new ChatException("Model returned tool calls when tool use was disabled");
        }
        events.onStatus("complete");
        return new TurnResult(
                requireAnswer(synthesised.content()),
                List.copyOf(rounds),
                List.copyOf(exchanges));
    }

    private ChatResponse generateWithProtocolRetry(
            List<Message> messages,
            String toolsJson,
            ChatRequest.ToolChoice toolChoice,
            GenOptions opts,
            List<ProtocolExchange> exchanges,
            ChatStreamListener listener) {
        ChatRequest firstRequest = new ChatRequest(messages, toolsJson, toolChoice);
        ProgressTextConsumer firstProgress = progressTextConsumer(toolChoice, listener);
        ChatResponse first = router.generateStreaming(
                firstRequest, opts, firstProgress);
        firstProgress.complete();
        listener.onResponse(first);
        ProtocolExchange firstExchange = new ProtocolExchange(
                firstRequest.toJson(), first.rawText(), first.protocolErrors());
        exchanges.add(firstExchange);
        listener.onProtocolExchange(
                firstExchange.requestJson(), firstExchange.rawResponse(), firstExchange.protocolErrors());
        if (first.isProtocolValid()) {
            return first;
        }

        listener.onStatus("protocol_retry");
        log.debug("Model protocol failure; requesting one retry: {}",
                first.protocolErrors());
        List<Message> retryMessages = new ArrayList<>(messages);
        retryMessages.add(Message.assistant(first.rawText()));
        retryMessages.add(Message.user(
                "The previous assistant response failed the model's tool-call protocol validation: "
                        + String.join("; ", first.protocolErrors())
                        + ". Retry the same turn using the model's declared response protocol."));
        ChatRequest retryRequest = new ChatRequest(retryMessages, toolsJson, toolChoice);
        ProgressTextConsumer retryProgress = progressTextConsumer(toolChoice, listener);
        ChatResponse retry = router.generateStreaming(
                retryRequest, opts, retryProgress);
        retryProgress.complete();
        listener.onResponse(retry);
        ProtocolExchange retryExchange = new ProtocolExchange(
                retryRequest.toJson(), retry.rawText(), retry.protocolErrors());
        exchanges.add(retryExchange);
        listener.onProtocolExchange(
                retryExchange.requestJson(), retryExchange.rawResponse(), retryExchange.protocolErrors());
        if (!retry.isProtocolValid()) {
            throw new ChatException("Model protocol failure after retry: "
                    + String.join("; ", retry.protocolErrors()));
        }
        return retry;
    }

    /**
     * Preserve real token streaming without exposing a partial tool-call envelope.
     * Content-only requests can forward every chunk. Tool-enabled requests may
     * contain model-owned JSON/control syntax, so only thinking-block markup and
     * body text are forwarded while the final structured response remains authoritative.
     */
    private static ProgressTextConsumer progressTextConsumer(
            ChatRequest.ToolChoice toolChoice,
            ChatStreamListener listener) {
        if (toolChoice == ChatRequest.ToolChoice.NONE) {
            return listener::onText;
        }
        return new ThinkingBlockStreamFilter(listener::onText);
    }

    private interface ProgressTextConsumer extends Consumer<String> {
        default void complete() {}
    }

    /**
     * Incrementally forwards one leading {@code <think>...</think>} block across
     * arbitrary chunk boundaries. Once non-whitespace protocol text or the closing
     * marker is observed, all remaining bytes are suppressed.
     */
    private static final class ThinkingBlockStreamFilter implements ProgressTextConsumer {
        private final Consumer<String> downstream;
        private final StringBuilder pending = new StringBuilder();
        private boolean inThinking;
        private boolean done;

        private ThinkingBlockStreamFilter(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void accept(String chunk) {
            if (done || chunk == null || chunk.isEmpty()) {
                return;
            }
            pending.append(chunk);
            drain();
        }

        private void drain() {
            while (true) {
                String marker = inThinking ? THINK_CLOSE : THINK_OPEN;
                int markerIndex = pending.indexOf(marker);
                if (markerIndex >= 0) {
                    if (!inThinking) {
                        if (!pending.substring(0, markerIndex).isBlank()) {
                            done = true;
                            pending.setLength(0);
                            return;
                        }
                        pending.delete(0, markerIndex + marker.length());
                        downstream.accept(marker);
                        inThinking = true;
                        continue;
                    }

                    if (markerIndex > 0) {
                        downstream.accept(pending.substring(0, markerIndex));
                    }
                    pending.delete(0, markerIndex + marker.length());
                    downstream.accept(marker);
                    inThinking = false;
                    done = true;
                    pending.setLength(0);
                    return;
                }

                // Keep only enough trailing characters to recognize a marker
                // split across the next native/token IPC chunk.
                int keep = marker.length() - 1;
                if (pending.length() > keep) {
                    int consumed = pending.length() - keep;
                    if (inThinking) {
                        downstream.accept(pending.substring(0, consumed));
                    } else if (!pending.substring(0, consumed).isBlank()) {
                        done = true;
                        pending.setLength(0);
                        return;
                    }
                    pending.delete(0, consumed);
                }
                return;
            }
        }

        @Override
        public void complete() {
            if (!done && inThinking) {
                if (pending.length() > 0) {
                    downstream.accept(pending.toString());
                }
                downstream.accept(THINK_CLOSE);
            }
            pending.setLength(0);
            inThinking = false;
            done = true;
        }
    }

    private static boolean hasGraphToolIntent(List<Message> history, String userInput) {
        for (Message message : history) {
            if ("tool".equals(message.role()) || !message.toolCalls().isEmpty()) {
                return true;
            }
        }
        String normalized = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        for (String marker : GRAPH_INTENT_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new ChatException("The model returned no assistant text.");
        }
        return answer.trim();
    }

}
