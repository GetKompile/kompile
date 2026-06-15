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

package ai.kompile.app.tools;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP Tool for inter-agent chat and task delegation.
 * Allows one agent (e.g., Claude Code connected via MCP) to delegate tasks
 * to other agents (Claude CLI, Codex, Gemini) and collect their responses.
 */
@Component
public class AgentDelegationTool {

    private static final Logger logger = LoggerFactory.getLogger(AgentDelegationTool.class);

    private final AgentChatService agentChatService;
    private final AgentRegistryService agentRegistry;
    private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            r -> { Thread t = new Thread(r, "agent-delegation"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    // Track async delegations
    private final Map<String, AsyncDelegation> asyncDelegations = new ConcurrentHashMap<>();

    @Autowired
    public AgentDelegationTool(
            @Autowired(required = false) AgentChatService agentChatService,
            @Autowired(required = false) AgentRegistryService agentRegistry) {
        this.agentChatService = agentChatService;
        this.agentRegistry = agentRegistry;
        logger.info("AgentDelegationTool initialized - AgentChatService: {}, AgentRegistry: {}",
                agentChatService != null ? "available" : "unavailable",
                agentRegistry != null ? "available" : "unavailable");
    }

    // ========================================================================
    // Input Records
    // ========================================================================

    public record DelegateTaskInput(
            String agentName,
            String message,
            String workingDirectory,
            Boolean enableRag,
            Boolean enableGraphRag,
            Boolean injectMcpTools,
            Boolean skipPermissions,
            Integer timeoutSeconds,
            Integer ragMaxResults,
            Double ragSimilarityThreshold,
            List<String> agentArgs
    ) {}

    public record DelegateTaskAsyncInput(
            String agentName,
            String message,
            String workingDirectory,
            Boolean enableRag,
            Boolean enableGraphRag,
            Boolean injectMcpTools,
            Boolean skipPermissions,
            Integer timeoutSeconds,
            Integer ragMaxResults,
            Double ragSimilarityThreshold,
            List<String> agentArgs
    ) {}

    public record GetDelegationResultInput(String delegationId, Boolean blocking) {}

    public record CancelDelegationInput(String delegationId) {}

    public record ListDelegationsInput() {}

    // ========================================================================
    // Synchronous Delegation
    // ========================================================================

    /**
     * Delegates a task to another agent and waits for the response.
     */
    @Tool(name = "delegate_task",
            description = "Delegates a task to another AI agent (Claude CLI, Codex, Gemini) and waits for their complete response. " +
                    "Use this for inter-agent collaboration: ask another agent to write code, analyze files, answer questions, or perform tasks. " +
                    "The delegated agent runs as a subprocess with optional RAG context and MCP tool access. " +
                    "Specify agentName (use list_agents to see available), message (the task/prompt), " +
                    "and optionally workingDirectory, enableRag, timeoutSeconds (default 300). " +
                    "Use agentArgs to pass extra CLI flags to the underlying agent command (e.g. ['--model', 'opus', '--max-turns', '5']). " +
                    "This call blocks until the agent completes. For non-blocking, use delegate_task_async.")
    public Map<String, Object> delegateTask(DelegateTaskInput input) {
        if (input.message() == null || input.message().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Message/task description cannot be empty");
        }

        if (agentChatService == null) {
            return Map.of("status", "error", "error", "Agent chat service not available");
        }

        if (agentRegistry == null) {
            return Map.of("status", "error", "error", "Agent registry not available");
        }

        // Resolve agent
        String agentName = resolveAgentName(input.agentName());
        if (agentName == null) {
            return Map.of("status", "error", "error", "No agent specified and no default agent available");
        }

        // Validate
        String validationError = validateAgent(agentName);
        if (validationError != null) {
            return Map.of("status", "error", "error", validationError);
        }

        int timeoutSeconds = input.timeoutSeconds() != null ? input.timeoutSeconds() : 300;

        logger.info("Delegating task to agent '{}', timeout: {}s", agentName, timeoutSeconds);

        AgentChatRequest request = buildRequest(agentName, input.message(), input.workingDirectory(),
                input.enableRag(), input.enableGraphRag(), input.injectMcpTools(),
                input.skipPermissions(), timeoutSeconds, input.ragMaxResults(), input.ragSimilarityThreshold(),
                input.agentArgs());

        long startTime = System.currentTimeMillis();
        AgentChatService.SyncChatResult chatResult = agentChatService.executeChatSync(request, timeoutSeconds);

        return buildResultMap(chatResult, agentName, startTime);
    }

    // ========================================================================
    // Async Delegation
    // ========================================================================

    /**
     * Delegates a task asynchronously and returns a delegation ID.
     */
    @Tool(name = "delegate_task_async",
            description = "Delegates a task to another AI agent asynchronously (non-blocking). Returns a delegationId immediately. " +
                    "Use get_delegation_result to poll for or wait for the result. " +
                    "Useful when you want to delegate tasks to multiple agents in parallel. " +
                    "Use agentArgs to pass extra CLI flags to the underlying agent command.")
    public Map<String, Object> delegateTaskAsync(DelegateTaskAsyncInput input) {
        if (input.message() == null || input.message().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Message/task description cannot be empty");
        }

        if (agentChatService == null) {
            return Map.of("status", "error", "error", "Agent chat service not available");
        }

        if (agentRegistry == null) {
            return Map.of("status", "error", "error", "Agent registry not available");
        }

        String agentName = resolveAgentName(input.agentName());
        if (agentName == null) {
            return Map.of("status", "error", "error", "No agent specified and no default agent available");
        }

        String validationError = validateAgent(agentName);
        if (validationError != null) {
            return Map.of("status", "error", "error", validationError);
        }

        int timeoutSeconds = input.timeoutSeconds() != null ? input.timeoutSeconds() : 300;
        String delegationId = "delegation-" + UUID.randomUUID().toString().substring(0, 8);

        AgentChatRequest request = buildRequest(agentName, input.message(), input.workingDirectory(),
                input.enableRag(), input.enableGraphRag(), input.injectMcpTools(),
                input.skipPermissions(), timeoutSeconds, input.ragMaxResults(), input.ragSimilarityThreshold(),
                input.agentArgs());

        long startTime = System.currentTimeMillis();

        CompletableFuture<AgentChatService.SyncChatResult> future = CompletableFuture.supplyAsync(
                () -> agentChatService.executeChatSync(request, timeoutSeconds), asyncExecutor);

        asyncDelegations.put(delegationId, new AsyncDelegation(delegationId, agentName,
                input.message(), future, startTime));

        logger.info("Async delegation '{}' started for agent '{}'", delegationId, agentName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("delegationId", delegationId);
        result.put("agentName", agentName);
        result.put("message", "Task delegated asynchronously. Use get_delegation_result with this delegationId to get the result.");

        return result;
    }

    /**
     * Gets the result of an async delegation.
     */
    @Tool(name = "get_delegation_result",
            description = "Gets the result of an asynchronous task delegation. If wait is true (default), blocks until the delegation completes. " +
                    "If wait is false, returns immediately with current status and any partial info.")
    public Map<String, Object> getDelegationResult(GetDelegationResultInput input) {
        if (input.delegationId() == null || input.delegationId().isEmpty()) {
            return Map.of("status", "error", "error", "Delegation ID is required");
        }

        AsyncDelegation delegation = asyncDelegations.get(input.delegationId());
        if (delegation == null) {
            return Map.of("status", "error", "error", "Delegation not found: " + input.delegationId());
        }

        boolean shouldWait = input.blocking() == null || input.blocking();

        if (delegation.future.isDone()) {
            // Already completed — return result
            asyncDelegations.remove(input.delegationId());
            try {
                AgentChatService.SyncChatResult chatResult = delegation.future.get();
                return buildResultMap(chatResult, delegation.agentName, delegation.startTime);
            } catch (Exception e) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "error");
                result.put("delegationId", input.delegationId());
                result.put("error", "Failed to get result: " + e.getMessage());
                return result;
            }
        }

        if (!shouldWait) {
            // Non-blocking check
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "pending");
            result.put("delegationId", input.delegationId());
            result.put("agentName", delegation.agentName);
            result.put("elapsedMs", System.currentTimeMillis() - delegation.startTime);
            result.put("message", "Delegation is still running. Call again with wait=true to block, or wait=false to poll.");
            return result;
        }

        // Blocking wait
        try {
            AgentChatService.SyncChatResult chatResult = delegation.future.get(600, TimeUnit.SECONDS);
            asyncDelegations.remove(input.delegationId());
            return buildResultMap(chatResult, delegation.agentName, delegation.startTime);
        } catch (TimeoutException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "timeout");
            result.put("delegationId", input.delegationId());
            result.put("message", "Timed out waiting for delegation result");
            return result;
        } catch (Exception e) {
            asyncDelegations.remove(input.delegationId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("delegationId", input.delegationId());
            result.put("error", "Failed: " + e.getMessage());
            return result;
        }
    }

