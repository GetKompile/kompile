/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.learning.HybridConsensusTrainer;
import ai.kompile.graph.reasoning.learning.MebnWeightLearner;
import ai.kompile.graph.reasoning.learning.MebnWeightSerializer;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Store-agnostic final FOL/PSL/MEBN learning for a portable {@link UnifiedGraph}.
 *
 * <p>This is the folder-local counterpart to the managed fact-sheet hydration adapter. It uses the
 * same graph-reasoning library primitives, learns PSL and MEBN against one hybrid consensus, and
 * stores the resulting program, theory, and weights inside the {@code .kgraph} archive. No Spring,
 * database, fact-sheet service, or long-lived process is required.</p>
 */
public final class UnifiedGraphReasoningLifecycle {

    public static final String FOL_PSL_PROGRAM_ARTIFACT = "reasoning/fol-psl-program.bin";
    public static final String PSL_WEIGHTS_ARTIFACT = "reasoning/psl-weights.json";
    public static final String MEBN_THEORY_ARTIFACT = "reasoning/mebn-theory.bin";
    public static final String MEBN_THEORY_JSON_ARTIFACT = "reasoning/mebn-theory.v1.json";
    public static final String MEBN_STRENGTHS_ARTIFACT = "reasoning/mebn-strengths.json";
    public static final String CONSENSUS_TARGETS_ARTIFACT = "reasoning/consensus-targets.bin";

    private UnifiedGraphReasoningLifecycle() {
    }

    /** Learning effort for one final corpus-wide graph pass. */
    public record Config(boolean enabled,
                         int pslSteps,
                         int mebnEpochs,
                         int consensusRounds,
                         double consensusWeight,
                         int maxRelationTypes) {
        public Config {
            pslSteps = Math.max(1, pslSteps);
            mebnEpochs = Math.max(1, mebnEpochs);
            consensusRounds = Math.max(1, consensusRounds);
            consensusWeight = clamp01(consensusWeight);
            maxRelationTypes = Math.max(1, maxRelationTypes);
        }

        public static Config defaults() {
            return new Config(true, 1, 1, 1, 0.35, 25);
        }
    }

