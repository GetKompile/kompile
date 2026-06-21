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

/**
 * Agent-facing primitive for verifying factual claims against the knowledge base.
 *
 * <p>This is the <em>P0-1 hallucination-control primitive</em> as described in the
 * agent-grounding infrastructure design. An LLM agent making a claim such as
 * {@code "isEmployedBy(Alice, Acme)"} calls {@link #verify(String)} to obtain a
 * {@link VerifyResult} with a {@link VerifyResult.Status} verdict, a calibrated
 * confidence score, and the evidence (fact keys + rule display strings) that
 * justify the verdict.</p>
 *
 * <p>Implementations are expected to be infra-free (no Spring, no new dependencies).
 * The canonical implementation is {@link DefaultKbVerifier}.</p>
 *
 * <h3>Lookup order</h3>
 * <ol>
 *   <li>Check {@link ai.kompile.graph.reasoning.fol.InferredFactStore#latest(String)}
 *       — if the atom is materialized with {@code confidence ≥ threshold} → SUPPORTED.</li>
 *   <li>Check the negated atom key ({@code "~" + atomKey}) in the inferred fact store
 *       — if present with high confidence → REFUTED.</li>
 *   <li>Check {@link ai.kompile.graph.reasoning.fol.FactStore#factFor(String)}
 *       — if directly observed as a hard fact → SUPPORTED (confidence = observed value).</li>
 *   <li>Otherwise → UNKNOWN (no targeted inference is triggered; that escalation lives
 *       in the Spring-side {@code KbGroundingService}).</li>
 * </ol>
 */
public interface KbVerifier {

    /**
     * Default confidence threshold below which a materialized fact is considered UNKNOWN
     * rather than SUPPORTED.
     */
    double DEFAULT_THRESHOLD = 0.5;

    /**
     * Verify an atom by its canonical key (e.g. {@code "isEmployedBy(Alice, Acme)"}).
     *
     * @param atomKey the canonical atom key
     * @return the verification result
     */
    VerifyResult verify(String atomKey);

    /**
     * Verify an atom given its predicate and arguments separately.
     *
     * <p>Convenience overload that formats the atom key as
     * {@code predicate(arg0, arg1, ...)}, then delegates to {@link #verify(String)}.</p>
     *
     * @param predicate the predicate name (e.g. {@code "isEmployedBy"})
     * @param args      the ground argument values
     * @return the verification result
     */
    default VerifyResult verify(String predicate, String... args) {
        StringBuilder sb = new StringBuilder(predicate).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        sb.append(')');
        return verify(sb.toString());
    }
}
