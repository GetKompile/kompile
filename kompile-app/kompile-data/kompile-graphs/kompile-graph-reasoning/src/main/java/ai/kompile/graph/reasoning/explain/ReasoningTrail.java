/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Unified reasoning trail: a single model that any reasoning mode can populate.
 *
 * PROOF:
 *   derivationTree — present when grounding path ran (nullable)
 *   entailments    — present for PSL/MEBN paths (nullable)
 *   evidence       — human-readable evidence strings
 *   activatedRules — rule names / PSL rule bodies that fired
 *
 * Per reasoning-trail-explainability-design.md §3.1.
 */
public record ReasoningTrail(
    // WHAT was explained
    String targetId,
    String question,

    // VERDICT
    double confidence,
    ConfidenceBreakdown breakdown,

    // PROOF
    DerivationTree derivationTree,
    List<EntailmentRecord> entailments,
    List<String> evidence,
    List<String> activatedRules,

    // PROVENANCE
    String inferenceMode,
    String runId,
    Instant computedAt,

    // NL
    String naturalLanguageSummary
) {
    public ReasoningTrail {
        Objects.requireNonNull(targetId, "targetId");
        if (inferenceMode == null) inferenceMode = "UNKNOWN";
        if (runId == null) runId = "";
        if (computedAt == null) computedAt = Instant.now();
        if (breakdown == null) breakdown = ConfidenceBreakdown.empty();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        activatedRules = activatedRules == null ? List.of() : List.copyOf(activatedRules);
        entailments = entailments == null ? List.of() : List.copyOf(entailments);
        if (naturalLanguageSummary == null) naturalLanguageSummary = "";
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    public static Builder builder(String targetId) {
        return new Builder(targetId);
    }

    public static class Builder {
        private final String targetId;
        private String question = "";
        private double confidence = 0.0;
        private ConfidenceBreakdown breakdown = ConfidenceBreakdown.empty();
        private DerivationTree derivationTree;
        private List<EntailmentRecord> entailments = List.of();
        private List<String> evidence = List.of();
        private List<String> activatedRules = List.of();
        private String inferenceMode = "UNKNOWN";
        private String runId = "";
        private Instant computedAt = Instant.now();
        private String naturalLanguageSummary = "";

        private Builder(String targetId) {
            this.targetId = Objects.requireNonNull(targetId, "targetId");
        }

        public Builder question(String q) { this.question = q; return this; }
        public Builder confidence(double c) { this.confidence = c; return this; }
        public Builder breakdown(ConfidenceBreakdown b) { this.breakdown = b; return this; }
        public Builder derivationTree(DerivationTree dt) { this.derivationTree = dt; return this; }
        public Builder entailments(List<EntailmentRecord> e) { this.entailments = e; return this; }
        public Builder evidence(List<String> e) { this.evidence = e; return this; }
        public Builder activatedRules(List<String> r) { this.activatedRules = r; return this; }
        public Builder inferenceMode(String m) { this.inferenceMode = m; return this; }
        public Builder runId(String id) { this.runId = id; return this; }
        public Builder computedAt(Instant t) { this.computedAt = t; return this; }
        public Builder naturalLanguageSummary(String s) { this.naturalLanguageSummary = s; return this; }

        public ReasoningTrail build() {
            return new ReasoningTrail(targetId, question, confidence, breakdown,
                derivationTree, entailments, evidence, activatedRules,
                inferenceMode, runId, computedAt, naturalLanguageSummary);
        }
    }

    /** Convenience: does this trail have a DerivationTree? */
    public boolean hasDerivationTree() { return derivationTree != null; }

    /** Convenience: does this trail have entailment records? */
    public boolean hasEntailments() { return !entailments.isEmpty(); }
}
