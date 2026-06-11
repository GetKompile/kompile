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

import ai.kompile.app.config.ContextualRagConfig;
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
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContextualChunkEnricher} — disabled mode, null chatModel fallback,
 * empty/null chunks, source attribution, cache operations, and error fallback.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ContextualChunkEnricherTest {

    @Mock private ContextualRagConfigService configService;

    private ContextualChunkEnricher enricher;

    @BeforeEach
    void setUp() {
        // Create enricher with null chatModel — the common case when no LLM is configured
        enricher = new ContextualChunkEnricher(configService, null);
    }

    private RetrievedDoc chunk(String id, String text) {
        return RetrievedDoc.builder()
                .id(id)
                .text(text)
                .metadata(Map.of())
                .build();
    }

    private RetrievedDoc chunkWithMeta(String id, String text, Map<String, Object> metadata) {
        return RetrievedDoc.builder()
                .id(id)
                .text(text)
                .metadata(metadata)
                .build();
    }

    private ContextualRagConfig disabledConfig() {
        return ContextualRagConfig.builder()
                .enabled(false)
                .sourceAttributionEnabled(true)
                .build();
    }

    private ContextualRagConfig enabledConfig() {
        return ContextualRagConfig.builder()
                .enabled(true)
                .includeDocumentSummary(false)
                .cachingEnabled(false)
                .fallbackOnError(true)
                .batchSize(10)
                .maxConcurrentRequests(5)
                .requestTimeoutSeconds(30)
                .build();
    }

    // ─── enrichChunks: disabled ──────────────────────────────────────

    @Test
    void enrichChunks_disabled_returnsWithSourceAttribution() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        List<RetrievedDoc> chunks = List.of(chunk("c1", "Hello"), chunk("c2", "World"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc text", "report.pdf");

        assertEquals(2, result.size());
        // Source attribution should be added
        assertEquals(0, result.get(0).getMetadata().get(SourceMetadataConstants.CHUNK_INDEX));
        assertEquals(2, result.get(0).getMetadata().get(SourceMetadataConstants.TOTAL_CHUNKS));
        assertEquals(false, result.get(0).getMetadata().get("contextualized"));
    }

    @Test
    void enrichChunks_disabled_preservesOriginalText() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        List<RetrievedDoc> chunks = List.of(chunk("c1", "Original text here"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "doc.pdf");

        assertEquals("Original text here", result.get(0).getText());
    }

    // ─── enrichChunks: null chatModel ────────────────────────────────

    @Test
    void enrichChunks_noChatModel_fallsBackToAttribution() {
        when(configService.getConfiguration()).thenReturn(enabledConfig());

        List<RetrievedDoc> chunks = List.of(chunk("c1", "Text"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "doc.pdf");

        assertEquals(1, result.size());
        assertEquals("Text", result.get(0).getText());
        assertEquals(false, result.get(0).getMetadata().get("contextualized"));
    }

    // ─── enrichChunks: null/empty input ──────────────────────────────

    @Test
    void enrichChunks_nullChunks_throwsNPE() {
        when(configService.getConfiguration()).thenReturn(enabledConfig());

        // When chatModel is null, code falls through to addSourceAttribution which NPEs on null list
        assertThrows(NullPointerException.class,
                () -> enricher.enrichChunks(null, "doc", "title"));
    }

    @Test
    void enrichChunks_emptyChunks_returnsEmpty() {
        when(configService.getConfiguration()).thenReturn(enabledConfig());

        List<RetrievedDoc> result = enricher.enrichChunks(List.of(), "doc", "title");
        assertTrue(result.isEmpty());
    }

    // ─── enrichChunks: source attribution metadata ───────────────────

    @Test
    void enrichChunks_addsDocumentTitleToMetadata() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        List<RetrievedDoc> chunks = List.of(chunk("c1", "Text"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "report.pdf");

        assertEquals("report.pdf",
                result.get(0).getMetadata().get(SourceMetadataConstants.SOURCE_FILENAME));
    }

    @Test
    void enrichChunks_preservesExistingMetadata() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        Map<String, Object> existingMeta = Map.of("custom_key", "custom_value");
        List<RetrievedDoc> chunks = List.of(chunkWithMeta("c1", "Text", existingMeta));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "doc.pdf");

        assertEquals("custom_value", result.get(0).getMetadata().get("custom_key"));
    }

    @Test
    void enrichChunks_doesNotOverwriteExistingSourceFilename() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        Map<String, Object> existingMeta = Map.of(SourceMetadataConstants.SOURCE_FILENAME, "original.pdf");
        List<RetrievedDoc> chunks = List.of(chunkWithMeta("c1", "Text", existingMeta));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "new_name.pdf");

        assertEquals("original.pdf",
                result.get(0).getMetadata().get(SourceMetadataConstants.SOURCE_FILENAME));
    }

    @Test
    void enrichChunks_multipleChunks_correctIndices() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        List<RetrievedDoc> chunks = List.of(
                chunk("c1", "First"), chunk("c2", "Second"), chunk("c3", "Third"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "Full doc", "doc.pdf");

        assertEquals(3, result.size());
        assertEquals(0, result.get(0).getMetadata().get(SourceMetadataConstants.CHUNK_INDEX));
        assertEquals(1, result.get(1).getMetadata().get(SourceMetadataConstants.CHUNK_INDEX));
        assertEquals(2, result.get(2).getMetadata().get(SourceMetadataConstants.CHUNK_INDEX));
        assertEquals(3, result.get(0).getMetadata().get(SourceMetadataConstants.TOTAL_CHUNKS));
        assertEquals(3, result.get(2).getMetadata().get(SourceMetadataConstants.TOTAL_CHUNKS));
    }

    // ─── enrichSingleChunk: disabled ─────────────────────────────────

    @Test
    void enrichSingleChunk_disabled_addsAttribution() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        RetrievedDoc result = enricher.enrichSingleChunk(
                chunk("c1", "Some text"), "Full doc", "report.pdf", 3, 10);

        assertEquals("Some text", result.getText());
        assertEquals(3, result.getMetadata().get(SourceMetadataConstants.CHUNK_INDEX));
    }

    @Test
    void enrichSingleChunk_noChatModel_fallsBack() {
        when(configService.getConfiguration()).thenReturn(enabledConfig());

        RetrievedDoc result = enricher.enrichSingleChunk(
                chunk("c1", "Some text"), "Full doc", "report.pdf", 0, 5);

        assertEquals("Some text", result.getText());
        assertEquals(false, result.getMetadata().get("contextualized"));
    }

    // ─── Cache operations ────────────────────────────────────────────

    @Test
    void clearCaches_noError() {
        assertDoesNotThrow(() -> enricher.clearCaches());
    }

    @Test
    void getCacheStats_returnsZeroInitially() {
        Map<String, Object> stats = enricher.getCacheStats();
        assertEquals(0, stats.get("documentSummaryCount"));
        assertEquals(0, stats.get("chunkContextCount"));
    }

    // ─── enrichChunks: preserves chunk ID and score ──────────────────

    @Test
    void enrichChunks_preservesIdAndScore() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        RetrievedDoc original = RetrievedDoc.builder()
                .id("chunk-42")
                .text("Hello")
                .metadata(Map.of())
                .score(0.95)
                .build();

        List<RetrievedDoc> result = enricher.enrichChunks(List.of(original), "doc", "doc.pdf");

        assertEquals("chunk-42", result.get(0).getId());
        assertEquals(0.95, result.get(0).getScore());
    }

    // ─── enrichChunks: null document title ───────────────────────────

    @Test
    void enrichChunks_nullDocTitle_noSourceFilenameAdded() {
        when(configService.getConfiguration()).thenReturn(disabledConfig());

        List<RetrievedDoc> chunks = List.of(chunk("c1", "Text"));
        List<RetrievedDoc> result = enricher.enrichChunks(chunks, "doc", null);

        // null title should not be added as SOURCE_FILENAME
        assertFalse(result.get(0).getMetadata().containsKey(SourceMetadataConstants.SOURCE_FILENAME));
    }
}
