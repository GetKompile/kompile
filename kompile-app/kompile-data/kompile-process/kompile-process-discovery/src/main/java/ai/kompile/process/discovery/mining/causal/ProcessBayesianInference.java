/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.causal;

import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetwork;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNode;
import ai.kompile.event.attribution.algorithm.bayesian.NoisyOrCpt;
import ai.kompile.event.attribution.algorithm.bayesian.VariableElimination;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph.Arc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bayesian inference driven by a discovered process: it builds a real {@link BayesianNetwork} over the
 * activities and runs the project's exact variable-elimination engine — so the mined structure drives
 * the inference, with no LLM and no changes to the attribution engine.
 *
 * <p>Encoding (the same noisy-OR model {@code BayesianNetworkBuilder} uses): each activity is a binary
 * node; a directly-follows relation becomes a directed edge whose causal strength is the dependency
 * measure; start activities get a higher root prior. Strongest edges are added first and any edge that
 * would create a cycle is dropped, yielding a DAG. Because parallel activities have a near-zero directed
 * dependency, they get <em>no</em> edge — i.e. the network encodes them as (conditionally) independent,
 * which is exactly the right probabilistic structure.
 */
public final class ProcessBayesianInference {

    private static final double START_PRIOR = 0.85;
    private static final double DEFAULT_PRIOR = 0.30;
    private static final double DEFAULT_STRENGTH = 0.50;

    /**
     * @param posteriors activity → P(active | evidence)
     * @param priors     activity → root prior it was anchored to
     * @param nodes      number of activities (nodes)
     * @param edges      number of directly-follows edges kept after cycle-breaking
     */
    public record Result(Map<String, Double> posteriors, Map<String, Double> priors, int nodes, int edges) {
    }

    private record Candidate(String from, String to, double strength) {
    }

    private ProcessBayesianInference() {
    }

    public static Result infer(DirectlyFollowsGraph dfg, Collection<String> evidenceActive) {
        List<String> activities = new ArrayList<>(dfg.activities());
        Map<String, String> toVar = new LinkedHashMap<>();
        for (int i = 0; i < activities.size(); i++) {
            toVar.put(activities.get(i), "v" + i);
        }

        BayesianNetwork network = new BayesianNetwork();
        for (String a : activities) {
            network.addNode(new BayesianNode(toVar.get(a), a, a));   // variable, kgNodeId=activity, title
        }

        // Directly-follows edges, strongest dependency first; drop any that would close a cycle.
        List<Candidate> candidates = new ArrayList<>();
        for (Arc arc : dfg.arcs().keySet()) {
            double s = DependencyMeasures.dependency(dfg, arc.from(), arc.to());
            if (s > 0.0) {
                candidates.add(new Candidate(arc.from(), arc.to(), s));
            }
        }
        candidates.sort((x, y) -> Double.compare(y.strength(), x.strength()));

        Map<String, Double> edgeStrength = new LinkedHashMap<>();
        int edges = 0;
        for (Candidate c : candidates) {
            String parentVar = toVar.get(c.from());
            String childVar = toVar.get(c.to());
            try {
                network.addEdge(parentVar, childVar);
                edgeStrength.put(parentVar + "->" + childVar, Math.min(1.0, c.strength()));
                edges++;
            } catch (IllegalArgumentException cycle) {
                // weaker back-edge would create a cycle — drop it to keep the DAG acyclic
            }
        }

        Map<String, Long> starts = dfg.startActivities();
        Map<String, Double> priors = new LinkedHashMap<>();
        for (String a : activities) {
            BayesianNode node = network.getNode(toVar.get(a));
            double prior = starts.containsKey(a) ? START_PRIOR : DEFAULT_PRIOR;
            priors.put(a, prior);
            if (node.isRoot()) {
                node.setCpt(NoisyOrCpt.buildPrior(node.getVariableName(), prior));
            } else {
                List<BayesianNode> parents = node.getParents();
                List<String> parentVars = new ArrayList<>(parents.size());
                double[] strengths = new double[parents.size()];
                for (int i = 0; i < parents.size(); i++) {
                    String parentVar = parents.get(i).getVariableName();
                    parentVars.add(parentVar);
                    strengths[i] = edgeStrength.getOrDefault(parentVar + "->" + node.getVariableName(), DEFAULT_STRENGTH);
                }
                node.setCpt(NoisyOrCpt.buildCpt(node.getVariableName(), parentVars, strengths, NoisyOrCpt.DEFAULT_LEAK));
            }
        }

        Map<String, Integer> evidence = new LinkedHashMap<>();
        if (evidenceActive != null) {
            for (String a : evidenceActive) {
                if (toVar.containsKey(a)) {
                    evidence.put(toVar.get(a), 1);
                }
            }
        }

        Map<String, Double> raw = VariableElimination.queryAll(network, evidence);
        Map<String, Double> posteriors = new LinkedHashMap<>();
        for (String a : activities) {
            Double p = raw.get(toVar.get(a));
            posteriors.put(a, p != null ? p : priors.get(a));
        }
        return new Result(posteriors, priors, activities.size(), edges);
    }
}
