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

import ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure MEBN-library tests for {@link RelationalMTheoryBuilder}.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>Typed EntityType per relation (distinct per source/target type name, all subtypes of
 *       AllNodes for isA-polymorphic SSBN lookup).</li>
 *   <li>Binary resident RV typed by {@code (SourceType, TargetType)} — distinct RV signature
 *       per type pair.</li>
 *   <li>IsA context constraints ({@code hasType}) present for both arg positions, referencing
 *       arg-var names that align exactly with the resident RV's default arg-var scheme
 *       ({@code TypeName_0}/{@code TypeName_1}).</li>
 *   <li>{@code isRelevant} INPUT uses the source entity type so its arg-var ({@code srcType_0})
 *       matches the binary RV's first argument for correct SSBN binding.</li>
 *   <li>The key safety property: noisy-OR weight learner sees one template edge per fragment,
 *       independent of the entity-set size — SSBN/learning cost stays bounded.</li>
 * </ul>
 */
class RelationalMTheoryBuilderTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // Structure: frags, entity types, resident RV arity
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void buildsRelevanceFragPlusOneBinaryFragPerRelation() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("SHARED_ENTITY", "ENTITY", "ENTITY", 0.7,
                        List.of("a", "b", "c"), List.of("a", "b", "c")),
                new RelationDescriptor("CITATION", "ENTITY", "ENTITY", 0.9,
                        List.of("a", "b", "c"), List.of("a", "b", "c")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        assertNotNull(theory.getMFrag("EntityRelevance"), "foundational relevance fragment");
        assertEquals(3, theory.getMFrags().size(), "relevance + 2 relation fragments");
        // AllNodes + ENTITY (shared source and target type for both relations)
        assertEquals(2, theory.getEntityTypes().size(),
                "AllNodes supertype + one ENTITY type");

        MFrag shared = theory.getMFrag("SHARED_ENTITY");
        assertNotNull(shared);

        // Resident: a binary, typed relationship RV.
        RandomVariable resident = shared.getResidentNodes().get(0);
        assertEquals("SHARED_ENTITY", resident.getName());
        assertEquals(2, resident.getArity(), "binary relationship RV over (srcType, tgtType)");
        assertEquals(RandomVariable.NodeRole.RESIDENT, resident.getRole());

        // Input parent: isRelevant, with the supplied activation probability.
        assertEquals("isRelevant", shared.getInputNodes().get(0).getName());
        assertEquals(0.7, shared.getEdgeStrength("isRelevant", "SHARED_ENTITY"), 1e-9);

        // hasType(IsA) × 2 + notEqual + edgeExists = 4 context constraints.
        assertEquals(4, shared.getContextConstraints().size());
    }

    @Test
    void emptyRelationsYieldsRelevanceFragmentOnly() {
        MTheory theory = RelationalMTheoryBuilder.build("t", List.of());
        assertEquals(1, theory.getMFrags().size());
        assertNotNull(theory.getMFrag("EntityRelevance"));
        // Only AllNodes entity type when there are no relation descriptors.
        assertEquals(1, theory.getEntityTypes().size());
    }

    @Test
    void nullRelationsYieldsRelevanceFragmentOnly() {
        MTheory theory = RelationalMTheoryBuilder.build("t", null);
        assertEquals(1, theory.getMFrags().size());
        assertNotNull(theory.getMFrag("EntityRelevance"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Typed EntityTypes and IsA supertype hierarchy
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void typedEntityTypesCreatedForEachDistinctTypeName() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice", "bob"), List.of("acme")),
                new RelationDescriptor("ownedBy", "ORG", "PERSON", 0.6,
                        List.of("acme"), List.of("alice")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        // AllNodes + PERSON + ORG = 3 distinct EntityTypes
        assertEquals(3, theory.getEntityTypes().size());
        assertNotNull(theory.getEntityType("AllNodes"), "base AllNodes type");
        assertNotNull(theory.getEntityType("PERSON"), "source type from first relation");
        assertNotNull(theory.getEntityType("ORG"),    "target type from first relation");
    }

    @Test
    void perTypeEntityTypesAreSubtypesOfAllNodes() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("rel", "PERSON", "ORG", 0.5,
                        List.of("p1"), List.of("o1")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        EntityType allNodes = theory.getEntityType(RelationalMTheoryBuilder.ALL_NODES_TYPE);
        EntityType person = theory.getEntityType("PERSON");
        EntityType org = theory.getEntityType("ORG");

        assertNotNull(allNodes);
        assertNotNull(person);
        assertNotNull(org);
        // IsA hierarchy: PERSON is subtype of AllNodes → SSBN lookup can walk chain to isRelevant
        assertSame(allNodes, person.getSuperType(),
                "PERSON.getSuperType() must be AllNodes for isA-polymorphic SSBN resolution");
        assertSame(allNodes, org.getSuperType(),
                "ORG.getSuperType() must be AllNodes");
    }

    @Test
    void entityIdsPopulatedInBothSpecificTypeAndAllNodes() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("rel", "PERSON", "ORG", 0.5,
                        List.of("p1", "p2"), List.of("o1")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        EntityType allNodes = theory.getEntityType(RelationalMTheoryBuilder.ALL_NODES_TYPE);
        EntityType person = theory.getEntityType("PERSON");
        EntityType org = theory.getEntityType("ORG");

        // Per-type sets
        assertTrue(person.getEntityIds().containsAll(List.of("p1", "p2")));
        assertTrue(org.getEntityIds().contains("o1"));
        // AllNodes is the union
        assertTrue(allNodes.getEntityIds().containsAll(List.of("p1", "p2", "o1")));
    }

    @Test
    void sameSourceAndTargetTypeMergesIntoOneEntityType() {
        // ENTITY → ENTITY: only one ENTITY EntityType should exist (not two duplicates).
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("links", "ENTITY", "ENTITY", 0.5,
                        List.of("e1", "e2"), List.of("e2", "e3")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        // AllNodes + ENTITY = 2 (not 3)
        assertEquals(2, theory.getEntityTypes().size());
        EntityType entityType = theory.getEntityType("ENTITY");
        assertNotNull(entityType);
        // All four IDs should be in the single ENTITY type
        assertTrue(entityType.getEntityIds().containsAll(List.of("e1", "e2", "e3")));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Typed binary resident RV
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void residentRvTypedBySourceAndTargetEntityType() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice"), List.of("acme")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("worksAt");
        assertNotNull(frag);

        RandomVariable resident = frag.getResidentNodes().get(0);
        assertEquals(2, resident.getArity(), "binary RV");
        assertEquals("PERSON", resident.getArgumentTypes().get(0).getTypeName(),
                "first argument type = source type");
        assertEquals("ORG", resident.getArgumentTypes().get(1).getTypeName(),
                "second argument type = target type");
    }

    @Test
    void argVarNamesFollowTypeNameUnderscoreIndexConvention() {
        // Arg-var names must be TypeName_0 and TypeName_1 so context constraints can reference them.
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice"), List.of("acme")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("worksAt");

        RandomVariable resident = frag.getResidentNodes().get(0);
        List<String> argVars = resident.getArgVars();
        assertEquals(2, argVars.size());
        assertEquals("PERSON_0", argVars.get(0));
        assertEquals("ORG_1", argVars.get(1));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // IsA context constraints
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void isAContextConstraintsPresentForBothArgPositions() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice"), List.of("acme")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("worksAt");
        List<LogicalConstraint> ctxs = frag.getContextConstraints();

        // There are 4 context constraints total.
        assertEquals(4, ctxs.size(),
                "IsA(src,SrcType) + IsA(tgt,TgtType) + notEqual + edgeExists");

        // IsA(src, PERSON) → hasType(PERSON_0, PERSON)
        assertTrue(ctxs.stream().anyMatch(c -> c.describe().contains("hasType(PERSON_0, PERSON)")),
                "IsA(arg0, PERSON) context constraint missing");
        // IsA(tgt, ORG) → hasType(ORG_1, ORG)
        assertTrue(ctxs.stream().anyMatch(c -> c.describe().contains("hasType(ORG_1, ORG)")),
                "IsA(arg1, ORG) context constraint missing");
    }

    @Test
    void argVarInIsAConstraintMatchesResidentRvArgVar() {
        // The hasType constraint's variable name must equal the arg-var produced by the binary RV
        // default constructor, so the SSBN generator can unify logical variables.
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("rel", "PERSON", "ORG", 0.5,
                        List.of("p1"), List.of("o1")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("rel");

        RandomVariable resident = frag.getResidentNodes().get(0);
        String srcArgVar = resident.getArgVars().get(0); // "PERSON_0"
        String tgtArgVar = resident.getArgVars().get(1); // "ORG_1"

        // The free variables of each IsA constraint must contain the matching arg-var.
        List<LogicalConstraint> ctxs = frag.getContextConstraints();
        assertTrue(ctxs.stream().anyMatch(c ->
                c.describe().contains("hasType(" + srcArgVar + ",") ||
                c.describe().contains("hasType(" + srcArgVar + " ,")),
                "IsA(src, ...) constraint must reference arg-var '" + srcArgVar + "'");
        assertTrue(ctxs.stream().anyMatch(c ->
                c.describe().contains("hasType(" + tgtArgVar + ",") ||
                c.describe().contains("hasType(" + tgtArgVar + " ,")),
                "IsA(tgt, ...) constraint must reference arg-var '" + tgtArgVar + "'");
    }

    @Test
    void notEqualAndEdgeExistsContextsUseTypedArgVars() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("rel", "PERSON", "ORG", 0.5,
                        List.of("p1"), List.of("o1")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("rel");

        List<LogicalConstraint> ctxs = frag.getContextConstraints();

        // notEqual must reference the typed arg-var names.
        assertTrue(ctxs.stream().anyMatch(c -> c.describe().contains("PERSON_0")
                && c.describe().contains("PERSON_0 != ORG_1")),
                "notEqual must reference PERSON_0 and ORG_1");
        // edgeExists must reference the typed arg-var names.
        assertTrue(ctxs.stream().anyMatch(c -> c.describe().equals("edgeExists(PERSON_0, ORG_1)")),
                "edgeExists must reference PERSON_0 and ORG_1");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // isRelevant INPUT — source-type typed for SSBN binding alignment
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void isRelevantInputTypedBySourceTypeForSsbnBindingAlignment() {
        // isRelevant(SourceType) has arg-var SourceTypeName_0, which matches the binary RV's
        // first position so the SSBN generator can bind the source entity to both nodes.
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("worksAt", "PERSON", "ORG", 0.8,
                        List.of("alice"), List.of("acme")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);
        MFrag frag = theory.getMFrag("worksAt");

        RandomVariable isRelevantInput = frag.getInputNodes().stream()
                .filter(rv -> "isRelevant".equals(rv.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(isRelevantInput, "isRelevant INPUT node must be present");
        assertEquals(1, isRelevantInput.getArity(), "unary — one entity argument");
        assertEquals("PERSON", isRelevantInput.getArgumentTypes().get(0).getTypeName(),
                "INPUT isRelevant typed by source type PERSON, not AllNodes");
        // Arg-var of isRelevant must match binary RV's first arg-var.
        assertEquals("PERSON_0", isRelevantInput.getArgVars().get(0),
                "isRelevant arg-var must equal the binary RV source arg-var PERSON_0");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Activation probability
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void activationProbabilitySetAsParentEdgeStrength() {
        List<RelationDescriptor> relations = List.of(
                new RelationDescriptor("SHARED_ENTITY", "ENTITY", "ENTITY", 0.73,
                        List.of("a"), List.of("b")));

        MTheory theory = RelationalMTheoryBuilder.build("t", relations);

        assertEquals(0.73,
                theory.getMFrag("SHARED_ENTITY").getEdgeStrength("isRelevant", "SHARED_ENTITY"),
                1e-9, "parent edge strength = supplied activationProbability");
    }

    @Test
    void activationProbabilityValidationRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new RelationDescriptor("r", "A", "B", 1.5, List.of(), List.of()),
                "activationProbability > 1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new RelationDescriptor("r", "A", "B", -0.1, List.of(), List.of()),
                "activationProbability < 0 must be rejected");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Learning-input boundedness (key safety property)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void learningInputBoundedByFragmentCountNotEntityCount() {
        // A large entity set must NOT increase the learner's template-edge count.
        List<RelationDescriptor> smallRelations = List.of(
                new RelationDescriptor("SHARED_ENTITY", "ENTITY", "ENTITY", 0.5,
                        List.of("a", "b"), List.of("a", "b")),
                new RelationDescriptor("CITATION", "ENTITY", "ENTITY", 0.5,
                        List.of("a", "b"), List.of("a", "b")));

        List<RelationDescriptor> largeRelations = List.of(
                new RelationDescriptor("SHARED_ENTITY", "ENTITY", "ENTITY", 0.5,
                        List.of("a", "b", "c", "d", "e", "f", "g", "h"),
                        List.of("a", "b", "c", "d", "e", "f", "g", "h")),
                new RelationDescriptor("CITATION", "ENTITY", "ENTITY", 0.5,
                        List.of("a", "b", "c", "d", "e", "f", "g", "h"),
                        List.of("a", "b", "c", "d", "e", "f", "g", "h")));

        MTheory small = RelationalMTheoryBuilder.build("t", smallRelations);
        MTheory large = RelationalMTheoryBuilder.build("t", largeRelations);

        assertEquals(2, SameDiffMebnStrengthLearner.collectEdges(small).size());
        assertEquals(2, SameDiffMebnStrengthLearner.collectEdges(large).size(),
                "one template parent edge per relation fragment, independent of |entities|");
    }
}
