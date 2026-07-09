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
import ai.kompile.graph.reasoning.tms.ContradictionDetector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Polynomial-time approximations of formal inconsistency measures over a
 * {@link MarkingResult} and the underlying conflict pairs.
 *
 * <h3>Scope and approximation note</h3>
 * <p>The measures implemented here are the cheap polynomial instances of the formal measures
 * defined by Hunter &amp; Konieczny (KR 2008 / AIJ 2010) and Thimm (2019).
 * The {@link #minRepairApprox(List)} method computes a greedy minimum hitting set
 * (ln(n)-approximation of I_hs per Chvátal 1979). Full computation of I_c via answer-set
 * programming and exact MIS enumeration is out of scope here; Tweety
 * (tweetyproject.org) provides the reference ASP implementation.</p>
 *
 * <h3>MIS restriction</h3>
 * <p>In a general knowledge base, a minimal inconsistent subset (MIS) can be of any size.
 * This class deals exclusively with <em>pair-shaped</em> contradictions as produced by
 * {@link BelnapMarking}: each conflict is a 2-element set (one positive fact, one
 * negating/conflicting fact). Therefore every MIS we enumerate has exactly 2 members.
 * This restriction is documented on {@link #miCount(List)} and does not hold for
 * longer derivation chains — use the full Tweety MIS enumerator for those.</p>
 *
 * <h3>References</h3>
 * <ul>
 *   <li>Hunter &amp; Konieczny, "Measuring Inconsistency through Minimal Inconsistent Sets",
 *       KR 2008; extended in <em>Artificial Intelligence</em> 174(14):1197–1216, 2010.</li>
 *   <li>Thimm, <em>Inconsistency Measurement</em>, 2019
 *       (reference implementation: Tweety, tweetyproject.org — not bundled here).</li>
 *   <li>Grant &amp; Hunter, "Distance-based measures of inconsistency", ECSQARU 2013
 *       (I_d and MI distance variants).</li>
 *   <li>Priest, <em>In Contradiction</em>, 2006 (LP paraconsistent logic;
 *       the Belnap B mark corresponds to the LP truth value "both T and F").</li>
 *   <li>Chvátal, "A greedy heuristic for the set-covering problem",
 *       <em>Mathematics of Operations Research</em> 4(3), 1979 (ln(n)-approximation).</li>
 * </ul>
 */
public final class InconsistencyMeasures {

    private InconsistencyMeasures() {}

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Primary measures
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Drastic inconsistency measure (I_D): 0 if there are no conflicts, 1 otherwise.
     *
     * <p>This is the coarsest possible measure — it distinguishes only "consistent" from
     * "inconsistent" and carries no quantitative information. Equivalent to Hunter &amp;
     * Konieczny's I_D = 0 iff {@code miCount == 0}.</p>
     *
     * @param conflictPairs the conflict pairs from a {@link BelnapMarking} pass
     * @return 0 (consistent) or 1 (inconsistent)
     */
    public static int drastic(List<ConflictPair> conflictPairs) {
        Objects.requireNonNull(conflictPairs, "conflictPairs must not be null");
        return conflictPairs.isEmpty() ? 0 : 1;
    }

    /**
     * Count of minimal inconsistent sets (I_MI).
     *
     * <p><b>Restriction</b>: this implementation only counts pair-shaped MISes (size 2), which
     * is the complete set of MISes for the contradiction kinds handled by {@link BelnapMarking}
     * (negated-pair and functional-predicate). For knowledge bases with longer derivation chains,
     * use the full Tweety MIS enumerator (ASP-based). Each distinct conflict pair here
     * corresponds to exactly one MIS of size 2.</p>
     *
     * @param conflictPairs the conflict pairs
     * @return number of MISes (= number of distinct conflict pairs)
     */
    public static int miCount(List<ConflictPair> conflictPairs) {
        Objects.requireNonNull(conflictPairs, "conflictPairs must not be null");
        return conflictPairs.size();
    }

    /**
     * Contension-like measure (I_c-inspired): fraction of atoms that are Belnap-B among all
     * atoms with any strong evidence.
     *
     * <p>This is the "inconsistency footprint" — how much of the strongly-evidenced region
     * is contested. A value of 0 means no contested atoms; 1 means every strongly-evidenced
     * atom is in conflict.</p>
     *
     * <p>Relation to Priest's LP: an atom marked B in Belnap logic is exactly the class of
     * atoms that are both "true" and "false" in LP (the Logic of Paradox). The contension
     * measure counts the relative mass of this paraconsistently-true region.</p>
     *
     * <p>Relation to Hunter &amp; Konieczny I_c: the contension measure of a knowledge base is
     * |{φ ∈ KB : φ is in some MIS}| / |KB| in the full formulation. Here we approximate it as
     * the proportion of strongly-evidenced atoms that are B (both positive and negative
     * evidence), which is the Belnap-marking analogue for the ground-atom level.</p>
     *
     * @param result the marking result
     * @return value in [0,1], or 0 if there are no strongly-evidenced atoms
     */
    public static double contensionLike(MarkingResult result) {
        Objects.requireNonNull(result, "result must not be null");
        int strongCount = result.strongEvidenceCount();
        if (strongCount == 0) return 0.0;
        return (double) result.bCount() / strongCount;
    }

    /**
     * Greedy minimum-repair approximation (I_hs approximation).
     *
     * <p>A <em>repair</em> of an inconsistent knowledge base is a minimal subset of facts whose
     * removal restores consistency; a <em>minimum repair</em> is the smallest such subset.
     * Computing the exact minimum hitting set of all MISes is NP-hard; this greedy algorithm
     * gives a ln(k)-approximation (Chvátal 1979) where k = number of MISes.</p>
     *
     * <p>Algorithm: repeat until no conflicts remain — find the fact that participates in the
     * most conflict pairs (ties broken by atom key lexicographic order for determinism), add it
     * to the repair set, remove all pairs containing it.</p>
     *
     * <p>The returned set contains the <em>canonical atom keys</em> of facts to remove
     * (i.e. the predicate+args canonical form used in {@link MarkingResult#byCanonicalAtom()}).
     * The caller is responsible for deciding which actual {@link Fact} instances to retract.</p>
     *
     * @param conflictPairs the conflict pairs from a {@link BelnapMarking} pass
     * @return an ordered list (greedy-pick order) of canonical atom keys to retract for a
     *         minimum-cardinality repair; empty if there are no conflicts
     */
    public static List<String> minRepairApprox(List<ConflictPair> conflictPairs) {
        Objects.requireNonNull(conflictPairs, "conflictPairs must not be null");
        if (conflictPairs.isEmpty()) return List.of();

        // Working copy — mutable list of pairs, each represented as a 2-element String array
        List<String[]> remaining = new ArrayList<>(conflictPairs.size());
        for (ConflictPair cp : conflictPairs) {
            remaining.add(new String[]{cp.canonicalKeyA(), cp.canonicalKeyB()});
        }

        List<String> repairSet = new ArrayList<>();

        while (!remaining.isEmpty()) {
            // Count how many remaining pairs each atom participates in
            Map<String, Integer> frequency = new HashMap<>();
            for (String[] pair : remaining) {
                frequency.merge(pair[0], 1, Integer::sum);
                frequency.merge(pair[1], 1, Integer::sum);
            }
            // Pick the atom with the highest frequency; break ties lexicographically
            String pivot = frequency.entrySet().stream()
                    .max((a, b) -> {
                        int cmp = a.getValue().compareTo(b.getValue());
                        if (cmp != 0) return cmp;
                        return b.getKey().compareTo(a.getKey()); // lexicographic tiebreak
                    })
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (pivot == null) break;

            repairSet.add(pivot);

            // Remove all pairs containing the pivot
            List<String[]> next = new ArrayList<>();
            for (String[] pair : remaining) {
                if (!pair[0].equals(pivot) && !pair[1].equals(pivot)) {
                    next.add(pair);
                }
            }
            remaining = next;
        }

        return List.copyOf(repairSet);
    }

    /**
     * Blame count for an atom: the number of conflict pairs that contain this atom's
     * canonical key.
     *
     * <p>A high blame count means this atom is "at the centre" of the inconsistency and
     * would be the first candidate for retraction in {@link #minRepairApprox}. This
     * corresponds to the responsibility / contribution score discussed in
     * Hunter &amp; Konieczny (2010) §7.</p>
     *
     * @param atomKey       the canonical atom key to query (e.g. {@code "CEO|[acme, alice]"} or
     *                      the raw key — any string compared directly against
     *                      {@link ConflictPair#canonicalKeyA()} / {@link ConflictPair#canonicalKeyB()})
     * @param conflictPairs the conflict pairs
     * @return number of conflict pairs containing this atom (0 if none)
     */
    public static int blame(String atomKey, List<ConflictPair> conflictPairs) {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(conflictPairs, "conflictPairs must not be null");
        int count = 0;
        for (ConflictPair cp : conflictPairs) {
            if (atomKey.equals(cp.canonicalKeyA()) || atomKey.equals(cp.canonicalKeyB())) {
                count++;
            }
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Conflict-pair builder from a Fact collection
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Build the list of {@link ConflictPair}s from a raw fact collection and evidence threshold.
     *
     * <p>This mirrors the two detection mechanisms in {@link BelnapMarking}: negated-pair conflicts
     * and functional-predicate conflicts. The pairs here use canonical atom keys and include a
     * human-readable {@link ConflictPair#description()} for the {@link InconsistencyReport}.</p>
     *
     * @param facts             the fact collection
     * @param evidenceThreshold minimum value for a fact to count as strong evidence
     * @return ordered list of conflict pairs
     */
    static List<ConflictPair> buildConflictPairs(Collection<Fact> facts, double evidenceThreshold) {
        if (facts == null || facts.isEmpty()) return List.of();

        List<Fact> safeList = new ArrayList<>();
        for (Fact f : facts) {
            if (f != null) safeList.add(f);
        }
        int n = safeList.size();
        BelnapMarking.ParsedAtom[] parsed = new BelnapMarking.ParsedAtom[n];
        for (int i = 0; i < n; i++) {
            parsed[i] = BelnapMarking.ParsedAtom.parse(safeList.get(i).atomKey());
        }

        // Deduplicate via set of ordered canonical-key pairs (canonicalKeyA <= canonicalKeyB lexicographically)
        Set<String> emitted = new LinkedHashSet<>();
        List<ConflictPair> result = new ArrayList<>();

        // ── Kind B: negated-pair conflicts ────────────────────────────────────────────────
        // Group strong-evidence facts by (predicate, args); pairs with one negated + one not.
        Map<String, List<Integer>> byGroundAtom = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Fact f = safeList.get(i);
            if (f.value() < evidenceThreshold) continue;
            BelnapMarking.ParsedAtom pa = parsed[i];
            String bucketKey = pa.predicate() + "|" + pa.args();
            byGroundAtom.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : byGroundAtom.values()) {
            int gs = group.size();
            if (gs < 2) continue;
            for (int gi = 0; gi < gs; gi++) {
                for (int gj = gi + 1; gj < gs; gj++) {
                    int i = group.get(gi), j = group.get(gj);
                    BelnapMarking.ParsedAtom pa = parsed[i], pb = parsed[j];
                    if (pa.negated() == pb.negated()) continue; // same polarity, no conflict
                    String posKey = pa.negated() ? pb.canonicalKey() : pa.canonicalKey();
                    String negKey = pa.negated() ? pa.canonicalKey() : pb.canonicalKey();
                    String pairKey = orderKey(posKey, negKey);
                    if (emitted.add(pairKey)) {
                        result.add(new ConflictPair(
                                posKey,
                                negKey,
                                "negated-pair: " + safeList.get(i).atomKey() + " ↔ " + safeList.get(j).atomKey()
                        ));
                    }
                }
            }
        }

        // ── Functional-predicate conflicts ────────────────────────────────────────────────
        Map<String, List<Integer>> byFuncSubject = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Fact f = safeList.get(i);
            if (f.value() < evidenceThreshold) continue;
            BelnapMarking.ParsedAtom pa = parsed[i];
            if (pa.negated()) continue;
            if (pa.args().isEmpty()) continue;
            if (!ContradictionDetector.isFunctionalPredicate(pa.predicate())) continue;
            String bucketKey = pa.predicate() + "|" + pa.args().get(0);
            byFuncSubject.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : byFuncSubject.values()) {
            int gs = group.size();
            if (gs < 2) continue;
            for (int gi = 0; gi < gs; gi++) {
                for (int gj = gi + 1; gj < gs; gj++) {
                    int i = group.get(gi), j = group.get(gj);
                    BelnapMarking.ParsedAtom pa = parsed[i], pb = parsed[j];
                    if (Objects.equals(pa.args(), pb.args())) continue; // same args → not a clash
                    String keyA = pa.canonicalKey();
                    String keyB = pb.canonicalKey();
                    String pairKey = orderKey(keyA, keyB);
                    if (emitted.add(pairKey)) {
                        result.add(new ConflictPair(
                                keyA,
                                keyB,
                                "functional-clash: " + safeList.get(i).atomKey() + " ↔ " + safeList.get(j).atomKey()
                        ));
                    }
                }
            }
        }

        return List.copyOf(result);
    }

    private static String orderKey(String a, String b) {
        return (a.compareTo(b) <= 0) ? (a + "||" + b) : (b + "||" + a);
    }
}
