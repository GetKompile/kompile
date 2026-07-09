/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.bayesian;

import java.util.*;

/**
 * Good's Weight of Evidence (WoE) decomposition for binary hypothesis variables.
 *
 * <h3>Definition</h3>
 * <p>For a binary hypothesis H ∈ {h, ¬h} and a set of evidence findings
 * E = {e₁, e₂, …, eₙ}, the weight of finding eᵢ for hypothesis H=h is:
 * <pre>
 *   W(H=h : eᵢ) = ln[ P(eᵢ | H=h, E∖{eᵢ}) / P(eᵢ | H=¬h, E∖{eᵢ}) ]
 * </pre>
 * where eᵢ denotes the observed state of the i-th finding variable (not the
 * variable itself), and the denominator conditions on the <em>complement</em>
 * state of H (the only other state, since H must be binary).
 * </p>
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>W &gt; 0 — finding supports H=h (the observed eᵢ is more likely when H=h)</li>
 *   <li>W &lt; 0 — finding refutes H=h (the observed eᵢ is more likely when H=¬h)</li>
 *   <li>W = 0 — finding is neutral</li>
 * </ul>
 * The weights are additive in the log-odds space:
 * {@code log-odds(H=h | E) ≈ log-odds(H=h) + Σ Wᵢ}.
 *
 * <h3>Binary-H restriction</h3>
 * <p>This implementation only supports binary hypothesis variables (cardinality 2).
 * For a non-binary H the leave-one-out WoE formula requires specifying <em>which</em>
 * complement state to condition on (the denominator P(eᵢ|H=¬h,…) is ambiguous with
 * ≥ 3 states). Pass a binary-state node or reduce H to binary form before calling
 * this method.  A clear {@link IllegalArgumentException} is thrown for non-binary H.</p>
 *
 * <h3>Zero-probability guard</h3>
 * <p>Probabilities in the ratio are floored at {@link #PROB_FLOOR} (1e-9) before
 * taking the logarithm to avoid −∞ weights when a state is structurally impossible.
 * Results near the floor should be treated as "very strong evidence" rather than
 * exact values.</p>
 *
 * @see <a href="https://doi.org/10.1093/biomet/72.2.359">Good 1985, Weight of Evidence</a>
 */
public final class WeightOfEvidence {

    /** Probability floor applied before ln() to guard against log(0). */
    public static final double PROB_FLOOR = 1e-9;

    private WeightOfEvidence() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Per-finding weight-of-evidence record.
     *
     * @param findingVar      the evidence variable name
     * @param findingState    the observed state index of that variable (0=FALSE, 1=TRUE for binary)
     * @param weightNats      W(H=h : eᵢ) in nats (natural log); positive → supports H
     * @param posteriorWithout P(H=hypothesisState | E∖{eᵢ}) — posterior without this finding
     * @param posteriorWith   P(H=hypothesisState | E) — posterior with all evidence including eᵢ
     */
    public record FindingWeight(
            String findingVar,
            int    findingState,
            double weightNats,
            double posteriorWithout,
            double posteriorWith) {}

