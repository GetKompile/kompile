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

import ai.kompile.app.prompts.domain.SystemPromptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for SystemPromptEntity with support for versioning and fact sheet scoping.
 */
@Repository
public interface SystemPromptRepository extends JpaRepository<SystemPromptEntity, String> {

    // ==================== Basic Lookups ====================

    /**
     * Find all prompts for a specific fact sheet, ordered by version descending.
     */
    List<SystemPromptEntity> findByFactSheetIdOrderByVersionDesc(Long factSheetId);

    /**
     * Find all prompts for a specific fact sheet, ordered by creation date descending.
     */
    List<SystemPromptEntity> findByFactSheetIdOrderByCreatedAtDesc(Long factSheetId);

    /**
     * Find the active prompt for a specific fact sheet.
     */
    Optional<SystemPromptEntity> findByFactSheetIdAndIsActiveTrue(Long factSheetId);

    /**
     * Find a specific version of a prompt by fact sheet ID and version number.
     */
    Optional<SystemPromptEntity> findByFactSheetIdAndVersion(Long factSheetId, Integer version);

    /**
     * Find prompts by name pattern (case-insensitive search).
     */
    @Query("SELECT p FROM SystemPromptEntity p WHERE p.factSheetId = :factSheetId " +
           "AND LOWER(p.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<SystemPromptEntity> searchByName(@Param("factSheetId") Long factSheetId,
                                          @Param("searchTerm") String searchTerm);

    // ==================== Version Management ====================

    /**
     * Get the maximum version number for a fact sheet.
     */
    @Query("SELECT MAX(p.version) FROM SystemPromptEntity p WHERE p.factSheetId = :factSheetId")
    Optional<Integer> findMaxVersionByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Get all versions of a prompt by following the parent chain.
     * This finds all prompts that share the same root (by finding all prompts with the same name).
     */
    @Query("SELECT p FROM SystemPromptEntity p WHERE p.factSheetId = :factSheetId " +
           "AND p.name = :name ORDER BY p.version DESC")
    List<SystemPromptEntity> findVersionHistory(@Param("factSheetId") Long factSheetId,
                                                @Param("name") String name);

    /**
     * Find all prompts that are children of a given prompt version.
     */
    List<SystemPromptEntity> findByParentVersionIdOrderByVersionDesc(String parentVersionId);

    // ==================== Activation Operations ====================

    /**
     * Deactivate all prompts for a fact sheet.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SystemPromptEntity p SET p.isActive = false WHERE p.factSheetId = :factSheetId")
    int deactivateAllForFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Activate a specific prompt.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SystemPromptEntity p SET p.isActive = true WHERE p.id = :promptId")
    int activatePrompt(@Param("promptId") String promptId);

    // ==================== Tag-based Queries ====================

    /**
     * Find prompts containing a specific tag.
     */
    @Query("SELECT p FROM SystemPromptEntity p WHERE p.factSheetId = :factSheetId " +
           "AND p.tagsJson LIKE CONCAT('%\"', :tag, '\"%')")
    List<SystemPromptEntity> findByTag(@Param("factSheetId") Long factSheetId,
                                       @Param("tag") String tag);

    // ==================== Counting ====================

    /**
     * Count prompts for a fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count active prompts for a fact sheet (should be 0 or 1).
     */
    long countByFactSheetIdAndIsActiveTrue(Long factSheetId);

    // ==================== Deletion ====================

    /**
     * Delete all prompts for a fact sheet.
     */
    @Modifying
    @Transactional
    void deleteByFactSheetId(Long factSheetId);
}
