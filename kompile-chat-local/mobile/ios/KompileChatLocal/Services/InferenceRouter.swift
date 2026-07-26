import Foundation
import Combine

/// Owns one complete local text-model bundle. A configured path is never treated as
/// runnable until every sidecar validates and SDX has loaded the exact model/tokenizer pair.
final class InferenceRouter: ObservableObject {
    enum ActiveRoute: String {
        case local = "LOCAL"
        case unavailable = "MODEL REQUIRED"
    }

    @Published var activeRoute: ActiveRoute = .unavailable

    let sdxService: SdxLlmService

    private var desiredBundle: LocalModelBundle?
    private var attemptedSignature: String?
    private var loadedSignature: String?
    private var cancellables = Set<AnyCancellable>()

    init() {
        self.sdxService = SdxLlmService()
        sdxService.$isAvailable
            .combineLatest(sdxService.$isModelLoaded)
            .receive(on: RunLoop.main)
            .sink { [weak self] isAvailable, _ in
                guard let self else { return }
                if isAvailable {
                    self.startModelLoadIfNeeded()
                }
                self.recomputeRoute()
            }
            .store(in: &cancellables)
    }

    func configure(settings: AppSettings) {
        let next: LocalModelBundle?
        do {
            next = try settings.configuredModelBundle?.validated()
        } catch {
            next = nil
            ImportDiagnostics.shared.record(
                phase: "model-configuration",
                message: error.localizedDescription,
                remediation: "Reimport a complete SDZ or all required manual components."
            )
        }

        if next != desiredBundle {
            desiredBundle = next
            attemptedSignature = nil
            loadedSignature = nil
            if sdxService.isModelLoaded {
                sdxService.unloadModel()
            }
        }
        guard desiredBundle != nil else {
            activeRoute = .unavailable
            return
        }
        startModelLoadIfNeeded()
        recomputeRoute()
    }

    func generate(messages: [ChatMessage], options: GenOptions) async throws -> String {
        guard activeRoute == .local else {
            throw InferenceError.noLocalModelLoaded
        }
        let prompt: String
        do {
            prompt = try await sdxService.renderChatPrompt(messages: messages)
        } catch {
            throw InferenceError.localFailed(
                "Tokenizer chat-template rendering failed: \(error.localizedDescription)"
            )
        }
        let result = await sdxService.generate(prompt: prompt, options: options)
        if result.hasPrefix("[SdxError]") {
            throw InferenceError.localFailed(result)
        }
        return result
    }

    private func startModelLoadIfNeeded() {
        guard sdxService.isAvailable, let bundle = desiredBundle else { return }
        let signature = signature(for: bundle)
        guard attemptedSignature != signature else { return }

        attemptedSignature = signature
        activeRoute = .unavailable
        Task { [weak self] in
            guard let self else { return }
            await self.sdxService.loadModel(bundle: bundle)
            await MainActor.run {
                guard self.desiredBundle == bundle else { return }
                if self.sdxService.isModelLoaded {
                    self.loadedSignature = signature
                } else {
                    ImportDiagnostics.shared.record(
                        phase: "model-load",
                        message: self.sdxService.loadError ?? "SDX could not load the local model.",
                        remediation: "Open Import Log, verify this accelerator build matches the SDZ target, then restage or reimport."
                    )
                }
                self.recomputeRoute()
            }
        }
    }

    private func recomputeRoute() {
        guard let bundle = desiredBundle else {
            activeRoute = .unavailable
            return
        }
        activeRoute = sdxService.isAvailable
            && sdxService.isModelLoaded
            && loadedSignature == signature(for: bundle)
            ? .local
            : .unavailable
    }

    private func signature(for bundle: LocalModelBundle) -> String {
        [
            bundle.targetProfile,
            bundle.modelPath,
            bundle.tokenizerPath,
            bundle.tokenizerConfigPath,
            bundle.textGenerationConfigPath ?? "",
            bundle.modelConfigPath ?? ""
        ].joined(separator: "\u{1F}")
    }
}

enum InferenceError: LocalizedError {
    case noLocalModelLoaded
    case localFailed(String)

    var errorDescription: String? {
        switch self {
        case .noLocalModelLoaded:
            return "No complete local SDX model is loaded. Import a compiled SDZ or all manual GGUF/GGML components in Settings."
        case .localFailed(let message):
            return "Local inference failed: \(message)"
        }
    }
}
