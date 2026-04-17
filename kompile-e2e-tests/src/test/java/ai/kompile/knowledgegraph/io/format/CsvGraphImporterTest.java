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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvGraphImporterTest {

    @Test
    void parsesSimpleNodesAndEdges() throws Exception {
        String nodes = """
                externalId,title,description,nodeType
                n1,Alpha,first node,ENTITY
                n2,Beta,,ENTITY
                """;
        String edges = """
                fromExternalId,toExternalId,edgeType,weight,description
                n1,n2,RELATED_TO,0.7,linked
                """;
        PortableGraph graph = new CsvGraphImporter().parse(
                nodes.getBytes(StandardCharsets.UTF_8),
                edges.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        PortableNode n1 = graph.nodes().get(0);
        assertEquals("n1", n1.externalId());
        assertEquals("Alpha", n1.title());
        PortableEdge e = graph.edges().get(0);
        assertEquals("n1", e.fromExternalId());
        assertEquals("n2", e.toExternalId());
        assertEquals(0.7, e.weight(), 1e-9);
    }

    @Test
    void stripsNeo4jAdminTypeSuffixes() throws Exception {
        String nodes = "nodeId:ID,title,description,:LABEL\np1,Foo,desc,Person\n";
        String edges = ":START_ID,:END_ID,:TYPE,weight:double\np1,p2,KNOWS,1.5\n";
        PortableGraph graph = new CsvGraphImporter().parse(
                nodes.getBytes(StandardCharsets.UTF_8),
                edges.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, graph.nodes().size());
        assertEquals("p1", graph.nodes().get(0).externalId());
        assertEquals("Person", graph.nodes().get(0).nodeType());
        assertEquals(1, graph.edges().size());
        assertEquals("KNOWS", graph.edges().get(0).edgeType());
        assertEquals(1.5, graph.edges().get(0).weight(), 1e-9);
    }

    @Test
    void preservesUnknownColumnsAsMetadata() throws Exception {
        String nodes = "externalId,title,nodeType,department\nn1,Alpha,ENTITY,Engineering\n";
        PortableGraph graph = new CsvGraphImporter().parse(
                nodes.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, graph.nodes().size());
        PortableNode n1 = graph.nodes().get(0);
        assertTrue(n1.metadata() != null && n1.metadata().containsKey("department"));
        assertEquals("Engineering", n1.metadata().get("department"));
    }

    @Test
    void handlesQuotedFieldsWithCommas() throws Exception {
        String nodes = "externalId,title,description,nodeType\nn1,\"Hello, world\",\"a, b\",ENTITY\n";
        PortableGraph graph = new CsvGraphImporter().parse(
                nodes.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, graph.nodes().size());
        assertEquals("Hello, world", graph.nodes().get(0).title());
        assertEquals("a, b", graph.nodes().get(0).description());
    }

    @Test
    void emptyInputReturnsEmptyGraph() throws Exception {
        PortableGraph g = new CsvGraphImporter().parse(new byte[0], null);
        assertEquals(0, g.nodes().size());
        assertEquals(0, g.edges().size());
    }
}
