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

package ai.kompile.app.facts.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity representing a Fact Sheet - a named collection of facts (documents/files).
 * Users can create multiple fact sheets and switch between them.
 * Fact sheets can be derived from other sheets or have facts copied between them.
 */
@Entity
@Table(name = "fact_sheets", indexes = {
    @Index(name = "idx_fact_sheet_name", columnList = "name"),
    @Index(name = "idx_fact_sheet_active", columnList = "isActive"),
    @Index(name = "idx_fact_sheet_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactSheet {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Name of the fact sheet (user-defined).
     */
    @Column(nullable = false, length = 255, unique = true)
    private String name;

    /**
     * Optional description of the fact sheet.
     */
    @Column(length = 1024)
    private String description;

    /**
     * Whether this is the currently active fact sheet.
     * Only one fact sheet should be active at a time.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * ID of the fact sheet this was derived from (if any).
     */
    @Column
    private Long derivedFromId;

    /**
     * When this fact sheet was created.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this fact sheet was last modified.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Color for UI display (hex color code).
     */
    @Column(length = 7)
    @Builder.Default
    private String color = "#1976d2";

    /**
     * Icon name for UI display.
     */
    @Column(length = 50)
    @Builder.Default
    private String icon = "folder";

    /**
     * Path to the vector (dense) store for this fact sheet.
     * If null, uses the global default.
     */
    @Column(length = 512)
    private String vectorStorePath;

    /**
     * Path to the keyword (sparse) index for this fact sheet.
     * If null, uses the global default.
     */
    @Column(length = 512)
    private String keywordIndexPath;

    // ==================== Retrieval Configuration ====================

    /**
     * Embedding model ID for retrieval (e.g., "bge-base-en-v1.5").
     * If null, uses the global default model.
     */
    @Column(length = 128)
    private String embeddingModel;

    /**
     * Source of the embedding model: 'archive', 'registry', or 'default'.
     */
    @Column(length = 32)
    @Builder.Default
    private String embeddingModelSource = "default";

    /**
     * Archive ID if embeddingModelSource is 'archive'.
     */
    @Column(length = 128)
    private String embeddingArchiveId;

    /**
     * The embedding model that was actually used when indexing documents.
     * This tracks what model the vector store contains embeddings from.
     * If this differs from embeddingModel, a reindex is required.
     * Set automatically when indexing completes successfully.
     */
    @Column(length = 128)
    private String indexedWithModel;

    /**
     * Timestamp when the vector store was last populated/indexed.
     * Used together with indexedWithModel to track index freshness.
     */
    @Column
    private Instant indexedAt;

    // ==================== Reranking Configuration ====================

    /**
     * Whether reranking is enabled for this fact sheet.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean rerankingEnabled = false;

    /**
     * Type of reranker: 'none', 'cross_encoder', 'rrf', 'mmr', 'rm3', 'bm25prf', etc.
     */
    @Column(length = 32)
    @Builder.Default
    private String rerankerType = "none";

    /**
     * Cross-encoder model ID for reranking (e.g., "ms-marco-MiniLM-L-6-v2").
     * Used when rerankerType is 'cross_encoder'.
     */
    @Column(length = 128)
    private String crossEncoderModel;

    /**
     * Source of the cross-encoder model: 'archive', 'registry', or 'default'.
     */
    @Column(length = 32)
    @Builder.Default
    private String crossEncoderModelSource = "default";

    /**
     * Archive ID if crossEncoderModelSource is 'archive'.
     */
    @Column(length = 128)
    private String crossEncoderArchiveId;

    /**
     * Number of top results to rerank.
     */
    @Column
    @Builder.Default
    private Integer rerankTopK = 100;

    /**
     * MMR lambda parameter (diversity vs relevance trade-off).
     */
    @Column
    @Builder.Default
    private Double mmrLambda = 0.5;

    // ==================== Knowledge Graph Building Configuration ====================

    /**
     * Whether knowledge graph building is enabled during document indexing.
     * When enabled, the configured graph builder will extract entities and
     * relationships from chunks as they are indexed.
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    @Builder.Default
    private Boolean enableGraphBuilding = false;

    /**
     * Type of graph builder to use: 'manual', 'llm', 'pattern'.
     * Default is 'llm' for LLM-based entity extraction.
     */
    @Column(length = 32, columnDefinition = "VARCHAR(32) DEFAULT 'llm'")
    @Builder.Default
    private String graphBuilderType = "llm";

    /**
     * JSON-serialized graph builder configuration.
     * Contains model settings, entity types, confidence thresholds, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String graphBuilderConfigJson;

    /**
     * Target graph storage backend: 'jpa' (kompile-knowledge-graph) or 'neo4j'.
     * Default is 'jpa' for the built-in JPA-based knowledge graph.
     */
    @Column(length = 16, columnDefinition = "VARCHAR(16) DEFAULT 'jpa'")
    @Builder.Default
    private String graphStorageType = "jpa";

    /**
     * The facts belonging to this sheet.
     */
    @OneToMany(mappedBy = "factSheet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Fact> facts = new HashSet<>();

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

    /**
     * Add a fact to this sheet.
     */
    public void addFact(Fact fact) {
        facts.add(fact);
        fact.setFactSheet(this);
    }

    /**
     * Remove a fact from this sheet.
     */
    public void removeFact(Fact fact) {
        facts.remove(fact);
        fact.setFactSheet(null);
    }

    /**
     * Get the count of facts in this sheet.
     */
    public int getFactCount() {
        return facts != null ? facts.size() : 0;
    }
}
