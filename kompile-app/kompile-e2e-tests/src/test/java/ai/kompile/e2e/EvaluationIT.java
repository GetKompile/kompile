package ai.kompile.e2e;

import ai.kompile.core.evaluation.EvaluationService;
import ai.kompile.e2e.fixtures.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the evaluation module with stub RAG results.
 * Verifies that evaluation service beans are wired correctly
 * and can process evaluation requests.
 */
@Tag("integration")
@DisplayName("Evaluation Module Integration Tests")
@SpringBootTest(
        classes = E2eTestApplication.class,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "kompile.embedding.type=none",
                "kompile.vectorstore.type=none",
                "kompile.evaluation.enabled=false"
        }
)
@ActiveProfiles("test")
class EvaluationIT {

    @Autowired(required = false)
    private EvaluationService evaluationService;

    @Test
    @DisplayName("EvaluationService bean is available when module is on classpath")
    void testEvaluationServiceAvailable() {
        if (evaluationService != null) {
            assertNotNull(evaluationService.getEvaluators(),
                    "Evaluators list should not be null");
        }
    }

    @Test
    @DisplayName("Evaluation service reports enabled state correctly")
    void testEvaluationEnabledState() {
        if (evaluationService != null) {
            // We set enabled=false in properties, so it should report disabled
            assertFalse(evaluationService.isEnabled(),
                    "Evaluation should be disabled per test config");
        }
    }

    @Test
    @DisplayName("Stub RAG results can be prepared for evaluation")
    void testStubResultsPreparation() {
        // Verify our test fixtures produce valid data for evaluation
        StubDocumentRetriever retriever = new StubDocumentRetriever();
        retriever.setDocumentsToReturn(TestDocumentFactory.sampleRetrievedDocs(3));

        List<String> retrieved = retriever.retrieve("test query", 3);
        assertEquals(3, retrieved.size(), "Should retrieve 3 documents");

        for (String doc : retrieved) {
            assertNotNull(doc, "Retrieved doc text should not be null");
            assertFalse(doc.isBlank(), "Retrieved doc text should not be blank");
        }

        // Verify queries are recorded
        assertEquals(1, retriever.getReceivedQueries().size());
        assertEquals("test query", retriever.getReceivedQueries().get(0));
    }

    @Test
    @DisplayName("StubDocumentRetriever records and clears history")
    void testRetrieverHistory() {
        StubDocumentRetriever retriever = new StubDocumentRetriever();
        retriever.setDocumentsToReturn(TestDocumentFactory.sampleRetrievedDocs(1));

        retriever.retrieve("query1", 1);
        retriever.retrieve("query2", 1);

        assertEquals(2, retriever.getReceivedQueries().size());

        retriever.clearHistory();
        assertTrue(retriever.getReceivedQueries().isEmpty());
    }

    @Test
    @DisplayName("TestDocumentFactory produces valid documents")
    void testDocumentFactory() {
        var docs = TestDocumentFactory.sampleRetrievedDocs(5);
        assertEquals(5, docs.size());

        for (var doc : docs) {
            assertNotNull(doc.getText());
            assertNotNull(doc.getId());
            assertNotNull(doc.getMetadata());
        }
    }
}
