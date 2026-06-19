/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.event;

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
 * Broadcasts project registration events via WebSocket so the web UI
 * can show real-time notifications when CLI agents register projects.
 *
 * <p>Topic: {@code /topic/project/registration}</p>
 */
@Service
public class ProjectRegistrationBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(ProjectRegistrationBroadcaster.class);

    public static final String TOPIC = "/topic/project/registration";

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public ProjectRegistrationBroadcaster(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onProjectRegistration(ProjectRegistrationEvent event) {
        if (messagingTemplate == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", event.getEventType().name());
            payload.put("projectId", event.getProjectId());
            payload.put("timestamp", Instant.now().toString());

            if (event.getProjectName() != null) {
                payload.put("projectName", event.getProjectName());
            }
            if (event.getDirectory() != null) {
                payload.put("directory", event.getDirectory());
            }
            payload.put("active", event.isActive());

            if (event.getIndexMetadata() != null && !event.getIndexMetadata().isEmpty()) {
                payload.put("indexMetadata", event.getIndexMetadata());
            }

            messagingTemplate.convertAndSend(TOPIC, payload);
            logger.info("Broadcast project registration event: {} for '{}'",
                    event.getEventType(), event.getProjectId());
        } catch (Exception e) {
            logger.debug("Failed to broadcast project registration event: {}",
                    e.getMessage());
        }
    }
}
