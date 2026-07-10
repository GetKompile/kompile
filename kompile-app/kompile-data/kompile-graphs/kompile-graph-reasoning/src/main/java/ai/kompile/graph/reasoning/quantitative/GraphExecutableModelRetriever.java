/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.Gap;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.GapCode;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.ModelMatch;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.ResolvedIntervention;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval.ScoreBreakdown;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * General graph-backed retrieval for executable quantitative models.
 *
 * <p>HybridReasoner contributes structural and semantic relevance. It does not override hard
 * compatibility checks for output identity, dimensions, units, or valid time.</p>
 */
public final class GraphExecutableModelRetriever implements ExecutableModelRetriever {

    private static final int CONNECTIVITY_PROBE_LIMIT = 8;
    private static final int MAX_INSTANCE_EXPANSION = 40;
    private static final double CONTEXT_DIMENSION_SCORE = 0.85;
    private static final double MIN_ALIAS_CONFIDENCE = 0.5;

    private final QuantitativeRuleCatalog catalog;
    private final HybridReasoner hybridReasoner;
    private final RankingWeights weights;
    private final double ambiguityMargin;
    private final double minimumResolutionScore;
    private final DimensionAliasResolver aliasResolver;

    public GraphExecutableModelRetriever() {
        this(new GraphQuantitativeRuleCatalog(),
                new HybridReasoner().semanticResolutionHops(2),
                RankingWeights.defaults(), 0.04, 0.45);
    }

    public GraphExecutableModelRetriever(
            QuantitativeRuleCatalog catalog,
            HybridReasoner hybridReasoner,
            RankingWeights weights,
            double ambiguityMargin) {
        this(catalog, hybridReasoner, weights, ambiguityMargin, 0.45);
    }

    public GraphExecutableModelRetriever(
            QuantitativeRuleCatalog catalog,
            HybridReasoner hybridReasoner,
            RankingWeights weights,
            double ambiguityMargin,
            double minimumResolutionScore) {
        this(catalog, hybridReasoner, weights, ambiguityMargin, minimumResolutionScore, null);
    }

