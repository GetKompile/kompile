/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.confidence;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link BasisType} and {@link StructuralFactAssertionService} (Pillar 2).
 *
 * <p>The headline contract: basis determines the certainty dynamics. A single STRUCTURAL
 * observation is ESTABLISHED immediately; a single CORROBORATIVE (LLM) observation is only
 * SPECULATIVE and must accumulate. Same {@code fromBetaEvidence} call, different prior W.</p>
 */
class BasisTypeStructuralAssertionTest {

    private final StructuralFactAssertionService svc = new StructuralFactAssertionService();

    // ── BasisType prior strengths ────────────────────────────────────────────────

    @Test
    void priorStrength_isPerBasis() {
        assertThat(BasisType.STRUCTURAL.priorStrength()).isEqualTo(0.1);
        assertThat(BasisType.LLM_EXTRACTION.priorStrength()).isEqualTo(2.0);
        assertThat(BasisType.PSL_INFERENCE.priorStrength()).isEqualTo(2.0);
        assertThat(BasisType.MEBN_INFERENCE.priorStrength()).isEqualTo(2.0);
        assertThat(BasisType.CORROBORATION.priorStrength()).isEqualTo(2.0);
        assertThat(BasisType.ASSERTED.priorStrength()).isEqualTo(0.0);
    }

    @Test
    void deductiveVsCorroborative_classification() {
        assertThat(BasisType.STRUCTURAL.isDeductive()).isTrue();
        assertThat(BasisType.STRUCTURAL.isCorroborative()).isFalse();
        assertThat(BasisType.LLM_EXTRACTION.isCorroborative()).isTrue();
        assertThat(BasisType.MEBN_INFERENCE.isCorroborative()).isTrue();
        assertThat(BasisType.ASSERTED.isDeductive()).isFalse();
        assertThat(BasisType.ASSERTED.isCorroborative()).isFalse();
    }

    @Test
    void fromString_isCaseInsensitive_andFallsBackToLlmExtraction() {
        assertThat(BasisType.fromString("structural")).isEqualTo(BasisType.STRUCTURAL);
        assertThat(BasisType.fromString("  ASSERTED ")).isEqualTo(BasisType.ASSERTED);
        assertThat(BasisType.fromString("psl_inference")).isEqualTo(BasisType.PSL_INFERENCE);
        // Unknown / blank / null → safe corroborative default (never deductive by accident)
        assertThat(BasisType.fromString("nonsense")).isEqualTo(BasisType.LLM_EXTRACTION);
        assertThat(BasisType.fromString("")).isEqualTo(BasisType.LLM_EXTRACTION);
        assertThat(BasisType.fromString(null)).isEqualTo(BasisType.LLM_EXTRACTION);
    }

    // ── The headline contrast: same evidence, different basis, different band ──────

    @Test
    void singleStructuralObservation_isEstablished() {
        // From: header trust 0.95, one observation, structural prior W=0.1
        Opinion op = svc.structural(0.95, 0.5);
        assertThat(op.disbelief()).isEqualTo(0.0);               // pure assertion, no disbelief
        assertThat(op.belief()).isGreaterThan(0.85);             // ≈0.905
        assertThat(op.uncertainty()).isLessThan(0.15);           // ≈0.095
        assertThat(op.projectBand()).isEqualTo(StrengthBand.ESTABLISHED);
    }

    @Test
    void singleLlmObservation_isOnlySpeculative() {
        // Same single observation but corroborative basis (W=2.0): must NOT be established
        Opinion op = svc.opinionFor(BasisType.LLM_EXTRACTION, 0.6, 0.0, 0.5);
        assertThat(op.belief()).isLessThan(0.30);                // ≈0.23
        assertThat(op.uncertainty()).isGreaterThan(0.60);        // ≈0.77
        assertThat(op.projectBand()).isEqualTo(StrengthBand.SPECULATIVE);
    }

    @Test
    void asserted_isCertainByConstruction() {
        Opinion op = svc.opinionFor(BasisType.ASSERTED, 0.0, 0.0, 0.5);
        assertThat(op.belief()).isEqualTo(1.0);
        assertThat(op.uncertainty()).isEqualTo(0.0);
        assertThat(op.expectation()).isEqualTo(1.0);
        assertThat(op.projectBand()).isEqualTo(StrengthBand.ESTABLISHED);
    }

    @Test
    void weakStructuralRule_isProbableNotEstablished() {
        // belongs_to_org from email domain is weaker: lower posStrength → mid band, still no disbelief
        Opinion op = svc.structural(0.25, 0.5);
        assertThat(op.disbelief()).isEqualTo(0.0);
        assertThat(op.belief()).isLessThan(0.85);                // ≈0.714
        assertThat(op.projectBand())
                .isIn(StrengthBand.HIGH, StrengthBand.PROBABLE);
    }

    // ── Provenance metadata ────────────────────────────────────────────────────────

    @Test
    void metadataFor_carriesOpinionAndBasisKeys() {
        Opinion op = svc.structural(0.95, 0.5);
        long now = 1_700_000_000_000L;
        Map<String, Object> meta = svc.metadataFor(BasisType.STRUCTURAL, op, 0.95, 0.0, 0.95, now);

        assertThat(meta).containsKey(GraphProvenanceKeys.OPINION);
        assertThat((String) meta.get(GraphProvenanceKeys.OPINION)).contains("\"belief\"");
        assertThat(meta.get(GraphProvenanceKeys.BASIS_TYPE)).isEqualTo("STRUCTURAL");
        assertThat((Double) meta.get(GraphProvenanceKeys.EVIDENCE_POS)).isCloseTo(0.95, within(1e-9));
        assertThat((Double) meta.get(GraphProvenanceKeys.EVIDENCE_NEG)).isEqualTo(0.0);
        assertThat((Double) meta.get(GraphProvenanceKeys.PRIOR_STRENGTH)).isEqualTo(0.1);
        assertThat((Double) meta.get(GraphProvenanceKeys.SOURCE_TRUST)).isEqualTo(0.95);
        assertThat(meta.get(GraphProvenanceKeys.VALID_FROM)).isEqualTo(now);
    }

    @Test
    void contradiction_raisesUncertainty_evenStructural() {
        // If a structural fact later gets contradicting evidence, belief drops / disbelief rises
        Opinion op = svc.opinionFor(BasisType.STRUCTURAL, 0.95, 0.95, 0.5);
        assertThat(op.disbelief()).isGreaterThan(0.0);
        assertThat(op.isConflicted(0.3)).isTrue();
    }
}
