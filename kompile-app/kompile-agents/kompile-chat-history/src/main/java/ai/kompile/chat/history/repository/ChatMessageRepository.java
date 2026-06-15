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

package ai.kompile.chat.history.repository;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ChatMessage entities.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * Find all messages for a specific session, ordered by creation time.
     */
    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);

    /**
     * Find all messages for a session ID.
     */
    List<ChatMessage> findBySession_SessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * Find messages created after a specific date.
     */
    List<ChatMessage> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime after);

    /**
     * Count messages in a session.
     */
    long countBySession(ChatSession session);

    /**
     * Delete all messages for a session.
     */
    void deleteBySession(ChatSession session);

    /**
     * Find a message by its ID with eager loading of session.
     * Used for fetching full message content by ID.
     */
    @Query("SELECT m FROM ChatMessage m JOIN FETCH m.session WHERE m.id = :messageId")
    Optional<ChatMessage> findByIdWithSession(@Param("messageId") Long messageId);

    /**
     * Count messages per session for a list of sessions (avoids N+1 lazy loading).
     * Returns Object[] rows of [sessionId (String), count (Long)].
     */
    @Query("SELECT m.session.sessionId, COUNT(m) FROM ChatMessage m WHERE m.session IN :sessions GROUP BY m.session.sessionId")
    List<Object[]> countMessagesBySessions(@Param("sessions") List<ChatSession> sessions);

    /**
     * Find all messages in a session up to and including a specific message ID.
     * Used for fork/branch operations where we need conversation history up to a point.
     *
     * @param sessionId the session ID (UUID string)
     * @param messageId the message ID to stop at (inclusive)
     * @return list of messages from session start up to and including the specified message
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.session.sessionId = :sessionId " +
           "AND m.createdAt <= (SELECT m2.createdAt FROM ChatMessage m2 WHERE m2.id = :messageId) " +
           "ORDER BY m.createdAt ASC")
    List<ChatMessage> findMessagesUntil(@Param("sessionId") String sessionId, @Param("messageId") Long messageId);
}
