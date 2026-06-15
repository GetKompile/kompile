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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonGraphImporter} — both {@code parse(byte[])} and
 * {@code parse(InputStream)}, empty graph, special characters, metadata, error handling.
 */
class JsonGraphImporterTest {

    private JsonGraphImporter importer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        importer = new JsonGraphImporter(mapper);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private InputStream stream(String s) {
        return new ByteArrayInputStream(json(s));
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_parsedFromBytes() throws IOException {
        String j = "{\"nodes\":[],\"edges\":[]}";
        PortableGraph g = importer.parse(json(j));
        assertNotNull(g);
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void emptyGraph_parsedFromStream() throws IOException {
        String j = "{\"nodes\":[],\"edges\":[]}";
        PortableGraph g = importer.parse(stream(j));
        assertNotNull(g);
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    // ─── single node (bytes) ──────────────────────────────────────────

    @Test
    void singleNode_parsedFromBytes() throws IOException {
        String j = """
                {
                  "nodes": [
                    {"externalId":"n1","title":"Alice","description":"A person","nodeType":"PERSON"}
                  ],
                  "edges": []
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertEquals(1, g.nodes().size());
        PortableNode n = g.nodes().get(0);
        assertEquals("n1", n.externalId());
        assertEquals("Alice", n.title());
        assertEquals("A person", n.description());
        assertEquals("PERSON", n.nodeType());
    }

    // ─── single node (stream) ─────────────────────────────────────────

    @Test
    void singleNode_parsedFromStream() throws IOException {
        String j = """
                {
                  "nodes": [
                    {"externalId":"n1","title":"Bob","nodeType":"PERSON"}
                  ],
                  "edges": []
                }
                """;
        PortableGraph g = importer.parse(stream(j));
        assertEquals(1, g.nodes().size());
        assertEquals("Bob", g.nodes().get(0).title());
    }

    // ─── multiple nodes ───────────────────────────────────────────────

    @Test
    void multipleNodes_allParsed() throws IOException {
        String j = """
                {
                  "nodes": [
                    {"externalId":"n1","title":"Alice","nodeType":"PERSON"},
                    {"externalId":"n2","title":"Bob","nodeType":"PERSON"},
                    {"externalId":"n3","title":"Acme","nodeType":"ORG"}
                  ],
                  "edges": []
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertEquals(3, g.nodes().size());
    }

    // ─── edges ────────────────────────────────────────────────────────

    @Test
    void singleEdge_parsedCorrectly() throws IOException {
        String j = """
                {
                  "nodes": [],
                  "edges": [
                    {
                      "fromExternalId":"n1",
                      "toExternalId":"n2",
                      "edgeType":"KNOWS",
                      "weight":0.8,
                      "description":"met at conf",
                      "provenance":"EXTRACTED",
                      "confidence":0.95
                    }
                  ]
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertEquals(1, g.edges().size());
        PortableEdge e = g.edges().get(0);
        assertEquals("n1", e.fromExternalId());
        assertEquals("n2", e.toExternalId());
        assertEquals("KNOWS", e.edgeType());
        assertEquals(0.8, e.weight(), 0.001);
        assertEquals("met at conf", e.description());
        assertEquals("EXTRACTED", e.provenance());
        assertEquals(0.95, e.confidence(), 0.001);
    }

    @Test
    void multipleEdges_allParsed() throws IOException {
        String j = """
                {
                  "nodes": [],
                  "edges": [
                    {"fromExternalId":"a","toExternalId":"b","edgeType":"REL1"},
                    {"fromExternalId":"b","toExternalId":"c","edgeType":"REL2"},
                    {"fromExternalId":"a","toExternalId":"c","edgeType":"REL3"}
                  ]
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertEquals(3, g.edges().size());
    }

    @Test
    void edgeWithNullWeight_parsedAsNull() throws IOException {
        String j = """
                {
                  "nodes": [],
                  "edges": [
                    {"fromExternalId":"a","toExternalId":"b","edgeType":"R"}
                  ]
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertNull(g.edges().get(0).weight());
    }

    // ─── metadata ────────────────────────────────────────────────────

    @Test
    void nodeWithMetadata_parsed() throws IOException {
        String j = """
                {
                  "nodes": [
                    {
                      "externalId":"n1",
                      "title":"Title",
                      "nodeType":"CONCEPT",
                      "metadata":{"color":"blue","priority":"high"}
                    }
                  ],
                  "edges": []
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertNotNull(g.nodes().get(0).metadata());
        assertEquals("blue", g.nodes().get(0).metadata().get("color").toString());
        assertEquals("high", g.nodes().get(0).metadata().get("priority").toString());
    }

    // ─── special characters ───────────────────────────────────────────

    @Test
    void specialCharsInTitle_preserved() throws IOException {
        String j = """
                {
                  "nodes": [
                    {"externalId":"n1","title":"Acme \\"Corp\\" & <Partners>","nodeType":"ORG"}
                  ],
                  "edges": []
                }
                """;
        PortableGraph g = importer.parse(json(j));
        assertEquals("Acme \"Corp\" & <Partners>", g.nodes().get(0).title());
    }

    @Test
    void unicodeChars_preserved() throws IOException {
        String j = "{\"nodes\":[{\"externalId\":\"n1\",\"title\":\"日本語\",\"nodeType\":\"ENTITY\"}],\"edges\":[]}";
        PortableGraph g = importer.parse(json(j));
        assertEquals("日本語", g.nodes().get(0).title());
    }

    // ─── error handling ───────────────────────────────────────────────

    @Test
    void invalidJson_throwsIOException() {
        assertThrows(IOException.class, () -> importer.parse(json("not json at all")));
    }

    @Test
    void invalidJsonStream_throwsIOException() {
        assertThrows(IOException.class, () -> importer.parse(stream("{broken")));
    }

    // ─── nodes and edges together ─────────────────────────────────────

    @Test
    void nodesAndEdges_parsedTogether() throws IOException {
        PortableNode n1 = new PortableNode("e1", "Apple", "Tech", "ORG", null);
        PortableNode n2 = new PortableNode("e2", "Google", null, "ORG", null);
        PortableEdge edge = new PortableEdge("e1", "e2", "COMPETES", 0.7, null, null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        // Export then parse
        JsonGraphExporter exporter = new JsonGraphExporter(mapper);
        byte[] bytes = exporter.toBytes(original);
        PortableGraph parsed = importer.parse(bytes);

        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());
        assertEquals("e1", parsed.edges().get(0).fromExternalId());
        assertEquals("e2", parsed.edges().get(0).toExternalId());
    }

    // ─── both parse methods are consistent ────────────────────────────

    @Test
    void byteAndStreamParse_sameResult() throws IOException {
        String j = """
                {
                  "nodes":[{"externalId":"x","title":"X","nodeType":"T"}],
                  "edges":[{"fromExternalId":"x","toExternalId":"y","edgeType":"R"}]
                }
                """;
        PortableGraph fromBytes = importer.parse(json(j));
        PortableGraph fromStream = importer.parse(stream(j));

        assertEquals(fromBytes.nodes().size(), fromStream.nodes().size());
        assertEquals(fromBytes.edges().size(), fromStream.edges().size());
        assertEquals(fromBytes.nodes().get(0).externalId(), fromStream.nodes().get(0).externalId());
    }
}
