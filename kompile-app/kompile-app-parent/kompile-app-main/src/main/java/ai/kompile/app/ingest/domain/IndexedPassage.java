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
    @Index(name = "idx_passage_fact_sheet_vector", columnList = "factSheetId, vectorStoreStatus"),
    @Index(name = "idx_passage_semantic_type", columnList = "semanticType"),
    @Index(name = "idx_passage_source_type", columnList = "sourceType"),
    @Index(name = "idx_passage_fact_sheet_semantic", columnList = "factSheetId, semanticType")
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

    // ===================== CONTENT TYPE & TABLE METADATA =====================

    /**
     * Content type of this passage (text, table, code, image).
     */
    @Column(length = 32)
    @Builder.Default
    private String contentType = "text";

    /**
     * Full content of the passage (for tables, this is the markdown table).
     * Stored as CLOB for large content.
     */
    @Column(columnDefinition = "CLOB")
    private String fullContent;

    /**
     * For tables: number of rows (excluding header).
     */
    @Column
    private Integer tableRowCount;

    /**
     * For tables: number of columns.
     */
    @Column
    private Integer tableColumnCount;

    /**
     * For tables: comma-separated list of column headers.
     */
    @Column(length = 1000)
    private String tableHeaders;

    /**
     * For tables: the table type (markdown, html, tsv).
     */
    @Column(length = 32)
    private String tableType;

    /**
     * For tables: the page number where the table was found.
     */
    @Column
    private Integer tablePageNumber;

    // ===================== SEMANTIC TYPE =====================

    /**
     * Semantic classification of this passage's content.
     * Helps categorize the type of content for better retrieval.
     */
    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ChunkSemanticType semanticType = ChunkSemanticType.TEXT;

    // ===================== SOURCE METADATA =====================

    /**
     * Type of source this chunk originates from (FILE, URL, API, etc.).
     * Denormalized from document for query efficiency.
     */
    @Column(length = 32)
    private String sourceType;

    /**
     * Source URL if the chunk came from a web source.
     * Denormalized from document for quick access.
     */
    @Column(length = 2048)
    private String sourceUrl;

    /**
     * Source file path if the chunk came from a file.
     * Denormalized from document for quick access.
     */
    @Column(length = 2048)
    private String sourcePath;

    /**
     * Title of the source document/page.
     * Useful for display and citation purposes.
     */
    @Column(length = 512)
    private String sourceTitle;

    /**
     * Author or creator of the source content.
     */
    @Column(length = 255)
    private String sourceAuthor;

    /**
     * Date the source was created or published (ISO format).
     */
    @Column(length = 32)
    private String sourceDate;

    // ===================== ENTITIES =====================

    /**
     * JSON-serialized list of entities for quick access without joins.
     * Format: [{"name": "entity1", "type": "PERSON"}, ...]
     * Full entity data is stored in chunk_entities table.
     */
    @Column(columnDefinition = "TEXT")
    private String entitiesJson;

    /**
     * Cached count of entities in this passage.
     */
    @Column
    @Builder.Default
    private Integer entityCount = 0;

    /**
     * Entities extracted from this passage.
     */
    @OneToMany(mappedBy = "passage", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ChunkEntity> entities = new ArrayList<>();

    // ===================== RELATIONS =====================

    /**
     * Cached count of relations this passage is involved in.
     */
    @Column
    @Builder.Default
    private Integer relationCount = 0;

    /**
     * Relations where this passage is the source.
     */
    @OneToMany(mappedBy = "sourceChunk", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ChunkRelation> outgoingRelations = new ArrayList<>();

    /**
     * Relations where this passage is the target.
     */
    @OneToMany(mappedBy = "targetChunk", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ChunkRelation> incomingRelations = new ArrayList<>();

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
        return create(document, chunkId, chunkIndex, contentHash, contentPreview,
                     "text", null, null);
    }

    /**
     * Create a new tracked passage with content type and full content.
     */
    public static IndexedPassage create(IndexedDocument document, String chunkId,
                                         Integer chunkIndex, String contentHash,
                                         String contentPreview, String contentType,
                                         String fullContent,
                                         java.util.Map<String, Object> metadata) {
        IndexedPassage.IndexedPassageBuilder builder = IndexedPassage.builder()
                .document(document)
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .factSheetId(document.getFactSheetId())
                .contentHash(contentHash)
                .contentPreview(truncatePreview(contentPreview))
                .contentType(contentType != null ? contentType : "text")
                .fullContent(fullContent)
                .keywordIndexStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .vectorStoreStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .graphStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .semanticType(ChunkSemanticType.TEXT)
                .entityCount(0)
                .relationCount(0)
                .entities(new ArrayList<>())
                .outgoingRelations(new ArrayList<>())
                .incomingRelations(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now());

        if (metadata != null) {
            // Extract table-specific metadata if present
            if ("table".equals(contentType)) {
                builder.semanticType(ChunkSemanticType.TABLE);
                if (metadata.get("table_row_count") instanceof Number) {
                    builder.tableRowCount(((Number) metadata.get("table_row_count")).intValue());
                }
                if (metadata.get("table_column_count") instanceof Number) {
                    builder.tableColumnCount(((Number) metadata.get("table_column_count")).intValue());
                }
                if (metadata.get("table_headers") instanceof String) {
                    builder.tableHeaders((String) metadata.get("table_headers"));
                }
                if (metadata.get("table_type") instanceof String) {
                    builder.tableType((String) metadata.get("table_type"));
                }
                if (metadata.get("page_number") instanceof Number) {
                    builder.tablePageNumber(((Number) metadata.get("page_number")).intValue());
                }
            } else if ("code".equals(contentType)) {
                builder.semanticType(ChunkSemanticType.CODE);
            }

            // Extract semantic type if explicitly provided
            if (metadata.get("semantic_type") instanceof String) {
                try {
                    builder.semanticType(ChunkSemanticType.valueOf(
                            ((String) metadata.get("semantic_type")).toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Keep default if invalid
                }
            }

            // Extract source metadata
            if (metadata.get("source_type") instanceof String) {
                builder.sourceType((String) metadata.get("source_type"));
            }
            if (metadata.get("source_url") instanceof String) {
                builder.sourceUrl((String) metadata.get("source_url"));
            }
            if (metadata.get("source_path") instanceof String) {
                builder.sourcePath((String) metadata.get("source_path"));
            }
            if (metadata.get("source_title") instanceof String) {
                builder.sourceTitle((String) metadata.get("source_title"));
            }
            if (metadata.get("source_author") instanceof String) {
                builder.sourceAuthor((String) metadata.get("source_author"));
            }
            if (metadata.get("source_date") instanceof String) {
                builder.sourceDate((String) metadata.get("source_date"));
            }

            // Extract entities JSON if provided
            if (metadata.get("entities_json") instanceof String) {
                builder.entitiesJson((String) metadata.get("entities_json"));
            }
        }

        IndexedPassage passage = builder.build();
        document.addPassage(passage);
        return passage;
    }

    /**
     * Check if this passage contains a table.
     */
    public boolean isTable() {
        return "table".equals(contentType);
    }

    /**
     * Get parsed table headers as a list.
     */
    public java.util.List<String> getTableHeadersList() {
        if (tableHeaders == null || tableHeaders.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return java.util.Arrays.asList(tableHeaders.split(","));
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

    // ===================== ENTITY MANAGEMENT =====================

    /**
     * Add an entity to this passage.
     */
    public void addEntity(ChunkEntity entity) {
        if (entities == null) {
            entities = new ArrayList<>();
        }
        entity.setPassage(this);
        entity.setFactSheetId(this.factSheetId);
        entities.add(entity);
        incrementEntityCount();
    }

    /**
     * Remove an entity from this passage.
     */
    public void removeEntity(ChunkEntity entity) {
        if (entities != null) {
            entities.remove(entity);
            decrementEntityCount();
        }
    }

    /**
     * Increment the entity count.
     */
    public void incrementEntityCount() {
        if (entityCount == null) entityCount = 0;
        entityCount++;
    }

    /**
     * Decrement the entity count.
     */
    public void decrementEntityCount() {
        if (entityCount == null || entityCount <= 0) {
            entityCount = 0;
        } else {
            entityCount--;
        }
    }

    /**
     * Check if this passage has any entities.
     */
    public boolean hasEntities() {
        return entityCount != null && entityCount > 0;
    }

    // ===================== RELATION MANAGEMENT =====================

    /**
     * Add an outgoing relation from this passage.
     */
    public void addOutgoingRelation(ChunkRelation relation) {
        if (outgoingRelations == null) {
            outgoingRelations = new ArrayList<>();
        }
        relation.setSourceChunk(this);
        relation.setFactSheetId(this.factSheetId);
        outgoingRelations.add(relation);
        incrementRelationCount();
    }

    /**
     * Add an incoming relation to this passage.
     */
    public void addIncomingRelation(ChunkRelation relation) {
        if (incomingRelations == null) {
            incomingRelations = new ArrayList<>();
        }
        relation.setTargetChunk(this);
        incomingRelations.add(relation);
        incrementRelationCount();
    }

    /**
     * Remove an outgoing relation.
     */
    public void removeOutgoingRelation(ChunkRelation relation) {
        if (outgoingRelations != null) {
            outgoingRelations.remove(relation);
            decrementRelationCount();
        }
    }

    /**
     * Remove an incoming relation.
     */
    public void removeIncomingRelation(ChunkRelation relation) {
        if (incomingRelations != null) {
            incomingRelations.remove(relation);
            decrementRelationCount();
        }
    }

    /**
     * Increment the relation count.
     */
    public void incrementRelationCount() {
        if (relationCount == null) relationCount = 0;
        relationCount++;
    }

    /**
     * Decrement the relation count.
     */
    public void decrementRelationCount() {
        if (relationCount == null || relationCount <= 0) {
            relationCount = 0;
        } else {
            relationCount--;
        }
    }

    /**
     * Check if this passage has any relations.
     */
    public boolean hasRelations() {
        return relationCount != null && relationCount > 0;
    }

    /**
     * Get all relations (both incoming and outgoing).
     */
    public List<ChunkRelation> getAllRelations() {
        List<ChunkRelation> all = new ArrayList<>();
        if (outgoingRelations != null) {
            all.addAll(outgoingRelations);
        }
        if (incomingRelations != null) {
            all.addAll(incomingRelations);
        }
        return all;
    }

    /**
     * Get relations of a specific type.
     */
    public List<ChunkRelation> getRelationsOfType(ChunkRelationType type) {
        return getAllRelations().stream()
                .filter(r -> r.getRelationType() == type)
                .toList();
    }

    /**
     * Get related passages (all passages connected by relations).
     */
    public List<IndexedPassage> getRelatedPassages() {
        List<IndexedPassage> related = new ArrayList<>();
        if (outgoingRelations != null) {
            outgoingRelations.forEach(r -> {
                if (r.getTargetChunk() != null) {
                    related.add(r.getTargetChunk());
                }
            });
        }
        if (incomingRelations != null) {
            incomingRelations.forEach(r -> {
                if (r.getSourceChunk() != null) {
                    related.add(r.getSourceChunk());
                }
            });
        }
        return related;
    }

    // ===================== SOURCE METADATA HELPERS =====================

    /**
     * Check if this passage has source metadata.
     */
    public boolean hasSourceMetadata() {
        return sourceUrl != null || sourcePath != null || sourceTitle != null;
    }

    /**
     * Get the source identifier (URL or path).
     */
    public String getSourceIdentifier() {
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            return sourceUrl;
        }
        if (sourcePath != null && !sourcePath.isEmpty()) {
            return sourcePath;
        }
        return null;
    }

    /**
     * Set source metadata from the parent document.
     */
    public void inheritSourceFromDocument() {
        if (document != null) {
            if (sourcePath == null) {
                sourcePath = document.getSourceId();
            }
            if (sourceTitle == null) {
                sourceTitle = document.getFileName();
            }
        }
    }
}
