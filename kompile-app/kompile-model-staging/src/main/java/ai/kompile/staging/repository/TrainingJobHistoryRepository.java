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

package ai.kompile.staging.repository;

import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.domain.TrainingJobHistory.JobStatus;
import ai.kompile.staging.domain.TrainingJobHistory.TrainingType;
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
 * Repository for TrainingJobHistory entities.
 */
@Repository
public interface TrainingJobHistoryRepository extends JpaRepository<TrainingJobHistory, Long> {

    Optional<TrainingJobHistory> findByTaskId(String taskId);

    List<TrainingJobHistory> findByStatusOrderByStartTimeDesc(JobStatus status);

    Page<TrainingJobHistory> findByStatus(JobStatus status, Pageable pageable);

    List<TrainingJobHistory> findByTrainingTypeOrderByStartTimeDesc(TrainingType trainingType);

    List<TrainingJobHistory> findByModelIdOrderByStartTimeDesc(String modelId);

    @Query("SELECT j FROM TrainingJobHistory j WHERE j.startTime > :since ORDER BY j.startTime DESC")
    List<TrainingJobHistory> findRecentJobs(@Param("since") Instant since);

    @Query("SELECT j FROM TrainingJobHistory j WHERE j.startTime > :since ORDER BY j.startTime DESC")
    Page<TrainingJobHistory> findRecentJobs(@Param("since") Instant since, Pageable pageable);

    List<TrainingJobHistory> findByStartTimeBetweenOrderByStartTimeDesc(Instant start, Instant end);

    @Query("SELECT j FROM TrainingJobHistory j WHERE j.status IN ('QUEUED', 'RUNNING') ORDER BY j.startTime ASC")
    List<TrainingJobHistory> findActiveJobs();

    long countByStatus(JobStatus status);

    @Query("SELECT COUNT(j) FROM TrainingJobHistory j WHERE j.status = :status AND j.startTime > :since")
    long countByStatusAndStartTimeAfter(@Param("status") JobStatus status, @Param("since") Instant since);

    @Query("SELECT j.status, COUNT(j), AVG(j.totalDurationMs) " +
            "FROM TrainingJobHistory j WHERE j.startTime BETWEEN :start AND :end GROUP BY j.status")
    List<Object[]> getJobStatisticsByStatus(@Param("start") Instant start, @Param("end") Instant end);

    boolean existsByTaskId(String taskId);

    List<TrainingJobHistory> findTop100ByOrderByStartTimeDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM TrainingJobHistory j WHERE j.startTime < :cutoff")
    int deleteJobsOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM TrainingJobHistory j WHERE j.status = 'COMPLETED' AND j.startTime < :cutoff")
    int deleteCompletedJobsOlderThan(@Param("cutoff") Instant cutoff);
}
