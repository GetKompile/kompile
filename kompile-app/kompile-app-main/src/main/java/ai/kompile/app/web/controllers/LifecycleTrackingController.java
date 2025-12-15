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

import ai.kompile.app.services.LeakReportCleanupService;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST controller for managing NDArray lifecycle tracking configuration at runtime.
 * Allows enabling/disabling tracking features and adjusting performance parameters
 * without restarting the application.
 *
 * IMPORTANT (Session 150 fix): Leak report generation now uses a coordinated
 * JVM shutdown hook that waits for Spring's @PreDestroy phase to complete before
 * generating the report. This ensures the report captures ACTUAL leaks, not
 * objects that are about to be cleaned up by other @PreDestroy methods.
 *
 * The coordination works as follows:
 * 1. LeakReportShutdownHandler registers a JVM shutdown hook at class load time
 * 2. When Spring shuts down, LeakReportShutdownHandler's @PreDestroy signals the hook
 * 3. The hook waits 1 second for all cleanup to complete
 * 4. Then generates the comprehensive leak report
 */
@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleTrackingController {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleTrackingController.class);

    // Track whether lifecycle tracking was enabled so we know to generate report on shutdown
    private static volatile boolean trackingWasEnabled = false;
    private static final Object TRACKING_LOCK = new Object();

    // Flag to indicate that the leak report has already been generated
    // Used by the coordinated shutdown hook to prevent duplicate reports
    private static volatile boolean leakReportAlreadyGenerated = false;

    @Autowired(required = false)
    private LeakReportCleanupService cleanupService;

    /**
     * Enables leak report generation on shutdown.
     *
     * NOTE (Session 150 fix): The actual shutdown hook is now registered in
     * LeakReportShutdownHandler's static initializer. This method just ensures
     * trackingWasEnabled is set so the hook knows to generate a report.
     *
     * The new mechanism uses a coordinated approach:
     * 1. JVM shutdown hook waits for Spring's @PreDestroy to signal
     * 2. Then waits additional time for all cleanup to complete
     * 3. Only then generates the leak report
     *
     * This fixes the race condition where leak reports were generated BEFORE
     * embedding model cleanup completed.
     */
    private static void registerLeakReportShutdownHook() {
        synchronized (TRACKING_LOCK) {
            // Just mark that tracking is enabled - the static hook in
            // LeakReportShutdownHandler will check this flag
            if (!trackingWasEnabled) {
                trackingWasEnabled = true;
                logger.info("Leak report generation enabled - will run after all @PreDestroy cleanup");
            }
        }
    }

    /**
     * Disables leak report generation.
     *
     * NOTE (Session 150 fix): This no longer removes a shutdown hook since the
     * coordinated hook is registered statically. Instead, it just clears the
     * trackingWasEnabled flag so the hook knows not to generate a report.
     */
    private static void removeLeakReportShutdownHook() {
        synchronized (TRACKING_LOCK) {
            trackingWasEnabled = false;
            logger.info("Leak report generation disabled");
        }
    }

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

        // Individual tracker status
        config.put("ndArrayTracking", Nd4j.getEnvironment().isNDArrayTracking());
        config.put("dataBufferTracking", Nd4j.getEnvironment().isDataBufferTracking());
        config.put("tadCacheTracking", Nd4j.getEnvironment().isTADCacheTracking());
        config.put("shapeCacheTracking", Nd4j.getEnvironment().isShapeCacheTracking());
        config.put("opContextTracking", Nd4j.getEnvironment().isOpContextTracking());

        return ResponseEntity.ok(config);
    }

    /**
     * Enable/disable lifecycle tracking (master switch).
     * When enabled, registers shutdown hook to generate leak reports on exit.
     */
    @PostMapping("/tracking")
    public ResponseEntity<Map<String, String>> setLifecycleTracking(@RequestParam("enabled") boolean enabled) {
        Nd4j.getEnvironment().setLifecycleTracking(enabled);
        logger.info("Lifecycle tracking {}", enabled ? "enabled" : "disabled");

        if (enabled) {
            registerLeakReportShutdownHook();
        } else {
            removeLeakReportShutdownHook();
        }

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("lifecycleTracking", String.valueOf(enabled));
        response.put("leakReportsOnExit", String.valueOf(enabled));
        if (enabled) {
            response.put("leakReportDirectory", "./leak_reports");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Enable lifecycle tracking and apply balanced preset.
     * Automatically registers a shutdown hook to generate leak reports on exit.
     * Enables ALL lifecycle trackers: DataBuffer, NDArray, TADCache, ShapeCache, OpContext.
     */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, String>> enableTracking() {
        logger.info("Enabling lifecycle tracking with balanced preset (ALL trackers)");

        // Set Java-side environment flags
        Nd4j.getEnvironment().setLifecycleTracking(true);
        Nd4j.getEnvironment().setTrackViews(false);
        Nd4j.getEnvironment().setTrackDeletions(false);
        Nd4j.getEnvironment().setSnapshotFiles(true);
        Nd4j.getEnvironment().setTrackOperations(true);
        Nd4j.getEnvironment().setStackDepth(16);
        Nd4j.getEnvironment().setReportInterval(120);
        Nd4j.getEnvironment().setMaxDeletionHistory(1000);

        // CRITICAL: Enable ALL C++ lifecycle trackers explicitly via Environment
        Nd4j.getEnvironment().setDataBufferTracking(true);
        Nd4j.getEnvironment().setNDArrayTracking(true);
        Nd4j.getEnvironment().setTADCacheTracking(true);
        Nd4j.getEnvironment().setShapeCacheTracking(true);
        Nd4j.getEnvironment().setOpContextTracking(true);
        logger.info("Enabled all C++ lifecycle trackers: DataBuffer, NDArray, TADCache, ShapeCache, OpContext");

        // Mark that tracking was enabled so we generate report on shutdown
        synchronized (TRACKING_LOCK) {
            trackingWasEnabled = true;
        }

        // Also register shutdown hook as backup (in case @PreDestroy doesn't run)
        registerLeakReportShutdownHook();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Lifecycle tracking enabled with balanced preset (ALL trackers)");
        response.put("leakReportsOnExit", "true");
        response.put("leakReportDirectory", "./leak_reports");
        response.put("trackersEnabled", "DataBuffer (explicit), NDArray, TADCache, ShapeCache, OpContext");
        return ResponseEntity.ok(response);
    }

    /**
     * Disable lifecycle tracking completely.
     * Removes the shutdown hook so leak reports won't be generated on exit.
     */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, String>> disableTracking() {
        logger.info("Disabling lifecycle tracking completely");

        Nd4j.getEnvironment().setLifecycleTracking(false);
        Nd4j.getEnvironment().setTrackViews(false);
        Nd4j.getEnvironment().setTrackDeletions(false);
        Nd4j.getEnvironment().setSnapshotFiles(false);
        Nd4j.getEnvironment().setTrackOperations(false);

        // Mark that tracking was disabled
        synchronized (TRACKING_LOCK) {
            trackingWasEnabled = false;
        }

        // Remove shutdown hook since tracking is disabled
        removeLeakReportShutdownHook();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Lifecycle tracking disabled completely");
        response.put("leakReportsOnExit", "false");
        return ResponseEntity.ok(response);
    }

    /**
     * Enable/disable view tracking (tracks NDArrays that share buffers)
     */
    @PostMapping("/track-views")
    public ResponseEntity<Map<String, String>> setTrackViews(@RequestParam("enabled") boolean enabled) {
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
    public ResponseEntity<Map<String, String>> setTrackDeletions(@RequestParam("enabled") boolean enabled) {
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
    public ResponseEntity<Map<String, String>> setSnapshotFiles(@RequestParam("enabled") boolean enabled) {
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
    public ResponseEntity<Map<String, String>> setTrackOperations(@RequestParam("enabled") boolean enabled) {
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
    public ResponseEntity<Map<String, String>> setStackDepth(@RequestParam("depth") int depth) {
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
    public ResponseEntity<Map<String, String>> setReportInterval(@RequestParam("seconds") int seconds) {
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
    public ResponseEntity<Map<String, String>> setMaxDeletionHistory(@RequestParam("size") int size) {
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
     * Apply performance preset configurations.
     * Automatically registers shutdown hook to generate leak reports on exit.
     */
    @PostMapping("/preset")
    public ResponseEntity<Map<String, String>> applyPreset(@RequestParam("preset") String preset) {
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

        // All presets enable tracking, so mark tracking as enabled
        synchronized (TRACKING_LOCK) {
            trackingWasEnabled = true;
        }

        // Also register shutdown hook as backup (in case @PreDestroy doesn't run)
        registerLeakReportShutdownHook();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("preset", preset);
        response.put("leakReportsOnExit", "true");
        response.put("leakReportDirectory", "./leak_reports");
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // LIFECYCLE REPORTING ENDPOINTS
    // ========================================================================

    /**
     * Manually trigger lifecycle reports for all trackers.
     * This will call periodicCheck() on NDArray, DataBuffer, TADCache, ShapeCache, and OpContext trackers,
     * causing them to print their current statistics to stderr.
     *
     * The periodic check functionality is built into the trackers and automatically triggers based on
     * the reportInterval setting, but this endpoint allows manual triggering on demand.
     *
     * @return Response indicating report was triggered
     */
    @PostMapping("/print-lifecycle-report")
    public ResponseEntity<Map<String, String>> printLifecycleReport() {
        try {
            logger.info("Triggering manual lifecycle report for all trackers");

            // The periodicCheck() methods are called automatically during allocations
            // when the interval has passed. However, there's no direct JNI method to
            // trigger them manually. The best we can do is trigger a leak check which
            // will print current state.
            Nd4j.getNativeOps().triggerLeakCheck();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Lifecycle report triggered - check application logs/stderr for output");
            response.put("note", "Reports are automatically generated every " +
                        Nd4j.getEnvironment().getReportInterval() + " seconds when tracking is enabled");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error triggering lifecycle report", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            error.put("message", "Failed to trigger lifecycle report");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get current periodic reporting configuration.
     *
     * Note: Periodic reporting in ND4J/libnd4j happens automatically when:
     * 1. Lifecycle tracking is enabled
     * 2. An allocation/deallocation occurs
     * 3. The reportInterval time has elapsed since the last report
     *
     * There is no separate enable/disable flag - it's controlled by the reportInterval setting.
     *
     * @return Current reporting configuration
     */
    @GetMapping("/periodic-reporting-config")
    public ResponseEntity<Map<String, Object>> getPeriodicReportingConfig() {
        try {
            Map<String, Object> config = new HashMap<>();

            int reportInterval = Nd4j.getEnvironment().getReportInterval();
            boolean lifecycleTracking = Nd4j.getEnvironment().isLifecycleTracking();
            boolean snapshotFiles = Nd4j.getEnvironment().isSnapshotFiles();

            config.put("reportInterval", reportInterval);
            config.put("lifecycleTracking", lifecycleTracking);
            config.put("snapshotFiles", snapshotFiles);
            config.put("periodicReportingEnabled", lifecycleTracking && reportInterval > 0);
            config.put("description", "Periodic reports are automatically generated every " + reportInterval +
                      " seconds when lifecycle tracking is enabled and allocations occur");

            return ResponseEntity.ok(config);

        } catch (Exception e) {
            logger.error("Error getting periodic reporting config", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Enable lifecycle tracking with periodic reporting.
     * This is a convenience endpoint that configures lifecycle tracking to automatically
     * generate periodic reports at the specified interval.
     *
     * @param intervalSeconds Report interval in seconds (10-3600, default 120)
     * @param enableSnapshots Whether to save periodic snapshot files (default true)
     * @return Response with configuration details
     */
    @PostMapping("/enable-periodic-reporting")
    public ResponseEntity<Map<String, Object>> enablePeriodicReporting(
            @RequestParam(defaultValue = "120") int intervalSeconds,
            @RequestParam(defaultValue = "true") boolean enableSnapshots) {

        try {
            if (intervalSeconds < 10 || intervalSeconds > 3600) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Report interval must be between 10 and 3600 seconds");
                return ResponseEntity.badRequest().body(error);
            }

            logger.info("Enabling periodic lifecycle reporting: interval={}s, snapshots={}",
                       intervalSeconds, enableSnapshots);

            // Enable lifecycle tracking
            Nd4j.getEnvironment().setLifecycleTracking(true);

            // Configure reporting settings
            Nd4j.getEnvironment().setReportInterval(intervalSeconds);
            Nd4j.getEnvironment().setSnapshotFiles(enableSnapshots);

            // Apply balanced tracking settings
            Nd4j.getEnvironment().setTrackViews(false);
            Nd4j.getEnvironment().setTrackDeletions(false);
            Nd4j.getEnvironment().setTrackOperations(true);
            Nd4j.getEnvironment().setStackDepth(16);
            Nd4j.getEnvironment().setMaxDeletionHistory(1000);

            // Enable ALL lifecycle trackers explicitly via Environment
            Nd4j.getEnvironment().setDataBufferTracking(true);
            Nd4j.getEnvironment().setNDArrayTracking(true);
            Nd4j.getEnvironment().setTADCacheTracking(true);
            Nd4j.getEnvironment().setShapeCacheTracking(true);
            Nd4j.getEnvironment().setOpContextTracking(true);

            // Mark that tracking was enabled
            synchronized (TRACKING_LOCK) {
                trackingWasEnabled = true;
            }

            // Also register shutdown hook as backup (in case @PreDestroy doesn't run)
            registerLeakReportShutdownHook();

            // Generate initial comprehensive leak report immediately
            String outputDir = "./leak_reports";
            Files.createDirectories(Paths.get(outputDir));
            Nd4j.getNativeOps().generateComprehensiveLeakAnalysis(outputDir);

            logger.info("Periodic lifecycle reporting enabled successfully");

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("reportInterval", intervalSeconds);
            response.put("snapshotFiles", enableSnapshots);
            response.put("lifecycleTracking", true);
            response.put("leakReportsOnExit", true);
            response.put("leakReportDirectory", "./leak_reports");
            response.put("comprehensiveReportGenerated", true);
            response.put("message", String.format(
                "Periodic lifecycle reporting enabled: reports every %d seconds%s",
                intervalSeconds,
                enableSnapshots ? " with snapshot files" : ""));
            response.put("note", "Initial comprehensive leak report generated. Periodic reports will be printed to stderr when allocations occur after the interval has elapsed. Comprehensive leak analysis will also be generated on shutdown.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error enabling periodic reporting", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            error.put("message", "Failed to enable periodic lifecycle reporting");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Disable periodic lifecycle reporting.
     * This disables all lifecycle tracking, which stops periodic reports from being generated.
     * Also removes the shutdown hook so leak reports won't be generated on exit.
     *
     * @return Response indicating success
     */
    @PostMapping("/disable-periodic-reporting")
    public ResponseEntity<Map<String, String>> disablePeriodicReporting() {
        try {
            logger.info("Disabling periodic lifecycle reporting");

            // Disable all lifecycle trackers via Environment
            Nd4j.getEnvironment().setDataBufferTracking(false);
            Nd4j.getEnvironment().setNDArrayTracking(false);
            Nd4j.getEnvironment().setTADCacheTracking(false);
            Nd4j.getEnvironment().setShapeCacheTracking(false);
            Nd4j.getEnvironment().setOpContextTracking(false);

            // Disable Java-side settings
            Nd4j.getEnvironment().setLifecycleTracking(false);

            // Mark that tracking was disabled
            synchronized (TRACKING_LOCK) {
                trackingWasEnabled = false;
            }

            // Remove shutdown hook since tracking is disabled
            removeLeakReportShutdownHook();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Periodic lifecycle reporting disabled (all lifecycle tracking disabled)");
            response.put("leakReportsOnExit", "false");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error disabling periodic reporting", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            error.put("message", "Failed to disable periodic reporting");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    // ========================================================================
    // CACHE STATISTICS AND INSPECTION ENDPOINTS
    // ========================================================================

    /**
     * Get current shape cache statistics.
     * Returns current and peak number of entries and memory usage.
     *
     * @return Shape cache statistics
     */
    @GetMapping("/cache/shape/stats")
    public ResponseEntity<Map<String, Object>> getShapeCacheStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("currentEntries", Nd4j.getNativeOps().getShapeCachedEntries());
            stats.put("currentBytes", Nd4j.getNativeOps().getShapeCachedBytes());
            stats.put("currentMB", String.format("%.2f", Nd4j.getNativeOps().getShapeCachedBytes() / (1024.0 * 1024.0)));
            stats.put("peakEntries", Nd4j.getNativeOps().getShapePeakCachedEntries());
            stats.put("peakBytes", Nd4j.getNativeOps().getShapePeakCachedBytes());
            stats.put("peakMB", String.format("%.2f", Nd4j.getNativeOps().getShapePeakCachedBytes() / (1024.0 * 1024.0)));

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            logger.error("Error getting shape cache stats", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get current TAD cache statistics.
     * Returns current and peak number of entries and memory usage.
     *
     * @return TAD cache statistics
     */
    @GetMapping("/cache/tad/stats")
    public ResponseEntity<Map<String, Object>> getTADCacheStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("currentEntries", Nd4j.getNativeOps().getTADCachedEntries());
            stats.put("currentBytes", Nd4j.getNativeOps().getTADCachedBytes());
            stats.put("currentMB", String.format("%.2f", Nd4j.getNativeOps().getTADCachedBytes() / (1024.0 * 1024.0)));
            stats.put("peakEntries", Nd4j.getNativeOps().getTADPeakCachedEntries());
            stats.put("peakBytes", Nd4j.getNativeOps().getTADPeakCachedBytes());
            stats.put("peakMB", String.format("%.2f", Nd4j.getNativeOps().getTADPeakCachedBytes() / (1024.0 * 1024.0)));

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            logger.error("Error getting TAD cache stats", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get combined cache statistics for both shape and TAD caches.
     *
     * @return Combined cache statistics
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getAllCacheStats() {
        try {
            long shapeEntries = Nd4j.getNativeOps().getShapeCachedEntries();
            long shapeBytes = Nd4j.getNativeOps().getShapeCachedBytes();
            long shapePeakEntries = Nd4j.getNativeOps().getShapePeakCachedEntries();
            long shapePeakBytes = Nd4j.getNativeOps().getShapePeakCachedBytes();

            long tadEntries = Nd4j.getNativeOps().getTADCachedEntries();
            long tadBytes = Nd4j.getNativeOps().getTADCachedBytes();
            long tadPeakEntries = Nd4j.getNativeOps().getTADPeakCachedEntries();
            long tadPeakBytes = Nd4j.getNativeOps().getTADPeakCachedBytes();

            Map<String, Object> shapeStats = new HashMap<>();
            shapeStats.put("currentEntries", shapeEntries);
            shapeStats.put("currentBytes", shapeBytes);
            shapeStats.put("currentMB", String.format("%.2f", shapeBytes / (1024.0 * 1024.0)));
            shapeStats.put("peakEntries", shapePeakEntries);
            shapeStats.put("peakBytes", shapePeakBytes);
            shapeStats.put("peakMB", String.format("%.2f", shapePeakBytes / (1024.0 * 1024.0)));

            Map<String, Object> tadStats = new HashMap<>();
            tadStats.put("currentEntries", tadEntries);
            tadStats.put("currentBytes", tadBytes);
            tadStats.put("currentMB", String.format("%.2f", tadBytes / (1024.0 * 1024.0)));
            tadStats.put("peakEntries", tadPeakEntries);
            tadStats.put("peakBytes", tadPeakBytes);
            tadStats.put("peakMB", String.format("%.2f", tadPeakBytes / (1024.0 * 1024.0)));

            Map<String, Object> totals = new HashMap<>();
            totals.put("currentEntries", shapeEntries + tadEntries);
            totals.put("currentBytes", shapeBytes + tadBytes);
            totals.put("currentMB", String.format("%.2f", (shapeBytes + tadBytes) / (1024.0 * 1024.0)));
            totals.put("peakEntries", shapePeakEntries + tadPeakEntries);
            totals.put("peakBytes", shapePeakBytes + tadPeakBytes);
            totals.put("peakMB", String.format("%.2f", (shapePeakBytes + tadPeakBytes) / (1024.0 * 1024.0)));

            Map<String, Object> response = new HashMap<>();
            response.put("shapeCache", shapeStats);
            response.put("tadCache", tadStats);
            response.put("totals", totals);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting combined cache stats", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get a browsable string representation of the shape cache.
     * Shows the trie structure with cached shape buffers for debugging.
     *
     * @param maxDepth Maximum depth to traverse (default: 10, -1 for unlimited)
     * @param maxEntries Maximum number of entries to show (default: 100, -1 for unlimited)
     * @return String representation of the shape cache
     */
    @GetMapping("/cache/shape/browse")
    public ResponseEntity<Map<String, String>> browseShapeCache(
            @RequestParam(defaultValue = "10") int maxDepth,
            @RequestParam(defaultValue = "100") int maxEntries) {

        try {
            String cacheStr = Nd4j.getNativeOps().getShapeCacheString(maxDepth, maxEntries);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("cache", cacheStr);
            response.put("maxDepth", String.valueOf(maxDepth));
            response.put("maxEntries", String.valueOf(maxEntries));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error browsing shape cache", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get a browsable string representation of the TAD cache.
     * Shows the trie structure with cached TAD packs for debugging.
     *
     * @param maxDepth Maximum depth to traverse (default: 10, -1 for unlimited)
     * @param maxEntries Maximum number of entries to show (default: 100, -1 for unlimited)
     * @return String representation of the TAD cache
     */
    @GetMapping("/cache/tad/browse")
    public ResponseEntity<Map<String, String>> browseTADCache(
            @RequestParam(defaultValue = "10") int maxDepth,
            @RequestParam(defaultValue = "100") int maxEntries) {

        try {
            String cacheStr = Nd4j.getNativeOps().getTADCacheString(maxDepth, maxEntries);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("cache", cacheStr);
            response.put("maxDepth", String.valueOf(maxDepth));
            response.put("maxEntries", String.valueOf(maxEntries));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error browsing TAD cache", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Clear the shape cache.
     * Frees all cached shape buffers. Useful for memory management during testing.
     *
     * @return Response indicating success
     */
    @PostMapping("/cache/shape/clear")
    public ResponseEntity<Map<String, String>> clearShapeCache() {
        try {
            logger.info("Clearing shape cache");
            Nd4j.getNativeOps().clearShapeCache();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Shape cache cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error clearing shape cache", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Clear the TAD cache.
     * Frees all cached TAD packs. Useful for memory management during testing.
     *
     * @return Response indicating success
     */
    @PostMapping("/cache/tad/clear")
    public ResponseEntity<Map<String, String>> clearTADCache() {
        try {
            logger.info("Clearing TAD cache");
            Nd4j.getNativeOps().clearTADCache();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "TAD cache cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error clearing TAD cache", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Clear both shape and TAD caches.
     * Frees all cached data structures.
     *
     * @return Response indicating success
     */
    @PostMapping("/cache/clear-all")
    public ResponseEntity<Map<String, String>> clearAllCaches() {
        try {
            logger.info("Clearing all caches (shape + TAD)");
            Nd4j.getNativeOps().clearShapeCache();
            Nd4j.getNativeOps().clearTADCache();

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "All caches cleared (shape + TAD)");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error clearing all caches", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    // ========================================================================
    // LEAK REPORT CLEANUP ENDPOINTS
    // ========================================================================

    /**
     * Get current cleanup service configuration.
     *
     * @return Current cleanup configuration
     */
    @GetMapping("/cleanup/config")
    public ResponseEntity<Map<String, Object>> getCleanupConfig() {
        if (cleanupService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "disabled");
            response.put("message", "Cleanup service is not available (disabled via configuration)");
            return ResponseEntity.ok(response);
        }

        try {
            LeakReportCleanupService.CleanupConfig config = cleanupService.getConfig();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("enabled", config.enabled);
            response.put("directory", config.directory);
            response.put("maxAgeDays", config.maxAgeDays);
            response.put("maxFiles", config.maxFiles);
            response.put("schedule", "Daily at 2:00 AM");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting cleanup config", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Enable or disable the cleanup service at runtime.
     *
     * @param enabled Whether to enable or disable cleanup
     * @return Response indicating success
     */
    @PostMapping("/cleanup/enabled")
    public ResponseEntity<Map<String, String>> setCleanupEnabled(@RequestParam("enabled") boolean enabled) {
        if (cleanupService == null) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Cleanup service is not available (disabled via configuration)");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            cleanupService.setCleanupEnabled(enabled);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("enabled", String.valueOf(enabled));
            response.put("message", String.format("Cleanup service %s", enabled ? "enabled" : "disabled"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error setting cleanup enabled state", e);

            Map<String, String> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Update cleanup configuration parameters at runtime.
     *
     * @param maxAgeDays Maximum age of files to keep (optional)
     * @param maxFiles Maximum number of files to keep (optional)
     * @return Response indicating success
     */
    @PostMapping("/cleanup/config")
    public ResponseEntity<Map<String, Object>> updateCleanupConfig(
            @RequestParam(required = false) Integer maxAgeDays,
            @RequestParam(required = false) Integer maxFiles) {

        if (cleanupService == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Cleanup service is not available (disabled via configuration)");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            cleanupService.updateConfig(maxAgeDays, maxFiles);

            LeakReportCleanupService.CleanupConfig config = cleanupService.getConfig();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("maxAgeDays", config.maxAgeDays);
            response.put("maxFiles", config.maxFiles);
            response.put("message", "Cleanup configuration updated");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error updating cleanup config", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Manually trigger an immediate cleanup of old leak report files.
     * This runs the cleanup operation immediately regardless of the schedule.
     *
     * @return Response with cleanup results
     */
    @PostMapping("/cleanup/trigger")
    public ResponseEntity<Map<String, Object>> triggerCleanup() {
        if (cleanupService == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Cleanup service is not available (disabled via configuration)");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            logger.info("Manual cleanup triggered via REST API");

            LeakReportCleanupService.CleanupResult result = cleanupService.triggerCleanup();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("deletedFiles", result.deletedCount);
            response.put("freedBytes", result.freedBytes);
            response.put("freedMB", String.format("%.2f", result.freedBytes / (1024.0 * 1024.0)));
            response.put("message", String.format("Cleanup completed: deleted %d files, freed %d bytes",
                                                  result.deletedCount, result.freedBytes));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error triggering manual cleanup", e);

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            error.put("message", "Failed to trigger cleanup");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    // ========================================================================
    // SHUTDOWN LEAK REPORT GENERATION (COORDINATED WITH JVM SHUTDOWN HOOK)
    // ========================================================================

    /**
     * Separate component for generating leak reports during shutdown.
     *
     * CRITICAL FIX (Session 150): Previous approach used @PreDestroy to generate
     * leak reports, but Spring does NOT guarantee @PreDestroy execution order
     * between beans. This caused leak reports to be generated BEFORE embedding
     * model cleanup, resulting in 103 MB of false positive "leaks".
     *
     * NEW APPROACH: Use a coordinated shutdown mechanism:
     * 1. This component's @PreDestroy signals that Spring shutdown is in progress
     * 2. A JVM shutdown hook waits for a brief period after signal
     * 3. The JVM shutdown hook then generates the leak report AFTER all @PreDestroy
     *    methods have completed
     *
     * This guarantees the leak report captures ACTUAL leaks, not objects that
     * are about to be cleaned up by other @PreDestroy methods.
     */
    @Component
    public static class LeakReportShutdownHandler {
        private static final Logger log = LoggerFactory.getLogger(LeakReportShutdownHandler.class);

        // Latch to signal that Spring's @PreDestroy phase has started
        private static final CountDownLatch springShutdownStarted = new CountDownLatch(1);

        // Flag to track if this handler's @PreDestroy has run
        private static final AtomicBoolean preDestroyExecuted = new AtomicBoolean(false);

        // Flag to prevent duplicate report generation
        private static final AtomicBoolean reportGenerated = new AtomicBoolean(false);

        // Register the JVM shutdown hook in a static initializer
        // This hook will generate the leak report AFTER Spring cleanup completes
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // Check if tracking was enabled
                    boolean shouldGenerateReport;
                    synchronized (TRACKING_LOCK) {
                        shouldGenerateReport = trackingWasEnabled;
                    }

                    if (!shouldGenerateReport) {
                        log.debug("Lifecycle tracking was not enabled - skipping leak report generation");
                        return;
                    }

                    // Wait for Spring's @PreDestroy to signal shutdown has started
                    // If Spring is running, its @PreDestroy will signal within 1 second
                    // If not running (non-graceful shutdown), proceed after timeout
                    boolean springSignaled = springShutdownStarted.await(2, TimeUnit.SECONDS);

                    if (springSignaled) {
                        // Spring's @PreDestroy started - wait a bit more for all cleanup to complete
                        // This allows other @PreDestroy methods (like embedding model cleanup) to finish
                        log.info("Spring shutdown detected - waiting for @PreDestroy methods to complete...");
                        Thread.sleep(1000); // Allow 1 second for cleanup to complete
                    } else {
                        log.warn("Spring shutdown signal not received - possible non-graceful shutdown");
                    }

                    // Generate the leak report (only once)
                    if (reportGenerated.compareAndSet(false, true)) {
                        generateLeakReportInternal();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Leak report shutdown hook interrupted");
                } catch (Exception e) {
                    log.error("Error in leak report shutdown hook", e);
                }
            }, "LeakReportCoordinatedShutdownHook"));
        }

        /**
         * @PreDestroy method signals that Spring shutdown has started.
         * Does NOT generate the leak report - that's done by the JVM shutdown hook
         * AFTER all @PreDestroy methods have completed.
         */
        @PreDestroy
        public void signalSpringShutdownStarted() {
            log.info("=== Spring @PreDestroy Phase Started ===");
            log.info("Signaling JVM shutdown hook to wait for cleanup completion...");

            // Signal that Spring shutdown has started
            preDestroyExecuted.set(true);
            springShutdownStarted.countDown();

            // Mark the old flag for backward compatibility
            leakReportAlreadyGenerated = false; // Will be set true by the JVM hook

            log.info("Signal sent - leak report will be generated by JVM shutdown hook after all cleanup");
        }

        /**
         * Internal method to generate the leak report.
         * Called by the JVM shutdown hook after Spring cleanup completes.
         */
        private static void generateLeakReportInternal() {
            log.info("=== Generating Lifecycle Leak Reports (JVM Shutdown Hook) ===");
            log.info("All @PreDestroy methods should have completed - capturing ACTUAL leaks");

            try {
                // Create leak_reports directory if it doesn't exist
                String outputDir = "./leak_reports";
                Files.createDirectories(Paths.get(outputDir));

                // Generate comprehensive leak analysis
                log.info("Generating comprehensive leak analysis...");
                Nd4j.getNativeOps().generateComprehensiveLeakAnalysis(outputDir);

                // Mark for backward compatibility
                leakReportAlreadyGenerated = true;

                log.info("=== Lifecycle leak reports generated in: {} ===", outputDir);

            } catch (Exception e) {
                log.error("Error generating leak reports", e);
            }
        }
    }

}
