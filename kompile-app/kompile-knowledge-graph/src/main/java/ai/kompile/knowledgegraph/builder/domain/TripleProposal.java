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
package ai.kompile.knowledgegraph.builder.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a proposed triple (subject-predicate-object) extracted from text.
 * Users can review, accept, or reject proposals before they become graph nodes/edges.
 */
@Entity
@Table(name = "triple_proposals", indexes = {
    @Index(name = "idx_tp_job", columnList = "extraction_job_id"),
    @Index(name = "idx_tp_status", columnList = "status"),
    @Index(name = "idx_tp_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_tp_confidence", columnList = "confidence"),
    @Index(name = "idx_tp_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripleProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references.
     */
    @Column(name = "proposal_id", nullable = false, unique = true, length = 36)
    private String proposalId;

    /**
     * The extraction job that created this proposal.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extraction_job_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ExtractionJob job;

    /**
     * The fact sheet this proposal belongs to.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    // ==================== Triple Components ====================

    /**
     * Name of the subject entity.
     */
    @Column(name = "subject_name", nullable = false, length = 512)
    private String subjectName;

    /**
     * Type of the subject entity (e.g., PERSON, ORGANIZATION).
     */
    @Column(name = "subject_type", length = 64)
    private String subjectType;

    /**
     * Description of the subject entity.
     */
    @Column(name = "subject_description", columnDefinition = "TEXT")
    private String subjectDescription;

    /**
     * The predicate/relationship connecting subject to object.
     */
    @Column(name = "predicate_name", nullable = false, length = 128)
    private String predicateName;

    /**
     * Name of the object entity.
     */
    @Column(name = "object_name", nullable = false, length = 512)
    private String objectName;

    /**
     * Type of the object entity.
     */
    @Column(name = "object_type", length = 64)
    private String objectType;

    /**
     * Description of the object entity.
     */
    @Column(name = "object_description", columnDefinition = "TEXT")
    private String objectDescription;

    // ==================== Source Tracking ====================

    /**
     * ID of the chunk where this triple was extracted.
     */
    @Column(name = "source_chunk_id", length = 255)
    private String sourceChunkId;

    /**
     * ID of the source document.
     */
    @Column(name = "source_document_id", length = 255)
    private String sourceDocumentId;

    /**
     * Text snippet providing context for the extraction.
     */
    @Column(name = "source_context", columnDefinition = "TEXT")
    private String sourceContext;

    // ==================== Confidence & Status ====================

    /**
     * Extraction confidence score (0.0 to 1.0).
     */
    @Column(nullable = false)
    @Builder.Default
    private Double confidence = 0.0;

    /**
     * Current proposal status.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProposalStatus status = ProposalStatus.PENDING;

    // ==================== Review Information ====================

    /**
     * When this proposal was reviewed (accepted or rejected).
     */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * Who reviewed this proposal (user identifier or "auto").
     */
    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    /**
     * Reason for rejection (if rejected).
     */
    @Column(name = "rejection_reason", length = 1024)
    private String rejectionReason;

    // ==================== Graph Node/Edge References ====================

    /**
     * ID of the created subject GraphNode (after acceptance).
     */
    @Column(name = "subject_node_id", length = 36)
    private String subjectNodeId;

    /**
     * ID of the created object GraphNode (after acceptance).
     */
    @Column(name = "object_node_id", length = 36)
    private String objectNodeId;

    /**
     * ID of the created GraphEdge (after acceptance).
     */
    @Column(name = "edge_id", length = 36)
    private String edgeId;

    // ==================== Metadata ====================

    /**
     * JSON-serialized additional metadata.
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * When this proposal was created.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Proposal status enumeration.
     */
    public enum ProposalStatus {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (proposalId == null) {
            proposalId = UUID.randomUUID().toString();
        }
    }

    /**
     * Accept this proposal.
     */
    public void accept(String reviewedBy) {
        this.status = ProposalStatus.ACCEPTED;
        this.reviewedAt = LocalDateTime.now();
        this.reviewedBy = reviewedBy;
    }

    /**
     * Reject this proposal.
     */
    public void reject(String reviewedBy, String reason) {
        this.status = ProposalStatus.REJECTED;
        this.reviewedAt = LocalDateTime.now();
        this.reviewedBy = reviewedBy;
        this.rejectionReason = reason;
    }

    /**
     * Returns a human-readable representation of this triple.
     */
    public String toDisplayString() {
        return String.format("(%s:%s) -[%s]-> (%s:%s) [%.2f]",
                subjectName, subjectType != null ? subjectType : "?",
                predicateName,
                objectName, objectType != null ? objectType : "?",
                confidence != null ? confidence : 0.0);
    }

    /**
     * Check if this proposal is pending review.
     */
    public boolean isPending() {
        return status == ProposalStatus.PENDING;
    }

    /**
     * Check if this proposal has been accepted.
     */
    public boolean isAccepted() {
        return status == ProposalStatus.ACCEPTED;
    }

    /**
     * Check if this proposal has been rejected.
     */
    public boolean isRejected() {
        return status == ProposalStatus.REJECTED;
    }
}
