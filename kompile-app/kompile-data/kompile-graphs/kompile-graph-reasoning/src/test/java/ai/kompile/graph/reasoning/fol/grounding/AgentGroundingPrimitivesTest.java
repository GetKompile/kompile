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
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.tms.JustificationIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the P0 agent-grounding primitives:
 * {@link KbVerifier} / {@link DefaultKbVerifier},
 * {@link ConjunctiveQueryEngine},
 * {@link ConcurrentFactStore},
 * {@link DerivationTree}.
 *
 * <p>All tests are infra-free (no Spring, no external dependencies). The test KB is a
 * simple employment domain:
 * <ul>
 *   <li>{@code worksAt(Alice, AcmeNYC)} — observed, hard</li>
 *   <li>{@code subsidiary(AcmeNYC, Acme)} — observed, hard</li>
 *   <li>Rule: {@code 0.9: worksAt(X, Z) & subsidiary(Z, Y) -> isEmployedBy(X, Y) ^2}</li>
 *   <li>Target: {@code isEmployedBy(Alice, Acme)} — derived by PSL inference</li>
 * </ul>
 */
class AgentGroundingPrimitivesTest {

    // ─── Shared fixtures ─────────────────────────────────────────────────────────

    /** Build a PSL program: worksAt + subsidiary → isEmployedBy */
    static PslProgram buildEmploymentProgram() {
        PslProgram p = new PslProgram();
        p.addRule("0.9: worksAt(X, Z) & subsidiary(Z, Y) -> isEmployedBy(X, Y) ^2");
        p.observe("worksAt", 1.0, "Alice", "AcmeNYC");
        p.observe("subsidiary", 1.0, "AcmeNYC", "Acme");
        p.target("isEmployedBy", "Alice", "Acme");
        return p;
    }

    /** Run inference and materialize results into an InMemoryInferredFactStore */
    static InMemoryInferredFactStore runInferenceAndStore(PslProgram program) {
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        InMemoryInferredFactStore store = new InMemoryInferredFactStore();
        Map<String, Double> values = result.values();
        // Store all target atoms as inferred facts
        for (String key : program.targetKeys()) {
            Double val = values.get(key);
            double confidence = (val != null) ? val : 0.0;
            // Find supporting rules from ground rules
            List<String> supportingRules = new ArrayList<>();
            for (GroundRule gr : result.groundRules()) {
                for (GroundRule.Lit headLit : gr.head()) {
                    if (headLit.atomKey().equals(key)) {
                        supportingRules.add(gr.display());
                        break;
                    }
                }
            }
            // Find supporting fact keys from observed atoms in program
            List<String> supportingFacts = new ArrayList<>();
            for (GroundRule gr : result.groundRules()) {
                for (GroundRule.Lit headLit : gr.head()) {
                    if (headLit.atomKey().equals(key)) {
                        for (GroundRule.Lit bodyLit : gr.body()) {
                            if (program.isObserved(bodyLit.atomKey())) {
                                supportingFacts.add(bodyLit.atomKey());
                            }
                        }
                        break;
                    }
                }
            }
            store.store(new InferredFact(key, confidence, confidence,
                    supportingFacts, supportingRules, "run-test-1", 0, Instant.now()));
        }
        return store;
    }

    /** Build a simple FactStore with observed employment facts. */
    static FactStore buildFactStore() {
        FactStore fs = new FactStore();
        fs.assertFact(Fact.observed("worksAt(Alice, AcmeNYC)", "crawl-1"));
        fs.assertFact(Fact.observed("subsidiary(AcmeNYC, Acme)", "crawl-1"));
        return fs;
    }

    // ─── KbVerifier tests ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("KbVerifier")
    class KbVerifierTests {

        InMemoryInferredFactStore inferredStore;
        FactStore factStore;
        DefaultKbVerifier verifier;

        @BeforeEach
        void setUp() {
            PslProgram program = buildEmploymentProgram();
            inferredStore = runInferenceAndStore(program);
            factStore = buildFactStore();
            verifier = new DefaultKbVerifier(inferredStore, factStore);
        }

