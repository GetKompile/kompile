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
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for fix #2: BeliefReviser.retractAndPurge must remove stale inferred
 * atoms from InferredFactStore so DefaultKbVerifier.verify() flips SUPPORTED → UNKNOWN
 * rather than returning stale SUPPORTED from dead inferred facts.
 *
 * <p>Also covers: weakened-atom survival, retractReviseAndPurge MAP freshness,
 * purgedAtoms/revisedAt population, and legacy retract() source compatibility.</p>
 */
class BeliefReviserPurgeTest {

    // ─── Shared fixtures ──────────────────────────────────────────────────────────

    /**
     * Minimal program: State(alice) + Link(alice,bob) → State(bob).
     * "State(bob)" has SOLE support from "State(alice)" via the Link rule.
     * Retracting "State(alice)" should leave "State(bob)" unsupported.
     */
    private PslProgram buildSingleSupportProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.target("State", "bob");
        return p;
    }

    /**
     * Multi-support program: State(bob) has TWO independent support paths —
     *   (1) State(alice) + Link(alice,bob)
     *   (2) State(carol) + Link(carol,bob)
     * Retracting "State(alice)" weakens "State(bob)" but does not fully remove its support,
     * so "State(bob)" should NOT be purged.
     */
    private PslProgram buildMultiSupportProgram() {
        PslProgram p = new PslProgram();
        p.addRule("2.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("State", 1.0, "carol");
        p.observe("Link", 1.0, "alice", "bob");
        p.observe("Link", 1.0, "carol", "bob");
        p.target("State", "bob");
        return p;
    }

    /**
     * Seed the InferredFactStore with the atom we want to test purging.
     */
    private InferredFactStore buildInferredStore(String... atomKeys) {
        InMemoryInferredFactStore store = new InMemoryInferredFactStore();
        for (String key : atomKeys) {
            store.store(InferredFact.of(key, 0.9, List.of(), List.of(), "run-1", 0));
        }
        return store;
    }

    /**
     * Build a FactStore consistent with the single-support program.
     */
    private FactStore buildSingleSupportFactStore() {
        FactStore fs = new FactStore();
        fs.assertFact(Fact.observed("State(alice)", "crawl-1"));
        fs.assertFact(Fact.observed("Link(alice, bob)", "crawl-1"));
        return fs;
    }

    /**
     * Build a FactStore consistent with the multi-support program.
     */
    private FactStore buildMultiSupportFactStore() {
        FactStore fs = new FactStore();
        fs.assertFact(Fact.observed("State(alice)", "crawl-1"));
        fs.assertFact(Fact.observed("State(carol)", "crawl-1"));
        fs.assertFact(Fact.observed("Link(alice, bob)", "crawl-1"));
        fs.assertFact(Fact.observed("Link(carol, bob)", "crawl-1"));
        return fs;
    }

    // ─── Fix #2: SUPPORTED → UNKNOWN regression ───────────────────────────────────

    @Nested
    @DisplayName("Fix #2: retractAndPurge flips verify SUPPORTED → UNKNOWN")
    class PurgeRegressionTest {

        private PslProgram program;
        private FactStore factStore;
        private InferredFactStore inferredStore;
        private JustificationIndex index;
        private final String SOLE_TARGET = "State(bob)";
        private final String RETRACTED_FACT = "State(alice)";

        @BeforeEach
        void setup() {
            program = buildSingleSupportProgram();
            factStore = buildSingleSupportFactStore();
            inferredStore = buildInferredStore(SOLE_TARGET);

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            index = JustificationIndex.build(result, factStore);
        }

        @Test
        @DisplayName("(a) State(bob) is in InferredFactStore before retraction")
        void inferredAtomPresentBeforeRetraction() {
            assertTrue(inferredStore.latest(SOLE_TARGET).isPresent(),
                    "State(bob) must be in the inferred store before retraction");
        }

        @Test
        @DisplayName("(b) State(bob) is ABSENT from InferredFactStore after retractAndPurge")
        void inferredAtomAbsentAfterPurge() {
            BeliefRevisionResult revision =
                    BeliefReviser.retractAndPurge(RETRACTED_FACT, factStore, index, inferredStore);

            // State(bob) was the sole dependent; it must have been purged.
            assertFalse(inferredStore.latest(SOLE_TARGET).isPresent(),
                    "State(bob) must be absent from the inferred store after retractAndPurge");
            assertTrue(revision.purgedAtoms().contains(SOLE_TARGET),
                    "purgedAtoms must list State(bob)");
        }

        @Test
        @DisplayName("(c) DefaultKbVerifier.verify flips SUPPORTED → UNKNOWN across the purge")
        void verifyFlipsSupportedToUnknown() {
            DefaultKbVerifier verifier = new DefaultKbVerifier(inferredStore, factStore);

            // Before retraction: inferred store has State(bob) → SUPPORTED
            VerifyResult before = verifier.verify(SOLE_TARGET);
            assertEquals(VerifyResult.Status.SUPPORTED, before.status(),
                    "State(bob) should be SUPPORTED before retraction (inferred store has it)");

            // Retract State(alice) + purge its sole dependents
            BeliefReviser.retractAndPurge(RETRACTED_FACT, factStore, index, inferredStore);

            // After purge: State(bob) gone from inferred store, also not in fact store → UNKNOWN
            VerifyResult after = verifier.verify(SOLE_TARGET);
            assertEquals(VerifyResult.Status.UNKNOWN, after.status(),
                    "State(bob) should be UNKNOWN after retractAndPurge removes stale inferred entry");
        }
    }

    // ─── Weakened atom survival ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Weakened atom (multi-support) survives purge")
    class WeakenedAtomSurvivalTest {

        @Test
        @DisplayName("State(bob) with two independent supports is NOT purged when alice is retracted")
        void weakenedAtomIsNotPurged() {
            PslProgram program = buildMultiSupportProgram();
            FactStore factStore = buildMultiSupportFactStore();
            InferredFactStore inferredStore = buildInferredStore("State(bob)");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            BeliefRevisionResult revision =
                    BeliefReviser.retractAndPurge("State(alice)", factStore, index, inferredStore);

            // State(bob) appears in weakenedAtoms (not unsupportedAtoms) because carol still supports it.
            assertTrue(revision.weakenedAtoms().contains("State(bob)") ||
                            revision.unsupportedAtoms().isEmpty() ||
                            !revision.unsupportedAtoms().contains("State(bob)"),
                    "State(bob) must not be in unsupportedAtoms when carol provides alternative support");

            // The inferred entry for State(bob) must survive because it has alternative support.
            assertTrue(inferredStore.latest("State(bob)").isPresent(),
                    "State(bob) inferred entry must survive purge when an alternative support exists");

            // purgedAtoms must NOT include State(bob).
            assertFalse(revision.purgedAtoms().contains("State(bob)"),
                    "purgedAtoms must not include State(bob) (it was only weakened, not solely dependent)");
        }
    }

    // ─── retractReviseAndPurge MAP freshness ──────────────────────────────────────

    @Nested
    @DisplayName("retractReviseAndPurge returns a non-null newResult reflecting the retraction")
    class RetractReviseAndPurgeTest {

        @Test
        @DisplayName("RevisionOutcome is non-null and newResult values map is non-empty")
        void revisionOutcomeIsNonNull() {
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();
            InferredFactStore inferredStore = buildInferredStore("State(bob)");

            HlMrfMapInference.Result initial = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(initial, factStore);

            BeliefReviser.RevisionOutcome outcome = BeliefReviser.retractReviseAndPurge(
                    "State(alice)", program, factStore, index, inferredStore);

            assertNotNull(outcome, "RevisionOutcome must not be null");
            assertNotNull(outcome.newResult(), "newResult must not be null");
            assertFalse(outcome.newResult().values().isEmpty(),
                    "newResult.values() must be non-empty after re-inference");
        }

        @Test
        @DisplayName("newResult MAP values reflect the retraction: State(bob) drops after alice removed")
        void newResultMapReflectsRetraction() {
            PslProgram programBefore = buildSingleSupportProgram();
            FactStore factStoreBefore = buildSingleSupportFactStore();
            // Solve once to get the pre-retraction value for comparison.
            HlMrfMapInference.Result before = HlMrfMapInference.solve(programBefore);
            double stateBobBefore = before.values().getOrDefault("State(bob)", 0.0);

            // Now set up fresh instances for the retract+revise path.
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();
            InferredFactStore inferredStore = buildInferredStore("State(bob)");

            HlMrfMapInference.Result initial = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(initial, factStore);

            BeliefReviser.RevisionOutcome outcome = BeliefReviser.retractReviseAndPurge(
                    "State(alice)", program, factStore, index, inferredStore);

            double stateBobAfter = outcome.newResult().values().getOrDefault("State(bob)", 0.0);

            // Without State(alice), the rule can no longer push State(bob) up; value should drop.
            assertTrue(stateBobAfter < stateBobBefore || stateBobAfter == 0.0,
                    "State(bob) MAP value should be lower (or zero) after alice is retracted; "
                            + "before=" + stateBobBefore + ", after=" + stateBobAfter);
        }
    }

    // ─── purgedAtoms and revisedAt metadata ──────────────────────────────────────

    @Nested
    @DisplayName("purgedAtoms and revisedAt are correctly populated")
    class MetadataTest {

        @Test
        @DisplayName("purgedAtoms contains every purged key and revisedAt is non-null")
        void purgedAtomsAndRevisedAtPopulated() {
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();
            InferredFactStore inferredStore = buildInferredStore("State(bob)");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            Instant before = Instant.now();
            BeliefRevisionResult revision =
                    BeliefReviser.retractAndPurge("State(alice)", factStore, index, inferredStore);
            Instant after = Instant.now();

            assertNotNull(revision.purgedAtoms(), "purgedAtoms must not be null");
            assertNotNull(revision.revisedAt(), "revisedAt must not be null");
            assertFalse(revision.revisedAt().isBefore(before),
                    "revisedAt must be at or after the call started");
            assertFalse(revision.revisedAt().isAfter(after),
                    "revisedAt must be at or before the call returned");
        }

        @Test
        @DisplayName("purgedAtoms is empty when no inferred entries match the unsupported set")
        void purgedAtomsEmptyWhenNothingToRemove() {
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();
            // InferredStore is EMPTY — nothing to purge.
            InferredFactStore inferredStore = new InMemoryInferredFactStore();

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            BeliefRevisionResult revision =
                    BeliefReviser.retractAndPurge("State(alice)", factStore, index, inferredStore);

            assertTrue(revision.purgedAtoms().isEmpty(),
                    "purgedAtoms must be empty when the inferred store has no matching entries");
        }
    }

    // ─── Legacy retract() compatibility ──────────────────────────────────────────

    @Nested
    @DisplayName("Legacy retract() is source-compatible and returns purgedAtoms=[] ")
    class LegacyRetractCompatibilityTest {

        @Test
        @DisplayName("retract() still compiles, behaves as before, and purgedAtoms is empty")
        void legacyRetractBehavesAsBeforeWithEmptyPurgedAtoms() {
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            int sizeBefore = factStore.size();
            BeliefRevisionResult revision = BeliefReviser.retract("State(alice)", factStore, index);

            assertNotNull(revision);
            assertEquals("State(alice)", revision.retractedFactKey());
            assertEquals(sizeBefore - 1, factStore.size(),
                    "Fact must have been removed from FactStore");
            assertNotNull(revision.unsupportedAtoms());
            assertNotNull(revision.weakenedAtoms());
            assertSame(factStore, revision.revisedFactStore());

            // New fields: legacy path must return empty purgedAtoms and a valid revisedAt.
            assertTrue(revision.purgedAtoms().isEmpty(),
                    "Legacy retract() must return purgedAtoms=[] (no InferredFactStore involved)");
            assertNotNull(revision.revisedAt(),
                    "Legacy retract() must set revisedAt");
        }

        @Test
        @DisplayName("retractAndRevise() is still source-compatible and returns purgedAtoms=[]")
        void legacyRetractAndReviseSourceCompatible() {
            PslProgram program = buildSingleSupportProgram();
            FactStore factStore = buildSingleSupportFactStore();

            HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
            JustificationIndex index = JustificationIndex.build(result, factStore);

            BeliefRevisionResult revision =
                    BeliefReviser.retractAndRevise("State(alice)", program, factStore, index);

            assertNotNull(revision);
            assertEquals("State(alice)", revision.retractedFactKey());
            assertFalse(revision.revisedFactStore().factFor("State(alice)").isPresent(),
                    "Retracted fact must be absent from the revised store");
            assertTrue(revision.purgedAtoms().isEmpty(),
                    "retractAndRevise() must return purgedAtoms=[] (no InferredFactStore purge)");
        }
    }
}
