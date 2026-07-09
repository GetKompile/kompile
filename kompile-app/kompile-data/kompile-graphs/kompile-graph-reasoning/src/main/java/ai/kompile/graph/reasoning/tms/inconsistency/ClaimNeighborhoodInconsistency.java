/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.inconsistency;

import ai.kompile.graph.reasoning.fol.Fact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Convenience entry point for the verify path: assesses the inconsistency of the
 * neighbourhood of a claim atom.
 *
 * <h3>Neighbourhood filter</h3>
 * <p>Given a claim atom key (e.g. {@code "worksAt(alice, acme)"}) and a collection of all
 * known facts, this class filters to the claim's <em>neighbourhood</em> — the facts that
 * share the claim's predicate OR any of the claim's string arguments — then runs a full
 * Belnap marking + inconsistency measure pass over that subset.</p>
 *
 * <p>Formally, for claim atom {@code P(a₁, …, aₙ)}:</p>
 * <ul>
 *   <li>A fact is in the neighbourhood if its <em>predicate</em> equals {@code P} (case-normalised), OR</li>
 *   <li>Its <em>atom key string</em> contains any of the argument strings {@code aᵢ}
 *       (simple substring match on the raw atom key — argument-level proximity, not equality).</li>
 * </ul>
 *
 * <p>This is a simple predicate+entity filter, not full graph traversal. The {@code radius}
 * parameter is reserved for future multi-hop expansion and is currently ignored — all
 * neighbourhood logic is at hop-1 (same predicate or same entity string).</p>
 *
 * <h3>Blame of claim</h3>
 * <p>After marking and pair computation, {@link InconsistencyMeasures#blame(String, List)} is
 * called with the <em>canonical key</em> of the claim atom (i.e., the form returned by
 * {@link BelnapMarking.ParsedAtom#canonicalKey()} when parsing the claim atom key). This lets
 * the report surface whether the claim itself is at the centre of a detected conflict.</p>
 *
 * <h3>Typical use</h3>
 * <pre>{@code
 * InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
 *         "worksAt(alice, acme)",
 *         factStore.allFacts(),
 *         1   // radius reserved, ignored
 * );
 * if (report.claimIsContested()) {
 *     verifyResult = verifyResult.withContention(report);
 * }
 * }</pre>
 *
 * <h3>References</h3>
 * <ul>
 *   <li>Hunter &amp; Konieczny, "Measuring Inconsistency through Minimal Inconsistent Sets",
 *       KR 2008 / AIJ 2010.</li>
 *   <li>Thimm, <em>Inconsistency Measurement</em>, 2019
 *       (Tweety reference implementation, tweetyproject.org).</li>
 * </ul>
 */
public final class ClaimNeighborhoodInconsistency {

    private ClaimNeighborhoodInconsistency() {}

    /**
     * Assess inconsistency in the neighbourhood of the given claim atom.
     *
     * @param claimAtomKey the raw atom key of the claim being verified, e.g.
     *                     {@code "worksAt(alice, acme)"} — may include negation decorators
     * @param allFacts     the full fact collection to filter from; nulls and null entries are ignored
     * @param radius       reserved for future multi-hop expansion; currently ignored (all filtering
     *                     is single-hop: predicate or entity mention)
     * @return an {@link InconsistencyReport} for the claim's neighbourhood
     * @throws NullPointerException if {@code claimAtomKey} is null
     */
    public static InconsistencyReport assess(String claimAtomKey,
                                             Collection<Fact> allFacts,
                                             int radius) {
        Objects.requireNonNull(claimAtomKey, "claimAtomKey must not be null");

        BelnapMarking.ParsedAtom claimParsed = BelnapMarking.ParsedAtom.parse(claimAtomKey);

        // Filter to neighbourhood
        List<Fact> neighbourhood = filterNeighbourhood(claimParsed, allFacts);

        // Run Belnap marking
        MarkingResult marking = BelnapMarking.mark(neighbourhood);

        // Build conflict pairs
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                neighbourhood, BelnapMarking.EVIDENCE_THRESHOLD);

        // Compute measures
        double contension = InconsistencyMeasures.contensionLike(marking);
        int blameOfClaim = InconsistencyMeasures.blame(claimParsed.canonicalKey(), pairs);
        List<String> minRepair = InconsistencyMeasures.minRepairApprox(pairs);

        // Build human-readable pair descriptions
        List<String> descriptions = new ArrayList<>(pairs.size());
        for (ConflictPair cp : pairs) {
            descriptions.add(cp.description());
        }

        return new InconsistencyReport(
                claimAtomKey,
                marking.bCount(),
                contension,
                blameOfClaim,
                descriptions,
                minRepair
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Neighbourhood filter
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Filter a fact collection to the neighbourhood of a parsed claim atom.
     *
     * <p>A fact is included if:</p>
     * <ol>
     *   <li>Its parsed predicate equals the claim's predicate (case-normalised), OR</li>
     *   <li>The raw atom key string contains any of the claim's arguments as a substring.</li>
     * </ol>
     *
     * <p>The claim atom itself is included if it appears in {@code allFacts} (same atom key
     * string). Unrelated facts are excluded.</p>
     */
    private static List<Fact> filterNeighbourhood(BelnapMarking.ParsedAtom claimParsed,
                                                  Collection<Fact> allFacts) {
        if (allFacts == null || allFacts.isEmpty()) return List.of();

        String claimPredicate = claimParsed.predicate(); // already normalised
        List<String> claimArgs = claimParsed.args();

        List<Fact> result = new ArrayList<>();
        for (Fact f : allFacts) {
            if (f == null) continue;
            BelnapMarking.ParsedAtom fp = BelnapMarking.ParsedAtom.parse(f.atomKey());
            // Predicate match
            if (fp.predicate().equals(claimPredicate)) {
                result.add(f);
                continue;
            }
            // Argument-string mention match (substring of raw atom key)
            if (!claimArgs.isEmpty()) {
                String rawKey = f.atomKey();
                for (String arg : claimArgs) {
                    if (arg != null && !arg.isBlank() && rawKey.contains(arg)) {
                        result.add(f);
                        break;
                    }
                }
            }
        }
        return result;
    }
}
