/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.evaluation.ExpectedProcessCase.PrecedenceConstraint;
import ai.kompile.process.discovery.evaluation.ExpectedProcessCase.RequiredConcept;
import ai.kompile.process.discovery.evaluation.ProcessCandidateEvaluation.Disposition;
import ai.kompile.process.discovery.evaluation.ProcessCandidateEvaluation.EvidenceResolution;
import ai.kompile.process.discovery.mining.ReasoningGraphProcessGenerator;
import ai.kompile.process.discovery.mining.log.Trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, domain-neutral quality evaluation for generated process candidates.
 *
 * <p>Recall uses these explicit conventions: 0/0 = 1 (nothing required is fully recalled),
 * 0/N = 0 for N > 0, values strictly between zero and one are partial, and 1 is full.
 * Trace coverage is the greatest required-concept recall attained by one trace; this prevents
 * unrelated traces from collectively masquerading as one supported process. A FULL disposition
 * means the assigned candidate reaches the case's hit threshold, PARTIAL means it has a positive
 * semantic score below threshold, and MISSED means it has none. With no assigned candidate the
 * disposition is UNASSIGNED and the score is zero. Hit rate likewise defines 0/0 as 1.
 */
public final class ProcessQualityEvaluator {

    private static final double EPSILON = 1.0e-12;

    public ProcessEvaluationResult evaluate(
            List<ExpectedProcessCase> expectedProcesses,
            List<ReasoningGraphProcessGenerator.Candidate> candidates) {
        return evaluate(expectedProcesses, candidates, null);
    }

    /**
     * Evaluate candidates while resolving their evidence ids through the graph that produced them.
     *
     * <p>The graph is read-only evaluation context. Candidate evidence ids are intentionally opaque,
     * so judging them without resolving entity labels, types, tags, attributes, and relation
     * endpoints discards semantic evidence already selected by the production generator. Passing a
     * {@code null} graph preserves the legacy text-only behavior.</p>
     */
    public ProcessEvaluationResult evaluate(
            List<ExpectedProcessCase> expectedProcesses,
            List<ReasoningGraphProcessGenerator.Candidate> candidates,
            ReasoningGraph graph) {
        List<ExpectedProcessCase> expected = expectedProcesses == null ? List.of() : List.copyOf(expectedProcesses);
        List<ReasoningGraphProcessGenerator.Candidate> uniqueCandidates = stableUniqueCandidates(candidates);
        EvidenceContext evidenceContext = EvidenceContext.from(graph);
        if (expected.isEmpty()) {
            return new ProcessEvaluationResult(List.of(), ProcessHitRate.of(0, 0));
        }

        ProcessCandidateEvaluation[][] scored = new ProcessCandidateEvaluation[expected.size()][uniqueCandidates.size()];
        for (int i = 0; i < expected.size(); i++) {
            for (int j = 0; j < uniqueCandidates.size(); j++) {
                scored[i][j] = score(expected.get(i), uniqueCandidates.get(j), evidenceContext);
            }
        }

        int[] assignment = maximumAssignment(scored, expected.size(), uniqueCandidates.size());
        List<ProcessCandidateEvaluation> evaluations = new ArrayList<>(expected.size());
        int hits = 0;
        for (int i = 0; i < expected.size(); i++) {
            ProcessCandidateEvaluation evaluation = assignment[i] < uniqueCandidates.size()
                    ? scored[i][assignment[i]] : unassigned(expected.get(i), evidenceContext);
            evaluations.add(evaluation);
            if (evaluation.disposition() == Disposition.FULL) {
                hits++;
            }
        }
        return new ProcessEvaluationResult(evaluations, ProcessHitRate.of(hits, expected.size()));
    }

