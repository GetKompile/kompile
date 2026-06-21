/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip fidelity tests for the RDF exporters/importers:
 * {@link NTriplesGraphExporter} ⇄ {@link NTriplesGraphImporter} and
 * {@link TurtleGraphExporter} ⇄ {@link TurtleGraphImporter}.
 *
 * <p>N-Triples round-trips are tested first because the Turtle importer delegates
 * its final term parsing to {@link NTriplesGraphImporter}.</p>
 */
class RdfRoundTripTest {

    // ─── shared fixtures ────────────────────────────────────────────────────

    private static PortableGraph simpleGraph() {
        PortableNode alice = new PortableNode("alice", "Alice", "A person", "PERSON", null);
        PortableNode bob   = new PortableNode("bob",   "Bob",   "A person", "PERSON", null);
        PortableEdge edge  = new PortableEdge("alice", "bob", "KNOWS", null, null);
        return new PortableGraph(List.of(alice, bob), List.of(edge));
    }

    private static PortableGraph enrichedGraph() {
        PortableNode n = new PortableNode(
                "acme", "Acme Corp", "A company", "ENTITY",
                Map.of("industry", "retail"),
                null, "graph-ng-1", 0.85, "2026-01-10T09:00");
        PortableEdge e = new PortableEdge("acme", "bob", "RELATED_TO", null, null);
        PortableNode bob = new PortableNode("bob", "Bob", null, "PERSON", null);
        return new PortableGraph(List.of(n, bob), List.of(e));
    }

    // ─── NTriples: basic round-trip ─────────────────────────────────────────

