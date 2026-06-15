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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonGraphExporter} — serialisation correctness, empty graph,
 * special characters, and roundtrip with {@link JsonGraphImporter}.
 */
class JsonGraphExporterTest {

    private JsonGraphExporter exporter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        exporter = new JsonGraphExporter(mapper);
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_producesValidJson() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        // Must be parseable
        JsonNode root = mapper.readTree(bytes);
        assertTrue(root.isObject());
    }

    @Test
    void emptyGraph_hasEmptyArrays() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        JsonNode root = mapper.readTree(bytes);
        assertTrue(root.has("nodes"));
        assertTrue(root.has("edges"));
        assertEquals(0, root.get("nodes").size());
        assertEquals(0, root.get("edges").size());
    }

    // ─── node serialisation ───────────────────────────────────────────

    @Test
    void singleNode_serialisedCorrectly() throws IOException {
        PortableNode node = new PortableNode("n1", "Alice", "A person", "PERSON", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        JsonNode root = mapper.readTree(bytes);

        assertEquals(1, root.get("nodes").size());
        JsonNode n = root.get("nodes").get(0);
        assertEquals("n1", n.get("externalId").asText());
        assertEquals("Alice", n.get("title").asText());
        assertEquals("A person", n.get("description").asText());
        assertEquals("PERSON", n.get("nodeType").asText());
    }

    @Test
    void twoNodes_bothSerialised() throws IOException {
        PortableNode n1 = new PortableNode("n1", "Alice", null, "PERSON", null);
        PortableNode n2 = new PortableNode("n2", "Bob", null, "PERSON", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of()));
        JsonNode root = mapper.readTree(bytes);

        assertEquals(2, root.get("nodes").size());
    }

    @Test
    void nullDescription_omittedFromOutput() throws IOException {
        PortableNode node = new PortableNode("n1", "Alice", null, "PERSON", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        String json = new String(bytes, StandardCharsets.UTF_8);
        // NON_NULL annotation should exclude null fields
        assertFalse(json.contains("\"description\":null"));
    }

    @Test
    void nodeWithMetadata_serialised() throws IOException {
        PortableNode node = new PortableNode("n1", "Alice", null, "PERSON",
                Map.of("source", "wiki", "score", 0.9));
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        JsonNode root = mapper.readTree(bytes);

        JsonNode n = root.get("nodes").get(0);
        assertTrue(n.has("metadata"));
        assertEquals("wiki", n.get("metadata").get("source").asText());
    }

    // ─── edge serialisation ───────────────────────────────────────────

    @Test
    void singleEdge_serialisedCorrectly() throws IOException {
        PortableEdge edge = new PortableEdge("n1", "n2", "KNOWS", 0.8, "desc", "EXTRACTED", 0.9);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(), List.of(edge)));
        JsonNode root = mapper.readTree(bytes);

        assertEquals(1, root.get("edges").size());
        JsonNode e = root.get("edges").get(0);
        assertEquals("n1", e.get("fromExternalId").asText());
        assertEquals("n2", e.get("toExternalId").asText());
        assertEquals("KNOWS", e.get("edgeType").asText());
        assertEquals(0.8, e.get("weight").asDouble(), 0.001);
        assertEquals("desc", e.get("description").asText());
        assertEquals("EXTRACTED", e.get("provenance").asText());
        assertEquals(0.9, e.get("confidence").asDouble(), 0.001);
    }

    @Test
    void nullWeight_omittedFromOutput() throws IOException {
        PortableEdge edge = new PortableEdge("n1", "n2", "KNOWS", null, null, null, null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(), List.of(edge)));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(json.contains("\"weight\":null"));
    }

    @Test
    void multipleEdges_allSerialised() throws IOException {
        PortableEdge e1 = new PortableEdge("n1", "n2", "KNOWS", null, null, null, null);
        PortableEdge e2 = new PortableEdge("n2", "n3", "WORKS_WITH", 0.5, null, null, null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(), List.of(e1, e2)));
        JsonNode root = mapper.readTree(bytes);

        assertEquals(2, root.get("edges").size());
    }

    // ─── special characters ───────────────────────────────────────────

    @Test
    void specialCharsInTitle_properlyEscaped() throws IOException {
        PortableNode node = new PortableNode("n1", "Acme \"Corp\" & <Partners>", null, "ORG", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        // Must parse cleanly — Jackson handles escaping
        JsonNode root = mapper.readTree(bytes);
        assertEquals("Acme \"Corp\" & <Partners>", root.get("nodes").get(0).get("title").asText());
    }

    @Test
    void unicodeChars_preserved() throws IOException {
        PortableNode node = new PortableNode("n1", "日本語テスト", "Описание", "ENTITY", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        JsonNode root = mapper.readTree(bytes);
        assertEquals("日本語テスト", root.get("nodes").get(0).get("title").asText());
        assertEquals("Описание", root.get("nodes").get(0).get("description").asText());
    }

    // ─── output format ────────────────────────────────────────────────

    @Test
    void output_isPrettyPrinted() throws IOException {
        PortableNode node = new PortableNode("n1", "A", null, "T", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(node), List.of()));
        String json = new String(bytes, StandardCharsets.UTF_8);
        // INDENT_OUTPUT produces newlines
        assertTrue(json.contains("\n"));
    }

    // ─── roundtrip with JsonGraphImporter ────────────────────────────

    @Test
    void roundtrip_nodesAndEdges_preserved() throws IOException {
        PortableNode n1 = new PortableNode("a1", "Apple", "Tech giant", "ORG", null);
        PortableNode n2 = new PortableNode("a2", "Google", null, "ORG", null);
        PortableEdge edge = new PortableEdge("a1", "a2", "COMPETES", 0.7, "market", "EXTRACTED", 0.85);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        byte[] bytes = exporter.toBytes(original);
        JsonGraphImporter importer = new JsonGraphImporter(mapper);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());

        PortableNode pn1 = parsed.nodes().get(0);
        assertEquals("a1", pn1.externalId());
        assertEquals("Apple", pn1.title());
        assertEquals("Tech giant", pn1.description());
        assertEquals("ORG", pn1.nodeType());

        PortableEdge pe = parsed.edges().get(0);
        assertEquals("a1", pe.fromExternalId());
        assertEquals("a2", pe.toExternalId());
        assertEquals("COMPETES", pe.edgeType());
        assertEquals(0.7, pe.weight(), 0.001);
        assertEquals("market", pe.description());
        assertEquals("EXTRACTED", pe.provenance());
        assertEquals(0.85, pe.confidence(), 0.001);
    }

    @Test
    void roundtrip_emptyGraph_preserved() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        JsonGraphImporter importer = new JsonGraphImporter(mapper);
        PortableGraph parsed = importer.parse(bytes);

        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    @Test
    void roundtrip_nodeWithMetadata_preserved() throws IOException {
        PortableNode node = new PortableNode("n1", "Test", null, "CONCEPT",
                Map.of("color", "blue", "weight", "42"));
        PortableGraph original = new PortableGraph(List.of(node), List.of());

        byte[] bytes = exporter.toBytes(original);
        JsonGraphImporter importer = new JsonGraphImporter(mapper);
        PortableGraph parsed = importer.parse(bytes);

        assertNotNull(parsed.nodes().get(0).metadata());
        assertEquals("blue", parsed.nodes().get(0).metadata().get("color").toString());
    }
}
