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

import ai.kompile.event.attribution.domain.InferenceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Exact inference in Bayesian networks via the Variable Elimination algorithm.
 *
 * <h3>Algorithm</h3>
 * <p>Given a query P(X | E = e), variable elimination computes the posterior
 * probability by:</p>
 * <ol>
 *   <li><b>Collect factors</b>: gather all CPTs from the Bayesian network</li>
 *   <li><b>Incorporate evidence</b>: reduce each factor by fixing observed variables
 *       to their observed values</li>
 *   <li><b>Determine elimination order</b>: order hidden variables (not query, not evidence)
 *       for sequential elimination — uses reverse topological order as a heuristic</li>
 *   <li><b>Eliminate</b>: for each hidden variable Z:
 *     <ul>
 *       <li>Collect all factors containing Z</li>
 *       <li>Multiply them into a single product factor</li>
 *       <li>Marginalize (sum out) Z from the product</li>
 *       <li>Replace the collected factors with the marginalized result</li>
 *     </ul>
 *   </li>
 *   <li><b>Result</b>: multiply remaining factors and normalize to get P(X | E = e)</li>
 * </ol>
 *
 * <p>Complexity: O(n * d^w) where n = number of variables, d = max cardinality,
 * w = treewidth of the induced graph. For sparse KG-derived networks this is
 * typically tractable.</p>
 */
public class VariableElimination {

    private static final Logger log = LoggerFactory.getLogger(VariableElimination.class);

    private VariableElimination() {}

    /**
     * Result of a traced query: the posterior factor plus a list of inference steps.
     */
    public static class TracedResult {
        private final Factor factor;
        private final List<InferenceStep> trace;

        public TracedResult(Factor factor, List<InferenceStep> trace) {
            this.factor = factor;
            this.trace = trace;
        }

        public Factor getFactor() { return factor; }
        public List<InferenceStep> getTrace() { return List.copyOf(trace); }
    }

    /**
     * Compute P(queryVariable | evidence) using variable elimination.
     *
     * @param network       the Bayesian network
     * @param queryVariable the variable to query
     * @param evidence      map of observed variable → state index (0 = FALSE, 1 = TRUE for binary)
     * @return a Factor over the query variable containing the posterior distribution
     */
    public static Factor query(BayesianNetwork network, String queryVariable,
                                Map<String, Integer> evidence) {
        return queryWithTrace(network, queryVariable, evidence).getFactor();
    }

    /**
     * Compute P(queryVariable | evidence) with a full inference trace.
     * Each elimination step is recorded as an {@link InferenceStep}.
     *
     * @param network       the Bayesian network
     * @param queryVariable the variable to query
     * @param evidence      map of observed variable → state index
     * @return TracedResult containing the posterior factor and inference trace
     */
    public static TracedResult queryWithTrace(BayesianNetwork network, String queryVariable,
                                               Map<String, Integer> evidence) {
        BayesianNode queryNode = network.getNode(queryVariable);
        if (queryNode == null) {
            throw new IllegalArgumentException("Unknown query variable: " + queryVariable);
        }

        List<InferenceStep> trace = new ArrayList<>();

        // 1. Collect all CPT factors
        List<Factor> factors = new ArrayList<>(network.getAllFactors());
        if (factors.isEmpty()) {
            throw new IllegalStateException("Network has no CPTs — build CPTs before querying");
        }

        log.debug("VE query: P({} | {}) with {} initial factors", queryVariable, evidence, factors.size());

        // 2. Incorporate evidence: reduce factors by observed values
        for (Map.Entry<String, Integer> e : evidence.entrySet()) {
            String evidenceVar = e.getKey();
            int evidenceState = e.getValue();
            List<Factor> reduced = new ArrayList<>();
            for (Factor f : factors) {
                reduced.add(f.reduce(evidenceVar, evidenceState));
            }
            factors = reduced;

            BayesianNode evidenceNode = network.getNode(evidenceVar);
            trace.add(InferenceStep.builder()
                    .eliminatedVariable(evidenceVar)
                    .eliminatedTitle(evidenceNode != null ? evidenceNode.getTitle() : evidenceVar)
                    .factorsInvolved(factors.size())
                    .operation("REDUCE")
                    .posteriorValue(evidenceState == 1 ? 1.0 : 0.0)
                    .build());
        }

        // 3. Determine elimination order: all variables except query and evidence
        Set<String> keepVars = new HashSet<>(evidence.keySet());
        keepVars.add(queryVariable);

        List<String> topoOrder = network.topologicalOrder();
        List<String> eliminationOrder = new ArrayList<>();
        for (int i = topoOrder.size() - 1; i >= 0; i--) {
            String var = topoOrder.get(i);
            if (!keepVars.contains(var)) {
                eliminationOrder.add(var);
            }
        }

        log.debug("Elimination order: {}", eliminationOrder);

        // Track running posterior estimate for the query variable
        double previousPosterior = Double.NaN;

        // 4. Eliminate each hidden variable
        for (String eliminateVar : eliminationOrder) {
            List<Factor> relevant = new ArrayList<>();
            List<Factor> irrelevant = new ArrayList<>();

            for (Factor f : factors) {
                if (f.getVariables().contains(eliminateVar)) {
                    relevant.add(f);
                } else {
                    irrelevant.add(f);
                }
            }

            if (relevant.isEmpty()) continue;

            // Collect variable names in relevant factors
            Set<String> involvedVars = new LinkedHashSet<>();
            for (Factor f : relevant) {
                involvedVars.addAll(f.getVariables());
            }

            // Multiply all relevant factors
            Factor product = relevant.get(0);
            for (int i = 1; i < relevant.size(); i++) {
                product = Factor.product(product, relevant.get(i));
            }

            // Sum out the variable
            Factor marginalized = product.marginalize(eliminateVar);

            // Replace factors
            factors = irrelevant;
            factors.add(marginalized);

            // Estimate current posterior for the query variable after this elimination
            Double currentPosterior = estimateQueryPosterior(factors, queryVariable);

            double shift = 0.0;
            if (!Double.isNaN(previousPosterior) && currentPosterior != null) {
                shift = Math.abs(currentPosterior - previousPosterior);
            }
            if (currentPosterior != null) {
                previousPosterior = currentPosterior;
            }

            BayesianNode eliminatedNode = network.getNode(eliminateVar);
            trace.add(InferenceStep.builder()
                    .eliminatedVariable(eliminateVar)
                    .eliminatedTitle(eliminatedNode != null ? eliminatedNode.getTitle() : eliminateVar)
                    .factorsInvolved(relevant.size())
                    .factorVariables(new ArrayList<>(involvedVars))
                    .operation("MARGINALIZE")
                    .priorValue(Double.isNaN(previousPosterior) ? null : previousPosterior)
                    .posteriorValue(currentPosterior)
                    .contributionWeight(shift)
                    .build());
        }

        // 5. Multiply remaining factors and normalize
        Factor result = factors.get(0);
        for (int i = 1; i < factors.size(); i++) {
            result = Factor.product(result, factors.get(i));
        }

        for (String evidenceVar : evidence.keySet()) {
            if (result.getVariables().contains(evidenceVar)) {
                result = result.marginalize(evidenceVar);
            }
        }

        Factor normalized = result.normalize();

        // Add final normalization step
        double finalPosterior = normalized.getValues().length > 1
                ? normalized.getValue(1) : normalized.getValue(0);
        trace.add(InferenceStep.builder()
                .eliminatedVariable(queryVariable)
                .eliminatedTitle(queryNode.getTitle())
                .factorsInvolved(factors.size())
                .operation("NORMALIZE")
                .posteriorValue(finalPosterior)
                .contributionWeight(0.0)
                .build());

        return new TracedResult(normalized, trace);
    }

