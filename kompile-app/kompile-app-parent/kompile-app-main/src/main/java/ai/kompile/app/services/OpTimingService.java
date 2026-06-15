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

package ai.kompile.app.services;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMessage;
import jakarta.annotation.PostConstruct;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for managing ND4J Op Timing profiling.
 * Uses NativeOps.setOpTimingEnabled() per ADR-OpTimingTracker.md
 * Persists settings via Nd4jEnvironmentConfigService.
 *
 * Also tracks subprocess overhead timing including:
 * - Subprocess startup time
 * - Model loading time
 * - IPC communication overhead
 * - Total subprocess lifecycle time
 */
@Service
public class OpTimingService {

    private static final Logger log = LoggerFactory.getLogger(OpTimingService.class);

    private final Nd4jEnvironmentConfigService configService;

    // Embedding model for subprocess op timing (may be null if no Anserini embedding)
    private volatile AnseriniEmbeddingModelImpl anseriniEmbeddingModel;

    private final AtomicBoolean detailedMode = new AtomicBoolean(false);
    private final AtomicBoolean traceMode = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    // Subprocess overhead tracking
    private final Map<String, SubprocessTimingStat> subprocessTimings = new ConcurrentHashMap<>();
    private final List<SubprocessTimingStat> subprocessTimingHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_SUBPROCESS_HISTORY = 100;

    @Autowired
    public OpTimingService(Nd4jEnvironmentConfigService configService,
                          @Autowired(required = false) List<EmbeddingModel> embeddingModels) {
        this.configService = configService;

        // Find AnseriniEmbeddingModelImpl if available (for subprocess op timing)
        if (embeddingModels != null) {
            for (EmbeddingModel model : embeddingModels) {
                if (model instanceof AnseriniEmbeddingModelImpl anserini) {
                    this.anseriniEmbeddingModel = anserini;
                    log.info("OpTimingService: Found AnseriniEmbeddingModelImpl for subprocess op timing");
                    break;
                }
            }
        }
        if (this.anseriniEmbeddingModel == null) {
            log.info("OpTimingService: No AnseriniEmbeddingModelImpl found - subprocess op timing not available");
        }
    }

    @PostConstruct
    public void init() {
        // Load persisted state from config
        Nd4jEnvironmentConfig config = configService.getConfiguration();
        if (config.profiling() != null && config.profiling()) {
            log.info("Restoring persisted op timing state: enabled=true");
            enabled.set(true);
            // Apply to native (main JVM)
            try {
                NativeOps ops = getNativeOps();
                if (ops != null) {
                    ops.setOpTimingEnabled(1, detailedMode.get() ? 1 : 0);
                }
            } catch (Throwable e) {
                log.warn("Failed to restore op timing to native: {}", e.getMessage());
            }
            // Configure subprocess op timing - this stores the desired state
            // so when subprocess starts, it will enable op timing before model loads
            if (anseriniEmbeddingModel != null) {
                log.info("Configuring subprocess op timing on init: enabled=true, detailed={}",
                        detailedMode.get());
                anseriniEmbeddingModel.configureSubprocessOpTiming(true, detailedMode.get());
            }
        }
    }

    /**
     * Called when subprocess becomes available to sync op timing state.
     * This is useful for cases where profiling was enabled before subprocess started.
     */
    public void syncOpTimingToSubprocess() {
        if (!enabled.get()) {
            return; // Nothing to sync
        }
        if (anseriniEmbeddingModel == null || !anseriniEmbeddingModel.isSubprocessAvailableForOpTiming()) {
            return; // Subprocess not available
        }
        log.info("Syncing op timing state to subprocess: enabled={}, detailed={}",
                enabled.get(), detailedMode.get());
        try {
            anseriniEmbeddingModel.configureSubprocessOpTiming(enabled.get(), detailedMode.get())
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to sync op timing to subprocess: {}", e.getMessage());
        }
    }

