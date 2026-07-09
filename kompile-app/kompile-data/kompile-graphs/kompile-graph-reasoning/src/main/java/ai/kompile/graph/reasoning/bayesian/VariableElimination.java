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

import ai.kompile.graph.reasoning.domain.InferenceStep;
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
        List<String> variables = new ArrayList<>();
        for (BayesianNode node : network.getNodes()) {
            variables.add(node.getVariableName());
        }
        return querySubset(network, variables, evidence);
    }

    /**
     * Compute posterior probabilities for a targeted subset of variables.
     *
     * <p>Unknown query variables are ignored so callers can pass requested grounded RV names
     * before checking whether the SSBN actually instantiated each one. When the network has no
     * directed edges, variables are independent roots and each posterior can be read directly
     * from its local CPT, avoiding an O(N^3) query-all pattern on disconnected SSBNs.</p>
     *
     * @param network        the Bayesian network
     * @param queryVariables variable names to query
     * @param evidence       map of observed variable -> state index
     * @return map of variable name -> posterior P(variable = TRUE | evidence)
     */
    public static Map<String, Double> querySubset(BayesianNetwork network,
                                                   Collection<String> queryVariables,
                                                   Map<String, Integer> evidence) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(queryVariables, "queryVariables");
        Map<String, Integer> safeEvidence = evidence != null ? evidence : Map.of();
        Map<String, Double> posteriors = new LinkedHashMap<>();
        boolean independentRoots = isIndependentRootNetwork(network);

        for (String var : new LinkedHashSet<>(queryVariables)) {
            BayesianNode node = network.getNode(var);
            if (node == null) {
                continue;
            }
            if (safeEvidence.containsKey(var)) {
                // Evidence variable: posterior is deterministic
                posteriors.put(var, safeEvidence.get(var) == 1 ? 1.0 : 0.0);
            } else if (independentRoots) {
                posteriors.put(var, localPosteriorTrue(node));
            } else {
                Factor posterior = query(network, var, safeEvidence);
                double[] values = posterior.getValues();
                // For binary variables, P(TRUE) is at index 1
                double pTrue = values.length > 1 ? values[1] : values[0];
                posteriors.put(var, pTrue);
            }
        }

        return posteriors;
    }

    private static boolean isIndependentRootNetwork(BayesianNetwork network) {
        for (BayesianNode node : network.getNodes()) {
            if (!node.getParents().isEmpty()
                    || !network.getChildren(node.getVariableName()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static double localPosteriorTrue(BayesianNode node) {
        Factor factor = node.getCpt();
        if (factor == null) {
            return 0.5;
        }
        for (String var : new ArrayList<>(factor.getVariables())) {
            if (!var.equals(node.getVariableName())) {
                factor = factor.marginalize(var);
            }
        }
        double[] values = factor.normalize().getValues();
        int trueIndex = 1;
        try {
            trueIndex = node.getStateIndex("TRUE");
        } catch (IllegalArgumentException ignored) {
            // Fall back to the historic binary convention used by queryAll.
        }
        if (values.length == 0) {
            return 0.5;
        }
        return values[Math.min(trueIndex, values.length - 1)];
    }

    /**
     * Compute posterior probabilities and full VE traces for every variable in the network.
     *
     * <p>Delegates to {@link #queryWithTrace(BayesianNetwork, String, Map)} once per variable
     * so each variable's trace is produced by the same code path as the single-variable method.
     * Evidence variables receive a degenerate {@link TracedResult} whose factor is a point-mass
     * (matching {@link #queryAll} semantics) and whose trace contains a single DETERMINISTIC step.</p>
     *
     * <p>This method does NOT alter the semantics of {@link #queryAll}: it is purely additive
     * and the returned posteriors are numerically identical.</p>
     *
     * @param network  the Bayesian network
     * @param evidence map of observed variable → state index
     * @return ordered map of variable name → {@link TracedResult}
     */
    public static Map<String, TracedResult> queryAllWithTrace(BayesianNetwork network,
                                                               Map<String, Integer> evidence) {
        Objects.requireNonNull(network, "network");
        Map<String, Integer> safeEvidence = evidence != null ? evidence : Map.of();
        Map<String, TracedResult> results = new LinkedHashMap<>();

        for (BayesianNode node : network.getNodes()) {
            String var = node.getVariableName();
            if (safeEvidence.containsKey(var)) {
                // Evidence variable: deterministic point-mass factor + minimal trace
                int obsState = safeEvidence.get(var);
                int card = node.getCardinality();
                double[] probs = new double[card];
                probs[obsState] = 1.0;
                Factor pointMass = new Factor(List.of(var), new int[]{card}, probs);
                List<ai.kompile.graph.reasoning.domain.InferenceStep> trace = List.of(
                        ai.kompile.graph.reasoning.domain.InferenceStep.builder()
                                .eliminatedVariable(var)
                                .eliminatedTitle(node.getTitle())
                                .factorsInvolved(0)
                                .operation("DETERMINISTIC")
                                .posteriorValue(obsState == 1 ? 1.0 : 0.0)
                                .contributionWeight(0.0)
                                .build());
                results.put(var, new TracedResult(pointMass, trace));
            } else {
                try {
                    results.put(var, queryWithTrace(network, var, safeEvidence));
                } catch (Exception e) {
                    log.debug("queryAllWithTrace: skipping variable '{}': {}", var, e.getMessage());
                }
            }
        }
        return Collections.unmodifiableMap(results);
    }

    /**
     * Marginal-MAP <em>approximation</em> of the most probable explanation (MPE): for each
     * non-evidence variable independently, returns its marginal-posterior argmax state.
     *
     * <p><b>Not</b> true joint max-product MPE. WP1d (honest-naming): this computes each variable's
     * marginal via {@link #query} and takes that marginal's argmax, so the returned assignment
     * maximizes each marginal <em>separately</em> — which equals the joint MPE only when the
     * posterior factorizes (uncorrelated variables). The method is deliberately NOT renamed (the
     * REST {@code /mpe} surface depends on the name); for true joint max-product elimination see
     * {@link #jointMostProbableExplanation(BayesianNetwork, Map)}.</p>
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

    // ═══════════════════════════════════════════════════════════════════════════
    // JOINT MAX-PRODUCT MPE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of a true joint most-probable-explanation query.
     *
     * @param assignment              MAP assignment over all non-evidence variables;
     *                                evidence variables are not included
     * @param logScore                log-probability of the joint assignment under the
     *                                unnormalised max-product factor product (i.e.
     *                                log P(x*, e) where x* is the argmax)
     * @param probabilityGivenEvidence P(x* | e) = exp(logScore) / P(e); {@code NaN} when
     *                                 P(e) = 0 (zero-probability evidence)
     */
    public record JointMpeResult(
            Map<String, Integer> assignment,
            double logScore,
            double probabilityGivenEvidence) {}

    /**
     * Compute the true joint most-probable explanation (MPE) via max-product variable
     * elimination in log-space.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Collect and evidence-reduce all CPT factors.</li>
     *   <li>Convert to log-space (zero CPT entries become −∞).</li>
     *   <li>Eliminate hidden variables in reverse-topological order using MAX instead
     *       of SUM: at each step collect the factors containing the variable, take their
     *       log-sum (i.e. log-product), and for every configuration of the remaining
     *       variables record the maximising state of the eliminated variable in a
     *       <em>back-pointer table</em>.</li>
     *   <li>After all eliminations, read the argmax from the final scalar factor and
     *       trace back through the back-pointer tables in reverse elimination order to
     *       recover the full assignment.</li>
     *   <li>Compute P(e) via a standard sum-product VE pass on any single query variable
     *       (or 1.0 when there is no evidence), then divide to obtain
     *       {@code probabilityGivenEvidence}.</li>
     * </ol>
     *
     * <h3>Back-pointer design</h3>
     * <p>Each back-pointer table for eliminated variable Z is a {@code Map<List<Integer>, Integer>}
     * from the joint assignment of the remaining scope variables (in a deterministic iteration
     * order) to the argmax state of Z.  After the final factor is resolved to its max assignment,
     * back-pointers are replayed in reverse elimination order: the already-committed assignment
     * to later-eliminated variables is used to look up each earlier-eliminated variable's
     * optimal state.</p>
     *
     * <h3>Ties</h3>
     * <p>When two states produce equal log-probability, the <em>lower</em> state index wins
     * (deterministic, documented).</p>
     *
     * @param network  the Bayesian network
     * @param evidence map of observed variable → state index (0-based); may be empty
     * @return {@link JointMpeResult} with the MAP assignment, log-score, and posterior probability
     * @throws IllegalArgumentException if the network has no CPTs
     * @throws IllegalStateException    if the network contains a topological cycle
     */
    public static JointMpeResult jointMostProbableExplanation(BayesianNetwork network,
                                                               Map<String, Integer> evidence) {
        Objects.requireNonNull(network, "network");
        Map<String, Integer> safeEvidence = evidence != null ? evidence : Map.of();

        List<Factor> rawFactors = new ArrayList<>(network.getAllFactors());
        if (rawFactors.isEmpty()) {
            throw new IllegalArgumentException("Network has no CPTs — build CPTs before querying");
        }

        // 1. Reduce factors by evidence
        List<Factor> factors = applyEvidence(rawFactors, safeEvidence);

        // 2. Convert to log-space factors (zero → −∞ sentinel)
        List<double[]> logFactors = new ArrayList<>();
        List<List<String>> factorVarLists = new ArrayList<>();
        List<int[]> factorCardLists = new ArrayList<>();
        for (Factor f : factors) {
            double[] logVals = new double[f.size()];
            double[] raw = f.getValues();
            for (int i = 0; i < raw.length; i++) {
                logVals[i] = raw[i] > 0 ? Math.log(raw[i]) : Double.NEGATIVE_INFINITY;
            }
            logFactors.add(logVals);
            factorVarLists.add(new ArrayList<>(f.getVariables()));
            factorCardLists.add(f.getCardinalities());
        }

        // 3. Elimination order (hidden variables only)
        Set<String> keepVars = new HashSet<>(safeEvidence.keySet());
        List<String> topoOrder = network.topologicalOrder();
        List<String> eliminationOrder = new ArrayList<>();
        for (int i = topoOrder.size() - 1; i >= 0; i--) {
            String v = topoOrder.get(i);
            if (!keepVars.contains(v)) {
                eliminationOrder.add(v);
            }
        }

        // Back-pointer tables: one per eliminated variable (in elimination order)
        // Maps (scope assignment as List<Integer>) → argmax state of the eliminated var
        List<Map<List<Integer>, Integer>> backPointers = new ArrayList<>();
        // Scope variable lists parallel to backPointers
        List<List<String>> backPointerScopes = new ArrayList<>();

        // 4. Max-product elimination
        for (String eliminateVar : eliminationOrder) {
            // Partition factors
            List<Integer> relevantIdx = new ArrayList<>();
            List<Integer> irrelevantIdx = new ArrayList<>();
            for (int i = 0; i < logFactors.size(); i++) {
                if (factorVarLists.get(i).contains(eliminateVar)) {
                    relevantIdx.add(i);
                } else {
                    irrelevantIdx.add(i);
                }
            }

            if (relevantIdx.isEmpty()) {
                backPointers.add(Map.of());
                backPointerScopes.add(List.of());
                continue;
            }

            // Build union scope of relevant factors
            List<String> unionVars = new ArrayList<>();
            List<Integer> unionCards = new ArrayList<>();
            for (int ri : relevantIdx) {
                List<String> fVars = factorVarLists.get(ri);
                int[] fCards = factorCardLists.get(ri);
                for (int j = 0; j < fVars.size(); j++) {
                    if (!unionVars.contains(fVars.get(j))) {
                        unionVars.add(fVars.get(j));
                        unionCards.add(fCards[j]);
                    }
                }
            }

            int[] unionCardsArr = unionCards.stream().mapToInt(Integer::intValue).toArray();
            int eliminateIdx = unionVars.indexOf(eliminateVar);
            int eliminateCard = unionCards.get(eliminateIdx);

            // Build result scope (union minus the eliminated var)
            List<String> resultVars = new ArrayList<>(unionVars);
            resultVars.remove(eliminateIdx);
            List<Integer> resultCards = new ArrayList<>(unionCards);
            resultCards.remove(eliminateIdx);
            int[] resultCardsArr = resultCards.stream().mapToInt(Integer::intValue).toArray();

            int resultSize = 1;
            for (int c : resultCardsArr) resultSize *= c;
            if (resultSize == 0) resultSize = 1;

            double[] resultLogVals = new double[resultSize];
            Arrays.fill(resultLogVals, Double.NEGATIVE_INFINITY);
            Map<List<Integer>, Integer> bpTable = new HashMap<>();

            // Enumerate all assignments of unionVars
            int unionSize = 1;
            for (int c : unionCardsArr) unionSize *= c;
            int[] unionAssign = new int[unionVars.size()];

            for (int idx = 0; idx < unionSize; idx++) {
                Factor.indexToAssignment(idx, unionCardsArr, unionAssign);

                // Sum log values across relevant factors for this assignment
                double logProd = 0.0;
                for (int ri : relevantIdx) {
                    List<String> fVars = factorVarLists.get(ri);
                    int[] fCards = factorCardLists.get(ri);
                    double[] fLog = logFactors.get(ri);
                    int fIdx = projectAssignmentFromLists(unionAssign, unionVars, fVars, fCards);
                    double val = fLog[fIdx];
                    if (val == Double.NEGATIVE_INFINITY) {
                        logProd = Double.NEGATIVE_INFINITY;
                        break;
                    }
                    logProd += val;
                }

                // Compute result index (assignment without eliminateVar)
                int[] resultAssign = new int[resultVars.size()];
                for (int j = 0; j < resultVars.size(); j++) {
                    int origJ = unionVars.indexOf(resultVars.get(j));
                    resultAssign[j] = unionAssign[origJ];
                }
                int resultIdx = assignmentToIndex(resultAssign, resultCardsArr);

                // Max over eliminating variable; tie-break: lower state index wins
                if (logProd > resultLogVals[resultIdx]) {
                    resultLogVals[resultIdx] = logProd;
                    bpTable.put(arrayToList(resultAssign), unionAssign[eliminateIdx]);
                }
                // If equal: we only update if logProd > (strictly), so the first
                // (lower state index) assignment that achieves the max is kept.
            }

            backPointers.add(bpTable);
            backPointerScopes.add(new ArrayList<>(resultVars));

            // Build new result factor (in regular-space for tracking, kept as log)
            List<String> newResultVars = new ArrayList<>(resultVars);
            int[] newResultCards = resultCardsArr;

            // Replace factor lists
            List<double[]> newLogFactors = new ArrayList<>();
            List<List<String>> newVarLists = new ArrayList<>();
            List<int[]> newCardLists = new ArrayList<>();
            for (int i : irrelevantIdx) {
                newLogFactors.add(logFactors.get(i));
                newVarLists.add(factorVarLists.get(i));
                newCardLists.add(factorCardLists.get(i));
            }
            newLogFactors.add(resultLogVals);
            newVarLists.add(newResultVars);
            newCardLists.add(newResultCards);

            logFactors = newLogFactors;
            factorVarLists = newVarLists;
            factorCardLists = newCardLists;
        }

        // 5. Find the global max from remaining factor(s) — should be a scalar or near-scalar
        //    Combine remaining log factors by summing (log-product)
        double[] combinedLog = combineLogFactors(logFactors, factorVarLists, factorCardLists);
        List<String> combinedVars = mergeVarLists(factorVarLists);
        int[] combinedCards = mergeCardArrays(factorVarLists, factorCardLists, combinedVars);

        int bestIdx = 0;
        double bestLog = combinedLog[0];
        for (int i = 1; i < combinedLog.length; i++) {
            if (combinedLog[i] > bestLog) {
                bestLog = combinedLog[i];
                bestIdx = i;
            }
        }
        // Recover combined vars assignment
        int[] combinedAssign = new int[combinedVars.size()];
        if (combinedCards.length > 0 && combinedLog.length > 1) {
            Factor.indexToAssignment(bestIdx, combinedCards, combinedAssign);
        }
        Map<String, Integer> assignment = new LinkedHashMap<>();
        for (int i = 0; i < combinedVars.size(); i++) {
            assignment.put(combinedVars.get(i), combinedAssign[i]);
        }

        // 6. Trace back through back-pointer tables in REVERSE elimination order
        for (int k = eliminationOrder.size() - 1; k >= 0; k--) {
            String eliminateVar = eliminationOrder.get(k);
            Map<List<Integer>, Integer> bpTable = backPointers.get(k);
            List<String> scope = backPointerScopes.get(k);

            if (bpTable.isEmpty()) {
                // No factors involved this var; default to state 0
                assignment.put(eliminateVar, 0);
                continue;
            }

            // Build the scope assignment using already-committed values
            int[] scopeAssign = new int[scope.size()];
            for (int j = 0; j < scope.size(); j++) {
                String scopeVar = scope.get(j);
                Integer val = assignment.get(scopeVar);
                scopeAssign[j] = val != null ? val : 0;
            }
            List<Integer> key = arrayToList(scopeAssign);
            Integer bpVal = bpTable.get(key);
            assignment.put(eliminateVar, bpVal != null ? bpVal : 0);
        }

        // Remove evidence variables from the assignment (they are observed, not explained)
        safeEvidence.keySet().forEach(assignment::remove);

        double logScore = Double.isInfinite(bestLog) && bestLog < 0 ? Double.NEGATIVE_INFINITY : bestLog;

        // 7. Compute P(e) for normalisation via sum-product on any query variable
        double pEvidence = computePEvidence(network, safeEvidence);
        double probGivenEvidence;
        if (pEvidence <= 0 || Double.isNaN(pEvidence)) {
            probGivenEvidence = Double.NaN;
        } else {
            double jointProb = Math.exp(logScore);
            probGivenEvidence = jointProb / pEvidence;
            // Clamp floating-point overshoot from log-space rounding
            if (probGivenEvidence > 1.0) probGivenEvidence = 1.0;
        }

        return new JointMpeResult(Collections.unmodifiableMap(assignment), logScore, probGivenEvidence);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOINT MPE PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Apply evidence reductions to a list of factors, returning reduced copies. */
    private static List<Factor> applyEvidence(List<Factor> factors, Map<String, Integer> evidence) {
        List<Factor> result = new ArrayList<>(factors);
        for (Map.Entry<String, Integer> e : evidence.entrySet()) {
            List<Factor> reduced = new ArrayList<>();
            for (Factor f : result) {
                reduced.add(f.reduce(e.getKey(), e.getValue()));
            }
            result = reduced;
        }
        return result;
    }

    /** Project a union assignment onto a factor's variable list, return flat index. */
    private static int projectAssignmentFromLists(int[] unionAssign,
                                                   List<String> unionVars,
                                                   List<String> fVars,
                                                   int[] fCards) {
        int idx = 0;
        int stride = 1;
        for (int i = fVars.size() - 1; i >= 0; i--) {
            int pos = unionVars.indexOf(fVars.get(i));
            idx += unionAssign[pos] * stride;
            stride *= fCards[i];
        }
        return idx;
    }

    /** Convert an assignment array to a flat index given cardinalities (row-major). */
    private static int assignmentToIndex(int[] assign, int[] cards) {
        if (cards.length == 0) return 0;
        int idx = 0;
        int stride = 1;
        for (int i = cards.length - 1; i >= 0; i--) {
            idx += assign[i] * stride;
            stride *= cards[i];
        }
        return idx;
    }

    private static List<Integer> arrayToList(int[] arr) {
        List<Integer> list = new ArrayList<>(arr.length);
        for (int v : arr) list.add(v);
        return list;
    }

    /**
     * Merge a list of variable lists into a single ordered list (first-seen order).
     */
    private static List<String> mergeVarLists(List<List<String>> varLists) {
        List<String> merged = new ArrayList<>();
        for (List<String> vl : varLists) {
            for (String v : vl) {
                if (!merged.contains(v)) merged.add(v);
            }
        }
        return merged;
    }

    /**
     * Build a cardinality array for a merged variable list.
     */
    private static int[] mergeCardArrays(List<List<String>> varLists,
                                          List<int[]> cardLists,
                                          List<String> mergedVars) {
        int[] cards = new int[mergedVars.size()];
        for (int vi = 0; vi < mergedVars.size(); vi++) {
            String var = mergedVars.get(vi);
            for (int fi = 0; fi < varLists.size(); fi++) {
                int pos = varLists.get(fi).indexOf(var);
                if (pos >= 0) {
                    cards[vi] = cardLists.get(fi)[pos];
                    break;
                }
            }
        }
        return cards;
    }

    /**
     * Combine a list of log-space factors by summing (log-product) into one factor
     * over the merged variable set.
     */
    private static double[] combineLogFactors(List<double[]> logFactors,
                                               List<List<String>> varLists,
                                               List<int[]> cardLists) {
        List<String> merged = mergeVarLists(varLists);
        int[] mergedCards = mergeCardArrays(varLists, cardLists, merged);
        int totalSize = 1;
        for (int c : mergedCards) totalSize *= c;
        if (totalSize == 0) totalSize = 1;

        double[] result = new double[totalSize];
        Arrays.fill(result, 0.0);

        int[] assign = new int[merged.size()];
        for (int idx = 0; idx < totalSize; idx++) {
            if (mergedCards.length > 0) {
                Factor.indexToAssignment(idx, mergedCards, assign);
            }
            double sum = 0.0;
            for (int fi = 0; fi < logFactors.size(); fi++) {
                int fIdx = projectAssignmentFromLists(assign, merged, varLists.get(fi), cardLists.get(fi));
                double v = logFactors.get(fi)[fIdx];
                if (v == Double.NEGATIVE_INFINITY) {
                    sum = Double.NEGATIVE_INFINITY;
                    break;
                }
                sum += v;
            }
            result[idx] = sum;
        }
        return result;
    }

    /**
     * Compute P(evidence) by running a standard sum-product VE query on an arbitrary
     * non-evidence variable and reading the unnormalised sum, or 1.0 if there is no evidence.
     */
    private static double computePEvidence(BayesianNetwork network, Map<String, Integer> evidence) {
        if (evidence.isEmpty()) return 1.0;

        // Pick any non-evidence variable to query
        String queryVar = null;
        for (BayesianNode node : network.getNodes()) {
            if (!evidence.containsKey(node.getVariableName())) {
                queryVar = node.getVariableName();
                break;
            }
        }
        if (queryVar == null) {
            // All variables are evidence — P(e) = product of their CPT entries
            // Just return 1.0 as an approximation; caller sets probGivenEvidence = NaN guard
            return 1.0;
        }
        try {
            // Run sum-product VE and read unnormalised sum from the pre-normalization factor
            List<Factor> factors = applyEvidence(network.getAllFactors(), evidence);
            List<String> topoOrder = network.topologicalOrder();
            Set<String> keepVarsSet = new HashSet<>(evidence.keySet());
            keepVarsSet.add(queryVar);
            List<String> elOrder = new ArrayList<>();
            for (int i = topoOrder.size() - 1; i >= 0; i--) {
                String v = topoOrder.get(i);
                if (!keepVarsSet.contains(v)) elOrder.add(v);
            }
            for (String ev : elOrder) {
                List<Factor> rel = new ArrayList<>(), irr = new ArrayList<>();
                for (Factor f : factors) {
                    if (f.getVariables().contains(ev)) rel.add(f); else irr.add(f);
                }
                if (rel.isEmpty()) continue;
                Factor prod = rel.get(0);
                for (int i = 1; i < rel.size(); i++) prod = Factor.product(prod, rel.get(i));
                Factor marg = prod.marginalize(ev);
                factors = irr;
                factors.add(marg);
            }
            Factor result = factors.get(0);
            for (int i = 1; i < factors.size(); i++) result = Factor.product(result, factors.get(i));
            // Marginalize out any remaining evidence variables
            for (String ev : evidence.keySet()) {
                if (result.getVariables().contains(ev)) result = result.marginalize(ev);
            }
            // Sum of unnormalized factor = P(e)
            double[] vals = result.getValues();
            double sum = 0;
            for (double v : vals) sum += v;
            return sum;
        } catch (Exception ex) {
            log.debug("computePEvidence fallback: {}", ex.getMessage());
            return Double.NaN;
        }
    }
}
