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

import ai.kompile.graph.reasoning.confidence.Opinion;

import java.util.List;
import java.util.Objects;

/**
 * A single reasoning modality's contribution to a {@link CompositeReasoningTrail}.
 *
 * <p>Each modality that ran successfully produces one {@code ModalityEvidence} entry
 * carrying its confidence signal, a human-readable one-line summary, the full list
 * of fine-grained evidence strings (rule firings, atom activations, RAG chunk excerpts,
 * causal chain hops, etc.), and an optional subjective-logic {@link Opinion} for
 * opinion-aware fusion (E17).</p>
 *
 * <h3>Opinion field (E17)</h3>
 * <p>When {@code opinion} is non-null it is used as-is by {@link EvidenceAccumulator}'s
 * opinion-aware fusion path.  When null the accumulator derives an
 * uncertainty-maximized opinion from {@link #confidence()} via
 * {@link Opinion#uncertaintyMaximized(double, double)} with base-rate 0.5.</p>
 *
 * @param kind       which reasoning engine produced this evidence
 * @param confidence the calibrated confidence signal in [0,1] from that engine;
 *                   {@code Double.NaN} when the engine produced no signal
 * @param summary    short human-readable description of what this modality found
 * @param details    zero or more fine-grained evidence strings from the engine
 * @param opinion    optional subjective-logic opinion; null = derive from confidence at fusion time
 */
public record ModalityEvidence(
        ModalityKind kind,
        double confidence,
        String summary,
        List<String> details,
        Opinion opinion
) {
    public ModalityEvidence {
        Objects.requireNonNull(kind, "kind");
        if (summary == null) summary = "";
        details = details == null ? List.of() : List.copyOf(details);
        // opinion stays nullable — absence means "derive from confidence at fusion time"
    }

    /**
     * Back-compat constructor: no opinion supplied.
     * Identical behaviour to the original 4-arg record constructor.
     */
    public ModalityEvidence(ModalityKind kind, double confidence, String summary, List<String> details) {
        this(kind, confidence, summary, details, null);
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    /**
     * Convenience factory: build from a {@link ReasoningTrail} produced by a single-mode engine.
     * Derives an uncertainty-maximized opinion from the trail's confidence (base-rate 0.5).
     */
    public static ModalityEvidence fromTrail(ModalityKind kind, ReasoningTrail trail) {
        Objects.requireNonNull(trail, "trail");
        List<String> details = new java.util.ArrayList<>();
        details.addAll(trail.evidence());
        details.addAll(trail.activatedRules());
        double conf = trail.confidence();
        Opinion derived = deriveOpinion(conf);
        return new ModalityEvidence(
                kind,
                conf,
                trail.naturalLanguageSummary(),
                details,
                derived
        );
    }

    /**
     * Factory that accepts an explicit {@link Opinion}, bypassing derivation.
     * Use when the calling engine already has a well-formed subjective-logic opinion.
     */
    public static ModalityEvidence fromTrailWithOpinion(ModalityKind kind,
                                                         ReasoningTrail trail,
                                                         Opinion opinion) {
        Objects.requireNonNull(trail, "trail");
        Objects.requireNonNull(opinion, "opinion");
        List<String> details = new java.util.ArrayList<>();
        details.addAll(trail.evidence());
        details.addAll(trail.activatedRules());
        return new ModalityEvidence(
                kind,
                trail.confidence(),
                trail.naturalLanguageSummary(),
                details,
                opinion
        );
    }

    /** Convenience factory: a simple evidence entry without a full trail (no opinion; derived at fusion time). */
    public static ModalityEvidence of(ModalityKind kind, double confidence,
                                      String summary, List<String> details) {
        return new ModalityEvidence(kind, confidence, summary, details, null);
    }

    /** Convenience factory: a simple evidence entry with an explicit opinion. */
    public static ModalityEvidence of(ModalityKind kind, double confidence,
                                      String summary, List<String> details, Opinion opinion) {
        return new ModalityEvidence(kind, confidence, summary, details, opinion);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** True when this modality produced a real (non-NaN, non-zero) confidence signal. */
    public boolean hasSignal() {
        return !Double.isNaN(confidence) && confidence > 0.0;
    }

    /**
     * Derive or return an opinion for this evidence entry.
     * When an explicit opinion was set it is returned as-is; otherwise derives
     * {@link Opinion#uncertaintyMaximized(double, double)} from {@link #confidence()}.
     * Returns null only when confidence is NaN (no signal).
     */
    public Opinion effectiveOpinion() {
        if (opinion != null) return opinion;
        return deriveOpinion(confidence);
    }

    /**
     * Derive an uncertainty-maximized opinion from a scalar confidence.
     * Returns null for NaN (no signal) so callers can skip vacuous modalities cleanly.
     */
    static Opinion deriveOpinion(double confidence) {
        if (Double.isNaN(confidence) || confidence <= 0.0) return null;
        return Opinion.uncertaintyMaximized(confidence, 0.5);
    }
}
