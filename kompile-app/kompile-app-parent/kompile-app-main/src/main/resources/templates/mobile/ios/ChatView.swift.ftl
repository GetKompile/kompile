import SwiftUI

struct ChatView: View {
    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var chatStorage: ChatStorageService

    @State private var currentSession: ChatSession = ChatSession()
    @State private var inputText: String = ""
    @State private var isGenerating: Bool = false
    @State private var streamedText: String = ""
    @State private var showSessionList: Bool = false
    @State private var scrollToBottom: Bool = false

    @StateObject private var localService = SdxInferenceService()
    @StateObject private var remoteService = RemoteLLMService()

    var body: some View {
        NavigationSplitView {
            SessionListView(
                sessions: chatStorage.sessions,
                selectedSession: $currentSession,
                onNewSession: createNewSession,
                onDelete: deleteSession
            )
            .navigationTitle("Sessions")
        } detail: {
            VStack(spacing: 0) {
                messageListView
                Divider()
                ChatInputView(
                    text: $inputText,
                    isGenerating: isGenerating,
                    onSend: sendMessage
                )
            }
            .navigationTitle(currentSession.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        createNewSession()
                    } label: {
                        Image(systemName: "plus.message")
                    }
                }
            }
        }
        .onAppear {
            loadInitialSession()
        }
    }

    private var messageListView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    if currentSession.messages.isEmpty {
                        emptyStateView
                    } else {
                        ForEach(currentSession.messages) { message in
                            ChatBubbleView(message: message)
                                .id(message.id)
                        }

                        if isGenerating {
                            StreamingTextView(text: streamedText)
                                .id("streaming")
                                .padding(.horizontal)
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .onChange(of: currentSession.messages.count) { _ in
                withAnimation {
                    if let lastId = currentSession.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
            }
            .onChange(of: streamedText) { _ in
                if isGenerating {
                    withAnimation {
                        proxy.scrollTo("streaming", anchor: .bottom)
                    }
                }
            }
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Start a Conversation")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Ask a question or start typing below.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private func loadInitialSession() {
        if let first = chatStorage.sessions.first {
            currentSession = first
        } else {
            let session = ChatSession()
            chatStorage.saveSession(session)
            currentSession = session
        }
    }

    private func createNewSession() {
        let session = ChatSession()
        chatStorage.saveSession(session)
        currentSession = session
    }

    private func deleteSession(_ session: ChatSession) {
        chatStorage.deleteSession(session)
        if currentSession.id == session.id {
            currentSession = chatStorage.sessions.first ?? ChatSession()
        }
    }

    private func sendMessage() {
        let trimmed = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isGenerating else { return }

        let userMessage = ChatMessage.userMessage(trimmed)
        currentSession.addMessage(userMessage)
        chatStorage.saveSession(currentSession)
        inputText = ""

        isGenerating = true
        streamedText = ""

        Task {
            let stream = await generateResponse(for: trimmed)
            var fullResponse = ""

            for await token in stream {
                fullResponse += token
                await MainActor.run {
                    streamedText = fullResponse
                }
            }

            await MainActor.run {
                let assistantMessage = ChatMessage.assistantMessage(fullResponse)
                currentSession.addMessage(assistantMessage)
                chatStorage.saveSession(currentSession)
                isGenerating = false
                streamedText = ""
            }
        }
    }

    private func generateResponse(for prompt: String) async -> AsyncStream<String> {
        switch appSettings.inferenceMode {
        case .local:
            if !localService.isModelLoaded, let path = AppConfig.modelBundlePath {
                await localService.loadModel(path: path)
            }
            return await localService.generate(prompt: prompt, maxTokens: appSettings.maxTokens)

        case .remote:
            remoteService.configure(apiKey: appSettings.apiKey)
            let messages = currentSession.messages.map { msg in
                RemoteLLMService.APIMessage(
                    role: msg.role == .sender ? "user" : msg.role == .assistant ? "assistant" : "system",
                    content: msg.content
                )
            }
            return await remoteService.generate(messages: messages)

        case .hybrid:
            let hybridService = HybridInferenceService(
                localService: localService,
                remoteService: remoteService
            )
            remoteService.configure(apiKey: appSettings.apiKey)
            if !localService.isModelLoaded, let path = AppConfig.modelBundlePath {
                await localService.loadModel(path: path)
            }
            return await hybridService.query(prompt: prompt, context: "")
        }
    }
}

#Preview {
    ChatView()
        .environmentObject(AppSettings())
        .environmentObject(ChatStorageService())
}
