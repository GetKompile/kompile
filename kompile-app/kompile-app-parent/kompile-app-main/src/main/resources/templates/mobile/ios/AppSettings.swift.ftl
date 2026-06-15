import Foundation
import SwiftUI

final class AppSettings: ObservableObject {
    private enum Keys {
        static let apiKey = "kompile_api_key"
        static let inferenceMode = "kompile_inference_mode"
        static let chunkSize = "kompile_chunk_size"
        static let topK = "kompile_top_k"
        static let modelId = "kompile_model_id"
        static let maxTokens = "kompile_max_tokens"
    }

    @Published var apiKey: String {
        didSet { UserDefaults.standard.set(apiKey, forKey: Keys.apiKey) }
    }

    @Published var inferenceMode: AppConfig.InferenceMode {
        didSet { UserDefaults.standard.set(inferenceMode.rawValue, forKey: Keys.inferenceMode) }
    }

    @Published var chunkSize: Int {
        didSet { UserDefaults.standard.set(chunkSize, forKey: Keys.chunkSize) }
    }

    @Published var topK: Int {
        didSet { UserDefaults.standard.set(topK, forKey: Keys.topK) }
    }

    @Published var modelId: String {
        didSet { UserDefaults.standard.set(modelId, forKey: Keys.modelId) }
    }

    @Published var maxTokens: Int {
        didSet { UserDefaults.standard.set(maxTokens, forKey: Keys.maxTokens) }
    }

    init() {
        let defaults = UserDefaults.standard

        self.apiKey = defaults.string(forKey: Keys.apiKey) ?? AppConfig.defaultApiKey
        self.modelId = defaults.string(forKey: Keys.modelId) ?? AppConfig.defaultModelId

        if let modeRaw = defaults.string(forKey: Keys.inferenceMode),
           let mode = AppConfig.InferenceMode(rawValue: modeRaw) {
            self.inferenceMode = mode
        } else {
            self.inferenceMode = AppConfig.defaultInferenceMode
        }

        let storedChunkSize = defaults.integer(forKey: Keys.chunkSize)
        self.chunkSize = storedChunkSize > 0 ? storedChunkSize : AppConfig.defaultChunkSize

        let storedTopK = defaults.integer(forKey: Keys.topK)
        self.topK = storedTopK > 0 ? storedTopK : AppConfig.defaultTopK

        let storedMaxTokens = defaults.integer(forKey: Keys.maxTokens)
        self.maxTokens = storedMaxTokens > 0 ? storedMaxTokens : AppConfig.maxTokensDefault
    }

    func resetToDefaults() {
        apiKey = AppConfig.defaultApiKey
        inferenceMode = AppConfig.defaultInferenceMode
        chunkSize = AppConfig.defaultChunkSize
        topK = AppConfig.defaultTopK
        modelId = AppConfig.defaultModelId
        maxTokens = AppConfig.maxTokensDefault
    }
}