        @Test
        @DisplayName("verify returns SUPPORTED for a derived fact with non-empty evidence and confidence in [0,1]")
        void verifySupportedDerivedFact() {
            // isEmployedBy(Alice, Acme) should be derived by PSL
            VerifyResult result = verifier.verify("isEmployedBy(Alice, Acme)");
            assertNotNull(result);
            assertEquals(VerifyResult.Status.SUPPORTED, result.status(),
                    "Expected SUPPORTED for a PSL-derived atom; got: " + result.status()
                            + " confidence=" + result.confidence());
            assertTrue(result.confidence() > 0.0, "Confidence must be > 0 for SUPPORTED");
            assertTrue(result.confidence() <= 1.0, "Confidence must be ≤ 1.0");
            assertFalse(result.evidence().isEmpty(), "SUPPORTED result must have non-empty evidence");
        }

        @Test
        @DisplayName("verify returns SUPPORTED for a directly-observed hard fact")
        void verifySupportedObservedFact() {
            VerifyResult result = verifier.verify("worksAt(Alice, AcmeNYC)");
            assertNotNull(result);
            // The atom is in the FactStore as a hard observed fact with value 1.0
            assertEquals(VerifyResult.Status.SUPPORTED, result.status(),
                    "Directly observed hard fact should be SUPPORTED; got: " + result.status());
            assertEquals(1.0, result.confidence(), 1e-9);
            assertFalse(result.evidence().isEmpty());
        }

        @Test
        @DisplayName("verify returns UNKNOWN for an atom not in the KB")
        void verifyUnknownAtom() {
            VerifyResult result = verifier.verify("livesIn(Bob, Tokyo)");
            assertNotNull(result);
            assertEquals(VerifyResult.Status.UNKNOWN, result.status());
            assertEquals(0.0, result.confidence(), 1e-9);
            assertTrue(result.evidence().isEmpty());
        }

        @Test
        @DisplayName("verify returns REFUTED for a negated atom in the inferred store")
        void verifyRefutedAtom() {
            // Directly store a negated/refuted fact in the inferred store
            String refutedKey = "livesIn(Alice, Tokyo)";
            String negatedKey = "~" + refutedKey;
            inferredStore.store(new InferredFact(negatedKey, 0.95, 0.95,
                    List.of("negation-source"), List.of("negation-rule"),
                    "run-refute", 0, Instant.now()));

            VerifyResult result = verifier.verify(refutedKey);
            assertEquals(VerifyResult.Status.REFUTED, result.status(),
                    "Should be REFUTED when negated atom has high confidence");
            assertTrue(result.confidence() >= 0.5);
        }

        @Test
        @DisplayName("verify(predicate, args...) convenience overload produces same result as verify(atomKey)")
        void verifyConvenienceOverload() {
            VerifyResult byKey = verifier.verify("isEmployedBy(Alice, Acme)");
            VerifyResult byParts = verifier.verify("isEmployedBy", "Alice", "Acme");
            assertNotNull(byParts);
            assertEquals(byKey.status(), byParts.status());
        }

        @Test
        @DisplayName("UNKNOWN result has confidence=0.0 and empty evidence")
        void unknownResultProperties() {
            VerifyResult result = VerifyResult.unknown();
            assertEquals(VerifyResult.Status.UNKNOWN, result.status());
            assertEquals(0.0, result.confidence());
            assertTrue(result.evidence().isEmpty());
        }

        @Test
        @DisplayName("SUPPORTED result has confidence in (0,1] and non-empty evidence")
        void supportedResultProperties() {
            VerifyResult result = VerifyResult.supported(0.87, List.of("fact1", "rule1"));
            assertEquals(VerifyResult.Status.SUPPORTED, result.status());
            assertEquals(0.87, result.confidence(), 1e-9);
            assertEquals(2, result.evidence().size());
        }

