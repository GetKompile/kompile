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

import ai.kompile.app.core.extraction.ConcurrentExtractionOrchestrator;
import ai.kompile.app.core.extraction.ContentExtractor;
import ai.kompile.app.core.extraction.StructuredItem;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContentExtractionStageTest {

    private ConcurrentExtractionOrchestrator mockOrchestrator;
    private ContentExtractionStage stage;

    @BeforeEach
    void setUp() {
        mockOrchestrator = mock(ConcurrentExtractionOrchestrator.class);
        when(mockOrchestrator.getExtractors()).thenReturn(Map.of());
        stage = new ContentExtractionStage(mockOrchestrator);
    }

    // --- getName ---

    @Test
    void getNameReturnsContentExtraction() {
        assertEquals("content-extraction", stage.getName());
    }

    // --- process: successful extraction ---

    @Test
    void processExtractsContent() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                mock(ConcurrentExtractionOrchestrator.CombinedExtractionResult.class);
        when(mockResult.chunks()).thenReturn(List.of(
                new RetrievedDoc("c1", "chunk one", Map.of())));
        when(mockResult.structuredItems()).thenReturn(List.of());
        when(mockResult.itemsByType()).thenReturn(Map.of());
        when(mockResult.documentsProcessed()).thenReturn(1);
        when(mockResult.getChunkCount()).thenReturn(1);
        when(mockResult.getTotalStructuredItems()).thenReturn(0);
        when(mockResult.extractorsUsed()).thenReturn(List.of("chunking"));
        when(mockResult.errors()).thenReturn(List.of());
        when(mockResult.hasErrors()).thenReturn(false);
        when(mockResult.getChunksPerSecond()).thenReturn(100.0);
        when(mockOrchestrator.extractAll(any(), any())).thenReturn(mockResult);

        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("Some document text")));

        ContentExtractionStage.ContentExtractionOutput output = stage.process(input);

        assertEquals(1, output.chunkCount());
        assertEquals(0, output.structuredItemCount());
        assertFalse(output.hasErrors());
        assertTrue(output.extractionTimeMs() >= 0);
    }

    @Test
    void processWithStructuredItems() throws Exception {
        StructuredItem entity = mock(StructuredItem.class);

        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                mock(ConcurrentExtractionOrchestrator.CombinedExtractionResult.class);
        when(mockResult.chunks()).thenReturn(List.of(
                new RetrievedDoc("c1", "chunk", Map.of())));
        when(mockResult.structuredItems()).thenReturn(List.of(entity));
        when(mockResult.itemsByType()).thenReturn(Map.of(
                StructuredItem.ItemType.ENTITY, List.of(entity)));
        when(mockResult.documentsProcessed()).thenReturn(1);
        when(mockResult.getChunkCount()).thenReturn(1);
        when(mockResult.getTotalStructuredItems()).thenReturn(1);
        when(mockResult.extractorsUsed()).thenReturn(List.of("chunking", "entity"));
        when(mockResult.errors()).thenReturn(List.of());
        when(mockResult.hasErrors()).thenReturn(false);
        when(mockResult.getChunksPerSecond()).thenReturn(50.0);
        when(mockOrchestrator.extractAll(any(), any())).thenReturn(mockResult);

        TokenizationStage.TokenizationOutput input = tokenizationOutput(
                List.of(new Document("text with entities")));

        ContentExtractionStage.ContentExtractionOutput output = stage.process(input);

        assertTrue(output.hasStructuredOutput());
        assertEquals(1, output.getEntities().size());
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
    void configureSetsChunkingThreads() {
        stage.configure(Map.of("chunkingThreads", 8));
        verify(mockOrchestrator).setThreadCount(ContentExtractor.ExtractorType.CHUNKING, 8);
    }

    @Test
    void configureSetsStructuredExtractionThreads() {
        stage.configure(Map.of("structuredExtractionThreads", 4));
        verify(mockOrchestrator).setThreadCount(ContentExtractor.ExtractorType.ENTITY, 4);
        verify(mockOrchestrator).setThreadCount(ContentExtractor.ExtractorType.CONCEPT, 4);
        verify(mockOrchestrator).setThreadCount(ContentExtractor.ExtractorType.FACT, 4);
    }

    // --- cancel / reset ---

    @Test
    void cancelAndResetCycle() {
        assertFalse(stage.isCancelled());
        stage.cancel();
        assertTrue(stage.isCancelled());
        verify(mockOrchestrator).cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // --- getOrchestrator ---

    @Test
    void getOrchestratorReturnsOrchestrator() {
        assertSame(mockOrchestrator, stage.getOrchestrator());
    }

    // --- ContentExtractionOutput ---

    @Test
    void outputAverageChunksPerDocument() {
        ContentExtractionStage.ContentExtractionOutput output =
                new ContentExtractionStage.ContentExtractionOutput(
                        List.of(), List.of(), Map.of(),
                        4, 12, 0, 100,
                        List.of(), "loader", "task", Map.of(), List.of());
        assertEquals(3.0, output.averageChunksPerDocument(), 0.01);
    }

    @Test
    void outputAverageChunksHandlesZeroDocs() {
        ContentExtractionStage.ContentExtractionOutput output =
                new ContentExtractionStage.ContentExtractionOutput(
                        List.of(), List.of(), Map.of(),
                        0, 0, 0, 100,
                        List.of(), "loader", "task", Map.of(), List.of());
        assertEquals(0.0, output.averageChunksPerDocument());
    }

    @Test
    void outputHasErrorsWhenErrorsPresent() {
        ContentExtractionStage.ContentExtractionOutput output =
                new ContentExtractionStage.ContentExtractionOutput(
                        List.of(), List.of(), Map.of(),
                        1, 0, 0, 100,
                        List.of(), "loader", "task", Map.of(),
                        List.of("some error"));
        assertTrue(output.hasErrors());
    }

    @Test
    void outputItemTypeAccessors() {
        StructuredItem entity = mock(StructuredItem.class);
        StructuredItem concept = mock(StructuredItem.class);
        StructuredItem fact = mock(StructuredItem.class);
        StructuredItem table = mock(StructuredItem.class);
        StructuredItem relationship = mock(StructuredItem.class);

        Map<StructuredItem.ItemType, List<StructuredItem>> byType = Map.of(
                StructuredItem.ItemType.ENTITY, List.of(entity),
                StructuredItem.ItemType.CONCEPT, List.of(concept),
                StructuredItem.ItemType.FACT, List.of(fact),
                StructuredItem.ItemType.TABLE, List.of(table),
                StructuredItem.ItemType.RELATIONSHIP, List.of(relationship)
        );

        ContentExtractionStage.ContentExtractionOutput output =
                new ContentExtractionStage.ContentExtractionOutput(
                        List.of(), List.of(), byType,
                        1, 0, 5, 100,
                        List.of(), "loader", "task", Map.of(), List.of());

        assertEquals(1, output.getEntities().size());
        assertEquals(1, output.getConcepts().size());
        assertEquals(1, output.getFacts().size());
        assertEquals(1, output.getTables().size());
        assertEquals(1, output.getRelationships().size());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                mock(ConcurrentExtractionOrchestrator.CombinedExtractionResult.class);
        when(mockResult.chunks()).thenReturn(List.of());
        when(mockResult.structuredItems()).thenReturn(List.of());
        when(mockResult.itemsByType()).thenReturn(Map.of());
        when(mockResult.documentsProcessed()).thenReturn(0);
        when(mockResult.getChunkCount()).thenReturn(0);
        when(mockResult.getTotalStructuredItems()).thenReturn(0);
        when(mockResult.extractorsUsed()).thenReturn(List.of());
        when(mockResult.errors()).thenReturn(List.of());
        when(mockResult.hasErrors()).thenReturn(false);
        when(mockResult.getChunksPerSecond()).thenReturn(0.0);
        when(mockOrchestrator.extractAll(any(), any())).thenReturn(mockResult);

        stage.process(tokenizationOutput(List.of(new Document("test"))));
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
