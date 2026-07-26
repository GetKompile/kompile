/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;

import java.util.List;

/**
 * Response body for POST /api/kb-grounding/claim (P1-5 claim dossier endpoint).
 *
 * <p>Aggregates evidence from five channels — direct graph connection, verified facts,
 * connecting paths, link plausibility (KGE), and learned rules — into a single fused verdict
 * and per-signal breakdown.</p>
 *
 * @param claimAtom    canonical atom key string, e.g. {@code "WORKSFOR(alice, acme)"}
 * @param subject      subject entity id
 * @param predicate    predicate / relation type
 * @param object       object entity id
 * @param verdict      {@code "SUPPORTED"} (fusedConfidence ≥ 0.65),
 *                     {@code "REFUTED"} (≤ 0.35), or {@code "UNCERTAIN"}
 * @param fusedScore   log-odds fused probability in [0,1]
 * @param supporting   evidence signals that support the claim
 * @param refuting     evidence signals that refute or challenge the claim
 * @param trace        full reasoning trace (walkable; carries all signal steps)
 * @param meta         standard grounding meta block (factSheetId, stale flag, version)
 */
public record ClaimResponse(
        String claimAtom,
        String subject,
        String predicate,
        String object,
        String verdict,
        double fusedScore,
        List<SignalItem> supporting,
        List<SignalItem> refuting,
        ReasoningTrace trace,
        GroundingMeta meta
) {

    /**
     * A single evidence signal with a plain-English channel label, description,
     * probability, and provenance keys.
     *
     * @param signal      plain-English channel name: one of "direct connection",
     *                    "verified facts", "connecting paths", "link plausibility",
     *                    "learned rules", "functional conflict", "negated fact"
     * @param description human-readable description of what was found
     * @param probability probability in [0,1] for this signal
     * @param provenance  source ids or node/edge keys backing this signal
     */
    public record SignalItem(
            String signal,
            String description,
            double probability,
            List<String> provenance
    ) {}
}
