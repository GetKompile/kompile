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

package ai.kompile.chat.history.controller;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.dto.*;
import ai.kompile.chat.history.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for chat history operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat-history")
@RequiredArgsConstructor
@ConditionalOnClass(name = "ai.kompile.chat.history.service.ChatHistoryService")
@ConditionalOnProperty(name = "kompile.chat.history.enabled", havingValue = "true", matchIfMissing = true)
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * Create a new chat session.
     */
    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionDto> createSession(@RequestBody CreateSessionRequest request) {
        log.info("Creating new chat session: {}", request.getTitle());
        ChatSession session = chatHistoryService.createSession(request.getTitle(), request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ChatSessionDto.fromEntity(session, false));
    }

    /**
     * Get all sessions for a user.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSessionDto>> getSessions(@RequestParam(name = "userId", required = false) String userId) {
        log.debug("Getting sessions for user: {}", userId);
        List<ChatSession> sessions;

        if (userId != null && !userId.isEmpty()) {
            sessions = chatHistoryService.getUserSessions(userId);
        } else {
            sessions = chatHistoryService.getRecentSessions(100);
        }

        List<ChatSessionDto> dtos = sessions.stream()
            .map(s -> ChatSessionDto.fromEntity(s, false))
            .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a specific session with all messages.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSessionDto> getSession(@PathVariable String sessionId) {
        log.debug("Getting session: {}", sessionId);
        return chatHistoryService.getSession(sessionId)
            .map(session -> ResponseEntity.ok(ChatSessionDto.fromEntity(session, true)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update session title.
     */
    @PatchMapping("/sessions/{sessionId}/title")
    public ResponseEntity<ChatSessionDto> updateSessionTitle(
        @PathVariable String sessionId,
        @RequestBody Map<String, String> request
    ) {
        String title = request.get("title");
        log.info("Updating session {} title to: {}", sessionId, title);

        return chatHistoryService.updateSessionTitle(sessionId, title)
            .map(session -> ResponseEntity.ok(ChatSessionDto.fromEntity(session, false)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        log.info("Deleting session: {}", sessionId);
        boolean deleted = chatHistoryService.deleteSession(sessionId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Add a message to a session.
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageDto> addMessage(
        @PathVariable String sessionId,
        @RequestBody AddMessageRequest request
    ) {
        log.debug("Adding {} message to session: {}", request.getRole(), sessionId);

        try {
            ChatMessage message = chatHistoryService.addMessage(
                sessionId,
                request.getRole(),
                request.getContent(),
                request.getModel()
            );
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChatMessageDto.fromEntity(message));
        } catch (IllegalArgumentException e) {
            log.error("Error adding message: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all messages for a session.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageDto>> getSessionMessages(@PathVariable String sessionId) {
        log.debug("Getting messages for session: {}", sessionId);
        List<ChatMessage> messages = chatHistoryService.getSessionMessages(sessionId);
        List<ChatMessageDto> dtos = messages.stream()
            .map(ChatMessageDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get full content of a single message by ID.
     * Used for copy/save/parse operations where frontend needs complete, untruncated content.
     */
    @GetMapping("/messages/{messageId}/content")
    public ResponseEntity<String> getMessageFullContent(@PathVariable Long messageId) {
        log.debug("Getting full content for message: {}", messageId);
        return chatHistoryService.getMessageContent(messageId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get full message by ID (includes all fields).
     * Used when frontend needs complete message data including metadata.
     */
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<ChatMessageDto> getMessageById(@PathVariable Long messageId) {
        log.debug("Getting message by ID: {}", messageId);
        return chatHistoryService.getMessageById(messageId)
            .map(msg -> ResponseEntity.ok(ChatMessageDto.fromEntity(msg)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all messages up to and including a specific message.
     * Used for fork/branch operations where frontend needs conversation history up to a point.
     *
     * @param sessionId the session UUID string
     * @param messageId the message ID to stop at (inclusive)
     */
    @GetMapping("/sessions/{sessionId}/messages/until/{messageId}")
    public ResponseEntity<List<ChatMessageDto>> getMessagesUntil(
        @PathVariable String sessionId,
        @PathVariable Long messageId
    ) {
        log.debug("Getting messages for session {} until message {}", sessionId, messageId);
        List<ChatMessage> messages = chatHistoryService.getMessagesUntil(sessionId, messageId);
        List<ChatMessageDto> dtos = messages.stream()
            .map(ChatMessageDto::fromEntity)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
}
