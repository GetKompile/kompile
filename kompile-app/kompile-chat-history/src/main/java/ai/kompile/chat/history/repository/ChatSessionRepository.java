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

import ai.kompile.chat.history.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ChatSession entities.
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * Find a session by its unique session ID.
     */
    Optional<ChatSession> findBySessionId(String sessionId);

    /**
     * Find all sessions for a specific user, ordered by most recently updated.
     */
    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * Find sessions updated after a specific date.
     */
    List<ChatSession> findByUpdatedAtAfterOrderByUpdatedAtDesc(LocalDateTime after);

    /**
     * Find sessions by user ID with pagination support.
     */
    @Query("SELECT s FROM ChatSession s WHERE s.userId = :userId ORDER BY s.updatedAt DESC")
    List<ChatSession> findRecentSessionsByUserId(String userId);

    /**
     * Check if a session exists by session ID.
     */
    boolean existsBySessionId(String sessionId);

    /**
     * Delete sessions older than a specific date.
     */
    void deleteByCreatedAtBefore(LocalDateTime before);

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT SHEET SCOPED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Find all sessions for a specific fact sheet, ordered by most recently updated.
     */
    List<ChatSession> findByFactSheetIdOrderByUpdatedAtDesc(Long factSheetId);

    /**
     * Find sessions for a user within a specific fact sheet.
     */
    List<ChatSession> findByFactSheetIdAndUserIdOrderByUpdatedAtDesc(Long factSheetId, String userId);

    /**
     * Find a session by session ID within a specific fact sheet.
     */
    Optional<ChatSession> findBySessionIdAndFactSheetId(String sessionId, Long factSheetId);

    /**
     * Count sessions in a fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Delete all sessions for a fact sheet.
     */
    void deleteByFactSheetId(Long factSheetId);

    /**
     * Find sessions without a fact sheet (legacy/unscoped sessions).
     */
    List<ChatSession> findByFactSheetIdIsNullOrderByUpdatedAtDesc();

    // ═══════════════════════════════════════════════════════════════════════════════
    // SOURCE-BASED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════════

    List<ChatSession> findBySourceOrderByUpdatedAtDesc(String source);

    List<ChatSession> findBySourceInOrderByUpdatedAtDesc(java.util.Collection<String> sources);

    boolean existsBySessionIdAndSource(String sessionId, String source);
}
