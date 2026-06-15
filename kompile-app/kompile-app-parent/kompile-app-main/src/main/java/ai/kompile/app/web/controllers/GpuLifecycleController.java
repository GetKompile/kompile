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

import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for monitoring GPU lifecycle management.
 *
 * <p>Exposes the status of:
 * <ul>
 *   <li>GPU devices and their memory reservations</li>
 *   <li>Managed services (embedding, VLM, etc.) and their lifecycle state</li>
 *   <li>Active evictions and preemptions</li>
 *   <li>Job-level GPU holds with timing information</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gpu-lifecycle")
public class GpuLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(GpuLifecycleController.class);

    @Autowired
    private GpuResourceManager gpuResourceManager;

    @Autowired
    private ModelLifecycleManager modelLifecycleManager;

    /**
     * Get comprehensive GPU lifecycle status.
     * Includes device info, reservations, managed service states, and active job holds.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("lifecycle", modelLifecycleManager.getStatus());
        return ResponseEntity.ok(status);
    }

    /**
     * Get GPU device information and memory reservations.
     */
    @GetMapping("/devices")
    public ResponseEntity<Map<String, Object>> getDevices() {
        return ResponseEntity.ok(gpuResourceManager.getStatus());
    }

    /**
     * Get all active job GPU holds.
     * Shows which jobs are currently holding GPU resources with timing information.
     */
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> getActiveJobs() {
        Map<String, Object> result = new LinkedHashMap<>();
        var holds = modelLifecycleManager.getActiveJobHolds();
        result.put("totalActiveJobs", holds.size());

        List<Map<String, Object>> jobList = new ArrayList<>();
        for (var hold : holds.values()) {
            Map<String, Object> jh = new LinkedHashMap<>();
            jh.put("jobId", hold.jobId());
            jh.put("serviceType", hold.serviceType());
            jh.put("device", hold.device().name());
            jh.put("acquiredAt", hold.acquiredAt().toString());
            jh.put("heldForMs", Duration.between(hold.acquiredAt(), Instant.now()).toMillis());
            jh.put("description", hold.description());
            jobList.add(jh);
        }
        result.put("jobs", jobList);
        return ResponseEntity.ok(result);
    }

    /**
     * Force-release GPU resources for a specific job.
     * Use with caution — only for stuck/orphaned jobs.
     */
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> forceReleaseJob(@PathVariable String jobId) {
        boolean hadHold = modelLifecycleManager.hasJobGpuHold(jobId);
        if (!hadHold) {
            return ResponseEntity.notFound().build();
        }

        log.warn("Force-releasing GPU hold for job '{}' via REST API", jobId);
        modelLifecycleManager.releaseGpuForJob(jobId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("status", "released");
        result.put("warning", "GPU resources force-released. The job's subprocess may still be running.");
        return ResponseEntity.ok(result);
    }

    /**
     * Get memory budgets for all service types.
     */
    @GetMapping("/budgets")
    public ResponseEntity<Map<String, Object>> getBudgets() {
        Map<String, Object> budgets = new LinkedHashMap<>();
        for (String serviceType : new String[]{"embedding", "vlm", "ingest", "vectorPopulation", "modelInit"}) {
            long budgetBytes = gpuResourceManager.getMemoryBudget(serviceType);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("budgetMb", budgetBytes / (1024 * 1024));
            info.put("priority", gpuResourceManager.getServicePriority(serviceType));
            info.put("hasReservation", gpuResourceManager.hasReservationForService(serviceType));
            info.put("reservationCount", gpuResourceManager.getReservationCount(serviceType));
            budgets.put(serviceType, info);
        }
        return ResponseEntity.ok(budgets);
    }

    /**
     * Update memory budget for a service type.
     */
    @PostMapping("/budgets/{serviceType}")
    public ResponseEntity<Map<String, Object>> updateBudget(
            @PathVariable String serviceType,
            @RequestParam long budgetMb) {
        gpuResourceManager.setMemoryBudget(serviceType, budgetMb * 1024 * 1024);
        log.info("Updated GPU memory budget for '{}': {}MB", serviceType, budgetMb);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceType", serviceType);
        result.put("budgetMb", budgetMb);
        result.put("status", "updated");
        return ResponseEntity.ok(result);
    }

    /**
     * Update priority for a service type.
     */
    @PostMapping("/priorities/{serviceType}")
    public ResponseEntity<Map<String, Object>> updatePriority(
            @PathVariable String serviceType,
            @RequestParam int priority) {
        gpuResourceManager.setServicePriority(serviceType, priority);
        log.info("Updated GPU priority for '{}': {}", serviceType, priority);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceType", serviceType);
        result.put("priority", priority);
        result.put("status", "updated");
        return ResponseEntity.ok(result);
    }
}
