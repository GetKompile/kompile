import Foundation
import SwiftUI

/// Persisted user settings.  All values are stored in UserDefaults via @AppStorage.
/// Shared across views as an EnvironmentObject.
final class AppSettings: ObservableObject {

    // ── Remote endpoint ───────────────────────────────────────────────────
    @AppStorage("remoteBaseUrl")
    var remoteBaseUrl: String = "http://localhost:8080"

    @AppStorage("remoteModel")
    var remoteModel: String = "gpt-4o-mini"

    @AppStorage("remoteApiKey")
    var remoteApiKey: String = ""

    // ── Local model ───────────────────────────────────────────────────────
    /// Absolute path to the .gguf / safetensors model file inside the app container.
    @AppStorage("localModelPath")
    var localModelPath: String = ""

    // ── Graph (.kgraph file) ──────────────────────────────────────────────
    /// Absolute path to the .kgraph file inside the app container.
    @AppStorage("kgraphPath")
    var kgraphPath: String = ""

    // ── Generation parameters ─────────────────────────────────────────────
    @AppStorage("temperature")
    var temperature: Double = 0.7

    @AppStorage("maxTokens")
    var maxTokens: Int = 1024

    @AppStorage("topP")
    var topP: Double = 0.9

    @AppStorage("topK")
    var topK: Int = 40

    @AppStorage("maxToolRounds")
    var maxToolRounds: Int = 4

    // ── Derived helpers ───────────────────────────────────────────────────

    /// True when a local model path has been configured.
    var hasLocalModel: Bool {
        !localModelPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// True when a remote endpoint has been configured.
    var hasRemoteEndpoint: Bool {
        !remoteBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// True when a .kgraph file path has been configured.
    var hasKgraph: Bool {
        !kgraphPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
