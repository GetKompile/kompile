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

package ai.kompile.crawler;

import ai.kompile.core.crawler.*;
import ai.kompile.core.crawler.pipeline.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that wire a stub crawler through the full pipeline:
 * CrawlerService → CrawlPipelineRouter → PipelineAwareCrawlListener.
 *
 * These tests verify that items discovered by a crawler are correctly routed
 * to the right pipeline and that the listener receives the correct routing decisions.
 */
class CrawlPipelineEndToEndTest {

    private CrawlerService service;

    @BeforeEach
    void setUp() {
        // Build a registry with our stub crawler
        CrawlerRegistry registry = new CrawlerRegistry(List.of(new StubCrawler()));
        service = new CrawlerService(registry, new ObjectMapper());
    }

    // ---- Full flow: discover → route → listener ----

    @Test
    void itemsAreRoutedToCorrectPipelineThroughFullFlow() throws Exception {
        // Define two pipelines: html for text, pdf-vlm for PDFs
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .maxDocuments(10)
                .pipelines(List.of(
                        IngestPipelineDefinition.builder()
                                .pipelineId("html")
                                .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                                .build(),
                        IngestPipelineDefinition.builder()
                                .pipelineId("pdf-vlm")
                                .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                                .build(),
                        IngestPipelineDefinition.builder()
                                .pipelineId("code")
                                .pipelineType(IngestPipelineDefinition.PipelineType.CODE)
                                .build()
                ))
                .routeRules(List.of(
                        ContentRouteRule.builder()
                                .pipelineId("pdf-vlm")
                                .contentTypes(List.of("application/pdf"))
                                .priority(10)
                                .build(),
                        ContentRouteRule.builder()
                                .pipelineId("code")
                                .fileExtensions(List.of(".py", ".java"))
                                .priority(10)
                                .build(),
                        ContentRouteRule.builder()
                                .pipelineId("html")
                                .contentTypes(List.of("text/html"))
                                .priority(20)
                                .build()
                ))
                .defaultPipelineId("html")
                .build();

        // Collect routing decisions
        List<RoutedCrawlItem> routedItems = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<CrawlSummary> summaries = new CopyOnWriteArrayList<>();

        PipelineAwareCrawlListener listener = new PipelineAwareCrawlListener() {
            @Override
            public void onItemRouted(RoutedCrawlItem routedItem) {
                routedItems.add(routedItem);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                summaries.add(summary);
                completionLatch.countDown();
            }
        };

        CrawlJob job = service.startCrawl(config, listener);
        assertNotNull(job.getJobId());

        // Wait for crawl to complete
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Crawl did not complete within timeout");

        // Verify routing decisions
        assertEquals(4, routedItems.size(), "Expected 4 routed items (stub emits 4 items)");

        // Find items by URL to verify pipeline assignment
        Map<String, String> urlToPipeline = new HashMap<>();
        for (RoutedCrawlItem ri : routedItems) {
            urlToPipeline.put(ri.item().getUrl(), ri.pipeline().getPipelineId());
        }

        assertEquals("html", urlToPipeline.get("stub://test/page.html"));
        assertEquals("pdf-vlm", urlToPipeline.get("stub://test/doc.pdf"));
        assertEquals("code", urlToPipeline.get("stub://test/main.py"));
        assertEquals("html", urlToPipeline.get("stub://test/unknown.xyz"));  // default pipeline

        // Verify summary
        assertEquals(1, summaries.size());
        assertEquals(CrawlStatus.COMPLETED, summaries.get(0).status());
        assertEquals(4, summaries.get(0).totalDiscovered());
    }

