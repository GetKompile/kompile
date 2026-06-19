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

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link TurtleGraphExporter} — real RDF grouped by subject with @prefix + 'a' shorthand. */
class TurtleGraphExporterTest {

    private final TurtleGraphExporter exporter = new TurtleGraphExporter();

    private String export(PortableGraph g) {
        return new String(exporter.toBytes(g), StandardCharsets.UTF_8);
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    @Test
    void declaresPrefixes() {
        String ttl = export(PortableGraph.empty());
        assertTrue(ttl.contains("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."), ttl);
        assertTrue(ttl.contains("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> ."), ttl);
    }

    @Test
    void node_usesTypeShorthandAndLabel() {
        String ttl = export(new PortableGraph(List.of(node("n1", "Alice", "PERSON")), List.of()));
        assertTrue(ttl.contains("<https://kompile.ai/kg/node/n1>"), ttl);
        assertTrue(ttl.contains("a <https://kompile.ai/kg/class/PERSON>"), ttl);
        assertTrue(ttl.contains("rdfs:label \"Alice\""), ttl);
    }

    @Test
    void multiPredicateSubject_groupedWithSemicolonsThenDot() {
        PortableNode n = new PortableNode("n1", "Alice", "An engineer", "PERSON", null);
        String ttl = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(ttl.contains(" ;\n"), "multiple predicates separated by ';': " + ttl);
        assertTrue(ttl.contains("rdfs:comment \"An engineer\""), ttl);
        assertTrue(ttl.trim().endsWith("."), "subject block terminates with '.': " + ttl);
    }

    @Test
    void edge_emitsRelationshipObject() {
        String ttl = export(new PortableGraph(
                List.of(node("alice", "Alice", "PERSON"), node("bob", "Bob", "PERSON")),
                List.of(new PortableEdge("alice", "bob", "KNOWS", null, null, null, null))));
        assertTrue(ttl.contains("<https://kompile.ai/kg/rel/KNOWS> <https://kompile.ai/kg/node/bob>"), ttl);
    }

    @Test
    void confidence_usesXsdDoubleShorthand() {
        PortableNode n = new PortableNode("n1", "Alice", null, "PERSON", null, null, null, 0.9, null);
        String ttl = export(new PortableGraph(List.of(n), List.of()));
        assertTrue(ttl.contains("\"0.9\"^^xsd:double"), ttl);
    }
}