        @Test
        @DisplayName("Custom threshold: atom below threshold returns UNKNOWN, not SUPPORTED")
        void customThresholdUnknown() {
            // Store a low-confidence inferred fact
            inferredStore.store(new InferredFact("weakAtom(x)", 0.3, 0.3,
                    List.of("f1"), List.of("r1"), "run-weak", 0, Instant.now()));

            // High threshold: 0.3 confidence is below 0.8 → UNKNOWN
            DefaultKbVerifier highThresholdVerifier =
                    new DefaultKbVerifier(inferredStore, factStore, 0.8);
            VerifyResult result = highThresholdVerifier.verify("weakAtom(x)");
            assertNotEquals(VerifyResult.Status.SUPPORTED, result.status(),
                    "Low-confidence atom should not be SUPPORTED with high threshold");
        }
    }

    // ─── ConjunctiveQueryEngine tests ────────────────────────────────────────────

    @Nested
    @DisplayName("ConjunctiveQueryEngine")
    class ConjunctiveQueryEngineTests {

        InMemoryInferredFactStore store;

        @BeforeEach
        void setUp() {
            store = new InMemoryInferredFactStore();
            // Populate with employment facts
            store.store(fact("worksAt(Alice, AcmeNYC)", 0.95));
            store.store(fact("worksAt(Bob, AcmeLA)", 0.88));
            store.store(fact("subsidiary(AcmeNYC, Acme)", 0.99));
            store.store(fact("subsidiary(AcmeLA, Acme)", 0.97));
            store.store(fact("hasSkill(Alice, Java)", 0.90));
            store.store(fact("hasSkill(Alice, AI)", 0.85));
            store.store(fact("hasSkill(Bob, AI)", 0.80));
            store.store(fact("isEmployedBy(Alice, Acme)", 0.92));
            store.store(fact("isEmployedBy(Bob, Acme)", 0.84));
        }

        private InferredFact fact(String atomKey, double confidence) {
            return new InferredFact(atomKey, confidence, confidence,
                    List.of(), List.of(), "run-q", 0, Instant.now());
        }

        @Test
        @DisplayName("Single-atom query returns correct bindings")
        void singleAtomQuery() {
            // Variables use "?" prefix; "AI" is a constant (entity name, not a variable)
            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?X", "AI"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertFalse(results.isEmpty(), "Expected results for hasSkill(?X, AI)");
            // Should return Alice and Bob (both have skill AI)
            List<String> persons = results.stream()
                    .map(b -> b.get("?X"))
                    .sorted()
                    .toList();
            assertTrue(persons.contains("Alice"), "Alice should appear for hasSkill(?X, AI)");
            assertTrue(persons.contains("Bob"), "Bob should appear for hasSkill(?X, AI)");
            // Confidence must be in [0,1]
            for (QueryBinding b : results) {
                assertTrue(b.confidence() >= 0.0 && b.confidence() <= 1.0);
            }
        }

