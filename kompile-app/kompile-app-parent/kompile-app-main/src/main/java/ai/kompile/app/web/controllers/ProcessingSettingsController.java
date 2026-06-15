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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.services.AdaptiveBatchingService;
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.PipelineConfigService;
import ai.kompile.app.services.audit.AdaptiveAuditEvent;
import ai.kompile.app.services.audit.AdaptiveAuditService;
import ai.kompile.app.services.pipeline.PipelineSettings;
import ai.kompile.app.web.dto.AdaptivePerformanceConfigDto;
import ai.kompile.app.web.dto.PipelineConfigDto;
import ai.kompile.app.web.dto.ProcessingSettingsRequest;
import ai.kompile.app.web.dto.ProcessingSettingsResponse;
import ai.kompile.app.web.dto.ProcessingSettingsResponse.MemoryStatus;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for managing processing settings and monitoring system resources.
 */
@RestController
@RequestMapping("/api/processing")
@CrossOrigin(origins = "*")
public class ProcessingSettingsController {

    private static final Logger logger = LoggerFactory.getLogger(ProcessingSettingsController.class);

    private final IngestConfiguration ingestConfiguration;
    private final DocumentIngestService documentIngestService;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final AdaptiveBatchingService adaptiveBatchingService;
    private final AdaptiveAuditService auditService;
    private final PipelineConfigService pipelineConfigService;
    private final AnseriniEmbeddingModelImpl embeddingModel;

    @Autowired
    public ProcessingSettingsController(
            @Autowired(required = false) IngestConfiguration ingestConfiguration,
            @Autowired(required = false) DocumentIngestService documentIngestService,
            @Autowired(required = false) @Qualifier("taskExecutor") TaskExecutor taskExecutor,
            @Autowired(required = false) AdaptiveBatchingService adaptiveBatchingService,
            @Autowired(required = false) AdaptiveAuditService auditService,
            @Autowired(required = false) PipelineConfigService pipelineConfigService,
            @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel
    ) {
        this.ingestConfiguration = ingestConfiguration;
        this.documentIngestService = documentIngestService;
        this.taskExecutor = taskExecutor instanceof ThreadPoolTaskExecutor ? (ThreadPoolTaskExecutor) taskExecutor : null;
        this.adaptiveBatchingService = adaptiveBatchingService;
        this.auditService = auditService;
        this.pipelineConfigService = pipelineConfigService;
        this.embeddingModel = embeddingModel;

        if (ingestConfiguration == null) {
            logger.warn("ProcessingSettingsController: IngestConfiguration is not available");
        }
        if (documentIngestService == null) {
            logger.warn("ProcessingSettingsController: DocumentIngestService is not available");
        }
        if (taskExecutor == null) {
            logger.warn("ProcessingSettingsController: TaskExecutor is not available");
        }
    }

