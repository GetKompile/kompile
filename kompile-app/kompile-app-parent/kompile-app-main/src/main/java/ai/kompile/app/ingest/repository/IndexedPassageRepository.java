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
import ai.kompile.app.ingest.domain.IndexedPassage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for IndexedPassage entities.
 * Provides queries for passage-level cross-index tracking and management.
 */
@Repository
public interface IndexedPassageRepository extends JpaRepository<IndexedPassage, Long> {

    // ═══════════════════════════════════════════════════════════════════════════
    // BASIC LOOKUPS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find a passage by its chunk ID.
     */
    Optional<IndexedPassage> findByChunkId(String chunkId);

    /**
     * Find all passages for a document.
     */
    List<IndexedPassage> findByDocument(IndexedDocument document);

    /**
     * Find all passages for a document, ordered by chunk index.
     */
    List<IndexedPassage> findByDocumentIdOrderByChunkIndex(Long documentId);

    /**
     * Find all passages for a document with pagination.
     */
    Page<IndexedPassage> findByDocumentId(Long documentId, Pageable pageable);

    /**
     * Find all passages for a fact sheet.
     */
    List<IndexedPassage> findByFactSheetId(Long factSheetId);

    /**
     * Find all passages for a fact sheet with pagination.
     */
    Page<IndexedPassage> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Find a passage by its vector ID.
     */
    Optional<IndexedPassage> findByVectorId(String vectorId);

    /**
     * Find a passage by its graph node ID.
     */
    Optional<IndexedPassage> findByGraphNodeId(String graphNodeId);

    /**
     * Check if a passage exists by chunk ID.
     */
    boolean existsByChunkId(String chunkId);

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS-BASED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find passages needing vector indexing.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.vectorStoreStatus != 'INDEXED'")
    List<IndexedPassage> findNeedingVectorIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find passages needing vector indexing with pagination.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.vectorStoreStatus != 'INDEXED'")
    Page<IndexedPassage> findNeedingVectorIndexing(@Param("factSheetId") Long factSheetId, Pageable pageable);

    /**
     * Find passages needing keyword indexing.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.keywordIndexStatus != 'INDEXED'")
    List<IndexedPassage> findNeedingKeywordIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find passages needing graph indexing.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.graphStatus != 'INDEXED'")
    List<IndexedPassage> findNeedingGraphIndexing(@Param("factSheetId") Long factSheetId);

    /**
     * Find passages in keyword index but not in vector store.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.keywordIndexStatus = 'INDEXED' " +
           "AND p.vectorStoreStatus != 'INDEXED'")
    List<IndexedPassage> findInKeywordNotInVector(@Param("factSheetId") Long factSheetId);

    /**
     * Find passages in vector store but not in keyword index.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.vectorStoreStatus = 'INDEXED' " +
           "AND p.keywordIndexStatus != 'INDEXED'")
    List<IndexedPassage> findInVectorNotInKeyword(@Param("factSheetId") Long factSheetId);

    /**
     * Find passages in vector store but not in knowledge graph.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.vectorStoreStatus = 'INDEXED' " +
           "AND p.graphStatus != 'INDEXED'")
    List<IndexedPassage> findInVectorNotInGraph(@Param("factSheetId") Long factSheetId);

    /**
     * Find fully indexed passages.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.keywordIndexStatus = 'INDEXED' " +
           "AND p.vectorStoreStatus = 'INDEXED' " +
           "AND p.graphStatus = 'INDEXED'")
    List<IndexedPassage> findFullyIndexed(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // CROSS-INDEX RESOLUTION BY CHUNK ID
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find passages by a collection of chunk IDs.
     */
    @Query("SELECT p FROM IndexedPassage p WHERE p.chunkId IN :chunkIds")
    List<IndexedPassage> findByChunkIds(@Param("chunkIds") Collection<String> chunkIds);

    /**
     * Get chunk IDs that are indexed in the vector store.
     */
    @Query("SELECT p.chunkId FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.vectorStoreStatus = 'INDEXED'")
    Set<String> findVectorIndexedChunkIds(@Param("factSheetId") Long factSheetId);

    /**
     * Get chunk IDs that are indexed in the keyword index.
     */
    @Query("SELECT p.chunkId FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.keywordIndexStatus = 'INDEXED'")
    Set<String> findKeywordIndexedChunkIds(@Param("factSheetId") Long factSheetId);