    @Test
    void serviceTracksJobAndRouter() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .pipelines(List.of(
                        IngestPipelineDefinition.builder()
                                .pipelineId("default")
                                .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                                .build()
                ))
                .defaultPipelineId("default")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        CrawlJob job = service.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        });

        // Job should be tracked
        assertTrue(service.getJob(job.getJobId()).isPresent());
        assertTrue(service.getRouter(job.getJobId()).isPresent());

        // Router should have the pipeline we defined
        CrawlPipelineRouter router = service.getRouter(job.getJobId()).get();
        assertEquals(1, router.getAllPipelines().size());
        assertEquals("default", router.getDefaultPipeline().getPipelineId());

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // After completion, job should still be tracked
        assertTrue(service.getJob(job.getJobId()).isPresent());
        assertEquals(CrawlStatus.COMPLETED, service.getJob(job.getJobId()).get().getStatus());
    }

    @Test
    void jobLifecyclePauseResumeCancel() throws Exception {
        // Use slow crawler that pauses between items
        CrawlerRegistry registry = new CrawlerRegistry(List.of(new SlowStubCrawler()));
        CrawlerService slowService = new CrawlerService(registry, new ObjectMapper());

        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://slow")
                .crawlerId("slow-stub")
                .maxDocuments(100)
                .build();

        CountDownLatch firstItemLatch = new CountDownLatch(1);
        CrawlJob job = slowService.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                firstItemLatch.countDown();
            }
        });

        // Wait for the crawl to start and discover at least one item
        assertTrue(firstItemLatch.await(5, TimeUnit.SECONDS), "Crawl didn't start");

        // Pause
        assertTrue(slowService.pauseJob(job.getJobId()));
        assertEquals(CrawlStatus.PAUSED, job.getStatus());

        // Resume
        assertTrue(slowService.resumeJob(job.getJobId()));
        assertEquals(CrawlStatus.RUNNING, job.getStatus());

        // Cancel
        assertTrue(slowService.cancelJob(job.getJobId()));
        // Status should be CANCELLED (eventually)
        job.getCompletionFuture().get(5, TimeUnit.SECONDS);
        assertEquals(CrawlStatus.CANCELLED, job.getStatus());
    }

    @Test
    void cleanupRemovesOnlyFinishedJobs() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        CrawlJob job = service.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, service.getAllJobs().size());

        int removed = service.cleanupJobs();
        assertEquals(1, removed);
        assertEquals(0, service.getAllJobs().size());
    }

    @Test
    void syntheticDefaultPipelineWhenNoPipelinesDefined() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .loaderName("custom-loader")
                .chunkerName("custom-chunker")
                .build();

        List<RoutedCrawlItem> routedItems = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        CrawlJob job = service.startCrawl(config, new PipelineAwareCrawlListener() {
            @Override
            public void onItemRouted(RoutedCrawlItem routedItem) {
                routedItems.add(routedItem);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // All items should go to the synthetic "default" pipeline
        assertFalse(routedItems.isEmpty());
        for (RoutedCrawlItem ri : routedItems) {
            assertEquals("default", ri.pipeline().getPipelineId());
            assertEquals("custom-loader", ri.pipeline().getLoaderName());
            assertEquals("custom-chunker", ri.pipeline().getChunkerName());
        }
    }

    @Test
    void routeRuleMatchedRuleIsSetForExplicitMatchesButNullForDefault() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .pipelines(List.of(
                        IngestPipelineDefinition.builder()
                                .pipelineId("pdf")
                                .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                                .build(),
                        IngestPipelineDefinition.builder()
                                .pipelineId("default")
                                .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                                .build()
                ))
                .routeRules(List.of(
                        ContentRouteRule.builder()
                                .pipelineId("pdf")
                                .contentTypes(List.of("application/pdf"))
                                .priority(10)
                                .build()
                ))
                .defaultPipelineId("default")
                .build();

        List<RoutedCrawlItem> routedItems = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.startCrawl(config, new PipelineAwareCrawlListener() {
            @Override
            public void onItemRouted(RoutedCrawlItem routedItem) {
                routedItems.add(routedItem);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        for (RoutedCrawlItem ri : routedItems) {
            if ("pdf".equals(ri.pipeline().getPipelineId())) {
                assertNotNull(ri.matchedRule(), "PDF match should have a non-null matchedRule");
                assertEquals("pdf", ri.matchedRule().getPipelineId());
            } else {
                assertNull(ri.matchedRule(), "Default fallback should have null matchedRule");
            }
        }
    }

    @Test
    void progressAndCompletionEventsFireDuringCrawl() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .build();

        List<CrawlProgress> progressEvents = new CopyOnWriteArrayList<>();
        CountDownLatch completionLatch = new CountDownLatch(1);

        service.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onProgress(CrawlProgress progress) {
                progressEvents.add(progress);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                completionLatch.countDown();
            }
        });

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));

        // The stub crawler emits progress events
        assertFalse(progressEvents.isEmpty(), "Should have received progress events");
    }

    @Test
    void multipleJobsRunConcurrently() throws Exception {
        CrawlConfig config1 = CrawlConfig.builder()
                .seed("stub://test1")
                .crawlerId("stub")
                .build();
        CrawlConfig config2 = CrawlConfig.builder()
                .seed("stub://test2")
                .crawlerId("stub")
                .build();

        CountDownLatch latch = new CountDownLatch(2);
        CrawlEventListener listener = new CrawlEventListener() {
            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        };

        CrawlJob job1 = service.startCrawl(config1, listener);
        CrawlJob job2 = service.startCrawl(config2, listener);

        assertNotEquals(job1.getJobId(), job2.getJobId());
        assertEquals(2, service.getAllJobs().size());

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(CrawlStatus.COMPLETED, service.getJob(job1.getJobId()).get().getStatus());
        assertEquals(CrawlStatus.COMPLETED, service.getJob(job2.getJobId()).get().getStatus());
    }

    @Test
    void activeJobsFilterExcludesCompleted() throws Exception {
        CrawlConfig config = CrawlConfig.builder()
                .seed("stub://test")
                .crawlerId("stub")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        CrawlJob job = service.startCrawl(config, new CrawlEventListener() {
            @Override
            public void onComplete(CrawlSummary summary) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(CrawlStatus.COMPLETED, job.getStatus());

        // Completed job should NOT appear in active list
        assertTrue(service.getActiveJobs().isEmpty());
        // But should appear in all jobs
        assertEquals(1, service.getAllJobs().size());
    }

    // ---- Stub Crawler: emits 4 items with different content types ----

    static class StubCrawler extends AbstractCrawler {

        @Override
        public String getId() { return "stub"; }

        @Override
        public String getName() { return "Stub Crawler"; }

        @Override
        public String getDescription() { return "Test crawler that emits simulated items"; }

        @Override
        public Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes() {
            return Set.of(DocumentSourceDescriptor.SourceType.URL);
        }

        @Override
        protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
            return new StubCrawlJob(jobId, config, listener);
        }

        @Override
        protected void executeCrawl(AbstractCrawlJob job) {
            StubCrawlJob stubJob = (StubCrawlJob) job;
            String seed = job.getConfig().getSeed();

            // Emit 4 items with different content types
            CrawlItem[] items = {
                    CrawlItem.builder()
                            .url(seed + "/page.html")
                            .contentType("text/html")
                            .discoveredAt(Instant.now())
                            .build(),
                    CrawlItem.builder()
                            .url(seed + "/doc.pdf")
                            .contentType("application/pdf")
                            .contentLength(50000L)
                            .discoveredAt(Instant.now())
                            .build(),
                    CrawlItem.builder()
                            .url(seed + "/main.py")
                            .contentType("text/plain")
                            .discoveredAt(Instant.now())
                            .build(),
                    CrawlItem.builder()
                            .url(seed + "/unknown.xyz")
                            .contentType("application/octet-stream")
                            .discoveredAt(Instant.now())
                            .build()
            };

            for (CrawlItem item : items) {
                if (job.shouldStop()) break;
                if (!job.checkPauseAndContinue()) break;

                job.incrementDiscovered();
                job.setCurrentItem(item.getUrl());
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                job.getListener().onProgress(job.getProgress());
            }
        }
    }

    static class StubCrawlJob extends AbstractCrawlJob {
        StubCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
            super(jobId, config, listener);
        }

        @Override
        public CrawlState checkpoint() {
            return CrawlState.builder().timestamp(Instant.now()).build();
        }
    }

    // ---- Slow stub crawler for lifecycle tests ----

    static class SlowStubCrawler extends AbstractCrawler {

        @Override
        public String getId() { return "slow-stub"; }

        @Override
        public String getName() { return "Slow Stub Crawler"; }

        @Override
        public String getDescription() { return "Slow test crawler for lifecycle tests"; }

        @Override
        public Set<DocumentSourceDescriptor.SourceType> getSupportedSourceTypes() {
            return Set.of(DocumentSourceDescriptor.SourceType.URL);
        }

        @Override
        protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
            return new StubCrawlJob(jobId, config, listener);
        }

        @Override
        protected void executeCrawl(AbstractCrawlJob job) {
            for (int i = 0; i < 100; i++) {
                if (job.shouldStop()) break;
                if (!job.checkPauseAndContinue()) break;

                CrawlItem item = CrawlItem.builder()
                        .url("stub://slow/item-" + i)
                        .contentType("text/plain")
                        .discoveredAt(Instant.now())
                        .build();

                job.incrementDiscovered();
                job.setCurrentItem(item.getUrl());
                job.getListener().onDocumentDiscovered(item);

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                job.incrementProcessed();
            }
        }
    }
}
