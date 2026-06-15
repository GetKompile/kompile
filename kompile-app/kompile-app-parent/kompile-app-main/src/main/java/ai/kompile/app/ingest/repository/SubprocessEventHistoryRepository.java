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

package ai.kompile.app.ingest.repository;

import ai.kompile.app.ingest.domain.SubprocessEventHistory;
import ai.kompile.app.ingest.domain.SubprocessEventHistory.EventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Repository for SubprocessEventHistory entities.
 */
@Repository
public interface SubprocessEventHistoryRepository extends JpaRepository<SubprocessEventHistory, Long> {

    /**
     * Find events by type ordered by timestamp.
     */
    List<SubprocessEventHistory> findByEventTypeOrderByTimestampDesc(EventType eventType);

    /**
     * Find events by model ID ordered by timestamp.
     */
    List<SubprocessEventHistory> findByModelIdOrderByTimestampDesc(String modelId);

    /**
     * Find recent events.
     */
    @Query("SELECT e FROM SubprocessEventHistory e WHERE e.timestamp > :since ORDER BY e.timestamp DESC")
    List<SubprocessEventHistory> findRecentEvents(@Param("since") Instant since);

    /**
     * Find recent events with pagination.
     */
    @Query("SELECT e FROM SubprocessEventHistory e ORDER BY e.timestamp DESC")
    Page<SubprocessEventHistory> findAllOrderedByTimestamp(Pageable pageable);

    /**
     * Find restart events.
     */
    @Query("SELECT e FROM SubprocessEventHistory e WHERE e.eventType IN ('SUBPROCESS_RESTARTING', 'SUBPROCESS_RESTART_SUCCESS', 'SUBPROCESS_RESTART_EXHAUSTED') ORDER BY e.timestamp DESC")
    List<SubprocessEventHistory> findRestartEvents();

    /**
     * Find restart events for a model.
     */
    @Query("SELECT e FROM SubprocessEventHistory e WHERE e.modelId = :modelId AND e.eventType IN ('SUBPROCESS_RESTARTING', 'SUBPROCESS_RESTART_SUCCESS', 'SUBPROCESS_RESTART_EXHAUSTED') ORDER BY e.timestamp DESC")
    List<SubprocessEventHistory> findRestartEventsForModel(@Param("modelId") String modelId);

    /**
     * Find crash events.
     */
    List<SubprocessEventHistory> findByEventTypeInOrderByTimestampDesc(List<EventType> eventTypes);

    /**
     * Count events by type.
     */
    long countByEventType(EventType eventType);

    /**
     * Count restart attempts in a time range.
     */
    @Query("SELECT COUNT(e) FROM SubprocessEventHistory e WHERE e.eventType = 'SUBPROCESS_RESTARTING' AND e.timestamp BETWEEN :start AND :end")
    long countRestartAttempts(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Count successful restarts in a time range.
     */
    @Query("SELECT COUNT(e) FROM SubprocessEventHistory e WHERE e.eventType = 'SUBPROCESS_RESTART_SUCCESS' AND e.timestamp BETWEEN :start AND :end")
    long countSuccessfulRestarts(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Get restart statistics.
     * Returns: [totalRestarts, successfulRestarts, exhaustedRestarts, avgAttempts]
     */
    @Query("SELECT COUNT(e), " +
            "SUM(CASE WHEN e.restartSuccessful = true THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN e.eventType = 'SUBPROCESS_RESTART_EXHAUSTED' THEN 1 ELSE 0 END), " +
            "AVG(e.restartAttemptNumber) " +
            "FROM SubprocessEventHistory e WHERE e.eventType IN ('SUBPROCESS_RESTARTING', 'SUBPROCESS_RESTART_SUCCESS', 'SUBPROCESS_RESTART_EXHAUSTED')")
    List<Object[]> getRestartStatistics();

    /**
     * Delete events older than a specified time.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SubprocessEventHistory e WHERE e.timestamp < :cutoff")
    int deleteEventsOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Find most recent N events.
     */
    List<SubprocessEventHistory> findTop100ByOrderByTimestampDesc();

    /**
     * Find events for a task.
     */
    List<SubprocessEventHistory> findByTaskIdOrderByTimestampDesc(String taskId);
}
