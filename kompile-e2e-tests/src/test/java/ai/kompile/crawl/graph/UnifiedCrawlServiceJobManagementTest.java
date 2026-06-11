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

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.CrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

/**
 * E2E tests for {@link UnifiedCrawlGraphServiceImpl} focusing on the synchronous
 * job management API: startJob validation, getJob, getAllJobs, getActiveJobs,
 * cancelJob, cleanupJobs, getAvailableSourceTypes, progress tracking, and
 * the job state machine.
 *
 * <p>The module-local test covers the full async pipeline (entity extraction,
 * vector indexing, cross-document relations). This test covers the job lifecycle
 * management layer that wraps the pipeline.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedCrawlServiceJobManagementTest {

    @Mock private CrawlerService crawlerService;
    @Mock private DocumentLoader fileLoader;
    @Mock private DocumentLoader emailLoader;

    private UnifiedCrawlGraphServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new UnifiedCrawlGraphServiceImpl();
        setField(service, "crawlerService", crawlerService);
        setField(service, "documentLoaders", List.of(fileLoader, emailLoader));

        when(fileLoader.supports(any(DocumentSourceDescriptor.class)))
                .thenAnswer(inv -> {
                    DocumentSourceDescriptor d = inv.getArgument(0);
                    return d != null && (d.getType() == SourceType.FILE || d.getType() == SourceType.DIRECTORY);
                });
        when(fileLoader.getName()).thenReturn("File Loader");

        when(emailLoader.supports(any(DocumentSourceDescriptor.class)))
                .thenAnswer(inv -> {
                    DocumentSourceDescriptor d = inv.getArgument(0);
                    return d != null && d.getType() == SourceType.EMAIL;
                });
        when(emailLoader.getName()).thenReturn("Email Loader");

        when(crawlerService.hasCrawlerForSourceType(any(DocumentSourceDescriptor.SourceType.class)))
                .thenAnswer(inv -> inv.getArgument(0) == SourceType.WEB_CRAWL);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private UnifiedCrawlSource fileSource(String label, String path) {
        return UnifiedCrawlSource.builder()
                .label(label)
                .sourceType(SourceType.FILE)
                .pathOrUrl(path)
                .build();
    }

    private UnifiedCrawlSource emailSource(String label) {
        return UnifiedCrawlSource.builder()
                .label(label)
                .sourceType(SourceType.EMAIL)
                .pathOrUrl("imap://mail.example.com")
                .build();
    }

    private UnifiedCrawlRequest simpleRequest(String name, UnifiedCrawlSource... sources) {
        return UnifiedCrawlRequest.builder()
                .name(name)
                .sources(List.of(sources))
                .graphExtraction(GraphExtractionConfig.builder().enabled(false).build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // startJob validation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class StartJobValidation {

        @Test
        void rejectsNullSources() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("bad request")
                    .sources(null)
                    .build();

            assertThrows(IllegalArgumentException.class, () -> service.startJob(request));
        }

        @Test
        void rejectsEmptySources() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("bad request")
                    .sources(List.of())
                    .build();

            assertThrows(IllegalArgumentException.class, () -> service.startJob(request));
        }

        @Test
        void validRequestReturnsJob() throws Exception {
            // Use a latch-based loader to control execution
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            assertNotNull(job);
            assertNotNull(job.getJobId());
            assertNotNull(job.getCreatedAt());
            assertEquals("test", job.getRequest().getName());
        }

        @Test
        void jobStartsInPendingOrRunning() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            // It starts as PENDING and quickly transitions to RUNNING
            UnifiedCrawlJob.Status status = job.getStatus().get();
            assertTrue(status == UnifiedCrawlJob.Status.PENDING
                    || status == UnifiedCrawlJob.Status.RUNNING
                    || status == UnifiedCrawlJob.Status.COMPLETED,
                    "Job should start as PENDING or have transitioned to RUNNING/COMPLETED");
        }

        @Test
        void sourceProgressInitialized() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test",
                    fileSource("docs", "/data/docs"),
                    emailSource("emails")));

            assertEquals(2, job.getSourceProgress().size());
            assertEquals("docs", job.getSourceProgress().get(0).getLabel());
            assertEquals("FILE", job.getSourceProgress().get(0).getSourceType());
            assertEquals("/data/docs", job.getSourceProgress().get(0).getPathOrUrl());
            assertEquals("emails", job.getSourceProgress().get(1).getLabel());
            assertEquals("EMAIL", job.getSourceProgress().get(1).getSourceType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getJob / getAllJobs / getActiveJobs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class JobRetrieval {

        @Test
        void getJobReturnsCreatedJob() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob created = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            Optional<UnifiedCrawlJob> retrieved = service.getJob(created.getJobId());
            assertTrue(retrieved.isPresent());
            assertEquals(created.getJobId(), retrieved.get().getJobId());
        }

        @Test
        void getJobReturnsEmptyForUnknownId() {
            Optional<UnifiedCrawlJob> result = service.getJob("nonexistent-id");
            assertFalse(result.isPresent());
        }

        @Test
        void getAllJobsReturnsAllCreated() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            service.startJob(simpleRequest("job1", fileSource("f1", "/a")));
            service.startJob(simpleRequest("job2", fileSource("f2", "/b")));

            List<UnifiedCrawlJob> all = service.getAllJobs();
            assertEquals(2, all.size());
        }

        @Test
        void getActiveJobsFiltersCompleted() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            // One job that blocks in the loader (stays RUNNING)
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob runningJob = service.startJob(simpleRequest("blocking", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            List<UnifiedCrawlJob> active = service.getActiveJobs();
            assertTrue(active.stream().anyMatch(j -> j.getJobId().equals(runningJob.getJobId())),
                    "Running job should appear in active list");

            blockLatch.countDown(); // unblock
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // cancelJob
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CancelJobTests {

        @Test
        void cancelRunningJobSucceeds() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown(); // signal that we're inside load
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            // Wait until the job is actually inside the loader (guaranteed RUNNING)
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            boolean cancelled = service.cancelJob(job.getJobId());
            assertTrue(cancelled);
            assertEquals(UnifiedCrawlJob.Status.CANCELLED, job.getStatus().get());
            assertNotNull(job.getCompletedAt());

            blockLatch.countDown();
        }

        @Test
        void cancelNonexistentJobReturnsFalse() {
            assertFalse(service.cancelJob("nonexistent-id"));
        }

        @Test
        void cancelCompletedJobReturnsFalse() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            // Wait for completion
            awaitJobStatus(job, UnifiedCrawlJob.Status.COMPLETED, 5);

            assertFalse(service.cancelJob(job.getJobId()));
        }

        @Test
        void cancelAlreadyCancelledJobReturnsFalse() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS));
            assertTrue(service.cancelJob(job.getJobId()), "First cancel should succeed");

            assertFalse(service.cancelJob(job.getJobId()), "Second cancel should return false");

            blockLatch.countDown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // cleanupJobs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CleanupJobTests {

        @Test
        void cleanupRemovesCompletedJobs() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("done", fileSource("f", "/data")));
            awaitJobStatus(job, UnifiedCrawlJob.Status.COMPLETED, 5);

            int removed = service.cleanupJobs();
            assertEquals(1, removed);
            assertTrue(service.getAllJobs().isEmpty());
        }

        @Test
        void cleanupRemovesCancelledJobs() throws Exception {
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("cancel-me", fileSource("f", "/data")));
            service.cancelJob(job.getJobId());

            int removed = service.cleanupJobs();
            assertEquals(1, removed);
            assertTrue(service.getAllJobs().isEmpty());

            blockLatch.countDown();
        }

        @Test
        void cleanupRemovesFailedJobs() throws Exception {
            when(fileLoader.load(any(), any())).thenThrow(new RuntimeException("load failure"));

            UnifiedCrawlJob job = service.startJob(simpleRequest("fail", fileSource("f", "/data")));
            awaitJobTerminal(job, 5);

            int removed = service.cleanupJobs();
            assertTrue(removed >= 1);
        }

        @Test
        void cleanupLeavesActiveJobsIntact() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            service.startJob(simpleRequest("running", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            int removed = service.cleanupJobs();
            assertEquals(0, removed);
            assertEquals(1, service.getAllJobs().size());

            blockLatch.countDown();
        }

        @Test
        void cleanupOnEmptyReturnsZero() {
            assertEquals(0, service.cleanupJobs());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getAvailableSourceTypes
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AvailableSourceTypeTests {

        @Test
        void alwaysIncludesDirectoryFileUrl() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "DIRECTORY".equals(t.type()) && t.available()));
            assertTrue(types.stream().anyMatch(t -> "FILE".equals(t.type()) && t.available()));
            assertTrue(types.stream().anyMatch(t -> "URL".equals(t.type()) && t.available()));
        }

        @Test
        void webCrawlAvailableWhenCrawlerServicePresent() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "WEB_CRAWL".equals(t.type()) && t.available()),
                    "WEB_CRAWL should be available when crawlerService is injected");
        }

        @Test
        void webCrawlUnavailableWhenCrawlerServiceNull() throws Exception {
            setField(service, "crawlerService", null);

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "WEB_CRAWL".equals(t.type()) && !t.available()),
                    "WEB_CRAWL should be unavailable when crawlerService is null");
        }

        @Test
        void emailAvailableWhenEmailLoaderPresent() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && t.available()));
        }

        @Test
        void emailUnavailableWhenNoEmailLoader() throws Exception {
            setField(service, "documentLoaders", List.of(fileLoader));

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && !t.available()));
        }

        @Test
        void sourceTypeHasRequiredProperties() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            UnifiedCrawlService.AvailableSourceType email = types.stream()
                    .filter(t -> "EMAIL".equals(t.type())).findFirst().orElseThrow();

            assertTrue(email.requiredProperties().contains("pathOrUrl"));
            assertFalse(email.optionalProperties().isEmpty());
        }

        @Test
        void allSourceTypesHaveDisplayNameAndDescription() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            for (UnifiedCrawlService.AvailableSourceType type : types) {
                assertNotNull(type.displayName(), "Missing displayName for " + type.type());
                assertNotNull(type.description(), "Missing description for " + type.type());
            }
        }

        @Test
        void expectedSourceTypeCount() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();
            assertEquals(DocumentSourceDescriptor.SourceType.values().length, types.size());
        }

        @Test
        void noLoadersReturnsListWithBaseTypes() throws Exception {
            setField(service, "documentLoaders", null);
            setField(service, "crawlerService", null);

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            // DIRECTORY, FILE, URL are always available (built-in)
            assertTrue(types.stream().anyMatch(t -> "DIRECTORY".equals(t.type()) && t.available()));
            // EMAIL, SLACK, etc. should be unavailable
            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && !t.available()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Progress snapshot
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ProgressSnapshotTests {

        @Test
        void snapshotCapturesJobState() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of(
                    new Document("text", Map.of())));

            UnifiedCrawlJob job = service.startJob(simpleRequest("snap", fileSource("f", "/data")));
            awaitJobTerminal(job, 5);

            UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
            assertEquals(job.getJobId(), snap.getJobId());
            assertEquals("snap", snap.getName());
            assertNotNull(snap.getCreatedAt());
            assertTrue(snap.getSourceProgress().size() >= 1);
        }

        @Test
        void snapshotReflectsCounters() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of(
                    new Document("doc1", Map.of()),
                    new Document("doc2", Map.of())));

            UnifiedCrawlJob job = service.startJob(simpleRequest("cnt", fileSource("f", "/data")));
            awaitJobTerminal(job, 5);

            UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
            assertTrue(snap.getDocumentsLoaded() >= 2,
                    "Should reflect at least 2 documents loaded");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Multi-job isolation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class MultiJobTests {

        @Test
        void jobsHaveUniqueIds() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job1 = service.startJob(simpleRequest("a", fileSource("f1", "/a")));
            UnifiedCrawlJob job2 = service.startJob(simpleRequest("b", fileSource("f2", "/b")));

            assertNotEquals(job1.getJobId(), job2.getJobId());
        }

        @Test
        void cancelOneJobDoesNotAffectOther() throws Exception {
            setField(service, "maxConcurrentJobs", 2);
            CountDownLatch started1 = new CountDownLatch(1);
            CountDownLatch started2 = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger(0);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                int n = loadCount.incrementAndGet();
                if (n == 1) started1.countDown();
                else started2.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job1 = service.startJob(simpleRequest("a", fileSource("f1", "/a")));
            UnifiedCrawlJob job2 = service.startJob(simpleRequest("b", fileSource("f2", "/b")));
            assertTrue(started1.await(3, TimeUnit.SECONDS), "Job1 should start loading");
            assertTrue(started2.await(3, TimeUnit.SECONDS), "Job2 should start loading");

            service.cancelJob(job1.getJobId());

            assertEquals(UnifiedCrawlJob.Status.CANCELLED, job1.getStatus().get());
            assertNotEquals(UnifiedCrawlJob.Status.CANCELLED, job2.getStatus().get());

            blockLatch.countDown();
        }

        @Test
        void cleanupOnlyRemovesTerminalJobs() throws Exception {
            setField(service, "maxConcurrentJobs", 2);
            CountDownLatch runningStarted = new CountDownLatch(1);
            CountDownLatch cancelledStarted = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            // Track invocation count to distinguish the two jobs
            java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger(0);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                int n = loadCount.incrementAndGet();
                if (n == 1) runningStarted.countDown();
                else cancelledStarted.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob running = service.startJob(simpleRequest("running", fileSource("f1", "/a")));
            assertTrue(runningStarted.await(3, TimeUnit.SECONDS), "First job should start loading");

            // Create and wait for the second job to start, then cancel it
            UnifiedCrawlJob cancelled = service.startJob(simpleRequest("cancelled", fileSource("f2", "/b")));
            assertTrue(cancelledStarted.await(3, TimeUnit.SECONDS), "Second job should start loading");
            service.cancelJob(cancelled.getJobId());

            int removed = service.cleanupJobs();
            assertEquals(1, removed);
            assertEquals(1, service.getAllJobs().size());
            assertEquals(running.getJobId(), service.getAllJobs().get(0).getJobId());

            blockLatch.countDown();
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private void awaitJobStatus(UnifiedCrawlJob job, UnifiedCrawlJob.Status expected, int timeoutSecs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (job.getStatus().get() == expected) return;
            Thread.sleep(50);
        }
        assertEquals(expected, job.getStatus().get(), "Job did not reach " + expected + " within timeout");
    }

    private void awaitJobTerminal(UnifiedCrawlJob job, int timeoutSecs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            UnifiedCrawlJob.Status s = job.getStatus().get();
            if (s == UnifiedCrawlJob.Status.COMPLETED || s == UnifiedCrawlJob.Status.FAILED
                    || s == UnifiedCrawlJob.Status.CANCELLED) return;
            Thread.sleep(50);
        }
        UnifiedCrawlJob.Status s = job.getStatus().get();
        assertTrue(s == UnifiedCrawlJob.Status.COMPLETED || s == UnifiedCrawlJob.Status.FAILED
                || s == UnifiedCrawlJob.Status.CANCELLED,
                "Job did not reach terminal state within timeout, was: " + s);
    }

}
