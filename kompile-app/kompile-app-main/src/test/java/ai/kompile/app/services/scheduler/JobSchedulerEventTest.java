package ai.kompile.app.services.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JobSchedulerEvent")
class JobSchedulerEventTest {

    private final Object source = new Object();

    @Test
    void jobQueuedEvent() {
        Map<String, Object> data = Map.of("priority", 75, "requiresGpu", true, "peakGpuMb", 2048L);
        JobSchedulerEvent event = JobSchedulerEvent.jobQueued(source, "j1", "ingest", 5, 2, data);

        assertEquals(JobSchedulerEvent.EventType.JOB_QUEUED, event.getEventType());
        assertEquals("j1", event.getJobId());
        assertEquals("ingest", event.getJobType());
        assertEquals("QUEUED", event.getCurrentPhase());
        assertEquals(5, event.getQueueDepth());
        assertEquals(2, event.getRunningCount());
        assertEquals(75, event.getData().get("priority"));
        assertTrue((Boolean) event.getData().get("requiresGpu"));
    }

    @Test
    void jobDispatchedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobDispatched(source, "j1", "ingest", "Test desc", 4, 3);

        assertEquals(JobSchedulerEvent.EventType.JOB_DISPATCHED, event.getEventType());
        assertEquals("DISPATCHED", event.getCurrentPhase());
        assertEquals("Test desc", event.getData().get("description"));
    }

    @Test
    void jobPhaseTransitionEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobPhaseTransition(
                source, "j1", "unifiedCrawl", "LOADING", "VECTOR_INDEXING", true, 2, 1);

        assertEquals(JobSchedulerEvent.EventType.JOB_PHASE_TRANSITION, event.getEventType());
        assertEquals("VECTOR_INDEXING", event.getCurrentPhase());
        assertTrue((Boolean) event.getData().get("requiresGpu"));
        assertEquals("VECTOR_INDEXING", event.getData().get("phase"));
        assertEquals("LOADING", event.getData().get("previousPhase"));
    }

    @Test
    void jobCompletedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobCompleted(source, "j1", "ingest", "Ingest doc", 15000L, 3, 1);

        assertEquals(JobSchedulerEvent.EventType.JOB_COMPLETED, event.getEventType());
        assertEquals("COMPLETED", event.getCurrentPhase());
        assertEquals(15000L, event.getData().get("durationMs"));
        assertEquals("Ingest doc", event.getData().get("description"));
    }

    @Test
    void jobFailedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobFailed(source, "j1", "training", "Train model", "OOM", 0, 0);

        assertEquals(JobSchedulerEvent.EventType.JOB_FAILED, event.getEventType());
        assertEquals("FAILED", event.getCurrentPhase());
        assertEquals("OOM", event.getData().get("error"));
        assertEquals("Train model", event.getData().get("description"));
    }

    @Test
    void jobFailedEventWithNullError() {
        JobSchedulerEvent event = JobSchedulerEvent.jobFailed(source, "j1", "training", null, null, 0, 0);
        assertEquals("Unknown", event.getData().get("error"));
    }

    @Test
    void jobCancelledEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobCancelled(source, "j1", "ingest", "User cancelled", 2, 0);

        assertEquals(JobSchedulerEvent.EventType.JOB_CANCELLED, event.getEventType());
        assertEquals("CANCELLED", event.getCurrentPhase());
        assertEquals("User cancelled", event.getData().get("cancelReason"));
    }

    @Test
    void jobPromotedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.jobPromoted(source, "j1", "ingest", 50, 100, 3, 1);

        assertEquals(JobSchedulerEvent.EventType.JOB_PROMOTED, event.getEventType());
        assertEquals("QUEUED", event.getCurrentPhase());
        assertEquals(100, event.getData().get("newPriority"));
        assertEquals(50, event.getData().get("oldPriority"));
    }

    @Test
    void queueFullEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.queueFull(source, "j1", "ingest", "Test ingest", 50, 10);

        assertEquals(JobSchedulerEvent.EventType.QUEUE_FULL, event.getEventType());
        assertEquals("j1", event.getJobId());
        assertEquals("ingest", event.getJobType());
        assertEquals(50, event.getQueueDepth());
        assertEquals("j1", event.getData().get("rejectedJobId"));
        assertEquals("Test ingest", event.getData().get("description"));
    }

    @Test
    void schedulerStartedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.schedulerStarted(source);

        assertEquals(JobSchedulerEvent.EventType.SCHEDULER_STARTED, event.getEventType());
        assertNull(event.getJobId());
        assertNull(event.getJobType());
    }

    @Test
    void schedulerStoppedEvent() {
        JobSchedulerEvent event = JobSchedulerEvent.schedulerStopped(source);
        assertEquals(JobSchedulerEvent.EventType.SCHEDULER_STOPPED, event.getEventType());
    }

    @Test
    void eventDataDefaultsToEmptyMap() {
        JobSchedulerEvent event = JobSchedulerEvent.jobDispatched(source, "j1", "t", null, 0, 0);
        assertNotNull(event.getData());
    }

    @Test
    void eventSourceIsPreserved() {
        JobSchedulerEvent event = JobSchedulerEvent.schedulerStarted(source);
        assertSame(source, event.getSource());
    }
}
