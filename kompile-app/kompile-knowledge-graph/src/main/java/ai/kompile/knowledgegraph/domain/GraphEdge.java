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
package ai.kompile.knowledgegraph.domain;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import jakarta.persistence.*;
import lombok.*;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents an edge/relationship between two nodes in the knowledge graph.
 */
@Entity
@Table(name = "graph_edges", indexes = {
    @Index(name = "idx_edge_source", columnList = "source_node_id"),
    @Index(name = "idx_edge_target", columnList = "target_node_id"),
    @Index(name = "idx_edge_type", columnList = "edgeType"),
    @Index(name = "idx_edge_weight", columnList = "weight"),
    @Index(name = "idx_edge_type_weight", columnList = "edgeType, weight"),
    @Index(name = "idx_edge_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_edge_fact_sheet_type", columnList = "fact_sheet_id, edgeType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references
     */
    @Column(nullable = false, unique = true, length = 36)
    private String edgeId;

    /**
     * Source node of this edge
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode sourceNode;

    /**
     * Target node of this edge
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode targetNode;

    /**
     * Type of this edge
     */
    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private EdgeType edgeType;

    /**
     * Edge weight (0.0 to 1.0 for most types, higher is stronger relationship)
     */
    @Column(nullable = false)
    private Double weight;

    /**
     * Human-readable description of the relationship
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Optional label for display
     */
    @Column(length = 255)
    private String label;

    /**
     * JSON array of shared entities (for SHARED_ENTITY edges)
     */
    @Column(columnDefinition = "TEXT")
    private String sharedEntitiesJson;

    /**
     * Similarity score (for EMBEDDING_SIMILARITY edges)
     */
    @Column
    private Double similarityScore;

    /**
     * Whether this edge is bidirectional
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean bidirectional = false;

    /**
     * JSON-serialized metadata
     */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this edge was last computed (for auto-computed edges)
     */
    @Column
    private LocalDateTime computedAt;

    /**
     * The fact sheet this edge belongs to (null for global/legacy edges).
     * Enables scoping knowledge graphs per fact sheet.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    // ═══════════════════════════════════════════════════════════════════════════
    // KNOWLEDGE GRAPH EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Knowledge graph embedding vector for this relation type.
     * Trained using TransE, RotatE, or other KG embedding algorithms.
     * Note: Relations of the same type share the same embedding, but we store
     * it per edge for query convenience.
     */
    @Column(name = "kg_relation_embedding", columnDefinition = "BLOB")
    @Convert(converter = INDArrayConverter.class)
    private INDArray kgRelationEmbedding;

    /**
     * Algorithm used to generate the KG embedding.
     */
    @Column(name = "kg_embedding_algorithm", length = 32)
    @Enumerated(EnumType.STRING)
    private KGEmbeddingAlgorithm kgEmbeddingAlgorithm;

    /**
     * Version/timestamp of the training run that produced this embedding.
     */
    @Column(name = "kg_embedding_version")
    private Long kgEmbeddingVersion;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (edgeId == null) {
            edgeId = UUID.randomUUID().toString();
        }
        if (bidirectional == null) {
            bidirectional = false;
        }
    }

    /**
     * Mark this edge as computed now (for similarity/entity edges)
     */
    public void markComputed() {
        this.computedAt = LocalDateTime.now();
    }

    /**
     * Check if this edge is stale (computed more than specified days ago)
     */
    public boolean isStale(int days) {
        if (computedAt == null) return true;
        return computedAt.isBefore(LocalDateTime.now().minusDays(days));
    }
}
