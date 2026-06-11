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

import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingStatusBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private AnseriniEmbeddingModelImpl embeddingModel;

    @Mock
    private StagingClientService stagingClientService;

    @Mock
    private StagingServiceConfigService stagingConfigService;

    @Mock
    private JobLogService jobLogService;

    @Mock
    private IndexingJobHistoryService jobHistoryService;

    private EmbeddingStatusBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new EmbeddingStatusBroadcaster(
                messagingTemplate, embeddingModel, stagingClientService,
                stagingConfigService, jobLogService, jobHistoryService);
        ReflectionTestUtils.setField(broadcaster, "broadcastEnabled", true);
        ReflectionTestUtils.setField(broadcaster, "broadcastIntervalMs", 3000L);
        broadcaster.init();
    }

    @Test
    void enableBroadcasting_incrementsSubscriberCount() {
        broadcaster.enableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(1);
        assertThat(broadcaster.isBroadcasting()).isTrue();
    }

    @Test
    void disableBroadcasting_decrementsSubscriberCount() {
        broadcaster.enableBroadcasting();
        broadcaster.enableBroadcasting();
        broadcaster.disableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isEqualTo(1);
        assertThat(broadcaster.isBroadcasting()).isTrue();
    }

    @Test
    void disableBroadcasting_toZero_stopsBroadcasting() {
        broadcaster.enableBroadcasting();
        broadcaster.disableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isZero();
        assertThat(broadcaster.isBroadcasting()).isFalse();
    }

    @Test
    void disableBroadcasting_belowZero_clampsToZero() {
        broadcaster.disableBroadcasting();
        broadcaster.disableBroadcasting();
        assertThat(broadcaster.getSubscriberCount()).isZero();
    }

    @Test
    void startBroadcasting_setsBroadcastingTrue() {
        broadcaster.startBroadcasting();
        assertThat(broadcaster.isBroadcasting()).isTrue();
    }

    @Test
    void stopBroadcasting_setsBroadcastingFalse() {
        broadcaster.startBroadcasting();
        broadcaster.stopBroadcasting();
        assertThat(broadcaster.isBroadcasting()).isFalse();
    }

    @Test
    void forceNextBroadcast_setsFlag() {
        broadcaster.forceNextBroadcast();
        // Verify the field is set — on next broadcastStatus it will send regardless
        boolean forcedValue = (boolean) ReflectionTestUtils.getField(broadcaster, "forceNextBroadcast");
        assertThat(forcedValue).isTrue();
    }

    @Test
    void broadcastNow_withTemplate_sendsMessage() {
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.getActiveModelId()).thenReturn("bge-base");
        when(embeddingModel.isLoading()).thenReturn(false);
        when(embeddingModel.getLoadingPhase()).thenReturn("");
        when(embeddingModel.getLoadingMessage()).thenReturn("");
        when(embeddingModel.getLoadingElapsedMs()).thenReturn(0L);
        when(embeddingModel.getModelSource()).thenReturn(AnseriniEmbeddingModelImpl.ModelSource.REGISTRY);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(32);
        when(embeddingModel.getInitializationError()).thenReturn(null);

        broadcaster.broadcastNow();

        verify(messagingTemplate).convertAndSend(
                eq(EmbeddingStatusBroadcaster.TOPIC_COMBINED_STATUS), any(Map.class));
    }

    @Test
    void broadcastNow_withoutTemplate_doesNotThrow() {
        EmbeddingStatusBroadcaster noTemplate = new EmbeddingStatusBroadcaster(
                null, null, null, null, null, null);
        ReflectionTestUtils.setField(noTemplate, "broadcastEnabled", true);
        ReflectionTestUtils.setField(noTemplate, "broadcastIntervalMs", 3000L);

        // Should complete without throwing
        noTemplate.broadcastNow();
    }

    @Test
    void collectStatus_withNullEmbeddingModel_returnsNotAvailable() {
        EmbeddingStatusBroadcaster noModel = new EmbeddingStatusBroadcaster(
                messagingTemplate, null, null, null, null, null);
        ReflectionTestUtils.setField(noModel, "broadcastEnabled", true);
        ReflectionTestUtils.setField(noModel, "broadcastIntervalMs", 3000L);

        Map<String, Object> status = noModel.collectStatus();
        assertThat(status).containsKey("embedding");
        @SuppressWarnings("unchecked")
        Map<String, Object> embedding = (Map<String, Object>) status.get("embedding");
        assertThat(embedding.get("available")).isEqualTo(false);
    }

    @Test
    void collectStatus_containsExpectedKeys() {
        when(embeddingModel.isInitialized()).thenReturn(false);
        when(embeddingModel.isLoading()).thenReturn(false);
        when(embeddingModel.getLoadingPhase()).thenReturn("");
        when(embeddingModel.getLoadingMessage()).thenReturn("");
        when(embeddingModel.getLoadingElapsedMs()).thenReturn(0L);
        when(embeddingModel.getModelSource()).thenReturn(AnseriniEmbeddingModelImpl.ModelSource.REGISTRY);
        when(embeddingModel.getInitializationError()).thenReturn(null);
        when(embeddingModel.getActiveModelId()).thenReturn("test-model");

        Map<String, Object> status = broadcaster.collectStatus();
        assertThat(status).containsKeys("timestamp", "embedding", "staging", "ready");
    }

    @Test
    void getRecentSubprocessLogs_emptyInitially() {
        assertThat(broadcaster.getRecentSubprocessLogs()).isEmpty();
    }

    @Test
    void handleSubprocessEvent_addsToRecentLogs() {
        when(jobLogService.isEnabled()).thenReturn(false);

        EmbeddingSubprocessEvent event = new EmbeddingSubprocessEvent(
                this,
                EmbeddingSubprocessEvent.EventType.HEARTBEAT,
                "model-123",
                Map.of("status", "ok")
        );
        broadcaster.handleSubprocessEvent(event);

        List<Map<String, Object>> logs = broadcaster.getRecentSubprocessLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("modelId")).isEqualTo("model-123");
        assertThat(logs.get(0).get("eventType")).isEqualTo("HEARTBEAT");
    }

    @Test
    void getRecentSubprocessLogs_limitedToMaxCapacity() {
        when(jobLogService.isEnabled()).thenReturn(false);

        // Add 250 events (limit is 200)
        for (int i = 0; i < 250; i++) {
            EmbeddingSubprocessEvent event = new EmbeddingSubprocessEvent(
                    this,
                    EmbeddingSubprocessEvent.EventType.HEARTBEAT,
                    "model-" + i,
                    Map.of()
            );
            broadcaster.handleSubprocessEvent(event);
        }

        List<Map<String, Object>> logs = broadcaster.getRecentSubprocessLogs();
        assertThat(logs.size()).isLessThanOrEqualTo(200);
    }

    @Test
    void clearSubprocessLogs_emptiesLogs() {
        when(jobLogService.isEnabled()).thenReturn(false);
        EmbeddingSubprocessEvent event = new EmbeddingSubprocessEvent(
                this, EmbeddingSubprocessEvent.EventType.HEARTBEAT, "m", Map.of());
        broadcaster.handleSubprocessEvent(event);

        broadcaster.clearSubprocessLogs();
        assertThat(broadcaster.getRecentSubprocessLogs()).isEmpty();
    }

    @Test
    void getRecentSubprocessLogs_withLimit_returnsLimited() {
        when(jobLogService.isEnabled()).thenReturn(false);
        for (int i = 0; i < 10; i++) {
            EmbeddingSubprocessEvent event = new EmbeddingSubprocessEvent(
                    this, EmbeddingSubprocessEvent.EventType.HEARTBEAT, "m-" + i, Map.of());
            broadcaster.handleSubprocessEvent(event);
        }

        List<Map<String, Object>> logs = broadcaster.getRecentSubprocessLogs(3);
        assertThat(logs).hasSize(3);
    }

    @Test
    void broadcastStatus_doesNotSendWhenNoBroadcastEnabledAndNoSubscribers() {
        // No subscribers, broadcasting=false — should not send
        broadcaster.broadcastStatus();
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void broadcastStatus_sendsWhenBroadcastingEnabled() {
        when(embeddingModel.isInitialized()).thenReturn(true);
        when(embeddingModel.dimensions()).thenReturn(768);
        when(embeddingModel.getActiveModelId()).thenReturn("model");
        when(embeddingModel.isLoading()).thenReturn(false);
        when(embeddingModel.getLoadingPhase()).thenReturn("");
        when(embeddingModel.getLoadingMessage()).thenReturn("");
        when(embeddingModel.getLoadingElapsedMs()).thenReturn(0L);
        when(embeddingModel.getModelSource()).thenReturn(AnseriniEmbeddingModelImpl.ModelSource.REGISTRY);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(32);
        when(embeddingModel.getInitializationError()).thenReturn(null);

        broadcaster.startBroadcasting();
        // Force the broadcast (previousStatusHash is empty, so it should send)
        broadcaster.broadcastStatus();

        verify(messagingTemplate).convertAndSend(
                eq(EmbeddingStatusBroadcaster.TOPIC_COMBINED_STATUS), any(Map.class));
    }
}
