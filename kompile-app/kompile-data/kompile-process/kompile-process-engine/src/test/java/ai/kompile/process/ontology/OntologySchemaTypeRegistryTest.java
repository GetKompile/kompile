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

import ai.kompile.graph.reasoning.discovery.RelationNormalizer;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeConstraint;
import ai.kompile.graph.reasoning.mebn.type.TypeNode;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void inferredMembers_addEntitiesToTypesTheyAreNotExplicitlyTypedAs() {
        OntologySchema schema = OntologySchema.builder()
                .name("functional-typing")
                .entityTypes(List.of(EntityTypeDefinition.builder().name("Packaging").build()))
                .build();

        // paper1 is graph-typed "OfficeSupply" but OWL-inferred to ALSO be "Packaging".
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("paper1", "OfficeSupply", "Printer paper");

        TypeHierarchy hierarchy = OntologySchemaTypeRegistry.toHierarchy(
                schema, graph, java.util.Map.of("Packaging", List.of("paper1")));

        assertTrue(hierarchy.entitiesOfType("Packaging", false).contains("paper1"),
                "An OWL-inferred member should belong to its inferred type even without explicit typing");
        assertTrue(hierarchy.entitiesOfType("OfficeSupply", false).contains("paper1"),
                "Exact graph membership is preserved");
    }

    @Test
    void ontologyRelationships_compileRelationNormalizationSchemas() {
        OntologySchema schema = OntologySchema.builder()
                .name("relation-normalization")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder().name("ApprovalRole").build(),
                        EntityTypeDefinition.builder().name("CloseStep").build()))
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("APPROVED_BY")
                                .canonicalType("APPROVED_BY")
                                .sourceEntityType("ApprovalRole")
                                .targetEntityType("CloseStep")
                                .observedTypes(List.of("APPROVES"))
                                .inverseTypes(List.of("AUTHORIZED_BY"))
                                .actionCategories(List.of("APPROVAL"))
                                .controlSignatures(List.of("CLOSE_APPROVAL_GATE"))
                                .policyMetadata(Map.of("approvalPolicy", "owner sign-off"))
                                .metadata(Map.of(
                                        "inverseTypes", "SIGNED_OFF_BY, OWNED_BY",
                                        "domain", "Approver",
                                        "range", "CloseActivity"))
                                .build()))
                .build();

        RelationNormalizer.RelationSchema compiled = OntologyRelationSchemaCompiler
                .toRelationSchemas(schema)
                .get(0);

        assertEquals("APPROVED_BY", compiled.canonicalType());
        assertTrue(compiled.observedTypes().contains("APPROVES"));
        assertTrue(compiled.observedTypes().contains("SIGNED_OFF_BY"));
        assertTrue(compiled.observedTypes().contains("AUTHORIZED_BY"));
        assertTrue(compiled.sourceTypes().contains("APPROVALROLE"));
        assertTrue(compiled.sourceTypes().contains("APPROVER"));
        assertTrue(compiled.targetTypes().contains("CLOSESTEP"));
        assertTrue(compiled.targetTypes().contains("CLOSEACTIVITY"));
        assertTrue(compiled.flipWhenSwapped());

        OntologyRelationSchemaCompiler.ProcessSemanticProfile profile = OntologyRelationSchemaCompiler
                .toProcessSemanticProfiles(schema)
                .get(0);
        assertTrue(profile.matchesRelationType("authorized by"));
        assertTrue(profile.actionCategories().contains("APPROVAL"));
        assertTrue(profile.controlSignatures().contains("CLOSE_APPROVAL_GATE"));
        assertEquals("owner sign-off", profile.policyMetadata().get("approvalPolicy"));
    }

    @Test
    void schemaFieldsAndRelationships_feedAttributeSchemasAndTypeConstraints() {
        OntologySchema schema = OntologySchema.builder()
                .name("schema-constraints")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder()
                                .name("Person")
                                .build(),
                        EntityTypeDefinition.builder()
                                .name("Employee")
                                .fields(List.of(
                                        FieldDefinition.builder()
                                                .name("employeeId")
                                                .type(FieldType.STRING)
                                                .required(true)
                                                .build(),
                                        FieldDefinition.builder()
                                                .name("manager")
                                                .type(FieldType.STRING)
                                                .fkReference("Person.id")
                                                .build()))
                                .build()))
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("MANAGES")
                                .sourceEntityType("Employee")
                                .targetEntityType("Person")
                                .cardinality(Cardinality.ONE_TO_MANY)
                                .build()))
                .build();

        TypeHierarchy hierarchy = OntologySchemaTypeRegistry.toHierarchy(schema, new MutableReasoningGraph());
        TypeNode employee = hierarchy.forType("Employee").orElseThrow();

        assertTrue(employee.getAttributeSchema().requiredAttributes().stream()
                        .anyMatch(attr -> attr.getName().equals("employeeId")),
                "Required schema fields must reach the TypeAttributeSchema");
        assertEquals("Person", employee.getAttributeSchema().attribute("manager").orElseThrow().getRefersToType(),
                "FK references should preserve the referenced entity type");
        assertTrue(employee.getConstraints().stream()
                        .anyMatch(TypeConstraint.RelationConstraint.class::isInstance),
                "Relationship source/target declarations must become relation constraints");
        assertTrue(employee.getConstraints().stream()
                        .filter(TypeConstraint.CardinalityConstraint.class::isInstance)
                        .map(TypeConstraint.CardinalityConstraint.class::cast)
                        .anyMatch(c -> c.cardinality() == TypeConstraint.Cardinality.ONE_TO_MANY),
                "Relationship cardinality must be preserved on the type node");
    }
}
