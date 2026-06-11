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

package ai.kompile.app.services.pipeline;

import ai.kompile.app.services.pipeline.stages.ExtractionStage;
import ai.kompile.app.services.pipeline.stages.TokenizationStage;
import ai.kompile.app.services.pipeline.stages.TokenizationStage.TokenOffset;
import ai.kompile.app.services.pipeline.stages.TokenizationStage.TokenizationOutput;
import ai.kompile.app.services.pipeline.stages.TokenizationStage.TokenizedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenizationStage} — whitespace/punctuation tokenization,
 * passthrough mode, token boundary detection, cancellation, and configuration.
 */
class TokenizationStageTest {

    private TokenizationStage stage;

    @BeforeEach
    void setUp() {
        stage = new TokenizationStage();
    }

    private ExtractionStage.ExtractionOutput extractionOutput(List<Document> docs) {
        return new ExtractionStage.ExtractionOutput(docs, "test-loader", 0L, 0L, "task-1", Map.of());
    }

    // ─── Basic tokenization ─────────────────────────────────────────────

    @Test
    void tokenizesSimpleSentence() throws Exception {
        Document doc = new Document("Hello world foo");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        assertEquals(1, output.documentCount());
        TokenizedDocument td = output.documents().get(0);
        assertTrue(td.wasTokenized());
        assertEquals(3, td.tokenCount());
        assertEquals(3, td.tokenOffsets().size());
        // "Hello" at 0-5
        assertEquals(0, td.tokenOffsets().get(0).startChar());
        assertEquals(5, td.tokenOffsets().get(0).endChar());
    }

    @Test
    void tokenizesPunctuationDelimiters() throws Exception {
        Document doc = new Document("a.b,c;d:e!f?g");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        TokenizedDocument td = output.documents().get(0);
        assertEquals(7, td.tokenCount()); // a b c d e f g
    }

    @Test
    void tokenizesMultipleDocuments() throws Exception {
        List<Document> docs = List.of(
                new Document("one two"),
                new Document("three four five")
        );
        TokenizationOutput output = stage.process(extractionOutput(docs));

        assertEquals(2, output.documentCount());
        assertEquals(5, output.totalTokens()); // 2 + 3
        assertTrue(output.tokenizationEnabled());
    }

    // ─── Empty/null text handling ────────────────────────────────────────

    @Test
    void handlesWhitespaceOnlyText() throws Exception {
        Document doc = new Document("   ");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        TokenizedDocument td = output.documents().get(0);
        assertTrue(td.wasTokenized());
        assertEquals(0, td.tokenCount());
        assertTrue(td.tokenOffsets().isEmpty());
    }

    @Test
    void handlesEmptyText() throws Exception {
        Document doc = new Document("");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        TokenizedDocument td = output.documents().get(0);
        assertEquals(0, td.tokenCount());
    }

    // ─── Passthrough mode (disabled) ─────────────────────────────────────

    @Test
    void passthroughWhenDisabled() throws Exception {
        stage.setEnabled(false);
        Document doc = new Document("Hello world");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        TokenizedDocument td = output.documents().get(0);
        assertFalse(td.wasTokenized());
        assertEquals(0, td.tokenCount());
        assertTrue(td.tokenOffsets().isEmpty());
        assertFalse(output.tokenizationEnabled());
    }

    // ─── maxTokenLength truncation ───────────────────────────────────────

    @Test
    void truncatesAtMaxTokenLength() throws Exception {
        stage.configure(Map.of("maxTokenLength", 2));
        Document doc = new Document("one two three four five");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));

        TokenizedDocument td = output.documents().get(0);
        assertEquals(2, td.tokenCount());
        assertEquals(2, td.tokenOffsets().size());
    }

    // ─── Token boundary detection ────────────────────────────────────────

    @Test
    void findTokenBoundaryReturnsEndOfTokenWhenInsideToken() throws Exception {
        Document doc = new Document("Hello world");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));
        TokenizedDocument td = output.documents().get(0);

        // offset 3 is inside "Hello" (0-5), should return 5 (end of token)
        assertEquals(5, td.findTokenBoundary(3));
    }

    @Test
    void findTokenBoundaryReturnsStartOfNextTokenWhenBetweenTokens() throws Exception {
        Document doc = new Document("Hello world");
        TokenizationOutput output = stage.process(extractionOutput(List.of(doc)));
        TokenizedDocument td = output.documents().get(0);

        // offset 5 is after "Hello" but before "world" (6-11)
        // Binary search lands on "world" token, targetOffset 5 < startChar 6, so returns startChar
        assertEquals(6, td.findTokenBoundary(5));
    }

    @Test
    void findTokenBoundaryPassthroughWhenNotTokenized() {
        TokenizedDocument td = new TokenizedDocument(
                new Document("text"), List.of(), 0, false
        );
        assertEquals(42, td.findTokenBoundary(42));
    }

    // ─── Configuration ───────────────────────────────────────────────────

    @Test
    void configureAcceptsAllKeys() {
        stage.configure(Map.of(
                "enabled", false,
                "maxTokenLength", 256,
                "tokenizerModel", "bert"
        ));
        assertFalse(stage.isEnabled());
    }

    @Test
    void configureEnablePreTokenizationKey() {
        stage.configure(Map.of("enablePreTokenization", false));
        assertFalse(stage.isEnabled());
    }

    @Test
    void configureNullIsNoOp() {
        stage.configure(null);
        assertTrue(stage.isEnabled()); // unchanged default
    }

    // ─── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancelBeforeProcessing() {
        stage.cancel();
        assertTrue(stage.isCancelled());

        assertThrows(InterruptedException.class, () ->
                stage.process(extractionOutput(List.of(new Document("text")))));
    }

    @Test
    void resetClearsCancellation() {
        stage.cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Stage metadata ──────────────────────────────────────────────────

    @Test
    void stageNameIsTokenization() {
        assertEquals("tokenization", stage.getName());
    }

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        stage.process(extractionOutput(List.of(new Document("word"))));
        assertEquals(1, stage.getMetrics().getItemsProcessed());
        assertEquals(0, stage.getMetrics().getItemsFailed());
    }

    // ─── TokenOffset record ──────────────────────────────────────────────

    @Test
    void tokenOffsetLength() {
        TokenOffset offset = new TokenOffset(5, 10, 0);
        assertEquals(5, offset.length());
    }

    // ─── Output helpers ──────────────────────────────────────────────────

    @Test
    void outputDocumentCountHandlesNull() {
        TokenizationOutput output = new TokenizationOutput(null, null, 0, 0, false, null, null);
        assertEquals(0, output.documentCount());
    }

    @Test
    void outputPreservesTaskIdAndMetadata() throws Exception {
        ExtractionStage.ExtractionOutput input = new ExtractionStage.ExtractionOutput(
                List.of(new Document("text")), "loader", 100L, 10L, "my-task", Map.of("key", "val")
        );
        TokenizationOutput output = stage.process(input);

        assertEquals("my-task", output.taskId());
        assertEquals("val", output.metadata().get("key"));
        assertEquals("loader", output.loaderUsed());
    }
}