    private NativeOps getNativeOps() {
        try {
            return Nd4j.getNativeOps();
        } catch (Throwable e) {
            log.warn("ND4J NativeOps not available: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Persist the profiling state to disk via Nd4jEnvironmentConfigService.
     */
    private void persistProfilingState(boolean profilingEnabled) {
        try {
            Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                    .profiling(profilingEnabled)
                    .build();
            configService.updateConfiguration(update);
            log.info("Persisted profiling state: {}", profilingEnabled);
        } catch (Exception e) {
            log.error("Failed to persist profiling state: {}", e.getMessage());
        }
    }

    /**
     * Enable op timing profiling.
     * This enables op timing in both the main JVM and the embedding subprocess (if available).
     */
    public Map<String, Object> enableTiming(boolean detailed) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Enable in main JVM
            NativeOps ops = getNativeOps();
            ops.setOpTimingEnabled(1, detailed ? 1 : 0);
            log.info("Op timing enabled in main JVM via NativeOps.setOpTimingEnabled(1, {})", detailed ? 1 : 0);

            enabled.set(true);
            detailedMode.set(detailed);
            traceMode.set(false);

            // Persist to disk
            persistProfilingState(true);

            // Also enable in subprocess if available
            boolean subprocessEnabled = false;
            if (anseriniEmbeddingModel != null && anseriniEmbeddingModel.isSubprocessAvailableForOpTiming()) {
                try {
                    Boolean subResult = anseriniEmbeddingModel.configureSubprocessOpTiming(true, detailed)
                            .get(30, TimeUnit.SECONDS);
                    subprocessEnabled = subResult != null && subResult;
                    if (subprocessEnabled) {
                        log.info("Op timing also enabled in embedding subprocess");
                    } else {
                        log.warn("Failed to enable op timing in subprocess");
                    }
                } catch (Exception e) {
                    log.warn("Error enabling op timing in subprocess: {}", e.getMessage());
                }
            }

            result.put("status", "success");
            result.put("enabled", true);
            result.put("detailedMode", detailed);
            result.put("traceMode", false);
            result.put("subprocessEnabled", subprocessEnabled);
            result.put("message", "Op timing enabled" + (detailed ? " with detailed mode" : "") +
                    (subprocessEnabled ? " (also in subprocess)" : ""));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("enabled", false);
            result.put("message", "Failed to enable op timing: " + e.getMessage());
            log.error("Failed to enable op timing", e);
        }
        return result;
    }

    /**
     * Enable op timing with trace mode.
     */
    public Map<String, Object> enableTimingWithTrace(boolean detailed) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            ops.setOpTimingEnabledWithTrace(detailed ? 1 : 0);
            log.info("Op timing enabled with trace via NativeOps.setOpTimingEnabledWithTrace({})", detailed ? 1 : 0);

            enabled.set(true);
            detailedMode.set(detailed);
            traceMode.set(true);

            // Persist to disk
            persistProfilingState(true);

            result.put("status", "success");
            result.put("enabled", true);
            result.put("detailedMode", detailed);
            result.put("traceMode", true);
            result.put("message", "Op timing enabled with trace mode");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("enabled", false);
            result.put("message", "Failed to enable op timing with trace: " + e.getMessage());
            log.error("Failed to enable op timing with trace", e);
        }
        return result;
    }

    /**
     * Disable op timing profiling.
     * This disables op timing in both the main JVM and the embedding subprocess (if available).
     */
    public Map<String, Object> disableTiming() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Disable in main JVM
            NativeOps ops = getNativeOps();
            ops.setOpTimingEnabled(0, 0);
            log.info("Op timing disabled in main JVM via NativeOps.setOpTimingEnabled(0, 0)");

            enabled.set(false);
            detailedMode.set(false);
            traceMode.set(false);

            // Persist to disk
            persistProfilingState(false);

            // Also disable in subprocess if available
            boolean subprocessDisabled = false;
            if (anseriniEmbeddingModel != null && anseriniEmbeddingModel.isSubprocessAvailableForOpTiming()) {
                try {
                    Boolean subResult = anseriniEmbeddingModel.configureSubprocessOpTiming(false, false)
                            .get(30, TimeUnit.SECONDS);
                    subprocessDisabled = subResult != null && subResult;
                    if (subprocessDisabled) {
                        log.info("Op timing also disabled in embedding subprocess");
                    }
                } catch (Exception e) {
                    log.warn("Error disabling op timing in subprocess: {}", e.getMessage());
                }
            }

            result.put("status", "success");
            result.put("enabled", false);
            result.put("subprocessDisabled", subprocessDisabled);
            result.put("message", "Op timing disabled" + (subprocessDisabled ? " (also in subprocess)" : ""));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Failed to disable op timing: " + e.getMessage());
            log.error("Failed to disable op timing", e);
        }
        return result;
    }

    /**
     * Get current op timing status.
     * Includes status for both main JVM and subprocess.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            result.put("enabled", enabled.get());
            result.put("detailedMode", detailedMode.get());
            result.put("traceMode", traceMode.get());
            result.put("numOps", ops.getOpTimingNumOps());
            result.put("totalExecutions", ops.getOpTimingTotalExecutions());

            // Add subprocess availability info
            boolean subprocessAvailable = anseriniEmbeddingModel != null &&
                    anseriniEmbeddingModel.isSubprocessAvailableForOpTiming();
            result.put("subprocessAvailable", subprocessAvailable);
            if (subprocessAvailable) {
                result.put("subprocessNote", "Embedding operations run in subprocess - " +
                        "use flush to see subprocess op timing");
            }
        } catch (Exception e) {
            result.put("enabled", enabled.get());
            result.put("detailedMode", detailedMode.get());
            result.put("traceMode", traceMode.get());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Check if profiling is currently enabled.
     */
    public boolean isProfilingEnabled() {
        return enabled.get();
    }

    /**
     * Flush timing data and get statistics.
     * Exports to CSV and parses the data from both main JVM and subprocess.
     * Subprocess stats are shown separately since they represent the actual inference operations.
     */
    public Map<String, Object> flushAndGetStats(int topN) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Get stats from main JVM
            NativeOps ops = getNativeOps();
            ops.flushOpTiming();

            int numOps = ops.getOpTimingNumOps();
            long totalExecs = ops.getOpTimingTotalExecutions();

            // Export to CSV and parse (main JVM)
            List<OpTimingStat> hotspots = new ArrayList<>();
            String csvContent = "";

            try {
                Path csvPath = Files.createTempFile("nd4j_timing_", ".csv");
                ops.exportOpTimingCSV(csvPath.toString());
                csvContent = Files.readString(csvPath);
                hotspots = parseCSV(csvContent, topN);
                Files.deleteIfExists(csvPath);
            } catch (Exception e) {
                log.warn("Failed to export/parse CSV from main JVM: {}", e.getMessage());
            }

            result.put("status", "success");
            result.put("topN", topN);
            result.put("profilingEnabled", enabled.get());
            result.put("numOps", numOps);
            result.put("totalExecutions", totalExecs);
            result.put("hotspots", hotspots);
            result.put("rawOutput", csvContent);
            result.put("flushTime", System.currentTimeMillis());

            // Get stats from subprocess (this is where the actual embedding ops run!)
            Map<String, Object> subprocessStats = new LinkedHashMap<>();
            boolean hasAnseriniModel = anseriniEmbeddingModel != null;
            boolean subprocessRunning = hasAnseriniModel && anseriniEmbeddingModel.isSubprocessAvailableForOpTiming();

            log.info("=== SUBPROCESS OP TIMING FLUSH === hasAnseriniModel={}, subprocessRunning={}",
                    hasAnseriniModel, subprocessRunning);

            subprocessStats.put("available", subprocessRunning);

            if (subprocessRunning) {
                try {
                    log.info("Sending op timing flush request to subprocess...");
                    EmbeddingSubprocessMessage.OpTimingFlushResponse subResponse =
                            anseriniEmbeddingModel.flushSubprocessOpTiming(topN, false)
                                    .get(30, TimeUnit.SECONDS);

                    log.info("Received subprocess response: success={}, numOps={}, totalExecutions={}, hotspotsCount={}, error={}",
                            subResponse.success(), subResponse.numOps(), subResponse.totalExecutions(),
                            subResponse.hotspots() != null ? subResponse.hotspots().size() : 0,
                            subResponse.error());

                    if (subResponse.success()) {
                        subprocessStats.put("success", true);
                        subprocessStats.put("numOps", subResponse.numOps());
                        subprocessStats.put("totalExecutions", subResponse.totalExecutions());

                        // Convert subprocess hotspots to the same format as main JVM
                        List<OpTimingStat> subHotspots = new ArrayList<>();
                        if (subResponse.hotspots() != null) {
                            for (EmbeddingSubprocessMessage.OpTimingStat subStat : subResponse.hotspots()) {
                                OpTimingStat converted = new OpTimingStat();
                                converted.rank = subStat.rank();
                                converted.opName = subStat.opName();
                                converted.calls = subStat.calls();
                                converted.totalMs = subStat.totalMs();
                                converted.avgUs = subStat.avgUs();
                                converted.stdDevUs = subStat.stdDevUs();
                                converted.minUs = subStat.minUs();
                                converted.maxUs = subStat.maxUs();
                                converted.helperPercent = subStat.helperPercent();
                                subHotspots.add(converted);
                            }
                            log.info("Converted {} hotspots from subprocess", subHotspots.size());
                        }
                        subprocessStats.put("hotspots", subHotspots);
                        log.info("Retrieved op timing from subprocess: {} ops, {} executions, {} hotspots",
                                subResponse.numOps(), subResponse.totalExecutions(), subHotspots.size());
                    } else {
                        log.warn("Subprocess op timing flush returned success=false: {}", subResponse.error());
                        subprocessStats.put("success", false);
                        subprocessStats.put("error", subResponse.error());
                        subprocessStats.put("hotspots", List.of());
                    }
                } catch (Exception e) {
                    log.error("Error getting op timing from subprocess: {}", e.getMessage(), e);
                    subprocessStats.put("success", false);
                    subprocessStats.put("error", e.getMessage());
                    subprocessStats.put("hotspots", List.of());
                }
            } else {
                log.info("Subprocess not available for op timing (hasAnseriniModel={}, subprocessRunning={})",
                        hasAnseriniModel, subprocessRunning);
                subprocessStats.put("success", false);
                subprocessStats.put("hotspots", List.of());
            }
            result.put("subprocess", subprocessStats);
            log.info("Final subprocess stats: {}", subprocessStats);

            // Build message
            StringBuilder message = new StringBuilder();
            if (hotspots.isEmpty() && enabled.get()) {
                message.append("Main JVM: profiling enabled but no ops captured yet.");
            } else {
                message.append(String.format("Main JVM: %d ops, %d executions.", numOps, totalExecs));
            }
            if (subprocessRunning) {
                @SuppressWarnings("unchecked")
                Boolean subSuccess = (Boolean) subprocessStats.get("success");
                if (Boolean.TRUE.equals(subSuccess)) {
                    Integer subNumOps = (Integer) subprocessStats.get("numOps");
                    Long subTotalExecs = (Long) subprocessStats.get("totalExecutions");
                    message.append(String.format(" Subprocess: %d ops, %d executions.",
                            subNumOps != null ? subNumOps : 0, subTotalExecs != null ? subTotalExecs : 0));
                } else {
                    message.append(" Subprocess: failed to retrieve stats.");
                }
            } else {
                message.append(" Subprocess: not available.");
            }
            result.put("message", message.toString());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Failed to flush stats: " + e.getMessage());
            result.put("hotspots", new ArrayList<>());
            log.error("Failed to flush op timing", e);
        }
        return result;
    }

    /**
     * Parse CSV output into structured data.
     * Actual CSV format from C++: OpName,Hash,Calls,TotalMs,AvgUs,StdDevUs,MinUs,MaxUs,HelperPct,...
     */
    private List<OpTimingStat> parseCSV(String csvContent, int topN) {
        List<OpTimingStat> stats = new ArrayList<>();
        if (csvContent == null || csvContent.isEmpty()) {
            return stats;
        }

        String[] lines = csvContent.split("\n");
        int rank = 1;

        for (String line : lines) {
            // Skip header line
            if (line.startsWith("OpName") || line.startsWith("op_name") || line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }

            String[] parts = line.split(",");
            // CSV format: OpName,Hash,Calls,TotalMs,AvgUs,StdDevUs,MinUs,MaxUs,HelperPct,...
            if (parts.length >= 9) {
                try {
                    OpTimingStat stat = new OpTimingStat();
                    stat.rank = rank++;
                    stat.opName = parts[0].trim();
                    // parts[1] is Hash - skip it
                    stat.calls = Long.parseLong(parts[2].trim());
                    stat.totalMs = Double.parseDouble(parts[3].trim());
                    stat.avgUs = Double.parseDouble(parts[4].trim());
                    stat.stdDevUs = parts.length > 5 ? Double.parseDouble(parts[5].trim()) : 0;
                    stat.minUs = parts.length > 6 ? Double.parseDouble(parts[6].trim()) : 0;
                    stat.maxUs = parts.length > 7 ? Double.parseDouble(parts[7].trim()) : 0;
                    stat.helperPercent = parts.length > 8 ? Double.parseDouble(parts[8].trim()) : 0;
                    stats.add(stat);

                    if (stats.size() >= topN) {
                        break;
                    }
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse CSV line: {}", line);
                }
            }
        }

        // Sort by totalMs descending (hotspots)
        stats.sort((a, b) -> Double.compare(b.totalMs, a.totalMs));

        // Re-rank after sorting
        for (int i = 0; i < stats.size(); i++) {
            stats.get(i).rank = i + 1;
        }

        return stats;
    }

    /**
     * Get cached statistics.
     */
    public Map<String, Object> getCachedStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            result.put("status", "success");
            result.put("numOps", ops.getOpTimingNumOps());
            result.put("totalExecutions", ops.getOpTimingTotalExecutions());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Get phase breakdown for an op.
     */
    public Map<String, Object> getOpBreakdown(String opName) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            ops.printOpTimingBreakdown(opName);
            result.put("status", "success");
            result.put("opName", opName);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("opName", opName);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Get histogram for an op.
     */
    public Map<String, Object> getOpHistogram(String opName) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            ops.printOpTimingHistogram(opName);
            result.put("status", "success");
            result.put("opName", opName);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("opName", opName);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Get per-thread statistics.
     */
    public Map<String, Object> getThreadStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            ops.printOpTimingThreadStats();
            result.put("status", "success");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Export timing data to Chrome trace format.
     */
    public Map<String, Object> exportChromeTrace() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            String path = System.getProperty("java.io.tmpdir") + "/nd4j_trace_" + System.currentTimeMillis() + ".json";
            ops.exportOpTimingChromeTrace(path);
            result.put("status", "success");
            result.put("path", path);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Export timing data to CSV format.
     */
    public Map<String, Object> exportCSV() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps ops = getNativeOps();
            String path = System.getProperty("java.io.tmpdir") + "/nd4j_timing_" + System.currentTimeMillis() + ".csv";
            ops.exportOpTimingCSV(path);
            result.put("status", "success");
            result.put("path", path);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Reset all timing data in both main JVM and subprocess.
     */
    public Map<String, Object> reset() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Reset main JVM
            NativeOps ops = getNativeOps();
            ops.resetOpTiming();
            log.info("Op timing data reset in main JVM");

            // Also reset subprocess via flush with reset=true
            boolean subprocessReset = false;
            if (anseriniEmbeddingModel != null && anseriniEmbeddingModel.isSubprocessAvailableForOpTiming()) {
                try {
                    EmbeddingSubprocessMessage.OpTimingFlushResponse resp =
                            anseriniEmbeddingModel.flushSubprocessOpTiming(0, true)
                                    .get(30, TimeUnit.SECONDS);
                    subprocessReset = resp.success();
                    if (subprocessReset) {
                        log.info("Op timing data reset in subprocess");
                    }
                } catch (Exception e) {
                    log.warn("Error resetting subprocess op timing: {}", e.getMessage());
                }
            }

            result.put("status", "success");
            result.put("subprocessReset", subprocessReset);
            result.put("message", "Op timing data reset" + (subprocessReset ? " (including subprocess)" : ""));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Data class for op timing statistics.
     */
    public static class OpTimingStat {
        public int rank;
        public String opName;
        public long calls;
        public double totalMs;
        public double avgUs;
        public double stdDevUs;
        public double minUs;
        public double maxUs;
        public double helperPercent;

        public int getRank() { return rank; }
        public String getOpName() { return opName; }
        public long getCalls() { return calls; }
        public double getTotalMs() { return totalMs; }
        public double getAvgUs() { return avgUs; }
        public double getStdDevUs() { return stdDevUs; }
        public double getMinUs() { return minUs; }
        public double getMaxUs() { return maxUs; }
        public double getHelperPercent() { return helperPercent; }
    }

    // ===================== SUBPROCESS OVERHEAD TRACKING =====================

    /**
     * Record subprocess timing when a subprocess starts.
     *
     * @param taskId The task identifier
     * @param subprocessType The type of subprocess (EMBEDDING, VECTOR_POPULATION, INGEST, MODEL_INIT)
     */
    public void recordSubprocessStart(String taskId, String subprocessType) {
        SubprocessTimingStat stat = new SubprocessTimingStat();
        stat.taskId = taskId;
        stat.subprocessType = subprocessType;
        stat.startTimeMs = System.currentTimeMillis();
        stat.startTimeNanos = System.nanoTime();
        subprocessTimings.put(taskId, stat);
        log.debug("Started subprocess timing for task {} (type: {})", taskId, subprocessType);
    }

    /**
     * Record when the subprocess has finished starting up (first heartbeat received).
     *
     * @param taskId The task identifier
     */
    public void recordSubprocessStartupComplete(String taskId) {
        SubprocessTimingStat stat = subprocessTimings.get(taskId);
        if (stat != null) {
            stat.startupCompleteNanos = System.nanoTime();
            stat.startupDurationMs = (stat.startupCompleteNanos - stat.startTimeNanos) / 1_000_000.0;
            log.debug("Subprocess {} startup complete in {:.2f}ms", taskId, stat.startupDurationMs);
        }
    }

    /**
     * Record when model loading starts in the subprocess.
     *
     * @param taskId The task identifier
     * @param modelId The model being loaded
     */
    public void recordModelLoadStart(String taskId, String modelId) {
        SubprocessTimingStat stat = subprocessTimings.get(taskId);
        if (stat != null) {
            stat.modelId = modelId;
            stat.modelLoadStartNanos = System.nanoTime();
        }
    }

    /**
     * Record when model loading completes.
     *
     * @param taskId The task identifier
     */
    public void recordModelLoadComplete(String taskId) {
        SubprocessTimingStat stat = subprocessTimings.get(taskId);
        if (stat != null && stat.modelLoadStartNanos > 0) {
            stat.modelLoadCompleteNanos = System.nanoTime();
            stat.modelLoadDurationMs = (stat.modelLoadCompleteNanos - stat.modelLoadStartNanos) / 1_000_000.0;
            log.debug("Subprocess {} model load complete in {:.2f}ms", taskId, stat.modelLoadDurationMs);
        }
    }

    /**
     * Record IPC message send timing.
     *
     * @param taskId The task identifier
     * @param messageType The type of message
     * @param durationNanos Time taken to serialize and send
     */
    public void recordIpcSend(String taskId, String messageType, long durationNanos) {
        SubprocessTimingStat stat = subprocessTimings.get(taskId);
        if (stat != null) {
            stat.ipcSendCount++;
            stat.ipcSendTotalNanos += durationNanos;
        }
    }

    /**
     * Record IPC message receive timing.
     *
     * @param taskId The task identifier
     * @param messageType The type of message
     * @param durationNanos Time taken to receive and deserialize
     */
    public void recordIpcReceive(String taskId, String messageType, long durationNanos) {
        SubprocessTimingStat stat = subprocessTimings.get(taskId);
        if (stat != null) {
            stat.ipcReceiveCount++;
            stat.ipcReceiveTotalNanos += durationNanos;
        }
    }

    /**
     * Record subprocess completion and finalize timing stats.
     *
     * @param taskId The task identifier
     * @param success Whether the subprocess completed successfully
     */
    public void recordSubprocessComplete(String taskId, boolean success) {
        SubprocessTimingStat stat = subprocessTimings.remove(taskId);
        if (stat != null) {
            stat.endTimeMs = System.currentTimeMillis();
            stat.endTimeNanos = System.nanoTime();
            stat.totalDurationMs = (stat.endTimeNanos - stat.startTimeNanos) / 1_000_000.0;
            stat.success = success;

            // Calculate IPC overhead
            stat.ipcOverheadMs = (stat.ipcSendTotalNanos + stat.ipcReceiveTotalNanos) / 1_000_000.0;

            // Calculate total subprocess overhead (startup + model load + IPC)
            stat.totalOverheadMs = stat.startupDurationMs + stat.modelLoadDurationMs + stat.ipcOverheadMs;
            stat.overheadPercent = stat.totalDurationMs > 0 ? (stat.totalOverheadMs / stat.totalDurationMs) * 100.0 : 0;

            // Add to history
            synchronized (subprocessTimingHistory) {
                subprocessTimingHistory.add(stat);
                while (subprocessTimingHistory.size() > MAX_SUBPROCESS_HISTORY) {
                    subprocessTimingHistory.remove(0);
                }
            }

            log.info("Subprocess {} completed: total={:.2f}ms, overhead={:.2f}ms ({:.1f}%), startup={:.2f}ms, modelLoad={:.2f}ms, ipc={:.2f}ms",
                    taskId, stat.totalDurationMs, stat.totalOverheadMs, stat.overheadPercent,
                    stat.startupDurationMs, stat.modelLoadDurationMs, stat.ipcOverheadMs);
        }
    }

    /**
     * Get current active subprocess timings.
     */
    public Map<String, Object> getActiveSubprocessTimings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCount", subprocessTimings.size());

        List<Map<String, Object>> activeList = new ArrayList<>();
        for (SubprocessTimingStat stat : subprocessTimings.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("taskId", stat.taskId);
            entry.put("subprocessType", stat.subprocessType);
            entry.put("modelId", stat.modelId);
            entry.put("startTimeMs", stat.startTimeMs);
            entry.put("elapsedMs", (System.nanoTime() - stat.startTimeNanos) / 1_000_000.0);
            entry.put("startupDurationMs", stat.startupDurationMs);
            entry.put("modelLoadDurationMs", stat.modelLoadDurationMs);
            entry.put("ipcSendCount", stat.ipcSendCount);
            entry.put("ipcReceiveCount", stat.ipcReceiveCount);
            activeList.add(entry);
        }
        result.put("active", activeList);
        return result;
    }

    /**
     * Get subprocess timing history.
     */
    public Map<String, Object> getSubprocessTimingHistory(int limit) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<SubprocessTimingStat> history;
        synchronized (subprocessTimingHistory) {
            int start = Math.max(0, subprocessTimingHistory.size() - limit);
            history = new ArrayList<>(subprocessTimingHistory.subList(start, subprocessTimingHistory.size()));
        }

        // Reverse to show most recent first
        Collections.reverse(history);

        result.put("count", history.size());
        result.put("history", history);

        // Calculate aggregate statistics
        if (!history.isEmpty()) {
            double totalStartup = 0, totalModelLoad = 0, totalIpc = 0, totalOverhead = 0, totalDuration = 0;
            int successCount = 0;

            for (SubprocessTimingStat stat : history) {
                totalStartup += stat.startupDurationMs;
                totalModelLoad += stat.modelLoadDurationMs;
                totalIpc += stat.ipcOverheadMs;
                totalOverhead += stat.totalOverheadMs;
                totalDuration += stat.totalDurationMs;
                if (stat.success) successCount++;
            }

            int count = history.size();
            Map<String, Object> aggregates = new LinkedHashMap<>();
            aggregates.put("avgStartupMs", totalStartup / count);
            aggregates.put("avgModelLoadMs", totalModelLoad / count);
            aggregates.put("avgIpcOverheadMs", totalIpc / count);
            aggregates.put("avgTotalOverheadMs", totalOverhead / count);
            aggregates.put("avgTotalDurationMs", totalDuration / count);
            aggregates.put("avgOverheadPercent", totalDuration > 0 ? (totalOverhead / totalDuration) * 100.0 : 0);
            aggregates.put("successRate", (successCount * 100.0) / count);
            result.put("aggregates", aggregates);
        }

        return result;
    }

    /**
     * Clear subprocess timing history.
     */
    public void clearSubprocessTimingHistory() {
        synchronized (subprocessTimingHistory) {
            subprocessTimingHistory.clear();
        }
        log.info("Subprocess timing history cleared");
    }

    /**
     * Data class for subprocess timing statistics.
     */
    public static class SubprocessTimingStat {
        // Identification
        public String taskId;
        public String subprocessType;
        public String modelId;

        // Timing points (in nanos for precision)
        public long startTimeMs;
        public long startTimeNanos;
        public long startupCompleteNanos;
        public long modelLoadStartNanos;
        public long modelLoadCompleteNanos;
        public long endTimeMs;
        public long endTimeNanos;

        // Calculated durations (in ms)
        public double startupDurationMs;
        public double modelLoadDurationMs;
        public double totalDurationMs;
        public double ipcOverheadMs;
        public double totalOverheadMs;
        public double overheadPercent;

        // IPC tracking
        public long ipcSendCount;
        public long ipcSendTotalNanos;
        public long ipcReceiveCount;
        public long ipcReceiveTotalNanos;

        // Result
        public boolean success;

        // Getters for JSON serialization
        public String getTaskId() { return taskId; }
        public String getSubprocessType() { return subprocessType; }
        public String getModelId() { return modelId; }
        public long getStartTimeMs() { return startTimeMs; }
        public long getEndTimeMs() { return endTimeMs; }
        public double getStartupDurationMs() { return startupDurationMs; }
        public double getModelLoadDurationMs() { return modelLoadDurationMs; }
        public double getTotalDurationMs() { return totalDurationMs; }
        public double getIpcOverheadMs() { return ipcOverheadMs; }
        public double getTotalOverheadMs() { return totalOverheadMs; }
        public double getOverheadPercent() { return overheadPercent; }
        public long getIpcSendCount() { return ipcSendCount; }
        public long getIpcReceiveCount() { return ipcReceiveCount; }
        public boolean isSuccess() { return success; }
    }
}
