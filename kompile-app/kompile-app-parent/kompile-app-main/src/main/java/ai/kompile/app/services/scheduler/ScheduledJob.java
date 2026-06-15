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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a job waiting in or running through the {@link ResourceAwareJobScheduler}.
 *
 * <p>The {@code jobId} correlates with {@link ai.kompile.app.services.ModelLifecycleManager}
 * job holds, so GPU reservations and scheduler records are linked.</p>
 */
public class ScheduledJob implements Comparable<ScheduledJob> {

    public enum JobState {
        QUEUED,
        ACQUIRING,
        RUNNING,
        PHASE_YIELDING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Functional interface for the actual work to execute.
     */
    @FunctionalInterface
    public interface JobExecutor {
        void execute(JobExecutionContext context) throws Exception;
    }

    /**
     * Context passed to {@link JobExecutor#execute} for phase reporting.
     */
    public record JobExecutionContext(
            String jobId,
            JobResourceProfile resourceProfile,
            PhaseCallback phaseCallback
    ) {}

    /**
     * Callback interface for reporting pipeline phase transitions.
     */
    @FunctionalInterface
    public interface PhaseCallback {
        void onPhaseTransition(String jobId, String phaseName,
                               boolean requiresGpu, long gpuMemoryBytes);
    }

    /**
     * Result of a completed/failed job.
     */
    public record JobResult(boolean success, String errorMessage, long durationMs) {}

    private final String jobId;
    private final String jobType;
    private final String description;
    private final JobResourceProfile resourceProfile;
    private final JobExecutor executor;
    private final Map<String, Object> metadata;
    private final Instant queuedAt;
    private volatile int priority;
    private final AtomicReference<String> currentPhase = new AtomicReference<>("QUEUED");
    private final AtomicReference<JobState> state = new AtomicReference<>(JobState.QUEUED);
    private final CompletableFuture<JobResult> resultFuture = new CompletableFuture<>();
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile boolean gpuHeld;
    private volatile String externalRef;
    private volatile boolean externallyDelegated;
    private volatile String blockedReason;
    private volatile String cancelReason;
    private volatile String errorMessage;

    private ScheduledJob(Builder builder) {
        this.jobId = builder.jobId;
        this.jobType = builder.jobType;
        this.description = builder.description;
        this.resourceProfile = builder.resourceProfile;
        this.executor = builder.executor;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
        this.queuedAt = Instant.now();
        this.priority = builder.priority;
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- Getters ---

    public String getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public String getDescription() { return description; }
    public JobResourceProfile getResourceProfile() { return resourceProfile; }
    public JobExecutor getExecutor() { return executor; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getQueuedAt() { return queuedAt; }
    public int getPriority() { return priority; }
    public String getCurrentPhase() { return currentPhase.get(); }
    public JobState getState() { return state.get(); }
    public CompletableFuture<JobResult> getResultFuture() { return resultFuture; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public boolean isGpuHeld() { return gpuHeld; }
    public String getExternalRef() { return externalRef; }
    public boolean isExternallyDelegated() { return externallyDelegated; }
    public String getBlockedReason() { return blockedReason; }
    public String getCancelReason() { return cancelReason; }
    public String getErrorMessage() { return errorMessage; }

    // --- State transitions ---

    public void setState(JobState newState) { state.set(newState); }
    public void setCurrentPhase(String phase) { currentPhase.set(phase); }
    public void setStartedAt(Instant at) { startedAt = at; }
    public void setCompletedAt(Instant at) { completedAt = at; }
    public void setGpuHeld(boolean held) { gpuHeld = held; }
    public void setPriority(int p) { priority = p; }
    public void setExternalRef(String ref) { externalRef = ref; }
    public void setExternallyDelegated(boolean delegated) { externallyDelegated = delegated; }
    public void setBlockedReason(String reason) { blockedReason = reason; }
    public void setCancelReason(String reason) { cancelReason = reason; }
    public void setErrorMessage(String msg) { errorMessage = msg; }

    public boolean isTerminal() {
        JobState s = state.get();
        return s == JobState.COMPLETED || s == JobState.FAILED || s == JobState.CANCELLED;
    }

    /**
     * Create a view for REST/WebSocket serialization.
     */
    public ScheduledJobView toView() {
        long durationMs = 0;
        if (startedAt != null) {
            Instant end = completedAt != null ? completedAt : Instant.now();
            durationMs = java.time.Duration.between(startedAt, end).toMillis();
        }
        long waitMs = startedAt != null
                ? java.time.Duration.between(queuedAt, startedAt).toMillis()
                : java.time.Duration.between(queuedAt, Instant.now()).toMillis();

        return new ScheduledJobView(
                jobId, jobType, description, state.get().name(),
                currentPhase.get(), priority, gpuHeld,
                queuedAt.toString(),
                startedAt != null ? startedAt.toString() : null,
                completedAt != null ? completedAt.toString() : null,
                durationMs, waitMs,
                resourceProfile.displayName(),
                resourceProfile.requiresGpu(),
                resourceProfile.peakGpuMemoryBytes() / (1024L * 1024L),
                metadata,
                externalRef,
                externallyDelegated,
                blockedReason,
                cancelReason
        );
    }

    @Override
    public int compareTo(ScheduledJob other) {
        // Higher priority first, then earlier queuedAt
        int cmp = Integer.compare(other.priority, this.priority);
        if (cmp != 0) return cmp;
        return this.queuedAt.compareTo(other.queuedAt);
    }

    /**
     * Serializable view of a scheduled job for REST/WebSocket.
     */
    public record ScheduledJobView(
            String jobId,
            String jobType,
            String description,
            String state,
            String currentPhase,
            int priority,
            boolean gpuHeld,
            String queuedAt,
            String startedAt,
            String completedAt,
            long durationMs,
            long waitMs,
            String profileName,
            boolean requiresGpu,
            long peakGpuMemoryMb,
            Map<String, Object> metadata,
            String externalRef,
            boolean externallyDelegated,
            String blockedReason,
            String cancelReason
    ) {}

    public static class Builder {
        private String jobId;
        private String jobType;
        private String description;
        private JobResourceProfile resourceProfile;
        private JobExecutor executor;
        private Map<String, Object> metadata;
        private int priority = 50;

        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder jobType(String jobType) { this.jobType = jobType; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder resourceProfile(JobResourceProfile profile) { this.resourceProfile = profile; return this; }
        public Builder executor(JobExecutor executor) { this.executor = executor; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }

        public ScheduledJob build() {
            if (jobId == null) throw new IllegalArgumentException("jobId is required");
            if (jobType == null) throw new IllegalArgumentException("jobType is required");
            if (resourceProfile == null) throw new IllegalArgumentException("resourceProfile is required");
            if (executor == null) throw new IllegalArgumentException("executor is required");
            return new ScheduledJob(this);
        }
    }
}
