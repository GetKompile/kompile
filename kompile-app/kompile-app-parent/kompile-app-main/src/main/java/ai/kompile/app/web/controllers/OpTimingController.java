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

import ai.kompile.app.services.OpTimingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for ND4J Op Timing profiling.
 * Provides endpoints to enable/disable profiling, capture timing data,
 * and export statistics for performance analysis.
 *
 * <p>Based on the OpTimingTracker system from libnd4j which provides:
 * <ul>
 *   <li>Lock-free ring buffer for minimal overhead</li>
 *   <li>Phase-level timing (validation, memory alloc, helper exec, native exec)</li>
 *   <li>Statistical analysis with histograms and percentiles</li>
 *   <li>Chrome trace export for visualization</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/op-timing")
public class OpTimingController {

    private static final Logger log = LoggerFactory.getLogger(OpTimingController.class);

    private final OpTimingService opTimingService;

    public OpTimingController(OpTimingService opTimingService) {
        this.opTimingService = opTimingService;
    }

    /**
     * Get current op timing status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(opTimingService.getStatus());
    }

    /**
     * Enable op timing profiling.
     *
     * @param detailed if true, enable detailed phase-level timing (higher overhead)
     */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enableTiming(
            @RequestParam(defaultValue = "false") boolean detailed) {
        log.info("Enabling op timing, detailed={}", detailed);
        return ResponseEntity.ok(opTimingService.enableTiming(detailed));
    }

    /**
     * Enable op timing with trace mode for Chrome trace export.
     *
     * @param detailed if true, enable detailed phase-level timing
     */
    @PostMapping("/enable-trace")
    public ResponseEntity<Map<String, Object>> enableTimingWithTrace(
            @RequestParam(defaultValue = "true") boolean detailed) {
        log.info("Enabling op timing with trace mode, detailed={}", detailed);
        return ResponseEntity.ok(opTimingService.enableTimingWithTrace(detailed));
    }

    /**
     * Disable op timing profiling.
     */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disableTiming() {
        log.info("Disabling op timing");
        return ResponseEntity.ok(opTimingService.disableTiming());
    }

    /**
     * Flush timing data and get hotspot statistics.
     *
     * @param topN number of top ops to return (default 20)
     */
    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flushAndGetStats(
            @RequestParam(defaultValue = "20") int topN) {
        log.debug("Flushing op timing, topN={}", topN);
        return ResponseEntity.ok(opTimingService.flushAndGetStats(topN));
    }

    /**
     * Get cached statistics without flushing.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCachedStats() {
        return ResponseEntity.ok(opTimingService.getCachedStats());
    }

    /**
     * Get phase breakdown for a specific operation.
     *
     * @param opName the operation name (e.g., "matmul", "conv2d")
     */
    @GetMapping("/breakdown/{opName}")
    public ResponseEntity<Map<String, Object>> getOpBreakdown(@PathVariable String opName) {
        log.debug("Getting breakdown for op: {}", opName);
        return ResponseEntity.ok(opTimingService.getOpBreakdown(opName));
    }

    /**
     * Get timing histogram for a specific operation.
     *
     * @param opName the operation name
     */
    @GetMapping("/histogram/{opName}")
    public ResponseEntity<Map<String, Object>> getOpHistogram(@PathVariable String opName) {
        log.debug("Getting histogram for op: {}", opName);
        return ResponseEntity.ok(opTimingService.getOpHistogram(opName));
    }

    /**
     * Get per-thread timing statistics.
     */
    @GetMapping("/thread-stats")
    public ResponseEntity<Map<String, Object>> getThreadStats() {
        return ResponseEntity.ok(opTimingService.getThreadStats());
    }

    /**
     * Export timing data to Chrome trace JSON format.
     * The trace can be visualized at chrome://tracing or in Perfetto.
     */
    @GetMapping("/export/chrome-trace")
    public ResponseEntity<Map<String, Object>> exportChromeTrace() {
        log.info("Exporting Chrome trace");
        return ResponseEntity.ok(opTimingService.exportChromeTrace());
    }

    /**
     * Export timing data to CSV format for analysis.
     */
    @GetMapping("/export/csv")
    public ResponseEntity<Map<String, Object>> exportCSV() {
        log.info("Exporting CSV");
        return ResponseEntity.ok(opTimingService.exportCSV());
    }

    /**
     * Reset all timing data.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        log.info("Resetting op timing data");
        return ResponseEntity.ok(opTimingService.reset());
    }

    // ===================== SUBPROCESS OVERHEAD ENDPOINTS =====================

    /**
     * Get currently active subprocess timings.
     */
    @GetMapping("/subprocess/active")
    public ResponseEntity<Map<String, Object>> getActiveSubprocessTimings() {
        return ResponseEntity.ok(opTimingService.getActiveSubprocessTimings());
    }

    /**
     * Get subprocess timing history.
     *
     * @param limit maximum number of entries to return (default 50)
     */
    @GetMapping("/subprocess/history")
    public ResponseEntity<Map<String, Object>> getSubprocessTimingHistory(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(opTimingService.getSubprocessTimingHistory(limit));
    }

    /**
     * Clear subprocess timing history.
     */
    @PostMapping("/subprocess/clear")
    public ResponseEntity<Map<String, Object>> clearSubprocessTimingHistory() {
        opTimingService.clearSubprocessTimingHistory();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "Subprocess timing history cleared");
        return ResponseEntity.ok(result);
    }
}
