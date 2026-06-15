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

package ai.kompile.a2a.service;

import ai.kompile.a2a.model.*;
import ai.kompile.a2a.server.AgentCardProvider;
import ai.kompile.a2a.server.KompileAgentExecutor;
import ai.kompile.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages A2A server-side functionality — exposes kompile agents to remote consumers
 * via the A2A protocol.
 * <p>
 * This service bridges the gap between the A2A JSON-RPC interface (handled by the
 * controller) and kompile's internal agent execution (subprocess or API-based).
 * An {@link AgentExecutionBackend} must be set to provide the actual agent execution
 * capability, typically wired from kompile-app-main's AgentChatService.
 */
@Service
public class A2AServerService {

    private static final Logger logger = LoggerFactory.getLogger(A2AServerService.class);

    private final Map<String, KompileAgentExecutor> executors = new ConcurrentHashMap<>();
    private KompileAgentExecutor.AgentBackend agentBackend;

    /**
     * Functional interface for plugging in the actual agent execution.
     * Set from kompile-app-main at startup.
     */
    @FunctionalInterface
    public interface AgentExecutionBackend extends KompileAgentExecutor.AgentBackend {}

    /**
     * Set the backend that performs actual agent execution.
     * Called during application startup from the configuration that bridges
     * AgentChatService into A2A.
     */
    public void setAgentBackend(AgentExecutionBackend backend) {
        this.agentBackend = backend;
        logger.info("A2A server backend configured");
    }

    /**
     * Get the platform-level AgentCard exposing all available kompile agents.
     */
    public AgentCard getPlatformCard(String baseUrl, List<AgentProvider> agents) {
        return AgentCardProvider.platformCard(baseUrl, agents);
    }

    /**
     * Get an AgentCard for a specific kompile agent.
     */
    public AgentCard getAgentCard(AgentProvider provider, String baseUrl) {
        return AgentCardProvider.fromAgentProvider(provider, baseUrl);
    }

    /**
     * Handle a synchronous message/send JSON-RPC call.
     */
    public JsonRpcResponse handleJsonRpc(JsonRpcRequest request, String agentName) {
        if (agentBackend == null) {
            return JsonRpcResponse.error(request.getId(), JsonRpcResponse.INTERNAL_ERROR,
                    "A2A server backend not configured");
        }

        String method = request.getMethod();
        Map<String, Object> params = request.getParams();

        try {
            switch (method) {
                case JsonRpcRequest.METHOD_MESSAGE_SEND:
                    return handleMessageSend(request.getId(), params, agentName);

                case JsonRpcRequest.METHOD_TASKS_GET:
                    return handleTasksGet(request.getId(), params, agentName);

                case JsonRpcRequest.METHOD_TASKS_CANCEL:
                    return handleTasksCancel(request.getId(), params, agentName);

                default:
                    return JsonRpcResponse.error(request.getId(),
                            JsonRpcResponse.METHOD_NOT_FOUND,
                            "Unknown method: " + method);
            }
        } catch (Exception e) {
            logger.error("A2A JSON-RPC error for method {}: {}", method, e.getMessage(), e);
            return JsonRpcResponse.error(request.getId(),
                    JsonRpcResponse.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Handle a streaming message/stream call.
     */
    public void handleStream(JsonRpcRequest request, String agentName, SseEmitter emitter) {
        if (agentBackend == null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("code", JsonRpcResponse.INTERNAL_ERROR,
                                "message", "A2A server backend not configured")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return;
        }

        KompileAgentExecutor executor = getOrCreateExecutor(agentName);

        @SuppressWarnings("unchecked")
        Map<String, Object> messageMap = (Map<String, Object>) request.getParams().get("message");
        String contextId = (String) request.getParams().get("contextId");

        A2AMessage message = parseMessage(messageMap);
        executor.handleMessageStream(message, contextId, emitter);
    }

    /**
     * Check if the A2A server is ready to accept requests.
     */
    public boolean isReady() {
        return agentBackend != null;
    }

    @PreDestroy
    public void shutdown() {
        executors.values().forEach(KompileAgentExecutor::shutdown);
        executors.clear();
    }

    private JsonRpcResponse handleMessageSend(Object requestId, Map<String, Object> params, String agentName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> messageMap = (Map<String, Object>) params.get("message");
        String contextId = (String) params.get("contextId");

        A2AMessage message = parseMessage(messageMap);
        KompileAgentExecutor executor = getOrCreateExecutor(agentName);
        A2ATask task = executor.handleMessageSend(message, contextId);

        return JsonRpcResponse.success(requestId, task);
    }

    private JsonRpcResponse handleTasksGet(Object requestId, Map<String, Object> params, String agentName) {
        String taskId = (String) params.get("taskId");
        if (taskId == null) {
            return JsonRpcResponse.error(requestId, JsonRpcResponse.INVALID_PARAMS, "taskId required");
        }

        KompileAgentExecutor executor = getOrCreateExecutor(agentName);
        A2ATask task = executor.getTask(taskId);

        if (task == null) {
            return JsonRpcResponse.error(requestId, JsonRpcResponse.TASK_NOT_FOUND,
                    "Task not found: " + taskId);
        }

        return JsonRpcResponse.success(requestId, task);
    }

    private JsonRpcResponse handleTasksCancel(Object requestId, Map<String, Object> params, String agentName) {
        String taskId = (String) params.get("taskId");
        if (taskId == null) {
            return JsonRpcResponse.error(requestId, JsonRpcResponse.INVALID_PARAMS, "taskId required");
        }

        KompileAgentExecutor executor = getOrCreateExecutor(agentName);
        boolean cancelled = executor.cancelTask(taskId);

        if (!cancelled) {
            return JsonRpcResponse.error(requestId, JsonRpcResponse.TASK_NOT_CANCELABLE,
                    "Task cannot be cancelled: " + taskId);
        }

        A2ATask task = executor.getTask(taskId);
        return JsonRpcResponse.success(requestId, task);
    }

    private KompileAgentExecutor getOrCreateExecutor(String agentName) {
        String name = agentName != null ? agentName : "default";
        return executors.computeIfAbsent(name, n ->
                new KompileAgentExecutor(agentBackend, n));
    }

    @SuppressWarnings("unchecked")
    private A2AMessage parseMessage(Map<String, Object> messageMap) {
        if (messageMap == null) {
            return A2AMessage.userText("");
        }

        String role = (String) messageMap.getOrDefault("role", "user");
        var parts = (List<Map<String, Object>>) messageMap.get("parts");

        if (parts == null || parts.isEmpty()) {
            return A2AMessage.builder().role(role).parts(List.of()).build();
        }

        List<A2AMessage.Part> parsedParts = parts.stream().map(p -> {
            String type = (String) p.getOrDefault("type", "text");
            if ("text".equals(type)) {
                return A2AMessage.Part.text((String) p.get("text"));
            } else if ("data".equals(type)) {
                return A2AMessage.Part.data((Map<String, Object>) p.get("data"));
            }
            return A2AMessage.Part.text(p.toString());
        }).toList();

        return A2AMessage.builder().role(role).parts(parsedParts).build();
    }
}
