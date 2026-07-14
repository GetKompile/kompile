import SwiftUI

/// Collapsible card showing a single tool round (call + result).
/// Used inside ChatView to surface tool-calling activity.
struct ToolRoundCard: View {

    let round: ToolRound
    @State private var isExpanded: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header row — always visible
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "bolt.fill")
                        .foregroundStyle(.orange)
                        .font(.caption)
                    Text("Tool: \(round.tool)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundStyle(.secondary)
                        .font(.caption2)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .buttonStyle(.plain)

            // Expandable detail
            if isExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 6) {
                    Group {
                        Text("Args")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(prettyJson(round.argsJson))
                            .font(.caption.monospaced())
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                    }
                    Divider()
                    Group {
                        Text("Result")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(prettyJson(round.resultJson))
                            .font(.caption.monospaced())
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                            .lineLimit(20)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Helpers

    private func prettyJson(_ raw: String) -> String {
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: .prettyPrinted),
              let str = String(data: pretty, encoding: .utf8) else {
            return raw
        }
        return str
    }
}
