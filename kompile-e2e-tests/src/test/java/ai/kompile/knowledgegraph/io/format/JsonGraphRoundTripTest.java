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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonGraphRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsNodesAndEdges() throws Exception {
        PortableGraph original = new PortableGraph(
                List.of(
                        new PortableNode("a", "Alpha", "first", "ENTITY", Map.of("k", "v")),
                        new PortableNode("b", "Beta", null, "DOCUMENT", null)
                ),
                List.of(
                        new PortableEdge("a", "b", "RELATED_TO", 0.5, "weak link")
                ));
        byte[] bytes = new JsonGraphExporter(mapper).toBytes(original);
        PortableGraph parsed = new JsonGraphImporter(mapper).parse(bytes);
        assertEquals(2, parsed.nodes().size());
        assertEquals(1, parsed.edges().size());
        assertEquals("Alpha", parsed.nodes().get(0).title());
        assertEquals("RELATED_TO", parsed.edges().get(0).edgeType());
        assertEquals(0.5, parsed.edges().get(0).weight(), 1e-9);
        assertNotNull(parsed.nodes().get(0).metadata());
        assertEquals("v", parsed.nodes().get(0).metadata().get("k"));
    }

    @Test
    void emptyGraphRoundTrips() throws Exception {
        byte[] bytes = new JsonGraphExporter(mapper).toBytes(PortableGraph.empty());
        PortableGraph parsed = new JsonGraphImporter(mapper).parse(bytes);
        assertEquals(0, parsed.nodes().size());
        assertEquals(0, parsed.edges().size());
    }
}
