/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.ontology;

import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the schema → {@link TypeHierarchy} feeder: an {@link OntologySchema} whose
 * {@link EntityTypeDefinition#getParentType() parentType} declares is-a links produces a hierarchy
 * in which an RV over a supertype sees subtype instances (subsumption membership).
 */
class OntologySchemaTypeRegistryTest {

    @Test
    void schemaParentType_buildsIsAHierarchy_andSubsumptionMembership() {
        OntologySchema schema = OntologySchema.builder()
                .name("test")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder().name("Person").build(),
                        EntityTypeDefinition.builder().name("Employee").parentType("Person").build()))
                .build();

        // One Employee instance; nothing is typed exactly "Person".
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("alice", "Employee", "Alice");

        TypeHierarchy hierarchy = OntologySchemaTypeRegistry.toHierarchy(schema, graph);

        assertTrue(hierarchy.isA("Employee", "Person"),
                "Schema parentType should declare Employee isA Person");
        assertTrue(hierarchy.entitiesOfType("Person", true).contains("alice"),
                "An Employee instance must be a subsumption-member of Person");
        assertFalse(hierarchy.entitiesOfType("Person", false).contains("alice"),
                "Direct (non-subsumption) membership of Person must NOT include the Employee");
    }

    @Test
    void nullSchema_yieldsEmptyDeclarations() {
        // No schema → no declared is-a links; membership-only hierarchy is still valid.
        TypeHierarchy hierarchy =
                OntologySchemaTypeRegistry.toHierarchy(null, new MutableReasoningGraph());
        assertTrue(hierarchy.allTypeNames().isEmpty(),
                "Null schema over an empty graph yields an empty hierarchy");
    }
}
