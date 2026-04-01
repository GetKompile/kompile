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

package ai.kompile.app.ingest.listener;

import ai.kompile.app.ingest.service.SubprocessEventHistoryService;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listens for EmbeddingSubprocessEvent and persists them to subprocess event history.
 * This captures all subprocess lifecycle events (start, stop, crash, restart, model load/fail)
 * and records them for later analysis and display in the UI.
 */
@Component
public class SubprocessEventListener {

    private static final Logger log = LoggerFactory.getLogger(SubprocessEventListener.class);

    private final SubprocessEventHistoryService eventHistoryService;

    @Autowired
    public SubprocessEventListener(
            @Autowired(required = false) SubprocessEventHistoryService eventHistoryService) {
        this.eventHistoryService = eventHistoryService;
        if (eventHistoryService != null) {
            log.info("SubprocessEventListener initialized - will persist subprocess events to history");
        } else {
            log.info("SubprocessEventListener initialized - event history service not available");
        }
    }

    @EventListener
    public void handleSubprocessEvent(EmbeddingSubprocessEvent event) {
        if (eventHistoryService == null) {
            log.debug("Event history service not available, skipping event: {}", event.getEventType());
            return;
        }

        String modelId = event.getModelId();
        Map<String, Object> data = event.getData();

        try {
            switch (event.getEventType()) {
                case SUBPROCESS_STARTED -> {
                    eventHistoryService.recordSubprocessStarted(modelId);
                }

                case SUBPROCESS_STOPPED -> {
                    eventHistoryService.recordSubprocessStopped(modelId);
                }

                case SUBPROCESS_CRASHED -> {
                    String error = getStringFromData(data, "error", "Unknown error");
                    // Exit code might not be available in the event
                    eventHistoryService.recordSubprocessCrashed(modelId, error, null, null);
                }

                case SUBPROCESS_RESTARTING -> {
                    int attemptNumber = getIntFromData(data, "attemptNumber", 1);
                    int maxAttempts = getIntFromData(data, "maxAttempts", 3);
                    String reason = getStringFromData(data, "reason", "UNKNOWN");
                    long backoffMs = getLongFromData(data, "backoffMs", 0);
                    long heapBytes = getLongFromData(data, "heapBytes", 0);
                    int batchSize = getIntFromData(data, "batchSize", 0);
                    int threads = getIntFromData(data, "threads", 0);
                    eventHistoryService.recordRestartAttempt(modelId, attemptNumber, maxAttempts,
                            reason, backoffMs, heapBytes, batchSize, threads, null);
                }

                case SUBPROCESS_RESTART_SUCCESS -> {
                    int attemptNumber = getIntFromData(data, "attemptNumber", 1);
                    eventHistoryService.recordRestartSuccess(modelId, attemptNumber, null);
                }

                case SUBPROCESS_RESTART_EXHAUSTED -> {
                    int totalAttempts = getIntFromData(data, "totalAttempts", 0);
                    String lastReason = getStringFromData(data, "lastReason", "UNKNOWN");
                    eventHistoryService.recordRestartExhausted(modelId, totalAttempts, lastReason, null);
                }

                case MODEL_LOADED -> {
                    int dimensions = getIntFromData(data, "dimensions", 0);
                    String encoderType = getStringFromData(data, "encoderType", "");
                    eventHistoryService.recordModelLoaded(modelId, dimensions, encoderType);
                }

                case MODEL_FAILED -> {
                    String error = getStringFromData(data, "error", "Unknown error");
                    eventHistoryService.recordModelFailed(modelId, error);
                }

                case PROGRESS, PHASE_TRANSITION, LOG, ERROR, HEARTBEAT -> {
                    // These are transient events, don't persist them to history
                    log.debug("Skipping transient event type: {}", event.getEventType());
                }

                default -> {
                    log.warn("Unknown subprocess event type: {}", event.getEventType());
                }
            }
        } catch (Exception e) {
            log.error("Failed to persist subprocess event {}: {}", event.getEventType(), e.getMessage(), e);
        }
    }

    private String getStringFromData(Map<String, Object> data, String key, String defaultValue) {
        if (data == null || !data.containsKey(key)) {
            return defaultValue;
        }
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getIntFromData(Map<String, Object> data, String key, int defaultValue) {
        if (data == null || !data.containsKey(key)) {
            return defaultValue;
        }
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private long getLongFromData(Map<String, Object> data, String key, long defaultValue) {
        if (data == null || !data.containsKey(key)) {
            return defaultValue;
        }
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
}
