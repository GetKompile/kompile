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

import ai.kompile.app.eval.domain.EvalTestResultEntity;
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
 * Repository for EvalTestResultEntity persistence operations.
 */
@Repository
public interface EvalTestResultRepository extends JpaRepository<EvalTestResultEntity, String> {

    /**
     * Find all results for a test case ordered by completion time (newest first).
     */
    List<EvalTestResultEntity> findByTestCaseIdOrderByCompletedAtDesc(String testCaseId);

    /**
     * Find the latest result for a test case.
     */
    Optional<EvalTestResultEntity> findFirstByTestCaseIdOrderByCompletedAtDesc(String testCaseId);

    /**
     * Find results for a test case within a time range.
     */
    List<EvalTestResultEntity> findByTestCaseIdAndCompletedAtBetweenOrderByCompletedAtDesc(
            String testCaseId, Instant from, Instant to);

    /**
     * Find results for a test case since a given time.
     */
    @Query("SELECT e FROM EvalTestResultEntity e WHERE e.testCaseId = :testCaseId " +
           "AND e.completedAt >= :since ORDER BY e.completedAt DESC")
    List<EvalTestResultEntity> findRecentResultsForTestCase(
            @Param("testCaseId") String testCaseId,
            @Param("since") Instant since);

    /**
     * Find all results for a fact sheet ordered by completion time.
     */
    List<EvalTestResultEntity> findByFactSheetIdOrderByCompletedAtDesc(Long factSheetId);

    /**
     * Paginated results for a fact sheet.
     */
    Page<EvalTestResultEntity> findByFactSheetIdOrderByCompletedAtDesc(Long factSheetId, Pageable pageable);

    /**
     * Find results for a suite execution.
     */
    List<EvalTestResultEntity> findBySuiteIdOrderByCompletedAtDesc(String suiteId);

    /**
     * Find results for a suite result.
     */
    List<EvalTestResultEntity> findBySuiteResultIdOrderByCompletedAtDesc(String suiteResultId);

    /**
     * Count passed results for a test case.
     */
    long countByTestCaseIdAndPassedTrue(String testCaseId);

    /**
     * Count total results for a test case.
     */
    long countByTestCaseId(String testCaseId);

    /**
     * Count passed results for a test case since a given time.
     */
    @Query("SELECT COUNT(e) FROM EvalTestResultEntity e " +
           "WHERE e.testCaseId = :testCaseId AND e.passed = true AND e.completedAt >= :since")
    long countPassedSince(@Param("testCaseId") String testCaseId, @Param("since") Instant since);

    /**
     * Count total results for a test case since a given time.
     */
    @Query("SELECT COUNT(e) FROM EvalTestResultEntity e " +
           "WHERE e.testCaseId = :testCaseId AND e.completedAt >= :since")
    long countSince(@Param("testCaseId") String testCaseId, @Param("since") Instant since);

    /**
     * Calculate average score for a test case since a given time.
     */
    @Query("SELECT AVG(e.score) FROM EvalTestResultEntity e " +
           "WHERE e.testCaseId = :testCaseId AND e.completedAt >= :since")
    Double getAverageScoreSince(@Param("testCaseId") String testCaseId, @Param("since") Instant since);

    /**
     * Delete results older than a given time.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM EvalTestResultEntity e WHERE e.completedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);

    /**
     * Delete results for a test case.
     */
    @Modifying
    @Transactional
    void deleteByTestCaseId(String testCaseId);

    /**
     * Get recent scores for trend analysis.
     */
    @Query("SELECT e.score, e.completedAt FROM EvalTestResultEntity e " +
           "WHERE e.testCaseId = :testCaseId AND e.completedAt >= :since " +
           "ORDER BY e.completedAt ASC")
    List<Object[]> getScoresSince(@Param("testCaseId") String testCaseId, @Param("since") Instant since);

    /**
     * Find consistently failing test cases for a fact sheet.
     */
    @Query("SELECT DISTINCT e.testCaseId FROM EvalTestResultEntity e " +
           "WHERE e.factSheetId = :factSheetId AND e.passed = false " +
           "AND e.completedAt >= :since " +
           "GROUP BY e.testCaseId " +
           "HAVING COUNT(e) >= :minFailures AND " +
           "SUM(CASE WHEN e.passed = true THEN 1 ELSE 0 END) = 0")
    List<String> findConsistentlyFailingTestCases(
            @Param("factSheetId") Long factSheetId,
            @Param("since") Instant since,
            @Param("minFailures") long minFailures);

    /**
     * Find results for a specific model.
     */
    List<EvalTestResultEntity> findByModelIdOrderByCompletedAtDesc(String modelId);

    /**
     * Find results for a specific experiment run.
     */
    List<EvalTestResultEntity> findByExperimentRunIdOrderByCompletedAtDesc(String experimentRunId);

    /**
     * Get metrics for a fact sheet.
     */
    @Query("SELECT " +
           "COUNT(e), " +
           "SUM(CASE WHEN e.passed = true THEN 1 ELSE 0 END), " +
           "AVG(e.score) " +
           "FROM EvalTestResultEntity e " +
           "WHERE e.factSheetId = :factSheetId AND e.completedAt >= :since")
    Object[] getFactSheetMetrics(@Param("factSheetId") Long factSheetId, @Param("since") Instant since);
}
