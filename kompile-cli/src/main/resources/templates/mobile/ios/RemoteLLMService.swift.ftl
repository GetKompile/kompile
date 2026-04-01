import Foundation

/// Service for communicating with an OpenAI-compatible remote LLM API.
/// Supports streaming Server-Sent Events (SSE) for real-time token delivery.
final class RemoteLLMService: ObservableObject {
    @Published var isProcessing: Bool = false
    @Published var lastError: String? = nil

    struct APIMessage: Codable {
        let role: String
        let content: String
    }

    private var apiKey: String = AppConfig.defaultApiKey
    private var baseURL: String = AppConfig.apiBaseURL
    private var model: String = "gpt-3.5-turbo"

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config)
    }()

    /// Configures the service with API credentials.
    func configure(apiKey: String, baseURL: String? = nil, model: String? = nil) {
        self.apiKey = apiKey
        if let baseURL = baseURL { self.baseURL = baseURL }
        if let model = model { self.model = model }
    }

    /// Generates a streaming response from the remote LLM.
    /// - Parameter messages: Array of conversation messages.
    /// - Returns: An AsyncStream yielding tokens as they arrive via SSE.
    func generate(messages: [APIMessage]) async -> AsyncStream<String> {
        AsyncStream { continuation in
            Task {
                await MainActor.run { self.isProcessing = true; self.lastError = nil }

                do {
                    let request = try buildRequest(messages: messages, stream: true)
                    let (bytes, response) = try await session.bytes(for: request)

                    guard let httpResponse = response as? HTTPURLResponse else {
                        throw LLMError.invalidResponse
                    }

                    guard httpResponse.statusCode == 200 else {
                        throw LLMError.httpError(statusCode: httpResponse.statusCode)
                    }

                    for try await line in bytes.lines {
                        guard line.hasPrefix("data: ") else { continue }
                        let data = String(line.dropFirst(6))

                        if data == "[DONE]" { break }

                        guard let jsonData = data.data(using: .utf8),
                              let chunk = try? JSONDecoder().decode(StreamChunk.self, from: jsonData),
                              let content = chunk.choices.first?.delta.content else {
                            continue
                        }

                        continuation.yield(content)
                    }
                } catch {
                    await MainActor.run {
                        self.lastError = error.localizedDescription
                    }
                }

                continuation.finish()
                await MainActor.run { self.isProcessing = false }
            }
        }
    }

    /// Generates a non-streaming response from the remote LLM.
    /// - Parameter messages: Array of conversation messages.
    /// - Returns: The complete response text.
    func generateComplete(messages: [APIMessage]) async throws -> String {
        await MainActor.run { isProcessing = true; lastError = nil }
        defer { Task { @MainActor in isProcessing = false } }

        let request = try buildRequest(messages: messages, stream: false)
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }

        guard httpResponse.statusCode == 200 else {
            let body = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw LLMError.apiError(message: body)
        }

        let completionResponse = try JSONDecoder().decode(CompletionResponse.self, from: data)
        guard let content = completionResponse.choices.first?.message.content else {
            throw LLMError.emptyResponse
        }

        return content
    }

    private func buildRequest(messages: [APIMessage], stream: Bool) throws -> URLRequest {
        guard let url = URL(string: "\(baseURL)/chat/completions") else {
            throw LLMError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = ChatRequest(
            model: model,
            messages: messages,
            stream: stream,
            max_tokens: 1024
        )

        request.httpBody = try JSONEncoder().encode(body)
        return request
    }
}

// MARK: - Request/Response Models

extension RemoteLLMService {
    struct ChatRequest: Codable {
        let model: String
        let messages: [APIMessage]
        let stream: Bool
        let max_tokens: Int
    }

    struct StreamChunk: Codable {
        let choices: [StreamChoice]
    }

    struct StreamChoice: Codable {
        let delta: DeltaContent
        let finish_reason: String?
    }

    struct DeltaContent: Codable {
        let content: String?
        let role: String?
    }

    struct CompletionResponse: Codable {
        let choices: [CompletionChoice]
    }

    struct CompletionChoice: Codable {
        let message: APIMessage
        let finish_reason: String?
    }
}

// MARK: - Errors

extension RemoteLLMService {
    enum LLMError: LocalizedError {
        case invalidURL
        case invalidResponse
        case httpError(statusCode: Int)
        case apiError(message: String)
        case emptyResponse

        var errorDescription: String? {
            switch self {
            case .invalidURL:
                return "Invalid API URL configuration."
            case .invalidResponse:
                return "Received an invalid response from the server."
            case .httpError(let code):
                return "HTTP error \(code)."
            case .apiError(let message):
                return "API error: \(message)"
            case .emptyResponse:
                return "The API returned an empty response."
            }
        }
    }
}
