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

import ai.kompile.app.services.ContextualChunkEnricher;
import ai.kompile.app.services.ContextualRagConfigService;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextualEnrichmentStageTest {

    private ContextualChunkEnricher mockEnricher;
    private ContextualRagConfigService mockConfigService;
    private ContextualEnrichmentStage stage;

    @BeforeEach
    void setUp() {
        mockEnricher = mock(ContextualChunkEnricher.class);
        mockConfigService = mock(ContextualRagConfigService.class);
        stage = new ContextualEnrichmentStage(mockEnricher, mockConfigService);
    }

    // --- getName ---

    @Test
    void getNameReturnsContextualEnrichment() {
        assertEquals("contextual-enrichment", stage.getName());
    }

    // --- process: enrichment disabled ---

    @Test
    void processPassesThroughWhenDisabled() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(false);

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "chunk text", Map.of())));

        ContextualEnrichmentStage.ContextualEnrichmentOutput output = stage.process(input);

        assertEquals(1, output.chunkCount());
        assertFalse(output.enrichmentApplied());
        assertEquals(0, output.chunksEnriched());
        verify(mockEnricher, never()).enrichChunks(any(), any(), any());
    }

    // --- process: enrichment enabled ---

    @Test
    void processEnrichesChunksWhenEnabled() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(true);

        RetrievedDoc enrichedChunk = new RetrievedDoc("c1", "enriched chunk text",
                Map.of(SourceMetadataConstants.SOURCE_ID, "doc1"));
        when(mockEnricher.enrichChunks(any(), any(), any()))
                .thenReturn(List.of(enrichedChunk));

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "original text",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc1"))));

        ContextualEnrichmentStage.ContextualEnrichmentOutput output = stage.process(input);

        assertEquals(1, output.chunkCount());
        assertTrue(output.enrichmentApplied());
        assertEquals(1, output.chunksEnriched());
        verify(mockEnricher).enrichChunks(any(), any(), any());
    }

    @Test
    void processGroupsChunksBySourceId() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(true);
        when(mockEnricher.enrichChunks(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0)); // return input as-is

        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "text1",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc1")),
                new RetrievedDoc("c2", "text2",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc1")),
                new RetrievedDoc("c3", "text3",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc2"))
        );

        ChunkingStage.ChunkingOutput input = chunkingOutput(chunks);
        ContextualEnrichmentStage.ContextualEnrichmentOutput output = stage.process(input);

        assertEquals(3, output.chunkCount());
        assertEquals(2, output.documentsProcessed());
        // enrichChunks called once per source document group
        verify(mockEnricher, times(2)).enrichChunks(any(), any(), any());
    }

    // --- process: enrichment failure fallback ---

    @Test
    void processFallsBackToOriginalChunksOnEnricherError() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(true);
        when(mockEnricher.enrichChunks(any(), any(), any()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "original text",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc1"))));

        ContextualEnrichmentStage.ContextualEnrichmentOutput output = stage.process(input);

        // Should still return the original chunk despite error
        assertEquals(1, output.chunkCount());
        assertEquals("original text", output.chunks().get(0).getText());
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        assertThrows(InterruptedException.class, () ->
                stage.process(chunkingOutput(List.of(
                        new RetrievedDoc("c1", "text", Map.of())))));
    }

    // --- setDocumentTexts ---

    @Test
    void setDocumentTextsProvidesContext() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(true);
        when(mockEnricher.enrichChunks(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        stage.setDocumentTexts(Map.of("doc1", "Full document text here"));

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "chunk",
                        Map.of(SourceMetadataConstants.SOURCE_ID, "doc1"))));

        stage.process(input);

        // Verify enricher received the document text
        verify(mockEnricher).enrichChunks(any(), eq("Full document text here"), any());
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsDocumentTexts() {
        stage.configure(Map.of("documentTexts", Map.of("d1", "text1")));
        // No exception — document texts are set internally
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

    // --- ContextualEnrichmentOutput ---

    @Test
    void outputChunkCountHandlesNull() {
        ContextualEnrichmentStage.ContextualEnrichmentOutput output =
                new ContextualEnrichmentStage.ContextualEnrichmentOutput(
                        null, 0, 0, 0, false, "loader", "task", Map.of());
        assertEquals(0, output.chunkCount());
    }

    @Test
    void outputEnrichmentRatio() {
        ContextualEnrichmentStage.ContextualEnrichmentOutput output =
                new ContextualEnrichmentStage.ContextualEnrichmentOutput(
                        List.of(
                                new RetrievedDoc("c1", "a", Map.of()),
                                new RetrievedDoc("c2", "b", Map.of()),
                                new RetrievedDoc("c3", "c", Map.of()),
                                new RetrievedDoc("c4", "d", Map.of())
                        ), 2, 3, 100, true, "loader", "task", Map.of());
        assertEquals(0.75, output.enrichmentRatio(), 0.01);
    }

    @Test
    void outputEnrichmentRatioHandlesZeroChunks() {
        ContextualEnrichmentStage.ContextualEnrichmentOutput output =
                new ContextualEnrichmentStage.ContextualEnrichmentOutput(
                        List.of(), 0, 0, 0, false, "loader", "task", Map.of());
        assertEquals(0.0, output.enrichmentRatio());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        when(mockConfigService.isEnabled()).thenReturn(false);

        stage.process(chunkingOutput(List.of(
                new RetrievedDoc("c1", "text", Map.of()))));
        assertNotNull(stage.getMetrics());
    }

    // --- helpers ---

    private ChunkingStage.ChunkingOutput chunkingOutput(List<RetrievedDoc> chunks) {
        return new ChunkingStage.ChunkingOutput(
                chunks, chunks.size(), 0, "test-chunker", "test-loader", "task-1", Map.of()
        );
    }
}
