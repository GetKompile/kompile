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
 * Dedicated tests for {@link HtmlGraphExporter}.
 * Complements the tests in {@link GraphExporterTest}.
 */
class HtmlGraphExporterTest {

    private HtmlGraphExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new HtmlGraphExporter();
    }

    private String export(PortableGraph graph) {
        return new String(exporter.toBytes(graph), StandardCharsets.UTF_8);
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, null, null, null, null);
    }

    private PortableEdge edgeWithProv(String from, String to, String type, String prov) {
        return new PortableEdge(from, to, type, 0.5, null, prov, null);
    }

    // ─── HTML structure ───────────────────────────────────────────────

    @Test
    void output_startsWithDoctype() {
        String html = export(PortableGraph.empty());
        assertTrue(html.startsWith("<!DOCTYPE html>"));
    }

    @Test
    void output_containsHtmlAndBody() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("</html>"));
        assertTrue(html.contains("<body>"));
        assertTrue(html.contains("</body>"));
    }

    @Test
    void output_containsHead() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("<head>"));
        assertTrue(html.contains("</head>"));
        assertTrue(html.contains("<meta charset=\"UTF-8\">"));
    }

    @Test
    void output_titleTag() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("<title>"));
        assertTrue(html.contains("Kompile"));
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_zeroStats() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("0 nodes"));
        assertTrue(html.contains("0 edges"));
    }

    @Test
    void emptyGraph_graphDataEmptyArrays() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("nodes: ["));
        assertTrue(html.contains("links: ["));
    }

    // ─── node data ────────────────────────────────────────────────────

    @Test
    void singleNode_inGraphDataNodes() {
        String html = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(html.contains("\"Alice\""));
        assertTrue(html.contains("\"PERSON\""));
        assertTrue(html.contains("\"n1\""));
    }

    @Test
    void twoNodes_stats() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alice", "PERSON"), node("n2", "Bob", "PERSON")), List.of());
        String html = export(g);
        assertTrue(html.contains("2 nodes"));
    }

    @Test
    void node_radiusField() {
        String html = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(html.contains(",r:"));
    }

    @Test
    void nullNodeType_defaultsToUnknown() {
        PortableGraph g = new PortableGraph(
                List.of(new PortableNode("n1", "Test", null, null, null)), List.of());
        String html = export(g);
        assertTrue(html.contains("UNKNOWN"));
    }

    // ─── edge data ────────────────────────────────────────────────────

    @Test
    void singleEdge_inGraphDataLinks() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "KNOWS")));
        String html = export(g);
        assertTrue(html.contains("\"KNOWS\""));
        assertTrue(html.contains("source:"));
        assertTrue(html.contains("target:"));
    }

    @Test
    void edgeStats_correct() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "R")));
        String html = export(g);
        assertTrue(html.contains("1 edges"));
    }

    @Test
    void edgeWithWeight_inLinks() {
        PortableEdge e = new PortableEdge("n1", "n2", "R", 0.75, null, null, null);
        PortableGraph g = new PortableGraph(List.of(), List.of(e));
        String html = export(g);
        assertTrue(html.contains("0.75"));
    }

    @Test
    void edgeWithNullWeight_defaultsToOne() {
        PortableEdge e = new PortableEdge("n1", "n2", "R", null, null, null, null);
        PortableGraph g = new PortableGraph(List.of(), List.of(e));
        String html = export(g);
        assertTrue(html.contains(",w:1.0"));
    }

    // ─── legend ───────────────────────────────────────────────────────

    @Test
    void legend_nodeTypesPresent() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "ORG"), node("n2", "B", "PERSON")), List.of());
        String html = export(g);
        assertTrue(html.contains("ORG"));
        assertTrue(html.contains("PERSON"));
    }

    @Test
    void legend_legendDiv() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("id=\"legend\""));
    }

    // ─── controls ────────────────────────────────────────────────────

    @Test
    void searchInput_present() {
        String html = export(PortableGraph.empty());
        assertTrue(html.contains("id=\"search\""));
        assertTrue(html.contains("placeholder="));
    }

    // ─── D3 script ────────────────────────────────────────────────────

    @Test
    void d3CdnLink_present() {
        String html = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(html.contains("d3js.org"));
    }

    @Test
    void d3SimulationCode_present() {
        String html = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(html.contains("forceSimulation"));
    }

    // ─── provenance ───────────────────────────────────────────────────

    @Test
    void provenanceInEdge_inLinksData() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edgeWithProv("n1", "n2", "R", "EXTRACTED")));
        String html = export(g);
        assertTrue(html.contains("\"EXTRACTED\""));
    }

    // ─── type colour map ──────────────────────────────────────────────

    @Test
    void typeColors_objectPresent() {
        PortableGraph g = new PortableGraph(List.of(node("n1", "A", "ORG")), List.of());
        String html = export(g);
        assertTrue(html.contains("const typeColors = {"));
        assertTrue(html.contains("\"ORG\""));
    }

    // ─── special character escaping ───────────────────────────────────

    @Test
    void nodeDescriptionWithNewline_escapedInJs() {
        PortableNode n = new PortableNode("n1", "A", "Line1\nLine2", "T", null);
        PortableGraph g = new PortableGraph(List.of(n), List.of());
        String html = export(g);
        assertTrue(html.contains("\\n"));
    }

    @Test
    void titleWithQuotes_escapedInJs() {
        PortableNode n = new PortableNode("n1", "Say \"hello\"", null, "T", null);
        PortableGraph g = new PortableGraph(List.of(n), List.of());
        String html = export(g);
        assertTrue(html.contains("\\\""));
    }
}
