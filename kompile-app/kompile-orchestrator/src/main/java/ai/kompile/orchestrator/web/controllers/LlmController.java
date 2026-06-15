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

import ai.kompile.core.util.FieldNames;
import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.OrchestratorService;
import ai.kompile.orchestrator.model.llm.ConversationMessage;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.service.impl.ConversationHistoryService;
import ai.kompile.orchestrator.service.impl.TaskCompletionHandler;
import ai.kompile.orchestrator.web.dto.InvokeLlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
public class LlmController {

    private final OrchestratorService orchestratorService;
    private final LlmIntegrationService llmService;

    @Autowired(required = false)
    private ConversationHistoryService conversationHistoryService;

    @Autowired(required = false)
    private TaskCompletionHandler taskCompletionHandler;

    /**
     * Invoke an LLM with a prompt.
     */
    @PostMapping("/invoke")
    public ResponseEntity<LlmSession> invoke(
            @PathVariable("instanceId") String instanceId,
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
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        log.info("Streaming LLM response for session: {}", sessionId);
        return llmService.streamResponse(sessionId);
    }

    /**
     * Get an LLM session by ID.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<LlmSession> getSession(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        return llmService.getSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get active LLM sessions for an orchestrator.
     */
    @GetMapping("/sessions/active")
    public ResponseEntity<List<LlmSession>> getActiveSessions(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(llmService.getActiveSessions(instanceId));
    }

    /**
     * Get LLM session history for an orchestrator.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<LlmSession>> getSessionHistory(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(llmService.getSessionHistory(instanceId, 100));
    }

    /**
     * Cancel an LLM session.
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        log.info("Cancelling LLM session {} for orchestrator: {}", sessionId, instanceId);
        orchestratorService.cancelLlmSession(sessionId);
        return ResponseEntity.ok(Map.of(
                FieldNames.SESSION_ID, sessionId,
                "message", "Session cancellation requested"));
    }

    /**
     * Register an LLM trigger.
     */
    @PostMapping("/triggers")
    public ResponseEntity<Map<String, Object>> registerTrigger(
            @PathVariable("instanceId") String instanceId,
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
    public ResponseEntity<List<LlmTrigger>> getTriggers(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(llmService.getTriggers(instanceId));
    }

    /**
     * Get available LLM providers.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<String>> getProviders(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(llmService.getAvailableProviders());
    }

    // ==================== Conversation History Endpoints ====================

    /**
     * Get conversation history for a session.
     */
    @GetMapping("/sessions/{sessionId}/conversation")
    public ResponseEntity<?> getConversationHistory(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }
        List<ConversationMessage> messages = conversationHistoryService.getConversationHistory(sessionId);
        return ResponseEntity.ok(messages);
    }

    /**
     * Get formatted conversation history as text.
     */
    @GetMapping("/sessions/{sessionId}/conversation/formatted")
    public ResponseEntity<?> getFormattedConversation(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }
        String formatted = conversationHistoryService.getFormattedHistory(sessionId);
        return ResponseEntity.ok(Map.of("conversation", formatted));
    }

    /**
     * Get conversation summary for a session.
     */
    @GetMapping("/sessions/{sessionId}/conversation/summary")
    public ResponseEntity<?> getConversationSummary(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }
        ConversationHistoryService.ConversationSummary summary =
                conversationHistoryService.getConversationSummary(sessionId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get all conversation messages for an orchestrator instance.
     */
    @GetMapping("/conversations")
    public ResponseEntity<?> getConversationsForOrchestrator(
            @PathVariable("instanceId") String instanceId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }
        Page<ConversationMessage> messages = conversationHistoryService.getMessagesForOrchestrator(
                instanceId, PageRequest.of(page, size));
        return ResponseEntity.ok(messages);
    }

    // ==================== Feedback Loop Endpoints ====================

    /**
     * Continue a feedback loop with additional input.
     */
    @PostMapping("/sessions/{sessionId}/feedback")
    public ResponseEntity<?> continueFeedbackLoop(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId,
            @RequestBody Map<String, String> request) {
        if (taskCompletionHandler == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Task completion handler not available"));
        }

        String feedbackMessage = request.get("message");
        if (feedbackMessage == null || feedbackMessage.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message is required"));
        }

        log.info("Continuing feedback loop for session {} with message: {}",
                sessionId, feedbackMessage.substring(0, Math.min(100, feedbackMessage.length())));

        LlmSession updatedSession = taskCompletionHandler.continueFeedbackLoop(sessionId, feedbackMessage);

        if (updatedSession == null) {
            return ResponseEntity.ok(Map.of(
                    FieldNames.SESSION_ID, sessionId,
                    "status", "ended",
                    "message", "Feedback loop has ended (max iterations reached or session not in feedback loop)"
            ));
        }

        return ResponseEntity.ok(Map.of(
                FieldNames.SESSION_ID, sessionId,
                "status", "continued",
                "session", updatedSession
        ));
    }

    /**
     * End a feedback loop.
     */
    @PostMapping("/sessions/{sessionId}/feedback/end")
    public ResponseEntity<?> endFeedbackLoop(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }

        conversationHistoryService.endFeedbackLoop(sessionId);
        log.info("Ended feedback loop for session {}", sessionId);

        return ResponseEntity.ok(Map.of(
                FieldNames.SESSION_ID, sessionId,
                "status", "ended",
                "message", "Feedback loop ended"
        ));
    }

    /**
     * Get feedback loop status for a session.
     */
    @GetMapping("/sessions/{sessionId}/feedback/status")
    public ResponseEntity<?> getFeedbackLoopStatus(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }

        boolean inLoop = conversationHistoryService.isInFeedbackLoop(sessionId);
        int iteration = conversationHistoryService.getCurrentFeedbackIteration(sessionId);
        List<ConversationMessage> feedbackMessages = conversationHistoryService.getFeedbackMessages(sessionId);

        return ResponseEntity.ok(Map.of(
                FieldNames.SESSION_ID, sessionId,
                "inFeedbackLoop", inLoop,
                "currentIteration", iteration,
                "feedbackMessages", feedbackMessages
        ));
    }

    /**
     * Get tool calls for a session.
     */
    @GetMapping("/sessions/{sessionId}/tools")
    public ResponseEntity<?> getToolCalls(
            @PathVariable("instanceId") String instanceId,
            @PathVariable(FieldNames.SESSION_ID) Long sessionId) {
        if (conversationHistoryService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Conversation history service not available"));
        }
        List<ConversationMessage> toolCalls = conversationHistoryService.getToolCalls(sessionId);
        return ResponseEntity.ok(toolCalls);
    }
}
