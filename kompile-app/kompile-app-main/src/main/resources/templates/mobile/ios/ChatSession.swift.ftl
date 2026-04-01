import Foundation

struct ChatSession: Identifiable, Codable, Equatable {
    let id: UUID
    var title: String
    var messages: [ChatMessage]
    let createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        title: String = "New Chat",
        messages: [ChatMessage] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.messages = messages
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var lastMessagePreview: String {
        guard let lastMessage = messages.last else {
            return "No messages yet"
        }
        let preview = lastMessage.content.prefix(80)
        return preview.count < lastMessage.content.count ? "\(preview)..." : String(preview)
    }

    var formattedDate: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: updatedAt, relativeTo: Date())
    }

    mutating func addMessage(_ message: ChatMessage) {
        messages.append(message)
        updatedAt = Date()
        if title == "New Chat", message.role == .sender {
            title = String(message.content.prefix(40))
        }
    }

    mutating func updateLastAssistantMessage(content: String) {
        guard let lastIndex = messages.lastIndex(where: { $0.role == .assistant }) else { return }
        messages[lastIndex] = ChatMessage(
            id: messages[lastIndex].id,
            role: .assistant,
            content: content,
            timestamp: messages[lastIndex].timestamp,
            sources: messages[lastIndex].sources
        )
        updatedAt = Date()
    }
}
