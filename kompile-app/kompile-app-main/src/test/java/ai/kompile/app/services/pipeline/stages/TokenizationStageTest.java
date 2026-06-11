/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.pipeline.stages;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TokenizationStageTest {

    private TokenizationStage stage;

    @BeforeEach
    void setUp() {
        stage = new TokenizationStage();
    }

    // --- getName ---

    @Test
    void getNameReturnsTokenization() {
        assertEquals("tokenization", stage.getName());
    }

    // --- process: enabled ---

    @Test
    void processTokenizesDocuments() throws Exception {
        ExtractionStage.ExtractionOutput input = extractionOutput(
                List.of(new Document("Hello world tokenization test")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        assertEquals(1, output.documentCount());
        assertTrue(output.totalTokens() > 0);
        assertTrue(output.tokenizationEnabled());
    }

    @Test
    void processProducesTokenOffsetsForEachWord() throws Exception {
        ExtractionStage.ExtractionOutput input = extractionOutput(
                List.of(new Document("one two three")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        TokenizationStage.TokenizedDocument tokenizedDoc = output.documents().get(0);
        assertTrue(tokenizedDoc.wasTokenized());
        assertEquals(3, tokenizedDoc.tokenCount());
        assertEquals(3, tokenizedDoc.tokenOffsets().size());
    }

    @Test
    void processHandlesEmptyText() throws Exception {
        ExtractionStage.ExtractionOutput input = extractionOutput(
                List.of(new Document("")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        assertEquals(1, output.documentCount());
        TokenizationStage.TokenizedDocument tokenizedDoc = output.documents().get(0);
        assertEquals(0, tokenizedDoc.tokenCount());
    }

    @Test
    void processHandlesMultipleDocuments() throws Exception {
        ExtractionStage.ExtractionOutput input = extractionOutput(List.of(
                new Document("first document"),
                new Document("second document"),
                new Document("third document")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        assertEquals(3, output.documentCount());
        assertTrue(output.totalTokens() >= 6); // at least 2 tokens per doc
    }

    // --- process: disabled ---

    @Test
    void processPassthroughWhenDisabled() throws Exception {
        stage.setEnabled(false);
        ExtractionStage.ExtractionOutput input = extractionOutput(
                List.of(new Document("Hello world")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        assertEquals(1, output.documentCount());
        assertFalse(output.tokenizationEnabled());
        TokenizationStage.TokenizedDocument tokenizedDoc = output.documents().get(0);
        assertFalse(tokenizedDoc.wasTokenized());
        assertEquals(0, tokenizedDoc.tokenCount());
        assertTrue(tokenizedDoc.tokenOffsets().isEmpty());
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        assertThrows(InterruptedException.class, () ->
                stage.process(extractionOutput(List.of(new Document("test")))));
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsEnabled() {
        stage.configure(Map.of("enabled", false));
        assertFalse(stage.isEnabled());
    }

    @Test
    void configureSetsEnabledViaAlternateKey() {
        stage.configure(Map.of("enablePreTokenization", false));
        assertFalse(stage.isEnabled());
    }

    @Test
    void configureSetsMaxTokenLength() throws Exception {
        stage.configure(Map.of("maxTokenLength", 3));
        ExtractionStage.ExtractionOutput input = extractionOutput(
                List.of(new Document("one two three four five six")));

        TokenizationStage.TokenizationOutput output = stage.process(input);

        TokenizationStage.TokenizedDocument tokenizedDoc = output.documents().get(0);
        assertEquals(3, tokenizedDoc.tokenCount());
    }

    // --- cancel / reset ---

    @Test
    void cancelAndResetCycle() {
        assertFalse(stage.isCancelled());
        stage.cancel();
        assertTrue(stage.isCancelled());
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // --- TokenOffset ---

    @Test
    void tokenOffsetLength() {
        TokenizationStage.TokenOffset offset = new TokenizationStage.TokenOffset(5, 10, 0);
        assertEquals(5, offset.length());
    }

    // --- TokenizedDocument ---

    @Test
    void tokenizedDocumentDelegatesTextAndId() {
        Document doc = new Document("doc-id", "Some text", Map.of("key", "val"));
        TokenizationStage.TokenizedDocument tokenized = new TokenizationStage.TokenizedDocument(
                doc, List.of(), 0, false);

        assertEquals("Some text", tokenized.getText());
        assertEquals("doc-id", tokenized.getId());
        assertEquals("val", tokenized.getMetadata().get("key"));
    }

    @Test
    void findTokenBoundaryReturnsTargetWhenNotTokenized() {
        Document doc = new Document("text");
        TokenizationStage.TokenizedDocument tokenized = new TokenizationStage.TokenizedDocument(
                doc, List.of(), 0, false);

        assertEquals(10, tokenized.findTokenBoundary(10));
    }

    @Test
    void findTokenBoundarySnapsToTokenEdge() {
        Document doc = new Document("hello world test");
        List<TokenizationStage.TokenOffset> offsets = List.of(
                new TokenizationStage.TokenOffset(0, 5, 0),   // "hello"
                new TokenizationStage.TokenOffset(6, 11, 1),  // "world"
                new TokenizationStage.TokenOffset(12, 16, 2)  // "test"
        );
        TokenizationStage.TokenizedDocument tokenized = new TokenizationStage.TokenizedDocument(
                doc, offsets, 3, true);

        // Target inside "world" (offset 8) should snap to end of "world" (11)
        int boundary = tokenized.findTokenBoundary(8);
        assertEquals(11, boundary);
    }

    // --- TokenizationOutput ---

    @Test
    void tokenizationOutputDocumentCountHandlesNull() {
        TokenizationStage.TokenizationOutput output = new TokenizationStage.TokenizationOutput(
                null, "loader", 0, 0, true, "task-1", null);
        assertEquals(0, output.documentCount());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        stage.process(extractionOutput(List.of(new Document("test"))));
        assertNotNull(stage.getMetrics());
    }

    // --- helpers ---

    private ExtractionStage.ExtractionOutput extractionOutput(List<Document> docs) {
        return new ExtractionStage.ExtractionOutput(
                docs, "test-loader", 100L, 10L, "task-1", Map.of()
        );
    }
}
