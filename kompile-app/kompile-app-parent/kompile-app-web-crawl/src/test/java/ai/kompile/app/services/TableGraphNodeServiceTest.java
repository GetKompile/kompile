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
package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableGraphNodeServiceTest {

    private KnowledgeGraphService knowledgeGraphService;
    private TableGraphNodeService service;

    @BeforeEach
    void setUp() {
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        service = new TableGraphNodeService(knowledgeGraphService);
    }

    // --- promoteTableNodes ---

    @Test
    void promoteTableNodesReturnsZeroForNullList() {
        assertEquals(0, service.promoteTableNodes(null, "task-1"));
    }

    @Test
    void promoteTableNodesReturnsZeroForEmptyList() {
        assertEquals(0, service.promoteTableNodes(List.of(), "task-1"));
    }

    @Test
    void promoteTableNodesSkipsNonTableDocuments() {
        Document textDoc = new Document("content", Map.of("content_type", "text"));
        Document imageDoc = new Document("content", Map.of("content_type", "image"));

        assertEquals(0, service.promoteTableNodes(List.of(textDoc, imageDoc), "task-1"));
        verify(knowledgeGraphService, never()).createTableNode(any(), any(), any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void promoteTableNodesCreatesNodeForTableDocument() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_path", "/docs/report.xlsx");
        meta.put("sheetName", "Q1 Data");
        meta.put("table_row_count", 50);
        meta.put("table_column_count", 8);
        meta.put("table_headers", "Name,Amount,Date");
        meta.put("full_table_content", "| Name | Amount | Date |\n| --- | --- | --- |");
        Document tableDoc = new Document("Table content", meta);

        GraphNode parentNode = mockGraphNode("parent-id", "report.xlsx", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNodeByExternalId("/docs/report.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(parentNode));
        GraphNode tableNode = mockGraphNode("table-id", "Q1 Data", NodeLevel.TABLE);
        when(knowledgeGraphService.createTableNode(eq("parent-id"), anyString(), eq("Q1 Data"),
                eq(50), eq(8), anyList(), anyString(), anyMap()))
                .thenReturn(tableNode);

        int created = service.promoteTableNodes(List.of(tableDoc), "task-1");

        assertEquals(1, created);
        verify(knowledgeGraphService).createTableNode(
                eq("parent-id"), contains("table:"), eq("Q1 Data"),
                eq(50), eq(8), anyList(), anyString(), anyMap());
    }

    @Test
    void promoteTableNodesUsesStructuralSectionWhenNoSheetName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_path", "/docs/page.html");
        meta.put("structural_section", "Revenue Summary");
        meta.put("table_row_count", 10);
        meta.put("table_column_count", 3);
        Document tableDoc = new Document("Table content", meta);

        GraphNode parentNode = mockGraphNode("parent-id", "page.html", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNodeByExternalId("/docs/page.html", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(parentNode));
        GraphNode tableNode = mockGraphNode("table-id", "Revenue Summary", NodeLevel.TABLE);
        when(knowledgeGraphService.createTableNode(eq("parent-id"), contains("Revenue Summary"),
                eq("Revenue Summary"), anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(tableNode);

        int created = service.promoteTableNodes(List.of(tableDoc), "task-1");

        assertEquals(1, created);
    }

    @Test
    void promoteTableNodesSkipsWhenNoParentDocumentNode() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_path", "/unknown/path.xlsx");
        meta.put("sheetName", "Sheet1");
        Document tableDoc = new Document("Table content", meta);

        when(knowledgeGraphService.getNodeByExternalId("/unknown/path.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of());

        int created = service.promoteTableNodes(List.of(tableDoc), "task-1");

        assertEquals(0, created);
        verify(knowledgeGraphService, never()).createTableNode(any(), any(), any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void promoteTableNodesFallsBackToFileNameSearch() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_filename", "report.xlsx");
        meta.put("sheetName", "Data");
        Document tableDoc = new Document("Table content", meta);

        // source_path is null, so fallback to fileName search
        GraphNode parentNode = mockGraphNode("parent-id", "report.xlsx", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(parentNode));
        GraphNode tableNode = mockGraphNode("table-id", "Data", NodeLevel.TABLE);
        when(knowledgeGraphService.createTableNode(eq("parent-id"), anyString(), eq("Data"),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(tableNode);

        int created = service.promoteTableNodes(List.of(tableDoc), "task-1");

        assertEquals(1, created);
    }

    @Test
    void promoteTableNodesHandlesMultipleTables() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("content_type", "table");
            meta.put("source_path", "/docs/data.xlsx");
            meta.put("sheetName", "Sheet" + i);
            docs.add(new Document("Content " + i, meta));
        }
        // Also add a non-table doc
        docs.add(new Document("Text", Map.of("content_type", "text")));

        GraphNode parentNode = mockGraphNode("parent-id", "data.xlsx", NodeLevel.DOCUMENT);
        GraphNode tableNode = mockGraphNode("tbl-id", "Sheet", NodeLevel.TABLE);
        when(knowledgeGraphService.getNodeByExternalId("/docs/data.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(parentNode));
        when(knowledgeGraphService.createTableNode(eq("parent-id"), anyString(), anyString(),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(tableNode);

        int created = service.promoteTableNodes(docs, "task-1");

        assertEquals(3, created);
        verify(knowledgeGraphService, times(3)).createTableNode(any(), any(), any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void promoteTableNodesCatchesExceptionAndContinues() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("content_type", "table");
        meta1.put("source_path", "/docs/bad.xlsx");
        meta1.put("sheetName", "BadSheet");
        Document badDoc = new Document("Bad", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("content_type", "table");
        meta2.put("source_path", "/docs/good.xlsx");
        meta2.put("sheetName", "GoodSheet");
        Document goodDoc = new Document("Good", meta2);

        GraphNode badParent = mockGraphNode("bad-parent", "bad.xlsx", NodeLevel.DOCUMENT);
        GraphNode goodParent = mockGraphNode("good-parent", "good.xlsx", NodeLevel.DOCUMENT);
        GraphNode goodTable = mockGraphNode("tbl-id", "GoodSheet", NodeLevel.TABLE);

        when(knowledgeGraphService.getNodeByExternalId("/docs/bad.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(badParent));
        when(knowledgeGraphService.createTableNode(eq("bad-parent"), anyString(), eq("BadSheet"),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenThrow(new RuntimeException("DB error"));

        when(knowledgeGraphService.getNodeByExternalId("/docs/good.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(goodParent));
        when(knowledgeGraphService.createTableNode(eq("good-parent"), anyString(), eq("GoodSheet"),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(goodTable);

        int created = service.promoteTableNodes(List.of(badDoc, goodDoc), "task-1");

        assertEquals(1, created);
    }

    @Test
    void promoteTableNodesIncludesMetadataProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_path", "/docs/data.xlsx");
        meta.put("sheetName", "Sheet1");
        meta.put("table_index", 2);
        meta.put("dq_flag_count", 5);
        meta.put("formulaCount", 12);
        Document tableDoc = new Document("Table content", meta);

        GraphNode parentNode = mockGraphNode("parent-id", "data.xlsx", NodeLevel.DOCUMENT);
        GraphNode tableNode = mockGraphNode("tbl-id", "Sheet1", NodeLevel.TABLE);
        when(knowledgeGraphService.getNodeByExternalId("/docs/data.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(parentNode));
        when(knowledgeGraphService.createTableNode(eq("parent-id"), anyString(), anyString(),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(tableNode);

        service.promoteTableNodes(List.of(tableDoc), "task-1");

        verify(knowledgeGraphService).createTableNode(
                any(), any(), any(), anyInt(), anyInt(), any(), any(),
                argThat(tableMeta -> {
                    assertEquals(2, tableMeta.get("tableIndex"));
                    assertEquals(5, tableMeta.get("dqFlagCount"));
                    assertEquals(12, tableMeta.get("formulaCount"));
                    return true;
                }));
    }

    @Test
    void promoteTableNodesTruncatesLongContent() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("Row ").append(i).append(": some data for this row | col2 | col3\n");
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source_path", "/docs/big.xlsx");
        meta.put("sheetName", "BigSheet");
        meta.put("full_table_content", longContent.toString());
        Document tableDoc = new Document("Table content", meta);

        GraphNode parentNode = mockGraphNode("parent-id", "big.xlsx", NodeLevel.DOCUMENT);
        GraphNode tableNode = mockGraphNode("tbl-id", "BigSheet", NodeLevel.TABLE);
        when(knowledgeGraphService.getNodeByExternalId("/docs/big.xlsx", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(parentNode));
        when(knowledgeGraphService.createTableNode(eq("parent-id"), anyString(), anyString(),
                anyInt(), anyInt(), anyList(), any(), anyMap()))
                .thenReturn(tableNode);

        service.promoteTableNodes(List.of(tableDoc), "task-1");

        verify(knowledgeGraphService).createTableNode(
                any(), any(), any(), anyInt(), anyInt(), any(),
                argThat(preview -> preview != null && preview.length() <= 504 && preview.endsWith("...")),
                any());
    }

    // --- Helpers ---

    private GraphNode mockGraphNode(String nodeId, String title, NodeLevel level) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getTitle()).thenReturn(title);
        when(node.getNodeType()).thenReturn(level);
        return node;
    }
}
