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

import ai.kompile.graph.reasoning.prior.DefaultPriorProvider;
import ai.kompile.graph.reasoning.prior.PriorContext;
import ai.kompile.graph.reasoning.prior.PriorProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 *   <li><b>x-update</b> (per rule, proximal step):
 *     <ul>
 *       <li>Squared-hinge logical rule: solve the quadratic
 *           {@code min w·(c·x - b)²₊ + (ρ/2)‖x - ẑ‖²} with a bounded
 *           projected-gradient proximal step.</li>
 *       <li>Linear-hinge logical rule: soft-threshold solution.</li>
 *       <li>Arithmetic ground rule: solve the bounded half-space projection for
 *           {@code Σ c_i x_i ≤/=/≥ rhs}; equality uses a signed multiplier solve.</li>
 *     </ul>
 *   </li>
 *   <li><b>z-update</b>: for each atom, average the local copies {@code x_r + u_r} over
 *       all rules that reference it, project to {@code [0,1]}, and fix observed atoms to
 *       their observed value.</li>
 *   <li><b>Dual update</b>: {@code u_r ← u_r + x_r - z} for each atom in rule {@code r}.</li>
 *   <li><b>Stopping</b>: in stacked local-copy space, primal residual
 *       {@code ‖x - Rz‖² = Σ|x_rj-z_i|²} and dual residual
 *       {@code ‖ρRΔz‖² = ρ²Σ refCount(i)·Δz_i²}, where {@code R} replicates each consensus
 *       coordinate per local copy; both use only free mapped copies.
 *       A normalized per-coordinate primal/stationarity gate also enforces the caller's
 *       requested accuracy, so aggregate relative thresholds cannot stop early.</li>
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
    /** Resolves informative warm-start priors for unobserved PSL target atoms (default: 0.5). */
    private PriorProvider priorProvider = DefaultPriorProvider.INSTANCE;

    public AdmmHlMrfInference() {
        this(DEFAULT_RHO, DEFAULT_EPS_ABS, DEFAULT_EPS_REL);
    }

    public AdmmHlMrfInference(double rho, double epsAbs, double epsRel) {
        if (rho <= 0) throw new IllegalArgumentException("rho must be positive, got " + rho);
        this.rho = rho;
        this.epsAbs = epsAbs;
        this.epsRel = epsRel;
    }

    /**
     * Override the prior provider used to warm-start unobserved target atoms in the ADMM
     * consensus variables.  The default is {@link DefaultPriorProvider#INSTANCE} (0.5).
     *
     * @param provider the provider to use; never {@code null}
     * @return this solver for fluent chaining
     */
    public AdmmHlMrfInference priorProvider(PriorProvider provider) {
        this.priorProvider = Objects.requireNonNull(provider, "provider");
        return this;
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
     * @param tolerance      positive requested normalized accuracy; invalid values fall back to
     *                       the configured {@code ε_abs}
     * @param hardWeight     penalty used only when reporting the final objective for hard
     *                       arithmetic constraints; retained for interface compatibility
     */
    public HlMrfMapInference.Result solve(PslProgram program,
                                          List<GroundRule> logicalRules,
                                          List<ArithmeticGroundRule> arithmeticRules,
                                          int maxIterations,
                                          double tolerance,
                                          double hardWeight) {
        List<String> atoms = new ArrayList<>(program.atomKeys());
        int n = atoms.size();
        if (logicalRules.isEmpty() && arithmeticRules.isEmpty()) {
            Map<String, Double> snap = program.valueSnapshot();
            for (String t : program.targetKeys()) snap.put(t, priorProvider.priorFor(t, PriorContext.EMPTY));
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
            z[i] = isObserved[i] ? program.value(key) : priorProvider.priorFor(key, PriorContext.EMPTY);
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
                x[r][j] = ai >= 0 ? z[ai] : priorProvider.priorFor(ruleAtoms.get(j), PriorContext.EMPTY);
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
                xa[r][j] = ai >= 0 ? z[ai] : priorProvider.priorFor(agr.atomKeys()[j], PriorContext.EMPTY);
            }
        }

        // Count how many rules reference each atom (for averaging)
        int[] refCount = new int[n];
        for (int r = 0; r < rl; r++) for (int j : ruleAtomIdx[r]) if (j >= 0) refCount[j]++;
        for (int r = 0; r < ra; r++) for (int j : aRuleAtomIdx[r]) if (j >= 0) refCount[j]++;

        // ─── ADMM iterations ─────────────────────────────────────────────────

        // A caller-supplied positive tolerance is an explicit normalized-accuracy request. Do
        // not silently weaken it with the configured absolute epsilon; invalid caller values
        // retain the solver's configured epsilon instead.
        double eps = effectiveTolerance(tolerance, epsAbs);
        double relativeEps = effectiveRelativeTolerance(epsRel);
        int activeLocalCopyCount = countActiveLocalCopies(
                ruleAtomIdx, aRuleAtomIdx, isObserved);
        boolean converged = false;
        boolean hardArithmeticFeasible = true;
        int iter;

        for (iter = 0; iter < maxIterations; iter++) {

            // 1. x-update: per logical rule, closed-form proximal step
            for (int r = 0; r < rl; r++) {
                GroundRule gr = logicalRules.get(r);
                xUpdateLogical(gr, x[r], u[r], z, ruleAtomIdx[r], isObserved, rho);
            }

            // 2. x-update: per arithmetic rule, project onto the constraint halfspace
            for (int r = 0; r < ra; r++) {
                ArithmeticGroundRule agr = arithmeticRules.get(r);
                hardArithmeticFeasible &= xUpdateArithmetic(
                        agr, xa[r], ua[r], z, aRuleAtomIdx[r], isObserved, rho);
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
                    zNew[i] = priorProvider.priorFor(atoms.get(i), PriorContext.EMPTY);
                } else {
                    zNew[i] = Math.max(0.0, Math.min(1.0, sumXU[i] / refCount[i]));
                }
            }

            // 4. Dual update: u += x - z. Observed atoms are fixed variables, not
            // consensus variables; their local copies and duals must remain pinned.
            double primalResidual2 = 0.0;
            for (int r = 0; r < rl; r++) {
                for (int j = 0; j < ruleAtomIdx[r].length; j++) {
                    int ai = ruleAtomIdx[r][j];
                    if (ai < 0) continue;
                    if (isObserved[ai]) {
                        x[r][j] = zNew[ai];
                        u[r][j] = 0.0;
                        continue;
                    }
                    double diff = x[r][j] - zNew[ai];
                    primalResidual2 += diff * diff;
                    u[r][j] += diff;
                }
            }
            for (int r = 0; r < ra; r++) {
                for (int j = 0; j < aRuleAtomIdx[r].length; j++) {
                    int ai = aRuleAtomIdx[r][j];
                    if (ai < 0) continue;
                    if (isObserved[ai]) {
                        xa[r][j] = zNew[ai];
                        ua[r][j] = 0.0;
                        continue;
                    }
                    double diff = xa[r][j] - zNew[ai];
                    primalResidual2 += diff * diff;
                    ua[r][j] += diff;
                }
            }

            // 5. Dual residual: the stacked replication residual is
            //   ||ρ R (z_new-z)||² = ρ² Σ_i refCount[i]·(Δz_i)².
            // It is not the square of an atom-level aggregate; doing that would add an
            // erroneous refCount² factor and make stopping depend on decomposition.
            double dualResidual2 = dualResidual2(rho, zNew, z, refCount);

            // Stopping criterion (Bach et al. UAI 2013, Section 3.3), restricted to free
            // local copies. Observed atoms are pinned and unmapped literals are not variables
            // in this consensus system, so neither contributes to a norm threshold.
            double localNorm = Math.sqrt(activeLocalNorm2(
                    x, xa, ruleAtomIdx, aRuleAtomIdx, isObserved));
            double consensusNorm2 = activeConsensusNorm2(zNew, refCount, isObserved);
            double ePrimal = Math.sqrt(activeLocalCopyCount) * eps
                    + relativeEps * Math.max(localNorm, Math.sqrt(consensusNorm2));
            double eDual = Math.sqrt(activeLocalCopyCount) * eps + relativeEps * rho
                    * Math.sqrt(dualNorm2(u, ua, ruleAtomIdx, aRuleAtomIdx));

            // Aggregate thresholds can become permissive when epsRel is large or when one
            // atom is replicated many times. Require normalized per-coordinate primal and
            // stationarity (dual-step) accuracy as well. The scale is the coordinate's own
            // magnitude, so this gate is independent of atom count, replication, and rho.
            boolean perCoordinateAccuracy = perCoordinateAccuracy(
                    x, xa, zNew, z, ruleAtomIdx, aRuleAtomIdx, isObserved, refCount, eps);

            z = zNew;

            if (Math.sqrt(primalResidual2) <= ePrimal
                    && Math.sqrt(dualResidual2) <= eDual
                    && perCoordinateAccuracy) {
                converged = true;
                break;
            }
        }

        // Assemble result
        Map<String, Double> resultValues = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) resultValues.put(atoms.get(i), z[i]);

        // Compute final objective using logical ground rules
        double obj = 0.0;
        for (GroundRule gr : logicalRules) obj += gr.potential(resultValues, hardWeight);
        for (ArithmeticGroundRule agr : arithmeticRules) obj += agr.potential(resultValues, hardWeight);

        // Hard arithmetic constraints are exact local projections, not finite penalties.  Do not
        // report convergence for an infeasible observed/free box or for a final consensus point
        // that still violates a feasible hard relation.
        if (converged) {
            if (!hardArithmeticFeasible) {
                converged = false;
            } else {
                for (ArithmeticGroundRule agr : arithmeticRules) {
                    if (agr.hard() && agr.distanceToSatisfaction(resultValues)
                            > HlMrfMapInference.HARD_VIOLATION_TOLERANCE) {
                        converged = false;
                        break;
                    }
                }
            }
        }

        // Capture final scaled duals: admmDuals[ruleIndex] = { atomKey -> u[r][j] }
        // Only logical rules; arithmetic duals are not attributed per-atom in the same way.
        Map<Integer, Map<String, Double>> admmDuals = new HashMap<>(rl);
        for (int r = 0; r < rl; r++) {
            GroundRule gr = logicalRules.get(r);
            List<String> ruleAtomList = ruleAtomKeys(gr);
            Map<String, Double> dualMap = new HashMap<>(ruleAtomList.size() * 2);
            for (int j = 0; j < ruleAtomIdx[r].length; j++) {
                if (ruleAtomIdx[r][j] >= 0) {
                    dualMap.put(ruleAtomList.get(j), u[r][j]);
                }
            }
            if (!dualMap.isEmpty()) {
                admmDuals.put(r, Collections.unmodifiableMap(dualMap));
            }
        }

        return new HlMrfMapInference.Result(resultValues, logicalRules, iter, obj, converged,
                Collections.unmodifiableMap(admmDuals));
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
                                       double[] z, int[] atomIdx, boolean[] isObserved,
                                       double rho) {
        int k = atomIdx.length;
        if (k == 0) return;

        List<String> atomKeys = ruleAtomKeys(gr);
        double w = gr.hard() ? 1e6 : gr.weight();
        boolean sq = gr.squared();

        // zHat_j = z[atomIdx[j]] - u[j]
        double[] zHat = new double[k];
        for (int j = 0; j < k; j++) {
            if (atomIdx[j] >= 0 && isObserved[atomIdx[j]]) {
                x[j] = z[atomIdx[j]];
                u[j] = 0.0;
            }
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
                if (atomIdx[j] < 0 || isObserved[atomIdx[j]]) continue;
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
     * Solve the arithmetic-rule local proximal problem over the unit box.  Observed atoms are
     * fixed coordinates, not variables in this subproblem.  The soft update is reduced to a
     * monotone one-dimensional multiplier equation; this is necessary because clipping a
     * half-space projection is not, in general, the projection onto the intersection.
     *
     * @return {@code false} only when a hard relation is infeasible after observed coordinates
     *         are fixed.  Soft rules always return {@code true}.
     */
    private static boolean xUpdateArithmetic(ArithmeticGroundRule agr, double[] xa, double[] ua,
                                             double[] z, int[] atomIdx, boolean[] isObserved,
                                             double rho) {
        int k = xa.length;
        if (k == 0) return true;

        double[] coefficients = agr.coefficients();
        double[] v = new double[k];
        boolean[] free = new boolean[k];
        for (int j = 0; j < k; j++) {
            int atom = atomIdx[j];
            if (atom >= 0 && isObserved[atom]) {
                xa[j] = z[atom];
                ua[j] = 0.0;
                v[j] = z[atom];
            } else if (atom >= 0) {
                v[j] = z[atom] - ua[j];
                free[j] = true;
            } else {
                // A grounded atom absent from the program is a fixed zero in the final map.
                v[j] = 0.0;
            }
        }

        double[] a = coefficients.clone();
        double b = agr.rhs();
        if (agr.op() == RelOp.GEQ) {
            // c·x >= rhs is (-c)·x <= -rhs.  Both sides must be negated.
            for (int j = 0; j < k; j++) a[j] = -a[j];
            b = -b;
        }

        // Move all fixed coordinates to the right-hand side of the free subproblem.
        for (int j = 0; j < k; j++) {
            if (!free[j]) {
                b -= a[j] * v[j];
                a[j] = 0.0;
            }
        }

        double[] x = new double[k];
        for (int j = 0; j < k; j++) x[j] = free[j] ? clamp01(v[j]) : v[j];
        if (agr.hard()) {
            boolean feasible = agr.op() == RelOp.EQ
                    ? projectHardEquality(x, v, a, b, free)
                    : projectHardHalfspace(x, v, a, b, free);
            System.arraycopy(x, 0, xa, 0, k);
            return feasible;
        }

        double weight = agr.weight();
        if (weight > 0.0 && hasFreeCoefficient(a, free)) {
            if (agr.op() == RelOp.EQ) {
                if (agr.squared()) {
                    solveSoftEqualitySquared(x, v, a, b, free, rho, weight);
                } else {
                    solveSoftEqualityLinear(x, v, a, b, free, rho, weight);
                }
            } else if (agr.squared()) {
                solveSoftHalfspaceSquared(x, v, a, b, free, rho, weight);
            } else {
                solveSoftHalfspaceLinear(x, v, a, b, free, rho, weight);
            }
        }
        System.arraycopy(x, 0, xa, 0, k);
        return true;
    }

    private static final int ROOT_ITERATIONS = 100;
    private static final int ROOT_BRACKET_ITERATIONS = 1024;
    private static final double ROOT_TOLERANCE = 1.0e-12;
    private static final double FEASIBILITY_TOLERANCE = 1.0e-12;

    private static void solveSoftHalfspaceSquared(double[] x, double[] v, double[] a,
                                                    double b, boolean[] free, double rho,
                                                    double weight) {
        double t0 = affine(x, a, b);
        if (!Double.isFinite(t0) || t0 <= 0.0) return;

        // s is the hinge excess.  KKT gives x=clip(v-(2w/rho)s*a), s=max(t(x),0).
        double lo = 0.0;
        double hi = Math.max(1.0, t0);
        double residual = softSquaredHalfspaceResidual(hi, v, a, b, free, rho, weight);
        boolean bracketed = Double.isFinite(residual) && residual <= 0.0;
        for (int bracket = 0; !bracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
            if (!Double.isFinite(hi)) break;
            double nextHi = hi * 2.0;
            if (!Double.isFinite(nextHi) || nextHi <= hi) break;
            hi = nextHi;
            residual = softSquaredHalfspaceResidual(hi, v, a, b, free, rho, weight);
            bracketed = Double.isFinite(residual) && residual <= 0.0;
        }
        if (!bracketed) {
            if (Double.isFinite(hi)) {
                applyMultiplier(x, v, a, hi, 2.0 * weight / rho, free);
            }
            return;
        }
        for (int i = 0; i < ROOT_ITERATIONS && hi - lo > ROOT_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            double midResidual = softSquaredHalfspaceResidual(mid, v, a, b, free, rho, weight);
            if (!Double.isFinite(midResidual)) {
                if (midResidual > 0.0) lo = mid;
                else if (midResidual < 0.0) hi = mid;
                else break;
            } else if (midResidual > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        applyMultiplier(x, v, a, 0.5 * (lo + hi), 2.0 * weight / rho, free);
    }

    private static double softSquaredHalfspaceResidual(double excess, double[] v, double[] a,
                                                        double b, boolean[] free, double rho,
                                                        double weight) {
        return affineWithMultiplier(v, a, b, excess, 2.0 * weight / rho, free) - excess;
    }

    private static void solveSoftHalfspaceLinear(double[] x, double[] v, double[] a,
                                                  double b, boolean[] free, double rho,
                                                  double weight) {
        double t0 = affine(x, a, b);
        if (!Double.isFinite(t0) || t0 <= 0.0) return;

        double tAtWeight = affineWithMultiplier(v, a, b, weight, 1.0 / rho, free);
        if (!Double.isFinite(tAtWeight)) return;
        double multiplier;
        if (tAtWeight >= 0.0) {
            multiplier = weight;
        } else {
            multiplier = bisectDecreasing(v, a, b, free, 0.0, weight, rho);
        }
        if (!Double.isFinite(multiplier)) return;
        applyMultiplier(x, v, a, multiplier, 1.0 / rho, free);
    }

    private static void solveSoftEqualityLinear(double[] x, double[] v, double[] a,
                                                 double b, boolean[] free, double rho,
                                                 double weight) {
        double t0 = affine(x, a, b);
        if (!Double.isFinite(t0) || Math.abs(t0) <= ROOT_TOLERANCE) return;

        double multiplier;
        if (t0 > 0.0) {
            double tAtWeight = affineWithMultiplier(v, a, b, weight, 1.0 / rho, free);
            if (!Double.isFinite(tAtWeight)) return;
            multiplier = tAtWeight <= 0.0
                    ? bisectDecreasing(v, a, b, free, 0.0, weight, rho)
                    : weight;
        } else {
            double tAtNegativeWeight = affineWithMultiplier(v, a, b, -weight, 1.0 / rho, free);
            if (!Double.isFinite(tAtNegativeWeight)) return;
            multiplier = tAtNegativeWeight >= 0.0
                    ? bisectDecreasing(v, a, b, free, -weight, 0.0, rho)
                    : -weight;
        }
        if (!Double.isFinite(multiplier)) return;
        applyMultiplier(x, v, a, multiplier, 1.0 / rho, free);
    }

    private static void solveSoftEqualitySquared(double[] x, double[] v, double[] a,
                                                  double b, boolean[] free, double rho,
                                                  double weight) {
        double t0 = affine(x, a, b);
        if (!Double.isFinite(t0) || Math.abs(t0) <= ROOT_TOLERANCE) return;

        // μ is the equality multiplier: x=clip(v-μa/rho), μ=2w(c·x-b).
        double lo;
        double hi;
        if (t0 > 0.0) {
            lo = 0.0;
            hi = 1.0;
            double residual = equalitySquaredResidual(hi, v, a, b, free, rho, weight);
            boolean bracketed = Double.isFinite(residual) && residual <= 0.0;
            for (int bracket = 0; !bracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
                if (!Double.isFinite(hi)) break;
                double nextHi = hi * 2.0;
                if (!Double.isFinite(nextHi) || nextHi <= hi) break;
                hi = nextHi;
                residual = equalitySquaredResidual(hi, v, a, b, free, rho, weight);
                bracketed = Double.isFinite(residual) && residual <= 0.0;
            }
            if (!bracketed) {
                if (Double.isFinite(hi)) {
                    applyMultiplier(x, v, a, hi, 1.0 / rho, free);
                }
                return;
            }
        } else {
            lo = -1.0;
            hi = 0.0;
            double residual = equalitySquaredResidual(lo, v, a, b, free, rho, weight);
            boolean bracketed = Double.isFinite(residual) && residual >= 0.0;
            for (int bracket = 0; !bracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
                if (!Double.isFinite(lo)) break;
                double nextLo = lo * 2.0;
                if (!Double.isFinite(nextLo) || nextLo >= lo) break;
                lo = nextLo;
                residual = equalitySquaredResidual(lo, v, a, b, free, rho, weight);
                bracketed = Double.isFinite(residual) && residual >= 0.0;
            }
            if (!bracketed) {
                if (Double.isFinite(lo)) {
                    applyMultiplier(x, v, a, lo, 1.0 / rho, free);
                }
                return;
            }
        }
        for (int i = 0; i < ROOT_ITERATIONS && hi - lo > ROOT_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            double midResidual = equalitySquaredResidual(mid, v, a, b, free, rho, weight);
            if (!Double.isFinite(midResidual)) {
                if (midResidual > 0.0) lo = mid;
                else if (midResidual < 0.0) hi = mid;
                else break;
            } else if (midResidual > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        applyMultiplier(x, v, a, 0.5 * (lo + hi), 1.0 / rho, free);
    }

    private static double equalitySquaredResidual(double multiplier, double[] v, double[] a,
                                                  double b, boolean[] free, double rho,
                                                  double weight) {
        return affineWithMultiplier(v, a, b, multiplier, 1.0 / rho, free)
                - multiplier / (2.0 * weight);
    }

    private static double bisectDecreasing(double[] v, double[] a, double b, boolean[] free,
                                           double lo, double hi, double rho) {
        for (int i = 0; i < ROOT_ITERATIONS && hi - lo > ROOT_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            double value = affineWithMultiplier(v, a, b, mid, 1.0 / rho, free);
            if (!Double.isFinite(value)) {
                if (value > 0.0) lo = mid;
                else if (value < 0.0) hi = mid;
                else return Double.NaN;
            } else if (value > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static boolean projectHardHalfspace(double[] x, double[] v, double[] a,
                                                 double b, boolean[] free) {
        double min = boxExtreme(a, free, false);
        if (!Double.isFinite(min) || !Double.isFinite(b)) {
            setBoxExtreme(x, a, free, false);
            return false;
        }
        if (min > b + FEASIBILITY_TOLERANCE) {
            setBoxExtreme(x, a, free, false);
            return false;
        }
        double target = clampNearLowerFeasibleBound(b, min);
        if (target <= min) {
            setBoxExtreme(x, a, free, false);
            return true;
        }
        double t0 = affine(x, a, target);
        if (!Double.isFinite(t0)) {
            setBoxExtreme(x, a, free, false);
            return false;
        }
        if (t0 <= FEASIBILITY_TOLERANCE) return true;

        double lo = 0.0;
        double hi = Math.max(1.0, t0);
        double residual = affineWithMultiplier(v, a, target, hi, 1.0, free);
        boolean bracketed = Double.isFinite(residual) && residual <= 0.0;
        for (int bracket = 0; !bracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
            if (!Double.isFinite(hi)) break;
            double nextHi = hi * 2.0;
            if (!Double.isFinite(nextHi) || nextHi <= hi) break;
            hi = nextHi;
            residual = affineWithMultiplier(v, a, target, hi, 1.0, free);
            bracketed = Double.isFinite(residual) && residual <= 0.0;
        }
        if (!bracketed) {
            setBoxExtreme(x, a, free, false);
            return false;
        }
        double multiplier = bisectDecreasing(v, a, target, free, lo, hi, 1.0);
        if (!Double.isFinite(multiplier)) {
            setBoxExtreme(x, a, free, false);
            return false;
        }
        applyMultiplier(x, v, a, multiplier, 1.0, free);
        return true;
    }

    private static boolean projectHardEquality(double[] x, double[] v, double[] a,
                                                double b, boolean[] free) {
        double min = boxExtreme(a, free, false);
        double max = boxExtreme(a, free, true);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(b)) {
            setBoxExtreme(x, a, free, Double.isFinite(b) && b > max);
            return false;
        }
        if (b < min - FEASIBILITY_TOLERANCE || b > max + FEASIBILITY_TOLERANCE) {
            setBoxExtreme(x, a, free, b > max);
            return false;
        }
        double target = clampNearFeasibleInterval(b, min, max);
        if (target == min) {
            setBoxExtreme(x, a, free, false);
            return true;
        }
        if (target == max) {
            setBoxExtreme(x, a, free, true);
            return true;
        }
        double initialResidual = affine(x, a, target);
        if (!Double.isFinite(initialResidual)) {
            setBoxExtreme(x, a, free, initialResidual < 0.0);
            return false;
        }
        if (Math.abs(initialResidual) <= FEASIBILITY_TOLERANCE) return true;

        double lo = -1.0;
        double hi = 1.0;
        double loResidual = affineWithMultiplier(v, a, target, lo, 1.0, free);
        boolean loBracketed = Double.isFinite(loResidual) && loResidual >= 0.0;
        for (int bracket = 0; !loBracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
            if (!Double.isFinite(lo)) break;
            double nextLo = lo * 2.0;
            if (!Double.isFinite(nextLo) || nextLo >= lo) break;
            lo = nextLo;
            loResidual = affineWithMultiplier(v, a, target, lo, 1.0, free);
            loBracketed = Double.isFinite(loResidual) && loResidual >= 0.0;
        }
        double hiResidual = affineWithMultiplier(v, a, target, hi, 1.0, free);
        boolean hiBracketed = Double.isFinite(hiResidual) && hiResidual <= 0.0;
        for (int bracket = 0; !hiBracketed && bracket < ROOT_BRACKET_ITERATIONS; bracket++) {
            if (!Double.isFinite(hi)) break;
            double nextHi = hi * 2.0;
            if (!Double.isFinite(nextHi) || nextHi <= hi) break;
            hi = nextHi;
            hiResidual = affineWithMultiplier(v, a, target, hi, 1.0, free);
            hiBracketed = Double.isFinite(hiResidual) && hiResidual <= 0.0;
        }
        if (!loBracketed || !hiBracketed) {
            setBoxExtreme(x, a, free, initialResidual < 0.0);
            return false;
        }
        double multiplier = bisectDecreasing(v, a, target, free, lo, hi, 1.0);
        if (!Double.isFinite(multiplier)) {
            setBoxExtreme(x, a, free, initialResidual < 0.0);
            return false;
        }
        applyMultiplier(x, v, a, multiplier, 1.0, free);
        return true;
    }

    private static double clampNearLowerFeasibleBound(double target, double minimum) {
        return target < minimum && target >= minimum - FEASIBILITY_TOLERANCE
                ? minimum : target;
    }

    private static double clampNearFeasibleInterval(double target, double minimum, double maximum) {
        if (target < minimum && target >= minimum - FEASIBILITY_TOLERANCE) return minimum;
        if (target > maximum && target <= maximum + FEASIBILITY_TOLERANCE) return maximum;
        return target;
    }

    private static boolean hasFreeCoefficient(double[] a, boolean[] free) {
        for (int j = 0; j < a.length; j++) {
            if (free[j] && a[j] != 0.0) return true;
        }
        return false;
    }

    private static double affine(double[] x, double[] a, double b) {
        return dot(x, a) - b;
    }

    private static double affineWithMultiplier(double[] v, double[] a, double b,
                                               double multiplier, double scale, boolean[] free) {
        double sum = 0.0;
        for (int j = 0; j < v.length; j++) {
            double coordinate = free[j]
                    ? projectedCoordinate(v[j], a[j], multiplier, scale) : v[j];
            sum += a[j] * coordinate;
        }
        return sum - b;
    }

    private static void applyMultiplier(double[] x, double[] v, double[] a,
                                        double multiplier, double scale, boolean[] free) {
        for (int j = 0; j < v.length; j++) {
            x[j] = free[j] ? projectedCoordinate(v[j], a[j], multiplier, scale) : v[j];
        }
    }

    private static double projectedCoordinate(double value, double coefficient,
                                              double multiplier, double scale) {
        if (coefficient == 0.0 || multiplier == 0.0 || scale == 0.0) {
            return clamp01(value);
        }
        double shifted = value - scale * multiplier * coefficient;
        return Double.isNaN(shifted) ? clamp01(value) : clamp01(shifted);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double boxExtreme(double[] a, boolean[] free, boolean maximum) {
        double value = 0.0;
        for (int j = 0; j < a.length; j++) {
            if (!free[j]) continue;
            if (maximum) value += a[j] > 0.0 ? a[j] : 0.0;
            else value += a[j] < 0.0 ? a[j] : 0.0;
        }
        return value;
    }

    private static void setBoxExtreme(double[] x, double[] a, boolean[] free, boolean maximum) {
        for (int j = 0; j < x.length; j++) {
            if (!free[j]) continue;
            if (maximum && a[j] > 0.0) x[j] = 1.0;
            if (!maximum && a[j] < 0.0) x[j] = 1.0;
            if (a[j] != 0.0 && ((maximum && a[j] < 0.0) || (!maximum && a[j] > 0.0))) {
                x[j] = 0.0;
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static List<String> ruleAtomKeys(GroundRule gr) {
        // Collect unique ordered atom keys from body then head
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (GroundRule.Lit l : gr.body()) keys.add(l.atomKey());
        for (GroundRule.Lit l : gr.head()) keys.add(l.atomKey());
        return new ArrayList<>(keys);
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static int countActiveLocalCopies(int[][] ruleAtomIdx,
                                               int[][] arithmeticAtomIdx,
                                               boolean[] isObserved) {
        int count = 0;
        for (int[] atoms : ruleAtomIdx) {
            for (int atom : atoms) {
                if (atom >= 0 && !isObserved[atom]) count++;
            }
        }
        for (int[] atoms : arithmeticAtomIdx) {
            for (int atom : atoms) {
                if (atom >= 0 && !isObserved[atom]) count++;
            }
        }
        return count;
    }

    private static double activeLocalNorm2(double[][] x, double[][] xa,
                                           int[][] ruleAtomIdx, int[][] arithmeticAtomIdx,
                                           boolean[] isObserved) {
        double s = 0.0;
        for (int r = 0; r < x.length; r++) {
            for (int j = 0; j < x[r].length; j++) {
                int atom = ruleAtomIdx[r][j];
                if (atom >= 0 && !isObserved[atom]) s += x[r][j] * x[r][j];
            }
        }
        for (int r = 0; r < xa.length; r++) {
            for (int j = 0; j < xa[r].length; j++) {
                int atom = arithmeticAtomIdx[r][j];
                if (atom >= 0 && !isObserved[atom]) s += xa[r][j] * xa[r][j];
            }
        }
        return s;
    }

    private static double activeConsensusNorm2(double[] z, int[] refCount,
                                               boolean[] isObserved) {
        double s = 0.0;
        for (int i = 0; i < z.length; i++) {
            if (!isObserved[i] && refCount[i] > 0) {
                s += refCount[i] * z[i] * z[i];
            }
        }
        return s;
    }

    /**
     * Squared norm of the stacked consensus dual residual. Each local copy contributes one
     * {@code dz_i}; replication therefore contributes {@code refCount[i]}, not its square.
     */
    static double dualResidual2(double rho, double[] zNew, double[] z, int[] refCount) {
        double s = 0.0;
        for (int i = 0; i < zNew.length; i++) {
            if (refCount[i] > 0) {
                double dz = zNew[i] - z[i];
                s += refCount[i] * dz * dz;
            }
        }
        return rho * rho * s;
    }

    /**
     * Squared norm of the stacked scaled-dual vector. Do not aggregate copies by atom: opposing
     * local duals are distinct coordinates, and aggregating them would cancel valid dual energy.
     */
    static double dualNorm2(double[][] u, double[][] ua,
                            int[][] ruleAtomIdx, int[][] arithmeticAtomIdx) {
        double s = 0.0;
        for (int r = 0; r < u.length; r++) {
            for (int j = 0; j < u[r].length; j++) {
                if (ruleAtomIdx[r][j] >= 0) s += u[r][j] * u[r][j];
            }
        }
        for (int r = 0; r < ua.length; r++) {
            for (int j = 0; j < ua[r].length; j++) {
                if (arithmeticAtomIdx[r][j] >= 0) s += ua[r][j] * ua[r][j];
            }
        }
        return s;
    }

    private static boolean perCoordinateAccuracy(double[][] x, double[][] xa,
                                                  double[] zNew, double[] z,
                                                  int[][] ruleAtomIdx, int[][] arithmeticAtomIdx,
                                                  boolean[] isObserved, int[] refCount,
                                                  double tolerance) {
        double maxPrimal = 0.0;
        for (int r = 0; r < x.length; r++) {
            for (int j = 0; j < x[r].length; j++) {
                int atom = ruleAtomIdx[r][j];
                if (atom >= 0 && !isObserved[atom]) {
                    maxPrimal = Math.max(maxPrimal,
                            normalizedDifference(x[r][j], zNew[atom]));
                }
            }
        }
        for (int r = 0; r < xa.length; r++) {
            for (int j = 0; j < xa[r].length; j++) {
                int atom = arithmeticAtomIdx[r][j];
                if (atom >= 0 && !isObserved[atom]) {
                    maxPrimal = Math.max(maxPrimal,
                            normalizedDifference(xa[r][j], zNew[atom]));
                }
            }
        }

        double maxStationarity = 0.0;
        for (int i = 0; i < zNew.length; i++) {
            if (!isObserved[i] && refCount[i] > 0) {
                maxStationarity = Math.max(maxStationarity,
                        normalizedDifference(zNew[i], z[i]));
            }
        }
        return maxPrimal <= tolerance && maxStationarity <= tolerance;
    }

    private static double normalizedDifference(double value, double reference) {
        // Truth values are bounded by [0,1], so the domain scale is the coordinate's unit
        // bound. This keeps a small movement toward a zero optimum small instead of making
        // every nonzero-versus-zero comparison report a relative error of one.
        double scale = Math.max(1.0, Math.max(Math.abs(value), Math.abs(reference)));
        return Math.abs(value - reference) / scale;
    }

    static double effectiveTolerance(double tolerance, double configuredEpsAbs) {
        if (Double.isFinite(tolerance) && tolerance > 0.0) return tolerance;
        return Double.isFinite(configuredEpsAbs) && configuredEpsAbs > 0.0
                ? configuredEpsAbs : DEFAULT_EPS_ABS;
    }

    private static double effectiveRelativeTolerance(double configuredEpsRel) {
        return Double.isFinite(configuredEpsRel) && configuredEpsRel >= 0.0
                ? configuredEpsRel : DEFAULT_EPS_REL;
    }
}
