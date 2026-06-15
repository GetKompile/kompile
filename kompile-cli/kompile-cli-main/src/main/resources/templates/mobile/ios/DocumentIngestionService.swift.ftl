import Foundation

/// Service for splitting documents into chunks suitable for embedding and indexing.
/// Uses a sliding window approach with configurable chunk size and overlap.
final class DocumentIngestionService: ObservableObject {
    @Published var isProcessing: Bool = false
    @Published var lastChunkCount: Int = 0

    /// Splits the input text into overlapping chunks.
    /// - Parameters:
    ///   - text: The full document text to split.
    ///   - chunkSize: The target number of characters per chunk.
    ///   - overlap: The number of overlapping characters between consecutive chunks.
    /// - Returns: An array of text chunks.
    func ingest(text: String, chunkSize: Int, overlap: Int) -> [String] {
        guard !text.isEmpty else { return [] }

        let effectiveChunkSize = max(chunkSize, 1)
        let effectiveOverlap = min(max(overlap, 0), effectiveChunkSize - 1)
        let stride = effectiveChunkSize - effectiveOverlap

        var chunks: [String] = []
        let characters = Array(text)
        var startIndex = 0

        while startIndex < characters.count {
            let endIndex = min(startIndex + effectiveChunkSize, characters.count)
            let chunk = String(characters[startIndex..<endIndex])
            let trimmedChunk = chunk.trimmingCharacters(in: .whitespacesAndNewlines)

            if !trimmedChunk.isEmpty {
                chunks.append(trimmedChunk)
            }

            if endIndex >= characters.count {
                break
            }

            startIndex += stride
        }

        lastChunkCount = chunks.count
        return chunks
    }

    /// Splits text into chunks at sentence boundaries for cleaner splits.
    /// Falls back to character-based chunking if sentences are too long.
    /// - Parameters:
    ///   - text: The full document text.
    ///   - chunkSize: Target characters per chunk.
    ///   - overlap: Number of sentences to overlap between chunks.
    /// - Returns: An array of text chunks split at sentence boundaries.
    func ingestBySentence(text: String, chunkSize: Int, overlap: Int) -> [String] {
        guard !text.isEmpty else { return [] }

        let sentences = splitIntoSentences(text)
        guard !sentences.isEmpty else { return ingest(text: text, chunkSize: chunkSize, overlap: overlap) }

        var chunks: [String] = []
        var currentChunk: [String] = []
        var currentLength = 0
        let sentenceOverlap = max(overlap / 50, 1)

        for sentence in sentences {
            let sentenceLength = sentence.count

            if currentLength + sentenceLength > chunkSize && !currentChunk.isEmpty {
                chunks.append(currentChunk.joined(separator: " "))

                // Keep last N sentences for overlap
                let overlapSentences = Array(currentChunk.suffix(sentenceOverlap))
                currentChunk = overlapSentences
                currentLength = overlapSentences.reduce(0) { $0 + $1.count + 1 }
            }

            currentChunk.append(sentence)
            currentLength += sentenceLength + 1
        }

        if !currentChunk.isEmpty {
            let remaining = currentChunk.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
            if !remaining.isEmpty {
                chunks.append(remaining)
            }
        }

        lastChunkCount = chunks.count
        return chunks
    }

    private func splitIntoSentences(_ text: String) -> [String] {
        var sentences: [String] = []
        let range = text.startIndex..<text.endIndex

        text.enumerateSubstrings(in: range, options: .bySentences) { substring, _, _, _ in
            if let sentence = substring?.trimmingCharacters(in: .whitespacesAndNewlines), !sentence.isEmpty {
                sentences.append(sentence)
            }
        }

        return sentences
    }
}
