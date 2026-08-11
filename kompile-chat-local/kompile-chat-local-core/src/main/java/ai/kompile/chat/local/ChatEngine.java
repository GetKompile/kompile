package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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

    private final InferenceRouter router;
    private final GraphToolBackend bridge;
    private final int maxToolRounds;
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
     * Result of a single user turn.
     *
     * @param answer the final assistant response text
     * @param rounds any tool dispatches that occurred before the final answer
     */
    public record TurnResult(String answer, List<ToolRound> rounds) {}

    /**
     * Create a chat engine.
     *
     * @param router        inference backend (local SDX or remote)
     * @param bridge        graph tool dispatcher
     * @param maxToolRounds maximum tool calls per user turn before forcing synthesis
     */
    public ChatEngine(InferenceRouter router, GraphToolBackend bridge, int maxToolRounds) {
        this.router = router;
        this.bridge = bridge;
        this.maxToolRounds = maxToolRounds;
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
        String toolsJson = bridge.catalogJson();

        for (int round = 0; round < maxToolRounds; round++) {
            ChatResponse response = generateWithProtocolRetry(
                    working, toolsJson, ChatRequest.ToolChoice.AUTO, opts);
            log.debug("Structured model output (round {}): content={}, calls={}, errors={}",
                    round, response.content(), response.toolCalls().size(),
                    response.protocolErrors());

            if (response.toolCalls().isEmpty()) {
                return new TurnResult(requireAnswer(response.content()), rounds);
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
                working, toolsJson, ChatRequest.ToolChoice.NONE, opts);
        if (!synthesised.toolCalls().isEmpty()) {
            throw new ChatException("Model returned tool calls when tool use was disabled");
        }
        return new TurnResult(requireAnswer(synthesised.content()), rounds);
    }

    private ChatResponse generateWithProtocolRetry(
            List<Message> messages,
            String toolsJson,
            ChatRequest.ToolChoice toolChoice,
            GenOptions opts) {
        ChatResponse first = router.generate(
                new ChatRequest(messages, toolsJson, toolChoice), opts);
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
        ChatResponse retry = router.generate(
                new ChatRequest(retryMessages, toolsJson, toolChoice), opts);
        if (!retry.isProtocolValid()) {
            throw new ChatException("Model protocol failure after retry: "
                    + String.join("; ", retry.protocolErrors()));
        }
        return retry;
    }

    private static String requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new ChatException("The model returned no assistant text.");
        }
        return answer.trim();
    }

}
