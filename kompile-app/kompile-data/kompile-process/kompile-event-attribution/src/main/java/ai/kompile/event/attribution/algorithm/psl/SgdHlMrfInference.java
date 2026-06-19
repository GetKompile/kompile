/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sparse, mini-batch stochastic-gradient HL-MRF MAP inference — the graph-scale tier of the
 * {@link HlMrfSolver} family.
 *
 * <p>Unlike {@link TensorHlMrfInference}, which materializes dense {@code R×A} incidence
 * matrices, this solver keeps a <b>sparse</b> flattened view of the ground rules
 * ({@code O(total literals)} memory) and optimizes with <b>SGD over mini-batches of ground
 * rules</b>: each step touches only the atoms in its batch and applies a sparse scatter-add
 * gradient update with a decaying learning rate. This is what makes whole-graph collective
 * inference (and, later, weight learning) tractable when the dense matrix would not fit.</p>
 *
 * <p>It is intentionally plain-Java / CPU: the sparse, irregular gather/scatter access pattern
 * does not benefit from dense GPU matrix kernels (that is the niche of the dense tensor tier),
 * and PSL's own SGD reasoner is likewise CPU. The gradient is the same analytic hinge gradient
 * used by {@link ScalarHlMrfInference} (parity-verified), so the result matches the convex global
 * optimum; SGD is deterministic here (fixed-seed shuffling) and returns the best-objective
 * iterate seen, which keeps the output stable despite stochastic step noise.</p>
 */
public class SgdHlMrfInference implements HlMrfSolver {

    public static final int DEFAULT_BATCH_SIZE = 256;
    public static final double DEFAULT_INITIAL_LEARNING_RATE = 0.5;
    /** Fixed RNG seed so mini-batch shuffling — and therefore the result — is reproducible. */
    public static final long SEED = 1234L;

    /** Stop after this many epochs with no significant objective improvement (oscillation-safe). */
    private static final int PATIENCE = 200;

    private final int batchSize;
    private final double initialLearningRate;

    public SgdHlMrfInference() {
        this(DEFAULT_BATCH_SIZE, DEFAULT_INITIAL_LEARNING_RATE);
    }

    public SgdHlMrfInference(int batchSize, double initialLearningRate) {
        this.batchSize = Math.max(1, batchSize);
        this.initialLearningRate = initialLearningRate;
    }

