/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.model.event.LlmTokenEvent;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.service.impl.EventBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket controller for real-time streaming of:
 * - LLM response tokens
 * - Conversation messages
 * - Task output logs
 *
 * <p>Clients subscribe to topics to receive real-time updates:
 * <ul>
 *   <li>/topic/orchestrator/llm/tokens/{sessionId} - LLM token streaming</li>
 *   <li>/topic/orchestrator/conversation/{sessionId} - Conversation updates</li>
 *   <li>/topic/orchestrator/output/{taskId} - Task output logs</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class WebSocketStreamController {

    private final LlmIntegrationService llmIntegrationService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private EventBroadcastService eventBroadcastService;

    // Track active streaming subscriptions to clean up on disconnect
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /**
     * Handle subscription to LLM token stream.
     * When a client subscribes, start streaming tokens from the LLM provider.
     */
    @SubscribeMapping("/orchestrator/llm/tokens/{sessionId}")
    public void subscribeToLlmTokens(@DestinationVariable Long sessionId) {
        log.info("Client subscribed to LLM token stream for session: {}", sessionId);

        // Get the session to find orchestrator instance
        LlmSession session = llmIntegrationService.getSession(sessionId).orElse(null);
        if (session == null) {
            log.warn("Session not found for token streaming: {}", sessionId);
            return;
        }

        String orchestratorInstanceId = session.getOrchestratorInstanceId();
        String providerId = session.getProviderId();

        // Start streaming from the LLM provider
        AtomicInteger tokenIndex = new AtomicInteger(0);

        Disposable subscription = llmIntegrationService.streamOutput(sessionId)
                .doOnNext(token -> {
                    // Publish token event for broadcasting
                    LlmTokenEvent event = LlmTokenEvent.token(
                            this, orchestratorInstanceId, sessionId,
                            token, tokenIndex.getAndIncrement(), providerId);
                    eventPublisher.publishEvent(event);
                })
                .doOnComplete(() -> {
                    // Publish completion event
                    LlmTokenEvent event = LlmTokenEvent.complete(
                            this, orchestratorInstanceId, sessionId,
                            tokenIndex.get(), providerId);
                    eventPublisher.publishEvent(event);
                    cleanupStream(sessionId.toString());
                    log.info("LLM token stream completed for session: {}", sessionId);
                })
                .doOnError(error -> {
                    log.error("Error in LLM token stream for session {}: {}",
                            sessionId, error.getMessage());
                    if (eventBroadcastService != null) {
                        eventBroadcastService.broadcastStreamError(
                                orchestratorInstanceId, sessionId, error.getMessage());
                    }
                    cleanupStream(sessionId.toString());
                })
                .subscribe();

        // Track the subscription for cleanup
        activeStreams.put(sessionId.toString(), subscription);
    }

    /**
     * Handle request to start streaming for a session.
     * Client sends a message to /app/stream/start/{sessionId}
     */
    @MessageMapping("/stream/start/{sessionId}")
    public void startStreaming(@DestinationVariable Long sessionId) {
        log.info("Starting LLM stream for session: {}", sessionId);
        subscribeToLlmTokens(sessionId);
    }

    /**
     * Handle request to stop streaming for a session.
     * Client sends a message to /app/stream/stop/{sessionId}
     */
    @MessageMapping("/stream/stop/{sessionId}")
    public void stopStreaming(@DestinationVariable Long sessionId) {
        log.info("Stopping LLM stream for session: {}", sessionId);
        cleanupStream(sessionId.toString());
    }

    /**
     * Handle conversation message send.
     * Client sends a message to /app/conversation/{sessionId}/send
     */
    @MessageMapping("/conversation/{sessionId}/send")
    public void sendConversationMessage(@DestinationVariable Long sessionId, String message) {
        log.info("Received conversation message for session {}: {}",
                sessionId, message.substring(0, Math.min(100, message.length())));

        try {
            LlmSession updatedSession = llmIntegrationService.sendMessage(sessionId, message);
            log.debug("Message sent to session {}, status: {}",
                    sessionId, updatedSession.getStatus());
        } catch (Exception e) {
            log.error("Error sending message to session {}: {}", sessionId, e.getMessage());
            if (eventBroadcastService != null) {
                LlmSession session = llmIntegrationService.getSession(sessionId).orElse(null);
                if (session != null) {
                    eventBroadcastService.broadcastStreamError(
                            session.getOrchestratorInstanceId(), sessionId, e.getMessage());
                }
            }
        }
    }

    /**
     * Clean up a streaming subscription.
     */
    private void cleanupStream(String sessionKey) {
        Disposable subscription = activeStreams.remove(sessionKey);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.debug("Cleaned up stream subscription for: {}", sessionKey);
        }
    }

    /**
     * Clean up all streaming subscriptions (called on shutdown).
     */
    public void cleanupAllStreams() {
        log.info("Cleaning up {} active stream subscriptions", activeStreams.size());
        activeStreams.values().forEach(subscription -> {
            if (!subscription.isDisposed()) {
                subscription.dispose();
            }
        });
        activeStreams.clear();
    }
}
