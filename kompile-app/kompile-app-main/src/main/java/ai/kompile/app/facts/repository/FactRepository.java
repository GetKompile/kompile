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

import ai.kompile.app.facts.domain.Fact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Fact entities.
 */
@Repository
public interface FactRepository extends JpaRepository<Fact, Long> {

    /**
     * Find all facts in a fact sheet.
     */
    List<Fact> findByFactSheetIdOrderByCreatedAtDesc(Long factSheetId);

    /**
     * Find facts in a sheet with pagination.
     */
    Page<Fact> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Find a fact by file name within a sheet.
     */
    Optional<Fact> findByFactSheetIdAndFileName(Long factSheetId, String fileName);

    /**
     * Find a fact by checksum within a sheet.
     */
    Optional<Fact> findByFactSheetIdAndChecksum(Long factSheetId, String checksum);

    /**
     * Check if a fact with the given checksum exists in a sheet.
     */
    boolean existsByFactSheetIdAndChecksum(Long factSheetId, String checksum);

    /**
     * Check if a fact with the given file name exists in a sheet.
     */
    boolean existsByFactSheetIdAndFileName(Long factSheetId, String fileName);

    /**
     * Find facts by source type within a sheet.
     */
    List<Fact> findByFactSheetIdAndSourceType(Long factSheetId, Fact.SourceType sourceType);

    /**
     * Search facts by file name pattern within a sheet.
     */
    @Query("SELECT f FROM Fact f WHERE f.factSheet.id = :sheetId AND LOWER(f.fileName) LIKE LOWER(CONCAT('%', :pattern, '%'))")
    List<Fact> searchByFileName(@Param("sheetId") Long sheetId, @Param("pattern") String pattern);

    /**
     * Search facts by tags within a sheet.
     */
    @Query("SELECT f FROM Fact f WHERE f.factSheet.id = :sheetId AND LOWER(f.tags) LIKE LOWER(CONCAT('%', :tag, '%'))")
    List<Fact> searchByTag(@Param("sheetId") Long sheetId, @Param("tag") String tag);

    /**
     * Count facts in a sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Delete all facts in a sheet.
     */
    @Modifying
    @Transactional
    void deleteByFactSheetId(Long factSheetId);

    /**
     * Find all facts across all sheets by checksum.
     */
    List<Fact> findByChecksum(String checksum);

    /**
     * Get total size of facts in a sheet.
     */
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM Fact f WHERE f.factSheet.id = :sheetId")
    long getTotalSizeBySheetId(@Param("sheetId") Long sheetId);

    /**
     * Find facts by extension within a sheet.
     */
    List<Fact> findByFactSheetIdAndExtension(Long factSheetId, String extension);

    /**
     * Find facts with notes within a sheet.
     */
    @Query("SELECT f FROM Fact f WHERE f.factSheet.id = :sheetId AND f.notes IS NOT NULL AND f.notes != ''")
    List<Fact> findWithNotes(@Param("sheetId") Long sheetId);

    /**
     * Find all unindexed facts in a sheet.
     */
    @Query("SELECT f FROM Fact f WHERE f.factSheet.id = :sheetId AND (f.indexed = false OR f.indexed IS NULL) ORDER BY f.createdAt ASC")
    List<Fact> findUnindexedByFactSheetId(@Param("sheetId") Long sheetId);

    /**
     * Find unindexed facts in a sheet with pagination.
     */
    @Query("SELECT f FROM Fact f WHERE f.factSheet.id = :sheetId AND (f.indexed = false OR f.indexed IS NULL)")
    Page<Fact> findUnindexedByFactSheetId(@Param("sheetId") Long sheetId, Pageable pageable);

    /**
     * Count unindexed facts in a sheet.
     */
    @Query("SELECT COUNT(f) FROM Fact f WHERE f.factSheet.id = :sheetId AND (f.indexed = false OR f.indexed IS NULL)")
    long countUnindexedByFactSheetId(@Param("sheetId") Long sheetId);

    /**
     * Count indexed facts in a sheet.
     */
    @Query("SELECT COUNT(f) FROM Fact f WHERE f.factSheet.id = :sheetId AND f.indexed = true")
    long countIndexedByFactSheetId(@Param("sheetId") Long sheetId);

    /**
     * Find all unindexed facts across all sheets.
     */
    @Query("SELECT f FROM Fact f WHERE f.indexed = false OR f.indexed IS NULL ORDER BY f.createdAt ASC")
    List<Fact> findAllUnindexed();

    /**
     * Find indexed facts by IDs.
     */
    @Query("SELECT f FROM Fact f WHERE f.id IN :ids AND f.indexed = true")
    List<Fact> findIndexedByIds(@Param("ids") Iterable<Long> ids);

    /**
     * Find unindexed facts by IDs.
     */
    @Query("SELECT f FROM Fact f WHERE f.id IN :ids AND (f.indexed = false OR f.indexed IS NULL)")
    List<Fact> findUnindexedByIds(@Param("ids") Iterable<Long> ids);

    /**
     * Mark facts as indexed by their IDs.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Fact f SET f.indexed = true, f.indexedAt = :now WHERE f.id IN :ids")
    int markAsIndexed(@Param("ids") Iterable<Long> ids, @Param("now") java.time.Instant now);

    /**
     * Mark a single fact as indexed.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Fact f SET f.indexed = true, f.indexedAt = :now WHERE f.id = :id")
    int markAsIndexed(@Param("id") Long id, @Param("now") java.time.Instant now);
}
