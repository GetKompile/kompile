/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SameDiff-backed MEBN noisy-OR edge-strength learner — the <em>production gradient path</em>
 * that replaces the per-edge Java analytic loop in {@link MebnWeightLearner}.
 *
 * <h3>Algorithm</h3>
 * <p>Uses the same analytic noisy-OR MSE gradient as {@link MebnWeightLearner#analyticGradient}
 * but expresses it as a vectorized SameDiff graph over an {@code [E, M]} tensor
 * (E = entity observations, M = learnable edges) rather than a scalar Java loop over E×M pairs.
 * This fuses the entire gradient computation into a single forward+backward pass on whatever
 * ND4J backend is active (CPU nd4j-native or GPU nd4j-cuda).</p>
 *
 * <h3>Gradient derivation</h3>
 * <p>For the noisy-OR posterior approximation (linear marginal):
 * <pre>
 *   predicted_X = s * pParent_X + eps   (ε ≈ 0 leakage; strength s clamps the noisy-OR)
 * </pre>
 * and MSE loss {@code L = mean((predicted - target)^2)}, the gradient w.r.t. {@code s} is:
 * <pre>
 *   ∂L/∂s = 2 · mean_X[ (s * pParent_X - (target_X - eps)) * pParent_X ]
 *          = 2 · mean_X[ (predicted_X - target_X) * pParent_X ]
 * </pre>
 * This is the exact noisy-OR marginal-approximation gradient — the same formula implemented
 * in {@link MebnWeightLearner#analyticGradient} but vectorized across all E entities and M
 * edges simultaneously by SameDiff autodiff.
 *
 * <h3>SameDiff graph</h3>
 * <pre>
 *   Trainable: s = SDVariable [M]                   (one strength per learnable MFrag edge)
 *   Constants (per epoch):
 *     pParent = INDArray [E, M]                     (posterior of parent RV for each entity×edge)
 *     target  = INDArray [E, M]                     (target value for child RV for each entity×edge)
 *
 *   Forward:
 *     predicted = s[1, M] * pParent[E, M]           (broadcast multiply)
 *     residual  = predicted - target                 [E, M]
 *     loss      = mean(residual^2)                   scalar MSE
 * </pre>
 * {@code calculateGradients("s")} returns {@code ∂loss/∂s}, shape {@code [M]}.
 *
 * <h3>Oracle verification</h3>
 * <p>The Java analytic gradient {@link MebnWeightLearner#analyticGradient} is kept as a
 * <em>numerical oracle</em> used in tests ({@link SameDiffMebnStrengthLearnerTest}) to verify
 * that the SameDiff gradient matches it within tolerance. It is not called on the production path.
 *
 * <h3>No availability gate</h3>
 * <p>ND4J + SameDiff are always-available compile-time dependencies of {@code kompile-graph-reasoning}
 * (see {@code nd4j-api} in the module pom). No {@code isAvailable()} guard — the ND4J backend
 * (CPU or GPU) is resolved at runtime by the loaded nd4j native jar. {@code MebnWeightLearner}
 * delegates unconditionally to this class as the sole production gradient implementation.</p>
 *
 * @see MebnWeightLearner
 */
public final class SameDiffMebnStrengthLearner {

    private static final Logger log = LoggerFactory.getLogger(SameDiffMebnStrengthLearner.class);

    private static final String VAR_S       = "s";
    private static final String CONST_PPAR  = "pParent";
    private static final String CONST_TGT   = "target";
    private static final String OP_PRED     = "predicted";
    private static final String OP_RESID    = "residual";
    private static final String LOSS        = "loss";

    /** Small leakage added so s=0 still gives a non-zero (but tiny) posterior floor. */
    static final double LEAKAGE = 1e-3;

    private SameDiffMebnStrengthLearner() {}

    // ─── Production entry point ────────────────────────────────────────────────

    /**
     * Learn the noisy-OR edge strengths of {@code theory} toward the given {@code observations}
     * using a vectorized SameDiff forward+backward pass.
     *
     * <p>This is the <em>only</em> gradient path — not gated behind isAvailable(). The method
     * mutates the theory's edge strengths in place (same contract as the scalar loop it replaces)
     * and returns the same theory object.
     *
     * @param theory       the MTheory to fit (mutated in place)
     * @param graph        the reasoning graph the theory grounds over
     * @param observations grounded RV name → target value in [0, 1]
     * @param maxEpochs    gradient-descent steps (≥1)
     * @param learningRate SGD step size
     * @param seed         RNG seed for minibatch subsampling (not used here — full batch)
     * @return the same theory with updated edge strengths
     */
    public static MTheory learn(MTheory theory, ReasoningGraph graph,
                                Map<String, Double> observations, int maxEpochs,
                                double learningRate, long seed) {
        if (theory == null || graph == null || observations == null || observations.isEmpty()) {
            return theory;
        }

        // Collect learnable edges (identical logic to MebnWeightLearner.collectEdges)
        List<MebnWeightLearner.Edge> edges = collectEdges(theory);
        if (edges.isEmpty()) {
            return theory;
        }

        int M = edges.size();

        // Build entity-observation arrays.  Each column corresponds to one MFrag edge;
        // each row corresponds to one entity observation that matches the child RV prefix.
        MebnInferenceService inferSvc = new MebnInferenceService();

        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                learningRate, 0.0, ProjectedGradientOptimizer.unitInterval());

        // Extract current strengths into a mutable Java array (also the SameDiff initial value).
        double[] strengths = new double[M];
        for (int i = 0; i < M; i++) {
            MebnWeightLearner.Edge e = edges.get(i);
            strengths[i] = e.mfrag().getEdgeStrength(e.parent(), e.child());
        }

        for (int epoch = 0; epoch < Math.max(1, maxEpochs); epoch++) {
            // ONE inference call yields all posteriors for this epoch.
            Map<String, Double> posteriors = inferSvc.infer(graph, theory, Map.of());

            // Build [E, M] tensors from the observations and posteriors.
            // For each edge m, we gather entity rows where the child RV prefix matches.
            // We use the FULL batch (no subsampling) — the tensor op handles all entities at once.
            TensorBatch batch = buildTensorBatch(edges, posteriors, observations);
            if (batch.rowCount() == 0) {
                break;  // no matching observations
            }

            // MSE loss at current strengths (for early-exit check).
            double loss = computeLoss(strengths, batch);
            if (loss < 1e-9) {
                break;
            }

            // SameDiff gradient: ∂MSE/∂s for all M edges simultaneously.
            double[] gradient = sdGradient(strengths, batch);

            optimizer.step(strengths, gradient);

            // Write updated strengths back to the theory.
            for (int i = 0; i < M; i++) {
                MebnWeightLearner.Edge e = edges.get(i);
                e.mfrag().setEdgeStrength(e.parent(), e.child(), strengths[i]);
            }

            log.debug("SameDiffMebnStrengthLearner epoch {}: loss={}", epoch, loss);
        }

        return theory;
    }

    // ─── SameDiff gradient (production) ───────────────────────────────────────

    /**
     * Compute the MSE gradient w.r.t. all M edge strengths in one SameDiff forward+backward pass.
     *
     * <p>Graph: {@code predicted = s[1,M] * pParent[E,M]},
     * {@code loss = mean((predicted - target)^2)}. Autodiff gives {@code ∂loss/∂s} in shape
     * {@code [M]}, which is the vectorized equivalent of the scalar loop in
     * {@link MebnWeightLearner#analyticGradient}.
     *
     * @param strengths current strength values {@code s[m]}, length M
     * @param batch     pre-built [E, M] tensor pair (pParent, target)
     * @return gradient array of length M
     */
    public static double[] sdGradient(double[] strengths, TensorBatch batch) {
        int M = strengths.length;
        int E = batch.rowCount();

        SameDiff sd = SameDiff.create();

        // Trainable: s [M]
        INDArray sArr = Nd4j.createFromArray(strengths).reshape(1, M).castTo(DataType.DOUBLE);
        SDVariable sVar = sd.var(VAR_S, sArr.dup());

        // Constants: pParent [E, M] and target [E, M]
        SDVariable pParVar  = sd.constant(CONST_PPAR, batch.pParent().castTo(DataType.DOUBLE));
        SDVariable targetVar = sd.constant(CONST_TGT,  batch.target().castTo(DataType.DOUBLE));

        // predicted [E, M] = s[1, M] * pParent[E, M]  (broadcasts across E rows)
        SDVariable predicted = sVar.mul(OP_PRED, pParVar);

        // residual [E, M] = predicted - target
        SDVariable residual = predicted.sub(OP_RESID, targetVar);

        // loss = mean(residual^2) — use var.mean(name) pattern (matching RotatELearner)
        SDVariable residSq = residual.mul("residSq", residual);
        residSq.mean(LOSS);   // scalar MSE loss named LOSS
        sd.setLossVariables(LOSS);

        // Associate the live strength array, run forward pass, then backward.
        sd.associateArrayWithVariable(sArr, VAR_S);
        // Forward pass materialises the computation graph; constants are already bound via sd.constant().
        sd.outputSingle(Map.of(), LOSS);
        Map<String, INDArray> grads = sd.calculateGradients(Map.of(), VAR_S);

        INDArray gArr = grads.get(VAR_S);
        if (gArr == null) {
            return new double[M];
        }
        // Gradient shape from SameDiff is [1, M] (matches sArr shape); flatten to [M].
        INDArray flat = gArr.reshape(M).castTo(DataType.DOUBLE);
        double[] result = new double[M];
        for (int i = 0; i < M; i++) {
            result[i] = flat.getDouble(i);
        }
        return result;
    }

    // ─── Loss helper (Java, no SameDiff) ──────────────────────────────────────

    /**
     * Compute MSE loss at the current strengths without autodiff.
     * Used only for the early-exit check — not on the gradient path.
     */
    public static double computeLoss(double[] strengths, TensorBatch batch) {
        int M = strengths.length;
        int E = batch.rowCount();
        double[][] pp = batch.pParentMatrix();
        double[][] tg = batch.targetMatrix();
        double sum = 0.0;
        int count = 0;
        for (int e = 0; e < E; e++) {
            for (int m = 0; m < M; m++) {
                double pred = strengths[m] * pp[e][m];
                double diff = pred - tg[e][m];
                sum += diff * diff;
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    // ─── Tensor batch builder ──────────────────────────────────────────────────

    /**
     * Build the {@code [E, M]} pParent and target arrays from the posteriors and observations.
     *
     * <p>For each edge {@code m} (child prefix {@code cPfx}, parent prefix {@code pPfx}),
     * we gather every observation key that starts with {@code cPfx}. Entity E is the number
     * of distinct such observations across all edges (row-union). For entities that match
     * only some edges, the missing columns carry {@code pParent=0, target=0} (zero gradient
     * contribution, same as the scalar fallback returning 0 when no parent key matches).</p>
     */
    public static TensorBatch buildTensorBatch(List<MebnWeightLearner.Edge> edges,
                                        Map<String, Double> posteriors,
                                        Map<String, Double> observations) {
        int M = edges.size();

        // Union of all observation keys that match at least one edge's child prefix.
        List<String> rowKeys = new ArrayList<>();
        for (Map.Entry<String, Double> obs : observations.entrySet()) {
            String k = obs.getKey();
            for (MebnWeightLearner.Edge e : edges) {
                if (k.startsWith(e.child())) {
                    rowKeys.add(k);
                    break;
                }
            }
        }
        if (rowKeys.isEmpty()) {
            return TensorBatch.empty(M);
        }

        int E = rowKeys.size();
        double[][] pParArr = new double[E][M];
        double[][] tgtArr  = new double[E][M];

        for (int r = 0; r < E; r++) {
            String obsKey = rowKeys.get(r);
            for (int m = 0; m < M; m++) {
                MebnWeightLearner.Edge edge = edges.get(m);
                if (!obsKey.startsWith(edge.child())) {
                    // This observation does not touch this edge — zero contribution.
                    continue;
                }
                String parentKey = edge.parent() + obsKey.substring(edge.child().length());
                pParArr[r][m] = posteriors.getOrDefault(parentKey, 0.5);
                tgtArr[r][m]  = observations.getOrDefault(obsKey, 0.0);
            }
        }

        INDArray pParND = Nd4j.create(pParArr);
        INDArray tgtND  = Nd4j.create(tgtArr);
        return new TensorBatch(pParND, tgtND, pParArr, tgtArr);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Collect learnable edges from the theory (same logic as {@link MebnWeightLearner}). */
    public static List<MebnWeightLearner.Edge> collectEdges(MTheory theory) {
        List<MebnWeightLearner.Edge> edges = new ArrayList<>();
        for (MFrag mfrag : theory.getMFrags()) {
            for (String key : mfrag.getEdgeStrengths().keySet()) {
                int sep = key.indexOf("->");
                if (sep > 0) {
                    edges.add(new MebnWeightLearner.Edge(
                            mfrag, key.substring(0, sep), key.substring(sep + 2)));
                }
            }
        }
        return edges;
    }

    // ─── TensorBatch ──────────────────────────────────────────────────────────

    /**
     * Pre-built {@code [E, M]} tensor pair for one gradient step.
     *
     * @param pParent     ND4J array of shape {@code [E, M]} — parent posterior per entity×edge
     * @param target      ND4J array of shape {@code [E, M]} — child target per entity×edge
     * @param pParentMatrix plain Java mirror (avoids repeated ND4J toDoubleMatrix per loss check)
     * @param targetMatrix  plain Java mirror
     */
    public record TensorBatch(INDArray pParent, INDArray target,
                       double[][] pParentMatrix, double[][] targetMatrix) {
        public int rowCount() {
            return (int) pParent.size(0);
        }

        static TensorBatch empty(int M) {
            INDArray z = Nd4j.zeros(DataType.DOUBLE, 0, M);
            return new TensorBatch(z, z, new double[0][M], new double[0][M]);
        }
    }
}