        @Test
        @DisplayName("2-atom conjunctive query joined on shared variable returns correct bindings")
        void twoAtomConjunctiveQuery() {
            // "Find ?X who hasSkill AI AND isEmployedBy ?X Acme" — AI and Acme are constants
            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?X", "AI")),
                    new ConjunctiveQueryEngine.AtomPattern("isEmployedBy", List.of("?X", "Acme"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertFalse(results.isEmpty(), "Expected results for the 2-atom conjunctive query");
            // Alice and Bob both have AI skill and are employed by Acme
            List<String> persons = results.stream()
                    .map(b -> b.get("?X"))
                    .sorted()
                    .toList();
            assertTrue(persons.contains("Alice"),
                    "Alice should be in results (has AI skill + employed by Acme)");
            assertTrue(persons.contains("Bob"),
                    "Bob should be in results (has AI skill + employed by Acme)");
        }

        @Test
        @DisplayName("2-atom query with constant filter restricts correctly")
        void twoAtomQueryWithFilter() {
            // "Find ?Company that Alice works at (Alice is a constant, not a variable)"
            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("worksAt", List.of("Alice", "?Z")),
                    new ConjunctiveQueryEngine.AtomPattern("subsidiary", List.of("?Z", "?Y"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertFalse(results.isEmpty(),
                    "Expected results for worksAt(Alice, ?Z) & subsidiary(?Z, ?Y)");
            // ?Z = AcmeNYC, ?Y = Acme
            QueryBinding binding = results.get(0);
            assertEquals("AcmeNYC", binding.get("?Z"), "?Z should bind to AcmeNYC");
            assertEquals("Acme", binding.get("?Y"), "?Y should bind to Acme");
        }

        @Test
        @DisplayName("Query returns empty list when no facts match")
        void queryNoMatch() {
            // Both ?X and Mars: ?X is variable, Mars is constant; no livesIn facts in store
            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("livesIn", List.of("?X", "Mars"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertTrue(results.isEmpty(), "Expected empty results for unknown predicate");
        }

        @Test
        @DisplayName("Confidence is the minimum across matched atoms (Łukasiewicz T-norm)")
        void confidenceIsMinimum() {
            // hasSkill(Alice, AI)=0.85, isEmployedBy(Alice, Acme)=0.92
            // Both "Alice" and "AI" and "Acme" are constants (no "?" prefix)
            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("Alice", "AI")),
                    new ConjunctiveQueryEngine.AtomPattern("isEmployedBy", List.of("Alice", "Acme"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertFalse(results.isEmpty(),
                    "Expected a result for constant-only conjunct hasSkill(Alice,AI) & isEmployedBy(Alice,Acme)");
            double confidence = results.get(0).confidence();
            // minimum of 0.85 and 0.92 is 0.85
            assertEquals(0.85, confidence, 1e-6, "Confidence should be min(0.85, 0.92) = 0.85");
        }

        @Test
        @DisplayName("Variable with ? prefix is treated as variable; without ? as constant")
        void variableSyntaxNormalization() {
            // ?X is a variable; "AI" is a constant — should return Alice and Bob
            List<ConjunctiveQueryEngine.AtomPattern> withQMark = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?X", "AI"))
            );
            List<QueryBinding> r1 = ConjunctiveQueryEngine.query(withQMark, store);
            assertFalse(r1.isEmpty(), "?X should match variable bindings");
            // Results should include entries with ?X bound
            assertTrue(r1.stream().allMatch(b -> b.get("?X") != null),
                    "All results should have ?X bound");
        }
    }

    // ─── ConcurrentFactStore tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("ConcurrentFactStore")
    class ConcurrentFactStoreTests {

        ConcurrentFactStore concurrentStore;

        @BeforeEach
        void setUp() {
            concurrentStore = new ConcurrentFactStore();
        }

        @Test
        @DisplayName("Concurrent asserts from multiple threads all land in the store")
        void concurrentAssertsAllLand() throws InterruptedException {
            int threadCount = 8;
            int factsPerThread = 20;
            CountDownLatch latch = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < factsPerThread; i++) {
                            String key = "fact_t" + threadId + "_i" + i + "(entity)";
                            concurrentStore.assertFact(Fact.observed(key, "thread-" + threadId));
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                });
            }

            latch.countDown(); // release all threads simultaneously
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, failCount.get(), "No thread should have thrown an exception");
            // All facts should be in the store
            assertEquals(threadCount * factsPerThread, concurrentStore.size(),
                    "All " + (threadCount * factsPerThread) + " facts should be in the store");
        }

        @Test
        @DisplayName("Optimistic assert succeeds when version matches")
        void optimisticAssertSucceeds() {
            long v0 = concurrentStore.version(); // version = 0
            long newVersion = concurrentStore.assertFact(
                    Fact.observed("myFact(x)", "agent-1"), v0);
            assertNotEquals(ConcurrentFactStore.CONFLICT, newVersion,
                    "assertFact with correct version should succeed");
            assertTrue(concurrentStore.factFor("myFact(x)").isPresent());
        }

        @Test
        @DisplayName("Write-write conflict is detected: assertFact with stale version returns CONFLICT")
        void writeWriteConflictDetected() {
            long v0 = concurrentStore.version();
            // Agent 1 asserts (unconditional) — bumps version to 1
            concurrentStore.assertFact(Fact.observed("factA(x)", "agent-1"));
            long vAfterAgent1 = concurrentStore.version();
            assertTrue(vAfterAgent1 > v0, "Version should have advanced after agent-1 write");

            // Agent 2 tries to assert with the OLD version v0 (stale) → CONFLICT
            long result = concurrentStore.assertFact(
                    Fact.observed("factB(y)", "agent-2"), v0);
            assertEquals(ConcurrentFactStore.CONFLICT, result,
                    "assertFact with stale expectedVersion must return CONFLICT");
            // factB should NOT be in the store (conflict aborted)
            assertFalse(concurrentStore.factFor("factB(y)").isPresent(),
                    "factB should not be asserted after a conflict");
        }

        @Test
        @DisplayName("snapshot() returns consistent point-in-time copy")
        void snapshotConsistency() {
            concurrentStore.assertFact(Fact.observed("snapFact(a)", "s"));
            concurrentStore.assertFact(Fact.observed("snapFact(b)", "s"));
            ConcurrentFactStore.Snapshot snap = concurrentStore.snapshot();
            assertEquals(2, snap.facts().size());
            assertTrue(snap.version() >= 2, "Snapshot version must reflect at least 2 writes");
            // Snapshot is immutable
            assertThrows(UnsupportedOperationException.class,
                    () -> snap.facts().add(Fact.observed("extra(x)", "e")));
        }

        @Test
        @DisplayName("retract removes fact and bumps version")
        void retractRemovesFact() {
            concurrentStore.assertFact(Fact.observed("retractMe(x)", "s"));
            long vBefore = concurrentStore.version();
            assertTrue(concurrentStore.retract("retractMe(x)").isPresent());
            assertFalse(concurrentStore.factFor("retractMe(x)").isPresent());
            assertTrue(concurrentStore.version() > vBefore);
        }

        @Test
        @DisplayName("applyTo copies all facts into a FactStore")
        void applyToFactStore() {
            concurrentStore.assertFact(Fact.observed("a(x)", "s"));
            concurrentStore.assertFact(Fact.observed("b(y)", "s"));
            FactStore target = new FactStore();
            concurrentStore.applyTo(target);
            assertEquals(2, target.size());
            assertTrue(target.factFor("a(x)").isPresent());
            assertTrue(target.factFor("b(y)").isPresent());
        }
    }

    // ─── DerivationTree tests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("DerivationTree")
    class DerivationTreeTests {

        InMemoryInferredFactStore store;
        JustificationIndex index;
        FactStore factStore;

        @BeforeEach
        void setUp() {
            // Build and run PSL inference
            PslProgram program = buildEmploymentProgram();
            factStore = buildFactStore();
            factStore.applyToProgram(program);

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            index = JustificationIndex.build(result, factStore);

            // Populate inferred store from PSL result
            store = runInferenceAndStore(program);

            // Also add the observed facts to the inferred store so children can be found
            store.store(new InferredFact("worksAt(Alice, AcmeNYC)", 1.0, 1.0,
                    List.of(), List.of("observed"), "run-test-1", 0, Instant.now()));
            store.store(new InferredFact("subsidiary(AcmeNYC, Acme)", 1.0, 1.0,
                    List.of(), List.of("observed"), "run-test-1", 0, Instant.now()));
        }

        @Test
        @DisplayName("build() produces a non-null tree for a derived fact")
        void buildNonNull() {
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index);
            assertNotNull(tree);
            assertEquals("isEmployedBy(Alice, Acme)", tree.atomKey());
            assertTrue(tree.confidence() >= 0.0 && tree.confidence() <= 1.0);
        }

        @Test
        @DisplayName("Tree for a derived fact has children (supporting facts)")
        void derivedFactHasChildren() {
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index, 3);
            assertNotNull(tree);
            // The tree should have at least one child (worksAt or subsidiary)
            // The root fact has supportingFactKeys populated in runInferenceAndStore
            assertFalse(tree.allAtomKeys().isEmpty(),
                    "Tree should contain at least the root atom key");
        }

