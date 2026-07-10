/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;
import ai.kompile.graph.reasoning.fol.grounding.QueryBinding;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KbGroundingService}.
 *
 * <p>Instantiates the service directly — no Spring Boot context, no mocks.
 * All lib primitives are exercised via real implementations from
 * {@code kompile-graph-reasoning}.</p>
 *
 * <p>Fact sheet 42 is used throughout to verify per-fact-sheet scoping.</p>
 */
class KbGroundingServiceTest {

    private static final long FS_ID = 42L;

    private KbGroundingService service;

    @BeforeEach
    void setUp() {
        service = new KbGroundingService();

        // Seed InferredFacts for fact sheet 42:
        //   isEmployedBy(Alice, Acme)  confidence=0.87  supporting=[worksAt(Alice, Acme_NYC)]
        //   worksAt(Alice, Acme_NYC)   confidence=0.95  (leaf / observed equivalent)
        //   subsidiary(Acme_NYC, Acme) confidence=0.92  (leaf)
        //   hasSkill(Alice, AI)        confidence=0.80
        //   hasSkill(Bob, AI)          confidence=0.75
        //   worksAt(Bob, Acme)         confidence=0.70

        service.seedInferredFacts(FS_ID, List.of(
                InferredFact.of(
                        "isEmployedBy(Alice, Acme)", 0.87,
                        List.of("worksAt(Alice, Acme_NYC)", "subsidiary(Acme_NYC, Acme)"),
                        List.of("0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z)"),
                        "run-1", 1L),
                InferredFact.of(
                        "worksAt(Alice, Acme_NYC)", 0.95,
                        List.of(), List.of(), "run-1", 1L),
                InferredFact.of(
                        "subsidiary(Acme_NYC, Acme)", 0.92,
                        List.of(), List.of(), "run-1", 1L),
                InferredFact.of(
                        "hasSkill(Alice, AI)", 0.80,
                        List.of(), List.of(), "run-1", 1L),
                InferredFact.of(
                        "hasSkill(Bob, AI)", 0.75,
                        List.of(), List.of(), "run-1", 1L),
                InferredFact.of(
                        "worksAt(Bob, Acme)", 0.70,
                        List.of(), List.of(), "run-1", 1L)
        ));
    }

    // ── verify ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify: known fact → SUPPORTED with non-empty evidence, confidence in [0,1]")
    void verify_knownFact_returnsSupportedWithEvidence() {
        VerifyResult result = service.verify(FS_ID, "isEmployedBy(Alice, Acme)");

        assertEquals(VerifyResult.Status.SUPPORTED, result.status(),
                "Expected SUPPORTED for a seeded InferredFact");
        double confidence = result.confidence();
        assertTrue(confidence >= 0.0 && confidence <= 1.0,
                "Confidence must be in [0,1] but was " + confidence);
        assertTrue(confidence > 0.5,
                "Confidence should be meaningful (>0.5) for a known fact");
        assertFalse(result.evidence().isEmpty(),
                "Evidence must be non-empty for a SUPPORTED result");
    }