    public GraphExecutableModelRetriever(
            QuantitativeRuleCatalog catalog,
            HybridReasoner hybridReasoner,
            RankingWeights weights,
            double ambiguityMargin,
            double minimumResolutionScore,
            DimensionAliasResolver aliasResolver) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.hybridReasoner = hybridReasoner;
        this.weights = weights == null ? RankingWeights.defaults() : weights;
        this.ambiguityMargin = ambiguityMargin > 0.0 ? ambiguityMargin : 0.04;
        this.minimumResolutionScore = minimumResolutionScore > 0.0
                && minimumResolutionScore <= 1.0 ? minimumResolutionScore : 0.45;
        this.aliasResolver = aliasResolver;
    }

    /** Same retriever with a host-provided vocabulary bridge (typically a small language model). */
    public GraphExecutableModelRetriever withAliasResolver(DimensionAliasResolver aliasResolver) {
        return new GraphExecutableModelRetriever(catalog, hybridReasoner, weights,
                ambiguityMargin, minimumResolutionScore, aliasResolver);
    }

    @Override
    public ModelRetrieval retrieve(ReasoningGraph graph, QuantitativeQuery query) {
        Objects.requireNonNull(graph, "graph");
        if (query == null || query.target() == null || !query.target().specified()) {
            Gap gap = gap(GapCode.MISSING_TARGET, null,
                    "A target measure selector is required.", Map.of());
            return result(ModelRetrieval.Status.INVALID, query, List.of(), null,
                    List.of(gap), Map.of());
        }

        List<QuantitativeRule> rules = catalog.rules(graph);
        DimensionAliasCatalog aliases = DimensionAliasCatalog.learn(graph);
        List<AppliedAlias> appliedAliases = new ArrayList<>();
        query = translateVocabulary(graph, query, aliases, appliedAliases);
        Map<String, HybridReasoner.ScoredEntity> hybrid = hybridScores(graph, query.queryEmbedding());
        List<ModelMatch> matches = new ArrayList<>();
        for (QuantitativeRule rule : rules) {
            GraphEntity output = graph.entity(rule.outputEntityId()).orElse(null);
            if (output == null) {
                continue;
            }
            ModelMatch match = score(graph, rule, output, query, hybrid, aliases);
            boolean semanticMatch = query.queryEmbedding() != null
                    && match.scores().hybrid() >= minimumResolutionScore;
            if (match.scores().outputMatch() >= minimumResolutionScore || semanticMatch) {
                matches.add(match);
            }
        }
        matches.sort(matchComparator());

        int topK = Math.min(query.topK(), matches.size());
        List<ModelMatch> ranked = List.copyOf(matches.subList(0, topK));
        List<ModelMatch> compatible = matches.stream().filter(ModelMatch::compatible).toList();
        if (compatible.isEmpty()) {
            GraphEntity directTarget = directTarget(graph, query.target());
            GapCode code = directTarget == null ? GapCode.MISSING_TARGET : GapCode.NO_PRODUCING_RULE;
            String message = directTarget == null
                    ? "No graph output resolves to the requested target."
                    : "The target exists, but no compatible graph rule produces it.";
            return result(ModelRetrieval.Status.NOT_FOUND, query, ranked, null,
                    List.of(gap(code, directTarget == null ? null : directTarget.id(), message, Map.of())),
                    Map.of(), appliedAliases);
        }

        // Interventions and goal controls resolve before target selection so ambiguous targets can
        // be disambiguated by reachability: a scenario's model must contain what it perturbs.
        List<Gap> gaps = new ArrayList<>();
        Map<String, String> resolutions = new LinkedHashMap<>();
        List<ResolvedIntervention> interventions = resolveInterventions(
                graph, query, resolutions, gaps, aliases);
        Set<String> interventionIds = interventions.stream()
                .map(ResolvedIntervention::entityId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        EntityResolution controlResolution = query.goal() == null
                ? null : resolveEntitySet(
                        graph, query.goal().control(), query.dimensions(), aliases, true);

        Map<String, List<QuantitativeRule>> byOutput = indexByOutput(rules);
        SelectionOutcome selection = selectTarget(graph, query, compatible, byOutput, interventionIds);
        ModelMatch selected = selection.selected();
        resolutions.put("target", selected.rule().outputEntityId());
        if (!selection.note().isEmpty()) {
            resolutions.put("targetSelection", selection.note());
        }
        if (selection.ambiguous()) {
            ModelMatch second = selection.runnerUp();
            Gap gap = gap(GapCode.AMBIGUOUS_TARGET, null,
                    "Multiple output measures match the target within the ambiguity margin.",
                    Map.of(
                            "first", selected.rule().outputEntityId(),
                            "second", second.rule().outputEntityId(),
                            "firstScore", format(selected.scores().total()),
                            "secondScore", format(second.scores().total())));
            return result(ModelRetrieval.Status.AMBIGUOUS, query, ranked, null,
                    List.of(gap), resolutions, appliedAliases);
        }

        Closure closure = new Closure(graph, query, byOutput, interventionIds, gaps);
        closure.visit(selected.rule());
        Set<String> planEntities = closureEntities(selected, closure);

        List<String> planControls = resolvePlanControls(
                graph, controlResolution, selected, planEntities, resolutions, gaps);
        String goalControl = planControls.isEmpty() ? null : planControls.get(0);

        if (!interventionIds.isEmpty()
                && java.util.Collections.disjoint(planEntities, interventionIds)) {
            gaps.add(gap(GapCode.INTERVENTION_DISCONNECTED, selected.rule().outputEntityId(),
                    "No resolved intervention is part of the selected model's dependency closure; "
                            + "the scenario cannot affect this target.",
                    Map.of("interventions", String.join(",", interventionIds))));
        }

        ModelRetrieval.Plan plan = new ModelRetrieval.Plan(
                selected.rule().outputEntityId(),
                closure.orderedRules,
                List.copyOf(closure.leaves),
                interventions,
                goalControl,
                List.copyOf(planControls));

        ModelRetrieval.Status status = statusFor(gaps);
        return result(status, query, ranked, plan, gaps, resolutions, appliedAliases);
    }

    /** A resolver-supplied vocabulary translation applied to this retrieval, for trace provenance. */
    private record AppliedAlias(
            String dimensionKey, String requested, String member, double confidence, String source) {
    }

    /**
     * Translates requested dimension vocabulary the graph cannot satisfy into graph members via
     * the host-provided resolver. Consulted only on demand: values already supported by explicit
     * dimensions, taxonomy aliases, or context evidence never reach the resolver. Answers outside
     * the offered member list or below the confidence floor are discarded. Translation rewrites
     * the question only — downstream compatibility remains pure graph evidence.
     */
    private QuantitativeQuery translateVocabulary(
            ReasoningGraph graph,
            QuantitativeQuery query,
            DimensionAliasCatalog aliases,
            List<AppliedAlias> applied) {
        if (aliasResolver == null) {
            return query;
        }
        Map<String, String> requested = new LinkedHashMap<>();
        collectDimensions(requested, query.dimensions());
        collectDimensions(requested, query.target() == null
                ? Map.of() : query.target().dimensions());
        for (QuantitativeQuery.Intervention intervention : query.interventions()) {
            collectDimensions(requested, intervention.target().dimensions());
        }
        if (query.goal() != null) {
            collectDimensions(requested, query.goal().control().dimensions());
        }

        Map<String, String> translations = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String key = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            String value = entry.getValue();
            if (satisfiable(graph, key, value, aliases)) {
                continue;
            }
            List<String> members = DimensionVocabulary.members(graph, key);
            if (members.isEmpty()) {
                continue;
            }
            aliasResolver.resolve(new DimensionAliasResolver.Question(key, value, members))
                    .filter(resolution -> resolution.confidence() >= MIN_ALIAS_CONFIDENCE)
                    .filter(resolution -> members.stream().anyMatch(member ->
                            QuantitativeGraphSupport.normalizeText(member).equals(
                                    QuantitativeGraphSupport.normalizeText(resolution.member()))))
                    .ifPresent(resolution -> {
                        translations.put(entry.getKey(), resolution.member());
                        applied.add(new AppliedAlias(key, value, resolution.member(),
                                resolution.confidence(), resolution.source()));
                    });
        }
        if (translations.isEmpty()) {
            return query;
        }

        QuantitativeQuery.MeasureSelector target = translateSelector(query.target(), translations);
        List<QuantitativeQuery.Intervention> interventions = query.interventions().stream()
                .map(intervention -> new QuantitativeQuery.Intervention(
                        translateSelector(intervention.target(), translations),
                        intervention.operation(), intervention.value()))
                .toList();
        QuantitativeQuery.Goal goal = query.goal() == null ? null : new QuantitativeQuery.Goal(
                translateSelector(query.goal().control(), translations),
                query.goal().targetValue(), query.goal().minimum(), query.goal().maximum(),
                query.goal().tolerance(), query.goal().maxIterations());
        return new QuantitativeQuery(query.mode(), target, interventions,
                translateDimensions(query.dimensions(), translations), query.asOf(),
                query.queryEmbedding(), query.topK(), goal);
    }

    private static void collectDimensions(Map<String, String> requested, Map<String, String> dims) {
        for (Map.Entry<String, String> entry : dims.entrySet()) {
            if (!QuantitativeGraphSupport.blank(entry.getKey())
                    && !QuantitativeGraphSupport.blank(entry.getValue())) {
                requested.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT)
                        + '|' + entry.getValue(), entry.getValue());
            }
        }
    }

    private static boolean satisfiable(
            ReasoningGraph graph, String key, String value, DimensionAliasCatalog aliases) {
        for (GraphEntity entity : graph.entities()) {
            QuantitativeGraphSupport.DimensionMatch match =
                    QuantitativeGraphSupport.dimensionCompatibility(entity,
                            QuantitativeGraphSupport.dimensions(entity), key, value, aliases);
            if (match == QuantitativeGraphSupport.DimensionMatch.MATCH
                    || match == QuantitativeGraphSupport.DimensionMatch.CONTEXT_MATCH) {
                return true;
            }
        }
        return false;
    }

    private static QuantitativeQuery.MeasureSelector translateSelector(
            QuantitativeQuery.MeasureSelector selector, Map<String, String> translations) {
        if (selector == null || selector.dimensions().isEmpty()) {
            return selector;
        }
        return new QuantitativeQuery.MeasureSelector(
                selector.entityId(), selector.text(), selector.type(), selector.unit(),
                translateDimensions(selector.dimensions(), translations));
    }

    private static Map<String, String> translateDimensions(
            Map<String, String> dims, Map<String, String> translations) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : dims.entrySet()) {
            String translationKey = entry.getKey() == null || entry.getValue() == null
                    ? null : entry.getKey().toLowerCase(Locale.ROOT) + '|' + entry.getValue();
            String translated = translationKey == null ? null : translations.get(translationKey);
            result.put(entry.getKey(), translated == null ? entry.getValue() : translated);
        }
        return result;
    }

    private static Set<String> closureEntities(ModelMatch selected, Closure closure) {
        Set<String> planEntities = new LinkedHashSet<>(closure.leaves);
        planEntities.add(selected.rule().outputEntityId());
        for (QuantitativeRule rule : closure.orderedRules) {
            planEntities.add(rule.outputEntityId());
            rule.inputs().forEach(input -> planEntities.add(input.entityId()));
        }
        return planEntities;
    }

    private record SelectionOutcome(
            ModelMatch selected, ModelMatch runnerUp, boolean ambiguous, String note) {
    }

    /**
     * Chooses among compatible candidates. With interventions present, candidates whose dependency
     * closure cannot reach any intervention are skipped in favor of the best reachable one; label
     * similarity alone cannot tell "Total net revenue" on a channel summary from the same line on
     * an entity roll-up, but reachability can.
     */
    private SelectionOutcome selectTarget(
            ReasoningGraph graph,
            QuantitativeQuery query,
            List<ModelMatch> compatible,
            Map<String, List<QuantitativeRule>> byOutput,
            Set<String> interventionIds) {
        ModelMatch best = compatible.get(0);
        if (!QuantitativeGraphSupport.blank(query.target().entityId()) || compatible.size() == 1) {
            return new SelectionOutcome(best, null, false, "");
        }
        if (!interventionIds.isEmpty()) {
            List<ModelMatch> connected = new ArrayList<>();
            int probeLimit = Math.min(compatible.size(), CONNECTIVITY_PROBE_LIMIT);
            for (int i = 0; i < probeLimit; i++) {
                ModelMatch candidate = compatible.get(i);
                if (closureContains(graph, query, byOutput, candidate, interventionIds)) {
                    connected.add(candidate);
                }
            }
            if (!connected.isEmpty()) {
                ModelMatch chosen = connected.get(0);
                boolean stillAmbiguous = connected.size() > 1
                        && !chosen.rule().outputEntityId().equals(
                                connected.get(1).rule().outputEntityId())
                        && chosen.scores().total() - connected.get(1).scores().total()
                        < ambiguityMargin;
                String note = chosen == best ? ""
                        : "intervention reachability preferred " + chosen.rule().outputEntityId()
                                + " over " + best.rule().outputEntityId();
                return new SelectionOutcome(chosen,
                        stillAmbiguous ? connected.get(1) : null, stillAmbiguous, note);
            }
        }
        boolean ambiguous = ambiguousTarget(query, compatible);
        return new SelectionOutcome(best, ambiguous ? compatible.get(1) : null, ambiguous, "");
    }

    private boolean closureContains(
            ReasoningGraph graph,
            QuantitativeQuery query,
            Map<String, List<QuantitativeRule>> byOutput,
            ModelMatch candidate,
            Set<String> entityIds) {
        if (entityIds.contains(candidate.rule().outputEntityId())) {
            return true;
        }
        Closure probe = new Closure(graph, query, byOutput, entityIds, new ArrayList<>());
        probe.visit(candidate.rule());
        if (probe.leaves.stream().anyMatch(entityIds::contains)) {
            return true;
        }
        for (QuantitativeRule rule : probe.orderedRules) {
            if (entityIds.contains(rule.outputEntityId())) {
                return true;
            }
            for (QuantitativeRule.Input input : rule.inputs()) {
                if (entityIds.contains(input.entityId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private ModelMatch score(
            ReasoningGraph graph,
            QuantitativeRule rule,
            GraphEntity output,
            QuantitativeQuery query,
            Map<String, HybridReasoner.ScoredEntity> hybridScores,
            DimensionAliasCatalog aliases) {
        List<String> rejectionReasons = new ArrayList<>();
        double outputMatch = QuantitativeGraphSupport.selectorScore(output, query.target());

        Map<String, String> requestedDimensions = new LinkedHashMap<>(query.dimensions());
        requestedDimensions.putAll(query.target().dimensions());
        int dimensionCount = requestedDimensions.size();
        double dimensionTotal = 0.0;
        for (Map.Entry<String, String> requested : requestedDimensions.entrySet()) {
            switch (QuantitativeGraphSupport.dimensionCompatibility(
                    output, rule.dimensions(), requested.getKey(), requested.getValue(), aliases)) {
                case MATCH -> dimensionTotal += 1.0;
                case CONTEXT_MATCH -> dimensionTotal += CONTEXT_DIMENSION_SCORE;
                case MISSING -> rejectionReasons.add("dimension " + requested.getKey()
                        + " is missing; cannot verify " + requested.getValue());
                case CONFLICT -> rejectionReasons.add("dimension " + requested.getKey() + "="
                        + QuantitativeGraphSupport.dimension(
                                output, rule.dimensions(), requested.getKey())
                        + " does not match " + requested.getValue());
            }
        }
        double dimensionMatch = dimensionCount == 0 ? 1.0 : dimensionTotal / dimensionCount;

        String requestedUnit = query.target().unit();
        String actualUnit = firstNonBlank(rule.unit(), QuantitativeGraphSupport.unit(output));
        double unitMatch = 1.0;
        if (!QuantitativeGraphSupport.blank(requestedUnit)) {
            if (QuantitativeGraphSupport.blank(actualUnit)) {
                unitMatch = 0.0;
                rejectionReasons.add("unit is missing; cannot verify " + requestedUnit);
            } else if (!actualUnit.equalsIgnoreCase(requestedUnit)) {
                unitMatch = 0.0;
                rejectionReasons.add("unit " + actualUnit + " does not match " + requestedUnit);
            }
        }

        double temporalMatch = 1.0;
        Instant asOf = query.asOf();
        GraphEntity source = graph.entity(rule.sourceEntityId()).orElse(null);
        if (asOf != null && (!output.isValidAt(asOf) || (source != null && !source.isValidAt(asOf)))) {
            temporalMatch = 0.0;
            rejectionReasons.add("rule or output is outside requested valid time");
        }

        double provenance = QuantitativeGraphSupport.clamp01(
                0.5 * rule.confidence() + 0.5 * rule.validationScore());
        HybridReasoner.ScoredEntity ruleHybrid = hybridScores.get(rule.sourceEntityId());
        HybridReasoner.ScoredEntity outputHybrid = hybridScores.get(rule.outputEntityId());
        double hybrid = Math.max(ruleHybrid == null ? 0.0 : ruleHybrid.score(),
                outputHybrid == null ? 0.0 : outputHybrid.score());

        double total = weights.weighted(
                outputMatch, dimensionMatch, unitMatch, temporalMatch, provenance, hybrid);
        return new ModelMatch(rule, output.label(),
                new ScoreBreakdown(outputMatch, dimensionMatch, unitMatch, temporalMatch,
                        provenance, hybrid, total),
                rejectionReasons.isEmpty(), rejectionReasons);
    }

    private Map<String, HybridReasoner.ScoredEntity> hybridScores(
            ReasoningGraph graph, double[] queryEmbedding) {
        if (hybridReasoner == null || graph.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, HybridReasoner.ScoredEntity> scores = new HashMap<>();
            for (HybridReasoner.ScoredEntity score : hybridReasoner.rank(graph, queryEmbedding)) {
                scores.put(score.entityId(), score);
            }
            return scores;
        } catch (RuntimeException | LinkageError ignored) {
            // The host distribution may intentionally omit a structural backend. Typed retrieval
            // remains available with lexical, compatibility, provenance, and replay scores.
            return Map.of();
        }
    }

    private List<ResolvedIntervention> resolveInterventions(
            ReasoningGraph graph,
            QuantitativeQuery query,
            Map<String, String> resolutions,
            List<Gap> gaps,
            DimensionAliasCatalog aliases) {
        List<ResolvedIntervention> result = new ArrayList<>();
        for (int i = 0; i < query.interventions().size(); i++) {
            QuantitativeQuery.Intervention intervention = query.interventions().get(i);
            EntityResolution resolution = resolveEntitySet(
                    graph, intervention.target(), query.dimensions(), aliases, true);
            String role = "intervention[" + i + "]";
            if (resolution.selected().isEmpty() && resolution.candidates().isEmpty()) {
                gaps.add(gap(GapCode.UNRESOLVED_INTERVENTION, null,
                        "No graph entity resolves to " + role + ".",
                        Map.of("requested", QuantitativeGraphSupport.requestedTarget(intervention.target()))));
                continue;
            }
            if (resolution.ambiguous()) {
                gaps.add(gap(GapCode.AMBIGUOUS_INTERVENTION, null,
                        "Multiple graph entities resolve to " + role + ".",
                        Map.of("candidates", String.join(",", resolution.candidates()))));
                continue;
            }
            resolutions.put(role, String.join(",", resolution.selected()));
            for (String entityId : resolution.selected()) {
                result.add(new ResolvedIntervention(
                        entityId, intervention.operation(), intervention.value(),
                        QuantitativeGraphSupport.requestedTarget(intervention.target())));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Finalizes goal controls against the selected plan. A control that resolves ambiguously
     * across sources (two workbooks carrying the same units grid) is retried inside the plan's
     * dependency closure: the goal knob must be part of the model being solved, and that scope is
     * usually exactly the disambiguation the raw graph could not provide.
     */
    private List<String> resolvePlanControls(
            ReasoningGraph graph,
            EntityResolution controlResolution,
            ModelMatch selected,
            Set<String> planEntities,
            Map<String, String> resolutions,
            List<Gap> gaps) {
        if (controlResolution == null) {
            return List.of();
        }
        if (controlResolution.selected().isEmpty() && controlResolution.group().isEmpty()) {
            gaps.add(gap(GapCode.UNRESOLVED_GOAL_CONTROL, null,
                    "The goal control does not resolve to a graph entity.",
                    Map.of("candidates", String.join(",", controlResolution.candidates()))));
            return List.of();
        }
        List<String> pool = controlResolution.ambiguous()
                ? controlResolution.group() : controlResolution.selected();
        List<String> inPlan = pool.stream().filter(planEntities::contains).toList();
        if (inPlan.isEmpty()) {
            if (controlResolution.ambiguous()) {
                gaps.add(gap(GapCode.UNRESOLVED_GOAL_CONTROL, null,
                        "The goal control resolves ambiguously and no candidate is part of the "
                                + "selected dependency closure.",
                        Map.of("candidates", String.join(",", controlResolution.candidates()))));
            } else {
                gaps.add(gap(GapCode.GOAL_CONTROL_NOT_IN_PLAN, pool.get(0),
                        "The requested goal control is not part of the selected dependency closure.",
                        Map.of("target", selected.rule().outputEntityId())));
            }
            return List.of();
        }
        List<String> controls = inPlan;
        if (inPlan.size() > 1 && controlResolution.ambiguous()) {
            List<GraphEntity> entities = inPlan.stream()
                    .map(id -> graph.entity(id).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            List<String> instances = instanceGroup(entities);
            if (instances == null) {
                gaps.add(gap(GapCode.UNRESOLVED_GOAL_CONTROL, null,
                        "The goal control resolves ambiguously even within the selected plan.",
                        Map.of("candidates", String.join(",", inPlan))));
                return List.of();
            }
            controls = instances;
        }
        resolutions.put("goalControl", String.join(",", controls));
        if (controls.size() > 1) {
            resolutions.put("goalControlCandidates", String.join(",", controls));
        }
        return controls;
    }

    /**
     * Resolves a selector to one entity, or to a family of instances when the near-tied candidates
     * are the same measure sliced along one facet: identical measure column across distinct row
     * slices ("Jul rev" per channel row), or the same row measure across periods ("Jun 2026",
     * "Jul 2026", "Aug 2026"). Anything else stays ambiguous rather than guessing.
     */
    private EntityResolution resolveEntitySet(
            ReasoningGraph graph,
            QuantitativeQuery.MeasureSelector selector,
            Map<String, String> inheritedDimensions,
            DimensionAliasCatalog aliases,
            boolean preferLeafValues) {
        List<EntityScore> candidates = new ArrayList<>();
        Map<String, String> dimensions = new LinkedHashMap<>(inheritedDimensions);
        dimensions.putAll(selector.dimensions());
        for (GraphEntity entity : graph.entities()) {
            if (!QuantitativeGraphSupport.quantitativeCandidate(entity)) {
                continue;
            }
            double score = QuantitativeGraphSupport.selectorScore(entity, selector);
            if (score < minimumResolutionScore) {
                continue;
            }
            double dimensionScore = 0.0;
            boolean incompatible = false;
            for (Map.Entry<String, String> requested : dimensions.entrySet()) {
                switch (QuantitativeGraphSupport.dimensionCompatibility(
                        entity, QuantitativeGraphSupport.dimensions(entity),
                        requested.getKey(), requested.getValue(), aliases)) {
                    case MATCH -> dimensionScore += 1.0;
                    case CONTEXT_MATCH -> dimensionScore += CONTEXT_DIMENSION_SCORE;
                    default -> incompatible = true;
                }
                if (incompatible) {
                    break;
                }
            }
            if (incompatible) {
                continue;
            }
            if (!dimensions.isEmpty()) {
                score = 0.8 * score + 0.2 * (dimensionScore / dimensions.size());
            }
            candidates.add(new EntityScore(entity, score));
        }
        candidates.sort(Comparator.comparingDouble(EntityScore::score).reversed()
                .thenComparing(candidate -> candidate.entity().id()));
        if (candidates.isEmpty()) {
            return new EntityResolution(List.of(), List.of(), false);
        }
        List<String> shortlist = candidates.stream().limit(8)
                .map(candidate -> candidate.entity().id()).toList();
        boolean exact = !QuantitativeGraphSupport.blank(selector.entityId())
                && candidates.get(0).entity().id().equalsIgnoreCase(selector.entityId());
        if (exact) {
            return new EntityResolution(
                    List.of(candidates.get(0).entity().id()), shortlist, false);
        }

        double topScore = candidates.get(0).score();
        List<GraphEntity> group = new ArrayList<>();
        for (EntityScore candidate : candidates) {
            if (topScore - candidate.score() >= ambiguityMargin) {
                break;
            }
            group.add(candidate.entity());
        }
        if (preferLeafValues) {
            List<GraphEntity> leaves = group.stream()
                    .filter(entity -> QuantitativeGraphSupport.blank(
                            QuantitativeGraphSupport.firstString(entity, "formula", "expression")))
                    .toList();
            if (!leaves.isEmpty() && leaves.size() < group.size()) {
                group = leaves;
            }
        }
        if (group.size() == 1) {
            return new EntityResolution(List.of(group.get(0).id()), shortlist, false);
        }
        List<String> instances = instanceGroup(group);
        if (instances != null) {
            return new EntityResolution(instances, shortlist, false);
        }
        return new EntityResolution(List.of(candidates.get(0).entity().id()), shortlist, true,
                group.stream().map(GraphEntity::id).toList());
    }

    /**
     * Near-tied candidates form an instance family only within one workbook sheet and only along
     * one facet: identical column label with pairwise-distinct row identities, or identical row
     * identity and column signature with pairwise-distinct period tokens.
     */
    private static List<String> instanceGroup(List<GraphEntity> group) {
        if (group.size() > MAX_INSTANCE_EXPANSION) {
            return null;
        }
        Set<String> sheets = new LinkedHashSet<>();
        Set<String> columnLabels = new LinkedHashSet<>();
        Set<String> columnSignatures = new LinkedHashSet<>();
        Set<String> rowSignatures = new LinkedHashSet<>();
        Set<String> temporalSets = new LinkedHashSet<>();
        for (GraphEntity entity : group) {
            Map<String, String> dimensions = QuantitativeGraphSupport.dimensions(entity);
            sheets.add(dimensions.getOrDefault("workbook", "")
                    + "|" + dimensions.getOrDefault("sheet", ""));
            String columnLabel = QuantitativeGraphSupport.firstString(entity, "columnLabel");
            columnLabels.add(columnLabel == null ? "" : columnLabel);
            columnSignatures.add(QuantitativeGraphSupport.nonTemporalSignature(columnLabel));
            rowSignatures.add(rowSignature(entity));
            temporalSets.add(QuantitativeGraphSupport.temporalTokens(columnLabel).toString());
        }
        if (sheets.size() != 1) {
            return null;
        }
        boolean rowSlices = columnLabels.size() == 1 && rowSignatures.size() == group.size();
        boolean periodSlices = rowSignatures.size() == 1 && columnSignatures.size() == 1
                && temporalSets.size() == group.size();
        if (!rowSlices && !periodSlices) {
            return null;
        }
        return group.stream().map(GraphEntity::id).toList();
    }

    private static String rowSignature(GraphEntity entity) {
        Map<String, String> dimensions = new java.util.TreeMap<>(
                QuantitativeGraphSupport.dimensions(entity));
        dimensions.keySet().removeAll(Set.of("sheet", "workbook", "column"));
        String rowLabel = QuantitativeGraphSupport.firstString(entity, "rowLabel");
        return QuantitativeGraphSupport.normalizeText(rowLabel) + "|" + dimensions;
    }

    private boolean ambiguousTarget(QuantitativeQuery query, List<ModelMatch> compatible) {
        if (!QuantitativeGraphSupport.blank(query.target().entityId()) || compatible.size() < 2) {
            return false;
        }
        ModelMatch first = compatible.get(0);
        ModelMatch second = compatible.get(1);
        return !first.rule().outputEntityId().equals(second.rule().outputEntityId())
                && first.scores().total() - second.scores().total() < ambiguityMargin;
    }

    private GraphEntity directTarget(
            ReasoningGraph graph, QuantitativeQuery.MeasureSelector selector) {
        if (!QuantitativeGraphSupport.blank(selector.entityId())) {
            return graph.entity(selector.entityId()).orElse(null);
        }
        return graph.entities().stream()
                .map(entity -> new EntityCandidate(
                        entity, QuantitativeGraphSupport.selectorScore(entity, selector)))
                .filter(candidate -> candidate.score() >= minimumResolutionScore)
                .max(Comparator.comparingDouble(EntityCandidate::score))
                .map(EntityCandidate::entity).orElse(null);
    }

    private static Map<String, List<QuantitativeRule>> indexByOutput(
            List<QuantitativeRule> rules) {
        Map<String, List<QuantitativeRule>> result = new LinkedHashMap<>();
        for (QuantitativeRule rule : rules) {
            result.computeIfAbsent(rule.outputEntityId(), ignored -> new ArrayList<>()).add(rule);
        }
        for (List<QuantitativeRule> values : result.values()) {
            values.sort(Comparator.comparingDouble(GraphExecutableModelRetriever::ruleQuality)
                    .reversed().thenComparing(QuantitativeRule::id));
        }
        return result;
    }

    private static double ruleQuality(QuantitativeRule rule) {
        return 0.5 * rule.confidence() + 0.5 * rule.validationScore();
    }

    private ModelRetrieval result(
            ModelRetrieval.Status status,
            QuantitativeQuery query,
            List<ModelMatch> matches,
            ModelRetrieval.Plan plan,
            List<Gap> gaps,
            Map<String, String> resolutions) {
        return result(status, query, matches, plan, gaps, resolutions, List.of());
    }

    private ModelRetrieval result(
            ModelRetrieval.Status status,
            QuantitativeQuery query,
            List<ModelMatch> matches,
            ModelRetrieval.Plan plan,
            List<Gap> gaps,
            Map<String, String> resolutions,
            List<AppliedAlias> appliedAliases) {
        Map<String, String> withAliases = new LinkedHashMap<>(resolutions);
        for (AppliedAlias alias : appliedAliases) {
            withAliases.put("alias:" + alias.dimensionKey(),
                    alias.requested() + " -> " + alias.member()
                            + " (" + alias.source() + ", " + format(alias.confidence()) + ")");
        }
        ReasoningTrace trace = retrievalTrace(status, query, matches, plan, gaps, appliedAliases);
        return new ModelRetrieval(status, query, matches, plan, gaps, withAliases, trace);
    }

    private ReasoningTrace retrievalTrace(
            ModelRetrieval.Status status,
            QuantitativeQuery query,
            List<ModelMatch> matches,
            ModelRetrieval.Plan plan,
            List<Gap> gaps,
            List<AppliedAlias> appliedAliases) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (AppliedAlias alias : appliedAliases) {
            premises.add(new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.ASSUMPTION,
                    "vocabulary: " + alias.dimensionKey() + " '" + alias.requested()
                            + "' means '" + alias.member() + "'",
                    "alias_resolution",
                    QuantitativeGraphSupport.clamp01(alias.confidence()),
                    alias.source(), List.of(), null,
                    Map.of("dimensionKey", alias.dimensionKey(),
                            "requested", alias.requested(),
                            "member", alias.member())));
        }
        for (ModelMatch match : matches) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("ruleId", match.rule().id());
            meta.put("outputEntityId", match.rule().outputEntityId());
            meta.put("totalScore", format(match.scores().total()));
            meta.put("outputMatch", format(match.scores().outputMatch()));
            meta.put("dimensionMatch", format(match.scores().dimensionMatch()));
            meta.put("unitMatch", format(match.scores().unitMatch()));
            meta.put("provenance", format(match.scores().provenance()));
            meta.put("hybrid", format(match.scores().hybrid()));
            if (!match.rejectionReasons().isEmpty()) {
                meta.put("rejectedBecause", String.join("; ", match.rejectionReasons()));
            }
            premises.add(new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.QUERY,
                    "candidate " + match.rule().id() + " -> " + match.outputLabel(),
                    "rank_executable_model",
                    QuantitativeGraphSupport.clamp01(match.scores().total()),
                    match.rule().sourceEntityId(),
                    List.of(), null, meta));
        }
        if (plan != null) {
            for (QuantitativeRule rule : plan.rules()) {
                premises.add(new ReasoningTrace.Step(
                        ReasoningTrace.StepKind.RULE,
                        "selected " + rule.id() + " -> " + rule.outputEntityId(),
                        rule.expression(), rule.confidence(), rule.sourceEntityId(),
                        List.of(), null, Map.of(
                                "engineId", rule.engineId(),
                                "validationScore", format(rule.validationScore()))));
            }
        }
        for (Gap gap : gaps) {
            premises.add(new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.REBUTTAL,
                    gap.code() + ": " + gap.message(),
                    "quantitative_gap", 1.0, gap.entityId(), List.of(), null,
                    gap.details()));
        }
        double confidence = matches.isEmpty() ? 0.0
                : QuantitativeGraphSupport.clamp01(matches.get(0).scores().total());
        String target = query == null ? "" : QuantitativeGraphSupport.requestedTarget(query.target());
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.QUERY,
                "Executable model retrieval " + status + " for " + target,
                "graph_executable_model_retrieval",
                confidence, null,
                Map.of("status", status.name(), "candidateCount", String.valueOf(matches.size()),
                        "gapCount", String.valueOf(gaps.size())),
                premises);
        return ReasoningTrace.of(root);
    }

    private static ModelRetrieval.Status statusFor(List<Gap> gaps) {
        if (gaps.stream().anyMatch(gap -> Set.of(
                GapCode.AMBIGUOUS_RULE, GapCode.AMBIGUOUS_INTERVENTION,
                GapCode.UNRESOLVED_GOAL_CONTROL).contains(gap.code()))) {
            return ModelRetrieval.Status.AMBIGUOUS;
        }
        return gaps.isEmpty() ? ModelRetrieval.Status.READY : ModelRetrieval.Status.PARTIAL;
    }

    private static Comparator<ModelMatch> matchComparator() {
        return Comparator.comparing(ModelMatch::compatible).reversed()
                .thenComparing((ModelMatch match) -> match.scores().total(), Comparator.reverseOrder())
                .thenComparing(match -> match.rule().id());
    }

    private static Gap gap(
            GapCode code, String entityId, String message, Map<String, String> details) {
        return new Gap(code, entityId, message, details);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!QuantitativeGraphSupport.blank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record EntityScore(GraphEntity entity, double score) {
    }

    /**
     * {@code group} carries the full near-tied candidate set when resolution stays ambiguous, so
     * later plan-scoped disambiguation can retry against a narrower entity population.
     */
    private record EntityResolution(
            List<String> selected, List<String> candidates, boolean ambiguous,
            List<String> group) {

        private EntityResolution {
            selected = List.copyOf(selected);
            candidates = List.copyOf(candidates);
            group = group == null ? List.of() : List.copyOf(group);
        }

        private EntityResolution(
                List<String> selected, List<String> candidates, boolean ambiguous) {
            this(selected, candidates, ambiguous, List.of());
        }
    }

    private record EntityCandidate(GraphEntity entity, double score) {
    }

    private final class Closure {
        private final ReasoningGraph graph;
        private final QuantitativeQuery query;
        private final Map<String, List<QuantitativeRule>> byOutput;
        private final Set<String> interventionEntities;
        private final List<Gap> gaps;
        private final List<QuantitativeRule> orderedRules = new ArrayList<>();
        private final Set<String> leaves = new LinkedHashSet<>();
        private final Set<String> visiting = new HashSet<>();
        private final Set<String> addedRules = new HashSet<>();

        private Closure(
                ReasoningGraph graph,
                QuantitativeQuery query,
                Map<String, List<QuantitativeRule>> byOutput,
                Set<String> interventionEntities,
                List<Gap> gaps) {
            this.graph = graph;
            this.query = query;
            this.byOutput = byOutput;
            this.interventionEntities = interventionEntities;
            this.gaps = gaps;
        }

        private void visit(QuantitativeRule rule) {
            if (addedRules.contains(rule.id())) {
                return;
            }
            if (!visiting.add(rule.outputEntityId())) {
                gaps.add(gap(GapCode.CYCLE, rule.outputEntityId(),
                        "Cycle detected while assembling the quantitative dependency closure.",
                        Map.of("ruleId", rule.id())));
                return;
            }

            for (QuantitativeRule.Input input : rule.inputs()) {
                if (interventionEntities.contains(input.entityId())) {
                    leaves.add(input.entityId());
                    continue;
                }
                List<QuantitativeRule> producers = applicableProducers(input.entityId());
                if (!producers.isEmpty()) {
                    if (producers.size() > 1
                            && ruleQuality(producers.get(0)) - ruleQuality(producers.get(1))
                            < ambiguityMargin) {
                        gaps.add(gap(GapCode.AMBIGUOUS_RULE, input.entityId(),
                                "Multiple rules can produce a required input.",
                                Map.of("first", producers.get(0).id(),
                                        "second", producers.get(1).id())));
                    }
                    visit(producers.get(0));
                    continue;
                }

                GraphEntity inputEntity = graph.entity(input.entityId()).orElse(null);
                OptionalDouble value = QuantitativeGraphSupport.numericValue(inputEntity);
                if (value.isPresent()) {
                    leaves.add(input.entityId());
                } else if (input.required()) {
                    gaps.add(gap(GapCode.MISSING_INPUT_VALUE, input.entityId(),
                            "No graph value or producing rule exists for required input " + input.alias() + ".",
                            Map.of("consumerRule", rule.id())));
                }
            }

            visiting.remove(rule.outputEntityId());
            if (addedRules.add(rule.id())) {
                orderedRules.add(rule);
            }
        }

        private List<QuantitativeRule> applicableProducers(String outputEntityId) {
            List<QuantitativeRule> candidates = byOutput.getOrDefault(outputEntityId, List.of());
            if (query.asOf() == null) {
                return candidates;
            }
            return candidates.stream().filter(candidate -> {
                GraphEntity source = graph.entity(candidate.sourceEntityId()).orElse(null);
                GraphEntity output = graph.entity(candidate.outputEntityId()).orElse(null);
                return (source == null || source.isValidAt(query.asOf()))
                        && (output == null || output.isValidAt(query.asOf()));
            }).toList();
        }
    }

    /** Configurable, domain-neutral candidate-ranking weights. */
    public record RankingWeights(
            double output,
            double dimensions,
            double unit,
            double temporal,
            double provenance,
            double hybrid) {

        public RankingWeights {
            if (output < 0 || dimensions < 0 || unit < 0 || temporal < 0
                    || provenance < 0 || hybrid < 0
                    || output + dimensions + unit + temporal + provenance + hybrid <= 0.0) {
                throw new IllegalArgumentException("ranking weights must be non-negative with a positive sum");
            }
        }

        public static RankingWeights defaults() {
            return new RankingWeights(0.35, 0.15, 0.10, 0.10, 0.20, 0.10);
        }

        private double weighted(
                double outputScore,
                double dimensionScore,
                double unitScore,
                double temporalScore,
                double provenanceScore,
                double hybridScore) {
            double sum = output + dimensions + unit + temporal + provenance + hybrid;
            return QuantitativeGraphSupport.clamp01((
                    output * outputScore
                            + dimensions * dimensionScore
                            + unit * unitScore
                            + temporal * temporalScore
                            + provenance * provenanceScore
                            + hybrid * hybridScore) / sum);
        }
    }
}