    /**
     * Estimate the current posterior P(queryVar=TRUE) from the active factors.
     * Returns null if the query variable is not yet isolated.
     */
    private static Double estimateQueryPosterior(List<Factor> factors, String queryVar) {
        try {
            // Find factors mentioning the query variable
            List<Factor> relevant = new ArrayList<>();
            for (Factor f : factors) {
                if (f.getVariables().contains(queryVar)) {
                    relevant.add(f);
                }
            }
            if (relevant.isEmpty()) return null;

            Factor combined = relevant.get(0);
            for (int i = 1; i < relevant.size(); i++) {
                combined = Factor.product(combined, relevant.get(i));
            }

            // Marginalize out everything except the query variable
            for (String var : new ArrayList<>(combined.getVariables())) {
                if (!var.equals(queryVar)) {
                    combined = combined.marginalize(var);
                }
            }
            combined = combined.normalize();
            return combined.getValues().length > 1 ? combined.getValue(1) : combined.getValue(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compute posterior probabilities for all variables given evidence.
     *
     * @param network  the Bayesian network
     * @param evidence map of observed variable → state index
     * @return map of variable name → posterior P(variable = TRUE | evidence)
     */
    public static Map<String, Double> queryAll(BayesianNetwork network,
                                                Map<String, Integer> evidence) {
        Map<String, Double> posteriors = new LinkedHashMap<>();

        for (BayesianNode node : network.getNodes()) {
            String var = node.getVariableName();
            if (evidence.containsKey(var)) {
                // Evidence variable: posterior is deterministic
                posteriors.put(var, evidence.get(var) == 1 ? 1.0 : 0.0);
            } else {
                Factor posterior = query(network, var, evidence);
                // For binary variables, P(TRUE) is at index 1
                double pTrue = posterior.getValues().length > 1 ? posterior.getValue(1) : posterior.getValue(0);
                posteriors.put(var, pTrue);
            }
        }

        return posteriors;
    }

    /**
     * Compute the most probable explanation (MPE): the assignment of all
     * non-evidence variables that maximizes the joint probability.
     *
     * <p>Uses max-product variable elimination: replaces sum with max in
     * the marginalization step.</p>
     *
     * @param network  the Bayesian network
     * @param evidence map of observed variable → state index
     * @return map of variable name → most likely state index
     */
    public static Map<String, Integer> mostProbableExplanation(BayesianNetwork network,
                                                                 Map<String, Integer> evidence) {
        Map<String, Integer> mpe = new LinkedHashMap<>(evidence);

        // For each non-evidence variable, find the state with highest posterior
        for (BayesianNode node : network.getNodes()) {
            String var = node.getVariableName();
            if (evidence.containsKey(var)) continue;

            Factor posterior = query(network, var, evidence);
            double[] values = posterior.getValues();

            int bestState = 0;
            double bestValue = values[0];
            for (int s = 1; s < values.length; s++) {
                if (values[s] > bestValue) {
                    bestState = s;
                    bestValue = values[s];
                }
            }
            mpe.put(var, bestState);
        }

        return mpe;
    }
}
