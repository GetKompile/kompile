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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A multi-modal evidence trace that holds the combined output of ALL applicable reasoning
 * engines for a single target / question.
 *
 * <p>Where {@link ReasoningTrail} is single-mode (one engine, NaN for inactive fields),
 * {@code CompositeReasoningTrail} carries one {@link ModalityEvidence} per engine that ran,
 * each with its own confidence, summary, and detail lines.  A fused confidence is computed
 * across the active modalities using the opinion-aware fusion strategy (E17).</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Assembled by {@link EvidenceAccumulator} after all engines complete.</li>
 *   <li>Immutable record — all collections are defensive-copied.</li>
 *   <li>{@link #naturalLanguageAnswer} is set when an LLM synthesis step ran;
 *       otherwise it is an empty string (the caller can display the per-modality
 *       summaries instead).</li>
 *   <li>{@link #fusedOpinion} carries the full subjective-logic opinion from which
 *       {@link #fusedConfidence} was derived ({@code opinion.expectation()}).
 *       Null when no modality had a signal.</li>
 * </ul>
 *
 * @param targetId              the entity id / atom key that was explained
 * @param question              the natural-language question that was asked (may be empty)
 * @param modalities            one entry per reasoning engine that contributed evidence
 * @param fusedConfidence       blended confidence across all active modalities (0..1)
 * @param naturalLanguageAnswer LLM-synthesised answer, or empty string if stubbed
 * @param computedAt            wall-clock timestamp of this composite result
 * @param runId                 correlation id for the whole fused run
 * @param fusedOpinion          full subjective-logic opinion for the fused result (E17); nullable
 */
public record CompositeReasoningTrail(
        String targetId,
        String question,
        List<ModalityEvidence> modalities,
        double fusedConfidence,
        String naturalLanguageAnswer,
        Instant computedAt,
        String runId,
        Opinion fusedOpinion
) {
    /** Correlated modalities (read same KB fact store) — AVERAGING fusion group. */
    private static final Set<ModalityKind> CORRELATED = EnumSet.of(
            ModalityKind.PSL, ModalityKind.MEBN, ModalityKind.GROUNDING, ModalityKind.HYBRID);

    /** Independent modalities (separate evidence channels) — CUMULATIVE fusion group. */
    private static final Set<ModalityKind> INDEPENDENT = EnumSet.of(
            ModalityKind.CAUSAL, ModalityKind.GRAPH_RAG, ModalityKind.RAG_CHUNK);

    public CompositeReasoningTrail {
        Objects.requireNonNull(targetId, "targetId");
        if (question == null) question = "";
        modalities = modalities == null ? List.of() : List.copyOf(modalities);
        if (naturalLanguageAnswer == null) naturalLanguageAnswer = "";
        if (computedAt == null) computedAt = Instant.now();
        if (runId == null) runId = "";
        // fusedOpinion stays nullable — absence means zero active modalities
    }

    /**
     * Back-compat constructor: no fusedOpinion supplied (null).
     * Identical to the original 7-arg record constructor so existing test code
     * that uses {@code new CompositeReasoningTrail(...7 args...)} compiles unchanged.
     */
    public CompositeReasoningTrail(String targetId, String question,
                                   List<ModalityEvidence> modalities,
                                   double fusedConfidence,
                                   String naturalLanguageAnswer,
                                   Instant computedAt,
                                   String runId) {
        this(targetId, question, modalities, fusedConfidence, naturalLanguageAnswer,
             computedAt, runId, null);
    }

    /** Number of modalities that produced a non-zero confidence signal. */
    public long activeModalityCount() {
        return modalities.stream().filter(ModalityEvidence::hasSignal).count();
    }

    /** True when more than one modality contributed a signal (the fused case). */
    public boolean isMultiModal() {
        return activeModalityCount() > 1;
    }

    /** True when an LLM synthesis answer is available. */
    public boolean hasAnswer() {
        return naturalLanguageAnswer != null && !naturalLanguageAnswer.isBlank();
    }

    /**
     * Convert this multi-modal trail into the unified {@link ReasoningTrace}: one
     * {@link ReasoningTrace.StepKind#FUSION} step per modality (its detail lines as fact premises),
     * all under a fused conclusion carrying {@link #fusedConfidence}.
     *
     * <h4>E3 enrichments</h4>
     * The root step carries {@code question}, {@code runId}, and {@code computedAt} in its meta map.
     * When {@link #fusedOpinion} is non-null the root step also carries the full opinion and the
     * meta key {@code fusion.operator=averaging+cumulative}.
     *
     * <h4>E17 per-modality enrichments</h4>
     * Each per-modality step carries:
     * <ul>
     *   <li>{@code modality=<KIND>} — which engine produced it</li>
     *   <li>{@code fusion.group=correlated|independent} — which fusion group it belongs to</li>
     *   <li>The modality's own {@link Opinion} when available ({@link ModalityEvidence#effectiveOpinion()})</li>
     * </ul>
     */
    public ReasoningTrace toReasoningTrace() {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (ModalityEvidence m : modalities) {
            List<ReasoningTrace.Step> details = new ArrayList<>();
            for (String d : m.details()) {
                details.add(ReasoningTrace.Step.fact(d, ReasoningTrace.clamp01(m.confidence()), m.kind().name()));
            }
            String label = m.summary().isEmpty() ? m.kind().name() : m.summary();

            // E17: per-modality fusion group classification
            String fusionGroup = CORRELATED.contains(m.kind()) ? "correlated"
                    : INDEPENDENT.contains(m.kind()) ? "independent"
                    : "correlated"; // unknown → treated as correlated (same safe default as accumulator)

            // E3 + E17: per-modality meta
            Map<String, String> modalityMeta = new HashMap<>();
            modalityMeta.put("modality", m.kind().name());
            modalityMeta.put("fusion.group", fusionGroup);

            // E17: attach per-modality opinion when available
            Opinion modalityOp = m.effectiveOpinion();
            ReasoningTrace.Step modalityStep = new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.FUSION, label,
                    m.kind().name(), ReasoningTrace.clamp01(m.confidence()), null, details,
                    modalityOp, modalityMeta);
            premises.add(modalityStep);
        }

        String conclusion = naturalLanguageAnswer.isEmpty() ? targetId : naturalLanguageAnswer;

        // E3 + E17: root meta
        Map<String, String> rootMeta = new HashMap<>();
        if (question != null && !question.isBlank()) rootMeta.put("question", question);
        if (runId != null && !runId.isBlank()) rootMeta.put("runId", runId);
        if (computedAt != null) rootMeta.put("computedAt", computedAt.toString());
        if (fusedOpinion != null) rootMeta.put("fusion.operator", "averaging+cumulative");

        // E17: root step carries fusedOpinion when available
        ReasoningTrace.Step root = new ReasoningTrace.Step(ReasoningTrace.StepKind.FUSION,
                conclusion, "fused", ReasoningTrace.clamp01(fusedConfidence), runId, premises,
                fusedOpinion, rootMeta);
        return ReasoningTrace.of(root);
    }
}
