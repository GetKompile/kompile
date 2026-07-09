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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BelnapMarking}.
 *
 * <p>Verifies the Belnap 4-valued marking algorithm against the specified scenarios from
 * the E15 implementation spec. All tests are pure in-memory, no infrastructure required.</p>
 */
class BelnapMarkingTest {

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private static Fact hard(String atomKey, double value) {
        return new Fact(atomKey, value, "test", Instant.now(), true);
    }

    private static Fact soft(String atomKey, double value) {
        return new Fact(atomKey, value, "test", Instant.now(), false);
    }

    // ── E15 spec scenario 1: {p, ~p, q} ─────────────────────────────────────────────────

    @Test
    @DisplayName("E15-spec: {p, ~p, q} → p marked B, q marked T, bCount=1")
    void negatedPairPlusUnrelated() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.9),
                hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);

        // p(x) canonicalKey: P|[x]
        // NOT_p(x) parses to predicate=P, negated=true, same canonicalKey
        // Both ≥0.7 → both present → mark B
        assertEquals(Mark.B, result.markFor("P|[x]"),
                "p(x) / NOT_p(x) pair: both sides ≥0.7 → should be B");
        assertEquals(Mark.T, result.markFor("Q|[y]"),
                "q(y): only positive evidence → should be T");
        assertEquals(1, result.bCount(), "Only one atom marked B");
    }

    @Test
    @DisplayName("E15-spec: {p, ~p, q} → contensionLike = 1/2 (1 B out of 2 with strong evidence)")
    void contensionOnePairOneUnrelated() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.9),
                hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);

        // Strong-evidence atoms: P|[x] (B) and Q|[y] (T) → strongEvidenceCount=2
        // contensionLike = 1 B / 2 strong = 0.5
        assertEquals(2, result.strongEvidenceCount());
        double contension = InconsistencyMeasures.contensionLike(result);
        assertEquals(0.5, contension, 1e-10, "contensionLike must be 1/2 for 1 B out of 2 strong atoms");
    }

    // ── Functional-predicate clash ────────────────────────────────────────────────────────

    @Test
    @DisplayName("E15-spec: CEO(acme,alice) + CEO(acme,bob) both 0.9 → both atoms B, miCount=1")
    void functionalClashCeo() {
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);

        // Both are non-negated, CEO is functional, same first arg (acme), different full args
        // → both atoms should be marked B (synthetic conflict)
        assertEquals(Mark.B, result.markFor("CEO|[acme, alice]"),
                "CEO(acme,alice) should be B (functional clash)");
        assertEquals(Mark.B, result.markFor("CEO|[acme, bob]"),
                "CEO(acme,bob) should be B (functional clash)");
        assertEquals(2, result.bCount(), "Both atoms in the functional clash should be B");
    }

    @Test
    @DisplayName("Functional clash miCount=1 via conflict pairs")
    void functionalClashMiCount() {
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                facts, BelnapMarking.EVIDENCE_THRESHOLD);
        assertEquals(1, pairs.size(), "Exactly one conflict pair for 2-value functional clash");
        assertEquals(1, InconsistencyMeasures.miCount(pairs), "miCount = 1 for a single functional clash");
    }

    // ── {p,~p,q,~q} → contensionLike=1.0 ────────────────────────────────────────────────

    @Test
    @DisplayName("E15-spec: {p,~p,q,~q} → contensionLike=1.0")
    void twoNegatedPairsContension() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.9),
                hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9),
                hard("NOT_q(y)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);

        assertEquals(2, result.bCount(), "Both p and q should be B");
        assertEquals(2, result.strongEvidenceCount(), "Both atoms have strong evidence");
        double contension = InconsistencyMeasures.contensionLike(result);
        assertEquals(1.0, contension, 1e-10, "All strongly-evidenced atoms are B → contension=1.0");
    }

    // ── minRepairApprox size=2 for {p,~p,q,~q} ───────────────────────────────────────────

    @Test
    @DisplayName("E15-spec: {p,~p,q,~q} → minRepairApprox size=2")
    void twoNegatedPairsMinRepairSize() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.9),
                hard("NOT_p(x)", 0.9),
                hard("q(y)", 0.9),
                hard("NOT_q(y)", 0.9)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                facts, BelnapMarking.EVIDENCE_THRESHOLD);
        assertEquals(2, pairs.size(), "Two conflict pairs (one per negated pair)");

        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);
        assertEquals(2, repair.size(),
                "Two independent conflict pairs need 2 repairs (each pair disjoint)");
    }

    // ── Blame: fact in two conflict pairs has blame 2 ─────────────────────────────────────

    @Test
    @DisplayName("E15-spec: fact in 2 conflict pairs has blame=2 and is picked first by greedy repair")
    void blameAndGreedyPivot() {
        // Construct: p vs ~p AND p vs ~q (where p appears in both pairs by being the
        // canonical key for two conflicts).
        // Simplest construction: atom A conflicts with B and also with C.
        // Use: CEO(acme, alice) vs CEO(acme, bob) AND CEO(acme, alice) vs CEO(acme, carol)
        // → 3 facts for same functional predicate+subject, 3 conflict pairs (C(3,2)=3)
        // → CEO|[acme, alice] appears in 2 of those pairs (vs bob and vs carol)
        //   CEO|[acme, bob] appears in 2 (vs alice and vs carol)
        //   CEO|[acme, carol] appears in 2 (vs alice and vs bob)
        // All have equal blame=2. Instead use a direct negated-pair scenario where one
        // canonical key appears in 2 pairs:
        // Pair1: P|[x] (pos) ↔ P|[x] (neg)
        // Pair2: Q|[x] also involves P|[x]? No — the canonical keys differ.
        // Best approach: P appears in one negated pair, and also in one functional clash.
        // We need CEO to be in the negated-pair too. Use STATUS (default functional predicate).

        // STATUS(x, a) ← negated pair with NOT_STATUS(x, a) → canonical STATUS|[x, a] → B
        // STATUS(x, a) ← functional clash with STATUS(x, b) → canonical STATUS|[x, a] appears again
        // Pair1: STATUS|[x, a] ↔ STATUS|[x, a] (negated — actually this is the same canonical key in both sides,
        //        but the negated pair is (STATUS|[x,a], NOT_STATUS|[x,a]) — the canonical keys ARE the same for both sides
        //        since NOT_STATUS strips the prefix.
        // The ConflictPair.canonicalKeyA and canonicalKeyB will be the same string in a negated-pair conflict!
        // That's correct — both sides map to the same canonical key.
        // Then the functional clash pair: STATUS|[x, a] ↔ STATUS|[x, b] — two distinct canonical keys.
        // So STATUS|[x, a] appears in pair1 (as both A and B) and pair2 (as A).
        // blame("STATUS|[x, a]", pairs) counts pairs where it's either A or B.
        // pair1: A = STATUS|[x, a], B = STATUS|[x, a] → contains it
        // pair2: A = STATUS|[x, a], B = STATUS|[x, b] → contains it
        // → blame = 2

        List<Fact> facts = List.of(
                hard("STATUS(x, a)", 0.9),      // positive
                hard("NOT_STATUS(x, a)", 0.9),  // negation of STATUS(x, a)
                hard("STATUS(x, b)", 0.9)        // functional clash with STATUS(x, a)
        );
        List<ConflictPair> pairs = InconsistencyMeasures.buildConflictPairs(
                facts, BelnapMarking.EVIDENCE_THRESHOLD);

        // Should have at least 2 pairs: negated pair + functional clash
        assertTrue(pairs.size() >= 2, "Expected at least 2 conflict pairs, got: " + pairs.size());

        String targetKey = "STATUS|[x, a]";
        int b = InconsistencyMeasures.blame(targetKey, pairs);
        assertTrue(b >= 2, "STATUS|[x, a] should have blame ≥2 (in negated pair AND functional clash), got: " + b);

        // Greedy repair should pick STATUS|[x, a] first (highest frequency)
        List<String> repair = InconsistencyMeasures.minRepairApprox(pairs);
        assertFalse(repair.isEmpty(), "Repair set should not be empty");
        assertEquals(targetKey, repair.get(0),
                "STATUS|[x, a] has the highest blame so greedy picks it first");
    }

    // ── Weak evidence does NOT mark B ─────────────────────────────────────────────────────

    @Test
    @DisplayName("E15-spec: weak evidence (0.4 vs 0.9) does NOT mark B (threshold respected)")
    void weakEvidenceDoesNotMarkB() {
        List<Fact> facts = List.of(
                soft("p(x)", 0.4),          // below 0.7 threshold → not strong evidence
                hard("NOT_p(x)", 0.9)       // strong negated evidence
        );
        MarkingResult result = BelnapMarking.mark(facts);

        // Only the negative side is strong → should be F (refuted), not B
        assertEquals(Mark.F, result.markFor("P|[x]"),
                "Only negative side is strong (0.9); positive is weak (0.4) → mark F, not B");
        assertEquals(0, result.bCount(), "No atom should be B when positive evidence is weak");
    }

    @Test
    @DisplayName("Both below threshold → mark N (no strong evidence)")
    void bothBelowThresholdMarkN() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.5),
                hard("NOT_p(x)", 0.6)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        assertEquals(Mark.N, result.markFor("P|[x]"),
                "Both below 0.7 threshold → N (neither)");
        assertEquals(0, result.bCount());
    }

    // ── Negation form variants ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bang-prefix negation form: !p(x) ↔ p(x) → B")
    void bangPrefixNegation() {
        List<Fact> facts = List.of(
                hard("p(x)", 0.9),
                hard("!p(x)", 0.8)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        assertEquals(Mark.B, result.markFor("P|[x]"),
                "!p(x) should be parsed as negated form of P(x)");
        assertEquals(1, result.bCount());
    }

    @Test
    @DisplayName("'not ' space-prefix negation form → B")
    void notSpacePrefixNegation() {
        List<Fact> facts = List.of(
                hard("State(alice)", 0.9),
                hard("not State(alice)", 0.85)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        assertEquals(Mark.B, result.markFor("STATE|[alice]"),
                "'not State(alice)' should be treated as negated");
        assertEquals(1, result.bCount());
    }

    @Test
    @DisplayName("NO_ prefix negation form → B")
    void noPrefixNegation() {
        List<Fact> facts = List.of(
                hard("State(alice)", 0.9),
                hard("NO_State(alice)", 0.85)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        assertEquals(Mark.B, result.markFor("STATE|[alice]"),
                "NO_State prefix should be treated as negated");
    }

    // ── Only positive evidence → T ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single strong positive fact → mark T")
    void singlePositiveFact() {
        MarkingResult result = BelnapMarking.mark(List.of(hard("CEO(acme, alice)", 0.9)));
        assertEquals(Mark.T, result.markFor("CEO|[acme, alice]"));
        assertEquals(0, result.bCount());
    }

    // ── Only negated evidence → F ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single strong negated fact → mark F")
    void singleNegatedFact() {
        MarkingResult result = BelnapMarking.mark(List.of(hard("NOT_State(alice)", 0.9)));
        assertEquals(Mark.F, result.markFor("STATE|[alice]"));
        assertEquals(0, result.bCount());
    }

    // ── Empty input ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty fact collection → empty result, bCount=0")
    void emptyInput() {
        MarkingResult result = BelnapMarking.mark(List.of());
        assertTrue(result.byCanonicalAtom().isEmpty());
        assertEquals(0, result.bCount());
        assertEquals(0, result.strongEvidenceCount());
    }

    @Test
    @DisplayName("Null collection → empty result")
    void nullInput() {
        MarkingResult result = BelnapMarking.mark(null);
        assertTrue(result.byCanonicalAtom().isEmpty());
        assertEquals(0, result.bCount());
    }

    // ── markMap entry point ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markMap: key→value map is equivalent to Fact-based marking")
    void markMapEntryPoint() {
        Map<String, Double> atomValues = Map.of(
                "State(alice)", 0.9,
                "NOT_State(alice)", 0.8
        );
        MarkingResult result = BelnapMarking.markMap(atomValues, BelnapMarking.EVIDENCE_THRESHOLD);
        assertEquals(Mark.B, result.markFor("STATE|[alice]"));
        assertEquals(1, result.bCount());
    }

    // ── Functional clash: different first arg does NOT clash ─────────────────────────────

    @Test
    @DisplayName("CEO(acme,alice) vs CEO(beta,alice) — different subject, not a functional clash")
    void functionalDifferentSubjectNoClash() {
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(beta, alice)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        // Different first args → no functional clash
        assertEquals(Mark.T, result.markFor("CEO|[acme, alice]"),
                "CEO(acme,alice) has no conflicting partner → T");
        assertEquals(Mark.T, result.markFor("CEO|[beta, alice]"),
                "CEO(beta,alice) has no conflicting partner → T");
        assertEquals(0, result.bCount());
    }

    // ── Functional clash: same args does NOT clash ────────────────────────────────────────

    @Test
    @DisplayName("CEO(acme,alice) vs CEO(acme,alice) — same args, no clash")
    void functionalSameArgsNoClash() {
        List<Fact> facts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, alice)", 0.9)
        );
        MarkingResult result = BelnapMarking.mark(facts);
        // Same args → not a conflict (idempotent assertion)
        assertNotEquals(Mark.B, result.markFor("CEO|[acme, alice]"),
                "Same args = same fact, not a clash");
        assertEquals(0, result.bCount());
    }

    // ── ParsedAtom canonical key ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ParsedAtom.parse: positive and negated form share the same canonicalKey")
    void parsedAtomSharedCanonicalKey() {
        BelnapMarking.ParsedAtom pos = BelnapMarking.ParsedAtom.parse("CEO(acme, alice)");
        BelnapMarking.ParsedAtom neg = BelnapMarking.ParsedAtom.parse("NOT_CEO(acme, alice)");

        assertFalse(pos.negated(), "Positive form should not be negated");
        assertTrue(neg.negated(), "NOT_ prefix form should be negated");
        assertEquals(pos.canonicalKey(), neg.canonicalKey(),
                "Both positive and negated forms must share the same canonicalKey");
    }

    @Test
    @DisplayName("ParsedAtom.parse: DISPROVES_ prefix is recognised")
    void disprovesPrefix() {
        BelnapMarking.ParsedAtom pa = BelnapMarking.ParsedAtom.parse("DISPROVES_State(alice)");
        assertTrue(pa.negated(), "DISPROVES_ prefix should mark atom as negated");
        assertEquals("STATE", pa.predicate(), "Predicate after stripping DISPROVES_ prefix");
    }
}
