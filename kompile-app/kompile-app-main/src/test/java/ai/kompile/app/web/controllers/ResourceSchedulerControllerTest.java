package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.*;
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

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResourceSchedulerController")
class ResourceSchedulerControllerTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock private ResourceAwareJobScheduler scheduler;
    @Mock private ResourceSchedulerConfigService configService;
    @Mock private JobSchedulerHistoryService historyService;

    private ResourceSchedulerController controller;

    @BeforeEach
    void setUp() {
        controller = new ResourceSchedulerController(scheduler, configService, historyService, null);
    }

    @Nested
    @DisplayName("GET /status")
    class Status {

        @Test
        void returnsSchedulerStatus() {
            Map<String, Object> status = Map.of("enabled", true, "running", true);
            when(scheduler.getStatus()).thenReturn(status);

            ResponseEntity<Map<String, Object>> response = controller.getStatus();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("enabled"));
        }
    }

    @Nested
    @DisplayName("GET /queue")
    class Queue {

        @Test
        void returnsQueueSnapshot() {
            when(scheduler.getQueueSnapshot()).thenReturn(List.of());

            ResponseEntity<List<ScheduledJob.ScheduledJobView>> response = controller.getQueue();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /running")
    class Running {

        @Test
        void returnsRunningSnapshot() {
            ScheduledJob.ScheduledJobView view = new ScheduledJob.ScheduledJobView(
                    "j1", "ingest", "Test", "RUNNING", "EMBEDDING",
                    50, true, Instant.now().toString(), Instant.now().toString(), null,
                    5000, 100, "Ingest", true, 2048, Map.of(), null, false, null, null);
            when(scheduler.getRunningSnapshot()).thenReturn(List.of(view));

            ResponseEntity<List<ScheduledJob.ScheduledJobView>> response = controller.getRunning();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(1, response.getBody().size());
            assertEquals("j1", response.getBody().get(0).jobId());
        }
    }

    @Nested
    @DisplayName("GET /jobs/{jobId}")
    class GetJob {

        @Test
        void returnsJobView() {
            ScheduledJob.ScheduledJobView view = new ScheduledJob.ScheduledJobView(
                    "j1", "ingest", "Test", "RUNNING", "EMBEDDING",
                    50, true, Instant.now().toString(), null, null,
                    0, 0, "Ingest", true, 2048, Map.of(), null, false, null, null);
            when(scheduler.getJobView("j1")).thenReturn(view);

            ResponseEntity<ScheduledJob.ScheduledJobView> response = controller.getJob("j1");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("j1", response.getBody().jobId());
        }

        @Test
        void returnsNotFoundForUnknownJob() {
            when(scheduler.getJobView("unknown")).thenReturn(null);

            ResponseEntity<ScheduledJob.ScheduledJobView> response = controller.getJob("unknown");

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("POST /jobs/{jobId}/cancel")
    class CancelJob {

        @Test
        void cancelsJob() {
            when(scheduler.cancel("j1")).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.cancelJob("j1");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("j1", response.getBody().get("jobId"));
            assertEquals(true, response.getBody().get("cancelled"));
        }

        @Test
        void cancelReturnsFalseForUnknown() {
            when(scheduler.cancel("unknown")).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.cancelJob("unknown");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(false, response.getBody().get("cancelled"));
        }
    }

    @Nested
    @DisplayName("POST /jobs/{jobId}/promote")
    class PromoteJob {

        @Test
        void promotesJob() {
            ResponseEntity<Map<String, Object>> response =
                    controller.promoteJob("j1", 100);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("j1", response.getBody().get("jobId"));
            assertEquals(100, response.getBody().get("newPriority"));
            verify(scheduler).promote("j1", 100);
        }
    }

    @Nested
    @DisplayName("Config endpoints")
    class Config {

        @Test
        void getConfigReturnsCurrentConfig() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            when(configService.getConfiguration()).thenReturn(config);

            ResponseEntity<ResourceSchedulerConfig> response = controller.getConfig();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isEnabled());
        }

        @Test
        void updateConfigSavesAndReturns() throws Exception {
            ResourceSchedulerConfig config = new ResourceSchedulerConfig();
            config.setEnabled(false);

            ResourceSchedulerConfig saved = new ResourceSchedulerConfig();
            saved.setEnabled(false);
            when(configService.getConfiguration()).thenReturn(saved);

            ResponseEntity<ResourceSchedulerConfig> response = controller.updateConfig(config);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(configService).saveConfiguration(config);
        }

        @Test
        void updateConfigReturns500OnError() throws Exception {
            doThrow(new IOException("Disk full")).when(configService).saveConfiguration(any());

            ResponseEntity<ResourceSchedulerConfig> response =
                    controller.updateConfig(new ResourceSchedulerConfig());

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }

        @Test
        void resetConfigRestoresDefaults() throws Exception {
            ResourceSchedulerConfig defaults = ResourceSchedulerConfig.defaults();
            when(configService.getConfiguration()).thenReturn(defaults);

            ResponseEntity<ResourceSchedulerConfig> response = controller.resetConfig();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(configService).resetToDefaults();
        }
    }

    @Nested
    @DisplayName("GET /profiles")
    class Profiles {

        @Test
        void returnsAllProfiles() {
            ResponseEntity<Map<String, JobResourceProfile>> response = controller.getProfiles();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertFalse(response.getBody().isEmpty());
            assertNotNull(response.getBody().get("ingest"));
        }
    }

    @Nested
    @DisplayName("History endpoints")
    class History {

        @Test
        void getHistoryReturnsRecentByDefault() {
            when(historyService.getRecentHistory(50)).thenReturn(List.of());

            ResponseEntity<List<JobSchedulerHistoryService.JobHistoryEntry>> response =
                    controller.getHistory(50, null, null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(historyService).getRecentHistory(50);
        }

        @Test
        void getHistoryFiltersByType() {
            when(historyService.getHistoryByType("ingest", 20)).thenReturn(List.of());

            controller.getHistory(20, "ingest", null);

            verify(historyService).getHistoryByType("ingest", 20);
            verify(historyService, never()).getRecentHistory(anyInt());
        }

        @Test
        void getHistoryFiltersByState() {
            when(historyService.getHistoryByState("FAILED", 10)).thenReturn(List.of());

            controller.getHistory(10, null, "FAILED");

            verify(historyService).getHistoryByState("FAILED", 10);
        }

        @Test
        void getHistoryStatsReturnsStats() {
            Map<String, Object> stats = Map.of("totalEntries", 42);
            when(historyService.getStats()).thenReturn(stats);

            ResponseEntity<Map<String, Object>> response = controller.getHistoryStats();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(42, response.getBody().get("totalEntries"));
        }
    }

    @Nested
    @DisplayName("Callback endpoint")
    class Callback {

        @Test
        void callbackAcknowledgesSuccess() {
            Map<String, Object> payload = Map.of(
                    "jobId", "j1", "success", true, "message", "Done");

            ResponseEntity<Map<String, Object>> response = controller.externalCallback(payload);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("acknowledged"));
            assertEquals("j1", response.getBody().get("jobId"));
            verify(scheduler).handleExternalCallback("j1", true, "Done");
        }

        @Test
        void callbackRejectsMissingJobId() {
            Map<String, Object> payload = Map.of("success", true);

            ResponseEntity<Map<String, Object>> response = controller.externalCallback(payload);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertNotNull(response.getBody().get("error"));
        }

        @Test
        void callbackRejectsBlankJobId() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", "  ");
            payload.put("success", true);

            ResponseEntity<Map<String, Object>> response = controller.externalCallback(payload);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("External scheduler endpoints")
    class ExternalScheduler {

        @Test
        void externalStatusReturnsInfo() {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("kubernetes");
            when(configService.getConfiguration()).thenReturn(config);
            when(scheduler.getExternalDelegateStatus()).thenReturn(List.of(
                    Map.of("mode", "kubernetes", "available", true, "active", true)));

            ResponseEntity<Map<String, Object>> response = controller.getExternalStatus();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("kubernetes", response.getBody().get("configuredMode"));
            assertTrue((Boolean) response.getBody().get("enabled"));
        }

        @Test
        void externalModesReturnsDelegateList() {
            when(scheduler.getExternalDelegateStatus()).thenReturn(List.of(
                    Map.of("mode", "kubernetes", "available", true),
                    Map.of("mode", "webhook", "available", false)));

            ResponseEntity<List<Map<String, Object>>> response = controller.getExternalModes();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(2, response.getBody().size());
        }

        @Test
        void cancelExternalJobDelegates() {
            when(scheduler.cancelExternal("j1"))
                    .thenReturn(CompletableFuture.completedFuture(true));

            ResponseEntity<Map<String, Object>> response = controller.cancelExternalJob("j1");

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(true, response.getBody().get("cancelled"));
        }
    }

    @Nested
    @DisplayName("GET /dashboard")
    class Dashboard {

        @Test
        void dashboardAggregatesAllData() {
            when(scheduler.getStatus()).thenReturn(Map.of("running", true));
            when(scheduler.getQueueSnapshot()).thenReturn(List.of());
            when(scheduler.getRunningSnapshot()).thenReturn(List.of());
            when(historyService.getRecentHistory(20)).thenReturn(List.of());
            when(historyService.getStats()).thenReturn(Map.of("totalEntries", 0));
            when(configService.getConfiguration()).thenReturn(ResourceSchedulerConfig.defaults());
            when(scheduler.getExternalDelegateStatus()).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> response = controller.getDashboard();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> dashboard = response.getBody();
            assertNotNull(dashboard.get("status"));
            assertNotNull(dashboard.get("queue"));
            assertNotNull(dashboard.get("running"));
            assertNotNull(dashboard.get("recentHistory"));
            assertNotNull(dashboard.get("stats"));
            assertNotNull(dashboard.get("profiles"));
            assertNotNull(dashboard.get("config"));
            assertNotNull(dashboard.get("externalScheduler"));
        }
    }
}
