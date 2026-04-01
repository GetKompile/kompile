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

import ai.kompile.staging.training.AlignmentService;
import ai.kompile.staging.web.dto.AlignmentConfigRequest;
import ai.kompile.staging.web.dto.TrainingJobStatus;
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
 * REST API controller for alignment training (RLHF, DPO, KTO, etc.).
 * Provides endpoints for starting alignment jobs, monitoring progress,
 * and querying available alignment algorithms.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/alignment")
@CrossOrigin(origins = "*")
public class AlignmentController {

    private static final Logger log = LoggerFactory.getLogger(AlignmentController.class);

    private final AlignmentService alignmentService;

    public AlignmentController(AlignmentService alignmentService) {
        this.alignmentService = alignmentService;
    }

    // ==================== Job Management ====================

    /**
     * Start a new alignment training job.
     */
    @PostMapping("/start")
    public ResponseEntity<TrainingJobStatus> startAlignment(@RequestBody AlignmentConfigRequest request) {
        try {
            log.info("Starting alignment job: algorithm={}, model={}, dataset={}",
                    request.getAlgorithm(), request.getBaseModelId(), request.getDatasetId());
            TrainingJobStatus status = alignmentService.startAlignment(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start alignment job", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get status of a specific alignment job.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<TrainingJobStatus> getJob(@PathVariable String jobId) {
        try {
            TrainingJobStatus status = alignmentService.getJob(jobId);
            if (status == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get alignment job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel an alignment job.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        try {
            log.info("Cancelling alignment job: {}", jobId);
            boolean cancelled = alignmentService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of("jobId", jobId, "cancelled", cancelled));
        } catch (Exception e) {
            log.error("Failed to cancel alignment job: {}", jobId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== SSE Log Streaming ====================

    /**
     * Subscribe to real-time log stream for an alignment job (SSE).
     */
    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobLogs(@PathVariable String jobId) {
        log.info("SSE subscription for alignment job logs: {}", jobId);
        return alignmentService.subscribeToJobLogs(jobId);
    }

    // ==================== Algorithms ====================

    /**
     * Get available alignment algorithms.
     */
    @GetMapping("/algorithms")
    public ResponseEntity<List<Map<String, String>>> getAvailableAlgorithms() {
        try {
            return ResponseEntity.ok(alignmentService.getAvailableAlgorithms());
        } catch (Exception e) {
            log.error("Failed to get available alignment algorithms", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
