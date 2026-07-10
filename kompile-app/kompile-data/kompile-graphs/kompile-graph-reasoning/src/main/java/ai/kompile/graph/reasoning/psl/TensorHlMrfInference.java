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

import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vectorized HL-MRF MAP inference using ND4J matrix operations — the same projected-gradient
 * algorithm as {@link ScalarHlMrfInference}, but with the per-iteration work expressed as dense
 * matrix products so it runs on whatever ND4J backend is active (CPU {@code nd4j-native} or GPU
 * {@code nd4j-cuda}). Selected by {@link HlMrfMapInference#chooseSolver(int)} for large /
 * collective-inference programs where the matrix throughput outweighs op-dispatch overhead.
 *
 * <h3>Formulation</h3>
 * Atoms are indexed {@code 0..A-1} into a column vector {@code v}. Each ground rule {@code r}
 * contributes two signed-incidence rows:
 * <pre>
 *   bodyTruth = relu(Mb·v + bodyConst)              // Łukasiewicz conjunction max(0, Σ lit − (n−1))
 *   headTruth = min(1, Mh·v + headConst)            // Łukasiewicz disjunction min(1, Σ lit)
 *   d         = relu(bodyTruth − headTruth)         // distance to satisfaction
 *   E         = Σ w ⊙ (sq ⊙ d² + (1−sq) ⊙ d)
 * </pre>
 * where {@code Mb[r,i] = ±1} for a positive/negated body literal (and {@code bodyConst} folds in
 * the negation offset and {@code −(n−1)} term; an empty body is the constant {@code 1}). Because,
 * wherever {@code d > 0}, both inner clamps are inactive, the gradient is exactly
 * {@code ∂E/∂v = (Mb − Mh)ᵀ · g} with {@code g = 1[d>0] ⊙ w ⊙ (2·sq⊙d + (1−sq))} — the matrix form
 * of the scalar solver's analytic gradient, so the two agree up to float precision.
 *
 * <p><b>Note:</b> the incidence matrices are dense ({@code O(R·A)}), which suits mid-scale programs;
 * a sparse gather / segment-sum formulation (and SameDiff autodiff / SGD mini-batching) is the
 * natural follow-up for graph-scale collective inference and weight learning.</p>
 */
public class TensorHlMrfInference implements HlMrfSolver {

    private static volatile Boolean available;

    /** Whether an ND4J backend is loadable in this process; the tensor path is skipped if not. */
    public static boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            synchronized (TensorHlMrfInference.class) {
                a = available;
                if (a == null) {
                    try {
                        Nd4j.ones(DataType.FLOAT, 1, 1);
                        a = Boolean.TRUE;
                    } catch (Throwable t) {
                        a = Boolean.FALSE;
                    }
                    available = a;
                }
            }
        }
        return a;
    }

    @Override
    public HlMrfMapInference.Result solve(PslProgram program, List<GroundRule> ground,
                                          int maxIterations, double tolerance, double hardWeight) {
        List<String> atoms = new ArrayList<>(program.atomKeys());
        int a = atoms.size();
        int r = ground.size();

        if (a == 0 || r == 0) {
            // Nothing to optimize: report the snapshot (targets at their neutral 0.5).
            Map<String, Double> values = program.valueSnapshot();
            for (String t : program.targetKeys()) values.put(t, 0.5);
            return new HlMrfMapInference.Result(values, ground, 0, 0.0, true);
        }

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < a; i++) index.put(atoms.get(i), i);

        // Initial assignment + target mask.
        float[][] v0 = new float[a][1];
        float[][] targetMask = new float[a][1];
        for (int i = 0; i < a; i++) {
            String key = atoms.get(i);
            boolean observed = program.isObserved(key);
            v0[i][0] = (float) (observed ? program.value(key) : 0.5);
            targetMask[i][0] = observed ? 0f : 1f;
        }

        // Signed incidence matrices and per-rule constants.
        float[][] mb = new float[r][a];
        float[][] mh = new float[r][a];
        float[][] bodyConst = new float[r][1];
        float[][] headConst = new float[r][1];
        float[][] w = new float[r][1];
        float[][] sq = new float[r][1];

        for (int ri = 0; ri < r; ri++) {
            GroundRule rule = ground.get(ri);
            int bodyNeg = 0;
            for (GroundRule.Lit lit : rule.body()) {
                mb[ri][index.get(lit.atomKey())] += lit.negated() ? -1f : 1f;
                if (lit.negated()) bodyNeg++;
            }
            bodyConst[ri][0] = rule.body().isEmpty()
                    ? 1f                                       // empty body ⇒ antecedent always true
                    : bodyNeg - (rule.body().size() - 1);
            int headNeg = 0;
            for (GroundRule.Lit lit : rule.head()) {
                mh[ri][index.get(lit.atomKey())] += lit.negated() ? -1f : 1f;
                if (lit.negated()) headNeg++;
            }
            headConst[ri][0] = headNeg;                        // empty head ⇒ 0
            w[ri][0] = (float) (rule.hard() ? hardWeight : rule.weight());
            sq[ri][0] = (rule.hard() || rule.squared()) ? 1f : 0f;
        }

        INDArray mbND = Nd4j.create(mb);
        INDArray mhND = Nd4j.create(mh);
        INDArray ctND = mbND.sub(mhND).transpose();           // (A,R), constant across iterations
        INDArray bodyConstND = Nd4j.create(bodyConst);
        INDArray headConstND = Nd4j.create(headConst);
        INDArray wND = Nd4j.create(w);
        INDArray sqND = Nd4j.create(sq);
        INDArray oneMinusSqND = Nd4j.ones(DataType.FLOAT, r, 1).sub(sqND);
        INDArray targetMaskND = Nd4j.create(targetMask);
        INDArray v = Nd4j.create(v0);

        double objective = objective(distance(mbND, mhND, bodyConstND, headConstND, v), wND, sqND, oneMinusSqND);
        double step = 0.1;
        boolean converged = false;
        int iter = 0;

        for (; iter < maxIterations; iter++) {
            INDArray d = distance(mbND, mhND, bodyConstND, headConstND, v);
            INDArray grad = gradient(d, ctND, wND, sqND, oneMinusSqND, targetMaskND);
            double gradNorm2 = grad.mul(grad).sumNumber().doubleValue();
            if (gradNorm2 <= tolerance * tolerance) {
                converged = true;
                break;
            }

            boolean accepted = false;
            for (int ls = 0; ls < 50; ls++) {
                INDArray candidate = clip01(v.sub(grad.mul(step)));
                double candidateObjective = objective(
                        distance(mbND, mhND, bodyConstND, headConstND, candidate), wND, sqND, oneMinusSqND);
                if (candidateObjective < objective) {
                    double maxChange = Nd4j.math().abs(candidate.sub(v)).maxNumber().doubleValue();
                    boolean tinyProgress = (objective - candidateObjective) < tolerance && maxChange < tolerance;
                    v = candidate;
                    objective = candidateObjective;
                    accepted = true;
                    if (tinyProgress) converged = true;
                    break;
                }
                step *= 0.5;
            }
            if (!accepted) {
                converged = true;
                break;
            }
            if (converged) break;
            step *= 1.5;
        }

        // Bulk-read the solution vector once (one host-sync on CUDA) instead of one JNI call per atom.
        double[] vArr = v.toDoubleVector();
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < a; i++) values.put(atoms.get(i), vArr[i]);
        return new HlMrfMapInference.Result(values, ground, iter, objective, converged);
    }

    /** d = relu(relu(Mb·v + bodyConst) − min(1, Mh·v + headConst)). */
    private static INDArray distance(INDArray mb, INDArray mh, INDArray bodyConst, INDArray headConst, INDArray v) {
        INDArray bodyTruth = Nd4j.nn().relu(mb.mmul(v).add(bodyConst), 0.0);
        INDArray headSum = mh.mmul(v).add(headConst);
        // min(1, headSum): clamp the upper bound only (lower bound is a finite −MAX_VALUE no-op).
        INDArray headTruth = Nd4j.math().clipByValue(headSum, -Double.MAX_VALUE, 1.0);
        return Nd4j.nn().relu(bodyTruth.sub(headTruth), 0.0);
    }

    private static double objective(INDArray d, INDArray w, INDArray sq, INDArray oneMinusSq) {
        INDArray pot = w.mul(sq.mul(d).mul(d).add(oneMinusSq.mul(d)));
        return pot.sumNumber().doubleValue();
    }

    private static INDArray gradient(INDArray d, INDArray ct, INDArray w, INDArray sq,
                                     INDArray oneMinusSq, INDArray targetMask) {
        INDArray mask = d.gt(0.0).castTo(DataType.FLOAT);
        INDArray g = mask.mul(w).mul(sq.mul(d).mul(2.0).add(oneMinusSq));   // (R,1)
        return ct.mmul(g).mul(targetMask);                                  // (A,1)
    }

    /** Clip into [0,1] via a single fused ClipByValue op (replaces the old two-relu trick). */
    private static INDArray clip01(INDArray x) {
        return Nd4j.math().clipByValue(x, 0.0, 1.0);
    }
}
