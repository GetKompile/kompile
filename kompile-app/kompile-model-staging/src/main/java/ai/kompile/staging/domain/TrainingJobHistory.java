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

package ai.kompile.staging.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing the complete history of a training job.
 * Persisted via JPA for survival across application restarts.
 */
@Entity
@Table(name = "training_job_history", indexes = {
        @Index(name = "idx_training_job_task_id", columnList = "taskId", unique = true),
        @Index(name = "idx_training_job_status", columnList = "status"),
        @Index(name = "idx_training_job_start_time", columnList = "startTime"),
        @Index(name = "idx_training_job_model_id", columnList = "modelId"),
        @Index(name = "idx_training_job_type", columnList = "trainingType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingJobHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // ===================== JOB IDENTIFICATION =====================

    @Column(nullable = false, length = 64, unique = true)
    private String taskId;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private TrainingType trainingType;

    @Column(nullable = false, length = 256)
    private String modelId;

    @Column(length = 256)
    private String datasetId;

    // ===================== JOB STATUS =====================

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    // ===================== TIMING =====================

    @Column(nullable = false)
    private Instant startTime;

    @Column
    private Instant endTime;

    @Column
    private Long totalDurationMs;

    // ===================== TRAINING PROGRESS =====================

    @Column
    private Integer currentEpoch;

    @Column
    private Integer totalEpochs;

    @Column
    private Long currentStep;

    @Column
    private Long totalSteps;

    @Column
    private Double finalLoss;

    @Column
    private Double finalEvalLoss;

    @Column
    private Double learningRate;

    // ===================== TRAINING PARAMETERS =====================

    @Column
    private Integer batchSize;

    @Column
    private Integer gradientAccumulationSteps;

    @Column(length = 32)
    private String lrSchedule;

    @Column
    private Double warmupRatio;

    @Column
    private Double maxGradNorm;

    @Column
    private Boolean fp16;

    @Column
    private Boolean bf16;

    @Column(length = 32)
    private String peftType;

    @Column
    private Integer seed;

    // ===================== OUTPUT =====================

    @Column(length = 512)
    private String outputModelPath;

    // ===================== ENVIRONMENT =====================

    @Column(length = 64)
    private String javaVersion;

    @Column(length = 128)
    private String osInfo;

    @Column
    private Integer availableProcessors;

    @Column
    private Long maxHeapMemoryBytes;

    @Column(length = 64)
    private String nd4jBackend;

    // ===================== ERROR INFORMATION =====================

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 256)
    private String errorType;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    @Column(length = 64)
    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    // ===================== RESTART TRACKING =====================

    @Column
    private Integer restartAttempts;

    @Column
    private Integer maxRestartAttempts;

    @Column
    private Instant lastRestartTime;

    @Column
    private Boolean recoveredAfterRestart;

    @Column(columnDefinition = "TEXT")
    private String restartHistoryJson;

    // ===================== METADATA =====================

    @Column(columnDefinition = "TEXT")
    private String additionalDetails;

    @PrePersist
    protected void onCreate() {
        if (startTime == null) startTime = Instant.now();
        if (status == null) status = JobStatus.QUEUED;
    }

    // ===================== ENUMS =====================

    public enum JobStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, MEMORY_KILLED
    }

    public enum TrainingType {
        FINETUNE, LORA, DISTILLATION, ALIGNMENT
    }

    public enum FailureReason {
        NONE, OUT_OF_MEMORY, MEMORY_KILLED, USER_CANCELLED, MODEL_NOT_FOUND,
        DATASET_ERROR, TRAINING_ERROR, CHECKPOINT_ERROR, IO_ERROR, TIMEOUT, UNKNOWN
    }

    // ===================== FACTORY METHODS =====================

    public static TrainingJobHistory createQueued(String taskId, TrainingType trainingType,
                                                   String modelId, String datasetId) {
        return TrainingJobHistory.builder()
                .taskId(taskId)
                .trainingType(trainingType)
                .modelId(modelId)
                .datasetId(datasetId)
                .status(JobStatus.QUEUED)
                .startTime(Instant.now())
                .currentEpoch(0)
                .currentStep(0L)
                .javaVersion(System.getProperty("java.version"))
                .osInfo(System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .availableProcessors(Runtime.getRuntime().availableProcessors())
                .maxHeapMemoryBytes(Runtime.getRuntime().maxMemory())
                .build();
    }

    public void markRunning() {
        this.status = JobStatus.RUNNING;
    }

    public void markCompleted(double finalLoss, double finalEvalLoss, long totalSteps,
                               String outputPath) {
        this.status = JobStatus.COMPLETED;
        this.finalLoss = finalLoss;
        this.finalEvalLoss = finalEvalLoss;
        this.totalSteps = totalSteps;
        this.outputModelPath = outputPath;
        this.failureReason = FailureReason.NONE;
        finalizeJob();
    }

    public void markFailed(String errorMessage, Throwable exception, FailureReason reason) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.failureReason = reason;
        if (exception != null) {
            this.errorType = exception.getClass().getName();
            this.stackTrace = truncateStackTrace(exception);
        }
        finalizeJob();
    }

    public void markCancelled(String reason) {
        this.status = JobStatus.CANCELLED;
        this.errorMessage = reason;
        this.failureReason = FailureReason.USER_CANCELLED;
        finalizeJob();
    }

    public void markMemoryKilled(double memoryPercent) {
        this.status = JobStatus.MEMORY_KILLED;
        this.failureReason = FailureReason.MEMORY_KILLED;
        this.errorMessage = String.format("Job killed: memory usage %.1f%% exceeded threshold", memoryPercent);
        finalizeJob();
    }

    public void updateProgress(int epoch, long step, double loss, double lr) {
        this.currentEpoch = epoch;
        this.currentStep = step;
        this.finalLoss = loss;
        this.learningRate = lr;
    }

    private void finalizeJob() {
        this.endTime = Instant.now();
        if (this.startTime != null) {
            this.totalDurationMs = java.time.Duration.between(this.startTime, this.endTime).toMillis();
        }
    }

    private static String truncateStackTrace(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 4000) break;
        }
        return sb.toString();
    }
}
