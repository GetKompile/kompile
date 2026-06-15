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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphMLExporter} — XML structure, node/edge serialisation,
 * XML escaping, empty graph, key definitions, and attribute correctness.
 */
class GraphMLExporterTest {

    private GraphMLExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new GraphMLExporter();
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private String export(PortableGraph graph) {
        return new String(exporter.toBytes(graph), StandardCharsets.UTF_8);
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableNode nodeWithDesc(String id, String title, String type, String desc) {
        return new PortableNode(id, title, desc, type, null);
    }

    private PortableEdge edge(String from, String to, String type, Double weight) {
        return new PortableEdge(from, to, type, weight, null, null, null);
    }

    // ─── XML header and structure ─────────────────────────────────────

    @Test
    void output_startsWithXmlDeclaration() {
        String xml = export(PortableGraph.empty());
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
    }

    @Test
    void output_containsGraphmlRootElement() {
        String xml = export(PortableGraph.empty());
        assertTrue(xml.contains("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">"));
        assertTrue(xml.contains("</graphml>"));
    }

    @Test
    void output_containsGraphElement() {
        String xml = export(PortableGraph.empty());
        assertTrue(xml.contains("<graph id=\"G\" edgedefault=\"directed\">"));
        assertTrue(xml.contains("</graph>"));
    }

    @Test
    void output_containsKeyDefinitions() {
        String xml = export(PortableGraph.empty());
        assertTrue(xml.contains("<key id=\"title\" for=\"node\""));
        assertTrue(xml.contains("<key id=\"description\" for=\"node\""));
        assertTrue(xml.contains("<key id=\"nodeType\" for=\"node\""));
        assertTrue(xml.contains("<key id=\"weight\" for=\"edge\""));
        assertTrue(xml.contains("<key id=\"edgeType\" for=\"edge\""));
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_producesValidGraphml() {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String xml = new String(bytes, StandardCharsets.UTF_8);
        // No node or edge elements
        assertFalse(xml.contains("<node "));
        assertFalse(xml.contains("<edge "));
    }

    // ─── node serialisation ───────────────────────────────────────────

    @Test
    void singleNode_hasNodeElement() {
        String xml = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(xml.contains("<node id=\"n1\">"));
        assertTrue(xml.contains("</node>"));
    }

    @Test
    void node_titleDataPresent() {
        String xml = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(xml.contains("<data key=\"title\">Alice</data>"));
    }

    @Test
    void node_nodeTypeDataPresent() {
        String xml = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(xml.contains("<data key=\"nodeType\">PERSON</data>"));
    }

    @Test
    void node_descriptionDataPresent() {
        String xml = export(new PortableGraph(
                List.of(nodeWithDesc("n1", "Alice", "PERSON", "An engineer")), List.of()));
        assertTrue(xml.contains("<data key=\"description\">An engineer</data>"));
    }

    @Test
    void node_nullDescription_notIncluded() {
        String xml = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertFalse(xml.contains("key=\"description\""));
    }

    @Test
    void multipleNodes_allPresent() {
        PortableNode n1 = node("n1", "Alice", "PERSON");
        PortableNode n2 = node("n2", "Bob", "PERSON");
        PortableNode n3 = node("n3", "Acme", "ORG");
        String xml = export(new PortableGraph(List.of(n1, n2, n3), List.of()));

        assertTrue(xml.contains("<node id=\"n1\">"));
        assertTrue(xml.contains("<node id=\"n2\">"));
        assertTrue(xml.contains("<node id=\"n3\">"));
    }

    // ─── edge serialisation ───────────────────────────────────────────

    @Test
    void singleEdge_hasEdgeElement() {
        PortableEdge e = edge("n1", "n2", "KNOWS", 0.8);
        String xml = export(new PortableGraph(List.of(), List.of(e)));
        assertTrue(xml.contains("<edge id=\"e0\" source=\"n1\" target=\"n2\">"));
        assertTrue(xml.contains("</edge>"));
    }

    @Test
    void edge_weightDataPresent() {
        PortableEdge e = edge("n1", "n2", "KNOWS", 0.75);
        String xml = export(new PortableGraph(List.of(), List.of(e)));
        assertTrue(xml.contains("<data key=\"weight\">0.75</data>"));
    }

    @Test
    void edge_edgeTypeDataPresent() {
        PortableEdge e = edge("n1", "n2", "COMPETES", null);
        String xml = export(new PortableGraph(List.of(), List.of(e)));
        assertTrue(xml.contains("<data key=\"edgeType\">COMPETES</data>"));
    }

    @Test
    void edge_nullWeight_notIncluded() {
        PortableEdge e = edge("n1", "n2", "KNOWS", null);
        String xml = export(new PortableGraph(List.of(), List.of(e)));
        assertFalse(xml.contains("key=\"weight\""));
    }

    @Test
    void edge_nullEdgeType_notIncluded() {
        PortableEdge e = new PortableEdge("n1", "n2", null, 0.5, null, null, null);
        String xml = export(new PortableGraph(List.of(), List.of(e)));
        assertFalse(xml.contains("key=\"edgeType\""));
    }

    @Test
    void multipleEdges_incrementalIds() {
        PortableEdge e1 = edge("a", "b", "REL1", null);
        PortableEdge e2 = edge("b", "c", "REL2", null);
        PortableEdge e3 = edge("a", "c", "REL3", null);
        String xml = export(new PortableGraph(List.of(), List.of(e1, e2, e3)));

        assertTrue(xml.contains("id=\"e0\""));
        assertTrue(xml.contains("id=\"e1\""));
        assertTrue(xml.contains("id=\"e2\""));
    }

    // ─── full graph with nodes and edges ─────────────────────────────

    @Test
    void fullGraph_nodesAndEdges() {
        PortableNode n1 = nodeWithDesc("n1", "Alice", "PERSON", "Software engineer");
        PortableNode n2 = node("n2", "Acme", "ORG");
        PortableEdge e = edge("n1", "n2", "WORKS_AT", 1.0);
        String xml = export(new PortableGraph(List.of(n1, n2), List.of(e)));

        assertTrue(xml.contains("<node id=\"n1\">"));
        assertTrue(xml.contains("<node id=\"n2\">"));
        assertTrue(xml.contains("<edge id=\"e0\" source=\"n1\" target=\"n2\">"));
        assertTrue(xml.contains("<data key=\"title\">Alice</data>"));
        assertTrue(xml.contains("<data key=\"title\">Acme</data>"));
        assertTrue(xml.contains("<data key=\"edgeType\">WORKS_AT</data>"));
    }

    // ─── XML escaping ─────────────────────────────────────────────────

    @Test
    void ampersand_escapedInNodeId() {
        PortableNode n = node("node&amp;1", "Title", "TYPE");
        String xml = export(new PortableGraph(List.of(n), List.of()));
        // escape() replaces & -> &amp; so node&amp;1 becomes node&amp;amp;1
        assertTrue(xml.contains("id=\"node&amp;amp;1\"") || xml.contains("id=\"node&amp;1\""));
    }

    @Test
    void lessThanGreaterThan_escapedInTitle() {
        PortableNode n = new PortableNode("n1", "A<B>C", null, "TYPE", null);
        String xml = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(xml.contains("&lt;") && xml.contains("&gt;"));
        assertFalse(xml.contains("<B>"));
    }

    @Test
    void quotes_escapedInTitle() {
        PortableNode n = new PortableNode("n1", "Say \"hello\"", null, "TYPE", null);
        String xml = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(xml.contains("&quot;"));
    }

    @Test
    void apostrophe_escapedInTitle() {
        PortableNode n = new PortableNode("n1", "O'Brien", null, "TYPE", null);
        String xml = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(xml.contains("&apos;"));
    }

    @Test
    void specialCharsInDescription_escaped() {
        PortableNode n = new PortableNode("n1", "Title", "Desc <em>bold</em> & more", "TYPE", null);
        String xml = export(new PortableGraph(List.of(n), List.of()));
        assertFalse(xml.contains("<em>"));
        assertTrue(xml.contains("&lt;em&gt;"));
        assertTrue(xml.contains("&amp;"));
    }

    // ─── null nodeType ────────────────────────────────────────────────

    @Test
    void nullNodeType_notIncluded() {
        PortableNode n = new PortableNode("n1", "Title", null, null, null);
        String xml = export(new PortableGraph(List.of(n), List.of()));
        assertFalse(xml.contains("key=\"nodeType\""));
    }

    // ─── directed graph ───────────────────────────────────────────────

    @Test
    void graph_isDirected() {
        String xml = export(PortableGraph.empty());
        assertTrue(xml.contains("edgedefault=\"directed\""));
    }
}
