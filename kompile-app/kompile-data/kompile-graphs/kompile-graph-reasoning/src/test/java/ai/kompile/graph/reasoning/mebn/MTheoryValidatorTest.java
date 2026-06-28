/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.mebn.MTheoryValidator.ValidationResult;
import ai.kompile.graph.reasoning.mebn.MTheoryValidator.Violation;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MTheoryValidator}.
 *
 * <p>Covers the two structural rules checked by the validator:</p>
 * <ol>
 *   <li><b>DUPLICATE_HOME</b> — no two MFrags may define the same resident RV
 *       (same name + same argument-type list).</li>
 *   <li><b>DEPENDENCY_CYCLE</b> — no circular MFrag dependency (A has INPUT whose home is B,
 *       and B has INPUT whose home is A, etc.).</li>
 * </ol>
 */
class MTheoryValidatorTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // Happy path: a correctly built theory must pass
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void validBuiltTheoryPassesValidation() {
        // A theory produced by RelationalMTheoryBuilder must always be structurally valid.
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice"), List.of("acme")),
                new RelationDescriptor("ownedBy", "ORG", "PERSON", 0.6,
                        List.of("acme"), List.of("alice")));

        MTheory theory = RelationalMTheoryBuilder.build("test-theory", relations);
        ValidationResult result = MTheoryValidator.validate(theory);

        assertTrue(result.isValid(),
                "A properly constructed theory must pass validation. Violations: "
                        + result.violations());
    }

    @Test
    void emptyTheoryPassesValidation() {
        MTheory theory = new MTheory("empty");
        assertTrue(MTheoryValidator.validate(theory).isValid());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rule 1 — DUPLICATE_HOME
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Simulates a theory loaded from serialised form where two MFrags both claim the same
     * resident RV. Since {@link MTheory#addMFrag} enforces the invariant at build time, we
     * inject the second MFrag directly into the private {@code mFrags} map via reflection —
     * exactly the scenario the validator is designed to catch post-deserialisation.
     */
    @Test
    void duplicateHomeMFragCaughtByValidator() throws Exception {
        MTheory theory = new MTheory("dup-test");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        // First MFrag defining isActive(ENTITY) as resident — normal addMFrag.
        MFrag fragA = new MFrag("FragA");
        fragA.addResidentNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.RESIDENT));
        theory.addMFrag(fragA);

        // Second MFrag with the identical resident-RV signature — bypasses addMFrag guard
        // via reflection (simulates a deserialised theory with a corrupt duplicate).
        MFrag fragB = new MFrag("FragB");
        fragB.addResidentNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.RESIDENT));
        injectMFragDirectly(theory, fragB);

        ValidationResult result = MTheoryValidator.validate(theory);

        assertFalse(result.isValid(), "duplicate home-MFrag must be detected");
        List<Violation> dups = result.violations().stream()
                .filter(v -> "DUPLICATE_HOME".equals(v.kind()))
                .toList();
        assertEquals(1, dups.size(), "exactly one DUPLICATE_HOME violation expected");
        assertTrue(dups.get(0).message().contains("isActive"),
                "violation message must mention the duplicated RV name");
        assertTrue(dups.get(0).message().contains("FragA"),
                "violation message must mention the first defining MFrag");
        assertTrue(dups.get(0).message().contains("FragB"),
                "violation message must mention the second defining MFrag");
    }

    @Test
    void propositionalRvDuplicateAlsoCaught() throws Exception {
        MTheory theory = new MTheory("dup-prop");

        // Propositional RVs (no argument types) are keyed by bare name only.
        MFrag fragA = new MFrag("FragA");
        fragA.addResidentNode(RandomVariable.propositional("globalFlag", RandomVariable.NodeRole.RESIDENT));
        theory.addMFrag(fragA);

        MFrag fragB = new MFrag("FragB");
        fragB.addResidentNode(RandomVariable.propositional("globalFlag", RandomVariable.NodeRole.RESIDENT));
        injectMFragDirectly(theory, fragB);

        ValidationResult result = MTheoryValidator.validate(theory);

        assertFalse(result.isValid());
        assertTrue(result.violations().stream()
                .anyMatch(v -> "DUPLICATE_HOME".equals(v.kind())
                        && v.message().contains("globalFlag")));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rule 2 — DEPENDENCY_CYCLE
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void directTwoNodeCycleCaughtByValidator() {
        MTheory theory = new MTheory("cycle-test");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        // FragA: resident=isActive(ENTITY), input=isRelevant(ENTITY) → depends on FragB.
        MFrag fragA = new MFrag("FragA");
        fragA.addResidentNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.RESIDENT));
        fragA.addInputNode(RandomVariable.unary("isRelevant", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(fragA);

        // FragB: resident=isRelevant(ENTITY), input=isActive(ENTITY) → depends on FragA.
        // Both addMFrag calls succeed since the resident RV signatures are distinct.
        MFrag fragB = new MFrag("FragB");
        fragB.addResidentNode(RandomVariable.unary("isRelevant", et, RandomVariable.NodeRole.RESIDENT));
        fragB.addInputNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(fragB);

        ValidationResult result = MTheoryValidator.validate(theory);

        assertFalse(result.isValid(), "a two-node dependency cycle must be detected");
        assertTrue(result.violations().stream().anyMatch(v -> "DEPENDENCY_CYCLE".equals(v.kind())),
                "violation kind must be DEPENDENCY_CYCLE");
    }

    @Test
    void threeNodeCycleCaughtByValidator() {
        MTheory theory = new MTheory("three-cycle");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        // A → B → C → A (three-node cycle).
        MFrag fragA = new MFrag("FragA");
        fragA.addResidentNode(RandomVariable.unary("rvA", et, RandomVariable.NodeRole.RESIDENT));
        fragA.addInputNode(RandomVariable.unary("rvC", et, RandomVariable.NodeRole.INPUT));

        MFrag fragB = new MFrag("FragB");
        fragB.addResidentNode(RandomVariable.unary("rvB", et, RandomVariable.NodeRole.RESIDENT));
        fragB.addInputNode(RandomVariable.unary("rvA", et, RandomVariable.NodeRole.INPUT));

        MFrag fragC = new MFrag("FragC");
        fragC.addResidentNode(RandomVariable.unary("rvC", et, RandomVariable.NodeRole.RESIDENT));
        fragC.addInputNode(RandomVariable.unary("rvB", et, RandomVariable.NodeRole.INPUT));

        theory.addMFrag(fragA);
        theory.addMFrag(fragB);
        theory.addMFrag(fragC);

        ValidationResult result = MTheoryValidator.validate(theory);

        assertFalse(result.isValid(), "a three-node dependency cycle must be detected");
        assertTrue(result.violations().stream().anyMatch(v -> "DEPENDENCY_CYCLE".equals(v.kind())));
    }

    @Test
    void linearDependencyChainPassesValidation() {
        // A → B (B depends on A) with no cycle: valid theory.
        MTheory theory = new MTheory("chain");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        // Root: defines isActive, has no inputs.
        MFrag root = new MFrag("Root");
        root.addResidentNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.RESIDENT));
        theory.addMFrag(root);

        // Child: defines isRelevant, uses isActive as INPUT (depends on Root).
        MFrag child = new MFrag("Child");
        child.addResidentNode(RandomVariable.unary("isRelevant", et, RandomVariable.NodeRole.RESIDENT));
        child.addInputNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(child);

        ValidationResult result = MTheoryValidator.validate(theory);

        assertTrue(result.isValid(),
                "A → B linear dependency chain must pass validation. Violations: "
                        + result.violations());
    }

    @Test
    void selfLoopInInputDoesNotTriggerCycle() {
        // An INPUT that references an RV defined in the same MFrag is excluded from the
        // dependency graph (the validator filters self-references).
        MTheory theory = new MTheory("self-loop");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        MFrag frag = new MFrag("Self");
        frag.addResidentNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.RESIDENT));
        // Input that points back to the same fragment — not a real cycle.
        frag.addInputNode(RandomVariable.unary("isActive", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(frag);

        ValidationResult result = MTheoryValidator.validate(theory);

        // A self-reference is legal (denotes a recursive CPT); the validator must NOT flag it.
        assertFalse(result.violations().stream().anyMatch(v -> "DEPENDENCY_CYCLE".equals(v.kind())),
                "self-reference within the same MFrag is not a cycle");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // validateOrThrow
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void validateOrThrowDoesNotThrowForValidTheory() {
        MTheory theory = RelationalMTheoryBuilder.build("t",
                List.of(new RelationDescriptor("rel", "A", "B", 0.5, List.of(), List.of())));
        assertDoesNotThrow(() -> MTheoryValidator.validateOrThrow(theory));
    }

    @Test
    void validateOrThrowThrowsIllegalStateOnCycle() {
        MTheory theory = new MTheory("err");
        EntityType et = new EntityType("ENTITY");
        theory.addEntityType(et);

        MFrag a = new MFrag("A");
        a.addResidentNode(RandomVariable.unary("rvA", et, RandomVariable.NodeRole.RESIDENT));
        a.addInputNode(RandomVariable.unary("rvB", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(a);

        MFrag b = new MFrag("B");
        b.addResidentNode(RandomVariable.unary("rvB", et, RandomVariable.NodeRole.RESIDENT));
        b.addInputNode(RandomVariable.unary("rvA", et, RandomVariable.NodeRole.INPUT));
        theory.addMFrag(b);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MTheoryValidator.validateOrThrow(theory));
        assertTrue(ex.getMessage().contains("DEPENDENCY_CYCLE"),
                "exception message must identify the violation kind");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Bypass the {@link MTheory#addMFrag} duplicate guard and inject an MFrag directly into
     * the internal {@code mFrags} map. Used to simulate a deserialised theory that violated
     * the unique-home invariant so we can test the validator independently of addMFrag.
     */
    @SuppressWarnings("unchecked")
    private static void injectMFragDirectly(MTheory theory, MFrag frag) throws Exception {
        Field field = MTheory.class.getDeclaredField("mFrags");
        field.setAccessible(true);
        ((Map<String, MFrag>) field.get(theory)).put(frag.getName(), frag);
    }
}
