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
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob.JobStatus;
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
 * Repository for ExtractionJob entities.
 */
@Repository
public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, Long> {

    /**
     * Find job by its external UUID.
     */
    Optional<ExtractionJob> findByJobId(String jobId);

    /**
     * Find all jobs for a fact sheet.
     */
    List<ExtractionJob> findByFactSheetId(Long factSheetId);

    /**
     * Find all jobs for a fact sheet (paginated).
     */
    Page<ExtractionJob> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Find all jobs with a specific status.
     */
    List<ExtractionJob> findByStatus(JobStatus status);

    /**
     * Find jobs by fact sheet and status.
     */
    List<ExtractionJob> findByFactSheetIdAndStatus(Long factSheetId, JobStatus status);

    /**
     * Find jobs by fact sheet and status (paginated).
     */
    Page<ExtractionJob> findByFactSheetIdAndStatus(Long factSheetId, JobStatus status, Pageable pageable);

    /**
     * Find jobs by builder type.
     */
    List<ExtractionJob> findByBuilderType(String builderType);

    /**
     * Find the most recent job for a fact sheet.
     */
    @Query("SELECT j FROM ExtractionJob j WHERE j.factSheetId = :factSheetId ORDER BY j.createdAt DESC")
    List<ExtractionJob> findRecentByFactSheet(@Param("factSheetId") Long factSheetId, Pageable pageable);

    /**
     * Find running jobs for a fact sheet.
     */
    @Query("SELECT j FROM ExtractionJob j WHERE j.factSheetId = :factSheetId AND j.status = 'RUNNING'")
    List<ExtractionJob> findRunningByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Find pending jobs ordered by creation time.
     */
    @Query("SELECT j FROM ExtractionJob j WHERE j.status = 'PENDING' ORDER BY j.createdAt ASC")
    List<ExtractionJob> findPendingJobsOrdered();

    /**
     * Find jobs created after a specific time.
     */
    @Query("SELECT j FROM ExtractionJob j WHERE j.createdAt > :since ORDER BY j.createdAt DESC")
    List<ExtractionJob> findJobsCreatedAfter(@Param("since") LocalDateTime since);

    /**
     * Count jobs by status.
     */
    long countByStatus(JobStatus status);

    /**
     * Count jobs by fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count jobs by fact sheet and status.
     */
    long countByFactSheetIdAndStatus(Long factSheetId, JobStatus status);

    /**
     * Check if a running job exists for a fact sheet.
     */
    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END FROM ExtractionJob j " +
           "WHERE j.factSheetId = :factSheetId AND j.status = 'RUNNING'")
    boolean hasRunningJob(@Param("factSheetId") Long factSheetId);

    /**
     * Delete old completed jobs.
     */
    @Modifying
    @Query("DELETE FROM ExtractionJob j WHERE j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
           "AND j.completedAt < :before")
    int deleteOldCompletedJobs(@Param("before") LocalDateTime before);

    /**
     * Delete all jobs for a fact sheet.
     */
    @Modifying
    @Query("DELETE FROM ExtractionJob j WHERE j.factSheetId = :factSheetId")
    int deleteByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Update job status.
     */
    @Modifying
    @Query("UPDATE ExtractionJob j SET j.status = :status, j.completedAt = :completedAt WHERE j.jobId = :jobId")
    int updateStatus(
        @Param("jobId") String jobId,
        @Param("status") JobStatus status,
        @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * Update job progress.
     */
    @Modifying
    @Query("UPDATE ExtractionJob j SET j.processedChunks = :processedChunks, " +
           "j.proposalsCreated = :proposalsCreated WHERE j.jobId = :jobId")
    int updateProgress(
        @Param("jobId") String jobId,
        @Param("processedChunks") Integer processedChunks,
        @Param("proposalsCreated") Integer proposalsCreated
    );
}
