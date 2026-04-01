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
package ai.kompile.app.prompts.repository;

import ai.kompile.app.prompts.domain.SystemPromptTestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for SystemPromptTestResultEntity.
 */
@Repository
public interface SystemPromptTestResultRepository extends JpaRepository<SystemPromptTestResultEntity, String> {

    // ==================== Basic Lookups ====================

    /**
     * Find all test results for a specific prompt, ordered by completion time descending.
     */
    List<SystemPromptTestResultEntity> findByPromptIdOrderByCompletedAtDesc(String promptId);

    /**
     * Find all test results for a specific eval suite, ordered by completion time descending.
     */
    List<SystemPromptTestResultEntity> findByEvalSuiteIdOrderByCompletedAtDesc(String evalSuiteId);

    /**
     * Find test results for a specific prompt and eval suite combination.
     */
    List<SystemPromptTestResultEntity> findByPromptIdAndEvalSuiteIdOrderByCompletedAtDesc(
            String promptId, String evalSuiteId);

    // ==================== Filtering by Status ====================

    /**
     * Find all passing test results for a prompt.
     */
    List<SystemPromptTestResultEntity> findByPromptIdAndPassedTrueOrderByCompletedAtDesc(String promptId);

    /**
     * Find all failing test results for a prompt.
     */
    List<SystemPromptTestResultEntity> findByPromptIdAndPassedFalseOrderByCompletedAtDesc(String promptId);

    // ==================== Time-based Queries ====================

    /**
     * Find test results within a time range.
     */
    @Query("SELECT r FROM SystemPromptTestResultEntity r " +
           "WHERE r.promptId = :promptId " +
           "AND r.completedAt >= :startTime AND r.completedAt <= :endTime " +
           "ORDER BY r.completedAt DESC")
    List<SystemPromptTestResultEntity> findByPromptIdAndTimeRange(
            @Param("promptId") String promptId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    /**
     * Find the most recent test result for a prompt.
     */
    @Query("SELECT r FROM SystemPromptTestResultEntity r " +
           "WHERE r.promptId = :promptId " +
           "ORDER BY r.completedAt DESC LIMIT 1")
    SystemPromptTestResultEntity findMostRecentByPromptId(@Param("promptId") String promptId);

    // ==================== Statistics ====================

    /**
     * Count test results for a prompt.
     */
    long countByPromptId(String promptId);

    /**
     * Count passing test results for a prompt.
     */
    long countByPromptIdAndPassedTrue(String promptId);

    /**
     * Get average score for a prompt.
     */
    @Query("SELECT AVG(r.score) FROM SystemPromptTestResultEntity r WHERE r.promptId = :promptId AND r.score IS NOT NULL")
    Double findAverageScoreByPromptId(@Param("promptId") String promptId);

    // ==================== Comparison Queries ====================

    /**
     * Find test results for comparing two prompts on the same eval suite.
     */
    @Query("SELECT r FROM SystemPromptTestResultEntity r " +
           "WHERE r.promptId IN (:promptId1, :promptId2) " +
           "AND r.evalSuiteId = :evalSuiteId " +
           "ORDER BY r.promptId, r.completedAt DESC")
    List<SystemPromptTestResultEntity> findForComparison(
            @Param("promptId1") String promptId1,
            @Param("promptId2") String promptId2,
            @Param("evalSuiteId") String evalSuiteId);

    // ==================== Deletion ====================

    /**
     * Delete all test results for a prompt.
     */
    void deleteByPromptId(String promptId);
}
