/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.process.discovery.evaluation;

import ai.kompile.process.discovery.mining.ReasoningGraphProcessGenerator;

import java.util.List;

/** Detailed score for one expected process and its uniquely assigned candidate. */
public record ProcessCandidateEvaluation(
        ExpectedProcessCase expectedProcess,
        ReasoningGraphProcessGenerator.Candidate candidate,
        double conceptRecall,
        boolean graphContextAvailable,
        double graphConceptRecall,
        EvidenceResolution evidenceResolution,
        double orderRecall,
        double scopePenalty,
        double traceCoverage,
        int traceCount,
        boolean traceCountSufficient,
        double score,
        List<String> matchedConcepts,
        List<String> missingConcepts,
        List<String> graphMatchedConcepts,
        List<String> graphMissingConcepts,
        List<String> matchedOrders,
        List<String> missingOrders,
        Disposition disposition) {

    public ProcessCandidateEvaluation {
        evidenceResolution = evidenceResolution == null ? EvidenceResolution.empty() : evidenceResolution;
        matchedConcepts = List.copyOf(matchedConcepts);
        missingConcepts = List.copyOf(missingConcepts);
        graphMatchedConcepts = List.copyOf(graphMatchedConcepts);
        graphMissingConcepts = List.copyOf(graphMissingConcepts);
        matchedOrders = List.copyOf(matchedOrders);
        missingOrders = List.copyOf(missingOrders);
    }

    public String candidateId() {
        return candidate == null ? null : candidate.id();
    }

    /** Resolution health for the graph evidence references carried by the assigned candidate. */
    public record EvidenceResolution(
            int entityReferences,
            int resolvedEntityReferences,
            List<String> unresolvedEntityIds,
            int relationReferences,
            int resolvedRelationReferences,
            List<String> unresolvedRelationIds) {

        public EvidenceResolution {
            unresolvedEntityIds = unresolvedEntityIds == null ? List.of() : List.copyOf(unresolvedEntityIds);
            unresolvedRelationIds = unresolvedRelationIds == null ? List.of() : List.copyOf(unresolvedRelationIds);
        }

        public static EvidenceResolution empty() {
            return new EvidenceResolution(0, 0, List.of(), 0, 0, List.of());
        }

        public double entityResolutionRate() {
            return entityReferences == 0 ? 1.0 : (double) resolvedEntityReferences / entityReferences;
        }

        public double relationResolutionRate() {
            return relationReferences == 0 ? 1.0 : (double) resolvedRelationReferences / relationReferences;
        }
    }

    /** FULL is a threshold hit; PARTIAL has some support but is below threshold. */
    public enum Disposition {
        FULL,
        PARTIAL,
        MISSED,
        UNASSIGNED
    }
}
