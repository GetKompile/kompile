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

import jakarta.persistence.*;
import lombok.*;

/**
 * Represents an entity mention within a graph node.
 * Used to compute shared-entity edges between nodes that mention the same entities.
 */
@Entity
@Table(name = "entity_mentions", indexes = {
    @Index(name = "idx_em_entity_name", columnList = "entityName"),
    @Index(name = "idx_em_node", columnList = "node_id"),
    @Index(name = "idx_em_type", columnList = "entityType"),
    @Index(name = "idx_em_entity_type", columnList = "entityName, entityType"),
    @Index(name = "idx_em_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_em_fact_sheet_entity", columnList = "fact_sheet_id, entityName")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityMention {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The node containing this entity mention
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode node;

    /**
     * Normalized entity name (lowercase, trimmed)
     */
    @Column(nullable = false, length = 255)
    private String entityName;

    /**
     * Type of entity: PERSON, ORG, LOCATION, CONCEPT, PRODUCT, TECHNOLOGY, etc.
     */
    @Column(length = 50)
    private String entityType;

    /**
     * How many times this entity is mentioned in the node's content
     */
    @Column
    @Builder.Default
    private Integer mentionCount = 1;

    /**
     * NER confidence score (0.0 to 1.0)
     */
    @Column
    private Double confidence;

    /**
     * JSON array of sample context snippets where this entity appears
     */
    @Column(columnDefinition = "TEXT")
    private String contextJson;

    /**
     * The fact sheet this entity mention belongs to (null for global/legacy mentions).
     * Enables scoping knowledge graphs per fact sheet.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * Increment the mention count
     */
    public void incrementMentionCount() {
        if (mentionCount == null) mentionCount = 0;
        mentionCount++;
    }

    /**
     * Static factory for creating a mention with basic info
     */
    public static EntityMention of(GraphNode node, String entityName, String entityType) {
        return EntityMention.builder()
            .node(node)
            .entityName(entityName.toLowerCase().trim())
            .entityType(entityType)
            .mentionCount(1)
            .build();
    }

    /**
     * Static factory with confidence
     */
    public static EntityMention of(GraphNode node, String entityName, String entityType, double confidence) {
        return EntityMention.builder()
            .node(node)
            .entityName(entityName.toLowerCase().trim())
            .entityType(entityType)
            .mentionCount(1)
            .confidence(confidence)
            .build();
    }
}
