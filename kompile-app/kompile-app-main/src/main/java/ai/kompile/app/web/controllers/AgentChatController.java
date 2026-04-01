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
import ai.kompile.app.web.dto.AgentChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
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

    private final AgentChatService chatService;
    private final AgentRegistryService agentRegistryService;

    public AgentChatController(AgentChatService chatService, AgentRegistryService agentRegistryService) {
        this.chatService = chatService;
        this.agentRegistryService = agentRegistryService;
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
    public SseEmitter streamChat(@RequestBody AgentChatRequest request) {
        log.info("Received chat request for agent: {}, RAG enabled: {}, timeout: {}s, message length: {}",
                request.getAgentName(),
                request.isEnableRag(),
                request.getTimeoutSeconds(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        // Calculate SSE timeout: 0 = no timeout (use max), otherwise use configured value
        long sseTimeout;
        if (request.getTimeoutSeconds() <= 0) {
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
            log.error("SSE error: {}", e.getMessage());
            emitter.completeWithError(e);
        });

        // Execute chat asynchronously
        chatService.executeChat(request, emitter);

        return emitter;
    }

    /**
     * Cancel a running chat process.
     */
    @PostMapping("/cancel/{processId}")
    public ResponseEntity<Map<String, Object>> cancelChat(@PathVariable String processId) {
        log.info("Cancelling chat process: {}", processId);

        boolean cancelled = chatService.cancelProcess(processId);

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
                "service", "agent-chat"
        ));
    }

    /**
     * Update the skipPermissions setting for an agent.
     */
    @PutMapping("/agents/{name}/skip-permissions")
    public ResponseEntity<Map<String, Object>> updateSkipPermissions(
            @PathVariable String name, @RequestBody Map<String, Boolean> body) {
        boolean skip = body.getOrDefault("skipPermissions", true);
        agentRegistryService.updateAgentSkipPermissions(name, skip);
        return ResponseEntity.ok(Map.of(
                "agent", name,
                "skipPermissions", skip
        ));
    }
}