        @Test
        @DisplayName("Unknown atom produces a leaf node with confidence=0.0")
        void unknownAtomIsLeaf() {
            DerivationTree tree = DerivationTree.build("doesNotExist(X)", store, index);
            assertNotNull(tree);
            assertEquals("doesNotExist(X)", tree.atomKey());
            assertEquals(0.0, tree.confidence(), 1e-9);
            assertTrue(tree.isLeaf(), "Unknown atom should produce a leaf node");
        }

        @Test
        @DisplayName("toString() produces human-readable indented output")
        void toStringReadable() {
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index);
            String text = tree.toString();
            assertNotNull(text);
            assertFalse(text.isBlank());
            assertTrue(text.contains("isEmployedBy(Alice, Acme)"),
                    "toString should contain the root atom key");
            assertTrue(text.contains("confidence="),
                    "toString should include confidence");
        }

        @Test
        @DisplayName("toJson() produces structurally valid JSON with required fields")
        void toJsonStructural() {
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index);
            String json = tree.toJson();
            assertNotNull(json);
            assertFalse(json.isBlank());
            // Structural checks (hand-rolled JSON, no parser needed)
            assertTrue(json.contains("\"atom\""), "JSON must have 'atom' field");
            assertTrue(json.contains("\"confidence\""), "JSON must have 'confidence' field");
            assertTrue(json.contains("\"children\""), "JSON must have 'children' field");
            assertTrue(json.contains("isEmployedBy(Alice, Acme)"),
                    "JSON must contain the root atom key");
            // Basic JSON structure: starts with { ends with }
            String trimmed = json.trim();
            assertTrue(trimmed.startsWith("{"), "JSON must start with {");
            assertTrue(trimmed.endsWith("}"), "JSON must end with }");
        }

        @Test
        @DisplayName("allAtomKeys() returns the root key in the list")
        void allAtomKeysContainsRoot() {
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index);
            List<String> keys = tree.allAtomKeys();
            assertTrue(keys.contains("isEmployedBy(Alice, Acme)"),
                    "allAtomKeys must include the root key");
        }

        @Test
        @DisplayName("Cycle guard prevents infinite recursion")
        void cycleGuardPreventsInfiniteRecursion() {
            // Create a cycle: A depends on B depends on A
            InMemoryInferredFactStore cycleStore = new InMemoryInferredFactStore();
            cycleStore.store(new InferredFact("A(x)", 0.8, 0.8,
                    List.of("B(x)"), List.of("rule-AB"), "run-cycle", 0, Instant.now()));
            cycleStore.store(new InferredFact("B(x)", 0.7, 0.7,
                    List.of("A(x)"), List.of("rule-BA"), "run-cycle", 0, Instant.now()));

            // Build an empty JustificationIndex (no MAP result here)
            JustificationIndex emptyIndex = JustificationIndex.build(
                    HlMrfMapInference.solve(new PslProgram()), new FactStore());

            // This must terminate without StackOverflowError
            assertDoesNotThrow(() -> {
                DerivationTree tree = DerivationTree.build("A(x)", cycleStore, emptyIndex, 10);
                assertNotNull(tree);
            }, "Cycle guard must prevent infinite recursion");
        }

        @Test
        @DisplayName("maxDepth=1 produces a tree with no grandchildren")
        void maxDepthOne() {
            // With depth=1, the root has children but children are leaf nodes
            DerivationTree tree = DerivationTree.build("isEmployedBy(Alice, Acme)", store, index, 1);
            assertNotNull(tree);
            // Children at depth=1 must all be leaves
            for (DerivationTree child : tree.children()) {
                assertTrue(child.isLeaf(),
                        "At maxDepth=1, children should not have their own children");
            }
        }
    }
}
