import Foundation

/// Provider-neutral message roles consumed by the imported model template.
enum MessageRole: String, Codable, Equatable {
    case system = "system"
    case user = "user"
    case assistant = "assistant"
    case tool = "tool"
}

/// Immutable structured chat message mirroring the Java Message record.
struct ChatMessage: Identifiable, Codable, Equatable {
    let id: UUID
    let role: MessageRole
    let content: String
    let timestamp: Date
    /// Canonical JSON array of assistant tool calls, when present.
    let toolCallsJson: String?
    let toolCallId: String?
    let toolName: String?

    init(
        id: UUID = UUID(),
        role: MessageRole,
        content: String,
        timestamp: Date = Date(),
        toolCallsJson: String? = nil,
        toolCallId: String? = nil,
        toolName: String? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.toolCallsJson = toolCallsJson
        self.toolCallId = toolCallId
        self.toolName = toolName
    }

    static func system(_ content: String) -> ChatMessage {
        ChatMessage(role: .system, content: content)
    }

    static func user(_ content: String) -> ChatMessage {
        ChatMessage(role: .user, content: content)
    }

    static func assistant(_ content: String, toolCallsJson: String? = nil) -> ChatMessage {
        ChatMessage(role: .assistant, content: content, toolCallsJson: toolCallsJson)
    }

    static func toolResult(
        callId: String?,
        tool: String,
        json: String
    ) -> ChatMessage {
        ChatMessage(
            role: .tool,
            content: json,
            toolCallId: callId,
            toolName: tool
        )
    }
}
