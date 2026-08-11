import Foundation
import Combine

/// Swift wrapper for the SDX AOT C ABI v2. The same runtime now owns canonical SDZ
/// resolution, tokenizer-aware chat rendering, model loading, and generation.
final class SdxLlmService: ObservableObject {
    @Published var isAvailable = false
    @Published var isModelLoaded = false
    @Published var loadError: String?
    @Published private(set) var abiVersion: Int32 = 0

    #if canImport(SdxLlm)
    // Graal isolate handles are OS-thread-affine. A serial DispatchQueue is not enough:
    // GCD may move successive blocks between threads, so all native calls use one Thread.
    private let inferenceThread = SdxAffineExecutor(name: "ai.kompile.chat.local.sdx")
    private var runtime: OpaquePointer?
    private var modelHandle: OpaquePointer?
    #endif

    init() {
        #if canImport(SdxLlm)
        createRuntime()
        #else
        loadError = "SdxLlm xcframework is not linked."
        #endif
    }

    deinit {
        unloadModel()
        destroyRuntime()
        #if canImport(SdxLlm)
        inferenceThread.stop()
        #endif
    }

    /// Resolve the selected target from a canonical complete SDZ. This delegates all
    /// archive bounds, hash, containment and complete-text-asset checks to SdxModelCache.
    func resolveModelBundle(
        sourcePath: String,
        targetProfile: String,
        cacheDirectory: String
    ) async throws -> SdxResolvedModelBundle {
        #if canImport(SdxLlm)
        return try await withCheckedThrowingContinuation { continuation in
            inferenceThread.async { [weak self] in
                guard let self, let rt = self.runtime else {
                    continuation.resume(
                        throwing: SdxServiceError.unavailable(
                            "SDX runtime is not ready. Check the import log and runtime build."
                        )
                    )
                    return
                }
                var out: UnsafeMutablePointer<CChar>?
                let status = sdxLlmResolveModelBundle(
                    rt,
                    sourcePath,
                    targetProfile,
                    cacheDirectory,
                    &out
                )
                guard status == 0, let pointer = out else {
                    if let pointer = out { sdxLlmFree(rt, pointer) }
                    continuation.resume(
                        throwing: SdxServiceError.operation(self.lastError(rt: rt))
                    )
                    return
                }
                let data = Data(String(cString: pointer).utf8)
                sdxLlmFree(rt, pointer)
                do {
                    let resolved = try JSONDecoder().decode(
                        SdxResolvedModelBundle.self,
                        from: data
                    )
                    guard resolved.schema == "sdx-resolved-text-model-v1" else {
                        throw SdxServiceError.operation(
                            "The runtime returned an unsupported model-bundle schema."
                        )
                    }
                    continuation.resume(returning: resolved)
                } catch {
                    continuation.resume(
                        throwing: SdxServiceError.operation(
                            "The runtime returned invalid model-bundle metadata: \(error.localizedDescription)"
                        )
                    )
                }
            }
        }
        #else
        throw SdxServiceError.unavailable(
            "Canonical SDZ import requires the SDX AOT ABI v2 xcframework."
        )
        #endif
    }

    /// Load one complete local model bundle. Canonical SDZ imports execute the resolver's
    /// immutable bundle through SdxRuntime/SdxTextSession; advanced loose GGUF/GGML imports
    /// retain the explicit legacy importer path. Both use the same tokenizer-owned template.
    func loadModel(bundle: LocalModelBundle) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            inferenceThread.async { [weak self] in
                guard let self else {
                    continuation.resume()
                    return
                }
                #if canImport(SdxLlm)
                guard let rt = self.runtime else {
                    DispatchQueue.main.async {
                        self.isModelLoaded = false
                        self.loadError = "SDX runtime is not initialised."
                        continuation.resume()
                    }
                    return
                }
                guard !bundle.tokenizerPath
                    .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    DispatchQueue.main.async {
                        self.isModelLoaded = false
                        self.loadError = "tokenizer.json is required for local chat."
                        continuation.resume()
                    }
                    return
                }

                if let existing = self.modelHandle {
                    sdxLlmUnloadModel(rt, existing)
                    self.modelHandle = nil
                }

                let handle: OpaquePointer?
                switch bundle.sourceKind {
                case .canonicalSdz:
                    do {
                        let cache = try self.deviceCompilationCacheDirectory()
                        let data = try JSONSerialization.data(
                            withJSONObject: [
                                "deviceCompilationCacheDirectory": cache.path
                            ],
                            options: []
                        )
                        guard let options = String(data: data, encoding: .utf8) else {
                            throw SdxServiceError.operation(
                                "Could not encode the SDX device compilation cache path."
                            )
                        }
                        handle = sdxLlmLoadCompiledModel(
                            rt,
                            bundle.modelPath,
                            bundle.tokenizerPath,
                            bundle.targetProfile,
                            options
                        )
                    } catch {
                        DispatchQueue.main.async {
                            self.isModelLoaded = false
                            self.loadError =
                                "Could not prepare the SDX device cache: \(error.localizedDescription)"
                            continuation.resume()
                        }
                        return
                    }
                case .manualComponents:
                    handle = sdxLlmLoadModel(
                        rt,
                        bundle.modelPath,
                        bundle.tokenizerPath,
                        nil
                    )
                }

