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
 * Learns MEBN noisy-OR <em>edge strengths</em> by minibatch numerical-gradient descent — the MEBN
 * counterpart to PSL weight learning, following the <b>same</b> SGD recipe as
 * {@code StructuredPerceptronLearner}: it shares the {@link ProjectedGradientOptimizer} for the
 * projected step + convergence, the gradient is the <b>mean</b> over the scored minibatch (divided by
 * batch size, so the learning rate is batch-size-invariant — standard online/minibatch SGD), and the
 * batch is subsampled when {@code batchSize > 0}.
 *
 * <h3>Online vs full-batch is the same routine</h3>
 * <p>There is one {@link #learn} routine. <b>Online/incremental</b> learning (the per-cascade path under
 * partial observability, where there is no complete target to converge to) is just {@code maxEpochs = 1}:
 * one warm-started step from the theory's current strengths that ACCUMULATES across cascades — the same
 * online stance as the Beta-evidence fact accumulation. <b>Full-batch</b> offline fitting is a larger
 * {@code maxEpochs} with the early-stop. No separate "minibatch" method, no per-call code duplication.</p>
 *
 * <p>NB: the finite-difference gradient costs one SSBN inference <em>per edge</em> and the loss is
 * observation-count-independent (one evidence-free inference yields all posteriors), so the
 * {@code batchSize} regulates the gradient magnitude/noise — not the per-step cost. The per-step cost is
 * {@code O(edges) × inference}; {@code maxEpochs = 1} (online) is the cost lever. An analytic noisy-OR
 * gradient would remove the per-edge inference entirely — a future optimization.</p>
 */
public final class MebnWeightLearner {

    private static final double DEFAULT_LEARNING_RATE = 0.3;
    private static final double DEFAULT_DELTA = 1e-3;

    private final double learningRate;
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
     * Fit the {@code theory}'s MFrag edge strengths toward the observed grounded-RV targets by minibatch
     * numerical-gradient descent. Warm-starts from (and mutates) the theory's current strengths. A theory
     * with no parent edges is returned unchanged. Pass {@code maxEpochs = 1} for an online/incremental step.
     *
     * @param theory       the MTheory to fit (mutated in place)
     * @param graph        the reasoning graph the theory grounds over
     * @param observations observed grounded-RV targets (grounded var name → target value in [0,1])
     * @param maxEpochs    gradient-descent steps (≥1); 1 = a single online step
     * @return the same theory, with updated edge strengths
     */
    public MTheory learn(MTheory theory, ReasoningGraph graph,
                         Map<String, Double> observations, int maxEpochs) {
        if (theory == null || graph == null || observations == null || observations.isEmpty()) {
            return theory;
        }
        List<Edge> edges = collectEdges(theory);
        if (edges.isEmpty()) {
            return theory; // nothing to learn — e.g. a unary "simple" theory has no parent edges
        }
        // Tolerance 0.0: run all epochs unless the (mean) loss is already ~0.
        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                learningRate, 0.0, ProjectedGradientOptimizer.unitInterval());
        List<Map.Entry<String, Double>> obsList = new ArrayList<>(observations.entrySet());
        Random rng = new Random(seed);
        double[] strengths = new double[edges.size()];
        double[] gradients = new double[edges.size()];

        for (int epoch = 0; epoch < Math.max(1, maxEpochs); epoch++) {
            Map<String, Double> batch = subsample(obsList, rng);
            double baseLoss = meanLoss(graph, theory, batch);
            if (baseLoss < 1e-9) {
                break;
            }
            // Finite-difference gradient of the MEAN loss: perturb each edge by +delta, measure the
            // change in mean loss, restore. Dividing by batch size keeps the step batch-size-invariant.
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                double original = e.mfrag.getEdgeStrength(e.parent, e.child);
                e.mfrag.setEdgeStrength(e.parent, e.child, clamp(original + delta));
                gradients[i] = (meanLoss(graph, theory, batch) - baseLoss) / delta;
                e.mfrag.setEdgeStrength(e.parent, e.child, original); // restore before the next gradient
                strengths[i] = original;
            }
            optimizer.step(strengths, gradients);
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                e.mfrag.setEdgeStrength(e.parent, e.child, strengths[i]);
            }
        }
        return theory;
    }

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
     * MEAN squared error between the evidence-free posteriors and the (sub)batch targets — i.e. the SSE
     * divided by the number of scored observations. Averaging (rather than summing) is what makes the
     * learning rate independent of batch size, exactly as in minibatch SGD for logistic regression / NNs.
     */
    private double meanLoss(ReasoningGraph graph, MTheory theory, Map<String, Double> observations) {
        Map<String, Double> posteriors = inference.infer(graph, theory, Map.of());
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

    private static double clamp(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }

    /** A learnable edge: its home MFrag and the parent→child RV names. */
    private record Edge(MFrag mfrag, String parent, String child) {
    }
}