    /** Compact lifecycle result suitable for crawl status and metadata surfaces. */
    public record Summary(boolean enabled,
                          boolean folPslLearned,
                          boolean mebnLearned,
                          int pslRuleCount,
                          int mebnFragmentCount,
                          int observedTargetCount,
                          int modelsTrained,
                          int consensusRounds) {
        public static Summary disabled() {
            return new Summary(false, false, false, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Learn all portable reasoning models against the complete graph and persist them in the graph.
     */
    public static Summary learn(UnifiedGraph graph, Config config) {
        Objects.requireNonNull(graph, "graph");
        Config effective = config == null ? Config.defaults() : config;
        if (!effective.enabled() || graph.isEmpty() || graph.relations().isEmpty()) {
            return Summary.disabled();
        }

        GraphPslProgramBuilder pslBuilder = new GraphPslProgramBuilder();
        PslProgram pslProgram = applyLearnedPslWeights(graph, pslBuilder.build(graph));
        MTheory mTheory = buildMTheory(graph, effective.maxRelationTypes());
        String priorMebnStrengths = graph.artifactText(MEBN_STRENGTHS_ARTIFACT);
        if (priorMebnStrengths != null && !priorMebnStrengths.isBlank()) {
            MebnWeightSerializer.applyStrengths(mTheory, priorMebnStrengths);
        }

        Map<String, Double> observed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pslBuilder.entityIdToConstant().entrySet()) {
            GraphEntity entity = graph.entity(entry.getKey()).orElse(null);
            if (entity != null) {
                observed.put(GraphPslProgramBuilder.STATE + "(" + entry.getValue() + ")",
                        clamp01(entity.confidence()));
            }
        }

        Map<String, Double> relationTruths = relationTruths(graph);
        if (!relationTruths.isEmpty() && mTheory.getMFrags().size() > 1) {
            // Learning needs observed graph truths, not Bayesian posteriors. Running exact inference
            // merely to discover which relation types the bounded MTheory retained makes folder-local
            // enrichment exponential in the generated SSBN. Filter the known graph facts against the
            // theory's resident variable names instead; buildMTheory already guarantees that retained
            // relation groundings are valid for those variables.
            Set<String> modeledRelations = new LinkedHashSet<>();
            mTheory.getMFrags().forEach(fragment -> fragment.getResidentNodes().forEach(
                    variable -> modeledRelations.add(variable.getName())));
            for (Map.Entry<String, Double> truth : relationTruths.entrySet()) {
                int argumentsStart = truth.getKey().indexOf('(');
                String relationName = argumentsStart > 0
                        ? truth.getKey().substring(0, argumentsStart)
                        : truth.getKey();
                if (modeledRelations.contains(relationName)) {
                    observed.put(truth.getKey(), truth.getValue());
                }
            }
        }

        boolean trainMebn = observed.keySet().stream()
                .anyMatch(key -> !key.startsWith(GraphPslProgramBuilder.STATE + "("));
        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                new PslWeightLearningService(), effective.pslSteps(),
                trainMebn, trainMebn ? new MebnWeightLearner() : null,
                trainMebn ? mTheory : null, trainMebn ? graph : null,
                effective.mebnEpochs(),
                false, null, null,
                new HybridReasoner(), effective.consensusWeight(), effective.consensusRounds(),
                pslBuilder.constantToEntityId(), null);
        HybridConsensusTrainer.Result learned = HybridConsensusTrainer.train(
                graph, pslProgram, observed, plan);

        graph.putModel(FOL_PSL_PROGRAM_ARTIFACT, learned.trainedProgram());
        graph.putArtifactText(PSL_WEIGHTS_ARTIFACT,
                PslWeightLearningService.weightsToJson(learned.trainedProgram().rules()));
        graph.putArtifactText(PSL_RULE_LEGEND_ARTIFACT, pslRuleLegend(graph));
        graph.putArtifactText(MEBN_THEORY_JSON_ARTIFACT,
                RelationalMTheoryArtifactCodec.toJson(mTheory));
        graph.putArtifactText(MEBN_STRENGTHS_ARTIFACT,
                MebnWeightSerializer.strengthsToJson(mTheory));
        graph.putModel(CONSENSUS_TARGETS_ARTIFACT,
                new LinkedHashMap<>(learned.consensusTargets()));
        // Project entity-state posteriors onto Subjective-Logic opinions so downstream consumers
        // (opinions, facts_by_tier, graph_reasoning_query) see trained beliefs without re-deriving
        // them from consensusTargets. Maps PSL constants back to entity ids via the builder.
        int opinionsProjected = 0;
        java.util.LinkedHashSet<String> projectedEntityIds = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Double> target : learned.consensusTargets().entrySet()) {
            String key = target.getKey();
            if (!key.startsWith(GraphPslProgramBuilder.STATE + "(") || !key.endsWith(")")) {
                continue;
            }
            String constant = key.substring(
                    GraphPslProgramBuilder.STATE.length() + 1, key.length() - 1);
            String entityId = pslBuilder.constantToEntityId().get(constant);
            if (entityId != null && graph.entity(entityId).isPresent()) {
                graph.putEntityOpinion(entityId,
                        Opinion.fromSoftTruth(clamp01(target.getValue())));
                projectedEntityIds.add(entityId);
                opinionsProjected++;
            }
        }
        graph.meta("reasoningLearning.entityOpinionsProjected", opinionsProjected);
        // Keep IDs structured: entity identifiers are opaque and may contain commas. The graph
        // archive preserves JSON arrays losslessly; a legacy comma-delimited string remains only a
        // reader compatibility concern in the CLI audit guard.
        graph.meta("reasoningLearning.projectedEntityIds", List.copyOf(projectedEntityIds));
        graph.meta("reasoningLearning.status", "COMPLETED")
                .meta("reasoningLearning.folPsl", true)
                .meta("reasoningLearning.mebn", learned.mebnTrained())
                .meta("reasoningLearning.pslRules", learned.trainedProgram().rules().size())
                .meta("reasoningLearning.mebnFragments", mTheory.getMFrags().size())
                .meta("reasoningLearning.observedTargets", observed.size())
                .meta("reasoningLearning.modelsTrained", learned.modelsTrained())
                .meta("reasoningLearning.consensusRounds", learned.rounds());

        return new Summary(true, !learned.trainedProgram().rules().isEmpty(),
                learned.mebnTrained(), learned.trainedProgram().rules().size(),
                mTheory.getMFrags().size(), observed.size(), learned.modelsTrained(),
                learned.rounds());
    }

