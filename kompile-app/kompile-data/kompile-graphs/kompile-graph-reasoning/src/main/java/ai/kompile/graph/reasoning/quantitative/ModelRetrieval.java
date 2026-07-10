/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ranked model candidates, the selected dependency closure, and all retrieval diagnostics. */
public record ModelRetrieval(
        Status status,
        QuantitativeQuery query,
        List<ModelMatch> candidates,
        Plan plan,
        List<Gap> gaps,
        Map<String, String> resolutions,
        ReasoningTrace trace) {

    public ModelRetrieval {
        status = status == null ? Status.INVALID : status;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        resolutions = resolutions == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(resolutions));
    }

    public boolean executable() {
        return plan != null && (status == Status.READY || status == Status.PARTIAL);
    }

    public enum Status {
        READY,
        PARTIAL,
        AMBIGUOUS,
        NOT_FOUND,
        INVALID
    }

    public record ScoreBreakdown(
            double outputMatch,
            double dimensionMatch,
            double unitMatch,
            double temporalMatch,
            double provenance,
            double hybrid,
            double total) {
    }

    public record ModelMatch(
            QuantitativeRule rule,
            String outputLabel,
            ScoreBreakdown scores,
            boolean compatible,
            List<String> rejectionReasons) {

        public ModelMatch {
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        }
    }

    /**
     * Rules are ordered dependency-first. Leaf entity ids must be supplied by graph observations or
     * resolved interventions. {@code goalControlCandidateIds} lists every plan-reachable control
     * instance when the goal selector resolves to a family (for example one units cell per
     * product); {@code goalControlEntityId} stays the primary control for single-control callers.
     */
    public record Plan(
            String targetEntityId,
            List<QuantitativeRule> rules,
            List<String> leafEntityIds,
            List<ResolvedIntervention> interventions,
            String goalControlEntityId,
            List<String> goalControlCandidateIds) {

        public Plan {
            rules = rules == null ? List.of() : List.copyOf(rules);
            leafEntityIds = leafEntityIds == null ? List.of() : List.copyOf(leafEntityIds);
            interventions = interventions == null ? List.of() : List.copyOf(interventions);
            goalControlCandidateIds = goalControlCandidateIds == null
                    ? List.of() : List.copyOf(goalControlCandidateIds);
        }

        public Plan(
                String targetEntityId,
                List<QuantitativeRule> rules,
                List<String> leafEntityIds,
                List<ResolvedIntervention> interventions,
                String goalControlEntityId) {
            this(targetEntityId, rules, leafEntityIds, interventions, goalControlEntityId,
                    goalControlEntityId == null ? List.of() : List.of(goalControlEntityId));
        }
    }

    public record ResolvedIntervention(
            String entityId,
            QuantitativeQuery.Operation operation,
            double value,
            String requestedTarget) {
    }

    public record Gap(
            GapCode code,
            String entityId,
            String message,
            Map<String, String> details) {

        public Gap {
            details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
        }
    }

    public enum GapCode {
        MISSING_TARGET,
        NO_PRODUCING_RULE,
        MISSING_INPUT_VALUE,
        AMBIGUOUS_TARGET,
        AMBIGUOUS_RULE,
        UNRESOLVED_INTERVENTION,
        AMBIGUOUS_INTERVENTION,
        UNRESOLVED_GOAL_CONTROL,
        GOAL_CONTROL_NOT_IN_PLAN,
        INTERVENTION_DISCONNECTED,
        CYCLE,
        INCOMPATIBLE_UNIT,
        INCOMPATIBLE_DIMENSION,
        OUTSIDE_VALID_TIME,
        UNSUPPORTED_EXPRESSION
    }
}
