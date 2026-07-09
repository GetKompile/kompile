/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.Step;
import ai.kompile.graph.reasoning.explain.ReasoningTrace.StepKind;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapts a {@link HlMrfMapInference.Result} for one target atom into a
 * unified {@link ReasoningTrace} (the "PSL MAP → KKT trace" recipe from
 * Bach et al. §4–5 and Kouki et al. TIIS 2020).
 *
 * <h3>Trace shape</h3>
 * <pre>
 * INFERENCE  "targetAtom"  "psl-map value=0.72"  confidence=0.72
 *   RULE     "rule display"  "w=2.0 d=0.05 dir=up pot=0.10"  confidence=0.95
 *     FACT   "observedAtom1"  "observed"  confidence=0.90  source=sourceId
 *     FACT   "observedAtom2"  "observed"  confidence=1.00  source=...
 *   RULE     ...
 * </pre>
 *
 * <ul>
 *   <li>The root is a {@link StepKind#INFERENCE} step for the target atom.</li>
 *   <li>Each premise is a {@link StepKind#RULE} step for one {@link HlMrfMapInference.AtomAttribution},
 *       sorted by weighted potential (strongest first), capped at {@link #DEFAULT_MAX_PREMISES}.</li>
 *   <li>Each rule step's leaves are {@link StepKind#FACT} steps for the rule's observed body atoms,
 *       with the {@link Fact#sourceId()} as provenance when a {@link FactStore} is provided.</li>
 * </ul>
 */
public final class PslTraceAdapter {

    /** Maximum number of rule premises under the root inference step (strongest first). */
    public static final int DEFAULT_MAX_PREMISES = 12;

    private PslTraceAdapter() {}

    /**
     * Build a {@link ReasoningTrace} for {@code targetAtom} from the MAP result.
     * Leaf FACT steps will not carry a source identifier (no FactStore).
     *
     * @param result     the MAP inference result
     * @param targetAtom the atom key to explain (e.g. {@code "isActive(alice)"})
     * @param hardWeight the penalty weight used for hard constraints
     * @return the trace rooted at the target atom's INFERENCE step
     */
    public static ReasoningTrace toTrace(HlMrfMapInference.Result result,
                                         String targetAtom,
                                         double hardWeight) {
        return toTrace(result, targetAtom, hardWeight, null, DEFAULT_MAX_PREMISES);
    }

    /**
     * Build a {@link ReasoningTrace} for {@code targetAtom} with FactStore-backed leaf provenance.
     *
     * <p>When {@code factStore} is non-null, each observed body atom that matches a {@link Fact} in
     * the store carries the {@link Fact#sourceId()} as the leaf step's source.  Atoms not in the
     * store are still emitted as FACT leaves (with a null source).</p>
     *
     * @param result     the MAP inference result
     * @param targetAtom the atom key to explain
     * @param hardWeight the penalty weight used for hard constraints
     * @param factStore  optional store used to resolve leaf-step source identifiers; may be null
     * @return the trace rooted at the target atom's INFERENCE step
     */
    public static ReasoningTrace toTrace(HlMrfMapInference.Result result,
                                         String targetAtom,
                                         double hardWeight,
                                         FactStore factStore) {
        return toTrace(result, targetAtom, hardWeight, factStore, DEFAULT_MAX_PREMISES);
    }

    /**
     * Full-parameter variant: same as the 4-arg overload but with an explicit cap on the number of
     * rule premises (strongest-by-potential first).
     *
     * @param result      the MAP inference result
     * @param targetAtom  the atom key to explain
     * @param hardWeight  the penalty weight used for hard constraints
     * @param factStore   optional FactStore for leaf provenance; may be null
     * @param maxPremises maximum number of rule steps under the root (≥ 1)
     * @return the trace rooted at the target atom's INFERENCE step
     */
    public static ReasoningTrace toTrace(HlMrfMapInference.Result result,
                                         String targetAtom,
                                         double hardWeight,
                                         FactStore factStore,
                                         int maxPremises) {
        double rawValue = result.values().getOrDefault(targetAtom, 0.0);
        double clampedValue = clamp01(rawValue);

        // Retrieve attributions (already sorted by weightedPotential desc)
        List<HlMrfMapInference.AtomAttribution> attributions =
                result.atomAttribution(targetAtom, hardWeight);

        // Cap to maxPremises (take the strongest)
        int cap = Math.max(1, maxPremises);
        List<HlMrfMapInference.AtomAttribution> top =
                attributions.size() > cap ? attributions.subList(0, cap) : attributions;

        // Build rule premise steps
        List<Step> rulePremises = new ArrayList<>(top.size());
        for (HlMrfMapInference.AtomAttribution attr : top) {
            Step ruleStep = buildRuleStep(attr, factStore, result.values());
            rulePremises.add(ruleStep);
        }

        // Root: INFERENCE step for the target atom
        String rootOp = String.format(Locale.ROOT, "psl-map value=%.4f", clampedValue);
        Step root = Step.derived(StepKind.INFERENCE, targetAtom, rootOp, clampedValue, rulePremises);

        return ReasoningTrace.of(root);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a {@link StepKind#RULE} step for one attribution, with FACT leaves for observed body
     * atoms.
     *
     * <p>A rule with an empty body (e.g. a soft prior {@code "0.5: Trust(A,B)"}) would produce a
     * RULE step with no children, making it a leaf — which violates the invariant that all leaves
     * are FACT steps.  For such empty-body rules the trace step is instead emitted as a
     * {@link StepKind#FACT} step with a {@code "prior-rule"} source annotation, so every leaf in
     * the trace is a FACT step and {@link ai.kompile.graph.reasoning.explain.ReasoningTrace#leaves()}
     * returns only FACT steps as documented.</p>
     */
    private static Step buildRuleStep(HlMrfMapInference.AtomAttribution attr,
                                      FactStore factStore,
                                      java.util.Map<String, Double> values) {
        GroundRule gr = attr.rule();
        double d = attr.distanceToSatisfaction();
        double pot = attr.weightedPotential();
        double w = gr.hard() ? Double.POSITIVE_INFINITY : gr.weight();
        String dirStr = attr.direction() > 0 ? "up" : (attr.direction() < 0 ? "down" : "neutral");

        String ruleOp;
        if (gr.hard()) {
            ruleOp = String.format(Locale.ROOT,
                    "w=hard d=%.4f dir=%s pot=%.4f dual=%.4f",
                    d, dirStr, pot, attr.dualForce());
        } else {
            ruleOp = String.format(Locale.ROOT,
                    "w=%.4f d=%.4f dir=%s pot=%.4f dual=%.4f",
                    w, d, dirStr, pot, attr.dualForce());
        }

        // Confidence for the rule step: 1 - min(1, d), clamped to [0,1]
        double ruleConfidence = clamp01(1.0 - Math.min(1.0, d));

        // Build FACT leaves: observed body atoms (positive, non-negated)
        List<Step> leaves = new ArrayList<>();
        for (GroundRule.Lit bodyLit : gr.body()) {
            if (bodyLit.negated()) continue; // skip negated body literals
            String atomKey = bodyLit.atomKey();
            double atomValue = values.getOrDefault(atomKey, 0.0);
            double leafConf = clamp01(atomValue);

            String source = null;
            if (factStore != null) {
                source = factStore.factFor(atomKey)
                        .map(Fact::sourceId)
                        .orElse(null);
            }

            leaves.add(Step.fact(atomKey, leafConf, source));
        }

        // If no FACT leaves were collected (e.g. an empty-body soft-prior rule like "0.5: Trust(A,B)"),
        // add a synthetic FACT leaf representing the prior rather than leaving the RULE step as a
        // childless leaf.  A leaf-less RULE step violates the invariant that all trace leaves are
        // FACT steps; adding a FACT child makes this RULE step a non-leaf while all leaves remain FACT.
        if (leaves.isEmpty()) {
            // The rule itself is the "evidence": its prior weight is the confidence.
            leaves.add(Step.fact(gr.display(), ruleConfidence, "prior-rule"));
        }

        return Step.derived(StepKind.RULE, gr.display(), ruleOp, ruleConfidence, leaves);
    }

    /** Clamp a possibly-NaN / out-of-range value into {@code [0, 1]}. */
    private static double clamp01(double x) {
        return Double.isNaN(x) ? 0.0 : Math.max(0.0, Math.min(1.0, x));
    }
}
