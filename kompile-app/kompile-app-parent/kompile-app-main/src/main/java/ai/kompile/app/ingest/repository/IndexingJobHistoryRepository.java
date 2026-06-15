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

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
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
import java.util.Optional;

/**
 * Repository for IndexingJobHistory entities.
 * Provides query methods for job history retrieval and management.
 */
@Repository
public interface IndexingJobHistoryRepository extends JpaRepository<IndexingJobHistory, Long> {

       /**
        * Find a job history by task ID.
        */
       Optional<IndexingJobHistory> findByTaskId(String taskId);

       /**
        * Find all jobs by status, ordered by start time descending.
        */
       List<IndexingJobHistory> findByStatusOrderByStartTimeDesc(JobStatus status);

       /**
        * Find all jobs by status with pagination.
        */
       Page<IndexingJobHistory> findByStatus(JobStatus status, Pageable pageable);

       /**
        * Find jobs by status within a time range.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.status = :status AND j.startTime > :since ORDER BY j.startTime DESC")
       List<IndexingJobHistory> findByStatusAndStartTimeAfter(@Param("status") JobStatus status, @Param("since") Instant since);

       /**
        * Find jobs by file name pattern.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.fileName LIKE :pattern ORDER BY j.startTime DESC")
       List<IndexingJobHistory> findByFileNamePattern(@Param("pattern") String pattern);

       /**
        * Find jobs within a time range.
        */
       List<IndexingJobHistory> findByStartTimeBetweenOrderByStartTimeDesc(Instant start, Instant end);

       /**
        * Find jobs older than a specified time.
        */
       List<IndexingJobHistory> findByStartTimeBefore(Instant cutoff);

       /**
        * Find jobs within a time range with pagination.
        */
       Page<IndexingJobHistory> findByStartTimeBetween(Instant start, Instant end, Pageable pageable);

       /**
        * Find recent jobs (last N hours).
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.startTime > :since ORDER BY j.startTime DESC")
       List<IndexingJobHistory> findRecentJobs(@Param("since") Instant since);

       /**
        * Find recent jobs with pagination.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.startTime > :since ORDER BY j.startTime DESC")
       Page<IndexingJobHistory> findRecentJobs(@Param("since") Instant since, Pageable pageable);

       /**
        * Find failed jobs by failure reason.
        */
       List<IndexingJobHistory> findByFailureReasonOrderByStartTimeDesc(FailureReason failureReason);

       /**
        * Find jobs by embedding model used.
        */
       List<IndexingJobHistory> findByEmbeddingModelUsedOrderByStartTimeDesc(String embeddingModelUsed);

       /**
        * Find jobs by loader used.
        */
       List<IndexingJobHistory> findByLoaderUsedOrderByStartTimeDesc(String loaderUsed);

       /**
        * Count jobs by status.
        */
       long countByStatus(JobStatus status);

       /**
        * Count jobs by status within a time range.
        */
       @Query("SELECT COUNT(j) FROM IndexingJobHistory j WHERE j.status = :status AND j.startTime > :since")
       long countByStatusAndStartTimeAfter(@Param("status") JobStatus status, @Param("since") Instant since);

       /**
        * Count all jobs within a time range.
        */
       @Query("SELECT COUNT(j) FROM IndexingJobHistory j WHERE j.startTime > :since")
       long countByStartTimeAfter(@Param("since") Instant since);

       /**
        * Count jobs by failure reason.
        */
       long countByFailureReason(FailureReason failureReason);

       /**
        * Get job statistics summary.
        * Returns: [status, count, avgDurationMs, totalDocumentsIndexed]
        */
       @Query("SELECT j.status, COUNT(j), AVG(j.totalDurationMs), SUM(j.documentsIndexed) " +
                     "FROM IndexingJobHistory j " +
                     "WHERE j.startTime BETWEEN :start AND :end " +
                     "GROUP BY j.status")
       List<Object[]> getJobStatisticsByStatus(@Param("start") Instant start, @Param("end") Instant end);

       /**
        * Get average processing times by phase.
        * Returns: [avgLoadingMs, avgConversionMs, avgChunkingMs, avgEmbeddingMs,
        * avgIndexingMs]
        */
       @Query("SELECT AVG(j.loadingDurationMs), AVG(j.conversionDurationMs), AVG(j.chunkingDurationMs), " +
                     "AVG(j.embeddingDurationMs), AVG(j.indexingDurationMs) " +
                     "FROM IndexingJobHistory j " +
                     "WHERE j.status = 'COMPLETED' AND j.startTime BETWEEN :start AND :end")
       List<Object[]> getAveragePhaseTimings(@Param("start") Instant start, @Param("end") Instant end);

       /**
        * Get failure statistics.
        * Returns: [failureReason, count, avgDurationMs]
        */
       @Query("SELECT j.failureReason, COUNT(j), AVG(j.totalDurationMs) " +
                     "FROM IndexingJobHistory j " +
                     "WHERE j.status IN ('FAILED', 'MEMORY_KILLED', 'CANCELLED') " +
                     "AND j.startTime BETWEEN :start AND :end " +
                     "GROUP BY j.failureReason")
       List<Object[]> getFailureStatistics(@Param("start") Instant start, @Param("end") Instant end);

