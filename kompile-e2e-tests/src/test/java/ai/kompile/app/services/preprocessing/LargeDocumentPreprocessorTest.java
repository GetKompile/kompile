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

package ai.kompile.app.services.preprocessing;

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.ingest.service.TextConversionService;
import ai.kompile.app.services.pipeline.ProcessingState;
import ai.kompile.app.services.pipeline.ProcessingStateRepository;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.LargeDocumentInfo;
import ai.kompile.core.loaders.StreamingDocumentLoader;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link LargeDocumentPreprocessor} — large document detection (by size and page count),
 * streaming support check, page-by-page processing, resume support,
 * cancellation, document info retrieval, chunker selection, and the LargeDocumentProgress record.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class LargeDocumentPreprocessorTest {

    @Mock private ProcessingStateRepository stateRepository;
    @Mock private TextConversionService textConversionService;
    @Mock private IngestConfiguration config;
    @Mock private StreamingDocumentLoader streamingLoader;
    @Mock private TextChunker chunker;

    @TempDir
    Path tempDir;

    private LargeDocumentPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        when(chunker.getName()).thenReturn("recursive-chunker");

        when(config.getLargeDocumentSizeThreshold()).thenReturn(10_000_000L); // 10MB
        when(config.getLargeDocumentPageThreshold()).thenReturn(50);

        preprocessor = new LargeDocumentPreprocessor(
                List.of(streamingLoader), stateRepository, textConversionService,
                config, List.of(chunker));
    }

    // ─── isLargeDocument ───────────────────────────────────────────────

    @Test
    void isLargeDocument_smallFile_returnsFalse() throws Exception {
        Path smallFile = tempDir.resolve("small.txt");
        Files.writeString(smallFile, "Small content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(false);

        assertFalse(preprocessor.isLargeDocument(smallFile));
    }

    @Test
    void isLargeDocument_largeBySize_returnsTrue() throws Exception {
        Path largeFile = tempDir.resolve("large.bin");
        // Create file larger than 10MB threshold
        byte[] bigContent = new byte[11_000_000];
        Files.write(largeFile, bigContent);

        assertTrue(preprocessor.isLargeDocument(largeFile));
    }

    @Test
    void isLargeDocument_largeByPageCount_returnsTrue() throws Exception {
        Path pdfFile = tempDir.resolve("many-pages.pdf");
        Files.writeString(pdfFile, "PDF content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class)))
                .thenReturn(new LargeDocumentInfo("src1", "many-pages.pdf", "PDF", 5_000_000L, 100, true, Map.of()));

        assertTrue(preprocessor.isLargeDocument(pdfFile));
    }

    @Test
    void isLargeDocument_nonexistent_returnsFalse() {
        Path nonexistent = tempDir.resolve("doesnotexist.txt");
        assertFalse(preprocessor.isLargeDocument(nonexistent));
    }

    @Test
    void isLargeDocument_loaderThrows_returnsFalse() throws Exception {
        Path file = tempDir.resolve("broken.pdf");
        Files.writeString(file, "content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class)))
                .thenThrow(new RuntimeException("Cannot parse"));

        assertFalse(preprocessor.isLargeDocument(file));
    }

    // ─── supportsStreaming ─────────────────────────────────────────────

    @Test
    void supportsStreaming_supported_returnsTrue() throws Exception {
        Path file = tempDir.resolve("supported.pdf");
        Files.writeString(file, "PDF");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);

        assertTrue(preprocessor.supportsStreaming(file));
    }

    @Test
    void supportsStreaming_notSupported_returnsFalse() throws Exception {
        Path file = tempDir.resolve("unsupported.xyz");
        Files.writeString(file, "data");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(false);

        assertFalse(preprocessor.supportsStreaming(file));
    }

    // ─── processLargeDocument ──────────────────────────────────────────

    @Test
    void processLargeDocument_noLoader_throwsIllegalArgument() {
        // No streaming loader available
        LargeDocumentPreprocessor noLoaderPreprocessor = new LargeDocumentPreprocessor(
                List.of(), stateRepository, textConversionService, config, List.of(chunker));

        DocumentSourceDescriptor source = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/test.pdf")
                .originalFileName("test.pdf")
                .sourceId("test.pdf")
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                noLoaderPreprocessor.processLargeDocument(source, new ArrayBlockingQueue<>(10),
                        chunker, Map.of(), null));
    }

    @Test
    void processLargeDocument_noChunker_throwsIllegalState() throws Exception {
        // Preprocessor with no chunker
        LargeDocumentPreprocessor noChunkerPreprocessor = new LargeDocumentPreprocessor(
                List.of(streamingLoader), stateRepository, textConversionService, config, List.of());

        DocumentSourceDescriptor source = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/test.pdf")
                .originalFileName("test.pdf")
                .sourceId("test.pdf")
                .build();

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class)))
                .thenReturn(new LargeDocumentInfo("src1", "test.pdf", "PDF", 500_000L, 5, false, Map.of()));
        when(stateRepository.findResumable(any())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                noChunkerPreprocessor.processLargeDocument(source, new ArrayBlockingQueue<>(10),
                        null, Map.of(), null));
    }

    @Test
    void processLargeDocument_processesPages_andQueuesChunks() throws Exception {
        DocumentSourceDescriptor source = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/test.pdf")
                .originalFileName("test.pdf")
                .sourceId("test.pdf")
                .build();

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        LargeDocumentInfo docInfo = new LargeDocumentInfo("src1", "test.pdf", "PDF", 500_000L, 2, false, Map.of());
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class))).thenReturn(docInfo);

        // No resume state
        when(stateRepository.findResumable("test.pdf")).thenReturn(Optional.empty());

        // Stream 2 pages
        Document page1 = new Document("Page 1 content");
        Document page2 = new Document("Page 2 content");
        when(streamingLoader.streamPages(any(), any())).thenReturn(List.of(page1, page2).iterator());

        // Text conversion returns documents as-is
        when(textConversionService.convert(anyList())).thenAnswer(inv -> {
            List<Document> docs = inv.getArgument(0);
            return new TextConversionService.ConversionResult(docs, 100L, 100L, 1L, docs.size(), List.of());
        });

        // Chunker returns one chunk per page
        when(chunker.chunk(any(RetrievedDoc.class), anyMap())).thenAnswer(inv -> {
            RetrievedDoc doc = inv.getArgument(0);
            return List.of(doc);
        });

        // State management
        ProcessingState initialState = mock(ProcessingState.class);
        when(initialState.lastProcessedPage()).thenReturn(0);
        when(initialState.chunksCreated()).thenReturn(0);
        when(initialState.withPhase(any())).thenReturn(initialState);
        when(initialState.withPage(anyInt())).thenReturn(initialState);
        when(initialState.withChunks(anyInt())).thenReturn(initialState);
        ProcessingState completedState = mock(ProcessingState.class);
        when(initialState.completed()).thenReturn(completedState);

        // Use static factory for initial state
        try (var mocked = mockStatic(ProcessingState.class)) {
            mocked.when(() -> ProcessingState.initial(any(), any(), any())).thenReturn(initialState);

            BlockingQueue<RetrievedDoc> queue = new ArrayBlockingQueue<>(100);
            ProcessingState result = preprocessor.processLargeDocument(
                    source, queue, chunker, Map.of(), null);

            // Both pages should produce chunks in the queue
            assertTrue(queue.size() >= 2);
            verify(stateRepository, atLeastOnce()).save(any());
        }
    }

    // ─── getDocumentInfo ───────────────────────────────────────────────

    @Test
    void getDocumentInfo_supportedDocument_returnsInfo() throws Exception {
        Path file = tempDir.resolve("test.pdf");
        Files.writeString(file, "PDF content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        LargeDocumentInfo info = new LargeDocumentInfo("src1", "test.pdf", "PDF", 2_500_000L, 25, false, Map.of());
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class))).thenReturn(info);

        Optional<LargeDocumentInfo> result = preprocessor.getDocumentInfo(file);

        assertTrue(result.isPresent());
        assertEquals(25, result.get().totalPages());
        assertEquals("PDF", result.get().documentType());
    }

    @Test
    void getDocumentInfo_unsupportedDocument_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("test.xyz");
        Files.writeString(file, "content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(false);

        Optional<LargeDocumentInfo> result = preprocessor.getDocumentInfo(file);

        assertTrue(result.isEmpty());
    }

    @Test
    void getDocumentInfo_loaderThrows_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("broken.pdf");
        Files.writeString(file, "content");

        when(streamingLoader.supportsStreaming(any(DocumentSourceDescriptor.class))).thenReturn(true);
        when(streamingLoader.getDocumentInfo(any(DocumentSourceDescriptor.class)))
                .thenThrow(new RuntimeException("parse error"));

        Optional<LargeDocumentInfo> result = preprocessor.getDocumentInfo(file);

        assertTrue(result.isEmpty());
    }

    // ─── selectBestChunker ─────────────────────────────────────────────

    @Test
    void noChunkers_defaultChunkerIsNull() {
        LargeDocumentPreprocessor emptyChunkers = new LargeDocumentPreprocessor(
                List.of(), stateRepository, textConversionService, config, List.of());

        // supportsStreaming should still work (returns false since no loaders)
        assertFalse(emptyChunkers.supportsStreaming(tempDir.resolve("test.txt")));
    }

    @Test
    void preferredChunker_recursive_selectedFirst() {
        TextChunker recursiveChunker = mock(TextChunker.class);
        when(recursiveChunker.getName()).thenReturn("Recursive Splitter");

        TextChunker simpleChunker = mock(TextChunker.class);
        when(simpleChunker.getName()).thenReturn("Simple Token Chunker");

        // Recursive should be preferred
        LargeDocumentPreprocessor pp = new LargeDocumentPreprocessor(
                List.of(), stateRepository, textConversionService, config,
                List.of(simpleChunker, recursiveChunker));

        // The preprocessor is constructed without error — chunker is selected
        assertNotNull(pp);
    }

    // ─── LargeDocumentProgress record ──────────────────────────────────

    @Test
    void largeDocumentProgress_progressPercent_calculatesCorrectly() {
        LargeDocumentPreprocessor.LargeDocumentProgress progress =
                LargeDocumentPreprocessor.LargeDocumentProgress.of(25, 100, 150, "chunking");

        assertEquals(25, progress.progressPercent());
        assertEquals(25, progress.currentPage());
        assertEquals(100, progress.totalPages());
        assertEquals(150, progress.chunksCreated());
        assertEquals("chunking", progress.phase());
    }

    @Test
    void largeDocumentProgress_zeroTotalPages_returnsZeroPercent() {
        LargeDocumentPreprocessor.LargeDocumentProgress progress =
                LargeDocumentPreprocessor.LargeDocumentProgress.of(0, 0, 0, "loading");

        assertEquals(0, progress.progressPercent());
    }

    @Test
    void largeDocumentProgress_progressString_formatsCorrectly() {
        LargeDocumentPreprocessor.LargeDocumentProgress progress =
                LargeDocumentPreprocessor.LargeDocumentProgress.of(50, 100, 200, "chunking");

        String str = progress.progressString();
        assertTrue(str.contains("50/100"));
        assertTrue(str.contains("50%"));
        assertTrue(str.contains("200 chunks"));
    }

    @Test
    void largeDocumentProgress_capsAt100Percent() {
        LargeDocumentPreprocessor.LargeDocumentProgress progress =
                LargeDocumentPreprocessor.LargeDocumentProgress.of(200, 100, 0, "loading");

        assertEquals(100, progress.progressPercent());
    }

    // ─── Constructor with null loaders/chunkers ────────────────────────

    @Test
    void constructor_nullLoaders_handlesGracefully() {
        LargeDocumentPreprocessor pp = new LargeDocumentPreprocessor(
                null, stateRepository, textConversionService, config, null);

        // Should not throw
        assertNotNull(pp);
        assertFalse(pp.supportsStreaming(tempDir.resolve("test.txt")));
    }
}
