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
import java.util.UUID;

/**
 * Entity representing a relationship between two chunks/passages.
 * Provides direct inter-chunk relationship tracking independent of the knowledge graph.
 * This enables efficient traversal of chunk relationships for context retrieval.
 */
@Entity
@Table(name = "chunk_relations", indexes = {
    @Index(name = "idx_chunk_rel_source", columnList = "source_chunk_id"),
    @Index(name = "idx_chunk_rel_target", columnList = "target_chunk_id"),
    @Index(name = "idx_chunk_rel_type", columnList = "relationType"),
    @Index(name = "idx_chunk_rel_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_chunk_rel_source_type", columnList = "source_chunk_id, relationType"),
    @Index(name = "idx_chunk_rel_fact_sheet_type", columnList = "factSheetId, relationType")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_chunk_relation", columnNames = {"source_chunk_id", "target_chunk_id", "relationType"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references.
     */
    @Column(nullable = false, unique = true, length = 36)
    private String relationId;

    /**
     * Source chunk of this relationship.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_chunk_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private IndexedPassage sourceChunk;

    /**
     * Target chunk of this relationship.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_chunk_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private IndexedPassage targetChunk;

    /**
     * Type of relationship between the chunks.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private ChunkRelationType relationType;

    /**
     * Relationship strength/weight (0.0 to 1.0).
     * Higher values indicate stronger relationships.
     */
    @Column
    @Builder.Default
    private Double weight = 1.0;

    /**
     * Similarity score for SIMILAR_TO relationships.
     */
    @Column
    private Double similarityScore;

    /**
     * Human-readable description of the relationship.
     */
    @Column(length = 512)
    private String description;

    /**
     * Optional label for display purposes.
     */
    @Column(length = 128)
    private String label;

    /**
     * JSON-serialized shared entities (for SHARED_ENTITY relationships).
     * Format: ["entity1", "entity2", ...]
     */
    @Column(columnDefinition = "TEXT")
    private String sharedEntitiesJson;

    /**
     * Whether this relationship is bidirectional.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean bidirectional = false;

    /**
     * How the relationship was determined.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RelationSource relationSource = RelationSource.AUTOMATIC;

    /**
     * JSON-serialized metadata.
     */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * Fact sheet this relation belongs to (for scoping).
     */
    @Column(nullable = false)
    private Long factSheetId;

    /**
     * When this relation was created.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this relation was last updated.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * When this relation was computed (for auto-computed relations).
     */
    @Column
    private Instant computedAt;

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
        if (relationId == null) {
            relationId = UUID.randomUUID().toString();
        }
        if (bidirectional == null) {
            bidirectional = false;
        }
        if (relationSource == null) {
            relationSource = RelationSource.AUTOMATIC;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ===================== ENUMS =====================

    /**
     * Source/origin of the relationship determination.
     */
    public enum RelationSource {
        /**
         * Automatically determined by the system
         */
        AUTOMATIC,

        /**
         * Determined via embedding similarity
         */
        EMBEDDING,

        /**
         * Determined via entity extraction
         */
        ENTITY_EXTRACTION,

        /**
         * Manually created by user
         */
        MANUAL,

        /**
         * Created via LLM analysis
         */
        LLM,

        /**
         * Imported from external source
         */
        IMPORTED
    }

    // ===================== FACTORY METHODS =====================

    /**
     * Create a sequential relationship between adjacent chunks.
     */
    public static ChunkRelation sequential(IndexedPassage source, IndexedPassage target, Long factSheetId) {
        return ChunkRelation.builder()
                .sourceChunk(source)
                .targetChunk(target)
                .relationType(ChunkRelationType.SEQUENTIAL)
                .weight(1.0)
                .bidirectional(false)
                .relationSource(RelationSource.AUTOMATIC)
                .factSheetId(factSheetId)
                .build();
    }

    /**
     * Create a similarity relationship between chunks.
     */
    public static ChunkRelation similar(IndexedPassage source, IndexedPassage target,
                                        double similarityScore, Long factSheetId) {
        return ChunkRelation.builder()
                .sourceChunk(source)
                .targetChunk(target)
                .relationType(ChunkRelationType.SIMILAR_TO)
                .weight(similarityScore)
                .similarityScore(similarityScore)
                .bidirectional(true)
                .relationSource(RelationSource.EMBEDDING)
                .factSheetId(factSheetId)
                .build();
    }

    /**
     * Create a shared entity relationship between chunks.
     */
    public static ChunkRelation sharedEntity(IndexedPassage source, IndexedPassage target,
                                              String sharedEntitiesJson, Long factSheetId) {
        return ChunkRelation.builder()
                .sourceChunk(source)
                .targetChunk(target)
                .relationType(ChunkRelationType.SHARED_ENTITY)
                .weight(1.0)
                .sharedEntitiesJson(sharedEntitiesJson)
                .bidirectional(true)
                .relationSource(RelationSource.ENTITY_EXTRACTION)
                .factSheetId(factSheetId)
                .build();
    }

    /**
     * Create a custom relationship.
     */
    public static ChunkRelation custom(IndexedPassage source, IndexedPassage target,
                                        ChunkRelationType type, String description,
                                        boolean bidirectional, Long factSheetId) {
        return ChunkRelation.builder()
                .sourceChunk(source)
                .targetChunk(target)
                .relationType(type)
                .weight(1.0)
                .description(description)
                .bidirectional(bidirectional)
                .relationSource(RelationSource.MANUAL)
                .factSheetId(factSheetId)
                .build();
    }

    // ===================== CONVENIENCE METHODS =====================

    /**
     * Mark this relation as computed now.
     */
    public void markComputed() {
        this.computedAt = Instant.now();
    }

    /**
     * Check if this relation is stale (computed more than specified days ago).
     */
    public boolean isStale(int days) {
        if (computedAt == null) return true;
        return computedAt.isBefore(Instant.now().minusSeconds(days * 86400L));
    }

    /**
     * Check if the relation involves a specific chunk.
     */
    public boolean involves(IndexedPassage chunk) {
        return (sourceChunk != null && sourceChunk.getId().equals(chunk.getId())) ||
               (targetChunk != null && targetChunk.getId().equals(chunk.getId()));
    }

    /**
     * Get the other chunk in this relationship given one chunk.
     */
    public IndexedPassage getOther(IndexedPassage chunk) {
        if (sourceChunk != null && sourceChunk.getId().equals(chunk.getId())) {
            return targetChunk;
        }
        if (targetChunk != null && targetChunk.getId().equals(chunk.getId())) {
            return sourceChunk;
        }
        return null;
    }
}