    private ProcessCandidateEvaluation score(
            ExpectedProcessCase expected,
            ReasoningGraphProcessGenerator.Candidate candidate,
            EvidenceContext evidenceContext) {
        List<String> corpus = corpus(candidate, evidenceContext);
        Set<String> matchedIds = new LinkedHashSet<>();
        List<String> matchedConcepts = new ArrayList<>();
        List<String> missingConcepts = new ArrayList<>();
        for (RequiredConcept concept : expected.requiredConcepts()) {
            if (matches(corpus, concept)) {
                matchedIds.add(concept.id());
                matchedConcepts.add(concept.id());
            } else {
                missingConcepts.add(concept.id());
            }
        }
        double conceptRecall = ratio(matchedConcepts.size(), expected.requiredConcepts().size());
        ConceptCoverage graphCoverage = graphConceptCoverage(expected, evidenceContext);
        EvidenceResolution evidenceResolution = evidenceResolution(candidate, evidenceContext);

        List<String> matchedOrders = new ArrayList<>();
        List<String> missingOrders = new ArrayList<>();
        for (PrecedenceConstraint order : expected.precedenceConstraints()) {
            if (matchesOrder(candidate, expected, order)) {
                matchedOrders.add(order.toString());
            } else {
                missingOrders.add(order.toString());
            }
        }
        double orderRecall = ratio(matchedOrders.size(), expected.precedenceConstraints().size());
        double traceCoverage = traceCoverage(candidate, expected.requiredConcepts());
        int traceCount = candidate.eventLog() == null ? 0 : candidate.eventLog().size();
        boolean traceCountSufficient = traceCount >= expected.minimumTraceCount();

        int activityCount = candidate.eventLog() == null ? 0 : candidate.eventLog().activityNames().size();
        double scopeLimit = Math.max(2.0, expected.requiredConcepts().size() * 1.75);
        double scopePenalty = activityCount <= scopeLimit || activityCount == 0
                ? 0.0 : (activityCount - scopeLimit) / activityCount;
        double candidateScore = clamp(candidate.score());
        double evaluationScore;
        if (expected.requiredConcepts().isEmpty() && expected.precedenceConstraints().isEmpty()) {
            evaluationScore = 1.0;
        } else if (expected.precedenceConstraints().isEmpty()) {
            evaluationScore = clamp(0.70 * conceptRecall + 0.20 * traceCoverage
                    + 0.10 * candidateScore - 0.20 * scopePenalty);
        } else {
            evaluationScore = clamp(0.55 * conceptRecall + 0.20 * orderRecall
                    + 0.15 * traceCoverage + 0.10 * candidateScore - 0.20 * scopePenalty);
        }

        boolean semanticSupport = !matchedConcepts.isEmpty() || !matchedOrders.isEmpty()
                || (!expected.requiredConcepts().isEmpty() && traceCoverage > 0.0);
        Disposition disposition = evaluationScore + EPSILON >= expected.hitThreshold()
                && traceCountSufficient
                ? Disposition.FULL : semanticSupport ? Disposition.PARTIAL : Disposition.MISSED;
        return new ProcessCandidateEvaluation(expected, candidate, conceptRecall,
                evidenceContext.available(), graphCoverage.recall(), evidenceResolution, orderRecall,
                scopePenalty, traceCoverage, traceCount, traceCountSufficient,
                evaluationScore, matchedConcepts, missingConcepts,
                graphCoverage.matchedConcepts(), graphCoverage.missingConcepts(),
                matchedOrders, missingOrders, disposition);
    }

    private static List<String> corpus(ReasoningGraphProcessGenerator.Candidate candidate,
                                       EvidenceContext evidenceContext) {
        List<String> texts = new ArrayList<>();
        add(texts, candidate.id());
        add(texts, candidate.family());
        if (candidate.eventLog() != null) {
            candidate.eventLog().activityNames().forEach(value -> add(texts, value));
            candidate.eventLog().variants().keySet().forEach(variant -> variant.forEach(value -> add(texts, value)));
        }
        ProcessSuggestion suggestion = candidate.suggestion();
        if (suggestion != null) {
            add(texts, suggestion.getName());
            add(texts, suggestion.getDescription());
            add(texts, suggestion.getNarrative());
            add(texts, suggestion.getProcessDocument());
            if (suggestion.getEvidence() != null) {
                suggestion.getEvidence().forEach(value -> add(texts, value));
            }
        }
        if (candidate.evidenceEntityIds() != null) {
            for (String entityId : candidate.evidenceEntityIds()) {
                add(texts, entityId);
                evidenceContext.entity(entityId).ifPresent(entity -> addEntityEvidence(texts, entity));
            }
        }
        if (candidate.evidenceRelationIds() != null) {
            for (String relationId : candidate.evidenceRelationIds()) {
                add(texts, relationId);
                GraphRelation relation = evidenceContext.relation(relationId);
                if (relation != null) {
                    addRelationEvidence(texts, relation, evidenceContext);
                }
            }
        }
        return List.copyOf(texts);
    }

    private static void addEntityEvidence(List<String> texts, GraphEntity entity) {
        add(texts, entity.id());
        add(texts, entity.label());
        add(texts, entity.type());
        entity.typeMemberships().forEach(value -> add(texts, value));
        entity.tags().forEach(value -> add(texts, value));
        add(texts, String.valueOf(entity.attributes()));
    }

