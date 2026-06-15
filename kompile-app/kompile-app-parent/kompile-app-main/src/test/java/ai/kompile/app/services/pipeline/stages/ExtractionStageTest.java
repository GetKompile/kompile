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

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExtractionStageTest {

    private DocumentLoader mockLoader;
    private ExtractionStage stage;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockLoader = mock(DocumentLoader.class);
        when(mockLoader.getName()).thenReturn("test-loader");
        when(mockLoader.supports(any())).thenReturn(true);
        stage = new ExtractionStage(List.of(mockLoader));
    }

    // --- getName ---

    @Test
    void getNameReturnsExtraction() {
        assertEquals("extraction", stage.getName());
    }

    // --- process: successful extraction ---

    @Test
    void processLoadsDocumentsFromFile() throws Exception {
        Path testFile = createTempFile("test.txt", "Hello world");
        Document doc = new Document("Hello world");
        when(mockLoader.load(any())).thenReturn(List.of(doc));

        ExtractionStage.ExtractionInput input = ExtractionStage.ExtractionInput.of(testFile, "task-1");
        ExtractionStage.ExtractionOutput output = stage.process(input);

        assertNotNull(output);
        assertFalse(output.documents().isEmpty());
        assertEquals("test-loader", output.loaderUsed());
        assertEquals("task-1", output.taskId());
        assertTrue(output.extractionTimeMs() >= 0);
    }

    @Test
    void processUsesPreferredLoader() throws Exception {
        DocumentLoader preferred = mock(DocumentLoader.class);
        when(preferred.getName()).thenReturn("preferred-loader");
        when(preferred.supports(any())).thenReturn(true);
        when(preferred.load(any())).thenReturn(List.of(new Document("content")));

        ExtractionStage stageWithTwo = new ExtractionStage(List.of(mockLoader, preferred));
        Path testFile = createTempFile("test.txt", "content");
        ExtractionStage.ExtractionInput input = new ExtractionStage.ExtractionInput(
                testFile, "preferred-loader", "task-1", null, null);

        ExtractionStage.ExtractionOutput output = stageWithTwo.process(input);

        assertEquals("preferred-loader", output.loaderUsed());
        verify(preferred).load(any());
    }

    @Test
    void processDocumentCountMatchesLoaderOutput() throws Exception {
        Path testFile = createTempFile("multi.txt", "doc content");
        when(mockLoader.load(any())).thenReturn(List.of(
                new Document("page 1"), new Document("page 2"), new Document("page 3")
        ));

        ExtractionStage.ExtractionOutput output = stage.process(
                ExtractionStage.ExtractionInput.of(testFile));

        assertEquals(3, output.documentCount());
    }

    // --- process: no loader found ---

    @Test
    void processThrowsWhenNoLoaderSupportsFile() throws Exception {
        when(mockLoader.supports(any())).thenReturn(false);
        Path testFile = createTempFile("unknown.xyz", "data");

        assertThrows(RuntimeException.class, () ->
                stage.process(ExtractionStage.ExtractionInput.of(testFile)));
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        Path testFile = tempDir.resolve("any.txt");

        assertThrows(InterruptedException.class, () ->
                stage.process(ExtractionStage.ExtractionInput.of(testFile)));
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsPreferredLoader() throws Exception {
        stage.configure(Map.of("preferredLoader", "test-loader"));
        Path testFile = createTempFile("test.txt", "content");
        when(mockLoader.load(any())).thenReturn(List.of(new Document("content")));

        ExtractionStage.ExtractionOutput output = stage.process(
                ExtractionStage.ExtractionInput.of(testFile));

        assertEquals("test-loader", output.loaderUsed());
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

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        Path testFile = createTempFile("test.txt", "content");
        when(mockLoader.load(any())).thenReturn(List.of(new Document("content")));

        stage.process(ExtractionStage.ExtractionInput.of(testFile));
        assertNotNull(stage.getMetrics());
    }

    // --- getAvailableLoaderNames ---

    @Test
    void getAvailableLoaderNamesReturnsAllLoaders() {
        DocumentLoader loader2 = mock(DocumentLoader.class);
        when(loader2.getName()).thenReturn("pdf-loader");
        ExtractionStage multiStage = new ExtractionStage(List.of(mockLoader, loader2));

        List<String> names = multiStage.getAvailableLoaderNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("test-loader"));
        assertTrue(names.contains("pdf-loader"));
    }

    // --- ExtractionInput ---

    @Test
    void extractionInputOfCreatesWithDefaults() {
        Path p = Path.of("/test/file.txt");
        ExtractionStage.ExtractionInput input = ExtractionStage.ExtractionInput.of(p);

        assertEquals(p, input.filePath());
        assertNull(input.preferredLoader());
        assertNull(input.taskId());
        assertNull(input.sourceId());
        assertNull(input.metadata());
    }

    @Test
    void extractionInputOfWithTaskId() {
        Path p = Path.of("/test/file.txt");
        ExtractionStage.ExtractionInput input = ExtractionStage.ExtractionInput.of(p, "task-42");

        assertEquals(p, input.filePath());
        assertEquals("task-42", input.taskId());
    }

    // --- ExtractionOutput ---

    @Test
    void extractionOutputDocumentCountHandlesNull() {
        ExtractionStage.ExtractionOutput output = new ExtractionStage.ExtractionOutput(
                null, "loader", 0, 0, "task-1", null);
        assertEquals(0, output.documentCount());
    }

    @Test
    void extractionOutputFileSizeTracked() throws Exception {
        Path testFile = createTempFile("sized.txt", "A".repeat(500));
        when(mockLoader.load(any())).thenReturn(List.of(new Document("content")));

        ExtractionStage.ExtractionOutput output = stage.process(
                ExtractionStage.ExtractionInput.of(testFile));

        assertEquals(500, output.fileSizeBytes());
    }

    // --- helpers ---

    private Path createTempFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
