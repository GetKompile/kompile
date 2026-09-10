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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String CONFLICT = "Conflict";
    public static final String PRIOR = "Prior";

    private double minEdgeWeight = 0.05;
    private double propagationWeight = 2.0;
    private double abductionWeight = 1.0;
    private double priorWeight = 1.0;
    private double conflictWeight = 2.0;
    private boolean includeAbduction = true;
    private boolean includeDefaultRules = true;

    private final Map<String, String> constantToEntityId = new LinkedHashMap<>();
    private final Map<String, String> entityIdToConstant = new LinkedHashMap<>();
    private final Map<String, String> constantToLabel = new LinkedHashMap<>();

    public GraphPslProgramBuilder minEdgeWeight(double w) { this.minEdgeWeight = w; return this; }
    public GraphPslProgramBuilder propagationWeight(double w) { this.propagationWeight = w; return this; }
    public GraphPslProgramBuilder abductionWeight(double w) { this.abductionWeight = w; return this; }
    public GraphPslProgramBuilder priorWeight(double w) { this.priorWeight = w; return this; }
    public GraphPslProgramBuilder conflictWeight(double w) { this.conflictWeight = w; return this; }
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

        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        entities.sort(Comparator.comparing(GraphEntity::id));
        Collection<GraphRelation> relations = graph.relations();
        Map<String, Integer> effectiveOutDegrees = effectiveOutDegrees(entities, relations);

        int i = 0;
        for (GraphEntity entity : entities) {
            String constant = "n" + (i++);
            constantToEntityId.put(constant, entity.id());
            entityIdToConstant.put(entity.id(), constant);
            constantToLabel.put(constant, entity.label().isEmpty() ? entity.id() : entity.label());

            double prior = NoisyOrCpt.estimatePrior(entity.confidence(),
                    effectiveOutDegrees.getOrDefault(entity.id(), 0));
            program.observe(PRIOR, prior, constant);
            program.target(STATE, constant);
        }

        Map<String, Double> links = new LinkedHashMap<>();
        Map<String, Double> conflicts = new LinkedHashMap<>();
        for (GraphRelation relation : relations) {
            String cs = entityIdToConstant.get(relation.sourceId());
            String ct = entityIdToConstant.get(relation.targetId());
            double strength = clamp01(relation.weight() * relation.confidence());
            if (cs == null || ct == null || strength < minEdgeWeight
                    || isIdentitySeparationType(relation.type())) {
                continue;
            }
            Map<String, Double> target = isConflictType(relation.type()) ? conflicts : links;
            mergeMax(target, cs, ct, strength);
            if (!relation.directed() || isSymmetricType(relation.type())) {
                mergeMax(target, ct, cs, strength);
            }
        }
        links.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> program.observe(LINK, entry.getValue(), parseSource(entry.getKey()), parseTarget(entry.getKey())));
        conflicts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> program.observe(CONFLICT, entry.getValue(), parseSource(entry.getKey()), parseTarget(entry.getKey())));

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
        // Contradictory evidence is an explicit penalty, not a positive link with a different label.
        program.addRule(PslRule.parse(conflictWeight + ": " + STATE + "(Y) & " + CONFLICT
                + "(X, Y) -> ^2"));
        // Soft-equality anchor of each entity's state to its structural prior.
        program.addRule(PslRule.parse(priorWeight + ": " + PRIOR + "(N) -> " + STATE + "(N) ^2"));
        program.addRule(PslRule.parse(priorWeight + ": " + STATE + "(N) -> " + PRIOR + "(N) ^2"));
    }

    private Map<String, Integer> effectiveOutDegrees(List<GraphEntity> entities,
                                                       Collection<GraphRelation> relations) {
        Map<String, Integer> degrees = new LinkedHashMap<>();
        for (GraphEntity entity : entities) {
            degrees.put(entity.id(), 0);
        }

        for (GraphRelation relation : relations) {
            if (isIdentitySeparationType(relation.type())) {
                continue;
            }
            double strength = clamp01(relation.weight() * relation.confidence());
            if (strength >= minEdgeWeight) {
                String sourceId = relation.sourceId();
                String targetId = relation.targetId();
                if (degrees.containsKey(sourceId)) {
                    degrees.merge(sourceId, 1, Integer::sum);
                }
                if ((!relation.directed() || isSymmetricType(relation.type()))
                        && !java.util.Objects.equals(sourceId, targetId)
                        && degrees.containsKey(targetId)) {
                    degrees.merge(targetId, 1, Integer::sum);
                }
            }
        }
        return degrees;
    }

    private static void mergeMax(Map<String, Double> values, String source, String target, double value) {
        values.merge(source + "\u0000" + target, value, Math::max);
    }

    private static String parseSource(String key) {
        return key.substring(0, key.indexOf('\u0000'));
    }

    private static String parseTarget(String key) {
        return key.substring(key.indexOf('\u0000') + 1);
    }

    /** True when the relation type is treated as contradictory evidence by the PSL program. */
    public static boolean isConflictType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("CONTRADICTS")
                || normalized.equals("CONFLICTS")
                || normalized.equals("REFUTES")
                || normalized.equals("DISAGREES")
                || normalized.equals("OPPOSES");
    }

    /** True when the relation type is excluded from the PSL Link/Conflict atoms entirely. */
    public static boolean isIdentitySeparationType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("NOT_SAME_AS")
                || normalized.equals("NOT_SAME")
                || normalized.equals("DIFFERENT_FROM");
    }

    private static boolean isSymmetricType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("SAME_AS")
                || normalized.equals("EQUIVALENT_TO")
                || normalized.equals("SIMILAR_TO")
                || normalized.equals("COREFERS_TO")
                || normalized.equals("SAME_ENTITY");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
