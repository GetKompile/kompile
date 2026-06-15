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

import java.time.Instant;
import java.util.Map;

/**
 * Represents an audit event for adaptive performance mode state transitions.
 *
 * <p>Audit events are generated for:</p>
 * <ul>
 *   <li>Adaptive mode enabled/disabled</li>
 *   <li>Configuration changes (thresholds, limits)</li>
 *   <li>Preset applied</li>
 *   <li>Batch size adjustments (automatic or manual)</li>
 *   <li>Memory threshold crossings</li>
 *   <li>Monitoring started/stopped</li>
 * </ul>
 */
public record AdaptiveAuditEvent(
        /** Unique event ID */
        String eventId,

        /** Type of audit event */
        EventType eventType,

        /** When the event occurred */
        Instant timestamp,

        /** Human-readable description of the event */
        String message,

        /** Previous state (for state transitions) */
        String previousState,

        /** New state (for state transitions) */
        String newState,

        /** Memory usage percentage at time of event */
        Double memoryPercent,

        /** Previous embedding batch size (for adjustments) */
        Integer previousEmbeddingBatch,

        /** New embedding batch size (for adjustments) */
        Integer newEmbeddingBatch,

        /** Previous index batch size (for adjustments) */
        Integer previousIndexBatch,

        /** New index batch size (for adjustments) */
        Integer newIndexBatch,

        /** Reason for the event (especially for adjustments) */
        String reason,

        /** Preset name if a preset was applied */
        String preset,

        /** Additional details as key-value pairs */
        Map<String, Object> details
) {
    /**
     * Types of adaptive performance audit events.
     */
    public enum EventType {
        /** Adaptive mode was enabled */
        MODE_ENABLED,
        /** Adaptive mode was disabled */
        MODE_DISABLED,
        /** Configuration was updated */
        CONFIG_UPDATED,
        /** A preset was applied */
        PRESET_APPLIED,
        /** Monitoring was started */
        MONITORING_STARTED,
        /** Monitoring was stopped */
        MONITORING_STOPPED,
        /** Batch sizes were reduced due to memory pressure */
        BATCH_REDUCED,
        /** Batch sizes were increased due to available memory */
        BATCH_INCREASED,
        /** Memory crossed the target threshold */
        MEMORY_TARGET_EXCEEDED,
        /** Memory crossed the critical threshold */
        MEMORY_CRITICAL_EXCEEDED,
        /** Memory dropped below target threshold (recovery) */
        MEMORY_RECOVERED,
        /** Manual batch size adjustment */
        MANUAL_ADJUSTMENT,
        /** System recommended configuration */
        SYSTEM_RECOMMENDATION
    }

    /**
     * Creates a mode enabled event.
     */
    public static AdaptiveAuditEvent modeEnabled(String preset, Map<String, Object> config) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MODE_ENABLED,
                Instant.now(),
                "Adaptive performance mode enabled" + (preset != null ? " with " + preset + " preset" : ""),
                "disabled",
                "enabled",
                null, null, null, null, null,
                "User enabled adaptive mode",
                preset,
                config
        );
    }

    /**
     * Creates a mode disabled event.
     */
    public static AdaptiveAuditEvent modeDisabled(String reason) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MODE_DISABLED,
                Instant.now(),
                "Adaptive performance mode disabled",
                "enabled",
                "disabled",
                null, null, null, null, null,
                reason,
                null,
                null
        );
    }

    /**
     * Creates a configuration updated event.
     */
    public static AdaptiveAuditEvent configUpdated(Map<String, Object> previousConfig, Map<String, Object> newConfig) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.CONFIG_UPDATED,
                Instant.now(),
                "Adaptive performance configuration updated",
                null,
                null,
                null, null, null, null, null,
                "Configuration change",
                null,
                Map.of("previous", previousConfig, "new", newConfig)
        );
    }

    /**
     * Creates a preset applied event.
     */
    public static AdaptiveAuditEvent presetApplied(String presetName, Map<String, Object> presetConfig) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.PRESET_APPLIED,
                Instant.now(),
                "Applied " + presetName + " preset for adaptive performance",
                null,
                presetName,
                null, null, null, null, null,
                "Preset selected by user",
                presetName,
                presetConfig
        );
    }

    /**
     * Creates a monitoring started event.
     */
    public static AdaptiveAuditEvent monitoringStarted(int checkIntervalMs, int cooldownMs) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MONITORING_STARTED,
                Instant.now(),
                "Adaptive performance monitoring started (interval: " + checkIntervalMs + "ms)",
                "stopped",
                "running",
                null, null, null, null, null,
                "Monitoring activated",
                null,
                Map.of("checkIntervalMs", checkIntervalMs, "cooldownMs", cooldownMs)
        );
    }

    /**
     * Creates a monitoring stopped event.
     */
    public static AdaptiveAuditEvent monitoringStopped(String reason) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MONITORING_STOPPED,
                Instant.now(),
                "Adaptive performance monitoring stopped",
                "running",
                "stopped",
                null, null, null, null, null,
                reason,
                null,
                null
        );
    }

    /**
     * Creates a batch reduced event.
     */
    public static AdaptiveAuditEvent batchReduced(
            String reason,
            double memoryPercent,
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch
    ) {
        double reductionPercent = 100.0 * (1.0 - ((double) newEmbeddingBatch / prevEmbeddingBatch));
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.BATCH_REDUCED,
                Instant.now(),
                String.format("Batch sizes reduced by %.0f%% due to memory pressure (%.1f%%)",
                        reductionPercent, memoryPercent),
                "normal",
                "reduced",
                memoryPercent,
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch,
                reason,
                null,
                Map.of(
                        "embeddingReduction", prevEmbeddingBatch - newEmbeddingBatch,
                        "indexReduction", prevIndexBatch - newIndexBatch
                )
        );
    }

    /**
     * Creates a batch increased event.
     */
    public static AdaptiveAuditEvent batchIncreased(
            String reason,
            double memoryPercent,
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch
    ) {
        double increasePercent = 100.0 * (((double) newEmbeddingBatch / prevEmbeddingBatch) - 1.0);
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.BATCH_INCREASED,
                Instant.now(),
                String.format("Batch sizes increased by %.0f%% - memory available (%.1f%%)",
                        increasePercent, memoryPercent),
                "reduced",
                "increased",
                memoryPercent,
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch,
                reason,
                null,
                Map.of(
                        "embeddingIncrease", newEmbeddingBatch - prevEmbeddingBatch,
                        "indexIncrease", newIndexBatch - prevIndexBatch
                )
        );
    }

    /**
     * Creates a memory target exceeded event.
     */
    public static AdaptiveAuditEvent memoryTargetExceeded(double memoryPercent, int targetThreshold) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MEMORY_TARGET_EXCEEDED,
                Instant.now(),
                String.format("Memory usage %.1f%% exceeded target threshold %d%%", memoryPercent, targetThreshold),
                "below_target",
                "above_target",
                memoryPercent, null, null, null, null,
                "Memory pressure detected",
                null,
                Map.of("targetThreshold", targetThreshold)
        );
    }

    /**
     * Creates a memory critical exceeded event.
     */
    public static AdaptiveAuditEvent memoryCriticalExceeded(double memoryPercent, int criticalThreshold) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MEMORY_CRITICAL_EXCEEDED,
                Instant.now(),
                String.format("CRITICAL: Memory usage %.1f%% exceeded critical threshold %d%%",
                        memoryPercent, criticalThreshold),
                "above_target",
                "critical",
                memoryPercent, null, null, null, null,
                "Critical memory pressure",
                null,
                Map.of("criticalThreshold", criticalThreshold)
        );
    }

    /**
     * Creates a memory recovered event.
     */
    public static AdaptiveAuditEvent memoryRecovered(double memoryPercent, int targetThreshold) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MEMORY_RECOVERED,
                Instant.now(),
                String.format("Memory usage %.1f%% dropped below target threshold %d%%", memoryPercent, targetThreshold),
                "above_target",
                "below_target",
                memoryPercent, null, null, null, null,
                "Memory pressure relieved",
                null,
                Map.of("targetThreshold", targetThreshold)
        );
    }

    /**
     * Creates a manual adjustment event.
     */
    public static AdaptiveAuditEvent manualAdjustment(
            int prevEmbeddingBatch, int newEmbeddingBatch,
            int prevIndexBatch, int newIndexBatch,
            String reason
    ) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.MANUAL_ADJUSTMENT,
                Instant.now(),
                "Manual batch size adjustment",
                null,
                null,
                null,
                prevEmbeddingBatch, newEmbeddingBatch,
                prevIndexBatch, newIndexBatch,
                reason,
                null,
                null
        );
    }

    /**
     * Creates a system recommendation event.
     */
    public static AdaptiveAuditEvent systemRecommendation(
            long systemMemoryMB,
            String recommendedPreset,
            Map<String, Object> recommendedConfig
    ) {
        return new AdaptiveAuditEvent(
                generateEventId(),
                EventType.SYSTEM_RECOMMENDATION,
                Instant.now(),
                String.format("System recommends %s preset for %dMB memory", recommendedPreset, systemMemoryMB),
                null,
                null,
                null, null, null, null, null,
                "Based on system memory analysis",
                recommendedPreset,
                Map.of("systemMemoryMB", systemMemoryMB, "recommendedConfig", recommendedConfig)
        );
    }

    private static String generateEventId() {
        return "ADP-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 10000);
    }
}
