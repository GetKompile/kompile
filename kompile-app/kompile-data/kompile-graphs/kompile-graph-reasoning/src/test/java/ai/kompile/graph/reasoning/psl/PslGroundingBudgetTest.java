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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PslGroundingBudgetTest {

    private static PslProgram mixedProgram() {
        return new PslProgram().observe("A", 1.0, "x").target("B", "x")
                .addRule("1.0: A(X) -> B(X)")
                .addArithmeticRule("B(X) <= 1 .");
    }

    private static PslProgram.GroundingLimits limits(int atoms, int rules, int incidences, long work) {
        return new PslProgram.GroundingLimits(atoms, rules, incidences, work);
    }

    private static void exceeded(PslProgram program, PslProgram.GroundingLimits limits, String cap) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> program.groundAll(limits));
        assertTrue(failure.getMessage().contains("grounding budget exceeded"), failure.getMessage());
        assertTrue(failure.getMessage().contains(cap), failure.getMessage());
    }

    @Test
    void legacyGroundingRemainsCompatible() {
        PslProgram program = mixedProgram();
        assertEquals(1, program.ground().size());
        assertEquals(1, program.groundArithmetic().size());
        assertFalse(program.isGroundingTruncated());
    }

    @Test
    void sharedBudgetReturnsBothKindsAndImmutableListsAtExactOutputLimits() {
        PslProgram program = mixedProgram();
        List<GroundRule> legacyLogical = program.ground();
        List<ArithmeticGroundRule> legacyArithmetic = program.groundArithmetic();
        PslProgram.GroundedProgram grounded = program.groundAll(limits(2, 2, 3, 100_000));
        assertEquals(legacyLogical, grounded.logicalRules());
        assertEquals(legacyArithmetic.size(), grounded.arithmeticRules().size());
        assertArrayEquals(legacyArithmetic.get(0).atomKeys(), grounded.arithmeticRules().get(0).atomKeys());
        assertArrayEquals(legacyArithmetic.get(0).coefficients(), grounded.arithmeticRules().get(0).coefficients());
        assertTrue(grounded.work() > 0);
        assertFalse(program.isGroundingTruncated());
        assertThrows(UnsupportedOperationException.class, () -> grounded.logicalRules().clear());
        assertThrows(UnsupportedOperationException.class, () -> grounded.arithmeticRules().clear());
        assertEquals(grounded.work(), program.groundAll(limits(2, 2, 3, grounded.work())).work());
        exceeded(program, limits(2, 2, 3, grounded.work() - 1), "maxWork");
    }

    @Test
    void resultCopiesCallerLists() {
        var logical = new java.util.ArrayList<GroundRule>();
        var arithmetic = new java.util.ArrayList<ArithmeticGroundRule>();
        var grounded = new PslProgram.GroundedProgram(logical, arithmetic, 0);
        logical.add(mixedProgram().ground().get(0));
        arithmetic.add(mixedProgram().groundArithmetic().get(0));
        assertTrue(grounded.logicalRules().isEmpty());
        assertTrue(grounded.arithmeticRules().isEmpty());
    }

    @Test
    void zeroOutputDistinctGuardExplosionStillExceedsWork() {
        PslProgram program = new PslProgram().addRule("1.0: P(X) & Q(Y) & X != X -> P(X)");
        for (int i = 0; i < 20; i++) {
            program.target("P", "p" + i).target("Q", "q" + i);
        }
        exceeded(program, limits(40, 0, 0, 200), "maxWork");
        assertTrue(program.ground().isEmpty());
    }

    @Test
    void predicateIndexBuildIsChargedEvenWithoutRulesAndWithWarmCache() {
        PslProgram program = new PslProgram();
        for (int i = 0; i < 20; i++) program.target("P", "p" + i);
        program.ground();
        exceeded(program, limits(20, 0, 0, 10), "maxWork");
        assertTrue(program.groundAll(limits(20, 0, 0, 100)).work() >= 20);
    }

    @Test
    void initialAtomsAreCappedBeforeGrounding() {
        exceeded(mixedProgram(), limits(1, 10, 10, 100_000), "maxAtoms");
    }

    @Test
    void cwaNewAtomsAreCappedBeforeRegistration() {
        PslProgram program = new PslProgram().target("Seed", "x").target("Seed", "y")
                .declareClosed("Missing", 1).addRule("1.0: Missing(X) -> Seed(X)");
        exceeded(program, limits(3, 10, 20, 100_000), "maxAtoms");
        assertEquals(3, program.atomCount(), "The over-budget atom must not be inserted");
    }

    @Test
    void cwaSucceedsWithinSharedAtomAndOutputLimits() {
        PslProgram program = new PslProgram().target("Seed", "x").target("Seed", "y")
                .declareClosed("Missing", 1).addRule("1.0: Missing(X) -> Seed(X)");
        var grounded = program.groundAll(limits(4, 2, 4, 100_000));
        assertEquals(2, grounded.logicalRules().size());
        assertEquals(4, program.atomCount());
        for (String constant : List.of("x", "y")) {
            String key = PslAtom.ground("Missing", constant).key();
            assertTrue(program.isObserved(key));
            assertEquals(0.0, program.value(key));
        }
    }

    @Test
    void cwaRepeatedVariableCartesianAllocationsAreChargedBeforeJoining() {
        PslProgram program = new PslProgram().declareClosed("Missing", 3)
                .addRule("1.0: Missing(X, X, X) -> Seed(X)");
        for (int i = 0; i < 20; i++) program.target("Seed", "s" + i);
        exceeded(program, limits(100, 100, 400, 1_000), "maxWork");
        assertEquals(20, program.atomCount(), "Work cap must stop Cartesian expansion before registration");
    }

    @Test
    void cwaConstantUniverseScanIsCharged() {
        PslProgram program = new PslProgram().declareClosed("Missing", 1)
                .addRule("1.0: Missing(X) -> Seed(X)");
        for (int i = 0; i < 20; i++) program.target("Seed", "s" + i);
        // Index construction fits, but scanning all atom arguments and building the CWA universe does not.
        exceeded(program, limits(100, 100, 400, 35), "maxWork");
        assertEquals(20, program.atomCount());
    }

    @Test
    void logicalAndArithmeticOutputsShareRuleAndIncidenceCaps() {
        exceeded(mixedProgram(), limits(2, 1, 100, 100_000), "maxRules");
        exceeded(mixedProgram(), limits(2, 10, 2, 100_000), "maxIncidences");
        PslProgram logical = new PslProgram().target("P", "x").addRule("1.0: P(X) -> P(X)");
        exceeded(logical, limits(1, 10, 1, 100_000), "maxIncidences");
    }

    @Test
    void multipleArithmeticTemplatesShareRuleCap() {
        PslProgram program = new PslProgram().target("P", "x")
                .addArithmeticRule("P(X) <= 1 .").addArithmeticRule("P(X) >= 0 .");
        exceeded(program, limits(1, 1, 100, 100_000), "maxRules");
        assertEquals(2, program.groundAll(limits(1, 2, 2, 100_000)).arithmeticRules().size());
    }

    @Test
    void arithmeticRawOuterBindingsAreChargedBeforeDeduplication() {
        PslProgram program = new PslProgram().addArithmeticRule("P(X, +Y) + Q(X, +Z) <= 1 .");
        for (int i = 0; i < 30; i++) {
            program.target("P", "x", "p" + i).target("Q", "x", "q" + i);
        }
        // All 900 raw bindings reduce to one outer binding, but allocations must still be bounded.
        exceeded(program, limits(60, 1, 60, 1_500), "maxWork");
        assertEquals(1, program.groundArithmetic().size());
    }

    @Test
    void arithmeticSummationScansAreChargedAndIncidencesAreCapped() {
        PslProgram program = new PslProgram().addArithmeticRule("P(X, +Y) <= 1 .");
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) program.target("P", "x" + x, "y" + y);
        }
        exceeded(program, limits(100, 10, 100, 3_000), "maxWork");
        exceeded(program, limits(100, 10, 99, 100_000), "maxIncidences");
        assertEquals(10, program.groundAll(limits(100, 10, 100, 100_000)).arithmeticRules().size());
    }

    @Test
    void activeBudgetIsResetAfterFailureAndSuccess() {
        PslProgram program = mixedProgram();
        exceeded(program, limits(2, 0, 0, 0), "maxWork");
        assertEquals(1, program.ground().size());
        assertEquals(1, program.groundArithmetic().size());
        assertEquals(1, program.groundAll(limits(2, 2, 3, 100_000)).logicalRules().size());
        program.target("A", "y").target("B", "y");
        assertEquals(2, program.ground().size());
        assertEquals(2, program.groundArithmetic().size());
    }

    @Test
    void registeredFunctionsAreRejectedWithoutExecutingCallbacks() {
        PslProgram program = mixedProgram().registerFunction("Unused", args -> {
            fail("Bounded grounding must not execute registered callbacks");
            return 1.0;
        });
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> program.groundAll(limits(2, 2, 3, 100_000)));
        assertTrue(failure.getMessage().contains("external functions"));
        assertEquals(1, program.ground().size());
    }

    @Test
    void limitsRejectNegativeValuesButAllowZero() {
        assertThrows(IllegalArgumentException.class, () -> limits(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> limits(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> limits(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> limits(0, 0, 0, -1));
        assertTrue(new PslProgram().groundAll(limits(0, 0, 0, 0)).logicalRules().isEmpty());
    }
}
