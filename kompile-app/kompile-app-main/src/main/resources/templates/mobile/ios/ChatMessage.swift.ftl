import Foundation

struct ChatMessage: Identifiable, Codable, Equatable {
    enum Role: String, Codable, CaseIterable {
        case sender
        case assistant
        case system
    }

    let id: UUID
    let role: Role
    var content: String
    let timestamp: Date
    var sources: [SourceReference]?

    init(
        id: UUID = UUID(),
        role: Role,
        content: String,
        timestamp: Date = Date(),
        sources: [SourceReference]? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.sources = sources
    }

    static func userMessage(_ content: String) -> ChatMessage {
        ChatMessage(role: .sender, content: content)
    }

    static func assistantMessage(_ content: String, sources: [SourceReference]? = nil) -> ChatMessage {
        ChatMessage(role: .assistant, content: content, sources: sources)
    }

    static func systemMessage(_ content: String) -> ChatMessage {
        ChatMessage(role: .system, content: content)
    }
}

struct SourceReference: Identifiable, Codable, Equatable {
    let id: UUID
    let documentName: String
    let chunkIndex: Int
    let snippet: String
    let relevanceScore: Float

    init(
        id: UUID = UUID(),
        documentName: String,
        chunkIndex: Int,
        snippet: String,
        relevanceScore: Float
    ) {
        self.id = id
        self.documentName = documentName
        self.chunkIndex = chunkIndex
        self.snippet = snippet
        self.relevanceScore = relevanceScore
    }
}
