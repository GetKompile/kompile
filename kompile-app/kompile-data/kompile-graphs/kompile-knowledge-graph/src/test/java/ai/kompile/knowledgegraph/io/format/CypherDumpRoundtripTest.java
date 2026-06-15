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
}
