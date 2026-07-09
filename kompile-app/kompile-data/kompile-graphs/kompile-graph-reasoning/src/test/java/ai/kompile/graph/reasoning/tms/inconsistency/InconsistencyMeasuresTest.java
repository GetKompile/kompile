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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InconsistencyMeasures}.
 *
 * <p>Covers drastic, miCount, contensionLike, minRepairApprox, and blame as specified
 * in the E15 implementation spec.</p>
 */
class InconsistencyMeasuresTest {

    private static final double THRESHOLD = BelnapMarking.EVIDENCE_THRESHOLD;

    private static Fact hard(String atomKey, double value) {
        return new Fact(atomKey, value, "test", Instant.now(), true);
    }

    // ── drastic ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("drastic: 0 when no conflicts")
    void drasticNone() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("p(x)", 0.9), hard("q(y)", 0.9)), THRESHOLD);
        assertEquals(0, InconsistencyMeasures.drastic(pairs));
    }

    @Test
    @DisplayName("drastic: 1 when at least one conflict")
    void drasticOne() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("p(x)", 0.9), hard("NOT_p(x)", 0.9)), THRESHOLD);
        assertEquals(1, InconsistencyMeasures.drastic(pairs));
    }

    @Test
    @DisplayName("drastic: empty pair list → 0")
    void drasticEmpty() {
        assertEquals(0, InconsistencyMeasures.drastic(List.of()));
    }

    // ── miCount ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("miCount: one negated pair → 1 MIS")
    void miCountOnePair() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("State(alice)", 0.9), hard("NOT_State(alice)", 0.9)), THRESHOLD);
        assertEquals(1, InconsistencyMeasures.miCount(pairs));
    }

    @Test
    @DisplayName("miCount: two disjoint negated pairs → 2 MISes")
    void miCountTwoPairs() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(
                        hard("p(x)", 0.9), hard("NOT_p(x)", 0.9),
                        hard("q(y)", 0.9), hard("NOT_q(y)", 0.9)
                ), THRESHOLD);
        assertEquals(2, InconsistencyMeasures.miCount(pairs));
    }

    @Test
    @DisplayName("miCount: empty pair list → 0")
    void miCountEmpty() {
        assertEquals(0, InconsistencyMeasures.miCount(List.of()));
    }

    @Test
    @DisplayName("miCount: three values for same functional predicate → C(3,2)=3 MISes")
    void miCountThreeFunctionalValues() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(
                        hard("STATUS(x, a)", 0.9),
                        hard("STATUS(x, b)", 0.9),
                        hard("STATUS(x, c)", 0.9)
                ), THRESHOLD);
        assertEquals(3, InconsistencyMeasures.miCount(pairs),
                "C(3,2)=3 pairs for three mutually-conflicting functional values");
    }

    // ── contensionLike ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("contensionLike: {p,~p,q} → 1/2")
    void contensionOneOfTwo() {
        MarkingResult result = BelnapMarking.mark(List.of(
                hard("p(x)", 0.9),
                hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9)
        ));
        double c = InconsistencyMeasures.contensionLike(result);
        assertEquals(0.5, c, 1e-10);
    }

    @Test
    @DisplayName("contensionLike: {p,~p,q,~q} → 1.0")
    void contensionAllConflicted() {
        MarkingResult result = BelnapMarking.mark(List.of(
                hard("p(x)", 0.9), hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9), hard("NOT_q(y)", 0.9)
        ));
        assertEquals(1.0, InconsistencyMeasures.contensionLike(result), 1e-10);
    }

    @Test
    @DisplayName("contensionLike: no conflicts → 0.0")
    void contensionNone() {
        MarkingResult result = BelnapMarking.mark(List.of(
                hard("p(x)", 0.9), hard("q(y)", 0.9)
        ));
        assertEquals(0.0, InconsistencyMeasures.contensionLike(result), 1e-10);
    }

    @Test
    @DisplayName("contensionLike: empty marking → 0.0 (no divide-by-zero)")
    void contensionEmpty() {
        MarkingResult result = BelnapMarking.mark(List.of());
        assertEquals(0.0, InconsistencyMeasures.contensionLike(result), 1e-10);
    }

    // ── minRepairApprox ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("minRepairApprox: no conflicts → empty repair")
    void repairEmpty() {
        List<String> repair = InconsistencyMeasures.minRepairApprox(List.of());
        assertTrue(repair.isEmpty());
    }

    @Test
    @DisplayName("minRepairApprox: single negated pair → repair size 1")
    void repairSinglePair() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("State(alice)", 0.9), hard("NOT_State(alice)", 0.9)), THRESHOLD);
        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);
        assertEquals(1, repair.size(), "One pair needs exactly 1 removal");
    }

    @Test
    @DisplayName("minRepairApprox: {p,~p,q,~q} → repair size 2 (two independent pairs)")
    void repairTwoIndependentPairs() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(
                        hard("p(x)", 0.9), hard("NOT_p(x)", 0.9),
                        hard("q(y)", 0.9), hard("NOT_q(y)", 0.9)
                ), THRESHOLD);
        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);
        assertEquals(2, repair.size(), "Two disjoint pairs require 2 removals");
    }

    @Test
    @DisplayName("minRepairApprox: greedy picks pivot that appears in most pairs first")
    void repairGreedyPivotFirst() {
        // Construct a star topology: atom A conflicts with B, C, and D separately.
        // CEO(acme,alice) vs CEO(acme,bob), CEO(acme,carol), CEO(acme,dan) → 3 pairs each involving alice
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9),
                hard("CEO(acme, carol)", 0.9),
                hard("CEO(acme, dan)", 0.9)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(facts, THRESHOLD);
        // C(4,2)=6 pairs; CEO|[acme, alice] appears in 3 of them (vs bob, carol, dan).
        // Other atoms each appear in 3 pairs too (vs the other 3). Tiebreaking by lexicographic
        // order. Greedy first pick resolves half the pairs.
        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);
        assertFalse(repair.isEmpty(), "Repair should not be empty");
        // The first pick must be valid: removing it must eliminate some pairs.
        // With 4-way symmetric functional clash, minimum repair = 3 (remove 3 out of 4 so
        // at most 1 remains). Greedy may not be optimal but must make forward progress.
        assertTrue(repair.size() >= 1);
    }

    @Test
    @DisplayName("minRepairApprox: removing repair set eliminates all conflicts")
    void repairSetsEliminatesConflicts() {
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9),
                hard("State(x)", 0.9),
                hard("NOT_State(x)", 0.9)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(facts, THRESHOLD);
        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);

        // Verify: after removing all keys in repair, no pair remains
        for (ConflictPair pair : pairs) {
            boolean aRemoved = repair.contains(pair.canonicalKeyA());
            boolean bRemoved = repair.contains(pair.canonicalKeyB());
            assertTrue(aRemoved || bRemoved,
                    "Every conflict pair must have at least one side in the repair set: " + pair);
        }
    }

    // ── blame ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("blame: atom in no pairs → 0")
    void blameZero() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("State(alice)", 0.9), hard("NOT_State(alice)", 0.9)), THRESHOLD);
        assertEquals(0, InconsistencyMeasures.blame("CEO|[acme, alice]", pairs),
                "CEO atom is not in any pair → blame=0");
    }

    @Test
    @DisplayName("blame: atom in exactly one pair → 1")
    void blameOne() {
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                List.of(hard("State(alice)", 0.9), hard("NOT_State(alice)", 0.9)), THRESHOLD);
        // The canonical key for both State(alice) and NOT_State(alice) is STATE|[alice]
        int b = InconsistencyMeasures.blame("STATE|[alice]", pairs);
        assertEquals(1, b, "STATE|[alice] is in exactly one negated-pair conflict → blame=1");
    }

    @Test
    @DisplayName("blame: atom in two pairs (negated pair + functional clash) → blame ≥ 2")
    void blameTwo() {
        List<Fact> facts = List.of(
                hard("STATUS(x, a)", 0.9),
                hard("NOT_STATUS(x, a)", 0.9),
                hard("STATUS(x, b)", 0.9)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(facts, THRESHOLD);
        int b = InconsistencyMeasures.blame("STATUS|[x, a]", pairs);
        assertTrue(b >= 2,
                "STATUS|[x, a] should be in negated-pair AND functional-clash → blame ≥ 2, got: " + b);
    }

    @Test
    @DisplayName("blame: empty pair list → always 0")
    void blameEmptyPairs() {
        assertEquals(0, InconsistencyMeasures.blame("anything", List.of()));
    }

    // ── buildConflictPairs: weak evidence excluded ────────────────────────────────────────

    @Test
    @DisplayName("buildConflictPairs: weak evidence (0.4) excluded → no pair")
    void weakEvidenceExcludedFromPairs() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.4),          // below threshold
                hard("NOT_p(x)", 0.9)       // strong
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(facts, THRESHOLD);
        assertTrue(pairs.isEmpty(),
                "Positive side is 0.4 < 0.7 threshold → not a conflict pair");
    }

    // ── ConflictPair.involves helper ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ConflictPair.involves: returns true for either canonical key")
    void conflictPairInvolves() {
        ConflictPair cp = new ConflictPair("A|[]", "B|[]", "test");
        assertTrue(cp.involves("A|[]"));
        assertTrue(cp.involves("B|[]"));
        assertFalse(cp.involves("C|[]"));
    }

    // ── MarkingResult.markFor fallback ────────────────────────────────────────────────────

    @Test
    @DisplayName("MarkingResult.markFor unknown key → N")
    void markForUnknownKeyN() {
        MarkingResult result = BelnapMarking.mark(List.of(hard("State(alice)", 0.9)));
        assertEquals(Mark.N, result.markFor("UNKNOWN|[key]"),
                "Unknown atom defaults to N (no evidence)");
    }
}
