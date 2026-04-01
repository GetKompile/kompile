import SwiftUI

struct SourceListView: View {
    @EnvironmentObject var appSettings: AppSettings
    @State private var documents: [DocumentSource] = []
    @State private var showImportSheet: Bool = false
    @State private var searchText: String = ""

    @StateObject private var ingestionService = DocumentIngestionService()
    @StateObject private var vectorService = VectorSearchService()

    var body: some View {
        NavigationStack {
            Group {
                if documents.isEmpty {
                    emptyStateView
                } else {
                    documentListView
                }
            }
            .navigationTitle("Sources")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showImportSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .searchable(text: $searchText, prompt: "Search documents")
            .sheet(isPresented: $showImportSheet) {
                DocumentImportView { name, text, sourceType in
                    importDocument(name: name, text: text, sourceType: sourceType)
                }
            }
        }
    }

    private var emptyStateView: some View {
        ContentUnavailableView(
            "No Documents",
            systemImage: "doc.text.magnifyingglass",
            description: Text("Import documents to build your local knowledge base.")
        )
    }

    private var documentListView: some View {
        List {
            ForEach(filteredDocuments) { document in
                DocumentRowView(document: document)
            }
            .onDelete { indexSet in
                let toDelete = indexSet.map { filteredDocuments[$0] }
                for doc in toDelete {
                    documents.removeAll { $0.id == doc.id }
                }
            }

            Section {
                HStack {
                    Text("Total chunks indexed")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(documents.reduce(0) { $0 + $1.chunkCount })")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var filteredDocuments: [DocumentSource] {
        if searchText.isEmpty {
            return documents
        }
        return documents.filter {
            $0.name.localizedCaseInsensitiveContains(searchText)
        }
    }

    private func importDocument(name: String, text: String, sourceType: DocumentSource.SourceType) {
        let chunks = ingestionService.ingest(
            text: text,
            chunkSize: appSettings.chunkSize,
            overlap: 64
        )

        let source = DocumentSource(
            name: name,
            sourceType: sourceType,
            chunkCount: chunks.count,
            sourceLocation: name
        )

        documents.append(source)

        Task {
            await vectorService.index(chunks: chunks)
        }
    }
}

struct DocumentRowView: View {
    let document: DocumentSource

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: document.sourceType.iconName)
                .font(.title2)
                .foregroundStyle(.blue)
                .frame(width: 36, height: 36)
                .background(Color.blue.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(document.name)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    Label(document.sourceType.displayName, systemImage: document.sourceType.iconName)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Text(document.sizeDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Text(document.formattedDate)
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    SourceListView()
        .environmentObject(AppSettings())
}
