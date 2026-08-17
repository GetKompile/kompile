package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * Process a single user turn.
     *
     * @param history   prior conversation turns (mutated by caller; engine appends nothing here)
     * @param userInput the user's latest message
     * @param opts      generation options
     * @return the final answer and any tool rounds that preceded it
     * @throws ChatException if no inference backend is available or generation fails fatally
     */
    public TurnResult chat(List<Message> history, String userInput, GenOptions opts)
            throws ChatException {

        // Build the working message list
        List<Message> working = new ArrayList<>(history);

        // Prepend system prompt if missing
        if (working.isEmpty() || !"system".equals(working.get(0).role())) {
            working.add(0, Message.system(systemPrompt));
        }

        working.add(Message.user(userInput));

        List<ToolRound> rounds = new ArrayList<>();
        List<ProtocolExchange> exchanges = new ArrayList<>();
        boolean toolsEnabled = toolRouting == ToolRouting.ALWAYS
                || hasGraphToolIntent(history, userInput);
        if (!toolsEnabled) {
            ChatResponse response = generateWithProtocolRetry(
                    working, "[]", ChatRequest.ToolChoice.NONE, opts, exchanges);
            if (!response.toolCalls().isEmpty()) {
                throw new ChatException("Model returned tool calls when tool use was disabled");
            }
            return new TurnResult(
                    requireAnswer(response.content()), List.of(), List.copyOf(exchanges));
        }

        String toolsJson = bridge.catalogJson();
        for (int round = 0; round < maxToolRounds; round++) {
            ChatResponse response = generateWithProtocolRetry(
                    working, toolsJson, ChatRequest.ToolChoice.AUTO, opts, exchanges);
            log.debug("Structured model output (round {}): content={}, calls={}, errors={}",
                    round, response.content(), response.toolCalls().size(),
                    response.protocolErrors());

            if (response.toolCalls().isEmpty()) {
                return new TurnResult(
                        requireAnswer(response.content()),
                        List.copyOf(rounds),
                        List.copyOf(exchanges));
            }

            working.add(Message.assistant(response));
            for (ChatToolCall call : response.toolCalls()) {
                String argsJson = MiniJson.write(call.arguments());
                String toolResult = bridge.execute(call.name(), argsJson);
                rounds.add(new ToolRound(call.name(), argsJson, toolResult));
                log.debug("Tool '{}' returned: {}", call.name(), toolResult);
                working.add(Message.toolResult(
                        call.id(), call.name(), toolResult));
            }
        }

        // Max rounds hit — ask the model to synthesise without more tool calls
        working.add(Message.user(
                "Please synthesize an answer from the tool results above without calling more tools."));
        ChatResponse synthesised = generateWithProtocolRetry(
                working, toolsJson, ChatRequest.ToolChoice.NONE, opts, exchanges);
        if (!synthesised.toolCalls().isEmpty()) {
            throw new ChatException("Model returned tool calls when tool use was disabled");
        }
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
            List<ProtocolExchange> exchanges) {
        ChatRequest firstRequest = new ChatRequest(messages, toolsJson, toolChoice);
        ChatResponse first = router.generate(firstRequest, opts);
        exchanges.add(new ProtocolExchange(
                firstRequest.toJson(), first.rawText(), first.protocolErrors()));
        if (first.isProtocolValid()) {
            return first;
        }

        log.debug("Model protocol failure; requesting one retry: {}",
                first.protocolErrors());
        List<Message> retryMessages = new ArrayList<>(messages);
        retryMessages.add(Message.assistant(first.rawText()));
        retryMessages.add(Message.user(
                "The previous assistant response failed the model's tool-call protocol validation: "
                        + String.join("; ", first.protocolErrors())
                        + ". Retry the same turn using the model's declared response protocol."));
        ChatRequest retryRequest = new ChatRequest(retryMessages, toolsJson, toolChoice);
        ChatResponse retry = router.generate(retryRequest, opts);
        exchanges.add(new ProtocolExchange(
                retryRequest.toJson(), retry.rawText(), retry.protocolErrors()));
        if (!retry.isProtocolValid()) {
            throw new ChatException("Model protocol failure after retry: "
                    + String.join("; ", retry.protocolErrors()));
        }
        return retry;
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
