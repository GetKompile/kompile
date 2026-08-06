/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.evaluation.ExpectedProcessCase.PrecedenceConstraint;
import ai.kompile.process.discovery.evaluation.ExpectedProcessCase.RequiredConcept;
import ai.kompile.process.discovery.evaluation.ProcessCandidateEvaluation.Disposition;
import ai.kompile.process.discovery.mining.ReasoningGraphProcessGenerator;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessQualityEvaluatorTest {

    private final ProcessQualityEvaluator evaluator = new ProcessQualityEvaluator();

    @Test
    void evaluatesMultipleExpectedProcessesAndTextualAliases() {
        ExpectedProcessCase intake = expected("intake", 0.70,
                List.of(concept("receive", "receive request", "intake")), List.of());
        ExpectedProcessCase fulfilment = expected("fulfil", 0.70,
                List.of(concept("dispatch", "dispatch item", "ship")), List.of());

        ProcessEvaluationResult result = evaluator.evaluate(List.of(intake, fulfilment), List.of(
                candidate("c2", 2, "Operations", "Shipping workflow", List.of(List.of("ship item")), 0.8),
                candidate("c1", 1, "Requests", "Intake request", List.of(List.of("receive request")), 0.8)));

        assertThat(result.evaluations()).extracting(ProcessCandidateEvaluation::candidateId)
                .containsExactly("c1", "c2");
        assertThat(result.hitRate()).isEqualTo(ProcessHitRate.of(2, 2));
    }

    @Test
    void duplicateCandidateIdsAreOneLogicalCandidate() {
        ExpectedProcessCase first = expected("first", 0.5,
                List.of(concept("review", "review")), List.of());
        ExpectedProcessCase second = expected("second", 0.5,
                List.of(concept("review", "review")), List.of());
        ReasoningGraphProcessGenerator.Candidate duplicate =
                candidate("same", 1, "review", "review", List.of(List.of("review")), 1.0);

        ProcessEvaluationResult result = evaluator.evaluate(List.of(first, second), List.of(duplicate, duplicate));

        assertThat(result.evaluations()).extracting(ProcessCandidateEvaluation::disposition)
                .containsExactly(Disposition.FULL, Disposition.UNASSIGNED);
        assertThat(result.evaluations()).extracting(ProcessCandidateEvaluation::candidateId)
                .containsExactly("same", null);
    }

    @Test
    void reportsPartialConceptAndOrderRecall() {
        ExpectedProcessCase expected = expected("case", 0.95,
                List.of(concept("start", "start"), concept("check", "check"), concept("finish", "finish")),
                List.of(new PrecedenceConstraint("start", "check"),
                        new PrecedenceConstraint("check", "finish")));
        ReasoningGraphProcessGenerator.Candidate candidate = candidate("partial", 1, "", "",
                List.of(List.of("start", "check")), 0.0);

        ProcessCandidateEvaluation evaluation = evaluator.evaluate(List.of(expected), List.of(candidate))
                .evaluations().get(0);

        assertThat(evaluation.conceptRecall()).isEqualTo(2.0 / 3.0);
        assertThat(evaluation.orderRecall()).isEqualTo(0.5);
        assertThat(evaluation.matchedConcepts()).containsExactly("start", "check");
        assertThat(evaluation.missingConcepts()).containsExactly("finish");
        assertThat(evaluation.matchedOrders()).containsExactly("start->check");
        assertThat(evaluation.missingOrders()).containsExactly("check->finish");
        assertThat(evaluation.disposition()).isEqualTo(Disposition.PARTIAL);
    }

    @Test
    void traceCoverageRequiresConceptsToCoOccurInOneTrace() {
        ExpectedProcessCase expected = expected("case", 0.99,
                List.of(concept("open", "open"), concept("close", "close")), List.of());
        ReasoningGraphProcessGenerator.Candidate candidate = candidate("split", 1, "", "",
                List.of(List.of("open"), List.of("close")), 0.0);

        ProcessCandidateEvaluation evaluation = evaluator.evaluate(List.of(expected), List.of(candidate))
                .evaluations().get(0);

        assertThat(evaluation.conceptRecall()).isEqualTo(1.0);
        assertThat(evaluation.traceCoverage()).isEqualTo(0.5);
        assertThat(evaluation.disposition()).isEqualTo(Disposition.PARTIAL);
    }

    @Test
    void minimumTraceCountPreventsOtherwiseHighScoringCandidateFromCountingAsHit() {
        ExpectedProcessCase expected = new ExpectedProcessCase(
                "close", "close process",
                List.of(concept("load", "load"), concept("publish", "publish")),
                List.of(), 2, 0.60);
        ReasoningGraphProcessGenerator.Candidate candidate = candidate(
                "one-trace", 1, "close", "load publish",
                List.of(List.of("load", "publish")), 1.0);

        ProcessCandidateEvaluation evaluation = evaluator.evaluate(List.of(expected), List.of(candidate))
                .evaluations().get(0);

        assertThat(evaluation.score()).isGreaterThanOrEqualTo(expected.hitThreshold());
        assertThat(evaluation.traceCount()).isEqualTo(1);
        assertThat(evaluation.traceCountSufficient()).isFalse();
        assertThat(evaluation.disposition()).isEqualTo(Disposition.PARTIAL);
    }

    @Test
    void computesGlobalMaximumOneToOneAssignment() {
        ExpectedProcessCase broad = expected("broad", 0.99,
                List.of(concept("x", "x"), concept("y", "y")), List.of());
        ExpectedProcessCase narrow = expected("narrow", 0.5,
                List.of(concept("x", "x")), List.of());
        ReasoningGraphProcessGenerator.Candidate x = candidate("x-candidate", 1, "", "", List.of(List.of("x")), 0.0);
        ReasoningGraphProcessGenerator.Candidate y = candidate("y-candidate", 2, "", "", List.of(List.of("y")), 0.0);

        ProcessEvaluationResult result = evaluator.evaluate(List.of(broad, narrow), List.of(x, y));

        assertThat(result.evaluations()).extracting(ProcessCandidateEvaluation::candidateId)
                .containsExactly("y-candidate", "x-candidate");
    }

    @Test
    void resolvesOpaqueCandidateEvidenceThroughTheProductionReasoningGraph() {
        ExpectedProcessCase expected = expected("governance", 0.65,
                List.of(concept("ontology", "semantic ontology"),
                        concept("review", "compliance review")),
                List.of());
        ReasoningGraphProcessGenerator.Candidate candidate = candidate(
                "opaque-candidate", 1, "", "", List.of(), 0.0,
                List.of("node-a"), List.of("edge-a"));
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity(GraphEntity.builder("node-a")
                        .type("OntologyAsset")
                        .label("Semantic Ontology")
                        .tag("governance")
                        .build())
                .addEntity(GraphEntity.builder("node-b")
                        .type("ControlActivity")
                        .label("Compliance Review")
                        .build())
                .addRelation(GraphRelation.builder("edge-a", "node-a", "node-b")
                        .type("REQUIRES")
                        .build());

        ProcessCandidateEvaluation withoutGraph = evaluator.evaluate(
                List.of(expected), List.of(candidate)).evaluations().get(0);
        ProcessEvaluationResult withGraphResult = evaluator.evaluate(
                List.of(expected), List.of(candidate), graph);
        ProcessCandidateEvaluation withGraph = withGraphResult.evaluations().get(0);

        assertThat(withoutGraph.conceptRecall()).isZero();
        assertThat(withoutGraph.graphContextAvailable()).isFalse();
        assertThat(withoutGraph.evidenceResolution().resolvedEntityReferences()).isZero();
        assertThat(withoutGraph.evidenceResolution().unresolvedEntityIds()).containsExactly("node-a");
        assertThat(withoutGraph.evidenceResolution().unresolvedRelationIds()).containsExactly("edge-a");
        assertThat(withoutGraph.disposition()).isEqualTo(Disposition.MISSED);
        assertThat(withGraph.conceptRecall()).isEqualTo(1.0);
        assertThat(withGraph.graphContextAvailable()).isTrue();
        assertThat(withGraph.graphConceptRecall()).isEqualTo(1.0);
        assertThat(withGraph.graphMatchedConcepts()).containsExactly("ontology", "review");
        assertThat(withGraph.evidenceResolution().entityResolutionRate()).isEqualTo(1.0);
        assertThat(withGraph.evidenceResolution().relationResolutionRate()).isEqualTo(1.0);
        assertThat(withGraph.evidenceResolution().unresolvedEntityIds()).isEmpty();
        assertThat(withGraph.evidenceResolution().unresolvedRelationIds()).isEmpty();
        assertThat(withGraph.matchedConcepts()).containsExactly("ontology", "review");
        assertThat(withGraph.disposition()).isEqualTo(Disposition.FULL);
        assertThat(withGraphResult.summary()).isEqualTo(new ProcessEvaluationResult.Summary(
                1, 1, 0, 0, 0, 1,
                2, 2, 2, 1.0, 1.0,
                1, 1, 0, 1.0,
                1, 1, 0, 1.0));
    }

    @Test
    void distinguishesGraphCoverageFromCandidateSynthesisCoverage() {
        ExpectedProcessCase expected = expected("governance", 0.65,
                List.of(concept("ontology", "semantic ontology")), List.of());
        ReasoningGraphProcessGenerator.Candidate candidate = candidate(
                "candidate-without-evidence", 1, "", "", List.of(), 0.0);
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity(GraphEntity.builder("node-a")
                        .type("OntologyAsset")
                        .label("Semantic Ontology")
                        .build());

        ProcessCandidateEvaluation evaluation = evaluator.evaluate(
                List.of(expected), List.of(candidate), graph).evaluations().get(0);

        assertThat(evaluation.graphConceptRecall()).isEqualTo(1.0);
        assertThat(evaluation.graphMatchedConcepts()).containsExactly("ontology");
        assertThat(evaluation.conceptRecall()).isZero();
        assertThat(evaluation.missingConcepts()).containsExactly("ontology");
        assertThat(evaluation.disposition()).isEqualTo(Disposition.MISSED);
    }

    @Test
    void definesZeroDenominatorsAndNoCandidateCases() {
        ProcessEvaluationResult empty = evaluator.evaluate(List.of(), List.of());
        assertThat(empty.hitRate()).isEqualTo(ProcessHitRate.of(0, 0));
        assertThat(empty.hitRate().rate()).isEqualTo(1.0);

        ExpectedProcessCase noRequirements = expected("none", 0.8, List.of(), List.of());
        ProcessCandidateEvaluation evaluated = evaluator.evaluate(List.of(noRequirements),
                List.of(candidate("empty", 1, "", "", List.of(), 0.0))).evaluations().get(0);
        assertThat(evaluated.conceptRecall()).isEqualTo(1.0);
        assertThat(evaluated.orderRecall()).isEqualTo(1.0);
        assertThat(evaluated.traceCoverage()).isEqualTo(1.0);
        assertThat(evaluated.disposition()).isEqualTo(Disposition.FULL);

        ProcessEvaluationResult noCandidates = evaluator.evaluate(List.of(noRequirements), List.of());
        assertThat(noCandidates.hitRate()).isEqualTo(ProcessHitRate.of(0, 1));
        assertThat(noCandidates.evaluations().get(0).disposition()).isEqualTo(Disposition.UNASSIGNED);
        assertThat(noCandidates.evaluations().get(0).conceptRecall()).isEqualTo(1.0);
    }

    private static ExpectedProcessCase expected(String id, double threshold,
                                                 List<RequiredConcept> concepts,
                                                 List<PrecedenceConstraint> orders) {
        return new ExpectedProcessCase(id, id + " process", concepts, orders, threshold);
    }

    private static RequiredConcept concept(String id, String... aliases) {
        return new RequiredConcept(id, id, Set.of(aliases));
    }

    private static ReasoningGraphProcessGenerator.Candidate candidate(
            String id, int rank, String family, String suggestionName,
            List<List<String>> variants, double score) {
        return candidate(id, rank, family, suggestionName, variants, score, List.of(), List.of());
    }

    private static ReasoningGraphProcessGenerator.Candidate candidate(
            String id, int rank, String family, String suggestionName,
            List<List<String>> variants, double score,
            List<String> evidenceEntityIds, List<String> evidenceRelationIds) {
        List<Trace> traces = new ArrayList<>();
        int caseNumber = 0;
        for (List<String> variant : variants) {
            String caseId = "case-" + caseNumber++;
            List<Event> events = variant.stream()
                    .map(activity -> new Event(caseId, activity, null, null, Map.of()))
                    .toList();
            traces.add(new Trace(caseId, events));
        }
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .id(id).name(suggestionName).description(suggestionName).build();
        return new ReasoningGraphProcessGenerator.Candidate(id, rank, null, family, score,
                new EventLog(traces), null, null, null, null, suggestion, null,
                evidenceEntityIds, evidenceRelationIds);
    }
}
