/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.bayesian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Greedy Most Relevant Explanation (MRE) via the Generalised Bayes Factor (GBF).
 *
 * <h3>What MRE is</h3>
 * <p>MRE finds a <em>parsimonious</em> partial explanation of the observed evidence —
 * a subset of non-evidence variable assignments {x₁=v₁, …, xₖ=vₖ} that best
 * "explains" the evidence in the sense that the observations become more probable
 * given the explanation than without it, relative to the prior.
 * <br>
 * Unlike {@link VariableElimination#jointMostProbableExplanation} (joint MPE), which
 * returns a complete assignment over ALL non-evidence variables, MRE stops adding
 * variables when including them no longer improves parsimony.  This makes MRE
 * <em>explaining-away-aware</em>: once the dominant cause is committed, adding a
 * second weaker cause will lower the GBF (because the residual probability gain no
 * longer justifies the prior "cost" of asserting that second cause is true).
 * <br>
 * Unlike {@link WeightOfEvidence} (per-finding leave-one-out log-likelihood ratios
 * decomposed over individual evidence items), MRE scores candidate explanation
 * variables jointly against the evidence.</p>
 *
 * <h3>Generalised Bayes Factor (GBF)</h3>
 * <pre>
 *   GBF(x | e) = [ P(x | e) · (1 − P(x)) ] / [ P(x) · (1 − P(x | e)) ]
 * </pre>
 * <p>where x is a <em>partial assignment</em> (x₁=v₁, …, xₖ=vₖ).
 * P(x | e) and P(x) are chained-product approximations:
 * <pre>
 *   P(x₁, …, xₖ | e) ≈ P(x₁|e) · P(x₂|e,x₁) · … · P(xₖ|e,x₁,…,x_{k−1})
 *   P(x₁, …, xₖ)     ≈ P(x₁)   · P(x₂|x₁)   · … · P(xₖ|x₁,…,x_{k−1})
 * </pre>
 * Each factor is a single-variable VE query with progressively extended evidence.
 * Probabilities are clamped to [{@link #EPS}, 1−{@link #EPS}] before division.</p>
 *
 * <h3>Greedy search</h3>
 * <ol>
 *   <li>Start with the empty assignment (GBF = 1.0).</li>
 *   <li>Evaluate every (candidateVar=state) extension; pick the one with highest GBF.</li>
 *   <li>Add it iff its Conditional Bayes Factor CBF = GBF(extended)/GBF(current) &gt; 1 + minGain.</li>
 *   <li>Repeat until no extension improves the score or the size cap {@code k} is reached.</li>
 * </ol>
 *
 * <h3>Complexity</h3>
 * <p>Exact MRE is NP^PP-hard (Yuan, Lu &amp; Druzdzel, JAIR 2011).  This greedy
 * approximation runs O(k · |candidates| · |states|) VE queries, each O(n · d^w).</p>
 *
 * @see <a href="https://jair.org/index.php/jair/article/view/10742">
 *      Yuan, Lu &amp; Druzdzel — "Most Relevant Explanation in Bayesian Networks",
 *      JAIR 2011</a>
 * @see WeightOfEvidence
 * @see VariableElimination#jointMostProbableExplanation
 */
public final class MostRelevantExplanation {

    private static final Logger log = LoggerFactory.getLogger(MostRelevantExplanation.class);

    /**
     * Epsilon used to clamp probabilities away from 0 and 1 before computing GBF
     * ratios in log-space.  Values at exactly 0 or 1 would produce ±∞ or NaN;
     * the clamp treats them as "near-certain" with a documented floor.
     */
    public static final double EPS = 1e-9;

    /** Default maximum explanation size (number of variable=state pairs). */
    public static final int DEFAULT_MAX_K = 4;

    /** Default minimum CBF gain required to extend the explanation. */
    public static final double DEFAULT_MIN_GAIN = 1e-6;

    private MostRelevantExplanation() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC RESULT TYPE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of a greedy MRE search.
     *
     * @param assignment  selected (variable → state) pairs in insertion order (step order);
     *                    empty if no extension improved the GBF above the gain threshold
     * @param gbf         GBF of the final explanation ({@code ≥ 1.0} for a non-trivial explanation)
     * @param stepGbfs    GBF after each greedy step (same length as {@code assignment.size()});
     *                    empty when no variable was selected
     * @param narrative   human-readable lines like "added cause=TRUE, GBF 1.00→3.45";
     *                    one line per greedy step
     */
    public record MreResult(
            LinkedHashMap<String, Integer> assignment,
            double gbf,
            List<Double> stepGbfs,
            List<String> narrative) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run greedy MRE over all non-evidence variables with default parameters.
     *
     * @param network  the Bayesian network
     * @param evidence observed variable → state index
     * @return MRE result
     */
    public static MreResult explain(BayesianNetwork network,
                                     Map<String, Integer> evidence) {
        return explain(network, evidence, candidateSet(network, evidence), DEFAULT_MAX_K, DEFAULT_MIN_GAIN);
    }

    /**
     * Run greedy MRE with an explicit candidate variable set and parameters.
     *
     * <p>Only variables whose names appear in {@code candidates} (and are not already
     * in {@code evidence}) are considered for inclusion in the explanation.</p>
     *
     * @param network    the Bayesian network
     * @param evidence   observed variable → state index
     * @param candidates names of variables eligible for inclusion; {@code null} means all
     *                   non-evidence variables
     * @param maxK       maximum explanation size (number of variable=state pairs to select)
     * @param minGain    minimum CBF improvement required to add a variable ({@code > 0})
     * @return MRE result
     * @throws IllegalArgumentException if the network has no CPTs
     */
    public static MreResult explain(BayesianNetwork network,
                                     Map<String, Integer> evidence,
                                     Collection<String> candidates,
                                     int maxK,
                                     double minGain) {
        Objects.requireNonNull(network, "network");
        Map<String, Integer> safeEvidence = evidence != null ? evidence : Map.of();
        if (network.getAllFactors().isEmpty()) {
            throw new IllegalArgumentException("Network has no CPTs — build CPTs before querying");
        }

        Set<String> candidateSet = candidates != null
                ? new LinkedHashSet<>(candidates)
                : candidateSet(network, safeEvidence);
        // Ensure candidates don't include evidence variables
        candidateSet.removeAll(safeEvidence.keySet());

        LinkedHashMap<String, Integer> selectedAssignment = new LinkedHashMap<>();
        List<Double> stepGbfs = new ArrayList<>();
        List<String> narrative = new ArrayList<>();
        double currentGbf = 1.0;   // GBF of the empty explanation

        for (int step = 0; step < maxK; step++) {
            // Evaluate all (candidateVar=state) extensions not yet selected
            String bestVar = null;
            int bestState = -1;
            double bestGbf = Double.NEGATIVE_INFINITY;

            for (String candidate : candidateSet) {
                if (selectedAssignment.containsKey(candidate)) continue;
                BayesianNode node = network.getNode(candidate);
                if (node == null) continue;

                for (int s = 0; s < node.getCardinality(); s++) {
                    LinkedHashMap<String, Integer> extended = new LinkedHashMap<>(selectedAssignment);
                    extended.put(candidate, s);
                    double gbf = computeGbf(network, safeEvidence, extended);
                    if (gbf > bestGbf) {
                        bestGbf = gbf;
                        bestVar = candidate;
                        bestState = s;
                    }
                }
            }

            if (bestVar == null) break;  // No candidates left

            double cbf = bestGbf / Math.max(currentGbf, EPS);
            if (cbf <= 1.0 + minGain) {
                log.debug("MRE stopping at step {}: best CBF={} does not exceed threshold 1+{}", step, cbf, minGain);
                break;  // No improvement beyond threshold
            }

            // Accept the best extension
            BayesianNode bestNode = network.getNode(bestVar);
            String stateLabel = (bestNode != null && bestState < bestNode.getStates().size())
                    ? bestNode.getStates().get(bestState)
                    : String.valueOf(bestState);
            double prevGbf = currentGbf;
            selectedAssignment.put(bestVar, bestState);
            currentGbf = bestGbf;
            stepGbfs.add(bestGbf);
            narrative.add(String.format("added %s=%s, GBF %.4f→%.4f", bestVar, stateLabel, prevGbf, bestGbf));
            log.debug("MRE step {}: {}", step + 1, narrative.get(narrative.size() - 1));
        }

        return new MreResult(
                selectedAssignment,
                currentGbf,
                Collections.unmodifiableList(stepGbfs),
                Collections.unmodifiableList(narrative));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GBF COMPUTATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute the Generalised Bayes Factor for a partial explanation {@code x} given
     * the observed evidence {@code e}.
     *
     * <pre>
     *   GBF(x | e) = [ P(x|e) · (1 − P(x)) ] / [ P(x) · (1 − P(x|e)) ]
     * </pre>
     *
     * <p>P(x|e) and P(x) are computed via the chained-product approximation:
     * the variables in {@code x} are iterated in their LinkedHashMap insertion order,
     * and each factor is a VE marginal query with the preceding variables committed.</p>
     *
     * @param network     the Bayesian network
     * @param evidence    the observation set e (variable → state)
     * @param explanation the partial assignment x (variable → state); may be empty
     * @return GBF ≥ 0; returns 1.0 for an empty explanation
     */
    public static double computeGbf(BayesianNetwork network,
                                     Map<String, Integer> evidence,
                                     Map<String, Integer> explanation) {
        if (explanation.isEmpty()) return 1.0;

        // P(x|e) via chained VE queries: P(x1|e) * P(x2|e,x1) * ...
        double pXGivenE = chainedProbability(network, evidence, explanation, true);
        // P(x) prior: P(x1) * P(x2|x1) * ... (no evidence)
        double pX = chainedProbability(network, Map.of(), explanation, false);

        // GBF = [P(x|e)·(1−P(x))] / [P(x)·(1−P(x|e))]
        double numerator   = clamp(pXGivenE) * (1.0 - clamp(pX));
        double denominator = clamp(pX)       * (1.0 - clamp(pXGivenE));

        if (denominator <= 0) {
            // Denominator is zero or negative → GBF is effectively infinite;
            // return a large but finite value to preserve ordering
            return Double.MAX_VALUE / 2.0;
        }
        return numerator / denominator;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute the chained joint probability of the given assignment, each factor
     * being a marginal VE query with progressively extended evidence.
     *
     * <p>For {@code withBaseEvidence=true}: each step queries P(xi | baseEvidence, x1..x_{i-1}).
     * For {@code withBaseEvidence=false}: each step queries P(xi | x1..x_{i-1}) with no base evidence.</p>
     */
    private static double chainedProbability(BayesianNetwork network,
                                              Map<String, Integer> baseEvidence,
                                              Map<String, Integer> assignment,
                                              boolean withBaseEvidence) {
        Map<String, Integer> accumulated = new LinkedHashMap<>(withBaseEvidence ? baseEvidence : Map.of());
        double product = 1.0;

        for (Map.Entry<String, Integer> entry : assignment.entrySet()) {
            String var = entry.getKey();
            int targetState = entry.getValue();

            BayesianNode node = network.getNode(var);
            if (node == null) continue;

            double prob;
            try {
                Factor posterior = VariableElimination.query(network, var, accumulated);
                posterior = posterior.normalize();
                double[] vals = posterior.getValues();
                prob = (targetState >= 0 && targetState < vals.length) ? vals[targetState] : EPS;
            } catch (Exception ex) {
                log.debug("chainedProbability: VE query for {} failed: {}", var, ex.getMessage());
                prob = EPS;
            }

            product *= Math.max(prob, EPS);
            // Extend accumulated evidence with this committed value
            accumulated.put(var, targetState);
        }
        return product;
    }

    /** Clamp a probability to [EPS, 1−EPS] before use in GBF ratios. */
    private static double clamp(double p) {
        return Math.max(EPS, Math.min(1.0 - EPS, p));
    }

    /** Build the default candidate set: all non-evidence variables present in the network. */
    private static Set<String> candidateSet(BayesianNetwork network, Map<String, Integer> evidence) {
        Set<String> candidates = new LinkedHashSet<>();
        for (BayesianNode node : network.getNodes()) {
            String var = node.getVariableName();
            if (!evidence.containsKey(var)) {
                candidates.add(var);
            }
        }
        return candidates;
    }
}
