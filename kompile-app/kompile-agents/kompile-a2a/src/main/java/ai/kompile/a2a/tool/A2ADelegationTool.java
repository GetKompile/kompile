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

package ai.kompile.a2a.tool;

import ai.kompile.a2a.client.A2AClient;
import ai.kompile.a2a.client.A2AClientException;
import ai.kompile.a2a.model.*;
import ai.kompile.a2a.service.A2ARegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP Tool for delegating tasks to remote A2A agents.
 * <p>
 * Allows an agent connected via MCP to discover and communicate with
 * remote agents that implement the A2A protocol. This enables cross-platform
 * agent collaboration — e.g., a Claude agent delegating specialized tasks
 * to a Gemini agent running elsewhere, or coordinating with agents built
 * on any A2A-compatible framework.
 */
@Component
public class A2ADelegationTool {

    private static final Logger logger = LoggerFactory.getLogger(A2ADelegationTool.class);

    private final A2ARegistryService registryService;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();
    private final Map<String, AsyncA2ADelegation> asyncDelegations = new ConcurrentHashMap<>();

    @Autowired
    public A2ADelegationTool(@Autowired(required = false) A2ARegistryService registryService) {
        this.registryService = registryService;
    }

    // ========================================================================
    // Input Records
    // ========================================================================

    public record DiscoverA2AAgentInput(String baseUrl) {}

    public record ListA2AAgentsInput() {}

    public record SendA2ATaskInput(
            String agentId,
            String message,
            String contextId,
            Integer timeoutSeconds
    ) {}

    public record SendA2ATaskAsyncInput(
            String agentId,
            String message,
            String contextId
    ) {}

    public record GetA2AResultInput(String delegationId, Boolean blocking) {}

    public record CancelA2ATaskInput(String delegationId) {}

    public record PingA2AAgentInput(String agentId) {}

    // ========================================================================
    // Discovery
    // ========================================================================

    @Tool(name = "discover_a2a_agent",
            description = "Discover and register a remote A2A (Agent-to-Agent) agent by its base URL. " +
                    "Fetches the agent's card from /.well-known/agent-card.json to learn its name, " +
                    "capabilities, and available skills. Use this before sending tasks to a remote agent. " +
                    "Example: discover_a2a_agent('http://remote-host:9090') to connect to another kompile " +
                    "instance or any A2A-compatible agent.")
    public Map<String, Object> discoverA2AAgent(DiscoverA2AAgentInput input) {
        if (registryService == null) {
            return Map.of("status", "error", "error", "A2A module not available");
        }

        if (input.baseUrl() == null || input.baseUrl().isBlank()) {
            return Map.of("status", "error", "error", "baseUrl is required");
        }

        try {
            A2AAgentConfig config = registryService.discoverAgent(input.baseUrl());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("agentId", config.getId());
            result.put("agentName", config.getName());
            result.put("baseUrl", config.getBaseUrl());
            result.put("reachable", config.isReachable());

            if (config.getAgentCard() != null) {
                AgentCard card = config.getAgentCard();
                result.put("description", card.getDescription());
                result.put("protocolVersion", card.getProtocolVersion());
                if (card.getSkills() != null) {
                    result.put("skills", card.getSkills().stream()
                            .map(s -> Map.of(
                                    "id", s.getId(),
                                    "name", s.getName(),
                                    "description", s.getDescription() != null ? s.getDescription() : ""))
                            .toList());
                }
                if (card.getCapabilities() != null) {
                    result.put("streaming", card.getCapabilities().isStreaming());
                }
            }

            result.put("message", "Agent discovered. Use send_a2a_task with agentId='" +
                    config.getId() + "' to send tasks.");
            return result;
        } catch (A2AClientException e) {
            return Map.of("status", "error", "error", "Discovery failed: " + e.getMessage());
        }
    }

