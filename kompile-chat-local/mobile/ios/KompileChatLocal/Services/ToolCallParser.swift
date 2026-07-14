import Foundation

/// Parses a potential tool-call JSON object from raw model output.
///
/// Direct port of `ai.kompile.chat.local.ToolCallParser`.
///
/// Two extraction strategies are tried in order:
/// 1. Fenced block: find ```json … ```
/// 2. Bare object: find the first '{' and last '}' and try that substring.
///
/// A valid tool call has exactly the keys "tool" (String) and "args" (Dictionary).
enum ToolCallParser {

    struct ToolCall {
        let tool: String
        let args: [String: Any]
    }

    /// Attempt to parse a tool call from the model's raw output.
    /// Returns nil if no valid tool-call JSON was found.
    static func parse(_ modelOutput: String) -> ToolCall? {
        let trimmed = modelOutput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Strategy 1: fenced ```json ... ``` block
        if let fenced = extractFencedBlock(trimmed) {
            if let result = tryParse(fenced) { return result }
        }

        // Strategy 2: first '{' … last '}'
        if let firstBrace = trimmed.firstIndex(of: "{"),
           let lastBrace = trimmed.lastIndex(of: "}"),
           firstBrace <= lastBrace {
            let candidate = String(trimmed[firstBrace...lastBrace])
            if let result = tryParse(candidate) { return result }
        }

        return nil
    }

    // MARK: - Private helpers

    /// Extract content of a ```json ... ``` fenced block, or nil if none found.
    private static func extractFencedBlock(_ text: String) -> String? {
        let fence = "```json"
        guard let fenceRange = text.range(of: fence) else { return nil }
        var contentStart = fenceRange.upperBound
        // Skip optional newline immediately after the opening fence (matches Java logic)
        if contentStart < text.endIndex && text[contentStart] == "\n" {
            contentStart = text.index(after: contentStart)
        }
        guard let closeRange = text.range(of: "```", range: contentStart..<text.endIndex) else {
            return nil
        }
        return String(text[contentStart..<closeRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Try to parse a candidate string as a tool-call JSON object.
    private static func tryParse(_ candidate: String) -> ToolCall? {
        let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let data = trimmed.data(using: .utf8) else { return nil }
        guard let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        guard let tool = parsed["tool"] as? String, !tool.isEmpty else { return nil }
        let args = parsed["args"] as? [String: Any] ?? [:]
        return ToolCall(tool: tool, args: args)
    }
}
