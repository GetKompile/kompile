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

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.repository.ChatMessageRepository;
import ai.kompile.chat.history.repository.ChatSessionRepository;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing chat history.
 * Chat sessions are scoped to fact sheets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
@Transactional
@ConditionalOnClass(name = "ai.kompile.chat.history.service.ChatHistoryService")
@ConditionalOnProperty(name = "kompile.chat.history.enabled", havingValue = "true", matchIfMissing = true)
public class ChatHistoryService {


    @Autowired
    private final ChatSessionRepository sessionRepository;
    @Autowired
    private final ChatMessageRepository messageRepository;

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT SHEET SCOPED OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Create a new chat session within a fact sheet.
     */
    public ChatSession createSession(String title, String userId, Long factSheetId) {
        String sessionId = UUID.randomUUID().toString();

        ChatSession session = ChatSession.builder()
            .sessionId(sessionId)
            .title(title != null ? title : "New Chat")
            .userId(userId)
            .factSheetId(factSheetId)
            .build();

        ChatSession saved = sessionRepository.save(session);
        log.info("Created new chat session: {} for user: {} in fact sheet: {}", sessionId, userId, factSheetId);
        return saved;
    }

    /**
     * Create a new chat session (legacy - no fact sheet).
     */
    public ChatSession createSession(String title, String userId) {
        return createSession(title, userId, null);
    }

    /**
     * Get all sessions for a fact sheet.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsByFactSheet(Long factSheetId) {
        return sessionRepository.findByFactSheetIdOrderByUpdatedAtDesc(factSheetId);
    }

    /**
     * Get sessions for a user within a specific fact sheet.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getUserSessionsInFactSheet(Long factSheetId, String userId) {
        return sessionRepository.findByFactSheetIdAndUserIdOrderByUpdatedAtDesc(factSheetId, userId);
    }

    /**
     * Get a session by ID within a specific fact sheet.
     */
    @Transactional(readOnly = true)
    public Optional<ChatSession> getSessionInFactSheet(String sessionId, Long factSheetId) {
        return sessionRepository.findBySessionIdAndFactSheetId(sessionId, factSheetId);
    }

    /**
     * Count sessions in a fact sheet.
     */
    @Transactional(readOnly = true)
    public long countSessionsInFactSheet(Long factSheetId) {
        return sessionRepository.countByFactSheetId(factSheetId);
    }

    /**
     * Delete all sessions for a fact sheet.
     */
    public void deleteSessionsByFactSheet(Long factSheetId) {
        sessionRepository.deleteByFactSheetId(factSheetId);
        log.info("Deleted all sessions for fact sheet: {}", factSheetId);
    }

