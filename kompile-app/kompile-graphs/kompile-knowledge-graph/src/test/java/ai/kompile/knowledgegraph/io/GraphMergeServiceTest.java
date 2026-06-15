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

package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.io.format.PortableGraph;
import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphMergeService} — multi-graph deduplication by
 * external ID, fuzzy title match, edge remapping, and metadata merge.
 */
class GraphMergeServiceTest {

    private GraphMergeService service;

    @BeforeEach
    void setUp() {
        service = new GraphMergeService();
    }

    private PortableNode node(String id, String title, String type) {
        return new PortableNode(id, title, null, type, null);
    }

    private PortableNode nodeWithDesc(String id, String title, String type, String description) {
        return new PortableNode(id, title, description, type, null);
    }

    private PortableNode nodeWithMeta(String id, String title, String type, Map<String, Object> meta) {
        return new PortableNode(id, title, null, type, meta);
    }

    private PortableEdge edge(String from, String to, String type) {
        return new PortableEdge(from, to, type, null, null, null, null);
    }

    // ─── Null/Empty handling ────────────────────────────────────────────

    @Test
    void merge_nullInput() {
        GraphMergeService.MergeResult result = service.merge(null);
        assertNotNull(result.merged());
        assertEquals(0, result.mergedNodes());
        assertEquals(0, result.deduplicatedNodes());
    }

    @Test
    void merge_emptyList() {
        GraphMergeService.MergeResult result = service.merge(List.of());
        assertNotNull(result.merged());
        assertEquals(0, result.mergedNodes());
    }

    // ─── Single graph passthrough ───────────────────────────────────────

    @Test
    void merge_singleGraph() {
        PortableGraph g = new PortableGraph(
                List.of(node("n1", "A", "ENTITY"), node("n2", "B", "ENTITY")),
                List.of(edge("n1", "n2", "RELATED")));

        GraphMergeService.MergeResult result = service.merge(List.of(g));
        assertEquals(2, result.mergedNodes());
        assertEquals(1, result.mergedEdges());
        assertEquals(0, result.deduplicatedNodes());
    }

    // ─── Exact ID deduplication ─────────────────────────────────────────

    @Test
    void merge_deduplicatesByExternalId() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(node("n1", "Apple Inc", "ORG")),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(1, result.mergedNodes());
        assertEquals(1, result.deduplicatedNodes());
        assertEquals(2, result.totalSourceNodes());
    }

    // ─── Fuzzy title deduplication ──────────────────────────────────────

    @Test
    void merge_fuzzyTitleMatchSameType() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("id-1", "Microsoft Corporation", "ORG")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(node("id-2", "Microsoft Corporatin", "ORG")), // typo
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(1, result.mergedNodes());
    }

    @Test
    void merge_noFuzzyMatchDifferentTypes() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("id-1", "Python", "LANGUAGE")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(node("id-2", "Python", "ANIMAL")),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(2, result.mergedNodes());
    }

    @Test
    void merge_noFuzzyMatchDissimilarTitles() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("id-1", "Apple", "ORG")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(node("id-2", "Google", "ORG")),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(2, result.mergedNodes());
        assertEquals(0, result.deduplicatedNodes());
    }

    // ─── Edge remapping ─────────────────────────────────────────────────

    @Test
    void merge_remapsEdgesAfterDedup() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG"), node("n2", "Tim Cook", "PERSON")),
                List.of(edge("n2", "n1", "CEO_OF")));
        PortableGraph g2 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG")), // same ID
                List.of(edge("n1", "n2", "EMPLOYS")));

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(2, result.mergedNodes()); // Apple + Tim Cook
        assertEquals(2, result.mergedEdges()); // CEO_OF + EMPLOYS
    }

    @Test
    void merge_deduplicatesEdgesAfterRemapping() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG"), node("n2", "Google", "ORG")),
                List.of(edge("n1", "n2", "COMPETES")));
        PortableGraph g2 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG"), node("n2", "Google", "ORG")),
                List.of(edge("n1", "n2", "COMPETES"))); // duplicate edge

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(1, result.mergedEdges()); // deduplicated
    }

    // ─── Description merging ────────────────────────────────────────────

    @Test
    void merge_keepsLongerDescription() {
        PortableGraph g1 = new PortableGraph(
                List.of(nodeWithDesc("n1", "Apple", "ORG", "Short")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(nodeWithDesc("n1", "Apple", "ORG", "A much longer description of Apple Inc")),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        assertEquals(1, result.mergedNodes());
        PortableNode merged = result.merged().nodes().get(0);
        assertTrue(merged.description().length() > 10);
    }

    // ─── Metadata merging ───────────────────────────────────────────────

    @Test
    void merge_combinesMetadata() {
        PortableGraph g1 = new PortableGraph(
                List.of(nodeWithMeta("n1", "Apple", "ORG", Map.of("source", "wiki"))),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(nodeWithMeta("n1", "Apple", "ORG", Map.of("revenue", "365B"))),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        PortableNode merged = result.merged().nodes().get(0);
        assertNotNull(merged.metadata());
        assertEquals("wiki", merged.metadata().get("source"));
        assertEquals("365B", merged.metadata().get("revenue"));
    }

    // ─── Three-way merge ────────────────────────────────────────────────

    @Test
    void merge_threeGraphs() {
        PortableGraph g1 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG")),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(node("n2", "Google", "ORG")),
                List.of());
        PortableGraph g3 = new PortableGraph(
                List.of(node("n1", "Apple", "ORG"), node("n3", "Amazon", "ORG")),
                List.of(edge("n1", "n3", "COMPETES")));

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2, g3));
        assertEquals(3, result.mergedNodes()); // Apple, Google, Amazon
        assertEquals(1, result.mergedEdges());
        assertEquals(4, result.totalSourceNodes());
    }

    // ─── Null title handling ────────────────────────────────────────────

    @Test
    void merge_nullTitleSkipsFuzzyMatch() {
        PortableGraph g1 = new PortableGraph(
                List.of(new PortableNode("id-1", null, null, "ORG", null)),
                List.of());
        PortableGraph g2 = new PortableGraph(
                List.of(new PortableNode("id-2", null, null, "ORG", null)),
                List.of());

        GraphMergeService.MergeResult result = service.merge(List.of(g1, g2));
        // Different IDs + null titles → no match
        assertEquals(2, result.mergedNodes());
    }
}
