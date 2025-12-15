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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.services.audit.AdaptiveAuditService;
import ai.kompile.app.web.dto.AdaptivePerformanceConfigDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for adaptive batch size management during document ingestion.
 * Monitors memory usage and throughput to automatically adjust batch sizes.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Real-time memory monitoring</li>
 *   <li>Automatic batch size reduction under memory pressure</li>
 *   <li>Batch size recovery when memory is freed</li>
 *   <li>WebSocket broadcasts for UI updates</li>
 *   <li>Configurable thresholds and limits</li>
 * </ul>
 */
@Service
public class AdaptiveBatchingService {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveBatchingService.class);

    private final IngestConfiguration ingestConfiguration;
    private final SimpMessagingTemplate messagingTemplate;
    private final AdaptiveAuditService auditService;

    // Current configuration
    private volatile AdaptivePerformanceConfigDto currentConfig = AdaptivePerformanceConfigDto.defaultConfig();
    private volatile AdaptivePerformanceConfigDto previousConfig = null;

    // Monitoring state
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final AtomicLong lastAdjustmentTime = new AtomicLong(0);
    private final AtomicInteger adjustmentCount = new AtomicInteger(0);

    // Current batch sizes (may differ from config due to adaptive adjustments)
    private final AtomicInteger currentEmbeddingBatchSize = new AtomicInteger(64);
    private final AtomicInteger currentIndexBatchSize = new AtomicInteger(100);

    // Monitoring executor
    private ScheduledExecutorService monitorExecutor;

    // Metrics tracking
    private volatile BatchAdjustment lastAdjustment;

    @Autowired
    public AdaptiveBatchingService(
            IngestConfiguration ingestConfiguration,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            AdaptiveAuditService auditService
    ) {
        this.ingestConfiguration = ingestConfiguration;
        this.messagingTemplate = messagingTemplate;
        this.auditService = auditService;

        // Initialize with current config values
        currentEmbeddingBatchSize.set(ingestConfiguration.getEmbeddingTargetBatchSize());
        currentIndexBatchSize.set(ingestConfiguration.getIndexBatchSize());
    }

    /**
     * Configures and starts adaptive performance monitoring.
     */
    public synchronized void configure(AdaptivePerformanceConfigDto config) {
        boolean wasEnabled = previousConfig != null && previousConfig.enabled();
        boolean isNowEnabled = config.enabled();

        // Capture previous config for audit
        Map<String, Object> prevConfigMap = previousConfig != null ? configToMap(previousConfig) : null;
        Map<String, Object> newConfigMap = configToMap(config);

        this.previousConfig = this.currentConfig;
        this.currentConfig = config;

        // Apply memory thresholds to IngestConfiguration
        if (config.targetMemoryPercent() > 0) {
            ingestConfiguration.setMemoryThresholdPercent(config.targetMemoryPercent());
        }
        if (config.criticalMemoryPercent() > 0) {
            ingestConfiguration.setMemoryCriticalPercent(config.criticalMemoryPercent());
        }

        // Reset current batch sizes to config maximums
        currentEmbeddingBatchSize.set(config.maxEmbeddingBatch());
        currentIndexBatchSize.set(config.maxIndexBatch());

        // Update IngestConfiguration
        ingestConfiguration.setEmbeddingTargetBatchSize(config.maxEmbeddingBatch());
        ingestConfiguration.setIndexBatchSize(config.maxIndexBatch());
        ingestConfiguration.setAdaptiveBatchSize(config.enabled());

        logger.info("Adaptive batching configured: enabled={}, preset={}, target={}%, critical={}%",
                config.enabled(), config.preset(), config.targetMemoryPercent(), config.criticalMemoryPercent());

        // Audit: Mode state transition
        if (!wasEnabled && isNowEnabled) {
            auditService.logModeEnabled(config.preset(), newConfigMap);
        } else if (wasEnabled && !isNowEnabled) {
            auditService.logModeDisabled("User disabled adaptive mode");
        } else if (wasEnabled && isNowEnabled && prevConfigMap != null) {
            // Config changed while enabled
            auditService.logConfigUpdated(prevConfigMap, newConfigMap);
        }

        // Audit: Preset applied
        if (config.preset() != null && !config.preset().isEmpty()) {
            auditService.logPresetApplied(config.preset(), newConfigMap);
        }

        if (config.enabled()) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }

    /**
     * Converts config to a map for audit logging.
     */
    private Map<String, Object> configToMap(AdaptivePerformanceConfigDto config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.enabled());
        map.put("targetMemoryPercent", config.targetMemoryPercent());
        map.put("criticalMemoryPercent", config.criticalMemoryPercent());
        map.put("minEmbeddingBatch", config.minEmbeddingBatch());
        map.put("maxEmbeddingBatch", config.maxEmbeddingBatch());
        map.put("minIndexBatch", config.minIndexBatch());
        map.put("maxIndexBatch", config.maxIndexBatch());
        map.put("checkIntervalMs", config.checkIntervalMs());
        map.put("adjustmentCooldownMs", config.adjustmentCooldownMs());
        map.put("preset", config.preset());
        return map;
    }

    /**
     * Starts the monitoring loop.
     */
    public synchronized void startMonitoring() {
        if (monitoringActive.get()) {
            logger.debug("Monitoring already active");
            return;
        }

        if (!currentConfig.enabled()) {
            logger.info("Adaptive mode not enabled, skipping monitoring start");
            return;
        }

        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adaptive-batch-monitor");
            t.setDaemon(true);
            return t;
        });

        monitorExecutor.scheduleAtFixedRate(
                this::monitorAndAdjust,
                currentConfig.checkIntervalMs(),
                currentConfig.checkIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        monitoringActive.set(true);
        logger.info("Adaptive batch monitoring started (interval: {}ms)", currentConfig.checkIntervalMs());

        // Audit: Monitoring started
        auditService.logMonitoringStarted(
                currentConfig.checkIntervalMs(),
                currentConfig.adjustmentCooldownMs()
        );
    }

    /**
     * Stops the monitoring loop.
     */
    public synchronized void stopMonitoring() {
        if (!monitoringActive.get()) {
            return;
        }

        monitoringActive.set(false);

        if (monitorExecutor != null) {
            monitorExecutor.shutdown();
            try {
                if (!monitorExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    monitorExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            monitorExecutor = null;
        }

        logger.info("Adaptive batch monitoring stopped");

        // Audit: Monitoring stopped
        auditService.logMonitoringStopped("Monitoring deactivated");
    }

    /**
     * Main monitoring loop - evaluates memory and adjusts batch sizes.
     */
    private void monitorAndAdjust() {
        try {
            IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();
            double memoryPercent = memInfo.usagePercent();

            // Audit: Log memory threshold transitions
            auditService.logMemoryTransition(
                    memoryPercent,
                    currentConfig.targetMemoryPercent(),
                    currentConfig.criticalMemoryPercent()
            );

            // Check if we're in cooldown period
            long timeSinceLastAdjustment = System.currentTimeMillis() - lastAdjustmentTime.get();
            if (timeSinceLastAdjustment < currentConfig.adjustmentCooldownMs()) {
                logger.trace("In cooldown period, {}ms remaining",
                        currentConfig.adjustmentCooldownMs() - timeSinceLastAdjustment);
                return;
            }

            BatchAdjustment adjustment = null;

            if (memoryPercent >= currentConfig.criticalMemoryPercent()) {
                // Critical memory - reduce aggressively
                adjustment = reduceBatchSizes("CRITICAL", memoryPercent, 0.5);
            } else if (memoryPercent >= currentConfig.targetMemoryPercent()) {
                // Above target - reduce moderately
                double factor = 1.0 - ((memoryPercent - currentConfig.targetMemoryPercent()) /
                        (currentConfig.criticalMemoryPercent() - currentConfig.targetMemoryPercent())) * 0.3;
                adjustment = reduceBatchSizes("HIGH", memoryPercent, factor);
            } else if (memoryPercent < currentConfig.targetMemoryPercent() - 10) {
                // Memory is healthy - try to increase batch sizes
                adjustment = increaseBatchSizes("LOW", memoryPercent);
            }

            if (adjustment != null) {
                lastAdjustment = adjustment;
                lastAdjustmentTime.set(System.currentTimeMillis());
                adjustmentCount.incrementAndGet();
                broadcastAdjustment(adjustment);
            }

            // Always broadcast current status for UI monitoring
            broadcastStatus(memoryPercent);

        } catch (Exception e) {
            logger.warn("Error in adaptive monitoring: {}", e.getMessage());
        }
    }

    /**
     * Reduces batch sizes based on memory pressure.
     */
    private BatchAdjustment reduceBatchSizes(String reason, double memoryPercent, double factor) {
        int oldEmbeddingBatch = currentEmbeddingBatchSize.get();
        int oldIndexBatch = currentIndexBatchSize.get();

        int newEmbeddingBatch = Math.max(
                currentConfig.minEmbeddingBatch(),
                (int) (oldEmbeddingBatch * factor)
        );
        int newIndexBatch = Math.max(
                currentConfig.minIndexBatch(),
                (int) (oldIndexBatch * factor)
        );

        // Only adjust if there's a meaningful change
        if (newEmbeddingBatch == oldEmbeddingBatch && newIndexBatch == oldIndexBatch) {
            return null;
        }

        currentEmbeddingBatchSize.set(newEmbeddingBatch);
        currentIndexBatchSize.set(newIndexBatch);

        // Update IngestConfiguration
        ingestConfiguration.setEmbeddingTargetBatchSize(newEmbeddingBatch);
        ingestConfiguration.setIndexBatchSize(newIndexBatch);

        logger.info("Batch sizes REDUCED [{}]: memory={}%, embedding: {} -> {}, index: {} -> {}",
                reason, String.format("%.1f", memoryPercent),
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch);

        // Audit: Batch reduced
        auditService.logBatchReduced(
                reason + " memory pressure",
                memoryPercent,
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch
        );

        return new BatchAdjustment(
                reason,
                "DECREASE",
                memoryPercent,
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch,
                System.currentTimeMillis()
        );
    }

    /**
     * Increases batch sizes when memory is healthy.
     */
    private BatchAdjustment increaseBatchSizes(String reason, double memoryPercent) {
        int oldEmbeddingBatch = currentEmbeddingBatchSize.get();
        int oldIndexBatch = currentIndexBatchSize.get();

        // Already at max?
        if (oldEmbeddingBatch >= currentConfig.maxEmbeddingBatch() &&
                oldIndexBatch >= currentConfig.maxIndexBatch()) {
            return null;
        }

        // Increase by 25%
        int newEmbeddingBatch = Math.min(
                currentConfig.maxEmbeddingBatch(),
                (int) (oldEmbeddingBatch * 1.25)
        );
        int newIndexBatch = Math.min(
                currentConfig.maxIndexBatch(),
                (int) (oldIndexBatch * 1.25)
        );

        // Only adjust if there's a meaningful change
        if (newEmbeddingBatch == oldEmbeddingBatch && newIndexBatch == oldIndexBatch) {
            return null;
        }

        currentEmbeddingBatchSize.set(newEmbeddingBatch);
        currentIndexBatchSize.set(newIndexBatch);

        // Update IngestConfiguration
        ingestConfiguration.setEmbeddingTargetBatchSize(newEmbeddingBatch);
        ingestConfiguration.setIndexBatchSize(newIndexBatch);

        logger.info("Batch sizes INCREASED [{}]: memory={}%, embedding: {} -> {}, index: {} -> {}",
                reason, String.format("%.1f", memoryPercent),
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch);

        // Audit: Batch increased
        auditService.logBatchIncreased(
                reason + " memory available",
                memoryPercent,
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch
        );

        return new BatchAdjustment(
                reason,
                "INCREASE",
                memoryPercent,
                oldEmbeddingBatch, newEmbeddingBatch,
                oldIndexBatch, newIndexBatch,
                System.currentTimeMillis()
        );
    }

    /**
     * Broadcasts a batch adjustment event via WebSocket.
     */
    private void broadcastAdjustment(BatchAdjustment adjustment) {
        if (messagingTemplate == null) {
            return;
        }

        try {
            messagingTemplate.convertAndSend("/topic/adaptive/adjustment", Map.of(
                    "type", "BATCH_ADJUSTMENT",
                    "reason", adjustment.reason(),
                    "direction", adjustment.direction(),
                    "memoryPercent", adjustment.memoryPercent(),
                    "oldEmbeddingBatch", adjustment.oldEmbeddingBatch(),
                    "newEmbeddingBatch", adjustment.newEmbeddingBatch(),
                    "oldIndexBatch", adjustment.oldIndexBatch(),
                    "newIndexBatch", adjustment.newIndexBatch(),
                    "timestamp", adjustment.timestamp()
            ));
        } catch (Exception e) {
            logger.warn("Failed to broadcast adjustment: {}", e.getMessage());
        }
    }

    /**
     * Broadcasts current status for UI monitoring.
     */
    private void broadcastStatus(double memoryPercent) {
        if (messagingTemplate == null) {
            return;
        }

        try {
            messagingTemplate.convertAndSend("/topic/adaptive/status", Map.of(
                    "type", "ADAPTIVE_STATUS",
                    "enabled", currentConfig.enabled(),
                    "memoryPercent", memoryPercent,
                    "targetMemoryPercent", currentConfig.targetMemoryPercent(),
                    "criticalMemoryPercent", currentConfig.criticalMemoryPercent(),
                    "currentEmbeddingBatch", currentEmbeddingBatchSize.get(),
                    "currentIndexBatch", currentIndexBatchSize.get(),
                    "adjustmentCount", adjustmentCount.get(),
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            logger.trace("Failed to broadcast status: {}", e.getMessage());
        }
    }

    /**
     * Gets the current adaptive configuration.
     */
    public AdaptivePerformanceConfigDto getCurrentConfig() {
        return currentConfig;
    }

    /**
     * Gets the current embedding batch size (may be adjusted from config).
     */
    public int getCurrentEmbeddingBatchSize() {
        return currentEmbeddingBatchSize.get();
    }

    /**
     * Gets the current index batch size (may be adjusted from config).
     */
    public int getCurrentIndexBatchSize() {
        return currentIndexBatchSize.get();
    }

    /**
     * Gets the last batch adjustment (if any).
     */
    public BatchAdjustment getLastAdjustment() {
        return lastAdjustment;
    }

    /**
     * Gets the total number of adjustments made.
     */
    public int getAdjustmentCount() {
        return adjustmentCount.get();
    }

    /**
     * Checks if monitoring is currently active.
     */
    public boolean isMonitoringActive() {
        return monitoringActive.get();
    }

    /**
     * Gets current status as a map for API responses.
     */
    public Map<String, Object> getStatus() {
        IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", currentConfig.enabled());
        status.put("monitoringActive", monitoringActive.get());
        status.put("preset", currentConfig.preset() != null ? currentConfig.preset() : "custom");
        status.put("targetMemoryPercent", currentConfig.targetMemoryPercent());
        status.put("criticalMemoryPercent", currentConfig.criticalMemoryPercent());
        status.put("currentMemoryPercent", memInfo.usagePercent());
        status.put("currentEmbeddingBatch", currentEmbeddingBatchSize.get());
        status.put("currentIndexBatch", currentIndexBatchSize.get());
        status.put("maxEmbeddingBatch", currentConfig.maxEmbeddingBatch());
        status.put("maxIndexBatch", currentConfig.maxIndexBatch());
        status.put("minEmbeddingBatch", currentConfig.minEmbeddingBatch());
        status.put("minIndexBatch", currentConfig.minIndexBatch());
        status.put("adjustmentCount", adjustmentCount.get());
        status.put("lastAdjustment", lastAdjustment != null ? Map.of(
                "reason", lastAdjustment.reason(),
                "direction", lastAdjustment.direction(),
                "timestamp", lastAdjustment.timestamp()
        ) : null);
        return status;
    }

    @PreDestroy
    public void cleanup() {
        stopMonitoring();
    }

    /**
     * Record representing a batch size adjustment event.
     */
    public record BatchAdjustment(
            String reason,
            String direction,
            double memoryPercent,
            int oldEmbeddingBatch,
            int newEmbeddingBatch,
            int oldIndexBatch,
            int newIndexBatch,
            long timestamp
    ) {}
}
