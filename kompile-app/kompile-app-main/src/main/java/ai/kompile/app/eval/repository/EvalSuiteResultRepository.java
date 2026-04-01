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
package ai.kompile.app.eval.repository;

import ai.kompile.app.eval.domain.EvalSuiteResultEntity;
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
 * Repository for EvalSuiteResultEntity persistence operations.
 */
@Repository
public interface EvalSuiteResultRepository extends JpaRepository<EvalSuiteResultEntity, String> {

    /**
     * Find all results for a suite ordered by completion time (newest first).
     */
    List<EvalSuiteResultEntity> findBySuiteIdOrderByCompletedAtDesc(String suiteId);

    /**
     * Find the latest result for a suite.
     */
    Optional<EvalSuiteResultEntity> findFirstBySuiteIdOrderByCompletedAtDesc(String suiteId);

    /**
     * Find results for a suite within a time range.
     */
    List<EvalSuiteResultEntity> findBySuiteIdAndCompletedAtBetweenOrderByCompletedAtDesc(
            String suiteId, Instant from, Instant to);

    /**
     * Find results for a suite since a given time.
     */
    @Query("SELECT e FROM EvalSuiteResultEntity e WHERE e.suiteId = :suiteId " +
           "AND e.completedAt >= :since ORDER BY e.completedAt DESC")
    List<EvalSuiteResultEntity> findRecentResultsForSuite(
            @Param("suiteId") String suiteId,
            @Param("since") Instant since);

    /**
     * Find all results for a fact sheet ordered by completion time.
     */
    List<EvalSuiteResultEntity> findByFactSheetIdOrderByCompletedAtDesc(Long factSheetId);

    /**
     * Paginated results for a fact sheet.
     */
    Page<EvalSuiteResultEntity> findByFactSheetIdOrderByCompletedAtDesc(Long factSheetId, Pageable pageable);

    /**
     * Count passed results for a suite.
     */
    long countBySuiteIdAndPassedTrue(String suiteId);

    /**
     * Count total results for a suite.
     */
    long countBySuiteId(String suiteId);

    /**
     * Count passed results for a suite since a given time.
     */
    @Query("SELECT COUNT(e) FROM EvalSuiteResultEntity e " +
           "WHERE e.suiteId = :suiteId AND e.passed = true AND e.completedAt >= :since")
    long countPassedSince(@Param("suiteId") String suiteId, @Param("since") Instant since);

    /**
     * Count total results for a suite since a given time.
     */
    @Query("SELECT COUNT(e) FROM EvalSuiteResultEntity e " +
           "WHERE e.suiteId = :suiteId AND e.completedAt >= :since")
    long countSince(@Param("suiteId") String suiteId, @Param("since") Instant since);

    /**
     * Calculate average pass rate for a suite since a given time.
     */
    @Query("SELECT AVG(e.passRate) FROM EvalSuiteResultEntity e " +
           "WHERE e.suiteId = :suiteId AND e.completedAt >= :since")
    Double getAveragePassRateSince(@Param("suiteId") String suiteId, @Param("since") Instant since);

    /**
     * Delete results older than a given time.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM EvalSuiteResultEntity e WHERE e.completedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);

    /**
     * Delete results for a suite.
     */
    @Modifying
    @Transactional
    void deleteBySuiteId(String suiteId);

    /**
     * Find suite result with test results eagerly loaded.
     */
    @Query("SELECT s FROM EvalSuiteResultEntity s LEFT JOIN FETCH s.testResults WHERE s.id = :resultId")
    Optional<EvalSuiteResultEntity> findByIdWithTestResults(@Param("resultId") String resultId);

    /**
     * Get pass rate trend for a suite.
     */
    @Query("SELECT e.passRate, e.completedAt FROM EvalSuiteResultEntity e " +
           "WHERE e.suiteId = :suiteId AND e.completedAt >= :since " +
           "ORDER BY e.completedAt ASC")
    List<Object[]> getPassRatesSince(@Param("suiteId") String suiteId, @Param("since") Instant since);

    /**
     * Find results for a specific model.
     */
    List<EvalSuiteResultEntity> findByModelIdOrderByCompletedAtDesc(String modelId);

    /**
     * Find result for a specific experiment run.
     */
    Optional<EvalSuiteResultEntity> findByExperimentRunId(String experimentRunId);

    /**
     * Get aggregate metrics for suites in a fact sheet.
     */
    @Query("SELECT " +
           "COUNT(e), " +
           "SUM(CASE WHEN e.passed = true THEN 1 ELSE 0 END), " +
           "AVG(e.passRate), " +
           "AVG(e.averageScore) " +
           "FROM EvalSuiteResultEntity e " +
           "WHERE e.factSheetId = :factSheetId AND e.completedAt >= :since")
    Object[] getFactSheetSuiteMetrics(@Param("factSheetId") Long factSheetId, @Param("since") Instant since);
}
