import Foundation

/// Drives structured graph tool-calling. Model protocol handling belongs to SDX.
final class ChatEngine {

    private let router: InferenceRouter
    private let graphService: GraphReasoningService
    private let maxToolRounds: Int

    func buildSystemPrompt() -> String {
        """
        You are a graph assistant. Use the available graph tools when the answer depends         on people, organizations, entities, or relationships in the graph. Inspect relevant         entities before drawing conclusions, do not invent missing facts, and give a concise         answer grounded in the returned graph data.
        """
    }

    init(router: InferenceRouter, graphService: GraphReasoningService, maxToolRounds: Int) {
        self.router = router
        self.graphService = graphService
        self.maxToolRounds = maxToolRounds
    }

    func chat(
        history: [ChatMessage],
        userInput: String,
        options: GenOptions
    ) async throws -> TurnResult {
        var working = history
        if working.isEmpty || working[0].role != .system {
            working.insert(ChatMessage.system(buildSystemPrompt()), at: 0)
        }
        working.append(ChatMessage.user(userInput))

        var rounds: [ToolRound] = []
        let toolsJson = graphService.catalogJson()

        for _ in 0..<maxToolRounds {
            let response = try await generateWithProtocolRetry(
                messages: working,
                toolsJson: toolsJson,
                toolChoice: .auto,
                options: options
            )
            if response.toolCalls.isEmpty {
                return TurnResult(answer: try requireAnswer(response.content), rounds: rounds)
            }

            working.append(ChatMessage.assistant(
                response.rawText,
                toolCallsJson: try response.toolCallsJson()
            ))
            for call in response.toolCalls {
                let argsJson = try jsonString(from: call.args)
                let toolResult = await graphService.dispatch(
                    tool: call.tool,
                    argsJson: argsJson
                )
                rounds.append(ToolRound(
                    tool: call.tool,
                    argsJson: argsJson,
                    resultJson: toolResult
                ))
                working.append(ChatMessage.toolResult(
                    callId: call.id,
                    tool: call.tool,
                    json: toolResult
                ))
            }
        }

        working.append(ChatMessage.user(
            "Please synthesize an answer from the tool results above without calling more tools."
        ))
        let synthesised = try await generateWithProtocolRetry(
            messages: working,
            toolsJson: toolsJson,
            toolChoice: .none,
            options: options
        )
        guard synthesised.toolCalls.isEmpty else {
            throw InferenceError.localFailed(
                "Model returned tool calls when tool use was disabled."
            )
        }
        return TurnResult(
            answer: try requireAnswer(synthesised.content),
            rounds: rounds
        )
    }

    private func generateWithProtocolRetry(
        messages: [ChatMessage],
        toolsJson: String,
        toolChoice: ChatToolChoice,
        options: GenOptions
    ) async throws -> StructuredChatResponse {
        let first = try await router.generate(
            messages: messages,
            toolsJson: toolsJson,
            toolChoice: toolChoice,
            options: options
        )
        if first.isProtocolValid {
            return first
        }

        var retryMessages = messages
        retryMessages.append(ChatMessage.assistant(first.rawText))
        retryMessages.append(ChatMessage.user(
            "The previous assistant response failed the model's tool-call protocol validation: "
                + first.protocolErrors.joined(separator: "; ")
                + ". Retry the same turn using the model's declared response protocol."
        ))
        let retry = try await router.generate(
            messages: retryMessages,
            toolsJson: toolsJson,
            toolChoice: toolChoice,
            options: options
        )
        guard retry.isProtocolValid else {
            throw InferenceError.localFailed(
                "Model protocol failure after retry: "
                    + retry.protocolErrors.joined(separator: "; ")
            )
        }
        return retry
    }

    private func jsonString(from dict: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: dict)
        guard let value = String(data: data, encoding: .utf8) else {
            throw InferenceError.localFailed("Could not encode tool arguments.")
        }
        return value
    }

    private func requireAnswer(_ answer: String) throws -> String {
        let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw InferenceError.localFailed("The model returned no assistant text.")
        }
        return trimmed
    }
}
