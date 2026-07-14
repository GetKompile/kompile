import Foundation

/// Calls any OpenAI-compatible HTTP endpoint.
/// Mirrors `ai.kompile.chat.local.RemoteChatModel`.
///
/// Supports:
///   - Non-streaming: POST /v1/chat/completions → choices[0].message.content
///   - SSE streaming: yields delta.content tokens via AsyncStream (matches
///     the scaffold's RemoteLLMService SSE pattern).
///
/// The base URL must NOT have a trailing slash.
/// If apiKey is empty, the Authorization header is omitted (works with local
/// endpoints like Ollama or a kompile-local server on port 8080).
final class RemoteChatService: ObservableObject {

    // MARK: - Published state

    @Published var isProcessing: Bool = false
    @Published var lastError: String? = nil

    // MARK: - Configuration (set by InferenceRouter when settings change)

    var baseUrl: String = ""
    var model: String = ""
    var apiKey: String = ""
    var timeoutSeconds: Double = 60

    // MARK: - URLSession

    private lazy var urlSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeoutSeconds
        config.timeoutIntervalForResource = timeoutSeconds * 5
        return URLSession(configuration: config)
    }()

    // MARK: - Availability

    var isConfigured: Bool {
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: - Non-streaming generate

    /// Send a messages array, return the assistant's full text response.
    /// Throws `RemoteChatError` on HTTP or parse failure.
    func generate(messages: [ChatMessage], options: GenOptions) async throws -> String {
        let body = buildRequestBody(messages: messages, options: options, stream: false)
        let request = try buildRequest(body: body)
        let (data, response) = try await urlSession.data(for: request)
        let httpResp = response as? HTTPURLResponse
        let status = httpResp?.statusCode ?? 0
        guard status == 200 else {
            let bodyStr = String(data: data, encoding: .utf8) ?? ""
            throw RemoteChatError.http(status: status, body: bodyStr)
        }
        return try extractContent(from: data)
    }

    // MARK: - SSE streaming generate

    /// Send a messages array, stream back delta tokens via AsyncStream.
    /// Yields each token string as it arrives.  Finishes (does not throw) on error,
    /// publishing the error message to `lastError`.
    func generateStreaming(messages: [ChatMessage], options: GenOptions) -> AsyncStream<String> {
        AsyncStream { continuation in
            Task {
                await MainActor.run { self.isProcessing = true; self.lastError = nil }
                do {
                    let body = self.buildRequestBody(messages: messages, options: options, stream: true)
                    let request = try self.buildRequest(body: body)
                    let (bytes, response) = try await self.urlSession.bytes(for: request)
                    let httpResp = response as? HTTPURLResponse
                    guard let httpResp = httpResp, httpResp.statusCode == 200 else {
                        throw RemoteChatError.http(status: httpResp?.statusCode ?? 0, body: "")
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data: ") else { continue }
                        let payload = String(line.dropFirst(6))
                        if payload == "[DONE]" { break }
                        guard let jsonData = payload.data(using: .utf8),
                              let chunk = try? JSONDecoder().decode(SSEChunk.self, from: jsonData),
                              let content = chunk.choices.first?.delta.content else { continue }
                        continuation.yield(content)
                    }
                } catch {
                    await MainActor.run { self.lastError = error.localizedDescription }
                }
                continuation.finish()
                await MainActor.run { self.isProcessing = false }
            }
        }
    }

    // MARK: - Private helpers

    private func buildRequestBody(messages: [ChatMessage], options: GenOptions, stream: Bool) -> Data {
        var msgArray: [[String: String]] = []
        for m in messages {
            // tool_result role is sent as "user" (OpenAI has no tool_result role in all variants)
            let role = m.role == .toolResult ? "user" : m.role.rawValue
            msgArray.append(["role": role, "content": m.content])
        }
        var body: [String: Any] = [
            "model": model,
            "messages": msgArray,
            "stream": stream,
            "max_tokens": options.maxTokens,
            "temperature": options.temperature
        ]
        // OpenAI-standard optional sampling params
        body["top_p"] = options.topP
        let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
        return data
    }

    private func buildRequest(body: Data) throws -> URLRequest {
        guard !baseUrl.isEmpty, let url = URL(string: baseUrl + "/v1/chat/completions") else {
            throw RemoteChatError.invalidUrl(baseUrl)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !apiKey.isEmpty {
            req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        req.httpBody = body
        return req
    }

    private func extractContent(from data: Data) throws -> String {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = root["choices"] as? [[String: Any]],
              !choices.isEmpty,
              let message = choices[0]["message"] as? [String: Any],
              let content = message["content"] as? String else {
            let raw = String(data: data, encoding: .utf8) ?? ""
            throw RemoteChatError.parseFailure(raw)
        }
        return content
    }
}

// MARK: - SSE chunk models

private extension RemoteChatService {
    struct SSEChunk: Decodable {
        let choices: [SSEChoice]
    }
    struct SSEChoice: Decodable {
        let delta: SSEDelta
        let finish_reason: String?
    }
    struct SSEDelta: Decodable {
        let content: String?
        let role: String?
    }
}

// MARK: - Errors

enum RemoteChatError: LocalizedError {
    case invalidUrl(String)
    case http(status: Int, body: String)
    case parseFailure(String)

    var errorDescription: String? {
        switch self {
        case .invalidUrl(let u): return "Invalid base URL: \(u)"
        case .http(let s, let b): return "HTTP \(s): \(b.prefix(200))"
        case .parseFailure(let r): return "Response parse failure: \(r.prefix(200))"
        }
    }
}