    @Test
    @DisplayName("verify: unknown atom → UNKNOWN with confidence=0.0 and empty evidence")
    void verify_unknownAtom_returnsUnknown() {
        VerifyResult result = service.verify(FS_ID, "neverAsserted(X, Y)");

        assertEquals(VerifyResult.Status.UNKNOWN, result.status());
        assertEquals(0.0, result.confidence(), 1e-9);
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    @DisplayName("verify: leaf fact (directly in InferredFactStore) is also SUPPORTED")
    void verify_leafFact_returnsSupported() {
        VerifyResult result = service.verify(FS_ID, "worksAt(Alice, Acme_NYC)");

        assertEquals(VerifyResult.Status.SUPPORTED, result.status());
        assertEquals(0.95, result.confidence(), 0.001);
    }

    // ── query ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("query: single conjunct → returns all matching bindings")
    void query_singleConjunct_returnsMatchingBindings() {
        List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?Person", "AI"))
        );

        List<QueryBinding> bindings = service.query(FS_ID, conjuncts, 50);

        assertFalse(bindings.isEmpty(), "Expected at least one binding for hasSkill(?Person, AI)");
        // Both Alice and Bob have the AI skill
        assertTrue(bindings.size() >= 2,
                "Expected bindings for Alice and Bob but got: " + bindings.size());
        boolean hasAlice = bindings.stream()
                .anyMatch(b -> "Alice".equals(b.get("?Person")) || "Alice".equals(b.get("Person")));
        boolean hasBob = bindings.stream()
                .anyMatch(b -> "Bob".equals(b.get("?Person")) || "Bob".equals(b.get("Person")));
        assertTrue(hasAlice, "Alice should appear in query results");
        assertTrue(hasBob, "Bob should appear in query results");
    }

    @Test
    @DisplayName("query: conjunctive pattern narrows results correctly")
    void query_conjunctivePattern_narrowsResults() {
        // worksAt(?Person, Acme_NYC) AND hasSkill(?Person, AI) → only Alice matches
        List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                new ConjunctiveQueryEngine.AtomPattern("worksAt", List.of("?Person", "Acme_NYC")),
                new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?Person", "AI"))
        );

        List<QueryBinding> bindings = service.query(FS_ID, conjuncts, 50);

