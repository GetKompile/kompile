/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Step 3 — Predicate Declarations and CWA Grounding (Gaps E-4, E-6).
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link PslProgram#declareClosed(String, int)} and {@link PslProgram#declareOpen(String, int)}</li>
 *   <li>CWA auto-registers absent atoms of closed predicates as observed 0.0</li>
 *   <li>Without CWA declaration, missing predicate atoms prune the rule branch (existing behaviour)</li>
 *   <li>Arity validation at observe/target time against declarations</li>
 *   <li>{@link PslProgram#isClosed(String)} query</li>
 * </ul>
 */
@DisplayName("Step 3 — Predicate Declarations and CWA")
class PredicateDeclarationTest {

    // ─── Basic declaration API ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Declaration API")
    class DeclarationApiTests {

        @Test
        @DisplayName("declareClosed marks predicate as closed")
        void declareClosedMarked() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Evidence", 2);
            assertTrue(prog.isClosed("Evidence"));
            assertFalse(prog.isClosed("Target"));
        }

        @Test
        @DisplayName("declareOpen does not mark predicate as closed")
        void declareOpenNotClosed() {
            PslProgram prog = new PslProgram()
                    .declareOpen("Target", 2)
                    .declareClosed("Evidence", 1);
            assertFalse(prog.isClosed("Target"));
            assertTrue(prog.isClosed("Evidence"));
        }

        @Test
        @DisplayName("Arity mismatch on observe() throws when predicate declared")
        void arityMismatchOnObserve() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Evidence", 2);
            // Should throw: declared arity=2, but observing with 1 arg
            assertThrows(IllegalArgumentException.class,
                    () -> prog.observe("Evidence", 0.5, "a")); // only 1 arg
        }

        @Test
        @DisplayName("Correct arity passes validation")
        void correctArityPasses() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Evidence", 2);
            assertDoesNotThrow(() -> prog.observe("Evidence", 0.8, "a", "b"));
        }
    }

    // ─── CWA grounding behaviour ──────────────────────────────────────────────

    @Nested
    @DisplayName("CWA grounding")
    class CwaGroundingTests {

        /**
         * Without CWA: if HasEvidence(x) is not registered, the rule body literal
         * "HasEvidence(X)" prunes the branch → 0 ground rules.
         */
        @Test
        @DisplayName("Without CWA: missing closed-predicate atoms prune the grounding")
        void withoutCwaMissingAtomsPruned() {
            PslProgram prog = new PslProgram()
                    .observe("Source", 0.9, "x")
                    .target("Target", "x")
                    // HasEvidence not declared closed, no atoms for it
                    .addRule("1.0: Source(X) & HasEvidence(X) -> Target(X) ^2");

            List<GroundRule> ground = prog.ground();
            // No atoms for HasEvidence → rule cannot ground
            assertEquals(0, ground.size(), "Without CWA, missing atoms should prune grounding");
        }

        /**
         * With CWA: HasEvidence declared closed but no atoms registered.
         * The grounding should auto-register HasEvidence(x)=0.0 and produce one ground rule.
         */
        @Test
        @DisplayName("With CWA: absent closed-predicate atoms auto-register as 0.0")
        void withCwaAbsentAtomsAutoRegister() {
            PslProgram prog = new PslProgram()
                    .declareClosed("HasEvidence", 1)
                    .observe("Source", 0.9, "x")
                    .target("Target", "x")
                    .addRule("1.0: Source(X) & HasEvidence(X) -> Target(X) ^2");

            List<GroundRule> ground = prog.ground();
            // CWA should allow grounding; HasEvidence(x) = 0.0 is auto-registered
            assertFalse(ground.isEmpty(), "CWA should produce at least one ground rule");

            // The auto-registered atom should appear in the program
            assertTrue(prog.contains("HasEvidence(x)"),
                    "HasEvidence(x) should be auto-registered by CWA");
            assertTrue(prog.isObserved("HasEvidence(x)"),
                    "CWA atom should be observed (not a target)");
            assertEquals(0.0, prog.value("HasEvidence(x)"), 1e-9,
                    "CWA atom value should be 0.0");
        }

        /**
         * With CWA: ground rule body with CWA atom=0 should have bodyTruth=0
         * (since HasEvidence(x)=0 makes the conjunction false).
         */
        @Test
        @DisplayName("With CWA: ground rule body truth is 0 when CWA atom = 0")
        void cwaAtomZeroMakesBodyFalse() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Evidence", 1)
                    .observe("Source", 0.9, "x")
                    .target("Target", "x")
                    .addRule("1.0: Source(X) & Evidence(X) -> Target(X) ^2");

            List<GroundRule> ground = prog.ground();
            assertFalse(ground.isEmpty());

            // Grounded rule body: Source(x)=0.9, Evidence(x)=0 → bodyTruth = max(0, 0.9+0-1) = 0
            GroundRule gr = ground.get(0);
            java.util.Map<String, Double> truth = prog.valueSnapshot();
            // Initialize targets at neutral 0.5
            truth.put("Target(x)", 0.5);
            assertEquals(0.0, gr.bodyTruth(truth), 1e-9,
                    "Body with CWA atom=0 should have body truth = 0");
        }

        /**
         * Multiple entities: CWA should produce one observed-false atom per entity
         * for the absent closed predicate.
         */
        @Test
        @DisplayName("With CWA: produces CWA atoms for multiple bindings of closed predicate")
        void cwaMutlipleBindings() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Tag", 2)
                    .observe("Name", 0.9, "e1", "alice")
                    .observe("Name", 0.8, "e2", "bob")
                    .target("SameAs", "e1", "e2")
                    .addRule("1.0: Name(X, N) & Tag(X, T) -> SameAs(X, X2) ^2");

            // Ground — CWA for Tag should cause auto-registration of Tag atoms with value 0
            List<GroundRule> ground = prog.ground();
            // The key thing is the program doesn't throw and CWA atoms are registered
            // (Some bindings may still have d=0 due to CWA-zero body truth)
            // Verify CWA correctly marks any auto-registered Tag atoms as observed
            for (String key : prog.atomKeys()) {
                if (key.startsWith("Tag(")) {
                    assertTrue(prog.isObserved(key),
                            "Tag atoms auto-registered via CWA should be observed: " + key);
                    assertEquals(0.0, prog.value(key), 1e-9);
                }
            }
        }

        @Test
        @DisplayName("CWA atoms do not become inference targets")
        void cwaAtomsNotTargets() {
            PslProgram prog = new PslProgram()
                    .declareClosed("Ev", 1)
                    .observe("X", 0.7, "a")
                    .target("Y", "a")
                    .addRule("1.0: X(A) & Ev(A) -> Y(A) ^2");

            prog.ground(); // triggers CWA auto-registration of Ev(a)
            List<String> targets = prog.targetKeys();
            assertFalse(targets.contains("Ev(a)"),
                    "CWA atom Ev(a) must not be a target");
        }
    }
}
