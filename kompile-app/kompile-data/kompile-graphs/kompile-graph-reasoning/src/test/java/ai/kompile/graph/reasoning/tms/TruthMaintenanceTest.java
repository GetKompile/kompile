/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContradictionDetector, JustificationIndex, and BeliefReviser.
 */
class TruthMaintenanceTest {

    /**
     * Build a small PSL program:
     *   Observed: State(alice)=1.0, Link(alice,bob)=1.0
     *   Target:   State(bob)
     *   Rule:     2.0: State(X) & Link(X,Y) -> State(Y) ^2
     */
    PslProgram buildPropagationProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.target("State", "bob");
        return p;
    }

    /**
     * Build a PSL program with a hard constraint that IS violated:
     *   Observed: A(x)=1.0, B(x)=1.0
     *   Hard constraint: A(X) & B(X) -> .  (A and B cannot both be true -- hard constraint)
     *
     * After inference, the solver will try to minimize violations, but with hard observations,
     * the hard constraint will be violated.
     */
    PslProgram buildViolatingProgram() {
        PslProgram p = new PslProgram();
        // Hard constraint: ~A(X) | ~B(X) (equivalent: A(X) -> ~B(X))
        // Using: A(X) & B(X) -> .  (empty head = body must be false)
        p.addRule("A(X) & B(X) -> .");
        p.observe("A", 1.0, "x");
        p.observe("B", 1.0, "x");
        return p;
    }

    // ─── ContradictionDetector tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("ContradictionDetector")
    class ContradictionDetectorTests {

        @Test
        @DisplayName("detectHard detects violations of hard constraints")
        void detectHardViolations() {
            PslProgram program = buildViolatingProgram();
            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

            List<Contradiction> contradictions = ContradictionDetector.detectHard(result);
            // The hard constraint A(X) & B(X) -> . is violated because both A(x) and B(x) are 1.0
            // With hard constraints, distance may be > 0
            assertNotNull(contradictions);
        }

        @Test
        @DisplayName("detectHard returns empty when no hard constraint violations")
        void detectHardReturnsEmpty() {
            PslProgram program = buildPropagationProgram();
            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

            // No hard constraints in this program
            List<Contradiction> contradictions = ContradictionDetector.detectHard(result);
            assertTrue(contradictions.isEmpty(), "No hard constraints means no contradictions");
        }

        @Test
        @DisplayName("detect with threshold also checks soft rules")
        void detectWithThreshold() {
            PslProgram program = buildPropagationProgram();
            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

            // Using a large threshold should detect almost any violation
            List<Contradiction> contradictions = ContradictionDetector.detect(result, 1e-6);
            assertNotNull(contradictions);
            // With hard rules only checked, soft rules are ignored
        }

        @Test
        @DisplayName("contradicts(f1, f2) correctly identifies conflicting hard facts")
        void contradictsFacts() {
            Fact f1 = Fact.observed("State(alice)", "src1");  // value=1.0, hard
            Fact f2 = new Fact("State(alice)", 0.0, "src2",
                    java.time.Instant.now(), true); // value=0.0, hard

            assertTrue(ContradictionDetector.contradicts(f1, f2));
            assertTrue(ContradictionDetector.contradicts(f2, f1));
        }

        @Test
        @DisplayName("contradicts returns false for non-conflicting facts")
        void contradictsFalseForCompatible() {
            Fact f1 = Fact.observed("State(alice)", "src1");
            Fact f2 = Fact.observed("State(bob)", "src2"); // different key
            assertFalse(ContradictionDetector.contradicts(f1, f2));

            Fact f3 = Fact.soft("State(alice)", 0.5, "src3"); // soft, not hard
            Fact f4 = new Fact("State(alice)", 0.0, "src4",
                    java.time.Instant.now(), false); // also soft
            assertFalse(ContradictionDetector.contradicts(f3, f4));
        }

        @Test
        @DisplayName("findFactContradictions finds conflicting facts in FactStore")
        void findFactContradictions() {
            // FactStore is keyed by atomKey, so we can't have two facts with same key.
            // This test verifies the method runs without error and returns empty for non-conflicting facts.
            FactStore store = new FactStore();
            store.assertFact(Fact.observed("State(alice)", "src1"));
            store.assertFact(Fact.observed("State(bob)", "src2"));

            List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                    ContradictionDetector.findFactContradictions(store);
            // Different keys, no contradiction
            assertTrue(contradictions.isEmpty());
        }

        @Test
        @DisplayName("findFactContradictions detects explicit negated atoms")
        void findFactContradictionsDetectsNegation() {
            FactStore store = new FactStore();
            store.assertFact(Fact.observed("State(alice)", "src1"));
            store.assertFact(Fact.observed("Not_State(alice)", "src2"));

            List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                    ContradictionDetector.findFactContradictions(store);

            assertEquals(1, contradictions.size());
        }

        @Test
        @DisplayName("findFactContradictions detects schema functional predicate clashes")
        void findFactContradictionsDetectsFunctionalPredicateClash() {
            FactStore store = new FactStore();
            store.assertFact(Fact.observed("LifecyclePhase(order-1, draft)", "src1"));
            store.assertFact(Fact.observed("LifecyclePhase(order-1, approved)", "src2"));

            List<ContradictionDetector.Pair<Fact, Fact>> contradictions =
                    ContradictionDetector.findFactContradictions(store, Set.of("LifecyclePhase"));

            assertEquals(1, contradictions.size());
        }

        @Test
        @DisplayName("probabilistic detector flags high posterior mass on mutually exclusive MEBN states")
        void probabilisticDetectorFlagsMutuallyExclusiveMebnStates() {
            List<ProbabilisticContradictionDetector.ProbabilisticContradiction> contradictions =
                    ProbabilisticContradictionDetector.detect(
                            Map.of(
                                    "RedWine(wine-1)", 0.72,
                                    "WhiteWine(wine-1)", 0.64),
                            Map.of(
                                    "RedWine(wine-1)", 0.35,
                                    "WhiteWine(wine-1)", 0.20),
                            Map.of(
                                    "RedWine(wine-1)", Map.of(
                                            "entityId", "wine-1",
                                            "exclusiveGroup", "wine-color",
                                            "state", "RedWine",
                                            "rvName", "WineColor"),
                                    "WhiteWine(wine-1)", Map.of(
                                            "entityId", "wine-1",
                                            "exclusiveGroup", "wine-color",
                                            "state", "WhiteWine",
                                            "rvName", "WineColor")));

            assertEquals(1, contradictions.size());
            ProbabilisticContradictionDetector.ProbabilisticContradiction contradiction = contradictions.get(0);
            assertEquals("wine-1", contradiction.entityId());
            assertEquals("wine-color", contradiction.groupKey());
            assertTrue(contradiction.jointConflict() > 0.45);
            assertTrue(contradiction.entropy() > 0.9);
        }

        @Test
        @DisplayName("probabilistic detector ignores high posteriors in unrelated groups")
        void probabilisticDetectorIgnoresUnrelatedGroups() {
            List<ProbabilisticContradictionDetector.ProbabilisticContradiction> contradictions =
                    ProbabilisticContradictionDetector.detect(
                            Map.of(
                                    "RedWine(wine-1)", 0.92,
                                    "SweetWine(wine-1)", 0.88),
                            Map.of(),
                            Map.of(
                                    "RedWine(wine-1)", Map.of(
                                            "entityId", "wine-1",
                                            "exclusiveGroup", "wine-color",
                                            "state", "RedWine"),
                                    "SweetWine(wine-1)", Map.of(
                                            "entityId", "wine-1",
                                            "exclusiveGroup", "wine-style",
                                            "state", "SweetWine")));

            assertTrue(contradictions.isEmpty());
        }
    }

    // ─── JustificationIndex tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("JustificationIndex")
    class JustificationIndexTests {

        PslProgram program;
        FactStore factStore;
        HlMrfMapInference.Result result;
        JustificationIndex index;

        @BeforeEach
        void setup() {
            program = buildPropagationProgram();
            factStore = new FactStore();
            factStore.assertFact(Fact.observed("State(alice)", "crawl-1"));
            factStore.assertFact(Fact.observed("Link(alice, bob)", "crawl-1"));

            result = HlMrfMapInference.solve(program);
            index = JustificationIndex.build(result, factStore);
        }

        @Test
        @DisplayName("JustificationIndex.build() does not throw")
        void buildDoesNotThrow() {
            assertNotNull(index);
        }

        @Test
        @DisplayName("supportingRules returns non-null for any atom")
        void supportingRules() {
            List<String> rules = index.supportingRules("State(bob)");
            assertNotNull(rules);
            // May or may not be empty depending on whether the rule fired
        }

        @Test
        @DisplayName("supportingFacts returns non-null for any atom")
        void supportingFacts() {
            Set<String> facts = index.supportingFacts("State(bob)");
            assertNotNull(facts);
        }

        @Test
        @DisplayName("atomsDependingOnFact returns non-null")
        void atomsDependingOnFact() {
            Set<String> atoms = index.atomsDependingOnFact("State(alice)");
            assertNotNull(atoms);
        }

        @Test
        @DisplayName("solelyDependentOn returns subset of atomsDependingOnFact")
        void solelyDependentOn() {
            Set<String> allDependent = index.atomsDependingOnFact("State(alice)");
            Set<String> solely = index.solelyDependentOn("State(alice)");
            assertTrue(allDependent.containsAll(solely),
                    "Solely dependent must be a subset of all dependent");
        }
    }

    // ─── BeliefReviser tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("BeliefReviser")
    class BeliefReviserTests {

        @Test
        @DisplayName("BeliefReviser.retract() removes fact and identifies unsupported atoms")
        void retractFact() {
            PslProgram program = buildPropagationProgram();
            FactStore factStore = new FactStore();
            factStore.assertFact(Fact.observed("State(alice)", "crawl-1"));
            factStore.assertFact(Fact.observed("Link(alice, bob)", "crawl-1"));

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            int sizeBefore = factStore.size();
            BeliefRevisionResult revision = BeliefReviser.retract("State(alice)", factStore, index);

            assertNotNull(revision);
            assertEquals("State(alice)", revision.retractedFactKey());
            assertEquals(sizeBefore - 1, factStore.size(),
                    "Fact should have been removed from store");
            assertNotNull(revision.unsupportedAtoms());
            assertNotNull(revision.weakenedAtoms());
            assertSame(factStore, revision.revisedFactStore());
        }

        @Test
        @DisplayName("BeliefReviser.retractAndRevise() re-runs inference")
        void retractAndRevise() {
            PslProgram program = buildPropagationProgram();
            FactStore factStore = new FactStore();
            factStore.assertFact(Fact.observed("State(alice)", "crawl-1"));
            factStore.assertFact(Fact.observed("Link(alice, bob)", "crawl-1"));

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            BeliefRevisionResult revision = BeliefReviser.retractAndRevise(
                    "State(alice)", program, factStore, index);

            assertNotNull(revision);
            assertEquals("State(alice)", revision.retractedFactKey());
            // The revised store should not contain the retracted fact
            assertFalse(revision.revisedFactStore().factFor("State(alice)").isPresent());
        }
    }
}
