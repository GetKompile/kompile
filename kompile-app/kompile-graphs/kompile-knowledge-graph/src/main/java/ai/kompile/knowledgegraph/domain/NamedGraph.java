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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a named knowledge graph, enabling a "graph of graphs" hierarchy.
 * Named graphs can be nested (parent/child) to model domain ontologies,
 * taxonomies, or scoped knowledge collections.
 */
@Entity
@Table(name = "named_graphs", indexes = {
    @Index(name = "idx_named_graph_id", columnList = "graphId", unique = true),
    @Index(name = "idx_named_graph_name", columnList = "name"),
    @Index(name = "idx_named_graph_parent", columnList = "parent_graph_id"),
    @Index(name = "idx_named_graph_fact_sheet", columnList = "fact_sheet_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class NamedGraph {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references. Generated in @PrePersist if null.
     */
    @Column(nullable = false, unique = true, length = 36)
    private String graphId;

    /**
     * User-defined name for this graph.
     */
    @Column(nullable = false, length = 500)
    private String name;

    /**
     * Optional description of the graph's purpose or contents.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Parent graph in the hierarchy. Null for root (top-level) graphs.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_graph_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private NamedGraph parentGraph;

    /**
     * Child graphs in the hierarchy.
     */
    @OneToMany(mappedBy = "parentGraph", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private List<NamedGraph> childGraphs = new ArrayList<>();

    /**
     * Optional fact sheet this graph is scoped to.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * Optional classification for this graph (e.g., "domain_ontology", "taxonomy", "knowledge_graph").
     */
    @Column(length = 100)
    private String ontologyType;

    /**
     * Optional JSON schema defining allowed node/edge types for this graph.
     */
    @Column(columnDefinition = "TEXT")
    private String schemaJson;

    /**
     * Cached count of GraphNode entries associated with this named graph.
     */
    @Column
    @Builder.Default
    private Integer nodeCount = 0;

    /**
     * Cached count of GraphEdge entries associated with this named graph.
     */
    @Column
    @Builder.Default
    private Integer edgeCount = 0;

    /**
     * Cached count of direct child graphs.
     */
    @Column
    @Builder.Default
    private Integer childGraphCount = 0;

    /**
     * Arbitrary JSON metadata for extensibility.
     */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (graphId == null) {
            graphId = UUID.randomUUID().toString();
        }
        if (nodeCount == null) nodeCount = 0;
        if (edgeCount == null) edgeCount = 0;
        if (childGraphCount == null) childGraphCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Returns the parent graph's graphId for API serialization.
     */
    public String getParentGraphId() {
        return parentGraph != null ? parentGraph.getGraphId() : null;
    }

    /**
     * Increment the cached child graph count.
     */
    public void incrementChildGraphCount() {
        if (childGraphCount == null) childGraphCount = 0;
        childGraphCount++;
    }

    /**
     * Decrement the cached child graph count (floor 0).
     */
    public void decrementChildGraphCount() {
        if (childGraphCount == null || childGraphCount <= 0) {
            childGraphCount = 0;
        } else {
            childGraphCount--;
        }
    }

    /**
     * Increment the cached node count.
     */
    public void incrementNodeCount() {
        if (nodeCount == null) nodeCount = 0;
        nodeCount++;
    }

    /**
     * Decrement the cached node count (floor 0).
     */
    public void decrementNodeCount() {
        if (nodeCount == null || nodeCount <= 0) {
            nodeCount = 0;
        } else {
            nodeCount--;
        }
    }
}
