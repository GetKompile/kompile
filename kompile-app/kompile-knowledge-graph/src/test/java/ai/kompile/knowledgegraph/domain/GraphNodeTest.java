/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GraphNode entity, covering NodeLevel and EdgeType enum values,
 * builder defaults, and helper methods.
 *
 * Notes on removed features:
 * - NodeLevel.ATTACHMENT was removed; TABLE is the structured-data node type.
 * - EdgeType.AUTHORED_BY, ADDRESSED_TO, EXTRACTED_FROM were removed.
 * - GraphNode composite/subGraphId fields were removed.
 * - Tests are updated to reflect the current 6-value NodeLevel and 8-value EdgeType.
 */
class GraphNodeTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE LEVEL ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void nodeLevelContainsAllExpectedValues() {
        NodeLevel[] values = NodeLevel.values();
        assertEquals(6, values.length);
        assertNotNull(NodeLevel.valueOf("SOURCE"));
        assertNotNull(NodeLevel.valueOf("DOCUMENT"));
        assertNotNull(NodeLevel.valueOf("SNIPPET"));
        assertNotNull(NodeLevel.valueOf("ENTITY"));
        assertNotNull(NodeLevel.valueOf("TABLE"));
        assertNotNull(NodeLevel.valueOf("CUSTOM"));
    }

    @Test
    void nodeLevelTableExists() {
        assertEquals(NodeLevel.TABLE, NodeLevel.valueOf("TABLE"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE TYPE ENUM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void edgeTypeContainsAllExpectedValues() {
        EdgeType[] values = EdgeType.values();
        assertEquals(8, values.length);
        assertNotNull(EdgeType.valueOf("HIERARCHICAL"));
        assertNotNull(EdgeType.valueOf("CONTAINS"));
        assertNotNull(EdgeType.valueOf("EMBEDDING_SIMILARITY"));
        assertNotNull(EdgeType.valueOf("SHARED_ENTITY"));
        assertNotNull(EdgeType.valueOf("USER_DEFINED"));
        assertNotNull(EdgeType.valueOf("CITATION"));
        assertNotNull(EdgeType.valueOf("TEMPORAL"));
        assertNotNull(EdgeType.valueOf("CROSS_SOURCE"));
    }

    @Test
    void edgeTypeContainsAndUserDefinedExist() {
        assertEquals(EdgeType.CONTAINS, EdgeType.valueOf("CONTAINS"));
        assertEquals(EdgeType.USER_DEFINED, EdgeType.valueOf("USER_DEFINED"));
        assertEquals(EdgeType.CROSS_SOURCE, EdgeType.valueOf("CROSS_SOURCE"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH NODE BUILDER DEFAULTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void builderDefaultsChildCountToZero() {
        GraphNode node = GraphNode.builder()
            .externalId("test-ext")
            .nodeType(NodeLevel.ENTITY)
            .title("Test Entity")
            .build();

        assertEquals(0, node.getChildCount());
    }

    @Test
    void builderDefaultsEdgeCountToZero() {
        GraphNode node = GraphNode.builder()
            .externalId("test-ext")
            .nodeType(NodeLevel.ENTITY)
            .title("Test Entity")
            .build();

        assertEquals(0, node.getEdgeCount());
    }

    @Test
    void builderSetsConfidence() {
        GraphNode node = GraphNode.builder()
            .externalId("comp-1")
            .nodeType(NodeLevel.ENTITY)
            .title("Organization")
            .confidence(0.95)
            .build();

        assertEquals(0.95, node.getConfidence());
    }

    @Test
    void confidenceCanBeNull() {
        GraphNode node = GraphNode.builder()
            .externalId("no-conf")
            .nodeType(NodeLevel.SOURCE)
            .title("Source Node")
            .build();

        assertNull(node.getConfidence());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIDENCE RANGE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void confidenceAcceptsBoundaryValues() {
        GraphNode low = GraphNode.builder()
            .externalId("low-conf")
            .nodeType(NodeLevel.ENTITY)
            .title("Low Confidence")
            .confidence(0.0)
            .build();
        assertEquals(0.0, low.getConfidence());

        GraphNode high = GraphNode.builder()
            .externalId("high-conf")
            .nodeType(NodeLevel.ENTITY)
            .title("High Confidence")
            .confidence(1.0)
            .build();
        assertEquals(1.0, high.getConfidence());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHILD/EDGE COUNT HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void incrementChildCount() {
        GraphNode node = GraphNode.builder()
            .externalId("parent")
            .nodeType(NodeLevel.SOURCE)
            .title("Parent")
            .build();

        assertEquals(0, node.getChildCount());
        node.incrementChildCount();
        assertEquals(1, node.getChildCount());
        node.incrementChildCount();
        assertEquals(2, node.getChildCount());
    }

    @Test
    void decrementChildCountStopsAtZero() {
        GraphNode node = GraphNode.builder()
            .externalId("parent")
            .nodeType(NodeLevel.SOURCE)
            .title("Parent")
            .build();

        node.decrementChildCount();
        assertEquals(0, node.getChildCount());
    }

    @Test
    void incrementEdgeCount() {
        GraphNode node = GraphNode.builder()
            .externalId("node")
            .nodeType(NodeLevel.ENTITY)
            .title("Node")
            .build();

        node.incrementEdgeCount();
        node.incrementEdgeCount();
        assertEquals(2, node.getEdgeCount());
    }

    @Test
    void decrementEdgeCountStopsAtZero() {
        GraphNode node = GraphNode.builder()
            .externalId("node")
            .nodeType(NodeLevel.ENTITY)
            .title("Node")
            .build();

        node.decrementEdgeCount();
        assertEquals(0, node.getEdgeCount());
    }

    @Test
    void incrementFromNullChildCount() {
        GraphNode node = GraphNode.builder()
            .externalId("null-count")
            .nodeType(NodeLevel.ENTITY)
            .title("Null Count")
            .childCount(null)
            .build();

        node.incrementChildCount();
        assertEquals(1, node.getChildCount());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TABLE NODES (TABLE replaces ATTACHMENT as structured-data node type)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void buildTableNode() {
        GraphNode document = GraphNode.builder()
            .externalId("doc-1")
            .nodeType(NodeLevel.DOCUMENT)
            .title("quarterly_report.xlsx")
            .build();

        GraphNode table = GraphNode.builder()
            .externalId("tab-sheet1")
            .nodeType(NodeLevel.TABLE)
            .title("Sheet 1 - Revenue")
            .parent(document)
            .contentPreview("Quarter,Revenue\nQ1,1000000\nQ2,1200000")
            .build();

        assertEquals(NodeLevel.TABLE, table.getNodeType());
        assertEquals("Sheet 1 - Revenue", table.getTitle());
        assertNotNull(table.getContentPreview());
        assertEquals(NodeLevel.DOCUMENT, table.getParent().getNodeType());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT HIERARCHY SCENARIO
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void documentHierarchyScenario() {
        // source -> document -> table (structured data hierarchy)
        GraphNode source = GraphNode.builder()
            .externalId("source-1")
            .nodeType(NodeLevel.SOURCE)
            .title("Data Source")
            .sourceType("FILE")
            .build();

        GraphNode document = GraphNode.builder()
            .externalId("doc-1")
            .nodeType(NodeLevel.DOCUMENT)
            .title("Q4 Financial Report")
            .parent(source)
            .sourceNode(source)
            .build();

        GraphNode table = GraphNode.builder()
            .externalId("sheet-1")
            .nodeType(NodeLevel.TABLE)
            .title("Revenue by Region")
            .parent(document)
            .sourceNode(source)
            .build();

        // Verify the hierarchy chain
        assertEquals(NodeLevel.SOURCE, source.getNodeType());
        assertEquals(NodeLevel.DOCUMENT, document.getNodeType());
        assertEquals(NodeLevel.TABLE, table.getNodeType());

        // Verify parent chain
        assertNull(source.getParent());
        assertEquals(source, document.getParent());
        assertEquals(document, table.getParent());

        // All trace back to the same source
        assertEquals(source, document.getSourceNode());
        assertEquals(source, table.getSourceNode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRE-PERSIST BEHAVIOR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void onCreateSetsNodeIdAndTimestamps() {
        GraphNode node = GraphNode.builder()
            .externalId("persist-test")
            .nodeType(NodeLevel.ENTITY)
            .title("Persist Test")
            .build();

        // Simulate @PrePersist
        node.onCreate();

        assertNotNull(node.getNodeId());
        assertNotNull(node.getCreatedAt());
        assertNotNull(node.getUpdatedAt());
    }

    @Test
    void onCreatePreservesExistingNodeId() {
        GraphNode node = GraphNode.builder()
            .externalId("persist-test")
            .nodeType(NodeLevel.ENTITY)
            .title("Persist Test")
            .nodeId("my-custom-uuid")
            .build();

        node.onCreate();

        assertEquals("my-custom-uuid", node.getNodeId());
    }
}
