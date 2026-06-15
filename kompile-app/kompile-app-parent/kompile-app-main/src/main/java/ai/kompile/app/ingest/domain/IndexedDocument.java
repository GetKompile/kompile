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

package ai.kompile.app.ingest.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a document's cross-index status.
 * Tracks whether a document has been indexed across the keyword index,
 * vector store, and knowledge graph.
 *
 * This provides document-level tracking with per-index status and timestamps.
 */
@Entity
@Table(name = "indexed_documents", indexes = {
    @Index(name = "idx_indexed_doc_source_id", columnList = "sourceId"),
    @Index(name = "idx_indexed_doc_checksum", columnList = "checksum"),
    @Index(name = "idx_indexed_doc_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_indexed_doc_fact_id", columnList = "factId"),
    @Index(name = "idx_indexed_doc_status", columnList = "overallStatus"),
    @Index(name = "idx_indexed_doc_fact_sheet_status", columnList = "factSheetId, overallStatus")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // ===================== IDENTIFICATION =====================

    /**
     * Stable source identifier (maps to SOURCE_ID metadata).
     * Format: file:///path/to/file.pdf or https://example.com/doc
     */
    @Column(nullable = false, length = 2048)
    private String sourceId;

    /**
     * Original file name.
     */
    @Column(length = 512)
    private String fileName;

    /**
     * SHA-256 checksum of source document for change detection.
     */
    @Column(length = 64)
    private String checksum;

    /**
     * Reference to the Fact entity (if managed through fact sheets).
     */
    @Column
    private Long factId;

    /**
     * Fact sheet this document belongs to (required for scoping).
     */
    @Column(nullable = false)
    private Long factSheetId;

    // ===================== KEYWORD INDEX STATUS =====================

    /**
     * Status of this document in the keyword (Lucene) index.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexStatus keywordIndexStatus = IndexStatus.NOT_INDEXED;

    /**
     * When this document was indexed in the keyword index.
     */
    @Column
    private Instant keywordIndexedAt;

    /**
     * Path to the keyword index where this document is indexed.
     */
    @Column(length = 512)
    private String keywordIndexPath;

    /**
     * Number of chunks/passages indexed in keyword index.
     */
    @Column
    @Builder.Default
    private Integer keywordPassageCount = 0;

    // ===================== VECTOR STORE STATUS =====================

    /**
     * Status of this document in the vector store.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexStatus vectorStoreStatus = IndexStatus.NOT_INDEXED;

    /**
     * When this document was indexed in the vector store.
     */
    @Column
    private Instant vectorIndexedAt;

    /**
     * Path to the vector store where this document is indexed.
     */
    @Column(length = 512)
    private String vectorStorePath;

    /**
     * Number of passages indexed in vector store.
     */
    @Column
    @Builder.Default
    private Integer vectorPassageCount = 0;

    // ===================== KNOWLEDGE GRAPH STATUS =====================

    /**
     * Status of this document in the knowledge graph.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexStatus graphStatus = IndexStatus.NOT_INDEXED;

    /**
     * When this document was indexed in the knowledge graph.
     */
    @Column
    private Instant graphIndexedAt;

    /**
     * Number of nodes created in knowledge graph for this document.
     */
    @Column
    @Builder.Default
    private Integer graphNodeCount = 0;

    // ===================== OVERALL STATUS =====================

    /**
     * Computed overall status across all indexes.
     * Updated automatically via computeOverallStatus().
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OverallIndexStatus overallStatus = OverallIndexStatus.NOT_INDEXED;

    // ===================== TIMESTAMPS =====================

    /**
     * When this tracking record was created.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this tracking record was last modified.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Last time a sync check was performed for this document.
     */
    @Column
    private Instant lastSyncCheckAt;

    // ===================== RELATIONSHIPS =====================

    /**
     * Passages belonging to this document.
     */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<IndexedPassage> passages = new ArrayList<>();

    // ===================== ENUMS =====================

    /**
     * Status of a document in a specific index.
     */
    public enum IndexStatus {
        /** Not indexed in this store */
        NOT_INDEXED,
        /** Currently being indexed */
        INDEXING,
        /** Successfully indexed */
        INDEXED,
        /** Indexing failed */
        FAILED,
        /** Source has changed since indexing (needs re-index) */
        STALE
    }

    /**
     * Overall cross-index status.
     */
    public enum OverallIndexStatus {
        /** Not indexed in any store */
        NOT_INDEXED,
        /** Indexed in some but not all stores */
        PARTIAL,
        /** Successfully indexed in all three stores */
        FULLY_INDEXED,
        /** Indexes have different versions or inconsistent state */
        OUT_OF_SYNC,
        /** At least one index failed */
        FAILED
    }

    // ===================== LIFECYCLE HOOKS =====================

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        computeOverallStatus();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        computeOverallStatus();
    }

    // ===================== STATUS COMPUTATION =====================

    /**
     * Compute the overall status based on individual index statuses.
     */
    public void computeOverallStatus() {
        int indexed = 0;
        int failed = 0;
        int stale = 0;

        if (keywordIndexStatus == IndexStatus.INDEXED) indexed++;
        if (keywordIndexStatus == IndexStatus.FAILED) failed++;
        if (keywordIndexStatus == IndexStatus.STALE) stale++;

        if (vectorStoreStatus == IndexStatus.INDEXED) indexed++;
        if (vectorStoreStatus == IndexStatus.FAILED) failed++;
        if (vectorStoreStatus == IndexStatus.STALE) stale++;

        if (graphStatus == IndexStatus.INDEXED) indexed++;
        if (graphStatus == IndexStatus.FAILED) failed++;
        if (graphStatus == IndexStatus.STALE) stale++;

        if (failed > 0) {
            overallStatus = OverallIndexStatus.FAILED;
        } else if (stale > 0) {
            overallStatus = OverallIndexStatus.OUT_OF_SYNC;
        } else if (indexed == 3) {
            overallStatus = OverallIndexStatus.FULLY_INDEXED;
        } else if (indexed > 0) {
            overallStatus = OverallIndexStatus.PARTIAL;
        } else {
            overallStatus = OverallIndexStatus.NOT_INDEXED;
        }
    }

    // ===================== CONVENIENCE METHODS =====================

    /**
     * Check if this document is fully indexed in all stores.
     */
    public boolean isFullyIndexed() {
        return overallStatus == OverallIndexStatus.FULLY_INDEXED;
    }

    /**
     * Check if this document needs keyword indexing.
     */
    public boolean needsKeywordIndexing() {
        return keywordIndexStatus != IndexStatus.INDEXED;
    }

    /**
     * Check if this document needs vector store indexing.
     */
    public boolean needsVectorIndexing() {
        return vectorStoreStatus != IndexStatus.INDEXED;
    }

    /**
     * Check if this document needs graph indexing.
     */
    public boolean needsGraphIndexing() {
        return graphStatus != IndexStatus.INDEXED;
    }

    /**
     * Check if this document needs sync to any index.
     */
    public boolean needsSyncToAnyIndex() {
        return needsKeywordIndexing() || needsVectorIndexing() || needsGraphIndexing();
    }

    // ===================== STATUS UPDATE METHODS =====================

    /**
     * Update keyword index status.
     */
    public void updateKeywordStatus(IndexStatus status, String indexPath, int passageCount) {
        this.keywordIndexStatus = status;
        this.keywordIndexPath = indexPath;
        this.keywordPassageCount = passageCount;
        if (status == IndexStatus.INDEXED) {
            this.keywordIndexedAt = Instant.now();
        }
        computeOverallStatus();
    }

    /**
     * Update vector store status.
     */
    public void updateVectorStatus(IndexStatus status, String storePath, int passageCount) {
        this.vectorStoreStatus = status;
        this.vectorStorePath = storePath;
        this.vectorPassageCount = passageCount;
        if (status == IndexStatus.INDEXED) {
            this.vectorIndexedAt = Instant.now();
        }
        computeOverallStatus();
    }

    /**
     * Update knowledge graph status.
     */
    public void updateGraphStatus(IndexStatus status, int nodeCount) {
        this.graphStatus = status;
        this.graphNodeCount = nodeCount;
        if (status == IndexStatus.INDEXED) {
            this.graphIndexedAt = Instant.now();
        }
        computeOverallStatus();
    }

    /**
     * Mark the document as stale (source has changed).
     */
    public void markAsStale() {
        if (keywordIndexStatus == IndexStatus.INDEXED) {
            keywordIndexStatus = IndexStatus.STALE;
        }
        if (vectorStoreStatus == IndexStatus.INDEXED) {
            vectorStoreStatus = IndexStatus.STALE;
        }
        if (graphStatus == IndexStatus.INDEXED) {
            graphStatus = IndexStatus.STALE;
        }
        computeOverallStatus();
    }

    // ===================== PASSAGE MANAGEMENT =====================

    /**
     * Add a passage to this document.
     */
    public void addPassage(IndexedPassage passage) {
        passages.add(passage);
        passage.setDocument(this);
    }

    /**
     * Remove a passage from this document.
     */
    public void removePassage(IndexedPassage passage) {
        passages.remove(passage);
        passage.setDocument(null);
    }

    /**
     * Get the passage count.
     */
    public int getPassageCount() {
        return passages != null ? passages.size() : 0;
    }

    // ===================== FACTORY METHOD =====================

    /**
     * Create a new tracked document.
     */
    public static IndexedDocument create(String sourceId, String fileName,
                                          String checksum, Long factId, Long factSheetId) {
        return IndexedDocument.builder()
                .sourceId(sourceId)
                .fileName(fileName)
                .checksum(checksum)
                .factId(factId)
                .factSheetId(factSheetId)
                .keywordIndexStatus(IndexStatus.NOT_INDEXED)
                .vectorStoreStatus(IndexStatus.NOT_INDEXED)
                .graphStatus(IndexStatus.NOT_INDEXED)
                .overallStatus(OverallIndexStatus.NOT_INDEXED)
                .keywordPassageCount(0)
                .vectorPassageCount(0)
                .graphNodeCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
