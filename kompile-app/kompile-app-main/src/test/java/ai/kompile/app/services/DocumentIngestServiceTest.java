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

package ai.kompile.app.services;

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.ingest.service.TextConversionService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DocumentIngestService}.
 * Tests constructor initialization and null-safety of optional dependencies
 * without loading Spring context or ND4J.
 *
 * This service is very large (2000+ lines) with many dependencies,
 * so we focus on constructor behavior, initialization safety, and mode selection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentIngestService")
class DocumentIngestServiceTest {

    private DocumentLoader mockDocumentLoader = mock(DocumentLoader.class);
    private TextChunker mockTextChunker = mock(TextChunker.class);
    private IndexerService mockIndexerService = mock(IndexerService.class);
    private EmbeddingModel mockEmbeddingModel = mock(EmbeddingModel.class);
    private IngestConfiguration mockIngestConfig = mock(IngestConfiguration.class);
    private MemoryWatchdogService mockMemoryWatchdog = mock(MemoryWatchdogService.class);
    private TextConversionService mockTextConversion = mock(TextConversionService.class);

    /**
     * Creates a DocumentIngestService with minimal required dependencies.
     * All optional dependencies are null.
     */
    private DocumentIngestService createServiceMinimal() {
        return new DocumentIngestService(
                null,   // messagingTemplate (optional)
                List.of(mockDocumentLoader),
                List.of(mockTextChunker),
                List.of(mockIndexerService),
                List.of(mockEmbeddingModel),
                mockIngestConfig,
                mockMemoryWatchdog,
                mockTextConversion,
                null,   // ingestEventService (optional)
                null,   // indexingJobHistoryService (optional)
                null,   // largeDocumentPreprocessor (optional)
                null,   // subprocessIngestLauncher (optional)
                null    // subprocessConfigService (optional)
        );
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    // =========================================================================
    // Constructor initialization
    // =========================================================================

    @Nested
    @DisplayName("Constructor initialization")
    class ConstructorInit {

        @Test
        @DisplayName("creates service with all optional deps null")
        void createsServiceWithNullOptionalDeps() {
            DocumentIngestService service = createServiceMinimal();
            assertNotNull(service);
        }

        @Test
        @DisplayName("creates service with empty loader and chunker lists")
        void createsServiceWithEmptyLists() {
            DocumentIngestService service = new DocumentIngestService(
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    List.of(mockIndexerService),
                    List.of(mockEmbeddingModel),
                    mockIngestConfig,
                    mockMemoryWatchdog,
                    mockTextConversion,
                    null, null, null, null, null);
            assertNotNull(service);
        }

        @Test
        @DisplayName("selects non-NoOp indexer from list")
        void selectsNonNoOpIndexer() throws Exception {
            NoOpIndexerService noOp = new NoOpIndexerService();
            DocumentIngestService service = new DocumentIngestService(
                    null,
                    List.of(mockDocumentLoader),
                    List.of(mockTextChunker),
                    List.of(noOp, mockIndexerService),
                    List.of(mockEmbeddingModel),
                    mockIngestConfig,
                    mockMemoryWatchdog,
                    mockTextConversion,
                    null, null, null, null, null);

            Object selectedIndexer = getField(service, "indexerService");
            assertNotNull(selectedIndexer);
            assertFalse(selectedIndexer instanceof NoOpIndexerService,
                    "Should select non-NoOp indexer");
        }

        @Test
        @DisplayName("falls back to NoOp indexer when only NoOp available")
        void fallsBackToNoOpIndexer() throws Exception {
            NoOpIndexerService noOp = new NoOpIndexerService();
            DocumentIngestService service = new DocumentIngestService(
                    null,
                    List.of(mockDocumentLoader),
                    List.of(mockTextChunker),
                    List.of(noOp),
                    List.of(mockEmbeddingModel),
                    mockIngestConfig,
                    mockMemoryWatchdog,
                    mockTextConversion,
                    null, null, null, null, null);

            Object selectedIndexer = getField(service, "indexerService");
            assertNotNull(selectedIndexer);
            assertTrue(selectedIndexer instanceof NoOpIndexerService);
        }

        @Test
        @DisplayName("handles null SubprocessIngestLauncher gracefully")
        void handlesNullSubprocessLauncher() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            Object launcher = getField(service, "subprocessIngestLauncher");
            assertNull(launcher);
        }

        @Test
        @DisplayName("handles null IngestEventService gracefully")
        void handlesNullIngestEventService() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            Object eventService = getField(service, "ingestEventService");
            assertNull(eventService);
        }

        @Test
        @DisplayName("handles null SubprocessConfigService gracefully")
        void handlesNullSubprocessConfigService() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            Object configService = getField(service, "subprocessConfigService");
            assertNull(configService);
        }

        @Test
        @DisplayName("handles null SimpMessagingTemplate gracefully")
        void handlesNullMessagingTemplate() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            Object template = getField(service, "messagingTemplate");
            assertNull(template);
        }

        @Test
        @DisplayName("stores document loaders list")
        void storesDocumentLoadersList() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            @SuppressWarnings("unchecked")
            List<DocumentLoader> loaders = (List<DocumentLoader>) getField(service, "documentLoaders");
            assertNotNull(loaders);
            assertEquals(1, loaders.size());
        }

        @Test
        @DisplayName("stores text chunkers list")
        void storesTextChunkersList() throws Exception {
            DocumentIngestService service = createServiceMinimal();
            @SuppressWarnings("unchecked")
            List<TextChunker> chunkers = (List<TextChunker>) getField(service, "textChunkers");
            assertNotNull(chunkers);
            assertEquals(1, chunkers.size());
        }
    }

    // =========================================================================
    // DisposableBean
    // =========================================================================

    @Nested
    @DisplayName("DisposableBean contract")
    class DisposableBean {

        @Test
        @DisplayName("implements DisposableBean interface")
        void implementsDisposableBean() {
            DocumentIngestService service = createServiceMinimal();
            assertTrue(service instanceof org.springframework.beans.factory.DisposableBean);
        }
    }
}
