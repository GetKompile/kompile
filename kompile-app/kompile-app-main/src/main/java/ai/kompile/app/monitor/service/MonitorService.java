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

package ai.kompile.app.monitor.service;

import ai.kompile.app.monitor.domain.MonitorRegistration;
import ai.kompile.app.monitor.domain.MonitorRegistration.MonitorStatus;
import ai.kompile.app.monitor.domain.MonitorRegistration.MonitorType;
import ai.kompile.app.monitor.dto.MonitorEvent;
import ai.kompile.app.monitor.repository.MonitorRegistrationRepository;
import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.service.ChatHistoryService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central service for chat monitors.
 *
 * Supports three monitor types:
 * <ul>
 *   <li><b>TASK_COMPLETION</b> — watches a subprocess task id; fires on completion.</li>
 *   <li><b>SCHEDULED_ONCE</b> — fires once at a specific wall-clock time.</li>
 *   <li><b>SCHEDULED_CRON</b> — fires on a recurring cron schedule.</li>
 * </ul>
 *
 * When a monitor fires, a {@link MonitorEvent} is broadcast on
 * {@code /topic/monitor/{sessionId}} and a SYSTEM chat message is persisted
 * to the bound session so the wake-up is visible on reconnect.
 */
@Service
@Slf4j
public class MonitorService {

    static final String MONITOR_GROUP = "kompile-monitors";
    static final String JOB_DATA_MONITOR_ID = "monitorId";

    private final MonitorRegistrationRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatHistoryService chatHistoryService;
    private final Scheduler scheduler;

    public MonitorService(
            MonitorRegistrationRepository repository,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired(required = false) ChatHistoryService chatHistoryService,
            @Autowired(required = false) Scheduler scheduler) {
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
        this.chatHistoryService = chatHistoryService;
        this.scheduler = scheduler;
    }

    /**
     * Re-register any ACTIVE scheduled monitors with Quartz on startup.
     * In-memory Quartz state is lost across restarts, but the DB row is not —
     * this is what makes the feature durable.
     */
    @PostConstruct
    public void rehydrateSchedules() {
        if (scheduler == null) {
            log.warn("Quartz scheduler not available; scheduled monitors disabled");
            return;
        }
        List<MonitorRegistration> active = repository.findByStatusOrderByCreatedAtDesc(MonitorStatus.ACTIVE);
        int rehydrated = 0;
        for (MonitorRegistration m : active) {
            try {
                switch (m.getType()) {
                    case SCHEDULED_ONCE -> registerOnceJob(m);
                    case SCHEDULED_CRON -> registerCronJob(m);
                    case TASK_COMPLETION -> { /* no Quartz state needed */ }
                }
                rehydrated++;
            } catch (Exception e) {
                log.error("Failed to rehydrate monitor {}: {}", m.getMonitorId(), e.getMessage());
            }
        }
        if (rehydrated > 0) {
            log.info("Rehydrated {} scheduled monitors", rehydrated);
        }
    }

    // ───────────────────────── REGISTRATION ─────────────────────────

