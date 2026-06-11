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
package ai.kompile.enrichment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "enrichment_audit_log", indexes = {
        @Index(name = "idx_audit_fact_sheet", columnList = "factSheetId"),
        @Index(name = "idx_audit_job_id", columnList = "enrichmentJobId"),
        @Index(name = "idx_audit_phase", columnList = "phase"),
        @Index(name = "idx_audit_audit_id", columnList = "auditId")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EnrichmentAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, length = 36)
    private String auditId;

    private Long factSheetId;

    private String enrichmentJobId;

    @Column(length = 32)
    private String phase;

    @Column(length = 64)
    private String action;

    private String targetNodeId;

    @Column(length = 32)
    private String targetType;

    @Column(columnDefinition = "TEXT")
    private String beforeSnapshot;

    @Column(columnDefinition = "TEXT")
    private String afterSnapshot;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    private boolean reverted = false;

    private String revertedBy;

    private Instant revertedAt;

    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
