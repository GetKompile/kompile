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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated tests for {@link CsvGraphImporter}.
 * Complements the roundtrip tests in {@link CsvGraphRoundtripTest}.
 */
class CsvGraphImporterTest {

    private CsvGraphImporter importer;

    @BeforeEach
    void setUp() {
        importer = new CsvGraphImporter();
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ─── basic parsing ────────────────────────────────────────────────

    @Test
    void parse_threeNodes() throws Exception {
        String csv = "externalId,title,nodeType\nn1,Alice,PERSON\nn2,Bob,PERSON\nn3,Acme,ORG\n";
        PortableGraph g = importer.parse(bytes(csv), null);
        assertEquals(3, g.nodes().size());
    }

    @Test
    void parse_threeEdges() throws Exception {
        String edges = "fromExternalId,toExternalId,edgeType,weight,description\n"
                + "n1,n2,KNOWS,0.9,\nn2,n3,WORKS_AT,0.8,employed\nn1,n3,MANAGES,,\n";
        PortableGraph g = importer.parse(null, bytes(edges));
        assertEquals(3, g.edges().size());
    }

    @Test
    void parse_edgeWithDescription() throws Exception {
        String edges = "fromExternalId,toExternalId,edgeType,weight,description\n"
                + "a,b,MANAGES,1.0,manages team\n";
        PortableGraph g = importer.parse(null, bytes(edges));
        assertEquals("manages team", g.edges().get(0).description());
    }

    // ─── null / empty inputs ──────────────────────────────────────────

    @Test
    void parse_nullNodes_emptyNodeList() throws Exception {
        String edges = "fromExternalId,toExternalId,edgeType,weight,description\na,b,REL,,\n";
        PortableGraph g = importer.parse(null, bytes(edges));
        assertTrue(g.nodes().isEmpty());
        assertEquals(1, g.edges().size());
    }

    @Test
    void parse_nullEdges_emptyEdgeList() throws Exception {
        String nodes = "externalId,title,nodeType\nn1,Title,TYPE\n";
        PortableGraph g = importer.parse(bytes(nodes), null);
        assertEquals(1, g.nodes().size());
        assertTrue(g.edges().isEmpty());
    }

    // ─── weight parsing ───────────────────────────────────────────────

    @Test
    void parse_edgeWeightParsed() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,b,R,3.14,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertEquals(3.14, g.edges().get(0).weight(), 0.001);
    }

    @Test
    void parse_invalidWeight_resultIsNull() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,b,R,notANumber,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertNull(g.edges().get(0).weight());
    }

    @Test
    void parse_blankWeight_resultIsNull() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,b,R,,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertNull(g.edges().get(0).weight());
    }

    // ─── provenance and confidence are null (CSV format) ─────────────

    @Test
    void parse_edgeProvenanceIsNull() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,b,R,,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertNull(g.edges().get(0).provenance());
        assertNull(g.edges().get(0).confidence());
    }

    // ─── blank-line skipping ──────────────────────────────────────────

    @Test
    void parse_blankLinesInNodesCsv_skipped() throws Exception {
        String csv = "externalId,title,nodeType\nn1,A,T\n\nn2,B,T\n\n";
        PortableGraph g = importer.parse(bytes(csv), null);
        assertEquals(2, g.nodes().size());
    }

    @Test
    void parse_blankLinesInEdgesCsv_skipped() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,b,R,,\n\nc,d,R,,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertEquals(2, g.edges().size());
    }

    // ─── skip edge without from or to ────────────────────────────────

    @Test
    void parse_blankFrom_edgeSkipped() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\n,b,R,,\na,b,R,,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertEquals(1, g.edges().size());
    }

    @Test
    void parse_blankTo_edgeSkipped() throws Exception {
        String csv = "fromExternalId,toExternalId,edgeType,weight,description\na,,R,,\na,b,R,,\n";
        PortableGraph g = importer.parse(null, bytes(csv));
        assertEquals(1, g.edges().size());
    }

    // ─── node metadata ────────────────────────────────────────────────

    @Test
    void parse_extraColumnsAsMetadata_multipleColumns() throws Exception {
        String csv = "externalId,title,nodeType,source,score,category\nn1,A,T,wiki,0.9,tech\n";
        PortableGraph g = importer.parse(bytes(csv), null);
        assertNotNull(g.nodes().get(0).metadata());
        assertEquals("wiki", g.nodes().get(0).metadata().get("source"));
        assertEquals("0.9", g.nodes().get(0).metadata().get("score"));
        assertEquals("tech", g.nodes().get(0).metadata().get("category"));
    }

    @Test
    void parse_noExtraColumns_metadataIsNull() throws Exception {
        String csv = "externalId,title,nodeType\nn1,A,T\n";
        PortableGraph g = importer.parse(bytes(csv), null);
        assertNull(g.nodes().get(0).metadata());
    }
}
