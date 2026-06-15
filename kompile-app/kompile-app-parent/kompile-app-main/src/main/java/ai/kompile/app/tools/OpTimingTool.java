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

package ai.kompile.app.tools;

import ai.kompile.app.services.OpTimingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class OpTimingTool {

    private static final Logger logger = LoggerFactory.getLogger(OpTimingTool.class);

    private final OpTimingService opTimingService;

    @Autowired
    public OpTimingTool(@Autowired(required = false) OpTimingService opTimingService) {
        this.opTimingService = opTimingService;
        logger.info("OpTimingTool initialized");
    }

    public record GetOpTimingStatusInput() {}
    public record EnableOpTimingInput(Boolean detailed) {}
    public record DisableOpTimingInput() {}
    public record FlushTimingStatsInput(Integer topN) {}
    public record GetOpBreakdownInput(String opName) {}
    public record GetOpHistogramInput(String opName) {}
    public record ExportChromeTraceInput() {}
    public record ResetOpTimingInput() {}

    @Tool(name = "get_op_timing_status",
            description = "Gets the current status of operation timing/profiling including whether it's enabled and cached stats.")
    public Map<String, Object> getOpTimingStatus(GetOpTimingStatusInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            var status = opTimingService.getStatus();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(status);
            return result;
        } catch (Exception e) {
            logger.error("Error getting op timing status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "enable_op_timing",
            description = "Enables operation timing/profiling. Set detailed=true for per-op granularity.")
    public Map<String, Object> enableOpTiming(EnableOpTimingInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            boolean detailed = input.detailed() != null && input.detailed();
            var result = opTimingService.enableTiming(detailed);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.putAll(result);
            return response;
        } catch (Exception e) {
            logger.error("Error enabling op timing: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "disable_op_timing",
            description = "Disables operation timing/profiling.")
    public Map<String, Object> disableOpTiming(DisableOpTimingInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            var result = opTimingService.disableTiming();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.putAll(result);
            return response;
        } catch (Exception e) {
            logger.error("Error disabling op timing: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "flush_timing_stats",
            description = "Flushes and returns timing statistics. Optional topN parameter to limit results (default 20).")
    public Map<String, Object> flushTimingStats(FlushTimingStatsInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            int topN = input.topN() != null && input.topN() > 0 ? input.topN() : 20;
            var stats = opTimingService.flushAndGetStats(topN);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(stats);
            return result;
        } catch (Exception e) {
            logger.error("Error flushing timing stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_op_breakdown",
            description = "Gets detailed timing breakdown for a specific operation by name.")
    public Map<String, Object> getOpBreakdown(GetOpBreakdownInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            if (input.opName() == null) return Map.of("status", "error", "error", "opName is required");
            var breakdown = opTimingService.getOpBreakdown(input.opName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(breakdown);
            return result;
        } catch (Exception e) {
            logger.error("Error getting op breakdown: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_op_histogram",
            description = "Gets a timing histogram for a specific operation by name.")
    public Map<String, Object> getOpHistogram(GetOpHistogramInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            if (input.opName() == null) return Map.of("status", "error", "error", "opName is required");
            var histogram = opTimingService.getOpHistogram(input.opName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(histogram);
            return result;
        } catch (Exception e) {
            logger.error("Error getting op histogram: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "export_chrome_trace",
            description = "Exports timing data as a Chrome trace format JSON for visualization in chrome://tracing.")
    public Map<String, Object> exportChromeTrace(ExportChromeTraceInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            var trace = opTimingService.exportChromeTrace();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(trace);
            return result;
        } catch (Exception e) {
            logger.error("Error exporting chrome trace: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_op_timing",
            description = "Resets all collected timing data and counters.")
    public Map<String, Object> resetOpTiming(ResetOpTimingInput input) {
        try {
            if (opTimingService == null) return Map.of("status", "error", "error", "OpTimingService not available");
            var result = opTimingService.reset();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.putAll(result);
            return response;
        } catch (Exception e) {
            logger.error("Error resetting op timing: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
