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

package ai.kompile.staging.web;

import ai.kompile.staging.training.PeftService;
import ai.kompile.staging.training.TrainingService;
import ai.kompile.staging.web.dto.TrainingConfigRequest;
import ai.kompile.staging.web.dto.TrainingJobStatus;
import ai.kompile.staging.web.dto.TrainingLogEntry;
import ai.kompile.staging.web.dto.TrainingMetricsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for training jobs.
 * Provides endpoints for starting, monitoring, and managing training jobs,
 * as well as querying available PEFT types, updaters, and learning rate schedules.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/training")
@CrossOrigin(origins = "*")
public class TrainingController {

    private static final Logger log = LoggerFactory.getLogger(TrainingController.class);

    private final TrainingService trainingService;
    private final PeftService peftService;

    public TrainingController(TrainingService trainingService, PeftService peftService) {
        this.trainingService = trainingService;
        this.peftService = peftService;
    }

    // ==================== Job Management ====================

    /**
     * Start a new training job.
     */
    @PostMapping("/start")
    public ResponseEntity<TrainingJobStatus> startTraining(@RequestBody TrainingConfigRequest request) {
        try {
            log.info("Starting training job for model: {} with dataset: {}", request.getModelId(), request.getDatasetId());
            TrainingJobStatus status = trainingService.startTraining(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start training job", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List all training jobs.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<TrainingJobStatus>> getAllJobs() {
        try {
            return ResponseEntity.ok(trainingService.getAllJobs());
        } catch (Exception e) {
            log.error("Failed to list training jobs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get status of a specific training job.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<TrainingJobStatus> getJob(@PathVariable String jobId) {
        try {
            TrainingJobStatus status = trainingService.getJob(jobId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get training job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel a training job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        try {
            log.info("Cancelling training job: {}", jobId);
            boolean cancelled = trainingService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of("jobId", jobId, "cancelled", cancelled));
        } catch (Exception e) {
            log.error("Failed to cancel training job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== SSE Log Streaming ====================

    /**
     * Subscribe to real-time log stream for a training job (SSE).
     */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobLogs(@PathVariable String jobId) {
        log.info("SSE subscription for training job logs: {}", jobId);
        return trainingService.subscribeToJobLogs(jobId);
    }

    // ==================== Logs & Metrics ====================

    /**
     * Get logs for a specific training job.
     */
    @GetMapping("/jobs/{jobId}/logs")
    public ResponseEntity<List<TrainingLogEntry>> getJobLogs(@PathVariable String jobId) {
        try {
            return ResponseEntity.ok(trainingService.getJobLogs(jobId));
        } catch (Exception e) {
            log.error("Failed to get logs for training job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get metrics history for a specific training job.
     */
    @GetMapping("/jobs/{jobId}/metrics")
    public ResponseEntity<List<TrainingMetricsSnapshot>> getMetricsHistory(@PathVariable String jobId) {
        try {
            return ResponseEntity.ok(trainingService.getMetricsHistory(jobId));
        } catch (Exception e) {
            log.error("Failed to get metrics for training job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== PEFT / Updater / LR Config ====================

    /**
     * Get available PEFT types.
     */
    @GetMapping("/peft-types")
    public ResponseEntity<List<Map<String, String>>> getPeftTypes() {
        try {
            return ResponseEntity.ok(peftService.getAvailablePeftTypes());
        } catch (Exception e) {
            log.error("Failed to get available PEFT types", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get available updater types.
     */
    @GetMapping("/updater-types")
    public ResponseEntity<List<Map<String, String>>> getUpdaterTypes() {
        try {
            return ResponseEntity.ok(peftService.getAvailableUpdaterTypes());
        } catch (Exception e) {
            log.error("Failed to get available updater types", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get available learning rate schedules.
     */
    @GetMapping("/lr-schedules")
    public ResponseEntity<List<Map<String, String>>> getLrSchedules() {
        try {
            return ResponseEntity.ok(peftService.getAvailableLrSchedules());
        } catch (Exception e) {
            log.error("Failed to get available LR schedules", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
