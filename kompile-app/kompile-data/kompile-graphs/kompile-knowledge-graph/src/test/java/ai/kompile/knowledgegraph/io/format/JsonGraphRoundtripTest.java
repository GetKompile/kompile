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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip fidelity tests for the canonical JSON portability format
 * ({@link JsonGraphExporter} ⇄ {@link JsonGraphImporter}).
 *
 * <p>This is the on-disk format used to bake a knowledge graph into a versioned
 * project so it survives a {@code git clone}. The serialization must therefore be
 * <em>lossless</em> for the scoping/quality fields ({@code factSheetId},
 * {@code namedGraphId}, {@code confidence}, {@code occurredAt}, node metadata,
 * edge provenance) — historically these were dropped by the exporter projection.</p>
 */
class JsonGraphRoundtripTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonGraphExporter exporter = new JsonGraphExporter(mapper);
    private final JsonGraphImporter importer = new JsonGraphImporter(mapper);

    @Test
    void enrichedNode_roundtripsLosslessly() throws Exception {
        PortableNode node = new PortableNode(
                "ext-1", "Acme Corp", "A company", "ENTITY",
                Map.of("industry", "retail", "barcode", "0012345678905"),
                42L, "graph-uuid-1", 0.87, "2026-01-15T10:30");

        PortableGraph parsed = importer.parse(exporter.toBytes(new PortableGraph(List.of(node), List.of())));

        assertEquals(1, parsed.nodes().size());
        PortableNode r = parsed.nodes().get(0);
        assertEquals("ext-1", r.externalId());
        assertEquals("Acme Corp", r.title());
        assertEquals("A company", r.description());
        assertEquals("ENTITY", r.nodeType());
        assertEquals(Long.valueOf(42), r.factSheetId());
        assertEquals("graph-uuid-1", r.namedGraphId());
        assertEquals(Double.valueOf(0.87), r.confidence());
        assertEquals("2026-01-15T10:30", r.occurredAt());
        assertNotNull(r.metadata());
        assertEquals("retail", r.metadata().get("industry"));
        assertEquals("0012345678905", r.metadata().get("barcode"));
    }

    @Test
    void enrichedEdge_roundtripsLosslessly() throws Exception {
        PortableEdge edge = new PortableEdge(
                "a", "b", "RESOLVES_TO", 0.9, "barcode match",
                "EXTRACTED", 0.95, "2026-02-01T00:00");
        PortableGraph original = new PortableGraph(
                List.of(new PortableNode("a", "A", null, "ENTITY", null),
                        new PortableNode("b", "B", null, "IDENTIFIER", null)),
                List.of(edge));

        PortableGraph parsed = importer.parse(exporter.toBytes(original));

        assertEquals(1, parsed.edges().size());
        PortableEdge r = parsed.edges().get(0);
        assertEquals("a", r.fromExternalId());
        assertEquals("b", r.toExternalId());
        assertEquals("RESOLVES_TO", r.edgeType());
        assertEquals(Double.valueOf(0.9), r.weight());
        assertEquals("barcode match", r.description());
        assertEquals("EXTRACTED", r.provenance());
        assertEquals(Double.valueOf(0.95), r.confidence());
        assertEquals("2026-02-01T00:00", r.occurredAt());
    }

    @Test
    void legacyCoreOnlyNode_stillRoundtrips() throws Exception {
        // Interop formats construct PortableNode via the 5-arg constructor; the
        // extended fields must serialize as absent (NON_NULL) and parse back as null.
        PortableNode node = new PortableNode("ext-1", "Title", "Desc", "ENTITY", null);

        PortableGraph parsed = importer.parse(exporter.toBytes(new PortableGraph(List.of(node), List.of())));

        PortableNode r = parsed.nodes().get(0);
        assertEquals("ext-1", r.externalId());
        assertNull(r.factSheetId());
        assertNull(r.namedGraphId());
        assertNull(r.confidence());
        assertNull(r.occurredAt());
    }
}
