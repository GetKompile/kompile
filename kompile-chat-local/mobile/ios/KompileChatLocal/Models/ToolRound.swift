import Foundation

/// A single tool dispatch that occurred within a conversation turn.
/// Mirrors `ChatEngine.ToolRound` from the Java core.
struct ToolRound: Identifiable, Codable, Equatable {
    let id: UUID
    /// Tool name that was called (e.g. "ask_graph_query").
    let tool: String
    /// Arguments passed to the tool (JSON string).
    let argsJson: String
    /// Result returned by the tool (JSON string).
    let resultJson: String

    init(tool: String, argsJson: String, resultJson: String) {
        self.id = UUID()
        self.tool = tool
        self.argsJson = argsJson
        self.resultJson = resultJson
    }
}

/// Result of a single user turn: the final answer + any tool rounds that preceded it.
/// Mirrors `ChatEngine.TurnResult`.
struct TurnResult {
    let answer: String
    let rounds: [ToolRound]
}
