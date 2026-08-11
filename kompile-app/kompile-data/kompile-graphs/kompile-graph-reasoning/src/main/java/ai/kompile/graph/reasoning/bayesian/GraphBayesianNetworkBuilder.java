/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.bayesian;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.prior.DefaultPriorProvider;
import ai.kompile.graph.reasoning.prior.PriorContext;
import ai.kompile.graph.reasoning.prior.PriorProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs a {@link BayesianNetwork} from a generic {@link ReasoningGraph} — the store-agnostic
 * counterpart of the former KG-specific {@code BayesianNetworkBuilder}.
 *
 * <p>Each entity becomes a binary node; each relation becomes a directed edge whose causal strength
 * is {@link GraphRelation#weight()}. Strongest edges are added first and any edge that would close a
 * cycle is dropped, yielding a DAG. Root nodes get a prior from their {@link GraphEntity#weight()};
 * non-roots get a noisy-OR CPT over their parents' edge strengths. Entities with no directed
 * dependency stay (conditionally) independent — exactly the right probabilistic structure.</p>
 */
public class GraphBayesianNetworkBuilder {

    private double defaultStrength = 0.5;
    private double leak = NoisyOrCpt.DEFAULT_LEAK;
    private PriorProvider priorProvider = DefaultPriorProvider.INSTANCE;

    private final Map<String, String> variableToEntityId = new LinkedHashMap<>();
    private final Map<String, String> entityIdToVariable = new LinkedHashMap<>();

    public GraphBayesianNetworkBuilder defaultStrength(double s) { this.defaultStrength = s; return this; }
    public GraphBayesianNetworkBuilder leak(double l)             { this.leak = l;            return this; }
    /** Override the prior provider for missing-edge-strength resolution (default: {@link DefaultPriorProvider#INSTANCE}). */
    public GraphBayesianNetworkBuilder priorProvider(PriorProvider p) { this.priorProvider = p; return this; }

    public Map<String, String> variableToEntityId() { return variableToEntityId; }
    public Map<String, String> entityIdToVariable() { return entityIdToVariable; }

    /** Build a DAG-structured Bayesian network for {@code graph}. */
    public BayesianNetwork build(ReasoningGraph graph) {
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        entities.sort(Comparator.comparing(GraphEntity::id));
        BayesianNetwork network = new BayesianNetwork();

        int i = 0;
        for (GraphEntity entity : entities) {
            String var = "v" + (i++);
            variableToEntityId.put(var, entity.id());
            entityIdToVariable.put(entity.id(), var);
            String title = entity.label().isEmpty() ? entity.id() : entity.label();
            network.addNode(new BayesianNode(var, entity.id(), title));
        }

        // Strongest relations first with stable secondary keys; drop any edge that would close a cycle.
        List<GraphRelation> relations = new ArrayList<>(graph.relations());
        relations.sort(Comparator
                .comparingDouble(GraphBayesianNetworkBuilder::effectiveStrength).reversed()
                .thenComparing(GraphBayesianNetworkBuilder::sourceId)
                .thenComparing(GraphBayesianNetworkBuilder::targetId)
                .thenComparing(r -> r.type() == null ? "" : r.type())
                .thenComparing(GraphRelation::id));

        Map<String, Double> edgeStrength = new LinkedHashMap<>();
        for (GraphRelation relation : relations) {
            String parentVar = entityIdToVariable.get(sourceId(relation));
            String childVar = entityIdToVariable.get(targetId(relation));
            double strength = effectiveStrength(relation);
            if (parentVar == null || childVar == null || parentVar.equals(childVar)
                    || strength <= 0.0 || isConflictType(relation.type())) {
                continue;
            }
            try {
                network.addEdge(parentVar, childVar);
                edgeStrength.put(parentVar + "->" + childVar, strength);
            } catch (IllegalArgumentException cycle) {
                // weaker back-edge would create a cycle — drop it to keep the DAG acyclic
            }
        }

        for (GraphEntity entity : entities) {
            BayesianNode node = network.getNode(entityIdToVariable.get(entity.id()));
            double prior = clamp01(entity.confidence());
            if (node.isRoot()) {
                node.setCpt(NoisyOrCpt.buildPrior(node.getVariableName(), prior));
            } else {
                List<BayesianNode> parents = node.getParents();
                List<String> parentVars = new ArrayList<>(parents.size());
                double[] strengths = new double[parents.size()];
                for (int k = 0; k < parents.size(); k++) {
                    String parentVar = parents.get(k).getVariableName();
                    parentVars.add(parentVar);
                    String edgeKey = parentVar + "->" + node.getVariableName();
                    // Prefer explicit edge strength from the map; fall back to PriorProvider (replaces flat 0.5).
                    strengths[k] = edgeStrength.containsKey(edgeKey)
                            ? edgeStrength.get(edgeKey)
                            : priorProvider.strengthFor(parentVar, node.getVariableName(), PriorContext.EMPTY);
                }
                node.setCpt(NoisyOrCpt.buildCpt(node.getVariableName(), parentVars, strengths, leak));
            }
        }
        return network;
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double effectiveStrength(GraphRelation relation) {
        return clamp01(relation.weight() * relation.confidence());
    }

    private static String sourceId(GraphRelation relation) {
        if (relation.directed() || relation.sourceId().compareTo(relation.targetId()) <= 0) {
            return relation.sourceId();
        }
        return relation.targetId();
    }

    private static String targetId(GraphRelation relation) {
        if (relation.directed() || relation.sourceId().compareTo(relation.targetId()) <= 0) {
            return relation.targetId();
        }
        return relation.sourceId();
    }

    private static boolean isConflictType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("CONTRADICTS")
                || normalized.equals("CONFLICTS")
                || normalized.equals("REFUTES")
                || normalized.equals("DISAGREES")
                || normalized.equals("OPPOSES")
                || normalized.equals("NOT_SAME_AS")
                || normalized.equals("NOT_SAME")
                || normalized.equals("DIFFERENT_FROM");
    }
}
