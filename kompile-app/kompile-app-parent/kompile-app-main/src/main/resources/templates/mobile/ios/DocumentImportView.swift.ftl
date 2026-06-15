import SwiftUI
import UniformTypeIdentifiers

struct DocumentImportView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var appSettings: AppSettings

    @State private var importMode: ImportMode = .file
    @State private var urlString: String = ""
    @State private var isFilePickerPresented: Bool = false
    @State private var importedFileName: String? = nil
    @State private var importedText: String? = nil
    @State private var isLoading: Bool = false
    @State private var errorMessage: String? = nil

    let onImport: (String, String, DocumentSource.SourceType) -> Void

    enum ImportMode: String, CaseIterable {
        case file = "File"
        case url = "URL"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Import Method") {
                    Picker("Source", selection: $importMode) {
                        ForEach(ImportMode.allCases, id: \.self) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                switch importMode {
                case .file:
                    fileImportSection
                case .url:
                    urlImportSection
                }

                if let error = errorMessage {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                            .font(.subheadline)
                    }
                }

                if importedText != nil {
                    Section {
                        previewSection
                    }
                }
            }
            .navigationTitle("Import Document")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Import") {
                        performImport()
                    }
                    .disabled(importedText == nil || isLoading)
                    .fontWeight(.semibold)
                }
            }
            .fileImporter(
                isPresented: $isFilePickerPresented,
                allowedContentTypes: supportedTypes,
                allowsMultipleSelection: false
            ) { result in
                handleFileImport(result)
            }
        }
    }

    private var fileImportSection: some View {
        Section("Select File") {
            Button {
                isFilePickerPresented = true
            } label: {
                HStack {
                    Image(systemName: "doc.badge.plus")
                        .font(.title2)
                        .foregroundStyle(.blue)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(importedFileName ?? "Choose a file")
                            .font(.subheadline)
                            .foregroundColor(importedFileName != nil ? .primary : .secondary)
                        Text("Supports TXT, PDF, MD, JSON")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var urlImportSection: some View {
        Section("Enter URL") {
            HStack {
                Image(systemName: "globe")
                    .foregroundStyle(.secondary)
                TextField("https://example.com/document.txt", text: $urlString)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
            }

            Button {
                fetchURL()
            } label: {
                HStack {
                    if isLoading {
                        ProgressView()
                            .padding(.trailing, 4)
                    }
                    Text(isLoading ? "Fetching..." : "Fetch Content")
                }
            }
            .disabled(urlString.isEmpty || isLoading)
        }
    }

    @ViewBuilder
    private var previewSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Text("Content Loaded")
                    .font(.subheadline)
                    .fontWeight(.medium)
            }

            if let text = importedText {
                Text(String(text.prefix(300)) + (text.count > 300 ? "..." : ""))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(6)

                Text("\(text.count) characters")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private var supportedTypes: [UTType] {
        [.plainText, .pdf, .json, .utf8PlainText]
    }

    private func handleFileImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let accessing = url.startAccessingSecurityScopedResource()
            defer {
                if accessing { url.stopAccessingSecurityScopedResource() }
            }
            do {
                let text = try String(contentsOf: url, encoding: .utf8)
                importedFileName = url.lastPathComponent
                importedText = text
                errorMessage = nil
            } catch {
                errorMessage = "Failed to read file: \(error.localizedDescription)"
            }
        case .failure(let error):
            errorMessage = "File picker error: \(error.localizedDescription)"
        }
    }

    private func fetchURL() {
        guard let url = URL(string: urlString) else {
            errorMessage = "Invalid URL format."
            return
        }

        isLoading = true
        errorMessage = nil

        Task {
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                guard let text = String(data: data, encoding: .utf8) else {
                    await MainActor.run {
                        errorMessage = "Could not decode content as text."
                        isLoading = false
                    }
                    return
                }
                await MainActor.run {
                    importedText = text
                    importedFileName = url.lastPathComponent
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Fetch failed: \(error.localizedDescription)"
                    isLoading = false
                }
            }
        }
    }

    private func performImport() {
        guard let text = importedText else { return }
        let name = importedFileName ?? "Untitled"
        let sourceType: DocumentSource.SourceType = importMode == .file ? .file : .url
        onImport(name, text, sourceType)
        dismiss()
    }
}

#Preview {
    DocumentImportView { name, text, type in
        print("Imported: \(name), \(text.count) chars, type: \(type)")
    }
    .environmentObject(AppSettings())
}