    /**
     * Cancels an active delegation.
     */
    @Tool(name = "cancel_delegation",
            description = "Cancels an active async task delegation. Returns any partial output if available.")
    public Map<String, Object> cancelDelegation(CancelDelegationInput input) {
        if (input.delegationId() == null || input.delegationId().isEmpty()) {
            return Map.of("status", "error", "error", "Delegation ID is required");
        }

        AsyncDelegation delegation = asyncDelegations.remove(input.delegationId());
        if (delegation == null) {
            return Map.of("status", "error", "error", "Delegation not found or already completed: " + input.delegationId());
        }

        delegation.future.cancel(true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("delegationId", input.delegationId());
        result.put("message", "Delegation cancelled");
        result.put("elapsedMs", System.currentTimeMillis() - delegation.startTime);

        return result;
    }

    /**
     * Lists all active async delegations.
     */
    @Tool(name = "list_delegations",
            description = "Lists all currently active async task delegations showing which agents are working on what tasks.")
    public Map<String, Object> listDelegations(ListDelegationsInput input) {
        List<Map<String, Object>> delegations = new ArrayList<>();

        for (AsyncDelegation delegation : asyncDelegations.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("delegationId", delegation.delegationId);
            info.put("agentName", delegation.agentName);
            info.put("taskPreview", delegation.taskMessage.length() > 100
                    ? delegation.taskMessage.substring(0, 100) + "..." : delegation.taskMessage);
            info.put("completed", delegation.future.isDone());
            info.put("elapsedMs", System.currentTimeMillis() - delegation.startTime);
            delegations.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("activeDelegations", delegations.size());
        result.put("delegations", delegations);

        return result;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String resolveAgentName(String agentName) {
        if (agentName != null && !agentName.isEmpty()) {
            return agentName;
        }
        if (agentRegistry != null) {
            return agentRegistry.getDefaultAgent().map(AgentProvider::getName).orElse(null);
        }
        return null;
    }

    private String validateAgent(String agentName) {
        if (agentRegistry == null) return "Agent registry not available";

        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            List<String> available = agentRegistry.getAllAgents().stream()
                    .map(AgentProvider::getName).toList();
            return "Agent not found: " + agentName + ". Available: " + available;
        }

        AgentProvider agent = agentOpt.get();
        if (!agent.isAvailable()) {
            return "Agent not available: " + agentName + ". Is " + agent.getCommand() + " installed?";
        }

        if (agent.isApiAgent()) {
            return "API agents are not supported for delegation. Use CLI agents (claude-cli, codex-cli, gemini-cli).";
        }

        return null;
    }

    private AgentChatRequest buildRequest(String agentName, String message, String workingDirectory,
                                           Boolean enableRag, Boolean enableGraphRag, Boolean injectMcpTools,
                                           Boolean skipPermissions, int timeoutSeconds,
                                           Integer ragMaxResults, Double ragSimilarityThreshold,
                                           List<String> agentArgs) {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage(message);
        request.setAgentName(agentName);
        request.setSkipPermissions(skipPermissions != null ? skipPermissions : true);
        request.setWorkingDirectory(workingDirectory);
        request.setEnableRag(enableRag != null ? enableRag : false);
        request.setEnableGraphRag(enableGraphRag != null ? enableGraphRag : false);
        request.setInjectMcpTools(injectMcpTools != null ? injectMcpTools : true);
        request.setTimeoutSeconds(timeoutSeconds);
        if (ragMaxResults != null) request.setRagMaxResults(ragMaxResults);
        if (ragSimilarityThreshold != null) request.setRagSimilarityThreshold(ragSimilarityThreshold);
        if (agentArgs != null && !agentArgs.isEmpty()) request.setAgentArgs(agentArgs);
        return request;
    }

    private Map<String, Object> buildResultMap(AgentChatService.SyncChatResult chatResult,
                                                String agentName, long startTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", chatResult.isSuccess() ? "success" : "error");
        result.put("agentName", agentName);

        if (chatResult.processId() != null) {
            result.put("processId", chatResult.processId());
        }

        result.put("response", chatResult.content());
        result.put("responseLength", chatResult.content().length());
        result.put("exitCode", chatResult.exitCode());
        result.put("executionTimeMs", chatResult.durationMs());

        if (chatResult.error() != null) {
            result.put("error", chatResult.error());
        }

        if (chatResult.sources() != null && !chatResult.sources().isEmpty()) {
            result.put("sourcesUsed", chatResult.sources().size());
        }

        if (chatResult.modifiedFiles() != null && !chatResult.modifiedFiles().isEmpty()) {
            result.put("modifiedFiles", chatResult.modifiedFiles());
        }

        if (chatResult.stats() != null && !chatResult.stats().isEmpty()) {
            result.put("stats", chatResult.stats());
        }

        return result;
    }

    // ========================================================================
    // Internal State
    // ========================================================================

    private record AsyncDelegation(
            String delegationId,
            String agentName,
            String taskMessage,
            CompletableFuture<AgentChatService.SyncChatResult> future,
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
