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
import ai.kompile.core.crawl.graph.CrawlChunkingConfig;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.CrawlJob;
import ai.kompile.core.crawler.CrawlStatus;
import ai.kompile.core.crawler.CrawlSummary;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.PipelineAwareCrawlListener;
import ai.kompile.core.crawler.pipeline.RoutedCrawlItem;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.kompile.crawler.CrawlerService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrawlSourceLoadingServiceTest {

    @Test
    void unifiedSourceJacksonRoundTripPreservesPipelineOverride() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UnifiedCrawlSource source = mapper.readValue("""
                {"label":"code","sourceType":"FILE","pathOrUrl":"Example.java","pipelineId":"code"}
                """, UnifiedCrawlSource.class);

        assertEquals("code", source.getPipelineId());
        assertTrue(mapper.writeValueAsString(source).contains("\"pipelineId\":\"code\""));
    }

    @Test
    void directExternalLoadersHonorUnifiedMaxDocumentsBeforeFetching() {
        Map<String, Object> jira = new java.util.LinkedHashMap<>(Map.of("maxIssues", 1000));
        CrawlSourceLoadingService.applyDirectLoaderLimits(UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.JIRA)
                .maxDocuments(12).build(), jira);
        assertEquals(12, jira.get("maxIssues"));

        Map<String, Object> reddit = new java.util.LinkedHashMap<>(Map.of("postLimit", "500"));
        CrawlSourceLoadingService.applyDirectLoaderLimits(UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.REDDIT)
                .maxDocuments(20).build(), reddit);
        assertEquals(20, reddit.get("postLimit"));

        Map<String, Object> notion = new java.util.LinkedHashMap<>(Map.of("maxPages", 250));
        CrawlSourceLoadingService.applyDirectLoaderLimits(UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.NOTION)
                .maxDocuments(15).build(), notion);
        assertEquals(15, notion.get("maxPages"));

        Map<String, Object> workspace = new java.util.LinkedHashMap<>();
        CrawlSourceLoadingService.applyDirectLoaderLimits(UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE)
                .maxDocuments(4).build(), workspace);
        assertEquals(4, workspace.get("gmailMaxMessages"));
        assertEquals(4, workspace.get("driveMaxFiles"));

        UnifiedCrawlSource drive = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.GDRIVE)
                .pathOrUrl("one,two,three")
                .maxDocuments(2).build();
        assertEquals("one,two", CrawlSourceLoadingService.applyDirectIdentifierLimits(
                drive, new java.util.LinkedHashMap<>()));

        Map<String, Object> docs = new java.util.LinkedHashMap<>();
        UnifiedCrawlSource googleDocs = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.GDOCS)
                .pathOrUrl("doc-1,doc-2")
                .maxDocuments(1).build();
        CrawlSourceLoadingService.applyDirectLoaderLimits(googleDocs, docs);
        CrawlSourceLoadingService.applyDirectIdentifierLimits(googleDocs, docs);
        assertEquals(1, ((List<?>) docs.get("documentIds")).size());
    }

    @Test
    void codePipelinesReceiveRealManagedLoaderAndChunkerDefaults() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        IngestPipelineDefinition code = IngestPipelineDefinition.builder()
                .pipelineId("code")
                .pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().pipelines(List.of(code))
                        .defaultPipelineId("code").build())
                .build();

        IngestPipelineDefinition effective = service.effectivePipelines(job).get(0);

        assertEquals("source-code", effective.getLoaderName());
        assertEquals("code-aware", effective.getChunkerName());
        assertEquals(1800, effective.getChunkSize());
        assertEquals(0, effective.getChunkOverlap());
    }

    @Test
    void sourcePipelineOverrideWinsAndStampsChunkingMetadata() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        IngestPipelineDefinition text = IngestPipelineDefinition.builder()
                .pipelineId("text").pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                .build();
        IngestPipelineDefinition code = IngestPipelineDefinition.builder()
                .pipelineId("code").pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                .loaderName("source-code").chunkerName("code-aware")
                .chunkSize(1800).chunkOverlap(0).build();
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("Example.java")
                .pipelineId("code")
                .chunkSize(900)
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder().request(UnifiedCrawlRequest.builder()
                .pipelines(List.of(text, code))
                .routeRules(List.of(ContentRouteRule.builder().pipelineId("text")
                        .fileExtensions(List.of(".java")).build()))
                .defaultPipelineId("text")
                .build()).build();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE).pathOrUrl("Example.java").build();

        RoutedCrawlItem routed = service.routeDirectSource(source, job, descriptor);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        service.applyPipelineMetadata(metadata, source, routed, job);

        assertEquals("code", routed.pipeline().getPipelineId());
        assertEquals("code", metadata.get(GraphConstants.META_PIPELINE_ID));
        assertEquals("source_override", metadata.get(GraphConstants.META_PIPELINE_ROUTE));
        assertEquals("code-aware", metadata.get(GraphConstants.META_CHUNKER_NAME));
        assertEquals(900, metadata.get(GraphConstants.META_CHUNK_SIZE_OVERRIDE));
    }

    @Test
    void processingFingerprintInvalidatesUnchangedContentWhenChunkingChanges() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        IngestPipelineDefinition code = IngestPipelineDefinition.builder()
                .pipelineId("code").pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                .loaderName("source-code").chunkerName("code-aware").build();
        RoutedCrawlItem routed = new RoutedCrawlItem(CrawlItem.builder().url("Example.java").build(),
                code, null);
        UnifiedCrawlSource first = UnifiedCrawlSource.builder().chunkSize(800).build();
        UnifiedCrawlSource second = UnifiedCrawlSource.builder().chunkSize(1600).build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build()).build();

        String firstHash = service.processingAwareHash("same-content", first, routed, job);
        String secondHash = service.processingAwareHash("same-content", second, routed, job);

        assertNotEquals(firstHash, secondHash);
        assertEquals(firstHash,
                service.processingAwareHash("same-content", first, routed, job));

        UnifiedCrawlJob changedGlobalChunking = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().chunking(CrawlChunkingConfig.builder()
                        .chunkSize(4096).chunkOverlap(128).build()).build()).build();
        assertNotEquals(firstHash,
                service.processingAwareHash("same-content", first, routed, changedGlobalChunking));

        UnifiedCrawlJob changedPreprocessing = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder()
                        .preprocessing(Map.of("redactPii", true)).build()).build();
        assertNotEquals(firstHash,
                service.processingAwareHash("same-content", first, routed, changedPreprocessing));
    }

    @Test
    void processingFingerprintCanonicalizesMapOrder() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        Map<String, Object> firstOptions = new java.util.LinkedHashMap<>();
        firstOptions.put("language", "java");
        firstOptions.put("preserveParagraphs", true);
        Map<String, Object> secondOptions = new java.util.LinkedHashMap<>();
        secondOptions.put("preserveParagraphs", true);
        secondOptions.put("language", "java");
        IngestPipelineDefinition firstPipeline = IngestPipelineDefinition.builder()
                .pipelineId("code").pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                .options(firstOptions).build();
        IngestPipelineDefinition secondPipeline = IngestPipelineDefinition.builder()
                .pipelineId("code").pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                .options(secondOptions).build();
        UnifiedCrawlSource source = UnifiedCrawlSource.builder().build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build()).build();

        String first = service.processingFingerprint(source,
                new RoutedCrawlItem(CrawlItem.builder().url("Example.java").build(), firstPipeline, null), job);
        String second = service.processingFingerprint(source,
                new RoutedCrawlItem(CrawlItem.builder().url("Example.java").build(), secondPipeline, null), job);

        assertEquals(first, second);
    }

    @Test
    void unknownExplicitSourcePipelineIsRejectedEvenWithoutRequestPipelines() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("Example.java")
                .pipelineId("missing")
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build()).build();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE).pathOrUrl("Example.java").build();

        assertThrows(IllegalArgumentException.class,
                () -> service.routeDirectSource(source, job, descriptor));
    }

    @Test
    void namedLoaderSelectionIsAuthoritative() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        DocumentLoader generic = loader("generic", true);
        DocumentLoader code = loader("source-code", true);
        ReflectionTestUtils.setField(service, "documentLoaders", List.of(generic, code));
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE).pathOrUrl("Example.java").build();

        assertEquals(code, service.resolveLoader(descriptor, "code"));
        assertEquals(generic, service.resolveLoader(descriptor, null));
    }

    @Test
    void crawlerRoutingDecisionControlsLoaderAndChunkerMetadata() throws Exception {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        CrawlerService crawler = mock(CrawlerService.class);
        ReflectionTestUtils.setField(service, "crawlerService", crawler);
        ReflectionTestUtils.setField(service, "documentLoaders", List.of(loader("source-code", true)));
        AtomicReference<ai.kompile.core.crawler.CrawlConfig> captured = new AtomicReference<>();
        when(crawler.startCrawl(any(), any())).thenAnswer(invocation -> {
            ai.kompile.core.crawler.CrawlConfig config = invocation.getArgument(0);
            PipelineAwareCrawlListener listener = invocation.getArgument(1);
            captured.set(config);
            CrawlItem item = CrawlItem.builder()
                    .url("/workspace/Example.java")
                    .sourceDescriptor(DocumentSourceDescriptor.builder()
                            .type(DocumentSourceDescriptor.SourceType.FILE)
                            .pathOrUrl("/workspace/Example.java").build())
                    .build();
            listener.onDocumentDiscovered(item);
            listener.onItemRouted(new RoutedCrawlItem(item, config.getPipelines().get(0), null));
            listener.onDocumentProcessed(item);
            Instant now = Instant.now();
            listener.onComplete(new CrawlSummary(CrawlStatus.COMPLETED, 1, 1, 0, 0, 0,
                    now, now, Duration.ZERO, List.of(), null));
            return mock(CrawlJob.class);
        });
        IngestPipelineDefinition code = IngestPipelineDefinition.builder()
                .pipelineId("code").pipelineType(IngestPipelineDefinition.PipelineType.CODE).build();
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("code").sourceType(DocumentSourceDescriptor.SourceType.DIRECTORY)
                .pathOrUrl("/workspace").pipelineId("code").build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("job")
                .request(UnifiedCrawlRequest.builder().sources(List.of(source))
                        .pipelines(List.of(code)).defaultPipelineId("code").build())
                .build();

        List<Document> docs = service.crawlSource(source, job,
                UnifiedCrawlJob.SourceProgress.builder().build());

        assertEquals("source-code", captured.get().getPipelines().get(0).getLoaderName());
        assertEquals("code-aware", captured.get().getPipelines().get(0).getChunkerName());
        assertEquals("code", docs.get(0).getMetadata().get(GraphConstants.META_PIPELINE_ID));
        assertEquals("code-aware", docs.get(0).getMetadata().get(GraphConstants.META_CHUNKER_NAME));
    }

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

    @Test
    void runtimeCredentialsReachTheLoaderButNeverPersistentDocumentMetadata() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .label("community")
                .sourceType(DocumentSourceDescriptor.SourceType.DISCORD)
                .pathOrUrl("guild-1")
                .properties(Map.of(
                        "botToken", "never-persist",
                        "X-Api-Key", "also-never-persist",
                        "spaceKey", "TEAM",
                        "mirrors", List.of("https://admin:secret@example.test/path"),
                        "nested", Map.of("clientSecret", "also-never-persist", "region", "us")))
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().factSheetId(7L).build())
                .build();

        Map<String, Object> runtime = service.runtimeSourceMetadata(source, job);
        Map<String, Object> persisted = service.sourceMetadata(source, job);

        assertEquals("never-persist", runtime.get("botToken"));
        assertFalse(persisted.containsKey("botToken"));
        assertFalse(persisted.containsKey("X-Api-Key"));
        assertEquals("TEAM", persisted.get("spaceKey"));
        assertEquals(List.of("https://example.test/path"), persisted.get("mirrors"));
        assertEquals(Map.of("region", "us"), persisted.get("nested"));
    }

    @Test
    void sourceScopeIdentityIsStableAndPathNormalized() {
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.DIRECTORY)
                .pathOrUrl("C:\\workspace\\docs")
                .build();

        assertEquals("DIRECTORY:C:/workspace/docs", CrawlSourceLoadingService.sourceScopeId(source));
    }

    @Test
    void locatorOptionalSourcesAcceptBlankPathOrUrlWhenIdentityLivesInProperties() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build())
                .build();
        UnifiedCrawlJob.SourceProgress progress = UnifiedCrawlJob.SourceProgress.builder()
                .label("mailbox").build();

        // Account-wide types need no locator at all; the gate must not fire before the
        // loader's own (precise) validation. With no loaders wired, the failure point is
        // the loader-availability throw, not the locator gate.
        UnifiedCrawlSource gmail = UnifiedCrawlSource.builder()
                .label("mailbox")
                .sourceType(DocumentSourceDescriptor.SourceType.GMAIL)
                .pathOrUrl("")
                .properties(Map.of("accessToken", "t"))
                .build();
        IllegalStateException gmailNoLoader = assertThrows(IllegalStateException.class,
                () -> service.loadFromSource(gmail, job, progress));
        assertTrue(gmailNoLoader.getMessage().contains("No loader or crawler available"),
                gmailNoLoader.getMessage());

        // Locator-centric types still demand one even with unrelated properties.
        UnifiedCrawlSource confluence = UnifiedCrawlSource.builder()
                .label("wiki")
                .sourceType(DocumentSourceDescriptor.SourceType.CONFLUENCE)
                .pathOrUrl(" ")
                .properties(Map.of("spaceKey", "DEV"))
                .build();
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.loadFromSource(confluence, job, progress));
        assertTrue(rejected.getMessage().contains("has no pathOrUrl"), rejected.getMessage());

        // Locator-centric + identity metadata (drive file ids) passes the gate; the
        // failure point moves past validation to loader availability.
        UnifiedCrawlSource gdrive = UnifiedCrawlSource.builder()
                .label("docs")
                .sourceType(DocumentSourceDescriptor.SourceType.GDRIVE)
                .pathOrUrl(null)
                .properties(Map.of("fileIds", List.of("file-1")))
                .build();
        IllegalStateException gdriveNoLoader = assertThrows(IllegalStateException.class,
                () -> service.loadFromSource(gdrive, job, progress));
        assertTrue(gdriveNoLoader.getMessage().contains("No loader or crawler available"),
                gdriveNoLoader.getMessage());
    }

    @Test
    void sourceMetadataAndScopeNeverExposeCredentialsEmbeddedInLocations() {
        UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                .sourceType(DocumentSourceDescriptor.SourceType.SQL)
                .pathOrUrl("jdbc:postgresql://admin:secret@db.example/data;password=hunter2")
                .build();
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());

        assertEquals(
                "SQL:jdbc:postgresql://db.example/data;password=<redacted>",
                CrawlSourceLoadingService.sourceScopeId(source));
        assertEquals(
                "jdbc:postgresql://db.example/data;password=<redacted>",
                service.sourceMetadata(source, null).get(GraphConstants.META_SOURCE_PATH));
    }

    @Test
    void sensitiveIncrementalLocationsUseStableOpaqueIdentity() {
        String raw = "https://admin:secret@example.test/path?token=hunter2";
        String rotated = "https://other:new-secret@example.test/path?token=replacement";

        String first = CrawlSourceLoadingService.persistentItemIdentity(raw);
        String second = CrawlSourceLoadingService.persistentItemIdentity(raw);

        assertEquals(first, second);
        assertEquals(first, CrawlSourceLoadingService.persistentItemIdentity(rotated),
                "credential rotation must retain the same resource identity");
        assertTrue(first.startsWith("sensitive-source:"));
        assertFalse(first.contains("admin"));
        assertFalse(first.contains("secret"));
        assertFalse(first.contains("hunter2"));
    }

    @Test
    void secretRotationDoesNotChangeProcessingFingerprint() {
        CrawlSourceLoadingService service = new CrawlSourceLoadingService(
                new CrawlDocumentTracker(), new PipelineStepTracker());
        UnifiedCrawlSource first = UnifiedCrawlSource.builder()
                .properties(Map.of("apiKey", "first-secret", "region", "us")).build();
        UnifiedCrawlSource second = UnifiedCrawlSource.builder()
                .properties(Map.of("apiKey", "rotated-secret", "region", "us")).build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().build()).build();

        assertEquals(service.processingFingerprint(first, null, job),
                service.processingFingerprint(second, null, job));
    }

    private static DocumentLoader loader(String name, boolean supports) {
        return new DocumentLoader() {
            @Override public String getName() { return name; }
            @Override public boolean supports(DocumentSourceDescriptor sourceDescriptor) { return supports; }
            @Override public List<Document> load(DocumentSourceDescriptor sourceDescriptor) {
                return List.of(new Document("content"));
            }
        };
    }
}