                let error = handle == nil ? self.lastError(rt: rt) : nil
                self.modelHandle = handle
                DispatchQueue.main.async {
                    self.isModelLoaded = handle != nil
                    self.loadError = error
                    continuation.resume()
                }
                #else
                DispatchQueue.main.async {
                    self.isModelLoaded = false
                    self.loadError = "SdxLlm xcframework is not linked."
                    continuation.resume()
                }
                #endif
            }
        }
    }

    func unloadModel() {
        #if canImport(SdxLlm)
        inferenceThread.sync { [weak self] in
            guard let self,
                  let rt = self.runtime,
                  let model = self.modelHandle else {
                return
            }
            sdxLlmUnloadModel(rt, model)
            self.modelHandle = nil
            DispatchQueue.main.async { self.isModelLoaded = false }
        }
        #endif
    }

    /// Run one model-owned structured chat turn.
    func generateChat(
        messages: [ChatMessage],
        toolsJson: String,
        toolChoice: ChatToolChoice,
        options: GenOptions
    ) async throws -> StructuredChatResponse {
        #if canImport(SdxLlm)
        let toolsData = Data(toolsJson.utf8)
        guard let tools = try JSONSerialization.jsonObject(with: toolsData) as? [[String: Any]] else {
            throw SdxServiceError.operation("Graph tool catalog must be a JSON array.")
        }
        let encodedMessages: [[String: Any]] = try messages.map { message in
            var value: [String: Any] = [
                "role": message.role.rawValue,
                "content": message.content
            ]
            if let callsJson = message.toolCallsJson {
                guard let calls = try JSONSerialization.jsonObject(
                    with: Data(callsJson.utf8)
                ) as? [[String: Any]] else {
                    throw SdxServiceError.operation(
                        "Assistant tool-call history is not a JSON array."
                    )
                }
                value["tool_calls"] = calls
            }
            if let id = message.toolCallId { value["tool_call_id"] = id }
            if let name = message.toolName { value["name"] = name }
            return value
        }
        let request: [String: Any] = [
            "messages": encodedMessages,
            "tools": toolChoice == .none ? [] : tools,
            "tool_choice": toolChoice.rawValue,
            "add_generation_prompt": true
        ]
        let requestData = try JSONSerialization.data(withJSONObject: request)
        guard let requestJson = String(data: requestData, encoding: .utf8) else {
            throw SdxServiceError.operation("Could not encode structured chat request.")
        }

        return try await withCheckedThrowingContinuation { continuation in
            inferenceThread.async { [weak self] in
                guard let self,
                      let rt = self.runtime,
                      let model = self.modelHandle else {
                    continuation.resume(
                        throwing: SdxServiceError.unavailable("Model is not loaded.")
                    )
                    return
                }
                var out: UnsafeMutablePointer<CChar>?
                let status = sdxLlmGenerateChat(
                    rt,
                    model,
                    requestJson,
                    options.toOptionsJson(),
                    &out
                )
                guard status == 0, let pointer = out else {
                    if let pointer = out { sdxLlmFree(rt, pointer) }
                    continuation.resume(
                        throwing: SdxServiceError.operation(self.lastError(rt: rt))
                    )
                    return
                }
                let structuredJson = String(cString: pointer)
                sdxLlmFree(rt, pointer)
                do {
                    continuation.resume(
                        returning: try StructuredChatResponse.decode(structuredJson)
                    )
                } catch {
                    continuation.resume(
                        throwing: SdxServiceError.operation(error.localizedDescription)
                    )
                }
            }
        }
        #else
        throw SdxServiceError.unavailable("SdxLlm xcframework is not linked.")
        #endif
    }

    /// Render conversation history with the tokenizer-owned chat template.
    func renderChatPrompt(messages: [ChatMessage]) async throws -> String {
        #if canImport(SdxLlm)
        let payload: [[String: String]] = messages.map { message in
            [
                "role": message.role.rawValue,
                "content": message.content
            ]
        }
        let data = try JSONSerialization.data(withJSONObject: payload, options: [])
        guard let json = String(data: data, encoding: .utf8) else {
            throw SdxServiceError.operation("Could not encode chat messages.")
        }

        return try await withCheckedThrowingContinuation { continuation in
            inferenceThread.async { [weak self] in
                guard let self,
                      let rt = self.runtime,
                      let model = self.modelHandle else {
                    continuation.resume(
                        throwing: SdxServiceError.unavailable("Model is not loaded.")
                    )
                    return
                }
                var out: UnsafeMutablePointer<CChar>?
                let status = sdxLlmRenderChatPrompt(rt, model, json, 1, &out)
                guard status == 0, let pointer = out else {
                    if let pointer = out { sdxLlmFree(rt, pointer) }
                    continuation.resume(
                        throwing: SdxServiceError.operation(self.lastError(rt: rt))
                    )
                    return
                }
                let prompt = String(cString: pointer)
                sdxLlmFree(rt, pointer)
                continuation.resume(returning: prompt)
            }
        }
        #else
        throw SdxServiceError.unavailable("SdxLlm xcframework is not linked.")
        #endif
    }

    func generate(prompt: String, options: GenOptions) async -> String {
        #if canImport(SdxLlm)
        return await withCheckedContinuation { continuation in
            inferenceThread.async { [weak self] in
                guard let self,
                      let rt = self.runtime,
                      let model = self.modelHandle else {
                    continuation.resume(returning: "[SdxError] Model not loaded")
                    return
                }
                var out: UnsafeMutablePointer<CChar>?
                let status = sdxLlmGenerate(
                    rt,
                    model,
                    prompt,
                    options.toOptionsJson(),
                    &out
                )
                if status == 0, let pointer = out {
                    let text = String(cString: pointer)
                    sdxLlmFree(rt, pointer)
                    continuation.resume(returning: text)
                } else {
                    let error = self.lastError(rt: rt)
                    if let pointer = out { sdxLlmFree(rt, pointer) }
                    continuation.resume(returning: "[SdxError] \(error)")
                }
            }
        }
        #else
        return "[SdxError] SdxLlm xcframework is not linked"
        #endif
    }

    private func createRuntime() {
        #if canImport(SdxLlm)
        inferenceThread.async { [weak self] in
            guard let self else { return }
            guard let rt = sdxLlmCreateRuntime() else {
                DispatchQueue.main.async {
                    self.isAvailable = false
                    self.loadError = "sdxLlmCreateRuntime() returned NULL."
                }
                return
            }
            let version = Int32(sdxLlmAbiVersion(rt))
            guard version >= 2 else {
                sdxLlmDestroyRuntime(rt)
                DispatchQueue.main.async {
                    self.abiVersion = version
                    self.isAvailable = false
                    self.loadError =
                        "SDX AOT ABI v2 is required for canonical SDZ import and tokenizer chat templates; linked runtime reports v\(version)."
                }
                return
            }
            self.runtime = rt
            DispatchQueue.main.async {
                self.abiVersion = version
                self.isAvailable = true
                self.loadError = nil
            }
        }
        #endif
    }

    private func destroyRuntime() {
        #if canImport(SdxLlm)
        inferenceThread.sync { [weak self] in
            guard let self, let rt = self.runtime else { return }
            sdxLlmDestroyRuntime(rt)
            self.runtime = nil
        }
        #endif
    }

    #if canImport(SdxLlm)
    private func deviceCompilationCacheDirectory() throws -> URL {
        let caches = try FileManager.default.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = caches.appendingPathComponent(
            "SDXDeviceCompilation",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }

    private func lastError(rt: OpaquePointer) -> String {
        var buffer = [CChar](repeating: 0, count: 1024)
        sdxLlmGetLastError(rt, &buffer, Int32(buffer.count))
        let result = String(cString: buffer)
        return result.isEmpty ? "Unknown SDX runtime error." : result
    }
    #endif
}

