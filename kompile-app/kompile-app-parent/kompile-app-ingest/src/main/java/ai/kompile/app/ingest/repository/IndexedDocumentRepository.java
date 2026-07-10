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

package ai.kompile.app.ingest.repository;

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.IndexStatus;
import ai.kompile.app.ingest.domain.IndexedDocument.OverallIndexStatus;
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
 * Repository for IndexedDocument entities.
 * Provides queries for cross-index tracking and management.
 */
@Repository
public interface IndexedDocumentRepository extends JpaRepository<IndexedDocument, Long> {

    // ═══════════════════════════════════════════════════════════════════════════
    // BASIC LOOKUPS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find a document by its source ID and fact sheet.
     */
    Optional<IndexedDocument> findBySourceIdAndFactSheetId(String sourceId, Long factSheetId);

    /**
     * Find a document by its checksum and fact sheet.
     */
    Optional<IndexedDocument> findByChecksumAndFactSheetId(String checksum, Long factSheetId);

    /**
     * Find a document by its fact ID and fact sheet.
     */
    Optional<IndexedDocument> findByFactIdAndFactSheetId(Long factId, Long factSheetId);

    /**
     * Find all documents for a fact sheet.
     */
    List<IndexedDocument> findByFactSheetId(Long factSheetId);

    /**
     * Find all documents for a fact sheet with pagination.
     */
    Page<IndexedDocument> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Check if a document exists for a source ID and fact sheet.
     */
    boolean existsBySourceIdAndFactSheetId(String sourceId, Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS-BASED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find documents by overall status for a fact sheet.
     */
    List<IndexedDocument> findByFactSheetIdAndOverallStatus(Long factSheetId, OverallIndexStatus status);

    /**
     * Find documents by overall status with pagination.
     */
    Page<IndexedDocument> findByFactSheetIdAndOverallStatus(
            Long factSheetId, OverallIndexStatus status, Pageable pageable);

    /**
     * Find documents needing keyword indexing.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.keywordIndexStatus != 'INDEXED'")
    List<IndexedDocument> findNeedingKeywordIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents needing vector indexing.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.vectorStoreStatus != 'INDEXED'")
    List<IndexedDocument> findNeedingVectorIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents needing graph indexing.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.graphStatus != 'INDEXED'")
    List<IndexedDocument> findNeedingGraphIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents needing any indexing.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND (d.keywordIndexStatus != 'INDEXED' " +
           "OR d.vectorStoreStatus != 'INDEXED' " +
           "OR d.graphStatus != 'INDEXED')")
    List<IndexedDocument> findNeedingAnyIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents needing any indexing with pagination.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND (d.keywordIndexStatus != 'INDEXED' " +
           "OR d.vectorStoreStatus != 'INDEXED' " +
           "OR d.graphStatus != 'INDEXED')")
    Page<IndexedDocument> findNeedingAnyIndexing(@Param("factSheetId") Long factSheetId, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-INDEX RESOLUTION QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find documents that are in keyword index but not in vector store.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.keywordIndexStatus = 'INDEXED' " +
           "AND d.vectorStoreStatus != 'INDEXED'")
    List<IndexedDocument> findInKeywordNotInVector(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents that are in vector store but not in keyword index.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.vectorStoreStatus = 'INDEXED' " +
           "AND d.keywordIndexStatus != 'INDEXED'")
    List<IndexedDocument> findInVectorNotInKeyword(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents that are indexed but not in knowledge graph.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND (d.keywordIndexStatus = 'INDEXED' OR d.vectorStoreStatus = 'INDEXED') " +
           "AND d.graphStatus != 'INDEXED'")
    List<IndexedDocument> findIndexedNotInGraph(@Param("factSheetId") Long factSheetId);

    /**
     * Find documents in vector store but not in graph.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.vectorStoreStatus = 'INDEXED' " +
           "AND d.graphStatus != 'INDEXED'")
    List<IndexedDocument> findInVectorNotInGraph(@Param("factSheetId") Long factSheetId);

    /**
     * Find fully indexed documents.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.overallStatus = 'FULLY_INDEXED'")
    List<IndexedDocument> findFullyIndexed(@Param("factSheetId") Long factSheetId);

    /**
     * Find stale documents (source has changed since indexing).
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND (d.keywordIndexStatus = 'STALE' " +
           "OR d.vectorStoreStatus = 'STALE' " +
           "OR d.graphStatus = 'STALE')")
    List<IndexedDocument> findStaleDocuments(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search documents by filename pattern.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND LOWER(d.fileName) LIKE LOWER(CONCAT('%', :pattern, '%'))")
    Page<IndexedDocument> searchByFileName(
            @Param("factSheetId") Long factSheetId,
            @Param("pattern") String pattern,
            Pageable pageable);

    /**
     * Search documents by filename pattern and status.
     */
    @Query("SELECT d FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "AND d.overallStatus = :status " +
           "AND LOWER(d.fileName) LIKE LOWER(CONCAT('%', :pattern, '%'))")
    Page<IndexedDocument> searchByFileNameAndStatus(
            @Param("factSheetId") Long factSheetId,
            @Param("pattern") String pattern,
            @Param("status") OverallIndexStatus status,
            Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Count documents by fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count documents by overall status for a fact sheet.
     */
    long countByFactSheetIdAndOverallStatus(Long factSheetId, OverallIndexStatus status);

    /**
     * Count documents by keyword index status.
     */
    long countByFactSheetIdAndKeywordIndexStatus(Long factSheetId, IndexStatus status);

    /**
     * Count documents by vector store status.
     */
    long countByFactSheetIdAndVectorStoreStatus(Long factSheetId, IndexStatus status);

    /**
     * Count documents by graph status.
     */
    long countByFactSheetIdAndGraphStatus(Long factSheetId, IndexStatus status);

    /**
     * Get status counts grouped by overall status.
     */
    @Query("SELECT d.overallStatus, COUNT(d) FROM IndexedDocument d " +
           "WHERE d.factSheetId = :factSheetId GROUP BY d.overallStatus")
    List<Object[]> getStatusCountsByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Get passage counts by index type.
     */
    @Query("SELECT SUM(d.keywordPassageCount), SUM(d.vectorPassageCount), SUM(d.graphNodeCount) " +
           "FROM IndexedDocument d WHERE d.factSheetId = :factSheetId")
    List<Object[]> getPassageCountsByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Get cross-index distribution.
     * Returns counts for each combination of index presence.
     */
    @Query("SELECT d.keywordIndexStatus, d.vectorStoreStatus, d.graphStatus, COUNT(d) " +
           "FROM IndexedDocument d WHERE d.factSheetId = :factSheetId " +
           "GROUP BY d.keywordIndexStatus, d.vectorStoreStatus, d.graphStatus")
    List<Object[]> getCrossIndexDistribution(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH UPDATES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update keyword index status for multiple documents.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedDocument d SET d.keywordIndexStatus = :status, " +
           "d.keywordIndexedAt = :timestamp, d.updatedAt = :timestamp WHERE d.id IN :ids")
    int updateKeywordStatus(
            @Param("ids") List<Long> ids,
            @Param("status") IndexStatus status,
            @Param("timestamp") Instant timestamp);

    /**
     * Update vector store status for multiple documents.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedDocument d SET d.vectorStoreStatus = :status, " +
           "d.vectorIndexedAt = :timestamp, d.updatedAt = :timestamp WHERE d.id IN :ids")
    int updateVectorStatus(
            @Param("ids") List<Long> ids,
            @Param("status") IndexStatus status,
            @Param("timestamp") Instant timestamp);

    /**
     * Update graph status for multiple documents.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedDocument d SET d.graphStatus = :status, " +
           "d.graphIndexedAt = :timestamp, d.updatedAt = :timestamp WHERE d.id IN :ids")
    int updateGraphStatus(
            @Param("ids") List<Long> ids,
            @Param("status") IndexStatus status,
            @Param("timestamp") Instant timestamp);

    /**
     * Mark documents as stale by checksum mismatch.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedDocument d SET " +
           "d.keywordIndexStatus = CASE WHEN d.keywordIndexStatus = 'INDEXED' THEN 'STALE' ELSE d.keywordIndexStatus END, " +
           "d.vectorStoreStatus = CASE WHEN d.vectorStoreStatus = 'INDEXED' THEN 'STALE' ELSE d.vectorStoreStatus END, " +
           "d.graphStatus = CASE WHEN d.graphStatus = 'INDEXED' THEN 'STALE' ELSE d.graphStatus END, " +
           "d.overallStatus = 'OUT_OF_SYNC', " +
           "d.updatedAt = :timestamp " +
           "WHERE d.id IN :ids")
    int markAsStale(@Param("ids") List<Long> ids, @Param("timestamp") Instant timestamp);

    /**
     * Update last sync check time.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedDocument d SET d.lastSyncCheckAt = :timestamp WHERE d.id IN :ids")
    int updateLastSyncCheckAt(@Param("ids") List<Long> ids, @Param("timestamp") Instant timestamp);

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete all documents for a fact sheet.
     */
    @Modifying
    @Transactional
    int deleteByFactSheetId(Long factSheetId);

    /**
     * Delete documents older than a specified time.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM IndexedDocument d WHERE d.factSheetId = :factSheetId AND d.createdAt < :cutoff")
    int deleteOlderThan(@Param("factSheetId") Long factSheetId, @Param("cutoff") Instant cutoff);
}
