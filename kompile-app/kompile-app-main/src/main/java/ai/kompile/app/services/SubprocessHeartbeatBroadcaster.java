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

import ai.kompile.app.subprocess.SubprocessMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts subprocess heartbeat memory data and phase transition durations
 * via WebSocket. Called by the individual subprocess launchers when they
 * receive heartbeat or phase transition messages.
 */
@Service
public class SubprocessHeartbeatBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessHeartbeatBroadcaster.class);

    public static final String TOPIC_SUBPROCESS_HEARTBEAT = "/topic/subprocess/heartbeat";
    public static final String TOPIC_SUBPROCESS_PHASE = "/topic/subprocess/phase";

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public SubprocessHeartbeatBroadcaster(@Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcast heartbeat memory data from a subprocess.
     *
     * @param taskId       the task ID
     * @param serviceType  the service type (ingest, vectorPopulation, vlm, etc.)
     * @param heartbeat    the heartbeat message from the subprocess
     */
    public void broadcastHeartbeat(String taskId, String serviceType, SubprocessMessage.Heartbeat heartbeat) {
        if (messagingTemplate == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("serviceType", serviceType);
            payload.put("timestamp", Instant.now().toString());
            payload.put("uptimeMs", heartbeat.uptimeMs());
            payload.put("heapUsedBytes", heartbeat.heapUsedBytes());
            payload.put("heapMaxBytes", heartbeat.heapMaxBytes());
            payload.put("heapUsagePercent", round(heartbeat.memoryUsagePercent()));
            payload.put("offHeapUsedBytes", heartbeat.offHeapUsedBytes());
            payload.put("offHeapMaxBytes", heartbeat.offHeapMaxBytes());
            payload.put("offHeapUsagePercent", round(heartbeat.offHeapUsagePercent()));
            payload.put("gpuUsedBytes", heartbeat.gpuUsedBytes());
            payload.put("gpuMaxBytes", heartbeat.gpuMaxBytes());
            payload.put("gpuUsagePercent", round(heartbeat.gpuUsagePercent()));

            messagingTemplate.convertAndSend(TOPIC_SUBPROCESS_HEARTBEAT, payload);
        } catch (Exception e) {
            logger.debug("Failed to broadcast subprocess heartbeat: {}", e.getMessage());
        }
    }

    /**
     * Broadcast a phase transition with duration info from a subprocess.
     *
     * @param taskId         the task ID
     * @param serviceType    the service type
     * @param fromPhase      the phase being exited
     * @param toPhase        the phase being entered
     * @param phaseDurationMs how long the previous phase lasted (0 if unknown)
     */
    public void broadcastPhaseTransition(String taskId, String serviceType,
                                          String fromPhase, String toPhase,
                                          long phaseDurationMs) {
        if (messagingTemplate == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("serviceType", serviceType);
            payload.put("timestamp", Instant.now().toString());
            payload.put("fromPhase", fromPhase);
            payload.put("toPhase", toPhase);
            payload.put("phaseDurationMs", phaseDurationMs);

            messagingTemplate.convertAndSend(TOPIC_SUBPROCESS_PHASE, payload);
        } catch (Exception e) {
            logger.debug("Failed to broadcast subprocess phase transition: {}", e.getMessage());
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
