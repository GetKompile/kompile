package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.CrawlJobRecord;
import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import ai.kompile.app.ingest.repository.CrawlJobRecordRepository;
import ai.kompile.core.crawler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CrawlJobPersistenceService")
class CrawlJobPersistenceServiceTest {

    @Mock
    private CrawlJobRecordRepository repository;

    private CrawlJobPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new CrawlJobPersistenceService(repository);
    }

    @Nested
    @DisplayName("markInterruptedOnStartup")
    class MarkInterruptedOnStartup {

        @Test
        void marksActiveJobsAsInterrupted() {
            CrawlJobRecord running = CrawlJobRecord.builder()
                    .crawlJobId("job-1")
                    .seed("https://a.com")
                    .status(CrawlJobStatus.RUNNING)
                    .build();
            CrawlJobRecord paused = CrawlJobRecord.builder()
                    .crawlJobId("job-2")
                    .seed("https://b.com")
                    .status(CrawlJobStatus.PAUSED)
                    .build();

            when(repository.findActiveOnStartup()).thenReturn(List.of(running, paused));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.markInterruptedOnStartup();

            assertEquals(CrawlJobStatus.INTERRUPTED, running.getStatus());
            assertNotNull(running.getEndedAt());
            assertEquals(CrawlJobStatus.INTERRUPTED, paused.getStatus());
            assertNotNull(paused.getEndedAt());
            verify(repository, times(2)).save(any());
        }

        @Test
        void noOpWhenNoActiveJobs() {
            when(repository.findActiveOnStartup()).thenReturn(List.of());

            service.markInterruptedOnStartup();

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("createRecord")
    class CreateRecord {

        @Test
        void createsRecordWithConfigJson() {
            CrawlJob job = mock(CrawlJob.class);
            when(job.getJobId()).thenReturn("job-10");

            CrawlConfig config = mock(CrawlConfig.class);
            when(config.getCrawlerId()).thenReturn("web");
            when(config.getSeed()).thenReturn("https://example.com");

            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CrawlJobRecord result = service.createRecord(job, config, "task-abc");

            assertEquals("job-10", result.getCrawlJobId());
            assertEquals("web", result.getCrawlerId());
            assertEquals("https://example.com", result.getSeed());
            assertEquals(CrawlJobStatus.RUNNING, result.getStatus());
            assertEquals("task-abc", result.getHistoryTaskId());
            assertEquals(0, result.getDocumentsDiscovered());
            assertNotNull(result.getStartedAt());
        }
    }

    @Nested
    @DisplayName("saveCheckpoint")
    class SaveCheckpoint {

        @Test
        void savesCheckpointWithProgress() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-20")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .build();

            when(repository.findByCrawlJobId("job-20")).thenReturn(Optional.of(record));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CrawlState state = CrawlState.builder()
                    .visitedUrls(Set.of("https://a.com"))
                    .build();
            CrawlProgress progress = new CrawlProgress(50, 40, 3, 7, 2, 5, "https://a.com/page", 80, Instant.now());

            service.saveCheckpoint("job-20", state, progress);

            assertNotNull(record.getLastCheckpointJson());
            assertNotNull(record.getLastCheckpointAt());
            assertEquals(50, record.getDocumentsDiscovered());
            assertEquals(40, record.getDocumentsProcessed());
            verify(repository).save(record);
        }

        @Test
        void noOpWhenJobNotFound() {
            when(repository.findByCrawlJobId("missing")).thenReturn(Optional.empty());

            service.saveCheckpoint("missing", CrawlState.builder().build(), null);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("finalizeRecord")
    class FinalizeRecord {

        @Test
        void setsTerminalStatusWithError() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-30")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .build();

            when(repository.findByCrawlJobId("job-30")).thenReturn(Optional.of(record));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CrawlState finalState = CrawlState.builder()
                    .visitedUrls(Set.of("https://a.com"))
                    .build();
            CrawlProgress finalProgress = new CrawlProgress(100, 95, 5, 0, 3, 5, "", 100, Instant.now());

            service.finalizeRecord("job-30", CrawlJobStatus.FAILED, finalState, finalProgress, "Connection timeout");

            assertEquals(CrawlJobStatus.FAILED, record.getStatus());
            assertNotNull(record.getEndedAt());
            assertEquals("Connection timeout", record.getErrorMessage());
            assertNotNull(record.getLastCheckpointJson());
            assertEquals(100, record.getDocumentsDiscovered());
            assertEquals(95, record.getDocumentsProcessed());
        }

        @Test
        void completedWithNoError() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-31")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.RUNNING)
                    .build();

            when(repository.findByCrawlJobId("job-31")).thenReturn(Optional.of(record));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.finalizeRecord("job-31", CrawlJobStatus.COMPLETED, null, null, null);

            assertEquals(CrawlJobStatus.COMPLETED, record.getStatus());
            assertNotNull(record.getEndedAt());
            assertNull(record.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("loadConfig and loadCheckpoint")
    class LoadMethods {

        @Test
        void loadConfigDeserializesJson() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-40")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .crawlConfigJson("{\"crawlerId\":\"web\",\"seed\":\"https://example.com\",\"maxDepth\":3}")
                    .build();

            when(repository.findByCrawlJobId("job-40")).thenReturn(Optional.of(record));

            Optional<CrawlConfig> config = service.loadConfig("job-40");

            assertTrue(config.isPresent());
            assertEquals("https://example.com", config.get().getSeed());
        }

        @Test
        void loadConfigReturnsEmptyWhenNoJson() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-41")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .crawlConfigJson(null)
                    .build();

            when(repository.findByCrawlJobId("job-41")).thenReturn(Optional.of(record));

            Optional<CrawlConfig> config = service.loadConfig("job-41");
            assertTrue(config.isEmpty());
        }

        @Test
        void loadCheckpointDeserializesJson() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-42")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .lastCheckpointJson("{\"visitedUrls\":[\"https://a.com\"],\"pendingUrls\":[\"https://b.com::1\"]}")
                    .build();

            when(repository.findByCrawlJobId("job-42")).thenReturn(Optional.of(record));

            Optional<CrawlState> state = service.loadCheckpoint("job-42");

            assertTrue(state.isPresent());
            assertTrue(state.get().getVisitedUrls().contains("https://a.com"));
            assertEquals(1, state.get().getPendingUrls().size());
            assertEquals("https://b.com::1", state.get().getPendingUrls().get(0));
        }

        @Test
        void loadCheckpointReturnsEmptyWhenNotFound() {
            when(repository.findByCrawlJobId("missing")).thenReturn(Optional.empty());

            Optional<CrawlState> state = service.loadCheckpoint("missing");
            assertTrue(state.isEmpty());
        }
    }

    @Nested
    @DisplayName("listResumable and getRecord")
    class ListMethods {

        @Test
        void listResumableDelegatesToRepo() {
            CrawlJobRecord r1 = CrawlJobRecord.builder()
                    .crawlJobId("job-50")
                    .seed("https://a.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .lastCheckpointJson("{}")
                    .build();

            when(repository.findResumableJobs()).thenReturn(List.of(r1));

            List<CrawlJobRecord> result = service.listResumable();
            assertEquals(1, result.size());
            assertEquals("job-50", result.get(0).getCrawlJobId());
        }

        @Test
        void getRecordDelegatesToRepo() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("job-51")
                    .seed("https://a.com")
                    .status(CrawlJobStatus.COMPLETED)
                    .build();

            when(repository.findByCrawlJobId("job-51")).thenReturn(Optional.of(record));

            Optional<CrawlJobRecord> result = service.getRecord("job-51");
            assertTrue(result.isPresent());
            assertEquals("job-51", result.get().getCrawlJobId());
        }
    }
}
