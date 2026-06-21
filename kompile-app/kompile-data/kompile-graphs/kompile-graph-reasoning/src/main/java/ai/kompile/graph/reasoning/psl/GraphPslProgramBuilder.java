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
package ai.kompile.graph.reasoning.psl;

import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@link PslProgram} from a generic {@link ReasoningGraph} — the store-agnostic
 * counterpart of the former KG-specific {@code KgPslProgramBuilder}. Any source (the knowledge
 * graph, the vector store, a mined process) becomes a {@code ReasoningGraph} via an adapter and is
 * then encoded here, so a single PSL encoding is shared across all of them.
 *
 * <p>Three predicates are produced:</p>
 * <ul>
 *   <li>{@code State(N)} — the inferred soft truth that entity {@code N} is active (a target),</li>
 *   <li>{@code Link(X, Y)} — an observed atom whose truth is the directed strength of the relation
 *       {@code X→Y} (read directly from {@link GraphRelation#weight()}),</li>
 *   <li>{@code Prior(N)} — an observed structural prior derived from the entity's
 *       {@link GraphEntity#weight() weight} and out-degree (via {@link NoisyOrCpt#estimatePrior}).</li>
 * </ul>
 *
 * <p>Entity ids (which can embed arbitrary characters) are replaced by safe synthetic constants
 * ({@code n0, n1, ...}); use {@link #constantToEntityId()} / {@link #entityIdToConstant()} to translate.</p>
 */
public class GraphPslProgramBuilder {

    public static final String STATE = "State";
    public static final String LINK = "Link";
    public static final String PRIOR = "Prior";

    private double minEdgeWeight = 0.05;
    private double propagationWeight = 2.0;
    private double abductionWeight = 1.0;
    private double priorWeight = 1.0;
    private boolean includeAbduction = true;
    private boolean includeDefaultRules = true;

    private final Map<String, String> constantToEntityId = new LinkedHashMap<>();
    private final Map<String, String> entityIdToConstant = new LinkedHashMap<>();
    private final Map<String, String> constantToLabel = new LinkedHashMap<>();

    public GraphPslProgramBuilder minEdgeWeight(double w) { this.minEdgeWeight = w; return this; }
    public GraphPslProgramBuilder propagationWeight(double w) { this.propagationWeight = w; return this; }
    public GraphPslProgramBuilder abductionWeight(double w) { this.abductionWeight = w; return this; }
    public GraphPslProgramBuilder priorWeight(double w) { this.priorWeight = w; return this; }
    public GraphPslProgramBuilder includeAbduction(boolean b) { this.includeAbduction = b; return this; }

    /** When false, only the atoms are populated and no default rules are added (caller supplies rules). */
    public GraphPslProgramBuilder includeDefaultRules(boolean b) { this.includeDefaultRules = b; return this; }

    public Map<String, String> constantToEntityId() { return constantToEntityId; }
    public Map<String, String> entityIdToConstant() { return entityIdToConstant; }
    public Map<String, String> constantToLabel() { return constantToLabel; }

    /** Build the PSL program for the whole {@code graph} (treat every entity's State as a target). */
    public PslProgram build(ReasoningGraph graph) {
        PslProgram program = new PslProgram();
        if (graph.isEmpty()) {
            return program;
        }

        int i = 0;
        for (GraphEntity entity : graph.entities()) {
            String constant = "n" + (i++);
            constantToEntityId.put(constant, entity.id());
            entityIdToConstant.put(entity.id(), constant);
            constantToLabel.put(constant, entity.label().isEmpty() ? entity.id() : entity.label());

            double prior = NoisyOrCpt.estimatePrior(entity.weight(), graph.outgoing(entity.id()).size());
            program.observe(PRIOR, prior, constant);
            program.target(STATE, constant);
        }

        for (GraphRelation relation : graph.relations()) {
            String cs = entityIdToConstant.get(relation.sourceId());
            String ct = entityIdToConstant.get(relation.targetId());
            if (cs == null || ct == null || relation.weight() < minEdgeWeight) {
                continue;
            }
            program.observe(LINK, relation.weight(), cs, ct);
        }

        if (includeDefaultRules) {
            addDefaultRules(program);
        }
        return program;
    }

    private void addDefaultRules(PslProgram program) {
        // Active causes tend to activate their effects.
        program.addRule(PslRule.parse(propagationWeight + ": " + STATE + "(X) & " + LINK
                + "(X, Y) -> " + STATE + "(Y) ^2"));
        // Observed effects raise the plausibility of their causes (abductive reasoning).
        if (includeAbduction) {
            program.addRule(PslRule.parse(abductionWeight + ": " + STATE + "(Y) & " + LINK
                    + "(X, Y) -> " + STATE + "(X) ^2"));
        }
        // Soft-equality anchor of each entity's state to its structural prior.
        program.addRule(PslRule.parse(priorWeight + ": " + PRIOR + "(N) -> " + STATE + "(N) ^2"));
        program.addRule(PslRule.parse(priorWeight + ": " + STATE + "(N) -> " + PRIOR + "(N) ^2"));
    }
}