    @Transactional
    public MonitorRegistration watchTask(String sessionId, String taskId, String description, String payload) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        MonitorRegistration m = MonitorRegistration.builder()
                .monitorId(newMonitorId())
                .type(MonitorType.TASK_COMPLETION)
                .status(MonitorStatus.ACTIVE)
                .sessionId(sessionId)
                .taskId(taskId)
                .description(description)
                .payload(payload)
                .build();
        MonitorRegistration saved = repository.save(m);
        log.info("Registered TASK_COMPLETION monitor {} for task {} -> session {}",
                saved.getMonitorId(), taskId, sessionId);
        return saved;
    }

    @Transactional
    public MonitorRegistration scheduleOnce(String sessionId, long fireAtEpochMs, String description, String payload) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (fireAtEpochMs <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("fireAtEpochMs must be in the future");
        }
        MonitorRegistration m = MonitorRegistration.builder()
                .monitorId(newMonitorId())
                .type(MonitorType.SCHEDULED_ONCE)
                .status(MonitorStatus.ACTIVE)
                .sessionId(sessionId)
                .fireAtEpochMs(fireAtEpochMs)
                .description(description)
                .payload(payload)
                .build();
        MonitorRegistration saved = repository.save(m);
        try {
            registerOnceJob(saved);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to schedule one-shot monitor: " + e.getMessage(), e);
        }
        log.info("Registered SCHEDULED_ONCE monitor {} for session {} firing at {}",
                saved.getMonitorId(), sessionId, Instant.ofEpochMilli(fireAtEpochMs));
        return saved;
    }

    @Transactional
    public MonitorRegistration scheduleCron(String sessionId, String cronExpression, String description, String payload) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("cronExpression is required");
        }
        MonitorRegistration m = MonitorRegistration.builder()
                .monitorId(newMonitorId())
                .type(MonitorType.SCHEDULED_CRON)
                .status(MonitorStatus.ACTIVE)
                .sessionId(sessionId)
                .cronExpression(cronExpression)
                .description(description)
                .payload(payload)
                .build();
        MonitorRegistration saved = repository.save(m);
        try {
            registerCronJob(saved);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to schedule cron monitor: " + e.getMessage(), e);
        }
        log.info("Registered SCHEDULED_CRON monitor {} for session {} cron {}",
                saved.getMonitorId(), sessionId, cronExpression);
        return saved;
    }

    // ───────────────────────── QUERY / CANCEL ─────────────────────────

    @Transactional(readOnly = true)
    public List<MonitorRegistration> listActive() {
        return repository.findByStatusOrderByCreatedAtDesc(MonitorStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<MonitorRegistration> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<MonitorRegistration> listBySession(String sessionId) {
        return repository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    @Transactional(readOnly = true)
    public Optional<MonitorRegistration> get(String monitorId) {
        return repository.findByMonitorId(monitorId);
    }

    @Transactional
    public boolean cancel(String monitorId) {
        Optional<MonitorRegistration> opt = repository.findByMonitorId(monitorId);
        if (opt.isEmpty()) return false;
        MonitorRegistration m = opt.get();
        if (m.getStatus() != MonitorStatus.ACTIVE) return false;

        m.setStatus(MonitorStatus.CANCELLED);
        m.setCancelledAt(LocalDateTime.now());
        repository.save(m);

        // Best-effort Quartz cleanup
        if (scheduler != null && m.getType() != MonitorType.TASK_COMPLETION) {
            try {
                scheduler.deleteJob(jobKey(m));
            } catch (SchedulerException e) {
                log.warn("Failed to delete Quartz job for monitor {}: {}", monitorId, e.getMessage());
            }
        }
        log.info("Cancelled monitor {}", monitorId);
        return true;
    }

    // ───────────────────────── FIRING ─────────────────────────

    /**
     * Invoked by {@code SubprocessIngestLauncher} (and any other task owner)
     * when a tracked task completes. Fires every ACTIVE TASK_COMPLETION monitor
     * bound to {@code taskId}.
     */
    @Transactional
    public void onTaskCompleted(String taskId, boolean success, String summary) {
        List<MonitorRegistration> monitors = repository.findByTaskIdAndStatus(taskId, MonitorStatus.ACTIVE);
        if (monitors.isEmpty()) return;
        log.info("Firing {} task-completion monitor(s) for task {}", monitors.size(), taskId);
        for (MonitorRegistration m : monitors) {
            fire(m, success, summary);
        }
    }

    /** Called by the Quartz job on cron/one-shot fire. */
    @Transactional
    public void fireScheduled(String monitorId) {
        Optional<MonitorRegistration> opt = repository.findByMonitorId(monitorId);
        if (opt.isEmpty()) {
            log.warn("Scheduled fire for missing monitor {}", monitorId);
            return;
        }
        MonitorRegistration m = opt.get();
        if (m.getStatus() != MonitorStatus.ACTIVE) {
            log.debug("Skipping fire for non-active monitor {}", monitorId);
            return;
        }
        fire(m, true, buildScheduledSummary(m));
    }

    private void fire(MonitorRegistration m, boolean success, String summary) {
        String title = switch (m.getType()) {
            case TASK_COMPLETION -> success
                    ? "Task complete: " + safe(m.getDescription(), m.getTaskId())
                    : "Task failed: " + safe(m.getDescription(), m.getTaskId());
            case SCHEDULED_ONCE -> "Scheduled trigger: " + safe(m.getDescription(), m.getMonitorId());
            case SCHEDULED_CRON -> "Scheduled trigger: " + safe(m.getDescription(), m.getMonitorId());
        };
        String body = summary != null && !summary.isBlank() ? summary : title;
        String messageContent = m.getPayload() != null && !m.getPayload().isBlank()
                ? body + "\n\n" + m.getPayload()
                : body;

        MonitorEvent event = new MonitorEvent(
                m.getMonitorId(),
                m.getType().name(),
                m.getSessionId(),
                m.getTaskId(),
                title,
                body,
                m.getPayload(),
                success,
                Instant.now()
        );

        // Broadcast via WebSocket
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend("/topic/monitor/" + m.getSessionId(), event);
                messagingTemplate.convertAndSend("/topic/monitor/all", event);
            } catch (Exception e) {
                log.warn("Failed to broadcast monitor event {}: {}", m.getMonitorId(), e.getMessage());
            }
        }

        // Persist wake-up as a SYSTEM chat message so it's visible on reload.
        if (chatHistoryService != null) {
            try {
                chatHistoryService.addMessage(
                        m.getSessionId(),
                        ChatMessage.MessageRole.SYSTEM,
                        messageContent,
                        null);
            } catch (IllegalArgumentException missing) {
                log.warn("Monitor {} cannot post wake-up: session {} not found",
                        m.getMonitorId(), m.getSessionId());
            } catch (Exception e) {
                log.warn("Failed to persist wake-up message for monitor {}: {}",
                        m.getMonitorId(), e.getMessage());
            }
        }

        m.setFiredAt(LocalDateTime.now());
        m.setFireCount((m.getFireCount() == null ? 0 : m.getFireCount()) + 1);
        if (m.getType() != MonitorType.SCHEDULED_CRON) {
            m.setStatus(MonitorStatus.FIRED);
        }
        repository.save(m);
    }

    // ───────────────────────── QUARTZ HELPERS ─────────────────────────

    private void registerOnceJob(MonitorRegistration m) throws SchedulerException {
        if (scheduler == null) {
            throw new SchedulerException("Quartz scheduler not available");
        }
        JobDetail job = JobBuilder.newJob(MonitorFireJob.class)
                .withIdentity(jobKey(m))
                .usingJobData(JOB_DATA_MONITOR_ID, m.getMonitorId())
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-" + m.getMonitorId(), MONITOR_GROUP)
                .startAt(new Date(m.getFireAtEpochMs()))
                .build();
        if (scheduler.checkExists(job.getKey())) {
            scheduler.deleteJob(job.getKey());
        }
        scheduler.scheduleJob(job, trigger);
    }

    private void registerCronJob(MonitorRegistration m) throws SchedulerException {
        if (scheduler == null) {
            throw new SchedulerException("Quartz scheduler not available");
        }
        JobDetail job = JobBuilder.newJob(MonitorFireJob.class)
                .withIdentity(jobKey(m))
                .usingJobData(JOB_DATA_MONITOR_ID, m.getMonitorId())
                .storeDurably()
                .build();
        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-" + m.getMonitorId(), MONITOR_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(m.getCronExpression()))
                .build();
        if (scheduler.checkExists(job.getKey())) {
            scheduler.deleteJob(job.getKey());
        }
        scheduler.scheduleJob(job, trigger);
    }

    private static JobKey jobKey(MonitorRegistration m) {
        return new JobKey("monitor-" + m.getMonitorId(), MONITOR_GROUP);
    }

    private static String safe(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String buildScheduledSummary(MonitorRegistration m) {
        if (m.getType() == MonitorType.SCHEDULED_CRON) {
            return "Scheduled monitor fired (cron: " + m.getCronExpression() + ")";
        }
        return "Scheduled monitor fired";
    }

    private static String newMonitorId() {
        return "mon-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
