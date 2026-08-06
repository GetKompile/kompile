/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

import java.util.List;

/** Complete evaluation in expected-process input order. */
public record ProcessEvaluationResult(
        List<ProcessCandidateEvaluation> evaluations,
        ProcessHitRate hitRate) {

    public ProcessEvaluationResult {
        evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    /** Aggregate process, concept-flow, and evidence-resolution metrics for reporting. */
    public Summary summary() {
        int full = 0;
        int partial = 0;
        int missed = 0;
        int unassigned = 0;
        int graphContextEvaluations = 0;
        int requiredConcepts = 0;
        int graphMatchedConcepts = 0;
        int candidateMatchedConcepts = 0;
        int entityReferences = 0;
        int resolvedEntityReferences = 0;
        int relationReferences = 0;
        int resolvedRelationReferences = 0;
        for (ProcessCandidateEvaluation evaluation : evaluations) {
            switch (evaluation.disposition()) {
                case FULL -> full++;
                case PARTIAL -> partial++;
                case MISSED -> missed++;
                case UNASSIGNED -> unassigned++;
            }
            if (evaluation.graphContextAvailable()) {
                graphContextEvaluations++;
            }
            requiredConcepts += evaluation.expectedProcess().requiredConcepts().size();
            graphMatchedConcepts += evaluation.graphMatchedConcepts().size();
            candidateMatchedConcepts += evaluation.matchedConcepts().size();
            ProcessCandidateEvaluation.EvidenceResolution evidence = evaluation.evidenceResolution();
            entityReferences += evidence.entityReferences();
            resolvedEntityReferences += evidence.resolvedEntityReferences();
            relationReferences += evidence.relationReferences();
            resolvedRelationReferences += evidence.resolvedRelationReferences();
        }
        return new Summary(evaluations.size(), full, partial, missed, unassigned,
                graphContextEvaluations, requiredConcepts, graphMatchedConcepts,
                candidateMatchedConcepts, ratio(graphMatchedConcepts, requiredConcepts),
                ratio(candidateMatchedConcepts, requiredConcepts),
                entityReferences, resolvedEntityReferences,
                entityReferences - resolvedEntityReferences,
                ratio(resolvedEntityReferences, entityReferences),
                relationReferences, resolvedRelationReferences,
                relationReferences - resolvedRelationReferences,
                ratio(resolvedRelationReferences, relationReferences));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    public record Summary(
            int expectedProcesses,
            int fullProcesses,
            int partialProcesses,
            int missedProcesses,
            int unassignedProcesses,
            int graphContextEvaluations,
            int requiredConceptOccurrences,
            int graphMatchedConceptOccurrences,
            int candidateMatchedConceptOccurrences,
            double graphConceptRecall,
            double candidateConceptRecall,
            int evidenceEntityReferences,
            int resolvedEvidenceEntityReferences,
            int unresolvedEvidenceEntityReferences,
            double evidenceEntityResolutionRate,
            int evidenceRelationReferences,
            int resolvedEvidenceRelationReferences,
            int unresolvedEvidenceRelationReferences,
            double evidenceRelationResolutionRate) {
    }
}
