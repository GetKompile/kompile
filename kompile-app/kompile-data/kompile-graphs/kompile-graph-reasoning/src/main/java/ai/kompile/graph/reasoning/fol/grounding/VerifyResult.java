/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import java.util.List;
import java.util.Objects;

/**
 * The result of verifying a factual claim against the knowledge base.
 *
 * <p>Returned by {@link KbVerifier#verify(String)} and its overloads. Carries a
 * {@link Status} verdict, a calibrated confidence score in [0, 1], and the supporting
 * evidence (fact atom keys and activated rule display strings) that justify the verdict.</p>
 *
 * <ul>
 *   <li>{@link Status#SUPPORTED} — the atom is present in the materialized
 *       {@link ai.kompile.graph.reasoning.fol.InferredFactStore} (or directly observed in the
 *       {@link ai.kompile.graph.reasoning.fol.FactStore}) with confidence ≥ the configured
 *       threshold. {@code evidence} is non-empty and {@code confidence} ∈ (0, 1].</li>
 *   <li>{@link Status#REFUTED} — the negated atom ({@code "~" + atomKey}) was inferred with
 *       high confidence, or a
 *       {@link ai.kompile.graph.reasoning.tms.ContradictionDetector} scan flagged a
 *       contradiction involving this atom. {@code confidence} reflects how strongly it is
 *       refuted.</li>
 *   <li>{@link Status#UNKNOWN} — neither support nor refutation could be found; the atom is
 *       simply not derivable from the current KB state. {@code confidence} = 0.0 and
 *       {@code evidence} is empty. When {@link #nearMissSuggestions} is non-empty, at least
 *       one rule came close: asserting those ground atoms would make the claim provable (E9).</li>
 * </ul>
 *
 * @param status               the verdict: {@link Status#SUPPORTED}, {@link Status#REFUTED},
 *                             or {@link Status#UNKNOWN}
 * @param confidence           calibrated soft-truth value in [0, 1]; 0.0 for UNKNOWN
 * @param evidence             supporting fact atom keys plus activated rule display strings;
 *                             empty for UNKNOWN
 * @param counterEvidence      atom keys or descriptions of competing/negating facts that tension the
 *                             verdict (E4/E5): present even when verdict is SUPPORTED to expose
 *                             tension; non-empty when REFUTED due to functional conflict or negated
 *                             atom; empty for pure UNKNOWN without detected contradictions
 * @param refutationBasis      human-readable description of why the atom is refuted or tensioned,
 *                             e.g. {@code "negated-atom"}, {@code "hard-false-fact"},
 *                             {@code "functional-conflict: worksAt(alice, otherCorp) @0.92"};
 *                             {@code null} when not applicable
 * @param nearMissSuggestions  E9 why-not: ground atom keys that, if asserted, would make the
 *                             claim provable via an existing rule; empty when the claim is not
 *                             UNKNOWN or no near-miss rule was found
 */
public record VerifyResult(
        Status status,
        double confidence,
        List<String> evidence,
        List<String> counterEvidence,
        String refutationBasis,
        List<String> nearMissSuggestions
) {

    /** The three possible verdicts for a factual claim. */
    public enum Status {
        /** The atom is supported by materialized or observed evidence. */
        SUPPORTED,
        /** The atom (or its equivalent) is contradicted by the KB. */
        REFUTED,
        /** The atom is not derivable from the current KB state. */
        UNKNOWN
    }

    public VerifyResult {
        Objects.requireNonNull(status, "status must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
        counterEvidence = (counterEvidence == null) ? List.of() : List.copyOf(counterEvidence);
        nearMissSuggestions = (nearMissSuggestions == null) ? List.of() : List.copyOf(nearMissSuggestions);
    }

    /**
     * Back-compat 3-arg constructor: status, confidence, evidence — counterEvidence,
     * refutationBasis, and nearMissSuggestions default to empty / null. All existing call
     * sites that use {@code new VerifyResult(status, conf, evidence)} compile unchanged.
     */
    public VerifyResult(Status status, double confidence, List<String> evidence) {
        this(status, confidence, evidence, List.of(), null, List.of());
    }

    /**
     * Back-compat 5-arg constructor: status, confidence, evidence, counterEvidence,
     * refutationBasis — nearMissSuggestions defaults to empty. All existing call sites
     * that use the 5-arg form compile unchanged.
     */
    public VerifyResult(Status status, double confidence, List<String> evidence,
                        List<String> counterEvidence, String refutationBasis) {
        this(status, confidence, evidence, counterEvidence, refutationBasis, List.of());
    }

    // ── Factories (all additive — chain through the canonical 6-arg constructor) ──

    /** Convenience factory for a SUPPORTED result (no counter-evidence, no near-miss). */
    public static VerifyResult supported(double confidence, List<String> evidence) {
        return new VerifyResult(Status.SUPPORTED, confidence, evidence, List.of(), null, List.of());
    }

    /** Convenience factory for a SUPPORTED result with counter-evidence (E4: tension visible). */
    public static VerifyResult supported(double confidence, List<String> evidence,
                                         List<String> counterEvidence, String refutationBasis) {
        return new VerifyResult(Status.SUPPORTED, confidence, evidence, counterEvidence, refutationBasis, List.of());
    }

    /** Convenience factory for a REFUTED result (no counter-evidence description). */
    public static VerifyResult refuted(double confidence, List<String> evidence) {
        return new VerifyResult(Status.REFUTED, confidence, evidence, List.of(), null, List.of());
    }

    /** Convenience factory for a REFUTED result with counter-evidence and basis (E4/E5). */
    public static VerifyResult refuted(double confidence, List<String> evidence,
                                       List<String> counterEvidence, String refutationBasis) {
        return new VerifyResult(Status.REFUTED, confidence, evidence, counterEvidence, refutationBasis, List.of());
    }

    /** Singleton UNKNOWN result (confidence=0.0, empty evidence, no counter-evidence, no suggestions). */
    public static VerifyResult unknown() {
        return new VerifyResult(Status.UNKNOWN, 0.0, List.of(), List.of(), null, List.of());
    }

    /**
     * UNKNOWN result with E9 near-miss suggestions.
     *
     * @param nearMissSuggestions ground atom keys that, if asserted, would make the claim provable
     */
    public static VerifyResult unknownWithSuggestions(List<String> nearMissSuggestions) {
        return new VerifyResult(Status.UNKNOWN, 0.0, List.of(), List.of(), null,
                nearMissSuggestions == null ? List.of() : nearMissSuggestions);
    }
}
