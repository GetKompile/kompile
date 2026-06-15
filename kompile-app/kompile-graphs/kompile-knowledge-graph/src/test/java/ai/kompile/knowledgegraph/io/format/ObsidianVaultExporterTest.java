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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated tests for {@link ObsidianVaultExporter}.
 * Complements the tests in {@link GraphExporterTest}.
 */
class ObsidianVaultExporterTest {

    private ObsidianVaultExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new ObsidianVaultExporter();
    }

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, null, null, null, null);
    }

    // ─── basic output ─────────────────────────────────────────────────

    @Test
    void output_isNonEmpty() throws Exception {
        byte[] zip = exporter.toZip(PortableGraph.empty());
        assertNotNull(zip);
        assertTrue(zip.length > 0);
    }

    @Test
    void emptyGraph_onlyIndexEntry() throws Exception {
        Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
        assertTrue(entries.containsKey("index.md"));
        // No entity files when no nodes
        assertTrue(entries.keySet().stream().noneMatch(k -> k.startsWith("entities/")));
    }

    @Test
    void threeNodes_threeEntityFiles() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alpha", "T"), node("n2", "Beta", "T"), node("n3", "Gamma", "T")),
                List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertEquals(3, entries.keySet().stream().filter(k -> k.startsWith("entities/")).count());
    }

    // ─── index content ────────────────────────────────────────────────

    @Test
    void index_nodeAndEdgeCounts() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "R")));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String index = entries.get("index.md");
        assertTrue(index.contains("**Nodes:** 2"));
        assertTrue(index.contains("**Edges:** 1"));
    }

    @Test
    void index_yamlFrontmatterTag() throws Exception {
        Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
        String index = entries.get("index.md");
        assertTrue(index.contains("knowledge-graph"));
    }

    // ─── entity file content ──────────────────────────────────────────

    @Test
    void entityFile_h1Title() throws Exception {
        PortableGraph g = new PortableGraph(List.of(node("n1", "Omega", "CONCEPT")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/Omega.md");
        assertNotNull(page);
        assertTrue(page.contains("# Omega"));
    }

    @Test
    void entityFile_frontmatterContainsId() throws Exception {
        PortableGraph g = new PortableGraph(List.of(node("unique-id-123", "MyNode", "TYPE")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/MyNode.md");
        assertNotNull(page);
        assertTrue(page.contains("id: \"unique-id-123\""));
    }

    @Test
    void entityFile_frontmatterContainsType() throws Exception {
        PortableGraph g = new PortableGraph(List.of(node("n1", "Node", "DOCUMENT")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/Node.md");
        assertTrue(page.contains("type: DOCUMENT"));
    }

    @Test
    void entityFile_descriptionShown() throws Exception {
        PortableNode n = new PortableNode("n1", "Alpha", "A long description", "TYPE", null);
        Map<String, String> entries = unzip(exporter.toZip(new PortableGraph(List.of(n), List.of())));
        String page = entries.get("entities/Alpha.md");
        assertTrue(page.contains("A long description"));
    }

    // ─── relationships ────────────────────────────────────────────────

    @Test
    void outgoingEdge_wikilink() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Source", "T"), node("n2", "Target", "T")),
                List.of(edge("n1", "n2", "POINTS_TO")));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/Source.md");
        assertTrue(page.contains("[[entities/Target|Target]]"));
        assertTrue(page.contains("POINTS_TO"));
    }

    @Test
    void incomingEdge_wikilink() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Source", "T"), node("n2", "Target", "T")),
                List.of(edge("n1", "n2", "POINTS_TO")));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/Target.md");
        assertTrue(page.contains("[[entities/Source|Source]]"));
    }

    @Test
    void edgeWithProvenance_backtickFormatting() throws Exception {
        PortableEdge e = new PortableEdge("n1", "n2", "REL", null, null, "EXTRACTED", null);
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(e));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/A.md");
        assertTrue(page.contains("`EXTRACTED`"));
    }

    @Test
    void edgeWithDescription_shownAfterDash() throws Exception {
        PortableEdge e = new PortableEdge("n1", "n2", "REL", null, "partnership deal", null, null);
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(e));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String page = entries.get("entities/A.md");
        assertTrue(page.contains("partnership deal"));
    }

    // ─── metadata section ─────────────────────────────────────────────

    @Test
    void metadata_displayedInEntityPage() throws Exception {
        PortableNode n = new PortableNode("n1", "Node", null, "TYPE",
                Map.of("source", "dbpedia", "confidence", 0.92));
        Map<String, String> entries = unzip(exporter.toZip(new PortableGraph(List.of(n), List.of())));
        String page = entries.get("entities/Node.md");
        assertTrue(page.contains("## Metadata"));
        assertTrue(page.contains("source"));
    }

    // ─── null nodeType defaults to UNKNOWN ───────────────────────────

    @Test
    void nullNodeType_usesUnknown() throws Exception {
        PortableNode n = new PortableNode("n1", "Nameless", null, null, null);
        Map<String, String> entries = unzip(exporter.toZip(new PortableGraph(List.of(n), List.of())));
        String page = entries.get("entities/Nameless.md");
        assertTrue(page.contains("UNKNOWN"));
    }

    // ─── filename sanitisation ────────────────────────────────────────

    @Test
    void spacesInTitle_replacedWithHyphen() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Two Words", "TYPE")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertTrue(entries.containsKey("entities/Two-Words.md"));
    }
}
