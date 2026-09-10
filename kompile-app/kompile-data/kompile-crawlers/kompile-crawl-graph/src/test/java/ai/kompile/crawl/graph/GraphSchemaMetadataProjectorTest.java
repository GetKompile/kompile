/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSchemaMetadataProjectorTest {

    @Test
    void projectsHierarchyMetadataWithoutReplacingConcreteTypes() {
        Entity employee = new Entity();
        employee.setId("alice");
        employee.setType("EMPLOYEE");
        employee.setMetadata(Map.of("source", "chunk-1"));
        Relationship emailed = new Relationship();
        emailed.setSource("alice");
        emailed.setTarget("bob");
        emailed.setType("EMAILED");
        emailed.setMetadata(Map.of("channel", "email"));
        Graph graph = Graph.builder()
                .entities(new ArrayList<>(List.of(employee)))
                .relationships(new ArrayList<>(List.of(emailed)))
                .build();
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("EMPLOYEE", "An employee", null, "PERSON")),
                List.of(new RelationshipType("EMAILED", "Sent email", null,
                        List.of(), "COMMUNICATION")), null);

        GraphSchemaMetadataProjector.project(graph, schema);

        assertEquals("EMPLOYEE", employee.getType());
        assertEquals(GraphSchemaMetadataProjector.schemaFingerprint(schema),
                employee.getMetadata().get(GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT));
        assertEquals("PERSON", employee.getMetadata().get("schema.parentType"));
        assertEquals(List.of("PERSON"), employee.getMetadata().get("schema.typeAncestors"));
        assertEquals("chunk-1", employee.getMetadata().get("source"));
        assertEquals("EMAILED", emailed.getType());
        assertEquals("COMMUNICATION",
                emailed.getMetadata().get("schema.connectionFamily"));
        assertEquals("email", emailed.getMetadata().get("channel"));
    }

    @Test
    void reprojectingRootAndUnclassifiedTypesRemovesStaleReservedMetadata() {
        Map<String, Object> entityMetadata = new java.util.LinkedHashMap<>(Map.of(
                "schema.parentType", "ORGANIZATION",
                "schema.typeAncestors", List.of("ORGANIZATION"),
                "schema_parent_type", "GROUP",
                "schema_type_ancestors", List.of("GROUP")));
        Map<String, Object> relationMetadata = new java.util.LinkedHashMap<>(Map.of(
                "schema.connectionFamily", "COMMUNICATION",
                "schema_connection_family", "SOCIAL"));

        GraphSchemaMetadataProjector.applyEntityMetadata(
                entityMetadata, ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary
                        .baselineSchema(), "PERSON");
        GraphSchemaMetadataProjector.applyRelationshipMetadata(
                relationMetadata, new GraphSchema(null, null, null), "KNOWS");

        assertFalse(entityMetadata.containsKey("schema.parentType"));
        assertFalse(entityMetadata.containsKey("schema.typeAncestors"));
        assertFalse(entityMetadata.containsKey("schema_parent_type"));
        assertFalse(entityMetadata.containsKey("schema_type_ancestors"));
        assertEquals(GraphSchemaMetadataProjector.schemaFingerprint(
                        ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary.baselineSchema()),
                entityMetadata.get(GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT));
        assertFalse(relationMetadata.containsKey("schema.connectionFamily"));
        assertFalse(relationMetadata.containsKey("schema_connection_family"));
    }

    @Test
    void projectsConnectionFamilyForRelationshipAlias() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        GraphSchema schema = new GraphSchema(
                List.of(),
                List.of(new RelationshipType("COMMUNICATES", "Communication", null,
                        List.of("EMAILED"), "COMMUNICATION")),
                List.of());

        GraphSchemaMetadataProjector.applyRelationshipMetadata(
                metadata, schema, "EMAILED");

        assertEquals("COMMUNICATION", metadata.get("schema.connectionFamily"));
    }

    @Test
    void fingerprintsArePermutationStableAndChangeWithTheContract() {
        GraphSchema first = new GraphSchema(
                List.of(new NodeType("EMPLOYEE", "An employee", null, "PERSON"),
                        new NodeType("PERSON", "A person", null, null)),
                List.of(new RelationshipType("EMAILED", "Sent email", null,
                        List.of("MESSAGED"), "COMMUNICATION")),
                List.of("(EMPLOYEE)-[:EMAILED]->(PERSON)"));
        GraphSchema permuted = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null, null),
                        new NodeType("EMPLOYEE", "An employee", null, "PERSON")),
                List.of(new RelationshipType("EMAILED", "Sent email", null,
                        List.of("MESSAGED"), "COMMUNICATION")),
                List.of("(EMPLOYEE)-[:EMAILED]->(PERSON)"));
        GraphSchema changed = new GraphSchema(
                List.of(new NodeType("EMPLOYEE", "An employee", null, "ORGANIZATION"),
                        new NodeType("PERSON", "A person", null, null)),
                List.of(new RelationshipType("EMAILED", "Sent email", null,
                        List.of("MESSAGED"), "SOCIAL")),
                List.of("(EMPLOYEE)-[:EMAILED]->(PERSON)"));

        assertEquals(GraphSchemaMetadataProjector.schemaFingerprint(first),
                GraphSchemaMetadataProjector.schemaFingerprint(permuted));
        assertNotEquals(GraphSchemaMetadataProjector.schemaFingerprint(first),
                GraphSchemaMetadataProjector.schemaFingerprint(changed));

        Entity firstBatchEntity = entity("first", "EMPLOYEE");
        Entity secondBatchEntity = entity("second", "EMPLOYEE");
        GraphSchemaMetadataProjector.project(Graph.builder()
                .entities(new ArrayList<>(List.of(firstBatchEntity))).build(), first);
        GraphSchemaMetadataProjector.project(Graph.builder()
                .entities(new ArrayList<>(List.of(secondBatchEntity))).build(), permuted);
        assertEquals(firstBatchEntity.getMetadata().get(GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT),
                secondBatchEntity.getMetadata().get(GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT));
    }

    @Test
    void forgedFingerprintIsReplacedAndSurvivesExistingRelationMetadataConversion() {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null, null)),
                List.of(new RelationshipType("KNOWS", "Knows", null,
                        List.of(), "SOCIAL")), List.of());
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of(
                GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT, "forged",
                "schema_fingerprint", "also-forged"));
        String fingerprint = GraphSchemaMetadataProjector.schemaFingerprint(schema);

        GraphSchemaMetadataProjector.applyRelationshipMetadata(
                metadata, schema, "KNOWS", fingerprint);
        String json = new GraphPersistenceHelper().semanticRelationMetadataJson(
                "job", "source", "inline_llm", "alice", "bob", "KNOWS", "knows",
                0.9d, metadata);

        assertEquals(fingerprint, metadata.get(GraphSchemaMetadataProjector.SCHEMA_FINGERPRINT));
        assertTrue(json.contains("\"schema.fingerprint\":\"" + fingerprint + "\""));
        assertFalse(json.contains("forged"));
    }

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }
}
