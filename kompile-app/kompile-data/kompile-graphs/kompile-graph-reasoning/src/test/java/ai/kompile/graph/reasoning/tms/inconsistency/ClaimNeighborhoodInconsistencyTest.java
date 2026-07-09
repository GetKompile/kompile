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
 * Unit tests for {@link ClaimNeighborhoodInconsistency}.
 *
 * <p>Verifies neighbourhood filtering, report field population, and the E15 spec scenarios.</p>
 */
class ClaimNeighborhoodInconsistencyTest {

    private static Fact hard(String atomKey, double value) {
        return new Fact(atomKey, value, "test", Instant.now(), true);
    }

    // ── E15-spec: neighbourhood filter pulls in related facts ─────────────────────────────

    @Test
    @DisplayName("E15-spec: claim worksAt(alice,acme) pulls in facts mentioning alice, acme, or worksAt")
    void neighbourhoodFilterIncludesRelated() {
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),           // same predicate
                hard("NOT_worksAt(alice, acme)", 0.9),        // negated form, same predicate → IN
                hard("worksAt(bob, beta)", 0.9),              // same predicate → IN
                hard("CEO(acme, carol)", 0.9),                // mentions 'acme' (arg) → IN
                hard("livesIn(alice, london)", 0.9),          // mentions 'alice' (arg) → IN
                hard("State(zeus)", 0.9),                     // unrelated → OUT
                hard("NOT_CEO(acme, carol)", 0.9)             // mentions 'acme' → IN
        );

        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);

        // The neighbourhood includes:
        // worksAt(alice,acme), NOT_worksAt(alice,acme) → conflict: claim is contested
        assertTrue(report.claimIsContested(),
                "Claim worksAt(alice,acme) should be contested: its negation is in the neighbourhood");

        // bCount should be at least 1 (the worksAt(alice,acme) conflict)
        assertTrue(report.bCount() >= 1, "At least one B-atom in the neighbourhood");

        // conflictPairDescriptions should be non-empty
        assertFalse(report.conflictPairDescriptions().isEmpty(),
                "Conflict pairs should be described");

        // minRepair should be non-empty
        assertFalse(report.minRepair().isEmpty(), "Minimum repair should not be empty");
    }

    @Test
    @DisplayName("E15-spec: unrelated facts (no predicate or arg match) are excluded from neighbourhood")
    void unrelatedFactsExcluded() {
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("CEO(zeus, mars)", 0.9),         // neither worksAt, alice, nor acme
                hard("State(jupiter)", 0.9)            // completely unrelated
        );

        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);

        // No conflicts in the neighbourhood (unrelated facts excluded)
        assertFalse(report.hasInconsistency(),
                "No conflicts expected when unrelated facts are excluded");
        assertFalse(report.claimIsContested(), "Claim itself has no conflicting partner");
    }

    // ── Report fields populated correctly ─────────────────────────────────────────────────

    @Test
    @DisplayName("Report claimAtom field matches the input claim atom key")
    void reportClaimAtomField() {
        String claim = "worksAt(alice, acme)";
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                claim, List.of(hard(claim, 0.9)), 1);
        assertEquals(claim, report.claimAtom());
    }

    @Test
    @DisplayName("Report contension in [0,1]")
    void reportContensionRange() {
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)",
                List.of(
                        hard("worksAt(alice, acme)", 0.9),
                        hard("NOT_worksAt(alice, acme)", 0.9)
                ), 1);
        assertTrue(report.contension() >= 0.0 && report.contension() <= 1.0,
                "Contension must be in [0,1], got: " + report.contension());
    }

    @Test
    @DisplayName("Report bCount matches MarkingResult.bCount for neighbourhood")
    void reportBCountMatchesMarking() {
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("NOT_worksAt(alice, acme)", 0.9),
                hard("worksAt(bob, acme)", 0.9)      // same predicate, no conflict
        );
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);

        // At minimum: worksAt(alice,acme) vs NOT_worksAt(alice,acme) → bCount ≥ 1
        assertTrue(report.bCount() >= 1);
    }

    // ── No conflicts → clean report ───────────────────────────────────────────────────────

    @Test
    @DisplayName("No conflicts in neighbourhood → clean report with all-zero measures")
    void noConflictsCleanReport() {
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("worksAt(alice, beta)", 0.9),  // worksAt is not a functional predicate by default
                hard("CEO(acme, carol)", 0.8)
        );
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);

        // worksAt is not a default functional predicate, so no functional clash
        // No negated pairs for worksAt
        assertFalse(report.hasInconsistency(), "No inconsistency expected");
        assertEquals(0, report.blameOfClaim(), "Claim has no blame");
        assertTrue(report.conflictPairDescriptions().isEmpty(), "No conflict descriptions");
        assertTrue(report.minRepair().isEmpty(), "No repair needed");
        assertEquals(0.0, report.contension(), 1e-10, "Contension should be 0.0");
    }

    // ── Empty fact collection ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty fact collection → report with zero everything")
    void emptyFacts() {
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", List.of(), 1);
        assertFalse(report.hasInconsistency());
        assertEquals(0, report.bCount());
        assertEquals(0.0, report.contension(), 1e-10);
        assertEquals(0, report.blameOfClaim());
        assertTrue(report.conflictPairDescriptions().isEmpty());
        assertTrue(report.minRepair().isEmpty());
    }

    // ── Functional predicate clash in neighbourhood ───────────────────────────────────────

    @Test
    @DisplayName("Functional-predicate clash in neighbourhood surfaces in the report")
    void functionalClashInNeighbourhood() {
        List<Fact> allFacts = List.of(
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9),
                hard("worksAt(alice, acme)", 0.9)   // alice is an arg → pulled into neighbourhood
        );
        // Claim: CEO(acme, alice)
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "CEO(acme, alice)", allFacts, 1);

        // CEO(acme,alice) vs CEO(acme,bob) → functional clash
        assertTrue(report.hasInconsistency(),
                "Functional CEO clash should produce inconsistency");
        assertTrue(report.bCount() >= 1);
        // The claim's canonical key is CEO|[acme, alice] → it participates in the clash
        assertTrue(report.claimIsContested(),
                "The claim atom CEO(acme,alice) is in the functional clash → contested");
    }

    // ── blameOfClaim when claim is not directly contested ─────────────────────────────────

    @Test
    @DisplayName("blameOfClaim=0 when neighbourhood has conflicts but not involving the claim atom")
    void blameZeroWhenClaimNotContested() {
        // Claim: worksAt(alice, acme)
        // Neighbourhood: CEO(acme, alice) vs CEO(acme, bob) [functional clash — acme is an arg]
        // worksAt(alice, acme) itself has no conflict partner
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("CEO(acme, alice)", 0.9),
                hard("CEO(acme, bob)", 0.9)
        );
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);

        // neighbourhood includes CEO(acme,alice) and CEO(acme,bob) because acme/alice are args
        // → there IS inconsistency in neighbourhood
        assertTrue(report.hasInconsistency());
        // But worksAt(alice,acme) canonical key = WORKSAT|[alice, acme] is not in any pair
        assertEquals(0, report.blameOfClaim(),
                "The claim worksAt(alice,acme) is not in the functional CEO clash → blame=0");
        assertFalse(report.claimIsContested());
    }

    // ── Radius parameter is reserved ──────────────────────────────────────────────────────

    @Test
    @DisplayName("radius parameter does not affect filtering (reserved / ignored)")
    void radiusParameterIgnored() {
        List<Fact> allFacts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("NOT_worksAt(alice, acme)", 0.9)
        );
        InconsistencyReport r0 = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 0);
        InconsistencyReport r1 = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 1);
        InconsistencyReport r5 = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", allFacts, 5);

        assertEquals(r0.bCount(), r1.bCount(), "radius=0 and radius=1 should give identical results");
        assertEquals(r1.bCount(), r5.bCount(), "radius=1 and radius=5 should give identical results");
    }

    // ── toString sanity check ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("InconsistencyReport.toString does not throw")
    void toStringNoThrow() {
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)",
                List.of(
                        hard("worksAt(alice, acme)", 0.9),
                        hard("NOT_worksAt(alice, acme)", 0.9)
                ), 1);
        String s = assertDoesNotThrow(report::toString);
        assertTrue(s.contains("worksAt(alice, acme)"),
                "toString should include the claim atom");
    }

    // ── NullPointerException on null claim ────────────────────────────────────────────────

    @Test
    @DisplayName("Null claim atom key → NullPointerException")
    void nullClaimThrows() {
        assertThrows(NullPointerException.class, () ->
                ClaimNeighborhoodInconsistency.assess(null, List.of(), 1));
    }

    // ── hasInconsistency / claimIsContested helpers ───────────────────────────────────────

    @Test
    @DisplayName("hasInconsistency and claimIsContested convenience methods consistent with fields")
    void convenienceMethodsConsistent() {
        List<Fact> facts = List.of(
                hard("worksAt(alice, acme)", 0.9),
                hard("NOT_worksAt(alice, acme)", 0.9)
        );
        InconsistencyReport report = ClaimNeighborhoodInconsistency.assess(
                "worksAt(alice, acme)", facts, 1);

        assertEquals(report.bCount() > 0, report.hasInconsistency());
        assertEquals(report.blameOfClaim() > 0, report.claimIsContested());
    }
}
