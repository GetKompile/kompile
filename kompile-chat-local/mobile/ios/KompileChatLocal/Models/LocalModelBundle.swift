import Foundation

enum LocalModelSourceKind: String, Codable {
    case canonicalSdz = "canonical-sdz"
    case manualComponents = "manual-components"
}

/// Durable, portable description of every local text-model input used by chat.
struct LocalModelBundle: Codable, Equatable {
    static let currentSchema = "kompile-local-text-model-v1"

    let schema: String
    let sourceKind: LocalModelSourceKind
    let targetProfile: String
    let modelPath: String
    let tokenizerPath: String
    let tokenizerConfigPath: String
    let textGenerationConfigPath: String?
    let modelConfigPath: String?
    let chatTemplatePath: String?
    let generationConfigPath: String?
    let sourceArchivePath: String?

    init(
        sourceKind: LocalModelSourceKind,
        targetProfile: String,
        modelPath: String,
        tokenizerPath: String,
        tokenizerConfigPath: String,
        textGenerationConfigPath: String? = nil,
        modelConfigPath: String? = nil,
        chatTemplatePath: String? = nil,
        generationConfigPath: String? = nil,
        sourceArchivePath: String? = nil
    ) {
        self.schema = Self.currentSchema
        self.sourceKind = sourceKind
        self.targetProfile = targetProfile
        self.modelPath = modelPath
        self.tokenizerPath = tokenizerPath
        self.tokenizerConfigPath = tokenizerConfigPath
        self.textGenerationConfigPath = textGenerationConfigPath
        self.modelConfigPath = modelConfigPath
        self.chatTemplatePath = chatTemplatePath
        self.generationConfigPath = generationConfigPath
        self.sourceArchivePath = sourceArchivePath
    }

    func validated(fileManager: FileManager = .default) throws -> LocalModelBundle {
        guard schema == Self.currentSchema else {
            throw LocalModelBundleError.invalid("Unsupported local model manifest schema: \(schema)")
        }
        guard Self.existsAndNonempty(modelPath, allowDirectory: true, fileManager: fileManager) else {
            throw LocalModelBundleError.invalid("The runtime model is missing or empty.")
        }
        guard Self.existsAndNonempty(tokenizerPath, allowDirectory: false, fileManager: fileManager) else {
            throw LocalModelBundleError.invalid("tokenizer.json is missing or empty.")
        }
        guard Self.existsAndNonempty(tokenizerConfigPath, allowDirectory: false, fileManager: fileManager) else {
            throw LocalModelBundleError.invalid(
                "tokenizer_config.json is missing. Import it or provide chat_template.jinja during manual import."
            )
        }
        let hasGenerationContract = textGenerationConfigPath.map {
            Self.existsAndNonempty($0, allowDirectory: false, fileManager: fileManager)
        } ?? false
        let hasModelConfig = modelConfigPath.map {
            Self.existsAndNonempty($0, allowDirectory: false, fileManager: fileManager)
        } ?? false
        guard hasGenerationContract || hasModelConfig else {
            throw LocalModelBundleError.invalid(
                "Provide the SDX text-generation contract or config.json."
            )
        }
        return self
    }

    private static func existsAndNonempty(
        _ path: String,
        allowDirectory: Bool,
        fileManager: FileManager
    ) -> Bool {
        var directory: ObjCBool = false
        guard fileManager.fileExists(atPath: path, isDirectory: &directory) else {
            return false
        }
        if directory.boolValue {
            return allowDirectory
        }
        guard let attributes = try? fileManager.attributesOfItem(atPath: path),
              let size = attributes[.size] as? NSNumber else {
            return false
        }
        return size.int64Value > 0
    }
}

/// JSON returned by sdxLlmResolveModelBundle.
struct SdxResolvedModelBundle: Decodable {
    let schema: String
    let targetProfile: String
    let compileKey: String
    let sourcePath: String
    let cacheEntryPath: String
    let modelPath: String
    let tokenizerPath: String
    let tokenizerConfigPath: String
    let textGenerationConfigPath: String

    func localBundle() -> LocalModelBundle {
        LocalModelBundle(
            sourceKind: .canonicalSdz,
            targetProfile: targetProfile,
            modelPath: modelPath,
            tokenizerPath: tokenizerPath,
            tokenizerConfigPath: tokenizerConfigPath,
            textGenerationConfigPath: textGenerationConfigPath,
            sourceArchivePath: sourcePath
        )
    }
}

enum LocalModelBundleError: LocalizedError {
    case invalid(String)

    var errorDescription: String? {
        switch self {
        case .invalid(let message):
            return message
        }
    }
}

enum SdxBuildConfiguration {
    static var targetProfile: String {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "SDXTargetProfile") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return configured?.isEmpty == false ? configured! : "ios-arm64-metal"
    }
}
