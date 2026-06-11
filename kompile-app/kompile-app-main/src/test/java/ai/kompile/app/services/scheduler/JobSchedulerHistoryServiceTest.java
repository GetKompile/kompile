package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobSchedulerHistoryService")
class JobSchedulerHistoryServiceTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock private ResourceSchedulerConfigService configService;

    private JobSchedulerHistoryService historyService;

    @BeforeEach
    void setUp() {
        ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
        when(configService.getConfiguration()).thenReturn(config);
        historyService = new JobSchedulerHistoryService(configService);
        // Don't call init() — it depends on KompileHome which isn't available in tests
    }

    @Nested
    @DisplayName("Event-based recording")
    class EventBasedRecording {

        @Test
        void completedEventRecordsHistory() {
            Object source = new Object();
            JobSchedulerEvent event = JobSchedulerEvent.jobCompleted(
                    source, "j1", "ingest", "Test ingest", 5000L, 0, 0);

            historyService.onJobSchedulerEvent(event);

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals(1, history.size());
            assertEquals("j1", history.get(0).jobId());
            assertEquals("ingest", history.get(0).jobType());
            assertEquals("COMPLETED", history.get(0).state());
            assertEquals(5000L, history.get(0).durationMs());
        }

        @Test
        void failedEventRecordsHistoryWithError() {
            Object source = new Object();
            JobSchedulerEvent event = JobSchedulerEvent.jobFailed(
                    source, "j1", "training", "Test training", "OOM error", 0, 0);

            historyService.onJobSchedulerEvent(event);

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals(1, history.size());
            assertEquals("FAILED", history.get(0).state());
            assertEquals("OOM error", history.get(0).error());
        }

        @Test
        void cancelledEventRecordsHistory() {
            Object source = new Object();
            JobSchedulerEvent event = JobSchedulerEvent.jobCancelled(
                    source, "j1", "crawl", "User cancelled", 0, 0);

            historyService.onJobSchedulerEvent(event);

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals(1, history.size());
            assertEquals("CANCELLED", history.get(0).state());
        }

        @Test
        void queuedEventDoesNotRecordHistory() {
            Object source = new Object();
            JobSchedulerEvent event = JobSchedulerEvent.jobQueued(
                    source, "j1", "ingest", 1, 0, Map.of());

            historyService.onJobSchedulerEvent(event);

            assertTrue(historyService.getRecentHistory(10).isEmpty());
        }

        @Test
        void phaseTransitionsAreIncludedInCompletion() {
            Object source = new Object();

            // Record phase transitions
            JobSchedulerEvent phase1 = JobSchedulerEvent.jobPhaseTransition(
                    source, "j1", "unifiedCrawl", null, "LOADING", false, 0, 1);
            JobSchedulerEvent phase2 = JobSchedulerEvent.jobPhaseTransition(
                    source, "j1", "unifiedCrawl", "LOADING", "CHUNKING", false, 0, 1);
            JobSchedulerEvent phase3 = JobSchedulerEvent.jobPhaseTransition(
                    source, "j1", "unifiedCrawl", "CHUNKING", "VECTOR_INDEXING", true, 0, 1);

            historyService.onJobSchedulerEvent(phase1);
            historyService.onJobSchedulerEvent(phase2);
            historyService.onJobSchedulerEvent(phase3);

            // Now complete
            JobSchedulerEvent completed = JobSchedulerEvent.jobCompleted(
                    source, "j1", "unifiedCrawl", "Crawl test", 30000L, 0, 0);
            historyService.onJobSchedulerEvent(completed);

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals(1, history.size());
            assertEquals(3, history.get(0).phases().size());
            assertEquals("LOADING", history.get(0).phases().get(0).phaseName());
            assertEquals("VECTOR_INDEXING", history.get(0).phases().get(2).phaseName());
            assertTrue(history.get(0).phases().get(2).requiresGpu());
        }

        @Test
        void nullJobIdEventsAreIgnored() {
            Object source = new Object();
            JobSchedulerEvent event = JobSchedulerEvent.schedulerStarted(source);
            historyService.onJobSchedulerEvent(event);
            assertTrue(historyService.getRecentHistory(10).isEmpty());
        }
    }

    @Nested
    @DisplayName("recordFromJob")
    class RecordFromJob {

        @Test
        void recordFromJobCreatesRichEntry() {
            ScheduledJob job = ScheduledJob.builder()
                    .jobId("j1").jobType("ingest")
                    .description("Test ingest")
                    .resourceProfile(JobResourceProfile.gpuRequired("ingest", "Ingest", 2 * GB, 4 * GB))
                    .executor(ctx -> {})
                    .priority(75)
                    .build();

            job.setStartedAt(Instant.now().minusSeconds(10));
            job.setCompletedAt(Instant.now());
            job.setState(ScheduledJob.JobState.COMPLETED);

            historyService.recordFromJob(job);

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals(1, history.size());

            JobSchedulerHistoryService.JobHistoryEntry entry = history.get(0);
            assertEquals("j1", entry.jobId());
            assertEquals("ingest", entry.jobType());
            assertEquals("Test ingest", entry.description());
            assertEquals("COMPLETED", entry.state());
            assertEquals(75, entry.priority());
            assertTrue(entry.requiresGpu());
            assertEquals(2048, entry.peakGpuMemoryMb());
            assertTrue(entry.durationMs() >= 9000);
            assertNotNull(entry.queuedAt());
            assertNotNull(entry.startedAt());
            assertNotNull(entry.completedAt());
        }
    }

    @Nested
    @DisplayName("Query API")
    class QueryApi {

        @BeforeEach
        void populateHistory() {
            Object source = new Object();
            historyService.onJobSchedulerEvent(
                    JobSchedulerEvent.jobCompleted(source, "j1", "ingest", null, 1000, 0, 0));
            historyService.onJobSchedulerEvent(
                    JobSchedulerEvent.jobCompleted(source, "j2", "ingest", null, 2000, 0, 0));
            historyService.onJobSchedulerEvent(
                    JobSchedulerEvent.jobFailed(source, "j3", "training", null, "OOM", 0, 0));
            historyService.onJobSchedulerEvent(
                    JobSchedulerEvent.jobCompleted(source, "j4", "crawl", null, 500, 0, 0));
        }

        @Test
        void getRecentHistoryRespectsLimit() {
            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(2);
            assertEquals(2, history.size());
        }

        @Test
        void getRecentHistoryReturnsMostRecentFirst() {
            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(10);
            assertEquals("j4", history.get(0).jobId());
            assertEquals("j1", history.get(3).jobId());
        }

        @Test
        void getHistoryByTypeFilters() {
            List<JobSchedulerHistoryService.JobHistoryEntry> ingestHistory =
                    historyService.getHistoryByType("ingest", 10);
            assertEquals(2, ingestHistory.size());
            assertTrue(ingestHistory.stream().allMatch(e -> "ingest".equals(e.jobType())));
        }

        @Test
        void getHistoryByStateFilters() {
            List<JobSchedulerHistoryService.JobHistoryEntry> failed =
                    historyService.getHistoryByState("FAILED", 10);
            assertEquals(1, failed.size());
            assertEquals("j3", failed.get(0).jobId());
        }

        @Test
        void getStatsReturnsAggregates() {
            Map<String, Object> stats = historyService.getStats();

            assertEquals(4, stats.get("totalEntries"));

            @SuppressWarnings("unchecked")
            Map<String, Long> byType = (Map<String, Long>) stats.get("countByType");
            assertEquals(2L, byType.get("ingest"));
            assertEquals(1L, byType.get("training"));
            assertEquals(1L, byType.get("crawl"));

            @SuppressWarnings("unchecked")
            Map<String, Long> byState = (Map<String, Long>) stats.get("countByState");
            assertEquals(3L, byState.get("COMPLETED"));
            assertEquals(1L, byState.get("FAILED"));

            assertNotNull(stats.get("avgDurationMsByType"));
            assertNotNull(stats.get("avgWaitMsByType"));
        }
    }

    @Nested
    @DisplayName("Cache management")
    class CacheManagement {

        @Test
        void cacheDoesNotExceedMaxSize() {
            Object source = new Object();
            // Generate more than MAX_RECENT_CACHE (500) entries
            for (int i = 0; i < 510; i++) {
                historyService.onJobSchedulerEvent(
                        JobSchedulerEvent.jobCompleted(source, "j" + i, "ingest", null, 100, 0, 0));
            }

            List<JobSchedulerHistoryService.JobHistoryEntry> history =
                    historyService.getRecentHistory(600);
            assertTrue(history.size() <= 500,
                    "Cache should be bounded at 500, got " + history.size());
        }
    }
}