    private static void addRelationEvidence(List<String> texts,
                                            GraphRelation relation,
                                            EvidenceContext evidenceContext) {
        add(texts, relation.id());
        add(texts, relation.type());
        relation.tags().forEach(value -> add(texts, value));
        add(texts, String.valueOf(relation.attributes()));

        GraphEntity source = evidenceContext.entity(relation.sourceId()).orElse(null);
        GraphEntity target = evidenceContext.entity(relation.targetId()).orElse(null);
        String sourceLabel = source == null ? relation.sourceId() : source.label();
        String targetLabel = target == null ? relation.targetId() : target.label();
        add(texts, sourceLabel);
        add(texts, targetLabel);
        add(texts, sourceLabel + " " + relation.type() + " " + targetLabel);
        if (source != null) {
            addEntityEvidence(texts, source);
        }
        if (target != null) {
            addEntityEvidence(texts, target);
        }
    }

    private static EvidenceResolution evidenceResolution(
            ReasoningGraphProcessGenerator.Candidate candidate,
            EvidenceContext evidenceContext) {
        List<String> entityIds = candidate.evidenceEntityIds() == null
                ? List.of() : candidate.evidenceEntityIds();
        List<String> relationIds = candidate.evidenceRelationIds() == null
                ? List.of() : candidate.evidenceRelationIds();
        int resolvedEntities = 0;
        List<String> unresolvedEntities = new ArrayList<>();
        for (String entityId : entityIds) {
            if (evidenceContext.entity(entityId).isPresent()) {
                resolvedEntities++;
            } else {
                unresolvedEntities.add(entityId);
            }
        }
        int resolvedRelations = 0;
        List<String> unresolvedRelations = new ArrayList<>();
        for (String relationId : relationIds) {
            if (evidenceContext.relation(relationId) != null) {
                resolvedRelations++;
            } else {
                unresolvedRelations.add(relationId);
            }
        }
        return new EvidenceResolution(entityIds.size(), resolvedEntities, unresolvedEntities,
                relationIds.size(), resolvedRelations, unresolvedRelations);
    }

    private static void add(List<String> texts, String value) {
        if (value != null && !value.isBlank()) {
            texts.add(normalize(value));
        }
    }

