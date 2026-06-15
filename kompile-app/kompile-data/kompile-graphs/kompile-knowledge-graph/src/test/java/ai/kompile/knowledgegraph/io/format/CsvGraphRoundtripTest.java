/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.io.format;

import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CsvGraphExporter} and {@link CsvGraphImporter} —
 * CSV roundtrip, quoting, neo4j header format, metadata columns, empty inputs.
 */
class CsvGraphRoundtripTest {

    private final CsvGraphExporter exporter = new CsvGraphExporter();
    private final CsvGraphImporter importer = new CsvGraphImporter();

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableEdge edge(String from, String to, String type, Double weight) {
        return new PortableEdge(from, to, type, weight, null, null, null);
    }

    // ─── Exporter: nodesCsv ────────────────────────────────────────────

    @Test
    void nodesCsv_header() {
        PortableGraph graph = new PortableGraph(List.of(), List.of());
        String csv = exporter.nodesCsv(graph);
        assertTrue(csv.startsWith("externalId,title,description,nodeType\n"));
    }

    @Test
    void nodesCsv_singleNode() {
        PortableGraph graph = new PortableGraph(
                List.of(node("e1", "Apple", "ENTITY")),
                List.of());
        String csv = exporter.nodesCsv(graph);
        assertTrue(csv.contains("e1,Apple,,ENTITY"));
    }

    @Test
    void nodesCsv_quotesCommasInTitle() {
        PortableNode n = new PortableNode("e1", "Acme, Inc", "A description", "ORG", null);
        PortableGraph graph = new PortableGraph(List.of(n), List.of());
        String csv = exporter.nodesCsv(graph);
        assertTrue(csv.contains("\"Acme, Inc\""));
    }

    // ─── Exporter: edgesCsv ────────────────────────────────────────────

    @Test
    void edgesCsv_header() {
        PortableGraph graph = new PortableGraph(List.of(), List.of());
        String csv = exporter.edgesCsv(graph);
        assertTrue(csv.startsWith("fromExternalId,toExternalId,edgeType,weight,description\n"));
    }

    @Test
    void edgesCsv_singleEdge() {
        PortableGraph graph = new PortableGraph(List.of(),
                List.of(edge("e1", "e2", "RELATED", 0.5)));
        String csv = exporter.edgesCsv(graph);
        assertTrue(csv.contains("e1,e2,RELATED,0.5,"));
    }

    @Test
    void edgesCsv_nullWeight() {
        PortableGraph graph = new PortableGraph(List.of(),
                List.of(edge("e1", "e2", "RELATED", null)));
        String csv = exporter.edgesCsv(graph);
        assertTrue(csv.contains("e1,e2,RELATED,,"));
    }

    // ─── Exporter: toZip ───────────────────────────────────────────────

