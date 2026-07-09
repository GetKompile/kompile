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

import ai.kompile.graph.reasoning.confidence.Opinion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E17 — opinion-aware modality fusion in {@link EvidenceAccumulator}.
 *
 * <p>Correlated modalities (PSL, MEBN, GROUNDING, HYBRID) are fused with Jøsang's averaging
 * operator (idempotent, prevents confidence inflation from shared-evidence sources).
 * Independent modalities (CAUSAL, GRAPH_RAG, RAG_CHUNK) are folded in via cumulative fusion
 * (withBaseRate 0.5 canonicalization) after the correlated group is resolved.
 * {@code fusedConfidence} = {@code fusedOpinion.expectation()}.</p>
 */
class OpinionAwareFusionTest {

    // ── Test 1: two agreeing correlated modalities → idempotent averaging ────────

    @Test
    void twoAgreeingCorrelated_averagingIdemotent_fusedApproxSameAsInputs() {
        // PSL(0.8) and MEBN(0.8) are both correlated.
        // Jøsang ABF is idempotent: averagingFuse(x, x) == x → fused expectation ≈ 0.8.
        EvidenceAccumulator acc = new EvidenceAccumulator("entity_1", "q");
        acc.add(ModalityEvidence.of(ModalityKind.PSL,  0.8, "psl result",  List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.MEBN, 0.8, "mebn result", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertNotNull(trail.fusedOpinion(), "fusedOpinion must be set for active modalities");
        // Averaging of two identical uncertainty-maximized opinions is idempotent → E ≈ 0.8
        assertEquals(0.8, trail.fusedConfidence(), 1e-6,
                "averaging-fusing two identical opinions must preserve expectation");
    }

    // ── Test 2: correlated pair + independent → cumulative step lowers uncertainty ─

    @Test
    void correlatedPluIndependent_cumulativeReducesUncertainty() {
        // PSL(0.8) + MEBN(0.8) correlated group → fused opinion with E=0.8.
        // RAG_CHUNK(0.8) independent → cumulative fold → E rises, uncertainty drops.
        EvidenceAccumulator accCorrelatedOnly = new EvidenceAccumulator("e", "q");
        accCorrelatedOnly.add(ModalityEvidence.of(ModalityKind.PSL,  0.8, "psl",  List.of()));
        accCorrelatedOnly.add(ModalityEvidence.of(ModalityKind.MEBN, 0.8, "mebn", List.of()));

        EvidenceAccumulator accWithIndependent = new EvidenceAccumulator("e", "q");
        accWithIndependent.add(ModalityEvidence.of(ModalityKind.PSL,       0.8, "psl",  List.of()));
        accWithIndependent.add(ModalityEvidence.of(ModalityKind.MEBN,      0.8, "mebn", List.of()));
        accWithIndependent.add(ModalityEvidence.of(ModalityKind.RAG_CHUNK, 0.8, "rag",  List.of()));

        CompositeReasoningTrail correlatedOnly = accCorrelatedOnly.build();
        CompositeReasoningTrail withIndependent = accWithIndependent.build();

        assertNotNull(withIndependent.fusedOpinion(), "fusedOpinion must be set");
        // The independent RAG_CHUNK confirmation must lower uncertainty (raise certainty) vs
        // correlated-only — cumulative fusion always reduces uncertainty when sources agree.
        double uCorr = correlatedOnly.fusedOpinion() != null
                ? correlatedOnly.fusedOpinion().uncertainty() : 1.0;
        double uFull = withIndependent.fusedOpinion().uncertainty();
        assertTrue(uFull < uCorr,
                "cumulative with agreeing independent source must reduce uncertainty: "
                + uFull + " should be < " + uCorr);
        // Confidence must be at least as high as correlated-only
        assertTrue(withIndependent.fusedConfidence() >= correlatedOnly.fusedConfidence(),
                "adding agreeing independent source must not lower confidence");
    }

    // ── Test 3: disagreement → fused strictly between the two extremes ───────────

    @Test
    void disagreement_fusedExpectationStrictlyBetweenExtremes() {
        // PSL(0.9) is correlated; GRAPH_RAG(0.2) is independent.
        // Correlated group alone: PSL(0.9) → E=0.9.
        // After cumulative fold with GRAPH_RAG(0.2) → E strictly between 0.2 and 0.9.
        EvidenceAccumulator acc = new EvidenceAccumulator("entity_conflict", "q");
        acc.add(ModalityEvidence.of(ModalityKind.PSL,       0.9, "psl high",  List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.GRAPH_RAG, 0.2, "rag low",   List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertNotNull(trail.fusedOpinion(), "fusedOpinion must be set");
        double e = trail.fusedConfidence();
        assertTrue(e > 0.2 && e < 0.9,
                "fused expectation must be strictly between 0.2 and 0.9 under conflict, got " + e);
        // Both belief and disbelief must be material (the disagreement is visible in the opinion)
        Opinion fused = trail.fusedOpinion();
        assertTrue(fused.belief() > 0.05, "belief must be material: " + fused.belief());
        assertTrue(fused.disbelief() > 0.05, "disbelief must be material: " + fused.disbelief());
    }

    // ── Test 4: explicit Opinion is used as-is, not re-derived ──────────────────

    @Test
    void explicitOpinionUsedAsIs_notRederived() {
        // Build a non-uncertainty-maximized opinion (low uncertainty, specific b/d)
        // and supply it directly. The accumulator must use it without modification.
        Opinion explicit = Opinion.fromSoftTruth(0.75, 20); // well-supported, low uncertainty
        ModalityEvidence ev = ModalityEvidence.of(ModalityKind.PSL, 0.75, "psl", List.of(), explicit);

        assertEquals(explicit, ev.opinion(),
                "explicit opinion must be stored verbatim on ModalityEvidence");
        assertEquals(explicit, ev.effectiveOpinion(),
                "effectiveOpinion() must return explicit opinion without re-derivation");

        EvidenceAccumulator acc = new EvidenceAccumulator("e", "q");
        acc.add(ev);

        CompositeReasoningTrail trail = acc.build();
        assertNotNull(trail.fusedOpinion(), "fusedOpinion must be set");
        // Uncertainty of fused should match explicit opinion's uncertainty (single-modality averaging = identity)
        assertEquals(explicit.uncertainty(), trail.fusedOpinion().uncertainty(), 1e-9,
                "single correlated modality: fused uncertainty must equal explicit opinion uncertainty");
    }

    // ── Test 5: trace carries fusedOpinion in root and fusion.group in modality steps ─

    @Test
    void trace_rootFusionStepCarriesFusedOpinionAndOperatorMeta() {
        EvidenceAccumulator acc = new EvidenceAccumulator("entity_trace", "Why?");
        acc.add(ModalityEvidence.of(ModalityKind.PSL,       0.8, "psl",  List.of("rule1")));
        acc.add(ModalityEvidence.of(ModalityKind.GRAPH_RAG, 0.7, "rag",  List.of("chunk1")));

        CompositeReasoningTrail trail = acc.build();
        ReasoningTrace trace = trail.toReasoningTrace();

        // Root step must carry fusedOpinion and operator meta
        ReasoningTrace.Step root = trace.conclusion();
        assertNotNull(root.opinion(), "root FUSION step must carry fusedOpinion");
        Map<String, String> rootMeta = root.meta();
        assertNotNull(rootMeta, "root meta must not be null");
        assertEquals("averaging+cumulative", rootMeta.get("fusion.operator"),
                "root meta must declare E17 fusion operator");

        // Per-modality steps must carry fusion.group
        List<ReasoningTrace.Step> premises = root.premises();
        assertEquals(2, premises.size(), "must have 2 modality steps");
        ReasoningTrace.Step pslStep = premises.get(0); // PSL is first added
        ReasoningTrace.Step ragStep = premises.get(1); // GRAPH_RAG is second
        assertEquals("correlated", pslStep.meta().get("fusion.group"),
                "PSL must be tagged as correlated");
        assertEquals("independent", ragStep.meta().get("fusion.group"),
                "GRAPH_RAG must be tagged as independent");

        // Modality steps must carry their per-modality opinion
        assertNotNull(pslStep.opinion(), "PSL modality step must carry its opinion");
        assertNotNull(ragStep.opinion(), "GRAPH_RAG modality step must carry its opinion");
    }

    // ── Test 6: back-compat — original 4-arg ModalityEvidence constructor still works ─

    @Test
    void backCompat_fourArgModalityEvidenceConstructor_opinionIsNull() {
        // The original 4-arg constructor (pre-E17) must still compile and produce opinion==null.
        ModalityEvidence ev = new ModalityEvidence(ModalityKind.HYBRID, 0.5, "summary", null);
        assertNull(ev.opinion(), "back-compat constructor must yield null opinion");
        // effectiveOpinion() must still derive one from confidence
        Opinion derived = ev.effectiveOpinion();
        assertNotNull(derived, "effectiveOpinion() must derive from confidence when opinion==null");
        assertEquals(0.5, derived.expectation(), 1e-6,
                "derived opinion must have E=confidence for uncertainty-maximized at baseRate=0.5");
    }

    // ── Test 7: back-compat — 7-arg CompositeReasoningTrail constructor works ─────

    @Test
    void backCompat_sevenArgCompositeReasoningTrailConstructor_fusedOpinionIsNull() {
        // Existing test code that builds CompositeReasoningTrail directly must still compile.
        CompositeReasoningTrail trail = new CompositeReasoningTrail(
                "target", "q",
                List.of(ModalityEvidence.of(ModalityKind.PSL, 0.7, "psl", List.of())),
                0.7, "", Instant.now(), "run-1");

        assertNull(trail.fusedOpinion(),
                "back-compat 7-arg constructor must yield null fusedOpinion");
        assertEquals("target", trail.targetId());
        assertEquals(0.7, trail.fusedConfidence(), 1e-9);
    }

    // ── Test 8: zero-active-modality path unchanged ───────────────────────────────

    @Test
    void zeroActiveModalities_fusedIsZeroAndOpinionIsNull() {
        EvidenceAccumulator acc = new EvidenceAccumulator("e", "q");
        acc.add(ModalityEvidence.of(ModalityKind.PSL,   Double.NaN, "no signal", List.of()));
        acc.add(ModalityEvidence.of(ModalityKind.CAUSAL, Double.NaN, "no signal", List.of()));

        CompositeReasoningTrail trail = acc.build();

        assertEquals(0.0, trail.fusedConfidence(), 1e-9,
                "all-NaN must yield fusedConfidence=0.0");
        assertNull(trail.fusedOpinion(), "all-NaN must yield null fusedOpinion");
    }
}
