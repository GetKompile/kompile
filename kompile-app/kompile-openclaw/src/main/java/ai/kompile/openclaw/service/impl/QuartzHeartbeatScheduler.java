/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.openclaw.service.impl;

import ai.kompile.openclaw.service.HeartbeatScheduler;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service("openclawHeartbeatScheduler")
@ConditionalOnClass(Scheduler.class)
public class QuartzHeartbeatScheduler implements HeartbeatScheduler {

    private static final String HEARTBEAT_GROUP = "openclaw-heartbeats";
    private static final String AGENT_ID_KEY = "agentId";
    private static final String SESSION_KEY = "sessionKey";
    private static final String MESSAGE_KEY = "message";

    private final Scheduler scheduler;

    public QuartzHeartbeatScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void scheduleHeartbeat(String id, String cronExpression, String agentId, String message) {
        scheduleHeartbeat(id, cronExpression, agentId, "cron:" + id, message);
    }

    @Override
    public void scheduleHeartbeat(String id, String cronExpression, String agentId, String sessionKey, String message) {
        try {
            JobDetail job = JobBuilder.newJob(HeartbeatJob.class)
                    .withIdentity(id, HEARTBEAT_GROUP)
                    .usingJobData(AGENT_ID_KEY, agentId)
                    .usingJobData(SESSION_KEY, sessionKey)
                    .usingJobData(MESSAGE_KEY, message)
                    .storeDurably()
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(id + "-trigger", HEARTBEAT_GROUP)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                    .build();

            scheduler.scheduleJob(job, trigger);
            log.info("Scheduled heartbeat '{}' for agent '{}' with cron: {}", id, agentId, cronExpression);
        } catch (SchedulerException e) {
            log.error("Failed to schedule heartbeat: {}", id, e);
            throw new RuntimeException("Failed to schedule heartbeat", e);
        }
    }

    @Override
    public boolean cancelHeartbeat(String id) {
        try {
            boolean deleted = scheduler.deleteJob(new JobKey(id, HEARTBEAT_GROUP));
            if (deleted) {
                log.info("Cancelled heartbeat: {}", id);
            }
            return deleted;
        } catch (SchedulerException e) {
            log.error("Failed to cancel heartbeat: {}", id, e);
            return false;
        }
    }

    @Override
    public boolean isScheduled(String id) {
        try {
            return scheduler.checkExists(new JobKey(id, HEARTBEAT_GROUP));
        } catch (SchedulerException e) {
            log.error("Failed to check heartbeat: {}", id, e);
            return false;
        }
    }

    @Override
    public void pauseHeartbeat(String id) {
        try {
            scheduler.pauseJob(new JobKey(id, HEARTBEAT_GROUP));
            log.info("Paused heartbeat: {}", id);
        } catch (SchedulerException e) {
            log.error("Failed to pause heartbeat: {}", id, e);
        }
    }

    @Override
    public void resumeHeartbeat(String id) {
        try {
            scheduler.resumeJob(new JobKey(id, HEARTBEAT_GROUP));
            log.info("Resumed heartbeat: {}", id);
        } catch (SchedulerException e) {
            log.error("Failed to resume heartbeat: {}", id, e);
        }
    }

    @Override
    public List<HeartbeatInfo> listHeartbeats() {
        List<HeartbeatInfo> heartbeats = new ArrayList<>();
        try {
            for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.groupEquals(HEARTBEAT_GROUP))) {
                JobDetail job = scheduler.getJobDetail(jobKey);
                Trigger trigger = scheduler.getTriggersOfJob(jobKey).stream().findFirst().orElse(null);
                
                if (trigger != null) {
                    JobDataMap data = job.getJobDataMap();
                    Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
                    
                    heartbeats.add(new HeartbeatInfo(
                            jobKey.getName(),
                            trigger instanceof CronTrigger ? ((CronTrigger) trigger).getCronExpression() : "unknown",
                            data.getString(AGENT_ID_KEY),
                            data.getString(SESSION_KEY),
                            data.getString(MESSAGE_KEY),
                            mapState(state),
                            toInstant(trigger.getNextFireTime()),
                            toInstant(trigger.getPreviousFireTime())
                    ));
                }
            }
        } catch (SchedulerException e) {
            log.error("Failed to list heartbeats", e);
        }
        return heartbeats;
    }

    private HeartbeatStatus mapState(Trigger.TriggerState state) {
        return switch (state) {
            case NORMAL -> HeartbeatStatus.SCHEDULED;
            case PAUSED -> HeartbeatStatus.PAUSED;
            case BLOCKED, ERROR -> HeartbeatStatus.ERROR;
            default -> HeartbeatStatus.SCHEDULED;
        };
    }

    private Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }

    public static class HeartbeatJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            JobDataMap data = context.getJobDetail().getJobDataMap();
            String agentId = data.getString(AGENT_ID_KEY);
            String sessionKey = data.getString(SESSION_KEY);
            String message = data.getString(MESSAGE_KEY);

            log.info("Heartbeat executing for agent '{}' in session '{}': {}", agentId, sessionKey, message);
        }
    }
}
