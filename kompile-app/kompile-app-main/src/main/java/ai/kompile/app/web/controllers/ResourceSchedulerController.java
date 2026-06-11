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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the resource-aware job scheduler.
 *
 * <p>Provides visibility into the job queue, running jobs, scheduler configuration,
 * job history, and resource profiles.</p>
 */
@RestController
@RequestMapping("/api/scheduler")
public class ResourceSchedulerController {

    private static final Logger log = LoggerFactory.getLogger(ResourceSchedulerController.class);

    private final ResourceAwareJobScheduler scheduler;
    private final ResourceSchedulerConfigService configService;
    private final JobSchedulerHistoryService historyService;
    private final JobSchedulerBroadcaster broadcaster;

    @Autowired
    public ResourceSchedulerController(
            ResourceAwareJobScheduler scheduler,
            ResourceSchedulerConfigService configService,
            JobSchedulerHistoryService historyService,
            @Autowired(required = false) JobSchedulerBroadcaster broadcaster) {
        this.scheduler = scheduler;
        this.configService = configService;
        this.historyService = historyService;
        this.broadcaster = broadcaster;
    }

    // ==================== Status ====================

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(scheduler.getStatus());
    }

    // ==================== Queue & Running ====================

    @GetMapping("/queue")
    public ResponseEntity<List<ScheduledJob.ScheduledJobView>> getQueue() {
        return ResponseEntity.ok(scheduler.getQueueSnapshot());
    }

    @GetMapping("/running")
    public ResponseEntity<List<ScheduledJob.ScheduledJobView>> getRunning() {
        return ResponseEntity.ok(scheduler.getRunningSnapshot());
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ScheduledJob.ScheduledJobView> getJob(@PathVariable String jobId) {
        ScheduledJob.ScheduledJobView view = scheduler.getJobView(jobId);
        if (view == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(view);
    }

    // ==================== Job Actions ====================

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        boolean cancelled = scheduler.cancel(jobId);
        log.info("Cancel request for job '{}': {}", jobId, cancelled ? "cancelled" : "not found/already terminal");
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "cancelled", cancelled
        ));
    }

    @PostMapping("/jobs/{jobId}/promote")
    public ResponseEntity<Map<String, Object>> promoteJob(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "100") int priority) {
        scheduler.promote(jobId, priority);
        log.info("Promote request for job '{}' to priority {}", jobId, priority);
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "newPriority", priority
        ));
    }

    // ==================== Config ====================

    @GetMapping("/config")
    public ResponseEntity<ResourceSchedulerConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    @PutMapping("/config")
    public ResponseEntity<ResourceSchedulerConfig> updateConfig(
            @RequestBody ResourceSchedulerConfig config) {
        try {
            configService.saveConfiguration(config);
            log.info("Scheduler config updated via REST: enabled={}, algorithm={}",
                    config.isEnabled(), config.getSchedulingAlgorithm());
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to update scheduler config: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/config/reset")
    public ResponseEntity<ResourceSchedulerConfig> resetConfig() {
        try {
            configService.resetToDefaults();
            log.info("Scheduler config reset to defaults via REST");
            return ResponseEntity.ok(configService.getConfiguration());
        } catch (Exception e) {
            log.error("Failed to reset scheduler config: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== Resource Profiles ====================

    @GetMapping("/profiles")
    public ResponseEntity<Map<String, JobResourceProfile>> getProfiles() {
        return ResponseEntity.ok(JobResourceProfiles.all());
    }

    // ==================== History ====================

    @GetMapping("/history")
    public ResponseEntity<List<JobSchedulerHistoryService.JobHistoryEntry>> getHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state) {
        List<JobSchedulerHistoryService.JobHistoryEntry> history;
        if (type != null) {
            history = historyService.getHistoryByType(type, limit);
        } else if (state != null) {
            history = historyService.getHistoryByState(state, limit);
        } else {
            history = historyService.getRecentHistory(limit);
        }
        return ResponseEntity.ok(history);
    }

    @GetMapping("/history/stats")
    public ResponseEntity<Map<String, Object>> getHistoryStats() {
        return ResponseEntity.ok(historyService.getStats());
    }

    // ==================== Events ====================

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> getRecentEvents(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (broadcaster != null) {
            result.put("events", broadcaster.getRecentEvents(limit));
            result.put("totalEventCount", broadcaster.getTotalEventCount());
        } else {
            result.put("events", List.of());
            result.put("totalEventCount", 0);
        }
        return ResponseEntity.ok(result);
    }

    // ==================== External Scheduler Callback ====================

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> externalCallback(
            @RequestBody Map<String, Object> payload) {
        String jobId = (String) payload.get("jobId");
        Boolean success = (Boolean) payload.getOrDefault("success", false);
        String message = (String) payload.getOrDefault("message", "");

        if (jobId == null || jobId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobId is required"));
        }

        log.info("External scheduler callback: jobId='{}', success={}", jobId, success);
        scheduler.handleExternalCallback(jobId, success, message);
        return ResponseEntity.ok(Map.of("acknowledged", true, "jobId", jobId));
    }

    // ==================== External Scheduler ====================

    @GetMapping("/external/status")
    public ResponseEntity<Map<String, Object>> getExternalStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("delegates", scheduler.getExternalDelegateStatus());
        status.put("configuredMode", configService.getConfiguration().getExternalSchedulerMode());
        status.put("enabled", configService.getConfiguration().isExternalSchedulerEnabled());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/external/modes")
    public ResponseEntity<List<Map<String, Object>>> getExternalModes() {
        return ResponseEntity.ok(scheduler.getExternalDelegateStatus());
    }

    @PostMapping("/jobs/{jobId}/cancel-external")
    public ResponseEntity<Map<String, Object>> cancelExternalJob(@PathVariable String jobId) {
        try {
            boolean cancelled = scheduler.cancelExternal(jobId).join();
            log.info("Cancel external job '{}': {}", jobId, cancelled);
            return ResponseEntity.ok(Map.of(
                    "jobId", jobId,
                    "cancelled", cancelled
            ));
        } catch (Exception e) {
            log.error("Error cancelling external job '{}': {}", jobId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "jobId", jobId
            ));
        }
    }

    // ==================== Combined Dashboard ====================

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("status", scheduler.getStatus());
        dashboard.put("queue", scheduler.getQueueSnapshot());
        dashboard.put("running", scheduler.getRunningSnapshot());
        dashboard.put("recentHistory", historyService.getRecentHistory(20));
        dashboard.put("stats", historyService.getStats());
        dashboard.put("profiles", JobResourceProfiles.all());
        dashboard.put("config", configService.getConfiguration());
        dashboard.put("externalScheduler", scheduler.getExternalDelegateStatus());
        if (broadcaster != null) {
            dashboard.put("recentEvents", broadcaster.getRecentEvents(50));
        }
        return ResponseEntity.ok(dashboard);
    }
}
