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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlSourceLoadingServiceTest {

    @Test
    void fileSourcesPreferCrawlerPathForIncrementalHashHandling() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(),
                new PipelineStepTracker());

        assertTrue(service.isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType.FILE));
        assertTrue(service.isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType.DIRECTORY));
        assertTrue(service.isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType.WEB_CRAWL));
        assertFalse(service.isCrawlPreferredSourceType(DocumentSourceDescriptor.SourceType.SLACK));
    }

    @Test
    void sourceChunkOverridesBecomeDocumentMetadataForTheSharedChunkingPhase() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("mail")
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/mail.txt")
                .chunkerName("sentence")
                .chunkSize(640)
                .chunkOverlap(32)
                .chunkerOptions(Map.of("preserveParagraphs", false))
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build())
                .build();

        Map<String, Object> metadata = service.sourceMetadata(source, job);

        assertEquals("sentence", metadata.get(GraphConstants.META_CHUNKER_NAME));
        assertEquals(640, metadata.get(GraphConstants.META_CHUNK_SIZE_OVERRIDE));
        assertEquals(32, metadata.get(GraphConstants.META_CHUNK_OVERLAP_OVERRIDE));
        assertEquals(Map.of("preserveParagraphs", false),
                metadata.get(GraphConstants.META_CHUNKER_OPTIONS));
    }
}
