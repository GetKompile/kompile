import Foundation

struct AppConfig {
    enum InferenceMode: String, CaseIterable, Identifiable {
        case local = "local"
        case hybrid = "hybrid"
        case remote = "remote"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .local: return "On-Device"
            case .hybrid: return "Hybrid"
            case .remote: return "Remote"
            }
        }

        var description: String {
            switch self {
            case .local: return "All inference runs on device using the bundled model."
            case .hybrid: return "Embeddings run locally; generation uses a remote API."
            case .remote: return "All inference is handled by a remote API endpoint."
            }
        }
    }

    static let defaultInferenceMode: InferenceMode = {
        switch "{{inferenceMode}}" {
        case "local": return .local
        case "hybrid": return .hybrid
        case "remote": return .remote
        default: return .local
        }
    }()

    static let defaultModelId: String = "{{modelId}}"
    static let defaultModelFileName: String = "{{modelFileName}}"
    static let defaultApiKey: String = "{{apiKeyPlaceholder}}"
    static let sdkVersion: String = "{{sdkVersion}}"
    static let packageName: String = "{{packageName}}"

    static var modelBundlePath: String? {
        Bundle.main.path(forResource: modelFileBaseName, ofType: modelFileExtension)
    }

    static var modelFileBaseName: String {
        let name = defaultModelFileName
        if let dotIndex = name.lastIndex(of: ".") {
            return String(name[name.startIndex..<dotIndex])
        }
        return name
    }

    static var modelFileExtension: String {
        let name = defaultModelFileName
        if let dotIndex = name.lastIndex(of: ".") {
            return String(name[name.index(after: dotIndex)...])
        }
        return "sdz"
    }

    static let defaultChunkSize: Int = 512
    static let defaultChunkOverlap: Int = 64
    static let defaultTopK: Int = 5
    static let maxTokensDefault: Int = 256
    static let apiBaseURL: String = "https://api.openai.com/v1"
}
