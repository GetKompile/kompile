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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AdaptiveBatchingService} — configuration, monitoring lifecycle,
 * batch size getters, status reporting, and preset application.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AdaptiveBatchingServiceTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AdaptiveAuditService auditService;

    private IngestConfiguration ingestConfig;
    private AdaptiveBatchingService service;

    @BeforeEach
    void setUp() {
        ingestConfig = new IngestConfiguration();
        service = new AdaptiveBatchingService(ingestConfig, messagingTemplate, auditService);
    }

    @AfterEach
    void tearDown() {
        service.stopMonitoring();
    }

    // ─── Initial state ───────────────────────────────────────────────

    @Test
    void initialState_defaultConfig() {
        AdaptivePerformanceConfigDto config = service.getCurrentConfig();
        assertNotNull(config);
        assertFalse(config.enabled());
    }

    @Test
    void initialState_batchSizesFromIngestConfig() {
        assertEquals(ingestConfig.getEmbeddingTargetBatchSize(), service.getCurrentEmbeddingBatchSize());
        assertEquals(ingestConfig.getIndexBatchSize(), service.getCurrentIndexBatchSize());
    }

    @Test
    void initialState_monitoringNotActive() {
        assertFalse(service.isMonitoringActive());
    }

    @Test
    void initialState_noLastAdjustment() {
        assertNull(service.getLastAdjustment());
        assertEquals(0, service.getAdjustmentCount());
    }

    // ─── configure ───────────────────────────────────────────────────

    @Test
    void configure_disabled_updatesConfig() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.defaultConfig();
        service.configure(config);

        assertEquals(config, service.getCurrentConfig());
        assertFalse(service.isMonitoringActive());
    }

    @Test
    void configure_enabled_startsMonitoring() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.balanced();
        service.configure(config);

        assertTrue(service.isMonitoringActive());
        assertTrue(service.getCurrentConfig().enabled());
    }

    @Test
    void configure_setsMemoryThresholds() {
        AdaptivePerformanceConfigDto config = new AdaptivePerformanceConfigDto(
                true, 70, 85, 4, 32, 10, 100, 2000, 5000, "custom");
        service.configure(config);

        assertEquals(70, ingestConfig.getMemoryThresholdPercent());
        assertEquals(85, ingestConfig.getMemoryCriticalPercent());
    }

    @Test
    void configure_setsBatchSizeLimits() {
        AdaptivePerformanceConfigDto config = new AdaptivePerformanceConfigDto(
                false, 75, 90, 4, 48, 10, 150, 2000, 5000, "custom");
        service.configure(config);

        assertEquals(48, service.getCurrentEmbeddingBatchSize());
        assertEquals(150, service.getCurrentIndexBatchSize());
    }

    @Test
    void configure_auditsWhenEnabled() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.balanced();
        service.configure(config);

        verify(auditService).logModeEnabled(eq("balanced"), any(Map.class));
        verify(auditService).logPresetApplied(eq("balanced"), any(Map.class));
    }

    @Test
    void configure_disableAfterEnable_stopsMonitoring() {
        service.configure(AdaptivePerformanceConfigDto.balanced());
        assertTrue(service.isMonitoringActive());

        service.configure(AdaptivePerformanceConfigDto.defaultConfig());
        assertFalse(service.isMonitoringActive());
    }

    // ─── startMonitoring / stopMonitoring ────────────────────────────

    @Test
    void startMonitoring_whenNotEnabled_doesNothing() {
        service.startMonitoring();
        assertFalse(service.isMonitoringActive());
    }

    @Test
    void startMonitoring_whenAlreadyActive_idempotent() {
        service.configure(AdaptivePerformanceConfigDto.balanced());
        assertTrue(service.isMonitoringActive());

        service.startMonitoring(); // second call
        assertTrue(service.isMonitoringActive());
    }

    @Test
    void stopMonitoring_whenNotActive_noError() {
        assertFalse(service.isMonitoringActive());
        assertDoesNotThrow(() -> service.stopMonitoring());
    }

    @Test
    void stopMonitoring_setsInactive() {
        service.configure(AdaptivePerformanceConfigDto.balanced());
        assertTrue(service.isMonitoringActive());

        service.stopMonitoring();
        assertFalse(service.isMonitoringActive());
    }

    // ─── getStatus ───────────────────────────────────────────────────

    @Test
    void getStatus_returnsCorrectFields() {
        Map<String, Object> status = service.getStatus();

        assertNotNull(status);
        assertNotNull(status.get("enabled"));
        assertNotNull(status.get("monitoringActive"));
        assertNotNull(status.get("currentEmbeddingBatch"));
        assertNotNull(status.get("currentIndexBatch"));
        assertNotNull(status.get("currentMemoryPercent"));
        assertNotNull(status.get("adjustmentCount"));
    }

    @Test
    void getStatus_reflectsConfiguration() {
        service.configure(AdaptivePerformanceConfigDto.conservative());

        Map<String, Object> status = service.getStatus();

        assertEquals(true, status.get("enabled"));
        assertEquals(true, status.get("monitoringActive"));
        assertEquals(65, status.get("targetMemoryPercent"));
        assertEquals(80, status.get("criticalMemoryPercent"));
        assertEquals("conservative", status.get("preset"));
    }

    // ─── Preset application ──────────────────────────────────────────

    @Test
    void configure_conservative_setsLowerLimits() {
        service.configure(AdaptivePerformanceConfigDto.conservative());

        assertEquals(32, service.getCurrentEmbeddingBatchSize());
        assertEquals(100, service.getCurrentIndexBatchSize());
    }

    @Test
    void configure_aggressive_setsHigherLimits() {
        service.configure(AdaptivePerformanceConfigDto.aggressive());

        assertEquals(128, service.getCurrentEmbeddingBatchSize());
        assertEquals(500, service.getCurrentIndexBatchSize());
    }

    // ─── AdaptivePerformanceConfigDto factory methods ────────────────

    @Test
    void configDto_defaultConfig_isDisabled() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.defaultConfig();
        assertFalse(config.enabled());
        assertEquals("balanced", config.preset());
    }

    @Test
    void configDto_fromPreset_conservative() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.fromPreset("conservative");
        assertTrue(config.enabled());
        assertEquals(65, config.targetMemoryPercent());
        assertEquals(80, config.criticalMemoryPercent());
    }

    @Test
    void configDto_fromPreset_aggressive() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.fromPreset("aggressive");
        assertTrue(config.enabled());
        assertEquals(85, config.targetMemoryPercent());
        assertEquals(95, config.criticalMemoryPercent());
    }

    @Test
    void configDto_fromPreset_unknown_defaultsToBalanced() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.fromPreset("unknown");
        assertEquals("balanced", config.preset());
    }

    @Test
    void configDto_fromPreset_null_defaultsToBalanced() {
        AdaptivePerformanceConfigDto config = AdaptivePerformanceConfigDto.fromPreset(null);
        assertEquals("balanced", config.preset());
    }

    // ─── Null messagingTemplate ──────────────────────────────────────

    @Test
    void configure_nullMessagingTemplate_noException() {
        AdaptiveBatchingService noWsService = new AdaptiveBatchingService(
                ingestConfig, null, auditService);
        assertDoesNotThrow(() -> noWsService.configure(AdaptivePerformanceConfigDto.balanced()));
        noWsService.stopMonitoring();
    }

    // ─── BatchAdjustment record ──────────────────────────────────────

    @Test
    void batchAdjustment_record_fieldsAccessible() {
        AdaptiveBatchingService.BatchAdjustment adj = new AdaptiveBatchingService.BatchAdjustment(
                "HIGH", "DECREASE", 85.0, 64, 32, 200, 100, System.currentTimeMillis());

        assertEquals("HIGH", adj.reason());
        assertEquals("DECREASE", adj.direction());
        assertEquals(85.0, adj.memoryPercent());
        assertEquals(64, adj.oldEmbeddingBatch());
        assertEquals(32, adj.newEmbeddingBatch());
        assertEquals(200, adj.oldIndexBatch());
        assertEquals(100, adj.newIndexBatch());
        assertTrue(adj.timestamp() > 0);
    }
}
