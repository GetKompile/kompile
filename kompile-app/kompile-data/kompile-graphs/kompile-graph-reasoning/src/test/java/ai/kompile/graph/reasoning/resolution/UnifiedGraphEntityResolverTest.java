/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.resolution;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphEntityResolverTest {

    @Test
    void sharedEmailResolvesPeopleButEmailNodeRemainsAnIdentifier() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(semantic("person-1", "PERSON", "Alice",
                        Map.of("email", "Alice@Example.com")))
                .addEntity(semantic("person-2", "PERSON", "Alice Example",
                        Map.of("emailAddress", "alice@example.com",
                                "aliases", List.of("Alice"))))
                .addEntity(semantic("email-1", "PERSON", "alice@example.com", Map.of()));

        UnifiedGraphEntityResolver.Result result =
                new UnifiedGraphEntityResolver().resolve(
                        graph, UnifiedGraphEntityResolver.Config.defaults());

        assertEquals(1, result.entitiesMerged());
        assertEquals(1, result.typesCorrected());
        assertEquals(1, result.identifierLinksCreated());
        assertEquals(2, result.graph().entities().size());

        GraphEntity email = result.graph().entity("email-1").orElseThrow();
        assertEquals("EMAIL_ADDRESS", email.type());

        String canonicalPerson = result.canonicalIds().get("person-1");
        assertEquals(canonicalPerson, result.canonicalIds().get("person-2"));
        assertNotEquals(email.id(), canonicalPerson);
        assertTrue(result.graph().relations().stream().anyMatch(relation ->
                relation.sourceId().equals(email.id())
                        && relation.targetId().equals(canonicalPerson)
                        && relation.type().equals("RESOLVES_TO")));
    }

    @Test
    void differentTypedEntitiesWithTheSameLabelDoNotMerge() {
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(semantic("person", "PERSON", "Mercury", Map.of()))
                .addEntity(semantic("company", "ORGANIZATION", "Mercury", Map.of()));

        UnifiedGraphEntityResolver.Result result =
                new UnifiedGraphEntityResolver().resolve(
                        graph, UnifiedGraphEntityResolver.Config.defaults());

        assertEquals(0, result.entitiesMerged());
        assertEquals(2, result.graph().entities().size());
    }

    private static GraphEntity semantic(
            String id, String type, String label, Map<String, Object> attributes) {
        return GraphEntity.builder(id)
                .type(type)
                .label(label)
                .tag("semantic")
                .attribute("provenance", "unified-corpus-extraction")
                .attributes(attributes)
                .build();
    }
}
