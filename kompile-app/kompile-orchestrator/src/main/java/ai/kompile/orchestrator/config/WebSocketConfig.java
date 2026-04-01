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
package ai.kompile.orchestrator.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time streaming of:
 * - Chat/conversation messages
 * - LLM response tokens
 * - Task output logs
 * - State machine transitions
 * - Workflow progress updates
 *
 * <p>Uses STOMP over WebSocket/SockJS for browser compatibility.
 *
 * <p>Topic structure:
 * <ul>
 *   <li>/topic/orchestrator/state - State change events</li>
 *   <li>/topic/orchestrator/task - Task lifecycle events</li>
 *   <li>/topic/orchestrator/output/{taskId} - Real-time task output</li>
 *   <li>/topic/orchestrator/llm - LLM session events</li>
 *   <li>/topic/orchestrator/llm/{sessionId}/tokens - LLM token streaming</li>
 *   <li>/topic/orchestrator/conversation/{sessionId} - Conversation messages</li>
 *   <li>/topic/orchestrator/workflow - Workflow progress</li>
 *   <li>/topic/orchestrator/heartbeat - Keep-alive signals</li>
 * </ul>
 */
@Configuration("orchestratorWebSocketConfig")
@EnableWebSocketMessageBroker
@ConditionalOnClass(name = "ai.kompile.orchestrator.config.WebSocketConfig")
@ConditionalOnProperty(name = "kompile.orchestrator.websocket.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final OrchestratorProperties properties;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple in-memory message broker for subscriptions
        registry.enableSimpleBroker("/topic", "/queue");

        // Set prefix for messages bound for @MessageMapping methods
        registry.setApplicationDestinationPrefixes("/app");

        // Set prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");

        log.info("WebSocket message broker configured with topics: /topic, /queue");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Primary WebSocket endpoint with SockJS fallback
        registry.addEndpoint("/ws/orchestrator")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setHeartbeatTime(properties.getWebsocket().getHeartbeatInterval().toMillis());

        // Raw WebSocket endpoint (no SockJS) for native clients
        registry.addEndpoint("/ws/orchestrator/raw")
                .setAllowedOriginPatterns("*");

        log.info("WebSocket STOMP endpoints registered: /ws/orchestrator (SockJS), /ws/orchestrator/raw");
    }
}