    /**
     * Decompose the evidence into per-finding weights of evidence for a binary
     * hypothesis variable.
     *
     * <p>The decomposition uses <em>leave-one-out</em> VE queries:
     * for each evidence finding eᵢ two queries are run:
     * <ol>
     *   <li>Query P(eᵢ | H=h,   E∖{eᵢ}) — clamp H to hypothesisState, remove eᵢ from evidence</li>
     *   <li>Query P(eᵢ | H=¬h,  E∖{eᵢ}) — clamp H to complementState (the only other state)</li>
     * </ol>
     * The weight is ln(q1 / q2), floored at {@link #PROB_FLOOR} in both numerator and denominator.
     * A third query P(H=h | E∖{eᵢ}) gives the posterior shift.
     * </p>
     *
     * <p>Evidence variables not present in the network are silently skipped.</p>
     *
     * @param net             the Bayesian network
     * @param hypothesisVar   name of the binary hypothesis variable (must have cardinality 2)
     * @param hypothesisState the state index for H=h (typically 1 = TRUE)
     * @param evidence        map of observed variable → observed state index (0=FALSE, 1=TRUE)
     * @return list of FindingWeight records sorted by |weightNats| descending
     * @throws IllegalArgumentException if hypothesisVar is unknown or not binary
     * @throws IllegalArgumentException if hypothesisVar appears in evidence (already observed)
     */
    public static List<FindingWeight> decompose(BayesianNetwork net,
                                                 String hypothesisVar,
                                                 int hypothesisState,
                                                 Map<String, Integer> evidence) {
        // ── Validate hypothesis variable ─────────────────────────────────────
        BayesianNode hNode = net.getNode(hypothesisVar);
        if (hNode == null) {
            throw new IllegalArgumentException(
                    "Unknown hypothesis variable: '" + hypothesisVar + "'");
        }
        if (hNode.getCardinality() != 2) {
            throw new IllegalArgumentException(
                    "WeightOfEvidence requires a binary hypothesis variable (cardinality = 2). " +
                    "Variable '" + hypothesisVar + "' has cardinality " + hNode.getCardinality() +
                    ". Reduce to binary form before calling this method.");
        }
        if (evidence.containsKey(hypothesisVar)) {
            throw new IllegalArgumentException(
                    "Hypothesis variable '" + hypothesisVar + "' is already observed (it appears in evidence). " +
                    "WeightOfEvidence requires the hypothesis to be a latent (non-observed) variable.");
        }

        // The complement state for binary H
        int complementState = 1 - hypothesisState;

        // Full-evidence posterior P(H=h | E) — used as posteriorWith for all findings
        Factor fullPosteriorFactor = VariableElimination.query(net, hypothesisVar, evidence);
        double posteriorWithFull = extractProb(fullPosteriorFactor, hypothesisState);

        List<FindingWeight> weights = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : evidence.entrySet()) {
            String findingVar = entry.getKey();
            int findingState  = entry.getValue();

            // Skip if this evidence variable doesn't exist in the network
            if (net.getNode(findingVar) == null) {
                continue;
            }

            // Evidence without eᵢ
            Map<String, Integer> evidenceMinus = new LinkedHashMap<>(evidence);
            evidenceMinus.remove(findingVar);

            // Clamp H=h and query P(eᵢ | H=h, E∖{eᵢ})
            Map<String, Integer> evidenceWithH = new LinkedHashMap<>(evidenceMinus);
            evidenceWithH.put(hypothesisVar, hypothesisState);
            double pEiGivenH = queryFindingProb(net, findingVar, findingState, evidenceWithH);

            // Clamp H=¬h and query P(eᵢ | H=¬h, E∖{eᵢ})
            Map<String, Integer> evidenceWithNotH = new LinkedHashMap<>(evidenceMinus);
            evidenceWithNotH.put(hypothesisVar, complementState);
            double pEiGivenNotH = queryFindingProb(net, findingVar, findingState, evidenceWithNotH);

            // W(H=h : eᵢ) = ln[ P(eᵢ|H=h,E∖{eᵢ}) / P(eᵢ|H=¬h,E∖{eᵢ}) ]
            double numerator   = Math.max(pEiGivenH,    PROB_FLOOR);
            double denominator = Math.max(pEiGivenNotH, PROB_FLOOR);
            double weightNats  = Math.log(numerator / denominator);

            // Posterior without eᵢ: P(H=h | E∖{eᵢ})
            Factor withoutFactor = VariableElimination.query(net, hypothesisVar, evidenceMinus);
            double posteriorWithout = extractProb(withoutFactor, hypothesisState);

            weights.add(new FindingWeight(findingVar, findingState, weightNats,
                    posteriorWithout, posteriorWithFull));
        }

        // Sort by |weightNats| descending — most influential findings first
        weights.sort(Comparator.comparingDouble((FindingWeight fw) -> Math.abs(fw.weightNats())).reversed());
        return Collections.unmodifiableList(weights);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run a VE query for the probability of a specific variable state given the provided evidence.
     * Returns the probability of the given {@code state} index (0-based), floored at 0.
     */
    private static double queryFindingProb(BayesianNetwork net,
                                            String var,
                                            int state,
                                            Map<String, Integer> evidence) {
        // If the variable itself is clamped by evidence, return its deterministic probability
        if (evidence.containsKey(var)) {
            return evidence.get(var) == state ? 1.0 : 0.0;
        }
        try {
            Factor f = VariableElimination.query(net, var, evidence);
            f = f.normalize();
            double[] vals = f.getValues();
            if (state < 0 || state >= vals.length) {
                return PROB_FLOOR;
            }
            return Math.max(vals[state], 0.0);
        } catch (Exception e) {
            // Network may not have enough CPTs for this clamped sub-query
            return PROB_FLOOR;
        }
    }

    /**
     * Extract the probability of a given state from a normalized single-variable factor.
     */
    private static double extractProb(Factor f, int state) {
        Factor norm = f.normalize();
        double[] vals = norm.getValues();
        if (state < 0 || state >= vals.length) {
            return 0.0;
        }
        return vals[state];
    }
}
