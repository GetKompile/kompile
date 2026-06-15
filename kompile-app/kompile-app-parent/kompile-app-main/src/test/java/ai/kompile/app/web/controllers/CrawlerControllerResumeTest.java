package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.domain.CrawlJobRecord;
import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import ai.kompile.app.services.CrawlJobPersistenceService;
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.core.crawler.*;
import ai.kompile.crawler.CrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CrawlerController Resume Endpoints")
class CrawlerControllerResumeTest {

    @Mock
    private CrawlerService crawlerService;

    @Mock
    private DocumentIngestService documentIngestService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CrawlJobPersistenceService crawlJobPersistenceService;

    private CrawlerController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new CrawlerController(crawlerService, documentIngestService, messagingTemplate);
        // Inject the optional crawlJobPersistenceService via reflection
        Field field = CrawlerController.class.getDeclaredField("crawlJobPersistenceService");
        field.setAccessible(true);
        field.set(controller, crawlJobPersistenceService);
    }

    @Nested
    @DisplayName("GET /api/crawlers/jobs/resumable")
    class ListResumable {

        @Test
        void returnsResumableJobs() {
            CrawlJobRecord record = CrawlJobRecord.builder()
                    .crawlJobId("crawl-1")
                    .crawlerId("web")
                    .seed("https://example.com")
                    .status(CrawlJobStatus.INTERRUPTED)
                    .startedAt(Instant.now())
                    .lastCheckpointAt(Instant.now())
                    .lastCheckpointJson("{\"visitedUrls\":[]}")
                    .documentsDiscovered(50)
                    .documentsProcessed(40)
                    .documentsFailed(2)
                    .historyTaskId("task-hist")
                    .build();

            when(crawlJobPersistenceService.listResumable()).thenReturn(List.of(record));

            ResponseEntity<?> response = controller.listResumableJobs();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
            assertNotNull(body);
            assertEquals(1, body.size());
            assertEquals("crawl-1", body.get(0).get("crawlJobId"));
            assertEquals("web", body.get(0).get("crawlerId"));
            assertEquals("https://example.com", body.get(0).get("seed"));
            assertEquals("INTERRUPTED", body.get(0).get("status"));
            assertEquals(50, body.get(0).get("documentsDiscovered"));
            assertEquals(40, body.get(0).get("documentsProcessed"));
            assertEquals(true, body.get(0).get("hasCheckpoint"));
        }

        @Test
        void returnsEmptyWhenNoResumableJobs() {
            when(crawlJobPersistenceService.listResumable()).thenReturn(List.of());

            ResponseEntity<?> response = controller.listResumableJobs();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            @SuppressWarnings("unchecked")
            List<?> body = (List<?>) response.getBody();
            assertNotNull(body);
            assertTrue(body.isEmpty());
        }
    }

    @Nested
    @DisplayName("POST /api/crawlers/jobs/{jobId}/restart")
    class RestartFromCheckpoint {

        @Test
        void returnsBadRequestWhenNoConfig() {
            when(crawlJobPersistenceService.loadConfig("job-missing")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.restartFromCheckpoint("job-missing");

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotNull(body);
            assertTrue(body.get("error").toString().contains("No saved configuration"));
        }

        @Test
        void returnsBadRequestWhenNoCheckpoint() {
            CrawlConfig config = mock(CrawlConfig.class);
            when(crawlJobPersistenceService.loadConfig("job-no-cp")).thenReturn(Optional.of(config));
            when(crawlJobPersistenceService.loadCheckpoint("job-no-cp")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.restartFromCheckpoint("job-no-cp");

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotNull(body);
            assertTrue(body.get("error").toString().contains("No checkpoint"));
        }

        @Test
        void resumesSuccessfully() {
            CrawlConfig config = mock(CrawlConfig.class);
            when(config.getCrawlerId()).thenReturn("web");
            when(config.getSeed()).thenReturn("https://example.com");
            when(config.getPipelines()).thenReturn(null);
            when(config.getRouteRules()).thenReturn(null);

            CrawlState checkpoint = CrawlState.builder()
                    .visitedUrls(Set.of("https://example.com"))
                    .pendingUrls(List.of("https://example.com/a::1"))
                    .build();

            CrawlJob newJob = mock(CrawlJob.class);
            when(newJob.getJobId()).thenReturn("crawl-new");
            when(newJob.getStatus()).thenReturn(CrawlStatus.RUNNING);
            when(newJob.getProgress()).thenReturn(
                    new CrawlProgress(0, 0, 0, 0, 0, 3, "", 0, Instant.now()));
            when(newJob.getConfig()).thenReturn(config);

            when(crawlJobPersistenceService.loadConfig("old-job")).thenReturn(Optional.of(config));
            when(crawlJobPersistenceService.loadCheckpoint("old-job")).thenReturn(Optional.of(checkpoint));
            when(crawlerService.startCrawl(eq(config), any())).thenReturn(newJob);
            when(crawlerService.getAllJobs()).thenReturn(List.of(newJob));

            ResponseEntity<?> response = controller.restartFromCheckpoint("old-job");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotNull(body);
            assertEquals("crawl-new", body.get("jobId"));
            assertEquals("old-job", body.get("originalJobId"));
            assertEquals(true, body.get("resumedFromCheckpoint"));

            // Verify config was set with previous state
            verify(config).setPreviousState(checkpoint);
        }
    }

    @Nested
    @DisplayName("periodicCrawlCheckpoint")
    class PeriodicCheckpoint {

        @Test
        void checkpointsActiveJobs() {
            CrawlJob runningJob = mock(CrawlJob.class);
            when(runningJob.getJobId()).thenReturn("active-1");
            when(runningJob.getStatus()).thenReturn(CrawlStatus.RUNNING);

            CrawlState state = CrawlState.builder().build();
            when(runningJob.checkpoint()).thenReturn(state);
            CrawlProgress progress = new CrawlProgress(10, 5, 1, 4, 1, 3, "page", 50, Instant.now());
            when(runningJob.getProgress()).thenReturn(progress);

            when(crawlerService.getActiveJobs()).thenReturn(List.of(runningJob));

            controller.periodicCrawlCheckpoint();

            verify(crawlJobPersistenceService).saveCheckpoint("active-1", state, progress);
        }

        @Test
        void skipsFinishedJobs() {
            CrawlJob completedJob = mock(CrawlJob.class);
            when(completedJob.getStatus()).thenReturn(CrawlStatus.COMPLETED);

            when(crawlerService.getActiveJobs()).thenReturn(List.of(completedJob));

            controller.periodicCrawlCheckpoint();

            verify(crawlJobPersistenceService, never()).saveCheckpoint(any(), any(), any());
        }

        @Test
        void continuesOnCheckpointFailure() {
            CrawlJob job1 = mock(CrawlJob.class);
            when(job1.getJobId()).thenReturn("job-fail");
            when(job1.getStatus()).thenReturn(CrawlStatus.RUNNING);
            when(job1.checkpoint()).thenThrow(new RuntimeException("snapshot failed"));

            CrawlJob job2 = mock(CrawlJob.class);
            when(job2.getJobId()).thenReturn("job-ok");
            when(job2.getStatus()).thenReturn(CrawlStatus.RUNNING);
            CrawlState state2 = CrawlState.builder().build();
            when(job2.checkpoint()).thenReturn(state2);
            CrawlProgress progress2 = new CrawlProgress(5, 3, 0, 2, 1, 3, "", 60, Instant.now());
            when(job2.getProgress()).thenReturn(progress2);

            when(crawlerService.getActiveJobs()).thenReturn(List.of(job1, job2));

            controller.periodicCrawlCheckpoint();

            // job1 failed but job2 should still be checkpointed
            verify(crawlJobPersistenceService).saveCheckpoint("job-ok", state2, progress2);
        }
    }

    @Nested
    @DisplayName("listResumableJobs when persistence is null")
    class NullPersistence {

        @Test
        void returnsEmptyListWhenPersistenceNull() throws Exception {
            // Create controller without persistence service
            CrawlerController ctrl = new CrawlerController(crawlerService, documentIngestService, messagingTemplate);
            // crawlJobPersistenceService defaults to null

            ResponseEntity<?> response = ctrl.listResumableJobs();

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        void restartReturnsBadRequestWhenPersistenceNull() throws Exception {
            CrawlerController ctrl = new CrawlerController(crawlerService, documentIngestService, messagingTemplate);

            ResponseEntity<?> response = ctrl.restartFromCheckpoint("job-1");

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }
    }
}