    /** Apply portable learned PSL weights to a freshly grounded graph program. */
    public static PslProgram applyLearnedPslWeights(UnifiedGraph graph, PslProgram program) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(program, "program");
        String weights = graph.artifactText(PSL_WEIGHTS_ARTIFACT);
        if (weights == null || weights.isBlank()) {
            return program;
        }
        return PslWeightLearningService.applyWeights(
                program, PslWeightLearningService.parseWeights(weights));
    }

    /** Reconstruct the learned portable MEBN theory, if one was stored with the graph. */
    public static MTheory learnedMTheory(UnifiedGraph graph) {
        Objects.requireNonNull(graph, "graph");
        String theoryJson = graph.artifactText(MEBN_THEORY_JSON_ARTIFACT);
        if (theoryJson != null && !theoryJson.isBlank()) {
            return RelationalMTheoryArtifactCodec.fromJson(theoryJson);
        }
        String strengths = graph.artifactText(MEBN_STRENGTHS_ARTIFACT);
        if (strengths == null || strengths.isBlank()) {
            return null;
        }
        MTheory theory = buildMTheory(graph, Config.defaults().maxRelationTypes());
        MebnWeightSerializer.applyStrengths(theory, strengths);
        return theory;
    }

    /** Build a deterministic typed relational MTheory directly from a store-agnostic graph. */
    public static MTheory buildMTheory(UnifiedGraph graph, int maxRelationTypes) {
        Objects.requireNonNull(graph, "graph");
        Map<String, RelationAccumulator> grouped = new LinkedHashMap<>();
        List<GraphRelation> relations = new ArrayList<>(graph.relations());
        relations.sort(Comparator.comparing(GraphRelation::type)
                .thenComparing(GraphRelation::sourceId)
                .thenComparing(GraphRelation::targetId));
        for (GraphRelation relation : relations) {
            String relationName = sanitizeIdentifier(relation.type(), "relation");
            if (!grouped.containsKey(relationName) && grouped.size() >= Math.max(1, maxRelationTypes)) {
                continue;
            }
            GraphEntity source = graph.entity(relation.sourceId()).orElse(null);
            GraphEntity target = graph.entity(relation.targetId()).orElse(null);
            if (source == null || target == null) {
                continue;
            }
            grouped.computeIfAbsent(relationName, ignored -> new RelationAccumulator())
                    .add(source, target, clamp01(relation.weight() * relation.confidence()));
        }

        List<RelationDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, RelationAccumulator> entry : grouped.entrySet()) {
            RelationAccumulator accumulator = entry.getValue();
            String sourceType = accumulator.dominantSourceType();
            String targetType = accumulator.dominantTargetType();
            descriptors.add(new RelationDescriptor(entry.getKey(), sourceType, targetType,
                    accumulator.meanWeight(), accumulator.sourceIds(sourceType),
                    accumulator.targetIds(targetType)));
        }
        return RelationalMTheoryBuilder.build(
                "portable-" + sanitizeIdentifier(graph.graphId(), "graph"), descriptors);
    }

    private static Map<String, Double> relationTruths(UnifiedGraph graph) {
        Map<String, Double> truths = new LinkedHashMap<>();
        for (GraphRelation relation : graph.relations()) {
            String relationName = sanitizeIdentifier(relation.type(), "relation");
            String key = relationName + "(" + relation.sourceId() + "," + relation.targetId() + ")";
            truths.merge(key, clamp01(relation.weight() * relation.confidence()), Math::max);
        }
        return truths;
    }

    private static String sanitizeIdentifier(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sanitized.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        String result = sanitized.toString().replaceAll("_+", "_");
        if (result.isBlank()) {
            result = fallback;
        }
        if (!Character.isLetter(result.charAt(0)) && result.charAt(0) != '_') {
            result = fallback + "_" + result;
        }
        return result;
    }

    /** Artifact holding a plain-text interpretation guide for the generic PSL rule names. */
    public static final String PSL_RULE_LEGEND_ARTIFACT = "reasoning/psl-rule-legend.txt";

    /**
     * Human-readable legend for {@link #PSL_WEIGHTS_ARTIFACT}: explains the structural predicate
     * vocabulary and lists which relation types of this graph were folded into Link vs Conflict.
     * The PSL program itself is deliberately structure-level (all relations share the
     * State/Link/Conflict atoms) so weights transfer across graphs; the legend preserves the
     * per-graph typing without changing the program.
     */
    private static String pslRuleLegend(UnifiedGraph graph) {
        Set<String> linkTypes = new TreeSet<>();
        Set<String> conflictTypes = new TreeSet<>();
        for (GraphRelation relation : graph.relations()) {
            if (GraphPslProgramBuilder.isIdentitySeparationType(relation.type())) {
                continue;
            }
            if (GraphPslProgramBuilder.isConflictType(relation.type())) {
                conflictTypes.add(relation.type());
            } else {
                linkTypes.add(relation.type());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PSL rule legend (rule keys in reasoning/psl-weights.json)\n");
        sb.append("========================================================\n");
        sb.append("State(N)       : latent truth of entity N (learning target).\n");
        sb.append("Link(X, Y)     : observed positive relation X->Y (weight*confidence).\n");
        sb.append("Conflict(X, Y) : observed contradictory relation X->Y (penalizes State).\n");
        sb.append("Prior(N)       : structural prior from entity confidence + out-degree.\n");
        sb.append("Rule suffix ^2 : hinge-loss weight of the rule; higher = trusted more.\n\n");
        sb.append("Relation types folded into Link   : ").append(linkTypes).append("\n");
        sb.append("Relation types folded into Conflict: ").append(conflictTypes).append("\n");
        return sb.toString();
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class RelationAccumulator {
        private final Map<String, Integer> sourceTypeCounts = new LinkedHashMap<>();
        private final Map<String, Integer> targetTypeCounts = new LinkedHashMap<>();
        private final Map<String, Set<String>> sourceIds = new LinkedHashMap<>();
        private final Map<String, Set<String>> targetIds = new LinkedHashMap<>();
        private double weightSum;
        private int count;

        private void add(GraphEntity source, GraphEntity target, double weight) {
            String sourceType = sanitizeIdentifier(source.type(), "ENTITY").toUpperCase(Locale.ROOT);
            String targetType = sanitizeIdentifier(target.type(), "ENTITY").toUpperCase(Locale.ROOT);
            sourceTypeCounts.merge(sourceType, 1, Integer::sum);
            targetTypeCounts.merge(targetType, 1, Integer::sum);
            sourceIds.computeIfAbsent(sourceType, ignored -> new LinkedHashSet<>()).add(source.id());
            targetIds.computeIfAbsent(targetType, ignored -> new LinkedHashSet<>()).add(target.id());
            weightSum += weight;
            count++;
        }

        private String dominantSourceType() {
            return dominant(sourceTypeCounts);
        }

        private String dominantTargetType() {
            return dominant(targetTypeCounts);
        }

        private Set<String> sourceIds(String type) {
            return sourceIds.getOrDefault(type, Set.of());
        }

        private Set<String> targetIds(String type) {
            return targetIds.getOrDefault(type, Set.of());
        }

        private double meanWeight() {
            return count == 0 ? 0.5 : clamp01(weightSum / count);
        }

        private static String dominant(Map<String, Integer> counts) {
            return counts.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse("ENTITY");
        }
    }
}
