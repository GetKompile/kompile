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

import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.domain.IngestEvent.EventType;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Repository for IngestEvent entities.
 * Provides query methods for audit log retrieval and cleanup.
 */
@Repository
public interface IngestEventRepository extends JpaRepository<IngestEvent, Long> {

    /**
     * Find all events for a specific task, ordered by timestamp.
     */
    List<IngestEvent> findByTaskIdOrderByTimestampAsc(String taskId);

    /**
     * Find all events for a task and phase.
     */
    List<IngestEvent> findByTaskIdAndPhaseOrderByTimestampAsc(String taskId, IngestPhase phase);

    /**
     * Find events by type for a task.
     */
    List<IngestEvent> findByTaskIdAndEventTypeOrderByTimestampAsc(String taskId, EventType eventType);

    /**
     * Find the latest event for a task.
     */
    @Query("SELECT e FROM IngestEvent e WHERE e.taskId = :taskId ORDER BY e.timestamp DESC LIMIT 1")
    IngestEvent findLatestByTaskId(@Param("taskId") String taskId);

    /**
     * Find all terminal events (COMPLETED, FAILED, CANCELLED) for analysis.
     */
    @Query("SELECT e FROM IngestEvent e WHERE e.eventType IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
           "ORDER BY e.timestamp DESC")
    List<IngestEvent> findTerminalEvents();

    /**
     * Find error events in a time range.
     */
    List<IngestEvent> findByEventTypeAndTimestampBetweenOrderByTimestampDesc(
            EventType eventType, Instant start, Instant end);

    /**
     * Find all events in a time range.
     */
    List<IngestEvent> findByTimestampBetweenOrderByTimestampAsc(Instant start, Instant end);

    /**
     * Count events by type for a task.
     */
    long countByTaskIdAndEventType(String taskId, EventType eventType);

    /**
     * Delete events older than a specified timestamp.
     * Used for cleanup of old audit logs.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM IngestEvent e WHERE e.timestamp < :cutoff")
    int deleteEventsOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Delete all events for a specific task.
     */
    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    /**
     * Find distinct task IDs with events in the given time range.
     */
    @Query("SELECT DISTINCT e.taskId FROM IngestEvent e WHERE e.timestamp BETWEEN :start AND :end")
    List<String> findDistinctTaskIdsByTimestampBetween(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Get summary statistics for completed tasks in a time range.
     */
    @Query("SELECT e.phase, COUNT(e), AVG(e.durationMs), SUM(e.itemsProcessed) " +
           "FROM IngestEvent e " +
           "WHERE e.eventType = 'PHASE_COMPLETED' " +
           "AND e.timestamp BETWEEN :start AND :end " +
           "GROUP BY e.phase")
    List<Object[]> getPhaseSummaryStats(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Find recent tasks (by their terminal events).
     */
    @Query("SELECT e FROM IngestEvent e WHERE e.eventType IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
           "AND e.timestamp > :since ORDER BY e.timestamp DESC")
    List<IngestEvent> findRecentTerminalEvents(@Param("since") Instant since);

    /**
     * Count total events in the database (for monitoring).
     */
    @Query("SELECT COUNT(e) FROM IngestEvent e")
    long countTotalEvents();

    /**
     * Find events by file name pattern.
     */
    @Query("SELECT e FROM IngestEvent e WHERE e.fileName LIKE :pattern ORDER BY e.timestamp DESC")
    List<IngestEvent> findByFileNamePattern(@Param("pattern") String pattern);
}
