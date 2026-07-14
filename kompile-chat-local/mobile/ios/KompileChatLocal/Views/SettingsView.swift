import SwiftUI
import UniformTypeIdentifiers

/// Settings screen — remote endpoint, local model path, kgraph file importer,
/// and generation parameters.
struct SettingsView: View {

    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var inferenceRouter: InferenceRouter
    @EnvironmentObject var graphReasoningService: GraphReasoningService

    @State private var showKgraphPicker: Bool = false
    @State private var showModelPicker: Bool = false
    @State private var showResetAlert: Bool = false
    @State private var kgraphPickerError: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                remoteSection
                localModelSection
                graphSection
                generationSection
                aboutSection
                resetSection
            }
            .navigationTitle("Settings")
            .alert("Reset Settings", isPresented: $showResetAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Reset", role: .destructive) {
                    resetDefaults()
                }
            } message: {
                Text("This will clear all saved settings. Cannot be undone.")
            }
            .sheet(isPresented: $showKgraphPicker) {
                DocumentPicker(
                    allowedTypes: [UTType(filenameExtension: "kgraph") ?? .data],
                    onPick: { url in
                        importKgraph(url: url)
                    },
                    onError: { err in
                        kgraphPickerError = err
                    }
                )
            }
            .sheet(isPresented: $showModelPicker) {
                DocumentPicker(
                    allowedTypes: [
                        UTType(filenameExtension: "gguf") ?? .data,
                        UTType(filenameExtension: "safetensors") ?? .data
                    ],
                    onPick: { url in
                        importModelFile(url: url)
                    },
                    onError: { _ in }
                )
            }
        }
    }

    // MARK: - Sections

    private var remoteSection: some View {
        Section {
            TextField("Base URL (no trailing slash)", text: $appSettings.remoteBaseUrl)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                .keyboardType(.URL)
            TextField("Model ID", text: $appSettings.remoteModel)
                .autocapitalization(.none)
                .disableAutocorrection(true)
            SecureField("API Key (leave blank for local servers)", text: $appSettings.remoteApiKey)
        } header: {
            Text("Remote Endpoint")
        } footer: {
            Text("Any OpenAI-compatible endpoint — Ollama, kompile-local, OpenAI, etc.")
        }
    }

    private var localModelSection: some View {
        Section {
            LabeledContent("Model file") {
                if appSettings.hasLocalModel {
                    Text(URL(fileURLWithPath: appSettings.localModelPath).lastPathComponent)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                } else {
                    Text("Not set")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Button("Import model file (.gguf / .safetensors)") {
                showModelPicker = true
            }
            if appSettings.hasLocalModel {
                Button("Clear model path", role: .destructive) {
                    appSettings.localModelPath = ""
                    inferenceRouter.configure(settings: appSettings)
                }
            }
        } header: {
            Text("Local Model (SDX LLM)")
        } footer: {
            Text("Requires the SdxLlm xcframework. The model file is copied into the app container.")
        }
    }

    private var graphSection: some View {
        Section {
            LabeledContent("Status") {
                HStack(spacing: 6) {
                    Circle()
                        .fill(graphReasoningService.isLoaded ? Color.green : Color.secondary)
                        .frame(width: 8, height: 8)
                    Text(graphReasoningService.isLoaded ? "Loaded" : "Not loaded")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            if let err = graphReasoningService.loadError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if let err = kgraphPickerError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            Button("Import .kgraph file") {
                showKgraphPicker = true
            }
            if appSettings.hasKgraph {
                LabeledContent("File") {
                    Text(URL(fileURLWithPath: appSettings.kgraphPath).lastPathComponent)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Button("Unload graph", role: .destructive) {
                    graphReasoningService.closeGraph()
                    appSettings.kgraphPath = ""
                }
            }
            if !graphReasoningService.isAvailable {
                Label("KompileReasoning library not linked", systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Knowledge Graph")
        } footer: {
            Text("Import a .kgraph file to enable graph-aware chat. Requires the KompileReasoning xcframework to reason locally; without it, graph tools return an error JSON.")
        }
    }

    private var generationSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Temperature")
                    Spacer()
                    Text(String(format: "%.2f", appSettings.temperature))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(value: $appSettings.temperature, in: 0.0...2.0, step: 0.05)
            }

            Stepper("Max tokens: \(appSettings.maxTokens)",
                    value: $appSettings.maxTokens, in: 64...4096, step: 64)
                .font(.subheadline)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Top-p")
                    Spacer()
                    Text(String(format: "%.2f", appSettings.topP))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                Slider(value: $appSettings.topP, in: 0.0...1.0, step: 0.05)
            }

            Stepper("Top-k: \(appSettings.topK)",
                    value: $appSettings.topK, in: 1...200, step: 1)
                .font(.subheadline)

            Stepper("Max tool rounds: \(appSettings.maxToolRounds)",
                    value: $appSettings.maxToolRounds, in: 1...8, step: 1)
                .font(.subheadline)
        } header: {
            Text("Generation Parameters")
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("Active route") {
                Text(inferenceRouter.activeRoute.rawValue)
                    .foregroundStyle(routeColor)
                    .font(.subheadline.weight(.semibold))
            }
            LabeledContent("KompileReasoning") {
                Text(graphReasoningService.isAvailable ? "Available" : "Not linked")
                    .foregroundStyle(graphReasoningService.isAvailable ? .green : .secondary)
                    .font(.subheadline)
            }
            LabeledContent("SdxLlm") {
                Text(inferenceRouter.sdxService.isAvailable ? "Available" : "Not linked")
                    .foregroundStyle(inferenceRouter.sdxService.isAvailable ? .green : .secondary)
                    .font(.subheadline)
            }
        } header: {
            Text("Status")
        }
    }

    private var resetSection: some View {
        Section {
            Button(role: .destructive) {
                showResetAlert = true
            } label: {
                HStack {
                    Spacer()
                    Text("Reset All Settings")
                    Spacer()
                }
            }
        }
    }

    // MARK: - Helpers

    private var routeColor: Color {
        switch inferenceRouter.activeRoute {
        case .local: return .green
        case .remote: return .blue
        case .unavailable: return .red
        }
    }

    private func importKgraph(url: URL) {
        // Copy into the app's Documents directory so the sandbox allows reading it.
        let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(url.lastPathComponent)
        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: url, to: dest)
            appSettings.kgraphPath = dest.path
            graphReasoningService.openGraph(at: dest.path)
        } catch {
            kgraphPickerError = "Failed to import: \(error.localizedDescription)"
        }
    }

    private func importModelFile(url: URL) {
        let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(url.lastPathComponent)
        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: url, to: dest)
            appSettings.localModelPath = dest.path
            inferenceRouter.configure(settings: appSettings)
        } catch {}
    }

    private func resetDefaults() {
        appSettings.remoteBaseUrl = "http://localhost:8080"
        appSettings.remoteModel = "gpt-4o-mini"
        appSettings.remoteApiKey = ""
        appSettings.localModelPath = ""
        appSettings.kgraphPath = ""
        appSettings.temperature = 0.7
        appSettings.maxTokens = 1024
        appSettings.topP = 0.9
        appSettings.topK = 40
        appSettings.maxToolRounds = 4
        graphReasoningService.closeGraph()
        inferenceRouter.configure(settings: appSettings)
    }
}

// MARK: - UIDocumentPickerViewController wrapper

private struct DocumentPicker: UIViewControllerRepresentable {

    let allowedTypes: [UTType]
    let onPick: (URL) -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick, onError: onError) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: allowedTypes)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        let onError: (String) -> Void
        init(onPick: @escaping (URL) -> Void, onError: @escaping (String) -> Void) {
            self.onPick = onPick; self.onError = onError
        }
        func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            // Start security-scoped access
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            onPick(url)
        }
        func documentPickerWasCancelled(_ c: UIDocumentPickerViewController) {}
    }
}

#Preview {
    SettingsView()
        .environmentObject(AppSettings())
        .environmentObject(InferenceRouter())
        .environmentObject(GraphReasoningService())
}
