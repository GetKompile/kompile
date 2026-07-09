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
import ai.kompile.graph.reasoning.tms.ContradictionDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for E5 functional-constraint refutation, E4 counter-evidence, and the
 * {@link ContradictionDetector#defaultFunctionalPredicates()} / {@code isFunctionalPredicate}
 * public accessors added in WP-D.
 *
 * <p>All tests are infra-free (no Spring). Domain: employment with a functional {@code CEO}
 * predicate (single CEO per company) and a non-functional {@code worksAt} predicate
 * (multiple employees allowed).</p>
 */
@DisplayName("DefaultKbVerifier — E4/E5 refutation and counter-evidence")
class DefaultKbVerifierRefutationTest {

    private InMemoryInferredFactStore inferredStore;
    private FactStore factStore;

    @BeforeEach
    void setUp() {
        inferredStore = new InMemoryInferredFactStore();
        factStore = new FactStore();
    }

    // ─── ContradictionDetector accessors ────────────────────────────────────────

    @Nested
    @DisplayName("ContradictionDetector public accessors")
    class Accessors {

        @Test
        @DisplayName("defaultFunctionalPredicates() returns non-empty, immutable set")
        void defaultFunctionalPredicates_nonEmpty() {
            Set<String> fps = ContradictionDetector.defaultFunctionalPredicates();
            assertNotNull(fps);
            assertFalse(fps.isEmpty());
            // Spot-check a few expected entries (upper-case normalised)
            assertTrue(fps.contains("CEO"),         "CEO must be functional");
            assertTrue(fps.contains("STATUS"),      "STATUS must be functional");
            assertTrue(fps.contains("HEADQUARTERS"),"HEADQUARTERS must be functional");
        }

        @Test
        @DisplayName("isFunctionalPredicate recognises explicit member")
        void isFunctionalPredicate_explicitMember() {
            assertTrue(ContradictionDetector.isFunctionalPredicate("CEO"));
            assertTrue(ContradictionDetector.isFunctionalPredicate("ceo"));   // lower-case normalises
            assertTrue(ContradictionDetector.isFunctionalPredicate("Status"));
        }

        @Test
        @DisplayName("isFunctionalPredicate recognises CURRENT_ prefix rule")
        void isFunctionalPredicate_currentPrefix() {
            assertTrue(ContradictionDetector.isFunctionalPredicate("CURRENT_OWNER"));
            assertTrue(ContradictionDetector.isFunctionalPredicate("current_state"));
        }

        @Test
        @DisplayName("isFunctionalPredicate rejects non-functional predicate")
        void isFunctionalPredicate_nonFunctional() {
            assertFalse(ContradictionDetector.isFunctionalPredicate("worksAt"));
            assertFalse(ContradictionDetector.isFunctionalPredicate("knows"));
            assertFalse(ContradictionDetector.isFunctionalPredicate("relatedTo"));
        }

        @Test
        @DisplayName("isFunctionalPredicate handles null/blank gracefully")
        void isFunctionalPredicate_nullBlank() {
            assertFalse(ContradictionDetector.isFunctionalPredicate(null));
            assertFalse(ContradictionDetector.isFunctionalPredicate(""));
            assertFalse(ContradictionDetector.isFunctionalPredicate("   "));
        }
    }

    // ─── E5: functional-conflict REFUTED (via InferredFactStore) ─────────────────

    @Nested
    @DisplayName("E5: functional-conflict refutation via InferredFactStore")
    class FunctionalConflictInferred {

        @Test
        @DisplayName("REFUTED with functional-conflict basis when competing CEO fact exists in inferred store")
        void refuted_whenCompetingCeoInferredFact() {
            // Competing inferred fact: CEO(Acme, Bob) with high confidence
            inferredStore.store(InferredFact.of(
                    "CEO(Acme, Bob)", 0.9,
                    List.of(), List.of(), "run-1", 1L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            // Verify CEO(Acme, Alice) — NOT in the store but CEO is functional → should be refuted
            VerifyResult result = verifier.verify("CEO(Acme, Alice)");

            assertEquals(VerifyResult.Status.REFUTED, result.status(),
                    "functional predicate with competing inferred fact must be REFUTED");
            assertTrue(result.confidence() >= 0.7,
                    "confidence must be at least the competitor's confidence");
            assertFalse(result.counterEvidence().isEmpty(),
                    "counterEvidence must name the competing atom");
            assertNotNull(result.refutationBasis(),
                    "refutationBasis must be set");
            assertTrue(result.refutationBasis().contains("functional-conflict"),
                    "refutationBasis must mention 'functional-conflict'");
            // The counterEvidence must reference the competing atom
            assertTrue(result.counterEvidence().stream().anyMatch(e -> e.contains("CEO(Acme, Bob)")),
                    "counterEvidence must mention CEO(Acme, Bob)");
        }

        @Test
        @DisplayName("UNKNOWN stays UNKNOWN when predicate is non-functional even if similar atoms exist")
        void unknown_nonFunctionalPredicateNotRefuted() {
            // worksAt is NOT functional — multiple employees can work at the same company
            inferredStore.store(InferredFact.of(
                    "worksAt(Alice, Acme)", 0.95,
                    List.of(), List.of(), "run-1", 1L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            // worksAt(Bob, Acme) — Bob not in store; worksAt is non-functional → UNKNOWN, not REFUTED
            VerifyResult result = verifier.verify("worksAt(Bob, Acme)");

            assertEquals(VerifyResult.Status.UNKNOWN, result.status(),
                    "non-functional predicate must not trigger E5 refutation");
            assertNull(result.refutationBasis(),
                    "no refutationBasis for UNKNOWN without functional conflict");
            assertTrue(result.counterEvidence().isEmpty(),
                    "no counterEvidence for UNKNOWN without functional conflict");
        }

        @Test
        @DisplayName("competitor below functional-conflict threshold does not trigger refutation")
        void unknown_competitorBelowThreshold() {
            // Low-confidence CEO competitor — below 0.7 threshold
            inferredStore.store(InferredFact.of(
                    "CEO(Acme, Bob)", 0.4,
                    List.of(), List.of(), "run-1", 1L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("CEO(Acme, Alice)");

            assertEquals(VerifyResult.Status.UNKNOWN, result.status(),
                    "low-confidence competitor must not trigger refutation");
        }
    }

    // ─── E5: functional-conflict REFUTED (via FactStore) ─────────────────────────

    @Nested
    @DisplayName("E5: functional-conflict refutation via FactStore")
    class FunctionalConflictFact {

        @Test
        @DisplayName("REFUTED when competing hard fact exists in FactStore for functional predicate")
        void refuted_whenCompetingHardFact() {
            // Hard observed fact: CEO(Acme, Bob) = 1.0
            factStore.assertFact(Fact.observed("CEO(Acme, Bob)", "hr-system"));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("CEO(Acme, Alice)");

            assertEquals(VerifyResult.Status.REFUTED, result.status(),
                    "functional predicate with competing hard fact must be REFUTED");
            assertFalse(result.counterEvidence().isEmpty());
            assertNotNull(result.refutationBasis());
            assertTrue(result.refutationBasis().contains("functional-conflict"));
        }
    }

    // ─── E4: counter-evidence for SUPPORTED results ───────────────────────────────

    @Nested
    @DisplayName("E4: counter-evidence attached to SUPPORTED results")
    class CounterEvidenceOnSupported {

        @Test
        @DisplayName("SUPPORTED result carries counterEvidence when negated-atom exists")
        void supported_counterEvidenceWhenNegatedAtomExists() {
            // The positive atom is strongly supported
            inferredStore.store(InferredFact.of(
                    "isEmployedBy(Alice, Acme)", 0.85,
                    List.of("worksAt(Alice, AcmeNYC)"), List.of("rule-1"), "run-1", 1L));

            // But the negated form also exists (tension)
            inferredStore.store(InferredFact.of(
                    "~isEmployedBy(Alice, Acme)", 0.6,
                    List.of(), List.of(), "run-1", 2L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("isEmployedBy(Alice, Acme)");

            assertEquals(VerifyResult.Status.SUPPORTED, result.status(),
                    "positive confidence ≥ threshold wins → SUPPORTED");
            assertFalse(result.counterEvidence().isEmpty(),
                    "counterEvidence must be populated when a negated atom exists");
            assertNotNull(result.refutationBasis(),
                    "refutationBasis must indicate the tension source");
        }

        @Test
        @DisplayName("SUPPORTED result has empty counterEvidence when no tension exists")
        void supported_noCounterEvidenceWhenClean() {
            inferredStore.store(InferredFact.of(
                    "isEmployedBy(Alice, Acme)", 0.92,
                    List.of("worksAt(Alice, AcmeNYC)"), List.of(), "run-1", 1L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("isEmployedBy(Alice, Acme)");

            assertEquals(VerifyResult.Status.SUPPORTED, result.status());
            assertTrue(result.counterEvidence().isEmpty(),
                    "no tension → counterEvidence must be empty");
            assertNull(result.refutationBasis());
        }
    }

    // ─── E4: negated-atom REFUTED ─────────────────────────────────────────────────

    @Nested
    @DisplayName("E4: negated-atom refutation")
    class NegatedAtomRefuted {

        @Test
        @DisplayName("REFUTED with 'negated-atom' basis when ~atom is in inferred store")
        void refuted_negatedAtom() {
            // The negated form is highly confident
            inferredStore.store(InferredFact.of(
                    "~isEmployedBy(Alice, Rival)", 0.88,
                    List.of(), List.of(), "run-1", 1L));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("isEmployedBy(Alice, Rival)");

            assertEquals(VerifyResult.Status.REFUTED, result.status());
            assertEquals(0.88, result.confidence(), 1e-9);
            assertEquals("negated-atom", result.refutationBasis());
            assertFalse(result.counterEvidence().isEmpty());
            assertTrue(result.counterEvidence().get(0).contains("~isEmployedBy(Alice, Rival)"),
                    "counterEvidence must name the negated atom");
        }
    }

    // ─── Hard-false-fact refutation ────────────────────────────────────────────────

    @Nested
    @DisplayName("Hard-false-fact refutation")
    class HardFalseFact {

        @Test
        @DisplayName("REFUTED with 'hard-false-fact' basis when FactStore has value≈0")
        void refuted_hardFalseFact() {
            // Explicitly false hard fact (value = 0.0)
            factStore.assertFact(new Fact("isEmployedBy(Alice, Rival)", 0.0,
                    "hr-system", java.time.Instant.now(), true));

            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);
            VerifyResult result = verifier.verify("isEmployedBy(Alice, Rival)");

            assertEquals(VerifyResult.Status.REFUTED, result.status());
            assertEquals("hard-false-fact", result.refutationBasis());
            assertFalse(result.counterEvidence().isEmpty());
        }
    }

    // ─── Back-compat: 3-arg VerifyResult constructor ─────────────────────────────

    @Nested
    @DisplayName("VerifyResult back-compat constructors and factories")
    class BackCompat {

        @Test
        @DisplayName("3-arg constructor sets counterEvidence=[] and refutationBasis=null")
        void threeArgConstructor_defaults() {
            VerifyResult vr = new VerifyResult(VerifyResult.Status.SUPPORTED, 0.9, List.of("e1"));
            assertTrue(vr.counterEvidence().isEmpty());
            assertNull(vr.refutationBasis());
        }

        @Test
        @DisplayName("supported(conf, evidence) 2-arg factory produces empty counterEvidence")
        void supportedFactory_twoArg() {
            VerifyResult vr = VerifyResult.supported(0.8, List.of("e1"));
            assertEquals(VerifyResult.Status.SUPPORTED, vr.status());
            assertTrue(vr.counterEvidence().isEmpty());
            assertNull(vr.refutationBasis());
        }

        @Test
        @DisplayName("refuted(conf, evidence) 2-arg factory produces empty counterEvidence")
        void refutedFactory_twoArg() {
            VerifyResult vr = VerifyResult.refuted(0.75, List.of());
            assertEquals(VerifyResult.Status.REFUTED, vr.status());
            assertTrue(vr.counterEvidence().isEmpty());
            assertNull(vr.refutationBasis());
        }

        @Test
        @DisplayName("unknown() produces UNKNOWN with empty counterEvidence")
        void unknownFactory() {
            VerifyResult vr = VerifyResult.unknown();
            assertEquals(VerifyResult.Status.UNKNOWN, vr.status());
            assertEquals(0.0, vr.confidence());
            assertTrue(vr.evidence().isEmpty());
            assertTrue(vr.counterEvidence().isEmpty());
            assertNull(vr.refutationBasis());
        }

        @Test
        @DisplayName("supported 4-arg factory populates counterEvidence and refutationBasis")
        void supportedFactory_fourArg() {
            VerifyResult vr = VerifyResult.supported(0.9, List.of("e1"),
                    List.of("~atom"), "negated-atom");
            assertEquals(VerifyResult.Status.SUPPORTED, vr.status());
            assertEquals(List.of("~atom"), vr.counterEvidence());
            assertEquals("negated-atom", vr.refutationBasis());
        }

        @Test
        @DisplayName("refuted 4-arg factory populates counterEvidence and refutationBasis")
        void refutedFactory_fourArg() {
            VerifyResult vr = VerifyResult.refuted(0.85, List.of(),
                    List.of("CEO(Acme, Bob)"), "functional-conflict: CEO(Acme, Bob) @0.92");
            assertEquals(VerifyResult.Status.REFUTED, vr.status());
            assertFalse(vr.counterEvidence().isEmpty());
            assertTrue(vr.refutationBasis().contains("functional-conflict"));
        }
    }
}
