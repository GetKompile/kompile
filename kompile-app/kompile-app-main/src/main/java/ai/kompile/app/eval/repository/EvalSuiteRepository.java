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

import ai.kompile.app.eval.domain.EvalSuiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for EvalSuiteEntity persistence operations.
 */
@Repository
public interface EvalSuiteRepository extends JpaRepository<EvalSuiteEntity, String> {

    /**
     * Find all suites for a specific fact sheet.
     */
    List<EvalSuiteEntity> findByFactSheetId(Long factSheetId);

    /**
     * Find all enabled suites for a specific fact sheet.
     */
    List<EvalSuiteEntity> findByFactSheetIdAndEnabledTrue(Long factSheetId);

    /**
     * Find a suite by name and fact sheet.
     */
    Optional<EvalSuiteEntity> findByNameAndFactSheetId(String name, Long factSheetId);

    /**
     * Find a suite by name.
     */
    Optional<EvalSuiteEntity> findByName(String name);

    /**
     * Find suites by tag (searches in JSON array).
     */
    @Query("SELECT e FROM EvalSuiteEntity e WHERE e.tagsJson LIKE %:tag%")
    List<EvalSuiteEntity> findByTagsContaining(@Param("tag") String tag);

    /**
     * Find all enabled suites.
     */
    List<EvalSuiteEntity> findByEnabledTrue();

    /**
     * Count suites for a fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Find all suites ordered by name.
     */
    List<EvalSuiteEntity> findAllByOrderByNameAsc();

    /**
     * Find all suites ordered by creation time.
     */
    List<EvalSuiteEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Find suites by fact sheet ordered by name.
     */
    List<EvalSuiteEntity> findByFactSheetIdOrderByNameAsc(Long factSheetId);

    /**
     * Find suite with test cases eagerly loaded.
     */
    @Query("SELECT s FROM EvalSuiteEntity s LEFT JOIN FETCH s.testCases WHERE s.id = :suiteId")
    Optional<EvalSuiteEntity> findByIdWithTestCases(@Param("suiteId") String suiteId);

    /**
     * Find all suites with their test case counts.
     */
    @Query("SELECT s, COUNT(t) FROM EvalSuiteEntity s LEFT JOIN s.testCases t " +
           "WHERE s.factSheetId = :factSheetId GROUP BY s")
    List<Object[]> findSuitesWithTestCaseCountByFactSheet(@Param("factSheetId") Long factSheetId);
}
