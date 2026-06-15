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

package ai.kompile.app.services.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Service for managing adaptive performance audit events.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>Event logging for all adaptive performance state transitions</li>
 *   <li>In-memory event storage with configurable retention</li>
 *   <li>WebSocket broadcasting for real-time UI updates</li>
 *   <li>Query methods for audit retrieval</li>
 *   <li>Statistics and summary generation</li>
 * </ul>
 */
@Service
public class AdaptiveAuditService {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveAuditService.class);

    // WebSocket topic for audit events
    private static final String AUDIT_TOPIC = "/topic/adaptive/audit";

    private final SimpMessagingTemplate messagingTemplate;

    // In-memory storage with bounded size
    private final ConcurrentLinkedDeque<AdaptiveAuditEvent> eventLog = new ConcurrentLinkedDeque<>();

    // Track memory state for transition detection
    private volatile MemoryState lastMemoryState = MemoryState.NORMAL;

    @Value("${kompile.adaptive.audit.max-events:1000}")
    private int maxEvents = 1000;

    @Value("${kompile.adaptive.audit.retention-hours:24}")
    private int retentionHours = 24;

    @Value("${kompile.adaptive.audit.enabled:true}")
    private boolean enabled = true;

    @Autowired
    public AdaptiveAuditService(@Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        logger.info("AdaptiveAuditService initialized: maxEvents={}, retentionHours={}, enabled={}",
                maxEvents, retentionHours, enabled);
    }

    /**
     * Memory state for tracking transitions.
     */
    private enum MemoryState {
        NORMAL,
        ABOVE_TARGET,
        CRITICAL
    }

    // ========== Event Logging Methods ==========

    /**
     * Log that adaptive mode was enabled.
     */
    public void logModeEnabled(String preset, Map<String, Object> config) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.modeEnabled(preset, config);
        recordAndBroadcast(event);
        logger.info("AUDIT: Adaptive mode ENABLED - preset={}", preset);
    }

    /**
     * Log that adaptive mode was disabled.
     */
    public void logModeDisabled(String reason) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.modeDisabled(reason);
        recordAndBroadcast(event);
        logger.info("AUDIT: Adaptive mode DISABLED - reason={}", reason);
    }

    /**
     * Log configuration update.
     */
    public void logConfigUpdated(Map<String, Object> previousConfig, Map<String, Object> newConfig) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.configUpdated(previousConfig, newConfig);
        recordAndBroadcast(event);
        logger.info("AUDIT: Configuration UPDATED");
    }

    /**
     * Log preset application.
     */
    public void logPresetApplied(String presetName, Map<String, Object> presetConfig) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.presetApplied(presetName, presetConfig);
        recordAndBroadcast(event);
        logger.info("AUDIT: Preset APPLIED - name={}", presetName);
    }

    /**
     * Log monitoring started.
     */
    public void logMonitoringStarted(int checkIntervalMs, int cooldownMs) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.monitoringStarted(checkIntervalMs, cooldownMs);
        recordAndBroadcast(event);
        logger.info("AUDIT: Monitoring STARTED - interval={}ms, cooldown={}ms", checkIntervalMs, cooldownMs);
    }

    /**
     * Log monitoring stopped.
     */
    public void logMonitoringStopped(String reason) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.monitoringStopped(reason);
        recordAndBroadcast(event);
        logger.info("AUDIT: Monitoring STOPPED - reason={}", reason);
    }

    /**
     * Log batch size reduction.
     */
    public void logBatchReduced(
            String reason,
            double memoryPercent,
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch
    ) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.batchReduced(
                reason, memoryPercent,
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch
        );
        recordAndBroadcast(event);
        logger.warn("AUDIT: Batch REDUCED - memory={}%, embedding: {} -> {}, index: {} -> {}",
                String.format("%.1f", memoryPercent),
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch);
    }

    /**
     * Log batch size increase.
     */
    public void logBatchIncreased(
            String reason,
            double memoryPercent,
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch
    ) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.batchIncreased(
                reason, memoryPercent,
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch
        );
        recordAndBroadcast(event);
        logger.info("AUDIT: Batch INCREASED - memory={}%, embedding: {} -> {}, index: {} -> {}",
                String.format("%.1f", memoryPercent),
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch);
    }

    /**
     * Log memory threshold crossing (with transition detection).
     */
    public void logMemoryTransition(double memoryPercent, int targetThreshold, int criticalThreshold) {
        if (!enabled) return;

        MemoryState newState;
        if (memoryPercent >= criticalThreshold) {
            newState = MemoryState.CRITICAL;
        } else if (memoryPercent >= targetThreshold) {
            newState = MemoryState.ABOVE_TARGET;
        } else {
            newState = MemoryState.NORMAL;
        }

        // Only log transitions
        if (newState != lastMemoryState) {
            AdaptiveAuditEvent event;

            switch (newState) {
                case CRITICAL:
                    event = AdaptiveAuditEvent.memoryCriticalExceeded(memoryPercent, criticalThreshold);
                    logger.warn("AUDIT: Memory CRITICAL - {}% >= {}%",
                            String.format("%.1f", memoryPercent), criticalThreshold);
                    break;
                case ABOVE_TARGET:
                    if (lastMemoryState == MemoryState.CRITICAL) {
                        // Recovering from critical but still above target
                        event = AdaptiveAuditEvent.memoryTargetExceeded(memoryPercent, targetThreshold);
                        logger.info("AUDIT: Memory recovering from critical, still above target - {}%",
                                String.format("%.1f", memoryPercent));
                    } else {
                        event = AdaptiveAuditEvent.memoryTargetExceeded(memoryPercent, targetThreshold);
                        logger.warn("AUDIT: Memory TARGET EXCEEDED - {}% >= {}%",
                                String.format("%.1f", memoryPercent), targetThreshold);
                    }
                    break;
                case NORMAL:
                default:
                    event = AdaptiveAuditEvent.memoryRecovered(memoryPercent, targetThreshold);
                    logger.info("AUDIT: Memory RECOVERED - {}% < {}%",
                            String.format("%.1f", memoryPercent), targetThreshold);
                    break;
            }

            recordAndBroadcast(event);
            lastMemoryState = newState;
        }
    }

    /**
     * Log manual batch adjustment.
     */
    public void logManualAdjustment(
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch,
            String reason
    ) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.manualAdjustment(
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch,
                reason
        );
        recordAndBroadcast(event);
        logger.info("AUDIT: Manual ADJUSTMENT - embedding: {} -> {}, index: {} -> {}",
                prevEmbeddingBatch, newEmbeddingBatch, prevIndexBatch, newIndexBatch);
    }

    /**
     * Log system recommendation.
     */
    public void logSystemRecommendation(long systemMemoryMB, String recommendedPreset, Map<String, Object> config) {
        if (!enabled) return;
        AdaptiveAuditEvent event = AdaptiveAuditEvent.systemRecommendation(systemMemoryMB, recommendedPreset, config);
        recordAndBroadcast(event);
        logger.info("AUDIT: System RECOMMENDATION - {}MB memory, preset={}", systemMemoryMB, recommendedPreset);
    }

    // ========== Internal Methods ==========

    private void recordAndBroadcast(AdaptiveAuditEvent event) {
        // Add to in-memory log
        eventLog.addFirst(event);

        // Enforce max size
        while (eventLog.size() > maxEvents) {
            eventLog.removeLast();
        }

        // Broadcast via WebSocket
        broadcastEvent(event);
    }

    private void broadcastEvent(AdaptiveAuditEvent event) {
        if (messagingTemplate == null) return;

        try {
            messagingTemplate.convertAndSend(AUDIT_TOPIC, Map.of(
                    "type", "ADAPTIVE_AUDIT_EVENT",
                    "eventId", event.eventId(),
                    "eventType", event.eventType().name(),
                    "timestamp", event.timestamp().toString(),
                    "message", event.message(),
                    "previousState", event.previousState() != null ? event.previousState() : "",
                    "newState", event.newState() != null ? event.newState() : "",
                    "memoryPercent", event.memoryPercent() != null ? event.memoryPercent() : -1,
                    "reason", event.reason() != null ? event.reason() : "",
                    "preset", event.preset() != null ? event.preset() : ""
            ));
        } catch (Exception e) {
            logger.warn("Failed to broadcast audit event: {}", e.getMessage());
        }
    }

    // ========== Query Methods ==========

    /**
     * Get all audit events (most recent first).
     */
    public List<AdaptiveAuditEvent> getAllEvents() {
        return new ArrayList<>(eventLog);
    }

    /**
     * Get the most recent N events.
     */
    public List<AdaptiveAuditEvent> getRecentEvents(int limit) {
        return eventLog.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get events of a specific type.
     */
    public List<AdaptiveAuditEvent> getEventsByType(AdaptiveAuditEvent.EventType eventType) {
        return eventLog.stream()
                .filter(e -> e.eventType() == eventType)
                .collect(Collectors.toList());
    }

    /**
     * Get events in a time range.
     */
    public List<AdaptiveAuditEvent> getEventsBetween(Instant start, Instant end) {
        return eventLog.stream()
                .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                .collect(Collectors.toList());
    }

    /**
     * Get events since a duration ago.
     */
    public List<AdaptiveAuditEvent> getEventsSince(Duration duration) {
        Instant cutoff = Instant.now().minus(duration);
        return eventLog.stream()
                .filter(e -> e.timestamp().isAfter(cutoff))
                .collect(Collectors.toList());
    }

    /**
     * Get batch adjustment events only.
     */
    public List<AdaptiveAuditEvent> getBatchAdjustmentEvents() {
        return eventLog.stream()
                .filter(e -> e.eventType() == AdaptiveAuditEvent.EventType.BATCH_REDUCED ||
                        e.eventType() == AdaptiveAuditEvent.EventType.BATCH_INCREASED)
                .collect(Collectors.toList());
    }

    /**
     * Get the total count of events.
     */
    public int getEventCount() {
        return eventLog.size();
    }

    /**
     * Get audit statistics/summary.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Count by event type
        Map<String, Long> countByType = eventLog.stream()
                .collect(Collectors.groupingBy(
                        e -> e.eventType().name(),
                        Collectors.counting()
                ));
        stats.put("countByType", countByType);

        // Total events
        stats.put("totalEvents", eventLog.size());

        // Time range
        if (!eventLog.isEmpty()) {
            stats.put("oldestEvent", eventLog.getLast().timestamp().toString());
            stats.put("newestEvent", eventLog.getFirst().timestamp().toString());
        }

        // Batch adjustment stats
        List<AdaptiveAuditEvent> adjustments = getBatchAdjustmentEvents();
        stats.put("totalBatchAdjustments", adjustments.size());

        long reductions = adjustments.stream()
                .filter(e -> e.eventType() == AdaptiveAuditEvent.EventType.BATCH_REDUCED)
                .count();
        long increases = adjustments.stream()
                .filter(e -> e.eventType() == AdaptiveAuditEvent.EventType.BATCH_INCREASED)
                .count();
        stats.put("batchReductions", reductions);
        stats.put("batchIncreases", increases);

        // Memory threshold crossings
        long memoryWarnings = eventLog.stream()
                .filter(e -> e.eventType() == AdaptiveAuditEvent.EventType.MEMORY_TARGET_EXCEEDED ||
                        e.eventType() == AdaptiveAuditEvent.EventType.MEMORY_CRITICAL_EXCEEDED)
                .count();
        stats.put("memoryThresholdCrossings", memoryWarnings);

        return stats;
    }

    // ========== Cleanup Methods ==========

    /**
     * Clear all events.
     */
    public void clearEvents() {
        int count = eventLog.size();
        eventLog.clear();
        logger.info("Cleared {} audit events", count);
    }

    /**
     * Remove events older than retention period.
     */
    public int cleanupOldEvents() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));
        int initialSize = eventLog.size();

        eventLog.removeIf(e -> e.timestamp().isBefore(cutoff));

        int removed = initialSize - eventLog.size();
        if (removed > 0) {
            logger.info("Cleaned up {} old audit events (retention: {} hours)", removed, retentionHours);
        }
        return removed;
    }

    /**
     * Check if audit logging is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enable or disable audit logging.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        logger.info("Adaptive audit logging {}", enabled ? "enabled" : "disabled");
    }
}
