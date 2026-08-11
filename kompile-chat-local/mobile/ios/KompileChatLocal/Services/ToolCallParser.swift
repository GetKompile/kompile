import Foundation

/// Strict decoder for the canonical structured result returned by SameDiff/SDX.
///
/// This does not parse model output. Thinking blocks, control tokens, and tool-call
/// syntax have already been handled by the imported model before this runs.
struct StructuredChatResponse {

    struct ToolCall {
        let id: String?
        let tool: String
        let args: [String: Any]
    }

    let rawText: String
    let content: String
    let reasoningContent: String
    let toolCalls: [ToolCall]
    let protocolErrors: [String]

    var isProtocolValid: Bool { protocolErrors.isEmpty }

    func toolCallsJson() throws -> String {
        let encoded: [[String: Any]] = toolCalls.map { call in
            var value: [String: Any] = [
                "type": "function",
                "function": [
                    "name": call.tool,
                    "arguments": call.args
                ]
            ]
            if let id = call.id { value["id"] = id }
            return value
        }
        let data = try JSONSerialization.data(withJSONObject: encoded)
        guard let json = String(data: data, encoding: .utf8) else {
            throw StructuredChatTransportError.invalidUTF8
        }
        return json
    }

    static func decode(_ json: String) throws -> StructuredChatResponse {
        guard let data = json.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw StructuredChatTransportError.invalidEnvelope
        }
        let required = ["rawText", "content", "toolCalls", "protocolErrors"]
        guard required.allSatisfy({ root.keys.contains($0) }),
              let rawText = root["rawText"] as? String,
              let content = root["content"] as? String,
              let calls = root["toolCalls"] as? [[String: Any]],
              let errors = root["protocolErrors"] as? [String] else {
            throw StructuredChatTransportError.invalidEnvelope
        }

        let toolCalls = try calls.map { value -> ToolCall in
            guard let name = value["name"] as? String,
                  !name.isEmpty,
                  let arguments = value["arguments"] as? [String: Any] else {
                throw StructuredChatTransportError.invalidToolCall
            }
            return ToolCall(
                id: value["id"] as? String,
                tool: name,
                args: arguments
            )
        }
        return StructuredChatResponse(
            rawText: rawText,
            content: content,
            reasoningContent: root["reasoningContent"] as? String ?? "",
            toolCalls: toolCalls,
            protocolErrors: errors
        )
    }
}

/// Source-compatible adapter for older callers. New code consumes the canonical
/// structured response directly and never invokes a model-text parser here.
@available(*, deprecated, message: "Use StructuredChatResponse.decode(_:)")
enum ToolCallParser {
    typealias Result = StructuredChatResponse
    typealias ToolCall = StructuredChatResponse.ToolCall

    static func parseStructuredResult(_ json: String) throws -> StructuredChatResponse {
        try StructuredChatResponse.decode(json)
    }
}

enum StructuredChatTransportError: LocalizedError {
    case invalidEnvelope
    case invalidToolCall
    case invalidUTF8

    var errorDescription: String? {
        switch self {
        case .invalidEnvelope:
            return "SDX returned an invalid structured chat result."
        case .invalidToolCall:
            return "SDX returned an invalid structured tool call."
        case .invalidUTF8:
            return "Could not encode structured tool-call history."
        }
    }
}
