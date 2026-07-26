import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct SettingsView: View {
    @EnvironmentObject var appSettings: AppSettings
    @EnvironmentObject var inferenceRouter: InferenceRouter
    @EnvironmentObject var graphReasoningService: GraphReasoningService
    @StateObject private var diagnostics = ImportDiagnostics.shared

    @State private var showKgraphPicker = false
    @State private var showSdzPicker = false
    @State private var showManualPicker = false
    @State private var showResetAlert = false
    @State private var importError: String?
    @State private var kgraphPickerError: String?
    @State private var isImporting = false

    // Staging source values are intentionally transient view state.
    @State private var stagingBase = ""
    @State private var huggingFaceReference = ""
    @State private var stagingComponents = ModelStagingHandoff.ComponentBundle()
    @State private var showAdvancedStaging = false

    var body: some View {
        NavigationStack {
            Form {
                localModelSection
                stagingSection
                importLogSection
                graphSection
                generationSection
                aboutSection
                resetSection
            }
            .navigationTitle("Settings")
            .onAppear {
                stagingBase = appSettings.modelStagingBaseUrl
            }
            .alert("Reset Settings", isPresented: $showResetAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Reset", role: .destructive) { resetDefaults() }
            } message: {
                Text("This clears model, graph, staging, and generation settings. Imported files and the separate import log remain available.")
            }
            .sheet(isPresented: $showKgraphPicker) {
                DocumentPicker(
                    allowedTypes: [UTType(filenameExtension: "kgraph") ?? .data],
                    allowsMultipleSelection: false,
                    onPick: { urls in
                        if let url = urls.first { await importKgraph(url: url) }
                    },
                    onError: { kgraphPickerError = $0 }
                )
            }
            .sheet(isPresented: $showSdzPicker) {
                DocumentPicker(
                    allowedTypes: [UTType(filenameExtension: "sdz") ?? .data],
                    allowsMultipleSelection: false,
                    onPick: { urls in
                        if let url = urls.first { await importCanonicalSdz(url: url) }
                    },
                    onError: { importError = $0 }
                )
            }
            .sheet(isPresented: $showManualPicker) {
                DocumentPicker(
                    allowedTypes: [
                        UTType(filenameExtension: "gguf") ?? .data,
                        UTType(filenameExtension: "ggml") ?? .data,
                        .json,
                        UTType(filenameExtension: "jinja") ?? .plainText
                    ],
                    allowsMultipleSelection: true,
                    onPick: { urls in await importManualComponents(urls: urls) },
                    onError: { importError = $0 }
                )
            }
        }
    }

    private var localModelSection: some View {
        Section {
            LabeledContent("Status") {
                HStack(spacing: 6) {
                    Circle()
                        .fill(inferenceRouter.activeRoute == .local ? Color.green : Color.secondary)
                        .frame(width: 8, height: 8)
                    Text(inferenceRouter.activeRoute.rawValue)
                        .font(.subheadline.weight(.semibold))
                }
            }
            LabeledContent("Accelerator target") {
                Text(SdxBuildConfiguration.targetProfile)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            if let bundle = appSettings.configuredModelBundle {
                LabeledContent("Model") {
                    Text(URL(fileURLWithPath: bundle.modelPath).lastPathComponent)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                LabeledContent("Tokenizer") {
                    Text(URL(fileURLWithPath: bundle.tokenizerPath).lastPathComponent)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                LabeledContent("Source") {
                    Text(bundle.sourceKind == .canonicalSdz ? "Canonical SDZ" : "Manual components")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else if !appSettings.localModelPath.isEmpty {
                Label(
                    "A legacy/incomplete model path exists. Reimport it with tokenizer and configuration files.",
                    systemImage: "exclamationmark.triangle"
                )
                .font(.caption)
                .foregroundStyle(.orange)
            }

            if let error = inferenceRouter.sdxService.loadError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if let importError {
                Text(importError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            if isImporting {
                HStack {
                    ProgressView()
                    Text("Validating and installing model…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Button("Import complete compiled SDZ") {
                showSdzPicker = true
            }
            .disabled(isImporting || !inferenceRouter.sdxService.isAvailable)

            Button("Import manual GGUF/GGML components") {
                showManualPicker = true
            }
            .disabled(isImporting)

            if appSettings.hasLocalModel || !appSettings.localModelPath.isEmpty {
                Button("Clear active model", role: .destructive) {
                    appSettings.clearLocalModel()
                    inferenceRouter.configure(settings: appSettings)
                }
            }
        } header: {
            Text("Local Model")
        } footer: {
            Text(
                "Preferred: import a complete .sdz compiled for this accelerator. Manual testing selects one GGUF/GGML plus tokenizer.json, tokenizer_config.json or chat_template.jinja, and config.json or text-generation.json in one picker. generation_config.json is optional."
            )
        }
    }

    private var stagingSection: some View {
        Section {
            TextField("https://staging.example", text: $stagingBase)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

            TextField("owner/repository or Hugging Face URL", text: $huggingFaceReference)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Open Hugging Face import in Safari") {
                openStaging(huggingFace: huggingFaceReference, components: nil)
            }
            .disabled(huggingFaceReference.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            DisclosureGroup("Advanced component URLs", isExpanded: $showAdvancedStaging) {
                stagingURLField("GGUF/GGML model URL", text: $stagingComponents.modelURL)
                stagingURLField("tokenizer.json URL", text: $stagingComponents.tokenizerURL)
                stagingURLField(
                    "tokenizer_config.json URL",
                    text: $stagingComponents.tokenizerConfigURL
                )
                stagingURLField("chat_template.jinja URL", text: $stagingComponents.chatTemplateURL)
                stagingURLField("config.json URL", text: $stagingComponents.modelConfigURL)
                stagingURLField(
                    "text-generation.json URL",
                    text: $stagingComponents.textGenerationURL
                )
                stagingURLField(
                    "generation_config.json URL (optional)",
                    text: $stagingComponents.generationConfigURL
                )
                Button("Open component import in Safari") {
                    openStaging(huggingFace: nil, components: stagingComponents)
                }
            }
        } header: {
            Text("Kompile Model Staging")
        } footer: {
            Text(
                "The app opens Safari and has no HTTP client. Repository and component values live only in the URL fragment and are cleared after launch. Only the credential-free staging base is saved."
            )
        }
    }

    @ViewBuilder
    private func stagingURLField(_ title: String, text: Binding<String>) -> some View {
        TextField(title, text: text)
            .font(.caption)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
    }

    private var importLogSection: some View {
        Section {
            NavigationLink {
                ImportDiagnosticLogView(diagnostics: diagnostics)
            } label: {
                LabeledContent("Import log") {
                    Text("\(diagnostics.entries.count)")
                        .foregroundStyle(.secondary)
                }
            }
        } footer: {
            Text("Errors are bounded, sanitized, and stored separately from app settings.")
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
            if let error = graphReasoningService.loadError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            if let kgraphPickerError {
                Text(kgraphPickerError).font(.caption).foregroundStyle(.red)
            }
            Button("Import .kgraph file") { showKgraphPicker = true }
            if appSettings.hasKgraph {
                LabeledContent("File") {
                    Text(URL(fileURLWithPath: appSettings.kgraphPath).lastPathComponent)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Button("Unload graph", role: .destructive) {
                    graphReasoningService.closeGraph()
                    appSettings.kgraphPath = ""
                }
            }
            if !graphReasoningService.isAvailable {
                Label(
                    "KompileReasoning library not linked",
                    systemImage: "exclamationmark.triangle"
                )
                .font(.caption)
                .foregroundStyle(.orange)
            }
        } header: {
            Text("Knowledge Graph")
        } footer: {
            Text("Import a .kgraph file to enable fully local graph-aware chat.")
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
            Stepper(
                "Max tokens: \(appSettings.maxTokens)",
                value: $appSettings.maxTokens,
                in: 64...4096,
                step: 64
            )
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
            Stepper("Top-k: \(appSettings.topK)", value: $appSettings.topK, in: 1...200)
            Stepper(
                "Max tool rounds: \(appSettings.maxToolRounds)",
                value: $appSettings.maxToolRounds,
                in: 1...8
            )
        } header: {
            Text("Generation Parameters")
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("Active route") {
                Text(inferenceRouter.activeRoute.rawValue)
                    .foregroundStyle(inferenceRouter.activeRoute == .local ? .green : .red)
                    .font(.subheadline.weight(.semibold))
            }
            LabeledContent("SdxLlm ABI") {
                Text(
                    inferenceRouter.sdxService.isAvailable
                        ? "v\(inferenceRouter.sdxService.abiVersion)"
                        : "Not linked / incompatible"
                )
                .foregroundStyle(inferenceRouter.sdxService.isAvailable ? .green : .secondary)
            }
            LabeledContent("KompileReasoning") {
                Text(graphReasoningService.isAvailable ? "Available" : "Not linked")
                    .foregroundStyle(graphReasoningService.isAvailable ? .green : .secondary)
            }
        } header: {
            Text("Runtime")
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

    @MainActor
    private func importCanonicalSdz(url: URL) async {
        importError = nil
        isImporting = true
        var copiedRoot: URL?
        do {
            let store = try ModelImportStore()
            let copied = try await Task.detached {
                try store.copyCanonicalArchive(from: url)
            }.value
            copiedRoot = copied.installRoot
            let cache = try store.modelCacheDirectory
            let resolved = try await inferenceRouter.sdxService.resolveModelBundle(
                sourcePath: copied.archivePath,
                targetProfile: SdxBuildConfiguration.targetProfile,
                cacheDirectory: cache.path
            )
            let bundle = try resolved.localBundle().validated()
            let manifest = try store.persistActiveManifest(bundle)
            appSettings.applyLocalModel(bundle, manifestPath: manifest.path)
            inferenceRouter.configure(settings: appSettings)
            ImportDiagnostics.shared.record(
                phase: "canonical-sdz",
                message: "Installed complete SDZ for \(bundle.targetProfile).",
                remediation: "None."
            )
        } catch {
            if let copiedRoot, let store = try? ModelImportStore() {
                store.removeInstallRoot(copiedRoot)
            }
            importError = error.localizedDescription
            ImportDiagnostics.shared.record(
                phase: "canonical-sdz",
                message: error.localizedDescription,
                remediation: "Restage the model for \(SdxBuildConfiguration.targetProfile) with tokenizer and text-generation assets, then import the new SDZ."
            )
        }
        isImporting = false
    }

    @MainActor
    private func importManualComponents(urls: [URL]) async {
        importError = nil
        isImporting = true
        var installedRoot: URL?
        do {
            let target = SdxBuildConfiguration.targetProfile
            let bundle = try await Task.detached {
                let store = try ModelImportStore()
                return try store.installManualComponents(from: urls, targetProfile: target)
            }.value
            installedRoot = URL(fileURLWithPath: bundle.modelPath).deletingLastPathComponent()
            let store = try ModelImportStore()
            let manifest = try store.persistActiveManifest(bundle)
            appSettings.applyLocalModel(bundle, manifestPath: manifest.path)
            inferenceRouter.configure(settings: appSettings)
            installedRoot = nil
            ImportDiagnostics.shared.record(
                phase: "manual-components",
                message: "Installed GGUF/GGML model with tokenizer and chat metadata.",
                remediation: "None."
            )
        } catch {
            if let installedRoot, let store = try? ModelImportStore() {
                store.removeInstallRoot(installedRoot)
            }
            importError = error.localizedDescription
            ImportDiagnostics.shared.record(
                phase: "manual-components",
                message: error.localizedDescription,
                remediation: "Select one model, tokenizer.json, tokenizer_config.json or chat_template.jinja, and config.json or text-generation.json."
            )
        }
        isImporting = false
    }

    @MainActor
    private func importKgraph(url: URL) async {
        do {
            let destination = FileManager.default.urls(
                for: .documentDirectory,
                in: .userDomainMask
            )[0].appendingPathComponent(url.lastPathComponent)
            try await Task.detached {
                let fileManager = FileManager.default
                let temporary = destination.deletingLastPathComponent()
                    .appendingPathComponent(".graph-\(UUID().uuidString).partial")
                var committed = false
                defer {
                    if !committed {
                        try? fileManager.removeItem(at: temporary)
                    }
                }
                try fileManager.copyItem(at: url, to: temporary)
                let attributes = try fileManager.attributesOfItem(atPath: temporary.path)
                guard let size = attributes[.size] as? NSNumber, size.int64Value > 0 else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                if fileManager.fileExists(atPath: destination.path) {
                    _ = try fileManager.replaceItemAt(
                        destination,
                        withItemAt: temporary,
                        backupItemName: nil,
                        options: []
                    )
                } else {
                    try fileManager.moveItem(at: temporary, to: destination)
                }
                committed = true
            }.value
            appSettings.kgraphPath = destination.path
            graphReasoningService.openGraph(at: destination.path)
        } catch {
            kgraphPickerError = "Failed to import graph: \(error.localizedDescription)"
        }
    }

    private func openStaging(
        huggingFace: String?,
        components: ModelStagingHandoff.ComponentBundle?
    ) {
        do {
            let url = try ModelStagingHandoff.build(
                baseURL: stagingBase,
                targetProfile: SdxBuildConfiguration.targetProfile,
                huggingFaceReference: huggingFace,
                components: components
            )
            UIApplication.shared.open(url, options: [:]) { opened in
                DispatchQueue.main.async {
                    if opened {
                        appSettings.modelStagingBaseUrl = stagingBase
                        huggingFaceReference = ""
                        stagingComponents = ModelStagingHandoff.ComponentBundle()
                        importError = nil
                    } else {
                        importError = "Safari could not open the staging URL."
                    }
                }
            }
        } catch {
            importError = error.localizedDescription
            ImportDiagnostics.shared.record(
                phase: "staging-handoff",
                message: error.localizedDescription,
                remediation: "Use a credential-free staging base and a valid Hugging Face repository or complete public component URLs."
            )
        }
    }

    private func resetDefaults() {
        appSettings.clearLocalModel()
        appSettings.kgraphPath = ""
        appSettings.modelStagingBaseUrl = ""
        appSettings.temperature = 0.7
        appSettings.maxTokens = 1024
        appSettings.topP = 0.9
        appSettings.topK = 40
        appSettings.maxToolRounds = 4
        stagingBase = ""
        huggingFaceReference = ""
        stagingComponents = ModelStagingHandoff.ComponentBundle()
        graphReasoningService.closeGraph()
        inferenceRouter.configure(settings: appSettings)
    }
}

private struct ImportDiagnosticLogView: View {
    @ObservedObject var diagnostics: ImportDiagnostics

    var body: some View {
        List {
            if diagnostics.entries.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "checkmark.circle")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("No Import Events")
                        .font(.headline)
                    Text("Model staging, validation, and load errors appear here.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
            ForEach(diagnostics.entries) { entry in
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(entry.phase)
                            .font(.caption.weight(.semibold))
                        Spacer()
                        Text(entry.timestamp, style: .date)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Text(entry.message).font(.subheadline)
                    if entry.remediation != "None." {
                        Text(entry.remediation)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .textSelection(.enabled)
            }
        }
        .navigationTitle("Import Log")
        .toolbar {
            if !diagnostics.entries.isEmpty {
                Button("Clear", role: .destructive) { diagnostics.clear() }
            }
        }
    }
}

private struct DocumentPicker: UIViewControllerRepresentable {
    let allowedTypes: [UTType]
    let allowsMultipleSelection: Bool
    let onPick: ([URL]) async -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onError: onError)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: allowedTypes)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = allowsMultipleSelection
        return picker
    }

    func updateUIViewController(
        _ viewController: UIDocumentPickerViewController,
        context: Context
    ) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: ([URL]) async -> Void
        let onError: (String) -> Void

        init(
            onPick: @escaping ([URL]) async -> Void,
            onError: @escaping (String) -> Void
        ) {
            self.onPick = onPick
            self.onError = onError
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard !urls.isEmpty else {
                onError("No files were selected.")
                return
            }
            var accessed: [URL] = []
            for url in urls {
                guard url.startAccessingSecurityScopedResource() else {
                    accessed.forEach { $0.stopAccessingSecurityScopedResource() }
                    onError("The selected file provider did not grant access. Select the files again from Files.")
                    return
                }
                accessed.append(url)
            }
            let grantedURLs = accessed
            Task {
                defer {
                    grantedURLs.forEach { $0.stopAccessingSecurityScopedResource() }
                }
                await onPick(urls)
            }
        }
    }
}

#Preview {
    SettingsView()
        .environmentObject(AppSettings())
        .environmentObject(InferenceRouter())
        .environmentObject(GraphReasoningService())
}
