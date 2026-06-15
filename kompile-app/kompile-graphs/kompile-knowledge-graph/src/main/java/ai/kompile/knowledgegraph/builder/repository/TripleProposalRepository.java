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
package ai.kompile.knowledgegraph.builder.repository;

import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal.ProposalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for TripleProposal entities.
 */
@Repository
public interface TripleProposalRepository extends JpaRepository<TripleProposal, Long> {

    /**
     * Find proposal by its external UUID.
     */
    Optional<TripleProposal> findByProposalId(String proposalId);

    /**
     * Find all proposals for a job.
     */
    List<TripleProposal> findByJob(ExtractionJob job);

    /**
     * Find all proposals for a job (paginated).
     */
    Page<TripleProposal> findByJob(ExtractionJob job, Pageable pageable);

    /**
     * Find all proposals for a job ID.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.job.jobId = :jobId")
    List<TripleProposal> findByJobId(@Param("jobId") String jobId);

    /**
     * Find all proposals for a job ID (paginated).
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.job.jobId = :jobId")
    Page<TripleProposal> findByJobId(@Param("jobId") String jobId, Pageable pageable);

    /**
     * Find all proposals for a fact sheet.
     */
    List<TripleProposal> findByFactSheetId(Long factSheetId);

    /**
     * Find all proposals for a fact sheet (paginated).
     */
    Page<TripleProposal> findByFactSheetId(Long factSheetId, Pageable pageable);

    /**
     * Find proposals by status.
     */
    List<TripleProposal> findByStatus(ProposalStatus status);

    /**
     * Find proposals by status (paginated).
     */
    Page<TripleProposal> findByStatus(ProposalStatus status, Pageable pageable);

    /**
     * Find proposals by fact sheet and status.
     */
    List<TripleProposal> findByFactSheetIdAndStatus(Long factSheetId, ProposalStatus status);

    /**
     * Find proposals by fact sheet and status (paginated).
     */
    Page<TripleProposal> findByFactSheetIdAndStatus(Long factSheetId, ProposalStatus status, Pageable pageable);

    /**
     * Find proposals by job and status.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.job.jobId = :jobId AND p.status = :status")
    List<TripleProposal> findByJobIdAndStatus(@Param("jobId") String jobId, @Param("status") ProposalStatus status);

    /**
     * Find proposals by job and status (paginated).
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.job.jobId = :jobId AND p.status = :status")
    Page<TripleProposal> findByJobIdAndStatus(
        @Param("jobId") String jobId,
        @Param("status") ProposalStatus status,
        Pageable pageable
    );

    /**
     * Find proposals above a confidence threshold.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.confidence >= :threshold AND p.status = 'PENDING' " +
           "ORDER BY p.confidence DESC")
    List<TripleProposal> findHighConfidencePending(@Param("threshold") Double threshold);

    /**
     * Find proposals above a confidence threshold for a fact sheet.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.factSheetId = :factSheetId AND p.confidence >= :threshold " +
           "AND p.status = 'PENDING' ORDER BY p.confidence DESC")
    List<TripleProposal> findHighConfidencePendingByFactSheet(
        @Param("factSheetId") Long factSheetId,
        @Param("threshold") Double threshold
    );

    /**
     * Find proposals by source chunk.
     */
    List<TripleProposal> findBySourceChunkId(String sourceChunkId);

    /**
     * Find proposals by source document.
     */
    List<TripleProposal> findBySourceDocumentId(String sourceDocumentId);

    /**
     * Search proposals by subject or object name.
     */
    @Query("SELECT p FROM TripleProposal p WHERE " +
           "LOWER(p.subjectName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(p.objectName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(p.predicateName) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<TripleProposal> searchByTripleComponents(@Param("query") String query, Pageable pageable);

    /**
     * Search proposals within a fact sheet.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.factSheetId = :factSheetId AND (" +
           "LOWER(p.subjectName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(p.objectName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(p.predicateName) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<TripleProposal> searchByFactSheetAndQuery(
        @Param("factSheetId") Long factSheetId,
        @Param("query") String query,
        Pageable pageable
    );

    /**
     * Count proposals by status.
     */
    long countByStatus(ProposalStatus status);

    /**
     * Count proposals by fact sheet.
     */
    long countByFactSheetId(Long factSheetId);

    /**
     * Count proposals by fact sheet and status.
     */
    long countByFactSheetIdAndStatus(Long factSheetId, ProposalStatus status);

    /**
     * Count proposals by job.
     */
    @Query("SELECT COUNT(p) FROM TripleProposal p WHERE p.job.jobId = :jobId")
    long countByJobId(@Param("jobId") String jobId);

    /**
     * Count proposals by job and status.
     */
    @Query("SELECT COUNT(p) FROM TripleProposal p WHERE p.job.jobId = :jobId AND p.status = :status")
    long countByJobIdAndStatus(@Param("jobId") String jobId, @Param("status") ProposalStatus status);

    /**
     * Bulk accept proposals.
     */
    @Modifying
    @Query("UPDATE TripleProposal p SET p.status = 'ACCEPTED', p.reviewedAt = :reviewedAt, " +
           "p.reviewedBy = :reviewedBy WHERE p.proposalId IN :proposalIds")
    int bulkAccept(
        @Param("proposalIds") List<String> proposalIds,
        @Param("reviewedAt") LocalDateTime reviewedAt,
        @Param("reviewedBy") String reviewedBy
    );

    /**
     * Bulk reject proposals.
     */
    @Modifying
    @Query("UPDATE TripleProposal p SET p.status = 'REJECTED', p.reviewedAt = :reviewedAt, " +
           "p.reviewedBy = :reviewedBy, p.rejectionReason = :reason WHERE p.proposalId IN :proposalIds")
    int bulkReject(
        @Param("proposalIds") List<String> proposalIds,
        @Param("reviewedAt") LocalDateTime reviewedAt,
        @Param("reviewedBy") String reviewedBy,
        @Param("reason") String reason
    );

    /**
     * Auto-accept high-confidence proposals.
     */
    @Modifying
    @Query("UPDATE TripleProposal p SET p.status = 'ACCEPTED', p.reviewedAt = :reviewedAt, " +
           "p.reviewedBy = 'auto' WHERE p.job.jobId = :jobId AND p.confidence >= :threshold AND p.status = 'PENDING'")
    int autoAcceptHighConfidence(
        @Param("jobId") String jobId,
        @Param("threshold") Double threshold,
        @Param("reviewedAt") LocalDateTime reviewedAt
    );

    /**
     * Delete all proposals for a job.
     */
    @Modifying
    @Query("DELETE FROM TripleProposal p WHERE p.job.jobId = :jobId")
    int deleteByJobId(@Param("jobId") String jobId);

    /**
     * Delete all proposals for a fact sheet.
     */
    @Modifying
    @Query("DELETE FROM TripleProposal p WHERE p.factSheetId = :factSheetId")
    int deleteByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Find proposals with multiple proposal IDs.
     */
    @Query("SELECT p FROM TripleProposal p WHERE p.proposalId IN :proposalIds")
    List<TripleProposal> findByProposalIds(@Param("proposalIds") List<String> proposalIds);
}