    /**
     * Get current processing settings and system status.
     */
    @GetMapping("/settings")
    public ResponseEntity<ProcessingSettingsResponse> getSettings() {
        if (ingestConfiguration == null) {
            return ResponseEntity.status(503).build();
        }

        IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();

        ProcessingSettingsResponse response = new ProcessingSettingsResponse(
                ingestConfiguration.getMaxConcurrentJobs(),
                ingestConfiguration.getActiveJobCount(),
                getQueuedJobCount(),
                ingestConfiguration.canAcceptNewJob(),
                ingestConfiguration.getIndexBatchSize(),
                ingestConfiguration.getMinBatchSize(),
                ingestConfiguration.getMaxBatchSize(),
                ingestConfiguration.isAdaptiveBatchSize(),
                ingestConfiguration.getMemoryThresholdPercent(),
                ingestConfiguration.getMemoryCriticalPercent(),
                MemoryStatus.fromMemoryInfo(
                        memInfo.maxBytes(),
                        memInfo.usedBytes(),
                        memInfo.usagePercent(),
                        memInfo.thresholdExceeded(),
                        memInfo.criticalExceeded()
                ),
                ingestConfiguration.getCorePoolSize(),
                ingestConfiguration.getMaxPoolSize(),
                taskExecutor != null ? taskExecutor.getActiveCount() : 0,
                taskExecutor != null ? taskExecutor.getThreadPoolExecutor().getQueue().size() : 0
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Update processing settings.
     */
    @PutMapping("/settings")
    public ResponseEntity<ProcessingSettingsResponse> updateSettings(@RequestBody ProcessingSettingsRequest request) {
        if (ingestConfiguration == null) {
            return ResponseEntity.status(503).build();
        }

        logger.info("Updating processing settings: {}", request);

        if (request.maxConcurrentJobs() != null) {
            ingestConfiguration.setMaxConcurrentJobs(request.maxConcurrentJobs());
        }

        if (request.indexBatchSize() != null) {
            ingestConfiguration.setIndexBatchSize(request.indexBatchSize());
        }

        if (request.memoryThresholdPercent() != null) {
            ingestConfiguration.setMemoryThresholdPercent(request.memoryThresholdPercent());
        }

        if (request.adaptiveBatchSize() != null) {
            ingestConfiguration.setAdaptiveBatchSize(request.adaptiveBatchSize());
        }

        return getSettings();
    }

    /**
     * Get current memory status only.
     */
    @GetMapping("/memory")
    public ResponseEntity<MemoryStatus> getMemoryStatus() {
        if (ingestConfiguration == null) {
            // Return basic memory info from Runtime if IngestConfiguration is not available
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double usagePercent = (usedMemory * 100.0) / maxMemory;

            MemoryStatus status = MemoryStatus.fromMemoryInfo(
                    maxMemory,
                    usedMemory,
                    usagePercent,
                    false,
                    false
            );
            return ResponseEntity.ok(status);
        }

        IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();

        MemoryStatus status = MemoryStatus.fromMemoryInfo(
                memInfo.maxBytes(),
                memInfo.usedBytes(),
                memInfo.usagePercent(),
                memInfo.thresholdExceeded(),
                memInfo.criticalExceeded()
        );

        return ResponseEntity.ok(status);
    }

    /**
     * Trigger garbage collection (for memory management).
     * Note: This is a hint to the JVM and may not immediately free memory.
     */
    @PostMapping("/gc")
    public ResponseEntity<Map<String, Object>> triggerGC() {
        Runtime runtime = Runtime.getRuntime();
        long beforeUsed = runtime.totalMemory() - runtime.freeMemory();
        long beforeUsedMB = beforeUsed / (1024 * 1024);

        System.gc();

        // Wait briefly for GC to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long afterUsed = runtime.totalMemory() - runtime.freeMemory();
        long afterUsedMB = afterUsed / (1024 * 1024);
        long freedMB = beforeUsedMB - afterUsedMB;
        double usagePercent = (afterUsed * 100.0) / runtime.maxMemory();

        return ResponseEntity.ok(Map.of(
                "message", "Garbage collection requested",
                "beforeUsageMB", beforeUsedMB,
                "afterUsageMB", afterUsedMB,
                "freedMB", Math.max(0, freedMB),
                "currentUsagePercent", usagePercent
        ));
    }

    /**
     * Get active job statistics.
     */
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> getJobStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeJobs", ingestConfiguration != null ? ingestConfiguration.getActiveJobCount() : 0);
        stats.put("maxConcurrentJobs", ingestConfiguration != null ? ingestConfiguration.getMaxConcurrentJobs() : 0);
        stats.put("queuedJobs", getQueuedJobCount());
        stats.put("canAcceptNewJob", ingestConfiguration != null && ingestConfiguration.canAcceptNewJob());
        stats.put("poolActiveThreads", taskExecutor != null ? taskExecutor.getActiveCount() : 0);
        stats.put("poolCoreSize", taskExecutor != null ? taskExecutor.getCorePoolSize() : 0);
        stats.put("poolMaxSize", taskExecutor != null ? taskExecutor.getMaxPoolSize() : 0);
        stats.put("poolQueueSize", getQueuedJobCount());
        return ResponseEntity.ok(stats);
    }

    private int getQueuedJobCount() {
        return taskExecutor != null ? taskExecutor.getThreadPoolExecutor().getQueue().size() : 0;
    }

    // ========== PIPELINE CONFIGURATION ENDPOINTS ==========

    /**
     * Get the current pipeline configuration (adaptive based on system resources).
     */
    @GetMapping("/pipeline-config")
    public ResponseEntity<Map<String, Object>> getPipelineConfig() {
        PipelineSettings settings = PipelineSettings.adaptive();

        Map<String, Object> config = new LinkedHashMap<>();

        // Extraction settings
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("preferredLoader", settings.preferredLoader());
        extraction.put("autoDetectLoader", settings.autoDetectLoader());
        extraction.put("threads", settings.extractionThreads());
        config.put("extraction", extraction);

        // Tokenization settings
        Map<String, Object> tokenization = new LinkedHashMap<>();
        tokenization.put("enabled", settings.enablePreTokenization());
        tokenization.put("model", settings.tokenizerModel());
        tokenization.put("maxTokenLength", settings.maxTokenLength());
        tokenization.put("threads", settings.tokenizationThreads());
        config.put("tokenization", tokenization);

        // Chunking settings
        Map<String, Object> chunking = new LinkedHashMap<>();
        chunking.put("type", settings.chunkerType());
        chunking.put("chunkSize", settings.chunkSize());
        chunking.put("chunkOverlap", settings.chunkOverlap());
        chunking.put("preserveParagraphs", settings.preserveParagraphs());
        chunking.put("threads", settings.chunkingThreads());
        config.put("chunking", chunking);

        // Embedding settings
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("batchSize", settings.embeddingBatchSize());
        embedding.put("threads", settings.embeddingThreads());
        config.put("embedding", embedding);

        // Indexing settings
        Map<String, Object> indexing = new LinkedHashMap<>();
        indexing.put("batchSize", settings.indexBatchSize());
        indexing.put("threads", 1); // Always 1 for Lucene
        config.put("indexing", indexing);

        // Queue settings
        Map<String, Object> queues = new LinkedHashMap<>();
        queues.put("capacity", settings.queueCapacity());
        queues.put("backpressureEnabled", settings.enableBackpressure());
        config.put("queues", queues);

        // System info
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("availableCores", Runtime.getRuntime().availableProcessors());
        system.put("maxMemoryMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        system.put("usedMemoryMB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
        config.put("system", system);

        return ResponseEntity.ok(config);
    }

    /**
     * Get available pipeline presets.
     */
    @GetMapping("/pipeline-presets")
    public ResponseEntity<Map<String, Object>> getPipelinePresets() {
        Map<String, Object> presets = new LinkedHashMap<>();

        // Adaptive preset (recommended)
        PipelineSettings adaptive = PipelineSettings.adaptive();
        presets.put("adaptive", Map.of(
                "name", "Adaptive",
                "description", "Auto-configured based on system resources (recommended)",
                "extractionThreads", adaptive.extractionThreads(),
                "tokenizationThreads", adaptive.tokenizationThreads(),
                "chunkingThreads", adaptive.chunkingThreads(),
                "embeddingThreads", adaptive.embeddingThreads(),
                "embeddingBatchSize", adaptive.embeddingBatchSize(),
                "indexBatchSize", adaptive.indexBatchSize()
        ));

        // Memory-optimized preset
        PipelineSettings memoryOpt = PipelineSettings.memoryOptimized();
        presets.put("memoryOptimized", Map.of(
                "name", "Memory Optimized",
                "description", "Minimal memory usage, suitable for constrained environments",
                "extractionThreads", memoryOpt.extractionThreads(),
                "tokenizationThreads", memoryOpt.tokenizationThreads(),
                "chunkingThreads", memoryOpt.chunkingThreads(),
                "embeddingThreads", memoryOpt.embeddingThreads(),
                "embeddingBatchSize", memoryOpt.embeddingBatchSize(),
                "indexBatchSize", memoryOpt.indexBatchSize()
        ));

        // High-throughput preset
        PipelineSettings highThroughput = PipelineSettings.highThroughput();
        presets.put("highThroughput", Map.of(
                "name", "High Throughput",
                "description", "Maximum throughput for systems with adequate resources",
                "extractionThreads", highThroughput.extractionThreads(),
                "tokenizationThreads", highThroughput.tokenizationThreads(),
                "chunkingThreads", highThroughput.chunkingThreads(),
                "embeddingThreads", highThroughput.embeddingThreads(),
                "embeddingBatchSize", highThroughput.embeddingBatchSize(),
                "indexBatchSize", highThroughput.indexBatchSize()
        ));

        return ResponseEntity.ok(presets);
    }

    /**
     * Get current pipeline stage metrics and bottleneck analysis.
     */
    @GetMapping("/stage-metrics")
    public ResponseEntity<Map<String, Object>> getStageMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (documentIngestService == null) {
            metrics.put("status", "unavailable");
            metrics.put("message", "Document ingest service not available");
        } else {
            // Get real diagnostics from active pipelines
            Map<String, Object> diagnostics = documentIngestService.getActivePipelineDiagnostics();
            Object rawCount = diagnostics.get("activePipelineCount");
            int activePipelineCount = (rawCount instanceof Number) ? ((Number) rawCount).intValue() : 0;

            if (activePipelineCount == 0) {
                metrics.put("status", "idle");
                metrics.put("message", "No pipeline currently running");
            } else {
                metrics.put("status", "processing");
                metrics.put("message", activePipelineCount + " pipeline(s) running");
                metrics.put("activePipelines", diagnostics.get("pipelines"));
            }
        }

        // Stage placeholders for detailed metrics (to be enhanced)
        metrics.put("extraction", createEmptyStageMetrics());
        metrics.put("tokenization", createEmptyStageMetrics());
        metrics.put("chunking", createEmptyStageMetrics());
        metrics.put("embedding", createEmptyStageMetrics());
        metrics.put("indexing", createEmptyStageMetrics());

        return ResponseEntity.ok(metrics);
    }

    /**
     * Get detailed pipeline diagnostics with bottleneck analysis.
     */
    @GetMapping("/pipeline-diagnostics")
    public ResponseEntity<Map<String, Object>> getPipelineDiagnostics() {
        if (documentIngestService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Document ingest service not available"));
        }
        return ResponseEntity.ok(documentIngestService.getActivePipelineDiagnostics());
    }

    private Map<String, Object> createEmptyStageMetrics() {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("itemsProcessed", 0);
        stage.put("itemsFailed", 0);
        stage.put("throughput", 0.0);
        stage.put("avgProcessingTimeMs", 0.0);
        stage.put("queueSize", 0);
        return stage;
    }

    // ========== PIPELINE SETTINGS ENDPOINTS (USER-CONFIGURABLE) ==========

    /**
     * Get current pipeline settings.
     * These are the user-configurable settings that persist across restarts.
     */
    @GetMapping("/pipeline-settings")
    public ResponseEntity<PipelineConfigDto> getPipelineSettings() {
        if (pipelineConfigService == null) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(pipelineConfigService.getConfigDto());
    }

    /**
     * Update pipeline settings.
     * Only non-null values in the request are applied.
     */
    @PutMapping("/pipeline-settings")
    public ResponseEntity<PipelineConfigDto> updatePipelineSettings(@RequestBody PipelineConfigDto request) {
        if (pipelineConfigService == null) {
            return ResponseEntity.status(503).build();
        }
        logger.info("Updating pipeline settings: {}", request);
        return ResponseEntity.ok(pipelineConfigService.updateConfig(request));
    }

    /**
     * Apply a pipeline preset.
     * Valid presets: defaults, highThroughput, lowMemory, keywordOnly
     */
    @PostMapping("/pipeline-settings/preset/{preset}")
    public ResponseEntity<PipelineConfigDto> applyPipelinePreset(@PathVariable String preset) {
        if (pipelineConfigService == null) {
            return ResponseEntity.status(503).build();
        }
        logger.info("Applying pipeline preset: {}", preset.replaceAll("[\\r\\n]", "_"));
        return ResponseEntity.ok(pipelineConfigService.applyPreset(preset));
    }

    /**
     * Reset pipeline settings to defaults.
     */
    @PostMapping("/pipeline-settings/reset")
    public ResponseEntity<PipelineConfigDto> resetPipelineSettings() {
        if (pipelineConfigService == null) {
            return ResponseEntity.status(503).build();
        }
        logger.info("Resetting pipeline settings to defaults");
        return ResponseEntity.ok(pipelineConfigService.resetToDefaults());
    }

    /**
     * Get available pipeline presets with their descriptions.
     */
    @GetMapping("/pipeline-settings/presets")
    public ResponseEntity<Map<String, Object>> getAvailablePipelinePresets() {
        Map<String, Object> presets = new LinkedHashMap<>();

        // Defaults preset
        presets.put("defaults", Map.of(
                "name", "Default",
                "description", "Balanced settings suitable for most use cases",
                "embeddingTimeoutSeconds", 300,
                "queueCapacity", 1000,
                "embeddingThreads", 1,
                "chunkingThreads", Math.min(Runtime.getRuntime().availableProcessors() / 2, 16),
                "indexingThreads", 4
        ));

        // High throughput preset
        presets.put("highThroughput", Map.of(
                "name", "High Throughput",
                "description", "Optimized for maximum processing speed on systems with adequate memory",
                "embeddingTimeoutSeconds", 300,
                "queueCapacity", 2000,
                "embeddingThreads", 1,
                "chunkingThreads", Math.min(Runtime.getRuntime().availableProcessors(), 16),
                "indexingThreads", Math.min(Runtime.getRuntime().availableProcessors() / 2, 8)
        ));

        // Low memory preset
        presets.put("lowMemory", Map.of(
                "name", "Low Memory",
                "description", "Minimal resource usage for memory-constrained systems",
                "embeddingTimeoutSeconds", 600,
                "queueCapacity", 250,
                "embeddingThreads", 1,
                "chunkingThreads", 2,
                "indexingThreads", 2
        ));

        // Keyword only preset
        presets.put("keywordOnly", Map.of(
                "name", "Keyword Only",
                "description", "Skip embedding generation (keyword search only)",
                "embeddingTimeoutSeconds", 0,
                "queueCapacity", 5000,
                "embeddingThreads", 0,
                "chunkingThreads", Math.min(Runtime.getRuntime().availableProcessors(), 16),
                "indexingThreads", Math.min(Runtime.getRuntime().availableProcessors() / 2, 8),
                "skipEmbedding", true
        ));

        return ResponseEntity.ok(presets);
    }

    // ========== ADAPTIVE PERFORMANCE ENDPOINTS ==========

    /**
     * Get current adaptive performance configuration and status.
     */
    @GetMapping("/adaptive")
    public ResponseEntity<Map<String, Object>> getAdaptiveStatus() {
        if (adaptiveBatchingService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Adaptive batching service not available"));
        }
        return ResponseEntity.ok(adaptiveBatchingService.getStatus());
    }

    /**
     * Configure adaptive performance mode.
     * Accepts a configuration object to enable/disable and configure adaptive batching.
     */
    @PutMapping("/adaptive")
    public ResponseEntity<Map<String, Object>> configureAdaptive(@RequestBody AdaptivePerformanceConfigDto config) {
        if (adaptiveBatchingService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Adaptive batching service not available"));
        }
        logger.info("Configuring adaptive performance: enabled={}, preset={}",
                config.enabled(), config.preset());

        adaptiveBatchingService.configure(config);

        return ResponseEntity.ok(adaptiveBatchingService.getStatus());
    }

    /**
     * Apply a preset adaptive configuration.
     * Valid presets: conservative, balanced, aggressive
     */
    @PostMapping("/adaptive/preset/{preset}")
    public ResponseEntity<Map<String, Object>> applyAdaptivePreset(@PathVariable String preset) {
        if (adaptiveBatchingService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Adaptive batching service not available"));
        }
        logger.info("Applying adaptive preset: {}", preset.replaceAll("[\\r\\n]", "_"));

        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.fromPreset(preset);
        adaptiveBatchingService.configure(config);

        return ResponseEntity.ok(adaptiveBatchingService.getStatus());
    }

    /**
     * Start adaptive monitoring (if not already running).
     */
    @PostMapping("/adaptive/start")
    public ResponseEntity<Map<String, Object>> startAdaptiveMonitoring() {
        if (adaptiveBatchingService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Adaptive batching service not available"));
        }
        adaptiveBatchingService.startMonitoring();
        return ResponseEntity.ok(adaptiveBatchingService.getStatus());
    }

    /**
     * Stop adaptive monitoring.
     */
    @PostMapping("/adaptive/stop")
    public ResponseEntity<Map<String, Object>> stopAdaptiveMonitoring() {
        if (adaptiveBatchingService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Adaptive batching service not available"));
        }
        adaptiveBatchingService.stopMonitoring();
        return ResponseEntity.ok(adaptiveBatchingService.getStatus());
    }

    /**
     * Get available adaptive presets for the UI.
     */
    @GetMapping("/adaptive/presets")
    public ResponseEntity<Map<String, Object>> getAdaptivePresets() {
        Map<String, Object> presets = new LinkedHashMap<>();

        // Conservative preset
        AdaptivePerformanceConfigDto conservative = AdaptivePerformanceConfigDto.conservative();
        presets.put("conservative", Map.of(
                "name", "Conservative",
                "description", "Lower thresholds, smaller batches - best for low-memory systems",
                "targetMemoryPercent", conservative.targetMemoryPercent(),
                "criticalMemoryPercent", conservative.criticalMemoryPercent(),
                "minEmbeddingBatch", conservative.minEmbeddingBatch(),
                "maxEmbeddingBatch", conservative.maxEmbeddingBatch(),
                "minIndexBatch", conservative.minIndexBatch(),
                "maxIndexBatch", conservative.maxIndexBatch()
        ));

        // Balanced preset
        AdaptivePerformanceConfigDto balanced = AdaptivePerformanceConfigDto.balanced();
        presets.put("balanced", Map.of(
                "name", "Balanced",
                "description", "Moderate thresholds - good for most systems (recommended)",
                "targetMemoryPercent", balanced.targetMemoryPercent(),
                "criticalMemoryPercent", balanced.criticalMemoryPercent(),
                "minEmbeddingBatch", balanced.minEmbeddingBatch(),
                "maxEmbeddingBatch", balanced.maxEmbeddingBatch(),
                "minIndexBatch", balanced.minIndexBatch(),
                "maxIndexBatch", balanced.maxIndexBatch()
        ));

        // Aggressive preset
        AdaptivePerformanceConfigDto aggressive = AdaptivePerformanceConfigDto.aggressive();
        presets.put("aggressive", Map.of(
                "name", "Aggressive",
                "description", "Higher thresholds, larger batches - maximum throughput",
                "targetMemoryPercent", aggressive.targetMemoryPercent(),
                "criticalMemoryPercent", aggressive.criticalMemoryPercent(),
                "minEmbeddingBatch", aggressive.minEmbeddingBatch(),
                "maxEmbeddingBatch", aggressive.maxEmbeddingBatch(),
                "minIndexBatch", aggressive.minIndexBatch(),
                "maxIndexBatch", aggressive.maxIndexBatch()
        ));

        // System info for context
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("presets", presets);
        response.put("systemInfo", Map.of(
                "maxMemoryMB", runtime.maxMemory() / (1024 * 1024),
                "availableCores", runtime.availableProcessors(),
                "recommendedPreset", getRecommendedPreset(runtime.maxMemory())
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Recommends a preset based on available memory.
     */
    private String getRecommendedPreset(long maxMemory) {
        long maxMemoryMB = maxMemory / (1024 * 1024);

        if (maxMemoryMB < 4096) {
            return "conservative"; // Less than 4GB
        } else if (maxMemoryMB < 8192) {
            return "balanced"; // 4-8GB
        } else {
            return "aggressive"; // 8GB+
        }
    }

    // ========== AUDIT ENDPOINTS ==========

    /**
     * Get all audit events.
     */
    @GetMapping("/adaptive/audit")
    public ResponseEntity<Map<String, Object>> getAuditEvents(
            @RequestParam(defaultValue = "100") int limit) {
        limit = Math.min(Math.max(limit, 1), 10_000);
        if (auditService == null) {
            return ResponseEntity.ok(Map.of(
                    "events", List.of(),
                    "totalCount", 0,
                    "auditEnabled", false,
                    "message", "Audit service not available"
            ));
        }

        List<AdaptiveAuditEvent> events = auditService.getRecentEvents(limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("events", events.stream()
                .map(this::eventToMap)
                .collect(Collectors.toList()));
        response.put("totalCount", auditService.getEventCount());
        response.put("auditEnabled", auditService.isEnabled());

        return ResponseEntity.ok(response);
    }

    /**
     * Get audit events by type.
     */
    @GetMapping("/adaptive/audit/type/{eventType}")
    public ResponseEntity<List<Map<String, Object>>> getAuditEventsByType(
            @PathVariable String eventType) {
        if (auditService == null) {
            return ResponseEntity.ok(List.of());
        }

        try {
            AdaptiveAuditEvent.EventType type = AdaptiveAuditEvent.EventType.valueOf(eventType.toUpperCase());
            List<AdaptiveAuditEvent> events = auditService.getEventsByType(type);

            return ResponseEntity.ok(events.stream()
                    .map(this::eventToMap)
                    .collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get audit events from the last N hours.
     */
    @GetMapping("/adaptive/audit/recent/{hours}")
    public ResponseEntity<List<Map<String, Object>>> getRecentAuditEvents(
            @PathVariable int hours) {
        if (auditService == null) {
            return ResponseEntity.ok(List.of());
        }

        hours = Math.min(Math.max(hours, 1), 720);
        List<AdaptiveAuditEvent> events = auditService.getEventsSince(Duration.ofHours(hours));

        return ResponseEntity.ok(events.stream()
                .map(this::eventToMap)
                .collect(Collectors.toList()));
    }

    /**
     * Get batch adjustment audit events only.
     */
    @GetMapping("/adaptive/audit/adjustments")
    public ResponseEntity<List<Map<String, Object>>> getBatchAdjustmentAuditEvents() {
        if (auditService == null) {
            return ResponseEntity.ok(List.of());
        }

        List<AdaptiveAuditEvent> events = auditService.getBatchAdjustmentEvents();

        return ResponseEntity.ok(events.stream()
                .map(this::eventToMap)
                .collect(Collectors.toList()));
    }

    /**
     * Get audit statistics and summary.
     */
    @GetMapping("/adaptive/audit/statistics")
    public ResponseEntity<Map<String, Object>> getAuditStatistics() {
        if (auditService == null) {
            return ResponseEntity.status(503).body(Map.of("status", "unavailable", "message", "Audit service not available"));
        }
        return ResponseEntity.ok(auditService.getStatistics());
    }

    /**
     * Clear all audit events.
     */
    @DeleteMapping("/adaptive/audit")
    public ResponseEntity<Map<String, Object>> clearAuditEvents() {
        if (auditService == null) {
            return ResponseEntity.ok(Map.of("message", "Audit service not available", "eventCount", 0));
        }

        auditService.clearEvents();
        return ResponseEntity.ok(Map.of(
                "message", "Audit events cleared",
                "eventCount", auditService.getEventCount()
        ));
    }

    /**
     * Cleanup old audit events based on retention policy.
     */
    @PostMapping("/adaptive/audit/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupAuditEvents() {
        if (auditService == null) {
            return ResponseEntity.ok(Map.of(
                    "message", "Audit service not available",
                    "eventsRemoved", 0,
                    "remainingEvents", 0
            ));
        }

        int removed = auditService.cleanupOldEvents();
        return ResponseEntity.ok(Map.of(
                "message", "Audit cleanup completed",
                "eventsRemoved", removed,
                "remainingEvents", auditService.getEventCount()
        ));
    }

    /**
     * Converts an audit event to a map for JSON serialization.
     */
    private Map<String, Object> eventToMap(AdaptiveAuditEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", event.eventId());
        map.put("eventType", event.eventType().name());
        map.put("timestamp", event.timestamp().toString());
        map.put("message", event.message());

        if (event.previousState() != null) {
            map.put("previousState", event.previousState());
        }
        if (event.newState() != null) {
            map.put("newState", event.newState());
        }
        if (event.memoryPercent() != null) {
            map.put("memoryPercent", event.memoryPercent());
        }
        if (event.previousEmbeddingBatch() != null) {
            map.put("previousEmbeddingBatch", event.previousEmbeddingBatch());
            map.put("newEmbeddingBatch", event.newEmbeddingBatch());
        }
        if (event.previousIndexBatch() != null) {
            map.put("previousIndexBatch", event.previousIndexBatch());
            map.put("newIndexBatch", event.newIndexBatch());
        }
        if (event.reason() != null) {
            map.put("reason", event.reason());
        }
        if (event.preset() != null) {
            map.put("preset", event.preset());
        }
        if (event.details() != null) {
            map.put("details", event.details());
        }

        return map;
    }

    // ========== GRAPH OPTIMIZATION ENDPOINTS ==========

    /**
     * Get graph optimization status for the current embedding model.
     */
    @GetMapping("/graph-optimization")
    public ResponseEntity<Map<String, Object>> getGraphOptimizationStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("available", false);
            response.put("message", "Embedding model not available");
            return ResponseEntity.ok(response);
        }

        response.put("available", true);
        response.put("embeddingModelLoaded", embeddingModel.isInitialized());
        response.put("currentModel", embeddingModel.getActiveModelId());
        response.put("description", "Pre-optimize and save the model graph for faster inference. " +
                "Optimizations include matmul+add fusion, constant folding, and dead code elimination.");
        response.put("note", "Optimization runs once and saves to a new model file. " +
                "Register the optimized model in the registry to use it.");

        return ResponseEntity.ok(response);
    }

    /**
     * Optimize the current model graph in-place, backing up the original.
     *
     * <p>This performs graph optimizations (matmul+add fusion, constant folding,
     * dead code elimination) and saves the result in-place, backing up the
     * original unoptimized model file for later restoration if needed.
     *
     * @param request Optional: modelId to optimize (defaults to currently loaded model)
     */
    @PostMapping("/graph-optimization/optimize-and-save")
    public ResponseEntity<Map<String, Object>> optimizeAndSaveModel(@RequestBody(required = false) Map<String, String> request) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("success", false);
            response.put("error", "Embedding model not available");
            return ResponseEntity.status(503).body(response);
        }

        if (!embeddingModel.isInitialized()) {
            response.put("success", false);
            response.put("error", "Embedding model not initialized");
            return ResponseEntity.status(503).body(response);
        }

        String modelId = embeddingModel.getActiveModelId();
        logger.info("Starting graph optimization for model: {}", modelId);

        try {
            // Get the encoder - in subprocess mode this returns null
            Object encoderObj = null;
            try {
                encoderObj = embeddingModel.getEncoder();
            } catch (Exception encoderEx) {
                logger.warn("getEncoder() threw an exception: {}", encoderEx.getMessage());
            }
            if (encoderObj == null || !(encoderObj instanceof SameDiffEncoder<?>)) {
                response.put("success", false);
                response.put("error", "Graph optimization not available - encoder runs in subprocess mode");
                return ResponseEntity.status(400).body(response);
            }
            SameDiffEncoder<?> encoder = (SameDiffEncoder<?>) encoderObj;

            // Save optimized model to separate directory
            String homeDir = System.getProperty("user.home");
            java.nio.file.Path modelsDir = java.nio.file.Paths.get(homeDir, ".kompile", "models", "optimized");
            java.nio.file.Files.createDirectories(modelsDir);
            java.nio.file.Path optimizedPath = modelsDir.resolve(modelId + "-optimized.fb");

            long startTime = System.currentTimeMillis();
            encoder.saveOptimized(optimizedPath);
            long elapsed = System.currentTimeMillis() - startTime;

            response.put("success", true);
            response.put("originalModel", modelId);
            response.put("optimizedModelPath", optimizedPath.toString());
            response.put("optimizationTimeMs", elapsed);
            response.put("message", String.format("Model optimized and saved in %dms.", elapsed));
            response.put("nextSteps", new String[]{
                    "1. Copy vocab.txt to the same directory as the optimized model",
                    "2. Register the optimized model in the model registry",
                    "3. Switch to model ID '" + modelId + "-optimized' to use it"
            });

            logger.info("Graph optimization complete for {}: saved to {} in {}ms",
                    modelId, optimizedPath, elapsed);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to optimize model {}", modelId, e);
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("modelId", modelId);
            return ResponseEntity.status(500).body(response);
        }
    }
}
