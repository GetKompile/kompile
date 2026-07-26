import SwiftUI
import UniformTypeIdentifiers

/// Main chat screen.
///
/// Features:
///   - Message bubble list with auto-scroll to bottom.
///   - Collapsible ToolRoundCard rows for each tool dispatch.
///   - LOCAL / MODEL REQUIRED route badge in the nav bar.
///   - Graph-loaded banner showing OVERVIEW summary when a .kgraph is open.
///   - UIDocumentPicker import for .kgraph and model files (via Settings nav link).
struct ChatView: View {

    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var inferenceRouter: InferenceRouter
    @EnvironmentObject var graphReasoningService: GraphReasoningService

    // MARK: - State

    @State private var messages: [ChatMessage] = []
    @State private var toolRoundsByTurn: [UUID: [ToolRound]] = [:]
    @State private var inputText: String = ""
    @State private var isGenerating: Bool = false
    @State private var errorMessage: String? = nil

    // MARK: - ChatEngine (built lazily after router is configured)

    @State private var chatEngine: ChatEngine? = nil

    // MARK: - Body

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Graph banner
                if graphReasoningService.isLoaded, let overview = graphReasoningService.overviewSummary {
                    graphBannerView(overview: overview)
                }

                // Error banner
                if let err = errorMessage {
                    errorBannerView(message: err)
                }

                // Message list
                messageListView

                Divider()

                // Input row
                inputRowView
            }
            .navigationTitle("Kompile Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    routeBadgeView
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        clearHistory()
                    } label: {
                        Image(systemName: "trash")
                    }
                    .disabled(messages.isEmpty || isGenerating)
                }
            }
        }
        .onAppear {
            ensureChatEngine()
        }
    }

    // MARK: - Sub-views

    private var messageListView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if messages.isEmpty {
                        emptyStateView
                    } else {
                        ForEach(messages) { msg in
                            if msg.role != .system && msg.role != .toolResult {
                                messageBubble(msg)
                                // Show tool rounds after an assistant turn that had them
                                if msg.role == .assistant,
                                   let rounds = toolRoundsByTurn[msg.id],
                                   !rounds.isEmpty {
                                    ForEach(rounds) { round in
                                        ToolRoundCard(round: round)
                                            .padding(.horizontal, 16)
                                    }
                                }
                            }
                        }
                        if isGenerating {
                            typingIndicatorView
                                .id("typing")
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .onChange(of: messages.count) { _ in
                withAnimation { scrollToLast(proxy: proxy) }
            }
            .onChange(of: isGenerating) { generating in
                if generating {
                    withAnimation { proxy.scrollTo("typing", anchor: .bottom) }
                }
            }
        }
    }

    @ViewBuilder
    private func messageBubble(_ msg: ChatMessage) -> some View {
        let isUser = msg.role == .user
        HStack {
            if isUser { Spacer(minLength: 60) }
            Text(msg.content)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(isUser ? Color.accentColor : Color(.secondarySystemBackground))
                .foregroundStyle(isUser ? Color.white : Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .textSelection(.enabled)
            if !isUser { Spacer(minLength: 60) }
        }
        .padding(.horizontal, 16)
    }

    private var typingIndicatorView: some View {
        HStack {
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { i in
                    Circle()
                        .fill(Color.secondary)
                        .frame(width: 7, height: 7)
                        .opacity(0.5)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            Spacer(minLength: 60)
        }
        .padding(.horizontal, 16)
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Spacer(minLength: 60)
            Image(systemName: "brain.head.profile")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Ask about your knowledge graph")
                .font(.title3.weight(.semibold))
            Text(inferenceRouter.activeRoute == .unavailable
                 ? "Import a compatible local model in Settings to get started."
                 : "Type a message below.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private var inputRowView: some View {
        HStack(spacing: 10) {
            TextField("Message…", text: $inputText, axis: .vertical)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .disabled(isGenerating)

            Button {
                sendMessage()
            } label: {
                Image(systemName: isGenerating ? "stop.circle.fill" : "arrow.up.circle.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isGenerating
                                     ? Color.secondary : Color.accentColor)
            }
            .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isGenerating)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var routeBadgeView: some View {
        let (label, color): (String, Color) = {
            switch inferenceRouter.activeRoute {
            case .local: return ("LOCAL", .green)
            case .unavailable: return ("MODEL REQUIRED", .red)
            }
        }()
        return Text(label)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }

    private func graphBannerView(overview: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "brain")
                .foregroundStyle(.purple)
            Text("Graph loaded")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.purple)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Color.purple.opacity(0.08))
    }

    private func errorBannerView(message: String) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(message)
                .font(.caption)
                .foregroundStyle(.primary)
                .lineLimit(3)
            Spacer()
            Button { errorMessage = nil } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.orange.opacity(0.1))
    }

    // MARK: - Actions

    private func sendMessage() {
        let trimmed = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isGenerating else { return }

        guard let engine = chatEngine else {
            errorMessage = "Chat engine not ready. Check Settings."
            return
        }

        let userMsg = ChatMessage.user(trimmed)
        messages.append(userMsg)
        inputText = ""
        errorMessage = nil
        isGenerating = true

        // Snapshot history excluding the message just added (engine appends it internally)
        let historySnapshot = messages.dropLast().map { $0 }
        let options = GenOptions.from(appSettings)

        Task {
            do {
                let result = try await engine.chat(
                    history: Array(historySnapshot),
                    userInput: trimmed,
                    options: options
                )
                await MainActor.run {
                    let assistantMsg = ChatMessage.assistant(result.answer)
                    messages.append(assistantMsg)
                    if !result.rounds.isEmpty {
                        toolRoundsByTurn[assistantMsg.id] = result.rounds
                    }
                    isGenerating = false
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isGenerating = false
                }
            }
        }
    }

    private func clearHistory() {
        messages = []
        toolRoundsByTurn = [:]
        errorMessage = nil
    }

    private func scrollToLast(proxy: ScrollViewProxy) {
        if isGenerating {
            proxy.scrollTo("typing", anchor: .bottom)
        } else if let lastId = messages.last?.id {
            proxy.scrollTo(lastId, anchor: .bottom)
        }
    }

    private func ensureChatEngine() {
        guard chatEngine == nil else { return }
        chatEngine = ChatEngine(
            router: inferenceRouter,
            graphService: graphReasoningService,
            maxToolRounds: appSettings.maxToolRounds
        )
    }
}

#Preview {
    ChatView()
        .environmentObject(AppSettings())
        .environmentObject(InferenceRouter())
        .environmentObject(GraphReasoningService())
}