    @Test
    void ntriples_roundtrip_nodeCount() {
        PortableGraph g = simpleGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);
        assertEquals(g.nodes().size(), imported.nodes().size(),
                "Node count must survive N-Triples round-trip");
    }

    @Test
    void ntriples_roundtrip_edgeCount() {
        PortableGraph g = simpleGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);
        assertEquals(g.edges().size(), imported.edges().size(),
                "Edge count must survive N-Triples round-trip");
    }

    @Test
    void ntriples_roundtrip_nodeFields() {
        PortableGraph g = simpleGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);

        PortableNode alice = findNode(imported, "alice");
        assertNotNull(alice, "alice node must be present after round-trip");
        assertEquals("Alice", alice.title(), "title must survive");
        assertEquals("A person", alice.description(), "description must survive");
        assertEquals("PERSON", alice.nodeType(), "nodeType must survive");
    }

    @Test
    void ntriples_roundtrip_edgeFields() {
        PortableGraph g = simpleGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);

        PortableEdge edge = imported.edges().get(0);
        assertEquals("alice", edge.fromExternalId(), "fromExternalId must survive");
        assertEquals("bob",   edge.toExternalId(),   "toExternalId must survive");
        assertEquals("KNOWS", edge.edgeType(),         "edgeType must survive");
    }

    @Test
    void ntriples_roundtrip_confidence() {
        PortableGraph g = enrichedGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);

        PortableNode acme = findNode(imported, "acme");
        assertNotNull(acme, "acme node must be present");
        assertNotNull(acme.confidence(), "confidence must survive");
        assertEquals(0.85, acme.confidence(), 1e-9, "confidence value must be exact");
    }

    @Test
    void ntriples_roundtrip_occurredAt() {
        PortableGraph g = enrichedGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);

        PortableNode acme = findNode(imported, "acme");
        assertNotNull(acme);
        assertEquals("2026-01-10T09:00", acme.occurredAt(), "occurredAt must survive");
    }

    @Test
    void ntriples_roundtrip_metadata() {
        PortableGraph g = enrichedGraph();
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);

        PortableNode acme = findNode(imported, "acme");
        assertNotNull(acme);
        assertNotNull(acme.metadata(), "metadata must not be null");
        assertEquals("retail", acme.metadata().get("industry"), "metadata['industry'] must survive");
    }

    @Test
    void ntriples_roundtrip_emptyGraph() {
        byte[] exported = new NTriplesGraphExporter().toBytes(PortableGraph.empty());
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);
        assertTrue(imported.nodes().isEmpty());
        assertTrue(imported.edges().isEmpty());
    }

    @Test
    void ntriples_nullPayload_returnsEmptyGraph() {
        PortableGraph g = new NTriplesGraphImporter().parse(null);
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void ntriples_roundtrip_specialCharactersInTitle() {
        PortableNode n = new PortableNode("n1", "Say \"hi\"\nthere", null, "ENTITY", null);
        PortableGraph g = new PortableGraph(List.of(n), List.of());
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);
        PortableNode back = findNode(imported, "n1");
        assertNotNull(back);
        assertEquals("Say \"hi\"\nthere", back.title(), "embedded quotes and newlines must round-trip");
    }

    @Test
    void ntriples_roundtrip_percentEncodedEdgeType() {
        // Edge types with spaces are percent-encoded in IRIs
        PortableEdge edge = new PortableEdge("a", "b", "WORKS WITH", null, null);
        PortableNode a = new PortableNode("a", "A", null, "ENTITY", null);
        PortableNode b = new PortableNode("b", "B", null, "ENTITY", null);
        PortableGraph g = new PortableGraph(List.of(a, b), List.of(edge));
        byte[] exported = new NTriplesGraphExporter().toBytes(g);
        PortableGraph imported = new NTriplesGraphImporter().parse(exported);
        assertEquals(1, imported.edges().size(), "edge must survive");
        assertEquals("WORKS WITH", imported.edges().get(0).edgeType(), "space-containing edge type must round-trip");
    }

    // ─── Turtle: basic round-trip ────────────────────────────────────────────

    @Test
    void turtle_roundtrip_nodeCount() {
        PortableGraph g = simpleGraph();
        byte[] exported = new TurtleGraphExporter().toBytes(g);
        PortableGraph imported = new TurtleGraphImporter().parse(exported);
        assertEquals(g.nodes().size(), imported.nodes().size(),
                "Node count must survive Turtle round-trip");
    }

    @Test
    void turtle_roundtrip_edgeCount() {
        PortableGraph g = simpleGraph();
        byte[] exported = new TurtleGraphExporter().toBytes(g);
        PortableGraph imported = new TurtleGraphImporter().parse(exported);
        assertEquals(g.edges().size(), imported.edges().size(),
                "Edge count must survive Turtle round-trip");
    }

    @Test
    void turtle_roundtrip_nodeTitle() {
        PortableGraph g = simpleGraph();
        byte[] exported = new TurtleGraphExporter().toBytes(g);
        PortableGraph imported = new TurtleGraphImporter().parse(exported);

        PortableNode alice = findNode(imported, "alice");
        assertNotNull(alice, "alice must survive Turtle round-trip");
        assertEquals("Alice", alice.title(), "title must survive Turtle round-trip");
    }

    @Test
    void turtle_roundtrip_edgeType() {
        PortableGraph g = simpleGraph();
        byte[] exported = new TurtleGraphExporter().toBytes(g);
        PortableGraph imported = new TurtleGraphImporter().parse(exported);

        assertFalse(imported.edges().isEmpty(), "edges must survive Turtle round-trip");
        PortableEdge edge = imported.edges().get(0);
        assertEquals("KNOWS", edge.edgeType(), "edgeType must survive Turtle round-trip");
        assertEquals("alice", edge.fromExternalId(), "fromExternalId must survive Turtle round-trip");
        assertEquals("bob",   edge.toExternalId(),   "toExternalId must survive Turtle round-trip");
    }

    @Test
    void turtle_roundtrip_emptyGraph() {
        byte[] exported = new TurtleGraphExporter().toBytes(PortableGraph.empty());
        PortableGraph imported = new TurtleGraphImporter().parse(exported);
        assertTrue(imported.nodes().isEmpty());
        assertTrue(imported.edges().isEmpty());
    }

    @Test
    void turtle_nullPayload_returnsEmptyGraph() {
        PortableGraph g = new TurtleGraphImporter().parse(null);
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void turtle_roundtrip_nodeType() {
        PortableGraph g = simpleGraph();
        byte[] exported = new TurtleGraphExporter().toBytes(g);
        PortableGraph imported = new TurtleGraphImporter().parse(exported);

        PortableNode alice = findNode(imported, "alice");
        assertNotNull(alice);
        assertEquals("PERSON", alice.nodeType(), "nodeType must survive Turtle round-trip");
    }

    // ─── NTriples literal-parser unit tests ──────────────────────────────────

    @Test
    void extractLiteral_simpleLiteral() {
        assertEquals("hello", NTriplesGraphImporter.extractLiteral("\"hello\""));
    }

    @Test
    void extractLiteral_literalWithDatatype() {
        assertEquals("0.9", NTriplesGraphImporter.extractLiteral(
                "\"0.9\"^^<http://www.w3.org/2001/XMLSchema#double>"));
    }

    @Test
    void extractLiteral_escapedQuote() {
        assertEquals("say \"hi\"", NTriplesGraphImporter.extractLiteral("\"say \\\"hi\\\"\""));
    }

    @Test
    void extractLiteral_escapedNewline() {
        assertEquals("line1\nline2", NTriplesGraphImporter.extractLiteral("\"line1\\nline2\""));
    }

    @Test
    void extractLiteral_notALiteral() {
        assertNull(NTriplesGraphImporter.extractLiteral("<http://example.org>"));
    }

    // ─── decode unit tests ───────────────────────────────────────────────────

    @Test
    void decode_percentEncodedSpace() {
        assertEquals("WORKS WITH", NTriplesGraphImporter.decode("WORKS%20WITH"));
    }

    @Test
    void decode_noEncoding() {
        assertEquals("ENTITY", NTriplesGraphImporter.decode("ENTITY"));
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private static PortableNode findNode(PortableGraph g, String externalId) {
        return g.nodes().stream()
                .filter(n -> externalId.equals(n.externalId()))
                .findFirst()
                .orElse(null);
    }
}
