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
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WhyNotExplainer} — E9 why-not / near-miss explanation.
 *
 * <p>All tests are infra-free (no Spring, no JPA). Domain: employment/location.</p>
 *
 * <p>Provenance: Herschel &amp; Hernández (PVLDB 2010), Lee et al. PUG (VLDB 2017).</p>
 */
@DisplayName("WhyNotExplainer — E9 near-miss explanation")
class WhyNotExplainerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private InMemoryInferredFactStore inferredStore;
    private FactStore factStore;

    @BeforeEach
    void setUp() {
        inferredStore = new InMemoryInferredFactStore();
        factStore = new FactStore();
    }

    /** Assert a hard observed fact (value 1.0) into the factStore. */
    private void observeFact(String atomKey) {
        factStore.assertFact(new Fact(atomKey, 1.0, "test", Instant.now(), true));
    }

    /** Store an inferred fact (confidence 0.9) into the inferredStore. */
    private void inferFact(String atomKey) {
        inferredStore.store(InferredFact.of(atomKey, 0.9, List.of(atomKey), List.of(), "run-1", 0));
    }

    /**
     * Build a DatalogRule: basedIn(?x, ?z) :- worksAt(?x, ?y), locatedIn(?y, ?z)
     */
    private static RecursiveQueryEngine.DatalogRule transitivityRule() {
        return new RecursiveQueryEngine.DatalogRule(
                "basedIn",
                List.of("?x", "?z"),
                List.of(
                        RecursiveQueryEngine.RuleAtom.pos("worksAt", "?x", "?y"),
                        RecursiveQueryEngine.RuleAtom.pos("locatedIn", "?y", "?z")
                )
        );
    }

    /**
     * Build a second rule: basedIn(?x, ?z) :- livesAt(?x, ?z)
     */
    private static RecursiveQueryEngine.DatalogRule directResidenceRule() {
        return new RecursiveQueryEngine.DatalogRule(
                "basedIn",
                List.of("?x", "?z"),
                List.of(
                        RecursiveQueryEngine.RuleAtom.pos("livesAt", "?x", "?z")
                )
        );
    }

    // ── Test 1: Transitivity scenario ────────────────────────────────────────────

    @Nested
    @DisplayName("Transitivity scenario")
    class TransitivityScenario {

        /**
         * Rule: basedIn(?x, ?z) :- worksAt(?x, ?y) & locatedIn(?y, ?z)
         * Facts: worksAt(alice, acme) is present; locatedIn(acme, london) is ABSENT.
         * Claim: basedIn(alice, london) → UNKNOWN with one near-miss.
         */
        @Test
        @DisplayName("missing one body atom produces completing-fact suggestion")
        void missingOneBodyAtom_producesCompletingFact() {
            // Arrange
            observeFact("worksAt(alice, acme)");
            // locatedIn(acme, london) is intentionally absent

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);

            // Act
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            // Assert
            assertFalse(report.isEmpty(), "Should have at least one near-miss");
            WhyNotExplainer.NearMiss miss = report.nearMisses().get(0);

            assertEquals(1, miss.missingAtoms().size(),
                    "Exactly one body atom should be missing");
            assertEquals(1, miss.satisfiedAtoms().size(),
                    "Exactly one body atom should be satisfied");

            // The satisfied atom should be the worksAt fact
            assertTrue(miss.satisfiedAtoms().stream().anyMatch(s -> s.contains("worksAt")),
                    "worksAt(alice, acme) should be satisfied: " + miss.satisfiedAtoms());

            // The missing atom should be locatedIn(acme, london)
            String missingAtom = miss.missingAtoms().get(0);
            assertTrue(missingAtom.contains("locatedIn"),
                    "locatedIn should be missing: " + missingAtom);
            assertTrue(missingAtom.contains("acme"),
                    "Missing atom should reference acme: " + missingAtom);
            assertTrue(missingAtom.contains("london"),
                    "Missing atom should reference london: " + missingAtom);

            // The completing fact should be fully ground
            assertNotNull(miss.completingFact(),
                    "completingFact should be non-null when exactly one atom is missing and fully ground");
            assertTrue(miss.completingFact().contains("locatedIn"),
                    "completingFact should be the locatedIn atom: " + miss.completingFact());
            assertFalse(miss.completingFact().contains("?"),
                    "completingFact must be fully ground (no variables)");

            // Suggestions should contain the completing fact
            assertFalse(report.suggestions().isEmpty(), "Suggestions should not be empty");
            assertTrue(report.suggestions().contains(miss.completingFact()),
                    "Suggestions should include the completing fact");

            // Closeness: 1 satisfied / 2 total = 0.5
            assertEquals(0.5, miss.closeness(), 0.001, "Closeness should be 0.5");
        }

        @Test
        @DisplayName("claim atom string is preserved in the report")
        void claimAtomPreservedInReport() {
            String claim = "basedIn(alice, london)";
            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain(claim);
            assertEquals(claim, report.claimAtom());
        }
    }

    // ── Test 2: Two candidate rules sorted by fewest missing ─────────────────────

    @Nested
    @DisplayName("Two candidate rules — sorted by fewest missing")
    class TwoRulesSorting {

        /**
         * Rule 1: basedIn(?x, ?z) :- worksAt(?x, ?y), locatedIn(?y, ?z)  — 2 body atoms
         * Rule 2: basedIn(?x, ?z) :- livesAt(?x, ?z)                      — 1 body atom
         *
         * Facts: none present. Claim: basedIn(alice, london).
         * With no facts, rule2 should produce a 1-missing miss, rule1 a 2-missing miss.
         * After sorting: rule2 first (fewest missing).
         */
        @Test
        @DisplayName("rule with fewest missing comes first in sorted output")
        void sortsByFewestMissingAtoms() {
            // No facts observed — both rules will have all atoms missing
            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule()),    // 2 body atoms
                    WhyNotExplainer.fromDatalogRule(directResidenceRule())  // 1 body atom
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            // Both rules matched; sorted by missing count ascending
            assertFalse(report.nearMisses().isEmpty());
            List<WhyNotExplainer.NearMiss> misses = report.nearMisses();
            // At least 2 near-miss entries
            assertTrue(misses.size() >= 2, "Should have near-miss from each rule");

            // First near-miss should have the fewest missing atoms
            int firstMissing = misses.get(0).missingAtoms().size();
            for (int i = 1; i < misses.size(); i++) {
                assertTrue(misses.get(i).missingAtoms().size() >= firstMissing,
                        "Near-misses should be sorted: entry[0] has fewest missing atoms");
            }

            // The rule with 1 body atom should be at the front (1 missing < 2 missing)
            // when no facts are present
            assertEquals(1, misses.get(0).missingAtoms().size(),
                    "First miss should have 1 missing atom (single-body rule)");
        }

        @Test
        @DisplayName("when first rule fully satisfied, it sorts before a partial match")
        void fullySatisfiedBodySortsFirst() {
            // worksAt + locatedIn both present → transitivity rule fully satisfied
            observeFact("worksAt(alice, acme)");
            observeFact("locatedIn(acme, london)");
            // livesAt absent → directResidence has 1 missing

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule()),
                    WhyNotExplainer.fromDatalogRule(directResidenceRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            assertFalse(report.nearMisses().isEmpty());
            // The first near-miss should be the one with 0 missing (full satisfaction)
            WhyNotExplainer.NearMiss first = report.nearMisses().get(0);
            assertEquals(0, first.missingAtoms().size(),
                    "Fully-satisfied rule body should have 0 missing atoms");
            assertEquals(1.0, first.closeness(), 0.001,
                    "Fully-satisfied rule body should have closeness=1.0");
        }
    }

    // ── Test 3: Fully-satisfied body (stale materialization signal) ───────────────

    @Nested
    @DisplayName("Fully-satisfied body — stale materialization signal")
    class FullySatisfiedBody {

        /**
         * Rule: basedIn(?x, ?z) :- worksAt(?x, ?y) & locatedIn(?y, ?z)
         * ALL body atoms present in stores, but basedIn(alice, london) NOT in inferredStore.
         * This signals stale materialization (the fixpoint wasn't re-run).
         */
        @Test
        @DisplayName("all body atoms present → NearMiss with zero missing and closeness=1.0")
        void allBodyAtomsSatisfied_reportsStaleMaterialization() {
            observeFact("worksAt(alice, acme)");
            observeFact("locatedIn(acme, london)");
            // basedIn(alice, london) deliberately NOT in inferredStore

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            assertFalse(report.isEmpty(), "Should detect a near-miss even when all body atoms satisfied");
            WhyNotExplainer.NearMiss miss = report.nearMisses().get(0);

            assertEquals(0, miss.missingAtoms().size(),
                    "Zero missing atoms when body is fully satisfied");
            assertEquals(1.0, miss.closeness(), 0.001,
                    "Closeness=1.0 when body is fully satisfied");
            // Stale materialization: the claim IS derivable but wasn't materialized
            assertTrue(miss.satisfiedAtoms().size() == 2,
                    "Both body atoms should be in satisfied list");
            // completingFact is null when 0 atoms are missing (nothing to complete)
            assertNull(miss.completingFact(),
                    "completingFact should be null when no atoms are missing");
        }

        @Test
        @DisplayName("suggestions list is empty when no completing fact needed")
        void suggestions_emptyWhenNothingMissing() {
            observeFact("worksAt(alice, acme)");
            observeFact("locatedIn(acme, london)");

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            assertTrue(report.suggestions().isEmpty(),
                    "Suggestions should be empty when no completing fact is needed");
        }
    }

    // ── Test 4: Binding enumeration cap respected ─────────────────────────────────

    @Nested
    @DisplayName("Binding enumeration cap")
    class BindingCapTest {

        /**
         * Create many candidate facts that enumerate bindings for a free variable.
         * The cap should prevent blowup.
         */
        @Test
        @DisplayName("cap is respected — no blowup with >cap candidate facts")
        void capRespected_noBlowup() {
            // Rule: basedIn(?x, ?z) :- worksAt(?x, ?y) & locatedIn(?y, ?z)
            // Observe many worksAt facts for alice to generate many ?y bindings
            int n = 600; // > DEFAULT_MAX_BINDINGS_PER_RULE = 500
            for (int i = 0; i < n; i++) {
                observeFact("worksAt(alice, company" + i + ")");
            }
            // No locatedIn facts → all will be missing

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            // Use a tiny cap to confirm termination
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore,
                    10,  // maxBindingsPerRule = 10
                    8    // maxRules default
            );

            long start = System.currentTimeMillis();
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");
            long elapsed = System.currentTimeMillis() - start;

            // Should complete quickly (well under 2 seconds even in slow CI)
            assertTrue(elapsed < 2000, "WhyNotExplainer must complete within 2s with cap, took: " + elapsed + "ms");
            // A report is returned (may or may not have near-misses depending on which 10 are explored)
            assertNotNull(report);
        }
    }

    // ── Test 5: DefaultKbVerifier integration ─────────────────────────────────────

    @Nested
    @DisplayName("DefaultKbVerifier integration (E9 wiring)")
    class DefaultKbVerifierIntegration {

        @Test
        @DisplayName("UNKNOWN claim gets nearMissSuggestions when near-miss rule found")
        void unknownClaim_getsNearMissSuggestions() {
            // Arrange: worksAt present, locatedIn absent
            observeFact("worksAt(alice, acme)");

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );

            DefaultKbVerifier verifier = new DefaultKbVerifier(
                    inferredStore, factStore,
                    DefaultKbVerifier.DEFAULT_THRESHOLD,
                    java.util.Set.of(),
                    DefaultKbVerifier.DEFAULT_FUNCTIONAL_CONFLICT_THRESHOLD,
                    rules
            );

            // Act
            VerifyResult result = verifier.verify("basedIn(alice, london)");

            // Assert
            assertEquals(VerifyResult.Status.UNKNOWN, result.status());
            assertFalse(result.nearMissSuggestions().isEmpty(),
                    "UNKNOWN result should have nearMissSuggestions when near-miss found");
            assertTrue(result.nearMissSuggestions().stream()
                    .anyMatch(s -> s.contains("locatedIn")),
                    "Suggestions should include locatedIn atom: " + result.nearMissSuggestions());
        }

        @Test
        @DisplayName("SUPPORTED claim does NOT get nearMissSuggestions (explainer skipped)")
        void supportedClaim_noNearMissSuggestions() {
            // Arrange: basedIn(alice, london) is already materialized
            inferFact("basedIn(alice, london)");

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );

            DefaultKbVerifier verifier = new DefaultKbVerifier(
                    inferredStore, factStore,
                    DefaultKbVerifier.DEFAULT_THRESHOLD,
                    java.util.Set.of(),
                    DefaultKbVerifier.DEFAULT_FUNCTIONAL_CONFLICT_THRESHOLD,
                    rules
            );

            // Act
            VerifyResult result = verifier.verify("basedIn(alice, london)");

            // Assert: SUPPORTED, no near-miss suggestions
            assertEquals(VerifyResult.Status.SUPPORTED, result.status());
            assertTrue(result.nearMissSuggestions().isEmpty(),
                    "SUPPORTED result must not have nearMissSuggestions");
        }

        @Test
        @DisplayName("without rules, UNKNOWN result has empty nearMissSuggestions")
        void noRules_unknownHasEmptySuggestions() {
            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("basedIn(alice, london)");
            assertEquals(VerifyResult.Status.UNKNOWN, result.status());
            assertTrue(result.nearMissSuggestions().isEmpty(),
                    "Without rules, nearMissSuggestions should be empty");
        }
    }

    // ── Test 6: VerifyResult back-compat ─────────────────────────────────────────

    @Nested
    @DisplayName("VerifyResult back-compat constructors")
    class VerifyResultBackCompat {

        @Test
        @DisplayName("3-arg constructor produces empty nearMissSuggestions")
        void threeArgCtor_emptyNearMissSuggestions() {
            VerifyResult r = new VerifyResult(VerifyResult.Status.SUPPORTED, 0.9,
                    List.of("fact1"));
            assertTrue(r.nearMissSuggestions().isEmpty());
        }

        @Test
        @DisplayName("5-arg constructor produces empty nearMissSuggestions")
        void fiveArgCtor_emptyNearMissSuggestions() {
            VerifyResult r = new VerifyResult(VerifyResult.Status.REFUTED, 0.8,
                    List.of(), List.of("~foo"), "negated-atom");
            assertTrue(r.nearMissSuggestions().isEmpty());
        }

        @Test
        @DisplayName("unknown() factory produces empty nearMissSuggestions")
        void unknownFactory_emptyNearMissSuggestions() {
            VerifyResult r = VerifyResult.unknown();
            assertEquals(VerifyResult.Status.UNKNOWN, r.status());
            assertTrue(r.nearMissSuggestions().isEmpty());
        }

        @Test
        @DisplayName("unknownWithSuggestions factory carries suggestions")
        void unknownWithSuggestions_carriesSuggestions() {
            List<String> sugg = List.of("locatedIn(acme, london)");
            VerifyResult r = VerifyResult.unknownWithSuggestions(sugg);
            assertEquals(VerifyResult.Status.UNKNOWN, r.status());
            assertEquals(sugg, r.nearMissSuggestions());
        }

        @Test
        @DisplayName("null suggestions in unknownWithSuggestions treated as empty")
        void unknownWithSuggestions_nullSafe() {
            VerifyResult r = VerifyResult.unknownWithSuggestions(null);
            assertTrue(r.nearMissSuggestions().isEmpty());
        }
    }

    // ── Test 7: PSL rule adapter ─────────────────────────────────────────────────

    @Nested
    @DisplayName("PSL rule adapter")
    class PslRuleAdapter {

        @Test
        @DisplayName("fromPslRule produces valid RuleNf with normalized variable prefix")
        void fromPslRule_producesValidNf() {
            // Build a PSL rule manually: basedIn(X, Z) :- worksAt(X, Y) & locatedIn(Y, Z)
            ai.kompile.graph.reasoning.psl.PslAtom head =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("basedIn", false,
                            ai.kompile.graph.reasoning.psl.Term.var("X"),
                            ai.kompile.graph.reasoning.psl.Term.var("Z"));
            ai.kompile.graph.reasoning.psl.PslAtom bodyWorksAt =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("worksAt", false,
                            ai.kompile.graph.reasoning.psl.Term.var("X"),
                            ai.kompile.graph.reasoning.psl.Term.var("Y"));
            ai.kompile.graph.reasoning.psl.PslAtom bodyLocatedIn =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("locatedIn", false,
                            ai.kompile.graph.reasoning.psl.Term.var("Y"),
                            ai.kompile.graph.reasoning.psl.Term.var("Z"));

            ai.kompile.graph.reasoning.psl.PslRule pslRule =
                    ai.kompile.graph.reasoning.psl.PslRule.weighted(1.0, false,
                            List.of(bodyWorksAt, bodyLocatedIn),
                            List.of(head));

            WhyNotExplainer.RuleNf nf = WhyNotExplainer.fromPslRule(pslRule);
            assertNotNull(nf, "fromPslRule should return non-null for a valid rule");
            assertEquals("basedin", nf.headPredicate(), "headPredicate should be lowercase");
            assertEquals(2, nf.headArgs().size(), "Head should have 2 args");
            // All args should be "?"-prefixed variables
            for (String arg : nf.headArgs()) {
                assertTrue(arg.startsWith("?"), "Head arg should be a variable: " + arg);
            }
            assertEquals(2, nf.body().size(), "Body should have 2 atoms");
        }

        @Test
        @DisplayName("PSL-adapted rule can detect near-miss via WhyNotExplainer")
        void pslAdaptedRule_detectsNearMiss() {
            // Arrange: worksAt observed, locatedIn absent
            observeFact("worksAt(alice, acme)");

            // Build PSL rule for basedIn :- worksAt & locatedIn
            ai.kompile.graph.reasoning.psl.PslAtom head =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("basedIn", false,
                            ai.kompile.graph.reasoning.psl.Term.var("X"),
                            ai.kompile.graph.reasoning.psl.Term.var("Z"));
            ai.kompile.graph.reasoning.psl.PslAtom bodyWorksAt =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("worksAt", false,
                            ai.kompile.graph.reasoning.psl.Term.var("X"),
                            ai.kompile.graph.reasoning.psl.Term.var("Y"));
            ai.kompile.graph.reasoning.psl.PslAtom bodyLocatedIn =
                    ai.kompile.graph.reasoning.psl.PslAtom.of("locatedIn", false,
                            ai.kompile.graph.reasoning.psl.Term.var("Y"),
                            ai.kompile.graph.reasoning.psl.Term.var("Z"));
            ai.kompile.graph.reasoning.psl.PslRule pslRule =
                    ai.kompile.graph.reasoning.psl.PslRule.weighted(1.0, false,
                            List.of(bodyWorksAt, bodyLocatedIn), List.of(head));

            WhyNotExplainer.RuleNf nf = WhyNotExplainer.fromPslRule(pslRule);
            assertNotNull(nf);

            WhyNotExplainer explainer = new WhyNotExplainer(List.of(nf), inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            assertFalse(report.isEmpty(), "PSL-adapted rule should detect near-miss");
            WhyNotExplainer.NearMiss miss = report.nearMisses().get(0);
            assertEquals(1, miss.missingAtoms().size(),
                    "Exactly one atom should be missing");
            assertTrue(miss.missingAtoms().get(0).contains("locatedin") ||
                       miss.missingAtoms().get(0).contains("locatedIn"),
                    "Missing atom should be locatedIn: " + miss.missingAtoms());
        }
    }

    // ── Test 8: No matching rules ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("no matching rules → empty report")
        void noMatchingRules_emptyReport() {
            // Only a worksAt rule, but we're asking about basedIn
            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(new RecursiveQueryEngine.DatalogRule(
                            "worksAt", List.of("?x", "?y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("employed", "?x", "?y"))))
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");
            assertTrue(report.isEmpty(), "No matching rules → report should be empty");
            assertTrue(report.suggestions().isEmpty());
        }

        @Test
        @DisplayName("null rules list → empty report (no NPE)")
        void nullRulesList_emptyReport() {
            WhyNotExplainer explainer = new WhyNotExplainer(null, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");
            assertTrue(report.isEmpty());
        }

        @Test
        @DisplayName("empty rules list → empty report")
        void emptyRulesList_emptyReport() {
            WhyNotExplainer explainer = new WhyNotExplainer(List.of(), inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");
            assertTrue(report.isEmpty());
        }

        @Test
        @DisplayName("invalid atom key (no parentheses) → empty report")
        void invalidAtomKey_emptyReport() {
            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule()));
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("just-a-name");
            assertTrue(report.isEmpty(), "Invalid atom key should produce empty report");
        }

        @Test
        @DisplayName("inferred-store fact counts as satisfied (confidence ≥ 0.5)")
        void inferredStoreFact_countsSatisfied() {
            // Use inferredStore instead of factStore
            inferFact("worksAt(alice, acme)");
            // locatedIn absent

            List<WhyNotExplainer.RuleNf> rules = List.of(
                    WhyNotExplainer.fromDatalogRule(transitivityRule())
            );
            WhyNotExplainer explainer = new WhyNotExplainer(rules, inferredStore, factStore);
            WhyNotExplainer.WhyNotReport report = explainer.explain("basedIn(alice, london)");

            assertFalse(report.isEmpty());
            WhyNotExplainer.NearMiss miss = report.nearMisses().get(0);
            // worksAt should be satisfied (from inferredStore)
            assertEquals(1, miss.satisfiedAtoms().size());
            assertEquals(1, miss.missingAtoms().size());
        }
    }
}
