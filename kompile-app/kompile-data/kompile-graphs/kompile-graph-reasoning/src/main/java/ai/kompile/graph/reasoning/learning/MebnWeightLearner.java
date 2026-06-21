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
import java.util.List;
import java.util.Map;

/**
 * Learns MEBN noisy-OR <em>edge strengths</em> — the per-{@link MFrag} causal weights that build the
 * CPTs — from observed random-variable states. This is the MEBN counterpart to PSL weight learning
 * ({@code PslWeightLearningService}): it closes the gap where MEBN edge strengths were hand-set.
 *
 * <p>It treats SSBN generation + variable elimination as a black-box loss over the edge strengths and
 * does batch numerical-gradient descent, so it reuses the existing MEBN machinery without deriving the
 * noisy-OR gradient analytically. After learning, a MEBN run ({@code MebnInferenceService.inferFacts})
 * uses the learned CPTs, so its probabilistic facts reflect weights fit to data rather than guesses.</p>
 */
public final class MebnWeightLearner {

    private static final double DEFAULT_LEARNING_RATE = 0.3;
    private static final double DEFAULT_DELTA = 1e-3;

    private final double learningRate;
    private final double delta;
    private final MebnInferenceService inference = new MebnInferenceService();

    public MebnWeightLearner() {
        this(DEFAULT_LEARNING_RATE, DEFAULT_DELTA);
    }

    public MebnWeightLearner(double learningRate, double delta) {
        this.learningRate = learningRate;
        this.delta = delta;
    }

    /**
     * Fit the {@code theory}'s MFrag edge strengths so its (evidence-free) SSBN posteriors match the
     * observed grounded-RV targets, by batch numerical-gradient descent. Mutates and returns the theory.
     * A theory with no parent edges (e.g. a unary "simple" theory) is returned unchanged.
     *
     * @param theory       the MTheory to fit (mutated in place)
     * @param graph        the reasoning graph the theory grounds over
     * @param observations observed grounded-RV targets (grounded var name → target value in [0,1])
     * @param maxEpochs    maximum gradient-descent epochs
     * @return the same theory, with learned edge strengths
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
        // Tolerance 0.0: MEBN learner has no convergence test — run all epochs unless baseLoss < 1e-9.
        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                learningRate, 0.0, ProjectedGradientOptimizer.unitInterval());
        for (int epoch = 0; epoch < maxEpochs; epoch++) {
            double baseLoss = loss(graph, theory, observations);
            if (baseLoss < 1e-9) {
                break;
            }
            // Finite-difference gradient: perturb each edge strength by +delta, measure loss change, restore.
            double[] gradients = new double[edges.size()];
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                double original = e.mfrag.getEdgeStrength(e.parent, e.child);
                e.mfrag.setEdgeStrength(e.parent, e.child, clamp(original + delta));
                gradients[i] = (loss(graph, theory, observations) - baseLoss) / delta;
                e.mfrag.setEdgeStrength(e.parent, e.child, original); // restore before the next gradient
            }
            // Read current strengths into an array, step with the optimizer, write back.
            double[] strengths = new double[edges.size()];
            for (int i = 0; i < edges.size(); i++) {
                strengths[i] = edges.get(i).mfrag.getEdgeStrength(edges.get(i).parent, edges.get(i).child);
            }
            optimizer.step(strengths, gradients);
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                e.mfrag.setEdgeStrength(e.parent, e.child, strengths[i]);
            }
        }
        return theory;
    }

    /** Sum of squared error between the evidence-free posteriors and the observed targets. */
    private double loss(ReasoningGraph graph, MTheory theory, Map<String, Double> observations) {
        Map<String, Double> posteriors = inference.infer(graph, theory, Map.of());
        double sum = 0.0;
        for (Map.Entry<String, Double> obs : observations.entrySet()) {
            Double predicted = posteriors.get(obs.getKey());
            if (predicted != null) {
                double error = predicted - obs.getValue();
                sum += error * error;
            }
        }
        return sum;
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
