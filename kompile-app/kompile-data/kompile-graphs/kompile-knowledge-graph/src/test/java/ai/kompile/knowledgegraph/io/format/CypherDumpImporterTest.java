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
 * Dedicated tests for {@link CypherDumpImporter}.
 * Complements the roundtrip tests in {@link CypherDumpRoundtripTest}.
 */
class CypherDumpImporterTest {

    private CypherDumpImporter importer;

    @BeforeEach
    void setUp() {
        importer = new CypherDumpImporter();
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ─── empty / blank input ──────────────────────────────────────────

    @Test
    void parse_emptyBytes_returnsEmptyGraph() {
        PortableGraph g = importer.parse(new byte[0]);
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void parse_onlyWhitespace_returnsEmptyGraph() {
        PortableGraph g = importer.parse(bytes("   \n\n  "));
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    // ─── node parsing ─────────────────────────────────────────────────

    @Test
    void parse_singleCreateNode() {
        String cypher = "CREATE (n:PERSON {externalId:'p1', title:'Alice', description:'An engineer'});";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(1, g.nodes().size());
        assertEquals("p1", g.nodes().get(0).externalId());
        assertEquals("Alice", g.nodes().get(0).title());
        assertEquals("An engineer", g.nodes().get(0).description());
        assertEquals("PERSON", g.nodes().get(0).nodeType());
    }

    @Test
    void parse_threeNodes() {
        String cypher = "CREATE (n:PERSON {externalId:'p1', title:'Alice'});\n"
                + "CREATE (n:PERSON {externalId:'p2', title:'Bob'});\n"
                + "CREATE (n:ORG {externalId:'o1', title:'Acme'});\n";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(3, g.nodes().size());
    }

    @Test
    void parse_nodeType_preservedAsUpperCase() {
        String cypher = "CREATE (n:Software_Engineer {externalId:'e1', title:'T'});";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals("SOFTWARE_ENGINEER", g.nodes().get(0).nodeType());
    }

    @Test
    void parse_nodeMissingExternalId_skipped() {
        String cypher = "CREATE (n:TYPE {title:'No ID'});\nCREATE (n:TYPE {externalId:'valid', title:'Valid'});";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(1, g.nodes().size());
        assertEquals("valid", g.nodes().get(0).externalId());
    }

    @Test
    void parse_nodeIdAlias_accepted() {
        // Some exports use nodeId instead of externalId
        String cypher = "CREATE (n:TYPE {nodeId:'alias1', title:'Aliased'});";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(1, g.nodes().size());
        assertEquals("alias1", g.nodes().get(0).externalId());
    }

    // ─── relationship parsing ─────────────────────────────────────────

    @Test
    void parse_singleRelationship() {
        String cypher = "MATCH (a {externalId:'p1'}), (b {externalId:'p2'}) "
                + "CREATE (a)-[:KNOWS {weight: 0.9}]->(b);";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(1, g.edges().size());
        PortableEdge e = g.edges().get(0);
        assertEquals("p1", e.fromExternalId());
        assertEquals("p2", e.toExternalId());
        assertEquals("KNOWS", e.edgeType());
        assertEquals(0.9, e.weight(), 0.001);
    }

    @Test
    void parse_relationshipWithDescription() {
        String cypher = "MATCH (a {externalId:'a'}), (b {externalId:'b'}) "
                + "CREATE (a)-[:WORKS_WITH {description:'partners'}]->(b);";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals("partners", g.edges().get(0).description());
    }

    @Test
    void parse_relationshipWithoutProperties() {
        String cypher = "MATCH (a {externalId:'a'}), (b {externalId:'b'}) "
                + "CREATE (a)-[:LINKED]->(b);";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(1, g.edges().size());
        assertEquals("LINKED", g.edges().get(0).edgeType());
        assertNull(g.edges().get(0).weight());
    }

    @Test
    void parse_edgeType_preservedAsUpperCase() {
        String cypher = "MATCH (a {externalId:'a'}), (b {externalId:'b'}) "
                + "CREATE (a)-[:RELATED_TO]->(b);";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals("RELATED_TO", g.edges().get(0).edgeType());
    }

    @Test
    void parse_threeRelationships() {
        // Note: edge type pattern [A-Z_]+ does not allow digits — use alphabetic-only labels
        String cypher = "MATCH (a {externalId:'a'}), (b {externalId:'b'}) CREATE (a)-[:KNOWS]->(b);\n"
                + "MATCH (b {externalId:'b'}), (c {externalId:'c'}) CREATE (b)-[:WORKS_WITH]->(c);\n"
                + "MATCH (a {externalId:'a'}), (c {externalId:'c'}) CREATE (a)-[:MANAGES]->(c);\n";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(3, g.edges().size());
    }

    // ─── mixed nodes and relationships ────────────────────────────────

    @Test
    void parse_nodesAndEdgesTogether() {
        String cypher =
                "CREATE (n:PERSON {externalId:'alice', title:'Alice'});\n"
                + "CREATE (n:PERSON {externalId:'bob', title:'Bob'});\n"
                + "MATCH (a {externalId:'alice'}), (b {externalId:'bob'}) "
                + "CREATE (a)-[:WORKS_WITH {weight: 1.0}]->(b);\n";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(2, g.nodes().size());
        assertEquals(1, g.edges().size());
    }

    // ─── unrecognized statements skipped ─────────────────────────────

    @Test
    void parse_unrecognizedStatements_returnsEmptyGraph() {
        String cypher = "RETURN 42;\nDROP TABLE test;\nSELECT * FROM nodes;\n";
        PortableGraph g = importer.parse(bytes(cypher));
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    // ─── numeric weight parsing ───────────────────────────────────────

    @Test
    void parse_integerWeight_parsedAsDouble() {
        String cypher = "MATCH (a {externalId:'a'}), (b {externalId:'b'}) "
                + "CREATE (a)-[:R {weight: 2}]->(b);";
        PortableGraph g = importer.parse(bytes(cypher));
        assertEquals(2.0, g.edges().get(0).weight(), 0.001);
    }
}
