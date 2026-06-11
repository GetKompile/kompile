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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChunkingStageTest {

    private TextChunker mockChunker;
    private ChunkingStage stage;

    @BeforeEach
    void setUp() {
        mockChunker = mock(TextChunker.class);
        when(mockChunker.getName()).thenReturn("test-chunker");
        stage = new ChunkingStage(mockChunker);
    }

    // --- getName ---

    @Test
    void getNameReturnsChunking() {
        assertEquals("chunking", stage.getName());
    }

    // --- process: successful chunking ---

    @Test
    void processChunksDocuments() throws Exception {
        RetrievedDoc chunk1 = new RetrievedDoc("c1", "chunk one", Map.of());
        RetrievedDoc chunk2 = new RetrievedDoc("c2", "chunk two", Map.of());
        when(mockChunker.chunk(any(), any(), any())).thenReturn(List.of(chunk1, chunk2));

        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("Some long document text")));

        ChunkingStage.ChunkingOutput output = stage.process(input);

        assertEquals(2, output.chunkCount());
        assertEquals(1, output.documentsChunked());
        assertEquals("test-chunker", output.chunkerUsed());
        assertTrue(output.chunkingTimeMs() >= 0);
    }

    @Test
    void processMultipleDocumentsAccumulatesChunks() throws Exception {
        RetrievedDoc chunk = new RetrievedDoc("c", "chunk", Map.of());
        when(mockChunker.chunk(any(), any(), any())).thenReturn(List.of(chunk));

        TokenizationStage.TokenizationOutput input = tokenizationOutput(List.of(
                new Document("doc 1"), new Document("doc 2"), new Document("doc 3")));

        ChunkingStage.ChunkingOutput output = stage.process(input);

        assertEquals(3, output.chunkCount()); // 1 chunk per doc
        assertEquals(3, output.documentsChunked());
    }

    // --- process: null chunker ---

    @Test
    void processWithNullChunkerReturnsSingleChunkPerDoc() throws Exception {
        ChunkingStage noChunker = new ChunkingStage(null);
        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("Some text content")));

        ChunkingStage.ChunkingOutput output = noChunker.process(input);

        assertEquals(1, output.chunkCount());
        assertEquals("none", output.chunkerUsed());
    }

    // --- process: empty text ---

    @Test
    void processSkipsEmptyTextDocuments() throws Exception {
        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("")));

        ChunkingStage.ChunkingOutput output = stage.process(input);

        assertEquals(0, output.chunkCount());
        verify(mockChunker, never()).chunk(any(), any(), any());
    }

    @Test
    void processSkipsWhitespaceOnlyDocuments() throws Exception {
        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("   \t\n  ")));

        ChunkingStage.ChunkingOutput output = stage.process(input);

        assertEquals(0, output.chunkCount());
    }

    // --- process: chunker failure ---

    @Test
    void processReturnsWholeDocOnChunkerFailure() throws Exception {
        when(mockChunker.chunk(any(), any(), any())).thenThrow(new RuntimeException("chunker error"));

        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("fallback text")));

        ChunkingStage.ChunkingOutput output = stage.process(input);

        assertEquals(1, output.chunkCount()); // fallback: whole doc as single chunk
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        assertThrows(InterruptedException.class, () ->
                stage.process(tokenizationOutput(List.of(new Document("test")))));
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsChunkSize() {
        stage.configure(Map.of("chunkSize", 500));
        // No direct getter, but verify no exception
    }

    @Test
    void configureSetsOverlapViaAlternateKey() {
        stage.configure(Map.of("chunkOverlap", 50));
        // No direct getter, but verify no exception
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

    // --- getChunkerName ---

    @Test
    void getChunkerNameReturnsChunkerName() {
        assertEquals("test-chunker", stage.getChunkerName());
    }

    @Test
    void getChunkerNameReturnsNoneWhenNull() {
        ChunkingStage noChunker = new ChunkingStage(null);
        assertEquals("none", noChunker.getChunkerName());
    }

    // --- ChunkingOutput ---

    @Test
    void chunkingOutputCountHandlesNull() {
        ChunkingStage.ChunkingOutput output = new ChunkingStage.ChunkingOutput(
                null, 0, 0, "chunker", "loader", "task", Map.of());
        assertEquals(0, output.chunkCount());
    }

    @Test
    void chunkingOutputAverageChunksPerDocument() {
        ChunkingStage.ChunkingOutput output = new ChunkingStage.ChunkingOutput(
                List.of(
                        new RetrievedDoc("c1", "a", Map.of()),
                        new RetrievedDoc("c2", "b", Map.of()),
                        new RetrievedDoc("c3", "c", Map.of()),
                        new RetrievedDoc("c4", "d", Map.of())
                ), 2, 10, "chunker", "loader", "task", Map.of());
        assertEquals(2.0, output.averageChunksPerDocument(), 0.01);
    }

    @Test
    void chunkingOutputAverageHandlesZeroDocs() {
        ChunkingStage.ChunkingOutput output = new ChunkingStage.ChunkingOutput(
                List.of(), 0, 0, "chunker", "loader", "task", Map.of());
        assertEquals(0.0, output.averageChunksPerDocument());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        RetrievedDoc chunk = new RetrievedDoc("c", "chunk", Map.of());
        when(mockChunker.chunk(any(), any(), any())).thenReturn(List.of(chunk));

        stage.process(tokenizationOutput(List.of(new Document("text"))));
        assertNotNull(stage.getMetrics());
    }

    // --- helpers ---

    private TokenizationStage.TokenizationOutput tokenizationOutput(List<Document> docs) {
        List<TokenizationStage.TokenizedDocument> tokenizedDocs = docs.stream()
                .map(doc -> new TokenizationStage.TokenizedDocument(doc, List.of(), 0, false))
                .toList();
        return new TokenizationStage.TokenizationOutput(
                tokenizedDocs, "test-loader", 10L, 0, false, "task-1", Map.of()
        );
    }
}