        assertFalse(bindings.isEmpty(), "Expected binding for Alice (worksAt Acme_NYC AND hasSkill AI)");
        // Confidence is min T-norm: min(0.95, 0.80) = 0.80
        QueryBinding row = bindings.get(0);
        assertTrue(row.confidence() > 0.0 && row.confidence() <= 1.0);
        assertTrue(row.confidence() <= 0.95,
                "Row confidence must be ≤ min of individual atom confidences");
    }

    @Test
    @DisplayName("query: no matching predicate → empty result, not an error")
    void query_noMatchingPredicate_returnsEmpty() {
        List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                new ConjunctiveQueryEngine.AtomPattern("unknownPred", List.of("?X"))
        );

        List<QueryBinding> bindings = service.query(FS_ID, conjuncts, 50);

        assertNotNull(bindings);
        assertTrue(bindings.isEmpty(), "No results expected for an unknown predicate");
    }

    // ── explain ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("explain: derived fact → non-empty DerivationTree with the correct root")
    void explain_derivedFact_returnsNonEmptyTree() {
        DerivationTree tree = service.explain(FS_ID, "isEmployedBy(Alice, Acme)", 3);

        assertNotNull(tree, "DerivationTree must not be null");
        assertEquals("isEmployedBy(Alice, Acme)", tree.atomKey());
        assertTrue(tree.confidence() > 0.0,
                "Root confidence should be positive for a known derived fact");
        // The supporting fact keys from InferredFact become children
        assertFalse(tree.children().isEmpty(),
                "Derived fact should have children (its supporting fact keys)");
    }

    @Test
    @DisplayName("explain: unknown atom → leaf DerivationTree with confidence=0.0")
    void explain_unknownAtom_returnsLeafWithZeroConfidence() {
        DerivationTree tree = service.explain(FS_ID, "unknownPred(X, Y)", 3);

        assertNotNull(tree);
        assertEquals("unknownPred(X, Y)", tree.atomKey());
        assertEquals(0.0, tree.confidence(), 1e-9);
        assertTrue(tree.isLeaf(), "Unknown atom should produce a leaf node");
    }

    // ── assertFact ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assertFact: new fact → version increments, subsequent verify → SUPPORTED")
    void assertFact_newFact_versionIncreasesAndVerifySupported() {
        long versionBefore = service.getState(FS_ID).concurrentFactStore().version();

        Fact fact = Fact.observed("isCeo(Bob, Acme)", "agent-session-1");
        KbGroundingService.AssertResult result = service.assertFact(FS_ID, fact);

        assertFalse(result.isConflict(), "Assert should succeed");
        assertTrue(result.version() > versionBefore,
                "Version should increase after assertFact: before=" + versionBefore
                        + " after=" + result.version());
        assertTrue(result.contradictions().isEmpty(),
                "No contradictions expected for a fresh hard fact");

        // The asserted fact is a hard observed fact (value=1.0), so verify should find it
        // via the FactStore (DefaultKbVerifier step 3)
        VerifyResult verified = service.verify(FS_ID, "isCeo(Bob, Acme)");
        assertEquals(VerifyResult.Status.SUPPORTED, verified.status(),
                "Asserted hard fact should be verifiable as SUPPORTED");
    }

    @Test
    @DisplayName("assertFact: optimistic-lock version mismatch → CONFLICT")
    void assertFact_versionMismatch_returnsConflict() {
        // Get current version, then advance it by asserting another fact
        long snapshotVersion = service.getState(FS_ID).concurrentFactStore().version();
        service.assertFact(FS_ID, Fact.observed("worksAt(Carol, Acme)", "interleaving-agent"));

        // Now try with the stale snapshot version → should conflict
        Fact staleAttempt = Fact.observed("worksAt(Dave, Acme)", "late-agent");
        KbGroundingService.AssertResult result = service.assertFact(FS_ID, staleAttempt, snapshotVersion);

        assertTrue(result.isConflict(),
                "Assert with stale expectedVersion should return CONFLICT");
    }

    @Test
    @DisplayName("verifyEnriched: direct observation → exact counterfactual fragility")
    void verifyEnriched_directObservation_counterfactualFragility() {
        service.assertFact(FS_ID, Fact.observed("employs(Acme, Alice)", "hr-doc"));

        KbGroundingService.EnrichedVerifyResult enriched =
                service.verifyEnriched(FS_ID, "employs(Acme, Alice)", 0.0, 0.5);

        assertEquals(VerifyResult.Status.SUPPORTED, enriched.result().status());
        // The observation is the only support: retracting it flips the verdict.
        assertEquals(java.util.List.of("employs(Acme, Alice)"), enriched.fragilityWouldFlipIf());
        assertEquals(1, enriched.fragilityMinimalSupportSize());
        assertEquals(0.0, enriched.fragilityRobustness(), 1.0e-9);
        assertFalse(enriched.openWorld(), "SUPPORTED verdicts are not open-world");
        assertTrue(enriched.evidenceCount() >= 1);
        assertTrue(enriched.sourceProvenance().contains("hr-doc"),
                "observed-fact source id should surface as provenance");
    }

    @Test
    @DisplayName("verifyEnriched: UNKNOWN + registered grounding rules → deep why-not report")
    void verifyEnriched_unknownWithRegisteredRules_runsDeepWhyNot() {
        long sheet = FS_ID + 1000;
        service.assertFact(sheet, Fact.observed("parent(alice, bob)", "family-doc"));
        PslProgram program = new PslProgram()
                .addRule("2.0: Parent(A, B) -> Ancestor(A, B) ^2");
        service.markEpoch(sheet, "run-whynot-1",
                FactSheetKbState.emptyJustificationIndex(), program.rules());

        KbGroundingService.EnrichedVerifyResult enriched =
                service.verifyEnriched(sheet, "ancestor(alice, bob)", 0.0, 0.5);

        assertEquals(VerifyResult.Status.UNKNOWN, enriched.result().status());
        assertTrue(enriched.openWorld(), "UNKNOWN verdicts carry an open-world opinion");
        assertNotNull(enriched.opinion());
        assertNotNull(enriched.deepWhyNotReport(),
                "registered grounding rules should enable deep why-not completion search");
        assertEquals("ancestor(alice, bob)", enriched.deepWhyNotReport().claimAtom());
    }
}
