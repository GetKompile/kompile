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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Learns MEBN noisy-OR <em>edge strengths</em> by minibatch gradient descent.
 *
 * <h3>Production path</h3>
 * <p>{@link #learn} delegates to {@link SameDiffMebnStrengthLearner}, which implements the
 * gradient as a vectorized {@code [E × M]} SameDiff autodiff pass — one forward + one backward
 * per epoch, backend-agnostic (CPU or GPU). The SameDiff gradient is the MSE gradient of the
 * noisy-OR marginal-approximation model w.r.t. all M edge strengths simultaneously.
 *
 * <h3>Oracle / reference gradient ({@link #analyticGradient})</h3>
 * <p>The per-edge analytic gradient is retained as a <em>numerical oracle</em> used in tests
 * ({@link SameDiffMebnStrengthLearnerTest}) to verify that the SameDiff gradient matches it
 * within tolerance. It is NOT called on the production path.
 *
 * <h3>Analytic noisy-OR gradient derivation</h3>
 * <p>The gradient of the MSE loss w.r.t. a noisy-OR edge strength {@code s_{ij}} is derived
 * analytically from the noisy-OR posterior:
 *
 * <pre>
 *   p(child=TRUE) = 1 - ∏_k (1 - s_k * p(parent_k=TRUE))   [noisy-OR]
 *
 *   ∂p/∂s_{ij} = p(parent_j=TRUE) * ∏_{k≠j} (1 - s_k * p(parent_k=TRUE))
 *              = p(parent_j=TRUE) * (1 - p(child=TRUE)) / (1 - s_{ij} * p(parent_j=TRUE) + ε)
 * </pre>
 *
 * For a binary MSE loss {@code L = mean((p(child) - target)²)}:
 * <pre>
 *   ∂L/∂s_{ij} = 2 * mean_over_instances[ (p(child_k) - target_k) * ∂p(child_k)/∂s_{ij} ]
 * </pre>
 *
 * This eliminates the per-edge inference loop: ONE inference call yields all posteriors
 * {@code p(child)} and {@code p(parent)}; the gradient is then computed algebraically in
 * O(edges × observations) vs the old O(edges × inference_cost). For a typical theory with
 * E edges and I inference passes (previously E+1 per step), complexity drops from
 * O((E+1) × SSBN_cost) to O(SSBN_cost + E × observations).
 *
 * <h3>Correctness guard</h3>
 * <p>The denominator {@code 1 - s_{ij} * p(parent_j)} is safe as long as
 * {@code s_{ij} * p(parent_j) < 1}, which is guaranteed by the unit-interval projection on
 * {@code s_{ij}} and {@code p(parent_j) ≤ 1}. The small {@code ε=1e-9} guard prevents
 * division by zero at the corner {@code s=1, p=1}.</p>
 *
 * <h3>Online vs full-batch</h3>
 * <p>Online/incremental (per-cascade) is {@code maxEpochs = 1}: one warm-started step that
 * accumulates across cascades — the same online stance as the Beta-evidence fact accumulation.
 * Full-batch offline fitting uses a larger {@code maxEpochs}. No separate code paths.</p>
 *
 * <h3>Fallback</h3>
 * <p>When the posterior map from inference does not contain a parent RV (possible for ungrounded
 * input nodes), the analytic gradient for that edge is zero — equivalent to the finite-difference
 * approximation returning near-zero. The one-shot inference result is still used for the child
 * posteriors and the loss computation.</p>
 */
public final class MebnWeightLearner {

    private static final double DEFAULT_LEARNING_RATE = 0.3;
    private static final double DEFAULT_DELTA = 1e-3;  // retained for API compatibility; unused in analytic path

    /** Small epsilon guarding the noisy-OR denominator from division by zero at (s=1, p_parent=1). */
    private static final double NOISY_OR_DENOM_EPS = 1e-9;

    private final double learningRate;
    @SuppressWarnings("unused")  // retained for backward-compatible constructors
    private final double delta;
    /** Observations sampled per step; {@code <= 0} → full batch (mirrors StructuredPerceptronLearner). */
    private final int batchSize;
    private final long seed;
    private final MebnInferenceService inference = new MebnInferenceService();

    public MebnWeightLearner() {
        this(DEFAULT_LEARNING_RATE, DEFAULT_DELTA, 0, 1234L);
    }

    public MebnWeightLearner(double learningRate, double delta) {
        this(learningRate, delta, 0, 1234L);
    }

    /**
     * @param batchSize observations sampled per gradient step; {@code <= 0} (or {@code >=} all) = full batch
     * @param seed      RNG seed for deterministic minibatch subsampling
     */
    public MebnWeightLearner(double learningRate, double delta, int batchSize, long seed) {
        this.learningRate = learningRate;
        this.delta = delta;
        this.batchSize = batchSize;
        this.seed = seed;
    }

    /**
     * Fit the {@code theory}'s MFrag edge strengths toward the observed grounded-RV targets.
     *
     * <p><b>Production path:</b> delegates to {@link SameDiffMebnStrengthLearner#learn}, which
     * computes the noisy-OR MSE gradient as a vectorized {@code [E, M]} SameDiff autodiff pass
     * (one forward + one backward per epoch). This is backend-agnostic: whichever ND4J native
     * library is on the classpath (CPU or GPU) executes the tensor ops. There is no
     * {@code isAvailable()} gate — SameDiff is always present as a compile-time dependency.
     *
     * <p>The method mutates the theory's edge strengths in place and returns the same object.
     *
     * @param theory       the MTheory to fit (mutated in place)
     * @param graph        the reasoning graph the theory grounds over
     * @param observations observed grounded-RV targets (grounded var name → target value in [0,1])
     * @param maxEpochs    gradient-descent steps (≥1); 1 = a single online step
     * @return the same theory, with updated edge strengths
     */
    public MTheory learn(MTheory theory, ReasoningGraph graph,
                         Map<String, Double> observations, int maxEpochs) {
        return SameDiffMebnStrengthLearner.learn(
                theory, graph, observations, maxEpochs, learningRate, seed);
    }

    // ─── Analytic gradient ─────────────────────────────────────────────────────

    /**
     * Compute the analytic noisy-OR MSE gradient for one edge {@code parent → child} with
     * strength {@code s}, using already-computed posteriors from ONE inference call.
     *
     * <p>Derivation:
     * <ol>
     *   <li>Noisy-OR posterior: {@code p_c = 1 - ∏_k (1 - s_k * p_{parent_k})}</li>
     *   <li>Partial derivative w.r.t. {@code s_{ij}}:
     *       {@code ∂p_c/∂s_{ij} = p_{parent_j} * (1 - p_c) / (1 - s_{ij} * p_{parent_j} + ε)}</li>
     *   <li>MSE gradient: {@code 2 * mean_k[(p_c_k - target_k) * ∂p_c_k/∂s_{ij}]}</li>
     * </ol>
     * where the mean is over batch observations whose key matches the child RV prefix.</p>
     *
     * @param edge      the edge being differentiated
     * @param s         current strength of this edge (from the theory)
     * @param posteriors the full posterior map from one inference call
     * @param batch     the current (sub)batch of target observations
     * @return the gradient of the MSE loss w.r.t. {@code s} (positive = loss increases with s)
     */
    double analyticGradient(Edge edge, double s,
                            Map<String, Double> posteriors,
                            Map<String, Double> batch) {
        String childPrefix  = edge.child();
        String parentPrefix = edge.parent();

        double gradSum = 0.0;
        int count = 0;

        for (Map.Entry<String, Double> obs : batch.entrySet()) {
            String obsKey = obs.getKey();
            // Match grounded child RV names — e.g. obs key "effect(alice)" starts with "effect"
            if (!obsKey.startsWith(childPrefix)) {
                continue;
            }
            double target  = obs.getValue();
            double pChild  = posteriors.getOrDefault(obsKey, 0.5);

            // Derive corresponding grounded parent key from the child key.
            // Convention: replace the child prefix with the parent prefix.
            // e.g. "effect(alice)" → "cause(alice)"
            String parentKey = parentPrefix + obsKey.substring(childPrefix.length());
            double pParent = posteriors.getOrDefault(parentKey, 0.5);

            // ∂p_c/∂s = p_parent * (1 - p_c) / (1 - s * p_parent + ε)
            double denom = 1.0 - s * pParent + NOISY_OR_DENOM_EPS;
            double dpds  = pParent * (1.0 - pChild) / denom;

            // MSE gradient contribution: 2*(p_c - target)*dp/ds
            // Factor of 2 cancels with 1/2 in MSE convention; we match meanLoss convention
            gradSum += (pChild - target) * dpds;
            count++;
        }

        return count > 0 ? 2.0 * gradSum / count : 0.0;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** Sample {@link #batchSize} observations (full batch when {@code batchSize <= 0} or ≥ all). */
    private Map<String, Double> subsample(List<Map.Entry<String, Double>> obs, Random rng) {
        if (batchSize <= 0 || batchSize >= obs.size()) {
            Map<String, Double> all = new LinkedHashMap<>(obs.size() * 2);
            for (Map.Entry<String, Double> e : obs) {
                all.put(e.getKey(), e.getValue());
            }
            return all;
        }
        List<Map.Entry<String, Double>> shuffled = new ArrayList<>(obs);
        Collections.shuffle(shuffled, rng);
        Map<String, Double> batch = new LinkedHashMap<>(batchSize * 2);
        for (int i = 0; i < batchSize; i++) {
            batch.put(shuffled.get(i).getKey(), shuffled.get(i).getValue());
        }
        return batch;
    }

    /**
     * MEAN squared error between the posterior map and the (sub)batch targets.
     * Averaging (rather than summing) makes the learning rate independent of batch size.
     */
    private static double meanLoss(Map<String, Double> posteriors, Map<String, Double> observations) {
        double sum = 0.0;
        int scored = 0;
        for (Map.Entry<String, Double> obs : observations.entrySet()) {
            Double predicted = posteriors.get(obs.getKey());
            if (predicted != null) {
                double error = predicted - obs.getValue();
                sum += error * error;
                scored++;
            }
        }
        return scored == 0 ? 0.0 : sum / scored;
    }

    private static List<Edge> collectEdges(MTheory theory) {
        List<Edge> edges = new ArrayList<>();
        for (MFrag mfrag : theory.getMFrags()) {
            for (String key : mfrag.getEdgeStrengths().keySet()) {
                int sep = key.indexOf("->");
                if (sep > 0) {
                    edges.add(new Edge(mfrag, key.substring(0, sep), key.substring(sep + 2)));
                }
            }
        }
        return edges;
    }

    /** A learnable edge: its home MFrag and the parent→child RV names. */
    public record Edge(MFrag mfrag, String parent, String child) {
    }
}
