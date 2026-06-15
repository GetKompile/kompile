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

import ai.kompile.staging.training.DistillationService;
import ai.kompile.staging.web.dto.DistillationConfigRequest;
import ai.kompile.core.staging.TrainingJobStatus;
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
 * REST API controller for knowledge distillation.
 * Provides endpoints for starting distillation jobs, monitoring progress,
 * and querying available distillation types.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/distillation")
@CrossOrigin(origins = "*")
public class DistillationController {

    private static final Logger log = LoggerFactory.getLogger(DistillationController.class);

    private final DistillationService distillationService;

    public DistillationController(DistillationService distillationService) {
        this.distillationService = distillationService;
    }

    // ==================== Job Management ====================

    /**
     * Start a new distillation job.
     */
    @PostMapping("/start")
    public ResponseEntity<TrainingJobStatus> startDistillation(@RequestBody DistillationConfigRequest request) {
        try {
            log.info("Starting distillation job: teacher={}, student={}, type={}",
                    request.getTeacherModelId(), request.getStudentModelId(), request.getDistillationType());
            TrainingJobStatus status = distillationService.startDistillation(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start distillation job", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get status of a specific distillation job.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<TrainingJobStatus> getJob(@PathVariable String jobId) {
        try {
            TrainingJobStatus status = distillationService.getJob(jobId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get distillation job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel a distillation job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        try {
            log.info("Cancelling distillation job: {}", jobId);
            boolean cancelled = distillationService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of("jobId", jobId, "cancelled", cancelled));
        } catch (Exception e) {
            log.error("Failed to cancel distillation job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== SSE Log Streaming ====================

    /**
     * Subscribe to real-time log stream for a distillation job (SSE).
     */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobLogs(@PathVariable String jobId) {
        log.info("SSE subscription for distillation job logs: {}", jobId);
        return distillationService.subscribeToJobLogs(jobId);
    }

    // ==================== Distillation Types ====================

    /**
     * Get available distillation types.
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getAvailableDistillationTypes() {
        try {
            return ResponseEntity.ok(distillationService.getAvailableDistillationTypes());
        } catch (Exception e) {
            log.error("Failed to get available distillation types", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
