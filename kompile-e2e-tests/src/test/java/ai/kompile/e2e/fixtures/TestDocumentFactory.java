package ai.kompile.e2e.fixtures;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Factory for creating test documents with realistic metadata.
 */
public final class TestDocumentFactory {

    private TestDocumentFactory() {}

    public static Document springDoc(String text) {
        return new Document(UUID.randomUUID().toString(), text, Map.of());
    }

    public static Document springDoc(String text, Map<String, Object> metadata) {
        return new Document(UUID.randomUUID().toString(), text, metadata);
    }

    public static List<Document> springDocs(String... texts) {
        return List.of(texts).stream()
                .map(TestDocumentFactory::springDoc)
                .toList();
    }

    public static RetrievedDoc retrievedDoc(String text) {
        return new RetrievedDoc(text);
    }

    public static RetrievedDoc retrievedDoc(String text, double score) {
        return new RetrievedDoc(text, Map.of(), score);
    }

    public static RetrievedDoc retrievedDocWithSource(String text, String source, int page) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_path", source);
        metadata.put("page_number", page);
        metadata.put("source_filename", source.substring(source.lastIndexOf('/') + 1));
        return new RetrievedDoc(UUID.randomUUID().toString(), text, metadata);
    }

    public static List<RetrievedDoc> sampleRetrievedDocs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> retrievedDocWithSource(
                        "Sample document content #" + i + ". This is test text for integration testing.",
                        "/test/docs/sample-" + i + ".pdf",
                        i + 1))
                .toList();
    }

    public static List<Document> sampleSpringDocs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> springDoc(
                        "Sample document content #" + i + ". This is test text for integration testing.",
                        Map.of("source", "test-" + i + ".pdf", "page", i + 1)))
                .toList();
    }
}
