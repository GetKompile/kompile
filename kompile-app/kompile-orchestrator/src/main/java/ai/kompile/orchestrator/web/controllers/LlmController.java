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
import ai.kompile.orchestrator.api.OrchestratorService;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.web.dto.InvokeLlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * REST controller for LLM integration.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/llm")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kompile.orchestrator", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmController {

    private final OrchestratorService orchestratorService;
    private final LlmIntegrationService llmService;

    /**
     * Invoke an LLM with a prompt.
     */
    @PostMapping("/invoke")
    public ResponseEntity<LlmSession> invoke(
            @PathVariable String instanceId,
            @RequestBody InvokeLlmRequest request) {
        log.info("Invoking LLM for orchestrator: {}", instanceId);

        LlmSession session;
        if (request.getProviderId() != null) {
            session = orchestratorService.invokeLlm(instanceId, request.getProviderId(), request.getPrompt());
        } else {
            session = orchestratorService.invokeLlm(instanceId, request.getPrompt());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * Stream an LLM response.
     */
    @GetMapping(value = "/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamResponse(
            @PathVariable String instanceId,
            @PathVariable Long sessionId) {
        log.info("Streaming LLM response for session: {}", sessionId);
        return llmService.streamResponse(sessionId);
    }

    /**
     * Get an LLM session by ID.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<LlmSession> getSession(
            @PathVariable String instanceId,
            @PathVariable Long sessionId) {
        return llmService.getSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get active LLM sessions for an orchestrator.
     */
    @GetMapping("/sessions/active")
    public ResponseEntity<List<LlmSession>> getActiveSessions(@PathVariable String instanceId) {
        return ResponseEntity.ok(llmService.getActiveSessions(instanceId));
    }

    /**
     * Get LLM session history for an orchestrator.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<LlmSession>> getSessionHistory(@PathVariable String instanceId) {
        return ResponseEntity.ok(llmService.getSessionHistory(instanceId, 100));
    }

    /**
     * Cancel an LLM session.
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(
            @PathVariable String instanceId,
            @PathVariable Long sessionId) {
        log.info("Cancelling LLM session {} for orchestrator: {}", sessionId, instanceId);
        orchestratorService.cancelLlmSession(sessionId);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "message", "Session cancellation requested"));
    }

    /**
     * Register an LLM trigger.
     */
    @PostMapping("/triggers")
    public ResponseEntity<Map<String, Object>> registerTrigger(
            @PathVariable String instanceId,
            @RequestBody LlmTrigger trigger) {
        log.info("Registering LLM trigger {} for orchestrator: {}", trigger.getTriggerId(), instanceId);
        orchestratorService.registerLlmTrigger(trigger);
        return ResponseEntity.ok(Map.of(
                "triggerId", trigger.getTriggerId(),
                "message", "Trigger registered successfully"));
    }

    /**
     * Get all registered triggers.
     */
    @GetMapping("/triggers")
    public ResponseEntity<List<LlmTrigger>> getTriggers(@PathVariable String instanceId) {
        return ResponseEntity.ok(llmService.getTriggers(instanceId));
    }

    /**
     * Get available LLM providers.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<String>> getProviders(@PathVariable String instanceId) {
        return ResponseEntity.ok(llmService.getAvailableProviders());
    }
}
