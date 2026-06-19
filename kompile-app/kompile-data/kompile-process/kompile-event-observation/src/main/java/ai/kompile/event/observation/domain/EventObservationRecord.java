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
 * Append-only ledger of individual observation batches for one event. Each row records the
 * occurrences/opportunities seen at a point in time, enabling the prior time-series endpoint,
 * windowed recompute, and audit. The running Beta lives on {@link ObservedEvent}; this is history.
 */
@Entity
@Table(name = "event_observation_records", indexes = {
        @Index(name = "idx_eor_event_key_time", columnList = "event_key, observed_at"),
        @Index(name = "idx_eor_factsheet_time", columnList = "fact_sheet_id, observed_at"),
        @Index(name = "idx_eor_observed_at", columnList = "observed_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventObservationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", length = 512, nullable = false)
    private String eventKey;

    @Column(name = "occurrences")
    private long occurrences;

    @Column(name = "opportunities")
    private long opportunities;

    /** Empirical probability snapshot (running Beta mean) at the time of this observation. */
    @Column(name = "probability_at")
    private double probabilityAt;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16)
    private EventSource source;

    @Column(name = "crawl_job_id", length = 128)
    private String crawlJobId;

    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    @PrePersist
    void onCreate() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
    }
}
