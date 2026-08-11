/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.admission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact-rule policy that converts graph-selected evidence into an operational verdict.
 *
 * <p>The policy never interprets {@link AdmissionDecision} as an operational decision. For example,
 * a workbook node can correctly be {@link AdmissionDecision#REUSE reused} while an attached status
 * rule makes the workbook operationally unusable.</p>
 */
public final class GraphOperationalPolicy {

    private static final String NOT_EVALUATED_RULE = "graph.not-evaluated";
    private static final String DEGRADED_RULE = "graph.degraded";
    private static final String NO_RULE = "graph.no-operational-rule";

    private final Map<String, Rule> rules;

    private GraphOperationalPolicy(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Evaluate only machine-readable rule identifiers from the graph trace.
     *
     * <p>Missing, disabled, degraded, and unconfigured evidence fails closed to
     * {@link OperationalDisposition#REVIEW}.</p>
     */
    public GraphOperationalVerdict evaluate(String candidateId, GraphAdmissionResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.evaluated()) {
            return fallback(candidateId, NOT_EVALUATED_RULE,
                    "Graph evidence was not evaluated.");
        }
        if (result.degraded()) {
            return fallback(candidateId, DEGRADED_RULE,
                    "Graph evidence is degraded and requires review.");
        }

        List<Match> matches = new ArrayList<>();
        for (AdmissionEvidence evidence : result.evidenceTrace().items()) {
            Rule rule = rules.get(evidence.ruleId());
            if (rule != null) {
                matches.add(new Match(rule, evidence));
            }
        }
        matches.sort(MATCH_ORDER);
        if (matches.isEmpty()) {
            return fallback(candidateId, NO_RULE,
                    "No configured operational graph rule matched.");
        }

        Match decisive = matches.get(0);
        List<String> supportingRuleIds = matches.stream()
                .map(match -> match.rule().ruleId())
                .distinct()
                .toList();
        return new GraphOperationalVerdict(
                candidateId,
                decisive.rule().disposition(),
                decisive.rule().ruleId(),
                decisive.rule().statement(),
                true,
                Optional.of(decisive.evidence()),
                supportingRuleIds);
    }

    private static GraphOperationalVerdict fallback(
            String candidateId, String ruleId, String statement) {
        return new GraphOperationalVerdict(
                candidateId,
                OperationalDisposition.REVIEW,
                ruleId,
                statement,
                false,
                Optional.empty(),
                List.of());
    }

    private static int dispositionPrecedence(OperationalDisposition disposition) {
        return switch (disposition) {
            case DENY -> 3;
            case REVIEW -> 2;
            case ALLOW -> 1;
        };
    }

    private static final Comparator<Match> MATCH_ORDER = (left, right) -> {
        int comparison = Integer.compare(right.rule().priority(), left.rule().priority());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(
                dispositionPrecedence(right.rule().disposition()),
                dispositionPrecedence(left.rule().disposition()));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(right.evidence().strength(), left.evidence().strength());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.rule().ruleId().compareTo(right.rule().ruleId());
        if (comparison != 0) {
            return comparison;
        }
        comparison = String.join("\u0000", left.evidence().entityPath())
                .compareTo(String.join("\u0000", right.evidence().entityPath()));
        if (comparison != 0) {
            return comparison;
        }
        return String.join("\u0000", left.evidence().predicatePath())
                .compareTo(String.join("\u0000", right.evidence().predicatePath()));
    };

    private record Rule(
            String ruleId,
            OperationalDisposition disposition,
            int priority,
            String statement) {
    }

    private record Match(Rule rule, AdmissionEvidence evidence) {
    }

    public static final class Builder {
        private final Map<String, Rule> rules = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Bind an exact evidence rule identifier to an operational disposition.
         *
         * @param priority higher values win; equal priorities use DENY, REVIEW, ALLOW precedence
         */
        public Builder rule(
                String ruleId,
                OperationalDisposition disposition,
                int priority,
                String statement) {
            String normalizedRuleId = requireText(ruleId, "ruleId");
            Objects.requireNonNull(disposition, "disposition");
            if (priority < 0) {
                throw new IllegalArgumentException("priority must not be negative: " + priority);
            }
            String normalizedStatement = requireText(statement, "statement");
            Rule previous = rules.putIfAbsent(
                    normalizedRuleId,
                    new Rule(normalizedRuleId, disposition, priority, normalizedStatement));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate operational rule binding: " + normalizedRuleId);
            }
            return this;
        }

        public GraphOperationalPolicy build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException(
                        "at least one operational graph rule must be configured");
            }
            return new GraphOperationalPolicy(rules);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        }
    }
}