    /**
     * Get sessions without a fact sheet (legacy/unscoped).
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getUnscopedSessions() {
        return sessionRepository.findByFactSheetIdIsNullOrderByUpdatedAtDesc();
    }

    /**
     * Migrate a session to a fact sheet.
     */
    public Optional<ChatSession> migrateSessionToFactSheet(String sessionId, Long factSheetId) {
        Optional<ChatSession> sessionOpt = sessionRepository.findBySessionId(sessionId);
        sessionOpt.ifPresent(session -> {
            session.setFactSheetId(factSheetId);
            sessionRepository.save(session);
            log.info("Migrated session {} to fact sheet {}", sessionId, factSheetId);
        });
        return sessionOpt;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STANDARD OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get a session by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<ChatSession> getSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    /**
     * Get all sessions for a user.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getUserSessions(String userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /**
     * Update session title.
     */
    public Optional<ChatSession> updateSessionTitle(String sessionId, String title) {
        Optional<ChatSession> sessionOpt = sessionRepository.findBySessionId(sessionId);
        sessionOpt.ifPresent(session -> {
            session.setTitle(title);
            sessionRepository.save(session);
            log.info("Updated session title: {} to '{}'", sessionId, title);
        });
        return sessionOpt;
    }

    /**
     * Add a message to a session.
     */
    public ChatMessage addMessage(String sessionId, ChatMessage.MessageRole role, String content, String model) {
        ChatSession session = sessionRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        ChatMessage message = ChatMessage.builder()
            .session(session)
            .role(role)
            .content(content)
            .model(model)
            .build();

        ChatMessage saved = messageRepository.save(message);

        // Update session timestamp and message count
        session.setUpdatedAt(LocalDateTime.now());
        session.setMessageCount(session.getMessageCount() + 1);
        sessionRepository.save(session);

        log.debug("Added {} message to session: {}", role, sessionId);
        return saved;
    }

    /**
     * Create a session with a specified source.
     */
    public ChatSession createSession(String title, String userId, Long factSheetId, String source) {
        String sessionId = UUID.randomUUID().toString();

        ChatSession session = ChatSession.builder()
            .sessionId(sessionId)
            .title(title != null ? title : "New Chat")
            .userId(userId)
            .factSheetId(factSheetId)
            .source(source)
            .build();

        ChatSession saved = sessionRepository.save(session);
        log.info("Created new chat session: {} source: {}", sessionId, source);
        return saved;
    }

    /**
     * Create a session with a given sessionId and source (for imports).
     */
    public ChatSession createSessionWithId(String sessionId, String title, String source) {
        return createSessionWithId(sessionId, title, source, null);
    }

    /**
     * Create a session with a given sessionId, source, and original timestamp (for imports).
     * The originalTimestamp preserves when the CLI conversation actually occurred.
     */
    public ChatSession createSessionWithId(String sessionId, String title, String source, Long originalTimestampMillis) {
        ChatSession session = ChatSession.builder()
            .sessionId(sessionId)
            .title(title != null ? title : "Imported Chat")
            .source(source)
            .originalTimestamp(originalTimestampMillis)
            .build();

        ChatSession saved = sessionRepository.save(session);
        log.info("Created imported chat session: {} source: {} originalTimestamp: {}", sessionId, source, originalTimestampMillis);
        return saved;
    }

    /**
     * Get sessions by source.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getSessionsBySource(String source) {
        return sessionRepository.findBySourceOrderByUpdatedAtDesc(source);
    }

    /**
     * Check if a session with given ID and source already exists.
     */
    @Transactional(readOnly = true)
    public boolean existsBySessionIdAndSource(String sessionId, String source) {
        return sessionRepository.existsBySessionIdAndSource(sessionId, source);
    }

    /**
     * Get all messages for a session.
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return messageRepository.findBySession_SessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * Delete a session and all its messages.
     */
    public boolean deleteSession(String sessionId) {
        Optional<ChatSession> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            sessionRepository.delete(sessionOpt.get());
            log.info("Deleted session: {}", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Delete old sessions based on age.
     */
    public void cleanupOldSessions(int maxAgeDays) {
        if (maxAgeDays <= 0) {
            return;
        }

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(maxAgeDays);
        sessionRepository.deleteByCreatedAtBefore(cutoffDate);
        log.info("Cleaned up sessions older than {} days", maxAgeDays);
    }

    /**
     * Get recent sessions.
     */
    @Transactional(readOnly = true)
    public List<ChatSession> getRecentSessions(int limit) {
        List<ChatSession> sessions = sessionRepository.findAll();
        return sessions.stream()
            .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
            .limit(limit)
            .toList();
    }

    /**
     * Get a single message by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<ChatMessage> getMessageById(Long messageId) {
        return messageRepository.findByIdWithSession(messageId);
    }

    /**
     * Get the full content of a message by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<String> getMessageContent(Long messageId) {
        return messageRepository.findById(messageId)
            .map(ChatMessage::getContent);
    }

    /**
     * Get all messages in a session up to and including a specific message.
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessagesUntil(String sessionId, Long messageId) {
        return messageRepository.findMessagesUntil(sessionId, messageId);
    }
}
