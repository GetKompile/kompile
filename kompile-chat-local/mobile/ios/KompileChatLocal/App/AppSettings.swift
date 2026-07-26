import Foundation
import SwiftUI

/// Persisted user settings.  All values are stored in UserDefaults via @AppStorage.
/// Shared across views as an EnvironmentObject.
final class AppSettings: ObservableObject {

    // ── Settings migrations ─────────────────────────────────────────────────
    // Versioned cleanup keeps endpoint credentials from surviving an upgrade from
    // the former remote-fallback build. These keys are intentionally migration-only.
    private static let migrationVersionKey = "settingsMigration.localOnly"
    private static let currentMigrationVersion = 1
    private static let legacyRemoteKeys = [
        "remoteBaseUrl",
        "remoteModel",
        "remoteApiKey"
    ]

    init(defaults: UserDefaults = .standard) {
        guard defaults.integer(forKey: Self.migrationVersionKey) < Self.currentMigrationVersion else {
            return
        }
        Self.legacyRemoteKeys.forEach { key in
            defaults.removeObject(forKey: key)
        }
        defaults.set(Self.currentMigrationVersion, forKey: Self.migrationVersionKey)
    }

    // ── Local model ───────────────────────────────────────────────────────
    /// Runtime paths are always app-owned. Canonical SDZ imports are resolved through
    /// SdxModelCache; manual GGUF/GGML imports keep every required sidecar together.
    @AppStorage("localModelPath")
    var localModelPath: String = ""

    @AppStorage("localTokenizerPath")
    var localTokenizerPath: String = ""

    @AppStorage("localTokenizerConfigPath")
    var localTokenizerConfigPath: String = ""

    @AppStorage("localTextGenerationConfigPath")
    var localTextGenerationConfigPath: String = ""

    @AppStorage("localModelConfigPath")
    var localModelConfigPath: String = ""

    @AppStorage("localChatTemplatePath")
    var localChatTemplatePath: String = ""

    @AppStorage("localGenerationConfigPath")
    var localGenerationConfigPath: String = ""

    @AppStorage("localSourceArchivePath")
    var localSourceArchivePath: String = ""

    @AppStorage("localModelSourceKind")
    var localModelSourceKind: String = ""

    @AppStorage("localTargetProfile")
    var localTargetProfile: String = ""

    @AppStorage("localModelManifestPath")
    var localModelManifestPath: String = ""

    /// Staging base only. Repository/component inputs and all credentials stay transient.
    @AppStorage("modelStagingBaseUrl")
    var modelStagingBaseUrl: String = ""

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

    /// A path alone is deliberately insufficient: chat needs tokenizer/config metadata.
    var hasLocalModel: Bool {
        configuredModelBundle != nil
    }

    var configuredModelBundle: LocalModelBundle? {
        guard let sourceKind = LocalModelSourceKind(rawValue: localModelSourceKind),
              !localModelPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !localTokenizerPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !localTokenizerConfigPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !localTargetProfile.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !localTextGenerationConfigPath.isEmpty || !localModelConfigPath.isEmpty else {
            return nil
        }
        return LocalModelBundle(
            sourceKind: sourceKind,
            targetProfile: localTargetProfile,
            modelPath: localModelPath,
            tokenizerPath: localTokenizerPath,
            tokenizerConfigPath: localTokenizerConfigPath,
            textGenerationConfigPath: localTextGenerationConfigPath.nilIfBlank,
            modelConfigPath: localModelConfigPath.nilIfBlank,
            chatTemplatePath: localChatTemplatePath.nilIfBlank,
            generationConfigPath: localGenerationConfigPath.nilIfBlank,
            sourceArchivePath: localSourceArchivePath.nilIfBlank
        )
    }

    func applyLocalModel(_ bundle: LocalModelBundle, manifestPath: String) {
        localModelPath = bundle.modelPath
        localTokenizerPath = bundle.tokenizerPath
        localTokenizerConfigPath = bundle.tokenizerConfigPath
        localTextGenerationConfigPath = bundle.textGenerationConfigPath ?? ""
        localModelConfigPath = bundle.modelConfigPath ?? ""
        localChatTemplatePath = bundle.chatTemplatePath ?? ""
        localGenerationConfigPath = bundle.generationConfigPath ?? ""
        localSourceArchivePath = bundle.sourceArchivePath ?? ""
        localModelSourceKind = bundle.sourceKind.rawValue
        localTargetProfile = bundle.targetProfile
        localModelManifestPath = manifestPath
    }

    func clearLocalModel() {
        localModelPath = ""
        localTokenizerPath = ""
        localTokenizerConfigPath = ""
        localTextGenerationConfigPath = ""
        localModelConfigPath = ""
        localChatTemplatePath = ""
        localGenerationConfigPath = ""
        localSourceArchivePath = ""
        localModelSourceKind = ""
        localTargetProfile = ""
        localModelManifestPath = ""
    }

    /// True when a .kgraph file path has been configured.
    var hasKgraph: Bool {
        !kgraphPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private extension String {
    var nilIfBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
