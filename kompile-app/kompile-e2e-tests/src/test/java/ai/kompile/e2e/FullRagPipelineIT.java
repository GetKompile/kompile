package ai.kompile.e2e;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.e2e.fixtures.*;
import org.junit.jupiter.api.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full RAG pipeline integration test:
 * create docs -> chunk -> embed -> index in vector store -> query -> verify results
 */
@Tag("e2e")
@DisplayName("Full RAG Pipeline E2E")
class FullRagPipelineIT {

    private InMemoryEmbeddingModel embeddingModel;
    private InMemoryVectorStore vectorStore;
    private StubLanguageModel languageModel;

    @BeforeEach
    void setUp() {
        embeddingModel = new InMemoryEmbeddingModel(384);
        vectorStore = new InMemoryVectorStore(embeddingModel);
        languageModel = new StubLanguageModel();
    }

    @Test
    @DisplayName("Documents indexed and retrieved via similarity search")
    void testDocumentsIndexedAndRetrieved() {
        // Create test documents
        List<Document> docs = TestDocumentFactory.sampleSpringDocs(5);

        // Index documents (embedding + storing happens inside add())
        int added = vectorStore.add(docs);
        assertEquals(5, added, "All documents should be indexed");
        assertEquals(5, vectorStore.getApproxVectorCount(), "Vector count should match");

        // Query using text from first document
        String queryText = docs.get(0).getText();
        List<Document> results = vectorStore.similaritySearch(queryText, 3);

        assertFalse(results.isEmpty(), "Search should return results");
        assertTrue(results.size() <= 3, "Should return at most k results");

        // The most similar document should be the one we queried with (exact match)
        assertEquals(queryText, results.get(0).getText(),
                "Top result should be the queried document (self-similarity = 1.0)");
    }

    @Test
    @DisplayName("Full pipeline: ingest, retrieve, generate response")
    void testFullPipeline() {
        // Simulate document ingestion
        List<Document> docs = List.of(
                TestDocumentFactory.springDoc("Machine learning is a subset of artificial intelligence.",
                        Map.of("source", "ml-intro.pdf")),
                TestDocumentFactory.springDoc("Neural networks are inspired by biological brain structures.",
                        Map.of("source", "nn-basics.pdf")),
                TestDocumentFactory.springDoc("The weather forecast predicts rain tomorrow.",
                        Map.of("source", "weather.txt"))
        );

        vectorStore.add(docs);

        // Query
        String query = "What is machine learning?";
        List<Document> retrieved = vectorStore.similaritySearch(query, 2);
        assertFalse(retrieved.isEmpty(), "Should retrieve relevant documents");

        // Pass to LLM
        List<String> context = retrieved.stream().map(Document::getText).toList();
        String response = languageModel.generateResponse(query, context);

        assertNotNull(response, "LLM should generate a response");
        assertTrue(response.contains("StubLLM response to:"), "Response should come from stub");
        assertTrue(response.contains("context_docs=2"), "Should include context count");
    }

    @Test
    @DisplayName("Empty vector store returns no results")
    void testEmptyStoreReturnsNoResults() {
        List<Document> results = vectorStore.similaritySearch("anything", 5);
        assertTrue(results.isEmpty(), "Empty store should return no results");
    }

    @Test
    @DisplayName("Delete removes documents from index")
    void testDeleteRemovesDocuments() {
        List<Document> docs = TestDocumentFactory.sampleSpringDocs(3);
        vectorStore.add(docs);
        assertEquals(3, vectorStore.getApproxVectorCount());

        // Delete first document
        vectorStore.delete(List.of(docs.get(0).getId()));
        assertEquals(2, vectorStore.getApproxVectorCount());
    }

    @Test
    @DisplayName("Embedding model produces correct dimensions")
    void testEmbeddingDimensions() {
        INDArray embedding = embeddingModel.embed("test text");
        assertEquals(1, embedding.rows(), "Single text should produce 1-row matrix");
        assertEquals(384, embedding.columns(), "Embedding dimension should be 384");
    }

    @Test
    @DisplayName("Same text produces identical embeddings (deterministic)")
    void testDeterministicEmbeddings() {
        INDArray emb1 = embeddingModel.embed("hello world");
        INDArray emb2 = embeddingModel.embed("hello world");
        assertEquals(emb1, emb2, "Same text should produce identical embeddings");
    }

    @Test
    @DisplayName("Different texts produce different embeddings")
    void testDifferentTextsProduceDifferentEmbeddings() {
        INDArray emb1 = embeddingModel.embed("hello world");
        INDArray emb2 = embeddingModel.embed("goodbye universe");
        assertNotEquals(emb1, emb2, "Different texts should produce different embeddings");
    }
}
