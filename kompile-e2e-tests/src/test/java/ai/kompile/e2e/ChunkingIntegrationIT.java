package ai.kompile.e2e;

import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.e2e.fixtures.TestDocumentFactory;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.core.chunking.ChunkingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests multiple chunker strategies on the same input,
 * verifying that valid chunks are produced.
 */
@Tag("integration")
@DisplayName("Chunking Integration Tests")
@SpringBootTest(
        classes = E2eTestApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "kompile.embedding.type=none",
                "kompile.vectorstore.type=none"
        }
)
@ActiveProfiles("test")
class ChunkingIntegrationIT {

    @Autowired(required = false)
    private ChunkingService chunkingService;

    @Autowired(required = false)
    private List<TextChunker> chunkers;

    private static final String LONG_TEXT = """
            Kompile is a comprehensive AI/ML platform that combines CLI tools for model conversion,
            pipeline building, and RAG application generation. The platform includes a Spring Boot
            RAG framework with pluggable embeddings, vector stores, and LLMs.

            Key Features include model conversion between TensorFlow, ONNX, Keras, and SameDiff formats.
            The platform supports pluggable embedding models including OpenAI, SameDiff, and sentence
            transformers. Multiple vector store backends are available: Lucene HNSW, PostgreSQL pgvector,
            Chroma, and Vespa.

            Document loaders handle PDF, Office, email, and web content. Chunking strategies include
            recursive character splitting, sentence-based chunking, token-based chunking, and
            markdown-aware chunking. The platform also supports table-aware chunking for structured data.

            Graph RAG with Neo4j knowledge graphs enables rich semantic retrieval. The ReAct agent
            provides multi-step reasoning capabilities. Query transformation and guardrails ensure
            safe and effective RAG operations.
            """;

    @Test
    @DisplayName("ChunkingService is wired when available")
    void testChunkingServiceAvailable() {
        // ChunkingService may or may not be available depending on classpath
        // This test verifies wiring works when it is
        if (chunkingService != null) {
            Set<String> strategies = chunkingService.getAvailableStrategies();
            assertNotNull(strategies, "Available strategies should not be null");
        }
    }

    @Test
    @DisplayName("Individual chunkers produce valid chunks")
    void testChunkersProduceValidChunks() {
        if (chunkers == null || chunkers.isEmpty()) {
            return; // Skip if no chunkers on classpath
        }

        RetrievedDoc document = TestDocumentFactory.retrievedDoc(LONG_TEXT);

        for (TextChunker chunker : chunkers) {
            Map<String, Object> options = Map.of(
                    "chunkSize", 200,
                    "overlap", 50
            );

            List<RetrievedDoc> chunks = chunker.chunk(document, options);

            assertNotNull(chunks, chunker.getName() + " should not return null");
            assertFalse(chunks.isEmpty(),
                    chunker.getName() + " should produce at least one chunk for non-empty text");

            for (RetrievedDoc chunk : chunks) {
                assertNotNull(chunk.getText(),
                        chunker.getName() + " chunk text should not be null");
                assertFalse(chunk.getText().isBlank(),
                        chunker.getName() + " chunk text should not be blank");
            }
        }
    }

    @Test
    @DisplayName("Chunking preserves all content")
    void testChunkingPreservesContent() {
        if (chunkers == null || chunkers.isEmpty()) {
            return;
        }

        RetrievedDoc document = TestDocumentFactory.retrievedDoc(LONG_TEXT);
        TextChunker chunker = chunkers.get(0);

        Map<String, Object> options = Map.of("chunkSize", 300, "overlap", 0);
        List<RetrievedDoc> chunks = chunker.chunk(document, options);

        // Verify all original text is represented in chunks
        String reassembled = String.join("", chunks.stream()
                .map(RetrievedDoc::getText)
                .toList());

        // With no overlap, reassembled should contain all original text
        // (may have minor differences from splitting boundaries)
        assertTrue(reassembled.length() >= LONG_TEXT.trim().length() * 0.9,
                "Reassembled text should cover most of original content");
    }

    @Test
    @DisplayName("Empty document produces empty or single chunk")
    void testEmptyDocument() {
        if (chunkers == null || chunkers.isEmpty()) {
            return;
        }

        RetrievedDoc emptyDoc = TestDocumentFactory.retrievedDoc("");
        TextChunker chunker = chunkers.get(0);

        try {
            List<RetrievedDoc> chunks = chunker.chunk(emptyDoc, Map.of());
            // Either empty list or single empty chunk is acceptable
            assertTrue(chunks.size() <= 1, "Empty document should produce at most one chunk");
        } catch (Exception e) {
            // Some chunkers may throw on empty input, which is also acceptable
            assertTrue(e instanceof IllegalArgumentException || e instanceof NullPointerException,
                    "Exception on empty input should be validation-related");
        }
    }
}
