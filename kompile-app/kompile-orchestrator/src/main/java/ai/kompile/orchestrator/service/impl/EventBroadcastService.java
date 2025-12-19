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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.event.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for broadcasting orchestrator events to WebSocket subscribers.
 */
@Slf4j
public class EventBroadcastService {

    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Optional: WebSocket template for STOMP messaging
    private SimpMessagingTemplate messagingTemplate;

    // Track subscribers for direct WebSocket
    private final Map<String, Set<EventListener>> subscribers = new ConcurrentHashMap<>();

    // Topic destinations
    private static final String TOPIC_BASE = "/topic/orchestrator";
    private static final String TOPIC_STATE = TOPIC_BASE + "/state";
    private static final String TOPIC_TASK = TOPIC_BASE + "/task";
    private static final String TOPIC_LLM = TOPIC_BASE + "/llm";
    private static final String TOPIC_WORKFLOW = TOPIC_BASE + "/workflow";
    private static final String TOPIC_OUTPUT = TOPIC_BASE + "/output";

    public EventBroadcastService(OrchestratorProperties properties) {
        this.properties = properties;
    }

    public void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // ==================== Spring Event Listeners ====================

    @EventListener
    public void onStateChange(StateChangeEvent event) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getEventType().name());
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("previousStateId", event.getPreviousStateId());
        payload.put("newStateId", event.getNewStateId());
        payload.put("timestamp", event.getEventTimestamp());
        payload.put("message", event.getMessage());

        broadcast(TOPIC_STATE, event.getOrchestratorInstanceId(), payload);
    }

    @EventListener
    public void onTaskEvent(TaskEvent event) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getEventType().name());
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("taskInstanceId", event.getTaskInstanceId());
        payload.put("taskStatus", event.getStatus() != null ? event.getStatus().name() : null);
        payload.put("exitCode", event.getExitCode());
        payload.put("timestamp", event.getEventTimestamp());
        payload.put("message", event.getMessage());

        broadcast(TOPIC_TASK, event.getOrchestratorInstanceId(), payload);
    }

    @EventListener
    public void onTaskOutput(TaskOutputEvent event) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "TASK_OUTPUT");
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("taskInstanceId", event.getTaskInstanceId());
        payload.put("line", event.getOutputLine());
        payload.put("timestamp", event.getEventTimestamp());

        // Use a task-specific topic for output streaming
        String topic = TOPIC_OUTPUT + "/" + event.getTaskInstanceId();
        broadcast(topic, event.getOrchestratorInstanceId(), payload);
    }

    @EventListener
    public void onLlmSessionEvent(LlmSessionEvent event) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getEventType().name());
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("sessionId", event.getSessionId());
        payload.put("providerId", event.getProviderId());
        payload.put("status", event.getStatus() != null ? event.getStatus().name() : null);
        payload.put("timestamp", event.getEventTimestamp());
        payload.put("message", event.getMessage());

        broadcast(TOPIC_LLM, event.getOrchestratorInstanceId(), payload);
    }

    @EventListener
    public void onWorkflowEvent(WorkflowEvent event) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getEventType().name());
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("workflowId", event.getWorkflowId());
        payload.put("status", event.getStatus() != null ? event.getStatus().name() : null);
        payload.put("currentStep", event.getCurrentStep());
        payload.put("totalSteps", event.getTotalSteps());
        payload.put("timestamp", event.getEventTimestamp());
        payload.put("message", event.getMessage());

        broadcast(TOPIC_WORKFLOW, event.getOrchestratorInstanceId(), payload);
    }

    @EventListener
    public void onOrchestratorEvent(OrchestratorEvent event) {
        if (!isEnabled()) return;

        // Generic event handling for events not covered above
        if (event instanceof StateChangeEvent ||
            event instanceof TaskEvent ||
            event instanceof TaskOutputEvent ||
            event instanceof LlmSessionEvent ||
            event instanceof WorkflowEvent) {
            return; // Already handled by specific listeners
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.getEventType().name());
        payload.put("orchestratorInstanceId", event.getOrchestratorInstanceId());
        payload.put("timestamp", event.getEventTimestamp());
        payload.put("message", event.getMessage());

        broadcast(TOPIC_BASE, event.getOrchestratorInstanceId(), payload);
    }

    // ==================== Broadcasting ====================

    private void broadcast(String topic, String orchestratorInstanceId, Map<String, Object> payload) {
        String json = toJson(payload);
        if (json == null) {
            return;
        }

        // Broadcast via STOMP if available
        if (messagingTemplate != null) {
            try {
                // General topic
                messagingTemplate.convertAndSend(topic, json);

                // Instance-specific topic
                if (orchestratorInstanceId != null) {
                    String instanceTopic = topic + "/" + orchestratorInstanceId;
                    messagingTemplate.convertAndSend(instanceTopic, json);
                }
            } catch (Exception e) {
                log.debug("Failed to broadcast via STOMP: {}", e.getMessage());
            }
        }

        // Log for debugging
        log.debug("Broadcast to {}: {}", topic, json);
    }

    /**
     * Broadcast a custom message.
     */
    public void broadcastCustom(String orchestratorInstanceId, String eventType, Map<String, Object> data) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>(data);
        payload.put("type", eventType);
        payload.put("orchestratorInstanceId", orchestratorInstanceId);
        payload.put("timestamp", System.currentTimeMillis());

        broadcast(TOPIC_BASE + "/custom", orchestratorInstanceId, payload);
    }

    /**
     * Broadcast output line (for streaming).
     */
    public void broadcastOutput(String orchestratorInstanceId, Long entityId, String entityType, String line) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "OUTPUT_LINE");
        payload.put("orchestratorInstanceId", orchestratorInstanceId);
        payload.put("entityId", entityId);
        payload.put("entityType", entityType);
        payload.put("line", line);
        payload.put("timestamp", System.currentTimeMillis());

        broadcast(TOPIC_OUTPUT + "/" + entityType.toLowerCase() + "/" + entityId, orchestratorInstanceId, payload);
    }

    /**
     * Broadcast heartbeat.
     */
    public void broadcastHeartbeat(String orchestratorInstanceId) {
        if (!isEnabled()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "HEARTBEAT");
        payload.put("orchestratorInstanceId", orchestratorInstanceId);
        payload.put("timestamp", System.currentTimeMillis());

        broadcast(TOPIC_BASE + "/heartbeat", orchestratorInstanceId, payload);
    }

    // ==================== Utility ====================

    private boolean isEnabled() {
        return properties.getWebsocket().isEnabled();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event: {}", e.getMessage());
            return null;
        }
    }
}
