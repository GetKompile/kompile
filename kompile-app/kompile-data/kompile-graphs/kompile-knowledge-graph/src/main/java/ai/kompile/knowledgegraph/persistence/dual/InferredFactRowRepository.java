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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link InferredFactRow}.
 */
@Repository
public interface InferredFactRowRepository extends JpaRepository<InferredFactRow, Long> {

    /**
     * Full version history for a given (factSheetId, atomKey), oldest first.
     */
    List<InferredFactRow> findByFactSheetIdAndAtomKeyOrderByVersionAsc(Long factSheetId, String atomKey);

    /**
     * Latest (highest-version) row for a given (factSheetId, atomKey).
     */
    Optional<InferredFactRow> findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(Long factSheetId, String atomKey);

    /**
     * All rows for a given (factSheetId, runId), ordered by atom key.
     * Used to implement {@link ai.kompile.graph.reasoning.fol.InferredFactStore#byRun(String)}.
     */
    List<InferredFactRow> findByFactSheetIdAndRunIdOrderByAtomKeyAsc(Long factSheetId, String runId);

    /**
     * The single latest row per atom key for a given fact sheet.
     *
     * <p>Uses a correlated sub-query to select the row whose version equals the maximum version
     * for that (factSheetId, atomKey) pair — equivalent to a DISTINCT ON in SQL but portable
     * across H2 and standard JPA JPQL.</p>
     */
    @Query("SELECT f FROM InferredFactRow f WHERE f.factSheetId = :factSheetId AND f.version = " +
           "(SELECT MAX(f2.version) FROM InferredFactRow f2 WHERE f2.factSheetId = :factSheetId AND f2.atomKey = f.atomKey)")
    List<InferredFactRow> findLatestByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Delete all rows for the given (factSheetId, atomKey) — used by {@code purge()}.
     * Requires {@link Transactional} and {@link Modifying} because it issues a bulk DELETE.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM InferredFactRow f WHERE f.factSheetId = :factSheetId AND f.atomKey = :atomKey")
    void deleteByFactSheetIdAndAtomKey(@Param("factSheetId") Long factSheetId,
                                        @Param("atomKey") String atomKey);

    /**
     * Count of distinct atom keys stored for the given fact sheet.
     * Used to implement {@link ai.kompile.graph.reasoning.fol.InferredFactStore#size()}.
     */
    @Query("SELECT COUNT(DISTINCT f.atomKey) FROM InferredFactRow f WHERE f.factSheetId = :factSheetId")
    int countDistinctAtomKeysByFactSheetId(@Param("factSheetId") Long factSheetId);
}
