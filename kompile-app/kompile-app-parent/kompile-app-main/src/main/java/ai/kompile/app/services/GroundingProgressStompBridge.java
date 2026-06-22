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

import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges {@link GroundingProgressEvent}s (published low in kompile-knowledge-graph by
 * {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}) to two
 * STOMP topics so the Angular frontend can subscribe for real-time cascade progress:
 * <ul>
 *   <li>{@code /topic/grounding/{factSheetId}} — narrow, per-fact-sheet subscription</li>
 *   <li>{@code /topic/grounding/all} — global fan-out for dashboards and admin panels</li>
 * </ul>
 *
 * <h3>Design</h3>
 * <p>Mirrors the pattern used by {@link EnrichmentEventBroadcaster} and
 * {@link EmbeddingStatusBroadcaster}: a lightweight {@code @EventListener} converts the
 * domain event into a JSON-serialisable {@link Map} and sends it via
 * {@link SimpMessagingTemplate}. The JSON shape matches the contract agreed with the
 * frontend agent:
 * <pre>
 * {
 *   factSheetId : number,
 *   cascadeId   : string,
 *   trigger     : "CRAWL"|"CHANNEL"|"ASSERT"|"MANUAL"|"CASCADE",
 *   stage       : string,
 *   status      : "STARTED"|"RUNNING"|"DONE"|"ERROR",
 *   message     : string,
 *   stepIndex   : number,
 *   totalSteps  : number,
 *   data        : object|null,
 *   timestamp   : number   // epoch ms
 * }
 * </pre>
 *
 * <h3>Coverage of the async cascade path</h3>
 * <p>The async post-crawl cascade runs inside
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook} which previously passed
 * a no-op progress callback to {@code GraphHydrationOrchestrator.enrich()}. Because
 * {@code IncrementalReasoningOrchestrator} now publishes events via
 * {@link org.springframework.context.ApplicationEventPublisher} (not the old callback),
 * this bridge receives those events <em>for free</em> — no change to the cascade hook was
 * required beyond threading the trigger label.</p>
 *
 * <h3>Thread safety</h3>
 * <p>{@link SimpMessagingTemplate} is thread-safe. The listener may be called from any
 * thread (the per-factSheet cascade executor, the Spring event dispatcher thread, etc.).</p>
 */
@Component
public class GroundingProgressStompBridge {

    private static final Logger log = LoggerFactory.getLogger(GroundingProgressStompBridge.class);

    /** Per-fact-sheet topic prefix. Full topic: "/topic/grounding/{factSheetId}". */
    private static final String TOPIC_PER_SHEET_PREFIX = "/topic/grounding/";

    /** Global fan-out topic — all cascade progress from all fact sheets. */
    private static final String TOPIC_ALL = "/topic/grounding/all";

    private final SimpMessagingTemplate messagingTemplate;

    public GroundingProgressStompBridge(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Forward a {@link GroundingProgressEvent} to both STOMP topics.
     *
     * <p>Exceptions from {@link SimpMessagingTemplate#convertAndSend} are caught and logged
     * at DEBUG level so that a WebSocket send failure never propagates back to the cascade thread
     * and aborts inference.</p>
     */
    @EventListener
    public void onGroundingProgress(GroundingProgressEvent event) {
        Map<String, Object> payload = buildPayload(event);
        String perSheetTopic = TOPIC_PER_SHEET_PREFIX + event.getFactSheetId();

        try {
            messagingTemplate.convertAndSend(perSheetTopic, payload);
        } catch (Exception e) {
            log.debug("GroundingProgressStompBridge: failed to send to {} — {}",
                    perSheetTopic, e.getMessage());
        }

        try {
            messagingTemplate.convertAndSend(TOPIC_ALL, payload);
        } catch (Exception e) {
            log.debug("GroundingProgressStompBridge: failed to send to {} — {}",
                    TOPIC_ALL, e.getMessage());
        }

        log.debug("GroundingProgress factSheet={} stage={} status={} step={}/{}",
                event.getFactSheetId(), event.getStage(), event.getStatus(),
                event.getStepIndex(), event.getTotalSteps());
    }

    /**
     * Convert a {@link GroundingProgressEvent} into the JSON-serialisable map matching
     * the frontend contract.
     */
    private static Map<String, Object> buildPayload(GroundingProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("factSheetId", event.getFactSheetId());
        payload.put("cascadeId",   event.getCascadeId());
        payload.put("trigger",     event.getTrigger());
        payload.put("stage",       event.getStage());
        payload.put("status",      event.getStatus());
        payload.put("message",     event.getMessage());
        payload.put("stepIndex",   event.getStepIndex());
        payload.put("totalSteps",  event.getTotalSteps());
        payload.put("data",        event.getData());    // may be null; Jackson serialises as null
        payload.put("timestamp",   event.getEventTimestamp());
        return payload;
    }
}
