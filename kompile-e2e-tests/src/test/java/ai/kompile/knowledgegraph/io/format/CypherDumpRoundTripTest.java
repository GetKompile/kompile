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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CypherDumpRoundTripTest {

    @Test
    void roundTripsPortableGraph() {
        PortableGraph original = new PortableGraph(
                List.of(
                        new PortableNode("e1", "Alpha", "first node", "ENTITY", null),
                        new PortableNode("e2", "Beta", null, "PERSON", null)
                ),
                List.of(
                        new PortableEdge("e1", "e2", "RELATED_TO", 0.5, "weak link")
                ));
        byte[] dump = new CypherDumpExporter().toBytes(original);
        PortableGraph parsed = new CypherDumpImporter().parse(dump);
        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());
        assertEquals("e1", parsed.nodes().get(0).externalId());
        assertEquals("Alpha", parsed.nodes().get(0).title());
        assertEquals("PERSON", parsed.nodes().get(1).nodeType());
        PortableEdge e = parsed.edges().get(0);
        assertEquals("e1", e.fromExternalId());
        assertEquals("e2", e.toExternalId());
        assertEquals("RELATED_TO", e.edgeType());
        assertEquals(0.5, e.weight(), 1e-9);
    }

    @Test
    void exporterEmitsCreateAndMatchStatements() {
        PortableGraph g = new PortableGraph(
                List.of(new PortableNode("x", "X", null, "ENTITY", null),
                        new PortableNode("y", "Y", null, "ENTITY", null)),
                List.of(new PortableEdge("x", "y", "FOO", null, null)));
        String dump = new String(new CypherDumpExporter().toBytes(g));
        org.junit.jupiter.api.Assertions.assertTrue(dump.contains("CREATE (n:ENTITY"),
                "Expected CREATE statement, got:\n" + dump);
        org.junit.jupiter.api.Assertions.assertTrue(dump.contains("MATCH"),
                "Expected MATCH statement, got:\n" + dump);
        org.junit.jupiter.api.Assertions.assertTrue(dump.contains("[:FOO"),
                "Expected edge type label, got:\n" + dump);
    }
}
