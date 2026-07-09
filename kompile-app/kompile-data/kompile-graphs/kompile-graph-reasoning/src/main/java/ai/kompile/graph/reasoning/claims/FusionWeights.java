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

/**
 * Per-kind weights for the log-odds linear fusion in {@link DossierBuilder}.
 *
 * <p>Fusion formula (supporting items contribute positively, refuting items negatively):
 * <pre>
 *   logit(p) = log(p / (1 - p))
 *   clamp(p) = max(1e-4, min(1 - 1e-4, p))
 *
 *   fused_logit = Σ_i  w_i · logit(clamp(p_i))   [supporting]
 *               + Σ_j -w_j · logit(clamp(p_j))   [refuting]
 *
 *   fused_score = σ(fused_logit) = 1 / (1 + exp(-fused_logit))
 * </pre>
 *
 * <p>Default weights are set by reliability tier:
 * <ul>
 *   <li>1.0 — direct-edge observation or Datalog logical proof (strongest)</li>
 *   <li>0.8 — PSL soft-truth inference</li>
 *   <li>0.7 — mined rule firing</li>
 *   <li>0.5 — indirect Knowledge-Linker path</li>
 *   <li>0.4 — KGE embedding plausibility (calibrated but speculative)</li>
 * </ul>
 *
 * <p>No training is required. These are interpretable defaults; the synthesis logistic scorer
 * ({@link ai.kompile.graph.reasoning.synthesis.LogisticAnswerScorer}) can later replace them
 * with learned weights per-domain.
 *
 * @param directEdgeWeight  weight for {@link DossierItem.Kind#DIRECT_EDGE}
 * @param datalogProofWeight weight for {@link DossierItem.Kind#DATALOG_PROOF}
 * @param pslWeight         weight for {@link DossierItem.Kind#PSL}
 * @param minedRuleWeight   weight for {@link DossierItem.Kind#MINED_RULE}
 * @param pathWeight        weight for {@link DossierItem.Kind#PATH}
 * @param kgeWeight         weight for {@link DossierItem.Kind#KGE}
 */
public record FusionWeights(
        double directEdgeWeight,
        double datalogProofWeight,
        double pslWeight,
        double minedRuleWeight,
        double pathWeight,
        double kgeWeight) {

    /** Default weights. */
    public static FusionWeights defaults() {
        return new FusionWeights(1.0, 1.0, 0.8, 0.7, 0.5, 0.4);
    }

    /**
     * Look up the weight for a given {@link DossierItem.Kind}.
     * Refuting-only kinds (FUNCTIONAL_CONFLICT, NEGATED_ATOM) use the Datalog weight
     * as they represent verified KB state.
     */
    public double weightFor(DossierItem.Kind kind) {
        return switch (kind) {
            case DIRECT_EDGE        -> directEdgeWeight;
            case DATALOG_PROOF      -> datalogProofWeight;
            case PSL                -> pslWeight;
            case MINED_RULE         -> minedRuleWeight;
            case PATH               -> pathWeight;
            case KGE                -> kgeWeight;
            case FUNCTIONAL_CONFLICT -> datalogProofWeight;
            case NEGATED_ATOM       -> datalogProofWeight;
        };
    }
}
