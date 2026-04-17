package ai.kompile.e2e;

import ai.kompile.openclaw.service.HeartbeatScheduler;
import ai.kompile.openclaw.service.HeartbeatScheduler.HeartbeatInfo;
import ai.kompile.openclaw.service.HeartbeatScheduler.HeartbeatStatus;
import ai.kompile.openclaw.service.impl.QuartzHeartbeatScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Quartz-based heartbeat scheduler: scheduling, cancellation,
 * pause/resume, listing, and state mapping.
 */
@Tag("e2e")
@ExtendWith(MockitoExtension.class)
@DisplayName("Heartbeat Scheduler")
class HeartbeatSchedulerTest {

    @Mock
    private Scheduler quartzScheduler;

    private QuartzHeartbeatScheduler heartbeatScheduler;

    @BeforeEach
    void setUp() {
        heartbeatScheduler = new QuartzHeartbeatScheduler(quartzScheduler);
    }

    // ── Scheduling ──

    @Test
    @DisplayName("Schedule heartbeat creates Quartz job and trigger")
    void testScheduleHeartbeat() throws Exception {
        heartbeatScheduler.scheduleHeartbeat(
                "daily-check", "0 0 9 * * ?", "agent-1", "session-1", "Check status"
        );

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(quartzScheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());

        JobDetail job = jobCaptor.getValue();
        assertEquals("daily-check", job.getKey().getName());
        assertEquals("openclaw-heartbeats", job.getKey().getGroup());
        assertEquals("agent-1", job.getJobDataMap().getString("agentId"));
        assertEquals("session-1", job.getJobDataMap().getString("sessionKey"));
        assertEquals("Check status", job.getJobDataMap().getString("message"));

        Trigger trigger = triggerCaptor.getValue();
        assertEquals("daily-check-trigger", trigger.getKey().getName());
        assertEquals("openclaw-heartbeats", trigger.getKey().getGroup());
    }

    @Test
    @DisplayName("Schedule without session key auto-generates key")
    void testScheduleWithoutSessionKey() throws Exception {
        heartbeatScheduler.scheduleHeartbeat(
                "heartbeat-1", "0 */5 * * * ?", "agent-1", "Ping"
        );

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(quartzScheduler).scheduleJob(jobCaptor.capture(), any(Trigger.class));
        assertEquals("cron:heartbeat-1", jobCaptor.getValue().getJobDataMap().getString("sessionKey"));
    }

    @Test
    @DisplayName("Schedule with invalid cron throws RuntimeException")
    void testScheduleInvalidCron() {
        assertThrows(RuntimeException.class, () ->
                heartbeatScheduler.scheduleHeartbeat("bad", "INVALID", "agent-1", "Test")
        );
    }

    // ── Cancellation ──

    @Test
    @DisplayName("Cancel heartbeat deletes Quartz job")
    void testCancelHeartbeat() throws Exception {
        when(quartzScheduler.deleteJob(new JobKey("daily-check", "openclaw-heartbeats")))
                .thenReturn(true);

        boolean cancelled = heartbeatScheduler.cancelHeartbeat("daily-check");

        assertTrue(cancelled);
        verify(quartzScheduler).deleteJob(new JobKey("daily-check", "openclaw-heartbeats"));
    }

    @Test
    @DisplayName("Cancel non-existent heartbeat returns false")
    void testCancelNonExistent() throws Exception {
        when(quartzScheduler.deleteJob(any(JobKey.class))).thenReturn(false);

        assertFalse(heartbeatScheduler.cancelHeartbeat("nonexistent"));
    }

    @Test
    @DisplayName("Cancel with scheduler exception returns false")
    void testCancelWithException() throws Exception {
        when(quartzScheduler.deleteJob(any(JobKey.class)))
                .thenThrow(new SchedulerException("Error"));

        assertFalse(heartbeatScheduler.cancelHeartbeat("failing"));
    }

    // ── isScheduled ──

    @Test
    @DisplayName("isScheduled returns true for existing job")
    void testIsScheduled() throws Exception {
        when(quartzScheduler.checkExists(new JobKey("daily-check", "openclaw-heartbeats")))
                .thenReturn(true);

        assertTrue(heartbeatScheduler.isScheduled("daily-check"));
    }

    @Test
    @DisplayName("isScheduled returns false for non-existent job")
    void testIsNotScheduled() throws Exception {
        when(quartzScheduler.checkExists(any(JobKey.class))).thenReturn(false);

        assertFalse(heartbeatScheduler.isScheduled("nonexistent"));
    }

    @Test
    @DisplayName("isScheduled returns false on exception")
    void testIsScheduledWithException() throws Exception {
        when(quartzScheduler.checkExists(any(JobKey.class)))
                .thenThrow(new SchedulerException("Error"));

        assertFalse(heartbeatScheduler.isScheduled("failing"));
    }

    // ── Pause/Resume ──

    @Test
    @DisplayName("Pause heartbeat pauses Quartz job")
    void testPauseHeartbeat() throws Exception {
        heartbeatScheduler.pauseHeartbeat("daily-check");

        verify(quartzScheduler).pauseJob(new JobKey("daily-check", "openclaw-heartbeats"));
    }

    @Test
    @DisplayName("Resume heartbeat resumes Quartz job")
    void testResumeHeartbeat() throws Exception {
        heartbeatScheduler.resumeHeartbeat("daily-check");

        verify(quartzScheduler).resumeJob(new JobKey("daily-check", "openclaw-heartbeats"));
    }

