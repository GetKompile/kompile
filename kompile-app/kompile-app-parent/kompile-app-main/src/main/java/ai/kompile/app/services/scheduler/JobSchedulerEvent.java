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

package ai.kompile.app.services.scheduler;

import org.springframework.context.ApplicationEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring event published by the {@link ResourceAwareJobScheduler}
 * for job lifecycle state changes.
 */
public class JobSchedulerEvent extends ApplicationEvent {

    public enum EventType {
        JOB_QUEUED,
        JOB_DISPATCHED,
        JOB_PHASE_TRANSITION,
        JOB_COMPLETED,
        JOB_FAILED,
        JOB_CANCELLED,
        JOB_PROMOTED,
        JOB_BLOCKED,
        JOB_SKIPPED_AHEAD,
        JOB_REORDERED,
        QUEUE_FULL,
        SCHEDULER_STARTED,
        SCHEDULER_STOPPED
    }

    private final EventType eventType;
    private final String jobId;
    private final String jobType;
    private final String currentPhase;
    private final int queueDepth;
    private final int runningCount;
    private final Map<String, Object> data;

    private JobSchedulerEvent(Object source, EventType eventType, String jobId,
                               String jobType, String currentPhase,
                               int queueDepth, int runningCount,
                               Map<String, Object> data) {
        super(source);
        this.eventType = eventType;
        this.jobId = jobId;
        this.jobType = jobType;
        this.currentPhase = currentPhase;
        this.queueDepth = queueDepth;
        this.runningCount = runningCount;
        this.data = data != null ? data : Map.of();
    }

    // --- Getters ---

    public EventType getEventType() { return eventType; }
    public String getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getCurrentPhase() { return currentPhase; }
    public int getQueueDepth() { return queueDepth; }
    public int getRunningCount() { return runningCount; }
    public Map<String, Object> getData() { return data; }

    // --- Factory methods ---

    public static JobSchedulerEvent jobQueued(Object source, String jobId, String jobType,
                                              int queueDepth, int runningCount,
                                              Map<String, Object> data) {
        return new JobSchedulerEvent(source, EventType.JOB_QUEUED, jobId, jobType,
                "QUEUED", queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobDispatched(Object source, String jobId, String jobType,
                                                   String description,
                                                   int queueDepth, int runningCount) {
        return new JobSchedulerEvent(source, EventType.JOB_DISPATCHED, jobId, jobType,
                "DISPATCHED", queueDepth, runningCount,
                description != null ? Map.of("description", description) : Map.of());
    }

    public static JobSchedulerEvent jobPhaseTransition(Object source, String jobId, String jobType,
                                                        String previousPhase, String newPhase,
                                                        boolean requiresGpu,
                                                        int queueDepth, int runningCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requiresGpu", requiresGpu);
        data.put("phase", newPhase);
        if (previousPhase != null) data.put("previousPhase", previousPhase);
        return new JobSchedulerEvent(source, EventType.JOB_PHASE_TRANSITION, jobId, jobType,
                newPhase, queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobCompleted(Object source, String jobId, String jobType,
                                                  String description, long durationMs,
                                                  int queueDepth, int runningCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("durationMs", durationMs);
        if (description != null) data.put("description", description);
        return new JobSchedulerEvent(source, EventType.JOB_COMPLETED, jobId, jobType,
                "COMPLETED", queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobFailed(Object source, String jobId, String jobType,
                                               String description, String error,
                                               int queueDepth, int runningCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", error != null ? error : "Unknown");
        if (description != null) data.put("description", description);
        return new JobSchedulerEvent(source, EventType.JOB_FAILED, jobId, jobType,
                "FAILED", queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobCancelled(Object source, String jobId, String jobType,
                                                  String cancelReason,
                                                  int queueDepth, int runningCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (cancelReason != null) data.put("cancelReason", cancelReason);
        return new JobSchedulerEvent(source, EventType.JOB_CANCELLED, jobId, jobType,
                "CANCELLED", queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobPromoted(Object source, String jobId, String jobType,
                                                 int oldPriority, int newPriority,
                                                 int queueDepth, int runningCount) {
        return new JobSchedulerEvent(source, EventType.JOB_PROMOTED, jobId, jobType,
                "QUEUED", queueDepth, runningCount,
                Map.of("oldPriority", oldPriority, "newPriority", newPriority));
    }

    public static JobSchedulerEvent queueFull(Object source, String rejectedJobId, String jobType,
                                               String description,
                                               int queueDepth, int runningCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (rejectedJobId != null) data.put("rejectedJobId", rejectedJobId);
        if (description != null) data.put("description", description);
        return new JobSchedulerEvent(source, EventType.QUEUE_FULL, rejectedJobId, jobType,
                null, queueDepth, runningCount, data);
    }

    public static JobSchedulerEvent jobBlocked(Object source, String jobId, String jobType,
                                                String blockedReason,
                                                int queueDepth, int runningCount) {
        return new JobSchedulerEvent(source, EventType.JOB_BLOCKED, jobId, jobType,
                "BLOCKED", queueDepth, runningCount,
                Map.of("blockedReason", blockedReason));
    }

    public static JobSchedulerEvent jobSkippedAhead(Object source, String skippedJobId,
                                                      String skippedJobType,
                                                      String blockedJobId, String blockedJobType,
                                                      String blockedReason,
                                                      int queueDepth, int runningCount) {
        return new JobSchedulerEvent(source, EventType.JOB_SKIPPED_AHEAD, skippedJobId,
                skippedJobType, "DISPATCHED", queueDepth, runningCount,
                Map.of("blockedJobId", blockedJobId,
                        "blockedJobType", blockedJobType,
                        "blockedReason", blockedReason));
    }

    public static JobSchedulerEvent jobReordered(Object source, int queueDepth, int runningCount,
                                                   int blockedCount, int skippedCount) {
        return new JobSchedulerEvent(source, EventType.JOB_REORDERED, null, null,
                null, queueDepth, runningCount,
                Map.of("blockedCount", blockedCount, "skippedCount", skippedCount));
    }

    public static JobSchedulerEvent schedulerStarted(Object source) {
        return new JobSchedulerEvent(source, EventType.SCHEDULER_STARTED, null, null,
                null, 0, 0, Map.of());
    }

    public static JobSchedulerEvent schedulerStopped(Object source) {
        return new JobSchedulerEvent(source, EventType.SCHEDULER_STOPPED, null, null,
                null, 0, 0, Map.of());
    }
}
