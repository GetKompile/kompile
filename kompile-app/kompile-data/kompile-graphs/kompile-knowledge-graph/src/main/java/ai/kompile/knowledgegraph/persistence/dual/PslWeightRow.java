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
package ai.kompile.knowledgegraph.persistence.dual;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity persisting one PSL rule weight row for a given program version.
 *
 * <p>Each row captures a single rule display key → weight mapping under a
 * {@code (programKey, version)} scope. Multiple rows at the same
 * {@code (programKey, version)} collectively form the full weight-set for that
 * program version.</p>
 *
 * <p>{@code programKey} is the internally-scoped key produced by
 * {@link DualStoreWeightStore}: {@code "fs:<factSheetId>:<programId>"} for
 * fact-sheet-scoped entries, or {@code programId} for global entries.</p>
 */
@Entity
@Table(
    name = "psl_weight_store",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_psl_weight_program_version_rule",
        columnNames = {"program_key", "version", "rule_display"}
    )
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PslWeightRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Internally-scoped program key (e.g. {@code "fs:42:my-program"} or {@code "my-program"}). */
    @Column(name = "program_key", nullable = false, length = 512)
    private String programKey;

    /** 1-based version number; all rows at the same (programKey, version) form one weight-set. */
    @Column(name = "version", nullable = false)
    private int version;

    /** Rule display string (the {@link ai.kompile.graph.reasoning.psl.PslRule#toString()} key). */
    @Column(name = "rule_display", nullable = false, length = 1024)
    private String ruleDisplay;

    /** The learned weight for this rule at this version. */
    @Column(name = "weight", nullable = false)
    private double weight;

    /**
     * Optional fact-sheet scope. Null means project-scoped (global).
     * Set when the enclosing {@link DualStoreWeightStore} was constructed with a factSheetId.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /** Wall-clock timestamp when this weight row was persisted. */
    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;
}
