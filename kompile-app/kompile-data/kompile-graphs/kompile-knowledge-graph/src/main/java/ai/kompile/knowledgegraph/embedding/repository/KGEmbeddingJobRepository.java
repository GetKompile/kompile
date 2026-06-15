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

package ai.kompile.knowledgegraph.embedding.repository;

import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for KGEmbeddingJob entities.
 */
@Repository
public interface KGEmbeddingJobRepository extends JpaRepository<KGEmbeddingJob, String> {

    /**
     * Find job by ID.
     */
    Optional<KGEmbeddingJob> findByJobId(String jobId);

    /**
     * Find jobs by fact sheet ID.
     */
    List<KGEmbeddingJob> findByFactSheetId(Long factSheetId);

    /**
     * Find jobs by fact sheet ID (paginated, most recent first).
     */
    Page<KGEmbeddingJob> findByFactSheetIdOrderByCreatedAtDesc(Long factSheetId, Pageable pageable);

    /**
     * Find jobs by status.
     */
    List<KGEmbeddingJob> findByStatus(JobStatus status);

    /**
     * Find jobs by fact sheet and status.
     */
    List<KGEmbeddingJob> findByFactSheetIdAndStatus(Long factSheetId, JobStatus status);

    /**
     * Find the most recent completed job for a fact sheet.
     */
    @Query("SELECT j FROM KGEmbeddingJob j WHERE j.factSheetId = :factSheetId AND j.status = 'COMPLETED' " +
           "ORDER BY j.completedAt DESC")
    List<KGEmbeddingJob> findMostRecentCompletedJob(@Param("factSheetId") Long factSheetId, Pageable pageable);

    /**
     * Check if there's a running job for a fact sheet.
     */
    @Query("SELECT COUNT(j) > 0 FROM KGEmbeddingJob j WHERE j.factSheetId = :factSheetId AND j.status = 'RUNNING'")
    boolean hasRunningJob(@Param("factSheetId") Long factSheetId);

    /**
     * Find running jobs.
     */
    @Query("SELECT j FROM KGEmbeddingJob j WHERE j.status = 'RUNNING'")
    List<KGEmbeddingJob> findRunningJobs();

    /**
     * Count jobs by fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count completed jobs by fact sheet.
     */
    @Query("SELECT COUNT(j) FROM KGEmbeddingJob j WHERE j.factSheetId = :factSheetId AND j.status = 'COMPLETED'")
    long countCompletedByFactSheetId(@Param("factSheetId") Long factSheetId);
}
