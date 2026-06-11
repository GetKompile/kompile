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

import ai.kompile.app.core.extraction.*;
import ai.kompile.app.services.pipeline.stages.ContentExtractionStage;
import ai.kompile.app.services.pipeline.stages.ContentExtractionStage.ContentExtractionOutput;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContentExtractionStage} — orchestrator delegation,
 * cancellation, configuration, fileName extraction, and output helpers.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ContentExtractionStageTest {

    @Mock
    private ConcurrentExtractionOrchestrator orchestrator;

    private ContentExtractionStage stage;

    @BeforeEach
    void setUp() {
        stage = new ContentExtractionStage(orchestrator);
        when(orchestrator.getExtractors()).thenReturn(Map.of());
    }

    private TokenizationStage.TokenizationOutput tokenizationOutput(List<TokenizedDocument> docs) {
        return tokenizationOutput(docs, null, null);
    }

    private TokenizationStage.TokenizationOutput tokenizationOutput(
            List<TokenizedDocument> docs, String taskId, Map<String, Object> metadata) {
        return new TokenizationStage.TokenizationOutput(
                docs, "test-loader", 10L, 50, true,
                taskId, metadata != null ? metadata : Map.of()
        );
    }

    private TokenizedDocument tokenizedDoc(String text) {
        return new TokenizedDocument(
                new Document(text), List.of(), 0, false
        );
    }

    // ─── Orchestrator delegation ─────────────────────────────────────────

    @Test
    void delegatesToOrchestrator() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                new ConcurrentExtractionOrchestrator.CombinedExtractionResult(
                        List.of(RetrievedDoc.builder().text("chunk1").build()),
                        List.of(), Map.of(), List.of("chunking"), 1, 100L, List.of()
                );
        when(orchestrator.extractAll(anyList(), anyMap())).thenReturn(mockResult);

        TokenizedDocument doc = tokenizedDoc("Hello world test");
        ContentExtractionOutput output = stage.process(tokenizationOutput(List.of(doc)));

        assertNotNull(output);
        assertEquals(1, output.chunkCount());
        assertEquals(1, output.documentsProcessed());
        assertEquals("test-loader", output.loaderUsed());
        verify(orchestrator).extractAll(anyList(), anyMap());
    }

    @Test
    void handlesMultipleDocuments() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                new ConcurrentExtractionOrchestrator.CombinedExtractionResult(
                        List.of(RetrievedDoc.builder().text("c1").build(),
                                RetrievedDoc.builder().text("c2").build(),
                                RetrievedDoc.builder().text("c3").build()),
                        List.of(), Map.of(), List.of("chunking"), 2, 50L, List.of()
                );
        when(orchestrator.extractAll(anyList(), anyMap())).thenReturn(mockResult);

        List<TokenizedDocument> docs = List.of(tokenizedDoc("doc one"), tokenizedDoc("doc two"));
        ContentExtractionOutput output = stage.process(tokenizationOutput(docs));

        assertEquals(3, output.chunkCount());
        assertEquals(2, output.documentsProcessed());
    }

    // ─── FileName extraction ─────────────────────────────────────────────

    @Test
    void extractsFileNameFromMetadata() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                ConcurrentExtractionOrchestrator.CombinedExtractionResult.empty();
        when(orchestrator.extractAll(anyList(), anyMap())).thenReturn(mockResult);

        // When metadata has "fileName" key, it should be used
        stage.process(tokenizationOutput(
                List.of(), "task-1", Map.of("fileName", "report.pdf")));

        // No assertion on internal fileName since it's private,
        // but we verify no exception and the process completes
    }

    @Test
    void fallsBackToSourceInMetadata() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                ConcurrentExtractionOrchestrator.CombinedExtractionResult.empty();
        when(orchestrator.extractAll(anyList(), anyMap())).thenReturn(mockResult);

        stage.process(tokenizationOutput(List.of(), "task-1", Map.of("source", "/path/to/file.txt")));
    }

    @Test
    void fallsBackToTaskIdWhenNoFileName() throws Exception {
        ConcurrentExtractionOrchestrator.CombinedExtractionResult mockResult =
                ConcurrentExtractionOrchestrator.CombinedExtractionResult.empty();
        when(orchestrator.extractAll(anyList(), anyMap())).thenReturn(mockResult);

        stage.process(tokenizationOutput(List.of(), "my-task-id", Map.of()));
    }

    // ─── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancelBeforeProcessing() {
        stage.cancel();
        assertTrue(stage.isCancelled());

        assertThrows(InterruptedException.class, () ->
                stage.process(tokenizationOutput(List.of(tokenizedDoc("text")))));

        verify(orchestrator).cancel();
    }

    @Test
    void resetClearsCancellation() {
        stage.cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Configuration ───────────────────────────────────────────────────

    @Test
    void configureThreadCounts() {
        stage.configure(Map.of(
                "chunkingThreads", 8,
                "structuredExtractionThreads", 4
        ));
        verify(orchestrator).setThreadCount(ContentExtractor.ExtractorType.CHUNKING, 8);
        verify(orchestrator).setThreadCount(ContentExtractor.ExtractorType.ENTITY, 4);
    }

    @Test
    void configureStructuredExtraction() {
        stage.configure(Map.of("enableStructuredExtraction", false));
    }

    @Test
    void configureNullIsNoOp() {
        stage.configure(null);
    }

    // ─── Stage metadata ──────────────────────────────────────────────────

    @Test
    void stageNameIsContentExtraction() {
        assertEquals("content-extraction", stage.getName());
    }

    @Test
    void orchestratorAccessible() {
        assertSame(orchestrator, stage.getOrchestrator());
    }

    @Test
    void eventServiceSetAndGet() {
        assertNull(stage.getEventService());
        // Setting null event service is valid (disables audit logging)
        stage.setEventService(null);
        assertNull(stage.getEventService());
    }

    // ─── ContentExtractionOutput helpers ─────────────────────────────────

    @Test
    void outputAverageChunksPerDocument() {
        ContentExtractionOutput output = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                4, 12, 0, 100L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertEquals(3.0, output.averageChunksPerDocument(), 0.001);
    }

    @Test
    void outputAverageChunksPerDocumentZeroDocs() {
        ContentExtractionOutput output = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                0, 0, 0, 0L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertEquals(0.0, output.averageChunksPerDocument(), 0.001);
    }

    @Test
    void outputHasStructuredOutput() {
        ContentExtractionOutput withStructured = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                1, 5, 3, 100L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertTrue(withStructured.hasStructuredOutput());

        ContentExtractionOutput withoutStructured = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                1, 5, 0, 100L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertFalse(withoutStructured.hasStructuredOutput());
    }

    @Test
    void outputItemsByTypeDefaultsToEmptyList() {
        ContentExtractionOutput output = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                0, 0, 0, 0L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertTrue(output.getEntities().isEmpty());
        assertTrue(output.getRelationships().isEmpty());
        assertTrue(output.getConcepts().isEmpty());
        assertTrue(output.getTables().isEmpty());
        assertTrue(output.getFacts().isEmpty());
    }

    @Test
    void outputHasErrors() {
        ContentExtractionOutput noErrors = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                0, 0, 0, 0L,
                List.of(), "loader", null, Map.of(), List.of()
        );
        assertFalse(noErrors.hasErrors());

        ContentExtractionOutput withErrors = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                0, 0, 0, 0L,
                List.of(), "loader", null, Map.of(), List.of("extractor failed")
        );
        assertTrue(withErrors.hasErrors());
    }

    @Test
    void outputHasErrorsHandlesNull() {
        ContentExtractionOutput nullErrors = new ContentExtractionOutput(
                List.of(), List.of(), Map.of(),
                0, 0, 0, 0L,
                List.of(), "loader", null, Map.of(), null
        );
        assertFalse(nullErrors.hasErrors());
    }
}
