/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.mebn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelationalMTheoryArtifactCodecTest {

    @Test
    void roundTripRebuildsCanonicalConstraintsAndStrengths() {
        MTheory original = RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.77,
                        List.of("alice"), List.of("doc-1"))));

        MTheory restored = RelationalMTheoryArtifactCodec.fromJson(
                RelationalMTheoryArtifactCodec.toJson(original));

        MFrag relation = restored.getMFrag("supports");
        assertEquals(0.77, relation.getEdgeStrength("isRelevant", "supports"), 1e-12);
        assertEquals(4, relation.getContextConstraints().size());
        assertEquals(List.of("alice"), restored.getEntityType("PERSON").getEntityIds().stream().toList());
        assertEquals(List.of("doc-1"), restored.getEntityType("DOCUMENT").getEntityIds().stream().toList());
    }

    @Test
    void restrictedCopyPreservesCanonicalSemanticsAndDoesNotMutateSource() {
        MTheory original = RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.77,
                        List.of("alice", "bob"), List.of("doc-1", "doc-2"))));

        MTheory restricted = RelationalMTheoryArtifactCodec.restrictToEntityIds(
                original, Set.of("alice", "doc-1"));

        assertNotSame(original, restricted);
        assertEquals(Set.of("alice"), restricted.getEntityType("PERSON").getEntityIds());
        assertEquals(Set.of("doc-1"), restricted.getEntityType("DOCUMENT").getEntityIds());
        assertEquals(0.77, restricted.getMFrag("supports")
                .getEdgeStrength("isRelevant", "supports"), 1e-12);
        assertEquals(4, restricted.getMFrag("supports").getContextConstraints().size());
        assertEquals(Set.of("alice", "bob"), original.getEntityType("PERSON").getEntityIds());
        assertEquals(Set.of("doc-1", "doc-2"), original.getEntityType("DOCUMENT").getEntityIds());
    }

    @Test
    void rejectsUnsupportedVersion() {
        String json = RelationalMTheoryArtifactCodec.toJson(
                RelationalMTheoryBuilder.build("portable", List.of()))
                .replace("\"version\":1", "\"version\":2");

        assertThrows(IllegalArgumentException.class,
                () -> RelationalMTheoryArtifactCodec.fromJson(json));
    }

    @Test
    void rejectsDuplicateRelationNamesInsteadOfDroppingSemantics() {
        String row = """
                {"name":"supports","sourceType":"PERSON","targetType":"DOCUMENT",
                 "strength":0.7,"sourceEntityIds":["alice"],"targetEntityIds":["doc-1"]}
                """.trim();
        String json = """
                {"format":"kompile-relational-mtheory","version":1,"builder":"relational-v1",
                 "theoryName":"portable","relations":[%s,%s]}
                """.formatted(row, row);

        assertThrows(IllegalArgumentException.class,
                () -> RelationalMTheoryArtifactCodec.fromJson(json));
    }

    @Test
    void rejectsNoncanonicalTheoryInsteadOfSilentlyChangingSemantics() {
        MTheory noncanonical = new MTheory("custom");
        EntityType type = new EntityType("PERSON");
        type.addEntity("alice");
        noncanonical.addEntityType(type);
        MFrag fragment = new MFrag("custom");
        fragment.addResidentNode(RandomVariable.unary(
                "custom", type, RandomVariable.NodeRole.RESIDENT));
        noncanonical.addMFrag(fragment);

        assertThrows(IllegalArgumentException.class,
                () -> RelationalMTheoryArtifactCodec.toJson(noncanonical));
    }
}
