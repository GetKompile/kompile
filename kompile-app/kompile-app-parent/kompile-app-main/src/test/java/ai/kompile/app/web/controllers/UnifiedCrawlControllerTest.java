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
package ai.kompile.app.web.controllers;

import ai.kompile.app.ontology.OntologySchemaEnrichmentService;
import ai.kompile.app.services.SingleSourceCrawlPreviewService;
import ai.kompile.app.services.SingleSourceCrawlStarter;
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawl.graph.GraphExtractionPreviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnifiedCrawlControllerTest {

    @Test
    void historyRerunRunsSchemaEnrichmentForEnrichmentStep() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OwlClassificationResponse expected = response();
        when(schema.generateSchemaAndTypes(7L)).thenReturn(expected);
        setSchemaEnrichmentService(controller, schema);

        OwlClassificationResponse result = invokeSchemaHistoryHook(controller, "ENRICHMENT", 7L);

        assertSame(expected, result);
        verify(schema).generateSchemaAndTypes(7L);
    }

    @Test
    void historyRerunDoesNotRunSchemaEnrichmentForStandaloneReasoningSteps() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        setSchemaEnrichmentService(controller, schema);

        OwlClassificationResponse result = invokeSchemaHistoryHook(controller, "DERIVATION", 7L);

        assertNull(result);
        verifyNoInteractions(schema);
    }

    private static void setSchemaEnrichmentService(UnifiedCrawlController controller,
                                                   OntologySchemaEnrichmentService service) throws Exception {
        Field field = UnifiedCrawlController.class.getDeclaredField("schemaEnrichmentService");
        field.setAccessible(true);
        field.set(controller, service);
    }

    private static OwlClassificationResponse invokeSchemaHistoryHook(UnifiedCrawlController controller,
                                                                     String step,
                                                                     Long factSheetId) throws Exception {
        Method method = UnifiedCrawlController.class.getDeclaredMethod(
                "runSchemaEnrichmentForHistoryRerun", String.class, Long.class);
        method.setAccessible(true);
        return (OwlClassificationResponse) method.invoke(controller, step, factSheetId);
    }

    private static OwlClassificationResponse response() {
        return OwlClassificationResponse.builder()
                .factSheetId(7L)
                .ontologyBound(true)
                .ontologyName("ontology")
                .inferredTypeCount(1)
                .inferredRelationCount(1)
                .entitiesClassified(1)
                .edgesMaterialized(1)
                .consistent(true)
                .reasonerActive(true)
                .build();
    }

    // ---- POST /api/unified-crawl/single-source ----

    private static SingleSourceCrawlStarter.SingleSourceRunRequest runRequest(
            String pathOrUrl, String content, boolean dryRun, List<String> steps, Integer waitTimeoutSeconds) {
        return new SingleSourceCrawlStarter.SingleSourceRunRequest(
                null, null, pathOrUrl, content, null, null, null, null, null, null,
                dryRun, steps, null, null, null, null, null, waitTimeoutSeconds);
    }

    private static void setField(UnifiedCrawlController controller, String name, Object value) throws Exception {
        Field field = UnifiedCrawlController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    @Test
    void singleSource_rejectsBothOrNeitherInputs() {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));

        ResponseEntity<?> both = controller.runSingleSource(
                runRequest("/tmp/doc.md", "inline text", true, null, null));
        assertEquals(400, both.getStatusCode().value());

        ResponseEntity<?> neither = controller.runSingleSource(
                runRequest(null, null, true, null, null));
        assertEquals(400, neither.getStatusCode().value());
    }

    @Test
    void singleSourceDry_delegatesToPreviewAndMapsUnifiedResponse() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlPreviewService preview = mock(SingleSourceCrawlPreviewService.class);
        setField(controller, "singleSourceCrawlPreviewService", preview);

        GraphExtractionPreviewService.PreviewResponse graph = new GraphExtractionPreviewService.PreviewResponse(
                true, true, true, true, "COMPLETED", 1, 0, 2, 1, "test-model",
                List.of(new GraphExtractionPreviewService.EntityPreview(
                        "e1", "Alice", "PERSON", List.of(), null, 0.9, "d1", "Doc", Map.of())),
                List.of(new GraphExtractionPreviewService.RelationPreview(
                        "e1", "e2", "WORKS_AT", "Alice", "Acme", null, 0.8, "d1", "Doc", Map.of(), null)),
                List.of("graph warning"));
        SingleSourceCrawlPreviewService.SingleSourcePreviewResponse previewResponse =
                new SingleSourceCrawlPreviewService.SingleSourcePreviewResponse(
                        "p1", true, "file", "doc.md", "COMPLETED", false, null, "tika", "default",
                        1, 1, 3, 0, 0, false, List.of(), List.of("preview warning"), graph);
        when(preview.preview(any())).thenReturn(previewResponse);

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, true, List.of("GRAPH_EXTRACTION"), null));

        assertEquals(200, response.getStatusCode().value());
        SingleSourceCrawlStarter.SingleSourceRunResponse body =
                (SingleSourceCrawlStarter.SingleSourceRunResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.dryRun());
        assertTrue(body.completed());
        assertEquals(false, body.persisted());
        assertNull(body.jobId());
        assertEquals(2, body.entityCount());
        assertEquals(1, body.relationCount());
        assertEquals(3, body.chunksCreated());
        assertEquals(1, body.documentsLoaded());
        assertEquals(1L, body.entityTypeCounts().get("PERSON"));
        assertEquals(1L, body.relationshipTypeCounts().get("WORKS_AT"));
        assertEquals(1, body.sampleEntities().size());
        assertEquals("Alice", body.sampleEntities().get(0).name());
        assertTrue(body.warnings().stream().anyMatch(w -> w.contains("persist runs only")),
                "steps + dryRun must warn, not error");
        assertTrue(body.warnings().contains("preview warning"));
        assertTrue(body.warnings().contains("graph warning"));

        ArgumentCaptor<SingleSourceCrawlPreviewService.SingleSourcePreviewRequest> captor =
                ArgumentCaptor.forClass(SingleSourceCrawlPreviewService.SingleSourcePreviewRequest.class);
        verify(preview).preview(captor.capture());
        assertEquals("file", captor.getValue().sourceType());
        assertEquals(1, captor.getValue().maxDocuments());
        assertEquals(Boolean.TRUE, captor.getValue().properties().get("graphPreviewEnabled"));
    }

    @Test
    void singleSourcePersist_startsViaStarterWithWaitOptions() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        SingleSourceCrawlStarter.SingleSourceCrawlResult result =
                new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "job-9", "COMPLETED", 5L, 1, true, true,
                        Boolean.TRUE, Boolean.TRUE,
                        Map.of("GRAPH_EXTRACTION", "RUN", "ENRICHMENT", "SKIP"),
                        List.of(), 4, 2, Map.of("PERSON", 4L), Map.of(), 3, 1, 0,
                        List.of(), List.of(), 120L);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class))).thenReturn(result);
        setField(controller, "singleSourceCrawlStarter", starter);

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, false, List.of("GRAPH_EXTRACTION"), 7));

        assertEquals(200, response.getStatusCode().value());
        SingleSourceCrawlStarter.SingleSourceRunResponse body =
                (SingleSourceCrawlStarter.SingleSourceRunResponse) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.dryRun());
        assertTrue(body.completed());
        assertTrue(body.persisted());
        assertEquals("job-9", body.jobId());
        assertEquals(5L, body.factSheetId());
        assertEquals(4, body.entityCount());
        assertEquals("RUN", body.stepsPlanned().get("GRAPH_EXTRACTION"));

        ArgumentCaptor<SingleSourceCrawlStarter.SingleSourceCrawlOptions> optionsCaptor =
                ArgumentCaptor.forClass(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class);
        ArgumentCaptor<UnifiedCrawlSource> sourceCaptor = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        verify(starter).start(anyString(), sourceCaptor.capture(), optionsCaptor.capture());
        assertTrue(optionsCaptor.getValue().waitForCompletion());
        assertEquals(7_000L, optionsCaptor.getValue().waitTimeoutMs());
        assertEquals(List.of("GRAPH_EXTRACTION"), optionsCaptor.getValue().steps());
        assertEquals(DocumentSourceDescriptor.SourceType.FILE, sourceCaptor.getValue().getSourceType());
        assertEquals("/tmp/doc.md", sourceCaptor.getValue().getPathOrUrl());
    }

    @Test
    void singleSourcePersist_queueFullMapsTo503() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenThrow(new IllegalStateException("Unified crawl queue is full; try again later"));
        setField(controller, "singleSourceCrawlStarter", starter);

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, false, null, 5));

        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    void singleSourceInlineContent_writesUploadsFileAndCrawlsIt(@TempDir Path tempDir) throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenReturn(new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "job-txt", "PENDING", null, 1, true, true,
                        Boolean.FALSE, Boolean.TRUE, Map.of(), List.of(),
                        0, 0, Map.of(), Map.of(), 0, 0, 0, List.of(), List.of(), 5L));
        setField(controller, "singleSourceCrawlStarter", starter);
        setField(controller, "uploadsPath", tempDir);

        SingleSourceCrawlStarter.SingleSourceRunRequest request =
                new SingleSourceCrawlStarter.SingleSourceRunRequest(
                        null, "My Note", null, "Alice works at Acme Corp.", null, null, null, null, null, null,
                        false, null, null, null, null, null, null, 1);
        ResponseEntity<?> response = controller.runSingleSource(request);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<UnifiedCrawlSource> sourceCaptor = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        verify(starter).start(anyString(), sourceCaptor.capture(),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class));
        UnifiedCrawlSource source = sourceCaptor.getValue();
        assertEquals(DocumentSourceDescriptor.SourceType.FILE, source.getSourceType());
        Path written = Path.of(source.getPathOrUrl());
        assertTrue(written.startsWith(tempDir), "inline content must land under uploadsPath");
        assertTrue(written.getFileName().toString().endsWith(".txt"));
        assertEquals("Alice works at Acme Corp.", Files.readString(written));
    }
}
