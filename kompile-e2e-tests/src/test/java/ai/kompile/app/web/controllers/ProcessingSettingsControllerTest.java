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
import ai.kompile.app.services.audit.AdaptiveAuditService;
import ai.kompile.app.web.dto.ProcessingSettingsRequest;
import ai.kompile.app.web.dto.ProcessingSettingsResponse;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessingSettingsControllerTest {

    @Mock
    private IngestConfiguration ingestConfiguration;

    @Mock
    private DocumentIngestService documentIngestService;

    @Mock
    private AdaptiveBatchingService adaptiveBatchingService;

    @Mock
    private AdaptiveAuditService auditService;

    @Mock
    private PipelineConfigService pipelineConfigService;

    @Mock
    private AnseriniEmbeddingModelImpl embeddingModel;

    private ProcessingSettingsController controllerWithConfig;
    private ProcessingSettingsController controllerNoConfig;

    @BeforeEach
    void setUp() {
        // Stub IngestConfiguration with sensible defaults
        IngestConfiguration.MemoryInfo memInfo = new IngestConfiguration.MemoryInfo(
                1_073_741_824L, // maxBytes = 1 GB
                536_870_912L,   // totalBytes
                536_870_912L,   // freeBytes
                536_870_912L,   // usedBytes
                50.0,           // usagePercent
                false,          // thresholdExceeded
                false,          // criticalExceeded
                false           // killThresholdExceeded
        );
        when(ingestConfiguration.getMemoryInfo()).thenReturn(memInfo);
        when(ingestConfiguration.getMaxConcurrentJobs()).thenReturn(4);
        when(ingestConfiguration.getActiveJobCount()).thenReturn(0);
        when(ingestConfiguration.canAcceptNewJob()).thenReturn(true);
        when(ingestConfiguration.getIndexBatchSize()).thenReturn(100);
        when(ingestConfiguration.getMinBatchSize()).thenReturn(25);
        when(ingestConfiguration.getMaxBatchSize()).thenReturn(500);
        when(ingestConfiguration.isAdaptiveBatchSize()).thenReturn(true);
        when(ingestConfiguration.getMemoryThresholdPercent()).thenReturn(80);
        when(ingestConfiguration.getMemoryCriticalPercent()).thenReturn(90);
        when(ingestConfiguration.getCorePoolSize()).thenReturn(4);
        when(ingestConfiguration.getMaxPoolSize()).thenReturn(8);

        controllerWithConfig = new ProcessingSettingsController(
                ingestConfiguration,
                documentIngestService,
                null, // taskExecutor — pass null to avoid ThreadPoolTaskExecutor complexity
                adaptiveBatchingService,
                auditService,
                pipelineConfigService,
                embeddingModel
        );

        controllerNoConfig = new ProcessingSettingsController(
                null, null, null, null, null, null, null
        );
    }

    // ── getSettings ───────────────────────────────────────────────────────

    @Test
    void getSettings_withConfig_returns200() {
        ResponseEntity<ProcessingSettingsResponse> resp = controllerWithConfig.getSettings();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(4, resp.getBody().maxConcurrentJobs());
        assertEquals(100, resp.getBody().indexBatchSize());
    }

    @Test
    void getSettings_nullConfig_returns503() {
        ResponseEntity<ProcessingSettingsResponse> resp = controllerNoConfig.getSettings();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    // ── updateSettings ────────────────────────────────────────────────────

    @Test
    void updateSettings_withConfig_appliesAndReturns200() {
        ProcessingSettingsRequest request = new ProcessingSettingsRequest(
                2, 50, 75, true
        );

        ResponseEntity<ProcessingSettingsResponse> resp = controllerWithConfig.updateSettings(request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(ingestConfiguration).setMaxConcurrentJobs(2);
        verify(ingestConfiguration).setIndexBatchSize(50);
        verify(ingestConfiguration).setMemoryThresholdPercent(75);
        verify(ingestConfiguration).setAdaptiveBatchSize(true);
    }

    @Test
    void updateSettings_nullConfig_returns503() {
        ProcessingSettingsRequest request = new ProcessingSettingsRequest(2, 50, 75, true);
        ResponseEntity<ProcessingSettingsResponse> resp = controllerNoConfig.updateSettings(request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
    }

    @Test
    void updateSettings_partialRequest_onlyUpdatesNonNull() {
        ProcessingSettingsRequest request = new ProcessingSettingsRequest(null, 200, null, null);

        controllerWithConfig.updateSettings(request);

        verify(ingestConfiguration, never()).setMaxConcurrentJobs(anyInt());
        verify(ingestConfiguration).setIndexBatchSize(200);
        verify(ingestConfiguration, never()).setMemoryThresholdPercent(anyInt());
        verify(ingestConfiguration, never()).setAdaptiveBatchSize(anyBoolean());
    }

    // ── getMemoryStatus ───────────────────────────────────────────────────

    @Test
    void getMemoryStatus_withConfig_returns200() {
        ResponseEntity<ProcessingSettingsResponse.MemoryStatus> resp = controllerWithConfig.getMemoryStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
    }

    @Test
    void getMemoryStatus_nullConfig_stillReturns200FromRuntime() {
        // When ingestConfiguration is null, falls back to Runtime.getRuntime()
        ResponseEntity<ProcessingSettingsResponse.MemoryStatus> resp = controllerNoConfig.getMemoryStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().maxMemoryMB() > 0);
    }

    // ── triggerGC ─────────────────────────────────────────────────────────

    @Test
    void triggerGC_returns200WithMemoryInfo() {
        ResponseEntity<Map<String, Object>> resp = controllerWithConfig.triggerGC();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("beforeUsageMB"));
        assertNotNull(resp.getBody().get("afterUsageMB"));
        assertEquals("Garbage collection requested", resp.getBody().get("message"));
    }

    // ── getJobStats ───────────────────────────────────────────────────────

    @Test
    void getJobStats_withConfig_returnsStats() {
        ResponseEntity<Map<String, Object>> resp = controllerWithConfig.getJobStats();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("activeJobs"));
        assertNotNull(resp.getBody().get("maxConcurrentJobs"));
    }

    @Test
    void getJobStats_nullConfig_returnsZeroStats() {
        ResponseEntity<Map<String, Object>> resp = controllerNoConfig.getJobStats();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("activeJobs"));
        assertEquals(0, resp.getBody().get("maxConcurrentJobs"));
    }

    // ── getPipelineConfig ─────────────────────────────────────────────────

    @Test
    void getPipelineConfig_returns200WithAdaptiveConfig() {
        ResponseEntity<Map<String, Object>> resp = controllerWithConfig.getPipelineConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().containsKey("extraction"));
        assertTrue(resp.getBody().containsKey("chunking"));
        assertTrue(resp.getBody().containsKey("embedding"));
        assertTrue(resp.getBody().containsKey("system"));
    }

    // ── getPipelinePresets ────────────────────────────────────────────────

    @Test
    void getPipelinePresets_returns200WithPresets() {
        ResponseEntity<Map<String, Object>> resp = controllerWithConfig.getPipelinePresets();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().containsKey("adaptive"));
        assertTrue(resp.getBody().containsKey("memoryOptimized"));
    }
}
