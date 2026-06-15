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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts GPU lifecycle events (acquire, release, eviction, restoration)
 * via WebSocket so the frontend can show real-time GPU state changes
 * without relying solely on polling.
 */
@Service
public class GpuLifecycleEventBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(GpuLifecycleEventBroadcaster.class);

    public static final String TOPIC_GPU_LIFECYCLE = "/topic/gpu-lifecycle";

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public GpuLifecycleEventBroadcaster(@Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onGpuLifecycleEvent(GpuLifecycleEvent event) {
        if (messagingTemplate == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", event.getEventType().name());
            payload.put("timestamp", Instant.now().toString());

            if (event.getJobId() != null) {
                payload.put("jobId", event.getJobId());
            }
            if (event.getServiceType() != null) {
                payload.put("serviceType", event.getServiceType());
            }
            if (event.getDevice() != null) {
                payload.put("deviceId", event.getDevice().nvidiaSmiIndex());
                payload.put("deviceName", event.getDevice().name());
            }

            // Include event-specific data
            if (event.getData() != null && !event.getData().isEmpty()) {
                payload.putAll(event.getData());
            }

            messagingTemplate.convertAndSend(TOPIC_GPU_LIFECYCLE, payload);
        } catch (Exception e) {
            logger.debug("Failed to broadcast GPU lifecycle event: {}", e.getMessage());
        }
    }
}
