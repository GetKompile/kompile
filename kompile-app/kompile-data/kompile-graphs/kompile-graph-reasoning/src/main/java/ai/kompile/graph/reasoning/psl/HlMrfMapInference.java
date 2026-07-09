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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point and router for HL-MRF MAP (most-probable-explanation) inference — the
 * hand-rolled analogue of PSL's {@code MPEInference}.
 *
 * <p>It minimizes the total weighted energy {@code E(y) = Σ_r w_r · d_r(y)^{p_r}} over the
 * target atom values {@code y ∈ [0,1]}, where {@code d_r} is each ground rule's
 * {@link GroundRule#distanceToSatisfaction distance to satisfaction} and {@code p_r ∈ {1,2}}.
 * The energy is convex (a sum of hinges over affine functions with non-negative weights), so
 * MAP is solved exactly by projected gradient descent. Atom truth values are continuous
 * {@code [0,1]} soft-truth — the defining difference from the discrete Bayesian-network path
 * in the sibling {@code algorithm.bayesian} package.</p>
 *
 * <p>This class grounds the program once and dispatches to an {@link HlMrfSolver} chosen by
 * problem size: the plain-Java {@link ScalarHlMrfInference} for the small subgraphs that
 * dominate in practice, and the ND4J-vectorized {@link TensorHlMrfInference} (GPU-capable)
 * once the program is large enough for matrix operations to pay off — provided an ND4J
 * backend is available, otherwise it falls back to the scalar solver.</p>
 */
public final class HlMrfMapInference {

    private HlMrfMapInference() {}

    public static final int DEFAULT_MAX_ITERATIONS = 2000;
    public static final double DEFAULT_TOLERANCE = 1e-6;
    public static final double DEFAULT_HARD_WEIGHT = 1.0e6;

    /**
     * Route to the ND4J {@link TensorHlMrfInference} backend at or above this many ground
     * rules (when a backend is available). Below it, the scalar solver is faster because it
     * avoids per-iteration op-dispatch and host/device transfer overhead. The default KG
     * subgraphs (maxNodes ≈ 100) sit well under this, so they stay on the scalar path.
     */
    public static final int DEFAULT_TENSOR_THRESHOLD = 4000;

    /**
     * Above this many dense incidence-matrix cells ({@code groundRules × atoms}) the dense
     * {@link TensorHlMrfInference} would use too much memory, so routing prefers the sparse
     * {@link SgdHlMrfInference} instead (≈ 50M floats ≈ 200 MB).
     */
    public static final long DEFAULT_DENSE_CELL_BUDGET = 50_000_000L;

    /**
     * Tolerance below which a hard constraint's distance-to-satisfaction counts as a violation
     * in {@link Result#hardViolations}.
     */
    public static final double HARD_VIOLATION_TOLERANCE = 1e-4;

    /**
     * Per-atom attribution of a ground rule's influence on one target atom.
     *
     * <p>Produced by {@link Result#atomAttribution(String, double)} and used by
     * {@code PslTraceAdapter} to build a walkable {@code ReasoningTrace}. The
     * {@code direction} field encodes the KKT "force" direction:
     * <ul>
     *   <li>{@code +1} — the rule pushes the atom's value <em>up</em> (atom appears
     *       in a positive head literal: satisfying the rule requires the atom to be
     *       truthy).</li>
     *   <li>{@code -1} — the rule pushes the atom's value <em>down</em> (atom appears
     *       in a positive body literal: the rule "consumes" this atom's truth to
     *       justify something else, pulling it toward its current value via the body
     *       conjunction).</li>
     *   <li>{@code 0} — the atom only appears in negated literals (direction is
     *       ambiguous / rule is marginal).</li>
     * </ul>
     *
     * @param rule                  the ground rule containing the target atom
     * @param distanceToSatisfaction Łukasiewicz distance d ∈ [0, 1]
     * @param weightedPotential     w · d^p; the atom's contribution to the total energy
     * @param satisfied             {@code true} when d ≤ {@link #HARD_VIOLATION_TOLERANCE}
     * @param direction             +1 pushes atom up, -1 pushes atom down, 0 neutral
     * @param dualForce             ADMM scaled dual magnitude at convergence for this rule/atom
     *                              pair; 0.0 when the solver did not surface duals
     */
    public record AtomAttribution(
            GroundRule rule,
            double distanceToSatisfaction,
            double weightedPotential,
            boolean satisfied,
            int direction,
            double dualForce) {}

    /**
     * Inference outcome.
     *
     * @param values      final truth assignment (observed atoms unchanged, targets optimized)
     * @param groundRules the ground rules that were optimized (for explainability)
     * @param iterations  number of descent iterations performed
     * @param objective   final total energy
     * @param converged   whether the descent reached the tolerance before the iteration cap
     * @param admmDuals   optional per-(ruleIndex, atomKey) scaled dual magnitudes at ADMM
     *                    convergence; empty map when not populated by the solver
     */
    public record Result(Map<String, Double> values, List<GroundRule> groundRules,
                         int iterations, double objective, boolean converged,
                         Map<Integer, Map<String, Double>> admmDuals) {

        /**
         * Back-compat constructor: creates a Result with an empty duals map.
         * All existing call sites use this form.
         */
        public Result(Map<String, Double> values, List<GroundRule> groundRules,
                      int iterations, double objective, boolean converged) {
            this(values, groundRules, iterations, objective, converged, Map.of());
        }

        public Result {
            admmDuals = (admmDuals == null) ? Map.of() : Map.copyOf(admmDuals);
        }

        /**
         * Ground rules that are marked hard and have a distance-to-satisfaction above
         * {@link #HARD_VIOLATION_TOLERANCE} at the final atom assignment.
         */
        public List<GroundRule> hardViolations() {
            List<GroundRule> violations = new ArrayList<>();
            for (GroundRule gr : groundRules) {
                if (gr.hard() && gr.distanceToSatisfaction(values) > HARD_VIOLATION_TOLERANCE) {
                    violations.add(gr);
                }
            }
            return violations;
        }

        /**
         * Per-ground-rule satisfaction detail at the MAP solution.
         *
         * <p>For every logical ground rule in {@link #groundRules} this computes the
         * Łukasiewicz {@link GroundRule#distanceToSatisfaction distance to satisfaction}
         * and its weighted energy contribution {@link GroundRule#potential potential} at the
         * final atom assignment {@link #values}. The results are ordered identically to
         * {@link #groundRules}.</p>
         *
         * <p>This is the primary hook for PSL "why" explanations: a downstream reasoning
         * trail can rank rules by {@link GroundRuleResult#distanceToSatisfaction()} to surface
         * the most violated constraints, or filter by {@link GroundRuleResult#satisfied()} to
         * identify exactly which constraints hold at the MAP solution.</p>
         *
         * <p>The computation is purely post-hoc — it reads the final {@link #values} map
         * and calls the existing {@link GroundRule} methods; no solver state is consulted.</p>
         *
         * @param hardWeight the penalty weight for hard constraints, used to compute
         *                   {@link GroundRuleResult#weightedPotential()}; pass
         *                   {@link HlMrfMapInference#DEFAULT_HARD_WEIGHT} if unsure
         * @return immutable list of per-rule results, one per entry in {@link #groundRules}
         */
        public List<GroundRuleResult> groundRuleResults(double hardWeight) {
            List<GroundRuleResult> results = new ArrayList<>(groundRules.size());
            for (GroundRule gr : groundRules) {
                double d = gr.distanceToSatisfaction(values);
                double pot = gr.potential(values, hardWeight);
                boolean satisfied = d <= HARD_VIOLATION_TOLERANCE;
                results.add(new GroundRuleResult(gr, d, pot, satisfied));
            }
            return results;
        }

        /**
         * Convenience overload that uses {@link HlMrfMapInference#DEFAULT_HARD_WEIGHT} for
         * the hard-constraint penalty when computing {@link GroundRuleResult#weightedPotential()}.
         *
         * @return immutable list of per-rule results
         * @see #groundRuleResults(double)
         */
        public List<GroundRuleResult> groundRuleResults() {
            return groundRuleResults(DEFAULT_HARD_WEIGHT);
        }

        /**
         * Reverse-index lookup: all ground rules in {@link #groundRules} that reference
         * {@code atomKey} in either their head or body (positive or negated literal).
         *
         * <p>The index is built lazily on the first call and is not cached across calls — the
         * Result is a record (immutable by contract) so callers that need repeated lookups
         * should cache the returned lists themselves.</p>
         *
         * @param atomKey the ground atom key to look up (e.g. {@code "State(alice)"})
         * @return unmodifiable list of ground rules that reference the atom; never null
         */
        public List<GroundRule> groundRulesFor(String atomKey) {
            if (atomKey == null) return List.of();
            List<GroundRule> found = new ArrayList<>();
            for (GroundRule gr : groundRules) {
                if (ruleContainsAtom(gr, atomKey)) {
                    found.add(gr);
                }
            }
            return Collections.unmodifiableList(found);
        }

        /**
         * Per-atom attribution for {@code atomKey}: one {@link AtomAttribution} per ground rule
         * that references the atom, sorted by {@link AtomAttribution#weightedPotential} descending
         * (strongest influencer first).
         *
         * <p>The {@link AtomAttribution#direction} encodes whether the rule pushes the atom up
         * (+1, head-positive literal) or down (-1, body-positive literal). Negated-only appearances
         * yield 0. When the atom appears in both head and body the head role wins (net +1).</p>
         *
         * <p>The {@link AtomAttribution#dualForce} is the ADMM scaled-dual magnitude
         * {@code |u_{r,j}|} at convergence, supplied by the ADMM solver via {@link #admmDuals};
         * it is 0.0 for all other solvers.</p>
         *
         * @param atomKey    the ground atom key to explain
         * @param hardWeight penalty used to compute weighted potential (use
         *                   {@link HlMrfMapInference#DEFAULT_HARD_WEIGHT} if unsure)
         * @return list of attributions sorted by weightedPotential desc; never null
         */
        public List<AtomAttribution> atomAttribution(String atomKey, double hardWeight) {
            if (atomKey == null) return List.of();
            List<GroundRule> rules = groundRulesFor(atomKey);
            if (rules.isEmpty()) return List.of();

            List<AtomAttribution> attribs = new ArrayList<>(rules.size());
            for (int ri = 0; ri < groundRules.size(); ri++) {
                GroundRule gr = groundRules.get(ri);
                if (!ruleContainsAtom(gr, atomKey)) continue;

                double d = gr.distanceToSatisfaction(values);
                double pot = gr.potential(values, hardWeight);
                boolean satisfied = d <= HARD_VIOLATION_TOLERANCE;

                // direction: head-positive wins over body-positive
                int direction = computeDirection(gr, atomKey);

                // dualForce: |u_{r,j}| from ADMM duals map
                double dualForce = 0.0;
                Map<String, Double> ruleDuals = admmDuals.get(ri);
                if (ruleDuals != null) {
                    Double raw = ruleDuals.get(atomKey);
                    if (raw != null) dualForce = Math.abs(raw);
                }

                attribs.add(new AtomAttribution(gr, d, pot, satisfied, direction, dualForce));
            }

            attribs.sort(Comparator.comparingDouble(AtomAttribution::weightedPotential).reversed());
            return Collections.unmodifiableList(attribs);
        }

        // ── Helpers ──────────────────────────────────────────────────────────────

        private static boolean ruleContainsAtom(GroundRule gr, String atomKey) {
            for (GroundRule.Lit l : gr.head()) {
                if (atomKey.equals(l.atomKey())) return true;
            }
            for (GroundRule.Lit l : gr.body()) {
                if (atomKey.equals(l.atomKey())) return true;
            }
            return false;
        }

        /**
         * Compute direction of the atom's role in the rule's gradient:
         * <ul>
         *   <li>Positive head literal  → rule pushes atom up  (+1)</li>
         *   <li>Negated head literal   → rule pushes atom down(-1, via 1-v)</li>
         *   <li>Positive body literal  → rule pushes atom down(-1) when active</li>
         *   <li>Negated body literal   → rule pushes atom up  (+1, via 1-v)</li>
         * </ul>
         * If the atom appears in both head (positive) and body, the head-positive contribution
         * dominates, returning +1. Net zero (equal push-up vs push-down) returns 0.
         */
        private static int computeDirection(GroundRule gr, String atomKey) {
            int score = 0;
            for (GroundRule.Lit l : gr.head()) {
                if (atomKey.equals(l.atomKey())) {
                    score += l.negated() ? -1 : +1;
                }
            }
            for (GroundRule.Lit l : gr.body()) {
                if (atomKey.equals(l.atomKey())) {
                    score += l.negated() ? +1 : -1;
                }
            }
            return Integer.compare(score, 0);
        }
    }

    public static Result solve(PslProgram program) {
        return solve(program, DEFAULT_MAX_ITERATIONS, DEFAULT_TOLERANCE, DEFAULT_HARD_WEIGHT);
    }

    public static Result solve(PslProgram program, int maxIterations, double tolerance, double hardWeight) {
        List<GroundRule> ground = program.ground();
        List<ArithmeticGroundRule> arithGround = program.groundArithmetic();

        if (!arithGround.isEmpty()) {
            // Use ADMM when arithmetic rules are present (required for proper halfspace projection)
            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            return admm.solve(program, ground, arithGround, maxIterations, tolerance, hardWeight);
        }

        return chooseSolver(ground.size(), program.atomCount())
                .solve(program, ground, maxIterations, tolerance, hardWeight);
    }

    /**
     * Threshold below which programs are considered "small" and routed to
     * {@link ScalarHlMrfInference}. The default KG subgraphs (maxNodes ≈ 100) sit well under
     * this, so they stay on the fast scalar path. ADMM startup overhead only pays off above
     * this threshold (roughly 500 ground rules).
     */
    public static final int DEFAULT_SCALAR_THRESHOLD = 500;

    /**
     * Pick the inference strategy by problem size:
     * <ul>
     *   <li>fewer than {@link #DEFAULT_SCALAR_THRESHOLD} ground rules ⇒
     *       {@link ScalarHlMrfInference} (fastest for small KG subgraphs; avoids ADMM startup
     *       overhead that dominates at this scale).</li>
     *   <li>{@link #DEFAULT_SCALAR_THRESHOLD}–{@link #DEFAULT_TENSOR_THRESHOLD} ground rules ⇒
     *       {@link AdmmHlMrfInference} (canonical PSL solver, Bach et al. UAI 2013).</li>
     *   <li>above {@link #DEFAULT_TENSOR_THRESHOLD}, if the dense incidence matrix fits
     *       {@link #DEFAULT_DENSE_CELL_BUDGET} and an ND4J backend is available ⇒
     *       {@link TensorHlMrfInference};</li>
     *   <li>otherwise ⇒ {@link SgdHlMrfInference} (sparse, mini-batch SGD).</li>
     * </ul>
     */
    public static HlMrfSolver chooseSolver(int groundRuleCount, int atomCount) {
        if (groundRuleCount < DEFAULT_SCALAR_THRESHOLD) {
            return new ScalarHlMrfInference();
        }
        if (groundRuleCount < DEFAULT_TENSOR_THRESHOLD) {
            return new AdmmHlMrfInference(); // canonical ADMM for mid-scale
        }
        long cells = (long) groundRuleCount * Math.max(1, atomCount);
        if (cells <= DEFAULT_DENSE_CELL_BUDGET && TensorHlMrfInference.isAvailable()) {
            return new TensorHlMrfInference();
        }
        return new SgdHlMrfInference();
    }
}
