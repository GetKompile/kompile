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
 * Entity representing a named entity extracted from a chunk/passage.
 * Provides direct entity storage on passages independent of the knowledge graph.
 * This enables efficient entity-based retrieval and filtering of chunks.
 */
@Entity
@Table(name = "chunk_entities", indexes = {
    @Index(name = "idx_chunk_ent_passage", columnList = "passage_id"),
    @Index(name = "idx_chunk_ent_name", columnList = "entityName"),
    @Index(name = "idx_chunk_ent_type", columnList = "entityType"),
    @Index(name = "idx_chunk_ent_name_type", columnList = "entityName, entityType"),
    @Index(name = "idx_chunk_ent_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_chunk_ent_fact_sheet_name", columnList = "factSheetId, entityName"),
    @Index(name = "idx_chunk_ent_fact_sheet_type", columnList = "factSheetId, entityType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The passage containing this entity.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passage_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private IndexedPassage passage;

    /**
     * Normalized entity name (lowercase, trimmed).
     */
    @Column(nullable = false, length = 255)
    private String entityName;

    /**
     * Original text as it appears in the content.
     */
    @Column(length = 255)
    private String originalText;

    /**
     * Type of entity.
     */
    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private EntityType entityType;

    /**
     * Number of times this entity is mentioned in the passage.
     */
    @Column
    @Builder.Default
    private Integer mentionCount = 1;

    /**
     * NER/extraction confidence score (0.0 to 1.0).
     */
    @Column
    private Double confidence;

    /**
     * Start position in the passage content.
     */
    @Column
    private Integer startOffset;

    /**
     * End position in the passage content.
     */
    @Column
    private Integer endOffset;

    /**
     * Context snippet where the entity appears (50 chars before/after).
     */
    @Column(length = 255)
    private String contextSnippet;

    /**
     * JSON-serialized additional positions if entity appears multiple times.
     * Format: [{"start": 10, "end": 20}, {"start": 50, "end": 60}]
     */
    @Column(columnDefinition = "TEXT")
    private String additionalPositionsJson;

    /**
     * How the entity was extracted.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ExtractionMethod extractionMethod = ExtractionMethod.NER;

    /**
     * Canonical/normalized form of the entity (for entity resolution).
     */
    @Column(length = 255)
    private String canonicalName;

    /**
     * External ID linking to entity in knowledge base (e.g., Wikidata ID).
     */
    @Column(length = 128)
    private String externalId;

    /**
     * Fact sheet this entity belongs to (denormalized for query efficiency).
     */
    @Column(nullable = false)
    private Long factSheetId;

    /**
     * When this entity was extracted.
     */
    @Column(nullable = false)
    private Instant createdAt;

    // ===================== LIFECYCLE HOOKS =====================

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (mentionCount == null) {
            mentionCount = 1;
        }
        if (extractionMethod == null) {
            extractionMethod = ExtractionMethod.NER;
        }
    }

    // ===================== ENUMS =====================

    /**
     * Types of named entities.
     */
    public enum EntityType {
        PERSON,
        ORGANIZATION,
        LOCATION,
        DATE,
        TIME,
        MONEY,
        PERCENT,
        PRODUCT,
        EVENT,
        WORK_OF_ART,
        LAW,
        LANGUAGE,
        TECHNOLOGY,
        CONCEPT,
        QUANTITY,
        ORDINAL,
        CARDINAL,
        URL,
        EMAIL,
        PHONE,
        CODE,
        FILE_PATH,
        VERSION,
        API,
        FUNCTION,
        CLASS,
        VARIABLE,
        CUSTOM,
        UNKNOWN
    }

    /**
     * Method used to extract the entity.
     */
    public enum ExtractionMethod {
        /**
         * Named Entity Recognition (NER) model
         */
        NER,

        /**
         * Regular expression pattern matching
         */
        REGEX,

        /**
         * Dictionary/gazetteer lookup
         */
        DICTIONARY,

        /**
         * LLM-based extraction
         */
        LLM,

        /**
         * Rule-based extraction
         */
        RULE,

        /**
         * Manually annotated
         */
        MANUAL,

        /**
         * Imported from external source
         */
        IMPORTED
    }

    // ===================== FACTORY METHODS =====================

    /**
     * Create a simple entity with basic info.
     */
    public static ChunkEntity of(IndexedPassage passage, String entityName,
                                  EntityType entityType, Long factSheetId) {
        return ChunkEntity.builder()
                .passage(passage)
                .entityName(entityName.toLowerCase().trim())
                .originalText(entityName)
                .entityType(entityType)
                .mentionCount(1)
                .factSheetId(factSheetId)
                .build();
    }

    /**
     * Create an entity with confidence and position.
     */
    public static ChunkEntity of(IndexedPassage passage, String entityName,
                                  EntityType entityType, double confidence,
                                  int startOffset, int endOffset, Long factSheetId) {
        return ChunkEntity.builder()
                .passage(passage)
                .entityName(entityName.toLowerCase().trim())
                .originalText(entityName)
                .entityType(entityType)
                .confidence(confidence)
                .startOffset(startOffset)
                .endOffset(endOffset)
                .mentionCount(1)
                .extractionMethod(ExtractionMethod.NER)
                .factSheetId(factSheetId)
                .build();
    }

    /**
     * Create an entity from LLM extraction.
     */
    public static ChunkEntity fromLlm(IndexedPassage passage, String entityName,
                                       EntityType entityType, String canonicalName,
                                       Long factSheetId) {
        return ChunkEntity.builder()
                .passage(passage)
                .entityName(entityName.toLowerCase().trim())
                .originalText(entityName)
                .entityType(entityType)
                .canonicalName(canonicalName)
                .mentionCount(1)
                .extractionMethod(ExtractionMethod.LLM)
                .factSheetId(factSheetId)
                .build();
    }

    // ===================== CONVENIENCE METHODS =====================

    /**
     * Increment the mention count.
     */
    public void incrementMentionCount() {
        if (mentionCount == null) mentionCount = 0;
        mentionCount++;
    }

    /**
     * Get the normalized entity name for matching.
     */
    public String getNormalizedName() {
        return entityName != null ? entityName.toLowerCase().trim() : null;
    }

    /**
     * Check if this entity matches another by name and type.
     */
    public boolean matches(String name, EntityType type) {
        if (name == null || entityName == null) return false;
        return entityName.equalsIgnoreCase(name.trim()) &&
               (type == null || entityType == type);
    }
}
