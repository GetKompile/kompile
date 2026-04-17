/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLdGraphRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsNodesAndEdges() throws Exception {
        PortableGraph original = new PortableGraph(
                List.of(
                        new PortableNode("a", "Alpha", "first", "PERSON", Map.of("city", "Paris")),
                        new PortableNode("b", "Beta", null, "PERSON", null)
                ),
                List.of(
                        new PortableEdge("a", "b", "KNOWS", null, null)
                ));
        byte[] bytes = new JsonLdGraphExporter(mapper).toBytes(original);
        PortableGraph parsed = new JsonLdGraphImporter(mapper).parse(bytes);

        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());
        assertEquals("a", parsed.nodes().get(0).externalId());
        assertEquals("Alpha", parsed.nodes().get(0).title());
        assertEquals("PERSON", parsed.nodes().get(0).nodeType());
        // Metadata round-trips through @context-aware properties as String values.
        assertNotNull(parsed.nodes().get(0).metadata());
        assertEquals("Paris", parsed.nodes().get(0).metadata().get("city"));

        PortableEdge edge = parsed.edges().get(0);
        assertEquals("a", edge.fromExternalId());
        assertEquals("b", edge.toExternalId());
        assertEquals("KNOWS", edge.edgeType());
    }

    @Test
    void exporterEmitsContextAndGraphArray() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(new PortableNode("x", "X", null, "ENTITY", null)),
                List.of());
        byte[] bytes = new JsonLdGraphExporter(mapper).toBytes(g);
        JsonNode root = mapper.readTree(bytes);
        assertTrue(root.has("@context"), "Expected @context");
        assertTrue(root.has("@graph"), "Expected @graph array");
        assertEquals(1, root.get("@graph").size());
        assertEquals("x", root.get("@graph").get(0).get("@id").asText());
        assertEquals("ENTITY", root.get("@graph").get(0).get("@type").asText());
    }

    @Test
    void importerDefaultsMissingTypeToEntity() throws Exception {
        String json = """
                {"@graph":[{"@id":"n1","title":"NoType"}]}
                """;
        PortableGraph parsed = new JsonLdGraphImporter(mapper).parse(json.getBytes());
        assertEquals(1, parsed.nodes().size());
        assertEquals("ENTITY", parsed.nodes().get(0).nodeType());
    }

    @Test
    void importerSkipsEntriesWithoutId() throws Exception {
        String json = """
                {"@graph":[{"title":"NoId"},{"@id":"n2","title":"Has Id"}]}
                """;
        PortableGraph parsed = new JsonLdGraphImporter(mapper).parse(json.getBytes());
        assertEquals(1, parsed.nodes().size());
        assertEquals("n2", parsed.nodes().get(0).externalId());
    }
}
