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
package ai.kompile.knowledgegraph.persistence.dual;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Spring Data JPA repository for {@link PslWeightRow}.
 */
@Repository
public interface PslWeightRowRepository extends JpaRepository<PslWeightRow, Long> {

    /**
     * All rows for a given program key, ordered by version ascending.
     */
    List<PslWeightRow> findByProgramKeyOrderByVersionAsc(String programKey);

    /**
     * All rows for a specific (programKey, version), ordered by rule display.
     */
    List<PslWeightRow> findByProgramKeyAndVersionOrderByRuleDisplayAsc(String programKey, int version);

    /**
     * Fetch the single row with the maximum version for a given program key.
     * Returns empty if no rows exist yet.
     */
    Optional<PslWeightRow> findTopByProgramKeyOrderByVersionDesc(String programKey);

    /**
     * All rows for a given fact sheet and program key, ordered by version ascending.
     * Used to load the full version history when fact-sheet scope is known.
     */
    List<PslWeightRow> findByFactSheetIdAndProgramKeyOrderByVersionAsc(Long factSheetId, String programKey);

    /**
     * Delete all rows at a specific (programKey, version).
     */
    void deleteByProgramKeyAndVersion(String programKey, int version);

    /**
     * True if any rows exist for the given program key.
     */
    boolean existsByProgramKey(String programKey);

    /**
     * All distinct version numbers for the given program key, ascending.
     */
    @Query("SELECT DISTINCT r.version FROM PslWeightRow r WHERE r.programKey = :programKey ORDER BY r.version ASC")
    List<Integer> findVersionsByProgramKey(@Param("programKey") String programKey);

    /**
     * All distinct program keys in the table.
     */
    @Query("SELECT DISTINCT r.programKey FROM PslWeightRow r")
    Set<String> findDistinctProgramKeys();

    /**
     * Maximum version number for the given program key, or null if no rows exist.
     */
    @Query("SELECT MAX(r.version) FROM PslWeightRow r WHERE r.programKey = :programKey")
    Integer findMaxVersionByProgramKey(@Param("programKey") String programKey);
}
