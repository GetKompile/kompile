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
package ai.kompile.openclaw.gateway;

import ai.kompile.openclaw.model.OpenClawRequest;
import ai.kompile.openclaw.model.OpenClawResponse;
import ai.kompile.openclaw.agent.OpenClawAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class OpenClawWebSocketHandler extends TextWebSocketHandler {

    private final OpenClawAgentService agentService;
    private final ObjectMapper objectMapper;

    private final Map<String, String> sessionToSessionKey = new ConcurrentHashMap<>();

    public OpenClawWebSocketHandler(OpenClawAgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connection established: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("WebSocket message received: {}", payload);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);

            String agentId = (String) request.getOrDefault("agentId", "jarvis");
            String userMessage = (String) request.get("message");
            String sessionKey = sessionToSessionKey.computeIfAbsent(
                    session.getId(),
                    id -> (String) request.getOrDefault("sessionKey", "ws:" + id)
            );

            if (userMessage == null || userMessage.isBlank()) {
                sendError(session, "Message is required");
                return;
            }

            OpenClawRequest openClawRequest = OpenClawRequest.builder()
                    .agentId(agentId)
                    .sessionKey(sessionKey)
                    .message(userMessage)
                    .build();

            OpenClawResponse response = agentService.execute(openClawRequest);

            sendResponse(session, response);

        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
            sendError(session, "Error processing message: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {} - {}", session.getId(), status);
        sessionToSessionKey.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error: {}", session.getId(), exception);
    }

    private void sendResponse(WebSocketSession session, OpenClawResponse response) throws IOException {
        String json = objectMapper.writeValueAsString(response);
        session.sendMessage(new TextMessage(json));
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        OpenClawResponse response = OpenClawResponse.error(error);
        sendResponse(session, response);
    }
}
