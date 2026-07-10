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
package ai.kompile.graph.reasoning.synthesis;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SignalGroup;

import java.util.List;

/**
 * WP12b (pure core) — maps raw signals from the real stores into calibrated {@link CandidateSignal}s
 * for {@link AnswerSynthesizer}, encoding the ω-semantics of Part I §1. Kept infra-free + static so the
 * mapping is unit-testable; the kg {@code AnswerSynthesisService} gathers the raw values (retrieval
 * scores, ontology type checks, verify verdicts, MEBN posteriors, conflict masses) and calls these.
 */
public final class CandidateSignalMapper {

    private CandidateSignalMapper() {
    }

    /**
     * ω_text — a retrieval similarity score (already clamped to [0,1]; anti-correlation = 0) becomes a
     * RETRIEVAL signal. Uncertainty is driven by the top1−top2 score margin: a clear winner is
     * low-uncertainty, a flat distribution is high-uncertainty.
     */
    public static CandidateSignal textSignal(double similarity, double top1MinusTop2Margin) {
        return CandidateSignal.of(SignalGroup.RETRIEVAL, "text",
                Opinion.fromEmbeddingScore(clamp01(similarity), uncertaintyFromMargin(top1MinusTop2Margin)));
    }

    /** ω_kge — a calibrated KGE link-prediction score (WP2c/WP6a) → RETRIEVAL signal. */
    public static CandidateSignal kgeSignal(double calibratedScore, double uncertainty) {
        return CandidateSignal.of(SignalGroup.RETRIEVAL, "kge",
                Opinion.fromEmbeddingScore(clamp01(calibratedScore), clamp01(uncertainty)));
    }

    /**
     * ω_type — the candidate's entity type vs the bound ontology: in {@code allowedEntityTypes} →
     * near-certain (STRUCTURAL); violates a DOMAIN/RANGE axiom or a disjointness → complement-dominated;
     * unbound ontology (or type simply absent from the schema) → vacuous (no constraint).
     */
    public static CandidateSignal typeSignal(boolean ontologyBound, boolean typeAllowed, boolean typeViolates) {
        Opinion o;
        if (!ontologyBound) {
            o = Opinion.vacuous(0.5);
        } else if (typeViolates) {
            o = new Opinion(0.05, 0.90, 0.05, 0.5); // complement-dominated: the type is wrong
        } else if (typeAllowed) {
            o = new Opinion(0.90, 0.02, 0.08, 0.5); // near-certain: type conforms to the schema
        } else {
            o = Opinion.vacuous(0.5);               // not in schema but not a violation → unknown
        }
        return new CandidateSignal(SignalGroup.TYPE, "type", o, "STRUCTURAL", List.of());
    }

    /**
     * ω_psl / engine — a KB verify verdict → ENGINE signal. SUPPORTED → belief ∝ confidence;
     * REFUTED → disbelief ∝ confidence; UNKNOWN → vacuous (no evidence, NOT disbelief).
     */
    public static CandidateSignal engineSignal(String label, VerifyResult.Status status, double confidence) {
        Opinion o = switch (status) {
            case SUPPORTED -> Opinion.fromSoftTruth(clamp01(confidence), 10);
            case REFUTED -> Opinion.fromSoftTruth(clamp01(confidence), 10).complement();
            case UNKNOWN -> Opinion.vacuous(0.5);
        };
        return CandidateSignal.of(SignalGroup.ENGINE, label, o);
    }

    /** ω_mebn — a Bayesian posterior in [0,1] → ENGINE signal (consensus-fused with ω_psl). */
    public static CandidateSignal mebnSignal(double posterior) {
        return CandidateSignal.of(SignalGroup.ENGINE, "mebn",
                Opinion.fromBayesianPosterior(clamp01(posterior), 0.5));
    }

    /**
     * ω_cons — consistency = complement of the maximum same-proposition conflict mass among the
     * candidate's supporting facts. High conflict → low consistency belief.
     */
    public static CandidateSignal consistencySignal(double maxConflictMass) {
        return CandidateSignal.of(SignalGroup.CONSISTENCY, "consistency",
                Opinion.fromSoftTruth(clamp01(1.0 - clamp01(maxConflictMass)), 5));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static double uncertaintyFromMargin(double margin) {
        // clear winner (large margin) → low u; flat distribution (margin ≈ 0) → high u. Bounded.
        return clamp(0.9 - 0.85 * clamp01(margin), 0.05, 0.9);
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
