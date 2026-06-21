/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import ai.kompile.graph.reasoning.explain.ReasoningTrail;

import java.time.Instant;
import java.util.List;

/**
 * Response body for POST /api/explain.
 *
 * <p>Carries the full {@link ReasoningTrail} plus a flat summary for quick consumption
 * by the frontend without traversing the nested trail structure.</p>
 *
 * @param targetId               the target that was explained
 * @param inferenceMode          GROUNDING | HYBRID | CAUSAL
 * @param verdict                SUPPORTED | REFUTED | UNKNOWN (from grounding) or null (hybrid)
 * @param confidence             blended confidence in [0,1]
 * @param naturalLanguageSummary deterministic NL summary
 * @param derivationTreeJson     JSON of the DerivationTree (null when no tree was produced)
 * @param evidence               flat evidence list for quick overlay rendering
 * @param activatedRules         rules that fired
 * @param computedAt             server timestamp
 * @param trail                  the full ReasoningTrail record (all fields)
 */
public record ExplainResponse(
        String targetId,
        String inferenceMode,
        String verdict,
        double confidence,
        String naturalLanguageSummary,
        String derivationTreeJson,
        List<String> evidence,
        List<String> activatedRules,
        Instant computedAt,
        ReasoningTrail trail
) {
    /** Construct a response directly from a ReasoningTrail, flattening key fields. */
    public static ExplainResponse fromTrail(ReasoningTrail trail) {
        String derivationJson = trail.hasDerivationTree()
                ? trail.derivationTree().toJson()
                : null;
        return new ExplainResponse(
                trail.targetId(),
                trail.inferenceMode(),
                null,              // verdict not applicable for all modes
                trail.confidence(),
                trail.naturalLanguageSummary(),
                derivationJson,
                trail.evidence(),
                trail.activatedRules(),
                trail.computedAt(),
                trail
        );
    }

    /** Construct with an explicit GROUNDING verdict. */
    public static ExplainResponse fromTrailWithVerdict(ReasoningTrail trail, String verdict) {
        String derivationJson = trail.hasDerivationTree()
                ? trail.derivationTree().toJson()
                : null;
        return new ExplainResponse(
                trail.targetId(),
                trail.inferenceMode(),
                verdict,
                trail.confidence(),
                trail.naturalLanguageSummary(),
                derivationJson,
                trail.evidence(),
                trail.activatedRules(),
                trail.computedAt(),
                trail
        );
    }
}
