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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HTML, SVG, Wiki, and Obsidian graph exporters —
 * output structure, content correctness, edge cases.
 */
class GraphExporterTest {

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableNode nodeWithDesc(String id, String title, String type, String desc) {
        return new PortableNode(id, title, desc, type, null);
    }

    private PortableNode nodeWithMeta(String id, String title, String type, Map<String, Object> meta) {
        return new PortableNode(id, title, null, type, meta);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, 0.8, null, null, null);
    }

    private PortableEdge edgeWithProv(String from, String to, String type, String prov) {
        return new PortableEdge(from, to, type, 0.5, null, prov, null);
    }

    private PortableGraph twoNodeGraph() {
        return new PortableGraph(
                List.of(node("e1", "Apple", "ORG"), node("e2", "Google", "ORG")),
                List.of(edge("e1", "e2", "COMPETES")));
    }

    private PortableGraph multiTypeGraph() {
        return new PortableGraph(
                List.of(
                        node("e1", "Apple", "ORG"),
                        node("e2", "Tim Cook", "PERSON"),
                        node("e3", "iPhone", "PRODUCT")),
                List.of(
                        edge("e2", "e1", "WORKS_AT"),
                        edgeWithProv("e1", "e3", "PRODUCES", "EXTRACTED")));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HTML Exporter
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class HtmlExporterTests {
        private final HtmlGraphExporter exporter = new HtmlGraphExporter();

        @Test
        void emptyGraph_producesValidHtml() {
            byte[] bytes = exporter.toBytes(PortableGraph.empty());
            String html = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(html.startsWith("<!DOCTYPE html>"));
            assertTrue(html.contains("</html>"));
            assertTrue(html.contains("0 nodes"));
            assertTrue(html.contains("0 edges"));
        }

        @Test
        void twoNodes_containsNodeData() {
            String html = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(html.contains("\"Apple\""));
            assertTrue(html.contains("\"Google\""));
            assertTrue(html.contains("\"COMPETES\""));
        }

        @Test
        void nodeStats_correct() {
            String html = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(html.contains("2 nodes"));
            assertTrue(html.contains("1 edges"));
        }

        @Test
        void nodeTypes_inLegend() {
            String html = new String(exporter.toBytes(multiTypeGraph()), StandardCharsets.UTF_8);
            assertTrue(html.contains("ORG"));
            assertTrue(html.contains("PERSON"));
            assertTrue(html.contains("PRODUCT"));
        }

        @Test
        void specialChars_escaped() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "Acme <Corp> & \"Partners\"", "ORG")),
                    List.of());
            String html = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            // HTML escaping in legend
            assertTrue(html.contains("&amp;") || html.contains("\\\""));
        }

        @Test
        void nullDescription_handledGracefully() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "Test", "ORG")),
                    List.of());
            assertDoesNotThrow(() -> exporter.toBytes(g));
        }

        @Test
        void nullNodeType_defaultsToUnknown() {
            PortableGraph g = new PortableGraph(
                    List.of(new PortableNode("e1", "Test", null, null, null)),
                    List.of());
            String html = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            assertTrue(html.contains("UNKNOWN"));
        }

        @Test
        void provenanceBadges_edgeClasses() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "A", "X"), node("e2", "B", "X")),
                    List.of(edgeWithProv("e1", "e2", "REL", "EXTRACTED")));
            String html = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            assertTrue(html.contains("\"EXTRACTED\""));
        }

        @Test
        void d3Script_embedded() {
            String html = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(html.contains("d3.v7.min.js"));
            assertTrue(html.contains("forceSimulation"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SVG Exporter
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class SvgExporterTests {
        private final SvgGraphExporter exporter = new SvgGraphExporter();

        @Test
        void emptyGraph_producesEmptySvg() {
            byte[] bytes = exporter.toBytes(PortableGraph.empty());
            String svg = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(svg.contains("<svg"));
            assertTrue(svg.contains("Empty graph"));
        }

        @Test
        void twoNodes_containsCircles() {
            String svg = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(svg.contains("<circle"));
            assertTrue(svg.contains("Apple"));
            assertTrue(svg.contains("Google"));
        }

        @Test
        void edges_drawnAsLines() {
            String svg = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(svg.contains("<line"));
            assertTrue(svg.contains("COMPETES"));
        }

        @Test
        void legend_containsNodeTypes() {
            String svg = new String(exporter.toBytes(multiTypeGraph()), StandardCharsets.UTF_8);
            assertTrue(svg.contains("Node Types"));
            assertTrue(svg.contains("ORG"));
            assertTrue(svg.contains("PERSON"));
            assertTrue(svg.contains("PRODUCT"));
        }

        @Test
        void provenanceStyles_applied() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "A", "X"), node("e2", "B", "X")),
                    List.of(edgeWithProv("e1", "e2", "R", "EXTRACTED")));
            String svg = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            assertTrue(svg.contains("#4caf50")); // EXTRACTED color
        }

        @Test
        void inferredEdge_hasDashedStroke() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "A", "X"), node("e2", "B", "X")),
                    List.of(edgeWithProv("e1", "e2", "R", "INFERRED")));
            String svg = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            assertTrue(svg.contains("stroke-dasharray"));
            assertTrue(svg.contains("#ff9800")); // INFERRED color
        }

        @Test
        void arrowMarker_defined() {
            String svg = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(svg.contains("<marker id=\"arrow\""));
            assertTrue(svg.contains("marker-end"));
        }

        @Test
        void nullNodeType_handledAsUnknown() {
            PortableGraph g = new PortableGraph(
                    List.of(new PortableNode("e1", "Test", null, null, null)),
                    List.of());
            String svg = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            assertTrue(svg.contains("UNKNOWN"));
        }

        @Test
        void longTitle_truncated() {
            PortableGraph g = new PortableGraph(
                    List.of(node("e1", "A very long entity name that exceeds twenty characters", "ORG")),
                    List.of());
            String svg = new String(exporter.toBytes(g), StandardCharsets.UTF_8);
            // Title should be truncated to ~20 chars with ellipsis
            assertTrue(svg.contains("…"));
        }

        @Test
        void xmlDeclaration_present() {
            String svg = new String(exporter.toBytes(twoNodeGraph()), StandardCharsets.UTF_8);
            assertTrue(svg.startsWith("<?xml version=\"1.0\""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Wiki Exporter
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class WikiExporterTests {
        private final WikiExporter exporter = new WikiExporter();

        @Test
        void emptyGraph_producesIndexOnly() throws Exception {
            byte[] zip = exporter.toZip(PortableGraph.empty());
            Map<String, String> entries = unzip(zip);
            assertTrue(entries.containsKey("index.md"));
            assertTrue(entries.get("index.md").contains("0 entities"));
        }

        @Test
        void twoNodes_indexLinksToEntities() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String index = entries.get("index.md");
            assertTrue(index.contains("Apple"));
            assertTrue(index.contains("Google"));
            assertTrue(index.contains("2 entities"));
        }

        @Test
        void entityPages_created() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            assertTrue(entries.containsKey("entities/Apple.md"));
            assertTrue(entries.containsKey("entities/Google.md"));
        }

        @Test
        void entityPage_containsRelationships() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String applePage = entries.get("entities/Apple.md");
            assertTrue(applePage.contains("Relates To") || applePage.contains("COMPETES"));
        }

        @Test
        void groupedByType_whenNoCommunity() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(multiTypeGraph()));
            assertTrue(entries.containsKey("articles/ORG.md"));
            assertTrue(entries.containsKey("articles/PERSON.md"));
            assertTrue(entries.containsKey("articles/PRODUCT.md"));
        }

        @Test
        void groupedByCommunity_whenMetadataPresent() throws Exception {
            PortableGraph g = new PortableGraph(
                    List.of(
                            nodeWithMeta("e1", "Apple", "ORG", Map.of("community", "tech")),
                            nodeWithMeta("e2", "Google", "ORG", Map.of("community", "tech")),
                            nodeWithMeta("e3", "Ford", "ORG", Map.of("community", "auto"))),
                    List.of());
            Map<String, String> entries = unzip(exporter.toZip(g));
            assertTrue(entries.containsKey("articles/Community-tech.md"));
            assertTrue(entries.containsKey("articles/Community-auto.md"));
        }

        @Test
        void articleTable_containsConnectionCounts() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String orgArticle = entries.get("articles/ORG.md");
            assertNotNull(orgArticle);
            assertTrue(orgArticle.contains("| Entity |"));
            assertTrue(orgArticle.contains("Apple"));
        }

        @Test
        void provenance_inEntityPage() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(multiTypeGraph()));
            String applePage = entries.get("entities/Apple.md");
            assertTrue(applePage.contains("EXTRACTED") || applePage.contains("PRODUCES"));
        }

        @Test
        void nodeWithDescription_shownInEntityPage() throws Exception {
            PortableGraph g = new PortableGraph(
                    List.of(nodeWithDesc("e1", "Apple", "ORG", "A tech company")),
                    List.of());
            Map<String, String> entries = unzip(exporter.toZip(g));
            assertTrue(entries.get("entities/Apple.md").contains("A tech company"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Obsidian Vault Exporter
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ObsidianExporterTests {
        private final ObsidianVaultExporter exporter = new ObsidianVaultExporter();

        @Test
        void emptyGraph_producesIndex() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
            assertTrue(entries.containsKey("index.md"));
            String index = entries.get("index.md");
            assertTrue(index.contains("Nodes:** 0"));
            assertTrue(index.contains("Edges:** 0"));
        }

        @Test
        void index_hasYamlFrontmatter() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(PortableGraph.empty()));
            String index = entries.get("index.md");
            assertTrue(index.startsWith("---\n"));
            assertTrue(index.contains("tags:"));
        }

        @Test
        void entityFiles_haveYamlFrontmatter() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String applePage = entries.get("entities/Apple.md");
            assertNotNull(applePage);
            assertTrue(applePage.startsWith("---\n"));
            assertTrue(applePage.contains("id: \"e1\""));
            assertTrue(applePage.contains("type: ORG"));
            assertTrue(applePage.contains("tags:"));
        }

        @Test
        void wikilinks_used() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String index = entries.get("index.md");
            assertTrue(index.contains("[[entities/"));
        }

        @Test
        void outgoingRelationships_listed() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String applePage = entries.get("entities/Apple.md");
            assertTrue(applePage.contains("Outgoing Relationships"));
            assertTrue(applePage.contains("COMPETES"));
            assertTrue(applePage.contains("Google"));
        }

        @Test
        void incomingRelationships_listed() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(twoNodeGraph()));
            String googlePage = entries.get("entities/Google.md");
            assertTrue(googlePage.contains("Incoming Relationships"));
            assertTrue(googlePage.contains("Apple"));
        }

        @Test
        void metadata_displayed() throws Exception {
            PortableGraph g = new PortableGraph(
                    List.of(nodeWithMeta("e1", "Apple", "ORG", Map.of("source", "wiki", "confidence", 0.95))),
                    List.of());
            Map<String, String> entries = unzip(exporter.toZip(g));
            String page = entries.get("entities/Apple.md");
            assertTrue(page.contains("Metadata"));
            assertTrue(page.contains("source"));
            assertTrue(page.contains("wiki"));
        }

        @Test
        void nodeDescription_inEntityPage() throws Exception {
            PortableGraph g = new PortableGraph(
                    List.of(nodeWithDesc("e1", "Apple", "ORG", "A multinational technology company")),
                    List.of());
            Map<String, String> entries = unzip(exporter.toZip(g));
            assertTrue(entries.get("entities/Apple.md").contains("A multinational technology company"));
        }

        @Test
        void groupedByType_inIndex() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(multiTypeGraph()));
            String index = entries.get("index.md");
            assertTrue(index.contains("## ORG"));
            assertTrue(index.contains("## PERSON"));
            assertTrue(index.contains("## PRODUCT"));
        }

        @Test
        void provenance_shownOnEdge() throws Exception {
            Map<String, String> entries = unzip(exporter.toZip(multiTypeGraph()));
            String applePage = entries.get("entities/Apple.md");
            assertTrue(applePage.contains("`EXTRACTED`"));
        }

        @Test
        void nullNodeType_handledAsUnknown() throws Exception {
            PortableGraph g = new PortableGraph(
                    List.of(new PortableNode("e1", "Test", null, null, null)),
                    List.of());
            Map<String, String> entries = unzip(exporter.toZip(g));
            String page = entries.get("entities/Test.md");
            assertTrue(page.contains("type: UNKNOWN"));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] content = zis.readAllBytes();
                entries.put(entry.getName(), new String(content, StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
