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

import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default infra-free implementation of {@link KbVerifier}.
 *
 * <p>All lookups are O(1) against the materialized {@link InferredFactStore} and the
 * directly-observed {@link FactStore}. No MAP re-inference is triggered here; targeted
 * inference escalation belongs in the Spring-side {@code KbGroundingService} layer.</p>
 *
 * <h3>Lookup order</h3>
 * <ol>
 *   <li>{@link InferredFactStore#latest(String)} for {@code atomKey} — if present and
 *       {@code confidence ≥ threshold} → {@link VerifyResult.Status#SUPPORTED}.</li>
 *   <li>{@link InferredFactStore#latest(String)} for {@code "~" + atomKey} (negated form) — if
 *       present with high confidence → {@link VerifyResult.Status#REFUTED}.</li>
 *   <li>{@link FactStore#factFor(String)} for {@code atomKey} — directly observed hard facts
 *       are treated as SUPPORTED (confidence = the fact's value).</li>
 *   <li>Otherwise → {@link VerifyResult.Status#UNKNOWN}.</li>
 * </ol>
 *
 * <p>This class is immutable and thread-safe provided the supplied stores are only
 * mutated before construction or under external synchronization.</p>
 */
public final class DefaultKbVerifier implements KbVerifier {

    private final InferredFactStore inferredFactStore;
    private final FactStore factStore;
    private final double threshold;

    /**
     * Create a verifier backed by the given stores, using the default confidence threshold.
     *
     * @param inferredFactStore the materialized inference result store (must not be null)
     * @param factStore         the directly-observed fact store (must not be null)
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore) {
        this(inferredFactStore, factStore, DEFAULT_THRESHOLD);
    }

    /**
     * Create a verifier with a custom confidence threshold.
     *
     * @param inferredFactStore the materialized inference result store (must not be null)
     * @param factStore         the directly-observed fact store (must not be null)
     * @param threshold         minimum confidence in [0, 1] to report SUPPORTED
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore, double threshold) {
        this.inferredFactStore = Objects.requireNonNull(inferredFactStore, "inferredFactStore must not be null");
        this.factStore = Objects.requireNonNull(factStore, "factStore must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0,1], got: " + threshold);
        }
        this.threshold = threshold;
    }

    @Override
    public VerifyResult verify(String atomKey) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        String trimmed = atomKey.trim();

        // Step 1: Check InferredFactStore for the positive atom
        Optional<InferredFact> inferredOpt = inferredFactStore.latest(trimmed);
        if (inferredOpt.isPresent()) {
            InferredFact inferred = inferredOpt.get();
            double confidence = inferred.confidence();
            if (confidence >= threshold) {
                // Build evidence: supporting fact keys + supporting rule ids
                List<String> evidence = buildEvidence(inferred);
                return VerifyResult.supported(confidence, evidence);
            }
            // confidence below threshold — not strongly supported; continue checking for refutation
        }

        // Step 2: Check for the negated atom in InferredFactStore (~atomKey)
        String negatedKey = buildNegatedKey(trimmed);
        Optional<InferredFact> negatedOpt = inferredFactStore.latest(negatedKey);
        if (negatedOpt.isPresent()) {
            InferredFact negated = negatedOpt.get();
            double negConfidence = negated.confidence();
            if (negConfidence >= threshold) {
                List<String> evidence = buildEvidence(negated);
                return VerifyResult.refuted(negConfidence, evidence);
            }
        }

        // Step 3: Check FactStore for a directly observed fact
        var factOpt = factStore.factFor(trimmed);
        if (factOpt.isPresent()) {
            var fact = factOpt.get();
            double factValue = fact.value();
            if (fact.hard() && factValue >= threshold) {
                // Hard-observed fact is authoritative
                List<String> evidence = List.of("observed:" + fact.sourceId());
                return VerifyResult.supported(factValue, evidence);
            }
            if (fact.hard() && factValue <= (1.0 - threshold)) {
                // Hard-observed fact with near-0 value: the atom is refuted
                List<String> evidence = List.of("observed-false:" + fact.sourceId());
                return VerifyResult.refuted(1.0 - factValue, evidence);
            }
        }

        // Step 4: Not derivable from current KB state
        return VerifyResult.unknown();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Build the evidence list from an {@link InferredFact}: supporting fact keys first,
     * then activated rule display strings.
     */
    private static List<String> buildEvidence(InferredFact fact) {
        List<String> evidence = new ArrayList<>(
                fact.supportingFactKeys().size() + fact.supportingRuleIds().size());
        evidence.addAll(fact.supportingFactKeys());
        evidence.addAll(fact.supportingRuleIds());
        return evidence;
    }

    /**
     * Build the negated atom key: strips a leading {@code "~"} if already negated
     * (double-negation = positive), otherwise prepends {@code "~"}.
     */
    static String buildNegatedKey(String atomKey) {
        if (atomKey.startsWith("~")) {
            return atomKey.substring(1).trim();
        }
        return "~" + atomKey;
    }
}
