import Foundation

/// Wraps the sdxLlm* C text-generation ABI.
///
/// Module guard: #if canImport(SdxLlm) — the xcframework module name.
/// When the framework is absent the service degrades: isAvailable == false,
/// generate() returns an error string.
///
/// ABI bound here (text-generation only — old dsp_runtime_c tensor API is NOT used):
///   sdxLlmCreateRuntime()    → OpaquePointer (runtime)
///   sdxLlmDestroyRuntime(rt) → Int32
///   sdxLlmAbiVersion(rt)     → Int32  (expect 1)
///   sdxLlmLoadModel(rt, modelPath, tokenizerPathOrNil, optionsJsonOrNil) → OpaquePointer (model)
///   sdxLlmUnloadModel(rt, m) → Int32
///   sdxLlmGenerate(rt, m, prompt, optionsJson, &outText) → Int32  (0=OK)
///   sdxLlmFree(rt, ptr)      — free every outText pointer
///   sdxLlmGetLastError(rt, buf, cap) → Int32
///
/// options JSON: {"maxNewTokens":N,"sampling":{"temperature":T,"topK":K,"topP":P[,"seed":S]}}
final class SdxLlmService: ObservableObject {

    // MARK: - Published state

    @Published var isAvailable: Bool = false
    @Published var isModelLoaded: Bool = false
    @Published var loadError: String? = nil

    // MARK: - Private state

    private let inferenceQueue = DispatchQueue(label: "ai.kompile.chat.local.sdx", qos: .userInitiated)

    #if canImport(SdxLlm)
    private var runtime: OpaquePointer? = nil
    private var modelHandle: OpaquePointer? = nil
    #endif

    // MARK: - Init / deinit

    init() {
        #if canImport(SdxLlm)
        isAvailable = true
        createRuntime()
        #else
        isAvailable = false
        #endif
    }

    deinit {
        unloadModel()
        destroyRuntime()
    }

    // MARK: - Model lifecycle

    /// Load a model from an absolute filesystem path.
    /// Optionally pass a separate tokenizer path; pass nil to auto-detect.
    func loadModel(path: String, tokenizerPath: String? = nil) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            inferenceQueue.async { [weak self] in
                guard let self = self else { continuation.resume(); return }
                #if canImport(SdxLlm)
                guard let rt = self.runtime else {
                    DispatchQueue.main.async {
                        self.loadError = "Runtime not initialised"
                    }
                    continuation.resume()
                    return
                }
                // Unload any previous model first
                if let existing = self.modelHandle {
                    sdxLlmUnloadModel(rt, existing)
                    self.modelHandle = nil
                }
                let handle = sdxLlmLoadModel(rt, path, tokenizerPath, nil)
                DispatchQueue.main.async {
                    if handle != nil {
                        self.modelHandle = handle
                        self.isModelLoaded = true
                        self.loadError = nil
                    } else {
                        self.isModelLoaded = false
                        self.loadError = self.lastError(rt: rt)
                    }
                }
                #endif
                continuation.resume()
            }
        }
    }

    /// Unload the current model and free its resources.
    func unloadModel() {
        #if canImport(SdxLlm)
        inferenceQueue.sync { [weak self] in
            guard let self = self,
                  let rt = self.runtime,
                  let m = self.modelHandle else { return }
            sdxLlmUnloadModel(rt, m)
            self.modelHandle = nil
            DispatchQueue.main.async { self.isModelLoaded = false }
        }
        #endif
    }

    // MARK: - Inference

    /// Generate text for a prompt using the loaded model.
    /// Returns the generated text, or an error string prefixed with "[SdxError]".
    ///
    /// - Parameters:
    ///   - prompt: Full prompt string (caller builds from conversation history).
    ///   - options: Generation options (temperature, maxTokens, etc.).
    func generate(prompt: String, options: GenOptions) async -> String {
        #if canImport(SdxLlm)
        return await withCheckedContinuation { continuation in
            inferenceQueue.async { [weak self] in
                guard let self = self,
                      let rt = self.runtime,
                      let m = self.modelHandle else {
                    continuation.resume(returning: "[SdxError] Model not loaded")
                    return
                }
                var outPtr: UnsafeMutablePointer<CChar>? = nil
                let rc = sdxLlmGenerate(rt, m, prompt, options.toOptionsJson(), &outPtr)
                if rc == 0, let ptr = outPtr {
                    let text = String(cString: ptr)
                    sdxLlmFree(rt, ptr)
                    continuation.resume(returning: text)
                } else {
                    let err = self.lastError(rt: rt)
                    if let ptr = outPtr { sdxLlmFree(rt, ptr) }
                    continuation.resume(returning: "[SdxError] \(err)")
                }
            }
        }
        #else
        return "[SdxError] SdxLlm library not available"
        #endif
    }

    // MARK: - Private helpers

    private func createRuntime() {
        #if canImport(SdxLlm)
        inferenceQueue.async { [weak self] in
            guard let self = self else { return }
            let rt = sdxLlmCreateRuntime()
            DispatchQueue.main.async {
                if rt != nil {
                    self.runtime = rt
                } else {
                    self.isAvailable = false
                    self.loadError = "sdxLlmCreateRuntime() returned NULL"
                }
            }
        }
        #endif
    }

    private func destroyRuntime() {
        #if canImport(SdxLlm)
        inferenceQueue.sync { [weak self] in
            guard let self = self, let rt = self.runtime else { return }
            sdxLlmDestroyRuntime(rt)
            self.runtime = nil
        }
        #endif
    }

    #if canImport(SdxLlm)
    private func lastError(rt: OpaquePointer) -> String {
        var buf = [CChar](repeating: 0, count: 512)
        sdxLlmGetLastError(rt, &buf, 512)
        return String(cString: buf)
    }
    #endif
}
