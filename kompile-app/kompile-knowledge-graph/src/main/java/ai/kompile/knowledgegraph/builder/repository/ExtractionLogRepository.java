/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.builder.repository;

import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ExtractionLogRecord entities.
 * Provides access to LLM extraction logs for full transparency.
 */
@Repository
public interface ExtractionLogRepository extends JpaRepository<ExtractionLogRecord, Long> {

    /**
     * Find all logs for a job.
     */
    List<ExtractionLogRecord> findByJob(ExtractionJob job);

    /**
     * Find all logs for a job (paginated).
     */
    Page<ExtractionLogRecord> findByJob(ExtractionJob job, Pageable pageable);

    /**
     * Find all logs for a job ID.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId ORDER BY l.createdAt ASC")
    List<ExtractionLogRecord> findByJobId(@Param("jobId") String jobId);

    /**
     * Find all logs for a job ID (paginated).
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId ORDER BY l.createdAt ASC")
    Page<ExtractionLogRecord> findByJobId(@Param("jobId") String jobId, Pageable pageable);

    /**
     * Find log for a specific chunk within a job.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.chunkId = :chunkId")
    Optional<ExtractionLogRecord> findByJobIdAndChunkId(
        @Param("jobId") String jobId,
        @Param("chunkId") String chunkId
    );

    /**
     * Find all logs for a chunk ID (across jobs).
     */
    List<ExtractionLogRecord> findByChunkId(String chunkId);

    /**
     * Find all logs for a document ID.
     */
    List<ExtractionLogRecord> findByDocumentId(String documentId);

    /**
     * Find failed logs for a job.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = false " +
           "ORDER BY l.createdAt ASC")
    List<ExtractionLogRecord> findFailedByJobId(@Param("jobId") String jobId);

    /**
     * Find successful logs for a job.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = true " +
           "ORDER BY l.createdAt ASC")
    List<ExtractionLogRecord> findSuccessfulByJobId(@Param("jobId") String jobId);

    /**
     * Find logs by model provider.
     */
    List<ExtractionLogRecord> findByModelProvider(String modelProvider);

    /**
     * Find logs by model name.
     */
    List<ExtractionLogRecord> findByModelName(String modelName);

    /**
     * Count logs by job.
     */
    @Query("SELECT COUNT(l) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId")
    long countByJobId(@Param("jobId") String jobId);

    /**
     * Count successful logs by job.
     */
    @Query("SELECT COUNT(l) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = true")
    long countSuccessfulByJobId(@Param("jobId") String jobId);

    /**
     * Count failed logs by job.
     */
    @Query("SELECT COUNT(l) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = false")
    long countFailedByJobId(@Param("jobId") String jobId);

    /**
     * Get average latency for a job.
     */
    @Query("SELECT AVG(l.latencyMs) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = true")
    Double getAverageLatencyByJobId(@Param("jobId") String jobId);

    /**
     * Get total tokens used for a job.
     */
    @Query("SELECT SUM(l.promptTokens + l.responseTokens) FROM ExtractionLogRecord l " +
           "WHERE l.job.jobId = :jobId AND l.success = true")
    Long getTotalTokensByJobId(@Param("jobId") String jobId);

    /**
     * Get total entities extracted for a job.
     */
    @Query("SELECT SUM(l.entitiesCount) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = true")
    Long getTotalEntitiesByJobId(@Param("jobId") String jobId);

    /**
     * Get total relationships extracted for a job.
     */
    @Query("SELECT SUM(l.relationshipsCount) FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId AND l.success = true")
    Long getTotalRelationshipsByJobId(@Param("jobId") String jobId);

    /**
     * Find recent logs (for debugging/monitoring).
     */
    @Query("SELECT l FROM ExtractionLogRecord l ORDER BY l.createdAt DESC")
    List<ExtractionLogRecord> findRecentLogs(Pageable pageable);

    /**
     * Find logs with errors matching a pattern.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.success = false AND " +
           "LOWER(l.errorMessage) LIKE LOWER(CONCAT('%', :pattern, '%'))")
    List<ExtractionLogRecord> findByErrorPattern(@Param("pattern") String pattern);

    /**
     * Delete all logs for a job.
     */
    @Modifying
    @Query("DELETE FROM ExtractionLogRecord l WHERE l.job.jobId = :jobId")
    int deleteByJobId(@Param("jobId") String jobId);

    /**
     * Delete old logs.
     */
    @Modifying
    @Query("DELETE FROM ExtractionLogRecord l WHERE l.createdAt < :before")
    int deleteOldLogs(@Param("before") LocalDateTime before);

    /**
     * Find logs with the most entities extracted.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.success = true " +
           "ORDER BY l.entitiesCount DESC")
    List<ExtractionLogRecord> findTopByEntitiesCount(Pageable pageable);

    /**
     * Find slowest extractions.
     */
    @Query("SELECT l FROM ExtractionLogRecord l WHERE l.success = true " +
           "ORDER BY l.latencyMs DESC")
    List<ExtractionLogRecord> findSlowestExtractions(Pageable pageable);
}
