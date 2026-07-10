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

import ai.kompile.app.subprocess.SubprocessPlacement;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
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
            PhaseCallback phaseCallback,
            SubprocessPlacement placement
    ) {
        /**
         * Backward-compatible constructor with no scheduler-assigned placement. A subprocess-spawning
         * executor should prefer the 4-arg form and deliver {@link #placement()} to its launcher via
         * {@code BackendConfigurable.applyPlacement} — this is the per-invocation delivery seam (the
         * placement rides the execution context, not a shared launcher field, so concurrent dispatch
         * of the same launcher type never races).
         */
        public JobExecutionContext(String jobId, JobResourceProfile resourceProfile, PhaseCallback phaseCallback) {
            this(jobId, resourceProfile, phaseCallback, null);
        }
    }

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

    // AtomicReference-backed state — custom accessors return/set the value (not the holder), so Lombok
    // is opted out for these two fields.
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final AtomicReference<String> currentPhase = new AtomicReference<>("QUEUED");
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final AtomicReference<JobState> state = new AtomicReference<>(JobState.QUEUED);

    /** Device-agnostic placement the scheduler assigned for this run (device + backend + memory bound). */
    private volatile SubprocessPlacement assignedPlacement;
    private final CompletableFuture<JobResult> resultFuture = new CompletableFuture<>();
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile boolean gpuHeld;
    private volatile String externalRef;
    private volatile boolean externallyDelegated;
    private volatile String blockedReason;
    private volatile String cancelReason;
    private volatile String errorMessage;

    @Builder
    private ScheduledJob(String jobId, String jobType, String description, JobResourceProfile resourceProfile,
                         JobExecutor executor, Map<String, Object> metadata, Integer priority) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        if (jobType == null) throw new IllegalArgumentException("jobType is required");
        if (resourceProfile == null) throw new IllegalArgumentException("resourceProfile is required");
        if (executor == null) throw new IllegalArgumentException("executor is required");
        this.jobId = jobId;
        this.jobType = jobType;
        this.description = description;
        this.resourceProfile = resourceProfile;
        this.executor = executor;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.queuedAt = Instant.now();
        this.priority = priority != null ? priority : 50;
    }

    // --- AtomicReference-backed state accessors (custom; Lombok opted out above) ---

    public String getCurrentPhase() { return currentPhase.get(); }
    public JobState getState() { return state.get(); }
    public void setCurrentPhase(String phase) { currentPhase.set(phase); }
    public void setState(JobState newState) { state.set(newState); }

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
}
