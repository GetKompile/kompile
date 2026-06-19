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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a node in the knowledge graph.
 * Nodes can be Sources, Documents, Snippets, Entities, or Custom nodes.
 */
@Entity
@Table(name = "graph_nodes", indexes = {
    @Index(name = "idx_node_type", columnList = "nodeType"),
    @Index(name = "idx_node_external_id", columnList = "externalId"),
    @Index(name = "idx_node_parent", columnList = "parent_id"),
    @Index(name = "idx_node_source", columnList = "source_id"),
    @Index(name = "idx_node_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_node_fact_sheet_type", columnList = "fact_sheet_id, nodeType"),
    @Index(name = "idx_node_occurred_at", columnList = "occurred_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNode {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references
     */
    @Column(nullable = false, unique = true, length = 36)
    private String nodeId;

    /**
     * Type/level of this node in the hierarchy
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private NodeLevel nodeType;

    /**
     * Original identifier from the source system (source_id, doc_id, chunk_id)
     */
    @Column(nullable = false, length = 512)
    private String externalId;

    /**
     * Display title for the node
     */
    @Column(nullable = false, length = 500)
    private String title;

    /**
     * Optional description
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Content preview (first 500 chars for snippets)
     */
    @Column(columnDefinition = "TEXT")
    private String contentPreview;

    /**
     * Parent node in the hierarchy (null for SOURCE nodes)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode parent;

    /**
     * Child nodes in the hierarchy
     */
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<GraphNode> children = new ArrayList<>();

    /**
     * Root source node reference (for quick source lookup from any level)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode sourceNode;

    /**
     * JSON-serialized metadata map
     */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    /**
     * Reference to vector store document ID (for embedding lookup)
     */
    @Column(length = 255)
    private String vectorId;

    /**
     * Source type (for SOURCE nodes only): FILE, URL, SLACK, CONFLUENCE, etc.
     */
    @Column(length = 50)
    private String sourceType;

    /**
     * Path or URL (for SOURCE nodes)
     */
    @Column(length = 2048)
    private String pathOrUrl;

    /**
     * Cached count of children
     */
    @Column
    @Builder.Default
    private Integer childCount = 0;

    /**
     * Cached count of edges connected to this node
     */
    @Column
    @Builder.Default
    private Integer edgeCount = 0;

    /**
     * The fact sheet this node belongs to (null for global/legacy nodes).
     * Enables scoping knowledge graphs per fact sheet.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * Confidence score for this node (0.0 to 1.0).
     * Used during entity resolution and compaction.
     */
    @Column
    private Double confidence;

    /**
     * Named graph this node belongs to (optional).
     * Enables grouping nodes into logical sub-graphs.
     */
    @Column(name = "named_graph_id", length = 255)
    private String namedGraphId;

    /**
     * Whether this node has been soft-deleted (marked stale).
     */
    @Column
    @Builder.Default
    private Boolean stale = false;

    /**
     * When this node was marked stale.
     */
    @Column(name = "stale_at")
    private LocalDateTime staleAt;

    /**
     * Whether a user has pinned this node (preventing automatic pruning).
     */
    @Column(name = "user_pinned")
    @Builder.Default
    private Boolean userPinned = false;

    /**
     * When this node expires (for TTL-based sweeping).
     */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /**
     * When this node was last verified as still valid.
     */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /**
     * When this node was first observed in the source.
     */
    @Column(name = "observed_at")
    private LocalDateTime observedAt;

    /**
     * When the real-world event represented by this node occurred.
     * For example: email sent time, document creation date, commit timestamp,
     * Google Docs creation time. Distinct from createdAt (DB insertion)
     * and observedAt (when first seen during crawl).
     */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    // ═══════════════════════════════════════════════════════════════════════════
    // KNOWLEDGE GRAPH EMBEDDINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Knowledge graph embedding vector for this entity.
     * Trained using TransE, RotatE, or other KG embedding algorithms.
     */
    @Column(name = "kg_embedding", columnDefinition = "BLOB")
    @Convert(converter = INDArrayConverter.class)
    private INDArray kgEmbedding;

    /**
     * Algorithm used to generate the KG embedding.
     */
    @Column(name = "kg_embedding_algorithm", length = 32)
    @Enumerated(EnumType.STRING)
    private KGEmbeddingAlgorithm kgEmbeddingAlgorithm;

    /**
     * Version/timestamp of the training run that produced this embedding.
     * Used to track which embeddings are from the same training session.
     */
    @Column(name = "kg_embedding_version")
    private Long kgEmbeddingVersion;

    /**
     * When the KG embedding was last updated.
     */
    @Column(name = "kg_embedding_updated_at")
    private Instant kgEmbeddingUpdatedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (nodeId == null) {
            nodeId = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Helper method to increment child count
     */
    public void incrementChildCount() {
        if (childCount == null) childCount = 0;
        childCount++;
    }

    /**
     * Helper method to decrement child count
     */
    public void decrementChildCount() {
        if (childCount == null || childCount <= 0) {
            childCount = 0;
        } else {
            childCount--;
        }
    }

    /**
     * Helper method to increment edge count
     */
    public void incrementEdgeCount() {
        if (edgeCount == null) edgeCount = 0;
        edgeCount++;
    }

    /**
     * Helper method to decrement edge count
     */
    public void decrementEdgeCount() {
        if (edgeCount == null || edgeCount <= 0) {
            edgeCount = 0;
        } else {
            edgeCount--;
        }
    }

    /** Shared parser for {@link #metadataJson}; ObjectMapper is thread-safe for reads. */
    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Parsed view of {@link #metadataJson}, exposed to API clients as {@code metadata}.
     *
     * <p>This is computed (not persisted) and lives on the shared domain object so that
     * <em>every</em> knowledge-graph store — JPA, the vector/matrix store, or any future
     * backend — surfaces node metadata to the UI identically, with no per-store mapping
     * code. The index-browser/graph-visualizer rely on this to render TABLE nodes (and
     * other structured entities) instead of a raw JSON blob.
     */
    @jakarta.persistence.Transient
    @com.fasterxml.jackson.annotation.JsonProperty(value = "metadata",
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public java.util.Map<String, Object> getMetadata() {
        if (metadataJson == null || metadataJson.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return METADATA_MAPPER.readValue(metadataJson,
                    METADATA_MAPPER.getTypeFactory().constructMapType(
                            java.util.LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }
}
