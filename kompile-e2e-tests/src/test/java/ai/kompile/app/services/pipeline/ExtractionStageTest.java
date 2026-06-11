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
import ai.kompile.app.services.pipeline.stages.ExtractionStage.ExtractionInput;
import ai.kompile.app.services.pipeline.stages.ExtractionStage.ExtractionOutput;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ExtractionStage} — loader selection, document extraction,
 * source attribution, cancellation, and configuration.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ExtractionStageTest {

    @Mock
    private DocumentLoader pdfLoader;

    @Mock
    private DocumentLoader excelLoader;

    @TempDir
    Path tempDir;

    private ExtractionStage stage;

    @BeforeEach
    void setUp() {
        when(pdfLoader.getName()).thenReturn("pdf-loader");
        when(excelLoader.getName()).thenReturn("excel-loader");
        stage = new ExtractionStage(List.of(pdfLoader, excelLoader));
    }

    // ─── Loader auto-detection ───────────────────────────────────────────

    @Test
    void autoDetectsLoaderByFileType() throws Exception {
        Path testFile = tempDir.resolve("test.pdf");
        Files.writeString(testFile, "PDF content");

        when(pdfLoader.supports(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(pdfLoader.load(any(DocumentSourceDescriptor.class)))
                .thenReturn(List.of(new Document("extracted text")));

        ExtractionOutput output = stage.process(ExtractionInput.of(testFile));

        assertEquals(1, output.documentCount());
        assertEquals("pdf-loader", output.loaderUsed());
        verify(pdfLoader).load(any(DocumentSourceDescriptor.class));
    }

    @Test
    void fallsBackToSecondLoaderWhenFirstDoesNotSupport() throws Exception {
        Path testFile = tempDir.resolve("test.xlsx");
        Files.writeString(testFile, "Excel content");

        when(pdfLoader.supports(any(DocumentSourceDescriptor.class))).thenReturn(false);
        when(excelLoader.supports(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(excelLoader.load(any(DocumentSourceDescriptor.class)))
                .thenReturn(List.of(new Document("cell data")));

        ExtractionOutput output = stage.process(ExtractionInput.of(testFile));

        assertEquals(1, output.documentCount());
        assertEquals("excel-loader", output.loaderUsed());
    }

    @Test
    void throwsWhenNoLoaderSupportsFile() throws Exception {
        Path testFile = tempDir.resolve("test.xyz");
        Files.writeString(testFile, "unknown format");

        when(pdfLoader.supports(any())).thenReturn(false);
        when(excelLoader.supports(any())).thenReturn(false);

        assertThrows(RuntimeException.class, () ->
                stage.process(ExtractionInput.of(testFile)));
    }

    // ─── Preferred loader ────────────────────────────────────────────────

    @Test
    void usesPreferredLoaderWhenSpecified() throws Exception {
        Path testFile = tempDir.resolve("report.pdf");
        Files.writeString(testFile, "data");

        when(excelLoader.getName()).thenReturn("excel-loader");
        when(excelLoader.supports(any())).thenReturn(true);
        when(excelLoader.load(any())).thenReturn(List.of(new Document("from excel loader")));

        ExtractionInput input = new ExtractionInput(testFile, "excel-loader", null, null, null);
        ExtractionOutput output = stage.process(input);

        assertEquals("excel-loader", output.loaderUsed());
    }

    @Test
    void fallsToAutoDetectWhenPreferredNotFound() throws Exception {
        Path testFile = tempDir.resolve("test.pdf");
        Files.writeString(testFile, "data");

        when(pdfLoader.supports(any())).thenReturn(true);
        when(pdfLoader.load(any())).thenReturn(List.of(new Document("text")));

        ExtractionInput input = new ExtractionInput(testFile, "nonexistent-loader", null, null, null);
        ExtractionOutput output = stage.process(input);

        assertEquals("pdf-loader", output.loaderUsed());
    }

    // ─── Multiple documents ──────────────────────────────────────────────

    @Test
    void loaderReturnsMultipleDocuments() throws Exception {
        Path testFile = tempDir.resolve("multi.pdf");
        Files.writeString(testFile, "multi page");

        when(pdfLoader.supports(any())).thenReturn(true);
        when(pdfLoader.load(any())).thenReturn(List.of(
                new Document("page 1"), new Document("page 2"), new Document("page 3")
        ));

        ExtractionOutput output = stage.process(ExtractionInput.of(testFile));

        assertEquals(3, output.documentCount());
    }

    // ─── Metadata propagation ────────────────────────────────────────────

    @Test
    void propagatesTaskIdAndMetadata() throws Exception {
        Path testFile = tempDir.resolve("meta.pdf");
        Files.writeString(testFile, "text");

        when(pdfLoader.supports(any())).thenReturn(true);
        when(pdfLoader.load(any())).thenReturn(List.of(new Document("doc")));

        ExtractionInput input = new ExtractionInput(
                testFile, null, "task-42", "source-1", Map.of("key", "val")
        );
        ExtractionOutput output = stage.process(input);

        assertEquals("task-42", output.taskId());
        assertEquals("val", output.metadata().get("key"));
    }

    // ─── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancelBeforeProcessing() throws Exception {
        Path testFile = tempDir.resolve("cancel.pdf");
        Files.writeString(testFile, "data");

        stage.cancel();
        assertTrue(stage.isCancelled());

        assertThrows(InterruptedException.class, () ->
                stage.process(ExtractionInput.of(testFile)));
    }

    @Test
    void resetClearsCancellation() {
        stage.cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Configuration ───────────────────────────────────────────────────

    @Test
    void configurePreferredLoader() {
        stage.configure(Map.of("preferredLoader", "excel-loader"));
    }

    @Test
    void configureAutoDetect() {
        stage.configure(Map.of("autoDetectLoader", false));
    }

    @Test
    void configureNullIsNoOp() {
        stage.configure(null);
    }

    // ─── Stage metadata ──────────────────────────────────────────────────

    @Test
    void stageNameIsExtraction() {
        assertEquals("extraction", stage.getName());
    }

    @Test
    void availableLoaderNamesReturned() {
        List<String> names = stage.getAvailableLoaderNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("pdf-loader"));
        assertTrue(names.contains("excel-loader"));
    }

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        Path testFile = tempDir.resolve("metrics.pdf");
        Files.writeString(testFile, "text");

        when(pdfLoader.supports(any())).thenReturn(true);
        when(pdfLoader.load(any())).thenReturn(List.of(new Document("doc")));

        stage.process(ExtractionInput.of(testFile));
        assertEquals(1, stage.getMetrics().getItemsProcessed());
    }

    // ─── ExtractionInput factory methods ─────────────────────────────────

    @Test
    void extractionInputOfPath() {
        Path p = Path.of("/tmp/test.pdf");
        ExtractionInput input = ExtractionInput.of(p);
        assertEquals(p, input.filePath());
        assertNull(input.preferredLoader());
        assertNull(input.taskId());
    }

    @Test
    void extractionInputOfPathAndTaskId() {
        Path p = Path.of("/tmp/test.pdf");
        ExtractionInput input = ExtractionInput.of(p, "my-task");
        assertEquals("my-task", input.taskId());
    }

    // ─── ExtractionOutput helpers ────────────────────────────────────────

    @Test
    void outputDocumentCountHandlesNull() {
        ExtractionOutput output = new ExtractionOutput(null, "loader", 0L, 0L, null, null);
        assertEquals(0, output.documentCount());
    }
}