    @Test
    void toZip_containsBothEntries() throws Exception {
        PortableGraph graph = new PortableGraph(
                List.of(node("e1", "A", "ENTITY")),
                List.of(edge("e1", "e2", "R", 1.0)));

        byte[] zip = exporter.toZip(graph);
        assertNotNull(zip);
        assertTrue(zip.length > 0);

        boolean foundNodes = false, foundEdges = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("nodes.csv".equals(entry.getName())) foundNodes = true;
                if ("edges.csv".equals(entry.getName())) foundEdges = true;
            }
        }
        assertTrue(foundNodes, "ZIP should contain nodes.csv");
        assertTrue(foundEdges, "ZIP should contain edges.csv");
    }

    // ─── Importer: basic parsing ───────────────────────────────────────

    @Test
    void parse_basicNodesCsv() throws Exception {
        String csv = "externalId,title,description,nodeType\ne1,Apple,,ENTITY\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, graph.nodes().size());
        assertEquals("e1", graph.nodes().get(0).externalId());
        assertEquals("Apple", graph.nodes().get(0).title());
        assertEquals("ENTITY", graph.nodes().get(0).nodeType());
    }

    @Test
    void parse_basicEdgesCsv() throws Exception {
        String nodesCsv = "externalId,title,nodeType\ne1,A,ENTITY\ne2,B,ENTITY\n";
        String edgesCsv = "fromExternalId,toExternalId,edgeType,weight,description\ne1,e2,RELATED,0.8,\n";
        PortableGraph graph = importer.parse(
                nodesCsv.getBytes(StandardCharsets.UTF_8),
                edgesCsv.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        assertEquals("e1", graph.edges().get(0).fromExternalId());
        assertEquals(0.8, graph.edges().get(0).weight());
    }

    // ─── Importer: null/empty inputs ───────────────────────────────────

    @Test
    void parse_nullBoth_emptyGraph() throws Exception {
        PortableGraph graph = importer.parse(null, null);
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    @Test
    void parse_emptyBytes_emptyGraph() throws Exception {
        PortableGraph graph = importer.parse(new byte[0], new byte[0]);
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
    }

    // ─── Importer: neo4j-admin header format ───────────────────────────

    @Test
    void parse_neo4jAdminHeaders() throws Exception {
        String csv = ":ID,:LABEL\ne1,DOCUMENT\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, graph.nodes().size());
        assertEquals("e1", graph.nodes().get(0).externalId());
        assertEquals("DOCUMENT", graph.nodes().get(0).nodeType());
    }

    @Test
    void parse_neo4jAdminEdgeHeaders() throws Exception {
        String csv = ":START_ID,:END_ID,:TYPE,weight:double\ne1,e2,COMPETES,0.9\n";
        PortableGraph graph = importer.parse(null, csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, graph.edges().size());
        assertEquals("e1", graph.edges().get(0).fromExternalId());
        assertEquals("e2", graph.edges().get(0).toExternalId());
        assertEquals("COMPETES", graph.edges().get(0).edgeType());
        assertEquals(0.9, graph.edges().get(0).weight());
    }

    // ─── Importer: metadata columns ────────────────────────────────────

    @Test
    void parse_extraColumnsAsMetadata() throws Exception {
        String csv = "externalId,title,nodeType,source,confidence\ne1,Apple,ENTITY,wiki,0.95\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, graph.nodes().size());
        assertNotNull(graph.nodes().get(0).metadata());
        assertEquals("wiki", graph.nodes().get(0).metadata().get("source"));
        assertEquals("0.95", graph.nodes().get(0).metadata().get("confidence"));
    }

    // ─── Importer: skips blank externalId ──────────────────────────────

    @Test
    void parse_skipsBlankExternalId() throws Exception {
        String csv = "externalId,title,nodeType\n,NoId,ENTITY\ne1,Valid,ENTITY\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, graph.nodes().size());
        assertEquals("e1", graph.nodes().get(0).externalId());
    }

    // ─── Importer: quoted values ───────────────────────────────────────

    @Test
    void parse_quotedValues() throws Exception {
        String csv = "externalId,title,description,nodeType\ne1,\"Acme, Inc\",\"A \"\"big\"\" company\",ORG\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);

        assertEquals("Acme, Inc", graph.nodes().get(0).title());
        assertEquals("A \"big\" company", graph.nodes().get(0).description());
    }

    // ─── Roundtrip: export then import ─────────────────────────────────

    @Test
    void roundtrip_exportImport_preservesData() throws Exception {
        PortableNode n1 = new PortableNode("e1", "Apple", "Tech company", "ORG", null);
        PortableNode n2 = new PortableNode("e2", "Google", null, "ORG", null);
        PortableEdge edge = new PortableEdge("e1", "e2", "COMPETES", 0.7, "market", null, null);
        PortableGraph original = new PortableGraph(List.of(n1, n2), List.of(edge));

        String nodesCsv = exporter.nodesCsv(original);
        String edgesCsv = exporter.edgesCsv(original);

        PortableGraph imported = importer.parse(
                nodesCsv.getBytes(StandardCharsets.UTF_8),
                edgesCsv.getBytes(StandardCharsets.UTF_8));

        assertEquals(2, imported.nodes().size());
        assertEquals(1, imported.edges().size());
        assertEquals("e1", imported.nodes().get(0).externalId());
        assertEquals("Apple", imported.nodes().get(0).title());
        assertEquals("ORG", imported.nodes().get(0).nodeType());
        assertEquals("Tech company", imported.nodes().get(0).description());
        assertEquals("e1", imported.edges().get(0).fromExternalId());
        assertEquals(0.7, imported.edges().get(0).weight());
    }

    // ─── Importer: missing title defaults to externalId ────────────────

    @Test
    void parse_missingTitle_usesExternalId() throws Exception {
        String csv = "externalId,nodeType\ne1,ENTITY\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("e1", graph.nodes().get(0).title());
    }

    // ─── Importer: default nodeType ────────────────────────────────────

    @Test
    void parse_missingNodeType_defaultsToEntity() throws Exception {
        String csv = "externalId,title\ne1,Apple\n";
        PortableGraph graph = importer.parse(csv.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("ENTITY", graph.nodes().get(0).nodeType());
    }

    // ─── Importer: default edgeType ────────────────────────────────────

    @Test
    void parse_missingEdgeType_defaultsToUserDefined() throws Exception {
        String csv = "fromExternalId,toExternalId,weight\ne1,e2,0.5\n";
        PortableGraph graph = importer.parse(null, csv.getBytes(StandardCharsets.UTF_8));
        assertEquals("USER_DEFINED", graph.edges().get(0).edgeType());
    }
}
