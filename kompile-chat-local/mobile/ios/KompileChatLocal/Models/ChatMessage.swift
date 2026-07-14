import Foundation

/// Role of a message in the conversation, mirroring the Java `Message` record's role strings.
enum MessageRole: String, Codable, Equatable {
    case system       = "system"
    case user         = "user"
    case assistant    = "assistant"
    case toolResult   = "tool_result"
}

/// An immutable chat message.  Mirrors `ai.kompile.chat.local.Message`.
///
/// The `toolResult` role uses content format "TOOL_RESULT <tool>: <json>"
/// exactly as defined in Message.toolResult() — this is critical for models
/// that do not have a native tool_result role.
struct ChatMessage: Identifiable, Codable, Equatable {
    let id: UUID
    let role: MessageRole
    let content: String
    /// Wall-clock timestamp when the message was created.
    let timestamp: Date

    // MARK: - Factory methods (mirror Message.java static constructors)

    static func system(_ content: String) -> ChatMessage {
        ChatMessage(id: UUID(), role: .system, content: content, timestamp: Date())
    }

    static func user(_ content: String) -> ChatMessage {
        ChatMessage(id: UUID(), role: .user, content: content, timestamp: Date())
    }

    static func assistant(_ content: String) -> ChatMessage {
        ChatMessage(id: UUID(), role: .assistant, content: content, timestamp: Date())
    }

    /// "TOOL_RESULT <tool>: <json>" — matches Message.toolResult() exactly.
    static func toolResult(tool: String, json: String) -> ChatMessage {
        ChatMessage(
            id: UUID(),
            role: .toolResult,
            content: "TOOL_RESULT \(tool): \(json)",
            timestamp: Date()
        )
    }
}
