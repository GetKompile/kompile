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

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.domain.JobLogEntry.LogSource;
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
 * Repository for JobLogEntry entities.
 * Provides query methods for job log retrieval, filtering, and cleanup.
 */
@Repository
public interface JobLogRepository extends JpaRepository<JobLogEntry, Long> {

    /**
     * Find all log entries for a specific task, ordered by sequence number.
     */
    List<JobLogEntry> findByTaskIdOrderBySequenceNumberAsc(String taskId);

    /**
     * Find log entries for a task with pagination, ordered by sequence number.
     */
    Page<JobLogEntry> findByTaskIdOrderBySequenceNumberAsc(String taskId, Pageable pageable);

    /**
     * Find log entries for a task filtered by log level.
     */
    List<JobLogEntry> findByTaskIdAndLevelOrderBySequenceNumberAsc(String taskId, LogLevel level);

    /**
     * Find log entries for a task filtered by multiple log levels.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND l.level IN :levels ORDER BY l.sequenceNumber ASC")
    List<JobLogEntry> findByTaskIdAndLevelsOrderBySequenceNumberAsc(
            @Param("taskId") String taskId,
            @Param("levels") List<LogLevel> levels);

    /**
     * Find log entries for a task filtered by multiple log levels with pagination.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND l.level IN :levels ORDER BY l.sequenceNumber ASC")
    Page<JobLogEntry> findByTaskIdAndLevelsOrderBySequenceNumberAsc(
            @Param("taskId") String taskId,
            @Param("levels") List<LogLevel> levels,
            Pageable pageable);

    /**
     * Find log entries for a task filtered by a single log level with pagination.
     */
    Page<JobLogEntry> findByTaskIdAndLevelOrderBySequenceNumberAsc(String taskId, LogLevel level, Pageable pageable);

    /**
     * Find log entries for a task filtered by source.
     */
    List<JobLogEntry> findByTaskIdAndSourceOrderBySequenceNumberAsc(String taskId, LogSource source);

    /**
     * Find the last N log entries for a task (for tailing).
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId ORDER BY l.sequenceNumber DESC")
    List<JobLogEntry> findLastNByTaskId(@Param("taskId") String taskId, Pageable pageable);

    /**
     * Count log entries for a specific task.
     */
    long countByTaskId(String taskId);

    /**
     * Count log entries by level for a task.
     */
    long countByTaskIdAndLevel(String taskId, LogLevel level);

    /**
     * Delete all log entries for a specific task.
     * Used for cascade delete when job history is deleted.
     */
    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    /**
     * Delete log entries older than a specified timestamp.
     * Used for cleanup of old logs based on retention policy.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM JobLogEntry l WHERE l.timestamp < :cutoff")
    int deleteLogsOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Delete oldest entries for a task to enforce max entries per job.
     * Deletes entries with sequence number below the threshold.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM JobLogEntry l WHERE l.taskId = :taskId AND l.sequenceNumber < :minSequence")
    int deleteOldestForTask(@Param("taskId") String taskId, @Param("minSequence") long minSequence);

    /**
     * Find the minimum sequence number to keep for a task to stay within max entries.
     * Returns the sequence number at the offset from the max.
     */
    @Query("SELECT l.sequenceNumber FROM JobLogEntry l WHERE l.taskId = :taskId ORDER BY l.sequenceNumber DESC")
    List<Long> findSequenceNumbersDescending(@Param("taskId") String taskId, Pageable pageable);

    /**
     * Count total log entries in the database.
     */
    @Query("SELECT COUNT(l) FROM JobLogEntry l")
    long countTotalLogs();

    /**
     * Get distinct task IDs that have log entries.
     */
    @Query("SELECT DISTINCT l.taskId FROM JobLogEntry l")
    List<String> findDistinctTaskIds();

    /**
     * Get count of logs grouped by task ID.
     */
    @Query("SELECT l.taskId, COUNT(l) FROM JobLogEntry l GROUP BY l.taskId ORDER BY COUNT(l) DESC")
    List<Object[]> getLogCountsByTaskId();

    /**
     * Get count of logs grouped by log level.
     */
    @Query("SELECT l.level, COUNT(l) FROM JobLogEntry l WHERE l.taskId = :taskId GROUP BY l.level")
    List<Object[]> getLogCountsByLevel(@Param("taskId") String taskId);

    /**
     * Search log entries by message content.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY l.sequenceNumber ASC")
    List<JobLogEntry> searchByMessage(@Param("taskId") String taskId, @Param("search") String search);

    /**
     * Search log entries by message content with pagination.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY l.sequenceNumber ASC")
    Page<JobLogEntry> searchByMessage(@Param("taskId") String taskId, @Param("search") String search, Pageable pageable);

    /**
     * Search log entries by message content filtered by multiple log levels with pagination.
     * Combines level filtering with search for better performance.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND l.level IN :levels AND LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY l.sequenceNumber ASC")
    Page<JobLogEntry> searchByMessageWithLevels(
            @Param("taskId") String taskId,
            @Param("search") String search,
            @Param("levels") List<LogLevel> levels,
            Pageable pageable);

    /**
     * Search log entries by message content filtered by a single log level with pagination.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND l.level = :level AND LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY l.sequenceNumber ASC")
    Page<JobLogEntry> searchByMessageWithLevel(
            @Param("taskId") String taskId,
            @Param("search") String search,
            @Param("level") LogLevel level,
            Pageable pageable);

    /**
     * Find log entries with errors (has stackTrace).
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId = :taskId AND l.stackTrace IS NOT NULL ORDER BY l.sequenceNumber ASC")
    List<JobLogEntry> findErrorsWithStackTrace(@Param("taskId") String taskId);

    /**
     * Get the max sequence number for a task.
     * Used for generating new sequence numbers.
     */
    @Query("SELECT MAX(l.sequenceNumber) FROM JobLogEntry l WHERE l.taskId = :taskId")
    Long findMaxSequenceNumber(@Param("taskId") String taskId);

    /**
     * Find log entries in a time range.
     */
    List<JobLogEntry> findByTaskIdAndTimestampBetweenOrderBySequenceNumberAsc(
            String taskId, Instant start, Instant end);

    /**
     * Delete logs for oldest tasks to enforce total entry limit.
     * Returns the task IDs of tasks whose logs were deleted.
     */
    @Query("SELECT l.taskId FROM JobLogEntry l GROUP BY l.taskId ORDER BY MIN(l.timestamp) ASC")
    List<String> findOldestTaskIds(Pageable pageable);

    /**
     * Find all log entries by source type, ordered by timestamp descending.
     * Used for retrieving all embedding logs across all models.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.source = :source ORDER BY l.timestamp DESC")
    List<JobLogEntry> findBySourceOrderByTimestampDesc(@Param("source") LogSource source, Pageable pageable);

    /**
     * Find log entries where task ID starts with a prefix and has a specific source.
     */
    @Query("SELECT l FROM JobLogEntry l WHERE l.taskId LIKE CONCAT(:prefix, '%') AND l.source = :source ORDER BY l.timestamp DESC")
    List<JobLogEntry> findByTaskIdPrefixAndSource(
            @Param("prefix") String taskIdPrefix,
            @Param("source") LogSource source,
            Pageable pageable);

    /**
     * Get distinct task IDs with a specific source.
     */
    @Query("SELECT DISTINCT l.taskId FROM JobLogEntry l WHERE l.source = :source")
    List<String> findDistinctTaskIdsBySource(@Param("source") LogSource source);
}
