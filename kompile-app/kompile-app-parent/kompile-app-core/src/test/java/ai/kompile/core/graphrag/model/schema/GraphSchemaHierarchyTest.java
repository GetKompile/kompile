/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.core.graphrag.model.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSchemaHierarchyTest {

    @Test
    void baselineContainsStableEntityRootsButNoGenericRelationshipPredicates() {
        GraphSchema baseline = SchemaHierarchyVocabulary.baselineSchema();

        assertEquals(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES,
                baseline.getNodeTypes().stream().map(NodeType::getLabel).toList());
        assertEquals("CREATIVE_WORK", baseline.getNodeParentTypes().get("DOCUMENT"));
        assertEquals("DOCUMENT", baseline.getNodeParentTypes().get("LAW"));
        assertTrue(baseline.isNodeTypeAssignableTo("LAW", "CREATIVE_WORK"));
        assertTrue(baseline.getAllRelationshipTypes().isEmpty());
        assertTrue(SchemaHierarchyVocabulary.isConnectionFamily("COMMUNICATION"));
        assertFalse(SchemaHierarchyVocabulary.isConnectionFamily("EMAILED"));
    }

    @Test
    void resolvesTransitiveSubtypeAssignmentsAndInheritedProperties() {
        GraphSchema schema = SchemaHierarchyVocabulary.withBaseline(new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", List.of(
                                new PropertyType("name", "String"))),
                        new NodeType("EMPLOYEE", "An employee", List.of(
                                new PropertyType("employeeId", "String")), "PERSON"),
                        new NodeType("EXECUTIVE", "An executive", List.of(
                                new PropertyType("level", "String")), "EMPLOYEE")),
                List.of(new RelationshipType("EMAILED", "Sent an email", null,
                        List.of("sent_email_to"), "COMMUNICATION")),
                List.of("(PERSON)-[:EMAILED]->(PERSON)")));

        assertTrue(schema.isNodeTypeAssignableTo("EXECUTIVE", "PERSON"));
        assertTrue(schema.isNodeTypeAssignableTo("EXECUTIVE", "EMPLOYEE"));
        assertFalse(schema.isNodeTypeAssignableTo("PERSON", "EXECUTIVE"));
        assertEquals(List.of("name", "employeeId", "level"),
                schema.getEffectiveNodeProperties("EXECUTIVE").stream()
                        .map(PropertyType::getName).toList());
        assertEquals("COMMUNICATION",
                schema.getRelationshipConnectionFamilies().get("EMAILED"));
    }

    @Test
    void legacyAndHierarchicalJsonRemainCompatible() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NodeType legacy = new NodeType("PERSON", "A person", null);
        RelationshipType legacyRelation = new RelationshipType("KNOWS", "Knows", null);

        assertNull(legacy.getParentType());
        assertNull(legacyRelation.getConnectionFamily());

        GraphSchema roundTrip = mapper.readValue(mapper.writeValueAsString(new GraphSchema(
                List.of(new NodeType("EMAIL_MESSAGE", "An email", null, "DOCUMENT")),
                List.of(new RelationshipType("EMAILED", "Sent email", null,
                        List.of(), "COMMUNICATION")), null)), GraphSchema.class);
        assertEquals("DOCUMENT", roundTrip.getNodeTypes().get(0).getParentType());
        assertEquals("COMMUNICATION",
                roundTrip.getRelationshipTypes().get(0).getConnectionFamily());

        GraphSchema legacyBaselineOverride = SchemaHierarchyVocabulary.withBaseline(
                new GraphSchema(List.of(new NodeType(
                        "DOCUMENT", "Custom document description", null)), null, null));
        assertEquals("CREATIVE_WORK",
                legacyBaselineOverride.getNodeTypeMap().get("DOCUMENT").getParentType());
        assertThrows(IllegalArgumentException.class, () ->
                SchemaHierarchyVocabulary.withBaseline(new GraphSchema(
                        List.of(new NodeType("DOCUMENT", "Bad override", null, "PRODUCT")),
                        null, null)));
        assertEquals("SOCIAL",
                SchemaHierarchyVocabulary.connectionFamilyForPredicate("PARENT_OF"));
    }

    @Test
    void malformedCyclesTerminateWithoutInventingAncestors() {
        GraphSchema schema = new GraphSchema(List.of(
                new NodeType("A", "A", null, "B"),
                new NodeType("B", "B", null, "A")), null, null);

        assertFalse(schema.isNodeTypeAssignableTo("A", "PERSON"));
        assertFalse(schema.isNodeTypeAssignableTo("A", "B"));
        assertTrue(schema.getNodeTypeAncestors("A").isEmpty());
    }

    @Test
    void preservesOpenVersusExplicitEmptyPropertyContracts() {
        GraphSchema schema = SchemaHierarchyVocabulary.withBaseline(new GraphSchema(
                List.of(
                        new NodeType("OPEN_TYPE", "Open", null, "CONCEPT"),
                        new NodeType("CLOSED_TYPE", "Closed", List.of(), "CONCEPT")),
                List.of(new RelationshipType(
                        "CLOSED_REL", "Closed relation", List.of(), List.of(), "REFERENCE")),
                null));

        assertNull(schema.getEffectiveNodeProperties("OPEN_TYPE"));
        assertTrue(schema.getEffectiveNodeProperties("CLOSED_TYPE").isEmpty());
        assertTrue(schema.getRelationshipTypes().get(0).getProperties().isEmpty());
        assertFalse(schema.getNodePropertiesByName().containsKey("OPEN_TYPE"));
        assertTrue(schema.getNodePropertiesByName().get("CLOSED_TYPE").isEmpty());
        assertTrue(schema.getRelationshipPropertiesByName().get("CLOSED_REL").isEmpty());
    }
}
