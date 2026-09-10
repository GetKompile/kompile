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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlOntologyTest {

    @Test
    void validUpdateAddsEntityRelationshipAliasesAndDirectedPattern() {
        CrawlOntology ontology = new CrawlOntology(initialSchema());

        CrawlOntology.UpdateResult result = ontology.update(new GraphSchema(
                List.of(new NodeType(
                        "EMAIL_ADDRESS",
                        "An email address that can identify a person or organization",
                        List.of(new PropertyType("address", "String")),
                        "CONCEPT")),
                List.of(new RelationshipType(
                        "IDENTIFIES",
                        "An address identifies its owning entity",
                        null,
                        List.of("email_for", "belongs_to"),
                        "IDENTITY")),
                List.of("(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)")));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertTrue(result.updated());
        assertEquals(2L, result.revision());
        assertTrue(result.schema().getAllNodeLabels().contains("EMAIL_ADDRESS"));
        assertTrue(result.schema().getAllRelationshipTypes().contains("IDENTIFIES"));
        assertEquals(List.of("belongs_to", "email_for"),
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
                        "REPORTS_TO", "A person reports to another person", null,
                        List.of(), "HIERARCHY")),
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
                        List.of("employed_by"),
                        "AFFILIATION")),
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

    @Test
    void reorderedEquivalentSchemasDoNotAdvanceRevisionOrFingerprint() {
        CrawlOntology ontology = new CrawlOntology(reorderedSchema(false, "String"));
        String beforeFingerprint = ontology.contentFingerprint();

        CrawlOntology.UpdateResult result = ontology.update(reorderedSchema(true, "String"));

        assertTrue(result.valid(), () -> result.errors().toString());
        assertFalse(result.updated());
        assertEquals(1L, result.revision());
        assertEquals(beforeFingerprint, ontology.contentFingerprint());
        assertEquals(List.of("employed_by", "works_for"),
                ontology.snapshot().getRelationshipTypeMap().get("WORKS_AT").getAliases());
    }

    @Test
    void actualContractChangeChangesFingerprint() {
        String original = CrawlOntology.contentFingerprint(reorderedSchema(false, "String"));
        String changedPropertyType = CrawlOntology.contentFingerprint(
                reorderedSchema(false, "Integer"));

        assertNotEquals(original, changedPropertyType);
    }

    @Test
    void mergePreservesEstablishedDetailsAndHierarchy() {
        GraphSchema merged = CrawlOntology.merge(
                new GraphSchema(
                        List.of(new NodeType(
                                "PERSON", "Established person", List.of(
                                new PropertyType("email", "String")), "CONCEPT")),
                        List.of(new RelationshipType(
                                "WORKS_AT", "Established employment", null,
                                List.of("employed_by"), "AFFILIATION")),
                        List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)")),
                new GraphSchema(
                        List.of(new NodeType(
                                "PERSON", "Replacement person", List.of(
                                new PropertyType("email", "Integer"),
                                new PropertyType("employeeId", "String")), "EVENT")),
                        List.of(new RelationshipType(
                                "WORKS_AT", "Replacement employment", null,
                                List.of("works_for"), "HIERARCHY")),
                        List.of("(ORGANIZATION)-[:WORKS_AT]->(PERSON)")));

        NodeType person = merged.getNodeTypeMap().get("PERSON");
        assertEquals("Established person", person.getDescription());
        assertEquals("CONCEPT", person.getParentType());
        assertEquals("String", person.getProperties().get(0).getType());
        assertTrue(person.getProperties().stream()
                .anyMatch(property -> "employeeId".equals(property.getName())));
        RelationshipType worksAt = merged.getRelationshipTypeMap().get("WORKS_AT");
        assertEquals("Established employment", worksAt.getDescription());
        assertEquals("AFFILIATION", worksAt.getConnectionFamily());
        assertEquals(List.of("employed_by", "works_for"), worksAt.getAliases());
    }

    @Test
    void snapshotsAreDefensiveCopies() {
        CrawlOntology ontology = new CrawlOntology(initialSchema());
        GraphSchema snapshot = ontology.snapshot();
        snapshot.getNodeTypeMap().get("PERSON").setDescription("mutated");
        snapshot.getNodeTypeMap().get("PERSON").getProperties().get(0).setType("Integer");
        snapshot.getRelationshipTypeMap().get("WORKS_AT").setAliases(List.of("mutated"));

        GraphSchema current = ontology.snapshot();
        assertEquals("A human being", current.getNodeTypeMap().get("PERSON").getDescription());
        assertEquals("String", current.getNodeTypeMap().get("PERSON")
                .getProperties().get(0).getType());
        assertEquals(List.of(), current.getRelationshipTypeMap().get("WORKS_AT").getAliases());
    }

    @Test
    void nullAndEmptyVocabularyRemainDistinct() {
        GraphSchema open = new GraphSchema(null, null, null);
        GraphSchema closed = new GraphSchema(List.of(), List.of(), List.of());

        assertNotEquals(CrawlOntology.contentFingerprint(open),
                CrawlOntology.contentFingerprint(closed));
        CrawlOntology ontology = new CrawlOntology(open);
        CrawlOntology.UpdateResult result = ontology.update(closed);
        assertTrue(result.updated());
        assertNotNull(result.schema().getNodeTypes());
        assertNotNull(result.schema().getRelationshipTypes());
        assertNotNull(result.schema().getPatterns());
        assertTrue(result.schema().getNodeTypes().isEmpty());
        assertTrue(result.schema().getRelationshipTypes().isEmpty());
        assertTrue(result.schema().getPatterns().isEmpty());
        assertNull(CrawlOntology.contentFingerprint(null));
    }

    @Test
    void aliasesRemainDistinctFromCanonicalRelationshipTypes() {
        GraphSchema canonicalType = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null),
                        new NodeType("ORGANIZATION", "An organization", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "Employment", null, List.of("employed_by"), "AFFILIATION")),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"));
        GraphSchema aliasPromotedToType = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null),
                        new NodeType("ORGANIZATION", "An organization", null)),
                List.of(new RelationshipType(
                        "EMPLOYED_BY", "Employment", null, List.of("WORKS_AT"), "AFFILIATION")),
                List.of("(PERSON)-[:EMPLOYED_BY]->(ORGANIZATION)"));

        CrawlOntology ontology = new CrawlOntology(canonicalType);
        assertTrue(ontology.snapshot().getAllRelationshipTypes().contains("WORKS_AT"));
        assertFalse(ontology.snapshot().getAllRelationshipTypes().contains("employed_by"));
        assertNotEquals(CrawlOntology.contentFingerprint(canonicalType),
                CrawlOntology.contentFingerprint(aliasPromotedToType));
    }

    @Test
    void canonicalizationPreservesLabelsAndDoesNotMutateInputs() {
        GraphSchema input = reorderedSchema(true, "String");

        GraphSchema canonical = CrawlOntology.canonicalize(input);

        assertEquals(List.of("works_for", "employed_by"),
                input.getRelationshipTypes().get(0).getAliases());
        assertEquals(List.of("name", "email"),
                input.getNodeTypes().get(1).getProperties().stream()
                        .map(PropertyType::getName).toList());
        assertEquals(List.of("employed_by", "works_for"),
                canonical.getRelationshipTypes().get(0).getAliases());
        assertEquals("PERSON", canonical.getNodeTypes().get(1).getLabel());

        GraphSchema userLabelSchema = new GraphSchema(
                List.of(new NodeType("UserLabel", "A user label", null)), null, null);
        assertEquals("UserLabel", CrawlOntology.canonicalize(userLabelSchema)
                .getNodeTypes().get(0).getLabel());
    }

    private static GraphSchema reorderedSchema(boolean reverse, String emailType) {
        List<PropertyType> personProperties = reverse
                ? List.of(new PropertyType("name", "String"), new PropertyType("email", emailType))
                : List.of(new PropertyType("email", emailType), new PropertyType("name", "String"));
        List<NodeType> nodes = reverse
                ? List.of(new NodeType("ORGANIZATION", "An organization", null),
                        new NodeType("PERSON", "A person", personProperties))
                : List.of(new NodeType("PERSON", "A person", personProperties),
                        new NodeType("ORGANIZATION", "An organization", null));
        List<String> aliases = reverse
                ? List.of("works_for", "employed_by")
                : List.of("employed_by", "works_for");
        List<String> patterns = reverse
                ? List.of("(ORGANIZATION)-[:WORKS_AT]->(PERSON)",
                        "(PERSON)-[:WORKS_AT]->(ORGANIZATION)")
                : List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)",
                        "(ORGANIZATION)-[:WORKS_AT]->(PERSON)");
        return new GraphSchema(
                nodes,
                List.of(new RelationshipType(
                        "WORKS_AT", "Employment", null, aliases, "AFFILIATION")),
                patterns);
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
                        null,
                        List.of(),
                        "AFFILIATION")),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"));
    }
}
