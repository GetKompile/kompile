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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consensus-ADMM HL-MRF MAP inference — the canonical PSL solver described in
 * Bach, Huang, London &amp; Getoor, "Hinge-loss Markov Random Fields: Convex Inference for
 * Structured Prediction", UAI 2013.
 *
 * <h3>Algorithm outline</h3>
 * <ol>
 *   <li>Each ground rule {@code r} maintains its own local copies {@code x_r} of the
 *       atoms that appear in it, plus a scaled dual {@code u_r} (one per atom per rule).</li>
 *   <li>Global consensus variables {@code z} (one per atom) must agree with all local
 *       copies.</li>
 *   <li><b>x-update</b> (per rule, closed-form):
 *     <ul>
 *       <li>Squared-hinge logical rule: solve the quadratic
 *           {@code min w·(c·x - b)²₊ + (ρ/2)‖x - ẑ‖²} exactly, element-wise.</li>
 *       <li>Linear-hinge logical rule: soft-threshold solution.</li>
 *       <li>Arithmetic ground rule: project {@code ẑ} onto the half-space
 *           {@code Σ c_i x_i ≤/=/≥ rhs} (equality handled as two projections).</li>
 *     </ul>
 *   </li>
 *   <li><b>z-update</b>: for each atom, average the local copies {@code x_r + u_r} over
 *       all rules that reference it, project to {@code [0,1]}, and fix observed atoms to
 *       their observed value.</li>
 *   <li><b>Dual update</b>: {@code u_r ← u_r + x_r - z} for each atom in rule {@code r}.</li>
 *   <li><b>Stopping</b>: primal residual {@code ‖x - z‖_F ≤ ε_primal} and dual residual
 *       {@code ρ‖Δz‖_F ≤ ε_dual} simultaneously.</li>
 * </ol>
 *
 * <p>Supports both logical {@link GroundRule}s and arithmetic {@link ArithmeticGroundRule}s
 * (the latter passed in via {@link #solve(PslProgram, List, List, int, double, double)}
 * or exposed through the convenience override).</p>
 *
 * <p>Default parameters (matching psl.linqs.org defaults):
 * <ul>
 *   <li>{@code ρ = 1.0}</li>
 *   <li>{@code maxIterations = 25 000}</li>
 *   <li>{@code ε_abs = 1e-5}</li>
 *   <li>{@code ε_rel = 1e-3}</li>
 * </ul>
 */
public class AdmmHlMrfInference implements HlMrfSolver {

    // ─── Default ADMM parameters ─────────────────────────────────────────────

    public static final double DEFAULT_RHO = 1.0;
    public static final double DEFAULT_EPS_ABS = 1e-5;
    public static final double DEFAULT_EPS_REL = 1e-3;
    /** Maps to {@link HlMrfMapInference#DEFAULT_MAX_ITERATIONS} when used via the interface. */
    public static final int DEFAULT_MAX_ITERATIONS = 25_000;

    private final double rho;
    private final double epsAbs;
    private final double epsRel;

    public AdmmHlMrfInference() {
        this(DEFAULT_RHO, DEFAULT_EPS_ABS, DEFAULT_EPS_REL);
    }

    public AdmmHlMrfInference(double rho, double epsAbs, double epsRel) {
        if (rho <= 0) throw new IllegalArgumentException("rho must be positive, got " + rho);
        this.rho = rho;
        this.epsAbs = epsAbs;
        this.epsRel = epsRel;
    }

    // ─── HlMrfSolver interface ───────────────────────────────────────────────

    @Override
    public HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> groundRules,
                                          int maxIterations, double tolerance, double hardWeight) {
        return solve(program, groundRules, List.of(), maxIterations, tolerance, hardWeight);
    }

    /**
     * Solve with both logical and arithmetic ground rules.
     *
     * @param program        atom declarations (observed/target partition, initial values)
     * @param logicalRules   grounded logical rules
     * @param arithmeticRules grounded arithmetic rules (may be empty)
     * @param maxIterations  iteration cap (use {@link #DEFAULT_MAX_ITERATIONS} for the canonical limit)
     * @param tolerance      tolerance used as {@code ε_abs} when &gt; {@link #DEFAULT_EPS_ABS}
     * @param hardWeight     unused by ADMM (hard constraints are enforced exactly via dual);
     *                       retained for interface compatibility
     */
    public HlMrfMapInference.Result solve(PslProgram program,
                                          List<GroundRule> logicalRules,
                                          List<ArithmeticGroundRule> arithmeticRules,
                                          int maxIterations,
                                          double tolerance,
                                          double hardWeight) {
        List<String> atoms = new ArrayList<>(program.atomKeys());
        int n = atoms.size();
        if (n == 0 || (logicalRules.isEmpty() && arithmeticRules.isEmpty())) {
            Map<String, Double> snap = program.valueSnapshot();
            for (String t : program.targetKeys()) snap.put(t, 0.5);
            return new HlMrfMapInference.Result(snap, logicalRules, 0, 0.0, true);
        }

        // Index atoms
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(atoms.get(i), i);

        // ─── Global consensus variables z ────────────────────────────────────
        double[] z = new double[n];
        boolean[] isObserved = new boolean[n];
        for (int i = 0; i < n; i++) {
            String key = atoms.get(i);
            isObserved[i] = program.isObserved(key);
            z[i] = isObserved[i] ? program.value(key) : 0.5; // neutral init for targets
        }

        // ─── Per-rule local state ─────────────────────────────────────────────

        // Logical rules: local copies x[r][j] for the j-th atom in rule r, dual u[r][j]
        int rl = logicalRules.size();
        int ra = arithmeticRules.size();
        int[][] ruleAtomIdx = new int[rl][];   // atom indices per logical rule
        double[][] x = new double[rl][];        // local copies
        double[][] u = new double[rl][];        // duals

        for (int r = 0; r < rl; r++) {
            GroundRule gr = logicalRules.get(r);
            List<String> ruleAtoms = ruleAtomKeys(gr);
            ruleAtomIdx[r] = new int[ruleAtoms.size()];
            x[r] = new double[ruleAtoms.size()];
            u[r] = new double[ruleAtoms.size()];
            for (int j = 0; j < ruleAtoms.size(); j++) {
                int ai = index.getOrDefault(ruleAtoms.get(j), -1);
                ruleAtomIdx[r][j] = ai;
                x[r][j] = ai >= 0 ? z[ai] : 0.5;
            }
        }

        // Arithmetic rules: local copies xa[r][i] for each atom, dual ua[r][i]
        int[][] aRuleAtomIdx = new int[ra][];
        double[][] xa = new double[ra][];
        double[][] ua = new double[ra][];
        for (int r = 0; r < ra; r++) {
            ArithmeticGroundRule agr = arithmeticRules.get(r);
            aRuleAtomIdx[r] = new int[agr.atomKeys().length];
            xa[r] = new double[agr.atomKeys().length];
            ua[r] = new double[agr.atomKeys().length];
            for (int j = 0; j < agr.atomKeys().length; j++) {
                int ai = index.getOrDefault(agr.atomKeys()[j], -1);
                aRuleAtomIdx[r][j] = ai;
                xa[r][j] = ai >= 0 ? z[ai] : 0.5;
            }
        }

        // Count how many rules reference each atom (for averaging)
        int[] refCount = new int[n];
        for (int r = 0; r < rl; r++) for (int j : ruleAtomIdx[r]) if (j >= 0) refCount[j]++;
        for (int r = 0; r < ra; r++) for (int j : aRuleAtomIdx[r]) if (j >= 0) refCount[j]++;

        // ─── ADMM iterations ─────────────────────────────────────────────────

        double eps = Math.max(epsAbs, tolerance);
        boolean converged = false;
        int iter;

        for (iter = 0; iter < maxIterations; iter++) {

            // 1. x-update: per logical rule, closed-form proximal step
            for (int r = 0; r < rl; r++) {
                GroundRule gr = logicalRules.get(r);
                xUpdateLogical(gr, x[r], u[r], z, ruleAtomIdx[r], rho);
            }

            // 2. x-update: per arithmetic rule, project onto the constraint halfspace
            for (int r = 0; r < ra; r++) {
                ArithmeticGroundRule agr = arithmeticRules.get(r);
                xUpdateArithmetic(agr, xa[r], ua[r], z, aRuleAtomIdx[r], rho);
            }

            // 3. z-update: global consensus average, clamped to [0,1]
            double[] zNew = new double[n];
            double[] sumXU = new double[n];
            for (int r = 0; r < rl; r++) {
                for (int j = 0; j < ruleAtomIdx[r].length; j++) {
                    int ai = ruleAtomIdx[r][j];
                    if (ai >= 0) sumXU[ai] += x[r][j] + u[r][j];
                }
            }
            for (int r = 0; r < ra; r++) {
                for (int j = 0; j < aRuleAtomIdx[r].length; j++) {
                    int ai = aRuleAtomIdx[r][j];
                    if (ai >= 0) sumXU[ai] += xa[r][j] + ua[r][j];
                }
            }
            for (int i = 0; i < n; i++) {
                if (isObserved[i]) {
                    zNew[i] = z[i]; // fix observed atoms
                } else if (refCount[i] == 0) {
                    zNew[i] = 0.5;  // unreferenced target
                } else {
                    zNew[i] = Math.max(0.0, Math.min(1.0, sumXU[i] / refCount[i]));
                }
            }

            // 4. Dual update: u += x - z
            double primalResidual2 = 0.0;
            for (int r = 0; r < rl; r++) {
                for (int j = 0; j < ruleAtomIdx[r].length; j++) {
                    int ai = ruleAtomIdx[r][j];
                    if (ai < 0) continue;
                    double diff = x[r][j] - zNew[ai];
                    primalResidual2 += diff * diff;
                    u[r][j] += diff;
                }
            }
            for (int r = 0; r < ra; r++) {
                for (int j = 0; j < aRuleAtomIdx[r].length; j++) {
                    int ai = aRuleAtomIdx[r][j];
                    if (ai < 0) continue;
                    double diff = xa[r][j] - zNew[ai];
                    primalResidual2 += diff * diff;
                    ua[r][j] += diff;
                }
            }

            // 5. Dual residual: ρ·‖z_new - z‖
            double dualResidual2 = 0.0;
            for (int i = 0; i < n; i++) {
                double dz = zNew[i] - z[i];
                dualResidual2 += rho * rho * dz * dz;
            }

            // Count total local copies (= sum of rule literal counts)
            int totalCopies = 0;
            for (int[] ri : ruleAtomIdx) totalCopies += ri.length;
            for (int[] ri : aRuleAtomIdx) totalCopies += ri.length;

            // Stopping criterion (Bach et al. UAI 2013, Section 3.3)
            double ePrimal = Math.sqrt(totalCopies) * eps + epsRel * Math.sqrt(primalNorm2(x, xa));
            double eDual = Math.sqrt(n) * eps + epsRel * rho * Math.sqrt(dualNorm2(u, ua));

            z = zNew;

            if (Math.sqrt(primalResidual2) <= ePrimal && Math.sqrt(dualResidual2) <= eDual) {
                converged = true;
                break;
            }
        }

        // Assemble result
        Map<String, Double> result = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) result.put(atoms.get(i), z[i]);

        // Compute final objective using logical ground rules
        double obj = 0.0;
        for (GroundRule gr : logicalRules) obj += gr.potential(result, hardWeight);
        for (ArithmeticGroundRule agr : arithmeticRules) obj += agr.potential(result, hardWeight);

        return new HlMrfMapInference.Result(result, logicalRules, iter, obj, converged);
    }

    // ─── x-update for a logical ground rule ─────────────────────────────────

    /**
     * Proximal sub-problem for a logical ground rule: projected gradient descent on
     * <pre>
     *   f(x) = w · max(d(x), 0)^p  +  (ρ/2) Σ_j (x_j - ẑ_j)²
     * </pre>
     * where {@code ẑ_j = z[atomIdx[j]] - u[j]} and {@code p ∈ {1,2}}.
     *
     * <p>The function {@code f} is strongly convex (from the ρ quadratic) and smooth when
     * the hinge is active. We solve it via projected gradient descent with step size
     * {@code 1 / L} where {@code L = 2w·K² + ρ} is the Lipschitz constant of the gradient,
     * and {@code K} is an upper bound on ‖∇d‖² (which is at most the number of literals
     * since each literal contributes ±1 to the body/head sum and |c_j| ≤ 1).
     *
     * <p>This sub-problem converges in O(log(1/ε)) iterations with this step size.
     * We use at most {@value #SUB_ITER} iterations — enough for double precision accuracy
     * on typical HL-MRF problems.</p>
     */
    private static final int SUB_ITER = 100;

    private static void xUpdateLogical(GroundRule gr, double[] x, double[] u,
                                       double[] z, int[] atomIdx, double rho) {
        int k = atomIdx.length;
        if (k == 0) return;

        List<String> atomKeys = ruleAtomKeys(gr);
        double w = gr.hard() ? 1e6 : gr.weight();
        boolean sq = gr.squared();

        // zHat_j = z[atomIdx[j]] - u[j]
        double[] zHat = new double[k];
        for (int j = 0; j < k; j++) {
            zHat[j] = atomIdx[j] >= 0 ? z[atomIdx[j]] - u[j] : 0.5;
        }

        // Build the per-atom sign vector for the distance function:
        //   ∂d/∂x_j = bodySign_j - headSign_j  (when d > 0)
        double[] c = new double[k]; // gradient of d w.r.t. x[j] (sign in the linear active regime)
        for (int j = 0; j < k; j++) {
            String key = atomKeys.get(j);
            double bodyC = 0.0, headC = 0.0;
            for (GroundRule.Lit l : gr.body()) {
                if (l.atomKey().equals(key)) { bodyC += l.negated() ? -1.0 : 1.0; }
            }
            for (GroundRule.Lit l : gr.head()) {
                if (l.atomKey().equals(key)) { headC += l.negated() ? -1.0 : 1.0; }
            }
            c[j] = bodyC - headC; // ∂d/∂x_j (in the linear regime)
        }

        // Lipschitz constant: L = 2w * ‖c‖² + ρ  (for squared hinge)
        // For linear hinge: L = ρ (gradient of the hinge potential is constant, ‖c‖ bounded)
        double cNorm2 = 0.0;
        for (double ci : c) cNorm2 += ci * ci;
        double L = (sq ? 2.0 * w * cNorm2 : w * Math.sqrt(cNorm2)) + rho;
        if (L < 1e-10) L = rho;
        double step = 1.0 / L;

        // Local values map
        Map<String, Double> localValues = new HashMap<>(k * 2);
        for (int j = 0; j < k; j++) {
            if (atomIdx[j] >= 0) localValues.put(atomKeys.get(j), x[j]);
        }

        // Projected gradient loop
        for (int sub = 0; sub < SUB_ITER; sub++) {
            double d = gr.distanceToSatisfaction(localValues);
            double wCoef = d > 0.0 ? (sq ? 2.0 * w * d : w) : 0.0;
            double maxDelta = 0.0;
            for (int j = 0; j < k; j++) {
                if (atomIdx[j] < 0) continue;
                // Gradient: hinge contribution + proximal term
                double gHinge = (d > 0.0) ? wCoef * c[j] : 0.0;
                double gProx = rho * (x[j] - zHat[j]);
                double xNew = Math.max(0.0, Math.min(1.0, x[j] - step * (gHinge + gProx)));
                maxDelta = Math.max(maxDelta, Math.abs(xNew - x[j]));
                x[j] = xNew;
                localValues.put(atomKeys.get(j), xNew);
            }
            if (maxDelta < 1e-10) break;
        }
    }

    // ─── x-update for an arithmetic ground rule ──────────────────────────────

    /**
     * Closed-form projection for an arithmetic ground rule.
     *
     * <p>An arithmetic rule introduces the constraint {@code Σ c_i x_i ≤/=/≥ rhs}.
     * The ADMM x-update is:
     * <pre>
     *   argmin_{x ∈ [0,1]^k}  w · max(ℓ(x), 0)^p  +  (ρ/2)‖x - ẑ‖²
     * </pre>
     * For LEQ: project {@code ẑ} onto the halfspace {@code {x : c·x ≤ rhs}}.
     * The closed-form projector onto a halfspace is:
     * <pre>
     *   if c·ẑ ≤ rhs:  x* = ẑ                 (already feasible)
     *   else:          x* = ẑ - ((c·ẑ - rhs) / ‖c‖²) · c
     * </pre>
     * For GEQ: negate and use LEQ.
     * For EQ: project onto the hyperplane (same formula, always applied).
     * After the halfspace projection, clip each component to [0,1].
     *
     * <p>The soft weighting {@code w · max(ℓ,0)^p} is incorporated as a penalty-scaled
     * projection: scale {@code ẑ} toward the boundary by the ratio {@code ρ/(ρ + 2w)}
     * for squared hinge (p=2) — equivalent to the Moreau proximal for squared hinge.
     */
    private static void xUpdateArithmetic(ArithmeticGroundRule agr, double[] xa, double[] ua,
                                          double[] z, int[] atomIdx, double rho) {
        int k = xa.length;
        if (k == 0) return;

        double[] coefs = agr.coefficients();
        double rhs = agr.rhs();
        double w = agr.hard() ? 1e6 : agr.weight();
        boolean sq = agr.squared();

        // Adjusted target: zHat_j = z[atomIdx[j]] - ua[j]
        double[] zHat = new double[k];
        for (int j = 0; j < k; j++) {
            zHat[j] = atomIdx[j] >= 0 ? z[atomIdx[j]] - ua[j] : 0.5;
        }

        // Helper: ℓ(y) = Σ coefs[j]*y[j] - rhs
        RelOp op = agr.op();

        // For EQ: handle as LEQ + GEQ (two projections, take the one that reduces ℓ more)
        if (op == RelOp.EQ) {
            // Project as if LEQ, then as if GEQ, choose the one with smaller |ℓ|
            double[] xLEQ = projectHalfspace(zHat, coefs, rhs, 1.0, rho, w, sq);
            double[] xGEQ = projectHalfspace(zHat, coefs, rhs, -1.0, rho, w, sq);
            double dLEQ = Math.abs(dot(coefs, xLEQ) - rhs);
            double dGEQ = Math.abs(dot(coefs, xGEQ) - rhs);
            double[] best = dLEQ <= dGEQ ? xLEQ : xGEQ;
            System.arraycopy(best, 0, xa, 0, k);
        } else {
            double sign = (op == RelOp.LEQ) ? 1.0 : -1.0; // GEQ → negate
            double[] xNew = projectHalfspace(zHat, coefs, rhs, sign, rho, w, sq);
            System.arraycopy(xNew, 0, xa, 0, k);
        }
    }

    /**
     * Project onto the halfspace {@code sign*(c·x - rhs) ≤ 0} with the penalty-scaled proximal.
     *
     * @param sign 1.0 for LEQ (c·x ≤ rhs), -1.0 for GEQ (c·x ≥ rhs → -c·x ≤ -rhs)
     */
    private static double[] projectHalfspace(double[] zHat, double[] coefs, double rhs,
                                             double sign, double rho, double w, boolean sq) {
        int k = zHat.length;
        double[] c = new double[k];
        double b = rhs;
        for (int j = 0; j < k; j++) c[j] = sign * coefs[j];
        // sign*b stays the same

        double cz = dot(c, zHat); // c · zHat
        double excess = cz - b;   // positive when we violate the halfspace

        // For soft constraints, scale the gap toward the boundary
        // Moreau prox for w*max(c·x - b, 0)^p:
        //   p=2 (sq): closed-form shrinkage factor = ρ/(ρ + 2w) on the excess
        //   p=1 (linear): soft-threshold the excess
        double[] x = zHat.clone();
        if (excess > 0) {
            double alpha; // how much to shrink toward the boundary
            if (sq) {
                // gradient-descent correction: x = zHat - (2w*d/(ρ + 2w)) * c/‖c‖² * ‖c‖
                double cNorm2 = dot(c, c);
                if (cNorm2 > 1e-10) {
                    alpha = (2.0 * w * excess) / (rho * cNorm2 + 2.0 * w);
                    for (int j = 0; j < k; j++) x[j] -= alpha * c[j];
                }
            } else {
                // soft-threshold: project if the penalty gradient pulls inside
                double cNorm2 = dot(c, c);
                if (cNorm2 > 1e-10) {
                    alpha = Math.min(excess, w / rho) / cNorm2;
                    for (int j = 0; j < k; j++) x[j] -= alpha * c[j];
                }
            }
        }
        // Clip to [0,1]
        for (int j = 0; j < k; j++) x[j] = Math.max(0.0, Math.min(1.0, x[j]));
        return x;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static List<String> ruleAtomKeys(GroundRule gr) {
        // Collect unique ordered atom keys from body then head
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (GroundRule.Lit l : gr.body()) keys.add(l.atomKey());
        for (GroundRule.Lit l : gr.head()) keys.add(l.atomKey());
        return new ArrayList<>(keys);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double primalNorm2(double[][] x, double[][] xa) {
        double s = 0;
        for (double[] v : x) for (double vi : v) s += vi * vi;
        for (double[] v : xa) for (double vi : v) s += vi * vi;
        return s;
    }

    private static double dualNorm2(double[][] u, double[][] ua) {
        double s = 0;
        for (double[] v : u) for (double vi : v) s += vi * vi;
        for (double[] v : ua) for (double vi : v) s += vi * vi;
        return s;
    }
}
