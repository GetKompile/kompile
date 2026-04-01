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

package ai.kompile.app.facts.repository;

import ai.kompile.app.facts.domain.FactSheet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for FactSheet entities.
 */
@Repository
public interface FactSheetRepository extends JpaRepository<FactSheet, Long> {

    /**
     * Find a fact sheet by name.
     */
    Optional<FactSheet> findByName(String name);

    /**
     * Check if a fact sheet with the given name exists.
     */
    boolean existsByName(String name);

    /**
     * Find the currently active fact sheet.
     */
    Optional<FactSheet> findByIsActiveTrue();

    /**
     * Find all fact sheets ordered by name.
     */
    List<FactSheet> findAllByOrderByNameAsc();

    /**
     * Find all fact sheets derived from a specific sheet.
     */
    List<FactSheet> findByDerivedFromId(Long derivedFromId);

    /**
     * Deactivate all fact sheets (used before activating a new one).
     */
    @Modifying
    @Transactional
    @Query("UPDATE FactSheet f SET f.isActive = false WHERE f.isActive = true")
    void deactivateAll();

    /**
     * Activate a specific fact sheet by ID.
     */
    @Modifying
    @Transactional
    @Query("UPDATE FactSheet f SET f.isActive = true WHERE f.id = :id")
    void activateById(@Param("id") Long id);

    /**
     * Count facts in a fact sheet.
     */
    @Query("SELECT COUNT(f) FROM Fact f WHERE f.factSheet.id = :sheetId")
    long countFactsBySheetId(@Param("sheetId") Long sheetId);

    /**
     * Find fact sheets with fact counts.
     */
    @Query("SELECT fs, COUNT(f) FROM FactSheet fs LEFT JOIN fs.facts f GROUP BY fs ORDER BY fs.name")
    List<Object[]> findAllWithFactCounts();
}
