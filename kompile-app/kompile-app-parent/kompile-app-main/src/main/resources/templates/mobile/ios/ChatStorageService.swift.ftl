import Foundation
import SwiftUI

/// Persistence service for chat sessions.
/// Uses UserDefaults with JSON encoding as the storage backend.
/// Provides methods to save, load, and delete chat sessions.
final class ChatStorageService: ObservableObject {
    @Published var sessions: [ChatSession] = []

    private let storageKey = "{{packageName}}.chat_sessions"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.sessions = loadSessionsFromStorage()
    }

    /// Saves a chat session. Updates an existing session if one with the same ID exists,
    /// otherwise appends it as a new session.
    /// - Parameter session: The chat session to save.
    func saveSession(_ session: ChatSession) {
        if let index = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[index] = session
        } else {
            sessions.append(session)
        }
        persistSessions()
    }

    /// Saves all sessions at once, replacing the entire stored collection.
    /// - Parameter sessions: The complete list of sessions to persist.
    func saveSessions(_ sessions: [ChatSession]) {
        self.sessions = sessions
        persistSessions()
    }

    /// Loads all persisted chat sessions from storage.
    /// - Returns: An array of ChatSession objects.
    func loadSessions() -> [ChatSession] {
        return sessions
    }

    /// Deletes a chat session by its ID.
    /// - Parameter session: The session to delete.
    func deleteSession(_ session: ChatSession) {
        sessions.removeAll { $0.id == session.id }
        persistSessions()
    }

    /// Deletes a session at the specified index set offsets.
    /// - Parameter offsets: The index set of sessions to delete.
    func deleteSessions(at offsets: IndexSet) {
        sessions.remove(atOffsets: offsets)
        persistSessions()
    }

    /// Removes all stored sessions.
    func clearAllSessions() {
        sessions.removeAll()
        persistSessions()
    }

    /// Finds a session by its ID.
    /// - Parameter id: The UUID of the session to find.
    /// - Returns: The matching ChatSession, or nil if not found.
    func findSession(by id: UUID) -> ChatSession? {
        return sessions.first { $0.id == id }
    }

    // MARK: - Private

    private func loadSessionsFromStorage() -> [ChatSession] {
        guard let data = defaults.data(forKey: storageKey) else {
            return []
        }
        do {
            return try decoder.decode([ChatSession].self, from: data)
        } catch {
            print("[ChatStorageService] Failed to decode sessions: \(error.localizedDescription)")
            return []
        }
    }

    private func persistSessions() {
        do {
            let data = try encoder.encode(sessions)
            defaults.set(data, forKey: storageKey)
        } catch {
            print("[ChatStorageService] Failed to encode sessions: \(error.localizedDescription)")
        }
    }
}
