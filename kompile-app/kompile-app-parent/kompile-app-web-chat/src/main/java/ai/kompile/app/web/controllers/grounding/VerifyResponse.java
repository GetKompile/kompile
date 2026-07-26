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

import java.util.List;

/**
 * Response body for POST /api/kb-grounding/verify.
 *
 * <p>New fields added in E4/E5/fixes #3,#4,#6:
 * <ul>
 *   <li>{@link #evidenceCount} — actual number of supporting evidence atoms (fix #3: was
 *       overloaded onto derivationDepth)</li>
 *   <li>{@link #derivationDepth} — real derivation depth from {@link
 *       ai.kompile.graph.reasoning.fol.grounding.DerivationTree#build} (fix #3)</li>
 *   <li>{@link #sourceProvenance} — actual {@code Fact.sourceId}s from the KB, deduplicated
 *       (fix #4); falls back to humanized evidence labels if no sourceIds resolve</li>
 *   <li>{@link #counterEvidence} — competing/negating atom descriptions; non-empty signals
 *       tension even when verdict is SUPPORTED</li>
 *   <li>{@link #refutationBasis} — why the atom is refuted or tensioned (E5), e.g.
 *       {@code "negated-atom"}, {@code "functional-conflict: worksAt(alice, otherCorp) @0.92"}</li>
 *   <li>{@link #opinion} — subjective-logic opinion {b, d, u, a} for UNKNOWN atoms (fix #6)</li>
 *   <li>{@link #openWorld} — true when the sparse-graph open-world assessor was consulted (fix #6)</li>
 *   <li>{@link #entityKnown} — true when the claim's subject/object resolve to a known graph node
 *       (fix #6)</li>
 *   <li>{@link #unknownReason} — {@code "entity-not-in-graph"} | {@code "no-evidence"} |
 *       {@code "contested"} | {@code "near-miss"} (fix #6 + E9)</li>
 *   <li>{@link #contradictions} — compact descriptions of detected KB contradictions sharing the
 *       claim's predicate or arguments (E4)</li>
 *   <li>{@link #nearMissSuggestions} — E9 why-not: ground atom keys that, if asserted, would make
 *       the claim provable via an existing rule; empty unless verdict is UNKNOWN and a near-miss
 *       was found</li>
 *   <li>{@link #fragility} — E12 counterfactual fragility; non-null only for SUPPORTED verdicts;
 *       carries the minimal support size, the "would-flip-if" set, and a robustness score [0,1]</li>
 *   <li>{@link #deepWhyNot} — P1-6 multi-hop why-not: ordered chains of missing base facts whose
 *       joint assertion would make the claim provable; null when the claim is SUPPORTED/REFUTED or
 *       when no rules are present or the rule/fact set exceeds the cost guard</li>
 * </ul>
 */
public record VerifyResponse(
        String verdict,
        double confidence,
        List<String> evidenceAtoms,
        List<String> activatedRules,
        /** Real derivation depth from the proof tree (fix #3). Was evidence-count approximation. */
        int derivationDepth,
        /** Actual supporting-fact sourceIds from the KB, deduplicated (fix #4). */
        List<String> sourceProvenance,
        /** Platt-scaled calibrated confidence in [0,1]. Equals {@code confidence} until calibration data is available. */
        double calibratedConfidence,
        /** Epistemic strength band: ESTABLISHED | HIGH | PROBABLE | SPECULATIVE | SUPPRESSED. */
        String strengthBand,
        GroundingMeta meta,
        /** Actual count of supporting evidence atoms (fix #3: was overloaded onto derivationDepth). */
        int evidenceCount,
        /** Competing or negating atom descriptions; non-empty signals tension (E4/E5). */
        List<String> counterEvidence,
        /** Human-readable refutation basis: "negated-atom", "hard-false-fact", "functional-conflict: ...". */
        String refutationBasis,
        /** Subjective-logic opinion {b,d,u,a} for UNKNOWN; null for SUPPORTED/REFUTED (fix #6). */
        OpinionDto opinion,
        /** True when the sparse-graph open-world assessor was consulted (fix #6). */
        boolean openWorld,
        /** True when the claim's subject/object resolve to a known graph node (fix #6). */
        boolean entityKnown,
        /**
         * Reason for UNKNOWN verdict:
         * {@code "entity-not-in-graph"} | {@code "no-evidence"} | {@code "contested"} | {@code "near-miss"} (E9).
         * {@code "near-miss"} is set when the entity is known and at least one completing fact was found.
         */
        String unknownReason,
        /** Compact contradiction descriptions sharing the claim's predicate/args (E4). */
        List<String> contradictions,
        /**
         * E9 why-not near-miss: ground atom keys that, if asserted into the KB, would make the
         * claim provable via an existing rule. Empty when verdict is SUPPORTED/REFUTED or when no
         * near-miss derivation was found. When non-empty, {@link #unknownReason} is {@code "near-miss"}.
         */
        List<String> nearMissSuggestions,
        /**
         * E12 counterfactual fragility analysis. Non-null only when verdict is SUPPORTED.
         * Carries the minimal support size, the "would-flip-if" base-fact keys, and a robustness
         * score in [0,1] (0=fully fragile, 1=redundantly supported).
         */
        FragilityDto fragility,
        /**
         * P1-6 deep (multi-hop) why-not analysis. Non-null only for UNKNOWN verdicts when rules
         * are available and the cost guard allows it. Each element is an ordered chain of missing
         * base facts whose joint assertion would make the claim provable.
         *
         * <p>Populated by {@link ai.kompile.graph.reasoning.fol.grounding.DeepWhyNot}
         * at depth {@code deepWhyNotMaxDepth} (configurable, default 2).</p>
         */
        DeepWhyNotDto deepWhyNot
) {

    /** Subjective-logic opinion tuple: belief, disbelief, uncertainty, base-rate. */
    public record OpinionDto(double b, double d, double u, double a) {}

    /**
     * E12 counterfactual fragility summary.
     *
     * @param wouldFlipIf          base-fact atom keys whose retraction would flip the SUPPORTED verdict
     * @param minimalSupportSize   total count of leaf base-facts the derivation rests on
     * @param robustness           heuristic in [0,1]: 0=every leaf is load-bearing (fully fragile),
     *                             1=no leaf is load-bearing (redundantly supported)
     */
    public record FragilityDto(
            List<String> wouldFlipIf,
            int minimalSupportSize,
            double robustness
    ) {}

    /**
     * P1-6 deep why-not result: multi-hop completion chains.
     *
     * @param completionSets   ordered list of completion chains; each chain is an ordered list of
     *                         missing base-fact atom keys whose joint assertion would make the claim
     *                         provable. Ranked by size (smallest first), then by recursion depth.
     * @param flatSuggestions  human-readable lines (bounded), one per completion hint; each line
     *                         describes which missing atoms are needed and their derivability status.
     * @param budgetExhausted  true when the node budget was hit before full exploration; suggestions
     *                         may be incomplete.
     */
    public record DeepWhyNotDto(
            List<List<String>> completionSets,
            List<String> flatSuggestions,
            boolean budgetExhausted
    ) {}
}
