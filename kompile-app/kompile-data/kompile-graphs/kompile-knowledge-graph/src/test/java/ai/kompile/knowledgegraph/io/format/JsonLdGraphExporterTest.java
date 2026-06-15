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
 * Dedicated tests for {@link JsonLdGraphExporter}.
 * Complements the roundtrip tests in {@link JsonLdRoundtripTest}.
 */
class JsonLdGraphExporterTest {

    private JsonLdGraphExporter exporter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        exporter = new JsonLdGraphExporter(mapper);
    }

    private JsonNode parse(byte[] bytes) throws IOException {
        return mapper.readTree(bytes);
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, null, null, null, null);
    }

    // ─── top-level structure ─────────────────────────────────────────

    @Test
    void output_containsAtContext() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        JsonNode root = parse(bytes);
        assertTrue(root.has("@context"));
    }

    @Test
    void output_containsAtGraph() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        JsonNode root = parse(bytes);
        assertTrue(root.has("@graph"));
        assertTrue(root.get("@graph").isArray());
    }

    @Test
    void context_containsSchemaOrg() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("schema.org"));
    }

    // ─── empty graph ──────────────────────────────────────────────────

    @Test
    void emptyGraph_graphArrayIsEmpty() throws IOException {
        byte[] bytes = exporter.toBytes(PortableGraph.empty());
        JsonNode root = parse(bytes);
        assertEquals(0, root.get("@graph").size());
    }

    // ─── node serialisation ───────────────────────────────────────────

    @Test
    void singleNode_inGraphArray() throws IOException {
        PortableNode n = node("n1", "Alice", "PERSON");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n), List.of()));
        JsonNode root = parse(bytes);
        assertEquals(1, root.get("@graph").size());
        JsonNode entry = root.get("@graph").get(0);
        assertEquals("n1", entry.get("@id").asText());
        assertEquals("PERSON", entry.get("@type").asText());
        assertEquals("Alice", entry.get("title").asText());
    }

    @Test
    void nodeWithDescription_descriptionPresent() throws IOException {
        PortableNode n = new PortableNode("n1", "Alice", "An engineer", "PERSON", null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n), List.of()));
        JsonNode root = parse(bytes);
        assertEquals("An engineer", root.get("@graph").get(0).get("description").asText());
    }

    @Test
    void nullDescription_omittedFromEntry() throws IOException {
        PortableNode n = node("n1", "Alice", "PERSON");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n), List.of()));
        JsonNode root = parse(bytes);
        assertFalse(root.get("@graph").get(0).has("description"));
    }

    @Test
    void nodeMetadata_includedInEntry() throws IOException {
        PortableNode n = new PortableNode("n1", "Alice", null, "PERSON",
                Map.of("color", "blue", "rank", "senior"));
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n), List.of()));
        JsonNode root = parse(bytes);
        JsonNode entry = root.get("@graph").get(0);
        assertEquals("blue", entry.get("color").asText());
        assertEquals("senior", entry.get("rank").asText());
    }

    @Test
    void multipleNodes_allInGraphArray() throws IOException {
        PortableNode n1 = node("n1", "Alice", "PERSON");
        PortableNode n2 = node("n2", "Bob", "PERSON");
        PortableNode n3 = node("n3", "Acme", "ORG");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2, n3), List.of()));
        JsonNode root = parse(bytes);
        assertEquals(3, root.get("@graph").size());
    }

    // ─── edge as @id reference ────────────────────────────────────────

    @Test
    void edge_addedAsIdReferenceOnSourceNode() throws IOException {
        PortableNode n1 = node("alice", "Alice", "PERSON");
        PortableNode n2 = node("bob", "Bob", "PERSON");
        PortableEdge e = edge("alice", "bob", "KNOWS");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of(e)));
        JsonNode root = parse(bytes);

        // Find Alice's entry
        JsonNode aliceEntry = null;
        for (JsonNode entry : root.get("@graph")) {
            if ("alice".equals(entry.get("@id").asText())) {
                aliceEntry = entry;
                break;
            }
        }
        assertNotNull(aliceEntry);
        String edgeKey = "knows"; // lowercase of KNOWS
        assertTrue(aliceEntry.has(edgeKey));
    }

    @Test
    void edgeType_usedAsLowercaseKey() throws IOException {
        PortableNode n1 = node("a", "A", "T");
        PortableNode n2 = node("b", "B", "T");
        PortableEdge e = edge("a", "b", "WORKS_WITH");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of(e)));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("works_with"));
    }

    @Test
    void nullEdgeType_usedAsRelatedToKey() throws IOException {
        PortableNode n1 = node("a", "A", "T");
        PortableNode n2 = node("b", "B", "T");
        PortableEdge e = new PortableEdge("a", "b", null, null, null, null, null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of(e)));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("relatedTo"));
    }

    @Test
    void edge_weightIncludedInRef() throws IOException {
        PortableNode n1 = node("a", "A", "T");
        PortableNode n2 = node("b", "B", "T");
        PortableEdge e = new PortableEdge("a", "b", "SCORED", 0.75, null, null, null);
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n1, n2), List.of(e)));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("0.75"));
    }

    @Test
    void unknownSourceNode_stubCreated() throws IOException {
        // Edge references a node not in the node list — exporter creates a stub
        PortableNode known = node("known", "Known", "TYPE");
        PortableEdge e = edge("unknown-src", "known", "REFS");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(known), List.of(e)));
        JsonNode root = parse(bytes);
        // Stub node for unknown-src should appear
        assertEquals(2, root.get("@graph").size());
    }

    // ─── pretty printing ──────────────────────────────────────────────

    @Test
    void output_isPrettyPrinted() throws IOException {
        PortableNode n = node("n1", "A", "T");
        byte[] bytes = exporter.toBytes(new PortableGraph(List.of(n), List.of()));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\n"));
    }
}
