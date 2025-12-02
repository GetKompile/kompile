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

import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing NDArray lifecycle tracking configuration at runtime.
 * Allows enabling/disabling tracking features and adjusting performance parameters
 * without restarting the application.
 */
@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleTrackingController {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleTrackingController.class);

    /**
     * Get current lifecycle tracking configuration
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("lifecycleTracking", Nd4j.getEnvironment().isLifecycleTracking());
        config.put("trackViews", Nd4j.getEnvironment().isTrackViews());
        config.put("trackDeletions", Nd4j.getEnvironment().isTrackDeletions());
        config.put("snapshotFiles", Nd4j.getEnvironment().isSnapshotFiles());
        config.put("trackOperations", Nd4j.getEnvironment().isTrackOperations());
        config.put("stackDepth", Nd4j.getEnvironment().getStackDepth());
        config.put("reportInterval", Nd4j.getEnvironment().getReportInterval());
        config.put("maxDeletionHistory", Nd4j.getEnvironment().getMaxDeletionHistory());

        return ResponseEntity.ok(config);
    }

    /**
     * Enable/disable lifecycle tracking (master switch)
     */
    @PostMapping("/tracking")
    public ResponseEntity<Map<String, String>> setLifecycleTracking(@RequestParam boolean enabled) {
        Nd4j.getEnvironment().setLifecycleTracking(enabled);
        logger.info("Lifecycle tracking {}", enabled ? "enabled" : "disabled");

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("lifecycleTracking", String.valueOf(enabled));
        return ResponseEntity.ok(response);
    }

    /**
     * Enable lifecycle tracking and apply balanced preset
     */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, String>> enableTracking() {
        logger.info("Enabling lifecycle tracking with balanced preset");

        Nd4j.getEnvironment().setLifecycleTracking(true);
        Nd4j.getEnvironment().setTrackViews(false);
        Nd4j.getEnvironment().setTrackDeletions(false);
        Nd4j.getEnvironment().setSnapshotFiles(true);
        Nd4j.getEnvironment().setTrackOperations(true);
        Nd4j.getEnvironment().setStackDepth(16);
        Nd4j.getEnvironment().setReportInterval(120);
        Nd4j.getEnvironment().setMaxDeletionHistory(1000);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Lifecycle tracking enabled with balanced preset");
        return ResponseEntity.ok(response);
    }

    /**
     * Disable lifecycle tracking completely
     */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, String>> disableTracking() {
        logger.info("Disabling lifecycle tracking completely");

        Nd4j.getEnvironment().setLifecycleTracking(false);
        Nd4j.getEnvironment().setTrackViews(false);
        Nd4j.getEnvironment().setTrackDeletions(false);
        Nd4j.getEnvironment().setSnapshotFiles(false);
        Nd4j.getEnvironment().setTrackOperations(false);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Lifecycle tracking disabled completely");
        return ResponseEntity.ok(response);
    }

    /**
     * Enable/disable view tracking (tracks NDArrays that share buffers)
     */
    @PostMapping("/track-views")
    public ResponseEntity<Map<String, String>> setTrackViews(@RequestParam boolean enabled) {
        Nd4j.getEnvironment().setTrackViews(enabled);
        logger.info("View tracking {}", enabled ? "enabled" : "disabled");

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("trackViews", String.valueOf(enabled));
        return ResponseEntity.ok(response);
    }

    /**
     * Enable/disable deletion tracking (captures stack traces on deallocation)
     * WARNING: High overhead when enabled
     */
    @PostMapping("/track-deletions")
    public ResponseEntity<Map<String, String>> setTrackDeletions(@RequestParam boolean enabled) {
        Nd4j.getEnvironment().setTrackDeletions(enabled);
        logger.warn("Deletion tracking {} - THIS HAS HIGH OVERHEAD", enabled ? "enabled" : "disabled");

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("trackDeletions", String.valueOf(enabled));
        response.put("warning", enabled ? "High overhead enabled" : null);
        return ResponseEntity.ok(response);
    }

    /**
     * Enable/disable snapshot files
     */
    @PostMapping("/snapshot-files")
    public ResponseEntity<Map<String, String>> setSnapshotFiles(@RequestParam boolean enabled) {
        Nd4j.getEnvironment().setSnapshotFiles(enabled);
        logger.info("Snapshot files {}", enabled ? "enabled" : "disabled");

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("snapshotFiles", String.valueOf(enabled));
        return ResponseEntity.ok(response);
    }

    /**
     * Enable/disable operation tracking (tracks which ops leak)
     */
    @PostMapping("/track-operations")
    public ResponseEntity<Map<String, String>> setTrackOperations(@RequestParam boolean enabled) {
        Nd4j.getEnvironment().setTrackOperations(enabled);
        logger.info("Operation tracking {}", enabled ? "enabled" : "disabled");

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("trackOperations", String.valueOf(enabled));
        return ResponseEntity.ok(response);
    }

    /**
     * Set stack trace depth (number of frames to capture)
     */
    @PostMapping("/stack-depth")
    public ResponseEntity<Map<String, String>> setStackDepth(@RequestParam int depth) {
        if (depth < 1 || depth > 256) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Stack depth must be between 1 and 256");
            return ResponseEntity.badRequest().body(error);
        }

        Nd4j.getEnvironment().setStackDepth(depth);
        logger.info("Stack depth set to {} frames", depth);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("stackDepth", String.valueOf(depth));
        return ResponseEntity.ok(response);
    }

    /**
     * Set report interval in seconds
     */
    @PostMapping("/report-interval")
    public ResponseEntity<Map<String, String>> setReportInterval(@RequestParam int seconds) {
        if (seconds < 10 || seconds > 3600) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Report interval must be between 10 and 3600 seconds");
            return ResponseEntity.badRequest().body(error);
        }

        Nd4j.getEnvironment().setReportInterval(seconds);
        logger.info("Report interval set to {} seconds", seconds);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("reportInterval", String.valueOf(seconds));
        return ResponseEntity.ok(response);
    }

    /**
     * Set max deletion history size
     */
    @PostMapping("/max-deletion-history")
    public ResponseEntity<Map<String, String>> setMaxDeletionHistory(@RequestParam int size) {
        if (size < 100 || size > 100000) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Max deletion history must be between 100 and 100000");
            return ResponseEntity.badRequest().body(error);
        }

        Nd4j.getEnvironment().setMaxDeletionHistory(size);
        logger.info("Max deletion history set to {} records", size);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("maxDeletionHistory", String.valueOf(size));
        return ResponseEntity.ok(response);
    }

    /**
     * Trigger immediate leak check (calls native triggerLeakCheck)
     */
    @PostMapping("/trigger-leak-check")
    public ResponseEntity<Map<String, String>> triggerLeakCheck() {
        logger.info("Triggering immediate leak check");
        Nd4j.getNativeOps().triggerLeakCheck();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Leak check triggered - check stderr for report");
        return ResponseEntity.ok(response);
    }

    /**
     * Apply performance preset configurations
     */
    @PostMapping("/preset")
    public ResponseEntity<Map<String, String>> applyPreset(@RequestParam String preset) {
        switch (preset.toLowerCase()) {
            case "minimal":
                // Minimal overhead - only basic tracking
                Nd4j.getEnvironment().setLifecycleTracking(true);
                Nd4j.getEnvironment().setTrackViews(false);
                Nd4j.getEnvironment().setTrackDeletions(false);
                Nd4j.getEnvironment().setSnapshotFiles(true);
                Nd4j.getEnvironment().setTrackOperations(false);
                Nd4j.getEnvironment().setStackDepth(8);
                Nd4j.getEnvironment().setReportInterval(300);
                Nd4j.getEnvironment().setMaxDeletionHistory(500);
                logger.info("Applied MINIMAL overhead preset");
                break;

            case "balanced":
                // Balanced - moderate tracking
                Nd4j.getEnvironment().setLifecycleTracking(true);
                Nd4j.getEnvironment().setTrackViews(false);
                Nd4j.getEnvironment().setTrackDeletions(false);
                Nd4j.getEnvironment().setSnapshotFiles(true);
                Nd4j.getEnvironment().setTrackOperations(true);
                Nd4j.getEnvironment().setStackDepth(16);
                Nd4j.getEnvironment().setReportInterval(120);
                Nd4j.getEnvironment().setMaxDeletionHistory(1000);
                logger.info("Applied BALANCED preset");
                break;

            case "detailed":
                // Detailed - full tracking (high overhead)
                Nd4j.getEnvironment().setLifecycleTracking(true);
                Nd4j.getEnvironment().setTrackViews(true);
                Nd4j.getEnvironment().setTrackDeletions(true);
                Nd4j.getEnvironment().setSnapshotFiles(true);
                Nd4j.getEnvironment().setTrackOperations(true);
                Nd4j.getEnvironment().setStackDepth(64);
                Nd4j.getEnvironment().setReportInterval(30);
                Nd4j.getEnvironment().setMaxDeletionHistory(5000);
                logger.warn("Applied DETAILED preset - HIGH OVERHEAD");
                break;

            default:
                Map<String, String> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Unknown preset. Valid presets: minimal, balanced, detailed");
                return ResponseEntity.badRequest().body(error);
        }

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("preset", preset);
        return ResponseEntity.ok(response);
    }
}
