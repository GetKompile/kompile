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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    String naturalLanguageSummary,

    // HUMAN-READABLE TRANSLATION MAP (atom key → display title for PSL grounding constants)
    Map<String, String> atomKeyToTitle,

    // RULE HUMANIZATION MAP (raw rule string → humanized label; used in derivation-tree JSON)
    Map<String, String> ruleToHumanized
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
        atomKeyToTitle = atomKeyToTitle == null ? Map.of() : Map.copyOf(atomKeyToTitle);
        ruleToHumanized = ruleToHumanized == null ? Map.of() : Map.copyOf(ruleToHumanized);
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
        private Map<String, String> atomKeyToTitle = Map.of();
        private Map<String, String> ruleToHumanized = Map.of();

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
        public Builder atomKeyToTitle(Map<String, String> m) { this.atomKeyToTitle = m; return this; }
        public Builder ruleToHumanized(Map<String, String> m) { this.ruleToHumanized = m; return this; }

        public ReasoningTrail build() {
            return new ReasoningTrail(targetId, question, confidence, breakdown,
                derivationTree, entailments, evidence, activatedRules,
                inferenceMode, runId, computedAt, naturalLanguageSummary,
                atomKeyToTitle, ruleToHumanized);
        }
    }

    /** Convenience: does this trail have a DerivationTree? */
    public boolean hasDerivationTree() { return derivationTree != null; }

    /** Convenience: does this trail have entailment records? */
    public boolean hasEntailments() { return !entailments.isEmpty(); }

    /**
     * Convert this trail into the unified {@link ReasoningTrace}. If a FOL {@link DerivationTree} is
     * present it becomes the proof tree; otherwise a single {@link ReasoningTrace.StepKind#INFERENCE}
     * conclusion is built over the {@code entailments} (as inference premises) and the {@code evidence}
     * (as fact premises). This is the bridge that lets every trail speak the one canonical trace type.
     *
     * <p><b>E3 root metadata</b>: the root step always carries {@code runId}, {@code computedAt}
     * (ISO-8601), {@code question}, {@code inferenceMode} in its meta map, plus any non-NaN
     * {@link ConfidenceBreakdown} fields as {@code breakdown.<name>} entries. In the entailments branch
     * {@code activatedRules.count} is also added. In the derivation-tree branch the same root meta is
     * attached to the derivation root via a {@link ReasoningTrace.Step#withMeta} copy.</p>
     */
    public ReasoningTrace toReasoningTrace() {
        // Build root meta from trail provenance
        Map<String, String> rootMeta = buildRootMeta();

        if (derivationTree != null) {
            ReasoningTrace raw = ReasoningTrace.fromDerivation(derivationTree);
            // Attach meta to the derivation root
            ReasoningTrace.Step metaRoot = ReasoningTrace.Step.withMeta(raw.conclusion(), rootMeta);
            return ReasoningTrace.of(metaRoot);
        }

        // Entailments branch
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (EntailmentRecord e : entailments) {
            String op = e.activatedRules().isEmpty() ? "entailment" : String.join("; ", e.activatedRules());
            premises.add(new ReasoningTrace.Step(ReasoningTrace.StepKind.INFERENCE,
                    e.groundedRvOrAtomKey(), op, ReasoningTrace.clamp01(e.posterior()),
                    e.inferenceRunId(), List.of(), null, null));
        }
        for (String ev : evidence) {
            premises.add(ReasoningTrace.Step.fact(ev, 1.0, "evidence"));
        }

        // Add activatedRules.count to root meta in the entailments branch
        if (!activatedRules.isEmpty()) {
            rootMeta = new HashMap<>(rootMeta);
            rootMeta.put("activatedRules.count", String.valueOf(activatedRules.size()));
        }

        ReasoningTrace.Step root = new ReasoningTrace.Step(ReasoningTrace.StepKind.INFERENCE,
                targetId, inferenceMode, ReasoningTrace.clamp01(confidence), runId, premises,
                null, rootMeta);
        return ReasoningTrace.of(root);
    }

    /** Build the standard root-step meta map from this trail's provenance and confidence breakdown. */
    private Map<String, String> buildRootMeta() {
        Map<String, String> meta = new HashMap<>();
        if (runId != null && !runId.isBlank()) meta.put("runId", runId);
        if (computedAt != null) meta.put("computedAt", computedAt.toString());
        if (question != null && !question.isBlank()) meta.put("question", question);
        if (inferenceMode != null && !inferenceMode.isBlank()) meta.put("inferenceMode", inferenceMode);
        if (breakdown != null) {
            addBreakdownField(meta, "breakdown.groundingConfidence", breakdown.groundingConfidence());
            addBreakdownField(meta, "breakdown.pslSoftTruth", breakdown.pslSoftTruth());
            addBreakdownField(meta, "breakdown.mebnPosterior", breakdown.mebnPosterior());
            addBreakdownField(meta, "breakdown.structuralScore", breakdown.structuralScore());
            addBreakdownField(meta, "breakdown.semanticScore", breakdown.semanticScore());
            addBreakdownField(meta, "breakdown.structuralWeight", breakdown.structuralWeight());
            addBreakdownField(meta, "breakdown.semanticWeight", breakdown.semanticWeight());
            addBreakdownField(meta, "breakdown.distanceToSatisfaction", breakdown.distanceToSatisfaction());
        }
        return meta;
    }

    private static void addBreakdownField(Map<String, String> meta, String key, double value) {
        if (!Double.isNaN(value)) {
            meta.put(key, String.valueOf(value));
        }
    }
}