/// Minimal single-thread executor for Graal isolate C APIs. It intentionally drains work on
/// one long-lived Foundation Thread instead of relying on a merely serial GCD queue.
private final class SdxAffineExecutor {
    private let condition = NSCondition()
    private var pending: [() -> Void] = []
    private var stopping = false
    private var worker: Thread!

    init(name: String) {
        worker = Thread { [weak self] in
            self?.drain()
        }
        worker.name = name
        worker.qualityOfService = .userInitiated
        worker.start()
    }

    func async(_ work: @escaping () -> Void) {
        condition.lock()
        guard !stopping else {
            condition.unlock()
            return
        }
        pending.append(work)
        condition.signal()
        condition.unlock()
    }

    func sync(_ work: @escaping () -> Void) {
        if Thread.current === worker {
            work()
            return
        }
        let completion = DispatchSemaphore(value: 0)
        async {
            work()
            completion.signal()
        }
        completion.wait()
    }

    func stop() {
        condition.lock()
        stopping = true
        condition.broadcast()
        condition.unlock()
    }

    private func drain() {
        while true {
            condition.lock()
            while pending.isEmpty && !stopping {
                condition.wait()
            }
            if stopping && pending.isEmpty {
                condition.unlock()
                return
            }
            let work = pending.removeFirst()
            condition.unlock()
            autoreleasepool {
                work()
            }
        }
    }
}

enum ChatToolChoice: String {
    case auto
    case required
    case none
}

enum SdxServiceError: LocalizedError {
    case unavailable(String)
    case operation(String)

    var errorDescription: String? {
        switch self {
        case .unavailable(let message), .operation(let message):
            return message
        }
    }
}
