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

import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.config.Adam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>The true noisy-OR posterior for a single parent with leak {@code λ} is:
 * <pre>
 *   predicted = 1 − (1 − λ) · (1 − s · pParent)
 *             = λ + (1 − λ) · s · pParent
 * </pre>
 * For multiple parents the product generalises over all parent contributions, but in the
 * {@code [E, M]} tensor layout each column is one independent edge, so the element-wise
 * formula applies per cell. The MSE gradient w.r.t. {@code s} via autodiff is:
 * <pre>
 *   ∂predicted/∂s = (1 − λ) · pParent
 *   ∂L/∂s = 2 · mean_X[ (predicted_X − target_X) · (1 − λ) · pParent_X ]
 * </pre>
 * This is exactly what {@link MebnWeightLearner#analyticGradient} computes (the
 * {@code (1 − p_c) / (1 − s · p_par + ε)} factor collapses to {@code (1 − λ)} when the
 * posteriors are produced by the same noisy-OR formula), so the two agree to autodiff
 * precision (≤ 1e-6 relative error on the single-edge fixture).
 *
 * <h3>SameDiff graph</h3>
 * <pre>
 *   Trainable: s = SDVariable [1, M]                (one strength per learnable MFrag edge)
 *   Constants (per epoch):
 *     pParent = INDArray [E, M]                     (posterior of parent RV for each entity×edge)
 *     target  = INDArray [E, M]                     (target value for child RV for each entity×edge)
 *
 *   Forward (true noisy-OR):
 *     sPP         = s · pParent                     [E, M]  (broadcast)
 *     oneMinusSPP = 1 − sPP                         [E, M]
 *     scaledInhib = (1 − leak) · oneMinusSPP        [E, M]
 *     predicted   = 1 − scaledInhib                 [E, M]
 *     residual    = predicted − target              [E, M]
 *     loss        = mean(residual²)                 scalar MSE
 * </pre>
 * {@code calculateGradients("s")} returns {@code ∂loss/∂s}, shape {@code [1, M]} → flattened to {@code [M]}.
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

    /**
     * Leakage term: must equal {@link NoisyOrCpt#DEFAULT_LEAK} so that the SameDiff forward graph
     * computes the same posterior that {@link ai.kompile.graph.reasoning.mebn.SSBNGenerator} uses
     * when building the SSBN CPTs.  Keeping these in sync is what makes the SameDiff autodiff
     * gradient agree with the {@link MebnWeightLearner#analyticGradient} oracle to floating-point
     * precision (see derivation in class Javadoc).
     */
    static final double LEAKAGE = NoisyOrCpt.DEFAULT_LEAK;

    // ── Adam hyper-parameters (same as RotatELearner) ─────────────────────────
    private static final double ADAM_BETA1 = 0.9;
    private static final double ADAM_BETA2 = 0.999;
    private static final double ADAM_EPS   = 1e-8;

    /** Package-private test seam for counting caller-owned SameDiff results released per step. */
    private static final AtomicLong EXECUTION_RESULT_CLOSES = new AtomicLong();

    static void resetExecutionResultCloseCountForTests() {
        EXECUTION_RESULT_CLOSES.set(0L);
    }

    static long executionResultCloseCountForTests() {
        return EXECUTION_RESULT_CLOSES.get();
    }

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

        // Extract current strengths into a mutable Java array (also the SameDiff initial value).
        double[] strengths = new double[M];
        for (int i = 0; i < M; i++) {
            MebnWeightLearner.Edge e = edges.get(i);
            strengths[i] = e.mfrag().getEdgeStrength(e.parent(), e.child());
        }

        AdamResources adamResources = null;
        INDArray sArr = null;
        try {
            // Adam optimizer: created once; moment state persists across all epochs.
            Adam adamConfig = new Adam(learningRate, ADAM_BETA1, ADAM_BETA2, ADAM_EPS);
            adamResources = newAdamUpdater(adamConfig, 2L * M);
            GradientUpdater<Adam> adamUpdater = adamResources.updater();
            // Live INDArray that Adam reads/writes; kept in sync with strengths[].
            sArr = createDoubleRow(strengths, M);

            for (int epoch = 0; epoch < Math.max(1, maxEpochs); epoch++) {
                // ONE targeted inference call yields the parent posteriors this epoch needs.
                Set<String> posteriorKeys = requiredPosteriorKeys(edges, observations);
                Map<String, Double> posteriors = inferSvc.inferVariables(graph, theory, Map.of(), posteriorKeys);

                // Build [E, M] tensors from the observations and posteriors.
                // For each edge m, we gather entity rows where the child RV prefix matches.
                // We use the FULL batch (no subsampling) — the tensor op handles all entities at once.
                TensorBatch batch = buildTensorBatch(edges, posteriors, observations);
                try {
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

                    // Adam step: update sArr in place, then project each element to [0,1].
                    INDArray gradArr = createDoubleRow(gradient, M);
                    try {
                        applyAdam(adamUpdater, sArr, gradArr, epoch);
                    } finally {
                        closeOwnedArrays(gradArr);
                    }
                    double[] updated = sArr.toDoubleVector();
                    for (int i = 0; i < M; i++) {
                        strengths[i] = Math.min(1.0, Math.max(0.0, updated[i]));
                        sArr.putScalar(i, strengths[i]);
                    }

                    // Write updated strengths back to the theory.
                    for (int i = 0; i < M; i++) {
                        MebnWeightLearner.Edge e = edges.get(i);
                        e.mfrag().setEdgeStrength(e.parent(), e.child(), strengths[i]);
                    }

                    log.debug("SameDiffMebnStrengthLearner epoch {}: loss={}", epoch, loss);
                } finally {
                    // buildTensorBatch owns these roots; sdGradient borrows them and copies
                    // constants into its short-lived graph before closing it.
                    batch.close();
                }
            }
        } finally {
            // The updater retains m/v views into this root state buffer, so keep the root alive
            // until training is complete and then release it exactly once.
            closeOwnedArrays(sArr);
            if (adamResources != null) {
                closeOwnedArrays(adamResources.state());
            }
        }

        return theory;
    }

    // ─── SameDiff gradient (production) ───────────────────────────────────────

    /**
     * Compute the MSE gradient w.r.t. all M edge strengths in one SameDiff forward+backward pass.
     *
     * <p>Graph (true noisy-OR):
     * {@code predicted = 1 − (1 − leak) · (1 − s[1,M] · pParent[E,M])},
     * {@code loss = mean((predicted - target)^2)}. Autodiff gives {@code ∂loss/∂s} in shape
     * {@code [1,M]} (flattened to {@code [M]}), matching the scalar loop in
     * {@link MebnWeightLearner#analyticGradient} to autodiff precision.
     *
     * @param strengths current strength values {@code s[m]}, length M
     * @param batch     pre-built [E, M] tensor pair (pParent, target), borrowed for this call
     * @return gradient array of length M
     */
    public static double[] sdGradient(double[] strengths, TensorBatch batch) {
        int M = strengths.length;
        SameDiff sd = null;
        INDArray sArr = null;
        INDArray pParentGraph = null;
        INDArray targetGraph = null;
        INDArray lossArray = null;
        Map<String, INDArray> grads = null;
        INDArray cast = null;
        Throwable operationFailure = null;
        try {
            sd = SameDiff.create();

            // Trainable: s [M]. This array is owned by the graph for this call.
            sArr = createDoubleRow(strengths, M);
            SDVariable sVar = sd.var(VAR_S, sArr);

            // Constants: copy the caller-owned TensorBatch roots before SameDiff marks its
            // constants non-closeable. The graph can therefore close only its own copies.
            pParentGraph = copyAsDouble(batch.pParent());
            targetGraph = copyAsDouble(batch.target());
            SDVariable pParVar  = sd.constant(CONST_PPAR, pParentGraph);
            SDVariable targetVar = sd.constant(CONST_TGT, targetGraph);

            // True noisy-OR forward graph:
            //   predicted[e,m] = 1 − (1 − leak) · (1 − s[m] · pParent[e,m])
            //   which equals   = leak + (1 − leak) · s · pParent

            // Step 1: s[1,M] · pParent[E,M] → [E,M]  (broadcasts s across E rows)
            SDVariable sPP = sVar.mul("sPP", pParVar);

            // Step 2: 1 − s · pParent  → [E,M]
            SDVariable oneMinusSPP = sPP.rsub("oneMinusSPP", 1.0);

            // Step 3: (1−leak) · (1 − s · pParent)  → [E,M]
            SDVariable scaledInhibit = oneMinusSPP.mul("scaledInhibit", 1.0 - LEAKAGE);

            // Step 4: predicted = 1 − (1−leak)·(1−s·pParent)  → [E,M]
            SDVariable predicted = scaledInhibit.rsub(OP_PRED, 1.0);

            // residual [E, M] = predicted - target
            SDVariable residual = predicted.sub(OP_RESID, targetVar);

            // loss = mean(residual^2) — use var.mean(name) pattern (matching RotatELearner)
            SDVariable residSq = residual.mul("residSq", residual);
            residSq.mean(LOSS);   // scalar MSE loss named LOSS
            sd.setLossVariables(LOSS);

            // Standard SameDiff execution returns independent caller-owned output copies.
            lossArray = sd.outputSingle(Map.of(), LOSS);
            grads = sd.calculateGradients(Map.of(), VAR_S);

            INDArray gArr = grads.get(VAR_S);
            if (gArr == null) {
                return new double[M];
            }
            // Copy the gradient values before releasing the returned gradient and its reshape
            // alias. The alias is included in result cleanup so its DataBuffer is closed once.
            INDArray flat = gArr.reshape(M);
            cast = flat.castTo(DataType.DOUBLE);
            return cast.toDoubleVector();
        } catch (RuntimeException | Error e) {
            operationFailure = e;
            throw e;
        } finally {
            RuntimeException cleanupFailure = closeExecutionResults(
                    lossArray, grads, cast, sArr, pParentGraph, targetGraph);
            // close() tears down sessions/plan caches and graph-owned constants. The explicit
            // guards below are idempotent fallbacks for partial graph construction or older
            // backends that leave an owned root unregistered after a failed op.
            cleanupFailure = appendFailure(cleanupFailure, closeSameDiff(sd));
            // Root cleanup must still run when graph teardown fails; SameDiff may have left
            // partially-registered roots behind after a failed operation.
            closeOwnedArrays(sArr, pParentGraph, targetGraph);
            if (cleanupFailure != null) {
                if (operationFailure != null) {
                    operationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    // ─── Adam helpers (mirrors RotatELearner pattern) ─────────────────────────

    /**
     * Instantiate an ND4J Adam {@link GradientUpdater} with fresh zero-initialised moment state.
     *
     * @param config   shared Adam hyper-parameter config
     * @param stateLen {@code 2 × parameter-count} (m‖v split)
     * @return ready Adam updater and its owned root state buffer
     */
    @SuppressWarnings("unchecked")
    private static AdamResources newAdamUpdater(Adam config, long stateLen) {
        INDArray state = Nd4j.zeros(DataType.DOUBLE, 1, stateLen);
        try {
            return new AdamResources(config.instantiate(state, true), state);
        } catch (RuntimeException | Error e) {
            closeOwnedArrays(state);
            throw e;
        }
    }

    /**
     * Apply one Adam step to {@code param} in place.
     * The updater rewrites {@code grad} with the bias-corrected update, then {@code param −= update}.
     *
     * @param updater   Adam updater (owns moment state)
     * @param param     parameter array updated in place
     * @param grad      gradient for this step ({@code null} ⇒ no-op)
     * @param iteration 0-based Adam step index
     */
    private static void applyAdam(GradientUpdater<Adam> updater, INDArray param,
                                   INDArray grad, int iteration) {
        if (grad == null) {
            return;
        }
        INDArray update = grad.castTo(DataType.DOUBLE);
        try {
            updater.applyUpdater(update, iteration, 0);
            param.subi(update);
        } finally {
            if (update != grad) {
                closeOwnedArrays(update);
            }
        }
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
                double pred = LEAKAGE + (1.0 - LEAKAGE) * strengths[m] * pp[e][m];
                double diff = pred - tg[e][m];
                sum += diff * diff;
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    // ─── Tensor batch builder ──────────────────────────────────────────────────

    /**
     * Return the posterior variable keys required to build the tensor batch for these observations.
     *
     * <p>The vectorized loss consumes parent posteriors only. Child targets come from the supplied
     * observations, so querying every SSBN variable is unnecessary and can dominate post-crawl
     * enrichment on large grounded theories.</p>
     */
    public static Set<String> requiredPosteriorKeys(List<MebnWeightLearner.Edge> edges,
                                                     Map<String, Double> observations) {
        Set<String> keys = new LinkedHashSet<>();
        if (edges == null || observations == null || observations.isEmpty()) {
            return keys;
        }
        for (Map.Entry<String, Double> obs : observations.entrySet()) {
            String obsKey = obs.getKey();
            for (MebnWeightLearner.Edge edge : edges) {
                if (obsKey.startsWith(edge.child())) {
                    keys.add(edge.parent() + obsKey.substring(edge.child().length()));
                }
            }
        }
        return keys;
    }

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

        INDArray pParND = null;
        INDArray tgtND = null;
        try {
            pParND = Nd4j.create(pParArr);
            tgtND = Nd4j.create(tgtArr);
            return new TensorBatch(pParND, tgtND, pParArr, tgtArr);
        } catch (RuntimeException | Error e) {
            closeOwnedArrays(pParND, tgtND);
            throw e;
        }
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
     *
     * <p>The ND4J roots are owned by this batch. {@link #sdGradient(double[], TensorBatch)} borrows
     * them and copies graph constants; callers should close the batch when its Java mirrors are no
     * longer needed.</p>
     */
    public record TensorBatch(INDArray pParent, INDArray target,
                       double[][] pParentMatrix, double[][] targetMatrix)
            implements AutoCloseable {
        public int rowCount() {
            return (int) pParent.size(0);
        }

        static TensorBatch empty(int M) {
            INDArray z = Nd4j.zeros(DataType.DOUBLE, 0, M);
            return new TensorBatch(z, z, new double[0][M], new double[0][M]);
        }

        /** Release the batch roots once the Java mirrors are no longer needed. */
        @Override
        public void close() {
            closeOwnedArrays(pParent, target);
        }
    }

    private record AdamResources(GradientUpdater<Adam> updater, INDArray state) {}

    /** Create an owned compact DOUBLE row without retaining the source array or its view. */
    private static INDArray createDoubleRow(double[] values, int width) {
        INDArray source = null;
        INDArray row = null;
        try {
            source = Nd4j.createFromArray(values);
            row = source.reshape(1, width);
            return copyAsDouble(row);
        } finally {
            // copyAsDouble always returns a separate root, so these construction roots are
            // safe to release even when the caller is about to bind the returned array.
            closeOwnedArrays(source, row);
        }
    }

    /** Copy a caller-owned array into an independent DOUBLE root for a SameDiff graph. */
    private static INDArray copyAsDouble(INDArray source) {
        INDArray cast = source.castTo(DataType.DOUBLE);
        try {
            return cast.dup();
        } finally {
            if (cast != source) {
                closeOwnedArrays(cast);
            }
        }
    }

    /**
     * Release standard-path output/gradient copies without changing closeability or ownership of
     * views. Identity and DataBuffer de-duplication protects aliases and graph-owned roots.
     */
    private static RuntimeException closeExecutionResults(
            INDArray lossArray,
            Map<String, INDArray> gradients,
            INDArray additionalResult,
            INDArray... protectedRoots) {
        Set<INDArray> protectedArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DataBuffer> protectedBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (INDArray root : protectedRoots) {
            if (root == null || !protectedArrays.add(root)) {
                continue;
            }
            DataBuffer data = dataBufferOf(root);
            if (data != null) {
                protectedBuffers.add(data);
            }
        }

        Set<INDArray> seenArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DataBuffer> seenBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        RuntimeException failure = null;
        failure = appendFailure(failure, closeExecutionResult(lossArray,
                protectedArrays, protectedBuffers, seenArrays, seenBuffers));
        if (gradients != null) {
            for (INDArray gradient : gradients.values()) {
                failure = appendFailure(failure, closeExecutionResult(gradient,
                        protectedArrays, protectedBuffers, seenArrays, seenBuffers));
            }
        }
        failure = appendFailure(failure, closeExecutionResult(additionalResult,
                protectedArrays, protectedBuffers, seenArrays, seenBuffers));
        return failure;
    }

    private static RuntimeException appendFailure(RuntimeException first, RuntimeException next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static RuntimeException closeExecutionResult(
            INDArray array,
            Set<INDArray> protectedArrays,
            Set<DataBuffer> protectedBuffers,
            Set<INDArray> seenArrays,
            Set<DataBuffer> seenBuffers) {
        if (array == null || protectedArrays.contains(array) || !seenArrays.add(array)
                || array.wasClosed()) {
            return null;
        }
        DataBuffer data = dataBufferOf(array);
        if (data != null && (data.wasClosed() || protectedBuffers.contains(data)
                || !seenBuffers.add(data))) {
            return null;
        }
        // Returned outputs are already caller-owned. Do not force-close a borrowed view.
        if (!array.closeable()) {
            return null;
        }
        try {
            array.close();
            if (array.wasClosed()) {
                EXECUTION_RESULT_CLOSES.incrementAndGet();
            }
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static DataBuffer dataBufferOf(INDArray array) {
        try {
            return array == null ? null : array.data();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @FunctionalInterface
    interface SameDiffCloseHookForTests {
        void close(SameDiff graph);
    }

    private static final SameDiffCloseHookForTests DEFAULT_SAME_DIFF_CLOSE_HOOK = SameDiff::close;
    private static volatile SameDiffCloseHookForTests sameDiffCloseHook = DEFAULT_SAME_DIFF_CLOSE_HOOK;

    static void setSameDiffCloseHookForTests(SameDiffCloseHookForTests hook) {
        sameDiffCloseHook = hook == null ? DEFAULT_SAME_DIFF_CLOSE_HOOK : hook;
    }

    static void resetSameDiffCloseHookForTests() {
        sameDiffCloseHook = DEFAULT_SAME_DIFF_CLOSE_HOOK;
    }

    private static RuntimeException closeSameDiff(SameDiff sd) {
        if (sd == null) {
            return null;
        }
        RuntimeException failure = null;
        SameDiff gradient = null;
        try {
            gradient = sd.getFunction("grad");
        } catch (RuntimeException e) {
            failure = e;
        }
        // The gradient graph owns its own execution session. Always attempt it first, then
        // attempt the parent even when gradient lookup/teardown fails.
        if (gradient != null && gradient != sd) {
            failure = appendFailure(failure, closeSameDiffPart(gradient));
        }
        failure = appendFailure(failure, closeSameDiffPart(sd));
        return failure;
    }

    private static RuntimeException closeSameDiffPart(SameDiff graph) {
        try {
            sameDiffCloseHook.close(graph);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * Close only roots owned by this class.  Identity de-duplication covers both repeated
     * references (TensorBatch.empty) and views sharing one DataBuffer (Adam m/v state).
     */
    private static void closeOwnedArrays(INDArray... arrays) {
        Set<INDArray> seenArrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<DataBuffer> seenBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (INDArray array : arrays) {
            if (array == null || !seenArrays.add(array) || array.wasClosed()) {
                continue;
            }
            DataBuffer data;
            try {
                data = array.data();
            } catch (RuntimeException e) {
                data = null;
            }
            if (data != null && (data.wasClosed() || seenBuffers.contains(data))) {
                continue;
            }
            try {
                // SameDiff constants/variables deliberately mark their buffers non-closeable;
                // these arrays are local roots owned by this class, so restore closeability only
                // for the final deterministic release. Attached workspace arrays remain guarded
                // by closeable() and are left to their workspace owner.
                if (data != null) {
                    data.setConstant(false);
                }
                array.setCloseable(true);
                if (array.closeable()) {
                    if (data != null && !seenBuffers.add(data)) {
                        continue;
                    }
                    array.close();
                }
            } catch (RuntimeException e) {
                log.debug("SameDiffMebnStrengthLearner array cleanup failed: {}", e.getMessage());
            }
        }
    }
}
