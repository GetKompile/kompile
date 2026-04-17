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

package ai.kompile.app.services.scheduling;

import ai.kompile.app.config.SchedulingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledPipelineServiceTest {

    @Mock
    private Scheduler scheduler;

    private SchedulingProperties properties;
    private ScheduledPipelineService service;

    @BeforeEach
    void setUp() {
        properties = new SchedulingProperties();
        properties.setEnabled(true);
        service = new ScheduledPipelineService(scheduler, properties);
    }

    @Test
    void testScheduleStalenessCheck() throws SchedulerException {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(null);

        service.scheduleStalenessCheck("test-1", "0 0 */6 * * ?", 1L);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());

        assertEquals(StalenessCheckJob.class, jobCaptor.getValue().getJobClass());
        assertEquals(1L, jobCaptor.getValue().getJobDataMap().getLong("factSheetId"));
        assertTrue(triggerCaptor.getValue() instanceof CronTrigger);
    }

    @Test
    void testScheduleReIngestion() throws SchedulerException {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(null);

        service.scheduleReIngestion("test-2", "0 0 2 * * ?", 1L);

        verify(scheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    @Test
    void testScheduleEvalRun() throws SchedulerException {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class)))
                .thenReturn(null);

        service.scheduleEvalRun("test-3", "0 0 3 * * ?", "suite-abc");

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), any(Trigger.class));
        assertEquals("suite-abc", jobCaptor.getValue().getJobDataMap().getString("suiteId"));
    }

    @Test
    void testCancelSchedule() throws SchedulerException {
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false, true);
        when(scheduler.deleteJob(any(JobKey.class))).thenReturn(true);

        boolean result = service.cancelSchedule("test-1");

        assertTrue(result);
        verify(scheduler).deleteJob(any(JobKey.class));
    }

    @Test
    void testCancelNonexistentSchedule() throws SchedulerException {
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        boolean result = service.cancelSchedule("nonexistent");

        assertFalse(result);
    }

    @Test
    void testListSchedules() throws SchedulerException {
        when(scheduler.getJobKeys(any(GroupMatcher.class))).thenReturn(Collections.emptySet());

        List<ScheduledPipelineService.ScheduleInfo> schedules = service.listSchedules();

        assertNotNull(schedules);
        assertTrue(schedules.isEmpty());
    }
}
