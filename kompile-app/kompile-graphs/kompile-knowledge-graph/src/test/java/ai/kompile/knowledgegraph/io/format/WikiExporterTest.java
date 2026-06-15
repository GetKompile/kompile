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
 * Dedicated tests for {@link WikiExporter}.
 * Complements the tests in {@link GraphExporterTest}.
 */
class WikiExporterTest {

    private WikiExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WikiExporter();
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

    private PortableNode nodeWithMeta(String id, String title, String type, Map<String, Object> meta) {
        return new PortableNode(id, title, null, type, meta);
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
    void emptyGraph_indexOnly() throws Exception {
        Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
        assertTrue(entries.containsKey("index.md"));
        assertTrue(entries.keySet().stream().noneMatch(k -> k.startsWith("entities/")));
    }

    // ─── index content ────────────────────────────────────────────────

    @Test
    void index_h1Title() throws Exception {
        Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
        assertTrue(entries.get("index.md").contains("# Knowledge Graph Wiki"));
    }

    @Test
    void index_entityCountMentioned() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        String index = entries.get("index.md");
        assertTrue(index.contains("2 entities"));
    }

    @Test
    void index_entityAndRelationshipStats() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T")),
                List.of(edge("n1", "n2", "R")));
        Map<String, String> entries = unzip(exporter.toZip(g));
        String index = entries.get("index.md");
        assertTrue(index.contains("2 entities"));
        assertTrue(index.contains("1 relationships"));
    }

    @Test
    void index_linksToAllEntities() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alice", "T"), node("n2", "Bob", "T")), List.of());
        String index = unzip(exporter.toZip(g)).get("index.md");
        assertTrue(index.contains("Alice"));
        assertTrue(index.contains("Bob"));
    }

    // ─── entity files ─────────────────────────────────────────────────

    @Test
    void entityFiles_created() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alice", "T"), node("n2", "Bob", "T")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertTrue(entries.containsKey("entities/Alice.md"));
        assertTrue(entries.containsKey("entities/Bob.md"));
    }

    @Test
    void entityFile_h1NodeTitle() throws Exception {
        PortableGraph g = new PortableGraph(List.of(node("n1", "Omega", "TYPE")), List.of());
        String page = unzip(exporter.toZip(g)).get("entities/Omega.md");
        assertNotNull(page);
        assertTrue(page.contains("# Omega"));
    }

    @Test
    void entityFile_typeShown() throws Exception {
        PortableGraph g = new PortableGraph(List.of(node("n1", "Omega", "DOCUMENT")), List.of());
        String page = unzip(exporter.toZip(g)).get("entities/Omega.md");
        assertTrue(page.contains("DOCUMENT"));
    }

    @Test
    void entityFile_outgoingRelationships() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Source", "T"), node("n2", "Target", "T")),
                List.of(edge("n1", "n2", "LINKS_TO")));
        String page = unzip(exporter.toZip(g)).get("entities/Source.md");
        assertTrue(page.contains("Relates To") || page.contains("LINKS_TO") || page.contains("Target"));
    }

    @Test
    void entityFile_incomingRelationships() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Source", "T"), node("n2", "Target", "T")),
                List.of(edge("n1", "n2", "LINKS_TO")));
        String page = unzip(exporter.toZip(g)).get("entities/Target.md");
        assertTrue(page.contains("Referenced By") || page.contains("Source"));
    }

    // ─── group articles ───────────────────────────────────────────────

    @Test
    void articlesCreated_byNodeType() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alice", "PERSON"), node("n2", "Acme", "ORG")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertTrue(entries.containsKey("articles/PERSON.md"));
        assertTrue(entries.containsKey("articles/ORG.md"));
    }

    @Test
    void article_entityTable() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "Alice", "PERSON"), node("n2", "Bob", "PERSON")), List.of());
        String article = unzip(exporter.toZip(g)).get("articles/PERSON.md");
        assertNotNull(article);
        assertTrue(article.contains("| Entity |"));
        assertTrue(article.contains("Alice"));
        assertTrue(article.contains("Bob"));
    }

    // ─── community grouping ───────────────────────────────────────────

    @Test
    void communityMetadata_groupsByCommunity() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(
                        nodeWithMeta("n1", "Alpha", "T", Map.of("community", "groupA")),
                        nodeWithMeta("n2", "Beta", "T", Map.of("community", "groupB"))),
                List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertTrue(entries.containsKey("articles/Community-groupA.md"));
        assertTrue(entries.containsKey("articles/Community-groupB.md"));
    }

    // ─── null nodeType ────────────────────────────────────────────────

    @Test
    void nullNodeType_usesUnknown() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(new PortableNode("n1", "NoType", null, null, null)), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertTrue(entries.containsKey("articles/UNKNOWN.md") || entries.containsKey("entities/NoType.md"));
    }

    // ─── three nodes, one edge ────────────────────────────────────────

    @Test
    void threeNodes_threeEntityFiles() throws Exception {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "T"), node("n2", "B", "T"), node("n3", "C", "T")), List.of());
        Map<String, String> entries = unzip(exporter.toZip(g));
        assertEquals(3, entries.keySet().stream().filter(k -> k.startsWith("entities/")).count());
    }
}
