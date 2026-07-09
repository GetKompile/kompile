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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.tms.ContradictionDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 *   <li>E5: if status would be UNKNOWN and the predicate is functional, scan
 *       {@link InferredFactStore#allLatest()} and {@link FactStore} for a competing high-confidence
 *       fact {@code p(sameSubject, differentObject)} → REFUTED with basis description.</li>
 *   <li>Otherwise → {@link VerifyResult.Status#UNKNOWN}.</li>
 * </ol>
 *
 * <p>E4 counter-evidence: for SUPPORTED results, any negated-atom entry or functional
 * competitor found in the stores is attached as {@link VerifyResult#counterEvidence()} without
 * changing the verdict, so callers can expose tension to users.</p>
 *
 * <p>This class is immutable and thread-safe provided the supplied stores are only
 * mutated before construction or under external synchronization.</p>
 */
public final class DefaultKbVerifier implements KbVerifier {

    /**
     * Minimum confidence of a competing fact required to trigger E5 functional-conflict
     * refutation. Default: 0.7.
     */
    public static final double DEFAULT_FUNCTIONAL_CONFLICT_THRESHOLD = 0.7;

    private final InferredFactStore inferredFactStore;
    private final FactStore factStore;
    private final double threshold;
    private final Set<String> functionalPredicates;
    private final double functionalConflictThreshold;

    /**
     * E9: optional rule normal forms for why-not / near-miss explanation.
     * When non-null and non-empty, a {@link WhyNotExplainer} is run on UNKNOWN verdicts
     * to produce {@link VerifyResult#nearMissSuggestions()}.
     */
    private final List<WhyNotExplainer.RuleNf> whyNotRules;

    // ── Constructors (all chain through the full 6-arg canonical form) ───────────

    /**
     * Create a verifier backed by the given stores, using the default confidence threshold
     * and the default functional-predicate set from {@link ContradictionDetector#defaultFunctionalPredicates()}.
     *
     * @param inferredFactStore the materialized inference result store (must not be null)
     * @param factStore         the directly-observed fact store (must not be null)
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore) {
        this(inferredFactStore, factStore, DEFAULT_THRESHOLD,
                ContradictionDetector.defaultFunctionalPredicates(),
                DEFAULT_FUNCTIONAL_CONFLICT_THRESHOLD, null);
    }

    /**
     * Create a verifier with a custom confidence threshold. Uses the default functional-predicate
     * set from {@link ContradictionDetector#defaultFunctionalPredicates()}.
     *
     * @param inferredFactStore the materialized inference result store (must not be null)
     * @param factStore         the directly-observed fact store (must not be null)
     * @param threshold         minimum confidence in [0, 1] to report SUPPORTED
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore, double threshold) {
        this(inferredFactStore, factStore, threshold,
                ContradictionDetector.defaultFunctionalPredicates(),
                DEFAULT_FUNCTIONAL_CONFLICT_THRESHOLD, null);
    }

    /**
     * Full constructor: custom threshold, custom functional-predicate set, and custom
     * functional-conflict threshold for E5 refutation.
     *
     * @param inferredFactStore             the materialized inference result store
     * @param factStore                     the directly-observed fact store
     * @param threshold                     minimum confidence to report SUPPORTED
     * @param functionalPredicates          predicates treated as single-valued per subject (E5)
     * @param functionalConflictThreshold   min confidence of a competing fact to trigger refutation
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore,
                              double threshold, Set<String> functionalPredicates,
                              double functionalConflictThreshold) {
        this(inferredFactStore, factStore, threshold, functionalPredicates,
                functionalConflictThreshold, null);
    }

    /**
     * E9-enabled constructor: adds optional rule normal forms for why-not near-miss explanation.
     * On UNKNOWN verdicts, {@link WhyNotExplainer} is run (bounded by default knobs) and the
     * completing-fact suggestions are attached to {@link VerifyResult#nearMissSuggestions()}.
     *
     * @param inferredFactStore             the materialized inference result store
     * @param factStore                     the directly-observed fact store
     * @param threshold                     minimum confidence to report SUPPORTED
     * @param functionalPredicates          predicates treated as single-valued per subject (E5)
     * @param functionalConflictThreshold   min confidence of a competing fact to trigger refutation
     * @param whyNotRules                   rule normal forms for E9 near-miss; null or empty disables E9
     */
    public DefaultKbVerifier(InferredFactStore inferredFactStore, FactStore factStore,
                              double threshold, Set<String> functionalPredicates,
                              double functionalConflictThreshold,
                              List<WhyNotExplainer.RuleNf> whyNotRules) {
        this.inferredFactStore = Objects.requireNonNull(inferredFactStore, "inferredFactStore must not be null");
        this.factStore = Objects.requireNonNull(factStore, "factStore must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0,1], got: " + threshold);
        }
        if (functionalConflictThreshold < 0.0 || functionalConflictThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "functionalConflictThreshold must be in [0,1], got: " + functionalConflictThreshold);
        }
        this.threshold = threshold;
        this.functionalPredicates = (functionalPredicates == null || functionalPredicates.isEmpty())
                ? ContradictionDetector.defaultFunctionalPredicates()
                : functionalPredicates;
        this.functionalConflictThreshold = functionalConflictThreshold;
        this.whyNotRules = (whyNotRules == null || whyNotRules.isEmpty()) ? null : whyNotRules;
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
                // E4: look for counter-evidence (negated atom or functional competitors)
                List<String> counterEvidence = findCounterEvidence(trimmed);
                String basis = counterEvidence.isEmpty() ? null : describeBasis(trimmed, counterEvidence);
                return VerifyResult.supported(confidence, evidence, counterEvidence, basis);
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
                List<String> counterEvidence = List.of(negatedKey);
                return VerifyResult.refuted(negConfidence, evidence, counterEvidence, "negated-atom");
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
                // E4: still look for counter-evidence
                List<String> counterEvidence = findCounterEvidence(trimmed);
                String basis = counterEvidence.isEmpty() ? null : describeBasis(trimmed, counterEvidence);
                return VerifyResult.supported(factValue, evidence, counterEvidence, basis);
            }
            if (fact.hard() && factValue <= (1.0 - threshold)) {
                // Hard-observed fact with near-0 value: the atom is refuted
                List<String> evidence = List.of("observed-false:" + fact.sourceId());
                List<String> counterEvidence = List.of(trimmed + " (value=" + factValue + ")");
                return VerifyResult.refuted(1.0 - factValue, evidence, counterEvidence, "hard-false-fact");
            }
        }

        // Step 4: E5 — functional-constraint refutation for UNKNOWN atoms
        // Parse the atom to get predicate + first arg for functional lookup.
        ParsedAtomKey parsed = ParsedAtomKey.parse(trimmed);
        if (parsed != null && ContradictionDetector.isFunctionalPredicate(parsed.predicate)) {
            FunctionalConflict fc = findFunctionalConflict(parsed);
            if (fc != null) {
                List<String> counterEvidence = List.of(fc.competingAtom);
                String basis = "functional-conflict: " + fc.competingAtom
                        + " @" + String.format("%.2f", fc.confidence);
                return VerifyResult.refuted(fc.confidence, List.of(), counterEvidence, basis);
            }
        }

        // Step 5: Not derivable from current KB state.
        // E9: run WhyNotExplainer if rules are available to find near-miss derivations.
        if (whyNotRules != null) {
            WhyNotExplainer explainer = new WhyNotExplainer(whyNotRules, inferredFactStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain(trimmed);
            if (!report.suggestions().isEmpty()) {
                return VerifyResult.unknownWithSuggestions(report.suggestions());
            }
        }
        return VerifyResult.unknown();
    }

    // ─── E4: counter-evidence for SUPPORTED results ──────────────────────────────

    /**
     * Collect counter-evidence for an atom that passed the SUPPORTED test: look for a negated
     * form in the inferred store and any functional competitors in both stores.
     */
    private List<String> findCounterEvidence(String atomKey) {
        List<String> result = new ArrayList<>();

        // (a) Negated atom in the inferred store
        String negKey = buildNegatedKey(atomKey);
        inferredFactStore.latest(negKey).ifPresent(neg -> {
            if (neg.confidence() >= threshold) {
                result.add(negKey + " (confidence=" + String.format("%.2f", neg.confidence()) + ")");
            }
        });

        // (b) Functional competitors (only if predicate is functional)
        ParsedAtomKey parsed = ParsedAtomKey.parse(atomKey);
        if (parsed != null && ContradictionDetector.isFunctionalPredicate(parsed.predicate)) {
            FunctionalConflict fc = findFunctionalConflict(parsed);
            if (fc != null) {
                result.add(fc.competingAtom + " @" + String.format("%.2f", fc.confidence));
            }
        }

        return result;
    }

    /** Produce a short basis string describing the counter-evidence source. */
    private static String describeBasis(String atomKey, List<String> counterEvidence) {
        if (counterEvidence.isEmpty()) return null;
        String first = counterEvidence.get(0);
        // Distinguish between negated-atom and functional-conflict
        String negKey = buildNegatedKey(atomKey);
        if (first.startsWith(negKey)) return "negated-atom";
        return "functional-conflict";
    }

    // ─── E5: functional-conflict refutation ──────────────────────────────────────

    /**
     * Scan both the inferred store and the fact store for a competing high-confidence fact
     * with the same predicate and first argument but different full atom key.
     *
     * @return a {@link FunctionalConflict} describing the winner, or null if none found
     */
    private FunctionalConflict findFunctionalConflict(ParsedAtomKey claim) {
        FunctionalConflict best = null;

        // Scan inferred store
        for (InferredFact candidate : inferredFactStore.allLatest()) {
            if (candidate.confidence() < functionalConflictThreshold) continue;
            ParsedAtomKey cp = ParsedAtomKey.parse(candidate.atomKey());
            if (cp == null) continue;
            if (!cp.predicate.equalsIgnoreCase(claim.predicate)) continue;
            if (!cp.firstArg.equalsIgnoreCase(claim.firstArg)) continue;
            if (candidate.atomKey().equalsIgnoreCase(claim.raw)) continue; // same atom
            if (best == null || candidate.confidence() > best.confidence) {
                best = new FunctionalConflict(candidate.atomKey(), candidate.confidence());
            }
        }

        // Scan fact store
        for (Fact candidate : factStore.allFacts()) {
            if (!candidate.hard() || candidate.value() < functionalConflictThreshold) continue;
            ParsedAtomKey cp = ParsedAtomKey.parse(candidate.atomKey());
            if (cp == null) continue;
            if (!cp.predicate.equalsIgnoreCase(claim.predicate)) continue;
            if (!cp.firstArg.equalsIgnoreCase(claim.firstArg)) continue;
            if (candidate.atomKey().equalsIgnoreCase(claim.raw)) continue; // same atom
            if (best == null || candidate.value() > best.confidence) {
                best = new FunctionalConflict(candidate.atomKey(), candidate.value());
            }
        }

        return best;
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

    // ─── Inner types ─────────────────────────────────────────────────────────────

    /** Minimal parse of an atom key into (raw, predicate, firstArg). */
    private record ParsedAtomKey(String raw, String predicate, String firstArg) {
        static ParsedAtomKey parse(String atomKey) {
            if (atomKey == null || atomKey.isBlank()) return null;
            int lp = atomKey.indexOf('(');
            if (lp < 0) return null; // 0-arity atom — cannot be functional in the usual sense
            int rp = atomKey.lastIndexOf(')');
            if (rp <= lp) return null;
            String predicate = atomKey.substring(0, lp).trim();
            if (predicate.isBlank()) return null;
            String inside = atomKey.substring(lp + 1, rp).trim();
            if (inside.isBlank()) return null;
            int comma = inside.indexOf(',');
            String firstArg = (comma >= 0)
                    ? inside.substring(0, comma).trim()
                    : inside.trim();
            if (firstArg.isBlank()) return null;
            return new ParsedAtomKey(atomKey, predicate.toUpperCase(Locale.ROOT), firstArg);
        }
    }

    /** Describes a detected functional competitor. */
    private record FunctionalConflict(String competingAtom, double confidence) {}
}
