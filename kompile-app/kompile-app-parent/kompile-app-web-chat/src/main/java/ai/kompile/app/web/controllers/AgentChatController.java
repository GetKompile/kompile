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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.services.agent.ChatHarnessClient;
import ai.kompile.app.services.agent.ChatContextBudgetService;
import ai.kompile.app.services.agent.ChatHistoryCompactor;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.app.web.dto.AgentChatCompactRequest;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.agent.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for agent chat with streaming support.
 * <p>
 * Provides SSE streaming endpoint for real-time agent responses
 * with optional RAG context augmentation.
 */
@RestController
@RequestMapping("/api/agents/chat")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    // Default timeout for long-running agent tasks (5 minutes)
    private static final long DEFAULT_SSE_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    // Maximum allowed timeout to prevent indefinite waits (30 minutes)
    private static final long MAX_SSE_TIMEOUT = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_HARNESS_TIMEOUT_SECONDS = 1_800;
    private static final long HARNESS_SSE_GRACE_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long MAX_HARNESS_SSE_TIMEOUT = TimeUnit.MINUTES.toMillis(31);

    private final AgentChatService chatService;
    private final AgentRegistryService agentRegistryService;
    private final IntegrationControlCredentials integrationCredentials;
    private final ChatHarnessClient harnessClient;

    public AgentChatController(AgentChatService chatService, AgentRegistryService agentRegistryService) {
        this(chatService, agentRegistryService, null, null);
    }

    public AgentChatController(
            AgentChatService chatService,
            AgentRegistryService agentRegistryService,
            IntegrationControlCredentials integrationCredentials) {
        this(chatService, agentRegistryService, integrationCredentials, null);
    }

    @Autowired
    public AgentChatController(
            AgentChatService chatService,
            AgentRegistryService agentRegistryService,
            IntegrationControlCredentials integrationCredentials,
            ChatHarnessClient harnessClient) {
        this.chatService = chatService;
        this.agentRegistryService = agentRegistryService;
        this.integrationCredentials = integrationCredentials;
        this.harnessClient = harnessClient;
    }

    /**
     * Stream chat response from an agent.
     * <p>
     * Supports RAG augmentation when enableRag is true.
     * Returns Server-Sent Events with the following event types:
     * - start: Process started
     * - chunk: Content chunk (text)
     * - tool_use: Agent is using a tool
     * - result: Agent result metadata
     * - complete: Process completed successfully
     * - error: Error occurred
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestBody AgentChatRequest request,
            HttpServletRequest servletRequest) {
        requireProvisionedAccess(request, servletRequest);
        requireHarnessAccess(request, servletRequest);
        log.info("Received chat request for agent: {}, RAG enabled: {}, timeout: {}s, message length: {}",
                sanitizeForLog(request.getAgentName()),
                request.isEnableRag(),
                request.getTimeoutSeconds(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        boolean harnessTurn = harnessClient != null && !isProvisioned(request);
        // A harness turn is always bounded and its SSE connection outlives the CLI's own timeout.
        // Provisioned/legacy turns retain their existing timeout contract.
        long sseTimeout;
        if (harnessTurn) {
            sseTimeout = harnessSseTimeout(request.getTimeoutSeconds());
            log.info("Using harness SSE timeout: {}ms", sseTimeout);
        } else if (request.getTimeoutSeconds() <= 0) {
            // No timeout - use a very long timeout (effectively infinite for practical purposes)
            sseTimeout = -1L; // -1 means no timeout in SseEmitter
            log.info("Using no SSE timeout (infinite)");
        } else {
            sseTimeout = Math.min(TimeUnit.SECONDS.toMillis(request.getTimeoutSeconds()), MAX_SSE_TIMEOUT);
            log.info("Using SSE timeout: {}ms", sseTimeout);
        }

        SseEmitter emitter = new SseEmitter(sseTimeout);

        // Set up error and completion handlers
        emitter.onCompletion(() -> log.debug("SSE connection completed"));
        emitter.onTimeout(() -> {
            log.warn("SSE connection timed out");
            emitter.complete();
        });
        emitter.onError(e -> {
            log.error("SSE error", e);
            emitter.completeWithError(e);
        });

        // Provisioned/channel turns retain their authenticated canonical runtime. Ordinary
        // browser turns are thin clients of the full kompile-cli-main harness.
        if (isProvisioned(request) || harnessClient == null) {
            chatService.executeChat(request, emitter);
        } else {
            harnessClient.executeChat(request, emitter);
        }

        return emitter;
    }

    static long harnessSseTimeout(int requestedSeconds) {
        int effectiveSeconds = requestedSeconds <= 0
                ? (int) TimeUnit.MILLISECONDS.toSeconds(DEFAULT_SSE_TIMEOUT)
                : Math.min(requestedSeconds, MAX_HARNESS_TIMEOUT_SECONDS);
        return Math.min(
                TimeUnit.SECONDS.toMillis(effectiveSeconds) + HARNESS_SSE_GRACE_MS,
                MAX_HARNESS_SSE_TIMEOUT);
    }

    private void requireProvisionedAccess(
            AgentChatRequest request,
            HttpServletRequest servletRequest) {
        if (request.getProvisionedAgentId() == null
                || request.getProvisionedAgentId().isBlank()) {
            return;
        }
        if (integrationCredentials == null || !integrationCredentials.matches(
                servletRequest.getHeader(ChannelControlHeaders.TOKEN_HEADER))) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "A valid integration admin token is required for provisioned-agent chat");
        }
        if (!"1".equals(servletRequest.getHeader(ChannelControlHeaders.REQUEST_HEADER))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Provisioned-agent chat requires " + ChannelControlHeaders.REQUEST_HEADER);
        }
        if (!servletRequest.isSecure()
                && !(isLoopback(servletRequest.getRemoteAddr())
                && isLoopback(servletRequest.getServerName()))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Remote provisioned-agent chat requires HTTPS");
        }
        try {
            ProvisionedAgentRuntime.canonicalTurnId(request.getTurnId());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    private static boolean isProvisioned(AgentChatRequest request) {
        return request != null && request.getProvisionedAgentId() != null
                && !request.getProvisionedAgentId().isBlank();
    }

    private void requireHarnessAccess(
            AgentChatRequest request,
            HttpServletRequest servletRequest) {
        if (harnessClient == null || isProvisioned(request)) return;
        requireHarnessControlAccess(servletRequest);
    }

    private void requireHarnessControlAccess(HttpServletRequest servletRequest) {
        if (isLoopback(servletRequest.getRemoteAddr())
                && isLoopback(servletRequest.getServerName())) {
            return;
        }
        boolean authenticated = servletRequest.isSecure()
                && integrationCredentials != null
                && integrationCredentials.matches(
                servletRequest.getHeader(ChannelControlHeaders.TOKEN_HEADER))
                && "1".equals(servletRequest.getHeader(ChannelControlHeaders.REQUEST_HEADER));
        if (!authenticated) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Remote Kompile CLI harness access requires HTTPS and a valid integration admin token");
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    /**
     * Cancel a running chat process.
     */
    @PostMapping("/cancel/{processId}")
    public ResponseEntity<Map<String, Object>> cancelChat(
            @PathVariable String processId,
            HttpServletRequest servletRequest) {
        log.info("Cancelling chat process: {}", sanitizeForLog(processId));

        boolean harnessProcess = harnessClient != null && processId.startsWith("harness-");
        if (harnessProcess) requireHarnessControlAccess(servletRequest);
        boolean cancelled = harnessProcess
                ? harnessClient.cancel(processId) : chatService.cancelProcess(processId);

        return ResponseEntity.ok(Map.of(
                "processId", processId,
                "cancelled", cancelled
        ));
    }

    /**
     * Health check for the chat endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "agent-chat",
                "engine", harnessClient == null ? "legacy-agent-chat" : "kompile-cli-main"
        ));
    }

    /** Authoritative non-secret harness personas and provider/model limits for the browser. */
    @GetMapping("/capabilities")
    public ResponseEntity<JsonNode> capabilities(
            @RequestParam(required = false) String workingDirectory,
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest servletRequest) {
        if (harnessClient == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Kompile CLI harness is unavailable");
        }
        requireHarnessControlAccess(servletRequest);
        return ResponseEntity.ok(harnessClient.capabilities(workingDirectory, refresh));
    }

    /**
     * The context budget for an agent's lane: the model's real context window
     * (staging metadata for local models, model catalogs otherwise), its output
     * reservation, and the resulting input budget. The chat window uses this to
     * show context usage and decide when to compact.
     */
    @GetMapping("/context-budget")
    public ResponseEntity<Map<String, Object>> contextBudget(
            @RequestParam String agentName,
            @RequestParam(required = false) String workingDirectory,
            HttpServletRequest servletRequest) {
        if (harnessClient != null) {
            requireHarnessControlAccess(servletRequest);
            return ResponseEntity.ok(harnessClient.contextBudget(agentName, workingDirectory));
        }
        Optional<AgentProvider> agent = agentRegistryService.getAgent(agentName);
        if (agent.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Agent not found: " + agentName));
        }
        try {
            ChatContextBudgetService.ContextBudget budget = chatService.resolveContextBudget(agent.get());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentName", budget.agentName());
            body.put("model", budget.model());
            body.put("contextWindow", budget.contextWindow());
            body.put("maxOutputTokens", budget.maxOutputTokens());
            body.put("inputBudgetTokens", budget.inputBudgetTokens());
            body.put("source", budget.source());
            body.put("compactTriggerRatio", ChatHistoryCompactor.TRIGGER_RATIO);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("Context budget resolution failed for {}: {}", sanitizeForLog(agentName), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manually compact a chat window's history: older messages are summarized through
     * the same lane the agent chats on, recent messages are preserved verbatim, and the
     * compacted history (led by the summary exchange) is returned for the window to adopt.
     */
    @PostMapping("/compact")
    public ResponseEntity<Map<String, Object>> compact(@RequestBody AgentChatCompactRequest request) {
        if (harnessClient != null) {
            return ResponseEntity.ok(Map.of(
                    "compacted", false,
                    "managedBy", "kompile-cli-main",
                    "reason", "The CLI harness owns transcript compaction automatically"));
        }
        Optional<AgentProvider> agent = agentRegistryService.getAgent(request.getAgentName());
        if (agent.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Agent not found: " + request.getAgentName()));
        }
        if (request.getChatHistory() == null || request.getChatHistory().isEmpty()) {
            return ResponseEntity.ok(Map.of("compacted", false, "reason", "history is empty"));
        }
        try {
            ChatContextBudgetService.ContextBudget budget = chatService.resolveContextBudget(agent.get());
            ChatHistoryCompactor.Result result = chatService.compactHistory(
                    agent.get(), request.getChatHistory(), request.getFocusInstruction());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("compacted", result.compacted());
            body.put("tokensBefore", result.tokensBefore());
            body.put("tokensAfter", result.tokensAfter());
            body.put("contextWindow", budget.contextWindow());
            body.put("model", budget.model());
            body.put("summary", result.summary());
            body.put("usedFallback", result.usedFallback());
            body.put("compactedHistory", result.history());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Chat compaction failed for {}: {}", sanitizeForLog(request.getAgentName()), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update the skipPermissions setting for an agent.
     */
    @PutMapping("/agents/{name}/skip-permissions")
    public ResponseEntity<Map<String, Object>> updateSkipPermissions(
            @PathVariable String name, @RequestBody Map<String, Boolean> body) {
        boolean skip = body.getOrDefault("skipPermissions", true);
        if (harnessClient == null) {
            agentRegistryService.updateAgentSkipPermissions(name, skip);
        }
        return ResponseEntity.ok(Map.of(
                "agent", name,
                "skipPermissions", skip,
                "scope", harnessClient == null ? "provider" : "request"
        ));
    }

    private static String sanitizeForLog(String value) {
        if (value == null) return null;
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