       /**
        * Get throughput statistics.
        * Returns: [totalDocumentsIndexed, totalChunksCreated, totalDurationMs,
        * totalJobs]
        */
       @Query("SELECT SUM(j.documentsIndexed), SUM(j.chunksCreated), SUM(j.totalDurationMs), COUNT(j) " +
                     "FROM IndexingJobHistory j " +
                     "WHERE j.status = 'COMPLETED' AND j.startTime BETWEEN :start AND :end")
       List<Object[]> getThroughputStatistics(@Param("start") Instant start, @Param("end") Instant end);

       /**
        * Find jobs with high memory usage.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.peakMemoryUsagePercent > :threshold " +
                     "ORDER BY j.peakMemoryUsagePercent DESC")
       List<IndexingJobHistory> findJobsWithHighMemoryUsage(@Param("threshold") double threshold);

       /**
        * Find long-running jobs (duration exceeds threshold).
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.totalDurationMs > :thresholdMs " +
                     "AND j.status = 'COMPLETED' ORDER BY j.totalDurationMs DESC")
       List<IndexingJobHistory> findLongRunningJobs(@Param("thresholdMs") long thresholdMs);

       /**
        * Find currently running jobs.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.status IN ('QUEUED', 'RUNNING') " +
                     "ORDER BY j.startTime ASC")
       List<IndexingJobHistory> findActiveJobs();

       /**
        * Delete jobs older than a specified time.
        */
       @Modifying
       @Transactional
       @Query("DELETE FROM IndexingJobHistory j WHERE j.startTime < :cutoff")
       int deleteJobsOlderThan(@Param("cutoff") Instant cutoff);

       /**
        * Delete completed jobs older than a specified time (keep failed for analysis).
        */
       @Modifying
       @Transactional
       @Query("DELETE FROM IndexingJobHistory j WHERE j.status = 'COMPLETED' AND j.startTime < :cutoff")
       int deleteCompletedJobsOlderThan(@Param("cutoff") Instant cutoff);

       /**
        * Get distinct embedding models used.
        */
       @Query("SELECT DISTINCT j.embeddingModelUsed FROM IndexingJobHistory j WHERE j.embeddingModelUsed IS NOT NULL")
       List<String> findDistinctEmbeddingModels();

       /**
        * Get distinct loaders used.
        */
       @Query("SELECT DISTINCT j.loaderUsed FROM IndexingJobHistory j WHERE j.loaderUsed IS NOT NULL")
       List<String> findDistinctLoaders();

       /**
        * Get distinct chunkers used.
        */
       @Query("SELECT DISTINCT j.chunkerUsed FROM IndexingJobHistory j WHERE j.chunkerUsed IS NOT NULL")
       List<String> findDistinctChunkers();

       /**
        * Check if a job exists for a task ID.
        */
       boolean existsByTaskId(String taskId);

       /**
        * Get the most recent N jobs.
        */
       List<IndexingJobHistory> findTop100ByOrderByStartTimeDesc();

       /**
        * Get jobs for a specific index path.
        */
       List<IndexingJobHistory> findByIndexPathOrderByStartTimeDesc(String indexPath);

       // ===================== RESTART TRACKING QUERIES =====================

       /**
        * Find jobs that recovered after restart.
        */
       List<IndexingJobHistory> findByRecoveredAfterRestartTrue();

       /**
        * Find jobs with restart attempts greater than specified value.
        */
       List<IndexingJobHistory> findByRestartAttemptsGreaterThan(int attempts);

       /**
        * Find jobs with restart attempts ordered by restart count.
        */
       @Query("SELECT j FROM IndexingJobHistory j WHERE j.restartAttempts > 0 ORDER BY j.restartAttempts DESC, j.startTime DESC")
       List<IndexingJobHistory> findJobsWithRestartsOrderedByAttempts();

       /**
        * Get restart statistics.
        * Returns: [totalJobsWithRestarts, successfulRecoveries, failedAfterRestarts, avgRestartAttempts]
        */
       @Query("SELECT COUNT(j), " +
                     "SUM(CASE WHEN j.recoveredAfterRestart = true THEN 1 ELSE 0 END), " +
                     "SUM(CASE WHEN j.recoveredAfterRestart = false AND j.status IN ('FAILED', 'MEMORY_KILLED') THEN 1 ELSE 0 END), " +
                     "AVG(j.restartAttempts) " +
                     "FROM IndexingJobHistory j WHERE j.restartAttempts > 0")
       List<Object[]> getRestartStatistics();

       /**
        * Get restart statistics within a time range.
        */
       @Query("SELECT COUNT(j), " +
                     "SUM(CASE WHEN j.recoveredAfterRestart = true THEN 1 ELSE 0 END), " +
                     "SUM(CASE WHEN j.recoveredAfterRestart = false AND j.status IN ('FAILED', 'MEMORY_KILLED') THEN 1 ELSE 0 END), " +
                     "AVG(j.restartAttempts) " +
                     "FROM IndexingJobHistory j WHERE j.restartAttempts > 0 AND j.startTime BETWEEN :start AND :end")
       List<Object[]> getRestartStatistics(@Param("start") Instant start, @Param("end") Instant end);

       /**
        * Count jobs that had restarts.
        */
       @Query("SELECT COUNT(j) FROM IndexingJobHistory j WHERE j.restartAttempts > 0")
       long countJobsWithRestarts();

       /**
        * Count jobs that recovered after restarts.
        */
       @Query("SELECT COUNT(j) FROM IndexingJobHistory j WHERE j.recoveredAfterRestart = true")
       long countJobsRecoveredAfterRestart();
}
