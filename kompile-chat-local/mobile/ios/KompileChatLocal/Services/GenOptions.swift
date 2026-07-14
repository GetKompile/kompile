import Foundation

/// Generation options for local or remote LLM inference.
/// Direct port of `ai.kompile.chat.local.GenOptions`.
///
/// `toOptionsJson()` produces the exact JSON schema expected by sdxLlmGenerate:
///   {"maxNewTokens":N,"sampling":{"temperature":T,"topK":K,"topP":P[,"seed":S]}}
struct GenOptions {

    var temperature: Double
    var maxTokens: Int
    var topP: Double
    var topK: Int
    /// -1 means "random seed" (omit from JSON).
    var seed: Int64

    // MARK: - Defaults

    static var defaults: GenOptions {
        GenOptions(temperature: 0.7, maxTokens: 1024, topP: 0.9, topK: 40, seed: -1)
    }

    // MARK: - From AppSettings

    static func from(_ settings: AppSettings) -> GenOptions {
        GenOptions(
            temperature: settings.temperature,
            maxTokens: settings.maxTokens,
            topP: settings.topP,
            topK: settings.topK,
            seed: -1
        )
    }

    // MARK: - JSON serialisation

    /// Produce the options_json payload consumed by sdxLlmGenerate.
    func toOptionsJson() -> String {
        var sampling: [String: Any] = [
            "temperature": temperature,
            "topK": topK,
            "topP": topP
        ]
        if seed != -1 {
            sampling["seed"] = seed
        }
        let root: [String: Any] = [
            "maxNewTokens": maxTokens,
            "sampling": sampling
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: root),
              let str = String(data: data, encoding: .utf8) else {
            return "{\"maxNewTokens\":\(maxTokens)}"
        }
        return str
    }
}