    @Test
    @DisplayName("Pause with exception does not throw")
    void testPauseWithException() throws Exception {
        doThrow(SchedulerException.class).when(quartzScheduler).pauseJob(any(JobKey.class));

        assertDoesNotThrow(() -> heartbeatScheduler.pauseHeartbeat("failing"));
    }

    @Test
    @DisplayName("Resume with exception does not throw")
    void testResumeWithException() throws Exception {
        doThrow(SchedulerException.class).when(quartzScheduler).resumeJob(any(JobKey.class));

        assertDoesNotThrow(() -> heartbeatScheduler.resumeHeartbeat("failing"));
    }

    // ── Listing ──

    @Test
    @DisplayName("List heartbeats returns heartbeat info")
    @SuppressWarnings("unchecked")
    void testListHeartbeats() throws Exception {
        JobKey jobKey = new JobKey("daily-check", "openclaw-heartbeats");
        Set<JobKey> jobKeys = new HashSet<>();
        jobKeys.add(jobKey);

        when(quartzScheduler.getJobKeys(any(GroupMatcher.class))).thenReturn(jobKeys);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("agentId", "agent-1");
        dataMap.put("sessionKey", "session-1");
        dataMap.put("message", "Check status");

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
        when(quartzScheduler.getJobDetail(jobKey)).thenReturn(jobDetail);

        CronTrigger cronTrigger = mock(CronTrigger.class);
        TriggerKey triggerKey = new TriggerKey("daily-check-trigger", "openclaw-heartbeats");
        when(cronTrigger.getKey()).thenReturn(triggerKey);
        when(cronTrigger.getCronExpression()).thenReturn("0 0 9 * * ?");
        when(cronTrigger.getNextFireTime()).thenReturn(new Date());
        when(cronTrigger.getPreviousFireTime()).thenReturn(null);

        doReturn(List.of(cronTrigger)).when(quartzScheduler).getTriggersOfJob(jobKey);
        when(quartzScheduler.getTriggerState(triggerKey)).thenReturn(Trigger.TriggerState.NORMAL);

        List<HeartbeatInfo> heartbeats = heartbeatScheduler.listHeartbeats();

        assertEquals(1, heartbeats.size());
        HeartbeatInfo info = heartbeats.get(0);
        assertEquals("daily-check", info.id());
        assertEquals("agent-1", info.agentId());
        assertEquals("session-1", info.sessionKey());
        assertEquals("Check status", info.message());
        assertEquals("0 0 9 * * ?", info.cronExpression());
        assertEquals(HeartbeatStatus.SCHEDULED, info.status());
        assertNotNull(info.nextFireTime());
        assertNull(info.lastFireTime());
    }

    @Test
    @DisplayName("List heartbeats returns empty on exception")
    @SuppressWarnings("unchecked")
    void testListHeartbeatsException() throws Exception {
        when(quartzScheduler.getJobKeys(any(GroupMatcher.class)))
                .thenThrow(new SchedulerException("Error"));

        List<HeartbeatInfo> heartbeats = heartbeatScheduler.listHeartbeats();
        assertTrue(heartbeats.isEmpty());
    }

    @Test
    @DisplayName("PAUSED trigger state maps to PAUSED heartbeat status")
    @SuppressWarnings("unchecked")
    void testPausedStateMapping() throws Exception {
        JobKey jobKey = new JobKey("paused-job", "openclaw-heartbeats");
        Set<JobKey> jobKeys = new HashSet<>();
        jobKeys.add(jobKey);

        when(quartzScheduler.getJobKeys(any(GroupMatcher.class))).thenReturn(jobKeys);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("agentId", "agent-1");
        dataMap.put("sessionKey", "s1");
        dataMap.put("message", "msg");

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
        when(quartzScheduler.getJobDetail(jobKey)).thenReturn(jobDetail);

        CronTrigger cronTrigger = mock(CronTrigger.class);
        TriggerKey triggerKey = new TriggerKey("paused-job-trigger", "openclaw-heartbeats");
        when(cronTrigger.getKey()).thenReturn(triggerKey);
        when(cronTrigger.getCronExpression()).thenReturn("0 0 * * * ?");
        when(cronTrigger.getNextFireTime()).thenReturn(null);
        when(cronTrigger.getPreviousFireTime()).thenReturn(null);

        doReturn(List.of(cronTrigger)).when(quartzScheduler).getTriggersOfJob(jobKey);
        when(quartzScheduler.getTriggerState(triggerKey)).thenReturn(Trigger.TriggerState.PAUSED);

        List<HeartbeatInfo> heartbeats = heartbeatScheduler.listHeartbeats();

        assertEquals(1, heartbeats.size());
        assertEquals(HeartbeatStatus.PAUSED, heartbeats.get(0).status());
    }

    // ── HeartbeatJob ──

    @Test
    @DisplayName("HeartbeatJob executes without throwing")
    void testHeartbeatJobExecution() {
        QuartzHeartbeatScheduler.HeartbeatJob job = new QuartzHeartbeatScheduler.HeartbeatJob();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("agentId", "agent-1");
        dataMap.put("sessionKey", "session-1");
        dataMap.put("message", "Check in");

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);

        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);

        assertDoesNotThrow(() -> job.execute(context));
    }
}