    /**
     * List all registered remote A2A agents.
     */
    @Tool(name = "list_a2a_agents",
            description = "List all registered remote A2A agents that are available for task delegation. " +
                    "Shows each agent's name, URL, capabilities, and connectivity status. " +
                    "Use discover_a2a_agent first to register new agents.")
    public Map<String, Object> listA2AAgents(ListA2AAgentsInput input) {
        if (registryService == null) {
            return Map.of("status", "error", "error", "A2A module not available");
        }

        List<A2AAgentConfig> agents = registryService.listAgents();

        List<Map<String, Object>> agentList = agents.stream().map(a -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", a.getId());
            info.put("name", a.getName());
            info.put("baseUrl", a.getBaseUrl());
            info.put("enabled", a.isEnabled());
            info.put("reachable", a.isReachable());
            if (a.getAgentCard() != null && a.getAgentCard().getSkills() != null) {
                info.put("skillCount", a.getAgentCard().getSkills().size());
            }
            if (a.getLastContactedAt() != null) {
                info.put("lastContacted", a.getLastContactedAt().toString());
            }
            return info;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("count", agents.size());
        result.put("agents", agentList);
        return result;
    }

    // ========================================================================
    // Task Delegation
    // ========================================================================

    /**
     * Send a task synchronously to a remote A2A agent and wait for the result.
     */
    @Tool(name = "send_a2a_task",
            description = "Send a task to a remote A2A agent and wait for the response. " +
                    "The remote agent processes the task using its own capabilities and returns the result. " +
                    "Use list_a2a_agents to see available agents. Specify agentId and message (the task/prompt). " +
                    "This enables cross-platform collaboration: delegate specialized work to agents running on " +
                    "other machines, in other frameworks (ADK, LangGraph, etc.), or using different LLM providers. " +
                    "For non-blocking calls, use send_a2a_task_async instead.")
    public Map<String, Object> sendA2ATask(SendA2ATaskInput input) {
        if (registryService == null) {
            return Map.of("status", "error", "error", "A2A module not available");
        }

        if (input.agentId() == null || input.agentId().isBlank()) {
            return Map.of("status", "error", "error", "agentId is required");
        }

        if (input.message() == null || input.message().isBlank()) {
            return Map.of("status", "error", "error", "message is required");
        }

        Optional<A2AClient> clientOpt = registryService.getClient(input.agentId());
        if (clientOpt.isEmpty()) {
            return Map.of("status", "error", "error", "Agent not found: " + input.agentId());
        }

        long startTime = System.currentTimeMillis();

        try {
            A2ATask task = clientOpt.get().sendMessage(
                    A2AMessage.userText(input.message()), input.contextId());

            return buildTaskResult(task, input.agentId(), startTime);
        } catch (A2AClientException e) {
            return Map.of(
                    "status", "error",
                    "agentId", input.agentId(),
                    "error", "Task failed: " + e.getMessage(),
                    "executionTimeMs", System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Send a task asynchronously to a remote A2A agent.
     */
    @Tool(name = "send_a2a_task_async",
            description = "Send a task asynchronously to a remote A2A agent. Returns a delegationId immediately. " +
                    "Use get_a2a_result to poll for or wait for the result. " +
                    "Useful for parallelizing work across multiple remote agents.")
    public Map<String, Object> sendA2ATaskAsync(SendA2ATaskAsyncInput input) {
        if (registryService == null) {
            return Map.of("status", "error", "error", "A2A module not available");
        }

        if (input.agentId() == null || input.message() == null) {
            return Map.of("status", "error", "error", "agentId and message are required");
        }

        Optional<A2AClient> clientOpt = registryService.getClient(input.agentId());
        if (clientOpt.isEmpty()) {
            return Map.of("status", "error", "error", "Agent not found: " + input.agentId());
        }

        String delegationId = "a2a-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        CompletableFuture<A2ATask> future = CompletableFuture.supplyAsync(() -> {
            try {
                return clientOpt.get().sendMessage(
                        A2AMessage.userText(input.message()), input.contextId());
            } catch (A2AClientException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);

        asyncDelegations.put(delegationId, new AsyncA2ADelegation(
                delegationId, input.agentId(), input.message(), future, startTime));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("delegationId", delegationId);
        result.put("agentId", input.agentId());
        result.put("message", "Task sent asynchronously. Use get_a2a_result with delegationId='" +
                delegationId + "' to get the result.");
        return result;
    }

    /**
     * Get the result of an async A2A delegation.
     */
    @Tool(name = "get_a2a_result",
            description = "Get the result of an asynchronous A2A task delegation. " +
                    "If blocking is true (default), waits until the remote agent completes. " +
                    "If false, returns current status immediately.")
    public Map<String, Object> getA2AResult(GetA2AResultInput input) {
        if (input.delegationId() == null) {
            return Map.of("status", "error", "error", "delegationId is required");
        }

        AsyncA2ADelegation delegation = asyncDelegations.get(input.delegationId());
        if (delegation == null) {
            return Map.of("status", "error", "error", "Delegation not found: " + input.delegationId());
        }

        if (delegation.future.isDone()) {
            asyncDelegations.remove(input.delegationId());
            try {
                A2ATask task = delegation.future.get();
                return buildTaskResult(task, delegation.agentId, delegation.startTime);
            } catch (Exception e) {
                return Map.of("status", "error", "error", "Failed: " + e.getMessage());
            }
        }

        boolean shouldWait = input.blocking() == null || input.blocking();
        if (!shouldWait) {
            return Map.of(
                    "status", "pending",
                    "delegationId", input.delegationId(),
                    "agentId", delegation.agentId,
                    "elapsedMs", System.currentTimeMillis() - delegation.startTime);
        }

        try {
            A2ATask task = delegation.future.get(600, TimeUnit.SECONDS);
            asyncDelegations.remove(input.delegationId());
            return buildTaskResult(task, delegation.agentId, delegation.startTime);
        } catch (TimeoutException e) {
            return Map.of("status", "timeout", "delegationId", input.delegationId());
        } catch (Exception e) {
            asyncDelegations.remove(input.delegationId());
            return Map.of("status", "error", "error", "Failed: " + e.getMessage());
        }
    }

    /**
     * Cancel an active async A2A delegation.
     */
    @Tool(name = "cancel_a2a_task",
            description = "Cancel an active async A2A task delegation.")
    public Map<String, Object> cancelA2ATask(CancelA2ATaskInput input) {
        if (input.delegationId() == null) {
            return Map.of("status", "error", "error", "delegationId is required");
        }

        AsyncA2ADelegation delegation = asyncDelegations.remove(input.delegationId());
        if (delegation == null) {
            return Map.of("status", "error", "error", "Delegation not found: " + input.delegationId());
        }

        delegation.future.cancel(true);
        return Map.of("status", "success", "message", "Delegation cancelled");
    }

    /**
     * Ping a remote A2A agent to check if it's reachable.
     */
    @Tool(name = "ping_a2a_agent",
            description = "Ping a remote A2A agent to check if it's reachable and responding. " +
                    "Returns connectivity status and updates the registry.")
    public Map<String, Object> pingA2AAgent(PingA2AAgentInput input) {
        if (registryService == null) {
            return Map.of("status", "error", "error", "A2A module not available");
        }

        if (input.agentId() == null) {
            return Map.of("status", "error", "error", "agentId is required");
        }

        boolean reachable = registryService.pingAgent(input.agentId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("agentId", input.agentId());
        result.put("reachable", reachable);
        return result;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Map<String, Object> buildTaskResult(A2ATask task, String agentId, long startTime) {
        Map<String, Object> result = new LinkedHashMap<>();

        boolean success = task.getStatus() != null &&
                task.getStatus().getState() == A2ATask.TaskState.COMPLETED;
        result.put("status", success ? "success" : "error");
        result.put("agentId", agentId);
        result.put("taskId", task.getId());

        if (task.getStatus() != null) {
            result.put("taskState", task.getStatus().getState().name());
            if (task.getStatus().getMessage() != null) {
                result.put("taskMessage", task.getStatus().getMessage());
            }
        }

        // Extract text from artifacts
        if (task.getArtifacts() != null && !task.getArtifacts().isEmpty()) {
            StringBuilder responseText = new StringBuilder();
            for (A2AArtifact artifact : task.getArtifacts()) {
                if (artifact.getParts() != null) {
                    for (A2AMessage.Part part : artifact.getParts()) {
                        if ("text".equals(part.getType()) && part.getText() != null) {
                            responseText.append(part.getText());
                        }
                    }
                }
            }
            result.put("response", responseText.toString());
            result.put("responseLength", responseText.length());
        }

        result.put("executionTimeMs", System.currentTimeMillis() - startTime);
        return result;
    }

    private record AsyncA2ADelegation(
            String delegationId,
            String agentId,
            String message,
            CompletableFuture<A2ATask> future,
            long startTime
    ) {}

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
