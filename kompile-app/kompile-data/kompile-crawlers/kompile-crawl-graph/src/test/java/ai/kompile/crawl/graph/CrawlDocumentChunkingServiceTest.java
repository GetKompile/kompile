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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.crawl.graph.CrawlChunkingConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrawlDocumentChunkingServiceTest {

    private CrawlDocumentChunkingService service;
    private TextChunker recursive;
    private TextChunker sentence;
    private TextChunker codeAware;

    @BeforeEach
    void setUp() {
        service = new CrawlDocumentChunkingService(mock(CrawlBatchPlanner.class),
                mock(PipelineStepTracker.class), mock(CrawlDocumentTracker.class));
        recursive = mock(TextChunker.class);
        sentence = mock(TextChunker.class);
        codeAware = mock(TextChunker.class);
        when(recursive.getName()).thenReturn("recursive-character");
        when(recursive.getDefaultOptions()).thenReturn(Map.of("chunkSize", 2_000, "overlap", 200));
        when(sentence.getName()).thenReturn("sentence");
        when(sentence.getDefaultOptions()).thenReturn(Map.of("chunkSize", 800, "overlap", 100));
        when(codeAware.getName()).thenReturn("code-aware");
        when(codeAware.getDefaultOptions()).thenReturn(Map.of("chunkSize", 1800, "overlap", 0));
        ReflectionTestUtils.setField(service, "textChunkers", List.of(codeAware, recursive, sentence));
        ReflectionTestUtils.setField(service, "projectChunkerName", "recursive");
        ReflectionTestUtils.setField(service, "projectChunkSize", 400);
        ReflectionTestUtils.setField(service, "projectChunkOverlap", 40);
    }

    @Test
    void sourceOverridesWinOverCrawlLegacyAndProjectConfiguration() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .chunking(CrawlChunkingConfig.builder()
                        .chunkerName("recursive-character")
                        .chunkSize(600)
                        .chunkOverlap(60)
                        .options(Map.of("preserveParagraphs", true))
                        .build())
                .vectorIndex(VectorIndexConfig.builder()
                        .chunkerName("recursive-character")
                        .chunkSize(500)
                        .chunkOverlap(50)
                        .build())
                .build();
        Document document = new Document("One sentence. Another sentence.", Map.of(
                GraphConstants.META_CHUNKER_NAME, "sentence",
                GraphConstants.META_CHUNK_SIZE_OVERRIDE, 700,
                GraphConstants.META_CHUNK_OVERLAP_OVERRIDE, 70,
                GraphConstants.META_CHUNKER_OPTIONS, Map.of("language", "en")));

        CrawlDocumentChunkingService.ChunkingPlan plan = service.resolvePlan(document,
                UnifiedCrawlJob.builder().request(request).build());

        assertSame(sentence, plan.chunker());
        assertEquals(700, plan.options().get("chunkSize"));
        assertEquals(70, plan.options().get("overlap"));
        assertEquals(70, plan.options().get("chunkOverlap"));
        assertEquals("en", plan.options().get("language"));
        assertEquals(true, plan.options().get("preserveParagraphs"));
    }

    @Test
    void crawlConfigurationOverridesTheLegacyVectorLocation() {
        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .chunking(CrawlChunkingConfig.builder()
                        .chunkerName("sentence")
                        .chunkSize(640)
                        .chunkOverlap(32)
                        .build())
                .vectorIndex(VectorIndexConfig.builder()
                        .chunkerName("recursive-character")
                        .chunkSize(500)
                        .chunkOverlap(50)
                        .build())
                .build();

        CrawlDocumentChunkingService.ChunkingPlan plan = service.resolvePlan(
                new Document("Text", Map.of()),
                UnifiedCrawlJob.builder().request(request).build());

        assertSame(sentence, plan.chunker());
        assertEquals(640, plan.options().get("chunkSize"));
        assertEquals(32, plan.options().get("overlap"));
    }

    @Test
    void projectInitRecursiveAliasAndZeroOverlapAreHonored() {
        ReflectionTestUtils.setField(service, "projectChunkOverlap", 0);

        CrawlDocumentChunkingService.ChunkingPlan plan = service.resolvePlan(
                new Document("Text", Map.of()),
                UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder().build()).build());

        assertSame(recursive, plan.chunker());
        assertEquals(400, plan.options().get("chunkSize"));
        assertEquals(0, plan.options().get("overlap"));
        assertEquals(0, plan.options().get("chunkOverlap"));
    }

    @Test
    void codeAwareChunkerIsSelectedOnlyForCodeContent() {
        ReflectionTestUtils.setField(service, "projectChunkerName", null);
        CrawlDocumentChunkingService.ChunkingPlan codePlan = service.resolvePlan(
                new Document("class Example {}", Map.of(GraphConstants.META_CONTENT_TYPE, "code")),
                UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder().build()).build());
        CrawlDocumentChunkingService.ChunkingPlan textPlan = service.resolvePlan(
                new Document("ordinary prose", Map.of()),
                UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder().build()).build());

        assertSame(codeAware, codePlan.chunker());
        assertSame(recursive, textPlan.chunker());
    }

    @Test
    void sourceEventSpansAreRebasedFromDocumentOffsetsToChunkOffsets() {
        String source = "Alpha event. Beta event.";
        Document document = new Document(source, Map.of(
                GraphConstants.META_SOURCE_EVENT_SPANS, List.of(
                        Map.of("start", 0, "end", 12, "kind", "sentence"),
                        Map.of("start", 13, "end", 24, "kind", "sentence"))));
        when(recursive.chunk(any(), any())).thenReturn(List.of(
                new RetrievedDoc("c1", "Alpha event.", Map.of()),
                new RetrievedDoc("c2", "Beta event.", Map.of())));

        List<Document> chunks = service.chunkOneDocument(document, recursive, Map.of(),
                UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder().build()).build());

        assertEquals(2, chunks.size());
        assertEquals(0, chunks.get(0).getMetadata().get(GraphConstants.META_CHUNK_SOURCE_START));
        assertEquals(12, chunks.get(0).getMetadata().get(GraphConstants.META_CHUNK_SOURCE_END));
        assertEquals(List.of(Map.of("start", 0, "end", 12, "kind", "sentence")),
                chunks.get(0).getMetadata().get(GraphConstants.META_SOURCE_EVENT_SPANS));
        assertEquals(13, chunks.get(1).getMetadata().get(GraphConstants.META_CHUNK_SOURCE_START));
        assertEquals(List.of(Map.of("start", 0, "end", 11, "kind", "sentence")),
                chunks.get(1).getMetadata().get(GraphConstants.META_SOURCE_EVENT_SPANS));
    }

    @Test
    void transformedChunksDiscardStaleDocumentRelativeSourceSpans() {
        Document document = new Document("Alpha event.", Map.of(
                GraphConstants.META_SOURCE_EVENT_SPANS,
                List.of(Map.of("start", 0, "end", 12, "kind", "sentence"))));
        when(recursive.chunk(any(), any())).thenReturn(List.of(
                new RetrievedDoc("c1", "ALPHA EVENT", Map.of(
                        GraphConstants.META_SOURCE_EVENT_SPANS,
                        List.of(Map.of("start", 0, "end", 12, "kind", "sentence"))))));

        List<Document> chunks = service.chunkOneDocument(document, recursive, Map.of(),
                UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder().build()).build());

        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).getMetadata().containsKey(GraphConstants.META_SOURCE_EVENT_SPANS));
        assertFalse(chunks.get(0).getMetadata().containsKey(GraphConstants.META_CHUNK_SOURCE_START));
        assertTrue(chunks.get(0).getText().contains("ALPHA"));
    }
}
