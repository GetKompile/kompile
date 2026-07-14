import Foundation
import Combine

/// Decides whether to use local (SdxLlmService) or remote (RemoteChatService) inference,
/// and publishes the active route so the UI can show a badge.
///
/// Routing logic: local-first.
///   - If SdxLlmService.isAvailable AND a model path is configured → LOCAL
///   - Otherwise if RemoteChatService.isConfigured → REMOTE
///   - Otherwise → UNAVAILABLE
///
/// Mirrors the `InferenceRouter` concept from `ai.kompile.chat.local.InferenceRouter`
/// but adapted for Swift/iOS.
final class InferenceRouter: ObservableObject {

    enum ActiveRoute: String {
        case local    = "LOCAL"
        case remote   = "REMOTE"
        case unavailable = "UNAVAILABLE"
    }

    // MARK: - Published state

    @Published var activeRoute: ActiveRoute = .unavailable

    // MARK: - Sub-services (created here, shared with ChatEngine)

    let sdxService: SdxLlmService
    let remoteService: RemoteChatService

    private var cancellables = Set<AnyCancellable>()

    init() {
        self.sdxService = SdxLlmService()
        self.remoteService = RemoteChatService()
    }

    // MARK: - Configuration

    /// Apply AppSettings to sub-services and re-evaluate the active route.
    func configure(settings: AppSettings) {
        remoteService.baseUrl = settings.remoteBaseUrl
        remoteService.model = settings.remoteModel
        remoteService.apiKey = settings.remoteApiKey

        recomputeRoute(settings: settings)

        // Observe settings changes
        settings.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self, weak settings] _ in
                guard let self = self, let settings = settings else { return }
                self.remoteService.baseUrl = settings.remoteBaseUrl
                self.remoteService.model = settings.remoteModel
                self.remoteService.apiKey = settings.remoteApiKey
                self.recomputeRoute(settings: settings)
            }
            .store(in: &cancellables)
    }

    // MARK: - Inference API (used by ChatEngine)

    /// Generate a response for the given message list.
    /// Dispatches to the active route.  Returns generated text or throws.
    func generate(messages: [ChatMessage], options: GenOptions) async throws -> String {
        switch activeRoute {
        case .local:
            let prompt = buildLocalPrompt(messages: messages)
            let result = await sdxService.generate(prompt: prompt, options: options)
            if result.hasPrefix("[SdxError]") {
                throw InferenceError.localFailed(result)
            }
            return result
        case .remote:
            return try await remoteService.generate(messages: messages, options: options)
        case .unavailable:
            throw InferenceError.noBackendAvailable
        }
    }

    // MARK: - Private

    private func recomputeRoute(settings: AppSettings) {
        if sdxService.isAvailable && settings.hasLocalModel {
            activeRoute = .local
        } else if remoteService.isConfigured {
            activeRoute = .remote
        } else {
            activeRoute = .unavailable
        }
    }

    /// Build a local prompt from the message list using standard chat template tokens.
    /// Format: <|system|>…<|user|>…<|assistant|>  (Phi-3 / ChatML style).
    private func buildLocalPrompt(messages: [ChatMessage]) -> String {
        var parts: [String] = []
        for msg in messages {
            switch msg.role {
            case .system:
                parts.append("<|system|>\n\(msg.content)<|end|>")
            case .user:
                parts.append("<|user|>\n\(msg.content)<|end|>")
            case .assistant:
                parts.append("<|assistant|>\n\(msg.content)<|end|>")
            case .toolResult:
                // tool_result sent as user content so the model sees it in context
                parts.append("<|user|>\n\(msg.content)<|end|>")
            }
        }
        // Open the assistant turn for the model to fill
        parts.append("<|assistant|>")
        return parts.joined(separator: "\n")
    }
}

enum InferenceError: LocalizedError {
    case noBackendAvailable
    case localFailed(String)

    var errorDescription: String? {
        switch self {
        case .noBackendAvailable: return "No inference backend is available. Configure a remote endpoint or load a local model."
        case .localFailed(let msg): return "Local inference failed: \(msg)"
        }
    }
}
