package ai.kompile.e2e;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.e2e.fixtures.*;
import org.junit.jupiter.api.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests EmbeddingModel + VectorStore interaction:
 * add, search, delete, count.
 */
@Tag("integration")
@DisplayName("Embedding + VectorStore Integration Tests")
class EmbeddingVectorStoreIT {

    private InMemoryEmbeddingModel embeddingModel;
    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        embeddingModel = new InMemoryEmbeddingModel(128);
        vectorStore = new InMemoryVectorStore(embeddingModel);
    }

    @Test
    @DisplayName("Add documents and verify count")
    void testAddAndCount() {
        List<Document> docs = TestDocumentFactory.sampleSpringDocs(10);
        int added = vectorStore.add(docs);

        assertEquals(10, added);
        assertEquals(10, vectorStore.getApproxVectorCount());
    }

    @Test
    @DisplayName("Add with pre-computed embeddings")
    void testAddWithEmbeddings() {
        List<Document> docs = TestDocumentFactory.sampleSpringDocs(3);
        INDArray embeddings = embeddingModel.embedDocuments(docs);

        int added = vectorStore.addWithEmbeddings(docs, embeddings);
        assertEquals(3, added);
        assertEquals(3, vectorStore.getApproxVectorCount());
    }

    @Test
    @DisplayName("Similarity search returns ordered results")
    void testSimilaritySearchOrdering() {
        Document target = TestDocumentFactory.springDoc("machine learning algorithms",
                Map.of("topic", "ml"));
        Document similar = TestDocumentFactory.springDoc("deep learning neural networks",
                Map.of("topic", "dl"));
        Document unrelated = TestDocumentFactory.springDoc("cooking pasta recipes",
                Map.of("topic", "food"));

        vectorStore.add(List.of(target, similar, unrelated));

        List<Document> results = vectorStore.similaritySearch("machine learning algorithms", 3);
        assertFalse(results.isEmpty());

        // First result should be the exact match
        assertEquals("machine learning algorithms", results.get(0).getText());
    }

    @Test
    @DisplayName("Similarity search with scores")
    void testSimilaritySearchWithScores() {
        vectorStore.add(List.of(
                TestDocumentFactory.springDoc("quantum computing basics"),
                TestDocumentFactory.springDoc("quantum physics experiments")
        ));

        INDArray queryEmb = embeddingModel.embed("quantum computing basics");
        List<ScoredDocument> scored = vectorStore.similaritySearchWithScores(queryEmb, 2, 0.0);

        assertFalse(scored.isEmpty());
        // Scores should be descending
        for (int i = 1; i < scored.size(); i++) {
            assertTrue(scored.get(i - 1).score() >= scored.get(i).score(),
                    "Results should be sorted by score descending");
        }
        // Exact match should have score close to 1.0
        assertTrue(scored.get(0).score() > 0.99,
                "Self-match score should be near 1.0, got " + scored.get(0).score());
    }

    @Test
    @DisplayName("Threshold filtering works")
    void testThresholdFiltering() {
        vectorStore.add(List.of(
                TestDocumentFactory.springDoc("apple banana cherry"),
                TestDocumentFactory.springDoc("completely unrelated text about space exploration")
        ));

        // Search with high threshold
        List<Document> results = vectorStore.similaritySearch("apple banana cherry", 10, 0.99);

        // Only the exact match should pass a very high threshold
        assertEquals(1, results.size(), "Only exact match should pass high threshold");
    }

    @Test
    @DisplayName("Delete removes specific documents")
    void testDelete() {
        List<Document> docs = TestDocumentFactory.sampleSpringDocs(5);
        vectorStore.add(docs);

        String idToDelete = docs.get(2).getId();
        boolean deleted = vectorStore.delete(List.of(idToDelete));

        assertTrue(deleted);
        assertEquals(4, vectorStore.getApproxVectorCount());
    }

    @Test
    @DisplayName("Delete all clears the store")
    void testDeleteAll() {
        vectorStore.add(TestDocumentFactory.sampleSpringDocs(5));
        assertEquals(5, vectorStore.getApproxVectorCount());

        vectorStore.deleteAll();
        assertEquals(0, vectorStore.getApproxVectorCount());
    }

    @Test
    @DisplayName("Batch embedding produces correct shape")
    void testBatchEmbedding() {
        List<String> texts = List.of("text one", "text two", "text three");
        INDArray embeddings = embeddingModel.embed(texts);

        assertEquals(3, embeddings.rows(), "Should have 3 rows");
        assertEquals(128, embeddings.columns(), "Should have 128 columns");
    }

    @Test
    @DisplayName("Embedding model dimensions() is consistent")
    void testDimensionsConsistency() {
        assertEquals(128, embeddingModel.dimensions());

        INDArray emb = embeddingModel.embed("test");
        assertEquals(embeddingModel.dimensions(), emb.columns());
    }
}
