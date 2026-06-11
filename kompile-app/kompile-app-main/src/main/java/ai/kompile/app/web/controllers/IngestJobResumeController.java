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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.IngestJobResumeService;
import ai.kompile.app.services.IngestJobResumeService.ResumableJobSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for listing and resuming ingest jobs from checkpoints.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/ingest/resume/jobs}                — list resumable jobs</li>
 *   <li>{@code POST /api/ingest/resume/jobs/{taskId}}        — resume a job</li>
 *   <li>{@code GET  /api/ingest/resume/jobs/{taskId}/checkpoint} — checkpoint details</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ingest/resume")
@ConditionalOnBean(IngestJobResumeService.class)
public class IngestJobResumeController {

    private static final Logger log = LoggerFactory.getLogger(IngestJobResumeController.class);

    private final IngestJobResumeService resumeService;

    public IngestJobResumeController(IngestJobResumeService resumeService) {
        this.resumeService = resumeService;
    }

    /**
     * List all ingest jobs that have a checkpoint and can be resumed.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<ResumableJobSummary>> listResumableJobs() {
        return ResponseEntity.ok(resumeService.listResumableJobs());
    }

    /**
     * Resume an ingest job from its checkpoint.
     * Creates a new task that picks up where the failed/paused job left off.
     *
     * @param taskId the task ID of the original job to resume
     * @return the new task ID and status
     */
    @PostMapping("/jobs/{taskId}")
    public ResponseEntity<?> resumeJob(@PathVariable String taskId) {
        try {
            String newTaskId = resumeService.resumeJob(taskId);
            return ResponseEntity.ok(Map.of(
                    "message", "Job resumed from checkpoint",
                    "originalTaskId", taskId,
                    "newTaskId", newTaskId,
                    "status", "RUNNING"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to resume job {}", taskId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to resume job: " + e.getMessage()));
        }
    }

    /**
     * Get checkpoint status and progress details for a job.
     */
    @GetMapping("/jobs/{taskId}/checkpoint")
    public ResponseEntity<Map<String, Object>> getCheckpointStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(resumeService.getCheckpointStatus(taskId));
    }
}
