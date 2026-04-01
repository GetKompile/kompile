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

import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.service.TrainingJobHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for training job history.
 * Provides persistent job history that survives application restarts.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/training/history")
@CrossOrigin(origins = "*")
public class TrainingJobHistoryController {

    private static final Logger log = LoggerFactory.getLogger(TrainingJobHistoryController.class);

    private final TrainingJobHistoryService historyService;

    public TrainingJobHistoryController(TrainingJobHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * List all training job history with pagination.
     */
    @GetMapping
    public ResponseEntity<Page<TrainingJobHistory>> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(historyService.getAllJobs(page, size));
        } catch (Exception e) {
            log.error("Failed to list training job history", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a specific job history by task ID.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TrainingJobHistory> getJob(@PathVariable String taskId) {
        return historyService.getJob(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get jobs filtered by status.
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<TrainingJobHistory>> getJobsByStatus(@PathVariable String status) {
        try {
            TrainingJobHistory.JobStatus jobStatus = TrainingJobHistory.JobStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(historyService.getJobsByStatus(jobStatus));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get jobs filtered by training type.
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<List<TrainingJobHistory>> getJobsByType(@PathVariable String type) {
        try {
            TrainingJobHistory.TrainingType trainingType = TrainingJobHistory.TrainingType.valueOf(type.toUpperCase());
            return ResponseEntity.ok(historyService.getJobsByTrainingType(trainingType));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get jobs for a specific model.
     */
    @GetMapping("/model/{modelId}")
    public ResponseEntity<List<TrainingJobHistory>> getJobsByModel(@PathVariable String modelId) {
        return ResponseEntity.ok(historyService.getJobsByModelId(modelId));
    }

    /**
     * Get recent jobs (last N hours).
     */
    @GetMapping("/recent")
    public ResponseEntity<List<TrainingJobHistory>> getRecentJobs(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(historyService.getRecentJobs(hours));
    }

    /**
     * Get currently active jobs.
     */
    @GetMapping("/active")
    public ResponseEntity<List<TrainingJobHistory>> getActiveJobs() {
        return ResponseEntity.ok(historyService.getActiveJobs());
    }

    /**
     * Get job statistics.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(historyService.getJobStatistics(hours));
    }

    /**
     * Delete a specific job history.
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteJob(@PathVariable String taskId) {
        boolean deleted = historyService.deleteJob(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "deleted", deleted));
    }

    /**
     * Force cleanup of old jobs.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup(
            @RequestParam(defaultValue = "30") int days) {
        int deleted = historyService.forceCleanup(days);
        return ResponseEntity.ok(Map.of("deletedCount", deleted, "olderThanDays", days));
    }
}
