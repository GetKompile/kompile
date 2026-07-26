import Foundation
import Combine

struct ImportDiagnosticEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let timestamp: Date
    let phase: String
    let message: String
    let remediation: String
}

/// Bounded, sanitized diagnostics stored separately from normal app settings.
final class ImportDiagnostics: ObservableObject {
    static let shared = ImportDiagnostics()

    @Published private(set) var entries: [ImportDiagnosticEntry]

    private let defaults: UserDefaults
    private let storageKey = "entries"
    private let maxEntries = 32
    private let maxFieldLength = 320

    init(defaults: UserDefaults? = nil) {
        self.defaults = defaults ?? UserDefaults(
            suiteName: "ai.kompile.chat.local.import-diagnostics"
        ) ?? .standard
        if let data = self.defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([ImportDiagnosticEntry].self, from: data) {
            self.entries = Array(decoded.prefix(maxEntries))
        } else {
            self.entries = []
        }
    }

    func record(phase: String, message: String, remediation: String) {
        let entry = ImportDiagnosticEntry(
            id: UUID(),
            timestamp: Date(),
            phase: sanitize(phase),
            message: sanitize(message),
            remediation: sanitize(remediation)
        )
        if Thread.isMainThread {
            insert(entry)
        } else {
            DispatchQueue.main.async { [weak self] in self?.insert(entry) }
        }
    }

    func clear() {
        if Thread.isMainThread {
            entries = []
            defaults.removeObject(forKey: storageKey)
        } else {
            DispatchQueue.main.async { [weak self] in self?.clear() }
        }
    }

    private func insert(_ entry: ImportDiagnosticEntry) {
        entries.insert(entry, at: 0)
        entries = Array(entries.prefix(maxEntries))
        if let data = try? JSONEncoder().encode(entries) {
            defaults.set(data, forKey: storageKey)
        }
    }

    private func sanitize(_ input: String) -> String {
        var value = input.replacingOccurrences(
            of: "[\\u0000-\\u001F\\u007F]",
            with: " ",
            options: .regularExpression
        )
        value = value.replacingOccurrences(
            of: "(?i)(authorization|token|password|secret|credential|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+",
            with: "$1=<redacted>",
            options: .regularExpression
        )
        value = value.replacingOccurrences(
            of: "https?://[^\\s]+",
            with: "<url>",
            options: .regularExpression
        )
        value = value.replacingOccurrences(
            of: "(?<![A-Za-z0-9])/(?:[^\\s/:]+/)+[^\\s:]+",
            with: "<local-path>",
            options: .regularExpression
        )
        value = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.count > maxFieldLength {
            value = String(value.prefix(maxFieldLength - 1)) + "…"
        }
        return value
    }
}
