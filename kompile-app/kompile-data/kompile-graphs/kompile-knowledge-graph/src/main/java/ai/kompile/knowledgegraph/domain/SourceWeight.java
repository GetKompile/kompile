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

import java.time.LocalDateTime;

/**
 * Represents a weight configuration for a source in the knowledge graph.
 * Weights affect how documents from this source are ranked in search results.
 */
@Entity
@Table(name = "source_weights", indexes = {
    @Index(name = "idx_sw_source_node", columnList = "source_node_id"),
    @Index(name = "idx_sw_topic", columnList = "topic"),
    @Index(name = "idx_sw_user", columnList = "userId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_source_topic_user", columnNames = {"source_node_id", "topic", "userId"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The source node this weight applies to
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GraphNode sourceNode;

    /**
     * Topic/domain this weight applies to (null = global weight for this source)
     */
    @Column(length = 255)
    private String topic;

    /**
     * User-defined base weight (0.0 to 2.0, default 1.0)
     */
    @Column(nullable = false)
    @Builder.Default
    private Double baseWeight = 1.0;

    /**
     * Computed topic relevance score (0.0 to 1.0)
     * How relevant this source is to its assigned topic
     */
    @Column
    private Double topicRelevanceScore;

    /**
     * Historical quality/accuracy score (0.0 to 1.0)
     * Based on user feedback and retrieval usefulness
     */
    @Column
    private Double qualityScore;

    /**
     * Recency boost factor (typically 0.8 to 1.2)
     * Higher for recently updated sources
     */
    @Column
    private Double recencyFactor;

    /**
     * Final computed effective weight
     */
    @Column(nullable = false)
    @Builder.Default
    private Double effectiveWeight = 1.0;

    /**
     * User who defined this weight (null = system/global)
     */
    @Column(length = 255)
    private String userId;

    /**
     * JSON explanation of why this weight was assigned
     */
    @Column(columnDefinition = "TEXT")
    private String reasonJson;

    /**
     * Whether this weight is enabled
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (baseWeight == null) baseWeight = 1.0;
        if (effectiveWeight == null) effectiveWeight = 1.0;
        if (enabled == null) enabled = true;
        computeEffectiveWeight();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        computeEffectiveWeight();
    }

    /**
     * Computes the effective weight based on all factors.
     * Formula: baseWeight * (0.5 + topicRelevance * 0.5) * (0.5 + quality * 0.5) * recencyFactor
     * Clamped to [0.0, 3.0]
     */
    public void computeEffectiveWeight() {
        double ew = baseWeight != null ? baseWeight : 1.0;

        // Apply topic relevance factor (scales from 0.5x to 1.0x)
        if (topicRelevanceScore != null) {
            ew *= (0.5 + topicRelevanceScore * 0.5);
        }

        // Apply quality factor (scales from 0.5x to 1.0x)
        if (qualityScore != null) {
            ew *= (0.5 + qualityScore * 0.5);
        }

        // Apply recency factor directly
        if (recencyFactor != null) {
            ew *= recencyFactor;
        }

        // Clamp to reasonable range
        this.effectiveWeight = Math.max(0.0, Math.min(3.0, ew));
    }

    /**
     * Updates the quality score based on user feedback
     * @param wasHelpful true if retrieval was helpful, false otherwise
     */
    public void updateQualityFromFeedback(boolean wasHelpful) {
        if (qualityScore == null) {
            qualityScore = wasHelpful ? 0.6 : 0.4;
        } else {
            // Exponential moving average
            double update = wasHelpful ? 1.0 : 0.0;
            qualityScore = qualityScore * 0.9 + update * 0.1;
        }
        computeEffectiveWeight();
    }
}
