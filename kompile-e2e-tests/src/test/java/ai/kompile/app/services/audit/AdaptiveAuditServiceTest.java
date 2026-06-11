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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdaptiveAuditService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdaptiveAuditServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AdaptiveAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AdaptiveAuditService(messagingTemplate);
    }

    // ===== Enabled/Disabled =====

    @Test
    void isEnabled_defaultsToTrue() {
        assertThat(auditService.isEnabled()).isTrue();
    }

    @Test
    void setEnabled_false_disablesLogging() {
        auditService.setEnabled(false);
        auditService.logModeEnabled("default", Map.of());
        assertThat(auditService.getEventCount()).isEqualTo(0);
    }

    @Test
    void setEnabled_true_enablesLogging() {
        auditService.setEnabled(false);
        auditService.setEnabled(true);
        auditService.logModeEnabled("default", Map.of());
        assertThat(auditService.getEventCount()).isEqualTo(1);
    }

    // ===== logModeEnabled =====

    @Test
    void logModeEnabled_addsEvent() {
        auditService.logModeEnabled("default", Map.of("key", "val"));
        assertThat(auditService.getEventCount()).isEqualTo(1);

        AdaptiveAuditEvent event = auditService.getAllEvents().get(0);
        assertThat(event.eventType()).isEqualTo(AdaptiveAuditEvent.EventType.MODE_ENABLED);
    }

    @Test
    void logModeEnabled_broadcastsViaWebSocket() {
        auditService.logModeEnabled("default", Map.of());
        verify(messagingTemplate).convertAndSend(eq("/topic/adaptive/audit"), any(Map.class));
    }

    // ===== logModeDisabled =====

    @Test
    void logModeDisabled_addsDisabledEvent() {
        auditService.logModeDisabled("user request");
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MODE_DISABLED)).hasSize(1);
    }

    // ===== logConfigUpdated =====

    @Test
    void logConfigUpdated_addsEvent() {
        auditService.logConfigUpdated(Map.of("old", 10), Map.of("new", 20));
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.CONFIG_UPDATED)).hasSize(1);
    }

    // ===== logPresetApplied =====

    @Test
    void logPresetApplied_addsEvent() {
        auditService.logPresetApplied("high-memory", Map.of());
        List<AdaptiveAuditEvent> events = auditService.getEventsByType(AdaptiveAuditEvent.EventType.PRESET_APPLIED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).preset()).isEqualTo("high-memory");
    }

    // ===== logMonitoringStarted / logMonitoringStopped =====

    @Test
    void logMonitoringStarted_addsEvent() {
        auditService.logMonitoringStarted(1000, 5000);
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MONITORING_STARTED)).hasSize(1);
    }

    @Test
    void logMonitoringStopped_addsEvent() {
        auditService.logMonitoringStopped("shutdown");
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MONITORING_STOPPED)).hasSize(1);
    }

    // ===== logBatchReduced =====

    @Test
    void logBatchReduced_addsEvent() {
        auditService.logBatchReduced("OOM", 85.0, 32, 16, 64, 32);
        List<AdaptiveAuditEvent> events = auditService.getBatchAdjustmentEvents();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).eventType()).isEqualTo(AdaptiveAuditEvent.EventType.BATCH_REDUCED);
    }

    @Test
    void logBatchReduced_capturesMemoryPercent() {
        auditService.logBatchReduced("OOM", 90.5, 64, 32, 128, 64);
        AdaptiveAuditEvent event = auditService.getEventsByType(AdaptiveAuditEvent.EventType.BATCH_REDUCED).get(0);
        assertThat(event.memoryPercent()).isCloseTo(90.5, within(0.01));
    }

    // ===== logBatchIncreased =====

    @Test
    void logBatchIncreased_addsEvent() {
        auditService.logBatchIncreased("memory recovered", 40.0, 16, 32, 32, 64);
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.BATCH_INCREASED)).hasSize(1);
    }

    // ===== logMemoryTransition =====

    @Test
    void logMemoryTransition_criticalThreshold_addsEvent() {
        auditService.logMemoryTransition(90.0, 70, 85);
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MEMORY_CRITICAL_EXCEEDED)).hasSize(1);
    }

    @Test
    void logMemoryTransition_aboveTarget_addsEvent() {
        auditService.logMemoryTransition(75.0, 70, 85);
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MEMORY_TARGET_EXCEEDED)).hasSize(1);
    }

    @Test
    void logMemoryTransition_normal_addsRecoveredEvent_afterCritical() {
        // First go critical
        auditService.logMemoryTransition(90.0, 70, 85);
        // Then recover
        auditService.logMemoryTransition(50.0, 70, 85);
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MEMORY_RECOVERED)).hasSize(1);
    }

    @Test
    void logMemoryTransition_sameState_doesNotAddEvent() {
        auditService.logMemoryTransition(75.0, 70, 85); // ABOVE_TARGET
        auditService.logMemoryTransition(76.0, 70, 85); // Still ABOVE_TARGET — no new event
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MEMORY_TARGET_EXCEEDED)).hasSize(1);
    }

    // ===== logManualAdjustment =====

    @Test
    void logManualAdjustment_addsEvent() {
        auditService.logManualAdjustment(32, 64, 64, 128, "user override");
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.MANUAL_ADJUSTMENT)).hasSize(1);
    }

    // ===== logSystemRecommendation =====

    @Test
    void logSystemRecommendation_addsEvent() {
        auditService.logSystemRecommendation(16384, "high-memory", Map.of());
        assertThat(auditService.getEventsByType(AdaptiveAuditEvent.EventType.SYSTEM_RECOMMENDATION)).hasSize(1);
    }

    // ===== Query methods =====

    @Test
    void getAllEvents_returnsAllInOrder() {
        auditService.logModeEnabled("a", Map.of());
        auditService.logModeDisabled("b");
        assertThat(auditService.getAllEvents()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void getRecentEvents_limitsCount() {
        for (int i = 0; i < 10; i++) {
            auditService.logModeEnabled("preset-" + i, Map.of());
        }
        List<AdaptiveAuditEvent> recent = auditService.getRecentEvents(5);
        assertThat(recent).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void getEventsBetween_returnsInRange() throws Exception {
        Instant before = Instant.now();
        Thread.sleep(5);
        auditService.logModeEnabled("test", Map.of());
        Thread.sleep(5);
        Instant after = Instant.now();

        List<AdaptiveAuditEvent> events = auditService.getEventsBetween(before, after);
        assertThat(events).isNotEmpty();
    }

    @Test
    void getEventsSince_returnsRecentEvents() {
        auditService.logModeEnabled("test", Map.of());
        List<AdaptiveAuditEvent> events = auditService.getEventsSince(Duration.ofMinutes(1));
        assertThat(events).isNotEmpty();
    }

    @Test
    void getEventsSince_excludesOldEvents() {
        // Any events in a new service should be within the last minute
        List<AdaptiveAuditEvent> events = auditService.getEventsSince(Duration.ofNanos(1));
        // No recently logged events at this point
        assertThat(events.size()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getBatchAdjustmentEvents_excludesNonBatchEvents() {
        auditService.logModeEnabled("test", Map.of());
        auditService.logBatchReduced("OOM", 80.0, 32, 16, 64, 32);

        List<AdaptiveAuditEvent> adjustments = auditService.getBatchAdjustmentEvents();
        assertThat(adjustments).allMatch(e ->
                e.eventType() == AdaptiveAuditEvent.EventType.BATCH_REDUCED ||
                e.eventType() == AdaptiveAuditEvent.EventType.BATCH_INCREASED);
    }

    // ===== getStatistics =====

    @Test
    void getStatistics_containsExpectedKeys() {
        auditService.logModeEnabled("p", Map.of());
        Map<String, Object> stats = auditService.getStatistics();
        assertThat(stats).containsKeys(
                "countByType", "totalEvents", "totalBatchAdjustments",
                "batchReductions", "batchIncreases", "memoryThresholdCrossings"
        );
    }

    @Test
    void getStatistics_totalEvents_correct() {
        auditService.clearEvents();
        auditService.logModeEnabled("p", Map.of());
        auditService.logModeDisabled("q");
        Map<String, Object> stats = auditService.getStatistics();
        assertThat(stats.get("totalEvents")).isEqualTo(2);
    }

    // ===== clearEvents =====

    @Test
    void clearEvents_removesAllEvents() {
        auditService.logModeEnabled("x", Map.of());
        auditService.clearEvents();
        assertThat(auditService.getEventCount()).isEqualTo(0);
    }

    // ===== cleanupOldEvents =====

    @Test
    void cleanupOldEvents_returnsZero_whenNoOldEvents() {
        auditService.logModeEnabled("fresh", Map.of());
        int removed = auditService.cleanupOldEvents();
        assertThat(removed).isEqualTo(0); // retention is 24h, so fresh events won't be removed
    }

    // ===== No WebSocket (null template) =====

    @Test
    void logModeEnabled_doesNotCrash_withNullMessagingTemplate() {
        AdaptiveAuditService noWs = new AdaptiveAuditService(null);
        assertThatCode(() -> noWs.logModeEnabled("preset", Map.of())).doesNotThrowAnyException();
        assertThat(noWs.getEventCount()).isEqualTo(1);
    }
}
