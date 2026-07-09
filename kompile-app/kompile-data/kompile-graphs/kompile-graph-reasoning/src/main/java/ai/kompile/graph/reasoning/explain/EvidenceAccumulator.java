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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-safe accumulator that collects {@link ModalityEvidence} from concurrent reasoning
 * engines and assembles a final {@link CompositeReasoningTrail}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * EvidenceAccumulator acc = new EvidenceAccumulator(targetId, question);
 * // from each engine (possibly concurrent):
 * acc.add(ModalityEvidence.fromTrail(ModalityKind.GROUNDING, groundingTrail));
 * acc.add(ModalityEvidence.fromTrail(ModalityKind.PSL, pslTrail));
 * // ...
 * CompositeReasoningTrail result = acc.build();
 * }</pre>
 *
 * <h3>Opinion-aware fusion (E17)</h3>
 * <p>Modalities are split into two groups by their epistemic relationship to the shared KB
 * fact store:</p>
 * <ul>
 *   <li><b>Correlated group</b> ({@link ModalityKind#PSL}, {@link ModalityKind#MEBN},
 *       {@link ModalityKind#GROUNDING}, {@link ModalityKind#HYBRID}) — these engines all
 *       read the same KB fact store, so their signals are correlated.  They are fused with
 *       Jøsang's <em>averaging</em> operator (idempotent, prevents spurious confidence
 *       inflation from shared evidence).</li>
 *   <li><b>Independent group</b> ({@link ModalityKind#CAUSAL}, {@link ModalityKind#GRAPH_RAG},
 *       {@link ModalityKind#RAG_CHUNK}) — these come from separate evidence channels.  They
 *       are kept as a separate collection.</li>
 * </ul>
 * <p>The correlated group is fused first (AVERAGING).  Then that result is combined with each
 * independent modality's opinion via the <em>cumulative</em> operator (after
 * {@code withBaseRate(0.5)} canonicalization to prevent non-monotone-expectation artefacts).
 * The final {@code fusedConfidence} is the combined opinion's {@code expectation()}.  When a
 * modality carries an explicit {@link Opinion} it is used as-is; otherwise one is derived via
 * {@link Opinion#uncertaintyMaximized(double, double)} with base-rate 0.5.</p>
 *
 * <p>Zero-active-modality behavior is unchanged: fused confidence = 0.0.</p>
 *
 * <h3>LLM synthesis</h3>
 * Call {@link #withAnswer(String)} before {@link #build()} to attach an LLM-produced answer.
 * If omitted the {@code naturalLanguageAnswer} field in the trail is an empty string.
 */
public class EvidenceAccumulator {

    /**
     * Correlated modalities: all read the same KB fact store → AVERAGING fusion (idempotent).
     */
    private static final Set<ModalityKind> CORRELATED = EnumSet.of(
            ModalityKind.PSL, ModalityKind.MEBN, ModalityKind.GROUNDING, ModalityKind.HYBRID);

    /**
     * Independent modalities: separate evidence channels → CUMULATIVE fusion after correlated group.
     */
    private static final Set<ModalityKind> INDEPENDENT = EnumSet.of(
            ModalityKind.CAUSAL, ModalityKind.GRAPH_RAG, ModalityKind.RAG_CHUNK);

    private final String targetId;
    private final String question;
    private final List<ModalityEvidence> modalities = new ArrayList<>();
    private String naturalLanguageAnswer = "";
    private final String runId = UUID.randomUUID().toString();

    public EvidenceAccumulator(String targetId, String question) {
        this.targetId = targetId;
        this.question = question == null ? "" : question;
    }

    /**
     * Add evidence from one modality.  Safe to call from multiple threads concurrently.
     */
    public synchronized EvidenceAccumulator add(ModalityEvidence evidence) {
        if (evidence != null) {
            modalities.add(evidence);
        }
        return this;
    }

    /**
     * Attach an LLM-synthesised natural-language answer.
     */
    public synchronized EvidenceAccumulator withAnswer(String answer) {
        this.naturalLanguageAnswer = answer == null ? "" : answer;
        return this;
    }

    /**
     * Assemble and return the {@link CompositeReasoningTrail}.
     * May be called only once after all engines have written their evidence.
     */
    public synchronized CompositeReasoningTrail build() {
        FusionResult fr = computeFused();
        return new CompositeReasoningTrail(
                targetId,
                question,
                new ArrayList<>(modalities),
                fr.confidence,
                naturalLanguageAnswer,
                Instant.now(),
                runId,
                fr.opinion
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Holder for the fused confidence and optional fused opinion. */
    private record FusionResult(double confidence, Opinion opinion) {}

    /**
     * Opinion-aware fusion (E17):
     * 1. Collect opinions from correlated modalities; fuse with AVERAGING.
     * 2. Collect opinions from independent modalities.
     * 3. Fold correlated result into each independent opinion via CUMULATIVE (withBaseRate 0.5).
     * 4. Return expectation() as fusedConfidence and the final opinion.
     */
    private FusionResult computeFused() {
        List<Opinion> correlatedOps = new ArrayList<>();
        List<Opinion> independentOps = new ArrayList<>();

        for (ModalityEvidence m : modalities) {
            if (!m.hasSignal()) continue;
            Opinion op = m.effectiveOpinion();
            if (op == null) continue;
            if (CORRELATED.contains(m.kind())) {
                correlatedOps.add(op);
            } else if (INDEPENDENT.contains(m.kind())) {
                independentOps.add(op);
            }
            // Unknown kinds treated as correlated (safe default: idempotent)
            else {
                correlatedOps.add(op);
            }
        }

        if (correlatedOps.isEmpty() && independentOps.isEmpty()) {
            return new FusionResult(0.0, null);
        }

        // Step 1: fuse correlated group with AVERAGING (idempotent for shared-evidence sources)
        Opinion fused = correlatedOps.isEmpty()
                ? null
                : Opinion.averagingFuse(correlatedOps);

        // Step 2: fold in independent sources via CUMULATIVE (canonicalized to baseRate=0.5)
        for (Opinion indOp : independentOps) {
            Opinion rebased = indOp.withBaseRate(0.5);
            if (fused == null) {
                fused = rebased;
            } else {
                fused = fused.withBaseRate(0.5).cumulativeFuse(rebased);
            }
        }

        if (fused == null) {
            return new FusionResult(0.0, null);
        }
        return new FusionResult(fused.expectation(), fused);
    }

    /**
     * Return the fused {@link Opinion} for the currently accumulated modalities,
     * using the same fusion logic as {@link #build()}.
     * Useful for inspection / testing before {@code build()} is called.
     */
    public synchronized Opinion fusedOpinion() {
        return computeFused().opinion;
    }
}