    @Override
    public HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> ground,
                                          int maxIterations, double tolerance, double hardWeight) {
        List<String> atoms = new ArrayList<>(program.atomKeys());
        int n = atoms.size();
        int r = ground.size();

        double[] values = new double[n];
        boolean[] isTarget = new boolean[n];
        Map<String, Integer> index = new HashMap<>();
        boolean anyTarget = false;
        for (int i = 0; i < n; i++) {
            String key = atoms.get(i);
            index.put(key, i);
            if (program.isObserved(key)) {
                values[i] = program.value(key);
            } else {
                values[i] = 0.5;
                isTarget[i] = true;
                anyTarget = true;
            }
        }

        Compiled rules = new Compiled(ground, index, hardWeight);
        if (n == 0 || r == 0 || !anyTarget) {
            return new HlMrfMapInference.Result(toMap(atoms, values), ground, 0, rules.objective(values), true);
        }

        int[] order = new int[r];
        for (int i = 0; i < r; i++) order[i] = i;
        Random rng = new Random(SEED);

        double[] grad = new double[n];
        boolean[] touchedFlag = new boolean[n];
        int[] touched = new int[n];

        double bestObjective = rules.objective(values);
        double[] bestValues = values.clone();
        int noImprove = 0;
        boolean converged = false;
        int steps = 0;
        int epoch = 0;
        int maxSteps = Math.max(1, maxIterations);

        while (steps < maxSteps) {
            shuffle(order, rng);
            double lr = initialLearningRate / (1.0 + 0.5 * epoch);

            for (int start = 0; start < r && steps < maxSteps; start += batchSize) {
                int end = Math.min(r, start + batchSize);
                int touchedCount = 0;

                for (int b = start; b < end; b++) {
                    int ri = order[b];
                    double d = rules.distance(ri, values);
                    if (d <= 0.0) continue;
                    double coef = rules.squared[ri] ? 2.0 * rules.weight[ri] * d : rules.weight[ri];

                    int[] bodyAtom = rules.bodyAtom[ri];
                    double[] bodySign = rules.bodySign[ri];
                    for (int j = 0; j < bodyAtom.length; j++) {
                        int i = bodyAtom[j];
                        if (!isTarget[i]) continue;
                        grad[i] += coef * bodySign[j];                 // ∂d/∂v on the body side
                        if (!touchedFlag[i]) { touchedFlag[i] = true; touched[touchedCount++] = i; }
                    }
                    int[] headAtom = rules.headAtom[ri];
                    double[] headSign = rules.headSign[ri];
                    for (int j = 0; j < headAtom.length; j++) {
                        int i = headAtom[j];
                        if (!isTarget[i]) continue;
                        grad[i] -= coef * headSign[j];                 // ∂d/∂v on the head side
                        if (!touchedFlag[i]) { touchedFlag[i] = true; touched[touchedCount++] = i; }
                    }
                }

                for (int k = 0; k < touchedCount; k++) {
                    int i = touched[k];
                    values[i] = clip01(values[i] - lr * grad[i]);
                    grad[i] = 0.0;
                    touchedFlag[i] = false;
                }
                steps++;
            }

            epoch++;
            double objective = rules.objective(values);
            if (objective < bestObjective) {
                boolean significant = (bestObjective - objective) >= tolerance;
                bestObjective = objective;
                bestValues = values.clone();
                noImprove = significant ? 0 : noImprove + 1;
            } else {
                noImprove++;
            }
            if (noImprove >= PATIENCE) {
                converged = true;
                break;
            }
        }

        return new HlMrfMapInference.Result(toMap(atoms, bestValues), ground, steps, bestObjective, converged);
    }

    private static Map<String, Double> toMap(List<String> atoms, double[] values) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < atoms.size(); i++) map.put(atoms.get(i), values[i]);
        return map;
    }

    /** Deterministic in-place Fisher–Yates shuffle. */
    private static void shuffle(int[] a, Random rng) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
    }

    private static double clip01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Sparse compiled form of the ground rules: per rule, the atom indices and signs for the
     * body and head, the folded Łukasiewicz constants, the effective weight (hard ⇒ hardWeight)
     * and whether the potential is squared.
     */
    private static final class Compiled {
        final int[][] bodyAtom;
        final int[][] headAtom;
        final double[][] bodySign;
        final double[][] headSign;
        final double[] bodyConst;
        final double[] headConst;
        final double[] weight;
        final boolean[] squared;

        Compiled(List<GroundRule> ground, Map<String, Integer> index, double hardWeight) {
            int r = ground.size();
            bodyAtom = new int[r][];
            headAtom = new int[r][];
            bodySign = new double[r][];
            headSign = new double[r][];
            bodyConst = new double[r];
            headConst = new double[r];
            weight = new double[r];
            squared = new boolean[r];

            for (int ri = 0; ri < r; ri++) {
                GroundRule rule = ground.get(ri);
                int nb = rule.body().size();
                bodyAtom[ri] = new int[nb];
                bodySign[ri] = new double[nb];
                int negB = 0;
                for (int j = 0; j < nb; j++) {
                    GroundRule.Lit lit = rule.body().get(j);
                    bodyAtom[ri][j] = index.get(lit.atomKey());
                    bodySign[ri][j] = lit.negated() ? -1.0 : 1.0;
                    if (lit.negated()) negB++;
                }
                bodyConst[ri] = nb == 0 ? 1.0 : (negB - (nb - 1));

                int nh = rule.head().size();
                headAtom[ri] = new int[nh];
                headSign[ri] = new double[nh];
                int negH = 0;
                for (int j = 0; j < nh; j++) {
                    GroundRule.Lit lit = rule.head().get(j);
                    headAtom[ri][j] = index.get(lit.atomKey());
                    headSign[ri][j] = lit.negated() ? -1.0 : 1.0;
                    if (lit.negated()) negH++;
                }
                headConst[ri] = negH;

                weight[ri] = rule.hard() ? hardWeight : rule.weight();
                squared[ri] = rule.hard() || rule.squared();
            }
        }

        /** Łukasiewicz body conjunction: {@code max(0, Σ sign·v + bodyConst)}. */
        double bodyTruth(int ri, double[] v) {
            double sum = bodyConst[ri];
            int[] atom = bodyAtom[ri];
            double[] sign = bodySign[ri];
            for (int j = 0; j < atom.length; j++) sum += sign[j] * v[atom[j]];
            return Math.max(0.0, sum);
        }

        /** Łukasiewicz head disjunction: {@code min(1, Σ sign·v + headConst)}; empty head ⇒ 0. */
        double headTruth(int ri, double[] v) {
            if (headAtom[ri].length == 0) return 0.0;
            double sum = headConst[ri];
            int[] atom = headAtom[ri];
            double[] sign = headSign[ri];
            for (int j = 0; j < atom.length; j++) sum += sign[j] * v[atom[j]];
            return Math.min(1.0, sum);
        }

        double distance(int ri, double[] v) {
            return Math.max(0.0, bodyTruth(ri, v) - headTruth(ri, v));
        }

        double objective(double[] v) {
            double sum = 0.0;
            for (int ri = 0; ri < weight.length; ri++) {
                double d = distance(ri, v);
                if (d <= 0.0) continue;
                sum += weight[ri] * (squared[ri] ? d * d : d);
            }
            return sum;
        }
    }
}
