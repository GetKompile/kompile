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
package ai.kompile.kclaw.service.impl;

import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.model.KClawRequest;
import ai.kompile.kclaw.model.KClawResponse;
import ai.kompile.gateway.core.service.HeartbeatScheduler;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Service("kclawHeartbeatScheduler")
@ConditionalOnClass(Scheduler.class)
public class QuartzHeartbeatScheduler implements HeartbeatScheduler {

    private static final String HEARTBEAT_GROUP = "kclaw-heartbeats";
    private static final String AGENT_ID_KEY = "agentId";
    private static final String SESSION_KEY = "sessionKey";
    private static final String MESSAGE_KEY = "message";

    private final Scheduler scheduler;
    private final ApplicationContext applicationContext;

    public QuartzHeartbeatScheduler(Scheduler scheduler, ApplicationContext applicationContext) {
        this.scheduler = scheduler;
        this.applicationContext = applicationContext;
        // Register a JobFactory that injects Spring context into Quartz jobs
        try {
            scheduler.getContext().put("applicationContext", applicationContext);
        } catch (SchedulerException e) {
            log.warn("Failed to set application context on Quartz scheduler", e);
        }
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

            log.info("Heartbeat firing for agent '{}' in session '{}': {}", agentId, sessionKey, message);

            try {
                ApplicationContext appCtx = (ApplicationContext) context.getScheduler()
                        .getContext().get("applicationContext");
                if (appCtx == null) {
                    log.error("Heartbeat job has no ApplicationContext — cannot invoke agent");
                    return;
                }

                KClawAgentService agentService = appCtx.getBean(KClawAgentService.class);
                KClawRequest request = KClawRequest.builder()
                        .agentId(agentId)
                        .sessionKey(sessionKey)
                        .message(message)
                        .build();

                KClawResponse response = agentService.execute(request);

                if (response.isSuccess()) {
                    log.info("Heartbeat completed for agent '{}': {} chars response",
                            agentId, response.getResponse() != null ? response.getResponse().length() : 0);
                } else {
                    log.warn("Heartbeat agent '{}' returned error: {}", agentId, response.getError());
                }
            } catch (SchedulerException e) {
                log.error("Failed to get ApplicationContext from scheduler", e);
            } catch (Exception e) {
                log.error("Heartbeat execution failed for agent '{}'", agentId, e);
            }
        }
    }
}
