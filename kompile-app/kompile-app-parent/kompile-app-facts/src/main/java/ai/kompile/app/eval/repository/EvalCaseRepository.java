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

import ai.kompile.app.eval.domain.EvalCaseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for EvalCaseEntity persistence operations.
 */
@Repository
public interface EvalCaseRepository extends JpaRepository<EvalCaseEntity, String> {

    /**
     * Find all test cases for a specific fact sheet.
     */
    List<EvalCaseEntity> findByFactSheetId(Long factSheetId);

    /**
     * Find all enabled test cases for a specific fact sheet.
     */
    List<EvalCaseEntity> findByFactSheetIdAndEnabledTrue(Long factSheetId);

    /**
     * Find all test cases in a specific suite.
     */
    List<EvalCaseEntity> findBySuiteId(String suiteId);

    /**
     * Find all test cases in a suite that are enabled.
     */
    List<EvalCaseEntity> findBySuiteIdAndEnabledTrue(String suiteId);

    /**
     * Find all test cases not assigned to any suite.
     */
    @Query("SELECT e FROM EvalCaseEntity e WHERE e.suite IS NULL")
    List<EvalCaseEntity> findUnassignedTestCases();

    /**
     * Find test cases for a fact sheet that are not in any suite.
     */
    @Query("SELECT e FROM EvalCaseEntity e WHERE e.factSheetId = :factSheetId AND e.suite IS NULL")
    List<EvalCaseEntity> findUnassignedByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Find test cases by tag (searches in JSON array).
     * Note: This uses a simple LIKE pattern; for production, consider JSON functions.
     */
    @Query("SELECT e FROM EvalCaseEntity e WHERE e.tagsJson LIKE %:tag%")
    List<EvalCaseEntity> findByTagsContaining(@Param("tag") String tag);

    /**
     * Find test cases by priority.
     */
    List<EvalCaseEntity> findByPriority(Integer priority);

    /**
     * Find test cases with priority >= given value.
     */
    List<EvalCaseEntity> findByPriorityGreaterThanEqual(Integer priority);

    /**
     * Count test cases for a fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count enabled test cases for a fact sheet.
     */
    long countByFactSheetIdAndEnabledTrue(Long factSheetId);

    /**
     * Count test cases in a suite.
     */
    long countBySuiteId(String suiteId);

    /**
     * Paginated query for test cases by fact sheet.
     */
    Page<EvalCaseEntity> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Find all test cases ordered by creation time.
     */
    List<EvalCaseEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Find test cases by fact sheet ordered by priority.
     */
    List<EvalCaseEntity> findByFactSheetIdOrderByPriorityDesc(Long factSheetId);

    /**
     * Find test cases in a suite ordered by creation time ascending.
     */
    List<EvalCaseEntity> findBySuiteIdOrderByCreatedAtAsc(String suiteId);
}
