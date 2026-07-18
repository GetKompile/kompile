package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drives a multi-turn conversation loop with graph tool-calling.
 *
 * <p>For each user turn the engine:</p>
 * <ol>
 *   <li>Builds the working message list (system prompt prepended if absent)</li>
 *   <li>Calls {@link InferenceRouter#generate} on the working history</li>
 *   <li>If the output parses as a tool call via {@link ToolCallParser}, dispatches it
     *       through {@link GraphToolBackend} and loops back to step 2 (up to {@code maxToolRounds})</li>
 *   <li>On malformed-JSON that <em>looks</em> tool-ish, requests one corrective retry</li>
 *   <li>When a plain text answer is detected (or max rounds hit), returns a {@link TurnResult}</li>
 * </ol>
 *
 * <p>Tool-result messages use the role {@code "user"} with the prefix
 * {@code "TOOL_RESULT <tool>: <json>"} for OpenAI-compatible history (models without
 * a native {@code tool_result} role treat this as context from the user turn).</p>
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

        for (int round = 0; round < maxToolRounds; round++) {
            String rawFull = router.generate(working, opts);
            // Strip Qwen3.x <think>...</think> blocks before parsing
            String raw = stripThink(rawFull);
            log.debug("Model output (round {}) after think-strip: {}", round, raw);

            Optional<ToolCallParser.ToolCall> tc = ToolCallParser.parse(raw);
            if (tc.isEmpty()) {
                // Check for malformed tool-ish output — one corrective retry
                if (raw.contains("\"tool\"")) {
                    log.debug("Malformed tool JSON detected, requesting corrective retry.");
                    working.add(Message.assistant(raw));
                    working.add(Message.user(
                            "Your tool call JSON was malformed. Please retry with valid JSON only — " +
                            "a single JSON object with exactly the keys \"tool\" and \"args\"."));
                    String retryFull = router.generate(working, opts);
                    String retry = stripThink(retryFull);
                    Optional<ToolCallParser.ToolCall> retryTc = ToolCallParser.parse(retry);
                    if (retryTc.isPresent()) {
                        // Process as a normal tool call (one round)
                        ToolCallParser.ToolCall call = retryTc.get();
                        String argsJson = MiniJson.write(call.args());
                        String result = bridge.execute(call.tool(), argsJson);
                        rounds.add(new ToolRound(call.tool(), argsJson, result));
                        working.add(Message.assistant(retry));
                        working.add(Message.toolResult(call.tool(), result));
                        // Then fall through to get the final answer
                        String finalAnswerFull = router.generate(working, opts);
                        String finalAnswer = stripThink(finalAnswerFull);
                        return new TurnResult(finalAnswer, rounds);
                    } else {
                        return new TurnResult(retry, rounds);
                    }
                }
                // Plain answer
                return new TurnResult(raw, rounds);
            }

            // Valid tool call — dispatch it
            ToolCallParser.ToolCall call = tc.get();
            String argsJson = MiniJson.write(call.args());
            String toolResult = bridge.execute(call.tool(), argsJson);
            rounds.add(new ToolRound(call.tool(), argsJson, toolResult));
            log.debug("Tool '{}' returned: {}", call.tool(), toolResult);

            // Append tool call + result to working history
            working.add(Message.assistant(raw));
            working.add(Message.toolResult(call.tool(), toolResult));
        }

        // Max rounds hit — ask the model to synthesise without more tool calls
        working.add(Message.user(
                "Please synthesize an answer from the tool results above without calling more tools."));
        String synthesisedFull = router.generate(working, opts);
        String synthesised = stripThink(synthesisedFull);
        return new TurnResult(synthesised, rounds);
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    /**
     * Strip Qwen3.x {@code <think>...</think>} blocks from raw model output.
     * Qwen3.x models emit reasoning traces in think blocks before the final answer.
     * After stripping, return the trimmed answer portion.
     *
     * @param raw raw model output, possibly containing think blocks
     * @return output with think blocks removed and leading/trailing whitespace trimmed
     */
    public static String stripThink(String raw) {
        if (raw == null) return "";
        // Remove <think>...</think> blocks (greedy — handles nested-looking tags too)
        String stripped = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        return stripped.isEmpty() ? raw.trim() : stripped;
    }

}
