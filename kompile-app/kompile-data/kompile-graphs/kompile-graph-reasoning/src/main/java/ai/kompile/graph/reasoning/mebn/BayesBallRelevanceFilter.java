/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.bayesian.Factor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Bayes-Ball relevance filter for pruning grounded SSBNs.
 *
 * <p>Implements the <em>ancestral-set</em> pruning criterion (Shachter 1998;
 * Santos &amp; Carvalho 2016 Bayes-Ball): a node is relevant to the query
 * P(Q&nbsp;|&nbsp;E) only if it is an ancestor of at least one node in
 * Q&nbsp;∪&nbsp;E (or is itself a query or evidence node).  All other nodes are
 * barren — they cannot influence the query posterior and may be removed.</p>
 *
 * <h3>Why ancestral-set is correct</h3>
 * <p>The ancestral set of Q&nbsp;∪&nbsp;E is the minimal set of nodes whose
 * joint distribution subsumes the conditional distribution P(Q&nbsp;|&nbsp;E).
 * Removing non-ancestors is safe: they are d-separated from Q given E in the
 * original DAG (Koller &amp; Friedman 2009, Proposition 3.1).</p>
 *
 * <h3>Conservative note</h3>
 * <p>Ancestral-set pruning is correct but <em>not always minimal</em>: some
 * ancestors of Q&nbsp;∪&nbsp;E may still be d-separated from Q given E via
 * active v-structures and evidence blocking.  Full Bayes-Ball would prune
 * those additional nodes; that more aggressive variant can be added later if
 * profiling shows the ancestral set is still too large in practice.</p>
 *
 * @see SSBNGenerator#generateForQuery(String)
 */
public final class BayesBallRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(BayesBallRelevanceFilter.class);

    private BayesBallRelevanceFilter() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute the ancestral-relevant set for computing P(queryVars | evidenceVars).
     *
     * <p>Traverses upward (child → parent) from each node in
     * {@code queryVars ∪ evidenceVars}, collecting all reachable ancestors.  The
     * result includes the seed nodes themselves.</p>
     *
     * @param network      the grounded Bayesian network
     * @param queryVars    names of the grounded query variables
     * @param evidenceVars names of the grounded evidence (finding) variables
     * @return the set of variable names that are relevant to the query
     */
    public static Set<String> ancestralRelevant(BayesianNetwork network,
                                                 Set<String> queryVars,
                                                 Set<String> evidenceVars) {
        Set<String> relevant = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        // Seed with query and evidence nodes that actually exist in the network
        for (String v : queryVars) {
            if (network.getNode(v) != null && relevant.add(v)) {
                queue.add(v);
            }
        }
        for (String v : evidenceVars) {
            if (network.getNode(v) != null && relevant.add(v)) {
                queue.add(v);
            }
        }

        // BFS upward: collect all ancestors
        while (!queue.isEmpty()) {
            String var = queue.poll();
            BayesianNode node = network.getNode(var);
            if (node == null) continue;
            for (BayesianNode parent : node.getParents()) {
                String parentVar = parent.getVariableName();
                if (relevant.add(parentVar)) {
                    queue.add(parentVar);
                }
            }
        }

        return relevant;
    }

    /**
     * Build a pruned copy of {@code original} keeping only nodes in {@code keepVars}.
     *
     * <p>Nodes are added in topological order so that when an edge P → C is wired,
     * both P and C already exist in the pruned network.  The CPT of each kept node
     * is copied from the original verbatim; this is safe when {@code keepVars} is an
     * ancestral set (the ancestral-set property guarantees that all parents of kept
     * nodes are also kept, so no CPT references a variable absent from the pruned
     * network).</p>
     *
     * @param original the full grounded SSBN
     * @param keepVars variable names to retain
     * @return a new {@link BayesianNetwork} containing only the kept nodes and edges
     */
    public static BayesianNetwork prune(BayesianNetwork original, Set<String> keepVars) {
        BayesianNetwork pruned = new BayesianNetwork();

        // Add nodes in topological order so parents precede children
        List<String> topoOrder = original.topologicalOrder();

        for (String var : topoOrder) {
            if (!keepVars.contains(var)) continue;
            BayesianNode orig = original.getNode(var);
            BayesianNode copy = new BayesianNode(
                    orig.getVariableName(),
                    orig.getKgNodeId(),
                    orig.getTitle(),
                    orig.getStates());
            // Copy CPT — safe when keepVars is an ancestral set (all CPT variable
            // references are within the kept subgraph)
            Factor cpt = orig.getCpt();
            if (cpt != null) {
                boolean cptVariablesAllKept = cpt.getVariables().stream().allMatch(keepVars::contains);
                if (cptVariablesAllKept) {
                    copy.setCpt(cpt);
                } else {
                    // Defensive: CPT references a pruned variable — leave null for now.
                    // Should not happen when keepVars is an ancestral set.
                    log.warn("Pruned node '{}' has CPT referencing removed variables; CPT dropped", var);
                }
            }
            pruned.addNode(copy);
        }

        // Wire edges between kept nodes
        for (String var : topoOrder) {
            if (!keepVars.contains(var)) continue;
            BayesianNode orig = original.getNode(var);
            for (BayesianNode parent : orig.getParents()) {
                String parentVar = parent.getVariableName();
                if (keepVars.contains(parentVar)) {
                    try {
                        pruned.addEdge(parentVar, var);
                    } catch (IllegalArgumentException e) {
                        log.warn("Skipped edge {} → {} during pruning: {}", parentVar, var, e.getMessage());
                    }
                }
            }
        }

        return pruned;
    }
}
