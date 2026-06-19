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
package ai.kompile.event.observation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The running Beta-Binomial counter for one event, keyed by {@link #eventKey}.
 *
 * <p>This is the authoritative, transactional record of an event's empirical prior: {@code alpha}
 * and {@code beta} are the live Beta parameters, with the cumulative {@code occurrenceCount} and
 * {@code opportunityCount} kept alongside for transparency. Column names are explicit snake_case so
 * the table and its indexes are independent of the active Hibernate naming strategy.</p>
 */
@Entity
@Table(name = "observed_events", indexes = {
        @Index(name = "idx_oe_event_key", columnList = "event_key", unique = true),
        @Index(name = "idx_oe_subject", columnList = "subject_node_id"),
        @Index(name = "idx_oe_connection", columnList = "source_node_id, target_node_id, edge_type"),
        @Index(name = "idx_oe_factsheet", columnList = "fact_sheet_id"),
        @Index(name = "idx_oe_type", columnList = "event_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ObservedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", length = 512, unique = true, nullable = false)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 32, nullable = false)
    private EventType eventType;

    /** Subject node id for ENTITY_OCCURRENCE / PROCESS_STEP_OCCURRENCE events. */
    @Column(name = "subject_node_id", length = 512)
    private String subjectNodeId;

    @Column(name = "source_node_id", length = 512)
    private String sourceNodeId;

    @Column(name = "target_node_id", length = 512)
    private String targetNodeId;

    @Column(name = "edge_type", length = 128)
    private String edgeType;

    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    @Column(name = "occurrence_count")
    private long occurrenceCount;

    @Column(name = "opportunity_count")
    private long opportunityCount;

    @Column(name = "alpha")
    private double alpha;

    @Column(name = "beta")
    private double beta;

    @Column(name = "first_observed_at")
    private LocalDateTime firstObservedAt;

    @Column(name = "last_observed_at")
    private LocalDateTime lastObservedAt;

    @Column(name = "last_decayed_at")
    private LocalDateTime lastDecayedAt;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /** Empirical probability P(event) = alpha / (alpha + beta). */
    public double probability() {
        double denom = alpha + beta;
        return denom <= 0.0 ? 0.0 : alpha / denom;
    }

    public double evidenceStrength() {
        return alpha + beta;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (firstObservedAt == null) {
            firstObservedAt = now;
        }
        if (lastObservedAt == null) {
            lastObservedAt = now;
        }
    }
}
