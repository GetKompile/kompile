import Foundation

struct DocumentSource: Identifiable, Codable, Equatable {
    enum SourceType: String, Codable, CaseIterable {
        case file
        case url

        var iconName: String {
            switch self {
            case .file: return "doc.fill"
            case .url: return "globe"
            }
        }

        var displayName: String {
            switch self {
            case .file: return "File"
            case .url: return "URL"
            }
        }
    }

    let id: UUID
    let name: String
    let sourceType: SourceType
    var chunkCount: Int
    let importedAt: Date
    var sourceLocation: String

    init(
        id: UUID = UUID(),
        name: String,
        sourceType: SourceType,
        chunkCount: Int = 0,
        importedAt: Date = Date(),
        sourceLocation: String = ""
    ) {
        self.id = id
        self.name = name
        self.sourceType = sourceType
        self.chunkCount = chunkCount
        self.importedAt = importedAt
        self.sourceLocation = sourceLocation
    }

    var formattedDate: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: importedAt)
    }

    var sizeDescription: String {
        "\(chunkCount) chunk\(chunkCount == 1 ? "" : "s")"
    }
}
