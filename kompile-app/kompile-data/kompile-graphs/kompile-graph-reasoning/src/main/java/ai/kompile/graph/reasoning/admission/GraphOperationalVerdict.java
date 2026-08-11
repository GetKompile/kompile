/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.admission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic operational projection of a graph admission evidence trace.
 *
 * <p>The policy-authored statement is safe to present to a small model. Graph-authored summaries are
 * retained only in {@link #decisiveEvidence()} for audit and are never copied into model context.</p>
 */
public record GraphOperationalVerdict(
        String candidateId,
        OperationalDisposition disposition,
        String ruleId,
        String statement,
        boolean policyMatched,
        Optional<AdmissionEvidence> decisiveEvidence,
        List<String> supportingRuleIds) {

    public GraphOperationalVerdict {
        candidateId = requireSingleLine(candidateId, "candidateId");
        Objects.requireNonNull(disposition, "disposition");
        ruleId = requireSingleLine(ruleId, "ruleId");
        statement = requireSingleLine(statement, "statement");
        decisiveEvidence = Objects.requireNonNull(decisiveEvidence, "decisiveEvidence");
        supportingRuleIds = Objects.requireNonNull(supportingRuleIds, "supportingRuleIds").stream()
                .map(value -> requireSingleLine(value, "supportingRuleIds element"))
                .distinct()
                .toList();
        if (policyMatched != decisiveEvidence.isPresent()) {
            throw new IllegalArgumentException(
                    "policyMatched must agree with decisiveEvidence presence");
        }
        if (policyMatched && !supportingRuleIds.contains(ruleId)) {
            throw new IllegalArgumentException(
                    "a matched verdict must include its decisive rule in supportingRuleIds");
        }
    }

    /**
     * Minimal policy-authored context for a small model that only renders or routes the verdict.
     *
     * <p>Candidate identifiers, graph paths, and graph-authored summaries are deliberately omitted.
     * The caller can append its own presentation question or declared tool protocol.</p>
     */
    public String toModelContext() {
        return "Graph policy verdict: " + disposition + ".\nPolicy fact: " + statement;
    }

    /** Stable arguments for deterministic routing without asking a model to reconstruct graph state. */
    public Map<String, Object> toToolArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("candidate_id", candidateId);
        arguments.put("disposition", disposition.name());
        arguments.put("rule_id", ruleId);
        arguments.put("statement", statement);
        return Collections.unmodifiableMap(arguments);
    }

    private static String requireSingleLine(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
