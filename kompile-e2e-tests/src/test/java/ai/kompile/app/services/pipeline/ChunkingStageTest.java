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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.pipeline.stages.ChunkingStage;
import ai.kompile.app.services.pipeline.stages.ChunkingStage.ChunkingOutput;
import ai.kompile.app.services.pipeline.stages.TokenizationStage;
import ai.kompile.app.services.pipeline.stages.TokenizationStage.TokenizedDocument;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ChunkingStage} — null chunker passthrough, delegation,
 * empty text handling, error fallback, cancellation, and configuration.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ChunkingStageTest {

    @Mock
    private TextChunker chunker;

    private ChunkingStage stage;

    @BeforeEach
    void setUp() {
        when(chunker.getName()).thenReturn("mock-chunker");
        stage = new ChunkingStage(chunker);
    }

    private TokenizationStage.TokenizationOutput tokenizationOutput(List<TokenizedDocument> docs) {
        return new TokenizationStage.TokenizationOutput(
                docs, "test-loader", 10L, 100, true, "task-1", Map.of()
        );
    }

    private TokenizedDocument tokenizedDoc(String text) {
        return new TokenizedDocument(
                new Document(text), List.of(), 0, false
        );
    }

    // ─── Null chunker passthrough ────────────────────────────────────────

    @Test
    void nullChunkerReturnsSingleChunkPerDocument() throws Exception {
        ChunkingStage nullStage = new ChunkingStage(null);
        TokenizedDocument doc = tokenizedDoc("Hello world");

        ChunkingOutput output = nullStage.process(tokenizationOutput(List.of(doc)));

        assertEquals(1, output.chunkCount());
        assertEquals("Hello world", output.chunks().get(0).getText());
        assertEquals("none", output.chunkerUsed());
    }

    @Test
    void nullChunkerNameIsNone() {
        ChunkingStage nullStage = new ChunkingStage(null);
        assertEquals("none", nullStage.getChunkerName());
    }

    // ─── Delegation to chunker ───────────────────────────────────────────

    @Test
    void delegatesChunkingToChunker() throws Exception {
        RetrievedDoc chunk1 = RetrievedDoc.builder().id("c1").text("part one").build();
        RetrievedDoc chunk2 = RetrievedDoc.builder().id("c2").text("part two").build();
        when(chunker.chunk(any(RetrievedDoc.class), anyMap(), any())).thenReturn(List.of(chunk1, chunk2));

        TokenizedDocument doc = tokenizedDoc("part one part two");
        ChunkingOutput output = stage.process(tokenizationOutput(List.of(doc)));

        assertEquals(2, output.chunkCount());
        assertEquals(1, output.documentsChunked());
        assertEquals("mock-chunker", output.chunkerUsed());
    }

    @Test
    void multipleDocumentsChunked() throws Exception {
        when(chunker.chunk(any(RetrievedDoc.class), anyMap(), any()))
                .thenReturn(List.of(RetrievedDoc.builder().text("chunk").build()));

        List<TokenizedDocument> docs = List.of(
                tokenizedDoc("doc one"),
                tokenizedDoc("doc two"),
                tokenizedDoc("doc three")
        );
        ChunkingOutput output = stage.process(tokenizationOutput(docs));

        assertEquals(3, output.chunkCount());
        assertEquals(3, output.documentsChunked());
    }

    // ─── Empty/null text handling ────────────────────────────────────────

    @Test
    void emptyTextSkippedReturnsEmptyChunks() throws Exception {
        TokenizedDocument doc = tokenizedDoc("");
        ChunkingOutput output = stage.process(tokenizationOutput(List.of(doc)));

        assertEquals(0, output.chunkCount());
        assertEquals(1, output.documentsChunked());
    }

    @Test
    void whitespaceOnlyTextReturnsEmptyChunks() throws Exception {
        TokenizedDocument doc = tokenizedDoc("   ");
        ChunkingOutput output = stage.process(tokenizationOutput(List.of(doc)));

        // Whitespace-only text is trimmed and treated as empty
        assertEquals(0, output.chunkCount());
    }

    // ─── Chunker failure fallback ────────────────────────────────────────

    @Test
    void fallsBackToWholeDocumentOnChunkerError() throws Exception {
        when(chunker.chunk(any(RetrievedDoc.class), anyMap(), any()))
                .thenThrow(new RuntimeException("chunking error"));

        TokenizedDocument doc = tokenizedDoc("content to preserve");
        ChunkingOutput output = stage.process(tokenizationOutput(List.of(doc)));

        assertEquals(1, output.chunkCount());
        assertEquals("content to preserve", output.chunks().get(0).getText());
    }

    @Test
    void fallsBackWhenChunkerReturnsNull() throws Exception {
        when(chunker.chunk(any(RetrievedDoc.class), anyMap(), any())).thenReturn(null);

        TokenizedDocument doc = tokenizedDoc("text");
        ChunkingOutput output = stage.process(tokenizationOutput(List.of(doc)));

        assertEquals(1, output.chunkCount());
        assertEquals("text", output.chunks().get(0).getText());
    }

    // ─── Configuration ───────────────────────────────────────────────────

    @Test
    void configureChunkSizeAndOverlap() {
        stage.configure(Map.of("chunkSize", 500, "overlap", 100));
        // No assertion on internal state, but verify no exception
    }

    @Test
    void configureChunkOverlapKey() {
        stage.configure(Map.of("chunkOverlap", 50));
        // Accepts alternate key name
    }

    @Test
    void configurePreserveParagraphs() {
        stage.configure(Map.of("preserveParagraphs", false));
    }

    @Test
    void configureUseTokenBoundaries() {
        stage.configure(Map.of("useTokenBoundaries", false));
    }

    @Test
    void configureNullIsNoOp() {
        stage.configure(null);
    }

    // ─── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancelBeforeProcessing() {
        stage.cancel();
        assertTrue(stage.isCancelled());

        assertThrows(InterruptedException.class, () ->
                stage.process(tokenizationOutput(List.of(tokenizedDoc("text")))));
    }

    @Test
    void resetClearsCancellation() {
        stage.cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Stage metadata ──────────────────────────────────────────────────

    @Test
    void stageNameIsChunking() {
        assertEquals("chunking", stage.getName());
    }

    @Test
    void chunkerNameDelegates() {
        assertEquals("mock-chunker", stage.getChunkerName());
    }

    // ─── Output helpers ──────────────────────────────────────────────────

    @Test
    void chunkCountHandlesNull() {
        ChunkingOutput output = new ChunkingOutput(null, 0, 0L, "none", "loader", null, null);
        assertEquals(0, output.chunkCount());
    }

    @Test
    void averageChunksPerDocumentCalculation() {
        ChunkingOutput output = new ChunkingOutput(
                List.of(
                        RetrievedDoc.builder().text("a").build(),
                        RetrievedDoc.builder().text("b").build(),
                        RetrievedDoc.builder().text("c").build(),
                        RetrievedDoc.builder().text("d").build()
                ),
                2, 100L, "chunker", "loader", null, null
        );
        assertEquals(2.0, output.averageChunksPerDocument(), 0.001);
    }

    @Test
    void averageChunksPerDocumentZeroDocs() {
        ChunkingOutput output = new ChunkingOutput(List.of(), 0, 0L, "none", "loader", null, null);
        assertEquals(0.0, output.averageChunksPerDocument(), 0.001);
    }

    @Test
    void outputPreservesTaskIdAndMetadata() throws Exception {
        when(chunker.chunk(any(RetrievedDoc.class), anyMap(), any()))
                .thenReturn(List.of(RetrievedDoc.builder().text("chunk").build()));

        TokenizationStage.TokenizationOutput input = new TokenizationStage.TokenizationOutput(
                List.of(tokenizedDoc("text")), "my-loader", 5L, 10, true, "my-task", Map.of("k", "v")
        );
        ChunkingOutput output = stage.process(input);

        assertEquals("my-task", output.taskId());
        assertEquals("v", output.metadata().get("k"));
        assertEquals("my-loader", output.loaderUsed());
    }
}
