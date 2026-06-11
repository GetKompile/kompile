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
 * Dedicated tests for {@link SvgGraphExporter}.
 * Complements the tests in {@link GraphExporterTest}.
 */
class SvgGraphExporterTest {

    private SvgGraphExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new SvgGraphExporter();
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
        return new PortableEdge(from, to, type, null, null, prov, null);
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_returnsNonEmptyBytes() {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void emptyGraph_containsEmptyGraphText() {
        String svg = export(PortableGraph.empty());
        assertTrue(svg.contains("Empty graph"));
    }

    @Test
    void emptyGraph_hasSvgElement() {
        String svg = export(PortableGraph.empty());
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("</svg>"));
    }

    // ─── non-empty graph structure ────────────────────────────────────

    @Test
    void nonEmptyGraph_xmlDeclaration() {
        String svg = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
    }

    @Test
    void nonEmptyGraph_svgNamespace() {
        String svg = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""));
    }

    @Test
    void nonEmptyGraph_hasBackgroundRect() {
        String svg = export(new PortableGraph(List.of(node("n1", "A", "T")), List.of()));
        assertTrue(svg.contains("<rect"));
        assertTrue(svg.contains("#0f1117")); // dark background
    }

    @Test
    void nonEmptyGraph_hasDefsWithArrowMarker() {
        String svg = export(new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "R"))));
        assertTrue(svg.contains("<defs>"));
        assertTrue(svg.contains("<marker id=\"arrow\""));
    }

    // ─── nodes ────────────────────────────────────────────────────────

    @Test
    void singleNode_circlePresent() {
        String svg = export(new PortableGraph(List.of(node("n1", "Alpha", "T")), List.of()));
        assertTrue(svg.contains("<circle"));
    }

    @Test
    void singleNode_labelPresent() {
        String svg = export(new PortableGraph(List.of(node("n1", "Alpha", "T")), List.of()));
        assertTrue(svg.contains("Alpha"));
    }

    @Test
    void threeNodes_threeCircles() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T"), node("n3", "C", "T")),
                List.of());
        String svg = export(g);
        // Count circle elements
        int count = svg.split("<circle").length - 1;
        // 3 node circles + possible legend circles
        assertTrue(count >= 3);
    }

    @Test
    void nullNodeType_usesUnknownForLegend() {
        PortableGraph g = new PortableGraph(
                List.of(new PortableNode("n1", "Test", null, null, null)), List.of());
        String svg = export(g);
        assertTrue(svg.contains("UNKNOWN"));
    }

    // ─── edges ────────────────────────────────────────────────────────

    @Test
    void edge_lineElement() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "KNOWS")));
        String svg = export(g);
        assertTrue(svg.contains("<line"));
    }

    @Test
    void edge_label() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "KNOWS")));
        String svg = export(g);
        assertTrue(svg.contains("KNOWS"));
    }

    @Test
    void edge_markerEnd() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "R")));
        String svg = export(g);
        assertTrue(svg.contains("marker-end=\"url(#arrow)\""));
    }

    // ─── provenance colours ───────────────────────────────────────────

    @Test
    void extractedEdge_greenStroke() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edgeWithProv("n1", "n2", "R", "EXTRACTED")));
        String svg = export(g);
        assertTrue(svg.contains("#4caf50")); // extracted = green
    }

    @Test
    void inferredEdge_orangeStrokeAndDash() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edgeWithProv("n1", "n2", "R", "INFERRED")));
        String svg = export(g);
        assertTrue(svg.contains("#ff9800")); // inferred = orange
        assertTrue(svg.contains("stroke-dasharray"));
    }

    @Test
    void ambiguousEdge_redStrokeAndDash() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edgeWithProv("n1", "n2", "R", "AMBIGUOUS")));
        String svg = export(g);
        assertTrue(svg.contains("#f44336")); // ambiguous = red
    }

    // ─── legend ───────────────────────────────────────────────────────

    @Test
    void legend_nodeTypesTitle() {
        String svg = export(new PortableGraph(List.of(node("n1", "A", "ORG")), List.of()));
        assertTrue(svg.contains("Node Types"));
    }

    @Test
    void legend_multipleTypes() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "ORG"), node("n2", "B", "PERSON")), List.of());
        String svg = export(g);
        assertTrue(svg.contains("ORG"));
        assertTrue(svg.contains("PERSON"));
    }

    // ─── title truncation ─────────────────────────────────────────────

    @Test
    void longTitle_truncatedWithEllipsis() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A very long entity name exceeding twenty chars", "T")), List.of());
        String svg = export(g);
        assertTrue(svg.contains("…"));
    }

    @Test
    void shortTitle_notTruncated() {
        PortableGraph g = new PortableGraph(List.of(node("n1", "Short", "T")), List.of());
        String svg = export(g);
        assertTrue(svg.contains("Short"));
        assertFalse(svg.contains("…"));
    }

    // ─── xml escaping ─────────────────────────────────────────────────

    @Test
    void titleWithAngle_escaped() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A<B>C", "T")), List.of());
        String svg = export(g);
        assertTrue(svg.contains("&lt;") || !svg.contains("<B>"));
    }

    @Test
    void titleWithAmpersand_escaped() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A&B", "T")), List.of());
        String svg = export(g);
        assertTrue(svg.contains("&amp;"));
    }
}
