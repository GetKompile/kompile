package ai.kompile.app.services.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobSchedulerBroadcaster")
class JobSchedulerBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ResourceAwareJobScheduler scheduler;

    private JobSchedulerBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        when(scheduler.getStatus()).thenReturn(new LinkedHashMap<>());
        when(scheduler.getQueueSnapshot()).thenReturn(List.of());
        when(scheduler.getRunningSnapshot()).thenReturn(List.of());

        broadcaster = new JobSchedulerBroadcaster(messagingTemplate, scheduler);
    }

    @Test
    void sendsBothEventAndStatusForQueuedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobQueued(
                new Object(), "j1", "ingest", 5, 2,
                Map.of("priority", 75));

        broadcaster.onJobSchedulerEvent(event);

        // Event payload
        ArgumentCaptor<Map<String, Object>> eventCaptor = captureMapPayload();
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/events"), eventCaptor.capture());

        Map<String, Object> payload = eventCaptor.getValue();
        assertEquals("JOB_QUEUED", payload.get("eventType"));
        assertEquals("j1", payload.get("jobId"));
        assertEquals("ingest", payload.get("jobType"));
        assertEquals("QUEUED", payload.get("currentPhase"));
        assertEquals(5, payload.get("queueDepth"));
        assertEquals(2, payload.get("runningCount"));
        assertEquals(75, payload.get("priority"));

        // Status snapshot
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/status"), any(Map.class));
    }

    @Test
    void sendsBothForCompletedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobCompleted(
                new Object(), "j1", "ingest", null, 15000L, 3, 1);

        broadcaster.onJobSchedulerEvent(event);

        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/events"), any(Map.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/status"), any(Map.class));
    }

    @Test
    void sendsEventAndStatusForSchedulerStarted() {
        JobSchedulerEvent event = JobSchedulerEvent.schedulerStarted(new Object());

        broadcaster.onJobSchedulerEvent(event);

        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/events"), any(Map.class));
        // schedulerStarted IS a state-changing event
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/status"), any(Map.class));
    }

    @Test
    void noOpWhenMessagingTemplateIsNull() {
        broadcaster = new JobSchedulerBroadcaster(null, scheduler);
        JobSchedulerEvent event = JobSchedulerEvent.jobQueued(
                new Object(), "j1", "ingest", 1, 0, Map.of());

        assertDoesNotThrow(() -> broadcaster.onJobSchedulerEvent(event));
    }

    @Test
    void eventPayloadIncludesTimestamp() {
        JobSchedulerEvent event = JobSchedulerEvent.jobDispatched(
                new Object(), "j1", "crawl", null, 0, 1);

        broadcaster.onJobSchedulerEvent(event);

        ArgumentCaptor<Map<String, Object>> captor = captureMapPayload();
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/events"), captor.capture());

        assertNotNull(captor.getValue().get("timestamp"));
    }

    @Test
    void eventPayloadOmitsNullJobId() {
        JobSchedulerEvent event = JobSchedulerEvent.schedulerStarted(new Object());

        broadcaster.onJobSchedulerEvent(event);

        ArgumentCaptor<Map<String, Object>> captor = captureMapPayload();
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/events"), captor.capture());

        assertFalse(captor.getValue().containsKey("jobId"));
    }

    @Test
    void statusSnapshotIncludesQueueAndRunning() {
        JobSchedulerEvent event = JobSchedulerEvent.jobDispatched(
                new Object(), "j1", "ingest", null, 2, 3);

        broadcaster.onJobSchedulerEvent(event);

        ArgumentCaptor<Map<String, Object>> captor = captureMapPayload();
        verify(messagingTemplate).convertAndSend(eq("/topic/scheduler/status"), captor.capture());

        Map<String, Object> status = captor.getValue();
        assertNotNull(status.get("queue"));
        assertNotNull(status.get("running"));
        assertNotNull(status.get("timestamp"));
    }

    @Test
    void exceptionInBroadcastDoesNotPropagate() {
        doThrow(new RuntimeException("WebSocket fail"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Map.class));

        JobSchedulerEvent event = JobSchedulerEvent.jobQueued(
                new Object(), "j1", "ingest", 1, 0, Map.of());

        assertDoesNotThrow(() -> broadcaster.onJobSchedulerEvent(event));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureMapPayload() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
