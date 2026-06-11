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

import ai.kompile.app.services.ContextualChunkEnricher;
import ai.kompile.app.services.ContextualRagConfigService;
import ai.kompile.app.services.pipeline.stages.ChunkingStage;
import ai.kompile.app.services.pipeline.stages.ContextualEnrichmentStage;
import ai.kompile.app.services.pipeline.stages.ContextualEnrichmentStage.ContextualEnrichmentOutput;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceMetadataConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContextualEnrichmentStage} — enrichment enabled/disabled,
 * grouping by source, error fallback, cancellation, configuration, and output records.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ContextualEnrichmentStageTest {

    @Mock
    private ContextualChunkEnricher enricher;

    @Mock
    private ContextualRagConfigService configService;

    private ContextualEnrichmentStage stage;

    @BeforeEach
    void setUp() {
        stage = new ContextualEnrichmentStage(enricher, configService);
    }

    private RetrievedDoc chunk(String id, String text, String sourceId) {
        return RetrievedDoc.builder()
                .id(id)
                .text(text)
                .metadata(Map.of(SourceMetadataConstants.SOURCE_ID, sourceId))
                .build();
    }

    private RetrievedDoc chunkWithFilename(String id, String text, String sourceId, String filename) {
        return RetrievedDoc.builder()
                .id(id)
                .text(text)
                .metadata(Map.of(
                        SourceMetadataConstants.SOURCE_ID, sourceId,
                        SourceMetadataConstants.SOURCE_FILENAME, filename))
                .build();
    }

    private ChunkingStage.ChunkingOutput chunkingOutput(List<RetrievedDoc> chunks) {
        return new ChunkingStage.ChunkingOutput(
                chunks, 1, 50L, "test-chunker", "test-loader", "task-1", Map.of());
    }

    // ─── Name ──────────────────────────────────────────────────────────

    @Test
    void getName() {
        assertEquals("contextual-enrichment", stage.getName());
    }

    // ─── Disabled passthrough ──────────────────────────────────────────

    @Test
    void process_enrichmentDisabled_passesThrough() throws Exception {
        when(configService.isEnabled()).thenReturn(false);
        List<RetrievedDoc> chunks = List.of(chunk("c1", "hello", "src1"));
        ChunkingStage.ChunkingOutput input = chunkingOutput(chunks);

        ContextualEnrichmentOutput output = stage.process(input);

        assertEquals(1, output.chunkCount());
        assertFalse(output.enrichmentApplied());
        assertEquals(0, output.chunksEnriched());
        assertSame(chunks, output.chunks());
        verifyNoInteractions(enricher);
    }

    // ─── Enabled — enricher called ─────────────────────────────────────

    @Test
    void process_enrichmentEnabled_callsEnricher() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = chunk("c1", "content", "src1");
        List<RetrievedDoc> enriched = List.of(
                RetrievedDoc.builder().id("c1").text("enriched content").metadata(Map.of()).build());
        when(enricher.enrichChunks(anyList(), isNull(), anyString())).thenReturn(enriched);

        ContextualEnrichmentOutput output = stage.process(chunkingOutput(List.of(c1)));

        assertTrue(output.enrichmentApplied());
        assertEquals(1, output.chunksEnriched());
        assertEquals("enriched content", output.chunks().get(0).getText());
    }

    // ─── Document text passed to enricher ──────────────────────────────

    @Test
    void process_documentTextPassedToEnricher() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        stage.setDocumentTexts(Map.of("src1", "full document text"));
        RetrievedDoc c1 = chunk("c1", "content", "src1");
        when(enricher.enrichChunks(anyList(), eq("full document text"), anyString()))
                .thenReturn(List.of(c1));

        stage.process(chunkingOutput(List.of(c1)));

        verify(enricher).enrichChunks(anyList(), eq("full document text"), anyString());
    }

    // ─── Grouping by source ────────────────────────────────────────────

    @Test
    void process_groupsChunksBySourceId() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = chunk("c1", "text1", "src1");
        RetrievedDoc c2 = chunk("c2", "text2", "src1");
        RetrievedDoc c3 = chunk("c3", "text3", "src2");
        when(enricher.enrichChunks(anyList(), any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        ContextualEnrichmentOutput output = stage.process(chunkingOutput(List.of(c1, c2, c3)));

        assertEquals(3, output.chunkCount());
        assertEquals(2, output.documentsProcessed());
        verify(enricher, times(2)).enrichChunks(anyList(), any(), anyString());
    }

    // ─── Enricher failure falls back to original chunks ────────────────

    @Test
    void process_enricherFailure_fallsBackToOriginal() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = chunk("c1", "content", "src1");
        when(enricher.enrichChunks(anyList(), any(), anyString()))
                .thenThrow(new RuntimeException("LLM error"));

        ContextualEnrichmentOutput output = stage.process(chunkingOutput(List.of(c1)));

        assertTrue(output.enrichmentApplied());
        assertEquals(1, output.chunkCount());
        assertEquals("content", output.chunks().get(0).getText());
        assertEquals(0, output.chunksEnriched());
    }

    // ─── Cancellation ──────────────────────────────────────────────────

    @Test
    void process_cancelledBefore_throwsInterrupted() {
        stage.cancel();
        assertTrue(stage.isCancelled());
        assertThrows(InterruptedException.class,
                () -> stage.process(chunkingOutput(List.of())));
    }

    @Test
    void process_cancelledDuringProcessing_throwsInterrupted() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = chunk("c1", "text", "src1");
        RetrievedDoc c2 = chunk("c2", "text", "src2");
        when(enricher.enrichChunks(anyList(), any(), anyString())).thenAnswer(inv -> {
            stage.cancel();
            return inv.getArgument(0);
        });

        assertThrows(InterruptedException.class,
                () -> stage.process(chunkingOutput(List.of(c1, c2))));
    }

    // ─── Reset ─────────────────────────────────────────────────────────

    @Test
    void reset_clearsCancelledAndDocumentTexts() throws Exception {
        stage.cancel();
        stage.setDocumentTexts(Map.of("src1", "text"));
        stage.reset();

        assertFalse(stage.isCancelled());
        // After reset, document texts are cleared. If enrichment enabled, enricher gets null docText
        when(configService.isEnabled()).thenReturn(true);
        when(enricher.enrichChunks(anyList(), isNull(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        RetrievedDoc c1 = chunk("c1", "text", "src1");
        stage.process(chunkingOutput(List.of(c1)));
        verify(enricher).enrichChunks(anyList(), isNull(), anyString());
    }

    // ─── Configure ─────────────────────────────────────────────────────

    @Test
    void configure_null_noOp() {
        assertDoesNotThrow(() -> stage.configure(null));
    }

    @Test
    void configure_setsDocumentTexts() throws Exception {
        stage.configure(Map.of("documentTexts", Map.of("src1", "full text")));
        when(configService.isEnabled()).thenReturn(true);
        when(enricher.enrichChunks(anyList(), eq("full text"), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        stage.process(chunkingOutput(List.of(chunk("c1", "text", "src1"))));
        verify(enricher).enrichChunks(anyList(), eq("full text"), anyString());
    }

    // ─── Metrics ───────────────────────────────────────────────────────

    @Test
    void metrics_initiallyZero() {
        PipelineStage.StageMetrics m = stage.getMetrics();
        assertNotNull(m);
    }

    // ─── setDocumentTexts null safety ──────────────────────────────────

    @Test
    void setDocumentTexts_null_clearsMap() {
        stage.setDocumentTexts(null);
        assertDoesNotThrow(() -> {
            when(configService.isEnabled()).thenReturn(false);
            stage.process(chunkingOutput(List.of(chunk("c1", "text", "src1"))));
        });
    }

    // ─── Title extraction from metadata ────────────────────────────────

    @Test
    void process_extractsTitleFromFilename() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = chunkWithFilename("c1", "text", "src1", "report.pdf");
        when(enricher.enrichChunks(anyList(), any(), eq("report.pdf")))
                .thenReturn(List.of(c1));

        stage.process(chunkingOutput(List.of(c1)));
        verify(enricher).enrichChunks(anyList(), any(), eq("report.pdf"));
    }

    @Test
    void process_extractsTitleFromPath() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = RetrievedDoc.builder()
                .id("c1").text("text")
                .metadata(Map.of(
                        SourceMetadataConstants.SOURCE_ID, "src1",
                        SourceMetadataConstants.SOURCE_PATH, "/data/files/report.pdf"))
                .build();
        when(enricher.enrichChunks(anyList(), any(), eq("report.pdf")))
                .thenReturn(List.of(c1));

        stage.process(chunkingOutput(List.of(c1)));
        verify(enricher).enrichChunks(anyList(), any(), eq("report.pdf"));
    }

    // ─── Chunk with null metadata groups to "unknown" ──────────────────

    @Test
    void process_nullMetadataChunk_groupsToUnknown() throws Exception {
        when(configService.isEnabled()).thenReturn(true);
        RetrievedDoc c1 = RetrievedDoc.builder().id("c1").text("text").build();
        when(enricher.enrichChunks(anyList(), any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        ContextualEnrichmentOutput output = stage.process(chunkingOutput(List.of(c1)));
        assertEquals(1, output.chunkCount());
    }

    // ─── Output record computed fields ─────────────────────────────────

    @Test
    void outputRecord_chunkCount() {
        ContextualEnrichmentOutput output = new ContextualEnrichmentOutput(
                List.of(RetrievedDoc.builder().id("c1").text("t").build()),
                1, 1, 100L, true, "loader", "task", Map.of());
        assertEquals(1, output.chunkCount());
    }

    @Test
    void outputRecord_enrichmentRatio() {
        ContextualEnrichmentOutput output = new ContextualEnrichmentOutput(
                List.of(
                        RetrievedDoc.builder().id("c1").text("t").build(),
                        RetrievedDoc.builder().id("c2").text("t").build()),
                1, 1, 100L, true, "loader", "task", Map.of());
        assertEquals(0.5, output.enrichmentRatio(), 0.01);
    }

    @Test
    void outputRecord_nullChunks_chunkCountZero() {
        ContextualEnrichmentOutput output = new ContextualEnrichmentOutput(
                null, 0, 0, 0L, false, null, null, null);
        assertEquals(0, output.chunkCount());
        assertEquals(0.0, output.enrichmentRatio());
    }
}
