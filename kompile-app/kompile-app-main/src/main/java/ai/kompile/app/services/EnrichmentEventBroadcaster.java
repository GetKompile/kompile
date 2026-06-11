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
package ai.kompile.app.services;

import ai.kompile.enrichment.domain.EnrichmentProgressEvent;
import ai.kompile.enrichment.domain.EnrichmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens for {@link EnrichmentProgressEvent}s published by the enrichment pipeline
 * and forwards them to the frontend via WebSocket STOMP topic.
 */
@Service
public class EnrichmentEventBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentEventBroadcaster.class);

    private static final String TOPIC = "/topic/enrichment/progress";

    private final SimpMessagingTemplate messagingTemplate;

    public EnrichmentEventBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onEnrichmentProgress(EnrichmentProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", event.getJobId());
        payload.put("factSheetId", event.getFactSheetId());
        payload.put("eventType", event.getEventType().name());
        payload.put("phase", event.getPhase());
        payload.put("step", event.getStep());
        payload.put("progressPercent", event.getProgressPercent());
        payload.put("message", event.getMessage());

        EnrichmentResult result = event.getResult();
        if (result != null) {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("chunksDeduped", result.getChunksDeduped());
            resultMap.put("nodesPruned", result.getNodesPruned());
            resultMap.put("validationFixed", result.getValidationFixed());
            resultMap.put("entitiesCategorized", result.getEntitiesCategorized());
            resultMap.put("processDefinitionsCreated", result.getProcessDefinitionsCreated());
            payload.put("result", resultMap);
        }

        try {
            messagingTemplate.convertAndSend(TOPIC, payload);
            log.debug("Broadcast enrichment event: {} for job {} factSheet {}",
                    event.getEventType(), event.getJobId(), event.getFactSheetId());
        } catch (Exception e) {
            log.warn("Failed to broadcast enrichment event: {}", e.getMessage());
        }
    }
}
