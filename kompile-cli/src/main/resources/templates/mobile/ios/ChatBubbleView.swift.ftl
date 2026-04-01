import SwiftUI

struct ChatBubbleView: View {
    let message: ChatMessage
    @State private var showSources: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if message.role == .sender {
                Spacer(minLength: 48)
            }

            VStack(alignment: message.role == .sender ? .trailing : .leading, spacing: 6) {
                roleLabel

                Text(message.content)
                    .font(.body)
                    .foregroundColor(textColor)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(bubbleBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

                if let sources = message.sources, !sources.isEmpty {
                    sourcesSection(sources: sources)
                }

                timestampLabel
            }

            if message.role != .sender {
                Spacer(minLength: 48)
            }
        }
        .padding(.horizontal, 16)
    }

    private var roleLabel: some View {
        Group {
            if message.role == .system {
                Label("System", systemImage: "info.circle")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var timestampLabel: some View {
        Text(message.timestamp, style: .time)
            .font(.caption2)
            .foregroundStyle(.tertiary)
    }

    private var textColor: Color {
        switch message.role {
        case .sender:
            return .white
        case .assistant:
            return .primary
        case .system:
            return .secondary
        }
    }

    private var bubbleBackground: some ShapeStyle {
        switch message.role {
        case .sender:
            return Color.blue
        case .assistant:
            return Color(.systemGray5)
        case .system:
            return Color(.systemGray6)
        }
    }

    @ViewBuilder
    private func sourcesSection(sources: [SourceReference]) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                showSources.toggle()
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "doc.text.magnifyingglass")
                    .font(.caption)
                Text("\(sources.count) source\(sources.count == 1 ? "" : "s")")
                    .font(.caption)
                Image(systemName: showSources ? "chevron.up" : "chevron.down")
                    .font(.caption2)
            }
            .foregroundStyle(.blue)
        }
        .buttonStyle(.plain)

        if showSources {
            VStack(spacing: 8) {
                ForEach(sources) { source in
                    SourceCardView(source: source)
                }
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

struct SourceCardView: View {
    let source: SourceReference

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "doc.fill")
                    .font(.caption)
                    .foregroundStyle(.blue)
                Text(source.documentName)
                    .font(.caption)
                    .fontWeight(.medium)
                    .lineLimit(1)
                Spacer()
                Text(String(format: "%.0f%%", source.relevanceScore * 100))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Text(source.snippet)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(3)
        }
        .padding(10)
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color(.systemGray4), lineWidth: 0.5)
        )
    }
}

#Preview {
    VStack(spacing: 12) {
        ChatBubbleView(message: .userMessage("Hello, how can I use this app?"))
        ChatBubbleView(message: .assistantMessage(
            "You can ask questions and I will respond using the loaded knowledge base.",
            sources: [
                SourceReference(documentName: "guide.pdf", chunkIndex: 3, snippet: "The application supports local and remote inference modes...", relevanceScore: 0.92)
            ]
        ))
        ChatBubbleView(message: .systemMessage("Session started"))
    }
    .padding()
}
