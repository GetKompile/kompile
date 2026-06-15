/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import java.util.*;

/**
 * Constructs conditional probability tables using the Noisy-OR model.
 *
 * <p>The Noisy-OR model is a compact representation of CPTs for binary variables
 * where multiple independent causes can each produce an effect. Instead of
 * requiring 2^N parameters for N parents, noisy-OR uses only N+1 parameters:</p>
 *
 * <ul>
 *   <li><b>Causal strength λ_i</b> for each parent i: P(Y=1 | only X_i is true)</li>
 *   <li><b>Leak probability λ_0</b>: P(Y=1 | all parents are false) — captures
 *       unmodeled causes</li>
 * </ul>
 *
 * <h3>Formula</h3>
 * <pre>
 * P(Y=0 | x_1, ..., x_n) = (1 - λ_0) * ∏_{i: x_i=1} (1 - λ_i)
 * P(Y=1 | x_1, ..., x_n) = 1 - P(Y=0 | x_1, ..., x_n)
 * </pre>
 *
 * <p>For the kompile knowledge graph, causal strengths are derived from:</p>
 * <ul>
 *   <li>Edge weight (0.0–1.0): base causal strength</li>
 *   <li>Edge confidence (0.0–1.0): modulates the strength</li>
 *   <li>Causal edge type multiplier: from CausalTraversal.computeHopStrength()</li>
 * </ul>
 */
public class NoisyOrCpt {

    private NoisyOrCpt() {}

    /**
     * Default leak probability for unmodeled causes.
     * A small leak accounts for causes not represented in the graph.
     */
    public static final double DEFAULT_LEAK = 0.05;

    /**
     * Build a CPT for a binary child node using the noisy-OR model.
     *
     * <p>The resulting factor has variables [parent_1, parent_2, ..., child]
     * with 2^(N+1) entries for N parents.</p>
     *
     * @param childVariable       the child variable name
     * @param parentVariables     ordered parent variable names
     * @param causalStrengths     λ_i for each parent (same order), each in [0, 1]
     * @param leakProbability     λ_0: probability child is true when all parents false
     * @return the CPT as a Factor
     */
    public static Factor buildCpt(String childVariable,
                                   List<String> parentVariables,
                                   double[] causalStrengths,
                                   double leakProbability) {
        if (parentVariables.size() != causalStrengths.length) {
            throw new IllegalArgumentException(
                    "Parent count " + parentVariables.size() + " != strength count " + causalStrengths.length);
        }

        // Validate ranges
        for (int i = 0; i < causalStrengths.length; i++) {
            causalStrengths[i] = clamp(causalStrengths[i], 0.0, 1.0);
        }
        leakProbability = clamp(leakProbability, 0.0, 1.0);

        // Variables: [parents..., child]
        List<String> allVars = new ArrayList<>(parentVariables);
        allVars.add(childVariable);

        int numParents = parentVariables.size();
        int[] cardinalities = new int[allVars.size()];
        Arrays.fill(cardinalities, 2); // All binary

        int totalEntries = 1;
        for (int c : cardinalities) totalEntries *= c;

        double[] values = new double[totalEntries];
        int[] assignment = new int[allVars.size()];

        for (int idx = 0; idx < totalEntries; idx++) {
            Factor.indexToAssignment(idx, cardinalities, assignment);

            int childState = assignment[numParents]; // Last variable is the child

            // Compute P(Y=0 | parents) using noisy-OR formula
            double pFalse = 1.0 - leakProbability;
            for (int i = 0; i < numParents; i++) {
                if (assignment[i] == 1) { // Parent i is TRUE
                    pFalse *= (1.0 - causalStrengths[i]);
                }
            }

            if (childState == 0) {
                values[idx] = pFalse;       // P(Y=FALSE | parents)
            } else {
                values[idx] = 1.0 - pFalse; // P(Y=TRUE | parents)
            }
        }

        return new Factor(allVars, cardinalities, values);
    }

    /**
     * Build a prior CPT for a root node (no parents).
     *
     * @param variable    the variable name
     * @param priorTrue   P(variable = TRUE)
     * @return the prior as a Factor
     */
    public static Factor buildPrior(String variable, double priorTrue) {
        priorTrue = clamp(priorTrue, 0.0, 1.0);
        return Factor.binary(List.of(variable), new double[]{1.0 - priorTrue, priorTrue});
    }

    /**
     * Compute causal strength λ_i from a knowledge graph edge.
     *
     * <p>Combines edge weight, confidence, and a type multiplier that
     * reflects the semantic strength of different edge types:</p>
     * <ul>
     *   <li>CAUSES: 1.0 (direct causation)</li>
     *   <li>TRIGGERS: 0.95</li>
     *   <li>ENABLES: 0.85</li>
     *   <li>DERIVED_FROM: 0.8</li>
     *   <li>CONTRIBUTES_TO: 0.7</li>
     *   <li>INFLUENCES: 0.6</li>
     *   <li>PREVENTS: 0.5</li>
     *   <li>CORRELATES_WITH: 0.3</li>
     * </ul>
     *
     * @param edgeWeight   edge weight (0.0–1.0), null defaults to 0.5
     * @param edgeConfidence edge confidence (0.0–1.0), null defaults to 0.5
     * @param typeMultiplier causal type multiplier from CausalTraversal
     * @return the causal strength λ in [0, 1]
     */
    public static double computeCausalStrength(Double edgeWeight, Double edgeConfidence,
                                                double typeMultiplier) {
        double weight = edgeWeight != null ? edgeWeight : 0.5;
        double confidence = edgeConfidence != null ? edgeConfidence : 0.5;
        return clamp(weight * confidence * typeMultiplier, 0.0, 1.0);
    }

    /**
     * Estimate a prior probability for a root node based on its graph properties.
     *
     * <p>Uses the node's confidence score if available, otherwise defaults to 0.5
     * (maximum uncertainty). Nodes with many outgoing edges (high influence) get
     * a slight boost as they represent active/common states.</p>
     *
     * @param nodeConfidence node confidence score, null defaults to 0.5
     * @param outDegree      number of outgoing edges
     * @return estimated prior P(node = TRUE) in [0.1, 0.9]
     */
    public static double estimatePrior(Double nodeConfidence, int outDegree) {
        double base = nodeConfidence != null ? nodeConfidence : 0.5;
        // Small boost for highly connected nodes (capped)
        double degreeBoost = Math.min(0.1, outDegree * 0.01);
        return clamp(base + degreeBoost, 0.1, 0.9);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
