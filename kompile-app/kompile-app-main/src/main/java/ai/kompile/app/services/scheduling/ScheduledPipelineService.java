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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for scheduling RAG pipeline operations via Quartz.
 * Supports scheduling staleness checks, re-ingestion, and evaluation suite runs.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kompile.scheduling.enabled", havingValue = "true")
public class ScheduledPipelineService {

    private static final String GROUP = "kompile-pipeline";

    private final Scheduler scheduler;
    private final SchedulingProperties properties;

    /**
     * Schedule a periodic staleness check for a fact sheet.
     */
    public void scheduleStalenessCheck(String scheduleId, String cronExpression, Long factSheetId)
            throws SchedulerException {
        JobDetail job = JobBuilder.newJob(StalenessCheckJob.class)
                .withIdentity("staleness-" + scheduleId, GROUP)
                .usingJobData("factSheetId", factSheetId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-staleness-" + scheduleId, GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        scheduler.scheduleJob(job, trigger);
        log.info("Scheduled staleness check '{}' for fact sheet {} with cron: {}",
                scheduleId, factSheetId, cronExpression);
    }

    /**
     * Schedule periodic re-ingestion of stale documents.
     */
    public void scheduleReIngestion(String scheduleId, String cronExpression, Long factSheetId)
            throws SchedulerException {
        JobDetail job = JobBuilder.newJob(ReIngestionJob.class)
                .withIdentity("reingestion-" + scheduleId, GROUP)
                .usingJobData("factSheetId", factSheetId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-reingestion-" + scheduleId, GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        scheduler.scheduleJob(job, trigger);
        log.info("Scheduled re-ingestion '{}' for fact sheet {} with cron: {}",
                scheduleId, factSheetId, cronExpression);
    }

    /**
     * Schedule periodic evaluation suite execution.
     */
    public void scheduleEvalRun(String scheduleId, String cronExpression, String suiteId)
            throws SchedulerException {
        JobDetail job = JobBuilder.newJob(EvalSuiteJob.class)
                .withIdentity("eval-" + scheduleId, GROUP)
                .usingJobData("suiteId", suiteId)
                .storeDurably()
                .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-eval-" + scheduleId, GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        scheduler.scheduleJob(job, trigger);
        log.info("Scheduled eval suite '{}' with cron: {}", scheduleId, cronExpression);
    }

    /**
     * Cancel a scheduled job.
     */
    public boolean cancelSchedule(String scheduleId) throws SchedulerException {
        // Try all prefixes
        for (String prefix : List.of("staleness-", "reingestion-", "eval-")) {
            JobKey key = new JobKey(prefix + scheduleId, GROUP);
            if (scheduler.checkExists(key)) {
                scheduler.deleteJob(key);
                log.info("Cancelled schedule '{}'", scheduleId);
                return true;
            }
        }
        // Try exact match
        JobKey key = new JobKey(scheduleId, GROUP);
        if (scheduler.checkExists(key)) {
            scheduler.deleteJob(key);
            log.info("Cancelled schedule '{}'", scheduleId);
            return true;
        }
        return false;
    }

    /**
     * List all scheduled pipeline jobs.
     */
    public List<ScheduleInfo> listSchedules() throws SchedulerException {
        List<ScheduleInfo> schedules = new ArrayList<>();

        Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP));
        for (JobKey key : jobKeys) {
            JobDetail job = scheduler.getJobDetail(key);
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);

            String cron = "";
            String nextFire = "";
            if (!triggers.isEmpty() && triggers.get(0) instanceof CronTrigger ct) {
                cron = ct.getCronExpression();
                if (ct.getNextFireTime() != null) {
                    nextFire = ct.getNextFireTime().toInstant().toString();
                }
            }

            String type = "unknown";
            if (key.getName().startsWith("staleness-")) type = "staleness-check";
            else if (key.getName().startsWith("reingestion-")) type = "re-ingestion";
            else if (key.getName().startsWith("eval-")) type = "eval-suite";

            schedules.add(new ScheduleInfo(
                    key.getName(),
                    type,
                    cron,
                    nextFire,
                    job.getJobDataMap().getWrappedMap()
            ));
        }

        return schedules;
    }

    public record ScheduleInfo(
            String id,
            String type,
            String cronExpression,
            String nextFireTime,
            Map<String, Object> jobData
    ) {}
}
