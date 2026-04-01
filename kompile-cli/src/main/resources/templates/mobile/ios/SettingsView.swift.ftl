import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var appSettings: AppSettings
    @State private var showResetAlert: Bool = false

    var body: some View {
        NavigationStack {
            Form {
                inferenceModeSection
                apiKeySection
                modelInfoSection
                ragConfigSection
                aboutSection
                resetSection
            }
            .navigationTitle("Settings")
            .alert("Reset Settings", isPresented: $showResetAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Reset", role: .destructive) {
                    appSettings.resetToDefaults()
                }
            } message: {
                Text("This will reset all settings to their default values. This cannot be undone.")
            }
        }
    }

    private var inferenceModeSection: some View {
        Section {
            Picker("Inference Mode", selection: $appSettings.inferenceMode) {
                ForEach(AppConfig.InferenceMode.allCases) { mode in
                    VStack(alignment: .leading) {
                        Text(mode.displayName)
                    }
                    .tag(mode)
                }
            }
            .pickerStyle(.menu)

            Text(appSettings.inferenceMode.description)
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("Inference Mode")
        }
    }

    @ViewBuilder
    private var apiKeySection: some View {
        if appSettings.inferenceMode == .remote || appSettings.inferenceMode == .hybrid {
            Section {
                SecureField("API Key", text: $appSettings.apiKey)
                    .textContentType(.password)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)

                if appSettings.apiKey == AppConfig.defaultApiKey || appSettings.apiKey.isEmpty {
                    Label("API key not configured", systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            } header: {
                Text("API Configuration")
            } footer: {
                Text("Required for remote and hybrid inference modes. Uses OpenAI-compatible API format.")
            }
        }
    }

    private var modelInfoSection: some View {
        Section {
            LabeledContent("Model ID") {
                Text(appSettings.modelId)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            LabeledContent("Model File") {
                Text(AppConfig.defaultModelFileName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            LabeledContent("Bundle Status") {
                HStack(spacing: 4) {
                    Image(systemName: AppConfig.modelBundlePath != nil ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundStyle(AppConfig.modelBundlePath != nil ? .green : .red)
                    Text(AppConfig.modelBundlePath != nil ? "Available" : "Not Found")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            Stepper("Max Tokens: \(appSettings.maxTokens)", value: $appSettings.maxTokens, in: 32...2048, step: 32)
                .font(.subheadline)
        } header: {
            Text("Model Information")
        }
    }

    private var ragConfigSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Chunk Size")
                        .font(.subheadline)
                    Spacer()
                    Text("\(appSettings.chunkSize)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(
                    value: Binding(
                        get: { Double(appSettings.chunkSize) },
                        set: { appSettings.chunkSize = Int($0) }
                    ),
                    in: 64...2048,
                    step: 64
                )
                .tint(.blue)
            }

            Stepper("Top-K Results: \(appSettings.topK)", value: $appSettings.topK, in: 1...20)
                .font(.subheadline)
        } header: {
            Text("RAG Configuration")
        } footer: {
            Text("Chunk size controls how documents are split for indexing. Top-K determines how many relevant chunks are retrieved per query.")
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("SDK Version") {
                Text(AppConfig.sdkVersion)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            LabeledContent("Package") {
                Text(AppConfig.packageName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            LabeledContent("Platform") {
                Text("iOS \(UIDevice.current.systemVersion)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("About")
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
}

#Preview {
    SettingsView()
        .environmentObject(AppSettings())
}
