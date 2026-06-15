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
package ai.kompile.orchestrator.repository;

import ai.kompile.orchestrator.model.llm.ConversationMessage;
import ai.kompile.orchestrator.model.llm.ConversationMessage.MessageRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for ConversationMessage entities.
 */
@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /**
     * Find all messages for a session, ordered by sequence.
     */
    List<ConversationMessage> findBySessionIdOrderBySequenceNumberAsc(Long sessionId);

    /**
     * Find all messages for a session, ordered by timestamp.
     */
    List<ConversationMessage> findBySessionIdOrderByTimestampAsc(Long sessionId);

    /**
     * Find messages for an orchestrator instance.
     */
    List<ConversationMessage> findByOrchestratorInstanceIdOrderByTimestampDesc(String orchestratorInstanceId);

    /**
     * Find messages by role.
     */
    List<ConversationMessage> findBySessionIdAndRoleOrderBySequenceNumberAsc(Long sessionId, MessageRole role);

    /**
     * Find messages for a task instance.
     */
    List<ConversationMessage> findByTaskInstanceIdOrderByTimestampAsc(Long taskInstanceId);

    /**
     * Find feedback loop messages for a session.
     */
    List<ConversationMessage> findBySessionIdAndFeedbackLoopTrueOrderByFeedbackIterationAsc(Long sessionId);

    /**
     * Get the maximum sequence number for a session.
     */
    @Query("SELECT MAX(m.sequenceNumber) FROM ConversationMessage m WHERE m.sessionId = :sessionId")
    Integer getMaxSequenceNumber(@Param("sessionId") Long sessionId);

    /**
     * Count messages in a session.
     */
    long countBySessionId(Long sessionId);

    /**
     * Count messages by role in a session.
     */
    long countBySessionIdAndRole(Long sessionId, MessageRole role);

    /**
     * Find recent messages for an orchestrator, paged.
     */
    Page<ConversationMessage> findByOrchestratorInstanceIdOrderByTimestampDesc(
            String orchestratorInstanceId, Pageable pageable);

    /**
     * Delete messages for a session.
     */
    void deleteBySessionId(Long sessionId);

    /**
     * Delete messages older than a given timestamp.
     */
    void deleteByTimestampBefore(LocalDateTime timestamp);

    /**
     * Find tool calls for a session.
     */
    @Query("SELECT m FROM ConversationMessage m WHERE m.sessionId = :sessionId AND m.role = 'TOOL_CALL' ORDER BY m.sequenceNumber")
    List<ConversationMessage> findToolCalls(@Param("sessionId") Long sessionId);

    /**
     * Get total token count for a session.
     */
    @Query("SELECT SUM(m.tokenCount) FROM ConversationMessage m WHERE m.sessionId = :sessionId")
    Long getTotalTokenCount(@Param("sessionId") Long sessionId);

    /**
     * Get conversation summary (latest messages).
     */
    @Query("SELECT m FROM ConversationMessage m WHERE m.sessionId = :sessionId ORDER BY m.sequenceNumber DESC")
    Page<ConversationMessage> getLatestMessages(@Param("sessionId") Long sessionId, Pageable pageable);
}
