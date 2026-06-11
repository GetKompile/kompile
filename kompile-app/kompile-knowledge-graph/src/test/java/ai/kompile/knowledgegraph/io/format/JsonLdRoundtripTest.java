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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonLdGraphExporter} and {@link JsonLdGraphImporter} — roundtrip,
 * edge creation from @id references, metadata handling, and error handling.
 */
class JsonLdRoundtripTest {

    private JsonLdGraphExporter exporter;
    private JsonLdGraphImporter importer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        exporter = new JsonLdGraphExporter(mapper);
        importer = new JsonLdGraphImporter(mapper);
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_roundtrips() throws IOException {
        PortableGraph original = PortableGraph.empty();
        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    // ─── single node ──────────────────────────────────────────────────

    @Test
    void singleNode_roundtrips() throws IOException {
        PortableNode node = new PortableNode("n1", "Node One", "A description", "PERSON", null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.nodes().size());
        PortableNode result = parsed.nodes().get(0);
        assertEquals("n1", result.externalId());
        assertEquals("Node One", result.title());
        assertEquals("A description", result.description());
        assertEquals("PERSON", result.nodeType());
    }

    // ─── nodes + edges ────────────────────────────────────────────────

    @Test
    void nodesAndEdges_roundtrip() throws IOException {
        PortableNode n1 = new PortableNode("alice", "Alice", "Engineer", "PERSON", null);
        PortableNode n2 = new PortableNode("bob", "Bob", "Manager", "PERSON", null);
        PortableEdge edge = new PortableEdge("alice", "bob", "WORKS_WITH", null, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(2, parsed.nodes().size());
        // Edge should be recreated from the @id reference in the exported JSON
        assertFalse(parsed.edges().isEmpty());
    }

    // ─── edge from inline @id reference ───────────────────────────────

    @Test
    void edgeCreatedFromIdReference() throws IOException {
        // Manually construct JSON-LD with inline @id references
        String jsonLd = """
                {
                  "@context": {},
                  "@graph": [
                    {
                      "@id": "alice",
                      "@type": "PERSON",
                      "title": "Alice",
                      "knows": { "@id": "bob" }
                    },
                    {
                      "@id": "bob",
                      "@type": "PERSON",
                      "title": "Bob"
                    }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());
        PortableEdge edge = parsed.edges().get(0);
        assertEquals("alice", edge.fromExternalId());
        assertEquals("bob", edge.toExternalId());
        assertEquals("KNOWS", edge.edgeType());
    }

    // ─── array of @id references → multiple edges ─────────────────────

    @Test
    void arrayOfIdReferences_createsMultipleEdges() throws IOException {
        String jsonLd = """
                {
                  "@context": {},
                  "@graph": [
                    {
                      "@id": "manager",
                      "@type": "PERSON",
                      "title": "Manager",
                      "manages": [
                        { "@id": "dev1" },
                        { "@id": "dev2" }
                      ]
                    },
                    { "@id": "dev1", "@type": "PERSON", "title": "Dev 1" },
                    { "@id": "dev2", "@type": "PERSON", "title": "Dev 2" }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, parsed.nodes().size());
        assertEquals(2, parsed.edges().size());
        assertTrue(parsed.edges().stream().allMatch(e -> e.edgeType().equals("MANAGES")));
    }

    // ─── metadata preserved ───────────────────────────────────────────

    @Test
    void metadataFields_preserved() throws IOException {
        PortableNode node = new PortableNode("n1", "Title", null, "CONCEPT",
                Map.of("color", "blue", "priority", "high"));
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.nodes().size());
        assertNotNull(parsed.nodes().get(0).metadata());
        assertEquals("blue", parsed.nodes().get(0).metadata().get("color"));
        assertEquals("high", parsed.nodes().get(0).metadata().get("priority"));
    }

    // ─── null type defaults to ENTITY ─────────────────────────────────

    @Test
    void nullType_defaultsToEntity() throws IOException {
        String jsonLd = """
                {
                  "@graph": [
                    { "@id": "n1", "title": "No Type" }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.nodes().size());
        assertEquals("ENTITY", parsed.nodes().get(0).nodeType());
    }

    // ─── name fallback when title missing ─────────────────────────────

    @Test
    void nameField_usedWhenTitleMissing() throws IOException {
        String jsonLd = """
                {
                  "@graph": [
                    { "@id": "n1", "name": "Fallback Name" }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals("Fallback Name", parsed.nodes().get(0).title());
    }

    @Test
    void idFallback_whenNoTitleOrName() throws IOException {
        String jsonLd = """
                {
                  "@graph": [
                    { "@id": "my-id" }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals("my-id", parsed.nodes().get(0).title());
    }

    // ─── entries without @id skipped ──────────────────────────────────

    @Test
    void entriesWithoutId_skipped() throws IOException {
        String jsonLd = """
                {
                  "@graph": [
                    { "title": "No ID" },
                    { "@id": "valid", "title": "Has ID" }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.nodes().size());
        assertEquals("valid", parsed.nodes().get(0).externalId());
    }

    // ─── missing @graph throws ────────────────────────────────────────

    @Test
    void missingGraphArray_throws() {
        String json = """
                { "something": "else" }
                """;
        assertThrows(IOException.class,
                () -> importer.parse(json.getBytes(StandardCharsets.UTF_8)));
    }

    // ─── exporter output contains @context ────────────────────────────

    @Test
    void exporter_outputContainsContext() throws IOException {
        PortableGraph graph = new PortableGraph(
                List.of(new PortableNode("n1", "T", null, "X", null)), List.of());
        byte[] bytes = exporter.toBytes(graph);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(json.contains("@context"));
        assertTrue(json.contains("@graph"));
        assertTrue(json.contains("schema.org"));
    }

    // ─── edge for unknown source creates stub node ────────────────────

    @Test
    void edgeForUnknownSource_createsStubInExport() throws IOException {
        // Edge references a node not in the node list
        PortableNode n1 = new PortableNode("known", "Known", null, "TYPE", null);
        PortableEdge edge = new PortableEdge("unknown-source", "known", "REFS", null, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        // Both nodes should exist (known + stub for unknown-source)
        assertEquals(2, parsed.nodes().size());
        assertTrue(parsed.nodes().stream().anyMatch(n -> n.externalId().equals("unknown-source")));
    }

    // ─── scalar metadata vs object reference ──────────────────────────

    @Test
    void scalarField_becomesMetadata_objectField_becomesEdge() throws IOException {
        String jsonLd = """
                {
                  "@graph": [
                    {
                      "@id": "n1",
                      "@type": "DOC",
                      "title": "Doc",
                      "version": "2.0",
                      "author": { "@id": "person1" }
                    }
                  ]
                }
                """;

        PortableGraph parsed = importer.parse(jsonLd.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, parsed.nodes().size());
        assertEquals("2.0", parsed.nodes().get(0).metadata().get("version"));
        assertEquals(1, parsed.edges().size());
        assertEquals("AUTHOR", parsed.edges().get(0).edgeType());
    }
}
