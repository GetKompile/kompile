import Foundation

/// Drives the multi-turn conversation loop with graph tool-calling.
///
/// EXACT port of `ai.kompile.chat.local.ChatEngine`.  See that class for the
/// authoritative docstring.  Behavioural rules reproduced here for clarity:
///
///  1. Build working message list; prepend system prompt if absent.
///  2. Call `router.generate(messages:options:)`.
///  3. If output parses as a tool call (via ToolCallParser):
///     - dispatch via GraphReasoningService.dispatch(tool:argsJson:)
///     - append assistant + TOOL_RESULT messages
///     - loop (up to maxToolRounds)
///  4. On malformed-JSON that looks tool-ish ("\"tool\"" present):
///     - one corrective retry with an instruction message
///     - if the retry parses, dispatch once then get final answer
///     - if the retry still fails, return the retry text as the answer
///  5. When max rounds hit, ask the model to synthesise without more tools.
///
/// Tool-result messages use role="tool_result" with content
/// "TOOL_RESULT <tool>: <json>" (matches Message.toolResult() contract).
///
/// System prompt wording is verbatim from ChatEngine.buildSystemPrompt() —
/// copied into ChatEngine.systemPromptText() so it is the single source of truth.
final class ChatEngine {

    // MARK: - Dependencies

    private let router: InferenceRouter
    private let graphService: GraphReasoningService
    private let maxToolRounds: Int

    // MARK: - System prompt

    /// Verbatim port of ChatEngine.buildSystemPrompt().
    /// Source: ai.kompile.chat.local.ChatEngine.buildSystemPrompt — keep in sync.
    func buildSystemPrompt() -> String {
        let catalog = graphService.catalogJson()
        return """
        You are a helpful AI assistant with access to a local knowledge graph.
        To query the graph, reply ONLY with a JSON object (no other text) in this exact format:
        {"tool": "<tool_name>", "args": {<arguments>}}
        Available tools:
        \(catalog)

        Rules:
        - Use a tool ONLY when the user's question requires graph data.
        - After receiving TOOL_RESULT, synthesize a natural answer.
        - If no tool is needed, answer directly.
        - Do not emit the JSON tool-call format unless you intend to call a tool.
        - When calling a tool, your entire response must be the JSON object and nothing else.
        """
    }

    // MARK: - Init

    init(router: InferenceRouter, graphService: GraphReasoningService, maxToolRounds: Int) {
        self.router = router
        self.graphService = graphService
        self.maxToolRounds = maxToolRounds
    }

    // MARK: - Public API

    /// Process a single user turn.
    ///
    /// - Parameters:
    ///   - history: Prior conversation messages.  NOT mutated; working copy is internal.
    ///   - userInput: The user's latest message text.
    ///   - options: Generation options (temperature, maxTokens, …).
    /// - Returns: TurnResult with the final answer and any tool rounds.
    /// - Throws: InferenceError if no backend is available or generation fails.
    func chat(history: [ChatMessage], userInput: String, options: GenOptions) async throws -> TurnResult {

        // 1. Build working message list
        var working: [ChatMessage] = history
        if working.isEmpty || working[0].role != .system {
            working.insert(ChatMessage.system(buildSystemPrompt()), at: 0)
        }
        working.append(ChatMessage.user(userInput))

        var rounds: [ToolRound] = []

        for round in 0..<maxToolRounds {
            let raw = try await router.generate(messages: working, options: options)

            if let tc = ToolCallParser.parse(raw) {
                // Valid tool call — dispatch
                let argsJson = jsonString(from: tc.args)
                let toolResult = await graphService.dispatch(tool: tc.tool, argsJson: argsJson)
                rounds.append(ToolRound(tool: tc.tool, argsJson: argsJson, resultJson: toolResult))

                working.append(ChatMessage.assistant(raw))
                working.append(ChatMessage.toolResult(tool: tc.tool, json: toolResult))
                // continue to next round
            } else {
                // Check for malformed tool-ish output — one corrective retry
                if raw.contains("\"tool\"") {
                    working.append(ChatMessage.assistant(raw))
                    working.append(ChatMessage.user(
                        "Your tool call JSON was malformed. Please retry with valid JSON only — " +
                        "a single JSON object with exactly the keys \"tool\" and \"args\"."
                    ))
                    let retry = try await router.generate(messages: working, options: options)
                    if let retryTc = ToolCallParser.parse(retry) {
                        // Process as a normal tool call (single round)
                        let argsJson = jsonString(from: retryTc.args)
                        let toolResult = await graphService.dispatch(tool: retryTc.tool, argsJson: argsJson)
                        rounds.append(ToolRound(tool: retryTc.tool, argsJson: argsJson, resultJson: toolResult))
                        working.append(ChatMessage.assistant(retry))
                        working.append(ChatMessage.toolResult(tool: retryTc.tool, json: toolResult))
                        // Get the final answer
                        let finalAnswer = try await router.generate(messages: working, options: options)
                        return TurnResult(answer: finalAnswer, rounds: rounds)
                    } else {
                        // Give up — return the retry text
                        return TurnResult(answer: retry, rounds: rounds)
                    }
                }
                // Plain text answer
                return TurnResult(answer: raw, rounds: rounds)
            }
        }

        // Max rounds hit — synthesise
        working.append(ChatMessage.user(
            "Please synthesize an answer from the tool results above without calling more tools."
        ))
        let synthesised = try await router.generate(messages: working, options: options)
        return TurnResult(answer: synthesised, rounds: rounds)
    }

    // MARK: - Private helpers

    /// Serialise a [String: Any] args dictionary to a JSON string.
    private func jsonString(from dict: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }
}
