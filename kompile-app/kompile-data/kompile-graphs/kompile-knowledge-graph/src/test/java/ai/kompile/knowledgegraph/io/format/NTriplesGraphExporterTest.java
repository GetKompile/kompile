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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link NTriplesGraphExporter} — real RDF (minted absolute IRIs, one triple per line). */
class NTriplesGraphExporterTest {

    private final NTriplesGraphExporter exporter = new NTriplesGraphExporter();

    private String export(PortableGraph g) {
        return new String(exporter.toBytes(g), StandardCharsets.UTF_8);
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, null, null, null, null);
    }

    @Test
    void emptyGraph_producesNoTriples() {
        assertTrue(export(PortableGraph.empty()).isBlank());
    }

    @Test
    void node_emitsTypeTriple() {
        String nt = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(nt.contains("<https://kompile.ai/kg/node/n1> "
                + "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                + "<https://kompile.ai/kg/class/PERSON> ."), nt);
    }

    @Test
    void node_emitsLabelTriple() {
        String nt = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(nt.contains("<https://kompile.ai/kg/node/n1> "
                + "<http://www.w3.org/2000/01/rdf-schema#label> \"Alice\" ."), nt);
    }

    @Test
    void edge_emitsRelationshipTriple() {
        String nt = export(new PortableGraph(
                List.of(node("alice", "Alice", "PERSON"), node("bob", "Bob", "PERSON")),
                List.of(edge("alice", "bob", "KNOWS"))));
        assertTrue(nt.contains("<https://kompile.ai/kg/node/alice> "
                + "<https://kompile.ai/kg/rel/KNOWS> "
                + "<https://kompile.ai/kg/node/bob> ."), nt);
    }

    @Test
    void everyLineIsWellFormed() {
        String nt = export(new PortableGraph(
                List.of(node("a", "A", "T"), node("b", "B", "T")),
                List.of(edge("a", "b", "REL"))));
        for (String line : nt.split("\n")) {
            if (line.isBlank()) continue;
            assertTrue(line.startsWith("<"), "subject must be an absolute IRI: " + line);
            assertTrue(line.endsWith(" ."), "triple must terminate with ' .': " + line);
        }
    }

    @Test
    void metadata_emitsPropertyTriple() {
        PortableNode n = new PortableNode("n1", "Alice", null, "PERSON", Map.of("color", "blue"));
        String nt = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(nt.contains("<https://kompile.ai/kg/prop/color> \"blue\" ."), nt);
    }

    @Test
    void confidence_emitsTypedDoubleLiteral() {
        // full ctor: externalId, title, description, nodeType, metadata, factSheetId, namedGraphId, confidence, occurredAt
        PortableNode n = new PortableNode("n1", "Alice", null, "PERSON", null, null, null, 0.9, null);
        String nt = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(nt.contains("<https://kompile.ai/kg/prop/confidence> "
                + "\"0.9\"^^<http://www.w3.org/2001/XMLSchema#double> ."), nt);
    }

    @Test
    void literalsAreEscaped() {
        PortableNode n = node("n1", "Say \"hi\"\nthere", "PERSON");
        String nt = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(nt.contains("\\\"hi\\\""), nt);   // embedded quotes escaped
        assertTrue(nt.contains("\\n"), nt);          // newline escaped, not literal
        assertFalse(nt.contains("hi\"\nthere"), "raw newline must not appear inside a literal");
    }

    @Test
    void unsafeLocalNamesArePercentEncoded() {
        String nt = export(new PortableGraph(
                List.of(node("a", "A", "T"), node("b", "B", "T")),
                List.of(edge("a", "b", "WORKS WITH"))));
        assertTrue(nt.contains("/rel/WORKS%20WITH>"), nt); // space -> %20
    }
}
