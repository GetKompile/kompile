import Foundation

/// Hybrid inference service that combines local embedding generation
/// (via SdxInferenceService) with remote text generation (via RemoteLLMService).
/// Embeddings are computed on-device for privacy; generation uses a remote API
/// for higher quality responses.
final class HybridInferenceService: ObservableObject {
    @Published var isProcessing: Bool = false

    private let localService: SdxInferenceService
    private let remoteService: RemoteLLMService
    private let vectorService: VectorSearchService

    init(
        localService: SdxInferenceService,
        remoteService: RemoteLLMService,
        vectorService: VectorSearchService = VectorSearchService()
    ) {
        self.localService = localService
        self.remoteService = remoteService
        self.vectorService = vectorService
    }

    /// Queries the hybrid pipeline: embeds the prompt locally, retrieves relevant
    /// context from the vector store, then sends the augmented prompt to the remote LLM.
    /// - Parameters:
    ///   - prompt: The user's query text.
    ///   - context: Optional additional context to include.
    /// - Returns: An AsyncStream of generated tokens from the remote LLM.
    func query(prompt: String, context: String) async -> AsyncStream<String> {
        await MainActor.run { isProcessing = true }

        // Step 1: Generate embedding locally for vector search
        let queryEmbedding = await localService.embed(text: prompt)

        // Step 2: Search vector store for relevant context
        var retrievedContext = context
        if !queryEmbedding.isEmpty {
            let topK = 5
            let relevantChunks = await vectorService.search(queryEmbedding: queryEmbedding, topK: topK)
            if !relevantChunks.isEmpty {
                let chunksText = relevantChunks.enumerated().map { index, chunk in
                    "[\(index + 1)] \(chunk)"
                }.joined(separator: "\n\n")
                retrievedContext = """
                Relevant context from documents:
                \(chunksText)

                \(context.isEmpty ? "" : "Additional context: \(context)")
                """
            }
        }

        // Step 3: Build augmented messages for remote generation
        var messages: [RemoteLLMService.APIMessage] = []

        let systemPrompt = """
        You are a helpful assistant. Use the provided context to answer the user's question accurately. \
        If the context doesn't contain relevant information, say so and answer to the best of your ability.
        """
        messages.append(RemoteLLMService.APIMessage(role: "system", content: systemPrompt))

        if !retrievedContext.isEmpty {
            messages.append(RemoteLLMService.APIMessage(role: "system", content: retrievedContext))
        }

        messages.append(RemoteLLMService.APIMessage(role: "user", content: prompt))

        // Step 4: Stream response from remote LLM
        let stream = await remoteService.generate(messages: messages)

        await MainActor.run { isProcessing = false }

        return stream
    }

    /// Embeds text locally without calling the remote service.
    /// Useful for indexing documents on-device.
    /// - Parameter text: The text to embed.
    /// - Returns: The embedding vector.
    func embedLocally(text: String) async -> [Float] {
        return await localService.embed(text: text)
    }
}