    /**
     * Get chunk IDs that are indexed in the knowledge graph.
     */
    @Query("SELECT p.chunkId FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "AND p.graphStatus = 'INDEXED'")
    Set<String> findGraphIndexedChunkIds(@Param("factSheetId") Long factSheetId);

    /**
     * Check which chunk IDs from a list are NOT in the vector store.
     */
    @Query("SELECT p.chunkId FROM IndexedPassage p WHERE p.chunkId IN :chunkIds " +
           "AND p.vectorStoreStatus != 'INDEXED'")
    Set<String> findChunkIdsNotInVectorStore(@Param("chunkIds") Collection<String> chunkIds);

    /**
     * Check which chunk IDs from a list are NOT in the knowledge graph.
     */
    @Query("SELECT p.chunkId FROM IndexedPassage p WHERE p.chunkId IN :chunkIds " +
           "AND p.graphStatus != 'INDEXED'")
    Set<String> findChunkIdsNotInGraph(@Param("chunkIds") Collection<String> chunkIds);

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Count passages by fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count passages by document.
     */
    long countByDocumentId(Long documentId);

    /**
     * Count passages by vector store status.
     */
    long countByFactSheetIdAndVectorStoreStatus(Long factSheetId, IndexStatus status);

    /**
     * Count passages by keyword index status.
     */
    long countByFactSheetIdAndKeywordIndexStatus(Long factSheetId, IndexStatus status);

    /**
     * Count passages by graph status.
     */
    long countByFactSheetIdAndGraphStatus(Long factSheetId, IndexStatus status);

    /**
     * Get status combination counts.
     */
    @Query("SELECT p.keywordIndexStatus, p.vectorStoreStatus, p.graphStatus, COUNT(p) " +
           "FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "GROUP BY p.keywordIndexStatus, p.vectorStoreStatus, p.graphStatus")
    List<Object[]> getStatusCombinationCounts(@Param("factSheetId") Long factSheetId);

    /**
     * Get passage counts by document for a fact sheet.
     */
    @Query("SELECT p.document.id, p.document.fileName, COUNT(p) " +
           "FROM IndexedPassage p WHERE p.factSheetId = :factSheetId " +
           "GROUP BY p.document.id, p.document.fileName")
    List<Object[]> getPassageCountsByDocument(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH UPDATES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mark a passage as indexed in the vector store.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET p.vectorStoreStatus = 'INDEXED', " +
           "p.vectorId = :vectorId, p.vectorIndexedAt = :timestamp, p.updatedAt = :timestamp " +
           "WHERE p.chunkId = :chunkId")
    int markVectorIndexed(
            @Param("chunkId") String chunkId,
            @Param("vectorId") String vectorId,
            @Param("timestamp") Instant timestamp);

    /**
     * Mark a passage as indexed in the keyword index.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET p.keywordIndexStatus = 'INDEXED', " +
           "p.keywordLuceneDocId = :luceneDocId, p.keywordIndexedAt = :timestamp, p.updatedAt = :timestamp " +
           "WHERE p.chunkId = :chunkId")
    int markKeywordIndexed(
            @Param("chunkId") String chunkId,
            @Param("luceneDocId") Integer luceneDocId,
            @Param("timestamp") Instant timestamp);

    /**
     * Mark a passage as indexed in the knowledge graph.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET p.graphStatus = 'INDEXED', " +
           "p.graphNodeId = :nodeId, p.graphIndexedAt = :timestamp, p.updatedAt = :timestamp " +
           "WHERE p.chunkId = :chunkId")
    int markGraphIndexed(
            @Param("chunkId") String chunkId,
            @Param("nodeId") String nodeId,
            @Param("timestamp") Instant timestamp);

    /**
     * Batch update vector store status for multiple passages.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET p.vectorStoreStatus = :status, " +
           "p.vectorIndexedAt = :timestamp, p.updatedAt = :timestamp " +
           "WHERE p.chunkId IN :chunkIds")
    int updateVectorStatusBatch(
            @Param("chunkIds") Collection<String> chunkIds,
            @Param("status") IndexStatus status,
            @Param("timestamp") Instant timestamp);

    /**
     * Batch update graph status for multiple passages.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET p.graphStatus = :status, " +
           "p.graphIndexedAt = :timestamp, p.updatedAt = :timestamp " +
           "WHERE p.chunkId IN :chunkIds")
    int updateGraphStatusBatch(
            @Param("chunkIds") Collection<String> chunkIds,
            @Param("status") IndexStatus status,
            @Param("timestamp") Instant timestamp);

    /**
     * Mark passages as stale.
     */
    @Modifying
    @Transactional
    @Query("UPDATE IndexedPassage p SET " +
           "p.keywordIndexStatus = CASE WHEN p.keywordIndexStatus = 'INDEXED' THEN 'STALE' ELSE p.keywordIndexStatus END, " +
           "p.vectorStoreStatus = CASE WHEN p.vectorStoreStatus = 'INDEXED' THEN 'STALE' ELSE p.vectorStoreStatus END, " +
           "p.graphStatus = CASE WHEN p.graphStatus = 'INDEXED' THEN 'STALE' ELSE p.graphStatus END, " +
           "p.updatedAt = :timestamp " +
           "WHERE p.document.id = :documentId")
    int markPassagesAsStaleByDocument(@Param("documentId") Long documentId, @Param("timestamp") Instant timestamp);

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete all passages for a fact sheet.
     */
    @Modifying
    @Transactional
    int deleteByFactSheetId(Long factSheetId);

    /**
     * Delete all passages for a document.
     */
    @Modifying
    @Transactional
    int deleteByDocumentId(Long documentId);

    /**
     * Delete passages by chunk IDs.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM IndexedPassage p WHERE p.chunkId IN :chunkIds")
    int deleteByChunkIds(@Param("chunkIds") Collection<String> chunkIds);
}
