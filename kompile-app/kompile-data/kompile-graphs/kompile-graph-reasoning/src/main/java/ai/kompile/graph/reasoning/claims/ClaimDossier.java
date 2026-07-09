/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.Step;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-signal evidence dossier for a single claim triple (subject, predicate, object).
 *
 * <p>A dossier aggregates evidence from multiple reasoning channels — direct graph edges,
 * logical derivations, PSL inference, KGE embeddings, mined rules, and the Knowledge Linker
 * path scorer — into a unified supporting + refuting structure and a single fused score.
 *
 * <p>The design follows the ExFaKT "supporting + refuting chains" paradigm:
 * Shiralkar et al., "Finding streams in knowledge graphs to support fact-checking,"
 * <em>WSDM 2017</em>; and the Knowledge Linker: Ciampaglia et al., <em>PLoS ONE</em> 2015.
 *
 * <p>Build instances via {@link DossierBuilder#assess}.
 *
 * @param claimAtom   canonical atom key string (e.g. {@code "basedIn(alice, london)"})
 * @param subject     subject entity id
 * @param predicate   predicate / relation type
 * @param object      object entity id
 * @param supporting  evidence items that support the claim
 * @param refuting    evidence items that refute the claim
 * @param fusedScore  log-odds fused probability in {@code [0, 1]}
 */
public record ClaimDossier(
        String claimAtom,
        String subject,
        String predicate,
        String object,
        List<DossierItem> supporting,
        List<DossierItem> refuting,
        double fusedScore) {

    public ClaimDossier {
        Objects.requireNonNull(claimAtom,  "claimAtom");
        Objects.requireNonNull(subject,    "subject");
        Objects.requireNonNull(predicate,  "predicate");
        Objects.requireNonNull(object,     "object");
        supporting = (supporting == null) ? List.of() : List.copyOf(supporting);
        refuting   = (refuting   == null) ? List.of() : List.copyOf(refuting);
        if (fusedScore < 0.0 || fusedScore > 1.0) {
            throw new IllegalArgumentException("fusedScore must be in [0,1], got " + fusedScore);
        }
    }

    /** All items (supporting + refuting) in a single list, supporting items first. */
    public List<DossierItem> allItems() {
        List<DossierItem> all = new ArrayList<>(supporting.size() + refuting.size());
        all.addAll(supporting);
        all.addAll(refuting);
        return all;
    }

    /**
     * Convert the dossier into a {@link ReasoningTrace}.
     *
     * <p>Structure:
     * <ul>
     *   <li>Root: {@link StepKind#INFERENCE} step for the claim atom, confidence = fusedScore.</li>
     *   <li>Supporting items: child steps of kinds FACT/RULE/QUERY/FUSION (mapped by item kind).</li>
     *   <li>Refuting items: child {@link StepKind#REBUTTAL} steps — this surfaces contradictions
     *       and defeats in the trace without changing the root confidence.</li>
     * </ul>
     *
     * <p>Each step carries a {@code "dossierKind"} meta entry so downstream renderers can
     * color-code by evidence type.
     *
     * @return a walkable {@link ReasoningTrace} for the claim
     */
    public ReasoningTrace toReasoningTrace() {
        List<Step> premises = new ArrayList<>();

        // Supporting items → mapped step kinds
        for (DossierItem item : supporting) {
            StepKind sk = supportingStepKind(item.kind());
            Step step = Step.derived(sk, item.description(), item.kind().name(),
                    item.probability(), null,
                    Map.of("dossierKind", item.kind().name()),
                    provenanceSteps(item));
            premises.add(step);
        }

        // Refuting items → REBUTTAL steps
        for (DossierItem item : refuting) {
            Step step = Step.derived(StepKind.REBUTTAL, item.description(), item.kind().name(),
                    item.probability(), null,
                    Map.of("dossierKind", item.kind().name()),
                    provenanceSteps(item));
            premises.add(step);
        }

        Step root = Step.derived(StepKind.INFERENCE, claimAtom, "log-odds-fusion",
                fusedScore, null, Map.of("subject", subject, "predicate", predicate, "object", object),
                premises);

        return ReasoningTrace.of(root);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Map a supporting item kind to an appropriate {@link StepKind}. */
    private static StepKind supportingStepKind(DossierItem.Kind kind) {
        return switch (kind) {
            case DIRECT_EDGE   -> StepKind.FACT;
            case DATALOG_PROOF -> StepKind.RULE;
            case PSL           -> StepKind.INFERENCE;
            case MINED_RULE    -> StepKind.RULE;
            case PATH          -> StepKind.QUERY;
            case KGE           -> StepKind.INFERENCE;
            // Refuting kinds should not appear in supporting — but fall back gracefully
            case FUNCTIONAL_CONFLICT, NEGATED_ATOM -> StepKind.FACT;
        };
    }

    /** Build leaf FACT steps from provenance strings. */
    private static List<Step> provenanceSteps(DossierItem item) {
        if (item.provenance().isEmpty()) return List.of();
        List<Step> leaves = new ArrayList<>();
        for (String p : item.provenance()) {
            leaves.add(Step.fact(p, item.probability(), item.kind().name()));
        }
        return leaves;
    }
}
