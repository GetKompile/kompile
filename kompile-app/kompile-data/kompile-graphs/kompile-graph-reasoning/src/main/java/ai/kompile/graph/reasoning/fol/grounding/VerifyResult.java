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
 *       {@code evidence} is empty.</li>
 * </ul>
 *
 * @param status     the verdict: {@link Status#SUPPORTED}, {@link Status#REFUTED},
 *                   or {@link Status#UNKNOWN}
 * @param confidence calibrated soft-truth value in [0, 1]; 0.0 for UNKNOWN
 * @param evidence   supporting fact atom keys plus activated rule display strings;
 *                   empty for UNKNOWN
 */
public record VerifyResult(
        Status status,
        double confidence,
        List<String> evidence
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
    }

    /** Convenience factory for a SUPPORTED result. */
    public static VerifyResult supported(double confidence, List<String> evidence) {
        return new VerifyResult(Status.SUPPORTED, confidence, evidence);
    }

    /** Convenience factory for a REFUTED result. */
    public static VerifyResult refuted(double confidence, List<String> evidence) {
        return new VerifyResult(Status.REFUTED, confidence, evidence);
    }

    /** Singleton UNKNOWN result (confidence=0.0, empty evidence). */
    public static VerifyResult unknown() {
        return new VerifyResult(Status.UNKNOWN, 0.0, List.of());
    }
}
