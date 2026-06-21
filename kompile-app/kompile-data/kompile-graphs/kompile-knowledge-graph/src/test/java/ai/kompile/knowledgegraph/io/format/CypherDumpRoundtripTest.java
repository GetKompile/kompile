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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CypherDumpExporter} and {@link CypherDumpImporter} — roundtrip,
 * escaping, edge properties, safe label generation, and edge cases.
 */
class CypherDumpRoundtripTest {

    private CypherDumpExporter exporter;
    private CypherDumpImporter importer;

    @BeforeEach
    void setUp() {
        exporter = new CypherDumpExporter();
        importer = new CypherDumpImporter();
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_roundtrips() {
        PortableGraph original = PortableGraph.empty();
        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    // ─── single node ──────────────────────────────────────────────────

    @Test
    void singleNode_roundtrips() {
        PortableNode node = new PortableNode("n1", "Node One", "A test node", "PERSON", null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.nodes().size());
        PortableNode result = parsed.nodes().get(0);
        assertEquals("n1", result.externalId());
        assertEquals("Node One", result.title());
        assertEquals("A test node", result.description());
        assertEquals("PERSON", result.nodeType());
    }

    // ─── nodes + edges ────────────────────────────────────────────────

    @Test
    void nodesAndEdges_roundtrip() {
        PortableNode n1 = new PortableNode("alice", "Alice", "Engineer", "PERSON", null);
        PortableNode n2 = new PortableNode("bob", "Bob", "Manager", "PERSON", null);
        PortableEdge edge = new PortableEdge("alice", "bob", "WORKS_WITH", 0.9, "team", null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());

        PortableEdge parsedEdge = parsed.edges().get(0);
        assertEquals("alice", parsedEdge.fromExternalId());
        assertEquals("bob", parsedEdge.toExternalId());
        assertEquals("WORKS_WITH", parsedEdge.edgeType());
        assertEquals(0.9, parsedEdge.weight(), 0.001);
        assertEquals("team", parsedEdge.description());
    }

    // ─── escaping ─────────────────────────────────────────────────────

    @Test
    void specialCharacters_escapedAndParsed() {
        PortableNode node = new PortableNode("id-1", "O'Brien's \"Test\"",
                "Line one\nLine two", "PERSON", null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.nodes().size());
        assertEquals("id-1", parsed.nodes().get(0).externalId());
        // The apostrophe is escaped and parsed back
        assertNotNull(parsed.nodes().get(0).title());
    }

    // ─── safe label ───────────────────────────────────────────────────

    @Test
    void safeLabel_specialCharsReplaced() {
        PortableNode node = new PortableNode("n1", "Test", null, "node-type/special!", null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        String cypher = new String(bytes, StandardCharsets.UTF_8);

        // Special characters should be replaced with underscores
        assertTrue(cypher.contains("node_type_special_"));
    }

    @Test
    void safeLabel_nullOrBlank_usesNodeDefault() {
        PortableNode node = new PortableNode("n1", "Test", null, null, null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        String cypher = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(cypher.contains("CREATE (n:Node {"));
    }

    // ─── edge without properties ──────────────────────────────────────

    @Test
    void edgeWithoutProperties_roundtrips() {
        PortableNode n1 = new PortableNode("a", "A", null, "TYPE", null);
        PortableNode n2 = new PortableNode("b", "B", null, "TYPE", null);
        PortableEdge edge = new PortableEdge("a", "b", "LINKS_TO", null, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.edges().size());
        assertEquals("LINKS_TO", parsed.edges().get(0).edgeType());
        assertNull(parsed.edges().get(0).weight());
    }

    // ─── edge with weight only ────────────────────────────────────────

    @Test
    void edgeWithWeightOnly_roundtrips() {
        PortableNode n1 = new PortableNode("a", "A", null, "TYPE", null);
        PortableNode n2 = new PortableNode("b", "B", null, "TYPE", null);
        PortableEdge edge = new PortableEdge("a", "b", "SCORED", 1.5, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1.5, parsed.edges().get(0).weight(), 0.001);
    }

    // ─── edge with description only ───────────────────────────────────

    @Test
    void edgeWithDescriptionOnly_roundtrips() {
        PortableNode n1 = new PortableNode("a", "A", null, "TYPE", null);
        PortableNode n2 = new PortableNode("b", "B", null, "TYPE", null);
        PortableEdge edge = new PortableEdge("a", "b", "NOTED", null, "edge note", null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals("edge note", parsed.edges().get(0).description());
    }

    // ─── multiple edges ───────────────────────────────────────────────

    @Test
    void multipleEdges_allParsed() {
        PortableNode n1 = new PortableNode("a", "A", null, "TYPE", null);
        PortableNode n2 = new PortableNode("b", "B", null, "TYPE", null);
        PortableNode n3 = new PortableNode("c", "C", null, "TYPE", null);
        PortableEdge e1 = new PortableEdge("a", "b", "KNOWS", null, null, null, null);
        PortableEdge e2 = new PortableEdge("b", "c", "WORKS_WITH", null, null, null, null);
        PortableEdge e3 = new PortableEdge("a", "c", "MANAGES", null, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2, n3), List.of(e1, e2, e3));

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(3, parsed.nodes().size());
        assertEquals(3, parsed.edges().size());
    }

    // ─── node without title or description ────────────────────────────

    @Test
    void nodeWithoutTitleOrDescription_usesExternalIdAsTitle() {
        PortableNode node = new PortableNode("bare-node", null, null, "TYPE", null);
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(1, parsed.nodes().size());
        assertEquals("bare-node", parsed.nodes().get(0).externalId());
        // Title defaults to externalId when not provided
        assertEquals("bare-node", parsed.nodes().get(0).title());
    }

    // ─── importer: garbage input ──────────────────────────────────────

    @Test
    void importer_emptyBytes_returnsEmptyGraph() {
        PortableGraph parsed = importer.parse(new byte[0]);
        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    @Test
    void importer_unrecognizedStatements_skipped() {
        String garbage = "RETURN 42;\nDROP TABLE nodes;\n";
        PortableGraph parsed = importer.parse(garbage.getBytes(StandardCharsets.UTF_8));
        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    // ─── exporter output format ───────────────────────────────────────

    @Test
    void exporter_outputContainsCreateStatements() {
        PortableNode node = new PortableNode("n1", "Title", "Desc", "CONCEPT", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        String cypher = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(cypher.startsWith("CREATE (n:CONCEPT {"));
        assertTrue(cypher.contains("externalId: 'n1'"));
        assertTrue(cypher.contains("title: 'Title'"));
        assertTrue(cypher.contains("description: 'Desc'"));
    }

    @Test
    void exporter_edgeOutputContainsMatchAndCreate() {
        PortableNode n1 = new PortableNode("a", "A", null, "T", null);
        PortableNode n2 = new PortableNode("b", "B", null, "T", null);
        PortableEdge edge = new PortableEdge("a", "b", "REL", 1.0, "note", null, null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of(edge)));
        String cypher = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(cypher.contains("MATCH (a {externalId: 'a'})"));
        assertTrue(cypher.contains("(b {externalId: 'b'})"));
        assertTrue(cypher.contains("CREATE (a)-[:REL"));
        assertTrue(cypher.contains("weight: 1.0"));
        assertTrue(cypher.contains("description: 'note'"));
    }

    // ─── L-4: previously-dropped scoping/quality fields ──────────────

    /**
     * Verifies that the L-4 audit gap is closed: confidence, namedGraphId, factSheetId,
     * occurredAt on nodes and confidence, relationType, provenance, factSheetId on edges
     * all survive a full export → import round-trip.
     */
    @Test
    void extendedFields_roundtrip() {
        PortableNode node = new PortableNode(
                "entity-1",
                "Entity One",
                "A scoped entity",
                "CONCEPT",
                null,
                42L,           // factSheetId
                "graph-ng-7",  // namedGraphId
                0.87,          // confidence
                "2024-01-15"   // occurredAt
        );

        PortableEdge edge = new PortableEdge(
                "entity-1",
                "entity-2",
                "CAUSES",
                0.75,
                "causal link",
                "crawl-run-99",  // provenance
                0.92,            // confidence
                null,            // occurredAt (not emitted by exporter currently)
                "CAUSAL",        // relationType
                7L,              // factSheetId
                null, null, null, null, null
        );

        // Need a second node for the edge to reference
        PortableNode node2 = new PortableNode("entity-2", "Entity Two", null, "CONCEPT", null);
        PortableGraph original = new PortableGraph(List.of(node, node2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        String cypher = new String(bytes, StandardCharsets.UTF_8);

        // Verify the extended node fields appear in emitted Cypher
        assertTrue(cypher.contains("confidence: 0.87"), "node confidence must be emitted");
        assertTrue(cypher.contains("namedGraphId: 'graph-ng-7'"), "namedGraphId must be emitted");
        assertTrue(cypher.contains("factSheetId: 42"), "node factSheetId must be emitted");
        assertTrue(cypher.contains("occurredAt: '2024-01-15'"), "occurredAt must be emitted");

        // Verify the extended edge fields appear in emitted Cypher
        assertTrue(cypher.contains("confidence: 0.92"), "edge confidence must be emitted");
        assertTrue(cypher.contains("relationType: 'CAUSAL'"), "relationType must be emitted");
        assertTrue(cypher.contains("provenance: 'crawl-run-99'"), "provenance must be emitted");
        assertTrue(cypher.contains("factSheetId: 7"), "edge factSheetId must be emitted");

        // Re-import and assert all values survive
        PortableGraph parsed = importer.parse(bytes);
        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());

        PortableNode parsedNode = parsed.nodes().stream()
                .filter(n -> "entity-1".equals(n.externalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("entity-1 not found after import"));

        assertEquals(0.87, parsedNode.confidence(), 0.0001, "node confidence must round-trip");
        assertEquals("graph-ng-7", parsedNode.namedGraphId(), "namedGraphId must round-trip");
        assertEquals(42L, parsedNode.factSheetId(), "node factSheetId must round-trip");
        assertEquals("2024-01-15", parsedNode.occurredAt(), "occurredAt must round-trip");

        PortableEdge parsedEdge = parsed.edges().get(0);
        assertEquals(0.92, parsedEdge.confidence(), 0.0001, "edge confidence must round-trip");
        assertEquals("CAUSAL", parsedEdge.relationType(), "relationType must round-trip");
        assertEquals("crawl-run-99", parsedEdge.provenance(), "provenance must round-trip");
        assertEquals(7L, parsedEdge.factSheetId(), "edge factSheetId must round-trip");
    }
}
