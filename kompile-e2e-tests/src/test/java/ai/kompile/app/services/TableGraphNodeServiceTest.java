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

package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TableGraphNodeService}.
 * Verifies that table Documents are promoted to GraphNode(TABLE) entries
 * with proper CONTAINS edges and metadata.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TableGraphNodeServiceTest {

    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private GraphNodeRepository graphNodeRepository;

    private TableGraphNodeService service;

    @BeforeEach
    void setUp() {
        service = new TableGraphNodeService(knowledgeGraphService, graphNodeRepository);

        // Mock createTableNode to return a valid node
        when(knowledgeGraphService.createTableNode(
                anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(GraphNode.builder()
                        .nodeId("table-1")
                        .nodeType(NodeLevel.TABLE)
                        .title("TestTable")
                        .build());

        // Default: findByExternalIdAndNodeType returns a DOCUMENT node for common paths
        GraphNode defaultDoc = GraphNode.builder()
                .nodeId("doc-uuid-1")
                .externalId("/data/report.xlsx")
                .nodeType(NodeLevel.DOCUMENT)
                .title("report.xlsx")
                .build();
        when(graphNodeRepository.findByExternalIdAndNodeType(anyString(), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(defaultDoc));
    }

    @Test
    void promotesTableDocumentToGraphNode() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("sheetName", "Revenue");
        meta.put("source_path", "/data/report.xlsx");
        meta.put("source_filename", "report.xlsx");
        meta.put("table_row_count", 50);
        meta.put("table_column_count", 5);
        meta.put("table_headers", "Product,Q1,Q2,Q3,Q4");
        meta.put("full_table_content", "| Product | Q1 |\n|---|---|\n| A | 100 |");

        Document tableDoc = new Document("table summary", meta);
        int created = service.promoteTableNodes(List.of(tableDoc), "task-1");

        assertEquals(1, created);
        verify(knowledgeGraphService).createTableNode(
                anyString(), contains("Revenue"), eq("Revenue"),
                eq(50), eq(5), any(), any(), any());
    }

    @Test
    void skipsNonTableDocuments() {
        Document textDoc = new Document("plain text", Map.of("content_type", "text"));
        Document noType = new Document("no type", new HashMap<>());

        int created = service.promoteTableNodes(List.of(textDoc, noType), "task-1");

        assertEquals(0, created);
        verify(knowledgeGraphService, never()).createTableNode(
                anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void handlesEmptyAndNullInputs() {
        assertEquals(0, service.promoteTableNodes(null, "task-1"));
        assertEquals(0, service.promoteTableNodes(List.of(), "task-1"));
    }

    @Test
    void promotesMultipleTablesFromBatch() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("content_type", "table");
            meta.put("sheetName", "Sheet" + i);
            meta.put("source_path", "/data/report.xlsx");
            meta.put("table_row_count", 10 + i);
            meta.put("table_column_count", 3);
            docs.add(new Document("summary " + i, meta));
        }

        // Also add a non-table doc
        docs.add(new Document("text doc", Map.of("content_type", "text")));

        int created = service.promoteTableNodes(docs, "task-1");

        assertEquals(3, created);
        verify(knowledgeGraphService, times(3)).createTableNode(
                anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void linksToExistingDocumentNodeWhenFound() {
        // Set up a pre-existing DOCUMENT node
        GraphNode existingDoc = GraphNode.builder()
                .nodeId("doc-uuid-specific")
                .externalId("/data/report.xlsx")
                .nodeType(NodeLevel.DOCUMENT)
                .title("report.xlsx")
                .build();
        when(graphNodeRepository.findByExternalIdAndNodeType(eq("/data/report.xlsx"), eq(NodeLevel.DOCUMENT)))
                .thenReturn(Optional.of(existingDoc));

        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("sheetName", "Summary");
        meta.put("source_path", "/data/report.xlsx");
        meta.put("source_filename", "report.xlsx");
        meta.put("table_row_count", 5);
        meta.put("table_column_count", 2);

        service.promoteTableNodes(List.of(new Document("summary", meta)), "task-1");

        // The parent node ID should be the UUID nodeId, not the externalId
        verify(knowledgeGraphService).createTableNode(
                eq("doc-uuid-specific"), anyString(), eq("Summary"),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void includesDqFlagMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("sheetName", "Validation");
        meta.put("source_path", "/data/checks.xlsx");
        meta.put("table_row_count", 20);
        meta.put("table_column_count", 3);
        meta.put("dq_flag_count", 5);
        meta.put("formulaCount", 10);
        meta.put("table_index", 2);

        service.promoteTableNodes(List.of(new Document("summary", meta)), "task-1");

        verify(knowledgeGraphService).createTableNode(
                anyString(), anyString(), eq("Validation"),
                eq(20), eq(3), any(), any(),
                argThat(m -> m != null && m.containsKey("dqFlagCount") && Integer.valueOf(5).equals(m.get("dqFlagCount"))));
    }

    @Test
    void usesStructuralSectionAsFallbackTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        // No sheetName — this is an HTML table
        meta.put("structural_section", "P&L Projections");
        meta.put("source_path", "/data/report.html");
        meta.put("table_row_count", 15);
        meta.put("table_column_count", 4);

        service.promoteTableNodes(List.of(new Document("summary", meta)), "task-1");

        verify(knowledgeGraphService).createTableNode(
                anyString(), anyString(), eq("P&L Projections"),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void continuesOnErrorForIndividualTable() {
        // First table will throw, second should still be promoted
        when(knowledgeGraphService.createTableNode(
                anyString(), contains("Bad"), anyString(),
                anyInt(), anyInt(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        List<Document> docs = new ArrayList<>();

        Map<String, Object> badMeta = new HashMap<>();
        badMeta.put("content_type", "table");
        badMeta.put("sheetName", "Bad");
        badMeta.put("source_path", "/data/report.xlsx");
        badMeta.put("table_row_count", 1);
        badMeta.put("table_column_count", 1);
        docs.add(new Document("bad", badMeta));

        Map<String, Object> goodMeta = new HashMap<>();
        goodMeta.put("content_type", "table");
        goodMeta.put("sheetName", "Good");
        goodMeta.put("source_path", "/data/report.xlsx");
        goodMeta.put("table_row_count", 1);
        goodMeta.put("table_column_count", 1);
        docs.add(new Document("good", goodMeta));

        int created = service.promoteTableNodes(docs, "task-1");

        assertEquals(1, created);
        verify(knowledgeGraphService, times(2)).createTableNode(
                anyString(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any(), any());
    }
}
