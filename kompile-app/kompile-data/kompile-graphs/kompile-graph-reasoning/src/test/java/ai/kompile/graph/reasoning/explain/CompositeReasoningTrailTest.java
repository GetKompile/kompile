/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.explain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the fused evidence-trace types:
 * {@link ModalityEvidence}, {@link EvidenceAccumulator}, and {@link CompositeReasoningTrail}.
 *
 * Proves that the merge path can carry >1 modality, computes fused confidence correctly,
 * and that the "all NaN" case does not blow up.
 */
class CompositeReasoningTrailTest {

    // ── ModalityEvidence ─────────────────────────────────────────────────────────

    @Test
    void modalityEvidence_hasSignal_trueForPositiveConfidence() {
        ModalityEvidence ev = ModalityEvidence.of(ModalityKind.GROUNDING, 0.8, "summary", List.of());
        assertTrue(ev.hasSignal(), "positive confidence must be a signal");
    }

    @Test
    void modalityEvidence_hasSignal_falseForNaN() {
        ModalityEvidence ev = ModalityEvidence.of(ModalityKind.PSL, Double.NaN, "no signal", List.of());
        assertFalse(ev.hasSignal(), "NaN confidence must not be a signal");
    }

    @Test
    void modalityEvidence_hasSignal_falseForZero() {
        ModalityEvidence ev = ModalityEvidence.of(ModalityKind.MEBN, 0.0, "zero", List.of());
        assertFalse(ev.hasSignal());
    }

    @Test
    void modalityEvidence_fromTrail_extractsEvidenceAndRules() {
        ReasoningTrail trail = ReasoningTrail.builder("atom(X)")
                .confidence(0.7)
                .evidence(List.of("fact_a", "fact_b"))
                .activatedRules(List.of("rule_1"))
                .naturalLanguageSummary("summary text")
                .build();

        ModalityEvidence ev = ModalityEvidence.fromTrail(ModalityKind.GROUNDING, trail);

        assertEquals(ModalityKind.GROUNDING, ev.kind());
        assertEquals(0.7, ev.confidence(), 1e-9);
        assertEquals("summary text", ev.summary());
        // details = evidence + activatedRules
        assertTrue(ev.details().contains("fact_a"));
        assertTrue(ev.details().contains("fact_b"));
        assertTrue(ev.details().contains("rule_1"));
        assertEquals(3, ev.details().size());
    }

    // ── EvidenceAccumulator ───────────────────────────────────────────────────────

    @Test
    void accumulator_singleModality_fusedEqualsModalityConfidence() {
        EvidenceAccumulator acc = new EvidenceAccumulator("entity_42", "Why is entity_42 relevant?");
        acc.add(ModalityEvidence.of(ModalityKind.HYBRID, 0.6, "hybrid summary", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertEquals(1, trail.modalities().size());
        assertEquals(0.6, trail.fusedConfidence(), 1e-9);
        assertEquals("entity_42", trail.targetId());
    }

    @Test
    void accumulator_multipleModalities_fusedIsOpinionAwareBlendOfActiveSignals() {
        EvidenceAccumulator acc = new EvidenceAccumulator("entity_99", "Explain entity_99");

        // GROUNDING (0.8) and PSL (0.4) are both correlated → fused via AVERAGING operator.
        // E17: fused confidence = averagingFuse(opinions).expectation() — NOT a plain mean.
        acc.add(ModalityEvidence.of(ModalityKind.GROUNDING, 0.8, "grounding", List.of("fact_a")));
        acc.add(ModalityEvidence.of(ModalityKind.PSL, 0.4, "psl", List.of("atom_x")));

        CompositeReasoningTrail trail = acc.build();

        assertEquals(2, trail.modalities().size(), "both modalities must be present");
        // Fused is strictly between min(0.4) and max(0.8)
        double fused = trail.fusedConfidence();
        assertTrue(fused > 0.4 && fused < 0.8,
                "opinion-aware fused must be between 0.4 and 0.8, got " + fused);
        // fusedOpinion must be non-null (E17 path was taken)
        assertNotNull(trail.fusedOpinion(), "fusedOpinion must be set when active modalities exist");
    }

    @Test
    void accumulator_nanModality_excludedFromFused() {
        EvidenceAccumulator acc = new EvidenceAccumulator("node_1", "");

        acc.add(ModalityEvidence.of(ModalityKind.GROUNDING, 0.9, "strong", List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.CAUSAL,    Double.NaN, "no causal signal", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertEquals(2, trail.modalities().size(), "NaN modality still present in trace");
        // fused ignores NaN → just 0.9
        assertEquals(0.9, trail.fusedConfidence(), 1e-9, "NaN must be excluded from average");
    }

    @Test
    void accumulator_allNaN_fusedIsZero() {
        EvidenceAccumulator acc = new EvidenceAccumulator("node_2", "");
        acc.add(ModalityEvidence.of(ModalityKind.MEBN,  Double.NaN, "no mebn", List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.CAUSAL, Double.NaN, "no causal", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertEquals(0.0, trail.fusedConfidence(), 1e-9, "all-NaN must yield fused=0");
    }

    // ── CompositeReasoningTrail multi-modal property ──────────────────────────────

    @Test
    void trail_isMultiModal_trueWhenMoreThanOneActiveModality() {
        EvidenceAccumulator acc = new EvidenceAccumulator("target_x", "q");
        acc.add(ModalityEvidence.of(ModalityKind.GROUNDING, 0.8, "g", List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.PSL,       0.5, "p", List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.MEBN,      0.3, "m", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertTrue(trail.isMultiModal(), "3 active modalities must be multi-modal");
        assertEquals(3, trail.activeModalityCount());
    }

    @Test
    void trail_hasAnswer_falseByDefault() {
        CompositeReasoningTrail trail = new EvidenceAccumulator("t", "q").build();
        assertFalse(trail.hasAnswer(), "no answer set => hasAnswer must be false");
    }

    @Test
    void trail_hasAnswer_trueAfterWithAnswer() {
        EvidenceAccumulator acc = new EvidenceAccumulator("t", "q");
        acc.withAnswer("LLM says: yes.");
        CompositeReasoningTrail trail = acc.build();
        assertTrue(trail.hasAnswer());
        assertEquals("LLM says: yes.", trail.naturalLanguageAnswer());
    }

    // ── Defensive / null-safety ───────────────────────────────────────────────────

    @Test
    void modalityEvidence_nullDetails_becomesEmptyList() {
        ModalityEvidence ev = new ModalityEvidence(ModalityKind.HYBRID, 0.5, "s", null);
        assertNotNull(ev.details());
        assertTrue(ev.details().isEmpty());
    }

    @Test
    void accumulator_addNull_ignored() {
        EvidenceAccumulator acc = new EvidenceAccumulator("t", "q");
        acc.add(null); // must not throw
        CompositeReasoningTrail trail = acc.build();
        assertTrue(trail.modalities().isEmpty());
    }
}
