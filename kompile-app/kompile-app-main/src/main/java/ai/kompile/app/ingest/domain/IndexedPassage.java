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

/**
 * Entity representing a passage/chunk's cross-index status.
 * Tracks whether an individual passage has been indexed across the keyword index,
 * vector store, and knowledge graph.
 *
 * This provides fine-grained, chunk-level tracking complementing the
 * document-level tracking in IndexedDocument.
 */
@Entity
@Table(name = "indexed_passages", indexes = {
    @Index(name = "idx_passage_chunk_id", columnList = "chunkId", unique = true),
    @Index(name = "idx_passage_doc_id", columnList = "document_id"),
    @Index(name = "idx_passage_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_passage_vector_id", columnList = "vectorId"),
    @Index(name = "idx_passage_graph_node_id", columnList = "graphNodeId"),
    @Index(name = "idx_passage_keyword_status", columnList = "keywordIndexStatus"),
    @Index(name = "idx_passage_vector_status", columnList = "vectorStoreStatus"),
    @Index(name = "idx_passage_graph_status", columnList = "graphStatus"),
    @Index(name = "idx_passage_fact_sheet_vector", columnList = "factSheetId, vectorStoreStatus")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedPassage {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // ===================== IDENTIFICATION =====================

    /**
     * Reference to parent IndexedDocument.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private IndexedDocument document;

    /**
     * Unique chunk/passage ID (matches RetrievedDoc.getId()).
     * UUID format, globally unique.
     */
    @Column(nullable = false, length = 64, unique = true)
    private String chunkId;

    /**
     * Chunk index within the parent document (0-indexed).
     */
    @Column
    private Integer chunkIndex;

    /**
     * Fact sheet this passage belongs to (denormalized for query efficiency).
     */
    @Column(nullable = false)
    private Long factSheetId;

    /**
     * SHA-256 hash of the passage content for change detection.
     */
    @Column(length = 64)
    private String contentHash;

    /**
     * Preview of the passage content (first 500 chars).
     */
    @Column(length = 500)
    private String contentPreview;

    // ===================== KEYWORD INDEX STATUS =====================

    /**
     * Status of this passage in the keyword (Lucene) index.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexedDocument.IndexStatus keywordIndexStatus = IndexedDocument.IndexStatus.NOT_INDEXED;

    /**
     * Lucene internal document ID for direct access.
     */
    @Column
    private Integer keywordLuceneDocId;

    /**
     * When this passage was indexed in the keyword index.
     */
    @Column
    private Instant keywordIndexedAt;

    // ===================== VECTOR STORE STATUS =====================

    /**
     * Status of this passage in the vector store.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexedDocument.IndexStatus vectorStoreStatus = IndexedDocument.IndexStatus.NOT_INDEXED;

    /**
     * Vector store document ID.
     * Links to the vector store entry for this passage.
     */
    @Column(length = 64)
    private String vectorId;

    /**
     * When this passage was indexed in the vector store.
     */
    @Column
    private Instant vectorIndexedAt;

    // ===================== KNOWLEDGE GRAPH STATUS =====================

    /**
     * Status of this passage in the knowledge graph.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private IndexedDocument.IndexStatus graphStatus = IndexedDocument.IndexStatus.NOT_INDEXED;

    /**
     * GraphNode nodeId for this passage.
     * Links to the knowledge graph node representing this passage.
     */
    @Column(length = 36)
    private String graphNodeId;

    /**
     * When this passage was indexed in the knowledge graph.
     */
    @Column
    private Instant graphIndexedAt;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ===================== CONVENIENCE METHODS =====================

    /**
     * Check if this passage is fully indexed in all stores.
     */
    public boolean isFullyIndexed() {
        return keywordIndexStatus == IndexedDocument.IndexStatus.INDEXED
            && vectorStoreStatus == IndexedDocument.IndexStatus.INDEXED
            && graphStatus == IndexedDocument.IndexStatus.INDEXED;
    }

    /**
     * Check if this passage is indexed in the vector store.
     */
    public boolean isInVectorStore() {
        return vectorStoreStatus == IndexedDocument.IndexStatus.INDEXED && vectorId != null;
    }

    /**
     * Check if this passage is indexed in the keyword index.
     */
    public boolean isInKeywordIndex() {
        return keywordIndexStatus == IndexedDocument.IndexStatus.INDEXED;
    }

    /**
     * Check if this passage is indexed in the knowledge graph.
     */
    public boolean isInKnowledgeGraph() {
        return graphStatus == IndexedDocument.IndexStatus.INDEXED && graphNodeId != null;
    }

    /**
     * Check if this passage needs keyword indexing.
     */
    public boolean needsKeywordIndexing() {
        return keywordIndexStatus != IndexedDocument.IndexStatus.INDEXED;
    }

    /**
     * Check if this passage needs vector store indexing.
     */
    public boolean needsVectorIndexing() {
        return vectorStoreStatus != IndexedDocument.IndexStatus.INDEXED;
    }

    /**
     * Check if this passage needs graph indexing.
     */
    public boolean needsGraphIndexing() {
        return graphStatus != IndexedDocument.IndexStatus.INDEXED;
    }

    // ===================== STATUS UPDATE METHODS =====================

    /**
     * Mark this passage as indexed in the keyword index.
     */
    public void markKeywordIndexed(Integer luceneDocId) {
        this.keywordIndexStatus = IndexedDocument.IndexStatus.INDEXED;
        this.keywordLuceneDocId = luceneDocId;
        this.keywordIndexedAt = Instant.now();
    }

    /**
     * Mark this passage as indexed in the vector store.
     */
    public void markVectorIndexed(String vectorId) {
        this.vectorStoreStatus = IndexedDocument.IndexStatus.INDEXED;
        this.vectorId = vectorId;
        this.vectorIndexedAt = Instant.now();
    }

    /**
     * Mark this passage as indexed in the knowledge graph.
     */
    public void markGraphIndexed(String graphNodeId) {
        this.graphStatus = IndexedDocument.IndexStatus.INDEXED;
        this.graphNodeId = graphNodeId;
        this.graphIndexedAt = Instant.now();
    }

    /**
     * Mark this passage as failed in a specific index.
     */
    public void markFailed(IndexType indexType) {
        switch (indexType) {
            case KEYWORD:
                this.keywordIndexStatus = IndexedDocument.IndexStatus.FAILED;
                break;
            case VECTOR:
                this.vectorStoreStatus = IndexedDocument.IndexStatus.FAILED;
                break;
            case GRAPH:
                this.graphStatus = IndexedDocument.IndexStatus.FAILED;
                break;
        }
    }

    /**
     * Mark this passage as stale in all indexes.
     */
    public void markAsStale() {
        if (keywordIndexStatus == IndexedDocument.IndexStatus.INDEXED) {
            keywordIndexStatus = IndexedDocument.IndexStatus.STALE;
        }
        if (vectorStoreStatus == IndexedDocument.IndexStatus.INDEXED) {
            vectorStoreStatus = IndexedDocument.IndexStatus.STALE;
        }
        if (graphStatus == IndexedDocument.IndexStatus.INDEXED) {
            graphStatus = IndexedDocument.IndexStatus.STALE;
        }
    }

    // ===================== ENUMS =====================

    /**
     * Index type for specifying which index to update.
     */
    public enum IndexType {
        KEYWORD,
        VECTOR,
        GRAPH
    }

    // ===================== FACTORY METHOD =====================

    /**
     * Create a new tracked passage.
     */
    public static IndexedPassage create(IndexedDocument document, String chunkId,
                                         Integer chunkIndex, String contentHash,
                                         String contentPreview) {
        IndexedPassage passage = IndexedPassage.builder()
                .document(document)
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .factSheetId(document.getFactSheetId())
                .contentHash(contentHash)
                .contentPreview(truncatePreview(contentPreview))
                .keywordIndexStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .vectorStoreStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .graphStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        document.addPassage(passage);
        return passage;
    }

    /**
     * Truncate content preview to max 500 characters.
     */
    private static String truncatePreview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > 500 ? content.substring(0, 500) : content;
    }
}
