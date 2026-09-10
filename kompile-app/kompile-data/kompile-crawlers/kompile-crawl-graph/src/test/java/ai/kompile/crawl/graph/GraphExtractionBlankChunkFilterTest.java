/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the blank-chunk filter in {@link GraphExtractionOrchestrator}.
 *
 * <p><strong>Root cause (live production crawl, 5 of 50 chunks failed):</strong>
 * The chunker emits chunks with empty or whitespace-only text (e.g. markup-only HTML pages
 * that cleaned to nothing). {@code toRetrievedDoc} calls
 * {@code new RetrievedDoc(id, doc.getText(), metadata)}, and {@link RetrievedDoc} enforces
 * "exactly one of text or media must be specified" — so an empty-text doc throws
 * {@link IllegalArgumentException} at construction time. Over the graph-subprocess RPC this
 * propagates as a Jackson {@code convertValue} failure that aborts the <em>whole batch</em>
 * (not just the one bad chunk), causing the batch-mates to be reported as failed too.</p>
 *
 * <p>The fix: {@link GraphExtractionOrchestrator#filterAndConvertDocs} drops blank chunks
 * before they enter the RPC and emits a WARN so operators can see that content was skipped.
 * These tests verify that behavior end-to-end.</p>
 */
class GraphExtractionBlankChunkFilterTest {

    // -------------------------------------------------------------------------
    // hasExtractableText
    // -------------------------------------------------------------------------

    @Test
    void hasExtractableText_trueForNonBlankText() {
        Document d = new Document("Some entity text about Alice.", Map.of());
        assertTrue(GraphExtractionOrchestrator.hasExtractableText(d),
                "Non-blank text document should have extractable text");
    }

    @Test
    void hasExtractableText_falseForEmptyString() {
        // Spring AI Document also validates text, but does allow "" (empty string)
        Document d = new Document("", Map.of());
        assertFalse(GraphExtractionOrchestrator.hasExtractableText(d),
                "Empty-string document must NOT be considered extractable (isBlank == true)");
    }

    @Test
    void hasExtractableText_falseForBlankWhitespace() {
        Document d = new Document("   \t\n  ", Map.of());
        assertFalse(GraphExtractionOrchestrator.hasExtractableText(d),
                "Whitespace-only document must NOT be considered extractable");
    }

    @Test
    void hasExtractableText_falseForNullDocument() {
        assertFalse(GraphExtractionOrchestrator.hasExtractableText(null),
                "Null document reference must return false safely");
    }

    @Test
    void hasSemanticGraphOutput_falseForNullAndEmptyGraphs() {
        assertFalse(GraphExtractionOrchestrator.hasSemanticGraphOutput(null));
        assertFalse(GraphExtractionOrchestrator.hasSemanticGraphOutput(Graph.builder().build()));
        assertFalse(GraphExtractionOrchestrator.hasSemanticGraphOutput(
                Graph.builder().entities(List.of()).relationships(List.of()).build()));
    }

    @Test
    void hasSemanticGraphOutput_trueWhenEntityOrRelationshipExists() {
        Entity entity = new Entity();
        entity.setId("entity-1");
        entity.setTitle("Entity One");
        entity.setType("CONCEPT");
        assertTrue(GraphExtractionOrchestrator.hasSemanticGraphOutput(
                Graph.builder().entities(List.of(entity)).relationships(List.of()).build()));
    }

    // -------------------------------------------------------------------------
    // filterAndConvertDocs — the critical guard that prevents batch failures
    // -------------------------------------------------------------------------

    /**
     * Primary regression test: mirrors the live Planning crawl scenario where 5 of 50 chunks
     * were empty, causing batches that contained them to fail entirely.
     */
    @Test
    void filterAndConvertDocs_keepsNonBlankChunksAndDropsBlanks() {
        List<Document> input = List.of(
                new Document("Alice works at Acme Corp.",                Map.of()),
                new Document("",                                          Map.of()),  // empty — must be dropped
                new Document("Bob leads the ML team.",                    Map.of()),
                new Document("Kubernetes orchestrates Docker containers.", Map.of()),
                new Document("   ",                                       Map.of()),  // blank — must be dropped
                new Document("TensorFlow is used for model training.",    Map.of()),
                new Document("\t\n",                                      Map.of()),  // blank — must be dropped
                new Document("Alice reports to Bob.",                     Map.of())
        );

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(input, "test-job-1");

        assertEquals(5, result.size(),
                "Exactly 5 non-blank chunks should survive the filter (3 blanks dropped)");
        assertTrue(result.stream().allMatch(rd -> rd.getText() != null && !rd.getText().isBlank()),
                "Every RetrievedDoc in the result must have non-blank text");
    }

    @Test
    void filterAndConvertDocs_allBlankYieldsEmptyList() {
        List<Document> input = List.of(
                new Document("",     Map.of()),
                new Document("  ",   Map.of()),
                new Document("\t\n", Map.of())
        );

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(input, "test-job-2");

        assertTrue(result.isEmpty(),
                "All-blank input must produce an empty list — never a list with invalid RetrievedDocs");
    }

    @Test
    void filterAndConvertDocs_allValidPassesThrough() {
        List<Document> input = List.of(
                new Document("Entity text A.", Map.of()),
                new Document("Entity text B.", Map.of()),
                new Document("Entity text C.", Map.of())
        );

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(input, "test-job-3");

        assertEquals(3, result.size(), "All valid chunks must pass through unchanged");
    }

    @Test
    void filterAndConvertDocs_emptyInputYieldsEmptyList() {
        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(List.of(), "test-job-4");
        assertTrue(result.isEmpty(), "Empty input must produce an empty result");
    }

    @Test
    void filterAndConvertDocs_nullInputYieldsEmptyList() {
        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(null, "test-job-5");
        assertTrue(result.isEmpty(), "Null input must produce an empty result safely");
    }

    /**
     * Verifies the core invariant: the filtered list never contains a blank-text RetrievedDoc.
     * This is the property that prevents the batch-fails-atomically bug: if any RetrievedDoc
     * in the list had blank text, constructing it would throw, aborting the whole convertValue.
     */
    @Test
    void filterAndConvertDocs_resultNeverContainsBlankTextEntry() {
        List<Document> mixed = List.of(
                new Document("Good text",    Map.of()),
                new Document("",             Map.of()),  // empty — dropped
                new Document("More text",    Map.of())
        );

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(mixed, "test-job-6");

        assertEquals(2, result.size());
        assertDoesNotThrow(() -> result.forEach(rd -> {
            assertNotNull(rd.getText(), "RetrievedDoc.getText() must never be null in the filtered list");
            assertFalse(rd.getText().isBlank(), "RetrievedDoc.getText() must never be blank in the filtered list");
        }));
    }

    @Test
    void filterAndConvertDocs_preservesDocumentIdsForNonBlankChunks() {
        // The docById reverse-lookup in extractGraphViaConstructor requires that the id on
        // the RetrievedDoc matches the id of the source Document.
        Document d1 = new Document("first chunk text",  Map.of());
        Document d2 = new Document("second chunk text", Map.of());
        Document d3 = new Document("",                  Map.of());  // blank, must be dropped

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(
                List.of(d1, d2, d3), "test-job-7");

        assertEquals(2, result.size());
        assertEquals(d1.getId(), result.get(0).getId(),
                "RetrievedDoc id must match its source Document id");
        assertEquals(d2.getId(), result.get(1).getId(),
                "RetrievedDoc id must match its source Document id");
    }

    @Test
    void filterAndConvertDocs_singleValidChunkIsSufficient() {
        // Edge case: only one good chunk in a batch of mixed content
        List<Document> input = List.of(
                new Document("  ",              Map.of()),
                new Document("Important text.", Map.of()),
                new Document("",               Map.of())
        );

        List<RetrievedDoc> result = GraphExtractionOrchestrator.filterAndConvertDocs(input, "test-job-8");

        assertEquals(1, result.size(),
                "Single valid chunk must be preserved even when neighbours are blank");
        assertEquals("Important text.", result.get(0).getText());
    }
}
