import Foundation

/// Service that wraps the SameDiff C FFI for on-device model inference.
/// Uses the dsp_runtime_c.h bridging header to call native functions.
/// Loads .sdz model files from the application bundle.
final class SdxInferenceService: ObservableObject {
    @Published var isModelLoaded: Bool = false
    @Published var isProcessing: Bool = false
    @Published var loadError: String? = nil

    private var modelHandle: OpaquePointer? = nil
    private let processingQueue = DispatchQueue(label: "{{packageName}}.sdx.inference", qos: .userInitiated)

    deinit {
        unloadModel()
    }

    /// Loads a SameDiff model from the given file path.
    /// - Parameter path: Absolute path to the .sdz model file.
    func loadModel(path: String) async {
        await MainActor.run {
            isProcessing = true
            loadError = nil
        }

        return await withCheckedContinuation { continuation in
            processingQueue.async { [weak self] in
                guard let self = self else {
                    continuation.resume()
                    return
                }

                #if canImport(DspRuntimeC)
                let handle = dsp_model_load(path)
                DispatchQueue.main.async {
                    if handle != nil {
                        self.modelHandle = handle
                        self.isModelLoaded = true
                        self.loadError = nil
                    } else {
                        self.isModelLoaded = false
                        self.loadError = "Failed to load model from: \(path)"
                    }
                    self.isProcessing = false
                    continuation.resume()
                }
                #else
                DispatchQueue.main.async {
                    // Stub mode: simulate model loading when native library is not available
                    self.isModelLoaded = true
                    self.loadError = nil
                    self.isProcessing = false
                    continuation.resume()
                }
                #endif
            }
        }
    }

    /// Unloads the currently loaded model and frees resources.
    func unloadModel() {
        #if canImport(DspRuntimeC)
        if let handle = modelHandle {
            dsp_model_free(handle)
        }
        #endif
        modelHandle = nil
        isModelLoaded = false
    }

    /// Generates text from a prompt using the loaded model.
    /// Returns an AsyncStream that yields tokens as they are generated.
    /// - Parameters:
    ///   - prompt: The input prompt text.
    ///   - maxTokens: Maximum number of tokens to generate.
    /// - Returns: An AsyncStream of generated token strings.
    func generate(prompt: String, maxTokens: Int) async -> AsyncStream<String> {
        AsyncStream { continuation in
            processingQueue.async { [weak self] in
                guard let self = self else {
                    continuation.finish()
                    return
                }

                DispatchQueue.main.async {
                    self.isProcessing = true
                }

                #if canImport(DspRuntimeC)
                guard let handle = self.modelHandle else {
                    continuation.finish()
                    DispatchQueue.main.async { self.isProcessing = false }
                    return
                }

                let callbackContext = UnsafeMutablePointer<AsyncStream<String>.Continuation>.allocate(capacity: 1)
                callbackContext.initialize(to: continuation)

                let callback: @convention(c) (UnsafePointer<CChar>?, UnsafeMutableRawPointer?) -> Bool = { tokenPtr, ctx in
                    guard let tokenPtr = tokenPtr,
                          let ctx = ctx else { return false }
                    let token = String(cString: tokenPtr)
                    let cont = ctx.assumingMemoryBound(to: AsyncStream<String>.Continuation.self).pointee
                    cont.yield(token)
                    return true
                }

                dsp_model_generate(
                    handle,
                    prompt,
                    Int32(maxTokens),
                    callback,
                    callbackContext
                )

                callbackContext.deinitialize(count: 1)
                callbackContext.deallocate()
                continuation.finish()
                #else
                // Stub mode: simulate token generation
                let stubResponse = self.generateStubResponse(for: prompt)
                let words = stubResponse.split(separator: " ")
                for word in words {
                    continuation.yield(String(word) + " ")
                    Thread.sleep(forTimeInterval: 0.05)
                }
                continuation.finish()
                #endif

                DispatchQueue.main.async {
                    self.isProcessing = false
                }
            }
        }
    }

    /// Generates embeddings for the given text using the loaded model.
    /// - Parameter text: The input text to embed.
    /// - Returns: An array of Float values representing the embedding vector.
    func embed(text: String) async -> [Float] {
        return await withCheckedContinuation { continuation in
            processingQueue.async { [weak self] in
                guard self != nil else {
                    continuation.resume(returning: [])
                    return
                }

                #if canImport(DspRuntimeC)
                guard let handle = self?.modelHandle else {
                    continuation.resume(returning: [])
                    return
                }

                var length: Int32 = 0
                guard let embeddingPtr = dsp_model_embed(handle, text, &length) else {
                    continuation.resume(returning: [])
                    return
                }

                let buffer = UnsafeBufferPointer(start: embeddingPtr, count: Int(length))
                let embedding = Array(buffer)
                dsp_free_embedding(embeddingPtr)
                continuation.resume(returning: embedding)
                #else
                // Stub mode: return a random normalized embedding vector
                let dims = 384
                var embedding = (0..<dims).map { _ in Float.random(in: -1.0...1.0) }
                let norm = sqrt(embedding.reduce(0) { $0 + $1 * $1 })
                if norm > 0 {
                    embedding = embedding.map { $0 / norm }
                }
                continuation.resume(returning: embedding)
                #endif
            }
        }
    }

    private func generateStubResponse(for prompt: String) -> String {
        let responses = [
            "This is a simulated response from the on-device model. In production, the SameDiff native library would generate actual text based on your prompt.",
            "The local model is running in stub mode. Connect the DspRuntimeC library to enable real inference on device.",
            "I am processing your request locally. The model \(AppConfig.defaultModelId) would normally handle this generation task."
        ]
        return responses[abs(prompt.hashValue) % responses.count]
    }
}
