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

import ai.kompile.app.services.scheduling.ScheduledPipelineService;
import ai.kompile.app.services.scheduling.ScheduledPipelineService.ScheduleInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing scheduled pipeline operations.
 */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(ScheduledPipelineService.class)
public class ScheduleController {

    private final ScheduledPipelineService scheduledPipelineService;

    /**
     * POST /api/schedules/staleness-check
     * Schedule a periodic staleness check.
     */
    @PostMapping("/staleness-check")
    public ResponseEntity<Map<String, Object>> scheduleStalenessCheck(
            @RequestBody StalenessCheckRequest request) {
        try {
            String id = UUID.randomUUID().toString().substring(0, 8);
            scheduledPipelineService.scheduleStalenessCheck(id, request.cron(), request.factSheetId());
            return ResponseEntity.ok(Map.of(
                    "scheduleId", "staleness-" + id,
                    "type", "staleness-check",
                    "cron", request.cron(),
                    "factSheetId", request.factSheetId()
            ));
        } catch (Exception e) {
            log.error("Failed to schedule staleness check", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/schedules/re-ingestion
     * Schedule periodic re-ingestion of stale documents.
     */
    @PostMapping("/re-ingestion")
    public ResponseEntity<Map<String, Object>> scheduleReIngestion(
            @RequestBody ReIngestionRequest request) {
        try {
            String id = UUID.randomUUID().toString().substring(0, 8);
            scheduledPipelineService.scheduleReIngestion(id, request.cron(), request.factSheetId());
            return ResponseEntity.ok(Map.of(
                    "scheduleId", "reingestion-" + id,
                    "type", "re-ingestion",
                    "cron", request.cron(),
                    "factSheetId", request.factSheetId()
            ));
        } catch (Exception e) {
            log.error("Failed to schedule re-ingestion", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/schedules/eval-suite
     * Schedule periodic evaluation suite execution.
     */
    @PostMapping("/eval-suite")
    public ResponseEntity<Map<String, Object>> scheduleEvalSuite(
            @RequestBody EvalSuiteRequest request) {
        try {
            String id = UUID.randomUUID().toString().substring(0, 8);
            scheduledPipelineService.scheduleEvalRun(id, request.cron(), request.suiteId());
            return ResponseEntity.ok(Map.of(
                    "scheduleId", "eval-" + id,
                    "type", "eval-suite",
                    "cron", request.cron(),
                    "suiteId", request.suiteId()
            ));
        } catch (Exception e) {
            log.error("Failed to schedule eval suite", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/schedules
     * List all scheduled pipeline jobs.
     */
    @GetMapping
    public ResponseEntity<List<ScheduleInfo>> listSchedules() {
        try {
            return ResponseEntity.ok(scheduledPipelineService.listSchedules());
        } catch (Exception e) {
            log.error("Failed to list schedules", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * DELETE /api/schedules/{id}
     * Cancel a scheduled job.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelSchedule(@PathVariable String id) {
        try {
            if (scheduledPipelineService.cancelSchedule(id)) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to cancel schedule {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // DTO Records
    public record StalenessCheckRequest(String cron, Long factSheetId) {}
    public record ReIngestionRequest(String cron, Long factSheetId) {}
    public record EvalSuiteRequest(String cron, String suiteId) {}
}
