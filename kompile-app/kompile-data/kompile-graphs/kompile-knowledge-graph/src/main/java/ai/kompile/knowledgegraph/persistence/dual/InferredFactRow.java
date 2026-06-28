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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity persisting one version of an {@link ai.kompile.graph.reasoning.fol.InferredFact}
 * in the relational dual-store.
 *
 * <p>The full fact is serialized as JSON in {@code provenanceJson} (via
 * {@link ai.kompile.graph.reasoning.fol.InferredFact#toJson()}) so the scalar columns
 * ({@code atomKey}, {@code version}, {@code runId}) serve as indexed query handles while the
 * full fact round-trips through the JSON column without schema drift.</p>
 *
 * <p>All rows are scoped to a {@code factSheetId} so each fact-sheet's data is isolated and
 * queryable independently.</p>
 */
@Entity
@Table(
    name = "inferred_fact_store",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_inferred_fact_fsheet_atom_version",
        columnNames = {"fact_sheet_id", "atom_key", "version"}
    )
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class InferredFactRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Fact-sheet scope — all queries are filtered on this column. Not null. */
    @Column(name = "fact_sheet_id", nullable = false)
    private Long factSheetId;

    /** Canonical atom key (e.g. {@code "isEmployedBy(Alice, Acme)"}). */
    @Column(name = "atom_key", nullable = false, length = 512)
    private String atomKey;

    /** Monotonic version counter — higher version supersedes lower for the same atomKey. */
    @Column(name = "version", nullable = false)
    private long version;

    /** Soft-truth value in [0, 1]. ("value" is a reserved word in H2/SQL — quote it for portable DDL.) */
    @Column(name = "`value`", nullable = false)
    private double value;

    /** Confidence score in [0, 1]. */
    @Column(name = "confidence", nullable = false)
    private double confidence;

    /** Inference run identifier. */
    @Column(name = "run_id", nullable = false, length = 256)
    private String runId;

    /**
     * Full JSON serialization of the {@link ai.kompile.graph.reasoning.fol.InferredFact}
     * produced by {@link ai.kompile.graph.reasoning.fol.InferredFact#toJson()}.
     * Stored in a CLOB so that supporting-key lists of arbitrary length are preserved.
     */
    @Lob
    @Column(name = "provenance_json", nullable = false)
    private String provenanceJson;

    /** Wall-clock timestamp when this fact was inferred. */
    @Column(name = "inferred_at", nullable = false)
    private Instant inferredAt;

    /**
     * Persisted {@link ai.kompile.graph.reasoning.confidence.StrengthBand} name for this fact row
     * (e.g. "ESTABLISHED", "HIGH", "PROBABLE", "SPECULATIVE", "SUPPRESSED").
     *
     * <p>Populated on first store() and updated by {@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker}
     * whenever the band changes. Allows durable "show all SPECULATIVE facts" queries against
     * the {@code band} column rather than recomputing the band from the confidence at read time.</p>
     *
     * <p>Nullable: rows written before the L2-durability feature lack a persisted band; callers
     * should derive the band from {@link #getConfidence()} when this field is null.</p>
     */
    @Column(name = "band", nullable = true, length = 32)
    private String band;

    /**
     * Promotion status: "NONE" (never promoted), "PROMOTED" (has been promoted at least once).
     * Set to "NONE" on first store() and flipped to "PROMOTED" by FactPromotionTracker.
     * Nullable for backward compatibility with pre-feature rows.
     */
    @Column(name = "promotion_status", nullable = true, length = 16)
    private String promotionStatus;

    /**
     * Durable corroboration count: how many times this atom key has been corroborated across
     * grounding cascade runs.  Persisted by {@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker}
     * so the count survives a restart.
     */
    @Column(name = "corroboration_count", nullable = false)
    private int corroborationCount;

    /**
     * Cumulative positive evidence weight accumulated via Beta-distribution mapping.
     * Each corroborating observation adds {@code sourceTrust} (not 1.0) to this accumulator
     * so that high-trust sources contribute more than low-trust ones.
     *
     * <p>Used by {@link ai.kompile.knowledgegraph.reasoning.FactPromotionTracker} to compute
     * {@code Opinion.fromBetaEvidence(evidencePos, evidenceNeg, 0.5, priorStrength)} and derive
     * a meaningful band rather than relying on the raw PSL MAP scalar.</p>
     *
     * <p>Default 0.0 (no evidence yet). Nullable for backward-compatibility with pre-slice-1 rows.</p>
     */
    @Column(name = "evidence_pos", nullable = true)
    private Double evidencePos;

    /**
     * Cumulative negative evidence weight (contradiction-sourced).
     * Incremented when {@link ai.kompile.knowledgegraph.grounding.KbCorrectionService} or
     * {@code ContradictionDetector} determines a source contradicts this fact.
     *
     * <p>Default 0.0. Nullable for backward-compatibility with pre-slice-1 rows.</p>
     */
    @Column(name = "evidence_neg", nullable = true)
    private Double evidenceNeg;
}