    private static boolean matches(List<String> normalizedTexts, RequiredConcept concept) {
        for (String alias : concept.aliases()) {
            Set<String> expectedTokens = tokens(alias);
            if (!expectedTokens.isEmpty()) {
                for (String text : normalizedTexts) {
                    if (tokens(text).containsAll(expectedTokens)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matchesOrder(ReasoningGraphProcessGenerator.Candidate candidate,
                                        ExpectedProcessCase expected,
                                        PrecedenceConstraint order) {
        if (candidate.eventLog() == null) {
            return false;
        }
        RequiredConcept before = concept(expected, order.beforeConceptId());
        RequiredConcept after = concept(expected, order.afterConceptId());
        for (Trace trace : candidate.eventLog().traces()) {
            int beforeIndex = firstMatch(trace.activitySequence(), before, 0);
            if (beforeIndex >= 0 && firstMatch(trace.activitySequence(), after, beforeIndex + 1) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int firstMatch(List<String> activities, RequiredConcept concept, int start) {
        for (int i = start; i < activities.size(); i++) {
            if (matches(List.of(normalize(activities.get(i))), concept)) {
                return i;
            }
        }
        return -1;
    }

    private static double traceCoverage(ReasoningGraphProcessGenerator.Candidate candidate,
                                        List<RequiredConcept> concepts) {
        if (concepts.isEmpty()) {
            return 1.0;
        }
        if (candidate.eventLog() == null || candidate.eventLog().isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (Trace trace : candidate.eventLog().traces()) {
            List<String> texts = trace.activitySequence().stream().map(ProcessQualityEvaluator::normalize).toList();
            long matched = concepts.stream().filter(concept -> matches(texts, concept)).count();
            best = Math.max(best, ratio((int) matched, concepts.size()));
        }
        return best;
    }

    private static RequiredConcept concept(ExpectedProcessCase expected, String id) {
        return expected.requiredConcepts().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    private static ProcessCandidateEvaluation unassigned(ExpectedProcessCase expected,
                                                         EvidenceContext evidenceContext) {
        List<String> missingConcepts = expected.requiredConcepts().stream().map(RequiredConcept::id).toList();
        List<String> missingOrders = expected.precedenceConstraints().stream().map(Object::toString).toList();
        ConceptCoverage graphCoverage = graphConceptCoverage(expected, evidenceContext);
        return new ProcessCandidateEvaluation(expected, null,
                ratio(0, expected.requiredConcepts().size()),
                evidenceContext.available(), graphCoverage.recall(), EvidenceResolution.empty(),
                ratio(0, expected.precedenceConstraints().size()), 0.0,
                expected.requiredConcepts().isEmpty() ? 1.0 : 0.0,
                0, expected.minimumTraceCount() == 0, 0.0,
                List.of(), missingConcepts,
                graphCoverage.matchedConcepts(), graphCoverage.missingConcepts(),
                List.of(), missingOrders, Disposition.UNASSIGNED);
    }

    private static ConceptCoverage graphConceptCoverage(ExpectedProcessCase expected,
                                                        EvidenceContext evidenceContext) {
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (evidenceContext.available()) {
            for (RequiredConcept concept : expected.requiredConcepts()) {
                (matches(evidenceContext.graphCorpus(), concept) ? matched : missing).add(concept.id());
            }
        } else {
            expected.requiredConcepts().stream().map(RequiredConcept::id).forEach(missing::add);
        }
        return new ConceptCoverage(
                ratio(matched.size(), expected.requiredConcepts().size()),
                List.copyOf(matched), List.copyOf(missing));
    }

    private static List<ReasoningGraphProcessGenerator.Candidate> stableUniqueCandidates(
            List<ReasoningGraphProcessGenerator.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ReasoningGraphProcessGenerator.Candidate> sorted = candidates.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(ReasoningGraphProcessGenerator.Candidate::rank)
                        .thenComparing(candidate -> candidate.id() == null ? "" : candidate.id()))
                .toList();
        Map<String, ReasoningGraphProcessGenerator.Candidate> unique = new LinkedHashMap<>();
        int anonymous = 0;
        for (ReasoningGraphProcessGenerator.Candidate candidate : sorted) {
            String key = candidate.id() == null || candidate.id().isBlank()
                    ? "#anonymous-" + anonymous++ : candidate.id();
            unique.putIfAbsent(key, candidate);
        }
        return List.copyOf(unique.values());
    }

    /** Hungarian minimum-cost assignment over candidates plus one zero-score dummy per expected row. */
    private static int[] maximumAssignment(ProcessCandidateEvaluation[][] scored, int rows, int candidates) {
        int columns = candidates + rows;
        double[] u = new double[rows + 1];
        double[] v = new double[columns + 1];
        int[] p = new int[columns + 1];
        int[] way = new int[columns + 1];
        for (int i = 1; i <= rows; i++) {
            p[0] = i;
            double[] min = new double[columns + 1];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[columns + 1];
            int j0 = 0;
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= columns; j++) {
                    if (!used[j]) {
                        double score = j <= candidates ? scored[i0 - 1][j - 1].score() : 0.0;
                        double current = (1.0 - score) - u[i0] - v[j];
                        if (current < min[j] - EPSILON) {
                            min[j] = current;
                            way[j] = j0;
                        }
                        if (min[j] < delta - EPSILON) {
                            delta = min[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= columns; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        min[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] assignment = new int[rows];
        Arrays.fill(assignment, candidates);
        for (int j = 1; j <= columns; j++) {
            if (p[j] != 0) {
                assignment[p[j] - 1] = j - 1;
            }
        }
        return assignment;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private static Set<String> tokens(String text) {
        String normalized = normalize(text);
        return normalized.isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace("&", " and ").replaceAll("[^a-z0-9]+", " ")
                .trim().replaceAll(" +", " ");
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record ConceptCoverage(double recall,
                                   List<String> matchedConcepts,
                                   List<String> missingConcepts) {
    }

    private record EvidenceContext(ReasoningGraph graph,
                                   Map<String, GraphRelation> relationsById,
                                   List<String> graphCorpus) {

        private static EvidenceContext from(ReasoningGraph graph) {
            if (graph == null) {
                return new EvidenceContext(null, Map.of(), List.of());
            }
            Map<String, GraphRelation> indexed = new LinkedHashMap<>();
            graph.relations().forEach(relation -> indexed.putIfAbsent(relation.id(), relation));
            EvidenceContext indexedContext = new EvidenceContext(graph, Map.copyOf(indexed), List.of());
            List<String> graphTexts = new ArrayList<>();
            graph.entities().forEach(entity -> addEntityEvidence(graphTexts, entity));
            graph.relations().forEach(relation -> addRelationEvidence(graphTexts, relation, indexedContext));
            return new EvidenceContext(graph, Map.copyOf(indexed), List.copyOf(graphTexts));
        }

        private boolean available() {
            return graph != null;
        }

        private java.util.Optional<GraphEntity> entity(String id) {
            return graph == null || id == null ? java.util.Optional.empty() : graph.entity(id);
        }

        private GraphRelation relation(String id) {
            return id == null ? null : relationsById.get(id);
        }
    }
}
