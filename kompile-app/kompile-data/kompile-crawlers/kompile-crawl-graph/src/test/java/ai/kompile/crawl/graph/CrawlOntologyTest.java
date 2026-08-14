/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlOntologyTest {

    @Test
    void validUpdateAddsEntityRelationshipAliasesAndDirectedPattern() {
        CrawlOntology ontology = new CrawlOntology(initialSchema());

        CrawlOntology.UpdateResult result = ontology.update(new GraphSchema(
                List.of(new NodeType(
                        "EMAIL_ADDRESS",
                        "An email address that can identify a person or organization",
                        List.of(new PropertyType("address", "String")))),
                List.of(new RelationshipType(
                        "IDENTIFIES",
                        "An address identifies its owning entity",
                        null,
                        List.of("email_for", "belongs_to"))),
                List.of("(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)")));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.updated());
        assertEquals(2L, result.revision());
        assertTrue(result.schema().getAllNodeLabels().contains("EMAIL_ADDRESS"));
        assertTrue(result.schema().getAllRelationshipTypes().contains("IDENTIFIES"));
        assertEquals(List.of("email_for", "belongs_to"),
                result.schema().getRelationshipTypeMap().get("IDENTIFIES").getAliases());
        assertTrue(result.schema().getPatterns()
                .contains("(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)"));
    }

    @Test
    void relationshipUpdateWithoutEndpointPatternIsRejectedAtomically() {
        CrawlOntology ontology = new CrawlOntology(initialSchema());

        CrawlOntology.UpdateResult result = ontology.update(new GraphSchema(
                List.of(),
                List.of(new RelationshipType(
                        "REPORTS_TO", "A person reports to another person", null)),
                List.of()));

        assertFalse(result.valid());
        assertFalse(result.updated());
        assertEquals(1L, result.revision());
        assertFalse(ontology.snapshot().getAllRelationshipTypes().contains("REPORTS_TO"));
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("SCHEMA_RELATION_PATTERN_MISSING")));
    }

    @Test
    void establishedPropertyContractWinsWhileAliasesAreAdded() {
        CrawlOntology ontology = new CrawlOntology(initialSchema());

        CrawlOntology.UpdateResult result = ontology.update(new GraphSchema(
                List.of(new NodeType(
                        "PERSON",
                        "A conflicting replacement description",
                        List.of(
                                new PropertyType("email", "Integer"),
                                new PropertyType("employeeId", "String")))),
                List.of(new RelationshipType(
                        "WORKS_AT",
                        "A conflicting replacement description",
                        null,
                        List.of("employed_by"))),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)")));

        assertTrue(result.valid(), () -> result.errors().toString());
        NodeType person = result.schema().getNodeTypeMap().get("PERSON");
        assertEquals("A human being", person.getDescription());
        assertEquals("String", person.getProperties().stream()
                .filter(property -> "email".equals(property.getName()))
                .findFirst().orElseThrow().getType());
        assertTrue(person.getProperties().stream()
                .anyMatch(property -> "employeeId".equals(property.getName())));
        RelationshipType worksAt =
                result.schema().getRelationshipTypeMap().get("WORKS_AT");
        assertEquals("A person is employed by an organization", worksAt.getDescription());
        assertEquals(List.of("employed_by"), worksAt.getAliases());
    }

    private static GraphSchema initialSchema() {
        return new GraphSchema(
                List.of(
                        new NodeType(
                                "PERSON",
                                "A human being",
                                List.of(new PropertyType("email", "String"))),
                        new NodeType(
                                "ORGANIZATION",
                                "A legal or operational organization",
                                null)),
                List.of(new RelationshipType(
                        "WORKS_AT",
                        "A person is employed by an organization",
                        null)),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"));
    }
}
